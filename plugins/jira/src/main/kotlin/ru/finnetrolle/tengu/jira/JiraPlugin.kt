package ru.finnetrolle.tengu.jira

import ru.finnetrolle.tengu.protocol.ErrorKind
import ru.finnetrolle.tengu.toolkit.AxiResult
import ru.finnetrolle.tengu.toolkit.InvocationContext
import ru.finnetrolle.tengu.toolkit.ToolPlugin

/**
 * Jira-плагин: сам владеет доступом (PAT в SecretScope, проверка на /myself),
 * переводит ошибки апстрима в AXI-конверты, строит AXI-совместимые payload'ы.
 * Маршрутизация команд; сами команды — в JiraAuthCommands/JiraIssuesCommands/JiraProjectsCommands.
 */
class JiraPlugin(private val baseUrl: String) : ToolPlugin {

    override val descriptor = jiraDescriptor()

    override suspend fun invoke(commandPath: List<String>, ctx: InvocationContext): AxiResult {
        val client = JiraApiClient(ctx.httpClient, baseUrl)
        val auth = JiraAuthCommands(client)
        val issues = JiraIssuesCommands(client)
        val projects = JiraProjectsCommands(client)
        return when (commandPath) {
            listOf("auth", "login") -> auth.login(ctx)
            listOf("auth", "status") -> auth.status(ctx)
            listOf("auth", "logout") -> auth.logout(ctx)
            listOf("issues", "list") -> issues.list(ctx)
            listOf("projects", "list") -> projects.list(ctx)
            listOf("issues", "view") -> issues.view(ctx)
            listOf("issues", "comments") -> issues.comments(ctx)
            listOf("issues", "create") -> issues.create(ctx)
            listOf("issues", "comment") -> issues.comment(ctx)
            else -> AxiResult.err(ErrorKind.INTERNAL, "unrouted command '${commandPath.joinToString(" ")}'")
        }
    }
}
