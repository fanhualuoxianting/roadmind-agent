# RoadMind Agent REST API 与 SSE 草案

> 文档状态：阶段 0 审查通过（实现前草案）
> API 前缀：`/api/v1`
> 数据格式：JSON，UTF-8
> 时间格式：ISO 8601，带时区或 UTC `Z`

## 1. 通用约定

### 1.1 请求头

| 请求头 | 必需 | 说明 |
| --- | --- | --- |
| `Authorization: Bearer <token>` | 非浏览器客户端启用后必需 | 浏览器首版优先使用会话 Cookie |
| `X-Request-ID` | 可选 | 客户端请求 ID；服务端校验长度并回传 |
| `Idempotency-Key` | 写操作按接口要求 | 1～128 字符，随机且不含敏感数据 |
| `Last-Event-ID` | SSE 重连可选 | 最后成功应用的事件 ID |
| `X-XSRF-TOKEN` | Cookie 会话的写请求必需 | 值来自 CSRF 初始化接口/可读 CSRF Cookie，不是会话 Cookie |
| `Accept-Language` | 可选 | 首版默认 `zh-CN` |

服务端始终生成或继承 `traceId`，不得直接信任超长或非法客户端 ID。所有 `BIGINT` 主键在 JSON 中序列化为十进制字符串，避免 JavaScript 超过 `Number.MAX_SAFE_INTEGER` 后丢失精度。

### 1.2 统一成功响应

```json
{
  "code": "OK",
  "message": "success",
  "data": {},
  "traceId": "01K2ROAD...",
  "timestamp": "2026-08-03T12:00:00.123Z"
}
```

### 1.3 统一错误响应

```json
{
  "code": "CONFIRMATION_REQUIRED",
  "message": "该计划包含需要确认的模拟车辆与家居操作",
  "details": {
    "confirmationId": "198000000000000001",
    "expiresAt": "2026-08-03T12:10:00Z"
  },
  "traceId": "01K2ROAD...",
  "timestamp": "2026-08-03T12:05:00.123Z"
}
```

校验错误的 `details` 仅包含字段名、规则和安全的提示，不回显完整敏感输入。

列表响应统一返回 `items` 与不透明 `nextCursor`。客户端不得解析 cursor 内部结构；没有下一页时 `nextCursor` 为 `null`。

### 1.4 浏览器身份与 CSRF

```http
GET /api/v1/me
GET /api/v1/security/csrf
```

- 浏览器首版使用服务端会话 Cookie；会话 Cookie 为 HttpOnly、SameSite，HTTPS 部署时必须 Secure。
- `GET /api/v1/security/csrf` 只返回 CSRF Token 及其请求头名称，不返回会话内容。
- 所有 Cookie 身份下的 POST/PATCH/PUT/DELETE 请求必须校验 CSRF；Axios 开启 `withCredentials` 并发送 `X-XSRF-TOKEN`。
- 阶段 1 的固定 demo 身份只在 `demo` Profile 和本地/回环环境启用，非 demo Profile 不自动登录。
- 开发跨端口时只允许精确来源并允许凭据，禁止 `Access-Control-Allow-Origin: *` 与 Cookie 组合。

### 1.5 HTTP 状态使用

| HTTP | 场景 |
| --- | --- |
| 200 | 查询、幂等重放、正常业务结果 |
| 201 | 资源创建成功 |
| 202 | 已接受异步任务或 Agent 请求 |
| 204 | 无响应体的取消/关闭成功 |
| 400 | JSON、字段或状态参数错误 |
| 401 | 未认证 |
| 403 | 无权限或策略拒绝 |
| 404 | 资源不存在或不可见 |
| 409 | 状态、版本或幂等冲突 |
| 410 | 确认或短期资源已过期 |
| 422 | 语义可读但缺少上下文/计划非法 |
| 429 | 限流 |
| 502 | 外部依赖返回无效结果 |
| 503 | 模型、地图、模拟器等依赖不可用 |
| 504 | 外部调用超时 |

## 2. 会话接口

### 2.1 创建会话

```http
POST /api/v1/conversations
Idempotency-Key: <key>
```

请求：

```json
{
  "title": "南京到苏州出行",
  "timezone": "Asia/Shanghai"
}
```

