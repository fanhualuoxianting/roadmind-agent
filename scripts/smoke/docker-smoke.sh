#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root_dir"

if ! docker info >/dev/null 2>&1; then
    echo 'Docker daemon is unavailable; start Docker Desktop and rerun this smoke.' >&2
    exit 2
fi

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

compose_env="${COMPOSE_ENV_FILE:-.env.example}"
project_name="${ROADMIND_SMOKE_PROJECT:-roadmind-smoke}"
export ROADMIND_NETWORK_NAME="${ROADMIND_NETWORK_NAME:-${project_name}-network}"
export ROADMIND_MYSQL_VOLUME_NAME="${ROADMIND_MYSQL_VOLUME_NAME:-${project_name}-mysql-data}"
export ROADMIND_REDIS_VOLUME_NAME="${ROADMIND_REDIS_VOLUME_NAME:-${project_name}-redis-data}"
compose=(docker compose --env-file "$compose_env" --profile full -p "$project_name")

if [[ -z "${ROADMIND_INTERNAL_TOKEN:-}" || "$ROADMIND_INTERNAL_TOKEN" == "disabled" ]]; then
    smoke_token="$("${SMOKE_PYTHON[@]}" -c 'import secrets; print(secrets.token_urlsafe(24))')"
    export ROADMIND_INTERNAL_TOKEN="$smoke_token"
    export ROADMIND_MCP_TOKEN="$smoke_token"
else
    export ROADMIND_MCP_TOKEN="${ROADMIND_MCP_TOKEN:-$ROADMIND_INTERNAL_TOKEN}"
fi

"${compose[@]}" config >/dev/null
"${compose[@]}" build vehicle-simulator roadmind-server roadmind-mcp-server
"${compose[@]}" up -d

wait_for_health() {
    local service="$1"
    local container health
    for _ in $(seq 1 "${SMOKE_CONTAINER_WAIT_ATTEMPTS:-60}"); do
        container="$("${compose[@]}" ps -q "$service")"
        if [[ -n "$container" ]]; then
            health="$(docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
            if [[ "$health" == "healthy" ]]; then
                return 0
            fi
        fi
        sleep 5
    done
    echo "container did not become healthy: $service" >&2
    "${compose[@]}" ps
    return 1
}

wait_for_health vehicle-simulator
wait_for_health roadmind-server
wait_for_health roadmind-mcp-server

ROADMIND_SERVER_BASE_URL="${ROADMIND_SERVER_BASE_URL:-http://127.0.0.1:8080}" \
ROADMIND_SIMULATOR_BASE_URL="${ROADMIND_SIMULATOR_BASE_URL:-http://127.0.0.1:8081}" \
    bash "$root_dir/scripts/smoke/http-smoke.sh"
ROADMIND_SERVER_BASE_URL="${ROADMIND_SERVER_BASE_URL:-http://127.0.0.1:8080}" \
    bash "$root_dir/scripts/smoke/sse-smoke.sh"
ROADMIND_MCP_BASE_URL="${ROADMIND_MCP_BASE_URL:-http://127.0.0.1:8090}" \
    bash "$root_dir/scripts/smoke/mcp-smoke.sh"

echo "Docker smoke passed for compose project $project_name. Containers are left running for inspection; clean up with the documented compose down command."
