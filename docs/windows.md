# Запуск tengu-сервера на Windows

Инструкция для cmd и PowerShell. Для Git Bash подойдёт [общий quickstart в README](../README.md).

## 0. Java

Нужен JDK 21+ (проверено на 25). Проверка:

```powershell
java -version
```

Если команда не найдена — установи JDK (например, [Adoptium Temurin 25](https://adoptium.net/)) или используй уже имеющийся `~/.jdks/openjdk-25`.

**Переменные на одну сессию** (PowerShell):

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\openjdk-25"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

(cmd):

```bat
set JAVA_HOME=%USERPROFILE%\.jdks\openjdk-25
set PATH=%JAVA_HOME%\bin;%PATH%
```

**Переменные навсегда** (выполнить один раз в PowerShell, подхватят только новые терминалы):

```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", "$env:USERPROFILE\.jdks\openjdk-25", "User")
$dir = "$([Environment]::GetEnvironmentVariable('JAVA_HOME','User'))\bin"
$old = [Environment]::GetEnvironmentVariable("Path", "User")
[Environment]::SetEnvironmentVariable("Path", "$dir;$old", "User")
```

## 1. Сборка проекта

```powershell
cd $env:USERPROFILE\dev\tengu
.\gradlew.bat build
```

Первая сборка скачивает Gradle 9.7.1 и зависимости — примерно 3 минуты. Дождись `BUILD SUCCESSFUL`.

## 2. Запуск сервера

Переменные окружения и запуск (PowerShell):

```powershell
cd $env:USERPROFILE\dev\tengu
$env:TENGU_HUB_TOKENS   = "dev=h-dev123"
$env:TENGU_DEV_SECRETS  = "1"
$env:TENGU_JIRA_BASE_URL = "https://jira.corp"   # твой Jira; без него jira-тул не загрузится

.\gradlew.bat :server:run
```

(cmd):

```bat
cd %USERPROFILE%\dev\tengu
set TENGU_HUB_TOKENS=dev=h-dev123
set TENGU_DEV_SECRETS=1
set TENGU_JIRA_BASE_URL=https://jira.corp
gradlew.bat :server:run
```

Расшифровка переменных:

| Переменная | Значение | Зачем |
|---|---|---|
| `TENGU_HUB_TOKENS` | `пользователь=токен` | кто допущен к хабу; здесь пользователь `dev`, токен `h-dev123` |
| `TENGU_DEV_SECRETS` | `1` | секреты (PAT) в файл вместо Vault; только для разработки |
| `TENGU_DEV_SECRETS_DIR` | путь | корень файлового хранилища секретов (по умолчанию `data\`) |
| `TENGU_JIRA_BASE_URL` | URL Jira | регистрирует jira-плагин; без неё останется только тул `status` |
| `TENGU_PORT` | число | порт, по умолчанию 8080 |

Сервер готов, когда в логе появится:

```
Responding at http://127.0.0.1:8080
```

Проверка из другого окна (curl встроен в Windows 10+):

```powershell
curl http://localhost:8080/v1/health
# {"serverVersion":"0.1.0","tools":2,"manifestVersion":2}
```

Останов сервера — `Ctrl+C` в его окне.

**Если порт 8080 занят** (например, сервер упал, а java-процесс остался):

```powershell
netstat -ano | findstr :8080     # последняя колонка — PID
taskkill /PID <PID> /F
```

## 3. CLI `tengu` (второе окно)

```powershell
cd $env:USERPROFILE\dev\tengu
.\gradlew.bat :cli:installDist
.\cli\build\install\tengu\bin\tengu.bat setup --url http://localhost:8080 --token h-dev123
.\cli\build\install\tengu\bin\tengu.bat          # дашборд доступных тулов
```

Конфиг сохраняется в `%APPDATA%\tengu` — `setup` делается один раз.

## 4. Проверка полного цикла с Jira

При запущенном сервере с `TENGU_JIRA_BASE_URL`:

```powershell
$t = ".\cli\build\install\tengu\bin\tengu.bat"
$t jira auth status                               # "no PAT configured" — норма до логина
echo <твой-PAT> | & $t jira auth login --token -  # PAT уедет на сервер в data\secrets\
$t jira issues list --project <KEY>               # живые тикеты
$t jira issues view <KEY-1>
```
