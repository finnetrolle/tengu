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
| `Routes.kt` | `tenguModule(deps)`: три endpoint'а, auth, 409-handshake, валидация, вызов плагина, исключения плагина → INTERNAL без стектрейса наружу. `ServerDeps` — всё, что нужно роутам (в тестах собирается вручную) |
| `PluginRegistry.kt` | In-process реестр: register (защита от дублей имён), find, сборка `Manifest` |
| `HubAuth.kt` | Статичные bearer-токены → userId из `TENGU_HUB_TOKENS`; шов под SSO/OIDC |
| `ServerInfo.kt` | `VERSION` и `MANIFEST_VERSION` — бамп последнего при любом изменении поверхности тулов |
| `secrets/SecretsStore.kt` | Интерфейс хранилища + `StoreSecretScope`: `scopeFor(userId, tool)` — единственная точка раздачи секретов плагинам |
| `secrets/VaultSecretsStore.kt` | Прод: Vault KV v2 через REST, пути `secret/tengu/{userId}/{tool}`, токен только в env сервера |
| `secrets/FileSecretsStore.kt` | Dev/test only: активируется лишь при `TENGU_DEV_SECRETS=1`, корень — `TENGU_DEV_SECRETS_DIR` (по умолчанию `data/secrets/…`), предупреждает в stderr |
| `tools/StatusTool.kt` | Первый плагин-диагност: версия, uptime, манифест, список тулов — обкатка контракта end-to-end |

## Конфигурация (env)

`TENGU_PORT` (8080), `TENGU_HUB_TOKENS` (`user=token,…`), `TENGU_JIRA_BASE_URL`, `TENGU_DEV_SECRETS=1`, `TENGU_DEV_SECRETS_DIR` (data), `TENGU_VAULT_ADDR`, `TENGU_VAULT_TOKEN`, `TENGU_VAULT_MOUNT` (secret), `TENGU_VAULT_PREFIX` (tengu).

## Запуск

```sh
./gradlew :server:run
# TENGU_HUB_TOKENS="dev=h-dev123" TENGU_DEV_SECRETS=1 TENGU_JIRA_BASE_URL=https://jira.corp
docker compose up -d    # прод-стенд: сервер + dev-Vault
```

## Тесты

`ServerRoutesTest` (7) на ktor-server-test-host: auth, health/manifest/invoke, 409-handshake. `./gradlew :server:test`. Полный цикл — `bash scripts/e2e.sh`.

Зависимости: `:protocol`, `:toolkit`, `:plugins:jira`, ktor-server-cio. Архитектура — [ARCHITECTURE.md](../ARCHITECTURE.md).
