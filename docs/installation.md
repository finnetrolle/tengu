# Установка Tengu

CLI и сервер устанавливаются отдельно. Если хаб уже работает, достаточно CLI,
адреса сервера и выданного администратором hub token. JVM на машине CLI не нужна.

## Готовые архивы

Скачай архив своей платформы и `SHA256SUMS` из
[GitHub Releases](https://github.com/finnetrolle/tengu/releases). Выпуск 0.1.0 имеет
статус MVP / prerelease.

| Архив | Назначение |
|---|---|
| `tengu-0.1.0-linux-x64.tar.gz` | CLI для Linux x64 с glibc |
| `tengu-0.1.0-macos-arm64.tar.gz` | CLI для macOS Apple Silicon |
| `tengu-0.1.0-windows-x64.zip` | CLI для Windows x64 |
| `tengu-server-0.1.0.zip` | Сервер с зависимостями, требуется JDK 21+ |

Перед распаковкой сравни SHA256 скачанного файла со строкой в `SHA256SUMS`.
Например, на Linux:

```sh
sha256sum tengu-0.1.0-linux-x64.tar.gz
```

На macOS:

```sh
shasum -a 256 tengu-0.1.0-macos-arm64.tar.gz
```

На Windows:

```powershell
Get-FileHash .\tengu-0.1.0-windows-x64.zip -Algorithm SHA256
```

### Linux / macOS

Для Linux:

```sh
tar -xzf tengu-0.1.0-linux-x64.tar.gz
mkdir -p "$HOME/.local/bin"
install -m 0755 tengu "$HOME/.local/bin/tengu"
export PATH="$HOME/.local/bin:$PATH"
tengu --version
```

На macOS используй архив `tengu-0.1.0-macos-arm64.tar.gz`; остальные команды те же.
Добавь `$HOME/.local/bin` в PATH своей оболочки для новых терминалов.
macOS-бинарник не подписан Developer ID и не нотарифицирован. Если macOS блокирует
запуск, после проверки источника и SHA256 разреши его в System Settings → Privacy & Security
или собери CLI из исходников. Глобально отключать Gatekeeper не нужно.

### Windows

```powershell
Expand-Archive .\tengu-0.1.0-windows-x64.zip -DestinationPath .\tengu-cli
.\tengu-cli\tengu.exe --version
```

Добавь каталог `tengu-cli` в пользовательский PATH или вызывай `.exe` по полному пути.
Windows-бинарник не подписан Authenticode.

### Подключение к существующему серверу

```sh
tengu setup --url https://tengu.example.com --token YOUR_HUB_TOKEN
tengu status
```

Подставь адрес и hub token своего сервера. Для локального первого запуска используй
[quickstart](../README.md#быстрый-старт); Jira PAT настраивается отдельной командой.

## Сборка CLI из исходников

Нужны Git и JDK 21+. На macOS дополнительно нужен полный Xcode с выбранными
developer tools; `xcrun xcodebuild -version` должен завершаться успешно.
При недоступном Xcode Gradle пропускает macOS-линковку: `BUILD SUCCESSFUL` само по
себе не означает, что macOS-бинарник появился.

```sh
git clone https://github.com/finnetrolle/tengu.git
cd tengu
```

| Платформа | Команда | Исполняемый файл |
|---|---|---|
| Linux x64 | `./gradlew :cli:linkReleaseExecutableLinuxX64` | `cli/build/bin/linuxX64/releaseExecutable/tengu.kexe` |
| macOS Apple Silicon | `./gradlew :cli:linkReleaseExecutableMacosArm64` | `cli/build/bin/macosArm64/releaseExecutable/tengu.kexe` |
| Windows x64 | `.\gradlew.bat :cli:linkReleaseExecutableMingwX64` | `cli\build\bin\mingwX64\releaseExecutable\tengu.exe` |

Первый native build скачивает toolchain Kotlin/Native в `~/.konan` (порядка 1 ГБ).
Linux-бинарник использует glibc и не предназначен для Alpine/musl.
Apple-таргет доступен только на macOS. Intel Mac и Linux ARM пока не имеют CLI-таргетов.
Скопируй результат в каталог PATH под именем `tengu` / `tengu.exe`.

## Сервер из архива

Распакуй `tengu-server-0.1.0.zip`. В каталоге `server-0.1.0` находятся скрипты
запуска `bin/server` и `bin/server.bat`, а также JAR-зависимости. Требуется JDK 21+
в PATH или корректный `JAVA_HOME`.

Linux / macOS:

```sh
unzip tengu-server-0.1.0.zip
TENGU_HUB_TOKENS="dev=tengu-local" TENGU_DEV_SECRETS=1 ./server-0.1.0/bin/server
```

PowerShell:

```powershell
Expand-Archive .\tengu-server-0.1.0.zip -DestinationPath .\tengu-server
$env:TENGU_HUB_TOKENS = "dev=tengu-local"
$env:TENGU_DEV_SECRETS = "1"
.\tengu-server\server-0.1.0\bin\server.bat
```

Это локальный dev-запуск. [Переменные сервера и стенд с Vault](../server/README.md),
[одноразовый Docker-профиль](local-docker.md), [границы безопасности](../SECURITY.md).
