package ru.finnetrolle.tengu.server.tools

import ru.finnetrolle.tengu.protocol.CommandDescriptor
import ru.finnetrolle.tengu.protocol.ToolDescriptor
import ru.finnetrolle.tengu.protocol.UsageExample
import ru.finnetrolle.tengu.toolkit.AxiResult
import ru.finnetrolle.tengu.toolkit.InvocationContext
import ru.finnetrolle.tengu.toolkit.ToolPlugin
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Duration
import java.time.Instant

/** Первый плагин-диагност: без секретов и апстримов, обкатывает контракт end-to-end. */
class StatusTool(
    private val serverVersion: String,
    private val startedAt: Instant,
    private val manifestVersion: Int,
    private val toolCount: () -> Int,
    private val toolSummaries: () -> List<Pair<String, String>>,
) : ToolPlugin {

    override val descriptor = ToolDescriptor(
        name = "status",
        summary = "Hub health and diagnostics",
        description = "Server version, uptime, manifest version and the list of registered tools.",
        commands = listOf(
            CommandDescriptor(
                path = listOf("status"),
                summary = "Show server version, uptime and tool count",
                longHelp = "Full roundtrip check: version, uptime, manifest version, registered tools.",
                examples = listOf(UsageExample("tengu status", "full roundtrip check")),
            ),
        ),
    )

    override suspend fun invoke(commandPath: List<String>, ctx: InvocationContext): AxiResult {
        val uptime = Duration.between(startedAt, Instant.now(ctx.clock))
        val payload = buildJsonObject {
            put("server", buildJsonObject {
                put("version", serverVersion)
                put("uptime", formatDuration(uptime))
                put("tools", toolCount())
                put("manifestVersion", manifestVersion)
            })
            putJsonArray("tools") {
                toolSummaries().forEach { (name, summary) ->
                    addJsonObject {
                        put("name", name)
                        put("summary", summary)
                    }
                }
            }
        }
        return AxiResult.Ok(payload, listOf("Run `tengu tools show <tool>` for a tool's commands"))
    }

    private fun formatDuration(d: Duration): String {
        val days = d.toDays()
        val hours = d.toHoursPart()
        val minutes = d.toMinutesPart()
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }
}
