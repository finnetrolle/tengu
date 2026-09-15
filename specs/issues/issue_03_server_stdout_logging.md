# TNG-03: Структурированные серверные логи в STDOUT

- **ID:** `TNG-03`
- **Тип:** Issue
- **Статус:** Done
- **Зависит от:** нет
- **Связана с:** [TNG-04: W3C-трассировка](issue_04_w3c_distributed_tracing.md)
- **Интервью:** завершено, критерии приняты, осталось 0 вопросов.

## Результат

Сервер Tengu в Docker пишет JSON Lines в STDOUT. По записям можно определить,
кто вызвал инструмент, какую команду, сколько заняло обращение и чем оно
завершилось. Отдельная настройка включает запись тела ответа.

Поток: `SLF4J → штатный Logback AsyncAppender → ConsoleAppender → STDOUT`.
Формирование событий принадлежит серверу; сбор и доставка после STDOUT
принадлежат инфраструктуре. Fluentd, OTel Collector и backend не требуются
для реализации или тестирования этой issue.

## Контекст и границы

- Сервер использует JVM 21, Ktor 3.5.2 и Logback 1.5.18.
  Зависимость Logback уже есть, конфигурации логирования в репозитории нет.
- Маршруты находятся в `server/.../ServerDeps.kt`, сборка приложения в
  `server/.../Main.kt`. Сейчас логируются отдельные ошибки body/plugin;
  общего события завершения запроса и request ID нет.
- `HubAuth.userIdFor` возвращает проверенный userId из статического соответствия
  токенов. `FileSecretsStore` уже предупреждает об unencrypted dev-хранилище.
- Сервер возвращает JSON: `InvokeResponse` либо `AxiErrorEnvelope`.
  TOON формируется в CLI. Содержимое и HTTP-статусы существующих ответов,
  descriptor surface и CLI не меняются; manifestVersion не повышается.
- Логирование относится к запросам, дошедшим до Ktor application pipeline.
  Сообщения JVM/launcher до инициализации приложения, ошибки разбора
  HTTP-транспорта до application pipeline и аварийные дампы JVM не входят
  в контракт формата прикладных записей.

Не входят: W3C tracing/экспорт spans (TNG-04), новые модули и logging API для
плагинов, файлы/ротация логов, сеть из logging appender, collector/backend,
DLP и guardrails, собственные очереди/потоки/доставка/повторы/резервирование,
классы над Logback для предотвращения потерь и нагрузочные испытания
медленного читателя. Production-код реализуется отдельной задачей после
этого интервью.

## Формат записи

UTF-8, один JSON-объект и один LF на запись. Без ANSI, текстового префикса,
pretty printing и многострочного stack trace вне JSON-строки.
Переносы и кавычки в значениях экранирует готовый JSON encoder.

### Базовые поля

| Поле | Тип и значение |
|---|---|
| `timestamp` | Строка ISO 8601, UTC с суффиксом Z; время создания события, не выгрузки очереди |
| `level` | Строка DEBUG, INFO, WARN или ERROR |
| `service_name` | Строка `tengu-server` |
| `service_version` | Строка из `ServerInfo.VERSION`; не дублировать версию литералом в XML |
| `logger` | Полное имя SLF4J logger |
| `message` | Человекочитаемое сообщение |
| `event` | Стабильный код события Tengu из таблицы ниже; у сторонних библиотек поле отсутствует |

Дополнительные поля записываются на верхнем уровне с указанными ниже типами.
Не экспортировать окружение, все свойства LoggerContext или весь MDC.
Неизвестные/неприменимые значения отсутствуют; не подставлять пустую строку,
фиктивного пользователя или случайный tracing ID.

Примеры итоговых записей при выключенном body capture:

```json
{"timestamp":"2026-09-09T12:00:00.000Z","level":"INFO","service_name":"tengu-server","service_version":"0.1.0","logger":"ru.finnetrolle.tengu.server.logging.RequestLogging","event":"http_request_completed","message":"HTTP request completed","request_id":"e67d6f92-1b34-4c51-94a8-5f802c7e9230","user_id":"alice","http_method":"POST","http_route":"/v1/invoke","http_status":200,"duration_ms":12,"outcome":"ok","tool":"status","command":["status"]}
```

