package ru.finnetrolle.tengu.cli

import ru.finnetrolle.tengu.protocol.Manifest
import ru.finnetrolle.tengu.protocol.ProtocolJson
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import ru.finnetrolle.tengu.cli.platform.createDirectories
import ru.finnetrolle.tengu.cli.platform.joinPath
import ru.finnetrolle.tengu.cli.platform.nowEpochSeconds
import ru.finnetrolle.tengu.cli.platform.readTextFile
import ru.finnetrolle.tengu.cli.platform.writeTextFile

@Serializable
data class CachedManifest(val fetchedAtIso: String, val manifest: Manifest)

/** Кэш манифеста: локальная валидация без раундтрипа + TTL 24 ч. */
object ManifestCache {
    private const val TTL_SECONDS = 24L * 60 * 60
    private val file get() = joinPath(CliConfig.dir, "manifest.json")

    fun cached(): Manifest? = read()?.takeIf { fresh(it) }?.manifest

    /** Даже протухший — пригодится оффлайн (валидация и --help без сервера). */
    fun anyCached(): Manifest? = read()?.manifest

    fun save(manifest: Manifest) {
        createDirectories(CliConfig.dir)
        val now = Instant.fromEpochSeconds(nowEpochSeconds()).toString()
        writeTextFile(file, ProtocolJson.json.encodeToString(CachedManifest(now, manifest)))
    }

    private fun fresh(c: CachedManifest): Boolean =
        runCatching {
            (Instant.fromEpochSeconds(nowEpochSeconds()) - Instant.parse(c.fetchedAtIso)).inWholeSeconds < TTL_SECONDS
        }.getOrDefault(false)

    private fun read(): CachedManifest? =
        readTextFile(file)?.let { runCatching { ProtocolJson.json.decodeFromString<CachedManifest>(it) }.getOrNull() }
}
