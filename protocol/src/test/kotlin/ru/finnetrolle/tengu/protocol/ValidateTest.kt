package ru.finnetrolle.tengu.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ValidateTest {

    private val cmd = CommandDescriptor(
        path = listOf("issues", "list"),
        summary = "List issues",
        args = listOf(ArgDescriptor("key", required = false)),
        flags = listOf(
            FlagDescriptor(name = "state", default = "open", allowedValues = listOf("open", "closed", "all")),
            FlagDescriptor(name = "limit", type = FlagType.INT),
            FlagDescriptor(name = "title", required = true),
        ),
        renamedFlags = mapOf("status" to "state"),
    )

    private val tool = ToolDescriptor(name = "jira", summary = "Jira", commands = listOf(cmd))
    private val manifest = Manifest(manifestVersion = 1, serverVersion = "0.1.0", tools = listOf(tool))

    @Test
    fun unknownToolIsUsageError() {
        val e = assertNotNull(Validate.tool(manifest, "nosuch"))
        assertEquals(ErrorKind.USAGE, e.kind)
        assertEquals(2, e.kind.exitCode())
        assertEquals("available tools: jira", e.helpHints.single())
    }

    @Test
    fun unknownCommandIsUsageError() {
        val e = assertNotNull(Validate.command(tool, listOf("nosuch")))
        assertEquals(ErrorKind.USAGE, e.kind)
        assertEquals("unknown command 'nosuch' for tool 'jira'", e.message)
    }

    @Test
    fun unknownFlagListsValidFlagsInline() {
        val e = assertNotNull(
            Validate.invoke("jira", cmd, args = emptyMap(), flags = mapOf("stat" to "open", "title" to "x")),
        )
        assertEquals(ErrorKind.USAGE, e.kind)
        assertEquals("unknown flag --stat for `jira issues list`", e.message)
        assertEquals(
            "valid flags for `jira issues list`: --state, --limit, --title (--help always allowed)",
            e.helpHints.single(),
        )
    }

    @Test
    fun renamedFlagGetsTargetedHint() {
        val e = assertNotNull(
            Validate.invoke("jira", cmd, args = emptyMap(), flags = mapOf("status" to "open", "title" to "x")),
        )
        assertEquals("--status was renamed; use --state instead", e.message)
    }

    @Test
    fun missingRequiredFlag() {
        val e = assertNotNull(Validate.invoke("jira", cmd, args = emptyMap(), flags = emptyMap()))
        assertEquals("--title is required", e.message)
    }

    @Test
    fun badIntRejected() {
        val e = assertNotNull(
            Validate.invoke("jira", cmd, args = emptyMap(), flags = mapOf("limit" to "abc", "title" to "x")),
        )
        assertEquals("--limit expects an integer, got \"abc\"", e.message)
    }

    @Test
    fun badEnumValueRejected() {
        val e = assertNotNull(
            Validate.invoke("jira", cmd, args = emptyMap(), flags = mapOf("state" to "wip", "title" to "x")),
        )
        assertEquals("--state must be one of: open, closed, all", e.message)
    }

    @Test
    fun validInvokePasses() {
        assertNull(
            Validate.invoke(
                "jira", cmd,
                args = mapOf("key" to "FOO-1"),
                flags = mapOf("state" to "closed", "limit" to "10", "title" to "x"),
            )
        )
    }
}