```json
{"timestamp":"2026-09-09T12:00:01.000Z","level":"WARN","service_name":"tengu-server","service_version":"0.1.0","logger":"ru.finnetrolle.tengu.server.logging.RequestLogging","event":"http_request_completed","message":"HTTP request completed","request_id":"64792f37-3847-4c40-82f2-c53e5f146b0c","http_method":"POST","http_route":"/v1/invoke","http_status":401,"duration_ms":1,"outcome":"error","error_kind":"AUTH"}
```

### Завершение запроса

| Поле | Тип и семантика |
|---|---|
| `request_id` | Сгенерированный сервером UUID v4, lowercase с дефисами |
| `user_id` | Строка из успешной серверной авторизации; иначе отсутствует |
| `http_method` | Метод HTTP |
| `http_route` | Один из `/v1/health`, `/v1/manifest`, `/v1/invoke`, `unmatched`; без query string и сырого URI |
| `http_status` | Целое число фактически сформированного ответа; отсутствует, если ответа нет |
| `duration_ms` | Неотрицательное целое, время до завершения обработки, по монотонным часам |
| `outcome` | `ok`, `noop`, `error` или `cancelled` |
| `tool` | Зарегистрированное имя инструмента, когда разрешено по descriptor |
| `command` | Массив строк зарегистрированного commandPath; например `["issues","list"]` |
| `error_kind` | Значение существующего ErrorKind, если сформирован соответствующий envelope |
| `exception_type` | Имя класса исключения, если оно перехвачено приложением |
| `exception_stacktrace` | Строка с кадрами стека этого исключения без его message |

Создать request ID до авторизации и вернуть его в `X-Request-ID`, включая
ошибочные ответы, сформированные приложением. Входящий `X-Request-ID`
не использовать как серверный ID. Сырой заголовок не логировать.

Для health пользователь отсутствует, даже если клиент прислал bearer header:
endpoint не выполняет авторизацию. При 401 userId неизвестен. После успешной
авторизации сохранять userId также для 400/404/409/502/500.
Не брать пользователя из request body или произвольного клиентского заголовка.
Не выводить неизвестные tool/command из пользовательского ввода как metadata.

Контекст хранить на уровне ApplicationCall; к управляемым приложением событиям
передавать поля явно через SLF4J. Снимок полей создаётся до передачи в AsyncAppender:
изменяемые maps/call objects не должны попадать в асинхронную очередь.
Не использовать обычный thread-local как владельца контекста coroutine.

## События и жизненный цикл

| Event | Уровень | Момент |
|---|---|---|
| `server_starting` | INFO | После успешной проверки logging config, до создания сервиса |
| `server_ready` | INFO | Когда HTTP listener готов принимать запросы |
| `server_stopping` | INFO | Начало штатного завершения |
| `server_stopped` | INFO | После остановки engine и закрытия HTTP client, до остановки Logback |
| `server_start_failed` | ERROR | Перехваченный сбой сборки/старта сервера |
| `server_configuration_error` | ERROR | Неверная logging env-настройка; запуск прекращается |
| `dev_secrets_enabled` | WARN | Активировано незашифрованное FileSecretsStore |
| `http_request_completed` | По правилам ниже | Один итоговый event на завершившийся application call |

Правила уровня итогового event, в порядке приоритета:
отмена без ответа → WARN; HTTP 5xx → ERROR; HTTP 4xx → WARN;
успешный GET health → DEBUG; остальные завершённые ответы → INFO.
Итоговый event подчиняется порогу логирования.

Ошибки чтения/декодирования body и сбой plugin отражаются в итоговом event.
Заменить текущие отдельные WARN/ERROR этих путей записью контекста для
итогового события, чтобы одна ошибка не создавала два сообщения о завершении.
В итоговый event добавляется разрешённое тело; отдельного дубликата body-event нет.

`outcome=noop` только для AxiResult.Noop; `error` для error envelope,
необработанного сбоя либо HTTP 4xx/5xx; обычный успех → `ok`.
Cancellation, наблюдаемая как CancellationException на logging seam, не
превращается логированием в новый HTTP 500: сохранить исключение/существующую
семантику выполнения, записать `cancelled`. Эта issue не переделывает
существующий fault barrier plugin и его cancellation policy.

