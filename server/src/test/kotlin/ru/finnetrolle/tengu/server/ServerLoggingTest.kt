package ru.finnetrolle.tengu.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import ru.finnetrolle.tengu.protocol.ErrorKind
import ru.finnetrolle.tengu.server.logging.installRequestLogging
import ru.finnetrolle.tengu.toolkit.AxiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerLoggingTest {
    @Test
    fun engineErrorsPreserveHttpStatusAndBodyWithoutLeakingIntoLogs() {
        val cases = listOf(
            Triple(BadRequestException("bad-request-marker"), 400, "bad-request-marker"),
            Triple(NotFoundException("missing-entity-marker"), 404, "missing-entity-marker"),
            Triple(IllegalStateException("internal-message-marker"), 500, "internal-message-marker"),
            Triple(IllegalStateException(), 500, ""),
        )
        for ((cause, status, body) in cases) LogCapture().use { logs ->
            testApplication {
                environment { log = logs.context.getLogger("io.ktor.test") }
                application {
                    installRequestLogging(logger = logs.logger)
                    routing { get("/failure-uri-marker") { throw cause } }
                }
                val response = client.get("/failure-uri-marker")
                assertEquals(status, response.status.value)
                assertEquals(body, response.bodyAsText())
                logs.barrier()
                assertRequest(logs.request(response), status, if (status < 500) "WARN" else "ERROR", "error")
                assertEquals(1, logs.requests().size)
                val output = logs.bytes().toString(Charsets.UTF_8)
                listOf("bad-request-marker", "missing-entity-marker", "internal-message-marker", "failure-uri-marker")
                    .forEach { assertFalse(it in output, it) }
            }
        }
    }

    @Test
    fun httpMatrixAndDescriptorMetadata() = LogCapture().use { logs ->
        testApplication {
            loggingApplication(logs)
            val health = client.get("/v1/health") { header("Authorization", "Bearer token-a") }
            val manifest = client.get("/v1/manifest") { header("Authorization", "Bearer token-a") }
            val status = invoke("""{"tool":"status","commandPath":["status"]}""")
            val missing = client.get("/v1/manifest")
            val bad = invoke(token = "invalid-token-marker")
            val malformed = invoke("{parse-input-marker")
            val tool = invoke("""{"tool":"unknown-tool-marker","commandPath":["run"]}""")
            val command = invoke("""{"tool":"fixture","commandPath":["unknown-command-marker"]}""")
            val flags = invoke("""{"tool":"fixture","commandPath":["run"],"flags":{"unknown":"flag-marker"}}""")
            val stale = invoke(version = "3")
            val unmatched = client.get("/unknown-uri-marker?secret=query-marker")
            logs.barrier()
            assertEquals(11, logs.requests().size)
            assertRequest(logs.request(health), 200, "DEBUG", "ok")
            assertRequest(logs.request(manifest), 200, "INFO", "ok")
            assertRequest(logs.request(status), 200, "INFO", "ok")
            listOf(missing, bad).forEach { assertRequest(logs.request(it), 401, "WARN", "error", "AUTH") }
            listOf(malformed, tool, command, flags).forEach {
                assertRequest(logs.request(it), 400, "WARN", "error", "USAGE")
                assertEquals("alice", logs.request(it).text("user_id"))
            }
            assertRequest(logs.request(stale), 409, "WARN", "error", "STALE_MANIFEST")
            assertEquals("alice", logs.request(stale).text("user_id"))
            assertRequest(logs.request(unmatched), 404, "WARN", "error")
            assertEquals("unmatched", logs.request(unmatched).text("http_route"))
            listOf(health, missing, bad, unmatched).forEach { assertFalse("user_id" in logs.request(it)) }
            assertFalse("tool" in logs.request(tool))
            assertEquals("fixture", logs.request(command).text("tool"))
            assertFalse("command" in logs.request(command))
            assertEquals(JsonArray(listOf(JsonPrimitive("run"))), logs.request(flags)["command"])
            assertEquals(JsonArray(listOf(JsonPrimitive("status"))), logs.request(status)["command"])
            val bytes = logs.bytes().toString(Charsets.UTF_8)
            listOf("parse-input-marker", "unknown-tool-marker", "unknown-command-marker", "flag-marker",
                "invalid-token-marker", "unknown-uri-marker", "query-marker", "forged-user-marker",
                "client-request-id-marker", "response_body").forEach { assertFalse(it in bytes, it) }
            assertEquals(12, logs.records().size) // Requests plus the test FIFO barrier; no duplicate route errors.
        }
    }

    @Test
    fun pluginResultMatrix() {
        val cases = listOf(
            Triple(AxiResult.Noop("unchanged"), 200, "noop"),
            Triple(AxiResult.err(ErrorKind.NOT_FOUND, "missing"), 404, "error"),
            Triple(AxiResult.err(ErrorKind.UPSTREAM, "upstream"), 502, "error"),
        )
        for ((result, status, outcome) in cases) LogCapture().use { logs ->
            testApplication {
                loggingApplication(logs, plugin = LoggingPlugin { _, _ -> result })
                val response = invoke()
                logs.barrier()
                assertEquals(status, response.status.value)
                val level = when (status) { 200 -> "INFO"; 404 -> "WARN"; else -> "ERROR" }
                val kind = when (status) { 404 -> "NOT_FOUND"; 502 -> "UPSTREAM"; else -> null }
                assertRequest(logs.request(response), status, level, outcome, kind)
                assertEquals("alice", logs.request(response).text("user_id"))
            }
        }
    }

    @Test
    fun overlappingCallsKeepTheirOwnIdentityInReverseCompletionOrder() {
        repeat(2) {
            LogCapture().use { logs -> testApplication {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                loggingApplication(logs, plugin = LoggingPlugin { _, ctx ->
                    if (ctx.userId == "alice") { entered.complete(Unit); release.await() }
                    AxiResult.ok("user" to ctx.userId)
                })
                withTimeout(5_000) { coroutineScope {
                    val first = async { invoke() }
                    entered.await()
                    val second = invoke(token = "token-b")
                    logs.barrier()
                    assertEquals(listOf("bob"), logs.requests().map { it.text("user_id") })
                    release.complete(Unit)
                    val alice = first.await()
                    val invalid = invoke(token = "invalid")
                    val health = client.get("/v1/health") { header("Authorization", "Bearer token-a") }
                    logs.barrier()
                    assertEquals("alice", logs.request(alice).text("user_id"))
                    assertEquals("bob", logs.request(second).text("user_id"))
                    assertFalse("user_id" in logs.request(invalid))
                    assertFalse("user_id" in logs.request(health))
                    val ids = listOf(alice, second, invalid, health).map { it.headers["X-Request-ID"]!! }
                    assertEquals(4, ids.toSet().size)
                    ids.forEach { id ->
                        val uuidV4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                        assertTrue(uuidV4.matches(id))
                        assertNotEquals("client-request-id-marker", id)
                    }
                    assertEquals(listOf("bob", "alice", null, null), logs.requests().map { it.text("user_id") })
                } }
            } }
        }
    }

    @Test
    fun unhandledApplicationFailureHasOneSafeCompletion() = LogCapture().use { logs ->
        testApplication {
            environment { log = logs.context.getLogger("io.ktor.test") }
            application {
                installRequestLogging(logger = logs.logger)
                routing { get("/unhandled-uri-marker") { error("unhandled-message-marker") } }
            }
            val response = client.get("/unhandled-uri-marker")
            logs.barrier()
            assertEquals(500, response.status.value)
            assertRequest(logs.request(response), 500, "ERROR", "error")
            assertTrue("ServerLoggingTest" in logs.request(response).text("exception_stacktrace")!!)
            val output = logs.bytes().toString(Charsets.UTF_8)
            assertFalse("unhandled-uri-marker" in output)
            assertFalse("unhandled-message-marker" in output)
            assertEquals(2, logs.records().size)
        }
    }

    @Test
    fun cancellationIsObservedWithoutInventingAnHttpResponse() = LogCapture().use { logs ->
        testApplication {
            val cancellation = CancellationException("cancel-secret-marker")
            val propagated = CompletableDeferred<CancellationException>()
            application {
                intercept(ApplicationCallPipeline.Setup) {
                    try {
                        proceed()
                    } catch (cause: CancellationException) {
                        propagated.complete(cause)
                        throw cause
                    }
                }
                installRequestLogging(logger = logs.logger)
                routing { get("/cancel") { throw cancellation } }
            }
            runCatching { client.get("/cancel") }
            assertEquals(cancellation, withTimeout(5_000) { propagated.await() })
            logs.barrier()
            val event = logs.requests().single()
            assertEquals("cancelled", event.text("outcome"))
            assertEquals("WARN", event.text("level"))
            assertFalse("http_status" in event)
            assertFalse("cancel-secret-marker" in logs.bytes().toString(Charsets.UTF_8))
        }
    }
}
