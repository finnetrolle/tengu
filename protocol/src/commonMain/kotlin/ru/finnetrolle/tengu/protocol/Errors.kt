package ru.finnetrolle.tengu.protocol

import kotlinx.serialization.Serializable

/** Вид ошибки. USAGE → exit 2, всё остальное → exit 1 (AXI §6). */
@Serializable
enum class ErrorKind { USAGE, AUTH, NOT_FOUND, STALE_MANIFEST, UPSTREAM, INTERNAL }

fun ErrorKind.exitCode(): Int = if (this == ErrorKind.USAGE) 2 else 1

// HTTP-коды kind'ов (значения как в ktor HttpStatusCode; сам ktor сюда не тянем -
// protocol исполняется и на CLI-стороне)
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_INTERNAL_SERVER_ERROR = 500
private const val HTTP_BAD_GATEWAY = 502

fun ErrorKind.httpStatus(): Int = when (this) {
    ErrorKind.USAGE -> HTTP_BAD_REQUEST
    ErrorKind.AUTH -> HTTP_UNAUTHORIZED
    ErrorKind.NOT_FOUND -> HTTP_NOT_FOUND
    ErrorKind.STALE_MANIFEST -> HTTP_CONFLICT
    ErrorKind.UPSTREAM -> HTTP_BAD_GATEWAY
    ErrorKind.INTERNAL -> HTTP_INTERNAL_SERVER_ERROR
}

/**
 * Единственный конверт ошибок: и по проводу (тело HTTP-ответа), и в рендере CLI
 * (error: … / help[N]: …). Сообщение самодостаточно, хинты — исправляющие за один ход.
 */
@Serializable
data class AxiErrorEnvelope(
    val kind: ErrorKind,
    val message: String,
    val helpHints: List<String> = emptyList(),
    val retryable: Boolean = false,
)
