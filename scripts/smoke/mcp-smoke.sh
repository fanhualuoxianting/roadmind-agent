#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$script_dir/lib.sh"
smoke_require curl

smoke_stage 'MCP token preflight'
if [[ -z "${ROADMIND_MCP_TOKEN:-}" || "$ROADMIND_MCP_TOKEN" == "disabled" ]]; then
    smoke_report_failure 'POST' "$SMOKE_MCP_URL/mcp" 'configured token' 'missing token' ''
    exit 2
fi

mcp_request() {
    local request_body="$1"
    local raw status body curl_exit=0
    raw="$(curl --silent --show-error --location \
        --write-out $'\n%{http_code}' \
        -H "X-RoadMind-MCP-Token: $ROADMIND_MCP_TOKEN" \
        -H 'Content-Type: application/json' \
        -H 'Accept: application/json, text/event-stream' \
        -X POST "$SMOKE_MCP_URL/mcp" \
        --data "$request_body")" || curl_exit=$?
    if [[ "$curl_exit" != "0" ]]; then
        smoke_report_failure 'POST' "$SMOKE_MCP_URL/mcp" '200' "curl-exit-$curl_exit" "$raw"
        exit 1
    fi
    status="${raw##*$'\n'}"
    body="${raw%$'\n'*}"
    if [[ "$status" != "200" ]]; then
        smoke_report_failure 'POST' "$SMOKE_MCP_URL/mcp" '200' "$status" "$body"
        exit 1
    fi
    printf '%s' "$body"
}

validate_mcp() {
    local body="$1"
    local expression="$2"
    local label="$3"
    "${SMOKE_PYTHON[@]}" -c '
import json
import sys

raw = sys.stdin.read()
documents = []
try:
    parsed = json.loads(raw.strip())
    if isinstance(parsed, dict):
        documents.append(parsed)
except json.JSONDecodeError:
    pass
for line in raw.splitlines():
    candidate = line[5:].strip() if line.startswith("data:") else line.strip()
    if not candidate or candidate == "[DONE]":
        continue
    try:
        parsed = json.loads(candidate)
    except json.JSONDecodeError:
        continue
    if isinstance(parsed, dict):
        documents.append(parsed)
if not documents:
    raise SystemExit(f"MCP response was not JSON: {sys.argv[2]}")
document = documents[-1]
if not eval(sys.argv[1], {"__builtins__": {}}, {"document": document, "set": set}):
    raise SystemExit(f"MCP assertion failed: {sys.argv[2]}")
' "$expression" "$label" <<<"$body"
}

smoke_stage 'MCP initialize'
initialize="$(mcp_request '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"roadmind-smoke","version":"1.0"}}}')"
if ! validate_mcp "$initialize" 'document.get("result", {}).get("serverInfo", {}).get("name") == "roadmind-controlled-tools"' 'initialize server info'; then
    smoke_report_failure 'POST' "$SMOKE_MCP_URL/mcp" 'valid initialize response' 'invalid-response' "$initialize"
    exit 1
fi

smoke_stage 'MCP tools list'
tools="$(mcp_request '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}')"
if ! validate_mcp "$tools" 'set(tool.get("name") for tool in document.get("result", {}).get("tools", [])) >= {"vehicle.get_status", "weather.get_forecast", "route.plan"}' 'read-only tool catalog'; then
    smoke_report_failure 'POST' "$SMOKE_MCP_URL/mcp" 'valid tools/list response' 'invalid-response' "$tools"
    exit 1
fi

smoke_stage 'MCP safe vehicle tool call'
call="$(mcp_request '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"vehicle.get_status","arguments":{"vehicleId":"198000000000000401"}}}')"
if ! validate_mcp "$call" '"error" not in document and document.get("result", {}).get("isError") is not True' 'safe vehicle tool call'; then
    smoke_report_failure 'POST' "$SMOKE_MCP_URL/mcp" 'valid vehicle.get_status response' 'invalid-response' "$call"
    exit 1
fi

echo 'MCP smoke passed: initialize, tools/list and vehicle.get_status.'
