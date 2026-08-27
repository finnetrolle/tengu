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
import io.ktor.server.application.log
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
import org.slf4j.Logger
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Всё, что нужно роутам; собирается в Main, в тестах — вручную. */
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

fun Application.tenguModule(deps: ServerDeps) {
    routing {
        get("/v1/health") {
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
            if (call.authorizedUser(deps) == null) return@get
            call.response.header("X-Tengu-Server-Version", deps.serverVersion)
            call.respondText(
                ProtocolJson.json.encodeToString(deps.registry.manifest(deps.manifestVersion, deps.serverVersion)),
                ContentType.Application.Json,
            )
        }

        post("/v1/invoke") { call.handleInvoke(deps, log) }
    }
}

private suspend fun ApplicationCall.handleInvoke(deps: ServerDeps, log: Logger) {
    val userId = authorizedUser(deps) ?: return
    if (rejectStaleManifest(deps)) return
    val request = receiveInvokeRequest(log) ?: return
    val tool = resolveTool(deps, request) ?: return
    if (rejectInvalidCommand(tool, request)) return
    respondInvokeResult(invokeTool(deps, userId, request, tool, log))
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

private suspend fun ApplicationCall.receiveInvokeRequest(log: Logger): InvokeRequest? {
    return try {
        ProtocolJson.json.decodeFromString<InvokeRequest>(receiveText())
    } catch (e: IllegalArgumentException) {
        // весь kotlinx.serialization (SerializationException и дети) - IllegalArgumentException
        log.warn("malformed invoke request body", e)
        respondError(AxiErrorEnvelope(ErrorKind.USAGE, "malformed invoke request body"))
        null
    } catch (e: IOException) {
        log.warn("could not read invoke request body", e)
        respondError(AxiErrorEnvelope(ErrorKind.USAGE, "malformed invoke request body"))
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
    return deps.registry.find(request.tool)!!
}

private suspend fun ApplicationCall.rejectInvalidCommand(
    tool: ToolPlugin,
    request: InvokeRequest,
): Boolean {
    val cmd = tool.descriptor.commands.firstOrNull { it.path == request.commandPath }
    val error = if (cmd == null) {
        Validate.command(tool.descriptor, request.commandPath)
    } else {
        Validate.invoke(tool.descriptor.name, cmd, request.args, request.flags)
    }
    if (error != null) respondError(error)
    return error != null
}

private suspend fun invokeTool(
    deps: ServerDeps,
    userId: String,
    request: InvokeRequest,
    tool: ToolPlugin,
    log: Logger,
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
            log.error("plugin '${tool.descriptor.name}' failed on '${request.commandPath.joinToString(" ")}'", e)
            AxiResult.err(
                ErrorKind.INTERNAL,
                "tool '${tool.descriptor.name}' failed to complete the request",
                listOf("Run `tengu status` to check hub health"),
            )
        }
}

private suspend fun ApplicationCall.respondInvokeResult(result: AxiResult) {
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
    return userId
}

private suspend fun ApplicationCall.respondError(e: AxiErrorEnvelope) {
    respondText(
        ProtocolJson.json.encodeToString(e),
        ContentType.Application.Json,
        HttpStatusCode.fromValue(e.kind.httpStatus()),
    )
}
