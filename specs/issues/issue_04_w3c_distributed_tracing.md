# TNG-04: Сквозные traceparent и session_id от CLI через сервер до Jira и Vault

- **ID:** `TNG-04`
- **Тип:** Issue
- **Статус:** Draft
- **Связана с:** [TNG-03: Серверные логи в STDOUT](issue_03_server_stdout_logging.md), реализована
- **Зависимости:** до автономной реализации закрыть технические решения по Native SDK и экспорту ниже
- **Оценка:** после проверки Native SDK и экспортёра

## Результат и согласованная граница

Агент передаёт W3C-контекст конкретному запуску Tengu. CLI продолжает трассу,
сервер создаёт собственные spans, а обращения плагинов к Jira и секретам в
Vault сохраняют ту же трассу с корректными родительскими связями. Если
внешнего контекста нет, CLI начинает новую трассу своего вызова. Прямой
HTTP-вызов сервера без контекста начинает трассу на сервере.

Параметр `--session-id` связывает вызовы одной беседы/задачи в группу трасс
для Langfuse или MLflow. Значение сохраняется от CLI через сервер и плагины
до исходящих запросов, экспортированных spans и серверных логов.

Задача охватывает всё выполнение, включая сервер, а не только передачу
заголовка из CLI. Нужны создание, завершение и экспорт spans; наличие ID
в заголовках или JSON-логах само по себе не является готовым результатом.

**Принятый контракт: необязательный `--session-id` задаётся отдельно для
каждого запуска CLI.** Tengu не генерирует и не запоминает сессию при
отсутствии параметра. Отдельного `--span-id` нет: собственные span IDs
создаёт трассировщик, а родитель передаётся в `traceparent`.

Документ заменяет прежний Draft TNG-04; новый ID для той же работы не
выделяется. Продуктовая граница согласована. Статус Draft сохраняется из-за
двух конкретных технических решений, перечисленных ниже.

## Контракт CLI

```sh
tengu --session-id chat-42 \
  --traceparent 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 \
  --tracestate vendor=value jira issues list --project FOO

TENGU_TRACEPARENT="$PARENT_CONTEXT" tengu --session-id chat-42 jira issues list --project FOO

# Самостоятельные трассы двух вызовов попадают в одну сессию
tengu --session-id chat-42 jira issues list --project FOO
tengu --session-id chat-42 jira issues list --project BAR
```

| Параметр | Эквивалент в окружении | Назначение |
|---|---|---|
| `--traceparent <value>` | `TENGU_TRACEPARENT` | Полное W3C-значение: trace ID, parent ID, trace flags |
| `--tracestate <value>` | `TENGU_TRACESTATE` | Необязательные данные внешнего трассировщика |
| `--session-id <value>` | Нет, только явный параметр | Идентификатор беседы/задачи для группировки трасс |

1. Глобальные параметры стоят перед первым именем builtin/tool:
   `tengu [global-options] <tool> <command> [tool-options]`. Поддерживаются
   `--name value` и `--name=value`. Их разбирает общий entry point до выбора
   builtin/tool и до сети; они доступны для обоих путей исполнения.
2. Явный `--traceparent` приоритетнее env и выбирает новую пару контекста:
   `TENGU_TRACESTATE` в этом случае не подмешивается, принимается только
   явный `--tracestate`. Без `--traceparent` используется env-parent;
   явный `--tracestate` может заменить state из env для этого parent.
3. Отсутствующие и пустые env-значения означают отсутствие значения.
   Пустое/пропущенное значение явного флага, повтор глобального флага,
   некорректный эффективный W3C-контекст или эффективный tracestate без
   traceparent дают AXI usage error, exit 2 до любого HTTP-запроса.
   Некорректный session ID также даёт usage error до сети; его формат
   определён ниже. Перекрытые env-значения не валидируются и не используются.
4. Валидация следует W3C, включая версионирование, ненулевые IDs, flags и
   правила tracestate. Ошибка объясняет параметр и ожидаемый формат без
   копирования сырого контекста в output/logs. Не ограничивать W3C
   собственным упрощённым форматом или только одним regex версии 00.
