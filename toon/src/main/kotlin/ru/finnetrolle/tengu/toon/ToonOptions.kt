package ru.finnetrolle.tengu.toon

data class ToonOptions(
    val indentSize: Int = 2,
    val delimiter: Char = ',',
    val maxInlineItems: Int = 8,
    val maxInlineWidth: Int = 100,
) {
    companion object {
        val DEFAULT = ToonOptions()
    }
}
