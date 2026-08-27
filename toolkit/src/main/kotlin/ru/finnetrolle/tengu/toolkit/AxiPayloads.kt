package ru.finnetrolle.tengu.toolkit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Билдеры AXI-совместимых payload'ов: минимальные схемы (§2), count-агрегаты (§4),
 * truncation с маркером и escape-хинтом (§3), definitive empties (§5).
 * Благодаря им автор плагина соблюдает AXI «однострочниками», не думая о рендере.
 */
object AxiPayloads {

    /** Строка таблицы из смешанных значений: row("key" to "FOO-1", "done" to false). */
    fun row(vararg pairs: Pair<String, Any?>): Map<String, JsonElement> =
        pairs.associate { (k, v) -> k to toJson(v) }

    /**
     * Список с агрегатом: count: 30 of 847 total + табличный массив.
     * Пустой результат → definitive empty (§5): `issues: 0 issues found (project FOO)`.
     */
    fun listOfItems(
        noun: String,
        rows: List<Map<String, JsonElement>>,
        page: Int,
        total: Int,
        emptyPhrase: String? = null,
        nextHint: String? = null,
    ): AxiResult.Ok {
        if (rows.isEmpty()) {
            return AxiResult.Ok(
                AxiResult.json(noun to (emptyPhrase ?: "0 $noun found")),
                emptyList(),
            )
        }
        return AxiResult.Ok(
            buildJsonObject {
                put("count", JsonPrimitive("$page of $total total"))
                put(noun, JsonArray(rows.map { JsonObject(it) }))
            },
            listOfNotNull(nextHint),
        )
    }

    /** Детальный вид: task: {…} — длинные поля через truncatedPreview. */
    fun detail(noun: String, vararg fields: Pair<String, Any?>, hints: List<String> = emptyList()): AxiResult.Ok =
        AxiResult.Ok(
            buildJsonObject { put(noun, JsonObject(fields.associate { (k, v) -> k to toJson(v) })) },
            hints,
        )

    /** §3: превью с маркером усечения; truncationHint добавляй только если реально усечено. */
    fun truncatedPreview(text: String, limit: Int = 800): JsonElement =
        if (text.length <= limit) JsonPrimitive(text)
        else JsonPrimitive(text.take(limit) + "... (truncated, ${text.length} chars total)")

    fun truncationHint(fullCommand: String): String = "Run `$fullCommand` to see the complete text"
}
