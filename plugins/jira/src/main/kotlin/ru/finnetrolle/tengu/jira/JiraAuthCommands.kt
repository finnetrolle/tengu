package ru.finnetrolle.tengu.jira

import ru.finnetrolle.tengu.protocol.ErrorKind
import ru.finnetrolle.tengu.toolkit.AxiResult
import ru.finnetrolle.tengu.toolkit.InvocationContext
import ru.finnetrolle.tengu.toolkit.SecretScope
import kotlinx.serialization.json.JsonObject

/** Команды auth: PAT живёт в SecretScope и проверяется запросом /myself. */
internal class JiraAuthCommands(private val client: JiraApiClient) {

    suspend fun login(ctx: InvocationContext): AxiResult {
        val pat = ctx.flags.getValue("token").trim()
        val me = try {
            client.myself(pat)
        } catch (e: JiraApiError) {
            return if (e.isAuthRejection()) {
                AxiResult.err(
                    ErrorKind.AUTH,
                    "PAT was rejected by jira",
                    listOf("Check the token and run `tengu jira auth login --token <PAT>` again"),
                )
            } else {
                translate(e)
            }
        }
        ctx.secrets.put("pat", pat)
        return AxiResult.ok(
            "auth" to "PAT stored for jira (${who(me)})",
            hints = listOf("Run `tengu jira issues list --project <KEY>` to try it"),
        )
    }

    suspend fun status(ctx: InvocationContext): AxiResult {
        val pat = ctx.secrets.get("pat")
            ?: return AxiResult.ok(
                "auth" to "no PAT configured for jira",
                hints = listOf("Run `tengu jira auth login --token <PAT>`"),
            )
        val me = try {
            client.myself(pat)
        } catch (e: JiraApiError) {
            return if (e.isAuthRejection()) {
                AxiResult.ok(
                    "auth" to "configured (ends …${SecretScope.last4(pat)}), but jira rejected it - re-login needed",
                    hints = listOf("Run `tengu jira auth login --token <PAT>`"),
                )
            } else {
                translate(e)
            }
        }
        return AxiResult.ok("auth" to "configured (${who(me)}, ends …${SecretScope.last4(pat)}, verified just now)")
    }

    suspend fun logout(ctx: InvocationContext): AxiResult =
        if (ctx.secrets.delete("pat")) AxiResult.ok("auth" to "PAT removed for jira")
        else AxiResult.Noop("no PAT configured for jira - nothing to remove")

    private fun who(me: JsonObject): String =
        "${me.str("displayName") ?: "?"} <${me.str("name") ?: me.str("accountId") ?: "?"}>"
}
