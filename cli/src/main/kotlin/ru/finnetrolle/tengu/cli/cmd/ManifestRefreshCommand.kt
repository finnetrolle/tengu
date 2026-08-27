package ru.finnetrolle.tengu.cli.cmd

import com.github.ajalt.clikt.core.CliktCommand
import ru.finnetrolle.tengu.cli.CliRuntime
import ru.finnetrolle.tengu.cli.render.ToonRenderer
import ru.finnetrolle.tengu.toon.Toon
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `tengu manifest refresh`: принудительно обновить кэш каталога тулов. */
class ManifestRefreshCommand : CliktCommand(name = "refresh") {

    override fun help(context: com.github.ajalt.clikt.core.Context) = "Refresh the cached tool catalog"

    override fun run() {
        val config = CliRuntime.requireConfig()
        val manifest = CliRuntime.ensureManifest(config, force = true)
        echo(
            Toon.encode(
                ToonRenderer.withHelp(
                    buildJsonObject {
                        put(
                            "manifest",
                            "v${manifest.manifestVersion} from ${config.serverUrl} (${manifest.tools.size} tools)",
                        )
                    },
                    listOf("Run `tengu tools list` to see the tools"),
                ),
            ),
        )
    }
}
