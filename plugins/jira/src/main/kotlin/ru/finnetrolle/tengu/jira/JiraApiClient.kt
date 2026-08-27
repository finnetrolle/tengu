package ru.finnetrolle.tengu.jira

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

class JiraApiError(val status: Int, val body: String) : Exception("jira api http $status")

/** Сентинел сбоя транспорта: jira не ответила осмысленным ответом (таймаут, обрыв, битый JSON). */
internal const val NETWORK_FAILURE = -1

private const val ERROR_BODY_SNIPPET = 300

private val json = Json { ignoreUnknownKeys = true }

/** Общее для GET/POST: неуспешный статус → ошибка с телом-сниппетом, иначе текст тела. */
private suspend fun getText(resp: HttpResponse): String {
    if (!resp.status.isSuccess()) {
        throw JiraApiError(resp.status.value, resp.bodyAsText().take(ERROR_BODY_SNIPPET))
    }
    return resp.bodyAsText()
}

private fun String.toJsonObject(): JsonObject =
    if (isBlank()) JsonObject(emptyMap())
    else json.parseToJsonElement(this) as? JsonObject
        ?: throw JiraApiError(NETWORK_FAILURE, "jira returned a non-object body")

private fun String.toJsonArray(): JsonArray =
    if (isBlank()) JsonArray(emptyList())
    else json.parseToJsonElement(this) as? JsonArray
        ?: throw JiraApiError(NETWORK_FAILURE, "jira returned a non-array body")

/** Тонкая HTTP-обёртка над Jira REST v2. PAT ставится на каждый запрос — плагин сам владеет доступом. */
class JiraApiClient(private val client: HttpClient, private val baseUrl: String) {

    suspend fun myself(pat: String): JsonObject = getJson(pat, "/rest/api/2/myself")

    suspend fun projects(pat: String): JsonArray =
        api {
            getText(client.get("$baseUrl/rest/api/2/project") { header("Authorization", "Bearer $pat") }).toJsonArray()
        }

    suspend fun search(pat: String, jql: String, startAt: Int, maxResults: Int, fields: List<String>): JsonObject =
        postJson(
            pat,
            "/rest/api/2/search",
            buildJsonObject {
                put("jql", jql)
                put("startAt", startAt)
                put("maxResults", maxResults)
                put("fields", JsonArray(fields.map { JsonPrimitive(it) }))
            },
        )

    suspend fun issue(pat: String, key: String): JsonObject = getJson(pat, "/rest/api/2/issue/$key")

    suspend fun create(pat: String, fields: JsonObject): JsonObject =
        postJson(pat, "/rest/api/2/issue", buildJsonObject { put("fields", fields) })

    suspend fun addComment(pat: String, key: String, body: String): JsonObject =
        postJson(pat, "/rest/api/2/issue/$key/comment", buildJsonObject { put("body", body) })

    private suspend fun getJson(pat: String, path: String): JsonObject =
        api {
            getText(client.get("$baseUrl$path") { header("Authorization", "Bearer $pat") }).toJsonObject()
        }

    private suspend fun postJson(pat: String, path: String, body: JsonObject): JsonObject =
        api {
            getText(
                client.post("$baseUrl$path") {
                    header("Authorization", "Bearer $pat")
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                },
            ).toJsonObject()
        }

    /**
     * Сетевые сбои (таймауты, обрывы соединения) и битый JSON → JiraApiError(NETWORK_FAILURE):
     * плагин переведёт их в «jira недоступен», без стектрейсов.
     */
    private suspend fun <T> api(block: suspend () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e.asNetworkError()
    } catch (e: SerializationException) {
        throw e.asNetworkError()
    }

    private fun Exception.asNetworkError(): JiraApiError =
        JiraApiError(NETWORK_FAILURE, message ?: this::class.simpleName ?: "network error")
}
