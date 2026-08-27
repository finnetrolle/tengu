package ru.finnetrolle.tengu.toolkit

import ru.finnetrolle.tengu.protocol.AxiErrorEnvelope
import ru.finnetrolle.tengu.protocol.ErrorKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Алгебра результата плагина. Noop — идемпотентная мутация «уже так» (exit 0, AXI §6);
 * Err несёт kind → exit code (USAGE → 2, прочее → 1) и исправляющие хинты.
 */
sealed interface AxiResult {
    data class Ok(val payload: JsonElement, val helpHints: List<String> = emptyList()) : AxiResult
    data class Noop(val message: String, val helpHints: List<String> = emptyList()) : AxiResult
    data class Err(val error: AxiErrorEnvelope) : AxiResult

    companion object {
        fun ok(vararg pairs: Pair<String, Any?>, hints: List<String> = emptyList()): Ok =
            Ok(json(*pairs), hints)

        fun err(
            kind: ErrorKind,
            message: String,
            hints: List<String> = emptyList(),
            retryable: Boolean = false,
        ): Err = Err(AxiErrorEnvelope(kind, message, hints, retryable))

        /** Собрать JsonObject из пар со смешанными значениями (String/Int/Boolean/JsonElement/null). */
        fun json(vararg pairs: Pair<String, Any?>): JsonElement = buildJsonObject {
            pairs.forEach { (k, v) -> put(k, toJson(v)) }
        }
    }
}

internal fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    else -> JsonPrimitive(v.toString())
}
