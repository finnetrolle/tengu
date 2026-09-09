# Tengu — архитектура

Хаб тулов для ИИ-агентов: агенты discover'ят доступные корпоративные инструменты и вызывают их через единый AXI-совместимый CLI. Каждый тул — плагин внутри сервиса, интегрирующий **одну** корпоративную систему и **сам владеющий доступом** к ней (агент про креды не знает).

> Живой документ: обновлять при изменении решений. Стандарт агентного интерфейса — `.agents/skills/axi/SKILL.md` (AXI), формат вывода — TOON (toonformat.dev).

## Топология

```
машины агентов                     корпоративная сеть
+---------------+   HTTPS          +--------------------------------+
| tengu CLI     |   bearer-токен   |  tengu server (Docker, 1 реплика)
| (не сервис —  |----------------->|  ┌────────────────────────────┐
| тонкий клиент,│  /v1/manifest    |  │ HTTP API (3 endpoint'а)    │
| много копий)  |  /v1/invoke      |  │ Bearer-auth → userId       │
+---------------+  /v1/health      |  │ PluginRegistry             │
                                  |  │  ├─ status (диагностика)   │
                                  |  │  └─ jira                   │
                                  |  │ SecretsStore ────────────┐ │
                                  |  └─────────────────────────┼─┘
                                  +────────────────────────────┼──+
                                                              │
                                        ┌─────────────────────┴──────────┐
                                        │ ВНЕШНИЕ СИСТЕМЫ (не наши)      │
                                        │ Vault ← PAT per user per tool  │
                                        │ Jira REST API                  │
                                        └────────────────────────────────┘
```

**Один сервис (модульный монолит) + тонкие клиенты.** Микросервисы не нужны при текущей нагрузке; швы для выделения заложены контрактом `ToolPlugin` и версионированным invoke-протоколом (см. «Эволюция»). Плагин — модуль внутри сервера, а не процесс.

## Модули Gradle

| Модуль | Ответственность | Зависимости |
|---|---|---|
| `:protocol` | DTO манифеста, InvokeRequest/Response, AxiErrorEnvelope, общий `validate()` — **KMP** (jvm + mingwX64 + linuxX64; macosArm64 на mac-хосте) | kotlinx-serialization |
| `:toon` | TOON-энкодер над JsonElement — **KMP** (jvm + mingwX64 + linuxX64; macosArm64 на mac-хосте) | kotlinx-serialization |
| `:toolkit` | SDK плагинов: ToolPlugin, InvocationContext, AxiResult, AxiPayloads, SecretScope | :protocol, ktor-client-core |
| `:plugins:jira` | JiraPlugin, JiraApiClient, команды | :toolkit, ktor-client-cio |
| `:server` | Ktor-сервер: auth, PluginRegistry, routes, SecretsStore, StatusTool | :protocol, :toolkit, :plugins:jira, ktor-server-cio |
| `:cli` | Агентский CLI: кэш манифеста, валидация, прокси, TOON-рендер, exit codes — **native-only KMP** (mingwX64 + linuxX64; macosArm64 на mac-хосте, без JVM-таргета) | :protocol, :toon, clikt, ktor-client-winhttp (Win) / ktor-client-curl (Linux) / ktor-client-darwin (macOS) |

**Жёсткое правило: `:cli` не зависит от `:toolkit` и плагинов.** CLI — тонкий прокси с минимальным класс-графом; `:protocol`/`:toon` — KMP-библиотеки: сервер ест их jvm()-вариант, CLI — нативные. Пакеты: `ru.finnetrolle.tengu.<module>`.

## Протокол клиент↔сервер

| Endpoint | Auth | Ответы |
|---|---|---|
| `GET /v1/health` | — | 200 `{serverVersion, uptimeSec, tools, manifestVersion}` |
| `GET /v1/manifest` | bearer | 200 `Manifest` (+ `X-Tengu-Server-Version`) |
| `POST /v1/invoke` | bearer (+ `X-Tengu-Manifest-Version`) | 200 `InvokeResponse` · 400 USAGE · 401 AUTH · 404 · 409 STALE_MANIFEST · 502 UPSTREAM · 500 — тело ошибки всегда `AxiErrorEnvelope` |

Принципы:

- `validate(descriptor, args, flags)` — чистая функция в `:protocol`, исполняется **на обеих сторонах**: CLI валидирует по кэшу манифеста до сети (exit 2 без раундтрипа), сервер перепроверяет (защита в глубину). Расхождение поведения исключено — один код.
- **409 STALE_MANIFEST** → CLI обновляет манифест и ретраит один раз, агент этого не замечает. Плюс TTL кэша 24 ч и `tengu manifest refresh`.
- `manifestVersion` бампируется при **любом** изменении поверхности (команды/флаги/переименования). Handshake версий проверяется end-to-end сценарием S8; e2e (S1–S8) запускается вручную через `bash scripts/e2e.sh` перед интеграцией.
- Таймаут 30 с; недоступный сервер → `error: cannot reach tengu server at <url>` + setup-хинт, exit 1.

