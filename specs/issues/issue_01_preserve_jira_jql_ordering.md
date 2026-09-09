# TNG-01: Сохранять пользовательский ORDER BY в Jira JQL

- **ID:** `TNG-01`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** High
- **Зависит от:** нет
- **Блокирует:** нет
- **Оценка:** 1 инженерный день
- **Уверенность:** High
- **Риск:** Low - локальное исправление построителя Jira JQL без нового внешнего контракта

## Результат

Пользователь может запросить через `tengu jira issues list` задачи в явно
заданном порядке. Команда

```sh
tengu jira issues list \
  --state all \
  --jql 'project = AILAB ORDER BY created DESC' \
  --limit 10
```

успешно возвращает первую страницу из десяти задач, упорядоченную Jira по
убыванию времени создания.

## Контекст и воспроизведение

Текущий `JiraIssuesCommands.buildJql` рассматривает всё значение `--jql` как
predicate, оборачивает его в скобки, а затем безусловно добавляет
`ORDER BY updated DESC`:

```jql
(project = AILAB ORDER BY created DESC) ORDER BY updated DESC
```

Jira отклоняет запрос:

```text
Error in the JQL Query: Expecting ')' but got 'ORDER'.
```

Таким образом, заявленный в help `Raw JQL` фактически не поддерживает
стандартную сортировку JQL, а пользователь не может получить последние
созданные задачи через публичный CLI.

## Принятое решение

- `--jql` поддерживает необязательную терминальную top-level секцию `ORDER BY`.
- Predicate из `--jql` объединяется с фильтрами `--project`, `--state` и
  `--assignee`; пользовательская секция сортировки остаётся после объединённого
  predicate.
- В итоговом JQL присутствует ровно одна секция `ORDER BY`.
- Если пользователь не задал сортировку, сохраняется текущий default
  `ORDER BY updated DESC`.
- `ORDER BY` внутри quoted string или вложенного выражения не считается
  терминальной секцией сортировки.
- Новые CLI-флаги и новый JQL parser общего назначения не добавляются.
- Некорректный JQL по-прежнему проверяет Jira; существующий перевод ошибки в
  AXI-конверт сохраняется.

## Обязательные случаи

| Вход | JQL, отправленный в Jira |
|---|---|
| `--state all --jql 'project = AILAB ORDER BY created DESC'` | `(project = AILAB) ORDER BY created DESC` |
| `--project AILAB --state open --jql 'labels = "ai" ORDER BY created DESC'` | `project = "AILAB" AND resolution is EMPTY AND (labels = "ai") ORDER BY created DESC` |
| `--state all --jql 'project = AILAB'` | `(project = AILAB) ORDER BY updated DESC` |
| default flags без `--jql` | `resolution is EMPTY ORDER BY updated DESC` |
| `--state all --jql 'summary ~ "ORDER BY"'` | `(summary ~ "ORDER BY") ORDER BY updated DESC` |
| `--state all --jql 'project = AILAB ORDER BY created DESC, key DESC'` | `(project = AILAB) ORDER BY created DESC, key DESC` |

Распознавание ключевых слов JQL регистронезависимое. Пробелы внутри
пользовательского predicate и списка сортировки не должны менять их семантику.

## Изменения

- Исправить построение JQL в
  `plugins/jira/src/main/kotlin/ru/finnetrolle/tengu/jira/JiraIssuesCommands.kt`.
- Добавить regression cases в
  `plugins/jira/src/test/kotlin/ru/finnetrolle/tengu/jira/JiraPluginTest.kt`.
- При необходимости уточнить пример сортировки в `plugins/jira/README.md`, не
  добавляя новую CLI-поверхность.

## Критерии готовности

- [ ] Вызов `issues list` с терминальным `ORDER BY created DESC` доходит до Jira
  без синтаксического искажения и без дополнительного `ORDER BY updated DESC`.
- [ ] Все шесть обязательных случаев из таблицы проверены через публичный
  `JiraPlugin.invoke`; `MockEngine` независимо фиксирует JSON-тело Jira search
  request, а assertion сравнивает поле `jql` с указанной строкой.
- [ ] Для пользовательской сортировки с несколькими полями сохраняются порядок
  полей и направления `ASC`/`DESC`.
- [ ] Значение `ORDER BY` внутри quoted string не меняет default sorting.
- [ ] Существующие фильтры, `--limit`, `--start-at`, набор запрашиваемых полей,
  AXI payload и перевод ошибок Jira не изменились.
- [ ] `./gradlew :plugins:jira:test`, `./gradlew build` и `git diff --check`
  завершаются успешно.

## Не входит

- Новый `--sort` или `--order` флаг и изменение manifest version.
- Полный синтаксический parser или клиентская validation произвольного JQL.
- Добавление `created` в стандартные или дополнительные колонки результата.
- Исправление pagination hint, если он не сохраняет `--jql`, `--limit` или
  другие параметры; это отдельный дефект.
- Изменения Jira auth, `JiraApiClient`, server routes, protocol или native CLI.
- Прямой обход Tengu и обращение агента к Jira REST API.

## Рассмотренные альтернативы

- Добавить `--sort created`: отклонено, потому что существующий `--jql` уже
  обещает raw JQL и исправление не требует расширения публичной поверхности.
- Всегда удалять пользовательский `ORDER BY`: отклонено, потому что результат
  продолжит сортироваться не так, как запросил пользователь.
- Передавать весь `--jql` без объединения: отклонено, потому что сломает
  документированную совместную работу `--jql` с `--project`, `--state` и
  `--assignee`.

## Ambiguity Report

```text
Goals:        0.0   воспроизведённый пользовательский результат задан
Acceptance:   0.05  шесть обязательных JQL cases и oracle перечислены
Boundaries:   0.0   изменение ограничено Jira JQL builder и regression tests
Alternatives: 0.0   новая CLI-поверхность и потеря фильтров отклонены
Assumptions:  0.1   Jira принимает стандартный terminal ORDER BY REST v2
Aggregate:    0.03  Ready for implementation.
```
