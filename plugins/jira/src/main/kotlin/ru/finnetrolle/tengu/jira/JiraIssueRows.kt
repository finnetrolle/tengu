package ru.finnetrolle.tengu.jira

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Допустимые значения --fields — колонки сверх базовой схемы. */
internal val EXTRA_FIELDS = setOf("assignee", "priority", "updated", "reporter", "type")

private val BASE_SCHEMA = listOf("key", "title", "state")

/** Экстракторы колонок: (issue, fields) → значение; отсутствующие поля отображаются как «-». */
private val ISSUE_COLUMNS: Map<String, (JsonObject, JsonObject) -> JsonElement> = linkedMapOf(
    "key" to { issue, _ -> JsonPrimitive(issue.str("key") ?: "-") },
    "title" to { _, fields -> JsonPrimitive(fields.str("summary") ?: "-") },
    "state" to { _, fields -> JsonPrimitive(fields.nested("status", "name") ?: "-") },
    "assignee" to { _, fields -> JsonPrimitive(fields.nested("assignee", "displayName") ?: "-") },
    "priority" to { _, fields -> JsonPrimitive(fields.nested("priority", "name") ?: "-") },
    "updated" to { _, fields -> JsonPrimitive(fields.str("updated") ?: "-") },
    "reporter" to { _, fields -> JsonPrimitive(fields.nested("reporter", "displayName") ?: "-") },
    "type" to { _, fields -> JsonPrimitive(fields.nested("issuetype", "name") ?: "-") },
)

/** Строка списка: базовая схема key/title/state, затем колонки из --fields в фиксированном порядке. */
internal fun issueRow(issue: JsonObject, extra: List<String>): Map<String, JsonElement> {
    val fields = issue["fields"] as? JsonObject ?: JsonObject(emptyMap())
    return (BASE_SCHEMA + EXTRA_FIELDS.filter { it in extra })
        .associateWith { ISSUE_COLUMNS.getValue(it)(issue, fields) }
}
