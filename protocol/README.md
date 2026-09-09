# :protocol - контракты провода

DTO и чистые функции протокола клиент↔сервер. Единственный модуль, который знают обе стороны: CLI валидирует по нему локально, сервер перепроверяет - один код, поведение сторон не расходится. Ни Ktor, ни Vault, ни Clikt здесь нет.

## Состав

| Файл | Что внутри |
|---|---|
| `Descriptors.kt` | Поверхность тулов: `ToolDescriptor` → `CommandDescriptor` (иерархический `path`, args, flags, fields, `renamedFlags`, examples). `Manifest` - каталог всех тулов (отдаётся `GET /v1/manifest`, кэшируется CLI) |
| `Invoke.kt` | `InvokeRequest` / `InvokeResponse`. Все значения args/flags по проводу - строки; типы проверяет `Validate` |
| `Errors.kt` | `ErrorKind` (USAGE, AUTH, NOT_FOUND, STALE_MANIFEST, UPSTREAM, INTERNAL), `AxiErrorEnvelope` - единственный конверт ошибок (и тело HTTP-ответа, и рендер CLI). Маппинги `exitCode()` (USAGE → 2, прочее → 1) и `httpStatus()` (400/401/404/409/502/500) |
| `Validate.kt` | Совместная usage-валидация: `tool()` / `command()` / `invoke()`. Неизвестные и переименованные флаги, обязательные, типы, `allowedValues`, позиционные аргументы. Возвращает `null`, если всё в порядке |
| `ProtocolJson.kt` | Единая Json-конфигурация провода: компактно (`encodeDefaults = false`), терпимо к новым полям |

## Зависимости

- kotlinx-serialization-json - единственная. Сам модуль не зависит от других модулей проекта.

## Тесты

`ValidateTest`, `ManifestRoundTripTest` - `./gradlew :protocol:jvmTest`

## Инварианты

- `manifestVersion` бампируется при **любом** изменении поверхности тулов (команды/флаги/переименования) - от этого зависит handshake 409 STALE_MANIFEST.
- Сообщение ошибки самодостаточно, helpHints исправляют за один ход (AXI §6).

Общая картина - [ARCHITECTURE.md](../ARCHITECTURE.md), протокол - раздел «Протокол клиент↔сервер».