Порядок событий остановки:
прекратить приём новых запросов, завершить штатную остановку engine и
HTTP client, записать `server_stopped`, затем вызвать `LoggerContext.stop()`.
Не полагаться на случайный порядок независимых JVM shutdown hooks;
согласовать остановку ресурсов и логгера в одном управляемом lifecycle.
После stop Logback приложение не должно пытаться записывать lifecycle events.

## Тело ответа и содержимое

Только для POST /v1/invoke, после успешной авторизации и валидации команды,
при включённом переключателе и разрешённом уровне итогового события.

- Тела health/manifest, ответы до успешной авторизации/валидации,
  команды с первым сегментом `auth` и команды, чей descriptor содержит
  хотя бы один secret flag, не записываются. Исключение применяется даже
  если конкретный запрос не передал secret flag и даже при ошибочном ответе.
- Для разрешённой команды записывается окончательный JSON-текст ответа,
  сформированного сервером, включая envelope/helpHints. Не логировать
  отдельный исходный ответ Jira/Vault.
- `response_body` - строка с этим текстом или его UTF-8 префиксом.
  Максимум 16 384 UTF-8 байта в значении до JSON-экранирования самой записи.
  Префикс заканчивается на границе Unicode code point, без replacement character.
- `response_body_bytes` - целое, исходный размер полного HTTP body в UTF-8.
  `response_body_truncated` - boolean, true только при превышении лимита.
  Все три поля отсутствуют, когда тело отключено или исключено.
- При усечении внутренний JSON-текст может быть неполным; наружная запись
  остаётся валидным JSON. Не обрезать итоговую JSON-строку побайтово.
- При выключенном body capture не создавать дополнительную сериализацию,
  копию или retained reference полного ответа исключительно ради логов.

Не логировать входные body, значения args/flags или HTTP-заголовки.
Сообщения Tengu являются стабильным текстом; не включать в них сырой URI,
неизвестную команду, текст parse exception или `AxiErrorEnvelope.message`
в обход выключенного body capture.

Для перехваченных исключений сформировать только `exception_type` и
`exception_stacktrace` из класса и StackTraceElement. Не передавать сырой
Throwable в logger и не использовать Throwable.toString/printStackTrace.
Причины и suppressed messages не выводятся; сериализация дерева исключений
не требуется. Отключить автоматический stackTrace provider JSON encoder,
чтобы он не добавил текст Throwable отдельно.

Пользователь допускает секреты, вручную вставленные в произвольный бизнес-текст
Jira, при включённой записи ответов. Поиск таких секретов принадлежит соседнему
guardrails-проекту и не является обязанностью этой issue. Согласованные
структурные исключения сохраняются. Не добавлять DLP, regex-redaction или
зависимость работоспособности логирования от guardrails.

## Конфигурация и Logback

| Env | Отсутствует | Допустимые значения |
|---|---|---|
| `TENGU_LOG_LEVEL` | INFO | DEBUG, INFO, WARN, ERROR |
| `TENGU_LOG_RESPONSE_BODY` | 0 | 0, 1 |

Значения точные: пустая строка, lowercase, лишние пробелы и другие значения
считаются неверными. Чтение только при запуске, hot reload отсутствует.
Проверять сначала LOG_LEVEL, затем LOG_RESPONSE_BODY. Первая неверная настройка
даёт один `server_configuration_error` с полями `config_key` и
`allowed_values` (массив строк), ERROR в STDOUT и exit 1 до открытия порта.
Сырое неверное значение не записывать. Использовать корректный bootstrap
Logback config, чтобы ошибка не исчезла из-за самого неверного уровня.

Уровень Tengu задаётся для `ru.finnetrolle.tengu`; root для библиотек - WARN.
DEBUG не включает body capture. Body capture не меняет уровень события.

Использовать штатные компоненты:

- `ch.qos.logback.classic.AsyncAppender`;
- вложенный `ch.qos.logback.core.ConsoleAppender`, target System.out,
  immediateFlush=true;
