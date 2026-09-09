# Быстрый старт на Windows

Нужны Windows x64, Git и JDK 21+ в PATH. Готовый CLI не требует JVM; JVM нужна
серверу и сборке из исходников. [Архивы релиза и установка](installation.md).

## 1. Получить исходники

PowerShell или cmd:

```powershell
git clone https://github.com/finnetrolle/tengu.git
cd tengu
java -version
```

Если Java не найдена, установи JDK 21+ и настрой `JAVA_HOME` на каталог JDK,
а `%JAVA_HOME%\bin` добавь в PATH. Открой новый терминал после изменения PATH.

## 2. Запустить сервер без Jira

PowerShell, терминал 1 из каталога `tengu`:

```powershell
$env:TENGU_HUB_TOKENS = "dev=tengu-local"
$env:TENGU_DEV_SECRETS = "1"
.\gradlew.bat :server:run
```

cmd:

```bat
set TENGU_HUB_TOKENS=dev=tengu-local
set TENGU_DEV_SECRETS=1
gradlew.bat :server:run
```

`tengu-local` - тестовый hub token для локального запуска, не Jira PAT.
Сервер слушает порт 8080. Остановить его можно через Ctrl+C.
Если порт занят, останови другой экземпляр сервера или задай `TENGU_PORT` и
используй этот же порт в командах ниже.

## 3. Собрать CLI и проверить подключение

PowerShell, терминал 2 из того же каталога `tengu`:

```powershell
.\gradlew.bat :cli:linkReleaseExecutableMingwX64
$tengu = (Resolve-Path ".\cli\build\bin\mingwX64\releaseExecutable\tengu.exe").Path
curl.exe -fsS http://127.0.0.1:8080/v1/health
& $tengu setup --url http://127.0.0.1:8080 --token tengu-local
& $tengu
& $tengu status
```

Если используешь архив релиза, укажи в `$tengu` путь к распакованному `tengu.exe`
и пропусти сборку. `status` должен показать версию и uptime сервера. Конфигурация
CLI сохраняется в `%APPDATA%\tengu`; `setup` нужен только при изменении сервера или токена.

Для cmd вместо `& $tengu` вызывай `.\cli\build\bin\mingwX64\releaseExecutable\tengu.exe`.
Чтобы пользоваться короткой командой `tengu`, добавь каталог бинарника в пользовательский PATH.

## 4. Подключить Jira

Останови сервер в терминале 1, добавь URL своей Jira и запусти его снова:

```powershell
$env:TENGU_JIRA_BASE_URL = "https://jira.example.com"
.\gradlew.bat :server:run
```

В cmd: `set TENGU_JIRA_BASE_URL=https://jira.example.com`, затем `gradlew.bat :server:run`.
Переменные hub token и dev-режима из шага 2 должны оставаться в этой сессии.

Введи PAT самостоятельно в обычном PowerShell. Значение скрыто при вводе,
не попадает в текст команды и передаётся CLI через stdin:

```powershell
$jiraPat = Read-Host "Jira PAT" -AsSecureString
$patPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($jiraPat)
try {
    [Runtime.InteropServices.Marshal]::PtrToStringBSTR($patPointer) | & $tengu jira auth login --token -
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($patPointer)
    $jiraPat.Dispose()
    Remove-Variable jiraPat, patPointer
}

& $tengu jira projects list
& $tengu jira issues list --project FOO --limit 5
```

Замени `FOO` ключом из списка проектов. PAT временно преобразуется в обычную строку
в памяти PowerShell, чтобы отправить его процессу CLI. Не вводи его в чат или
публичные логи. В cmd для безопасного ввода PAT перейди в PowerShell.

## Конфигурация сервера

| Переменная | Назначение |
|---|---|
| `TENGU_HUB_TOKENS` | Пользователи хаба: `user=token,...` |
| `TENGU_JIRA_BASE_URL` | Адрес Jira; без него доступен только `status` |
| `TENGU_DEV_SECRETS=1` | Незашифрованные файлы PAT, только для разработки |
| `TENGU_DEV_SECRETS_DIR` | Каталог файлового хранилища, по умолчанию `data`; файлы `{user}/{tool}.json` |
| `TENGU_PORT` | Порт сервера, по умолчанию 8080 |

Для общего стенда используй собственные hub tokens и
[конфигурацию сервера с Vault](../server/README.md). Для контейнера с PAT в памяти
есть [локальный Docker-профиль](local-docker.md).
