#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$script_dir/lib.sh"
smoke_require curl

smoke_stage 'SSE CSRF token'
csrf_response="$(smoke_request 200 "$SMOKE_SERVER_URL/api/v1/security/csrf")"
csrf_token="$(smoke_json_value "$csrf_response" 'data.token')"
smoke_stage 'SSE conversation creation'
conversation="$(smoke_request 201 \
    -X POST "$SMOKE_SERVER_URL/api/v1/conversations" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $csrf_token" \
    -H "Idempotency-Key: roadmind-sse-conversation-$(date +%s)" \
    --data '{"title":"RoadMind SSE smoke","timezone":"Asia/Shanghai"}')"
conversation_id="$(smoke_json_value "$conversation" 'data.conversationId')"
smoke_stage 'SSE agent request acceptance'
accepted="$(smoke_request 202 \
    -X POST "$SMOKE_SERVER_URL/api/v1/conversations/$conversation_id/agent-requests" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $csrf_token" \
    -H "Idempotency-Key: roadmind-sse-agent-$(date +%s)" \
    --data '{"message":"查询车辆状态","clientContext":{"timezone":"Asia/Shanghai"}}')"
task_id="$(smoke_json_value "$accepted" 'data.taskId')"

smoke_stage 'SSE event stream'
raw=""
curl_exit=0
raw="$(curl --silent --show-error --location --no-buffer --max-time "${SMOKE_SSE_TIMEOUT:-30}" \
    --cookie-jar "$SMOKE_COOKIE_JAR" --cookie "$SMOKE_COOKIE_JAR" \
    --write-out $'\n%{http_code}' \
    -H 'Accept: text/event-stream' \
    "$SMOKE_SERVER_URL/api/v1/agent-tasks/$task_id/events")" || curl_exit=$?
status="${raw##*$'\n'}"
body="${raw%$'\n'*}"
if [[ "$status" != "200" ]]; then
    smoke_report_failure 'GET' "$SMOKE_SERVER_URL/api/v1/agent-tasks/$task_id/events" '200' "$status" "$body"
    exit 1
fi

if ! "${SMOKE_PYTHON[@]}" -c '
import sys
body = sys.stdin.read()
if "stream.complete" not in body or "agent.response.ready" not in body:
    raise SystemExit("SSE stream did not contain stream.complete and agent.response.ready")
' <<<"$body"; then
    smoke_report_failure 'GET' "$SMOKE_SERVER_URL/api/v1/agent-tasks/$task_id/events" 'agent.response.ready + stream.complete' 'missing-event' ''
    exit 1
fi

# curl exit 28 is expected if the server keeps the connection open after the terminal event.
if [[ "$curl_exit" != "0" && "$curl_exit" != "28" ]]; then
    smoke_report_failure 'GET' "$SMOKE_SERVER_URL/api/v1/agent-tasks/$task_id/events" '200' "curl-exit-$curl_exit" "$body"
    exit "$curl_exit"
fi

echo "SSE smoke passed: task $task_id emitted agent.response.ready and stream.complete."