5. Каждый запуск получает независимый неизменяемый снимок контекста.
   Не хранить его в StoredConfig, manifest cache, файле текущей трассы
   или общей изменяемой переменной. Интеграция передаёт отдельные argv/env
   дочернему процессу; не изменяет общий process env между задачами. Session
   ID берётся только из `--session-id`, без `TENGU_SESSION_ID`, автоопределения
   сессии агента или fallback из ранее выполненной команды.
6. Параметры не попадают в `InvokeRequest.args/flags`, ToolDescriptor или
   validation параметров плагина. Tool manifest и его версия не меняются
   только из-за этих транспортных параметров.
7. Справка описывает параметры, precedence и примеры. Одиночные
   `-v`, `-V`, `--version` сохраняют fast path без сети, SDK/exporter и
   проверки tracing-env. Help не создаёт spans и новой telemetry-сети;
   уже существующий сетевой lookup справки допустим.
8. Обычные команды, включая dashboard/cache-only, setup, manifest refresh,
   status и ошибки выполнения, имеют один CLI span. Не добавлять обращений
   к хабу ради трассировки локальной команды.

Имена env являются контрактом Tengu, а не обещанием автоматической поддержки
конкретным агентом. Идентификатор беседы не заменяет W3C-контекст.

## Семантика сессии и совместимость с Langfuse/MLflow

Проверено по официальной документации 2026-09-10:

