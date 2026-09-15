# Серверные JSON-логи

Собранный JVM distribution и Docker ENTRYPOINT `bin/server` пишут JSON Lines в
STDOUT. HTTP body и CLI stdout/exit остаются прежними; дополнительный HTTP header
`X-Request-ID` коррелирует ответ с одним итоговым событием. Логирование покрывает
запросы, дошедшие до Ktor application pipeline. JVM/launcher до инициализации,
ошибки HTTP transport до pipeline и аварийные JVM dumps вне этого контракта.

Быстрый запуск:

```sh
TENGU_HUB_TOKENS=alice=example-token TENGU_DEV_SECRETS=1 \
TENGU_LOG_LEVEL=DEBUG TENGU_LOG_RESPONSE_BODY=1 server/build/install/server/bin/server
```

Настройки читаются только при запуске. Обычно достаточно defaults INFO/0.
Готовность определяется событием `server_ready` и GET `/v1/health`.
В production STDOUT читает инфраструктура; сетевой appender, collector и backend
серверу не нужны. W3C tracing остаётся отдельной задачей TNG-04.

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
семантику выполнения, записать `cancelled`. Логирование не изменяет
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

При включённой записи ответов сохраняется произвольный бизнес-текст Jira,
в том числе вручную вставленные секреты. Поиск таких секретов принадлежит соседнему
guardrails-проекту и не является обязанностью серверного логирования. Согласованные
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
фиктивные IDs, SDK или будущие extension points в текущий logging contract.
TNG-04 не блокирует реализацию логирования.

## Проверка

```sh
./gradlew :server:test
bash scripts/e2e-logging.sh
```

HTTP-тесты используют injectable `ServerDeps`, test-only ToolPlugin и настоящий
encoder/AsyncAppender с OutputStreamAppender. Process-тесты запускают main из
`installDist/lib/*` без дочернего Gradle. Контейнерный сценарий собирает настоящий
Dockerfile, проверяет INFO/0 и DEBUG/1, останавливает контейнер через SIGTERM и
проверяет stdout, stderr, lifecycle и writable layer независимым Python parser.
Unicode fixtures от настоящего encoder дополнительно проверяются Python по HTTP bytes.
При ошибке сценарий возвращает nonzero и сообщает каталог диагностических файлов.
Перед интеграцией запустить эту проверку вручную после `bash scripts/e2e.sh`
(S1-S8) в поддерживаемом Linux окружении.
