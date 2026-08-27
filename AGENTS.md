# Repository Guidelines

## Project Structure & Module Organization

Tengu is a Kotlin/Gradle modular monolith. Shared wire DTOs and validation live in `protocol/`; TOON rendering lives in `toon/`; plugin APIs belong in `toolkit/`. Integrations are isolated under `plugins/` (currently `plugins/jira/`). `server/` hosts the Ktor service, while `cli/` is a thin Kotlin/Native client for Linux and Windows. Tests mirror production source sets under each module's `src/test`, `src/commonTest`, or `src/jvmTest`; golden fixtures are in `toon/src/jvmTest/resources/golden`. Keep architectural decisions current in `ARCHITECTURE.md`.

## Build, Test, and Development Commands

- `./gradlew build`: compile every module, run unit/golden tests, and execute Detekt checks.
- `./gradlew :server:run`: start the local server; configure it with `TENGU_HUB_TOKENS` and related environment variables documented in `README.md`.
- `./gradlew :cli:linkReleaseExecutableLinuxX64`: build `tengu.kexe`; use `...MingwX64` for `tengu.exe`.
- `bash scripts/e2e.sh`: build a native CLI, start an isolated server on port 8080, and run scenarios S1-S8. Live Jira coverage is enabled only when the `TENGU_E2E_JIRA_*` variables are set.

Use JDK 21 or newer. The Gradle wrapper provisions the configured toolchain.

## Coding Style & Naming Conventions

Follow official Kotlin style with four-space indentation. Use `UpperCamelCase` for types, `lowerCamelCase` for functions and properties, and packages under `ru.finnetrolle.tengu.<module>`. File names should match their primary declaration. Run `./gradlew check` before submitting; Detekt configuration is under `config/detekt/`. Preserve the core boundary: `:cli` must not depend on `:toolkit` or integration plugins.

## Testing Guidelines

Use `kotlin.test`; name test classes and files `*Test`. Add focused module tests for behavior changes, golden JSON/TOON pairs for renderer changes, and extend `scripts/e2e.sh` when user-visible CLI/server flows change. There is no numeric coverage threshold, but new behavior must have regression coverage.

## Commit & Pull Request Guidelines

History favors concise, single-line commits, often prefixed by the affected area, such as `cli: ...` or `gitignore: ...`. Keep each commit focused. Pull requests should explain the behavior and affected modules, link the issue when applicable, list verification commands, and include representative CLI output for protocol, help, or rendering changes. CI must pass the full build, Windows cross-compile, and E2E suite.

## Security & Configuration

Never commit hub tokens, Jira PATs, Vault credentials, `.env`, or generated `data/`. Use `TENGU_DEV_SECRETS=1` only for local development; it stores secrets unencrypted.
