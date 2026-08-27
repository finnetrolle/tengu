package ru.finnetrolle.tengu.cli.tool

import ru.finnetrolle.tengu.cli.CliRuntime
import ru.finnetrolle.tengu.cli.ManifestCache
import ru.finnetrolle.tengu.cli.ServerClient
import ru.finnetrolle.tengu.cli.ServerError
import ru.finnetrolle.tengu.cli.StoredConfig
import ru.finnetrolle.tengu.cli.platform.readStdinAll
import ru.finnetrolle.tengu.cli.render.ToonRenderer
import ru.finnetrolle.tengu.protocol.AxiErrorEnvelope
import ru.finnetrolle.tengu.protocol.CommandDescriptor
import ru.finnetrolle.tengu.protocol.ErrorKind
import ru.finnetrolle.tengu.protocol.FlagDescriptor
import ru.finnetrolle.tengu.protocol.FlagKind
import ru.finnetrolle.tengu.protocol.FlagType
import ru.finnetrolle.tengu.protocol.InvokeRequest
import ru.finnetrolle.tengu.protocol.ToolDescriptor
import ru.finnetrolle.tengu.protocol.Validate
import kotlin.system.exitProcess

/**
 * Агентский путь: `tengu <tool> <command> [flags]`.
 * Валидация по кэшу манифеста ДО сети (fail-loud, exit 2 без раундтрипа),
 * 409 STALE_MANIFEST → авто-refresh и один ретрай, незаметный для агента.
 */
object ToolInvocation {

    fun run(argv: List<String>) {
        val config = CliRuntime.requireConfig()
        var manifest = CliRuntime.ensureManifest(config)
        val toolName = argv.first()
        val rest = argv.drop(1)

        // неизвестный тул мог появиться на сервере после кэширования — refresh и перепроверка
        var tool = manifest.tool(toolName)
        if (tool == null) {
            manifest = CliRuntime.fetchAndSave(config)
            tool = manifest.tool(toolName) ?: CliRuntime.fail(Validate.tool(manifest, toolName)!!)
        }

        // `tengu jira --help` — справка по тулу
        printToolHelpIfRequested(rest, tool)

        var matched = matchCommand(tool, rest)
            // fallback: команда, совпадающая с именем тула, вызывается без повтора — `tengu status`
            ?: matchCommand(tool, listOf(toolName) + rest)
        if (matched == null) {
            // и новую команду кэш тоже мог не знать — refresh и повтор
            manifest = CliRuntime.fetchAndSave(config)
            val freshTool = manifest.tool(toolName)!!
            matched = matchCommand(freshTool, rest) ?: matchCommand(freshTool, listOf(toolName) + rest)
                ?: CliRuntime.fail(Validate.command(freshTool, rest.takeWhile { !it.startsWith("-") })!!)
        }
        val (cmd, remaining) = matched

        val parsed = parseTokens(cmd, remaining)

        // --help всегда проходит, даже вместе с неизвестными флагами (AXI §6)
        if (parsed.flags["help"] == "true") {
            print(HelpText.command(toolName, cmd))
            exitProcess(0)
        }

        // сперва флаги (неизвестный флаг важнее лишнего аргумента — его значение
        // парсер не потребляет и оно попадает в позиционные), затем extras
        Validate.invoke(toolName, cmd, parsed.args, parsed.flags)?.let { CliRuntime.fail(it) }

        parsed.extra.firstOrNull()?.let { failOnUnexpectedArgument(toolName, cmd, it) }

        // конвенция CLI: значение '-' у secret-флага читается из stdin
        // (токен не оседает в истории шелла; резолвится до отправки на сервер)
        val flags = resolveStdinSecrets(cmd, parsed.flags)

        val request = InvokeRequest(toolName, cmd.path, parsed.args, flags)
        invokeAndExit(config, request, manifest.manifestVersion) { e ->
            if (e.envelope.kind == ErrorKind.STALE_MANIFEST) retryAfterRefresh(config, request)
            CliRuntime.fail(e.envelope)
        }
    }

    /** `--help`/`-h` сразу после имени тула: краткий справочник по командам и выход 0. */
    private fun printToolHelpIfRequested(rest: List<String>, tool: ToolDescriptor) {
        if (rest == listOf("--help") || rest == listOf("-h")) {
            print(HelpText.tool(tool))
            exitProcess(0)
        }
    }

    /** Лишний позиционный аргумент: usage-конверт с хинтом на справку. */
    private fun failOnUnexpectedArgument(toolName: String, cmd: CommandDescriptor, arg: String): Nothing {
        val label = (listOf(toolName) + cmd.path).joinToString(" ")
        CliRuntime.fail(
            AxiErrorEnvelope(
                kind = ErrorKind.USAGE,
                message = "unexpected argument '$arg' for `$label`",
                helpHints = listOf("Run `tengu $label --help` for usage"),
            ),
        )
    }

