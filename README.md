# tengu

Хаб тулов для ИИ-агентов: единый AXI-совместимый CLI (`tengu`), через который агенты discover'ят корпоративные системы (Jira, …) и вызывают их. Плагины и доступы живут на центральном сервере; агент про креды не знает.

- Архитектура и решения — [ARCHITECTURE.md](ARCHITECTURE.md)
- Запуск на Windows (cmd/PowerShell) — [docs/windows.md](docs/windows.md)
- Стандарт агентного интерфейса — `.agents/skills/axi/SKILL.md`

## Как выглядит для агента

```sh
$ tengu
bin: tengu
description: Agent tool hub: discover and invoke corporate tools
tools[2]{name,summary}:
  status,Hub health and diagnostics
  jira,"Jira issues: list, view, create, comment"
help[2]:
  Run `tengu tools show <tool>` for what a tool can do
  Run `tengu <tool> <command> --help` for command usage

$ tengu jira issues list --project FOO
count: 2 of 213 total
issues[2]{key,title,state}:
  FOO-1,Fix auth bug,Open
  FOO-2,Add pagination,Closed
help[1]: Run `tengu jira issues list --project FOO --start-at 2` for the next page

$ tengu jira issues list --stat open
error: unknown flag --stat for `jira issues list`
help[1]:
  "valid flags for `jira issues list`: --project, --state, --assignee, --jql, --limit, --start-at, --fields (--help always allowed)"
```

Ошибки использования — exit 2, рантайм-ошибки — exit 1, всё структурное в stdout, stderr пуст.

## Поднятие сервера и сборка под команду `tengu`

### Шаг 0. Требования

JDK 21+ (проверено на 25). Если java нет в PATH — укажи `JAVA_HOME`:

```sh
export JAVA_HOME=~/.jdks/openjdk-25    # пример: IntelliJ-кладка ~/.jdks
java -version                          # проверка
```

### Шаг 1. Собрать проект

```sh
cd ~/dev/tengu
./gradlew build
```

Первая сборка скачивает Gradle 9.7.1 и зависимости — примерно 3 минуты. `BUILD SUCCESSFUL` = все модули и тесты зелёные.

### Шаг 2. Поднять сервер (терминал 1)

```sh
export JAVA_HOME=~/.jdks/openjdk-25

TENGU_HUB_TOKENS="dev=h-dev123" \
TENGU_DEV_SECRETS=1 \
TENGU_JIRA_BASE_URL="https://jira.corp" \
./gradlew :server:run
```

- `TENGU_HUB_TOKENS` — список `пользователь=токен` хаба (тут: пользователь `dev`, токен `h-dev123`)
- `TENGU_DEV_SECRETS=1` — секреты (PAT) в файл вместо Vault; только для разработки (корень — `TENGU_DEV_SECRETS_DIR`, по умолчанию `data/`, файлы `data/{user}/{tool}.json`)
- `TENGU_JIRA_BASE_URL` — адрес твоего Jira; без неё jira-тул не зарегистрируется (будет только `status`)

Сервер готов, когда в логе появится `Responding at http://127.0.0.1:8080`. Проверка:

```sh
curl -s http://localhost:8080/v1/health
# {"serverVersion":"0.1.0","tools":2,"manifestVersion":3}
```

Останов — Ctrl+C.

### Шаг 3. Собрать CLI

```sh
./gradlew :cli:linkReleaseExecutableMingwX64   # Windows → tengu.exe
./gradlew :cli:linkReleaseExecutableLinuxX64   # Linux → tengu.kexe (можно с любого хоста)
```

Исполняемый файл: `cli/build/bin/mingwX64/releaseExecutable/tengu.exe` (Windows) / `cli/build/bin/linuxX64/releaseExecutable/tengu.kexe` (Linux). Бинарь самодостаточный — JVM на машине не нужна. Первая сборка скачивает тулчейн Kotlin/Native (~1 ГБ в `~/.konan`).

### Шаг 4. Связать CLI с сервером (один раз)

```sh
cli/build/bin/mingwX64/releaseExecutable/tengu.exe setup --url http://localhost:8080 --token h-dev123
# setup: configured for http://localhost:8080
```

Конфиг уходит в `%APPDATA%\tengu` (Windows) / `~/.config/tengu` (Linux).

### Шаг 5. Короткая команда (опционально)

```sh
alias tengu=~/dev/tengu/cli/build/bin/mingwX64/releaseExecutable/tengu.exe
# или: скопируй tengu.exe/tengu.kexe в каталог из PATH
```

### Шаг 6. Проверка

```sh
tengu            # дашборд доступных тулов
tengu status     # полный roundtrip через сервер
```

## Проверка всего цикла

```sh
./gradlew build       # юнит/golden-тесты всех модулей
bash scripts/e2e.sh   # сценарии S1–S8, поднимает свой сервер на :8080
```

Для живого S6-сценария: `TENGU_E2E_JIRA_URL`, `TENGU_E2E_JIRA_PAT`, `TENGU_E2E_JIRA_PROJECT`.

## Продакшен-стенд

```sh
cat > .env <<EOF
TENGU_HUB_TOKENS=alice=h-xxx,bob=h-yyy
TENGU_JIRA_BASE_URL=https://jira.corp
TENGU_VAULT_TOKEN=<vault token>
EOF
docker compose up -d                  # сервер + dev-Vault
```

Секреты (PAT) пользователей хранятся в Vault (`secret/tengu/{user}/{tool}`); `TENGU_DEV_SECRETS=1` переключает на незашифрованный файловый стор — только для разработки.
