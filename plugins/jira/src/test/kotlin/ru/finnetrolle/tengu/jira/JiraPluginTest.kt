package ru.finnetrolle.tengu.jira

import ru.finnetrolle.tengu.toolkit.AxiResult
import ru.finnetrolle.tengu.toolkit.FakeSecretScope
import ru.finnetrolle.tengu.toolkit.FlagDefaults
import ru.finnetrolle.tengu.toolkit.InvocationContext
import ru.finnetrolle.tengu.toolkit.SecretScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JiraPluginTest {

    private val plugin = JiraPlugin("https://jira.corp")

    private val myself = """{"name":"jdoe","displayName":"John Doe"}"""
    private val searchTwoPages = """
        {"startAt":0,"maxResults":50,"total":213,"issues":[
          {"key":"FOO-1","fields":{"summary":"Fix auth bug","status":{"name":"Open"}}},
          {"key":"FOO-2","fields":{"summary":"Add pagination","status":{"name":"Closed"}}}
        ]}
    """.trimIndent()
    private val searchEmpty = """{"startAt":0,"maxResults":50,"total":0,"issues":[]}"""

    private fun issue(description: String): String = """
        {"key":"FOO-1","fields":{
          "summary":"Fix auth bug",
          "status":{"name":"Open"},
          "assignee":{"displayName":"Alice"},
          "reporter":{"displayName":"Bob"},
          "priority":{"name":"P1"},
          "updated":"2026-08-27T10:00:00.000+0000",
          "description":${JsonPrimitive(description)},
          "comment":{"total":7,"comments":[
            {"author":{"displayName":"Alice"},"body":"first comment"},
            {"author":{"displayName":"Bob"},"body":"second comment"}
          ]}
        }}
    """.trimIndent()

    private fun jiraMock(handler: (path: String) -> Pair<HttpStatusCode, String>): HttpClient =
        HttpClient(MockEngine { request ->
            val (status, body) = handler(request.url.encodedPath)
            respond(
                body,
                status,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

    private fun ctx(
        httpClient: HttpClient,
        scope: SecretScope = FakeSecretScope(),
        args: Map<String, String> = emptyMap(),
        flags: Map<String, String> = emptyMap(),
    ) = InvocationContext("jira", "dev", args, flags, scope, httpClient, Clock.systemUTC())

    /**
     * Флаги в том виде, в каком их видит плагин: сервер мержит дефолты декларации
     * вызванной команды до invoke.
     */
    private fun flags(commandPath: List<String>, vararg pairs: Pair<String, String>): Map<String, String> =
        FlagDefaults.withDefaults(plugin.descriptor, commandPath, mapOf(*pairs))

    private fun patScope(pat: String? = "secret-pat-1234"): SecretScope = runBlocking {
        FakeSecretScope().also { if (pat != null) it.put("pat", pat) }
    }

    @Test
    fun authLoginVerifiesAndStoresPat() = runBlocking {
        val scope = FakeSecretScope()
        val result = plugin.invoke(
            listOf("auth", "login"),
            ctx(jiraMock { OK(myself) }, scope, flags = mapOf("token" to "new-pat-9999")),
        )
        assertTrue(result is AxiResult.Ok, "expected Ok, got $result")
        assertEquals("new-pat-9999", scope.get("pat"))
        val payload = (result as AxiResult.Ok).payload as JsonObject
        assertEquals("PAT stored for jira (John Doe <jdoe>)", (payload["auth"] as JsonPrimitive).content)
    }

    @Test
    fun authLoginRejectedTokenIsAuthError() = runBlocking {
        val result = plugin.invoke(
            listOf("auth", "login"),
            ctx(jiraMock { HttpStatusCode.Unauthorized to """{"error":{}}""" }, flags = mapOf("token" to "bad")),
        )
        val err = result as AxiResult.Err
        assertEquals("PAT was rejected by jira", err.error.message)
        assertTrue(err.error.helpHints.single().startsWith("Check the token"))
    }

    @Test
    fun authStatusWithoutPatIsDefinitiveEmpty() = runBlocking {
        val result = plugin.invoke(listOf("auth", "status"), ctx(jiraMock { OK(it) }, patScope(null)))
        val ok = result as AxiResult.Ok
        val payload = ok.payload as JsonObject
        assertEquals("no PAT configured for jira", (payload["auth"] as JsonPrimitive).content)
        assertTrue(ok.helpHints.single().startsWith("Run `tengu jira auth login"))
    }

    @Test
    fun authLogoutTwiceIsNoop() = runBlocking {
        val scope = patScope()
        assertTrue(plugin.invoke(listOf("auth", "logout"), ctx(jiraMock { OK(it) }, scope)) is AxiResult.Ok)
        val second = plugin.invoke(listOf("auth", "logout"), ctx(jiraMock { OK(it) }, scope))
        assertTrue(second is AxiResult.Noop)
        assertEquals("no PAT configured for jira - nothing to remove", (second as AxiResult.Noop).message)
    }

    @Test
    fun issuesListAggregatesAndHintsNextPage() = runBlocking {
        val result = plugin.invoke(
            listOf("issues", "list"),
            ctx(
                jiraMock { path ->
                    assertTrue(path.startsWith("/rest/api/2/search"))
                    OK(searchTwoPages)
                },
                patScope(),
                flags = flags(listOf("issues", "list"), "project" to "FOO"),
            ),
        )
        val ok = result as AxiResult.Ok
        val payload = ok.payload as JsonObject
        assertEquals("2 of 213 total", (payload["count"] as JsonPrimitive).content)
        val issues = payload["issues"] as JsonArray
        assertEquals(2, issues.size)
        assertEquals(
            listOf("key", "title", "state"),
            (issues.first() as JsonObject).keys.toList(),
            "default schema is minimal (AXI §2)",
        )
        assertTrue(ok.helpHints.single().contains("--start-at 2"), "next page hint carries the offset")
    }

    @Test
    fun issuesListEmptyIsDefinitive() = runBlocking {
        val result = plugin.invoke(
            listOf("issues", "list"),
            ctx(
                jiraMock { OK(searchEmpty) },
                patScope(),
                flags = flags(listOf("issues", "list"), "project" to "FOO", "state" to "closed"),
            ),
        )
        val ok = result as AxiResult.Ok
        val payload = ok.payload as JsonObject
        assertEquals("0 issues found (project FOO, state closed)", (payload["issues"] as JsonPrimitive).content)
        assertTrue(ok.helpHints.isEmpty())
    }

    @Test
    fun issuesViewTruncatesWithEscapeHint() = runBlocking {
        val long = "x".repeat(2000)
        val result = plugin.invoke(
            listOf("issues", "view"),
            ctx(jiraMock { OK(issue(long)) }, patScope(), args = mapOf("key" to "FOO-1")),
        )
        val ok = result as AxiResult.Ok
        val issueObj = (ok.payload as JsonObject)["issue"] as JsonObject
        val desc = (issueObj["description"] as JsonPrimitive).content
        assertTrue(desc.endsWith("(truncated, 2000 chars total)"))
        assertEquals(7, (issueObj["comments"] as JsonPrimitive).content.toInt(), "comment count aggregate (§4)")
        assertTrue(ok.helpHints.single().contains("--full"))
    }

    @Test
    fun issuesViewFullSkipsHintWhenNotTruncated() = runBlocking {
        val result = plugin.invoke(
            listOf("issues", "view"),
            ctx(
                jiraMock { OK(issue("short body")) },
                patScope(),
                args = mapOf("key" to "FOO-1"),
                flags = mapOf("full" to "true"),
            ),
        )
        val ok = result as AxiResult.Ok
        assertTrue(ok.helpHints.isEmpty(), "hint only when actually truncated (§3)")
        val issueObj = (ok.payload as JsonObject)["issue"] as JsonObject
        assertEquals("short body", (issueObj["description"] as JsonPrimitive).content)
    }

    @Test
    fun upstream401TranslatesToAuthError() = runBlocking {
        val result = plugin.invoke(
            listOf("issues", "list"),
            ctx(
                jiraMock { HttpStatusCode.Unauthorized to "{}" },
                patScope(),
                flags = flags(listOf("issues", "list")),
            ),
        )
        val err = result as AxiResult.Err
        assertEquals(ru.finnetrolle.tengu.protocol.ErrorKind.AUTH, err.error.kind)
        assertEquals("stored PAT was rejected by jira", err.error.message)
    }

    @Test
    fun missingPatTranslatesToAuthHint() = runBlocking {
        val result = plugin.invoke(listOf("issues", "list"), ctx(jiraMock { OK(it) }, patScope(null)))
        val err = result as AxiResult.Err
        assertEquals("no PAT configured for jira", err.error.message)
        assertTrue(err.error.helpHints.single().startsWith("Run `tengu jira auth login"))
    }

    @Test
    fun issuesCreateReturnsKeyWithoutUpstreamUrl() = runBlocking {
        val result = plugin.invoke(
            listOf("issues", "create"),
            ctx(
                jiraMock { OK("""{"key":"FOO-42","self":"https://jira.corp/rest/api/2/issue/1"}""") },
                patScope(),
                flags = flags(listOf("issues", "create"), "project" to "FOO", "title" to "Fix login"),
            ),
        )
        val ok = result as AxiResult.Ok
        val issueObj = (ok.payload as JsonObject)["issue"] as JsonObject
        assertEquals("FOO-42", (issueObj["key"] as JsonPrimitive).content)
        assertFalse(issueObj.containsKey("url"), "plugin contract forbids upstream URLs in output")
        assertFalse(ok.helpHints.isEmpty())
    }

    @Test
    fun issuesCommentsExcerptCarriesTruncationMarkerAndHint() = runBlocking {
        val longBody = "y".repeat(250)
        val oneLongComment = """
            {"key":"FOO-1","fields":{"summary":"s","comment":{"total":1,"comments":[
              {"author":{"displayName":"Alice"},"body":${JsonPrimitive(longBody)}}
            ]}}}
        """.trimIndent()
        val result = plugin.invoke(
            listOf("issues", "comments"),
            ctx(
                jiraMock { OK(oneLongComment) },
                patScope(),
                args = mapOf("key" to "FOO-1"),
                flags = flags(listOf("issues", "comments")),
            ),
        )
        val ok = result as AxiResult.Ok
        val comments = (ok.payload as JsonObject)["comments"] as JsonArray
        val excerpt = ((comments.single() as JsonObject)["excerpt"] as JsonPrimitive).content
        assertTrue(
            excerpt.endsWith("... (truncated, 250 chars total)"),
            "excerpt carries the truncation marker (AXI §3)",
        )
        assertTrue(ok.helpHints.single().contains("no full-comment view"), "hint explains the missing full view")
    }

    private fun OK(body: String): Pair<HttpStatusCode, String> = HttpStatusCode.OK to body
}
