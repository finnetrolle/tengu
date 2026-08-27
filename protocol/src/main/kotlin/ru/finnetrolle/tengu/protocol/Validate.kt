package ru.finnetrolle.tengu.protocol

/**
 * Совместная usage-валидация: исполняется и в CLI (по кэшу манифеста, до сети —
 * AXI §6 fail-loud без раундтрипа), и на сервере (защита в глубину). Один код —
 * поведение сторон не расходится. Возвращает null, если всё в порядке.
 */
object Validate {

    fun tool(manifest: Manifest, toolName: String): AxiErrorEnvelope? {
        if (manifest.tool(toolName) == null) {
            return AxiErrorEnvelope(
                kind = ErrorKind.USAGE, // неизвестный тул = usage-ошибка → exit 2 (NOT_FOUND — рантайм)
                message = "unknown tool '$toolName'",
                helpHints = listOf("available tools: ${manifest.tools.joinToString(", ") { it.name }}"),
            )
        }
        return null
    }

    fun command(toolDescriptor: ToolDescriptor, commandPath: List<String>): AxiErrorEnvelope? {
        if (toolDescriptor.commands.none { it.path == commandPath }) {
            val given = commandPath.joinToString(" ")
            return AxiErrorEnvelope(
                kind = ErrorKind.USAGE,
                message = if (given.isEmpty()) "no command given for tool '${toolDescriptor.name}'"
                else "unknown command '$given' for tool '${toolDescriptor.name}'",
                helpHints = listOf(
                    "valid commands for '${toolDescriptor.name}': " +
                        toolDescriptor.commands.joinToString(", ") { it.path.joinToString(" ") },
                ),
            )
        }
        return null
    }

    fun invoke(
        toolName: String,
        cmd: CommandDescriptor,
        args: Map<String, String>,
        flags: Map<String, String>,
    ): AxiErrorEnvelope? {
        val label = (listOf(toolName) + cmd.path).joinToString(" ")
        return unknownFlagError(label, cmd, flags)
            ?: missingFlagError(label, cmd, flags)
            ?: flagValueError(label, cmd, flags)
            ?: missingArgError(label, cmd, args)
    }

    // 1) неизвестные флаги; переименованные получают точечный хинт (AXI §6)
    private fun unknownFlagError(
        label: String,
        cmd: CommandDescriptor,
        flags: Map<String, String>,
    ): AxiErrorEnvelope? =
        flags.keys.firstNotNullOfOrNull { name ->
            val replacement = cmd.renamedFlags[name]
            when {
                name == "help" -> null
                replacement != null ->
                    usage("--$name was renamed; use --$replacement instead", listOf(validFlagsHint(label, cmd)))
                cmd.flags.none { it.name == name } ->
                    usage("unknown flag --$name for `$label`", listOf(validFlagsHint(label, cmd)))
                else -> null
            }
        }

    // 2) обязательные флаги
    private fun missingFlagError(
        label: String,
        cmd: CommandDescriptor,
        flags: Map<String, String>,
    ): AxiErrorEnvelope? =
        cmd.flags.firstOrNull { it.required && !flags.containsKey(it.name) }?.let {
            usage("--${it.name} is required", listOf("Run `tengu $label --help` for usage"))
        }

    // 3) типы и допустимые значения
    private fun flagValueError(
        label: String,
        cmd: CommandDescriptor,
        flags: Map<String, String>,
    ): AxiErrorEnvelope? =
        flags.entries.firstNotNullOfOrNull { (name, raw) ->
            cmd.flags.firstOrNull { it.name == name }?.let { flagValueError(label, it, raw) }
        }

    private fun flagValueError(label: String, f: FlagDescriptor, raw: String): AxiErrorEnvelope? {
        val typeMismatch = when {
            f.type == FlagType.INT && raw.toIntOrNull() == null ->
                "--${f.name} expects an integer, got \"$raw\""
            f.type == FlagType.BOOL && raw != "true" && raw != "false" ->
                "--${f.name} expects true or false, got \"$raw\""
            else -> null
        }
        val allowed = f.allowedValues
        return typeMismatch?.let { usage(it, listOf("Run `tengu $label --help` for usage")) }
            ?: if (allowed != null && raw !in allowed) {
                usage(
                    "--${f.name} must be one of: ${allowed.joinToString(", ")}",
                    listOf("Run `tengu $label --help` for usage"),
                )
            } else {
                null
            }
    }

    // 4) позиционные аргументы
    private fun missingArgError(
        label: String,
        cmd: CommandDescriptor,
        args: Map<String, String>,
    ): AxiErrorEnvelope? =
        cmd.args.firstOrNull { it.required && !args.containsKey(it.name) }?.let {
            usage("missing required argument <${it.name}>", listOf("Run `tengu $label --help` for usage"))
        }

    private fun usage(message: String, hints: List<String>): AxiErrorEnvelope =
        AxiErrorEnvelope(kind = ErrorKind.USAGE, message = message, helpHints = hints)

    private fun validFlagsHint(label: String, cmd: CommandDescriptor): String =
        if (cmd.flags.isEmpty()) "`$label` takes no flags"
        else "valid flags for `$label`: ${cmd.flags.joinToString(", ") { "--" + it.name }} (--help always allowed)"
}
