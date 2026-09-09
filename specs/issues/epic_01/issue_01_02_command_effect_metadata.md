# TNG-01-02: Описывать эффект и policy команды в manifest

- **ID:** `TNG-01-02`
- **Тип:** Issue
- **Родитель:** [EPIC-01](../../epics/epic_01_safe_external_mutations.md)
- **Статус:** Ready for implementation
- **Приоритет:** High
- **Зависит от:** нет
- **Блокирует:** [TNG-01-03](issue_01_03_two_phase_approval.md)
- **Оценка:** 1-2 инженерных дня
- **Уверенность:** High
- **Режим:** Expand/migrate
- **Покрывает:** R4, R5, R10

## Результат

Manifest Tengu машинно сообщает агенту, CLI и серверу, является ли команда
read-only или мутационной и требует ли она подтверждения. `tengu tools show`
и command help показывают эти свойства без необходимости знать реализацию
плагина.

## Контракт

Добавить в `:protocol` сериализуемые enum:

```kotlin
enum class CommandEffect { READ, WRITE, DELETE }
enum class ConfirmationPolicy { NONE, REQUIRED }
```

Расширить `CommandDescriptor`:

```kotlin
val effect: CommandEffect = CommandEffect.READ
val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.NONE
```

Defaults сохраняют source/wire compatibility для существующих read-only
descriptor fixtures. Новые поля читаются старыми клиентами как unknown fields,
а новый клиент трактует отсутствующие поля как `READ/NONE`.

Registry при регистрации или построении manifest проверяет инвариант:

```text
effect in {WRITE, DELETE} => confirmationPolicy == REQUIRED
```

Нарушение является configuration/programming error и не позволяет серверу
запуститься с небезопасным plugin descriptor.

## Классификация текущих команд

| Команда | effect | confirmationPolicy |
|---|---|---|
| `status` | READ | NONE |
| `jira auth status` | READ | NONE |
| `jira projects list` | READ | NONE |
| `jira issues list` | READ | NONE |
| `jira issues view` | READ | NONE |
| `jira issues comments` | READ | NONE |
| `jira auth login` | WRITE | REQUIRED |
| `jira issues create` | WRITE | REQUIRED |
| `jira issues comment` | WRITE | REQUIRED |
| `jira auth logout` | DELETE | REQUIRED |

Будущая `jira issues transition` из TNG-02 должна объявляться как
`WRITE/REQUIRED`.

## Изменения

- `protocol/Descriptors.kt`: enum и поля descriptor.
- `protocol/ManifestRoundTripTest.kt`: wire round-trip и defaults.
- `server/PluginRegistry.kt`: fail-fast validation инварианта.
- `server/ServerRoutesTest.kt`: manifest содержит metadata мутационных команд.
- `plugins/jira/JiraDescriptor.kt`: полная классификация Jira surface.
- `server/tools/StatusTool.kt`: явная или default read-only классификация.
- `cli/render/ToonRenderer.kt` и `cli/tool/HelpText.kt`: эффект и policy в
  tool detail и command help.
- README модулей и `ARCHITECTURE.md`: описать metadata как единый источник
  policy.
- `ServerInfo.MANIFEST_VERSION`: увеличить версию из-за изменения поверхности.

## Миграция

1. Добавить backward-compatible protocol fields и round-trip tests.
2. Классифицировать все команды текущего manifest.
3. Включить fail-fast server validation.
4. Вывести metadata в CLI help/catalog.

На этом этапе команды продолжают выполняться по текущему `/v1/invoke` без
серверной блокировки. Enforcement доставляет TNG-01-03. Документация должна
явно обозначить это переходное ограничение.

## Критерии готовности

### Manifest round-trip

- **Stimulus:** manifest содержит одну `READ/NONE`, одну `WRITE/REQUIRED` и одну
  `DELETE/REQUIRED` команду.
- **Public seam:** `ProtocolJson` encode/decode `Manifest`.
- **Observable result:** значения сохраняются после round-trip; descriptor без
  новых полей декодируется как `READ/NONE`.
- **Independent oracle:** literal JSON fixtures и assertions enum values.

### Небезопасный descriptor

- **Stimulus:** plugin объявляет `WRITE/NONE` или `DELETE/NONE`.
- **Public seam:** `PluginRegistry.register` либо документированный manifest
  construction seam.
- **Observable result:** регистрация детерминированно отклоняется до запуска
  server routes с сообщением, содержащим tool и command path.
- **Independent oracle:** fake plugin descriptor в server unit test.

### Полная классификация Jira

- **Stimulus:** загружается текущий `jiraDescriptor`.
- **Public seam:** `ToolDescriptor.commands`.
- **Observable result:** все десять текущих command paths имеют значения из
  конечной таблицы; ни одна мутационная команда не имеет `NONE`.
- **Independent oracle:** table-driven test с exact map command path -> metadata.

### CLI disclosure

- **Stimulus:** рендер `tools show jira` и help для `issues create`.
- **Public seam:** `ToolDetail` и `HelpText.command`.
- **Observable result:** catalog/help однозначно сообщает `WRITE` и
  `confirmation: required`; read-only help не получает лишний warning block.
- **Independent oracle:** renderer golden or exact structured payload assertions.

## Проверка

- [ ] `./gradlew :protocol:allTests` завершается успешно.
- [ ] `./gradlew :server:test` завершается успешно.
- [ ] Jira descriptor classification test завершается успешно.
- [ ] Renderer tests подтверждают disclosure.
- [ ] `./gradlew build` завершается успешно.
- [ ] `git diff --check` завершается успешно.

## Не входит

- Блокировка `/v1/invoke` без approval.
- Pending approvals, digest, TTL и approver auth.
- Интерактивные terminal prompts.
- Изменение args, flags или payload существующих команд.
- Отдельные уровни риска кроме `READ`, `WRITE`, `DELETE`.
- Автоматический вывод effect из HTTP method или имени команды.
