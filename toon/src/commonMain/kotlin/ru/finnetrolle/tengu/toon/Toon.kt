package ru.finnetrolle.tengu.toon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * TOON-энкодер: JsonElement → TOON (toonformat.dev, поведение зафиксировано golden-тестами).
 * Чистая функция на выходной границе — truncation/aggregates/empty-states кладут
 * в JsonElement сами плагины (через AxiPayloads), энкодер их не знает.
 *
 * Формы массивов:
 *  - inline:      `key[3]: a,b,c` — только примитивы, коротко
 *  - block:       `key[N]:` + по элементу на строку — примитивы, не влезающие в inline
 *  - табличная:   `key[N]{f1,f2}:` + строки-значения — объекты с одинаковым набором ключей
 *  - list-форма:  `key[N]:` + `- элемент` — смешанное/вложенное
 */
object Toon {

    fun encode(value: JsonElement, options: ToonOptions = ToonOptions.DEFAULT): String {
        val sb = StringBuilder()
        writeElement(sb, value, 0, options)
        return sb.toString()
    }

    private fun writeElement(sb: StringBuilder, value: JsonElement, level: Int, opts: ToonOptions) {
        when (value) {
            is JsonObject -> value.forEach { (k, v) -> writeMember(sb, k, v, level, opts) }
            else -> sb.append(indent(level, opts)).append(scalar(value, opts)).append('\n')
        }
    }

    private fun writeMember(sb: StringBuilder, key: String, value: JsonElement, level: Int, opts: ToonOptions) {
        val ind = indent(level, opts)
        when (value) {
            is JsonObject -> {
                if (value.isEmpty()) {
                    sb.append(ind).append(key).append(": {}\n")
                } else {
                    sb.append(ind).append(key).append(":\n")
                    writeElement(sb, value, level + 1, opts)
                }
            }
            is JsonArray -> writeArray(sb, key, value, level, opts)
            else -> sb.append(ind).append(key).append(": ").append(scalar(value, opts)).append('\n')
        }
    }

    private fun writeArray(sb: StringBuilder, key: String, arr: JsonArray, level: Int, opts: ToonOptions) {
        if (arr.isEmpty()) {
            sb.append(indent(level, opts)).append(key).append("[0]:\n")
            return
        }

        // только примитивы → inline или block
        if (arr.all { it !is JsonObject && it !is JsonArray }) {
            writePrimitivesArray(sb, key, arr, level, opts)
            return
        }

        // табличная форма: все элементы — объекты с одинаковым набором ключей и простыми значениями
        val objects = arr.filterIsInstance<JsonObject>()
        val keys = if (objects.size == arr.size) tabularKeys(objects) else null
        if (keys == null) {
            writeListArray(sb, key, arr, level, opts)
        } else {
            writeTabularArray(sb, key, arr, objects, keys, level, opts)
        }
    }

    private fun writePrimitivesArray(
        sb: StringBuilder,
        key: String,
        arr: JsonArray,
        level: Int,
        opts: ToonOptions,
    ) {
        val ind = indent(level, opts)
        val d = opts.delimiter.toString()
        val rendered = arr.map { scalar(it, opts) }
        val inline = "$ind$key[${arr.size}]: " + rendered.joinToString(d)
        if (arr.size <= opts.maxInlineItems && inline.length <= opts.maxInlineWidth) {
            sb.append(inline).append('\n')
        } else {
            sb.append(ind).append(key).append('[').append(arr.size).append("]:\n")
            val child = indent(level + 1, opts)
            rendered.forEach { sb.append(child).append(it).append('\n') }
        }
    }

    /** Ключи табличной формы, либо null, если набор не одинаков или значения не простые. */
    private fun tabularKeys(objects: List<JsonObject>): List<String>? {
        val keys = objects.first().keys.toList()
        val uniform = keys.isNotEmpty() && objects.all { o ->
            o.size == keys.size && keys.all { o.containsKey(it) } &&
                o.values.all { v -> v !is JsonObject && v !is JsonArray }
        }
        return if (uniform) keys else null
    }

    private fun writeTabularArray(
        sb: StringBuilder,
        key: String,
        arr: JsonArray,
        objects: List<JsonObject>,
        keys: List<String>,
        level: Int,
        opts: ToonOptions,
    ) {
        val child = indent(level + 1, opts)
        val d = opts.delimiter.toString()
        sb.append(indent(level, opts)).append(key).append('[').append(arr.size).append("]{")
            .append(keys.joinToString(d)).append("}:\n")
        objects.forEach { o ->
            sb.append(child).append(keys.joinToString(d) { k -> scalar(o[k]!!, opts) }).append('\n')
        }
    }

    // list-форма
    private fun writeListArray(sb: StringBuilder, key: String, arr: JsonArray, level: Int, opts: ToonOptions) {
        val child = indent(level + 1, opts)
        sb.append(indent(level, opts)).append(key).append('[').append(arr.size).append("]:\n")
        arr.forEach { item ->
            when (item) {
                is JsonObject -> {
                    val body = StringBuilder()
                    writeElement(body, item, level + 2, opts)
                    val lines = body.toString().removeSuffix("\n").split('\n')
                    lines.forEachIndexed { i, line ->
                        if (i == 0) sb.append(child).append("- ").append(line.substring((level + 2) * opts.indentSize))
                        else sb.append(line)
                        sb.append('\n')
                    }
                }
                is JsonArray -> {
                    // вложенный массив внутри list-формы: его элементы уходит на уровень глубже
                    val body = StringBuilder()
                    writeElement(body, JsonObject(mapOf("item" to item)), level + 2, opts)
                    sb.append(child).append("- ").append(body.toString().substring((level + 2) * opts.indentSize))
                }
                else -> sb.append(child).append("- ").append(scalar(item, opts)).append('\n')
            }
        }
    }

    private fun scalar(value: JsonElement, opts: ToonOptions): String {
        val p = value as JsonPrimitive
        return if (p.isString) Quoting.render(p.content, opts.delimiter) else p.content
    }

    private fun indent(level: Int, opts: ToonOptions): String = " ".repeat(level * opts.indentSize)
}
