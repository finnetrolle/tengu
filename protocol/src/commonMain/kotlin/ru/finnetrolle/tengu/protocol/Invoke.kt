package ru.finnetrolle.tengu.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Запрос на выполнение команды тула. Позиционные аргументы и флаги — все значения
 * строками (BOOL → "true"/"false"); типы уже проверены validate() на обеих сторонах.
 */
@Serializable
data class InvokeRequest(
    val tool: String,
    val commandPath: List<String>,
    val args: Map<String, String> = emptyMap(),
    val flags: Map<String, String> = emptyMap(),
)

/**
 * Успешный ответ: структурированные данные (CLI рендерит их как TOON),
 * подсказки следующего шага (AXI §9) и exit code (0 = успех и no-op).
 */
@Serializable
data class InvokeResponse(
    val payload: JsonElement,
    val helpHints: List<String> = emptyList(),
    val exitCode: Int = 0,
)
