package ru.finnetrolle.tengu.server

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Level
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import ru.finnetrolle.tengu.toolkit.AxiResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogEncodingTest {
    @Test
    fun productionEncoderPreservesTypesEscapesAndOnlyAllowlistedFields() = LogCapture().use { logs -> runBlocking {
        val async = logs.context.getLogger("ROOT").getAppender("ASYNC") as AsyncAppender
        assertEquals(256, async.queueSize)
        assertEquals(0, async.discardingThreshold)
        assertFalse(async.isNeverBlock)
        assertEquals(1_000, async.maxFlushTime)
        assertTrue(logs.context.statusManager.copyOfStatusList.none {
            it.level >= ch.qos.logback.core.status.Status.WARN
        })
        logs.context.putProperty("private-context-marker", "hidden")
        logs.context.mdcAdapter.put("private-mdc-marker", "hidden")
        val before = Instant.now()
        try {
            logs.logger.atInfo().addKeyValue("event", "encoding_fixture")
                .addKeyValue("command", listOf("issues", "list"))
                .addKeyValue("duration_ms", 17L)
                .addKeyValue("response_body_truncated", true)
                .addKeyValue("private-kv-marker", "hidden")
                .setCause(IllegalStateException("throwable-message-marker"))
                .log("line\ncarriage\rquote\"Кириллица 😀")
        } finally { logs.context.mdcAdapter.remove("private-mdc-marker") }
        val after = Instant.now()
        logs.barrier()
        val record = logs.records().first()
        assertEquals("tengu-server", record.text("service_name"))
        assertEquals(ServerInfo.VERSION, record.text("service_version"))
        assertEquals("ru.finnetrolle.tengu.server.logging.RequestLogging", record.text("logger"))
        assertEquals("line\ncarriage\rquote\"Кириллица 😀", record.text("message"))
        assertEquals(JsonPrimitive(17), record["duration_ms"])
        assertEquals(JsonPrimitive(true), record["response_body_truncated"])
        assertEquals(JsonArray(listOf(JsonPrimitive("issues"), JsonPrimitive("list"))), record["command"])
        val timestamp = Instant.parse(record.text("timestamp"))
        assertTrue(!timestamp.isBefore(before.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)))
        assertTrue(!timestamp.isAfter(after))
        val output = logs.bytes().toString(Charsets.UTF_8)
        assertTrue(record.text("timestamp")!!.endsWith("Z"))
        assertFalse('\u001b' in output)
        assertFalse('\r' in output)
        assertEquals(2, output.count { it == '\n' })
        listOf("private-context-marker", "private-mdc-marker", "private-kv-marker", "throwable-message-marker",
            "stack_trace", "trace_id", "span_id").forEach { assertFalse(it in output, it) }
    } }

    @Test
    fun thresholdsAndBodySwitchAreIndependent() {
        val expected = mapOf(
            Level.DEBUG to listOf("DEBUG", "INFO", "WARN", "ERROR"),
            Level.INFO to listOf("INFO", "WARN", "ERROR"),
            Level.WARN to listOf("WARN", "ERROR"),
            Level.ERROR to listOf("ERROR"),
        )
        for ((level, levels) in expected) for (body in listOf(false, true)) LogCapture(level).use { logs ->
            testApplication {
                loggingApplication(logs, body, LoggingPlugin { _, ctx ->
                    if (ctx.flags["text"] == "throw") error("hidden-exception") else AxiResult.ok("value" to "ok")
                })
                client.get("/v1/health")
                invoke()
                invoke("bad-json")
                invoke("""{"tool":"fixture","commandPath":["run"],"flags":{"text":"throw"}}""")
                logs.context.getLogger("library.fixture").info("library-info-hidden")
                logs.context.getLogger("library.fixture").warn("library-warn-visible")
                logs.barrier()
                assertEquals(levels, logs.requests().map { it.text("level") })
                for (record in logs.requests()) {
                    val allowed = body && record.text("level") in listOf("INFO", "ERROR")
                    assertEquals(allowed, "response_body" in record, "threshold=$level body=$body event=$record")
                }
                val library = logs.records().filter { it.text("logger") == "library.fixture" }
                assertEquals(listOf("library-warn-visible"), library.map { it.text("message") })
                assertFalse("event" in library.single())
            }
        }
    }
}
