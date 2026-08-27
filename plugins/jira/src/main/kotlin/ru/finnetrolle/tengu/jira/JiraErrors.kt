package ru.finnetrolle.tengu.jira

import ru.finnetrolle.tengu.protocol.ErrorKind
import ru.finnetrolle.tengu.toolkit.AxiResult
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ---------- перевод ошибок апстрима ----------

internal fun errNoPat(): AxiResult.Err =
    AxiResult.err(ErrorKind.AUTH, "no PAT configured for jira", listOf("Run `tengu jira auth login --token <PAT>`"))

/** Jira отвергла PAT: 401/403 на любом запросе. */
internal fun JiraApiError.isAuthRejection(): Boolean =
    status == HttpStatusCode.Unauthorized.value || status == HttpStatusCode.Forbidden.value

/** Перевод ошибок апстрима: никаких URL/стек-трейсов наружу, только действие (AXI §6). */
internal fun translate(e: JiraApiError, key: String? = null): AxiResult.Err = when (e.status) {
    NETWORK_FAILURE -> AxiResult.err(
        ErrorKind.UPSTREAM,
        "cannot reach jira at the configured URL",
        listOf("Run `tengu status` to check hub health, then retry"),
    )
    HttpStatusCode.Unauthorized.value, HttpStatusCode.Forbidden.value -> AxiResult.err(
        ErrorKind.AUTH,
        "stored PAT was rejected by jira",
        listOf("Run `tengu jira auth login --token <PAT>` to re-login"),
    )
    HttpStatusCode.NotFound.value -> AxiResult.err(
        ErrorKind.NOT_FOUND,
        "issue '$key' not found",
        listOf("Run `tengu jira issues list --project <KEY>` to see existing issues"),
    )
    HttpStatusCode.BadRequest.value -> {
        val reason = jiraReason(e.body)
        AxiResult.err(
            ErrorKind.USAGE,
            if (reason != null) "jira rejected the query: $reason" else "--jql/--project values rejected by jira",
            listOf("Run `tengu jira projects list` to see valid project keys"),
        )
    }
    else -> AxiResult.err(
        ErrorKind.UPSTREAM,
        "jira request failed (HTTP ${e.status})",
        listOf("Run `tengu status` to check hub health, then retry"),
    )
}

/** Причина из тела ошибки Jira: errorMessages[0] или первая errors-строка. */
private fun jiraReason(body: String): String? = runCatching {
    val obj = Json.parseToJsonElement(body).let { it as? JsonObject } ?: return null
    val messages = obj["errorMessages"] as? JsonArray
    (messages?.firstOrNull() as? JsonPrimitive)?.content
        ?: (obj["errors"] as? JsonObject)?.values?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
}.getOrNull()