响应 `201`：

```json
{
  "code": "OK",
  "message": "created",
  "data": {
    "conversationId": "198000000000000001",
    "status": "ACTIVE",
    "timezone": "Asia/Shanghai",
    "createdAt": "2026-08-03T12:00:00Z"
  },
  "traceId": "01K2...",
  "timestamp": "2026-08-03T12:00:00Z"
}
```

### 2.2 查询会话

```http
GET /api/v1/conversations/{conversationId}
```

返回会话元数据、当前任务摘要、待确认数量和最近消息；不默认返回完整内部上下文或 Prompt。

### 2.3 查询消息

```http
GET /api/v1/conversations/{conversationId}/messages?beforeId={id}&limit=30
```

`limit` 最大 100，使用游标而非页码避免新增消息导致漂移。

### 2.4 关闭会话

```http
POST /api/v1/conversations/{conversationId}/close
Idempotency-Key: <key>
```

关闭后不再接受新 Agent 请求，但保留审计和历史查询。

## 3. Agent 请求接口

### 3.1 提交用户消息

```http
POST /api/v1/conversations/{conversationId}/agent-requests
Idempotency-Key: <key>
```

请求：

```json
{
  "message": "明天早上 8 点从南京去苏州接朋友。车现在电量不多，帮我规划一下。天气冷的话提前开空调，出门后把家里的灯关掉。",
  "clientContext": {
    "timezone": "Asia/Shanghai"
  }
}
```

响应 `202`：

```json
{
  "code": "ACCEPTED",
  "message": "Agent 已开始处理",
  "data": {
    "taskId": "198000000000000101",
    "status": "PLANNING",
    "eventsUrl": "/api/v1/agent-tasks/198000000000000101/events"
  },
  "traceId": "01K2...",
  "timestamp": "2026-08-03T12:01:00Z"
}
```

同一个 `Idempotency-Key` 和相同消息返回原任务；同一个 Key 但消息摘要不同返回 `409 IDEMPOTENCY_CONFLICT`。

### 3.2 查询 Agent 任务

```http
GET /api/v1/agent-tasks/{taskId}
```

响应数据：

```json
{
  "taskId": "198000000000000101",
  "conversationId": "198000000000000001",
  "goal": "规划南京到苏州的接人行程",
  "status": "WAITING_CONFIRMATION",
  "planVersion": 2,
  "entities": {
    "origin": "南京",
    "destination": "苏州",
    "departureTime": "2026-08-04T08:00:00+08:00"
  },
  "progress": {
    "completedSteps": 5,
    "totalSteps": 8
  },
  "pendingConfirmationIds": ["198000000000000301"],
  "createdAt": "2026-08-03T12:01:00Z",
  "updatedAt": "2026-08-03T12:01:04Z"
}
```

### 3.3 查询当前计划

```http
GET /api/v1/agent-tasks/{taskId}/plan
```

返回服务端校验后的计划，不返回模型未校验草稿：

```json
{
  "taskId": "198000000000000101",
  "planVersion": 2,
  "goal": "规划南京到苏州的出行任务",
  "status": "WAITING_CONFIRMATION",
  "steps": [
    {
      "stepId": "198000000000000201",
      "stepKey": "step-001",
      "tool": "vehicle.get_status",
      "riskLevel": "READ_ONLY",
      "dependsOn": [],
      "status": "SUCCEEDED"
    },
    {
      "stepId": "198000000000000204",
      "stepKey": "step-004",
      "tool": "vehicle.schedule_climate",
      "riskLevel": "HIGH_RISK_WRITE",
      "dependsOn": ["step-002"],
      "status": "WAITING_CONFIRMATION"
    }
  ]
}
```

### 3.4 取消 Agent 任务

```http
POST /api/v1/agent-tasks/{taskId}/cancel
Idempotency-Key: <key>
```

只取消尚未执行的步骤和可取消的定时任务。已成功执行的写操作不会自动反向执行；响应明确列出无法撤销的结果。

## 4. Agent SSE

### 4.1 建立连接

```http
GET /api/v1/agent-tasks/{taskId}/events
Accept: text/event-stream
Last-Event-ID: {taskId}:{sequence}
```

原生浏览器 `EventSource` 不方便设置自定义 Authorization Header。首版二选一：

