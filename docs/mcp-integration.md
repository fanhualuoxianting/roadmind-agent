# RoadMind MCP 集成说明

## 启动

先启动主 Server，再启动独立 MCP 进程：

```powershell
$env:ROADMIND_SERVER_BASE_URL = "http://127.0.0.1:8080"
$env:ROADMIND_MCP_TOKEN = "replace-with-the-same-local-token-as-server"
.\mvnw.cmd -pl roadmind-mcp-server spring-boot:run
```

主 Server 使用 `ROADMIND_INTERNAL_TOKEN` 接收桥接请求；两个变量应设置为同一个本机随机值。不要把令牌写入仓库或提交到 Git。

## MCP 客户端约束

- 端点：`POST /mcp`；
- Header：`X-RoadMind-MCP-Token`；
- `Accept` 同时包含 `application/json` 和 `text/event-stream`；
- 先发送 JSON-RPC `initialize`，再发送 `tools/list` 或 `tools/call`；
- 当前只读工具：`vehicle.get_status`、`weather.get_forecast`、`route.plan`；
- 单个文本参数最多 128 字符，MCP 请求体最多 64 KiB；
- 未知工具、非法参数、缺少令牌和主 Server 拒绝都会返回协议错误或受控工具错误。

示例初始化请求：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"roadmind-client","version":"1.0"}}}
```

MCP 只负责协议和认证适配；真正的工具描述、风险级别、参数校验、超时、依赖错误和 trace 仍由主 Server 的 ToolRuntime 负责。MCP 进程停止不会影响主 Web/Server。
