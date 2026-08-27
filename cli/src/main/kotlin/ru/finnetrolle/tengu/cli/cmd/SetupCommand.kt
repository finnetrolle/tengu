package ru.finnetrolle.tengu.cli.cmd

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import ru.finnetrolle.tengu.cli.CliConfig
import ru.finnetrolle.tengu.cli.CliRuntime
import ru.finnetrolle.tengu.cli.StoredConfig
import ru.finnetrolle.tengu.cli.render.ToonRenderer
import ru.finnetrolle.tengu.toon.Toon
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `tengu setup --url --token`: конфигурация + немедленная проверка связности и манифеста. */
class SetupCommand : CliktCommand(name = "setup") {

    override fun help(context: Context) = "Configure the hub server URL and your token"

    private val url by option("--url", help = "Server base URL, e.g. http://hub.corp:8080").required()
    private val token by option("--token", help = "Hub bearer token (ask your admin)").required()

    override fun run() {
        val config = StoredConfig(url.trim().trimEnd('/'), token.trim())
        // конфиг не сохраняется, пока сервер не ответил манифестом
        CliRuntime.fetchAndSave(config)
        CliConfig.save(config)
        echo(
            Toon.encode(
                ToonRenderer.withHelp(
                    buildJsonObject { put("setup", "configured for ${config.serverUrl}") },
                    listOf("Run `tengu` to see available tools"),
                ),
            ),
        )
    }
}
