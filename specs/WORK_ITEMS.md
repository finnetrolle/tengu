# Tengu: epics и issues

Этот реестр хранит открытую работу проекта. Структура повторяет подход Vigilant,
но корневой каталог называется `specs/` по принятому для Tengu соглашению.

## Размещение

```text
specs/
  WORK_ITEMS.md
  epics/
    epic_NN_name.md
  issues/
    issue_NN_name.md
    epic_NN/
      issue_NN_MM_name.md
```

- Epic имеет ID `EPIC-NN`.
- Самостоятельная issue имеет ID `TNG-NN`.
- Дочерняя issue epic имеет ID `TNG-NN-MM`.
- Новые ID не переиспользуются.
- Статус в issue-файле является источником истины.

Допустимые статусы: `Draft`, `Ready for implementation`, `In progress`,
`Blocked`, `Done`.

## Реестр

| Work item | Статус | Прогресс | Оценка |
|---|---|---:|---:|
| [EPIC-01: Безопасное подтверждение внешних мутаций Tengu](epics/epic_01_safe_external_mutations.md) | `Ready for implementation` | 0/3 | 4.5-8 инженерных дней |
| [EPIC-02: Подключение инструментов Tengu по OpenAPI](epics/epic_02_openapi_tools.md) | `Draft` | не декомпозирован | после уточнения пилота |
| [EPIC-03: Внешняя авторизация команд Tengu](epics/epic_03_external_authorization.md) | `Draft` | не декомпозирован | после уточнения контракта и пилотного провайдера |
| [TNG-01: Сохранять пользовательский ORDER BY в Jira JQL](issues/issue_01_preserve_jira_jql_ordering.md) | `Ready for implementation` | не начата | 1 инженерный день |
| [TNG-02: Добавить команду смены статуса Jira-задачи](issues/issue_02_jira_issue_transition.md) | `Blocked` | не начата | 2 инженерных дня |
| [TNG-03: Структурированные серверные логи в STDOUT](issues/issue_03_server_stdout_logging.md) | `Ready for implementation` | не начата | не оценивалась |
| [TNG-04: W3C-трассировка вызовов Tengu CLI и сервера](issues/issue_04_w3c_distributed_tracing.md) | `Draft` | согласована цель | после технической проработки |

## Следующая работа

Текущий следующий шаг: реализовать [TNG-01](issues/issue_01_preserve_jira_jql_ordering.md).

Параллельный frontier EPIC-01:
[TNG-01-01](issues/epic_01/issue_01_01_external_mutation_policy.md) и
[TNG-01-02](issues/epic_01/issue_01_02_command_effect_metadata.md).
TNG-02 заблокирована до завершения
[TNG-01-03](issues/epic_01/issue_01_03_two_phase_approval.md).
