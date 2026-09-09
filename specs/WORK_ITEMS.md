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
| [TNG-01: Сохранять пользовательский ORDER BY в Jira JQL](issues/issue_01_preserve_jira_jql_ordering.md) | `Ready for implementation` | не начата | 1 инженерный день |
| [TNG-02: Добавить команду смены статуса Jira-задачи](issues/issue_02_jira_issue_transition.md) | `Ready for implementation` | не начата | 2 инженерных дня |

## Следующая работа

Текущий следующий шаг: реализовать [TNG-01](issues/issue_01_preserve_jira_jql_ordering.md).
