#!/usr/bin/env python3
"""Independent standard-library oracle for HTTP transcripts and real Logback bytes."""
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def request(base, path, token=None, body=None):
    headers = {"X-Request-ID": "incoming-id-marker", "X-User-ID": "forged-user-marker"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=None if body is None else body.encode(), headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=2)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return {"status": response.status, "request_id": response.headers.get("X-Request-ID"),
                "body": response.read().decode("utf-8")}


def exercise(base, destination):
    deadline = time.monotonic() + 45
    while True:
        try:
            health = request(base, "/v1/health", "token-a")
            require(health["status"] == 200, "health failed")
            break
        except (OSError, AssertionError):
            if time.monotonic() >= deadline:
                raise AssertionError("container listener did not become ready") from None
            time.sleep(0.1)
    calls = {"health": health}
    calls["manifest"] = request(base, "/v1/manifest?query-marker=hidden", "token-a")
    for user, token in (("alice", "token-a"), ("bob", "token-b")):
        calls[user] = request(base, "/v1/invoke", token, '{"tool":"status","commandPath":["status"]}')
    calls["malformed"] = request(base, "/v1/invoke", "token-a", "{parse-marker-secret")
    calls["unauthorized"] = request(base, "/v1/invoke", "invalid-bearer-marker", "body-marker-secret")
    calls["malformed_path"] = request(base, "/bad-path-marker%ZZ")
    pathlib.Path(destination).write_text(json.dumps(calls, ensure_ascii=False), encoding="utf-8")


def records_from_bytes(raw):
    require(raw.endswith(b"\n"), "missing final LF")
    require(b"\x1b" not in raw and b"\r" not in raw, "ANSI or raw CR")
    lines = raw[:-1].split(b"\n")
    require(all(lines), "blank log line")
    return [json.loads(line.decode("utf-8")) for line in lines]


