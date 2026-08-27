package ru.finnetrolle.tengu.protocol

import kotlinx.serialization.Serializable

/** Тип значения флага. Все значения по проводу — строки, тип проверяет validate(). */
@Serializable
enum class FlagType { STRING, INT, BOOL }

/** OPTION принимает значение (--x v), SWITCH — булев переключатель (--x). */
@Serializable
enum class FlagKind { OPTION, SWITCH }

@Serializable
data class FlagDescriptor(
    val name: String,                        // без дефисов: "state"
    val type: FlagType = FlagType.STRING,
    val kind: FlagKind = FlagKind.OPTION,
    val required: Boolean = false,
    val default: String? = null,
    val allowedValues: List<String>? = null,
    val secret: Boolean = false,             // редактируется в логах и ошибках
    val help: String = "",
)

@Serializable
data class ArgDescriptor(
    val name: String,                        // "key"
    val required: Boolean = true,
)

/** Поле спискового представления — словарь для --fields. */
@Serializable
data class FieldDescriptor(
    val name: String,
    val help: String = "",
    val inDefault: Boolean = false,
)

@Serializable
data class UsageExample(
    val command: String,                     // "tengu jira issues list --project FOO"
    val what: String = "",
)

/** Команда тула; путь иерархический: ["issues", "list"]. */
@Serializable
data class CommandDescriptor(
    val path: List<String>,
    val summary: String,
    val longHelp: String = "",
    val args: List<ArgDescriptor> = emptyList(),
    val flags: List<FlagDescriptor> = emptyList(),
    val fields: List<FieldDescriptor> = emptyList(),
    val renamedFlags: Map<String, String> = emptyMap(),   // старое → новое, точечные хинты
    val examples: List<UsageExample> = emptyList(),
)

@Serializable
data class ToolDescriptor(
    val name: String,                        // "jira"
    val summary: String,                     // одна строка для списков
    val description: String = "",            // длиннее, для `tools show`
    val commands: List<CommandDescriptor> = emptyList(),
)

/**
 * Каталог поверхности всех тулов. Отдаётся агенту целиком (GET /v1/manifest),
 * кэшируется CLI и служит входом для локальной валидации и --help.
 */
@Serializable
data class Manifest(
    val manifestVersion: Int,                // бампируется при ЛЮБОМ изменении поверхности
    val serverVersion: String,
    val tools: List<ToolDescriptor> = emptyList(),
) {
    fun tool(name: String): ToolDescriptor? = tools.firstOrNull { it.name == name }

    fun command(toolName: String, path: List<String>): CommandDescriptor? =
        tool(toolName)?.commands?.firstOrNull { it.path == path }
}