    /** Конвенция CLI: '-' у secret-флага → значение из stdin (токен не попадает в историю шелла). */
    private fun resolveStdinSecrets(cmd: CommandDescriptor, flags: Map<String, String>): Map<String, String> {
        val secretNames = cmd.flags.filter { it.secret }.map { it.name }.toSet()
        if (secretNames.none { flags[it] == "-" }) return flags
        return flags.mapValues { (name, value) ->
            if (value == "-" && name in secretNames) readStdinValue() else value
        }
    }

    private fun readStdinValue(): String = readStdinAll().trim()

    /**
     * Один invoke-раунд: TOON-рендер ответа и выход с exit code тула. Сбой сети/сервера
     * уходит в [onError] — вызывающий решает, ретраить ли 409 STALE_MANIFEST.
     */
    private fun invokeAndExit(
        config: StoredConfig,
        request: InvokeRequest,
        manifestVersion: Int,
        onError: (ServerError) -> Nothing,
    ): Nothing {
        val response = try {
            ServerClient(config).use { it.invoke(request, manifestVersion) }
        } catch (e: ServerError) {
            onError(e)
        }
        print(ToonRenderer.response(response))
        exitProcess(response.exitCode)
    }

    /** 409 STALE_MANIFEST: свежий манифест, повторная валидация и один ретрай. */
    private fun retryAfterRefresh(config: StoredConfig, request: InvokeRequest): Nothing {
        val fresh = try {
            ServerClient(config).use { it.fetchManifest() }
        } catch (e: ServerError) {
            CliRuntime.fail(e.envelope)
        }
        ManifestCache.save(fresh)

        val tool = fresh.tool(request.tool) ?: CliRuntime.fail(Validate.tool(fresh, request.tool)!!)
        val cmd = tool.commands.firstOrNull { it.path == request.commandPath }
            ?: CliRuntime.fail(Validate.command(tool, request.commandPath)!!)
        Validate.invoke(request.tool, cmd, request.args, request.flags)?.let { CliRuntime.fail(it) }

        invokeAndExit(config, request, fresh.manifestVersion) { e -> CliRuntime.fail(e.envelope) }
    }

    /** Длиннейшее совпадение префикса с путями команд; остаток — флаги и позиционные. */
    private fun matchCommand(tool: ToolDescriptor, tokens: List<String>): Pair<CommandDescriptor, List<String>>? {
        var best: CommandDescriptor? = null
        var bestLen = 0
        for (cmd in tool.commands) {
            val p = cmd.path
            if (p.size <= tokens.size && tokens.take(p.size) == p && p.size > bestLen) {
                best = cmd
                bestLen = p.size
            }
        }
        return best?.let { it to tokens.drop(bestLen) }
    }

    private data class Parsed(val args: Map<String, String>, val flags: Map<String, String>, val extra: List<String>)

    /** Один разобранный флаг: имя, значение и потреблён ли следующий токен под значение. */
    private data class ParsedFlag(val name: String, val value: String, val tookNextToken: Boolean)

    /**
     * Разбор флагов/позиционных: `--name value`, `--name=value`; SWITCH/BOOL не потребляют
     * следующий токен. Неизвестные флаги собираются как есть — канонический конверт
     * ошибок выдаст единый Validate (один и тот же текст на клиенте и сервере).
     */
    private fun parseTokens(cmd: CommandDescriptor, tokens: List<String>): Parsed {
        val args = mutableListOf<Pair<String, String>>()
        val flags = mutableMapOf<String, String>()
        val extra = mutableListOf<String>()
        val flagByName = cmd.flags.associateBy { it.name }

        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "--help" || t == "-h" -> flags["help"] = "true"

                t.startsWith("--") -> {
                    val parsed = parseLongFlag(t, tokens.getOrNull(i + 1), flagByName)
                    flags[parsed.name] = parsed.value
                    if (parsed.tookNextToken) i++
                }

                else -> {
                    val slot = cmd.args.getOrNull(args.size)
                    if (slot != null) args.add(slot.name to t) else extra.add(t)
                }
            }
            i++
        }
        return Parsed(args.toMap(), flags, extra)
    }

    /** Токен `--name[=value]`: имя и значение по правилам flagValue. */
    private fun parseLongFlag(token: String, next: String?, flagByName: Map<String, FlagDescriptor>): ParsedFlag {
        val body = token.removePrefix("--")
        val eq = body.indexOf('=')
        val name = if (eq >= 0) body.substring(0, eq) else body
        val inline = if (eq >= 0) body.substring(eq + 1) else null
        val (value, tookNextToken) = flagValue(flagByName[name], inline, next)
        return ParsedFlag(name, value, tookNextToken)
    }

    /**
     * Значение флага: inline после `=`, "true" для SWITCH/BOOL, иначе следующий токен.
     * Второй элемент пары — потреблён ли следующий токен.
     */
    private fun flagValue(desc: FlagDescriptor?, inline: String?, next: String?): Pair<String, Boolean> = when {
        inline != null -> inline to false
        desc == null -> "" to false // неизвестный флаг: значение не важно, Validate ответит канонично
        desc.type == FlagType.BOOL || desc.kind == FlagKind.SWITCH -> "true" to false
        // пропущенное значение → required/format-ошибка от Validate, если флаг обязателен
        next == null || next.startsWith("--") -> "" to false
        else -> next to true
    }
}
