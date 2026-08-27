package ru.finnetrolle.tengu.jira

import ru.finnetrolle.tengu.protocol.ArgDescriptor
import ru.finnetrolle.tengu.protocol.CommandDescriptor
import ru.finnetrolle.tengu.protocol.FieldDescriptor
import ru.finnetrolle.tengu.protocol.FlagDescriptor
import ru.finnetrolle.tengu.protocol.FlagType
import ru.finnetrolle.tengu.protocol.ToolDescriptor
import ru.finnetrolle.tengu.protocol.UsageExample

/** Дескриптор jira — единый источник правды для манифеста сервера и валидации/help CLI. */
internal fun jiraDescriptor(): ToolDescriptor = ToolDescriptor(
    name = "jira",
    summary = "Jira issues: list, view, create, comment",
    description = "Corporate Jira. Each agent uses their own PAT (tengu jira auth login); " +
        "issues are listed with a minimal schema, long fields live in the detail view.",
    commands = authCommands() + projectsCommands() + issuesCommands(),
)

private fun authCommands(): List<CommandDescriptor> = listOf(
    CommandDescriptor(
        path = listOf("auth", "login"),
        summary = "Store your Jira PAT (verified against /myself)",
        flags = listOf(
            FlagDescriptor(
                name = "token",
                required = true,
                secret = true,
                help = "Personal access token; '-' reads it from stdin",
            ),
        ),
        examples = listOf(UsageExample("tengu jira auth login --token <PAT>", "store the token")),
    ),
    CommandDescriptor(
        path = listOf("auth", "status"),
        summary = "Show whether a PAT is configured and valid",
        examples = listOf(UsageExample("tengu jira auth status")),
    ),
    CommandDescriptor(
        path = listOf("auth", "logout"),
        summary = "Remove the stored PAT (idempotent)",
        examples = listOf(UsageExample("tengu jira auth logout")),
    ),
)

private fun projectsCommands(): List<CommandDescriptor> = listOf(
    CommandDescriptor(
        path = listOf("projects", "list"),
        summary = "List projects your PAT can see",
        examples = listOf(UsageExample("tengu jira projects list", "keys for --project")),
    ),
)

private fun issuesCommands(): List<CommandDescriptor> = listOf(
    issuesListCommand(),
    CommandDescriptor(
        path = listOf("issues", "view"),
        summary = "Show one issue with truncated description and comment count",
        args = listOf(ArgDescriptor("key")),
        flags = listOf(
            FlagDescriptor(name = "full", type = FlagType.BOOL, help = "Show the complete description"),
        ),
        examples = listOf(UsageExample("tengu jira issues view FOO-42")),
    ),
    CommandDescriptor(
        path = listOf("issues", "comments"),
        summary = "List comments of an issue",
        args = listOf(ArgDescriptor("key")),
        flags = listOf(FlagDescriptor(name = "limit", type = FlagType.INT, default = "20")),
        examples = listOf(UsageExample("tengu jira issues comments FOO-42")),
    ),
    CommandDescriptor(
        path = listOf("issues", "create"),
        summary = "Create an issue",
        flags = listOf(
            FlagDescriptor(name = "project", required = true, help = "Project KEY"),
            FlagDescriptor(name = "title", required = true, help = "Issue summary"),
            FlagDescriptor(name = "body", help = "Description"),
            FlagDescriptor(name = "type", default = "Task", help = "Issue type name"),
            FlagDescriptor(name = "assignee", help = "Username"),
        ),
        examples = listOf(UsageExample("tengu jira issues create --project FOO --title \"Fix login\"")),
    ),
    CommandDescriptor(
        path = listOf("issues", "comment"),
        summary = "Comment on an issue",
        args = listOf(ArgDescriptor("key")),
        flags = listOf(FlagDescriptor(name = "body", required = true, help = "Comment text")),
        examples = listOf(UsageExample("tengu jira issues comment FOO-42 --body \"Looked into it\"")),
    ),
)

private fun issuesListCommand(): CommandDescriptor = CommandDescriptor(
    path = listOf("issues", "list"),
    summary = "List issues with a minimal default schema",
    longHelp = "Default schema: key, title, state. Use --fields to request more.",
    flags = listOf(
        FlagDescriptor(name = "project", help = "Project KEY, e.g. FOO"),
        FlagDescriptor(
            name = "state",
            default = "open",
            allowedValues = listOf("open", "closed", "all"),
            help = "Filter by resolution state",
        ),
        FlagDescriptor(name = "assignee", help = "Username or 'me'"),
        FlagDescriptor(name = "jql", help = "Raw JQL, combined with the filters above"),
        FlagDescriptor(name = "limit", type = FlagType.INT, default = "50", help = "Page size"),
        FlagDescriptor(name = "start-at", type = FlagType.INT, default = "0", help = "Page offset"),
        FlagDescriptor(name = "fields", help = "Extra columns: assignee, priority, updated, reporter, type"),
    ),
    fields = listOf(
        FieldDescriptor("key", inDefault = true),
        FieldDescriptor("title", inDefault = true),
        FieldDescriptor("state", inDefault = true),
        FieldDescriptor("assignee"), FieldDescriptor("priority"), FieldDescriptor("updated"),
        FieldDescriptor("reporter"), FieldDescriptor("type"),
    ),
    examples = listOf(
        UsageExample("tengu jira issues list --project FOO", "open issues of a project"),
        UsageExample("tengu jira issues list --assignee me --state all", "everything assigned to you"),
    ),
)
