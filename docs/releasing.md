# Ручной выпуск версии

Релизы собираются и публикуются вручную, без обязательного GitHub Actions.
Команды ниже показывают выпуск `0.1.0`; для следующей версии замени номер во всех
именах и проверь версии в `build.gradle.kts`, `Version.kt` CLI и `ServerInfo.kt` сервера.
Версия манифеста меняется только вместе с поверхностью команд.

## Подготовка и проверка

Используй зафиксированный commit без локальных изменений кода. Для macOS нужен
Mac с полным Xcode. Сначала выполни локальные проверки:

```sh
./gradlew check
./gradlew :cli:linkReleaseExecutableLinuxX64 :cli:linkReleaseExecutableMingwX64 :server:distZip
```

На macOS:

```sh
xcrun xcodebuild -version
./gradlew :cli:linkReleaseExecutableMacosArm64
```

Проверь наличие свежих бинарников: задачи не должны быть пропущены из-за
отсутствующего Xcode. `UP-TO-DATE` допустим для неизменившихся исходников.
На целевых ОС проверь `--version`, `--help`, `setup` и `status` с изолированным
тестовым сервером. E2E на Linux/Windows: `bash scripts/e2e.sh`.
Запиши в release notes, какие проверки реально выполнены и какие платформы
только собраны. Живую Jira проверяй только на отдельном тестовом стенде.

## Архивы

Собери в `build/release` следующие файлы:

| Имя | Содержимое |
|---|---|
| `tengu-0.1.0-linux-x64.tar.gz` | Linux `tengu.kexe` под именем `tengu`, `LICENSE` |
| `tengu-0.1.0-macos-arm64.tar.gz` | macOS `tengu.kexe` под именем `tengu`, `LICENSE` |
| `tengu-0.1.0-windows-x64.zip` | `tengu.exe`, `LICENSE` |
| `tengu-server-0.1.0.zip` | Gradle `server/build/distributions/server-0.1.0.zip` и `server-0.1.0/LICENSE` |

В tar-архивах сохрани право исполнения CLI, в серверном ZIP - права скрипта
`bin/server`. Не включай `.env`, `data/`, кэши, конфигурацию CLI, отладочные символы
и пользовательские секреты. Распакуй архивы в пустой каталог и проверь их содержимое
и запуск. macOS и Windows сборки без подписи должны быть явно обозначены.

Из каталога с четырьмя архивами создай контрольные суммы, например на macOS:

```sh
cd build/release
shasum -a 256 tengu-0.1.0-linux-x64.tar.gz tengu-0.1.0-macos-arm64.tar.gz tengu-0.1.0-windows-x64.zip tengu-server-0.1.0.zip > SHA256SUMS
shasum -a 256 -c SHA256SUMS
```

На Linux можно использовать `sha256sum`. Release notes должны содержать commit,
возможности выпуска, ограничения, ссылку на установку и результаты проверок.

## Публикация

Отправь commit в GitHub, затем создай тег, указывающий именно на проверенный commit.
Вместо `COMMIT_SHA` и `NOTES_FILE` подставь полный хеш и путь к подготовленным notes:

```sh
gh release create v0.1.0 build/release/*.tar.gz build/release/*.zip build/release/SHA256SUMS \
  --repo finnetrolle/tengu --target COMMIT_SHA --title "Tengu 0.1.0 (MVP)" \
  --notes-file NOTES_FILE --prerelease --draft
```

Проверь состав draft, контрольные суммы и notes, затем опубликуй:

```sh
gh release edit v0.1.0 --repo finnetrolle/tengu --draft=false
```

После публикации проверь доступность архивов на [странице релизов](https://github.com/finnetrolle/tengu/releases).
Не заменяй опубликованные бинарники другой сборкой той же версии: исправления
должны выходить новым выпуском.
