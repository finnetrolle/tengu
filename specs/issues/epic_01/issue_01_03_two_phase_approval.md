# TNG-01-03: Ввести серверное двухфазное подтверждение мутаций

- **ID:** `TNG-01-03`
- **Тип:** Issue
- **Родитель:** [EPIC-01](../../epics/epic_01_safe_external_mutations.md)
- **Статус:** Blocked
- **Приоритет:** High
- **Зависит от:** [TNG-01-02](issue_01_02_command_effect_metadata.md)
- **Блокирует:** [TNG-02](../issue_02_jira_issue_transition.md)
- **Оценка:** 3-5 инженерных дней
- **Уверенность:** Medium
- **Режим:** Tracer bullet
- **Покрывает:** R2, R3, R6-R11

## Результат

Сервер Tengu гарантирует, что команда с
`confirmationPolicy=REQUIRED` не достигнет `ToolPlugin.invoke`, пока человек с
отдельным approver credential не подтвердит точные tool, command path, args и
flags. Approval нельзя повторно использовать или перенести на изменённый
запрос.

## Публичный сценарий

Первый запуск мутации:

```sh
tengu jira issues create --project AILAB --title "Add transition command"
```

возвращает структурированный результат и exit code 1:

```text
confirmation_required:
  id: apr_...
  tool: jira
  command: issues create
  effect: WRITE
  summary: Create Jira issue in AILAB titled "Add transition command"
  expires_at: 2026-09-09T19:10:00Z
help[2]:
  Review the exact operation before approving it
  An authorized human can run `tengu approvals approve apr_... --token -`
```

После ручного approval агент повторяет исходную команду с глобальным флагом:

```sh
tengu jira issues create \
  --project AILAB \
  --title "Add transition command" \
  --approval apr_...
```

При полном совпадении digest сервер атомарно consume approval и вызывает
плагин один раз.

## Approval authority

- Обычный hub token может читать, подготавливать мутацию и исполнять уже
  approved request, но не может одобрять.
- Approver endpoint использует отдельную конфигурацию
  `TENGU_APPROVER_TOKENS` с тем же строгим форматом `user=token`, но отдельным
  namespace и validation.
- Approver token не сохраняется в agent `StoredConfig` и не отдаётся через
  manifest, health, errors или logs.
- Human-owned команда принимает token через `--token -`, чтобы секрет не
  попадал в shell history.
- Один пользователь не может approve pending action другого пользователя.

## Protocol

Добавить DTO:

```text
PrepareRequest  = InvokeRequest
PendingApproval = id, digest, tool, commandPath, effect, redactedPreview, expiresAt
ApprovalRecord  = id, userId, digest, state, createdAt, expiresAt, approvedAt?
```

Добавить endpoints:

- `POST /v1/invoke/prepare` с hub token: server-side validation, digest и
  pending record, без вызова плагина;
- `POST /v1/approvals/{id}/approve` с approver token: `PENDING -> APPROVED`;
- существующий `POST /v1/invoke` принимает `X-Tengu-Approval-Id` и для
  `REQUIRED` команд выполняет verify + atomic consume перед plugin invocation.

Read-only `/v1/invoke` остаётся однофазным. Вызов REQUIRED-команды без approval
не должен случайно выполнить prepare и mutation в одном HTTP request.

## Digest и redaction

- Digest: SHA-256 от канонического JSON, содержащего `userId`, `tool`,
  `commandPath`, отсортированные `args` и отсортированные `flags`.
- `approvalId`, manifest version и transport headers не входят в digest.
- Secret flag values входят в digest, но не попадают в preview, logs или
  `ApprovalRecord`.
- Pending store хранит digest и redacted metadata, а не исходный
  `InvokeRequest`.
- Повторный invoke обязан передать исходные args/flags; server вычисляет digest
  заново.
- TTL первой версии: 10 минут по injected `Clock`.

## Lifecycle и атомарность

```text
PENDING --approve--> APPROVED --consume--> CONSUMED
   |                     |
   +------timeout--------+------timeout----> EXPIRED
```

- Approval state transition атомарен для concurrent requests.
- Только один exact invoke может успешно consume record.
- Consume выполняется перед вызовом мутационного плагина. Если плагин после
  consume завершился ошибкой или ответ потерян, автоматический retry запрещён;
  нужна новая подготовка и новое подтверждение.
- Рестарт сервера очищает in-memory store и безопасно инвалидирует pending и
  approved records.
- Повторное prepare одинакового запроса создаёт новый approval ID; скрытая
  дедупликация не выполняется.

## Ошибки

Добавить `ErrorKind.CONFIRMATION_REQUIRED` и структурированные данные pending
approval в protocol response либо отдельный response DTO prepare endpoint.
Конкретный DTO выбирается так, чтобы CLI не разбирал human text.

`CONFIRMATION_REQUIRED` отображается в HTTP 428 Precondition Required, даёт
CLI exit code 1 и `retryable=false`: повтор без нового состояния не должен
происходить автоматически. Prepare endpoint возвращает pending approval с
HTTP 200, потому что подготовка успешно завершена.