## Контракт плагина

```kotlin
interface ToolPlugin {
    val descriptor: ToolDescriptor                    // поверхность тула — чистые данные
    suspend fun invoke(commandPath: List<String>, ctx: InvocationContext): AxiResult
}
```

- **ToolDescriptor** (name, summary, description, commands[]) — единый источник правды: из него сервер строит `/v1/manifest`, CLI — валидацию и `--help`. Команды иерархические (`path = ["issues","list"]`), с флагами, типами, дефолтами, `allowedValues`, примерами и `renamedFlags` (точечные хинты при переименованиях).
- **InvocationContext**: `userId` (из bearer-токена), провалидированные `args`/`flags`, `secrets: SecretScope` (скоуп уже ограничен парой (user, tool) — чужие секреты недостижимы архитектурно), общий **credential-free** `HttpClient` (плагин сам ставит свой auth-заголовок), `clock`.
- **AxiResult = Ok(payload, helpHints) | Noop(message) | Err(AxiErrorEnvelope)**. `Noop` — идемпотентные мутации, exit 0 (AXI §6). `Err` несёт kind (USAGE→exit 2, прочее→exit 1), message и help-хинты.
- **AxiPayloads** — билдеры `listOfItems / detail / truncated / emptyState`: минимальные схемы, count'ы, truncation с escape-хинтом, definitive empties (AXI §2–§5) становятся однострочниками.

Обязанности автора плагина: дескриптор описывает всю поверхность; ошибки апстрима переводятся, а не текут (никаких имён зависимостей и URL в выводе); бамп `manifestVersion` при изменениях; вызовы stateless. Гарантии: валидация входа уже сделана, секреты скоуплены, рендер/транспорт/exit codes — не забота плагина.

## Секреты

- `SecretsStore { put/get/delete/describe/scopeFor }`; `describe` возвращает мету (last4, setAt, version) — никогда значение.
- **VaultSecretsStore** (prod): Vault KV v2 через REST (без тяжёлого Java-драйвера), пути `secret/tengu/{userId}/{tool}`, заголовок `X-Vault-Token`.
- **FileSecretsStore** (только dev/test): `data/{user}/{tool}.json` (корень - `TENGU_DEV_SECRETS_DIR`, по умолчанию `data`), активируется лишь при `TENGU_DEV_SECRETS=1`, громкое предупреждение в лог.
- Поток PAT: `tengu jira auth login` → плагин проверяет токен на `GET /rest/api/2/myself` → `secrets.put("pat", …)` → Vault. Токен пересекает провод один раз, исключён из capture вместе со всеми ответами команды с `FlagDescriptor.secret`, агенту не возвращается.

## Auth хаба

Статичные bearer-токены → userId: `TENGU_HUB_TOKENS="jdoe=h-abc123,..."`. 401 → `AxiErrorEnvelope(AUTH)` с хинтом `tengu setup --url <url> --token <hub token>`. SSO/OIDC — в будущем, интерфейс `HubAuth` это предусматривает.

## AXI-соответствие

| AXI | Где реализовано |
|---|---|
| §1 TOON | `:toon`, рендер только на выходной границе CLI |
| §2–§5 схемы/aggregates/truncation/empties | `AxiPayloads` в `:toolkit` |
| §6 ошибки/exit codes/no-prompts/fail-loud | `AxiErrorEnvelope` + `validate()` в `:protocol`; консоль Clikt перенаправлена в stdout |
| §8 content-first | no-args дашборд CLI (bin/description + таблица тулов) |
| §9 contextual disclosure | `helpHints` в InvokeResponse и AxiErrorEnvelope |
| §10 help/--version | `--help` из дескриптора; `--version` fast-path до загрузки классов |
| §7 ambient context | вне MVP (follow-up: SessionStart-хуки + устанавливаемый skill) |

## Конфигурация

Сервер (env): `TENGU_PORT` (8080), `TENGU_HUB_TOKENS`, `TENGU_VAULT_ADDR`, `TENGU_VAULT_TOKEN`, `TENGU_VAULT_MOUNT` (secret), `TENGU_VAULT_PREFIX` (tengu), `TENGU_JIRA_BASE_URL`, `TENGU_DEV_SECRETS`, `TENGU_DEV_SECRETS_DIR` (data — корень файлового стора dev-режима).

CLI: `%APPDATA%\tengu\config.json` (Win) / `~/.config/tengu/config.json` — `{serverUrl, hubToken}` + кэш `manifest.json` (`fetchedAt`, `manifestVersion`).

## Серверные логи

