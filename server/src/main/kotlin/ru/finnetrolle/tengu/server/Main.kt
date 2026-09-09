package ru.finnetrolle.tengu.server

import ru.finnetrolle.tengu.jira.JiraPlugin
import ru.finnetrolle.tengu.server.secrets.FileSecretsStore
import ru.finnetrolle.tengu.server.secrets.SecretsStore
import ru.finnetrolle.tengu.server.secrets.VaultSecretsStore
import ru.finnetrolle.tengu.server.tools.StatusTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import ch.qos.logback.classic.LoggerContext
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import ru.finnetrolle.tengu.server.logging.LoggingConfig
import ru.finnetrolle.tengu.server.logging.LoggingConfigurationException
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

private const val DEFAULT_PORT = 8080

fun main() {
    System.setProperty("tengu.service.version", ServerInfo.VERSION)
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    LoggingConfig.bootstrap(context)
    val log = context.getLogger("ru.finnetrolle.tengu.server.Main")
    val config = try {
        LoggingConfig.fromEnv(System.getenv())
    } catch (invalid: LoggingConfigurationException) {
        log.atError().addKeyValue("event", "server_configuration_error")
            .addKeyValue("config_key", invalid.key)
            .addKeyValue("allowed_values", invalid.allowedValues.toList())
            .log("Invalid logging configuration")
        context.stop()
        exitProcess(1)
    }
    config.applyTo(context)
    // One owner coordinates the engine, client and Logback; no independent Ktor shutdown hook.
    System.setProperty("io.ktor.server.engine.ShutdownHook", "false")
    val runtime = ServerRuntime(context, config)
    Runtime.getRuntime().addShutdownHook(Thread({ runtime.stop() }, "tengu-shutdown"))
    if (!runtime.start()) exitProcess(1)
    runtime.awaitStop()
}

/** The same lock serializes startup, startup failure and SIGTERM, including partial construction. */
private class ServerRuntime(private val context: LoggerContext, private val config: LoggingConfig) {
    private val log = context.getLogger("ru.finnetrolle.tengu.server.Main")
    private val stopped = CountDownLatch(1)
    private val engineFailure = AtomicReference<Throwable?>()
    private var closed = false
    private var client: HttpClient? = null
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Synchronized
    fun start(): Boolean {
        if (closed) return false
        return runCatching {
            log.atInfo().addKeyValue("event", "server_starting").log("Server starting")
            val httpClient = HttpClient(ClientCIO).also { client = it }
            val deps = serverDeps(httpClient)
            val rootConfig = serverConfig {
                // CIO also reports failed startup from a root coroutine. Main owns the failure;
                // prevent the coroutine default handler from dumping its raw Throwable to stderr.
                parentCoroutineContext = CoroutineExceptionHandler { _, cause ->
                    engineFailure.compareAndSet(null, cause)
                    stopped.countDown()
                }
                module { tenguModule(deps, config) }
            }
            engine = embeddedServer(CIO, rootConfig) {
                connector { port = System.getenv("TENGU_PORT")?.toIntOrNull() ?: DEFAULT_PORT }
            }
            engine!!.start(wait = false)
            log.atInfo().addKeyValue("event", "server_ready").log("Server ready")
            true
        }.getOrElse { cause ->
            val event = log.atError().addKeyValue("event", "server_start_failed")
                .addKeyValue("exception_type", cause.javaClass.name)
            cause.stackTrace.takeIf { it.isNotEmpty() }?.let { frames ->
                event.addKeyValue("exception_stacktrace", frames.joinToString("\n") { it.toString() })
            }
            event.log("Server failed to start")
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        if (closed) return
        closed = true
        log.atInfo().addKeyValue("event", "server_stopping").log("Server stopping")
        // Attempt every cleanup even if a previous resource failed. Never dump Throwable messages.
        val engineFailure = runCatching { engine?.stop(1_000, 5_000) }.exceptionOrNull()
        val clientFailure = runCatching {
            client?.let { it.close(); runBlocking { it.coroutineContext.job.join() } }
        }.exceptionOrNull()
        val failure = engineFailure ?: clientFailure ?: this.engineFailure.get()
        if (engineFailure != null && clientFailure != null) engineFailure.addSuppressed(clientFailure)
        val event = log.atInfo().addKeyValue("event", "server_stopped")
        failure?.let {
            event.addKeyValue("exception_type", it.javaClass.name)
            it.stackTrace.takeIf { frames -> frames.isNotEmpty() }?.let { frames ->
                event.addKeyValue("exception_stacktrace", frames.joinToString("\n") { frame -> frame.toString() })
            }
        }
        event.log("Server stopped")
        try {
            context.stop()
        } finally {
            stopped.countDown()
        }
    }

    fun awaitStop() {
        try {
            stopped.await()
            stop()
        } catch (_: InterruptedException) {
            stop()
            Thread.currentThread().interrupt()
        }
    }
}

private fun serverDeps(httpClient: HttpClient): ServerDeps {
    val startedAt = Instant.now()
    val registry = PluginRegistry()
    registry.register(
        StatusTool(
            serverVersion = ServerInfo.VERSION,
            startedAt = startedAt,
            manifestVersion = ServerInfo.MANIFEST_VERSION,
            toolCount = { registry.tools.size },
            toolSummaries = { registry.tools.map { it.descriptor.name to it.descriptor.summary } },
        ),
    )
    System.getenv("TENGU_JIRA_BASE_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { baseUrl ->
        registry.register(JiraPlugin(baseUrl))
    }

    val secretsStore: SecretsStore =
        if (System.getenv("TENGU_DEV_SECRETS") == "1")
            // корень переопределяется TENGU_DEV_SECRETS_DIR — e2e/CI изолируют стор от локальных данных
            FileSecretsStore(Path.of(System.getenv("TENGU_DEV_SECRETS_DIR") ?: "data"))
        else VaultSecretsStore.fromEnv(httpClient)

    return ServerDeps(
        registry = registry,
        auth = StaticTokenHubAuth.fromEnv(),
        httpClient = httpClient,
        clock = Clock.systemUTC(),
        startedAt = startedAt,
        serverVersion = ServerInfo.VERSION,
        manifestVersion = ServerInfo.MANIFEST_VERSION,
        secretsScopeFor = { userId, tool -> secretsStore.scopeFor(userId, tool) },
    )
}
