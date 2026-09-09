# :plugins:jira - плагин Jira

Интеграция корпоративной Jira: issues и projects через REST v2. Плагин **сам владеет доступом** - PAT каждого пользователя хранится в его `SecretScope` (Vault на проде), агент про креды не знает. Регистрируется сервером только при заданной `TENGU_JIRA_BASE_URL`.

## Поверхность (`tengu jira …`)

| Команда | Что делает |
|---|---|
| `auth login` / `auth status` / `auth logout` | PAT: сохранить (с проверкой на `/rest/api/2/myself`, `--token -` читает stdin), проверить, удалить (идемпотентно) |
| `issues list` | JQL из флагов `--project/--state/--assignee/--jql`, пагинация `--limit/--start-at`, минимальная схема key/title/state + `--fields` |
| `issues view <KEY>` | Детальный вид, описание усечено до 800 символов (`--full` - целиком) |
| `issues comments <KEY>` | Комментарии (author + excerpt) |
| `issues create` | `--project --title` обязательны, `--body/--type/--assignee` опциональны |
| `issues comment <KEY>` | `--body` обязательно |
| `projects list` | Ключи проектов, видимые PAT - источник значений для `--project` |

## Состав

| Файл | Что внутри |
|---|---|
| `JiraPlugin.kt` | Реализация `ToolPlugin`: дескриптор всей поверхности, маршрутизация команд, сборка payload'ов через `AxiPayloads`, `translate()` - перевод ошибок апстрима в AXI-конверты (никаких URL/стектрейсов, только действие) |
| `JiraApiClient.kt` | Тонкая HTTP-обёртка REST v2: myself, project, search, issue, create, comment. PAT ставится на каждый запрос; сетевые сбои → `JiraApiError(-1)`. JSON-хелперы `str/int/nested` |

## Зависимости

- `:toolkit` (контракт, `AxiPayloads`, `SecretScope`), ktor-client-cio.

## Секреты

Ключ `pat`. Поток: `auth login` → проверка на `/myself` → `secrets.put` → Vault/файл. Токен пересекает провод один раз, редактируется в логах (`FlagDescriptor.secret`).

## Тесты

`JiraPluginTest` - на ktor-client-mock: auth-потоки, list/view/create/comment, перевод ошибок. `./gradlew :plugins:jira:test`. Живой сценарий S6 - через `scripts/e2e.sh` с `TENGU_E2E_JIRA_URL`.

Контракт и гарантии - [ARCHITECTURE.md](../../ARCHITECTURE.md), «Контракт плагина».
