package ru.finnetrolle.tengu.cli.tool

import ru.finnetrolle.tengu.cli.render.ToolDetail
import ru.finnetrolle.tengu.protocol.CommandDescriptor
import ru.finnetrolle.tengu.protocol.FlagDescriptor
import ru.finnetrolle.tengu.protocol.FlagKind
import ru.finnetrolle.tengu.protocol.FlagType
import ru.finnetrolle.tengu.protocol.ToolDescriptor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** --help для тула и команды: краткий полный справочник по запрошенному (AXI §10). */
object HelpText {

    /** `tengu jira --help`: список команд тула. */
    fun tool(tool: ToolDescriptor): String = ToolDetail.renderCatalog(
        buildJsonObject {
            put("tool", tool.name)
            put("summary", tool.summary)
            if (tool.description.isNotEmpty()) put("description", tool.description)
        },
        tool,
    )

    /** `tengu jira issues list --help`: флаги с дефолтами, аргументы, 2–3 примера. */
    fun command(toolName: String, cmd: CommandDescriptor): String {
        val label = (listOf(toolName) + cmd.path).joinToString(" ")
        val sb = StringBuilder()
        sb.append("tengu ").append(label).append(" — ").append(cmd.summary).append("\n")
        if (cmd.longHelp.isNotEmpty()) sb.append("\n").append(cmd.longHelp).append("\n")
        sb.append("\nusage: tengu ").append(label)
        appendUsageArgs(sb, cmd)
        sb.append(" [flags]\n")
        appendArgsSection(sb, cmd)
        appendFlagsSection(sb, cmd)
        appendExamplesSection(sb, cmd)
        return sb.toString()
    }

    /** Аргументы в usage-строке: обязательные `<name>`, необязательные `[<name>]`. */
    private fun appendUsageArgs(sb: StringBuilder, cmd: CommandDescriptor) {
        cmd.args.forEach { a -> sb.append(if (a.required) " <${a.name}>" else " [<${a.name}>]") }
    }

    private fun appendArgsSection(sb: StringBuilder, cmd: CommandDescriptor) {
        if (cmd.args.isEmpty()) return
        sb.append("\nargs:\n")
        cmd.args.forEach { a -> sb.append("  ").append(a.name).append("\n") }
    }

    private fun appendFlagsSection(sb: StringBuilder, cmd: CommandDescriptor) {
        if (cmd.flags.isEmpty()) return
        sb.append("\nflags:\n")
        cmd.flags.forEach { f -> appendFlag(sb, f) }
    }

    private fun appendFlag(sb: StringBuilder, f: FlagDescriptor) {
        sb.append("  --").append(f.name).append(flagValueHint(f)).append("\n")
        if (f.help.isNotEmpty()) sb.append("      ").append(f.help).append("\n")
        if (f.default != null) sb.append("      default: ").append(f.default).append("\n")
        if (f.required) sb.append("      required\n")
    }

    /** Плейсхолдер значения флага: `<a|b>`, `<int>`, `<value>` или пусто для SWITCH/BOOL. */
    private fun flagValueHint(f: FlagDescriptor): String {
        val allowed = f.allowedValues
        return when {
            allowed != null -> " <${allowed.joinToString("|")}>"
            f.type == FlagType.INT -> " <int>"
            f.kind == FlagKind.SWITCH || f.type == FlagType.BOOL -> "" // переключатель без значения
            else -> " <value>"
        }
    }

    private fun appendExamplesSection(sb: StringBuilder, cmd: CommandDescriptor) {
        if (cmd.examples.isEmpty()) return
        sb.append("\nexamples:\n")
        cmd.examples.forEach { e ->
            sb.append("  ").append(e.command)
            if (e.what.isNotEmpty()) sb.append("    # ").append(e.what)
            sb.append("\n")
        }
    }
}
