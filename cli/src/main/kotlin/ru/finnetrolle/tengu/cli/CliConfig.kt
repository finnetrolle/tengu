package ru.finnetrolle.tengu.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class StoredConfig(val serverUrl: String, val hubToken: String)

object CliConfig {
    /** %APPDATA%\tengu (Windows) / ~/.config/tengu (Linux). */
    val dir: Path = System.getenv("APPDATA")?.let { Path.of(it, "tengu") }
        ?: Path.of(System.getProperty("user.home"), ".config", "tengu")

    private val file: Path get() = dir.resolve("config.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun load(): StoredConfig? =
        if (Files.exists(file)) {
            runCatching { json.decodeFromString<StoredConfig>(Files.readString(file)) }.getOrNull()
        } else null

    fun save(config: StoredConfig) {
        Files.createDirectories(dir)
        Files.writeString(file, json.encodeToString(config))
    }
}
