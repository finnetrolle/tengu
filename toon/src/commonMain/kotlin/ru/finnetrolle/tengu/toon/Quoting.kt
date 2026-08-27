package ru.finnetrolle.tengu.toon

/**
 * Правила кавычек TOON: строка остаётся голой, если она «чистая» — без разделителя,
 * переводов строк, ведущих/хвостовых пробелов, не начинается с # и не выглядит как
 * число/булево/null (иначе агент прочтёт её не как строку). Вложенные кавычки в голой
 * строке допустимы (фиксировано golden-тестами).
 */
object Quoting {

    fun render(s: String, delimiter: Char): String = if (needsQuotes(s, delimiter)) quote(s) else s

    fun needsQuotes(s: String, delimiter: Char): Boolean {
        if (s.isEmpty()) return true
        if (s != s.trim()) return true
        if (s.startsWith("#")) return true
        if (containsSpecialChars(s, delimiter)) return true
        if (looksScalar(s)) return true
        return false
    }

    /** Разделитель и управляющие символы ломают позиционную структуру - требуют кавычек. */
    private fun containsSpecialChars(s: String, delimiter: Char): Boolean =
        s.any { it == delimiter || it == '\n' || it == '\r' || it == '\t' }

    private fun looksScalar(s: String): Boolean =
        s == "true" || s == "false" || s == "null" || s.toDoubleOrNull() != null

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
}
