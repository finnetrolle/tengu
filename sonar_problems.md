# SonarQube: результаты анализа tengu

Дата анализа: 2026-08-27
SonarQube: Community Build 26.8.0.126808
Ключ проекта: `local:tengu:5b6257d3628b`
Quality Gate: **OK**

**Открытых проблем нет.** Все находки анализа 2026-08-27 закрыты (см. историю исправлений). Подтверждено повторным анализом 2026-08-27: 0 issues, 0 hotspots, Quality Gate OK.

## Метрики

| Метрика | Значение |
|---|---:|
| Строк кода (`ncloc`) | 2424 |
| Bugs | 0 |
| Vulnerabilities | 0 |
| Security hotspots | 0 |
| Code smells | 0 |
| SonarQube coverage | 54.3% |
| JaCoCo line coverage | 72.7% (737/1014) |
| JaCoCo branch coverage | 47.2% (274/580) |
| Duplicated lines | 0.0% |
| Reliability rating | A (1.0) |
| Security rating | A (1.0) |
| Maintainability rating | A (1.0) |

SonarQube импортировал агрегированный JaCoCo XML. Его общая coverage учитывает
покрытие строк и условий, поэтому отличается от отдельной JaCoCo line coverage.

## История исправлений

| Дата | Правило | Исправление |
|---|---|---|
| 2026-08-27 | docker:S6471 | Runtime-stage Dockerfile: создан системный пользователь `tengu` (uid 1001), `COPY --chown`, `USER tengu` перед ENTRYPOINT. Проверено: docker build + запуск контейнера (health 200, `id` = uid 1001) |
| 2026-08-27 | kotlin:S1192 | ServerDeps.kt: литерал "malformed invoke request body" вынесен в `private const val MALFORMED_INVOKE_BODY` |
| 2026-08-27 | kotlin:S6626 | cli/build.gradle.kts: задаче `extractCurlStatic` добавлены group `build` и description (обход KTOR-9460) |
| 2026-08-27 | kotlin:S6615 | ToolInvocation.kt: удалено мёртвое присваивание `tool = freshTool` (значение не читалось после блока refresh) |
| 2026-08-27 | kotlin:S6517 | HubAuth.kt: `interface HubAuth` стал `fun interface` (SAM-конверсия, контракт сохранён) |
| 2026-08-27 | JaCoCo | Добавлен агрегированный отчёт для JVM-тестов `protocol`, `toon`, `toolkit`, `plugins:jira` и `server`; SonarQube coverage выросла с 0.0% до 54.3% |
| 2026-08-27 | kotlin:S6532 | VaultSecretsStore.kt: `ensureSuccess` переписан на `check(status.isSuccess()) { ... }` без смены типа исключения и сообщения |

Попутно (вне реестра, найдено при верификации docker:S6471): сборка Docker-образа была сломана - build-stage не копировал `cli/`, а `settings.gradle.kts` включает `:cli` (Gradle 9: "Configuring project ':cli' without an existing directory is not allowed"). Исправлено: `COPY cli ./cli` в Dockerfile, `cli` убран из `.dockerignore`.
