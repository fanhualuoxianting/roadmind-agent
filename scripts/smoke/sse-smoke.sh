#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$script_dir/lib.sh"
smoke_require curl

csrf_response="$(smoke_request 200 "$SMOKE_SERVER_URL/api/v1/security/csrf")"
csrf_token="$(smoke_json_value "$csrf_response" 'data.token')"
conversation="$(smoke_request 201 \
    -X POST "$SMOKE_SERVER_URL/api/v1/conversations" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $csrf_token" \
    -H "Idempotency-Key: roadmind-sse-conversation-$(date +%s)" \
    --data '{"title":"RoadMind SSE smoke","timezone":"Asia/Shanghai"}')"
conversation_id="$(smoke_json_value "$conversation" 'data.conversationId')"
accepted="$(smoke_request 202 \
    -X POST "$SMOKE_SERVER_URL/api/v1/conversations/$conversation_id/agent-requests" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $csrf_token" \
    -H "Idempotency-Key: roadmind-sse-agent-$(date +%s)" \
    --data '{"message":"查询车辆状态","clientContext":{"timezone":"Asia/Shanghai"}}')"
task_id="$(smoke_json_value "$accepted" 'data.taskId')"

raw=""
curl_exit=0
raw="$(curl --silent --show-error --location --no-buffer --max-time "${SMOKE_SSE_TIMEOUT:-30}" \
    --cookie-jar "$SMOKE_COOKIE_JAR" --cookie "$SMOKE_COOKIE_JAR" \
    --write-out $'\n%{http_code}' \
    -H 'Accept: text/event-stream' \
    "$SMOKE_SERVER_URL/api/v1/agent-tasks/$task_id/events" || curl_exit=$?)"
status="${raw##*$'\n'}"
body="${raw%$'\n'*}"
if [[ "$status" != "200" ]]; then
    echo "SSE endpoint returned HTTP $status" >&2
    exit 1
fi

"${SMOKE_PYTHON[@]}" -c '
import sys
body = sys.stdin.read()
if "stream.complete" not in body or "agent.response.ready" not in body:
    raise SystemExit("SSE stream did not contain stream.complete and agent.response.ready")
' <<<"$body"

# curl exit 28 is expected if the server keeps the connection open after the terminal event.
if [[ "$curl_exit" != "0" && "$curl_exit" != "28" ]]; then
    echo "SSE curl failed with exit code $curl_exit" >&2
    exit "$curl_exit"
fi

echo "SSE smoke passed: task $task_id emitted agent.response.ready and stream.complete."
