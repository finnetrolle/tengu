package ru.finnetrolle.tengu.toolkit

import kotlinx.serialization.Serializable

@Serializable
data class SecretMeta(
    val setAtIso: String,
    val last4: String,
    val version: Long = 0,
)

/**
 * Единственный доступ плагина к секретам. Скоуп связан с парой (пользователь, тул)
 * на этапе конструирования: плагин jira физически не может прочитать секреты
 * плагина gitlab или чужого пользователя — ошибка доступа невозможна архитектурно.
 */
interface SecretScope {
    suspend fun get(key: String): String?            // null = не настроен
    suspend fun put(key: String, value: String)
    suspend fun delete(key: String): Boolean
    suspend fun describe(key: String): SecretMeta?   // мета без значения

    companion object {
        private const val TAIL_CHARS = 4

        /** Хвост секрета для SecretMeta.last4 — единая точка вместо копий в хранилищах. */
        fun last4(secret: String): String = secret.takeLast(TAIL_CHARS)
    }
}

/** Для тестов и dev-режима без Vault. */
class FakeSecretScope(private val map: MutableMap<String, String> = mutableMapOf()) : SecretScope {

    override suspend fun get(key: String): String? = map[key]
    override suspend fun put(key: String, value: String) { map[key] = value }
    override suspend fun delete(key: String): Boolean = map.remove(key) != null
    override suspend fun describe(key: String): SecretMeta? =
        map[key]?.let { SecretMeta(setAtIso = "1970-01-01T00:00:00Z", last4 = SecretScope.last4(it)) }
}
