# :cli — агентский CLI

Тонкий прокси `tengu`: discover и вызов тулов через хаб. **Жёсткое правило: не зависит от `:toolkit` и плагинов** — минимальный класс-граф ради startup-латентности. Всю поверхность CLI узнаёт из манифеста по проводу.

**Native-only KMP-модуль** (mingwX64 + linuxX64): самодостаточный бинарь без JVM на машине агента. HTTP-движки: WinHttp на Windows (системная winhttp.dll, TLS через SChannel), Curl на Linux (статическая линковка).

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
| `platform/*` | expect/actual-слой вместо java.*: env, файловый IO, stdin, время, HTTP-движок (WinHttp/Curl), UTF-8-консоль Windows. `nativeMain` — общий posix, `mingwX64Main`/`linuxX64Main` — специфика |

## Зависимости

`:protocol`, `:toon`, clikt, ktor-client-core; платформенно: ktor-client-winhttp (mingwX64), ktor-client-curl (linuxX64), kotlinx-coroutines-core. JVM-зависимостей нет — модуль не имеет jvm()-таргета.

## Сборка и тесты

```sh
./gradlew :cli:linkReleaseExecutableMingwX64   # Windows → cli/build/bin/mingwX64/releaseExecutable/tengu.exe
./gradlew :cli:linkReleaseExecutableLinuxX64   # Linux (кросс-компиляция с любого хоста) → …/linuxX64/releaseExecutable/tengu.kexe
```

Первый запуск скачивает тулчейн Kotlin/Native (~1 ГБ в `~/.konan`), дальше из кэша.

Нюанс линковки: ktor-client-curl 3.4+ бандлит libcurl/libssl/libcrypto статикой внутри klib в порядке, ломающем GNU-линкер (KTOR-9460); обход — задача `extractCurlStatic` + `linkerOpts` в `build.gradle.kts`, возвращают архивы в конец линии линкера.

Нюанс рантайма: у curl-движка известное зависание после серии запросов в одном процессе (KTOR-9141). CLI — процесс на 1–3 запроса с выходом, паттерн «процесс-на-вызов» проблему не затрагивает; не делать из tengu долгоживущий демон без смены движка.

Юнит-тестов в модуле нет — поведение покрыто e2e-сценариями S1–S8 (`bash scripts/e2e.sh`, гоняет нативный бинарь под ОС хоста).

Протокол и топология — [ARCHITECTURE.md](../ARCHITECTURE.md); быстрый старт — [README.md](../README.md).
