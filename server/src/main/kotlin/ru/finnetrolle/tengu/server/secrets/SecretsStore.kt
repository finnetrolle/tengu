package ru.finnetrolle.tengu.server.secrets

import ru.finnetrolle.tengu.toolkit.SecretMeta
import ru.finnetrolle.tengu.toolkit.SecretScope

/**
 * Серверное хранилище секретов. Реализации: Vault (prod), файл (dev/test).
 * scopeFor() — единственная точка раздачи доступа плагинам: скоуп жёстко
 * привязан к паре (userId, tool), плагин не видит чужого.
 */
interface SecretsStore {
    suspend fun put(userId: String, tool: String, key: String, value: String)
    suspend fun get(userId: String, tool: String, key: String): String?
    suspend fun delete(userId: String, tool: String, key: String): Boolean
    suspend fun describe(userId: String, tool: String, key: String): SecretMeta?

    fun scopeFor(userId: String, tool: String): SecretScope = StoreSecretScope(this, userId, tool)
}

class StoreSecretScope(
    private val store: SecretsStore,
    private val userId: String,
    private val tool: String,
) : SecretScope {
    override suspend fun get(key: String): String? = store.get(userId, tool, key)
    override suspend fun put(key: String, value: String) = store.put(userId, tool, key, value)
    override suspend fun delete(key: String): Boolean = store.delete(userId, tool, key)
    override suspend fun describe(key: String): SecretMeta? = store.describe(userId, tool, key)
}
