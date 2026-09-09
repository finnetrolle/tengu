# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Tengu is a tool hub for AI agents: a single AXI-compatible CLI (`tengu`) that agents use to discover and invoke corporate tools (Jira, ...). Plugins and credentials live on a central server; the agent never sees credentials. Kotlin/Gradle modular monolith (JVM server) + Kotlin/Native CLI.

Full architecture and decisions: `ARCHITECTURE.md` (living document - keep it current when decisions change). Agent interface standard: `.agents/skills/axi/SKILL.md`. Output format: TOON (toonformat.dev).

## Commands

JDK 21+ required (toolchains auto-provision via foojay).

```sh
./gradlew build                                    # all modules: compile + unit/golden tests + detekt
./gradlew :server:run                              # local server (env vars below)
./gradlew :cli:linkReleaseExecutableMingwX64       # → cli/build/bin/mingwX64/releaseExecutable/tengu.exe
./gradlew :cli:linkReleaseExecutableLinuxX64       # → cli/build/bin/linuxX64/releaseExecutable/tengu.kexe
./gradlew :cli:linkReleaseExecutableMacosArm64     # → cli/build/bin/macosArm64/releaseExecutable/tengu.kexe (mac host only)
bash scripts/e2e.sh                                # E2E S1-S8: builds native CLI, starts own server on :8080
```

Single test (JVM modules):

```sh
./gradlew :server:test --tests "ru.finnetrolle.tengu.server.ServerRoutesTest"
./gradlew :plugins:jira:test --tests "*JiraPluginTest"
```

KMP modules (`:protocol`, `:toon`): common tests run through the JVM target:

```sh
./gradlew :protocol:jvmTest --tests "*ValidateTest"
```

Native tests (`mingwX64Test`/`linuxX64Test`/`macosArm64Test`) execute only on their matching host: the `macosArm64` target is created only on a macOS host (K/N cannot cross-compile Apple targets from Linux/Windows), and `onlyIf` in root `build.gradle.kts` gates the other cross-cases (e.g. `mingwX64Test` on Linux). `scripts/e2e.sh` supports only Linux and Windows (MINGW) hosts - on macOS it aborts. First native link downloads the konan toolchain (~1 GB into `~/.konan`).

Local server env:

```sh
TENGU_HUB_TOKENS="dev=h-dev123" TENGU_DEV_SECRETS=1 TENGU_JIRA_BASE_URL="https://jira.corp" ./gradlew :server:run
```

Live-Jira E2E additionally needs `TENGU_E2E_JIRA_URL`, `TENGU_E2E_JIRA_PAT`, `TENGU_E2E_JIRA_PROJECT` (S6 is skipped otherwise).

## Architecture

Gradle modules (packages `ru.finnetrolle.tengu.<module>`):

| Module | Role |
|---|---|
| `:protocol` | Wire DTOs (manifest, InvokeRequest/Response, AxiErrorEnvelope) + shared usage validation (`Validate` object). KMP: jvm + mingwX64 + linuxX64 (+ macosArm64 on mac hosts) |
| `:toon` | TOON encoder over JsonElement. KMP. Golden fixtures in `toon/src/jvmTest/resources/golden` |
| `:toolkit` | Plugin SDK: `ToolPlugin`, `InvocationContext`, `AxiResult`, `AxiPayloads`, `SecretScope` |
| `:plugins:jira` | JiraPlugin, JiraApiClient, commands. Template for future integrations |
| `:server` | Ktor: bearer auth → userId, PluginRegistry, 3 endpoints (`/v1/health`, `/v1/manifest`, `/v1/invoke`), SecretsStore, StatusTool |
| `:cli` | Thin Kotlin/Native client (clikt): manifest cache, validation, proxy, TOON render, exit codes. native-only - no JVM target |

Hard boundary: **`:cli` must not depend on `:toolkit` or plugins**. CLI is a thin proxy; `:protocol`/`:toon` are the shared KMP libs (server consumes jvm(), CLI consumes native variants).

Key invariants:

- **ToolDescriptor is the single source of truth** (name, summary, commands with hierarchical paths, flags, defaults, `allowedValues`, `renamedFlags`). Server builds `/v1/manifest` from it; CLI builds validation and `--help` from it.
- **Usage validation is shared `Validate` entry points (`tool`/`command`/`invoke`) in `:protocol`, executed on both sides**: CLI validates against cached manifest before any network call (usage error → exit 2, no roundtrip); server revalidates (defense in depth).
- **Exit codes / streams**: usage errors → exit 2, runtime errors → exit 1, all structural output → stdout, stderr stays empty.
- **409 STALE_MANIFEST** → CLI refreshes manifest and retries once, invisible to the agent. Manifest cache TTL 24 h; also self-heals on unknown tool/command.
- **`manifestVersion` must be bumped on ANY surface change** (commands/flags/renames). Handshake verified end-to-end by scenario S8; e2e runs in CI on every push.
- **Secrets**: Vault in prod (`secret/tengu/{user}/{tool}`); `FileSecretsStore` only for dev/test behind `TENGU_DEV_SECRETS=1` (unencrypted). Plugin secret scopes are limited to (user, tool) pairs - other users' secrets are unreachable by construction. Secret flag values support stdin via `-`.

## Conventions

- Official Kotlin style, four-space indent, `UpperCamelCase` types, `lowerCamelCase` functions/properties, file name matches primary declaration.
- Tests: `kotlin.test`, classes/files named `*Test`, mirroring source sets. New behavior needs regression coverage; renderer changes need golden JSON/TOON pairs.
- Extend `scripts/e2e.sh` when user-visible CLI/server flows change. Note: S3 hardcodes the jira command count (`commands[9]`) - update it when the tool surface changes.
- Detekt config: `config/detekt/` (+ `detekt-toon.yml` for `:toon`). Run `./gradlew check` before submitting.
- Commits: concise, single-line, area-prefixed (`cli: ...`, `server: ...`).
- CI (`.github/workflows/ci.yml`): full build + Windows cross-compile + E2E must pass.
- Never commit hub tokens, Jira PATs, Vault credentials, `.env`, or generated `data/`.
