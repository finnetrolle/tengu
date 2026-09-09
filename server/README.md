# :server — центральный хаб

Ktor-сервис (модульный монолит, 1 реплика): держит плагины, раздаёт манифест, исполняет invoke, владеет секретами. Агентские CLI ходят в него по HTTPS с bearer-токеном; плагины — модули внутри процесса, а не сервисы.

## API

| Endpoint | Auth | Поведение |
|---|---|---|
| `GET /v1/health` | — | `{serverVersion, tools, manifestVersion}` |
| `GET /v1/manifest` | bearer | `Manifest` + заголовок `X-Tengu-Server-Version` |
| `POST /v1/invoke` | bearer (+ `X-Tengu-Manifest-Version`) | `InvokeResponse` · ошибки — всегда `AxiErrorEnvelope` с HTTP-кодом по kind (400/401/404/409/502/500) |

Рукопожатие версий: клиент со старым кэшем манифеста получает 409 `STALE_MANIFEST` и обновляется; серверная валидация — тот же `Validate` из `:protocol`, что в CLI (защита в глубину).

## Состав

| Файл | Что внутри |
|---|---|
| `Main.kt` | Сборка: registry (+ StatusTool всегда, + JiraPlugin при `TENGU_JIRA_BASE_URL`), выбор SecretsStore, общий `HttpClient`, запуск CIO-сервера |
| `ServerDeps.kt` | `tenguModule(deps)`: три endpoint'а, auth, 409-handshake, валидация, вызов плагина, исключения плагина → INTERNAL без стектрейса наружу. `ServerDeps` — всё, что нужно роутам (в тестах собирается вручную) |
| `PluginRegistry.kt` | In-process реестр: register (защита от дублей имён), find, сборка `Manifest` |
| `HubAuth.kt` | Статичные bearer-токены → userId из `TENGU_HUB_TOKENS`; шов под SSO/OIDC |
| `ServerInfo.kt` | `VERSION` и `MANIFEST_VERSION` — бамп последнего при любом изменении поверхности тулов |
| `secrets/SecretsStore.kt` | Интерфейс хранилища + `StoreSecretScope`: `scopeFor(userId, tool)` — единственная точка раздачи секретов плагинам |
| `secrets/VaultSecretsStore.kt` | Прод: Vault KV v2 через REST, пути `secret/tengu/{userId}/{tool}`, токен только в env сервера |
| `secrets/FileSecretsStore.kt` | Dev/test only: активируется лишь при `TENGU_DEV_SECRETS=1`, корень — `TENGU_DEV_SECRETS_DIR` (по умолчанию `data/{user}/{tool}.json`), пишет JSON WARN `dev_secrets_enabled` в STDOUT |
| `tools/StatusTool.kt` | Первый плагин-диагност: версия, uptime, манифест, список тулов — обкатка контракта end-to-end |

## Конфигурация (env)

`TENGU_PORT` (8080), `TENGU_HUB_TOKENS` (`user=token,…`), `TENGU_JIRA_BASE_URL`, `TENGU_DEV_SECRETS=1`, `TENGU_DEV_SECRETS_DIR` (data), `TENGU_VAULT_ADDR`, `TENGU_VAULT_TOKEN`, `TENGU_VAULT_MOUNT` (secret), `TENGU_VAULT_PREFIX` (tengu).

## Логирование

JSON Lines в STDOUT: timestamp/level/service/logger/message и стабильный `event`.
Каждый завершённый application call даёт одно событие с `request_id`, user/command,
status/outcome и монотонной duration; тот же UUID v4 возвращается в `X-Request-ID`.
Входящий ID не используется. `TENGU_LOG_LEVEL` default INFO,
`TENGU_LOG_RESPONSE_BODY` default 0. Точный перечень значений, поля и типы,
примеры успеха/ошибки, структурные исключения тела, lifecycle и OTel mapping:
[контракт логов](../docs/server-logging.md).

Штатная цепочка SLF4J -> Logback AsyncAppender -> ConsoleAppender -> STDOUT,
encoder 9.0; собственных потоков доставки, retry и logging API для плагинов нет.

## Запуск

```sh
./gradlew :server:run
# TENGU_HUB_TOKENS="dev=h-dev123" TENGU_DEV_SECRETS=1 TENGU_JIRA_BASE_URL=https://jira.corp
docker compose up -d    # прод-стенд: сервер + dev-Vault
```

## Тесты

`./gradlew :server:test`: прежние HTTP-контракты, logging/body/UTF-8, конкурентные calls,
конфигурация и child JVM из installDist. Полный цикл S1-S8: `bash scripts/e2e.sh`
в Linux/Windows shell; Docker logs и shutdown: `bash scripts/e2e-logging.sh`.

Зависимости: `:protocol`, `:toolkit`, `:plugins:jira`, ktor-server-cio. Архитектура — [ARCHITECTURE.md](../ARCHITECTURE.md).
