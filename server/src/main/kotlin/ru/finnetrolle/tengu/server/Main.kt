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
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

fun main() {
    val port = System.getenv("TENGU_PORT")?.toIntOrNull() ?: 8080
    val httpClient = HttpClient(ClientCIO)
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

    val deps = ServerDeps(
        registry = registry,
        auth = StaticTokenHubAuth.fromEnv(),
        httpClient = httpClient,
        clock = Clock.systemUTC(),
        startedAt = startedAt,
        serverVersion = ServerInfo.VERSION,
        manifestVersion = ServerInfo.MANIFEST_VERSION,
        secretsScopeFor = { userId, tool -> secretsStore.scopeFor(userId, tool) },
    )

    Runtime.getRuntime().addShutdownHook(Thread { httpClient.close() })
    embeddedServer(CIO, port = port) { tenguModule(deps) }.start(wait = true)
}