- готовый `LoggingEventCompositeJsonEncoder` из
  `net.logstash.logback:logstash-logback-encoder:9.0`: timestamp, level,
  logger, message, статические поля сервиса и согласованные SLF4J key-values.
  Без самописного encoder, appender, listener или logging facade.

Технические настройки AsyncAppender закрепить в конфигурации:
queueSize=256, discardingThreshold=0, neverBlock=false, maxFlushTime=1000.
Это штатная очередь Logback: нет раннего отбрасывания INFO и нет собственного
механизма компенсации потерь. При неожиданном заполнении действует стандартное
ожидание Logback. Не добавлять env-переключатели этих параметров.

Эксплуатационное допущение пользователя: STDOUT читается быстрее производства
логов. Не проектировать отдельную обработку перегрузки и не обещать гарантий
при SIGKILL, отказе ОС или после STDOUT. Для завершения используется штатный
stop Logback с указанным timeout; своих фоновых потоков, очередей и retry нет.

Encoder 9.0 требует Java 17+, Logback 1.5+ и Jackson 3; сервер JVM 21 подходит
по документированным требованиям. Зафиксировать зависимости в version catalog,
не менять ProtocolJson/HTTP-сериализацию. Проверить runtime classpath запуском
собранного сервера, а не только компиляцией.

## Связь с OTel и TNG-04

Документировать отображение без реализации collector/exporter:

| Поле Tengu | OTel |
|---|---|
| timestamp | Timestamp |
| level | SeverityText; DEBUG/INFO/WARN/ERROR → SeverityNumber 5/9/13/17 |
| message | Body |
| service_name, service_version | Resource attributes service.name, service.version |
| logger | InstrumentationScope.name |
| event | EventName |
| request/context/result/body fields | Attributes, сохраняя типы |

Это прикладной JSON-формат, не OTLP wire payload. Наблюдаемое время доставки
определяет collector. Логи сами по себе не создают spans.

В TNG-03 request_id обеспечивает локальную корреляцию. TNG-04 добавит реальные
trace/span IDs после реализации трассировки. Её согласованная цель:
продолжать внешний W3C-контекст через CLI, сервер и исходящие Jira/Vault
при наличии, иначе начинать трассу в CLI. Необязательный `--session-id`
связывает несколько трасс одной беседы через `X-Session-ID`, span attribute
`session.id` и поле серверных логов `session_id`; без параметра не генерируется.
Этот контракт планируется в TNG-04. Не добавлять пустые tracing-поля,
фиктивные IDs, SDK или будущие extension points в TNG-03.
TNG-04 не блокирует реализацию логирования.

## План изменений

Пути относительно корня репозитория; новые файлы перечислены как план реализации.
Сокращение `server/.../` обозначает
`server/src/main/kotlin/ru/finnetrolle/tengu/server/`, а `server/src/test/.../`
обозначает `server/src/test/kotlin/ru/finnetrolle/tengu/server/`.

| Компонент | Изменение |
|---|---|
| `gradle/libs.versions.toml`, `server/build.gradle.kts` | Encoder dependency; fixture запуска установленного JVM distribution |
| `server/src/main/resources/logback.xml` (новый) | Готовые encoder, AsyncAppender и ConsoleAppender; фиксированный набор providers |
| `server/.../logging/LoggingConfig.kt` (новый) | Две env-настройки, bootstrap и конфигурация context; без logging facade |
| `server/.../logging/RequestLogging.kt` (новый) | ApplicationCall context, итоговый event, UTF-8 prefix, разрешённые поля исключения |
| `server/.../Main.kt` | Ранняя logging initialization и согласованный lifecycle shutdown |
| `server/.../ServerDeps.kt` | Привязка user/tool/command/result/error/body к call; удалить дубли старых error logs |
| `server/.../secrets/FileSecretsStore.kt` | Стабильный dev_secrets_enabled event |
| `server/src/test/.../ServerLoggingTest.kt` (новый) | Матрица HTTP/контекста/body и контролируемая конкуренция |
| `server/src/test/.../LogEncodingTest.kt` (новый) | Проверка байтов настоящего encoder/AsyncAppender |
| `server/src/test/.../LoggingProcessTest.kt` (новый) | Отдельный JVM процесс, env, exit и listener |
| `scripts/e2e-logging.sh` и `scripts/assert-logging.py` (новые) | Docker fixture и независимые JSON/UTF-8 assertions |
| `scripts/e2e.sh` | Сохранить S1-S8; после них вручную запускать контейнерную проверку в Linux окружении |
| `README.md`, `server/README.md`, `ARCHITECTURE.md` | Настройки, schema, примеры, OTel mapping и границы ответственности |

