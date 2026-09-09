# Участие в Tengu

Сообщения об ошибках, предложения и pull requests приветствуются. Писать можно
по-русски или по-английски. Перед новой задачей проверь [GitHub Issues](https://github.com/finnetrolle/tengu/issues)
и [реестр работ](specs/WORK_ITEMS.md). Для крупного изменения сначала опиши
проблему и ожидаемое поведение в issue.

## Локальная разработка

Нужны Git и JDK 21+. Gradle Wrapper скачивает Gradle и зависимости; Kotlin/Native
при первой сборке скачивает свой toolchain. Сборка macOS CLI требует Apple Silicon
и полного Xcode. Начни с [quickstart](README.md#быстрый-старт).

```sh
git clone https://github.com/finnetrolle/tengu.git
cd tengu
./gradlew check
```

На Windows используй `gradlew.bat`. GitHub Actions не требуется для локального
цикла работы: результаты проверок указываются в pull request.

## Код и тесты

Следуй официальному стилю Kotlin: отступ четыре пробела, `UpperCamelCase` для
типов, `lowerCamelCase` для функций и свойств. Имена файлов соответствуют основным
объявлениям, пакеты начинаются с `ru.finnetrolle.tengu`.

- Сохраняй границы из [ARCHITECTURE.md](ARCHITECTURE.md): `:cli` зависит от `:protocol` и `:toon`, без зависимости от `:toolkit` и плагинов.
- Новое поведение покрывай регрессионными тестами на `kotlin.test`; тестовые файлы называй `*Test.kt`.
- Изменения рендера проверяй golden-парами JSON/TOON в `toon/src/jvmTest/resources/golden`.
- Изменения команд, флагов и манифеста сопровождай обновлением `ServerInfo.MANIFEST_VERSION`, документации и примеров CLI.
- Изменения пользовательского потока CLI/сервера отражай в `scripts/e2e.sh`.

## Проверки перед pull request

```sh
./gradlew check          # unit/golden-тесты и Detekt; native-тесты по хосту
./gradlew build          # полная сборка
```

Для работы над отдельным модулем:

```sh
./gradlew :protocol:jvmTest :toon:jvmTest
./gradlew :toolkit:test :plugins:jira:test :server:test
```

E2E для изменений CLI/сервера:

```sh
bash scripts/e2e.sh
```

Скрипт требует `JAVA_HOME`, curl и свободный порт 8080. Он поддерживает Linux и
Windows через Git Bash/MSYS; macOS пока не поддерживается. На macOS проверь
quickstart нативным CLI и явно укажи это ограничение в PR. Скрипт запускает свой
сервер, поэтому предварительно останови другой экземпляр на этом порту.
Живые вызовы Jira требуют отдельного тестового стенда; без переменных
`TENGU_E2E_JIRA_*` сценарий S6 пропускается. Не используй для него рабочие секреты.

Опциональный отчёт покрытия JVM:

```sh
./gradlew :jacocoTestReport
```

HTML: `build/reports/jacoco/test/html/index.html`; XML:
`build/reports/jacoco/test/jacocoTestReport.xml`. Native CLI в JaCoCo не входит.

## Pull requests

Один PR решает одну задачу. Укажи проблему, новое поведение, связанную issue и
фактически выполненные проверки. Для изменений CLI приложи пример команды и
вывода; если проверка пропущена, напиши причину. Коммиты делай короткими, с
областью изменения, например `cli: clarify setup help`.

Не включай `.env`, токены, PAT, конфигурацию личного CLI, `data/` и результаты
сборки. Об уязвимостях сообщай приватно по [SECURITY.md](SECURITY.md).
