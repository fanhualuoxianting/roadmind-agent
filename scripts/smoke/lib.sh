#!/usr/bin/env bash
set -euo pipefail

SMOKE_SERVER_URL="${ROADMIND_SERVER_BASE_URL:-http://127.0.0.1:8080}"
SMOKE_SIMULATOR_URL="${ROADMIND_SIMULATOR_BASE_URL:-http://127.0.0.1:8081}"
SMOKE_MCP_URL="${ROADMIND_MCP_BASE_URL:-http://127.0.0.1:8090}"
SMOKE_COOKIE_JAR="${SMOKE_COOKIE_JAR:-$(mktemp)}"

if command -v python3 >/dev/null 2>&1; then
    SMOKE_PYTHON=(python3)
elif command -v python >/dev/null 2>&1; then
    SMOKE_PYTHON=(python)
elif command -v py >/dev/null 2>&1; then
    SMOKE_PYTHON=(py -3)
else
    echo 'missing Python 3 command: install python3, python, or the Windows py launcher' >&2
    exit 2
fi

if [[ "${SMOKE_COOKIE_JAR_OWNED:-1}" == "1" ]]; then
    trap 'rm -f "$SMOKE_COOKIE_JAR"' EXIT
fi

smoke_require() {
    local command_name
    for command_name in "$@"; do
        if ! command -v "$command_name" >/dev/null 2>&1; then
            echo "missing required command: $command_name" >&2
            exit 2
        fi
    done
}

smoke_request() {
    local expected_status="$1"
    shift
    local raw status body
    raw="$(curl --silent --show-error --location \
        --cookie-jar "$SMOKE_COOKIE_JAR" --cookie "$SMOKE_COOKIE_JAR" \
        --write-out $'\n%{http_code}' "$@")"
    status="${raw##*$'\n'}"
    body="${raw%$'\n'*}"
    if [[ "$status" != "$expected_status" ]]; then
        echo "HTTP smoke request expected $expected_status but received $status" >&2
        exit 1
    fi
    printf '%s' "$body"
}

smoke_json_value() {
    local body="$1"
    local path="$2"
    "${SMOKE_PYTHON[@]}" -c '
import json
import sys

value = json.loads(sys.stdin.read())
for part in sys.argv[1].split("."):
    value = value[int(part)] if isinstance(value, list) else value[part]
if isinstance(value, bool):
    print(str(value).lower())
else:
    print(value)
' "$path" <<<"$body"
}

smoke_json_assert() {
    local body="$1"
    local expression="$2"
    local label="$3"
    shift 3
    "${SMOKE_PYTHON[@]}" -c '
import json
import sys

document = json.loads(sys.stdin.read())
if not eval(sys.argv[1], {"__builtins__": {}}, {"document": document, "sys": sys, "bool": bool}):
    raise SystemExit(f"JSON assertion failed: {sys.argv[2]}")
' "$expression" "$label" "$@" <<<"$body"
}