Не менять production-код `:cli`, `:protocol`, `:toolkit` или Jira-команд.
Не добавлять production test-only endpoint или плагин: HTTP fixtures используют
существующий injectable `ServerDeps` и тестовые ToolPlugin в test source set.

## Приёмка и evidence contracts

### AC1. HTTP-события и уровни

- **Stimulus:** выполнить конечную матрицу ниже в ServerLoggingTest при DEBUG.
  Для Noop и plugin errors использовать тестовый ToolPlugin через ServerDeps.
- **Public seam:** HTTP-ответы testApplication и байты реального encoder,
  прошедшие через AsyncAppender в тестовый OutputStreamAppender.
- **Observable result:** по одному http_request_completed на request ID
  с указанными status/level/outcome; schema и поля совпадают с контрактом.
  Длительность неотрицательна; для plugin с gate итогового event нет до
  завершения gate. Нет повторной записи старого error/warn из маршрутов.
- **Independent oracle:** таблица ниже, независимый JSON parser
  (kotlinx.serialization, не encoder Jackson), HTTP headers и заданные
  результаты fake plugin. Не использовать production enum mapping/formatter
  для построения ожидаемых значений.

| Case | Stimulus | Status | Level | Outcome / error_kind |
|---|---|---:|---|---|
| health | GET /v1/health | 200 | DEBUG | ok |
| manifest | GET /v1/manifest, valid auth | 200 | INFO | ok |
| invoke-ok | status invoke | 200 | INFO | ok |
| invoke-noop | fake Noop | 200 | INFO | noop |
| missing-auth | manifest без токена | 401 | WARN | error / AUTH |
| bad-auth | invoke с неверным токеном | 401 | WARN | error / AUTH |
| malformed | invoke с некорректным JSON, valid auth | 400 | WARN | error / USAGE |
| unknown-tool | invoke неизвестного инструмента | 400 | WARN | error / USAGE |
| unknown-command | invoke неизвестной команды | 400 | WARN | error / USAGE |
| invalid-flags | неизвестный/невалидный флаг | 400 | WARN | error / USAGE |
| stale | иной X-Tengu-Manifest-Version | 409 | WARN | error / STALE_MANIFEST |
| missing-entity | fake Err(NOT_FOUND) | 404 | WARN | error / NOT_FOUND |
| upstream | fake Err(UPSTREAM) | 502 | ERROR | error / UPSTREAM |
| internal | fake plugin throws | 500 | ERROR | error / INTERNAL, exception fields |
| unmatched | GET неизвестного маршрута | 404 | WARN | error, route=unmatched |

Отдельный test-only маршрут в testApplication отменяет call после установки
logging hook: CancellationException проходит через seam, event имеет
outcome=cancelled/WARN и не выдумывает отправленный HTTP status. Production
маршруты ради fixture не расширять.

### AC2. Изоляция и корреляция

- **Stimulus:** два перекрывающихся invoke от alice/bob; fake plugin удерживает
  первый через CompletableDeferred, завершает второй, затем освобождает первый.
  После них запрос с неверным bearer. Повторить с одинаковым присланным клиентом
  X-Request-ID; health вызвать также с valid bearer.
- **Public seam:** HTTP X-Request-ID и закодированные логи, считанные после drain.
- **Observable result:** различные серверные UUID; записи alice/bob совпадают
  со своими ответами и пользователями при обратном порядке завершения;
  неверная авторизация и health не получают чужого user_id. Присланный ID
  не заменяет серверный. Нет потери контекста между созданием и кодированием.
- **Independent oracle:** фиксированное соответствие тестовых токенов именам,
  идентификаторы из response headers и порядок, заданный gate.
  Не использовать Thread.sleep для организации конкуренции.

