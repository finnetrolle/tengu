package ru.finnetrolle.tengu.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggingProcessTest {
    @Test
    fun defaultsAndEveryValidEnvironmentCombination() {
        val settings = listOf(emptyMap<String, String>()) + listOf("DEBUG", "INFO", "WARN", "ERROR").flatMap { level ->
            listOf("0", "1").map { body -> mapOf("TENGU_LOG_LEVEL" to level, "TENGU_LOG_RESPONSE_BODY" to body) }
        }
        for (env in settings) LoggingProcess(env).use { child ->
            child.awaitReady()
            val health = child.request("/v1/health")
            val invoke = child.request("/v1/invoke", """{"tool":"status","commandPath":["status"]}""")
            val malformed = child.request("/v1/invoke", "{request-secret-marker")
            assertEquals(200, health.statusCode())
            assertEquals(200, invoke.statusCode())
            assertEquals(400, malformed.statusCode())
            child.stop()
            val level = env["TENGU_LOG_LEVEL"] ?: "INFO"
            val records = child.records()
            fun logged(response: HttpResponse<String>) = records.singleOrNull {
                it.text("request_id") == response.headers().firstValue("X-Request-ID").orElseThrow()
            }
            assertEquals(level == "DEBUG", logged(health) != null)
            assertEquals(level in listOf("DEBUG", "INFO"), logged(invoke) != null)
            assertEquals(level != "ERROR", logged(malformed) != null)
            logged(invoke)?.let {
                assertEquals(env["TENGU_LOG_RESPONSE_BODY"] == "1", "response_body" in it)
                if (env["TENGU_LOG_RESPONSE_BODY"] == "1") assertEquals(invoke.body(), it.text("response_body"))
            }
            assertFalse("request-secret-marker" in child.stdout())
            assertEquals("", child.stderr())
            if (level in listOf("DEBUG", "INFO")) {
                assertEquals(listOf("server_starting", "server_ready", "server_stopping", "server_stopped"),
                    records.mapNotNull { it.text("event") }.filter { it.startsWith("server_") })
                assertEquals("server_stopped", records.last().text("event"))
                assertEquals(1, records.count { it.text("event") == "dev_secrets_enabled" })
            }
        }
    }

    @Test
    fun invalidEnvironmentStopsBeforeAnyResourceConstructionOrListener() {
        val cases = listOf("", "debug", "VERBOSE", " INFO ", "invalid-level-sentinel").map {
            Triple(mapOf("TENGU_LOG_LEVEL" to it), "TENGU_LOG_LEVEL", listOf("DEBUG", "INFO", "WARN", "ERROR"))
        } + listOf("", "true", "2", " 1 ", "invalid-body-sentinel").map {
            Triple(mapOf("TENGU_LOG_RESPONSE_BODY" to it), "TENGU_LOG_RESPONSE_BODY", listOf("0", "1"))
        } + Triple(mapOf("TENGU_LOG_LEVEL" to "both-invalid-level", "TENGU_LOG_RESPONSE_BODY" to "both-invalid-body"),
            "TENGU_LOG_LEVEL", listOf("DEBUG", "INFO", "WARN", "ERROR"))
        for ((env, key, allowed) in cases) {
            // Missing Vault config would fail service construction if logging validation ran too late.
            LoggingProcess(env + ("TENGU_DEV_SECRETS" to "0")).use { child ->
                child.awaitInvalidExitWithoutListener()
                assertEquals(1, child.exitCode())
                assertEquals("", child.stderr())
                val event = child.records().single()
                assertEquals("server_configuration_error", event.text("event"))
                assertEquals("ERROR", event.text("level"))
                assertEquals(key, event.text("config_key"))
                val expectedValues = kotlinx.serialization.json.JsonArray(
                    allowed.map { kotlinx.serialization.json.JsonPrimitive(it) },
                )
                assertEquals(expectedValues, event["allowed_values"])
                assertEquals(setOf("timestamp", "level", "logger", "message", "service_name", "service_version",
                    "event", "config_key", "allowed_values"), event.keys)
                assertFalse("sentinel" in child.stdout())
                assertFalse("both-invalid" in child.stdout())
            }
        }
    }

    @Test
    fun listenerFailureIsJsonOnlyAndClosesThePartiallyStartedEngine() = ServerSocket(0).use { occupied ->
        LoggingProcess(mapOf("TENGU_PORT" to occupied.localPort.toString())).use { child ->
            child.awaitExit()
            assertEquals(1, child.exitCode())
            assertEquals("", child.stderr())
            val records = child.records()
            assertEquals(listOf("server_starting", "server_start_failed", "server_stopping", "server_stopped"),
                records.mapNotNull { it.text("event") }.filter { it.startsWith("server_") })
            assertFalse("Address already in use" in child.stdout())
            assertTrue(records.single { it.text("event") == "server_start_failed" }.containsKey("exception_type"))
        }
    }

    @Test
    fun serviceConstructionFailureClosesResourcesAndProducesSafeJson() =
        LoggingProcess(mapOf("TENGU_DEV_SECRETS" to "0")).use { child ->
            child.awaitExit()
            assertEquals(1, child.exitCode())
            assertEquals("", child.stderr())
            val records = child.records()
            assertEquals(listOf("server_starting", "server_start_failed", "server_stopping", "server_stopped"),
                records.map { it.text("event") })
            assertEquals("ERROR", records[1].text("level"))
            assertEquals("java.lang.IllegalStateException", records[1].text("exception_type"))
            assertTrue("MainKt" in records[1].text("exception_stacktrace")!!)
            assertFalse("TENGU_VAULT_ADDR is not set" in child.stdout())
        }
}

