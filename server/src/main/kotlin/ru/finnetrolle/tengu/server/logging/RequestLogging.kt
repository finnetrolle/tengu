package ru.finnetrolle.tengu.server.logging

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.isHandled
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import ru.finnetrolle.tengu.protocol.CommandDescriptor
import ru.finnetrolle.tengu.protocol.ErrorKind
import kotlin.time.TimeSource
import java.util.UUID

private const val BODY_LIMIT = 16_384
private const val UTF8_CONTINUATION_MASK = 0xc0
private const val UTF8_CONTINUATION = 0x80
private val requestLogKey = AttributeKey<RequestLogContext>("tengu.request.log")

/** Call-owned values; only immutable strings, primitives and copied lists reach SLF4J. */
class RequestLogContext {
    val requestId: String = UUID.randomUUID().toString()
    private val started = TimeSource.Monotonic.markNow()
    var route: String = "unmatched"
    var userId: String? = null
    var tool: String? = null
    var command: List<String>? = null
    var bodyAllowed: Boolean = false
    var outcome: String? = null
    var errorKind: ErrorKind? = null
    private var exceptionType: String? = null
    private var exceptionStack: String? = null
    private var body: String? = null
    private var bodyBytes: Int? = null
    private var bodyTruncated: Boolean? = null

    fun resolvedCommand(descriptor: CommandDescriptor) {
        command = descriptor.path.toList()
        bodyAllowed = descriptor.path.firstOrNull() != "auth" && descriptor.flags.none { it.secret }
    }

    fun exception(cause: Throwable) {
        exceptionType = cause.javaClass.name
        exceptionStack = cause.stackTrace.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.toString() }
    }

    fun capture(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var end = minOf(bytes.size, BODY_LIMIT)
        // If truncated, the first omitted byte must start a code point.
        while (end < bytes.size && end > 0 && (bytes[end].toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION) {
            end--
        }
        body = if (bytes.size <= BODY_LIMIT) text else String(bytes, 0, end, Charsets.UTF_8)
        bodyBytes = bytes.size
        bodyTruncated = bytes.size > BODY_LIMIT
    }

    fun level(method: String, status: Int?): Level = when {
        outcome == "cancelled" && status == null -> Level.WARN
        status != null && status >= HttpStatusCode.InternalServerError.value -> Level.ERROR
        status != null && status >= HttpStatusCode.BadRequest.value -> Level.WARN
        method == "GET" && route == "/v1/health" && status == HttpStatusCode.OK.value -> Level.DEBUG
        else -> Level.INFO
    }

    fun complete(logger: Logger, method: String, status: Int?) {
        val level = level(method, status)
        if (!logger.isEnabledForLevel(level)) return
        val event = logger.atLevel(level)
        event.addKeyValue("event", "http_request_completed")
            .addKeyValue("request_id", requestId)
            .addKeyValue("http_method", method)
            .addKeyValue("http_route", route)
            .addKeyValue("duration_ms", started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0))
            .addKeyValue(
                "outcome", outcome ?: if (status != null && status >= HttpStatusCode.BadRequest.value) {
                    "error"
                } else {
                    "ok"
                },
            )
        status?.let { event.addKeyValue("http_status", it) }
        userId?.let { event.addKeyValue("user_id", it) }
        tool?.let { event.addKeyValue("tool", it) }
        command?.let { event.addKeyValue("command", it.toList()) }
        errorKind?.let { event.addKeyValue("error_kind", it.name) }
        exceptionType?.let { event.addKeyValue("exception_type", it) }
        exceptionStack?.let { event.addKeyValue("exception_stacktrace", it) }
        body?.let {
            event.addKeyValue("response_body", it)
                .addKeyValue("response_body_bytes", bodyBytes)
                .addKeyValue("response_body_truncated", bodyTruncated)
        }
        event.log("HTTP request completed")
    }
}

val ApplicationCall.requestLog: RequestLogContext get() = attributes[requestLogKey]

/** Wrap the application pipeline, including unmatched routes and cancellation, exactly once. */
@Suppress("TooGenericExceptionCaught") // Observe any application failure while preserving cancellation.
fun Application.installRequestLogging(
    config: LoggingConfig = LoggingConfig(),
    logger: Logger = LoggerFactory.getLogger("ru.finnetrolle.tengu.server.logging.RequestLogging"),
) {
    sendPipeline.intercept(ApplicationSendPipeline.After) {
        val context = call.attributes.getOrNull(requestLogKey)
        val content = subject as? TextContent
        if (config.responseBody && context?.bodyAllowed == true && content != null) {
            val status = content.status?.value ?: call.response.status()?.value ?: HttpStatusCode.OK.value
            if (logger.isEnabledForLevel(context.level(call.request.httpMethod.value, status))) {
                context.capture(content.text)
            }
        }
    }
    intercept(ApplicationCallPipeline.Setup) {
        val context = RequestLogContext()
        call.attributes.put(requestLogKey, context)
        call.response.header("X-Request-ID", context.requestId)
        try {
            proceed()
            if (!call.isHandled) call.respond(HttpStatusCode.NotFound)
        } catch (cause: CancellationException) {
            context.outcome = "cancelled"
            context.exception(cause)
            throw cause
        } catch (cause: Throwable) {
            context.outcome = "error"
            context.exception(cause)
            if (!call.response.isCommitted) call.respond(HttpStatusCode.InternalServerError)
            // The failure is already recorded; do not send it to Ktor's raw URI/Throwable logger.
        } finally {
            context.complete(logger, call.request.httpMethod.value, call.response.status()?.value)
        }
    }
}
