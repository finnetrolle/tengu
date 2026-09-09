package ru.finnetrolle.tengu.server

import ru.finnetrolle.tengu.protocol.AxiErrorEnvelope
import ru.finnetrolle.tengu.protocol.CommandDescriptor
import ru.finnetrolle.tengu.protocol.ErrorKind
import ru.finnetrolle.tengu.protocol.InvokeRequest
import ru.finnetrolle.tengu.protocol.InvokeResponse
import ru.finnetrolle.tengu.protocol.ProtocolJson
import ru.finnetrolle.tengu.protocol.Validate
import ru.finnetrolle.tengu.protocol.httpStatus
import ru.finnetrolle.tengu.toolkit.AxiResult
import ru.finnetrolle.tengu.toolkit.FlagDefaults
import ru.finnetrolle.tengu.toolkit.InvocationContext
import ru.finnetrolle.tengu.toolkit.SecretScope
import ru.finnetrolle.tengu.toolkit.ToolPlugin
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import ru.finnetrolle.tengu.server.logging.LoggingConfig
import ru.finnetrolle.tengu.server.logging.installRequestLogging
import ru.finnetrolle.tengu.server.logging.requestLog
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Всё, что нужно роутам; собирается в Main, в тестах — вручную. */

private const val MALFORMED_INVOKE_BODY = "malformed invoke request body"

class ServerDeps(
    val registry: PluginRegistry,
    val auth: HubAuth,
    val httpClient: HttpClient,
    val clock: Clock,
    val startedAt: Instant,
    val serverVersion: String,
    val manifestVersion: Int,
    val secretsScopeFor: (userId: String, tool: String) -> SecretScope,
)

fun Application.tenguModule(
    deps: ServerDeps,
    loggingConfig: LoggingConfig = LoggingConfig(),
    requestLogger: Logger = LoggerFactory.getLogger("ru.finnetrolle.tengu.server.logging.RequestLogging"),
) {
    installRequestLogging(loggingConfig, requestLogger)
    routing {
        get("/v1/health") {
            call.requestLog.route = "/v1/health"
            call.respondText(
                ProtocolJson.json.encodeToString(
                    buildJsonObject {
                        put("serverVersion", deps.serverVersion)
                        put("uptimeSec", Duration.between(deps.startedAt, Instant.now(deps.clock)).seconds)
                        put("tools", deps.registry.tools.size)
                        put("manifestVersion", deps.manifestVersion)
                    },
                ),
                ContentType.Application.Json,
            )
        }

        get("/v1/manifest") {
            call.requestLog.route = "/v1/manifest"
            if (call.authorizedUser(deps) == null) return@get
            call.response.header("X-Tengu-Server-Version", deps.serverVersion)
            call.respondText(
                ProtocolJson.json.encodeToString(deps.registry.manifest(deps.manifestVersion, deps.serverVersion)),
                ContentType.Application.Json,
            )
        }

        post("/v1/invoke") {
            call.requestLog.route = "/v1/invoke"
            call.handleInvoke(deps)
        }
    }
}

private suspend fun ApplicationCall.handleInvoke(deps: ServerDeps) {
    val userId = authorizedUser(deps) ?: return
    if (rejectStaleManifest(deps)) return
    val request = receiveInvokeRequest() ?: return
    val tool = resolveTool(deps, request) ?: return
    if (rejectInvalidCommand(tool, request)) return
    respondInvokeResult(invokeTool(deps, userId, request, tool))
}

/** Рукопожатие версий: агент со старым кэшем получает 409 и обновляется. */
private suspend fun ApplicationCall.rejectStaleManifest(deps: ServerDeps): Boolean {
    val clientVersion = request.header("X-Tengu-Manifest-Version")?.toIntOrNull()
    if (clientVersion == null || clientVersion == deps.manifestVersion) return false
    respondError(
        AxiErrorEnvelope(
            kind = ErrorKind.STALE_MANIFEST,
            message = "local tool catalog is out of date (server manifest v${deps.manifestVersion})",
            retryable = true,
        ),
    )
    return true
}

private suspend fun ApplicationCall.receiveInvokeRequest(): InvokeRequest? {
    return try {
        ProtocolJson.json.decodeFromString<InvokeRequest>(receiveText())
    } catch (e: IllegalArgumentException) {
        // весь kotlinx.serialization (SerializationException и дети) - IllegalArgumentException
        requestLog.exception(e)
        respondError(AxiErrorEnvelope(ErrorKind.USAGE, MALFORMED_INVOKE_BODY))
        null
    } catch (e: IOException) {
        requestLog.exception(e)
        respondError(AxiErrorEnvelope(ErrorKind.USAGE, MALFORMED_INVOKE_BODY))
        null
    }
}

