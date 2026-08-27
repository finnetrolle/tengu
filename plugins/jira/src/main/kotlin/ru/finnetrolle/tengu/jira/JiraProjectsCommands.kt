package ru.finnetrolle.tengu.jira

import ru.finnetrolle.tengu.toolkit.AxiPayloads
import ru.finnetrolle.tengu.toolkit.AxiResult
import ru.finnetrolle.tengu.toolkit.InvocationContext
import kotlinx.serialization.json.JsonObject

/** Команды projects: справочник ключей для --project у issues. */
internal class JiraProjectsCommands(private val client: JiraApiClient) {

    suspend fun list(ctx: InvocationContext): AxiResult {
        val pat = ctx.secrets.get("pat") ?: return errNoPat()
        val projects = try {
            client.projects(pat)
        } catch (e: JiraApiError) {
            return translate(e)
        }
        val rows = projects.map { p ->
            val obj = p as JsonObject
            AxiPayloads.row("key" to (obj.str("key") ?: "-"), "name" to (obj.str("name") ?: "-"))
        }
        return AxiPayloads.listOfItems(
            noun = "projects",
            rows = rows,
            page = rows.size,
            total = rows.size,
            emptyPhrase = "0 projects visible to your PAT",
            nextHint = null,
        ).let { ok ->
            ok.copy(helpHints = listOf("Run `tengu jira issues list --project <key>` to list issues"))
        }
    }
}
