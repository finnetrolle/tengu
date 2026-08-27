package ru.finnetrolle.tengu.toon

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden-тесты фиксируют поведение энкодера (TOON-спека — working draft,
 * поведение pinned файлами, а не внешней зависимостью).
 */
class ToonGoldenTest {

    @Test
    fun goldenFixturesMatch() {
        val goldenDir = Paths.get(javaClass.classLoader.getResource("golden")!!.toURI())
        Files.list(goldenDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }.sorted().forEach { jsonFile ->
                val name = jsonFile.fileName.toString().removeSuffix(".json")
                val toonFile = jsonFile.resolveSibling("$name.toon")
                val input = Json.parseToJsonElement(Files.readString(jsonFile))
                val expected = Files.readString(toonFile)
                assertEquals(expected, Toon.encode(input), "fixture $name")
            }
        }
    }
}
