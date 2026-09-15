package ru.finnetrolle.tengu.server

import ch.qos.logback.classic.Level
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import ru.finnetrolle.tengu.protocol.ErrorKind
import ru.finnetrolle.tengu.toolkit.AxiResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogBodyTest {
    private val unicodeEvidence: Path
        get() = Path.of(System.getProperty("tengu.test.fixtures"), "body-boundaries.jsonl")

    @Test
    fun unhandledFailureAfterValidationNeverCapturesItsExceptionMessage() {
        for (body in listOf(false, true)) LogCapture().use { logs -> testApplication {
            loggingApplication(logs, body, secretsScopeFor = { _, _ -> error("scope-exception-marker") })
            val response = invoke()
            assertEquals(500, response.status.value)
            assertEquals("scope-exception-marker", response.bodyAsText())
            logs.barrier()
            val event = logs.request(response)
            assertRequest(event, 500, "ERROR", "error")
            assertEquals("alice", event.text("user_id"))
            assertNoBody(event)
            assertEquals("java.lang.IllegalStateException", event.text("exception_type"))
            assertFalse("scope-exception-marker" in logs.bytes().toString(Charsets.UTF_8))
        } }
    }

    @Test
    fun exactFinalBodyForOkNoopAndErrorIncludingBusinessText() {
        val results = listOf(
            AxiResult.ok("text" to "token=business-secret\n\r\"Кириллица 😀", hints = listOf("next")),
            AxiResult.Noop("unchanged", listOf("help")),
            AxiResult.err(ErrorKind.UPSTREAM, "failed", listOf("retry")),
        )
        for (result in results) LogCapture().use { logs -> testApplication {
            loggingApplication(logs, body = true, plugin = LoggingPlugin { _, _ -> result })
            val response = invoke()
            val body = response.bodyAsText()
            logs.barrier()
            val record = logs.request(response)
            assertEquals(body, record.text("response_body"))
            assertEquals(body.toByteArray(Charsets.UTF_8).size.toString(), record.text("response_body_bytes"))
            assertEquals("false", record.text("response_body_truncated"))
            if (result is AxiResult.Ok) assertTrue("token=business-secret" in record.text("response_body")!!)
            assertEquals(2, logs.bytes().count { it == '\n'.code.toByte() }) // One event plus barrier.
        } }
    }

    @Test
    fun bodiesAreAbsentWhenDisabledEvenAtDebug() {
        for (level in listOf(Level.DEBUG, Level.INFO)) LogCapture(level).use { logs -> testApplication {
            loggingApplication(logs)
            invoke()
            logs.barrier()
            assertNoBody(logs.requests().single())
            assertFalse("business-marker" in logs.bytes().toString(Charsets.UTF_8))
        } }
    }

    @Test
    fun structuralExclusionsApplyToSuccessAndError() {
        for (result in listOf(AxiResult.ok("value" to "secret-output-marker"),
            AxiResult.err(ErrorKind.UPSTREAM, "secret-output-marker"))) {
            LogCapture().use { logs -> testApplication {
                loggingApplication(logs, body = true, plugin = LoggingPlugin { _, _ -> result })
                invoke("""{"tool":"fixture","commandPath":["auth","status"]}""")
                invoke("""{"tool":"fixture","commandPath":["secret"]}""")
                invoke("""{"tool":"fixture","commandPath":["secret"],"flags":{"token":"secret-input-marker"}}""")
                invoke("""{"tool":"fixture","commandPath":["run"],"flags":{"bogus":"prevalidation-marker"}}""")
                invoke("bad-json-marker")
                invoke(token = "invalid-token-marker")
                invoke(version = "3")
                client.get("/v1/health")
                client.get("/v1/manifest") { header("Authorization", "Bearer token-a") }
                logs.barrier()
                assertEquals(9, logs.requests().size)
                logs.requests().forEach(::assertNoBody)
                val output = logs.bytes().toString(Charsets.UTF_8)
                listOf("secret-output-marker", "secret-input-marker", "prevalidation-marker", "bad-json-marker",
                    "invalid-token-marker").forEach { assertFalse(it in output, it) }
            } }
        }
    }

    @Test
    fun fullHttpBodiesAtUtf8LimitAndAcrossMultibyteBoundary() {
        Files.createDirectories(unicodeEvidence.parent)
        Files.writeString(unicodeEvidence, "")
        // Independent wire fixture: encodeDefaults=false gives this literal envelope around a string payload.
        val prefix = """{"payload":"""" // Includes the payload's opening quote.
        val suffix = "\"}"
        val overhead = (prefix + suffix).toByteArray(Charsets.UTF_8).size
        for (size in listOf(16_383, 16_384, 16_385)) {
            val payload = "a".repeat(size - overhead)
            assertBodyBoundary(payload, prefix + payload + suffix, size, (prefix + payload + suffix).take(16_384))
        }
        // Emoji begins two bytes before the cutoff and must be omitted in its entirety.
        val payload = "a".repeat(16_382 - prefix.length) + "😀" + "tail"
        val expected = prefix + payload + suffix
        assertBodyBoundary(
            payload, expected, expected.toByteArray(Charsets.UTF_8).size, prefix + "a".repeat(16_382 - prefix.length),
        )
    }

    private fun assertBodyBoundary(payload: String, expected: String, size: Int, truncated: String) =
        LogCapture().use { logs -> testApplication {
            loggingApplication(logs, body = true, plugin = LoggingPlugin { _, _ ->
                AxiResult.Ok(JsonPrimitive(payload))
            })
            val response = invoke()
            assertEquals(expected, response.bodyAsText())
            assertEquals(size, response.bodyAsText().toByteArray(Charsets.UTF_8).size)
            logs.barrier()
            val event = logs.request(response)
            assertEquals(size.toString(), event.text("response_body_bytes"))
            assertEquals((size > 16_384).toString(), event.text("response_body_truncated"))
            assertEquals(if (size <= 16_384) expected else truncated, event.text("response_body"))
            Files.writeString(unicodeEvidence, buildJsonObject {
                put("http_body", response.bodyAsText())
                put("encoded_event", logs.bytes().toString(Charsets.UTF_8).lineSequence().first {
                    it.contains(response.headers["X-Request-ID"]!!)
                })
            }.toString() + "\n", StandardOpenOption.APPEND)
            assertFalse('\uFFFD' in event.text("response_body")!!)
            assertTrue(event.text("response_body")!!.toByteArray(Charsets.UTF_8).size <= 16_384)
        } }

    @Test
    fun exceptionMessagesNeverReachLogsRegardlessOfBodySetting() {
        for (body in listOf(false, true)) LogCapture().use { logs -> testApplication {
            loggingApplication(logs, body, LoggingPlugin { _, _ ->
                throw IllegalStateException(
                    "exception-message-marker", IllegalArgumentException("cause-message-marker"),
                )
                    .apply { addSuppressed(IllegalStateException("suppressed-message-marker")) }
            })
            val response = invoke()
            logs.barrier()
            val event = logs.request(response)
            assertRequest(event, 500, "ERROR", "error", "INTERNAL")
            assertEquals("alice", event.text("user_id"))
            assertEquals("java.lang.IllegalStateException", event.text("exception_type"))
            assertTrue("LogBodyTest" in event.text("exception_stacktrace")!!)
            val output = logs.bytes().toString(Charsets.UTF_8)
            listOf("exception-message-marker", "cause-message-marker", "suppressed-message-marker", "stack_trace")
                .forEach { assertFalse(it in output, it) }
            if (body) assertEquals(response.bodyAsText(), event.text("response_body")) else assertNoBody(event)
        } }
    }

    private fun assertNoBody(event: kotlinx.serialization.json.JsonObject) {
        listOf("response_body", "response_body_bytes", "response_body_truncated").forEach { assertFalse(it in event) }
    }
}