### AC3. Содержимое и границы UTF-8

- **Stimulus:** конечные cases BODY-OFF (в том числе DEBUG), BODY-ON-OK,
  BODY-ON-NOOP, BODY-ON-ERR после валидации; EXCLUDE-AUTH,
  EXCLUDE-SECRET-DESCRIPTOR с переданным и с отсутствующим optional secret flag,
  EXCLUDE-PREVALIDATION, EXCLUDE-UNAUTHORIZED, EXCLUDE-HEALTH, EXCLUDE-MANIFEST.
  Отдельно ответы с LF/CR/кавычками/кириллицей/emoji; полные HTTP body размером
  16 383, 16 384 и 16 385 байт, включая multibyte символ на границе.
- **Public seam:** HTTP body и JSON-строка итогового события ServerLoggingTest.
- **Observable result:** присутствие трёх response_body-полей только в разрешённых
  cases; точный body до лимита, корректный Unicode prefix после него, исходный
  размер и признак усечения. Наружная запись всегда разбирается как одна строка.
  Входные marker values не появляются в metadata/message.
- **Independent oracle:** строка реально полученного HTTP body; размер через
  UTF-8 encoding в тесте; заданные Unicode fixtures, проверенные дополнительно
  Python json/bytes в контейнерном сценарии. Не вызывать production prefix
  function для вычисления expected.

Exception fixture содержит уникальные маркеры в message, cause и suppressed,
а также известный кадр тестового класса. Public seam - итоговый event и stdout.
Oracle - заданные маркеры и имя кадра. Ожидаются exception_type/stack frames,
отсутствуют три message-маркера и сырой Throwable dump, независимо от body flag.
Отдельный разрешённый ответ с произвольным secret-like бизнес-текстом сохраняет
его: эвристическая редакция не должна незаметно менять согласованный body.

### AC4. Конфигурация и фильтрация

- **Stimulus:** defaults и матрица DEBUG/INFO/WARN/ERROR × body 0/1.
  В каждом случае вызвать health, обычный успех, 400 и 500.
  Неверные env: для LOG_LEVEL - пусто, debug, VERBOSE, пробелы вокруг INFO;
  для BODY - пусто, true, 2, пробелы вокруг 1; также обе переменные неверны.
- **Public seam:** отдельный JVM процесс из installDist с независимыми
  stdout/stderr streams, exit code и HTTP-listener. HTTP/plugin cases для
  фильтрации выполняются также в изолированном logging context.
- **Observable result:** defaults=INFO/0; DEBUG видит четыре класса событий,
  INFO скрывает успешный health, WARN сохраняет только 400/500, ERROR только 500.
  Тела не меняют уровень. Библиотечный test logger INFO скрыт, WARN виден.
  Invalid env: ровно один configuration ERROR, корректные config_key и
  allowed_values, exit 1, listener не запускается, сырое значение отсутствует.
- **Independent oracle:** заданная выше таблица, sentinel в неверном значении,
  процессные exit/streams. В fixture проверять порядок validation до engine
  construction и сетевое отсутствие listener; не считать один случайный
  неуспешный TCP probe доказательством отсутствия старта.
- **Fixture:** LoggingProcessTest запускает Java main из
  `server/build/install/server/lib/*`; `:server:test` получает installDist
  как подготовку. Ребёнок не запускает Gradle. При тестах менять только
  окружение дочернего процесса.

### AC5. Упаковка, STDOUT и остановка

- **Stimulus / fixture:** `bash scripts/e2e-logging.sh` собирает существующий
  Dockerfile и запускает контейнер без TTY, с двумя фиктивными hub users,
  dev secrets во временном каталоге и без Jira/Vault. Вызвать manifest,
  status от двух пользователей, malformed invoke и invalid auth.
  Повторить с DEBUG/body=1. Выполнить `docker stop --time 10`.
- **Public seam:** `docker logs` без `--timestamps`, stdout/stderr раздельно,
  HTTP headers/body и Docker inspect. Не смешивать build output с server logs.
