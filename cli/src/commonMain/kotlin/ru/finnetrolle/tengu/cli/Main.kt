package ru.finnetrolle.tengu.cli

import ru.finnetrolle.tengu.cli.cmd.ManifestRefreshCommand
import ru.finnetrolle.tengu.cli.platform.initConsoleUtf8
import ru.finnetrolle.tengu.cli.cmd.SetupCommand
import ru.finnetrolle.tengu.cli.cmd.ToolsCommand
import ru.finnetrolle.tengu.cli.render.Dashboard
import ru.finnetrolle.tengu.cli.tool.ToolInvocation
import ru.finnetrolle.tengu.protocol.AxiErrorEnvelope
import ru.finnetrolle.tengu.protocol.ErrorKind
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.ParameterFormatter

fun main(argv: Array<String>) {
    // до любого вывода: интерактивная Windows-консоль должна стать UTF-8 (на пайпы не влияет)
    initConsoleUtf8()

    // fast path (AXI §10): версия — до загрузки Clikt/Ktor
    if (argv.size == 1 && argv[0] in VERSION_FLAGS) {
        println("tengu ${Version.VERSION}")
        return
    }

    if (argv.isEmpty()) {
        val config = CliRuntime.requireConfig()
        val manifest = CliRuntime.ensureManifest(config)
        print(Dashboard.render(manifest))
        return
    }

    when {
        argv[0] in BUILTINS || argv[0].startsWith("-") -> runBuiltins(argv)
        else -> ToolInvocation.run(argv.toList())
    }
}

private val VERSION_FLAGS = setOf("-v", "-V", "--version")
private val BUILTINS = setOf("tools", "setup", "manifest")

private class ManifestCommand : CliktCommand(name = "manifest") {
    override fun help(context: Context) = "Tool catalog cache operations"

    init {
        subcommands(ManifestRefreshCommand())
    }

    override fun run() {
        // группа-контейнер: реальную работу выполняют субкоманды
    }
}

private class TenguRootCommand : CliktCommand(name = "tengu") {
    override fun help(context: Context) = "Agent tool hub: discover and invoke corporate tools"

    init {
        subcommands(
            ToolsCommand(),
            SetupCommand(),
            ManifestCommand(),
        )
    }

    override fun run() {
        // пустой вызов без субкоманды до сюда не доходит: Main обрабатывает его раньше
    }
}

private fun runBuiltins(argv: Array<String>) {
    val root = TenguRootCommand()
    try {
        root.parse(argv.toList())
    } catch (e: PrintHelpMessage) {
        // clikt 5: текст справки не в e.message — форматируется из контекста команды, бросившей ошибку
        print(root.getFormattedHelp(e) ?: "")
    } catch (e: UsageError) {
        CliRuntime.fail(wrapClikt(usageText(e), argv))
    } catch (e: CliktError) {
        CliRuntime.fail(wrapClikt(e.message ?: "error", argv))
    }
}

/** Короткий текст ошибки использования: clikt 5 держит его в formatMessage подклассов, а не в message. */
private fun usageText(e: UsageError): String =
    e.message ?: e.context?.let { ctx -> e.formatMessage(ctx.localization, ParameterFormatter.Plain) } ?: "usage error"

private fun wrapClikt(message: String, argv: Array<String>): AxiErrorEnvelope = AxiErrorEnvelope(
    kind = ErrorKind.USAGE,
    message = message.lineSequence().firstOrNull { it.isNotBlank() } ?: "usage error",
    helpHints = listOf("Run `tengu ${argv.firstOrNull() ?: ""} --help` for usage"),
)
