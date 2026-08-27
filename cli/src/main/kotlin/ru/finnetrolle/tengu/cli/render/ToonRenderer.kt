package ru.finnetrolle.tengu.cli.render

import ru.finnetrolle.tengu.protocol.AxiErrorEnvelope
import ru.finnetrolle.tengu.protocol.InvokeResponse
import ru.finnetrolle.tengu.protocol.Manifest
import ru.finnetrolle.tengu.protocol.ToolDescriptor
import ru.finnetrolle.tengu.toon.Toon
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Рендер всего, что CLI выводит агенту, — только TOON на stdout (AXI §1, §6, §9).
 * help-хинты добавляются как help[N]-массив и кодируются тем же энкодером.
 */
object ToonRenderer {

    fun response(resp: InvokeResponse): String = Toon.encode(withHelp(resp.payload, resp.helpHints))

    fun error(e: AxiErrorEnvelope): String = Toon.encode(
        withHelp(buildJsonObject { put("error", JsonPrimitive(e.message)) }, e.helpHints),
    )

    fun withHelp(payload: JsonElement, hints: List<String>): JsonElement {
        if (hints.isEmpty()) return payload
        val obj = payload as? JsonObject ?: return payload
        return buildJsonObject {
            obj.forEach { (k, v) -> put(k, v) }
            putJsonArray("help") { hints.forEach { add(JsonPrimitive(it)) } }
        }
    }
}

/** Массив `tools{name,summary}` — общий элемент дашборда и `tengu tools list` (AXI §8). */
internal fun JsonObjectBuilder.putToolsArray(tools: List<ToolDescriptor>) {
    putJsonArray("tools") {
        tools.forEach { t ->
            addJsonObject {
                put("name", t.name)
                put("summary", t.summary)
            }
        }
    }
}

/** No-args дашборд: content-first, не мануал (AXI §8), с идентификацией тула (§10). */
object Dashboard {

    fun render(manifest: Manifest): String {
        val payload = buildJsonObject {
            put("bin", binPath())
            put("description", "Agent tool hub: discover and invoke corporate tools")
            putToolsArray(manifest.tools)
        }
        return Toon.encode(
            ToonRenderer.withHelp(
                payload,
                listOf(
                    "Run `tengu tools show <tool>` for what a tool can do",
                    "Run `tengu <tool> <command> --help` for command usage",
                ),
            ),
        )
    }

    /** MVP: TENGU_BIN или имя в PATH; абсолютный путь с ~ — полировка Phase 4. */
    fun binPath(): String = System.getenv("TENGU_BIN") ?: "tengu"
}

/** `tengu tools show <tool>`: возможности тула из кэша манифеста. */
object ToolDetail {

    fun render(tool: ToolDescriptor): String = renderCatalog(
        buildJsonObject {
            put("tool", buildJsonObject {
                put("name", tool.name)
                put("summary", tool.summary)
                if (tool.description.isNotEmpty()) put("description", tool.description)
            })
        },
        tool,
    )

    /**
     * Общий рендер справочника тула: шапка + каталог команд + hint (AXI §10).
     * Шапки различаются: `--help` тула — плоские поля, `tools show` — объект `tool`.
     */
    internal fun renderCatalog(head: JsonObject, tool: ToolDescriptor): String {
        val payload = buildJsonObject {
            head.forEach { (k, v) -> put(k, v) }
            putJsonArray("commands") {
                tool.commands.forEach { c ->
                    addJsonObject {
                        put("command", c.path.joinToString(" "))
                        put("summary", c.summary)
                    }
                }
            }
        }
        return Toon.encode(
            ToonRenderer.withHelp(payload, listOf("Run `tengu ${tool.name} <command> --help` for flags and examples")),
        )
    }
}