Сервер владеет JSON-событиями и call-контекстом. Request UUID v4 создаётся до auth,
возвращается в `X-Request-ID`; итоговый event содержит только проверенного userId
и разрешённые имена descriptor. Coroutine-контекст хранится в `ApplicationCall`,
в AsyncAppender передаются снимки строк/чисел/boolean/list, без MDC и сырых Throwable.
HTTP JSON и CLI остаются прежними, manifestVersion не повышается.

Доставка: SLF4J -> штатный Logback AsyncAppender (256, без раннего discard,
neverBlock=false, maxFlushTime=1000) -> ConsoleAppender (System.out, immediateFlush)
-> logstash encoder 9.0 (Jackson 3). После STDOUT отвечает инфраструктура.
INFO/0 - defaults `TENGU_LOG_LEVEL`/`TENGU_LOG_RESPONSE_BODY`; библиотеки от WARN.
Body capture допускает только прошедший auth/validation POST invoke, исключает
`auth` и любой secret flag в descriptor, ограничен 16 384 UTF-8 байтами.

Один JVM shutdown hook владеет остановкой engine, закрытием/ожиданием HTTP client,
`server_stopped` и остановкой LoggerContext в этом порядке. Ktor hook отключён;
start, partial failure и stop сериализуются одним lifecycle owner. Собственных
очередей, logging threads, appender, encoder, facade или retry нет.

[Полная схема, lifecycle, примеры и OTel mapping](docs/server-logging.md).
Tracing/export spans относится к TNG-04; логи сами не создают spans.

## Разработка

```sh
export JAVA_HOME=~/.jdks/openjdk-25            # машина разработчика
./gradlew build                                # всё + тесты (нативные тесты — по хосту)
bash scripts/e2e.sh                            # сценарии S1–S8 нативным бинарём (поднимает свой сервер на :8080)
./gradlew :server:run                          # сервер (TENGU_HUB_TOKENS=dev=h-dev123)
./gradlew :cli:linkReleaseExecutableMingwX64   # CLI → cli/build/bin/mingwX64/releaseExecutable/tengu.exe
./gradlew :cli:linkReleaseExecutableLinuxX64   # …/linuxX64/releaseExecutable/tengu.kexe (кросс-компиляция)
./gradlew :cli:linkReleaseExecutableMacosArm64 # …/macosArm64/releaseExecutable/tengu.kexe (только на mac-хосте)
docker compose up -d                           # прод-стенд: сервер + dev-Vault
```

CLI — Kotlin/Native: дев-режим и релиз на одном нативном бинаре (mingwX64 — WinHttp, linuxX64 — статический Curl, macosArm64 — Darwin/NSURLSession; все самодостаточны, JVM на машинах агентов не нужна). macOS-таргет создаётся только на mac-хосте: K/N не кросс-компилирует Apple-таргеты с Linux/Windows. Первая сборка качает тулчейн konan (~1 ГБ в `~/.konan`). Нюанс: у ktor-client-curl 3.4+ бандл статических либ линкуется в ломающем порядке (KTOR-9460) — обход в `cli/build.gradle.kts` (`extractCurlStatic`). Docker-сервер — JVM (multi-stage, temurin).

## Текущий статус (MVP 0.1.0)

Реализовано: протокол + TOON-энкодер (golden-тесты), сервер (auth/manifest/invoke, STALE_MANIFEST-handshake), CLI (дашборд, tools list/show, проксирование, локальная валидация, 409/unknown-tool/unknown-command self-healing кэша, stdin-конвенция для secret-флагов), StatusTool, Jira-плагин (auth + issues, PAT в Vault/файл), FileSecretsStore за `TENGU_DEV_SECRETS=1`, e2e S1–S8 (24 проверки). CLI — Kotlin/Native (mingwX64 + linuxX64): самодостаточные бинари, startup десятки мс; `--version` fast-path сохранён.

Нюанс, о котором стоит помнить: даши/`tools list` отдают кэш манифеста до 24 ч (TTL) — свежесть гарантируется на пути invoke (409 → авто-refresh) и при unknown tool/command (refresh + перепроверка).

Отложено (см. «Эволюция»): AXI §7 (SessionStart-хуки + устанавливаемый skill), SSO/OIDC, plugin-executor.

## Эволюция (вне MVP)

- **Plugin-executor**: плагин как отдельный сервис/процесс, говорящий тем же invoke-протоколом — для изоляции, тяжёлых SDK, чужих языков, своего cadence релизов. Агентский CLI не меняется.
- SSO/OIDC вместо статичных токенов; API-gateway при внешнем доступе.
- AXI §7: SessionStart-хуки (Claude Code/Codex/OpenCode) + генерируемый skill.
- Горизонтальное масштабирование: реплики stateless-сервера за балансировщиком.
