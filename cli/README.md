# :cli — агентский CLI

Тонкий прокси `tengu`: discover и вызов тулов через хаб. **Жёсткое правило: не зависит от `:toolkit` и плагинов** — минимальный класс-граф ради startup-латентности и будущего native-image. Всю поверхность CLI узнаёт из манифеста по проводу.

## Поведение

| Вызов | Что делает |
|---|---|
| `tengu` | No-args дашборд: bin/description + таблица тулов из кэша (content-first, AXI §8) |
| `tengu --version` | Fast-path **до загрузки** Clikt/Ktor (AXI §10) |
| `tengu setup --url --token` | Конфиг + немедленная проверка (манифест не ответил — конфиг не сохраняется) |
| `tengu tools [list]` / `tools show <tool>` | Каталог из кэша манифеста |
| `tengu manifest refresh` | Принудительное обновление кэша |
| `tengu <tool> <command> …` | Агентский путь: локальная валидация по кэшу → invoke → TOON-рендер |

Ключевые конвенции: exit 2 — usage, exit 1 — рантайм; весь структурный вывод — TOON в stdout, stderr пуст; `'-'` у secret-флага читает значение из stdin (токен не оседает в истории шелла); 409 `STALE_MANIFEST` → авто-refresh и один ретрай, агент этого не замечает; unknown tool/command в кэше → refresh и перепроверка.

## Состав

| Файл | Что внутри |
|---|---|
| `Main.kt` | Точка входа: fast-path `--version`, дашборд, builtins (`tools/setup/manifest`) через Clikt vs агентский путь |
| `CliRuntime.kt` | Среда выполнения: `requireConfig`, `ensureManifest` (кэш → TTL → сеть), `fail` (конверт → TOON + exit code) |
| `CliConfig.kt` | `config.json` в `%APPDATA%\tengu` (Win) / `~/.config/tengu` |
| `ManifestCache.kt` | Кэш манифеста с TTL 24 ч; протухший кэш всё равно читается оффлайн (валидация и `--help` без сервера) |
| `ServerClient.kt` | HTTP-клиент хаба: fetchManifest/invoke, таймаут 30 с; любые сбои → `ServerError` с AXI-конвертом |
| `tool/ToolInvocation.kt` | Сердце агентского пути: матчинг command path, разбор `--flag value/--flag=value`/позиционных, валидация ДО сети, stdin-секреты, 409-ретрай |
| `tool/HelpText.kt` | `--help` тула и команды: флаги с дефолтами, аргументы, примеры |
| `cmd/*` | Clikt-обёртки builtins: Setup, Tools (list/show), ManifestRefresh |
| `render/ToonRenderer.kt` | Весь вывод — TOON (AXI §1/§6/§9): response/error + `help[N]`; `Dashboard`, `ToolDetail` |

## Зависимости

`:protocol`, `:toon`, clikt, ktor-client-cio, slf4j-nop (stderr CLI должен быть пустым).

## Сборка и тесты

```sh
./gradlew :cli:installDist
# → cli/build/install/tengu/bin/tengu (Windows: tengu.bat)
```

Юнит-тестов в модуле нет — поведение покрыто e2e-сценариями S1–S8 (`bash scripts/e2e.sh`).

Протокол и топология — [ARCHITECTURE.md](../ARCHITECTURE.md); быстрый старт — [README.md](../README.md).
