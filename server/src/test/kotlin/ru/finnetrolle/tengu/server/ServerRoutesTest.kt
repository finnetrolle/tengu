package ru.finnetrolle.tengu.server

import ru.finnetrolle.tengu.protocol.AxiErrorEnvelope
import ru.finnetrolle.tengu.protocol.ErrorKind
import ru.finnetrolle.tengu.protocol.InvokeRequest
import ru.finnetrolle.tengu.protocol.InvokeResponse
import ru.finnetrolle.tengu.protocol.Manifest
import ru.finnetrolle.tengu.protocol.ProtocolJson
import ru.finnetrolle.tengu.server.tools.StatusTool
import ru.finnetrolle.tengu.toolkit.FakeSecretScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerRoutesTest {

    private fun withServer(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            val registry = PluginRegistry()
            registry.register(
                StatusTool(
                    serverVersion = "0.1.0-test",
                    startedAt = Instant.EPOCH,
                    manifestVersion = 1,
                    toolCount = { registry.tools.size },
                    toolSummaries = { registry.tools.map { it.descriptor.name to it.descriptor.summary } },
                ),
            )
            tenguModule(
                ServerDeps(
                    registry = registry,
                    auth = StaticTokenHubAuth(mapOf("h-dev123" to "dev")),
                    httpClient = HttpClient(CIO),
                    clock = Clock.systemUTC(),
                    startedAt = Instant.EPOCH,
                    serverVersion = "0.1.0-test",
                    manifestVersion = 1,
                    secretsScopeFor = { _, _ -> FakeSecretScope() },
                ),
            )
        }
        test()
    }

    private suspend fun ApplicationTestBuilder.authedInvoke(body: String, manifestVersion: Int? = null) =
        client.post("/v1/invoke") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer h-dev123")
            manifestVersion?.let { header("X-Tengu-Manifest-Version", it.toString()) }
            setBody(body)
        }

    @Test
    fun healthIsOpen() = withServer {
        val resp = client.get("/v1/health")
        assertEquals(200, resp.status.value)
        assertTrue(resp.bodyAsText().contains("\"serverVersion\":\"0.1.0-test\""))
    }

    @Test
    fun healthReportsUptimeSec() = withServer {
        val resp = client.get("/v1/health")
        val uptimeSec = ProtocolJson.json.parseToJsonElement(resp.bodyAsText())
            .jsonObject["uptimeSec"]?.jsonPrimitive?.content?.toLongOrNull()
        assertNotNull(uptimeSec, "health must report uptimeSec")
        assertTrue(uptimeSec >= 0L)
    }

    @Test
    fun manifestRequiresAuth() = withServer {
        val resp = client.get("/v1/manifest")
        assertEquals(401, resp.status.value)
        val envelope = ProtocolJson.json.decodeFromString<AxiErrorEnvelope>(resp.bodyAsText())
        assertEquals(ErrorKind.AUTH, envelope.kind)
        assertTrue(envelope.helpHints.single().startsWith("Run `tengu setup"))
    }

    @Test
    fun manifestListsTools() = withServer {
        val resp = client.get("/v1/manifest") { header(HttpHeaders.Authorization, "Bearer h-dev123") }
        assertEquals(200, resp.status.value)
        assertEquals("0.1.0-test", resp.headers["X-Tengu-Server-Version"])
        val manifest = ProtocolJson.json.decodeFromString<Manifest>(resp.bodyAsText())
        assertEquals(listOf("status"), manifest.tools.map { it.name })
        assertEquals(listOf("status"), manifest.tools.single().commands.single().path)
    }

    @Test
    fun invokeStatusRoundtrip() = withServer {
        val resp = authedInvoke(ProtocolJson.json.encodeToString(InvokeRequest("status", listOf("status"))))
        assertEquals(200, resp.status.value)
        val invoke = ProtocolJson.json.decodeFromString<InvokeResponse>(resp.bodyAsText())
        assertEquals(0, invoke.exitCode)
        assertTrue(invoke.payload.toString().contains("0.1.0-test"))
        assertTrue(invoke.helpHints.isNotEmpty())
    }

    @Test
    fun invokeUnknownToolIsUsage() = withServer {
        val resp = authedInvoke(ProtocolJson.json.encodeToString(InvokeRequest("nosuch", listOf("x"))))
        assertEquals(400, resp.status.value)
        val envelope = ProtocolJson.json.decodeFromString<AxiErrorEnvelope>(resp.bodyAsText())
        assertEquals(ErrorKind.USAGE, envelope.kind)
        assertEquals("available tools: status", envelope.helpHints.single())
    }

    @Test
    fun invokeUnknownFlagIsUsage() = withServer {
        val resp = authedInvoke(
            ProtocolJson.json.encodeToString(InvokeRequest("status", listOf("status"), flags = mapOf("bogus" to "1"))),
        )
        assertEquals(400, resp.status.value)
        val envelope = ProtocolJson.json.decodeFromString<AxiErrorEnvelope>(resp.bodyAsText())
        assertEquals(ErrorKind.USAGE, envelope.kind)
        assertEquals("unknown flag --bogus for `status status`", envelope.message)
    }

    @Test
    fun staleManifestHeaderIs409() = withServer {
        val resp = authedInvoke(
            ProtocolJson.json.encodeToString(InvokeRequest("status", listOf("status"))),
            manifestVersion = 0,
        )
        assertEquals(409, resp.status.value)
        val envelope = ProtocolJson.json.decodeFromString<AxiErrorEnvelope>(resp.bodyAsText())
        assertEquals(ErrorKind.STALE_MANIFEST, envelope.kind)
        assertTrue(envelope.retryable)
    }
}