1. 同源 HttpOnly Cookie 会话，推荐用于浏览器演示；或
2. 通过短时、一次范围受限的 SSE ticket 放在查询参数，ticket 不写日志且 60 秒内建立连接。

不得把长期 Bearer Token 直接放 URL。

浏览器原生 `EventSource` 在同一次连接自动重连时会发送 `Last-Event-ID`，但页面刷新后不能由 JavaScript 自定义该请求头。首版刷新恢复采用“先查询任务快照，再建立新流”；若阶段 4 需要从本地保存序号精确续传，可增加校验后的 `afterSequence` 查询参数，不能把它直接拼入 Redis Key。

### 4.2 通用事件信封

```text
id: 198000000000000101:42
event: agent.step.updated
data: {"schemaVersion":1,"eventId":"198000000000000101:42","sequence":42,"type":"agent.step.updated","traceId":"01K2...","taskId":"198000000000000101","occurredAt":"2026-08-03T12:01:04.123Z","data":{"stepKey":"step-004","status":"WAITING_CONFIRMATION"}}
```

JSON 结构：

```json
{
  "schemaVersion": 1,
  "eventId": "198000000000000101:42",
  "sequence": 42,
  "type": "agent.step.updated",
  "traceId": "01K2...",
  "taskId": "198000000000000101",
  "occurredAt": "2026-08-03T12:01:04.123Z",
  "data": {}
}
```

### 4.3 Agent 事件类型

| 事件 | 关键数据 |
| --- | --- |
| `agent.task.updated` | task status、progress |
| `agent.context.updated` | 变更后的安全实体摘要、修正字段 |
| `agent.plan.created` | planVersion、stepCount |
| `agent.step.updated` | stepKey、tool、status |
| `tool.call.started` | executionId、tool、attempt |
| `tool.call.completed` | executionId、status、durationMs |
| `policy.confirmation.required` | confirmationId、actions、expiresAt |
| `policy.denied` | stepKey、reasonCode |
| `verifier.completed` | stepKey、verification status |
| `agent.response.ready` | messageId、task final status |
| `stream.snapshot` | 当前完整安全快照，恢复超窗时使用 |
| `stream.error` | error code、recoverable |
| `stream.complete` | final status、lastSequence |

### 4.4 心跳与重连

心跳可以使用 SSE 注释：

```text
: heartbeat 2026-08-03T12:01:15Z
```

客户端策略：

- 保存最后成功应用的 `eventId`；
- 只应用 `sequence > lastSequence` 的事件；
- 断线后 1s、2s、5s、10s 上限退避并加抖动；
- 收到 `stream.snapshot` 后以快照为准重置局部 Store；
- 收到 `stream.complete` 主动关闭连接。

## 5. 用户确认接口

### 5.1 查询确认详情

```http
GET /api/v1/confirmations/{confirmationId}
```

返回用户真正需要判断的信息：

```json
{
  "confirmationId": "198000000000000301",
  "taskId": "198000000000000101",
  "planVersion": 2,
  "status": "PENDING",
  "actions": [
    {
      "confirmationItemId": "198000000000000311",
      "stepId": "198000000000000204",
      "title": "在 07:45 将模拟车辆空调设为 22°C",
      "tool": "vehicle.schedule_climate",
      "riskLevel": "HIGH_RISK_WRITE",
      "target": "RoadMind Demo Car",
      "effect": "仅修改数字孪生车辆的计划任务"
    },
    {
      "confirmationItemId": "198000000000000312",
      "stepId": "198000000000000205",
      "title": "出门后关闭客厅灯",
      "tool": "home.control_device",
      "riskLevel": "HIGH_RISK_WRITE",
      "target": "模拟家庭 · 客厅灯",
      "effect": "仅修改模拟家居状态"
    }
  ],
  "expiresAt": "2026-08-03T12:10:00Z"
}
```

### 5.2 批准确认

```http
POST /api/v1/confirmations/{confirmationId}/approve
Idempotency-Key: <key>
```

请求：

```json
{
  "planVersion": 2,
  "approvedItemIds": [
    "198000000000000311",
    "198000000000000312"
  ]
}
```

