package ru.finnetrolle.tengu.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.finnetrolle.tengu.cli.platform.createDirectories
import ru.finnetrolle.tengu.cli.platform.envVar
import ru.finnetrolle.tengu.cli.platform.joinPath
import ru.finnetrolle.tengu.cli.platform.readTextFile
import ru.finnetrolle.tengu.cli.platform.writeTextFile

@Serializable
data class StoredConfig(val serverUrl: String, val hubToken: String)

object CliConfig {
    /** %APPDATA%\tengu (Windows) / ~/.config/tengu (Linux). */
    val dir: String = envVar("APPDATA")?.let { joinPath(it, "tengu") }
        ?: joinPath(envVar("HOME") ?: envVar("USERPROFILE") ?: ".", ".config", "tengu")

    private val file: String get() = joinPath(dir, "config.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun load(): StoredConfig? =
        readTextFile(file)?.let { runCatching { json.decodeFromString<StoredConfig>(it) }.getOrNull() }

    fun save(config: StoredConfig) {
        createDirectories(dir)
        writeTextFile(file, json.encodeToString(config))
    }
}
