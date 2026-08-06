#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "$script_dir/lib.sh"
smoke_require curl

health="$(smoke_request 200 "$SMOKE_SERVER_URL/actuator/health")"
smoke_json_assert "$health" 'document.get("status") == "UP"' 'server health is UP'

capabilities="$(smoke_request 200 "$SMOKE_SERVER_URL/api/v1/settings/capabilities")"
smoke_json_assert "$capabilities" 'document["code"] == "OK" and document["data"]["agentMode"] == "RULE_STUB" and document["data"]["writesEnabled"] is False and document["data"]["toolCount"] >= 3' 'safe default capabilities'

simulator_health="$(smoke_request 200 "$SMOKE_SIMULATOR_URL/actuator/health")"
smoke_json_assert "$simulator_health" 'document.get("status") == "UP"' 'simulator health is UP'

simulator_state="$(smoke_request 200 "$SMOKE_SIMULATOR_URL/internal/v1/vehicles/demo-vehicle-001/state")"
smoke_json_assert "$simulator_state" 'document["code"] == "OK" and document["data"]["vehicleId"] == "demo-vehicle-001"' 'digital twin state endpoint'

csrf_response="$(smoke_request 200 "$SMOKE_SERVER_URL/api/v1/security/csrf")"
csrf_token="$(smoke_json_value "$csrf_response" 'data.token')"

conversation="$(smoke_request 201 \
    -X POST "$SMOKE_SERVER_URL/api/v1/conversations" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $csrf_token" \
    -H "Idempotency-Key: roadmind-smoke-conversation-$(date +%s)" \
    --data '{"title":"RoadMind smoke","timezone":"Asia/Shanghai"}')"
smoke_json_assert "$conversation" 'document["code"] == "OK" and bool(document["data"]["conversationId"])' 'conversation creation'
conversation_id="$(smoke_json_value "$conversation" 'data.conversationId')"

accepted="$(smoke_request 202 \
    -X POST "$SMOKE_SERVER_URL/api/v1/conversations/$conversation_id/agent-requests" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $csrf_token" \
    -H "Idempotency-Key: roadmind-smoke-agent-$(date +%s)" \
    --data '{"message":"查询车辆状态","clientContext":{"timezone":"Asia/Shanghai"}}')"
smoke_json_assert "$accepted" 'document["code"] == "ACCEPTED" and bool(document["data"]["taskId"])' 'agent task acceptance'
task_id="$(smoke_json_value "$accepted" 'data.taskId')"

terminal_status=""
for _ in $(seq 1 "${SMOKE_TASK_POLL_ATTEMPTS:-30}"); do
    task="$(smoke_request 200 "$SMOKE_SERVER_URL/api/v1/agent-tasks/$task_id")"
    terminal_status="$(smoke_json_value "$task" 'data.status')"
    if [[ "$terminal_status" == "SUCCEEDED" || "$terminal_status" == "FAILED" || "$terminal_status" == "MODEL_UNAVAILABLE" || "$terminal_status" == "INVALID_MODEL_OUTPUT" ]]; then
        smoke_json_assert "$task" 'bool(document["data"]["taskId"])' 'agent task lookup'
        break
    fi
    sleep 1
done

if [[ "$terminal_status" != "SUCCEEDED" ]]; then
    echo "agent task did not succeed; final status: $terminal_status" >&2
    exit 1
fi

echo "HTTP smoke passed: capabilities, simulator state, conversation, agent acceptance and terminal task lookup."
