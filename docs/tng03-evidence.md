# TNG-03: журнал приёмки

Исходный согласованный контракт: `specs/issues/issue_03_server_stdout_logging.md`
в commit `6d0ee17c5ed5fe5ccfaf79b49a34e46c114d11bd`. Issue сохранена, требования
и non-goals доступны в исходной ревизии. Базовое рабочее дерево было чистым.
Ветка: `codex/tng-03-stdout-logging`.

Route: critical; execution: inline. Рекомендация skill - gpt-6-astra/xhigh;
текущие настройки модели не проверены, переключение не заявляется.
Режим тестирования: behavior-first slices, независимые HTTP/JSON/UTF-8 oracles.

## Команды и результаты

| Код | Команда | Результат |
|---|---|---|
| V1 | `./gradlew :server:test :server:detekt --console=plain` | PASS: 24 test methods; HTTP, body, encoder, 4 process tests и 8 прежних routes tests; Detekt PASS |
| V2 | `./gradlew build check :cli:linkReleaseExecutableMingwX64 --console=plain` | PASS: build/check всех модулей, Windows cross-compile; платформенные native tests запускаются только на своём хосте |
| V3 | `python3 scripts/assert-logging.py unicode server/build/logging-fixtures/body-boundaries.jsonl` | PASS: независимые Python json/bytes для реальных HTTP bodies и encoder bytes, 16383/16384/16385 и emoji boundary |
| V4 | `bash scripts/e2e-logging.sh` | PASS: Docker INFO/0 и DEBUG/1, JSON/UTF-8, correlation/privacy, lifecycle/SIGTERM и no logging files |
| V5 | `bash scripts/e2e.sh` в Linux amd64 Docker | PASS: 31 checks, 0 failures; optional live Jira S6 пропущен без credentials. Выполнено в Linux amd64/Rosetta, не на macOS |
| V6 | `git diff --check`; structural diff/search и проверка Markdown links/status | PASS: diff/search, XML и локальные Markdown links; unique ID, registration и синхронный Done в issue/реестре |

Логи запусков и input hashes сохраняются локально в `build/reports/tng03/`.
Команды выполнялись через `rtk proxy`. До финальных проходов устранены: раннее
чтение ещё не установленного HTTP status для body capture, неподдерживаемый XML
charset (текстовая диагностика Logback), сырой CIO startup Throwable в stderr,
совместимость Docker harness с Bash 3.2. Пересекавшиеся Gradle test runs дали
конфликт общих report files; окончательные Gradle проверки выполнялись последовательно.

## Acceptance ledger

