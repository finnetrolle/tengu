package ru.finnetrolle.tengu.cli

import ru.finnetrolle.tengu.protocol.AxiErrorEnvelope
import ru.finnetrolle.tengu.protocol.ErrorKind
import ru.finnetrolle.tengu.protocol.Manifest
import ru.finnetrolle.tengu.protocol.exitCode
import ru.finnetrolle.tengu.cli.render.ToonRenderer
import kotlin.system.exitProcess

/** Среда выполнения CLI: конфиг, манифест-кэш, аварийный выход. */
object CliRuntime {

    fun fail(e: AxiErrorEnvelope): Nothing {
        print(ToonRenderer.error(e))
        exitProcess(e.kind.exitCode())
    }

    fun requireConfig(): StoredConfig =
        CliConfig.load() ?: fail(
            AxiErrorEnvelope(
                kind = ErrorKind.AUTH,
                message = "tengu is not configured",
                helpHints = listOf("Run `tengu setup --url <url> --token <hub token>`"),
            ),
        )

    fun ensureManifest(config: StoredConfig, force: Boolean = false): Manifest {
        if (!force) ManifestCache.cached()?.let { return it }
        return fetchAndSave(config)
    }

    fun fetchAndSave(config: StoredConfig): Manifest {
        val manifest = try {
            ServerClient(config).use { it.fetchManifest() }
        } catch (e: ServerError) {
            fail(e.envelope)
        }
        ManifestCache.save(manifest)
        return manifest
    }
}
