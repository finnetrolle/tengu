package ru.finnetrolle.tengu.toolkit

import ru.finnetrolle.tengu.protocol.CommandDescriptor
import ru.finnetrolle.tengu.protocol.FlagDescriptor
import ru.finnetrolle.tengu.protocol.ToolDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FlagDefaultsTest {

    private val listPath = listOf("issues", "list")
    private val commentsPath = listOf("issues", "comments")

    private val tool = ToolDescriptor(
        name = "jira",
        summary = "Jira",
        commands = listOf(
            CommandDescriptor(
                path = listPath,
                summary = "List issues",
                flags = listOf(
                    FlagDescriptor(name = "limit", default = "50"),
                    FlagDescriptor(name = "state"),
                    FlagDescriptor(name = "max", default = "100"),
                ),
            ),
            CommandDescriptor(
                path = commentsPath,
                summary = "List comments",
                flags = listOf(FlagDescriptor(name = "limit", default = "20")),
            ),
        ),
    )

    @Test
    fun defaultsResolvePerCommand() {
        val list = FlagDefaults.withDefaults(tool, listPath, emptyMap())
        assertEquals(mapOf("limit" to "50", "max" to "100"), list)
        val comments = FlagDefaults.withDefaults(tool, commentsPath, emptyMap())
        assertEquals(
            mapOf("limit" to "20"),
            comments,
            "same flag name takes the default of the invoked command, other commands do not leak",
        )
    }

    @Test
    fun missingFlagGetsItsDefault() {
        val out = FlagDefaults.withDefaults(tool, listPath, mapOf("state" to "open"))
        assertEquals("50", out["limit"])
        assertEquals("open", out["state"])
    }

    @Test
    fun presentFlagIsNotOverridden() {
        val out = FlagDefaults.withDefaults(tool, listPath, mapOf("limit" to "10", "max" to "5"))
        assertEquals("10", out["limit"])
        assertEquals("5", out["max"])
    }

    @Test
    fun flagWithoutDefaultIsNotAdded() {
        val out = FlagDefaults.withDefaults(tool, listPath, emptyMap())
        assertEquals(mapOf("limit" to "50", "max" to "100"), out)
        assertNull(out["state"])
        assertFalse(out.containsKey("state"))
    }

    @Test
    fun unknownCommandPathAppliesNoDefaults() {
        val out = FlagDefaults.withDefaults(tool, listOf("nope"), mapOf("limit" to "7"))
        assertEquals(mapOf("limit" to "7"), out)
        assertEquals(emptyMap<String, String>(), FlagDefaults.withDefaults(tool, listOf("nope"), emptyMap()))
    }
}