- Langfuse Sessions объединяют несколько трасс взаимодействия для просмотра
  беседы и оценки сессии. Общий session ID не меняет родительские связи spans.
  [Sessions](https://langfuse.com/docs/observability/features/sessions)
- Langfuse принимает OTel `session.id` и отображает его как `sessionId`.
  Для фильтров и агрегаций атрибут нужен на дочерних observations, не только
  на root. [OTel mapping и propagation](https://langfuse.com/integrations/native/opentelemetry)
- MLflow группирует и фильтрует трассы через metadata `mlflow.trace.session`;
  его OTLP ingestion автоматически отображает туда span attribute `session.id`.
  [Sessions](https://mlflow.org/docs/latest/genai/tracing/track-users-sessions/),
  [Attribute mapping](https://mlflow.org/docs/latest/genai/tracing/opentelemetry/attribute-mapping/)

Отсюда контракт Tengu: **один canonical span attribute `session.id`**, без
отдельных vendor SDK или дублирования в `langfuse.session.id` и
`mlflow.trace.session`. Выбор backend не меняет CLI/HTTP-контракт.

### Владелец и время жизни

- Значение выбирает вызывающая сторона. Она повторяет его в каждом
  Tengu-вызове одной беседы/задачи и использует то же значение для своих
  LLM-трасс, отправляемых в тот же проект/experiment observability backend.
  Tengu не может самостоятельно найти или разметить внешние LLM-трассы.
- Две независимые задачи получают разные значения. Параллельные действия
  одной задачи могут иметь общий session ID, разные trace IDs и свои spans.
  Общее значение не создаёт блокировок, дедупликации или общего mutable state.
- Session ID является непрозрачной меткой группировки, не user ID,
  credential, auth session, idempotency key или parent span. Не выводить
  из него trace ID, не объединять трассы в одну и не держать span открытым
  на всю беседу. В пределах одной трассы вызывающая сторона передаёт
  согласованное значение; Tengu не содержит глобального реестра трасс/сессий.

| Вход конкретного запуска | Результат |
|---|---|
| Только session ID | Новая трасса, spans помечены этой сессией |
| Только traceparent | Продолжение трассы, session attribute отсутствует |
| Оба | Продолжение трассы и независимая метка сессии |
| Ни одного | Новая трасса, session attribute отсутствует |

### Формат и перенос

1. Каноническое имя CLI-флага: `--session-id`, включая форму
   `--session-id=value`. Он необязателен и не требует traceparent.
2. Значение: от 1 до 200 видимых US-ASCII символов (`U+0021..U+007E`).
   Без пробелов, control characters и Unicode; без trim, смены регистра,
   хеширования или усечения. Это транспортный контракт Tengu. Верхняя
   граница соответствует правилу `<=200` в примечании Langfuse Attribute
   Propagation; граничный случай 200 проверяется и в backend smoke test.
3. CLI передаёт `X-Session-ID` во всех фактических запросах manifest/invoke,
   включая refresh/retry. Сервер читает этот header независимо от W3C,
   хранит проверенное значение в контексте конкретного запроса и возвращает
   его в `X-Session-ID` ответа. Валидная сессия сохраняется и при замене
   malformed traceparent новой трассой.
4. Прямой HTTP-клиент использует тот же header и формат. Отсутствующий
   header означает отсутствие сессии. Пустой/некорректный или повторённый
   header даёт HTTP 400 с обычным AXI-конвертом `USAGE` до tool dispatch
   и upstream calls. SERVER span ошибки всё равно создаётся, но сырое
   некорректное значение не отражается в attributes, logs или response.
5. Общее значение проходит через coroutine context, InvocationContext,
   SecretScope и исходящие запросы Jira/Vault как `X-Session-ID`. Не
   включать его в args/flags плагина, business payload или default headers
   общего HttpClient. При отсутствии значения header/attribute/log field
   пропускается, автоматической генерации или пустой строки нет.
6. Каждый создаваемый Tengu span C/H/S/I/J/V получает `session.id` из
   актуального контекста при создании. Одного attribute на C или S
   недостаточно. Уже созданный внешний parent P не изменяется. HTTP
   `traceparent` не переносит span attributes, поэтому `X-Session-ID`
   извлекается и преобразуется в локальный attribute на сервере отдельно.
7. Произвольный W3C baggage carrier в этой версии не вводится: перенос
   session ID выполняется явным header и контекстом выполнения. Plain
   `span.setAttribute` без наследования дочерними spans также недостаточен.

## Модель spans

Для инструментированного внешнего родителя P ожидается следующая модель:

```text
P: внешняя операция, если передана
  C: INTERNAL tengu.cli
    H1: CLIENT GET /v1/manifest, если потребовалась загрузка
      S1: SERVER GET /v1/manifest
    H2: CLIENT POST /v1/invoke
      S2: SERVER POST /v1/invoke
        I: INTERNAL tengu.tool.invoke
          V: CLIENT HTTP-вызов Vault, если понадобились секреты
          J: CLIENT HTTP-вызов Jira, если команда обращается к Jira
```

Без внешнего родителя C является root. Для прямого HTTP-клиента S является
child переданного parent либо root. V и J создаются для каждой фактической
HTTP-попытки; это соседние children I, если вызовы выполняются непосредственно
из invoke. Spans внутри удалённых Jira/Vault не обещаются без их инструментации.

### CLI и HTTP

- Один C живёт от выполнения команды до результата/ошибки. Manifest cache
  miss/expiry, self-healing unknown tool/command и повтор после 409 остаются
  внутри этого C.
- Каждая попытка manifest/invoke создаёт новый H и передаёт его контекст
  через стандартные HTTP `traceparent`/`tracestate`. Trace ID сохраняется,
  parent-id заголовка равен ID отправляющего H. При заданной сессии тот же
  запрос несёт `X-Session-ID`, а H получает `session.id`.
- При `409 STALE_MANIFEST` последовательность invoke -> manifest refresh ->
  retry invoke имеет разные H/S spans и один C/trace ID. Не менять политику
  одного retry; отказ telemetry не повторяет бизнес-операцию. Session ID
  неизменен на manifest refresh и retry, новые session IDs не создаются.

### Сервер, toolkit, плагины и секреты

- В Ktor создать SERVER span до auth, handshake, decode и validation.
  Охват входных путей: health, manifest, invoke, unmatched route;
  результатов: success/no-op, 401, 400, 404, 409, 500 и cancellation.
- Валидный HTTP parent продолжает trace. Отсутствующий или malformed
  traceparent создаёт новый root SERVER span; связанный orphan tracestate
  отбрасывается. При валидном parent некорректный tracestate отбрасывается
  отдельно. Прикладной HTTP-статус из-за этого не меняется. Использовать
  проверенный W3C propagator для HTTP.
- Контекст принадлежит ApplicationCall и coroutine execution конкретного
  запроса. Сохранять его при suspend/resume, withContext, дочерней coroutine,
  исключении и отмене. Thread-local без coroutine propagation и глобальный
  currentTrace не подходят.
- После auth и validation обернуть `ToolPlugin.invoke` в I. Передать контекст
  через границу InvocationContext/выполнения плагина, не добавляя Ktor server
  types в toolkit. Доступ к SecretScope и общему HttpClient сохраняет parent.
- Общий credential-free HttpClient создаёт CLIENT span на фактический запрос
  и inject актуального контекста. Нельзя записывать traceparent или session
  ID текущего запроса в default headers общего клиента.
- Покрыть Jira GET/POST и Vault GET/PUT/DELETE, включая metadata list и
  отсутствующие credentials. FileSecretsStore не создаёт фиктивный HTTP span.
  Авторизация, upstream credentials и границы доступа к секретам сохраняются.
- Trace context и session ID не являются доказательством авторизации.
  Session ID переносится по контракту выше; общий baggage API не вводится.

### Логи

- События запроса получают реальные `trace_id`, `span_id`, `parent_span_id`
  при наличии родителя. У `http_request_completed` span_id принадлежит S;
  у существующего события внутри дочерней операции используется её span.
  У root parent ID пропускается. Startup/shutdown вне запроса не получают IDs.
- При заданной сессии события запроса дополнительно получают `session_id`
  из того же immutable context; поле совпадает с `session.id` его spans.
  Это разрешённое поле корреляции, а не копирование набора HTTP headers.
  При отсутствии сессии поле пропускается; в metric labels оно не добавляется.
- Сохранить request_id/X-Request-ID, outcomes, redaction, уровни и JSON Lines.
  Обновить encoder allowlist и assertions TNG-03, которые сейчас запрещают
  tracing-поля до реализации трассировки; добавить allowlist `session_id`.
- AsyncAppender получает готовые снимки IDs без позднего чтения активного
  coroutine/thread context. При interleaving запросов A/B IDs не смешиваются.
- Не копировать тела, auth, секреты, query или сырые propagation headers
  в span attributes/events. CLI stdout сохраняет AXI payload.

## Завершение, sampling и доставка

- Созданный span завершается один раз при success/no-op, usage/runtime error,
  upstream exception/timeout и cancellation. Fault barrier не должен
  проглатывать отмену или оставлять span открытым.
- Централизовать финализацию CLI до exitProcess, включая CliRuntime.fail и
  ToolInvocation. После завершения C выполнить ограниченный flush/shutdown;
  точный предел фиксируется до Ready.
- Встроить telemetry shutdown в существующий lifecycle owner сервера:
  остановка приёма запросов, завершение application/HTTP work, завершение
  spans и flush exporter, затем завершение logging runtime.
- Отказ exporter не меняет результат, exit code, HTTP-ответ или число
  бизнес-попыток. Очереди и ожидания ограничены. Telemetry HTTP не
  инструментирует себя и не несёт hub/Jira/Vault credentials.
- Parent-based sampling сохраняет решение валидного внешнего родителя.
  Unsampled контекст продолжает распространяться без обещания экспорта.
  Создание контекста, запись и доставка различаются; sampled flag не
  гарантирует приём backend.
- Root sampler, включение экспорта, endpoint/protocol, flush/backpressure
  закрепляются в техническом решении ниже. E2E обязательно включает запись
  и локальный exporter; отсутствие экспорта в отключённом режиме не PASS.

## План изменений

| Область | Обязательное изменение |
|---|---|
| `cli/.../Main.kt` | Global options до dispatch, invocation context, help/version exceptions |
| `cli/.../CliRuntime.kt`, `ToolInvocation.kt` | Один C, cache/refresh/retry, финализация управляемых выходов |
| `cli/.../ServerClient.kt` | CLIENT spans, W3C inject и X-Session-ID для manifest/invoke |
| `server/.../Main.kt`, `ServerDeps.kt` | SDK lifecycle, SERVER/INTERNAL spans, session validation и coroutine context |
| `toolkit/.../ToolPlugin.kt`, `SecretScope.kt` | Trace/session context invoke/secret calls без server types и transport flags |
| `plugins/jira/.../JiraApiClient.kt`, `server/.../secrets/VaultSecretsStore.kt` | Исходящие HTTP spans/propagation; проверить каждый перечисленный метод |
| `server/.../logging/RequestLogging.kt`, `server/src/main/resources/logback.xml` | Корреляция событий и allowlist trace/span/parent/session fields |
| Gradle и платформенный слой | Подтверждённые SDK/exporter для JVM и Native; CLI не зависит от toolkit/plugins |
| Новые `scripts/e2e-tracing.sh`, `scripts/assert-tracing.py`, module tests | Native CLI, real server, fake Jira/Vault, независимый receiver/oracle |
| README, CLI/server README, ARCHITECTURE, `docs/server-logging.md` | Публичный контракт, настройка экспорта, схема spans и диагностика |

## Evidence contract и критерии готовности

Добавить изолированный `scripts/e2e-tracing.sh`: Native CLI с временными
config/cache, реальный JVM/Ktor server, управляемые HTTP doubles Jira/Vault
и локальный receiver экспортированных spans. Корпоративные системы/PAT не
нужны. Собирать stdout/stderr/exit code, JSON logs, upstream headers и экспорт.
Независимый oracle `scripts/assert-tracing.py` не импортирует production
propagator/SDK-код Tengu: сверяет известные W3C vectors и граф по
traceId/spanId/parentSpanId. Формат и запуск receiver фиксируются после выбора
export protocol. Каждый AC имеет stimulus, public seam, result и oracle.

| ID | Stimulus | Public seam | Наблюдаемый результат | Independent oracle |
|---|---|---|---|---|
| AC-01 | Известный sampled parent + state через argv и отдельно env | Native CLI -> hub -> fake Jira/Vault, receiver | Один trace ID, C child P, H/S/I/J/V связаны по модели; state дошёл до upstream | Фиксированные IDs, captured headers, независимо построенный граф |
| AC-02 | Нет parent в CLI; отдельно direct HTTP без parent | Receiver, upstream headers | Root C либо S, ненулевые IDs, без вымышленного внешнего parent | Independent decode экспорта и проверка root/children |
| AC-03 | Разные argv/env, замена parent, форма `--name=value` | CLI output/exit, первый hub request | Precedence по контракту, state перекрытого env-parent отсутствует | Различные vectors A/B и HTTP capture |
| AC-04 | Empty/missing flag, duplicate, all-zero ID, bad length/hex/version, invalid/orphan state | CLI output/exit, HTTP counters | AXI usage error/exit 2 до сети без отражения сырого значения | W3C vectors, ожидаемый exit, нулевые HTTP counters |
| AC-05 | Direct HTTP valid/absent/malformed parent; valid/invalid/orphan state | HTTP response, receiver, upstream headers | Продолжение либо root/discard по контракту; прежний business response | W3C vectors, graph и контрольный ответ без tracing |
| AC-06 | Empty/expired cache, unknown tool/command refresh, stale manifest 409 | Native CLI, hub requests, receiver | Один C, отдельные H/S; один retry для 409 | Управляемый manifest, counters и graph |
| AC-07 | Health/manifest/invoke/unknown route; success/no-op, 401/400/404/409/500 | Real HTTP, receiver, JSON logs | S охватывает early returns, I/J/V только при выполнении; completion log связан с S | Фиксированные запросы/статусы, сопоставление IDs экспорта и logs |
| AC-08 | Jira GET/POST, Vault GET/PUT/DELETE и metadata GET, missing secrets, FileSecretsStore | Upstream HTTP, receiver | CLIENT на реальный HTTP request, его ID в parent-id; FileSecretsStore без HTTP span | Endpoint/method counters против экспорта |
| AC-09 | Два CLI с parent A/B: разные session IDs; затем общий session ID; задержки upstream, withContext/child coroutine и обратное завершение | CLI processes, общий server/client, headers/export/logs | A/B изолированы; сессия не протекает между вызовами и сохраняется при смене coroutine; одинаковая сессия не сливает trace IDs и не сериализует операции; общий env/config/cache не мутируется | Барьеры fixture вместо sleep, ожидаемые A/B graphs и точные значения session.id/session_id |
| AC-10 | Test plugin suspend/withContext/child coroutine, exception, timeout, cancellation, server shutdown | Real HTTP, receiver, logs | Parent сохранён; начатые spans завершены один раз; процесс выходит | Барьеры/ошибки doubles, уникальные IDs, end timestamps и exit |
| AC-11 | Валидный tracing input; CLI success/no-op/ошибка аргументов плагина/runtime error; receiver reachable/refused/hanging | CLI process, receiver | При рабочем receiver завершённый C доставлен до нормального выхода; при отказе прежний business result и ограниченный выход | OS exit/deadline, captured output и receiver records; предел до Ready |
| AC-12 | Unsampled и sampled parent | HTTP headers, receiver | Unsampled context сохранён; sampled fixture экспортирует граф | Фиксированные flags 00/01, независимый header decode и receiver |
| AC-13 | Version/help, cache-only dashboard, setup с session ID и без; auth/body/query sentinels | CLI streams/network, export/logs | Version без сети/SDK; local commands без лишних hub calls; прежний output/exit; session.id только на созданных spans, без секретных sentinels | Golden output, counters, dependency inspection и literal scan |
| AC-14 | Linux x64, Windows x64, macOS arm64 | Реальный Native package на своём host | Link + запуск + AC-01/02/09/11 для SDK/exporter; самодостаточность поставки | Host process fixture и receiver, а не только compile |
| AC-15 | CLI --session-id и direct HTTP X-Session-ID; с parent и без; cache miss и 409 refresh/retry; Jira/Vault | Headers в hub/upstream/response, receiver и JSON logs | Одинаковое значение session.id на C/H/S/I/J/V, session_id в событиях запроса и X-Session-ID на перечисленных HTTP hops/ответах; trace lineage прежний | Синтетический ID, независимый разбор каждого вида span из модели и точное сопоставление headers/logs |
| AC-16 | Session отсутствует после завершённого вызова с сессией; отдельно traceparent без session; в env задан TENGU_SESSION_ID | CLI/direct HTTP, receiver, headers/logs | Header/attribute/log field отсутствуют, env не является источником; нет auto-generated/default session или наследования предыдущего значения | Последовательность контролируемых запросов через общий server/client и проверки отсутствия ключей |
| AC-17 | Session длиной 1/199/200/201, empty/missing/duplicate flag, space/control/Unicode, duplicate HTTP header; valid session + malformed W3C | CLI exit/output, server HTTP, receiver/logs и upstream counters | Валидные ID проходят неизменными; invalid CLI -> exit 2 без сети, invalid HTTP -> 400 USAGE без upstream/отражения значения; valid session сохраняется при новом server root | Независимые ASCII/length vectors, expected status, captured bytes и counters |
| AC-18 | Два разных sampled trace IDs с одной сессией, третий с другой, четвёртый без сессии | OTLP receiver; smoke test выбранного Langfuse или MLflow через read API/query | session.id присутствует на C/H/S/I/J/V; запрос сессии возвращает ровно первые две трассы и их observations, без объединения trace IDs; ID длиной 200 сохраняется | Точные входные IDs, независимый OTLP decode, запрос backend Session/trace metadata; закрепить версию и команду до Ready |

Для AC-04/05 использовать W3C test suite и vectors контракта; версии/edge
cases перечислить рядом с тестами. AC-10 проверяет контролируемую отмену;
SIGKILL/потеря питания не обещают доставку. Между хостами oracle проверяет
IDs, не предполагает идеальную синхронизацию часов.

После реализации: `./gradlew build`, `./gradlew check`, Windows cross-compile,
`bash scripts/e2e.sh` на Linux/Windows и новый tracing E2E. Существующий e2e.sh
не работает на macOS; tracing fixture должен иметь macOS-запуск. Cross-link
не доказывает runtime. Logging assertions TNG-03 меняются только для реальных
trace/span/session fields. Для AC-18 до Ready закрепить локальную тестовую
версию одного backend (Langfuse или MLflow), команду запуска и read-запрос,
проверяющий группировку. Внешний LLM для fixture не требуется; это проверка
стандартного OTLP mapping, а не реализация vendor SDK или production deployment.
Production build для редактирования этой issue не требуется.

## Не входит

- Автогенерация/постоянное хранение session ID, session env fallback,
  session registry/API, baggage и отдельные trace-id/span-id флаги.
- Адаптеры/установка hooks для Codex/OpenCode, изменение Vigilant.
- Восстановление LLM-трассы из текста/task ID/истории: нужен внешний W3C parent.
- Production collector/backend, dashboards, vendor SDK и backend selector
  в Tengu; OTLP-миграция логов, metrics. Локальный compatibility smoke test
  сессий входит по AC-18.
- Spans на каждую функцию/DTO или внутри Jira/Vault, изменение бизнес-операций,
  retry policy или plugin descriptors.
- Доставка после SIGKILL, бесконечный flush или собственная durable queue.

## Технические решения до Ready

1. **Native SDK и coroutine propagation.** Подтвердить версии библиотек и
   exporter для linuxX64/mingwX64/macosArm64 реальной линковкой и минимальным
   экспортом parent/child. JVM-only зависимость не подходит CLI. На 2026-09-10
   официальная страница OpenTelemetry Kotlin перечисляет Android, JVM, iOS,
   JavaScript; это не подтверждает Native desktop targets Tengu. Выбор
   SDK/interop/адаптации является технической проработкой в этой issue,
   а не разрешением убрать spans CLI.
2. **Экспорт и lifecycle.** Выбрать совместимый формат/протокол CLI и сервера,
   точные env keys/defaults, root sampler, режим без exporter, TLS/auth config
   и finite timeout/queue limits. Предпочтение стандартному OTLP и SDK-механизмам,
   без второго логирования в stdout. Закрепить receiver fixture, команды,
   проверенную версию backend/session mapping AC-18 и измеримые пределы
   AC-10/11/14; оценить flush короткого CLI по baseline.

После этих пунктов заменить раздел точным решением и синхронно перевести
issue/реестр в Ready for implementation. Продуктовых вопросов по carrier,
охвату сервера и session ID не осталось. До этого не объявлять Native-поставку
готовой к автономной реализации.

## Рассмотренные альтернативы

- Только копировать заголовок CLI -> server: не покрывает собственные spans,
  серверную обработку, исходящие запросы и требуемое дерево операций.
- Current trace/session в общем env/config: смешивает параллельные вызовы.
- Один trace ID на всю сессию: теряет разделение независимых операций;
  сессия реализуется отдельным attribute для группировки нескольких трасс.
- Сессия только на root span: недостаточно для фильтрации дочерних
  observations в Langfuse; session.id нужен на C/H/S/I/J/V.
- Vendor-specific session attributes/SDK: для актуального OTLP mapping
  Langfuse и MLflow достаточно общего session.id.
- Span-id без trace ID/flags: неполный W3C-контекст.
- Убрать CLI span из-за Native SDK: нарушает согласованную границу;
  сначала проверить реализацию на трёх targets.

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.0
  Acceptance:   0.25
  Boundaries:   0.0
  Alternatives: 0.25
  Assumptions:  0.5
  Aggregate:    0.2
```

Низкий aggregate не снимает конкретный readiness blocker: Native SDK,
export receiver и пределы lifecycle ещё не подтверждены.

## Источники

- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [W3C test suite](https://github.com/w3c/trace-context/tree/main/test)
- [OTel: Kotlin SDK, supported platforms](https://opentelemetry.io/docs/languages/kotlin/getting-started/)
- [OTel: Context propagation](https://opentelemetry.io/docs/concepts/context-propagation/)
- [OTel: Protocol exporter](https://opentelemetry.io/docs/specs/otel/protocol/exporter/)
- [Langfuse: Sessions](https://langfuse.com/docs/observability/features/sessions)
- [Langfuse: OpenTelemetry mapping и propagation](https://langfuse.com/integrations/native/opentelemetry)
- [MLflow: Track users and sessions](https://mlflow.org/docs/latest/genai/tracing/track-users-sessions/)
- [MLflow: Session attribute mapping](https://mlflow.org/docs/latest/genai/tracing/opentelemetry/attribute-mapping/)
- [AXI](../../.agents/skills/axi/SKILL.md)
