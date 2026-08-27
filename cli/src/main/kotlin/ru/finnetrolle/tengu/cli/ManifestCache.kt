package ru.finnetrolle.tengu.cli

import ru.finnetrolle.tengu.protocol.Manifest
import ru.finnetrolle.tengu.protocol.ProtocolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

@Serializable
data class CachedManifest(val fetchedAtIso: String, val manifest: Manifest)

/** Кэш манифеста: локальная валидация без раундтрипа + TTL 24 ч. */
object ManifestCache {
    private val ttl: Duration = Duration.ofHours(24)
    private val file get() = CliConfig.dir.resolve("manifest.json")

    fun cached(): Manifest? = read()?.takeIf { fresh(it) }?.manifest

    /** Даже протухший — пригодится оффлайн (валидация и --help без сервера). */
    fun anyCached(): Manifest? = read()?.manifest

    fun save(manifest: Manifest) {
        Files.createDirectories(CliConfig.dir)
        Files.writeString(file, ProtocolJson.json.encodeToString(CachedManifest(Instant.now().toString(), manifest)))
    }

    private fun fresh(c: CachedManifest): Boolean =
        runCatching { Duration.between(Instant.parse(c.fetchedAtIso), Instant.now()) < ttl }.getOrDefault(false)

    private fun read(): CachedManifest? =
        if (Files.exists(file)) {
            runCatching { ProtocolJson.json.decodeFromString<CachedManifest>(Files.readString(file)) }.getOrNull()
        } else null
}
