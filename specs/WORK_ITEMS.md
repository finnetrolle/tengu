# Tengu: epics и issues

Этот реестр хранит открытую работу проекта. Структура повторяет подход Vigilant,
но корневой каталог называется `specs/` по принятому для Tengu соглашению.

Продуктовая основа для проработки задач: [PRODUCT.md](PRODUCT.md).

Рабочий проект состава корпоративного MVP и порядка выполнения:
[MVP.md](MVP.md). Интеграция внешней авторизации в EPIC-03 отмечена `TBD`
до предоставления существующего сервиса политик; статусы issues сохраняются.

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
| [EPIC-01: Безопасное подтверждение внешних мутаций Tengu](epics/epic_01_safe_external_mutations.md) | `Blocked` | 0/1; контракт metadata требует пересмотра | после пересмотра контракта |
| [EPIC-02: Подключение инструментов Tengu по OpenAPI](epics/epic_02_openapi_tools.md) | `Draft` | не декомпозирован | после уточнения пилота |
| [EPIC-03: Внешняя авторизация команд Tengu](epics/epic_03_external_authorization.md) | `Draft` | не декомпозирован | после уточнения контракта и пилотного провайдера |
| [TNG-02: Добавить команду смены статуса Jira-задачи](issues/issue_02_jira_issue_transition.md) | `Blocked` | не начата | 2 инженерных дня |
| [TNG-03: Структурированные серверные логи в STDOUT](issues/issue_03_server_stdout_logging.md) | `Done` | реализована, проверки пройдены | не оценивалась |
| [TNG-04: W3C-трассировка вызовов Tengu CLI и сервера](issues/issue_04_w3c_distributed_tracing.md) | `Draft` | согласована цель | после технической проработки |
| [TNG-05: Передача агенту информации об инструментах, командах и их использовании](issues/issue_05_agent_tool_discovery.md) | `Draft` | задача на проработку процесса | после определения пилота и объёма проверки |

## Следующая работа

Следующая проработка:
[TNG-05](issues/issue_05_agent_tool_discovery.md).
Готовых к реализации issues сейчас нет. EPIC-01 и TNG-01-03 заблокированы
до пересмотра контракта metadata после отмены TNG-01-02. TNG-05 не заменяет
этот контракт автоматически.
TNG-02 заблокирована до завершения
[TNG-01-03](issues/epic_01/issue_01_03_two_phase_approval.md).
