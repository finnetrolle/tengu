# :server - центральный хаб

Ktor-сервис (модульный монолит, 1 реплика): держит плагины, раздаёт манифест, исполняет invoke, владеет секретами. Агентские CLI ходят в него по HTTPS с bearer-токеном; плагины - модули внутри процесса, а не сервисы.

## API

| Endpoint | Auth | Поведение |
|---|---|---|
| `GET /v1/health` | - | `{serverVersion, uptimeSec, tools, manifestVersion}` |
| `GET /v1/manifest` | bearer | `Manifest` + заголовок `X-Tengu-Server-Version` |
| `POST /v1/invoke` | bearer (+ `X-Tengu-Manifest-Version`) | `InvokeResponse` · ошибки - всегда `AxiErrorEnvelope` с HTTP-кодом по kind (400/401/404/409/502/500) |

Рукопожатие версий: клиент со старым кэшем манифеста получает 409 `STALE_MANIFEST` и обновляется; серверная валидация - тот же `Validate` из `:protocol`, что в CLI (защита в глубину).

## Состав

| Файл | Что внутри |
|---|---|
| `Main.kt` | Сборка: registry (+ StatusTool всегда, + JiraPlugin при `TENGU_JIRA_BASE_URL`), выбор SecretsStore, общий `HttpClient`, запуск CIO-сервера |
| `ServerDeps.kt` | `tenguModule(deps)`: три endpoint'а, auth, 409-handshake, валидация, вызов плагина, исключения плагина → INTERNAL без стектрейса наружу. `ServerDeps` - всё, что нужно роутам (в тестах собирается вручную) |
| `PluginRegistry.kt` | In-process реестр: register (защита от дублей имён), find, сборка `Manifest` |
| `HubAuth.kt` | Статичные bearer-токены → userId из `TENGU_HUB_TOKENS`; шов под SSO/OIDC |
| `ServerInfo.kt` | `VERSION` и `MANIFEST_VERSION` - бамп последнего при любом изменении поверхности тулов |
| `secrets/SecretsStore.kt` | Интерфейс хранилища + `StoreSecretScope`: `scopeFor(userId, tool)` - единственная точка раздачи секретов плагинам |
| `secrets/VaultSecretsStore.kt` | Прод: Vault KV v2 через REST, пути `secret/tengu/{userId}/{tool}`, токен только в env сервера |
| `secrets/FileSecretsStore.kt` | Dev/test only: активируется лишь при `TENGU_DEV_SECRETS=1`, корень - `TENGU_DEV_SECRETS_DIR` (по умолчанию `data`, файлы `{user}/{tool}.json`), пишет предупреждение в лог |
| `tools/StatusTool.kt` | Первый плагин-диагност: версия, uptime, манифест, список тулов - обкатка контракта end-to-end |

## Конфигурация (env)

| Переменная | Значение / назначение |
|---|---|
| `TENGU_PORT` | `8080` по умолчанию; сервер слушает все интерфейсы |
| `TENGU_HUB_TOKENS` | Пользователи и bearer-токены: `user=token,...` |
| `TENGU_JIRA_BASE_URL` | URL Jira; без него плагин не регистрируется |
| `TENGU_DEV_SECRETS` | `1` включает незашифрованное файловое хранилище для разработки |
| `TENGU_DEV_SECRETS_DIR` | Корень файлового хранилища, по умолчанию `data` |
| `TENGU_VAULT_ADDR` | URL Vault, обязателен без dev-хранилища |
| `TENGU_VAULT_TOKEN` | Токен Vault, обязателен без dev-хранилища |
| `TENGU_VAULT_MOUNT` | KV v2 mount, по умолчанию `secret` |
| `TENGU_VAULT_PREFIX` | Префикс путей, по умолчанию `tengu` |

## Запуск

```sh
TENGU_HUB_TOKENS="dev=tengu-local" TENGU_DEV_SECRETS=1 ./gradlew :server:run
```

Для Jira добавь `TENGU_JIRA_BASE_URL`. [Полный quickstart](../README.md#быстрый-старт),
[запуск из готового серверного архива](../docs/installation.md#сервер-из-архива).

### Тестовый стенд с Vault

Из корня репозитория:

```sh
cp .env.example .env
docker compose up --build -d
```

При необходимости задай URL Jira в `.env`. Стенд использует dev-Vault с токеном
`dev-root`, данные которого не рассчитаны на постоянное хранение. Не подменяй
`TENGU_VAULT_TOKEN` произвольным значением: оно должно соответствовать запущенному Vault.
Остановка: `docker compose down`. Одноразовый профиль без Vault описан в
[docs/local-docker.md](../docs/local-docker.md).

### Общий стенд / production

Поставляемый Compose-файл предназначен для тестирования. Для общего стенда нужны
самостоятельно настроенный Vault KV v2, собственные hub tokens, HTTPS на reverse proxy
и ограниченный доступ к HTTP-порту сервера. Передай `TENGU_VAULT_ADDR` и
`TENGU_VAULT_TOKEN`, а `TENGU_DEV_SECRETS` оставь выключенным. Mount и префикс
задаются переменными выше; по умолчанию данные лежат в `secret/tengu/{user}/{tool}`.
Политика Vault должна разрешать операции с данными этого префикса и чтение metadata.
Не используй dev-root token для production. [Границы безопасности](../SECURITY.md).

## Тесты

`ServerRoutesTest` на ktor-server-test-host: auth, health/manifest/invoke, 409-handshake. `./gradlew :server:test`. Полный цикл - `bash scripts/e2e.sh`.

Зависимости: `:protocol`, `:toolkit`, `:plugins:jira`, ktor-server-cio. Архитектура - [ARCHITECTURE.md](../ARCHITECTURE.md).