def schema(record):
    require(isinstance(record, dict), "log must be an object")
    for key in ("timestamp", "level", "service_name", "service_version", "logger", "message"):
        require(isinstance(record.get(key), str), f"missing/string {key}: {record}")
    require(re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z", record["timestamp"]), "timestamp")
    require(record["level"] in ("DEBUG", "INFO", "WARN", "ERROR"), "level")
    require(record["service_name"] == "tengu-server", "service name")
    for key in ("trace_id", "span_id", "mdc", "context", "stack_trace"):
        require(key not in record, f"unexpected {key}")
    if record.get("event") == "http_request_completed":
        require(type(record.get("duration_ms")) is int and record["duration_ms"] >= 0, "duration")
        require(type(record.get("http_status")) is int, "status")
        require(record["http_route"] in ("/v1/health", "/v1/manifest", "/v1/invoke", "unmatched"), "route")
        require(re.fullmatch(r"[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}",
                             record["request_id"]), "UUID v4")


def check_body(event, body):
    raw = body.encode("utf-8")
    expected = raw[:16384].decode("utf-8", errors="ignore")
    require(event["response_body"] == expected, "body does not match actual HTTP bytes/prefix")
    require(type(event["response_body_bytes"]) is int and event["response_body_bytes"] == len(raw), "body size")
    require(type(event["response_body_truncated"]) is bool, "truncated must be boolean")
    require(event["response_body_truncated"] == (len(raw) > 16384), "truncated flag")
    require(len(event["response_body"].encode("utf-8")) <= 16384, "body exceeds limit")
    require("\ufffd" not in event["response_body"], "replacement character")


def verify(directory, mode):
    directory = pathlib.Path(directory)
    raw = (directory / "stdout.jsonl").read_bytes()
    records = records_from_bytes(raw)
    for record in records:
        schema(record)
    require(not (directory / "stderr.txt").read_bytes(), "application stderr is not empty")
    state = json.loads((directory / "inspect.json").read_text())[0]
    require(state["State"]["ExitCode"] in (0, 143), f"unexpected container exit: {state['State']}")
    require(not state["Config"]["Tty"], "must run without TTY")
    require(state["Config"]["Entrypoint"] == ["bin/server"], "real ENTRYPOINT was changed")
    lifecycle = [r["event"] for r in records if r.get("event", "").startswith("server_")]
    require(lifecycle == ["server_starting", "server_ready", "server_stopping", "server_stopped"], str(lifecycle))
    require(records[-1].get("event") == "server_stopped", "event after shutdown")
    require(sum(r.get("event") == "dev_secrets_enabled" for r in records) == 1, "dev warning")
    http = json.loads((directory / "http.json").read_text())
    ids = [r["request_id"] for r in http.values()]
    require(len(set(ids)) == len(ids), "request IDs repeated")
    expected = {"health": (200, "DEBUG", "ok", None, None), "manifest": (200, "INFO", "ok", "alice", None),
                "alice": (200, "INFO", "ok", "alice", None), "bob": (200, "INFO", "ok", "bob", None),
                "malformed": (400, "WARN", "error", "alice", "USAGE"),
                "unauthorized": (401, "WARN", "error", None, "AUTH"),
                "malformed_path": (400, "WARN", "error", None, None)}
    requests = [r for r in records if r.get("event") == "http_request_completed"]
    for name, (status, level, outcome, user, kind) in expected.items():
        response = http[name]
        require(response["status"] == status, f"HTTP {name}")
        if name == "malformed_path":
            require(response["body"] == "Url decode failed for /bad-path-marker%ZZ", "HTTP error body changed")
        matches = [r for r in requests if r["request_id"] == response["request_id"]]
        if name == "health" and mode == "default":
            require(not matches, "INFO logs successful health")
            continue
        require(len(matches) == 1, f"expected one completion for {name}: {matches}")
        event = matches[0]
        require((event["http_status"], event["level"], event["outcome"], event.get("user_id"), event.get("error_kind"))
                == (status, level, outcome, user, kind), f"wrong context for {name}")
        require(event["service_version"] == json.loads(http["health"]["body"])["serverVersion"], "version drift")
        if name in ("alice", "bob"):
            require(event["tool"] == "status" and event["command"] == ["status"], "descriptor metadata")
        if mode == "debug" and name in ("alice", "bob"):
            check_body(event, response["body"])
        else:
            require(not any(key.startswith("response_body") for key in event), f"excluded body {name}")
    for marker in (b"parse-marker-secret", b"body-marker-secret", b"invalid-bearer-marker", b"incoming-id-marker",
                   b"forged-user-marker", b"query-marker", b"bad-path-marker"):
        require(marker not in raw, f"input leaked: {marker!r}")
    require(not (directory / "secret-files.txt").read_text(), "unexpected file in temporary secrets directory")
    changes = (directory / "files.txt").read_text().splitlines()
    # JVM perfdata and the tmpfs mountpoint are expected; no log files, regardless of extension.
    require("/tmp/tengu-secrets" in state["HostConfig"]["Tmpfs"], "missing isolated tmpfs")
    require(all(re.fullmatch(r"[AC] /tmp(?:/(?:hsperfdata_tengu(?:/\d+)?|tengu-secrets))?", line) for line in changes),
            f"unexpected writable-layer files: {changes}")
    print(f"PASS Docker {mode}: JSON/UTF-8, HTTP correlation, privacy, SIGTERM, no logging files")


def unicode_fixtures(path):
    rows = records_from_bytes(pathlib.Path(path).read_bytes())
    sizes = set()
    for row in rows:
        record = json.loads(row["encoded_event"])
        schema(record)
        check_body(record, row["http_body"])
        sizes.add(len(row["http_body"].encode("utf-8")))
    require({16383, 16384, 16385}.issubset(sizes), "missing exact UTF-8 boundary cases")
    require(any("😀" in r["http_body"] for r in rows), "missing multibyte boundary")
    print("PASS real encoder Unicode fixtures: 16383/16384/16385 bytes and emoji boundary")


if __name__ == "__main__":
    try:
        {"exercise": exercise, "verify": verify, "unicode": unicode_fixtures}[sys.argv[1]](*sys.argv[2:])
    except (AssertionError, OSError, ValueError, KeyError) as error:
        print(f"FAIL logging: {error}", file=sys.stderr)
        sys.exit(1)