/** Launch the installed distribution directly, with independent streams and child-only environment. */
private class LoggingProcess(env: Map<String, String>) : AutoCloseable {
    private val directory = Files.createTempDirectory("tengu-logging-process-")
    private val out = directory.resolve("stdout.jsonl")
    private val err = directory.resolve("stderr.txt")
    private val port = env["TENGU_PORT"]?.toInt() ?: ServerSocket(0).use { it.localPort }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
    private val process: Process

    init {
        val distribution = System.getProperty("tengu.test.distribution")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val builder = ProcessBuilder(java, "-cp", "$distribution/lib/*", "ru.finnetrolle.tengu.server.MainKt")
            .redirectOutput(out.toFile()).redirectError(err.toFile())
        builder.environment().keys.removeIf {
            it.startsWith("TENGU_") || it in listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
        }
        builder.environment().putAll(mapOf("TENGU_PORT" to port.toString(), "TENGU_DEV_SECRETS" to "1",
            "TENGU_DEV_SECRETS_DIR" to directory.resolve("secrets").toString(),
            "TENGU_HUB_TOKENS" to "alice=token-a,bob=token-b"))
        builder.environment().putAll(env)
        process = builder.start()
    }

    fun request(path: String, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer token-a")
        if (body != null) builder.POST(HttpRequest.BodyPublishers.ofString(body))
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    fun awaitReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (process.isAlive && System.nanoTime() < deadline) {
            if (runCatching { request("/v1/health").statusCode() == 200 }.getOrDefault(false)) return
            process.waitFor(20, TimeUnit.MILLISECONDS)
        }
        error("Server did not become ready: ${stdout()} ${stderr()}")
    }

    fun awaitInvalidExitWithoutListener() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var probes = 0
        do {
            val connected = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 20) }
            }.isSuccess
            assertFalse(connected, "Invalid logging config opened a listener")
            probes++
        } while (!process.waitFor(5, TimeUnit.MILLISECONDS) && System.nanoTime() < deadline)
        assertTrue(probes > 1, "Observe the whole child lifetime, not one failed TCP probe")
        awaitExit()
    }

    fun awaitExit() { assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Child did not exit: ${stdout()} ${stderr()}") }
    fun stop() { process.destroy(); awaitExit(); assertTrue(exitCode() in listOf(0, 143), "exit ${exitCode()}") }
    fun exitCode(): Int = process.exitValue()
    fun stdout(): String = Files.readString(out)
    fun stderr(): String = Files.readString(err)
    fun records(): List<JsonObject> {
        val output = stdout()
        if (output.isEmpty()) return emptyList()
        assertTrue(output.endsWith("\n"))
        return output.dropLast(1).split('\n').map { Json.parseToJsonElement(it).jsonObject }
    }

    override fun close() {
        try {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            http.close()
            directory.toFile().deleteRecursively()
        }
    }
}
