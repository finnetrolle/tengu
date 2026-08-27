#!/usr/bin/env bash
# E2E-сценарии tengu (S1–S8 из плана). Поднимает свой сервер на :8080.
# Требования: JAVA_HOME, curl. S6 (живой Jira) пропускается, если не задан TENGU_E2E_JIRA_URL.
set -u
cd "$(dirname "$0")/.."

# JAVA_HOME: из окружения или типичные локации
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in "$HOME/.jdks/openjdk-25" "/usr/lib/jvm/temurin-25-jdk-amd64" "/opt/java/openjdk"; do
        if [ -x "$candidate/bin/java" ]; then export JAVA_HOME="$candidate"; break; fi
    done
fi
[ -n "${JAVA_HOME:-}" ] || { echo "JAVA_HOME is not set"; exit 1; }

TENGU="cli/build/install/tengu/bin/tengu"
PASS=0
FAIL=0
TMP="$(mktemp -d)"
trap 'cleanup' EXIT

cleanup() {
    [ -n "${SERVER_PID:-}" ] && kill "$SERVER_PID" 2>/dev/null
    # убить java-ребёнка gradle, если остался
    local pid
    pid=$(netstat -ano 2>/dev/null | grep ":8080.*LISTENING" | head -1 | awk '{print $NF}')
    [ -n "$pid" ] && taskkill //PID "$pid" //F >/dev/null 2>&1
    rm -rf "$TMP"
}

ok()   { PASS=$((PASS + 1)); echo "ok   $1"; }
fail() { FAIL=$((FAIL + 1)); echo "FAIL $1 ${2:+— $2}"; }

expect_exit() { # name expected_exit cmd...
    local name="$1" expected="$2"; shift 2
    "$@" >"$TMP/out" 2>"$TMP/err"; local actual=$?
    if [ "$actual" = "$expected" ]; then ok "$name"; else fail "$name" "expected exit $expected, got $actual"; sed 's/^/     /' "$TMP/out"; fi
}
expect_out() { # name needle cmd...
    local name="$1" needle="$2"; shift 2
    "$@" >"$TMP/out" 2>"$TMP/err"
    if grep -q "$needle" "$TMP/out"; then ok "$name"; else fail "$name" "stdout missing: $needle"; sed 's/^/     /' "$TMP/out"; fi
}

echo "== сборка =="
./gradlew :cli:installDist -q || { echo "build failed"; exit 1; }

echo "== запуск сервера =="
TENGU_HUB_TOKENS="dev=h-dev123" \
TENGU_DEV_SECRETS=1 \
TENGU_DEV_SECRETS_DIR="$TMP/secrets" \
TENGU_JIRA_BASE_URL="${TENGU_E2E_JIRA_URL:-http://jira.invalid}" \
    ./gradlew :server:run -q &
SERVER_PID=$!
for _ in $(seq 1 60); do
    curl -sf -m 2 http://localhost:8080/v1/health >/dev/null && break
    sleep 1
done
curl -sf -m 2 http://localhost:8080/v1/health >/dev/null || { echo "server did not start"; exit 1; }

# изолированный конфиг CLI (не трогаем реальный %APPDATA% / ~/.config)
export APPDATA="$TMP/appdata"
export HOME="$TMP/home"
mkdir -p "$APPDATA" "$HOME"

"$TENGU" setup --url http://localhost:8080 --token h-dev123 >/dev/null || { echo "setup failed"; exit 1; }

echo "== S1: --version fast path =="
expect_exit "S1 exit 0" 0 "$TENGU" --version
expect_out  "S1 вывод" "tengu 0.1.0" "$TENGU" --version
expect_exit "S1 -V" 0 "$TENGU" -V

echo "== S2: content-first дашборд =="
expect_out "S2 bin/description" "description:" "$TENGU"
expect_out "S2 таблица тулов" "tools\[2\]{name,summary}" "$TENGU"
"$TENGU" >"$TMP/out" 2>"$TMP/err"
if [ ! -s "$TMP/err" ]; then ok "S2 stderr пуст"; else fail "S2 stderr пуст" "$(head -1 "$TMP/err")"; fi

echo "== S3: tools list/show =="
expect_out "S3 list count" "count: 2 tools" "$TENGU" tools list
# 9 команд jira: auth×3, issues×5, projects×1 — обнови при изменении поверхности
expect_out "S3 show jira" "commands\[9\]" "$TENGU" tools show jira
expect_exit "S3 exit 0" 0 "$TENGU" tools list

echo "== S4: status roundtrip =="
expect_out "S4 version" "version:" "$TENGU" status
expect_out "S4 uptime" "uptime:" "$TENGU" status
expect_exit "S4 exit 0" 0 "$TENGU" status

echo "== S5: fail-loud =="
expect_exit "S5 неизвестный тул → 2" 2 "$TENGU" nosuchtool
expect_out  "S5 подсказка тулов" "available tools:" "$TENGU" nosuchtool
expect_exit "S5 неизвестная команда → 2" 2 "$TENGU" status wrongcmd
expect_exit "S5 неизвестный флаг → 2" 2 "$TENGU" jira issues list --stat open
expect_out  "S5 инлайн списка флагов" "valid flags" "$TENGU" jira issues list --stat open
expect_exit "S5 без required → 2" 2 "$TENGU" jira issues create

echo "== S6: живой Jira (опционально) =="
if [ -n "${TENGU_E2E_JIRA_URL:-}" ] && [ -n "${TENGU_E2E_JIRA_PAT:-}" ] && [ -n "${TENGU_E2E_JIRA_PROJECT:-}" ]; then
    echo "jira-pat" | "$TENGU" jira auth login --token - >/dev/null
    expect_out  "S6 count-агрегат" "count:" "$TENGU" jira issues list --project "$TENGU_E2E_JIRA_PROJECT"
    expect_out  "S6 минимальная схема" "issues\[" "$TENGU" jira issues list --project "$TENGU_E2E_JIRA_PROJECT"
    expect_exit "S6 пустой список → 0" 0 "$TENGU" jira issues list --project "$TENGU_E2E_JIRA_PROJECT" --state closed
else
    echo "skip S6 (нет TENGU_E2E_JIRA_URL/PAT/PROJECT) — шейпы покрыты юнит-тестами плагина"
fi

echo "== S7: auth =="
expect_out  "S7 no PAT (definitive)" "no PAT configured" "$TENGU" jira auth status
expect_exit "S7 status exit 0" 0 "$TENGU" jira auth status
expect_exit "S7 logout #1" 0 "$TENGU" jira auth logout
expect_exit "S7 logout #2 (no-op)" 0 "$TENGU" jira auth logout

echo "== S8: stale manifest =="
sed -i 's/"manifestVersion":[0-9]*/"manifestVersion":1/' "$APPDATA/tengu/manifest.json"
expect_exit "S8 авто-refresh + retry" 0 "$TENGU" status
# ожидаемая версия — от самого сервера, чтобы тест не устаревал при бампе манифеста
SERVER_MV=$(curl -sf -m 2 http://localhost:8080/v1/health | sed 's/.*"manifestVersion":\([0-9]*\).*/\1/')
if grep -q "\"manifestVersion\":$SERVER_MV" "$APPDATA/tengu/manifest.json"; then ok "S8 кэш восстановлен"; else fail "S8 кэш восстановлен"; fi

echo
echo "итог: $PASS ok, $FAIL fail"
[ "$FAIL" = 0 ]
