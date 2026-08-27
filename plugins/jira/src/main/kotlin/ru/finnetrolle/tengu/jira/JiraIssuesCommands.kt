package ru.finnetrolle.tengu.jira

import ru.finnetrolle.tengu.protocol.ErrorKind
import ru.finnetrolle.tengu.toolkit.AxiPayloads
import ru.finnetrolle.tengu.toolkit.AxiResult
import ru.finnetrolle.tengu.toolkit.InvocationContext
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Команды issues: список с минимальной схемой, детали с усечением описания, создание и комментирование. */
internal class JiraIssuesCommands(
    private val client: JiraApiClient,
) {

    suspend fun list(ctx: InvocationContext): AxiResult {
        val pat = ctx.secrets.get("pat") ?: return errNoPat()
        val jql = buildJql(ctx.flags)
        val limit = ctx.flags.getValue("limit").toInt()
        val startAt = ctx.flags.getValue("start-at").toInt()
        val extra = (ctx.flags["fields"] ?: "")
            .split(',').map { it.trim() }.filter { it in EXTRA_FIELDS }

        val result = try {
            client.search(pat, jql, startAt, limit, (listOf("summary", "status") + extra).distinct())
        } catch (e: JiraApiError) {
            return translate(e)
        }
        val total = result.int("total") ?: 0
        val issues = (result["issues"] as? JsonArray) ?: JsonArray(emptyList())
        val rows = issues.map { issueRow(it as JsonObject, extra) }

        val context = listOfNotNull(
            ctx.flags["project"]?.let { "project $it" },
            "state ${ctx.flags.getValue("state")}",
        ).joinToString(", ")
        val nextFrom = startAt + issues.size
        val nextHint =
            if (nextFrom < total) "Run `${nextListCommand(ctx.flags, nextFrom)}` for the next page" else null

        return AxiPayloads.listOfItems(
            noun = "issues",
            rows = rows,
            page = issues.size,
            total = total,
            emptyPhrase = "0 issues found ($context)",
            nextHint = nextHint,
        )
    }

    suspend fun view(ctx: InvocationContext): AxiResult {
        val pat = ctx.secrets.get("pat") ?: return errNoPat()
        val key = ctx.args.getValue("key")
        val full = ctx.flags["full"] == "true"

        val issue = try {
            client.issue(pat, key)
        } catch (e: JiraApiError) {
            return translate(e, key)
        }
        val fields = issue["fields"] as? JsonObject ?: JsonObject(emptyMap())
        val description = fields.str("description") ?: ""
        val descriptionEl: JsonElement =
            if (full || description.length <= TRUNCATE_AT) JsonPrimitive(description.ifEmpty { "-" })
            else AxiPayloads.truncatedPreview(description, TRUNCATE_AT)
        val hints = if (!full && description.length > TRUNCATE_AT) {
            listOf(AxiPayloads.truncationHint("tengu jira issues view $key --full"))
        } else emptyList()

        return AxiResult.Ok(
            buildJsonObject {
                put("issue", issueJson(key, fields, commentCount(fields), descriptionEl))
            },
            hints,
        )
    }

    suspend fun comments(ctx: InvocationContext): AxiResult {
        val pat = ctx.secrets.get("pat") ?: return errNoPat()
        val key = ctx.args.getValue("key")
        val limit = ctx.flags.getValue("limit").toInt()

        val issue = try {
            client.issue(pat, key)
        } catch (e: JiraApiError) {
            return translate(e, key)
        }
        val fields = issue["fields"] as? JsonObject ?: JsonObject(emptyMap())
        val all = ((fields["comment"] as? JsonObject)?.get("comments") as? JsonArray) ?: JsonArray(emptyList())
        val shown = all.take(limit)
        val excerpted = shown.any { ((it as JsonObject).str("body") ?: "").length > COMMENT_EXCERPT }

        return AxiPayloads.listOfItems(
            noun = "comments",
            rows = shown.map { c ->
                val obj = c as JsonObject
                AxiPayloads.row(
                    "author" to (obj.nested("author", "displayName") ?: obj.nested("author", "name") ?: "-"),
                    "excerpt" to AxiPayloads.truncatedPreview(obj.str("body") ?: "-", COMMENT_EXCERPT),
                )
            },
            page = shown.size,
            total = all.size,
            emptyPhrase = "0 comments on $key",
            nextHint = if (excerpted) EXCERPT_HINT else null,
        )
    }

    suspend fun create(ctx: InvocationContext): AxiResult {
        val pat = ctx.secrets.get("pat") ?: return errNoPat()

        val created = try {
            client.create(
                pat,
                buildJsonObject {
                    put("project", buildJsonObject { put("key", ctx.flags.getValue("project")) })
                    put("summary", ctx.flags.getValue("title"))
                    ctx.flags["body"]?.let { put("description", it) }
                    put("issuetype", buildJsonObject { put("name", ctx.flags.getValue("type")) })
                    ctx.flags["assignee"]?.let { put("assignee", buildJsonObject { put("name", it) }) }
                },
            )
        } catch (e: JiraApiError) {
            return if (e.status == HttpStatusCode.BadRequest.value) {
                AxiResult.err(
                    ErrorKind.USAGE,
                    "jira rejected the issue (check --project and --type)",
                    listOf("Run `tengu jira issues list --project <KEY>` to verify the project key"),
                )
            } else {
                translate(e)
            }
        }
        val key = (created["key"] as? JsonPrimitive)?.content ?: "?"
        return AxiPayloads.detail(
            "issue",
            "key" to key,
            hints = listOf("Run `tengu jira issues view $key` to see it"),
        )
    }

    suspend fun comment(ctx: InvocationContext): AxiResult {
        val pat = ctx.secrets.get("pat") ?: return errNoPat()
        val key = ctx.args.getValue("key")

        try {
            client.addComment(pat, key, ctx.flags.getValue("body"))
        } catch (e: JiraApiError) {
            return translate(e, key)
        }
        return AxiResult.ok(
            "comment" to "added to $key",
            hints = listOf("Run `tengu jira issues comments $key` to see all comments"),
        )
    }

    private fun buildJql(flags: Map<String, String>): String {
        val clauses = buildList {
            flags["project"]?.let { add("project = \"$it\"") }
            when (flags.getValue("state")) {
                "open" -> add("resolution is EMPTY")
                "closed" -> add("resolution is not EMPTY")
            }
            flags["assignee"]?.let { add(if (it == "me") "assignee = currentUser()" else "assignee = \"$it\"") }
            flags["jql"]?.let { add("($it)") }
        }
        return if (clauses.isEmpty()) {
            "ORDER BY updated DESC"
        } else {
            "${clauses.joinToString(" AND ")} ORDER BY updated DESC"
        }
    }

    private fun nextListCommand(flags: Map<String, String>, startAt: Int): String = buildString {
        append("tengu jira issues list")
        flags["project"]?.let { append(" --project $it") }
        val state = flags.getValue("state")
        if (state != "open") append(" --state $state")
        flags["assignee"]?.let { append(" --assignee $it") }
        flags["fields"]?.let { append(" --fields $it") }
        append(" --start-at $startAt")
    }

    /** Число комментариев: агрегат total, иначе длина comments (§4). */
    private fun commentCount(fields: JsonObject): Int {
        val comments = fields["comment"] as? JsonObject
        return comments?.int("total")
            ?: (comments?.get("comments") as? JsonArray)?.size
            ?: 0
    }

    private fun issueJson(key: String, fields: JsonObject, comments: Int, description: JsonElement): JsonObject =
        buildJsonObject {
            put("key", key)
            put("title", fields.str("summary") ?: "-")
            put("state", fields.nested("status", "name") ?: "-")
            put("assignee", fields.nested("assignee", "displayName") ?: "-")
            put("reporter", fields.nested("reporter", "displayName") ?: "-")
            put("priority", fields.nested("priority", "name") ?: "-")
            put("updated", fields.str("updated") ?: "-")
            put("comments", comments)
            put("description", description)
        }

    private companion object {
        const val TRUNCATE_AT = 800
        const val COMMENT_EXCERPT = 100
        val EXCERPT_HINT = "Comments are excerpted to $COMMENT_EXCERPT chars; tengu has no full-comment view"
    }
}
