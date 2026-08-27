package ru.finnetrolle.tengu.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestRoundTripTest {

    @Test
    fun manifestRoundTripsThroughWireJson() {
        val manifest = Manifest(
            manifestVersion = 3,
            serverVersion = "0.1.0",
            tools = listOf(
                ToolDescriptor(
                    name = "jira",
                    summary = "Jira issues",
                    description = "Corporate Jira",
                    commands = listOf(
                        CommandDescriptor(
                            path = listOf("issues", "list"),
                            summary = "List issues",
                            longHelp = "Lists issues with a minimal default schema.",
                            args = listOf(ArgDescriptor("key", required = false)),
                            flags = listOf(
                                FlagDescriptor(
                                    name = "state",
                                    default = "open",
                                    allowedValues = listOf("open", "closed", "all"),
                                ),
                                FlagDescriptor(name = "limit", type = FlagType.INT, default = "50"),
                                FlagDescriptor(name = "token", secret = true),
                            ),
                            fields = listOf(FieldDescriptor("assignee", help = "who owns it")),
                            renamedFlags = mapOf("status" to "state"),
                            examples = listOf(UsageExample("tengu jira issues list --project FOO")),
                        ),
                    ),
                ),
                ToolDescriptor(name = "status", summary = "Hub health"),
            ),
        )

        val encoded = ProtocolJson.json.encodeToString(manifest)
        val decoded = ProtocolJson.json.decodeFromString<Manifest>(encoded)

        assertEquals(manifest, decoded)
        // компактность провода: дефолты не сериализуются
        assertTrue(encoded.contains("\"manifestVersion\""))
        assertTrue(!encoded.contains("\"kind\""))
    }
}