服务端逐项重新计算 `payloadHash`，在同一事务中把批准 item 置为 `APPROVED/READY`，并把对应步骤置为 `READY`；Executor 随后原子领取，不由 Controller 直接调用工具。未列出的 item 保持 `PENDING`，因此支持逐项或批量批准。确认过期返回 `410 CONFIRMATION_EXPIRED`；计划版本变化返回 `409 CONFIRMATION_PLAN_CHANGED`。

### 5.3 拒绝确认

```http
POST /api/v1/confirmations/{confirmationId}/reject
Idempotency-Key: <key>
```

请求：

```json
{
  "rejectedItemIds": ["198000000000000311"],
  "reason": "不用提前开空调，只保留路线规划"
}
```

`rejectedItemIds` 省略时拒绝组内全部仍为 `PENDING` 的 item。拒绝后 Agent 可基于明确结果生成新计划，但不能自动再次请求相同高风险操作。

## 6. 车辆数字孪生接口

对外 API 统一标注模拟。`roadmind-web` 访问 `roadmind-server`；模拟器内部 API 默认不直接暴露给浏览器。

### 6.1 查询车辆列表

```http
GET /api/v1/vehicles
```

### 6.2 查询车辆状态

```http
GET /api/v1/vehicles/{vehicleId}/status
```

响应：

```json
{
  "vehicleId": "198000000000000401",
  "displayName": "RoadMind Demo Car",
  "mode": "DIGITAL_TWIN",
  "batteryPercent": 38.0,
  "estimatedRangeKm": 210.0,
  "cabinTemperature": 8.5,
  "doorLocked": true,
  "charging": false,
  "location": {
    "coordinateSystem": "GCJ-02",
    "longitude": 118.7969,
    "latitude": 32.0603,
    "city": "南京"
  },
  "tirePressure": {
    "frontLeft": 2.4,
    "frontRight": 2.4,
    "rearLeft": 2.3,
    "rearRight": 2.3
  },
  "stateVersion": 12,
  "observedAt": "2026-08-03T12:00:00Z"
}
```

### 6.3 修改模拟状态（演示管理接口）

```http
PATCH /api/v1/demo/vehicles/{vehicleId}/state
Idempotency-Key: <key>
```

请求仅允许白名单字段：

```json
{
  "expectedVersion": 12,
  "batteryPercent": 25,
  "cabinTemperature": 5.0
}
```

此接口仅在 `demo` Profile 和管理员权限下启用，并写审计。生产 Profile 不注册该路由。

### 6.4 车辆命令

普通用户不得直接绕过 Agent 确认调用高风险命令。供 Executor 使用的内部应用接口示例：

```http
POST /internal/v1/vehicles/{vehicleId}/commands/schedule-climate
POST /internal/v1/vehicles/{vehicleId}/commands/lock
GET  /internal/v1/vehicles/{vehicleId}/scheduled-tasks
```

内部不代表免授权：只有已经生成 `AuthorizedToolCall` 的 Executor 可调用，并携带服务身份、trace 和幂等键。

## 7. 行程接口

### 7.1 查询已校验路线计划

```http
GET /api/v1/route-plans/{routePlanId}
```

返回归一化路线、`routeVersion`、`routeHash`、坐标系、距离、时长、来源模式和缓存时间。`sourceMode` 必须明确为 `LIVE`、`CACHE` 或 `STUB`；测试 Stub 不能显示为实时高德路线。只有资源所属用户能读取。

### 7.2 创建模拟行程

```http
POST /api/v1/trips
Idempotency-Key: <key>
```

请求：

```json
{
  "taskId": "198000000000000101",
  "vehicleId": "198000000000000401",
  "routePlanId": "198000000000000501",
  "simulationSpeed": 5
}
```

仅允许 `1`、`5`、`20` 倍速。创建后状态为 `PLANNED` 或 `READY`。

### 7.3 行程控制

```http
POST /api/v1/trips/{tripId}/start
POST /api/v1/trips/{tripId}/pause
POST /api/v1/trips/{tripId}/resume
POST /api/v1/trips/{tripId}/cancel
POST /api/v1/trips/{tripId}/reset
PATCH /api/v1/trips/{tripId}/simulation-speed
```

所有写接口要求 `Idempotency-Key`。`reset` 仅在 demo 模式允许，并回到相同路线初始模拟状态。

