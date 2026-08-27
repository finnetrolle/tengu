# :toolkit — SDK плагинов

Всё, что видит автор плагина: контракт `ToolPlugin`, контекст вызова, алгебра результата, билдеры AXI-payload'ов и доступ к секретам. Фреймворк-агностик — ни серверного Ktor, ни Vault в сигнатурах; это шов для будущего plugin-executor (плагин отдельным процессом тем же контрактом).

## Состав

| Файл | Что внутри |
|---|---|
| `ToolPlugin.kt` | Контракт: `descriptor` (вся поверхность тула — данные, единый источник правды для манифеста, валидации и `--help`) + `suspend invoke(commandPath, ctx)`. `InvocationContext`: `userId`, провалидированные `args`/`flags`, `secrets`, общий credential-free `HttpClient` (свой auth-заголовок плагин ставит сам), `clock` |
| `AxiResult.kt` | Алгебра результата: `Ok(payload, helpHints)` / `Noop(message)` — идемпотентные мутации, exit 0 / `Err(AxiErrorEnvelope)`. Билдеры `ok()` / `err()` / `json()` |
| `AxiPayloads.kt` | AXI-совместимые payload'ы однострочниками: `listOfItems` (count-агрегат + табличный массив, пустой результат → definitive empty), `detail`, `truncatedPreview` + `truncationHint`, `row` (§2–§5) |
| `SecretScope.kt` | `SecretScope` (get/put/delete/describe) — единственный доступ плагина к секретам. Скоуп привязан к паре (userId, tool) на конструировании: чужие секреты недостижимы архитектурно. `SecretMeta` — мета без значения. `FakeSecretScope` — для тестов и dev |

## Гарантии и обязанности

Автору гарантируется: вход уже провалидирован, секреты скоуплены, рендер/транспорт/exit codes — не его забота. Автор обязан: описать всю поверхность в дескрипторе, переводить ошибки апстрима в конверты (без URL и стектрейсов наружу), бампировать `manifestVersion` при изменениях, держать вызовы stateless. Подробно — [ARCHITECTURE.md](../ARCHITECTURE.md), «Контракт плагина».

## Зависимости

- `:protocol` (дескрипторы, конверт ошибок), ktor-client-core (только тип `HttpClient` в сигнатурах).

## Тесты

Своих нет — обкатывается плагинами и сервером (`JiraPluginTest`, `ServerRoutesTest`).
