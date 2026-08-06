#!/usr/bin/env bash
set -euo pipefail

SMOKE_SERVER_URL="${ROADMIND_SERVER_BASE_URL:-http://127.0.0.1:8080}"
SMOKE_SIMULATOR_URL="${ROADMIND_SIMULATOR_BASE_URL:-http://127.0.0.1:8081}"
SMOKE_MCP_URL="${ROADMIND_MCP_BASE_URL:-http://127.0.0.1:8090}"
SMOKE_STAGE="${SMOKE_STAGE:-unknown}"
SMOKE_COOKIE_JAR="${SMOKE_COOKIE_JAR:-$(mktemp)}"
SMOKE_LAST_METHOD=""
SMOKE_LAST_URL=""
SMOKE_LAST_STATUS=""
SMOKE_LAST_BODY=""

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

smoke_stage() {
    SMOKE_STAGE="$1"
}

smoke_safe_error_details() {
    local body="$1"
    printf '%s' "$body" | "${SMOKE_PYTHON[@]}" -c '
import json
import re
import sys

try:
    document = json.loads(sys.stdin.read())
except (json.JSONDecodeError, TypeError):
    print("response_code/message=unavailable (non-JSON body omitted)")
    raise SystemExit

found = {}
code_keys = {"code", "errorcode", "error_code"}
message_keys = {"message", "errormessage", "error_message", "error"}

def walk(value):
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = str(key).lower()
            if normalized in code_keys and isinstance(child, (str, int, float, bool)):
                found.setdefault("code", child)
            if normalized in message_keys and isinstance(child, (str, int, float, bool)):
                found.setdefault("message", child)
            walk(child)
    elif isinstance(value, list):
        for child in value:
            walk(child)

def safe(value):
    text = str(value).replace("\r", " ").replace("\n", " ")
    text = re.sub(
        r"(?i)\b(authorization|cookie|csrf|token|password|secret)\b\s*[:=]\s*\S+",
        r"\1=[REDACTED]",
        text,
    )
    return text[:200]

walk(document)
parts = []
if "code" in found:
    parts.append("response_code=" + safe(found["code"]))
if "message" in found:
    parts.append("response_message=" + safe(found["message"]))
print(" ".join(parts) if parts else "response_code/message=unavailable (body omitted)")
'
}

smoke_report_failure() {
    local method="$1"
    local url="$2"
    local expected_status="$3"
    local actual_status="$4"
    local body="$5"
    local safe_url="${url%%\?*}"
    echo 'Smoke request failed' >&2
    echo "stage=${SMOKE_STAGE:-unknown}" >&2
    echo "method=$method" >&2
    echo "url=$safe_url" >&2
    echo "expected_status=$expected_status" >&2
    echo "actual_status=$actual_status" >&2
    echo "$(smoke_safe_error_details "$body")" >&2
}

smoke_request() {
    local expected_status="$1"
    shift
    local raw status body curl_exit=0
    local method='GET' url='' pending_method=0 argument
    for argument in "$@"; do
        case "$argument" in
            -X|--request)
                pending_method=1
                ;;
            --request=*)
                method="${argument#*=}"
                ;;
            http://*|https://*)
                url="$argument"
                ;;
            *)
                if [[ "$pending_method" == "1" ]]; then
                    method="$argument"
                    pending_method=0
                fi
                ;;
        esac
    done
    raw="$(curl --silent --show-error --location \
        --cookie-jar "$SMOKE_COOKIE_JAR" --cookie "$SMOKE_COOKIE_JAR" \
        --write-out $'\n%{http_code}' "$@")" || curl_exit=$?
    if [[ "$curl_exit" != "0" ]]; then
        SMOKE_LAST_METHOD="$method"
        SMOKE_LAST_URL="$url"
        SMOKE_LAST_STATUS="curl-exit-$curl_exit"
        SMOKE_LAST_BODY="$raw"
        smoke_report_failure "$method" "$url" "$expected_status" "curl-exit-$curl_exit" "$raw"
        exit 1
    fi
    status="${raw##*$'\n'}"
    body="${raw%$'\n'*}"
    SMOKE_LAST_METHOD="$method"
    SMOKE_LAST_URL="$url"
    SMOKE_LAST_STATUS="$status"
    SMOKE_LAST_BODY="$body"
    if [[ "$status" != "$expected_status" ]]; then
        smoke_report_failure "$method" "$url" "$expected_status" "$status" "$body"
        exit 1
    fi
    printf '%s' "$body"
}

smoke_json_value() {
    local body="$1"
    local path="$2"
    local value
    if ! value="$("${SMOKE_PYTHON[@]}" -c '
import json
import sys

value = json.loads(sys.stdin.read())
for part in sys.argv[1].split("."):
    value = value[int(part)] if isinstance(value, list) else value[part]
if isinstance(value, bool):
    print(str(value).lower())
else:
    print(value)
' "$path" <<<"$body")"; then
        smoke_report_failure "${SMOKE_LAST_METHOD:-unknown}" "${SMOKE_LAST_URL:-unknown}" "valid JSON field $path" "${SMOKE_LAST_STATUS:-unknown}" "$body"
        exit 1
    fi
    printf '%s\n' "$value"
}

smoke_json_assert() {
    local body="$1"
    local expression="$2"
    local label="$3"
    shift 3
    if ! "${SMOKE_PYTHON[@]}" -c '
import json
import sys

document = json.loads(sys.stdin.read())
if not eval(sys.argv[1], {"__builtins__": {}}, {"document": document, "sys": sys, "bool": bool}):
    raise SystemExit(f"JSON assertion failed: {sys.argv[2]}")
' "$expression" "$label" "$@" <<<"$body"; then
        smoke_report_failure "${SMOKE_LAST_METHOD:-unknown}" "${SMOKE_LAST_URL:-unknown}" "JSON assertion: $label" "${SMOKE_LAST_STATUS:-unknown}" "$body"
        exit 1
    fi
}