### 7.4 查询行程

```http
GET /api/v1/trips/{tripId}
```

返回路线摘要、最新遥测、状态、速度倍率、路线版本和 `mode: SIMULATION`。

### 7.5 行程 SSE

```http
GET /api/v1/trips/{tripId}/events
Accept: text/event-stream
Last-Event-ID: {tripId}:{sequence}
```

遥测事件：

```text
id: 198000000000000601:128
event: trip.telemetry
data: {"schemaVersion":1,"eventId":"198000000000000601:128","sequence":128,"type":"trip.telemetry","traceId":"01K2...","tripId":"198000000000000601","occurredAt":"2026-08-04T00:26:12Z","data":{"longitude":119.2145,"latitude":31.8421,"coordinateSystem":"GCJ-02","speedKmh":82,"heading":126,"batteryPercent":31,"remainingRangeKm":168,"remainingDistanceKm":147,"estimatedArrivalTime":"2026-08-04T01:42:00Z","tripStatus":"DRIVING","simulationSpeed":5}}
```

行程事件类型：

| 事件 | 说明 |
| --- | --- |
| `trip.snapshot` | 初次连接或重放超窗时的完整快照 |
| `trip.status.changed` | 状态机变化 |
| `trip.telemetry` | 位置与车辆数据 |
| `trip.low-battery` | 低电量事件及阈值 |
| `trip.replanning.started` | Agent 开始重新规划 |
| `trip.route.updated` | 新路线与版本摘要 |
| `trip.error` | 可恢复/不可恢复异常 |
| `trip.completed` | 完成并关闭流 |

## 8. 工具调用与审计接口

### 8.1 查询工具调用

```http
GET /api/v1/agent-tasks/{taskId}/tool-calls?cursor={id}&limit=50
```

响应只提供脱敏摘要、状态、耗时、重试次数和 trace。原始模型 Prompt、密钥和敏感家庭信息不返回。

### 8.2 查询单次调用

```http
GET /api/v1/tool-calls/{executionId}
```

### 8.3 查询审计日志

```http
GET /api/v1/audit-logs?taskId={taskId}&action={action}&cursor={id}&limit=50
GET /api/v1/traces/{traceId}
```

普通用户只能查看自己的资源；管理员接口应独立鉴权。阶段 7 才开放完整查询页面。

## 9. 偏好、定时任务与模拟家居接口

这些接口在对应阶段落地，阶段 1 不创建空 Controller。

### 9.1 用户偏好

```http
GET    /api/v1/preferences?category={category}&cursor={cursor}&limit=30
PUT    /api/v1/preferences/{category}/{preferenceKey}
DELETE /api/v1/preferences/{category}/{preferenceKey}
```

`PUT` 与 `DELETE` 要求 `Idempotency-Key`。响应只返回当前用户的分类值；精确地点和家庭设备映射按敏感数据处理，不进入普通审计详情。

### 9.2 持久化定时任务

```http
GET  /api/v1/scheduled-tasks?status={status}&cursor={cursor}&limit=30
GET  /api/v1/scheduled-tasks/{scheduledTaskId}
POST /api/v1/scheduled-tasks/{scheduledTaskId}/cancel
```

用户只能取消属于自己且尚未执行的任务。定时执行车辆/家居高风险动作时必须携带原计划版本、确认 item、`payloadHash` 和同一幂等键；接口不能把普通提醒授权升级为车辆控制授权。

### 9.3 模拟家居状态

```http
GET /api/v1/home-devices
GET /api/v1/home-devices/{deviceId}
```

普通用户只有只读查询。`home.control_device` 只能由持有 `AuthorizedToolCall` 的 Executor 调用；演示状态重置若实现，放在 `demo` Profile 的管理员路由并写审计。响应始终包含 `mode: SIMULATION`。

## 10. Agent 评测接口（阶段 8）

```http
GET  /api/v1/evaluation-runs?cursor={cursor}&limit=30
GET  /api/v1/evaluation-runs/{runId}
GET  /api/v1/evaluation-runs/{runId}/cases?status={status}&cursor={cursor}&limit=50
POST /api/v1/evaluation-runs
```

