package ru.finnetrolle.tengu.toolkit

import ru.finnetrolle.tengu.protocol.ToolDescriptor

/** Мерж дефолтов флагов: дескриптор — единый источник правды для дефолтов. */
object FlagDefaults {

    /**
     * Возвращает [flags], дополненные дефолтами из декларации [descriptor] для
     * вызванной команды [commandPath]: отсутствующий флаг получает default
     * своего FlagDescriptor, явно заданные значения не перетираются, флаги без
     * дефолта не добавляются. Команда ищется по точному равенству path (как в
     * Validate.command); дефолты других команд не участвуют - у модели нет
     * наследования флагов от родительских групп, Validate.invoke допускает
     * только флаги самой команды. Неизвестный path - пустой мерж: [flags]
     * возвращаются без изменений. При одноимённых флагах внутри команды
     * побеждает первое объявление.
     */
    fun withDefaults(
        descriptor: ToolDescriptor,
        commandPath: List<String>,
        flags: Map<String, String>,
    ): Map<String, String> {
        val command = descriptor.commands.firstOrNull { it.path == commandPath } ?: return flags
        val missing = LinkedHashMap<String, String>()
        command.flags.forEach { flag ->
            val default = flag.default ?: return@forEach
            if (flag.name !in flags && flag.name !in missing) missing[flag.name] = default
        }
        return if (missing.isEmpty()) flags else flags + missing
    }
}
