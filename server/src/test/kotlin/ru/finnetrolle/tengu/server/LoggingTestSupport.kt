package ru.finnetrolle.tengu.server

import ch.qos.logback.classic.AsyncAppender
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.OutputStreamAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.finnetrolle.tengu.protocol.CommandDescriptor
import ru.finnetrolle.tengu.protocol.FlagDescriptor
import ru.finnetrolle.tengu.protocol.ToolDescriptor
import ru.finnetrolle.tengu.server.logging.LoggingConfig
import ru.finnetrolle.tengu.server.tools.StatusTool
import ru.finnetrolle.tengu.toolkit.AxiResult
import ru.finnetrolle.tengu.toolkit.FakeSecretScope
import ru.finnetrolle.tengu.toolkit.InvocationContext
import ru.finnetrolle.tengu.toolkit.ToolPlugin
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Actual production XML, encoder and queue; only the final byte sink is replaced. */
internal class LogCapture(level: Level = Level.DEBUG) : AutoCloseable {
    val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
    private val sink = EncodedOutput()
    val logger get() = context.getLogger("ru.finnetrolle.tengu.server.logging.RequestLogging")

    init {
        LoggingConfig.bootstrap(context)
        LoggingConfig(level).applyTo(context)
        val async = context.getLogger("ROOT").getAppender("ASYNC") as AsyncAppender
        val console = async.getAppender("STDOUT") as OutputStreamAppender<ILoggingEvent>
        console.outputStream = sink
    }

    suspend fun barrier() {
        logger.atError().addKeyValue("event", "test_barrier").log("Test barrier")
        withTimeout(5_000) {
            while (sink.events.receive().text("event") != "test_barrier") { /* Drain through FIFO marker. */ }
        }
    }

    fun records(): List<JsonObject> = sink.toString(Charsets.UTF_8).lineSequence()
        .filter { it.isNotEmpty() }.map { Json.parseToJsonElement(it).jsonObject }.toList()

    fun bytes(): ByteArray = sink.toByteArray()
    fun requests(): List<JsonObject> = records().filter { it.text("event") == "http_request_completed" }
    fun request(response: HttpResponse): JsonObject = requests().single {
        it.text("request_id") == response.headers["X-Request-ID"]
    }.also { event ->
        assertEquals(response.call.request.method.value, event.text("http_method"))
        val path = response.call.request.url.encodedPath
        val expectedRoute = when (path) {
            "/v1/health" -> "/v1/health"
            "/v1/manifest" -> "/v1/manifest"
            "/v1/invoke" -> "/v1/invoke"
            else -> "unmatched"
        }
        assertEquals(expectedRoute, event.text("http_route"))
    }

    override fun close() { context.stop() }
}

private class EncodedOutput : ByteArrayOutputStream() {
    val events = Channel<JsonObject>(Channel.UNLIMITED)
    private var consumed = 0

    @Synchronized
    override fun flush() {
        val data = toByteArray()
        for (index in consumed until data.size) {
            if (data[index] == '\n'.code.toByte()) {
                val line = String(data, consumed, index - consumed, Charsets.UTF_8)
                events.trySend(Json.parseToJsonElement(line).jsonObject).getOrThrow()
                consumed = index + 1
            }
        }
    }
}

internal fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

internal class LoggingPlugin(
    private val behavior: suspend (List<String>, InvocationContext) -> AxiResult = { _, _ ->
        AxiResult.ok("text" to "business-marker\n\r\"Привет 😀 token=unredacted", hints = listOf("next"))
    },
) : ToolPlugin {
    override val descriptor = ToolDescriptor("fixture", "Logging fixture", commands = listOf(
        CommandDescriptor(listOf("run"), "Run", flags = listOf(FlagDescriptor("text"))),
        CommandDescriptor(listOf("auth", "status"), "Auth"),
        CommandDescriptor(listOf("secret"), "Secret", flags = listOf(FlagDescriptor("token", secret = true))),
    ))

    override suspend fun invoke(commandPath: List<String>, ctx: InvocationContext): AxiResult =
        behavior(commandPath, ctx)
}

internal fun ApplicationTestBuilder.loggingApplication(
    capture: LogCapture,
    body: Boolean = false,
    plugin: ToolPlugin = LoggingPlugin(),
    secretsScopeFor: (String, String) -> ru.finnetrolle.tengu.toolkit.SecretScope = { _, _ -> FakeSecretScope() },
) {
    application {
        val registry = PluginRegistry().apply { register(plugin) }
        registry.register(StatusTool(ServerInfo.VERSION, Instant.EPOCH, 4, { registry.tools.size }, { emptyList() }))
        val http = HttpClient(MockEngine { respond("unused") })
        monitor.subscribe(io.ktor.server.application.ApplicationStopped) { http.close() }
        tenguModule(
            ServerDeps(registry, StaticTokenHubAuth(mapOf("token-a" to "alice", "token-b" to "bob")),
                http, Clock.systemUTC(), Instant.EPOCH, ServerInfo.VERSION, 4, secretsScopeFor),
            LoggingConfig(responseBody = body), capture.logger,
        )
    }
}

internal suspend fun ApplicationTestBuilder.invoke(
    body: String = """{"tool":"fixture","commandPath":["run"]}""",
    token: String = "token-a",
    version: String = "4",
): HttpResponse = client.post("/v1/invoke?query-marker=hidden") {
    header("Authorization", "Bearer $token")
    header("X-Request-ID", "client-request-id-marker")
    header("X-Tengu-Manifest-Version", version)
    header("X-User-ID", "forged-user-marker")
    setBody(body)
}

internal fun assertRequest(record: JsonObject, status: Int, level: String, outcome: String, kind: String? = null) {
    assertEquals(status.toString(), record.text("http_status"))
    assertEquals(level, record.text("level"))
    assertEquals(outcome, record.text("outcome"))
    assertEquals(kind, record.text("error_kind"))
    assertNotNull(record.text("duration_ms")?.toLongOrNull()?.takeIf { it >= 0 })
}