创建评测仅允许管理员或本地演示身份，要求 `Idempotency-Key`。请求必须声明 `mode: STUB` 或 `mode: LIVE_MODEL`；报告保留模型、参数、代码版本、时间和失败明细，前端不得把 Stub 指标标成真实模型指标。

## 11. 设置与模型配置接口

第一版只暴露非敏感状态，不把 API Key 返回前端：

```http
GET /api/v1/settings/capabilities
GET /api/v1/settings/model-status
GET /api/v1/settings/map-status
```

响应示例：

```json
{
  "model": {
    "configured": true,
    "provider": "OPENAI_COMPATIBLE",
    "modelName": "configured-model",
    "healthy": true
  },
  "map": {
    "provider": "AMAP",
    "configured": false,
    "degraded": true
  }
}
```

写入 API Key 的管理界面不在首版范围；本地通过环境变量配置。

## 12. 错误码草案

### 12.1 通用与认证

```text
INVALID_REQUEST
VALIDATION_ERROR
UNAUTHENTICATED
CSRF_TOKEN_INVALID
FORBIDDEN
RESOURCE_NOT_FOUND
RATE_LIMITED
STATE_CONFLICT
IDEMPOTENCY_CONFLICT
```

### 12.2 会话与 Agent

```text
CONVERSATION_CLOSED
MESSAGE_TOO_LARGE
MISSING_CONTEXT
CONTEXT_CONFLICT
MODEL_UNAVAILABLE
MODEL_TIMEOUT
MODEL_OUTPUT_INVALID
PLAN_INVALID
PLAN_CYCLE_DETECTED
UNKNOWN_TOOL
```

### 12.3 策略与确认

```text
POLICY_DENIED
TOOL_NOT_ALLOWED
PARAMETER_NOT_ALLOWED
CONFIRMATION_REQUIRED
CONFIRMATION_EXPIRED
CONFIRMATION_ALREADY_DECIDED
CONFIRMATION_ALREADY_CONSUMED
CONFIRMATION_PLAN_CHANGED
CONFIRMATION_PAYLOAD_MISMATCH
PROMPT_INJECTION_SUSPECTED
```

### 12.4 工具与验证

```text
TOOL_TIMEOUT
TOOL_UNAVAILABLE
TOOL_EXECUTION_FAILED
TOOL_RESULT_INVALID
VERIFICATION_FAILED
VERIFICATION_MISMATCH
DEPENDENCY_STEP_FAILED
```

### 12.5 行程与地图

```text
VEHICLE_SIMULATOR_UNAVAILABLE
VEHICLE_STATE_CONFLICT
ROUTE_PROVIDER_UNAVAILABLE
ROUTE_NOT_FOUND
MAP_NOT_CONFIGURED
TRIP_INVALID_TRANSITION
TRIP_STREAM_REPLAY_EXPIRED
TRIP_ALREADY_COMPLETED
```

### 12.6 偏好、调度与评测

```text
PREFERENCE_NOT_FOUND
SCHEDULED_TASK_NOT_CANCELLABLE
SCHEDULED_TASK_EXPIRED
HOME_DEVICE_OFFLINE
EVALUATION_MODE_NOT_ALLOWED
EVALUATION_RUN_NOT_FOUND
```

## 13. 安全与限额

- 所有列表接口限制 `limit`，默认 30/50，最大 100。
- 用户消息最大 8 KiB；计划最大 20 步；单工具参数 JSON 最大 16 KiB。
- SSE 每用户、每任务和全局分别限连接数；连接建立需要资源归属校验。
- 内部模拟器接口绑定私有网络或 localhost，不暴露公网。
- Demo 管理接口只在明确 Profile 注册，并在 UI 显示“模拟控制”。
- CORS 只允许显式前端源；本地开发源通过配置提供。Cookie 写请求同时校验 CSRF。
- 统一输入校验后再写审计，日志对控制字符和换行做规范化，防止日志注入。

## 14. 版本演进

- URL 采用 `/api/v1`。
- SSE 数据使用独立 `schemaVersion`；增加字段保持向后兼容。
- 删除/重命名字段必须升级 API 或事件 schema。
- 工具名稳定，行为变化通过 `toolVersion` 记录。
- 计划保存实际执行时的工具版本，评测才能复现。