/** Серверная валидация — тот же код, что в CLI (защита в глубину). */
private suspend fun ApplicationCall.resolveTool(deps: ServerDeps, request: InvokeRequest): ToolPlugin? {
    val manifest = deps.registry.manifest(deps.manifestVersion, deps.serverVersion)
    val error = Validate.tool(manifest, request.tool)
    if (error != null) {
        respondError(error)
        return null
    }
    return deps.registry.find(request.tool)!!.also { requestLog.tool = it.descriptor.name }
}

private suspend fun ApplicationCall.rejectInvalidCommand(
    tool: ToolPlugin,
    request: InvokeRequest,
): Boolean {
    val cmd = tool.descriptor.commands.firstOrNull { it.path == request.commandPath }
    requestLog.command = cmd?.path?.toList()
    val error = if (cmd == null) {
        Validate.command(tool.descriptor, request.commandPath)
    } else {
        Validate.invoke(tool.descriptor.name, cmd, request.args, request.flags)
    }
    if (error != null) respondError(error) else if (cmd != null) requestLog.resolvedCommand(cmd)
    return error != null
}

private suspend fun ApplicationCall.invokeTool(
    deps: ServerDeps,
    userId: String,
    request: InvokeRequest,
    tool: ToolPlugin,
): AxiResult {
    val ctx = InvocationContext(
        tool = tool.descriptor.name,
        userId = userId,
        args = request.args,
        // дескриптор - единый источник дефолтов: мержим до плагина, чтобы плагин
        // всегда видел полную карту флагов; дефолты берутся у вызванной команды
        flags = FlagDefaults.withDefaults(tool.descriptor, request.commandPath, request.flags),
        secrets = deps.secretsScopeFor(userId, tool.descriptor.name),
        httpClient = deps.httpClient,
        clock = deps.clock,
    )
    // fault barrier: любой сбой плагина (апстрим, секреты, баг) не должен ронять HTTP-вызов -
    // агент получает INTERNAL-конверт и help-хинт. Общего конкретного предка у исключений
    // ktor/serialization/чужого кода нет, поэтому ловим всё (runCatching ловит Throwable,
    // как прежний catch(Exception) - с тем же внешним контрактом).
    return runCatching { tool.invoke(request.commandPath, ctx) }
        .getOrElse { e ->
            requestLog.exception(e)
            AxiResult.err(
                ErrorKind.INTERNAL,
                "tool '${tool.descriptor.name}' failed to complete the request",
                listOf("Run `tengu status` to check hub health"),
            )
        }
}

private suspend fun ApplicationCall.respondInvokeResult(result: AxiResult) {
    if (result is AxiResult.Noop) requestLog.outcome = "noop"
    when (result) {
        is AxiResult.Ok -> respondText(
            ProtocolJson.json.encodeToString(InvokeResponse(result.payload, result.helpHints, 0)),
            ContentType.Application.Json,
        )
        is AxiResult.Noop -> respondText(
            ProtocolJson.json.encodeToString(
                InvokeResponse(AxiResult.json("message" to result.message), result.helpHints, 0),
            ),
            ContentType.Application.Json,
        )
        is AxiResult.Err -> respondError(result.error)
    }
}

private suspend fun ApplicationCall.authorizedUser(deps: ServerDeps): String? {
    val token = request.header("Authorization")?.removePrefix("Bearer ")?.trim()
    val userId = deps.auth.userIdFor(token)
    if (userId == null) {
        respondError(
            AxiErrorEnvelope(
                kind = ErrorKind.AUTH,
                message = "missing or invalid hub token",
                helpHints = listOf("Run `tengu setup --url <url> --token <hub token>`"),
            ),
        )
    }
    requestLog.userId = userId
    return userId
}

private suspend fun ApplicationCall.respondError(e: AxiErrorEnvelope) {
    requestLog.errorKind = e.kind
    requestLog.outcome = "error"
    respondText(
        ProtocolJson.json.encodeToString(e),
        ContentType.Application.Json,
        HttpStatusCode.fromValue(e.kind.httpStatus()),
    )
}