| AC | Реальный путь | Публичное наблюдение | Независимый oracle | Команда / результат |
|---|---|---|---|---|
| AC1 HTTP и уровни | Ktor testApplication -> auth/parse/resolve/validate/invoke/respond; fake plugin через ServerDeps; unmatched и test-only cancellation/unhandled routes | По одному event на response X-Request-ID. 200 health DEBUG, manifest/status INFO; Noop noop; AUTH 401, USAGE 400, STALE_MANIFEST 409, NOT_FOUND 404 WARN; UPSTREAM 502 и INTERNAL 500 ERROR. Неотрицательная duration; перед освобождением gate event первого call отсутствует. Cancellation проходит наружу logging seam тем же исключением, без придуманного status. Нет старых дублирующих route logs | Литеральная таблица статусов/уровней/kinds, заданный AxiResult, HTTP headers; kotlinx.serialization читает реальные AsyncAppender/encoder bytes. FIFO barrier доказывает отсутствие преждевременного event | V1 PASS |
| AC2 изоляция | Два overlap invoke alice/bob: первый удерживается CompletableDeferred, второй заканчивается, затем первый освобождается; следом invalid auth и health с bearer | Логи идут bob/alice, совпадают с собственными HTTP request IDs; все IDs различные UUID v4, присланный ID игнорируется; health/401 без user_id | Фиксированное token-a -> alice, token-b -> bob; порядок задаётся gate, не sleep; response headers служат ключами. Два повторения | V1 PASS |
| AC3 body и исключения | Окончательный TextContent после HTTP transformations; Ok/Noop/Err; body off DEBUG/INFO; auth и secret descriptors с/без optional flag, при Ok и Err; prevalidation/401/health/manifest; plugin throws | Только разрешённые ответы содержат три body fields; строка совпадает с HTTP body, включая helpHints. Исключения не пропускают входные markers или message/cause/suppressed. Бизнес-текст с token= сохраняется. Exact sizes 16383/16384/16385; emoji на границе обрезается целиком, без replacement character | Полученный HTTP body, буквальный wire envelope, Unicode fixture; размер стандартным UTF-8 encoding. Отдельный Python parser проверяет actual encoded bytes, production prefix helper не используется | V1, V3, V4 PASS |
| AC4 env и фильтрация | Java main из installDist/lib/* с отдельными stdout/stderr и child-only env; HTTP testApplication с isolated LoggerContext | Defaults INFO/0; DEBUG/INFO/WARN/ERROR x body0/1; health/success/400/500 отфильтрованы по таблице, body не меняет уровень. Library INFO отсутствует, WARN виден без event. 11 invalid cases дают один configuration ERROR, exit1, правильные key/allowed_values, без raw values и listener | Литеральные ожидаемые уровни и env values. Invalid child наблюдается TCP probes весь срок жизни, одновременно проверяется единственное configuration event до server_starting; отсутствие Vault config выявляет слишком раннее создание сервиса | V1 PASS |
| AC5 упаковка и остановка | Настоящий Dockerfile/ENTRYPOINT bin/server, без TTY, fake alice/bob, tmpfs dev secrets; INFO/0 и DEBUG/1; docker stop --time 10 | JSON Lines stdout, stderr пуст, lifecycle starting/ready/stopping/stopped ровно один раз и по порядку; completed requests видны после drain; exit0/143, без SIGKILL137; нет logging files в writable layer/tmpfs | Python standard-library JSON/schema/bytes; HTTP transcripts с IDs/body; Docker inspect и diff; real encoder Unicode fixtures | V4 PASS |
| AC6 границы, docs и регрессии | Прежние module tests + native CLI/server S1-S8; static diff XML/dependencies/modules и Markdown docs; CI workflow | HTTP/CLI contracts прежние, manifestVersion=4; стандартные AsyncAppender/ConsoleAppender/encoder, root WARN, фиксированная очередь. Документы содержат schema/types, success/error JSON, env, body rules, lifecycle и OTel mapping; Docker smoke после S1-S8 в CI | Старые тесты и CLI stdout/exit assertions; source diff против исходной ревизии, XML parse и Markdown link validation. Runtime/oracle для docs не применимы: структурный контракт | V1/V2/V5/V6 PASS |

## Non-goals ledger

Для структурных строк runtime/oracle неприменимы: проверяется состав diff/config,
а не поведение выдуманного компонента.

| Non-goal | Проверка границы и наблюдение | Evidence |
|---|---|---|
| W3C tracing, spans, SDK и фиктивные tracing IDs | Production search не содержит tracing SDK/trace_id/span_id; encoder-тест проверяет отсутствие полей; TNG-04 остаётся Draft | V1, V6 |
| Новые модули и logging API для плагинов | settings/toolkit/plugins production diff пуст; logging находится в server | V6 |
| Logging files и rotation | Только ConsoleAppender; file/rolling appenders отсутствуют; Docker diff/tmpfs проверены | V4, V6 |
| Сеть из appender, collector/backend, OTel exporter | XML содержит только STDOUT + ASYNC, зависимостей exporter нет; runtime без Jira/Vault/backend | V4, V6 |
| DLP/guardrails/regex-redaction | Нет production redaction/guardrails; test сохраняет token= в разрешённом business text | V1, V6 |
| Собственная доставка, очереди, retries и резервирование | Стандартный Logback AsyncAppender; custom Appender/Encoder/Listener и logging facade отсутствуют. Один JVM lifecycle hook не является потоком доставки логов | V6 |
| Дополнительные потоки для logging/backpressure и slow-reader load tests | В logging package нет Thread/Queue/Channel; default Logback worker из AsyncAppender; load tests не добавлены | V6 |
| Компенсация потерь / гарантия при SIGKILL и после STDOUT | В docs указаны штатный flush timeout, быстро читаемый STDOUT, пределы SIGKILL/ОС/дальнейшей доставки; компенсации нет | V6 |
| Изменение HTTP body/status, descriptor surface, CLI и manifestVersion | protocol/toolkit/plugins/cli production diff пуст; существующие HTTP tests и S1-S8, ServerInfo неизменён | V1, V2, V5, V6 |
| Изменение plugin fault barrier/cancellation policy | Существующий runCatching сохранён; заменена только запись исключения в context. Отдельно tested cancellation вне plugin barrier | V1, V6 |
| Production test-only endpoints/plugins | Fake ToolPlugin и cancel/unhandled routes только в test source set; production routes прежние | V6 |
| Изменение ProtocolJson/HTTP serializer ради Jackson | Jackson 3 приходит только с encoder 9.0; ProtocolJson unchanged; HTTP продолжает kotlinx.serialization | V1, V2, V6 |
| Hot reload и новые env-настройки очереди | Две logging env читаются в main до resources; XML queue settings литеральные, scan/reload/env queue knobs отсутствуют | V1, V6 |
| Нагрузочные проверки и доказательство доставки в OTel | Не реализованы и не объявлены пройденными; документация отделяет JSON от OTLP | V6 |

## Lifecycle matrix

| Владелец | Terminal event | Observable cleanup | Failure path |
|---|---|---|---|
| ApplicationCall / RequestLogContext | Ответ, необработанный сбой или CancellationException | Одна закодированная completion record, совпадающая с response header; snapshots не содержат call/maps | Parse/plugin failure записывает только class/frames; cancellation rethrow проверяется внешним Ktor interceptor |
| ServerRuntime и единственный JVM hook | SIGTERM / interrupt / fatal engine completion | Engine stop вызывается до client close/join, затем server_stopped и LoggerContext.stop; process/container exit и порядок JSON проверены | Startup, failure и stop сериализованы; closed исключает повторные lifecycle records |
| Частично построенный server | Ошибка service construction или listener bind | Exit1, no ready, stopping/stopped; stderr пуст; оба process paths проверены | Engine и client cleanup предпринимаются независимо; первая ошибка сохраняется, последующая suppressed, сообщения не экспортируются |
| HTTP client | Engine остановлен либо construction/start failed | close + coroutineContext.job.join до server_stopped (source order); процесс штатно завершается | Cleanup client вызывается даже при ошибке engine cleanup |
| Ktor root coroutine | Ошибка startup/runtime engine | CoroutineExceptionHandler передаёт termination владельцу, предотвращает сырой stderr dump | Реальный occupied-port process test проверяет JSON-only failure; обработчик сам ничего не логирует после stop |
| LoggerContext / AsyncAppender | После server_stopped | Штатный stop/drain с maxFlushTime1000; после stop нет lifecycle logging | Гарантии сверх штатного timeout/SIGKILL не добавлены |
| Test fixtures | Test completion/failure/timeout | Child process destroyed/waited, HttpClient closed, temp dirs removed; Docker traps удаляют containers/image | Failing Docker run возвращает nonzero и сохраняет диагностику; нет замедления stdout reader |

## Повторное использование и scope

До добавления helpers выполнены `rg --files server scripts config .agents` и поиск
`Logger|log\.|shutdown|stop\(|validat.*work|work.*validat` по server/scripts/buildSrc.
Canonical anchors: ServerDeps, StaticTokenHubAuth, PluginRegistry, Validate,
ProtocolJson, StatusTool, FileSecretsStore и scripts/e2e.sh. Они переиспользованы.
Logging encoder/queue, isolated JVM process launcher, encoder byte sink и work-item validator
в исходном репозитории отсутствовали. Production использует штатный Logback;
новые launcher/barrier/HTTP helpers ограничены test source/scripts. S1-S8 сохраняет сценарии, но заранее собирает installDist и запускает штатный
launcher напрямую: readiness не включает компиляцию; cleanup завершает только свой PID.
FIFO marker
нужен для наблюдения drain без Thread.sleep в concurrency-тестах.

Словарь поиска по production, tests, KDoc, README/ARCHITECTURE/docs, specs и
связанной TNG-04: старые log.warn/log.error, malformed invoke, FileSecretsStore,
Responding at, stderr, редачится; новые event/request_id/user_id/http_route,
response_body/bytes/truncated, exception_type/stacktrace, TENGU_LOG_LEVEL,
TENGU_LOG_RESPONSE_BODY, AsyncAppender, ShutdownHook, trace_id/span_id.
Runtime docs обновлены; будущая интеграция TNG-04 не реализуется здесь.

Diff ограничен server production/tests/resources, encoder dependency/catalog,
Docker logging harness и CI, logging docs и согласованным closure metadata.
Исходное дерево было чистым. Дополнительно затронуты specs/MVP.md (убран
завершённый TNG-03 из frontier) и связанная TNG-04 (интеграция теперь описана
относительно реализованного logging); её статус Draft и scope не менялись.
Новые docs/tng03-evidence.md и docs/server-logging.md обслуживают приёмку и контракт.
Work-item validator в проекте не найден; вместо отсутствующей команды выполнена
однократная проверка ID, статуса, регистрации и локальных Markdown links после closure.


S1-S8 запускается в изолированной копии дерева `/tmp/tng03-linux` с теми же
production sources и final e2e.sh, через `docker run --rm --platform linux/amd64`
на `eclipse-temurin:21-jdk`. В fixture задано
`GRADLE_OPTS=-Dorg.gradle.vfs.watch=false` из-за неподдерживаемого file watcher
эмулятора; это не настройка сервера и не изменение production Gradle config.
Предыдущий cold run превысил старый readiness timeout во время компиляции сервера;
final harness измеряет готовность только после installDist. Проверки производительности
из эмулятора не выводятся. Без реальной Jira optional S6 не заявляется пройденным.

Структурная closure-проверка: `python3 /tmp/tng03-validate-work-items.py` (exit 0).
Её исходник сохранён в локальном каталоге evidence вместе с console logs и hashes.
