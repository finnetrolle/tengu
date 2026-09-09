#!/usr/bin/env bash
# Real Docker ENTRYPOINT, no TTY; server streams are captured separately from build output.
set -euo pipefail
cd "$(dirname "$0")/.."

tmp="$(mktemp -d)"
suffix="$(basename "$tmp" | tr '[:upper:]' '[:lower:]')"
image="tengu-logging-test:$suffix"
containers=()
cleanup() {
    local code="$1"
    trap - EXIT
    for container in "${containers[@]:-}"; do
        [ -z "$container" ] || docker rm -f "$container" >/dev/null 2>&1 || true
    done
    docker image rm "$image" >/dev/null 2>&1 || true
    if [ "$code" -eq 0 ]; then
        rm -rf "$tmp"
    else
        echo "Logging evidence retained at $tmp" >&2
    fi
    exit "$code"
}
trap 'cleanup "$?"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Produces real encoded Unicode boundary fixtures; up-to-date after the full build.
./gradlew :server:test --console=plain >"$tmp/tests.log" 2>&1 || { cat "$tmp/tests.log"; exit 1; }
python3 scripts/assert-logging.py unicode server/build/logging-fixtures/body-boundaries.jsonl
docker build -t "$image" . >"$tmp/build.log" 2>&1 || { cat "$tmp/build.log"; exit 1; }

for mode in default debug; do
    directory="$tmp/$mode"
    mkdir -p "$directory"
    container="tengu-logging-$suffix-$mode"
    containers+=("$container")
    settings=(-e TENGU_DEV_SECRETS=1)
    if [ "$mode" = debug ]; then settings+=(-e TENGU_LOG_LEVEL=DEBUG -e TENGU_LOG_RESPONSE_BODY=1); fi
    docker run -d --name "$container" -p 127.0.0.1::8080 \
        --tmpfs /tmp/tengu-secrets:rw,mode=1777 \
        -e TENGU_HUB_TOKENS=alice=token-a,bob=token-b \
        -e TENGU_DEV_SECRETS_DIR=/tmp/tengu-secrets \
        "${settings[@]}" "$image" >"$directory/container-id"
    port="$(docker inspect --format '{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}' "$container")"
    python3 scripts/assert-logging.py exercise "http://127.0.0.1:$port" "$directory/http.json"
    docker exec "$container" find /tmp/tengu-secrets -type f >"$directory/secret-files.txt"
    docker stop --time 10 "$container" >"$directory/stop.txt"
    docker logs "$container" >"$directory/stdout.jsonl" 2>"$directory/stderr.txt"
    docker inspect "$container" >"$directory/inspect.json"
    docker diff "$container" >"$directory/files.txt"
    python3 scripts/assert-logging.py verify "$directory" "$mode"
done
echo "Logging Docker checks passed (default INFO/0 and DEBUG/1)."