- **Observable result:** строки процесса являются JSON без ANSI/пустых строк;
  stderr приложения пуст; starting/ready/stopping/stopped по одному при INFO,
  в указанном порядке. После stop видны итоговые записи завершённых запросов.
  Контейнер не завершён SIGKILL (137); JVM при SIGTERM может вернуть 143.
  Нет logging files в writable layer/временном каталоге.
  При чтении stdout тестом очередь обслуживается без искусственного замедления.
- **Independent oracle:** Python standard library json и явная schema/ожидаемые
  события в scripts/assert-logging.py, независимые HTTP response headers/body,
  inspect и список файлов контейнерного слоя. Для ряда метаданных отсутствие
  raw запроса проверяется sentinel values. Не использовать encoder для oracle.
- **Packaging constraint:** тестировать образ с настоящим ENTRYPOINT
  `bin/server`, не подменять запуск тестовым main или оболочкой.
  Сборщик/OTel backend не нужен. Выводить причины failures и возвращать nonzero;
  контейнеры, временные сети/каталоги удалять через trap.

### AC6. Границы и регрессии

- Статический review diff/config: используются стандартные AsyncAppender,
  ConsoleAppender и encoder; отсутствуют собственная доставка, retry,
  дополнительные потоки/очереди и logging facade. Граница CLI/toolkit сохранена.
- Проверить сохранение формата HTTP body и CLI stdout/exit на существующих
  тестах и сценариях S1-S8; сборка и Windows cross-compile проходят.
- Документация содержит поля и типы, JSON-пример успешного и ошибочного события,
  env/defaults, ограничения тела, работу AsyncAppender, lifecycle и OTel mapping.
- Контейнерный smoke test запускается вручную после S1-S8. Существующий scripts/e2e.sh
  поддерживает Linux/Windows shell; на macOS не объявлять этот suite пройденным
  без поддерживаемого Linux окружения.
- Проверки производительности/перегрузки и доставки до OTel не требуются.

Команды реализации:

```sh
./gradlew :server:test
./gradlew build
./gradlew :cli:linkReleaseExecutableMingwX64
bash scripts/e2e.sh
bash scripts/e2e-logging.sh
```

## Рассмотренные альтернативы

- Plain text и многострочные стектрейсы отклонены: фиксируем JSON Lines.
- Постоянная запись response body отклонена: отдельный opt-in.
- Синхронный ConsoleAppender возможен технически, но итоговое решение использует
  разрешённый пользователем штатный AsyncAppender. Своя доставка не нужна.
- request_id не заменяет W3C tracing; полноценная трассировка выделена в TNG-04.
- Поиск секретов в свободном бизнес-тексте оставлен guardrails-проекту.

## Источники

Проверены 2026-09-09.

- [Docker: View container logs](https://docs.docker.com/engine/logging/)
- [OTel: Logs Data Model](https://opentelemetry.io/docs/specs/otel/logs/data-model/)
- [Logback: AsyncAppender](https://logback.qos.ch/manual/appenders-async-sift.html#AsyncAppender)
- [Logback: ConsoleAppender](https://logback.qos.ch/manual/appenders-console-file.html#ConsoleAppender)
- [Logstash Logback Encoder 9.0](https://github.com/logfellow/logstash-logback-encoder/tree/logstash-logback-encoder-9.0)

## Ambiguity Report

```text
Ambiguity Report:
  Goals:        0.00
  Acceptance:   0.25
  Boundaries:   0.00
  Alternatives: 0.00
  Assumptions:  0.25
  Aggregate:    0.10
```

Готово к реализации. Эксплуатационное допущение о быстром читателе STDOUT
задано пользователем; совместимость собранного runtime и lifecycle проверяются
предписанными process/container fixtures. Небольшая оценка неоднозначности
приёмки относится к механике test harness и платформенному exit code JVM,
а не к невыбранной пользовательской семантике. TNG-04 остаётся отдельным Draft.
Код приложения при подготовке этой issue не изменялся.


## Завершение реализации

Выполнено 2026-09-10. [Closure ledger и результаты проверок](../../docs/tng03-evidence.md).
Согласованный текст до реализации сохранён в commit
`6d0ee17c5ed5fe5ccfaf79b49a34e46c114d11bd`; разделы интервью и исходного контекста
выше описывают состояние до выполнения задачи.
