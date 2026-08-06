# RoadMind Smoke Scripts

这些脚本只验证已经启动的服务；它们不会写入密钥，也不会自动启动或停止用户已有的应用进程。

## Git Bash / WSL

在仓库根目录执行：

```bash
bash scripts/smoke/http-smoke.sh
bash scripts/smoke/sse-smoke.sh
ROADMIND_MCP_TOKEN="$ROADMIND_INTERNAL_TOKEN" bash scripts/smoke/mcp-smoke.sh
```

`http-smoke.sh` 需要主服务 `:8080` 和车辆模拟器 `:8081`；`sse-smoke.sh` 需要主服务；`mcp-smoke.sh` 需要 MCP `:8090` 和主服务。可通过 `ROADMIND_SERVER_BASE_URL`、`ROADMIND_SIMULATOR_BASE_URL`、`ROADMIND_MCP_BASE_URL` 覆盖地址。

## Docker 全链路

Docker daemon 可用时，使用不会默认 `down` 的独立 Compose 项目：

```bash
bash scripts/smoke/docker-smoke.sh
```

脚本会临时生成 MCP 内部令牌，构建三个应用镜像，等待容器健康后执行 HTTP、SSE 和 MCP Smoke。令牌只存在于当前进程环境，不会写入文件或输出。

清理本次独立 Smoke 项目时，不删除卷：

```bash
ROADMIND_NETWORK_NAME=roadmind-smoke-network \
ROADMIND_MYSQL_VOLUME_NAME=roadmind-smoke-mysql-data \
ROADMIND_REDIS_VOLUME_NAME=roadmind-smoke-redis-data \
docker compose --env-file .env.example --profile full -p roadmind-smoke down
```

当前仓库没有可执行 PowerShell 的等价实现；Windows 用户可在 Git Bash 或 WSL 执行上述脚本。若使用 PowerShell，先启动 Git Bash/WSL，再从仓库根目录运行同一命令。
