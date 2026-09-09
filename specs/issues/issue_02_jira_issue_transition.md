# TNG-02: Добавить команду смены статуса Jira-задачи

- **ID:** `TNG-02`
- **Тип:** Issue
- **Статус:** Ready for implementation
- **Приоритет:** High
- **Зависит от:** нет
- **Блокирует:** нет
- **Оценка:** 2 инженерных дня
- **Уверенность:** Medium
- **Риск:** Medium - новая мутационная команда поверх Jira workflow transitions

## Результат

Пользователь или агент может перевести одну Jira-задачу в доступный целевой
статус через единый Tengu CLI:

```sh
tengu jira issues transition AILAB-1353 --status "To Do"
```

Команда не требует знания внутреннего Jira transition ID, не выбирает переход
по частичному совпадению и безопасно повторяется, если задача уже находится в
целевом статусе.

## Контекст

Jira-плагин поддерживает просмотр, создание и комментирование задач, но не
смену статуса. Из-за этого агенту приходится покидать Tengu и использовать
веб-интерфейс Jira. Jira меняет статус не прямой записью поля, а выполнением
одного из transitions, доступных задаче в её текущем workflow state.

Текущие границы реализации:

- публичная поверхность описана в `JiraDescriptor.kt`;
- команды маршрутизирует `JiraPlugin.kt`;
- Jira REST v2 инкапсулирован в `JiraApiClient.kt`;
- issue-сценарии и AXI payload находятся в `JiraIssuesCommands.kt`;
- PAT доступен плагину только через `SecretScope`;
- любое изменение tool descriptor требует bump `ServerInfo.MANIFEST_VERSION`.

## Принятое решение

### CLI-контракт

```text
tengu jira issues transition <KEY> --status <STATUS>
```

- `<KEY>` - обязательный позиционный аргумент задачи.
- `--status` - обязательное непустое имя целевого статуса.
- Внешние пробелы в `--status` игнорируются.
- Сопоставление с destination status name регистронезависимое.
- Разрешено только полное совпадение после нормализации. Частичное и fuzzy
  matching запрещены.
- Пользователь не передаёт transition ID или имя transition action.

### Выполнение

1. Получить задачу и её текущий `fields.status.name`.
2. Если нормализованный текущий статус совпадает с целевым, вернуть успешный
   идемпотентный no-op и не запрашивать выполнение transition.
3. Получить доступные transitions задачи.
4. Найти transitions, у которых нормализованный `to.name` полностью совпадает
   с целевым статусом.
5. Если совпадение единственное, выполнить transition по его ID.
6. Если совпадений нет или их несколько, ничего не менять и вернуть
   actionable usage error.

Успешное изменение возвращает AXI detail:

```text
issue:
  key: AILAB-1353
  from: Reopened
  to: To Do
  changed: true
```

Если задача уже находится в целевом статусе, вернуть `AxiResult.Noop` с
сообщением `AILAB-1353 already in To Do`.

### Jira REST v2

- Текущий статус: `GET /rest/api/2/issue/{key}`.
- Доступные переходы: `GET /rest/api/2/issue/{key}/transitions`.
- Выполнение перехода: `POST /rest/api/2/issue/{key}/transitions` с телом:

```json
{"transition":{"id":"31"}}
```

Успешный пустой ответ Jira должен обрабатываться как нормальный результат, а
не как ошибка JSON parsing.

## Ошибки и безопасность

- Без PAT использовать существующую AUTH-ошибку с подсказкой `auth login`.
- Ответы Jira 401/403 переводить в существующую ошибку о невалидном PAT.
- Для отсутствующей задачи сохранять существующую NOT_FOUND-семантику с её
  ключом.
- Если целевой статус недоступен, вернуть `ErrorKind.USAGE`, перечислить
  конечный набор доступных destination status names и не выполнять POST.
- Если несколько transitions ведут в один нормализованный целевой статус,
  вернуть `ErrorKind.USAGE`, перечислить совпавшие transition actions и
  destination statuses и не выбирать произвольный ID.
- Если Jira отклоняет POST из-за обязательных transition fields, вернуть
  понятную ошибку без retry и без попытки автоматически заполнить поля.
- Мутационный POST не повторять автоматически после неопределённого ответа.
- PAT, Jira URL, response body и stack trace не должны попадать в AXI payload.
- Сетевые ошибки и невалидный JSON переводить через существующий механизм
  `JiraApiError` / `translate`.

## Изменения

- `plugins/jira/src/main/kotlin/ru/finnetrolle/tengu/jira/JiraDescriptor.kt`:
  добавить `issues transition`, аргумент `key`, обязательный `--status` и
  пример использования.
- `plugins/jira/src/main/kotlin/ru/finnetrolle/tengu/jira/JiraPlugin.kt`:
  добавить маршрутизацию команды.
- `plugins/jira/src/main/kotlin/ru/finnetrolle/tengu/jira/JiraApiClient.kt`:
  добавить получение transitions и выполнение transition, включая пустой
  успешный response body.
- `plugins/jira/src/main/kotlin/ru/finnetrolle/tengu/jira/JiraIssuesCommands.kt`:
  реализовать нормализацию, no-op, уникальный выбор перехода и AXI-результат.
- `plugins/jira/src/main/kotlin/ru/finnetrolle/tengu/jira/JiraErrors.kt`:
  добавить actionable errors для недоступного/неоднозначного перехода и
  required transition fields.
