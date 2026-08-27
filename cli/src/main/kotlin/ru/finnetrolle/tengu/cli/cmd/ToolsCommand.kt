package ru.finnetrolle.tengu.cli.cmd

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import ru.finnetrolle.tengu.cli.CliRuntime
import ru.finnetrolle.tengu.cli.render.ToolDetail
import ru.finnetrolle.tengu.cli.render.ToonRenderer
import ru.finnetrolle.tengu.cli.render.putToolsArray
import ru.finnetrolle.tengu.protocol.Validate
import ru.finnetrolle.tengu.toon.Toon
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `tengu tools` / `tengu tools list`: каталог тулов из кэша манифеста. */
class ToolsCommand : CliktCommand(name = "tools") {

    override val invokeWithoutSubcommand = true
    override fun help(context: Context) = "List available tools or show what a tool can do"

    init {
        subcommands(ToolsListCommand(), ToolsShowCommand())
    }

    override fun run() {
        if (currentContext.invokedSubcommand == null) ToolsListCommand().run()
    }
}

/** `tengu tools list`. */
class ToolsListCommand : CliktCommand(name = "list") {

    override fun help(context: Context) = "List available tools"

    override fun run() {
        val config = CliRuntime.requireConfig()
        val manifest = CliRuntime.ensureManifest(config)
        val payload = buildJsonObject {
            put("count", "${manifest.tools.size} tools")
            putToolsArray(manifest.tools)
        }
        echo(Toon.encode(ToonRenderer.withHelp(payload, listOf("Run `tengu tools show <tool>` for details"))))
    }
}

/** `tengu tools show <tool>`: команды и возможности конкретного тула. */
class ToolsShowCommand : CliktCommand(name = "show") {

    override fun help(context: Context) = "Show what a tool can do"

    private val tool by argument(help = "Tool name, e.g. jira")

    override fun run() {
        val config = CliRuntime.requireConfig()
        val manifest = CliRuntime.ensureManifest(config)
        val descriptor = manifest.tool(tool) ?: CliRuntime.fail(Validate.tool(manifest, tool)!!)
        echo(ToolDetail.render(descriptor))
    }
}
