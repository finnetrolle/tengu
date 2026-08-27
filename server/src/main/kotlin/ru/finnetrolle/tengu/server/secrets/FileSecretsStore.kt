package ru.finnetrolle.tengu.server.secrets

import ru.finnetrolle.tengu.toolkit.SecretMeta
import ru.finnetrolle.tengu.toolkit.SecretScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * DEV/TEST ONLY: секреты в data/{user}/{tool}.json (корень — TENGU_DEV_SECRETS_DIR,
 * по умолчанию data). Активируется только при TENGU_DEV_SECRETS=1 — Main не
 * сконструирует его иначе; конструктор дублирует предупреждение в лог.
 */
class FileSecretsStore(private val root: Path) : SecretsStore {

    private val log = LoggerFactory.getLogger(FileSecretsStore::class.java)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        log.warn(
            "FileSecretsStore active — secrets stored UNENCRYPTED under " +
                "${root.toAbsolutePath()}. Never use in production.",
        )
    }

    override suspend fun put(userId: String, tool: String, key: String, value: String) = mutex.withLock {
        val file = file(userId, tool)
        val obj = readObj(file).toMutableMap()
        obj[key] = JsonPrimitive(value)
        obj["_setAt_$key"] = JsonPrimitive(Instant.now().toString())
        Files.createDirectories(file.parent)
        Files.writeString(file, JsonObject(obj).toString())
        Unit
    }

    override suspend fun get(userId: String, tool: String, key: String): String? = mutex.withLock {
        readObj(file(userId, tool))[key]?.let { (it as JsonPrimitive).content }
    }

    override suspend fun delete(userId: String, tool: String, key: String): Boolean = mutex.withLock {
        val file = file(userId, tool)
        val obj = readObj(file).toMutableMap()
        val had = obj.remove(key) != null
        obj.remove("_setAt_$key")
        if (had) {
            Files.createDirectories(file.parent)
            Files.writeString(file, JsonObject(obj).toString())
        }
        had
    }

    override suspend fun describe(userId: String, tool: String, key: String): SecretMeta? {
        val value = get(userId, tool, key) ?: return null
        val setAt = readObj(file(userId, tool))["_setAt_$key"]?.let { (it as JsonPrimitive).content } ?: "unknown"
        return SecretMeta(setAtIso = setAt, last4 = SecretScope.last4(value), version = 0)
    }

    private fun file(userId: String, tool: String): Path =
        root.resolve(userId.replace(Regex("[^A-Za-z0-9_.-]"), "_")).resolve("$tool.json")

    private fun readObj(file: Path): Map<String, kotlinx.serialization.json.JsonElement> =
        if (Files.exists(file)) {
            runCatching { json.parseToJsonElement(Files.readString(file)).jsonObject.toMap() }.getOrDefault(emptyMap())
        } else emptyMap()
}