- `plugins/jira/src/test/kotlin/ru/finnetrolle/tengu/jira/JiraPluginTest.kt`:
  добавить regression tests всех обязательных веток.
- `plugins/jira/README.md` и `ARCHITECTURE.md`: обновить описанную поверхность.
- `server/src/main/kotlin/ru/finnetrolle/tengu/server/ServerInfo.kt`: увеличить
  `MANIFEST_VERSION` с 4 до 5.
- `scripts/e2e.sh`: добавить локальную проверку CLI validation новой команды.
  Не выполнять живой мутационный transition без отдельной управляемой fixture.

## Критерии готовности

### 1. Успешный переход

- **Stimulus:** fixture задачи `FOO-1` имеет статус `Open`; Jira возвращает один
  transition с ID `31` и destination `To Do`; вызывается команда с
  `--status "To Do"`.
- **Public seam:** `JiraPlugin.invoke` с command path `issues transition` и
  публичный descriptor команды.
- **Observable result:** выполнен ровно один POST на
  `/rest/api/2/issue/FOO-1/transitions` с ID `31`; payload содержит
  `key=FOO-1`, `from=Open`, `to=To Do`, `changed=true`.
- **Independent oracle:** `MockEngine` независимо фиксирует HTTP method, path и
  JSON body; отдельные assertions проверяют AXI payload.

### 2. Идемпотентный no-op

- **Stimulus:** текущий статус задачи `To Do`, пользователь передал
  `--status "  to do  "`.
- **Public seam:** `JiraPlugin.invoke`.
- **Observable result:** возвращён `AxiResult.Noop` с сообщением
  `FOO-1 already in To Do`; endpoint выполнения transition не вызван.
- **Independent oracle:** счётчики запросов `MockEngine` и assertion типа и
  сообщения результата.

### 3. Недоступный статус

- **Stimulus:** Jira возвращает только destinations `In Progress` и `Closed`,
  пользователь запросил `To Do`.
- **Public seam:** `JiraPlugin.invoke`.
- **Observable result:** `ErrorKind.USAGE` перечисляет `In Progress` и `Closed`;
  POST отсутствует.
- **Independent oracle:** статическая transitions fixture и отдельный счётчик
  POST-запросов.

### 4. Неоднозначный переход

- **Stimulus:** Jira возвращает два разных transition IDs, ведущих в статусы с
  одинаковым нормализованным именем.
- **Public seam:** `JiraPlugin.invoke`.
- **Observable result:** возвращена ошибка неоднозначности с обоими transition
  actions; ни один transition не выполнен.
- **Independent oracle:** fixture с двумя ID и проверка отсутствия POST.

### 5. Transition требует дополнительных полей

- **Stimulus:** POST transition получает HTTP 400 с Jira error о required field.
- **Public seam:** `JiraPlugin.invoke`.
- **Observable result:** actionable error без URL/PAT и без retry.
- **Independent oracle:** `MockEngine` возвращает 400 и подтверждает ровно один
  POST.

### 6. Descriptor, auth и регрессия

- **Stimulus:** загружается Jira descriptor; отдельно выполняются cases без PAT,
  с Jira 401/403, 404 и network failure.
- **Public seam:** manifest/CLI validation и `JiraPlugin.invoke`.
- **Observable result:** новая команда требует `key` и `--status`; ошибки имеют
  существующие AXI kinds и hints; контракты прежних команд не меняются.
- **Independent oracle:** descriptor assertions, HTTP fixtures и существующий
  `JiraPluginTest`.

## Проверка

- [ ] Все шесть acceptance sections покрыты focused tests.
- [ ] CLI validation отклоняет отсутствие `<KEY>` или `--status` с exit code 2.
- [ ] `./gradlew :plugins:jira:test` завершается успешно.
- [ ] `./gradlew build` завершается успешно.
- [ ] `bash scripts/e2e.sh` завершается успешно без обязательной живой мутации.
- [ ] `git diff --check` завершается успешно.

## Не входит

- Передача `resolution`, комментария и произвольных transition fields.
- Выбор перехода по transition ID или имени action.
- Массовый переход нескольких задач.
- Изменение Jira workflow или его конфигурации.
- Частичный, fuzzy или эвристический поиск похожего статуса.
- Автоматический retry мутационного POST.
- Живой E2E-переход без выделенной тестовой задачи и сценария восстановления.
- Прямой обход Tengu и обращение агента к Jira REST API.

Поддержка transitions с дополнительными обязательными полями должна быть
отдельной issue.

## Рассмотренные альтернативы

- Прямая запись `fields.status`: отклонена, потому что Jira меняет статус через
  workflow transitions.
- Требовать transition ID: отклонено, потому что ID является внутренней деталью
  конкретного Jira workflow и неудобен для агента.
- Частичное совпадение статуса: отклонено из-за риска выполнить неверную
  мутацию.
- Автоматически выбирать один из нескольких transitions в тот же статус:
  отклонено как недетерминированное поведение.
- Поддержать transition fields сразу: отложено по YAGNI; первая версия покрывает
  переходы без дополнительных обязательных полей.

## Ambiguity Report

```text
Goals:        0.0   наблюдаемый пользовательский результат задан
Acceptance:   0.05  шесть runtime cases имеют независимые HTTP oracles
Boundaries:   0.0   дополнительные fields, bulk и workflow config исключены
Alternatives: 0.0   опасные и избыточные варианты явно отклонены
Assumptions:  0.1   Jira REST v2 возвращает transitions[].to.name и принимает ID
Aggregate:    0.03  Ready for implementation.
```
