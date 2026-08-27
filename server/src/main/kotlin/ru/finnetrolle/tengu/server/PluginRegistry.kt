package ru.finnetrolle.tengu.server

import ru.finnetrolle.tengu.protocol.Manifest
import ru.finnetrolle.tengu.toolkit.ToolPlugin

/** In-process реестр плагинов; mutable, чтобы Main мог собрать инструменты с взаимными ссылками. */
class PluginRegistry {
    private val plugins = mutableListOf<ToolPlugin>()

    val tools: List<ToolPlugin> get() = plugins.toList()

    fun register(plugin: ToolPlugin) {
        require(plugins.none { it.descriptor.name == plugin.descriptor.name }) {
            "duplicate tool name '${plugin.descriptor.name}'"
        }
        plugins += plugin
    }

    fun find(name: String): ToolPlugin? = plugins.firstOrNull { it.descriptor.name == name }

    fun manifest(manifestVersion: Int, serverVersion: String): Manifest =
        Manifest(manifestVersion, serverVersion, tools.map { it.descriptor })
}