Следующие случаи завершаются до `ToolPlugin.invoke`:

- отсутствующий approval;
- неизвестный approval ID;
- pending, но ещё не approved record;
- expired record;
- consumed record;
- другой `userId`;
- несовпадающий digest;
- попытка approve обычным hub token;
- попытка approve action другого пользователя.

Ошибки содержат безопасный следующий шаг и не включают исходные secret values,
approver token, Jira URL или stack trace.

## Изменения

- `:protocol`: prepare/approval DTO, error kind и wire tests.
- `:server`: approver auth, in-memory approval store, routes, digest/redaction и
  enforcement перед `invokeTool`.
- `:cli`: подготовка REQUIRED-команды, structured preview, global
  `--approval`, human-owned `approvals approve` и stdin secret handling.
- `ARCHITECTURE.md`, protocol/server/CLI README: trust boundary, lifecycle и
  recovery after expiration/restart.
- `ServerInfo.MANIFEST_VERSION`: bump, если меняется descriptor/help surface.
- `scripts/e2e.sh`: сценарий с отдельными hub и approver tokens.

Не добавлять approval logic внутрь Jira plugin: policy является общей
ответственностью protocol/server и применяется ко всем plugins.

## Критерии готовности

### Read-only bypass

- **Stimulus:** hub user вызывает `jira issues list` без approval.
- **Public seam:** `POST /v1/invoke`.
- **Observable result:** plugin вызывается один раз, response и exit code не
  меняются.
- **Independent oracle:** fake plugin invocation counter и существующий route
  response assertion.

### Mutation prepare

- **Stimulus:** hub user запускает `issues create` без approval.
- **Public seam:** CLI и `POST /v1/invoke/prepare`.
- **Observable result:** plugin invocation count остаётся 0; CLI получает
  machine-readable preview с `PENDING`, ID и expiry.
- **Independent oracle:** fake mutating plugin с counter и fixed `Clock`.

### Human approval and exact commit

- **Stimulus:** approver того же user approves pending ID; hub user повторяет
  exact request с approval ID.
- **Public seam:** approve endpoint и `POST /v1/invoke`.
- **Observable result:** record проходит `PENDING -> APPROVED -> CONSUMED`,
  plugin вызывается ровно один раз и возвращает обычный payload.
- **Independent oracle:** approval store state plus fake plugin counter and
  captured invocation context.

### Changed request rejection

- **Stimulus:** после approval изменяется отдельно каждый класс binding:
  `userId`, `tool`, `commandPath`, один arg, один non-secret flag, один secret
  flag.
- **Public seam:** `POST /v1/invoke`.
- **Observable result:** каждый из шести cases отклоняется до plugin invocation;
  approval остаётся непригодным для изменённого request.
- **Independent oracle:** table-driven requests, digest assertions и plugin
  counter 0.

### Lifecycle rejection matrix

- **Stimulus:** invoke получает соответственно unknown, pending, expired и
  consumed approval ID; два concurrent invoke используют один approved ID.
- **Public seam:** `POST /v1/invoke`.
- **Observable result:** четыре последовательных cases отклонены; в concurrent
  case только один request вызывает plugin.
- **Independent oracle:** fixed/mutable clock, approval store state и atomic
  fake plugin counter.

### Authority and secret redaction

- **Stimulus:** обычный hub token пытается approve; approver другого user
  пытается approve; prepare содержит secret flag.
- **Public seam:** approve/prepare endpoints и server logs captured test sink.
- **Observable result:** обе попытки approve отклонены; secret отсутствует в
  response, record и logs, но изменение secret меняет digest.
- **Independent oracle:** literal token fixtures, recursive JSON search и
  captured log assertions.

### E2E

- **Stimulus:** локальный server запускается с отдельными hub/approver tokens;
  fake mutating plugin проходит prepare, manual approve command и exact commit.
- **Public seam:** собранный native CLI и HTTP server.
- **Observable result:** до approval side effect отсутствует, после approval
  появляется ровно один раз; повтор commit отклоняется.
- **Independent oracle:** server-owned deterministic counter exposed только
  test fixture и shell assertions exit code/output.

## Проверка

- [ ] Все шесть acceptance sections покрыты automated tests.
- [ ] `./gradlew :protocol:allTests` завершается успешно.
- [ ] `./gradlew :server:test` завершается успешно.
- [ ] Native CLI targets компилируются.
- [ ] `bash scripts/e2e.sh` проверяет prepare/approve/commit и replay rejection.
- [ ] `./gradlew build` завершается успешно.
- [ ] `git diff --check` завершается успешно.

## Не входит

- Approval web UI и push notifications.
- Хранение approvals в database/Vault или восстановление после рестарта.
- Автоматическое одобрение по prompt text, `--yes` или прошлому разрешению.
- Выдача approver token через agent setup/config.
- Подтверждение read-only команд.
- Retry мутационного plugin call после consume.
- Undo уже выполненного внешнего действия.
- Plugin-specific approval implementations.
