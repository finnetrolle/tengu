package ru.finnetrolle.tengu.server.secrets

import ru.finnetrolle.tengu.toolkit.SecretMeta
import ru.finnetrolle.tengu.toolkit.SecretScope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Production-хранилище: HashiCorp Vault KV v2 через REST (без тяжёлого Java-драйвера).
 * Пути: {addr}/v1/{mount}/data/{prefix}/{userId}/{tool}. Токен Vault — только в env сервера.
 */
class VaultSecretsStore(
    private val client: HttpClient,
    private val addr: String,
    private val token: String,
    private val mount: String = "secret",
    private val prefix: String = "tengu",
) : SecretsStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun put(userId: String, tool: String, key: String, value: String) {
        val resp = client.put(dataUrl(userId, tool)) {
            contentType(ContentType.Application.Json)
            header(VAULT_TOKEN_HEADER, token)
            setBody(
                buildJsonObject {
                    put("data", buildJsonObject { put(key, value) })
                }.toString(),
            )
        }
        resp.ensureSuccess("write secret")
    }

    override suspend fun get(userId: String, tool: String, key: String): String? {
        val resp = client.get(dataUrl(userId, tool)) { header(VAULT_TOKEN_HEADER, token) }
        if (resp.status == HttpStatusCode.NotFound) return null
        resp.ensureSuccess("read secret")
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val inner = (body["data"] as? JsonObject)?.get("data") as? JsonObject
        return (inner?.get(key) as? JsonPrimitive)?.content
    }

    override suspend fun delete(userId: String, tool: String, key: String): Boolean {
        get(userId, tool, key) ?: return false
        val resp = client.delete(dataUrl(userId, tool)) { header(VAULT_TOKEN_HEADER, token) }
        resp.ensureSuccess("delete secret")
        return true
    }

    override suspend fun describe(userId: String, tool: String, key: String): SecretMeta? {
        val value = get(userId, tool, key) ?: return null
        val setAt = runCatching {
            val resp = client.get(metaUrl(userId, tool)) { header(VAULT_TOKEN_HEADER, token) }
            if (!resp.status.isSuccess()) "unknown"
            else {
                val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"] as? JsonObject
                (data?.get("created_time") as? JsonPrimitive)?.content ?: "unknown"
            }
        }.getOrDefault("unknown")
        return SecretMeta(setAtIso = setAt, last4 = SecretScope.last4(value), version = 0)
    }

    private fun dataUrl(userId: String, tool: String): String =
        "$addr/v1/$mount/data/$prefix/${userSeg(userId)}/$tool"

    private fun metaUrl(userId: String, tool: String): String =
        "$addr/v1/$mount/metadata/$prefix/${userSeg(userId)}/$tool"

    private fun userSeg(userId: String): String = userId.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private suspend fun HttpResponse.ensureSuccess(what: String) {
        if (!status.isSuccess()) {
            throw IllegalStateException("vault $what failed (HTTP ${status.value})")
        }
    }

    companion object {
        private const val VAULT_TOKEN_HEADER = "X-Vault-Token"

        fun fromEnv(client: HttpClient, env: Map<String, String> = System.getenv()): VaultSecretsStore {
            val addr = env["TENGU_VAULT_ADDR"]?.trimEnd('/')
                ?: error("TENGU_VAULT_ADDR is not set and TENGU_DEV_SECRETS is not enabled")
            val token = env["TENGU_VAULT_TOKEN"] ?: error("TENGU_VAULT_TOKEN is not set")
            return VaultSecretsStore(
                client = client,
                addr = addr,
                token = token,
                mount = env["TENGU_VAULT_MOUNT"] ?: "secret",
                prefix = env["TENGU_VAULT_PREFIX"] ?: "tengu",
            )
        }
    }
}
