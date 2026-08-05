# 阶段 2 验收记录

日期：2026-08-04

范围：基础工具调用闭环、Spring AI / 规则入口、只读车辆/天气/路线工具、异步 Agent 任务、基础 SSE、Agent 工作台。

> 本项目为智能座舱 Agent 工程演示项目，与小米汽车及其他汽车厂商无官方关联。车辆控制、车辆位置和车辆状态均通过数字孪生模拟服务实现。

## 1. 交付结论

阶段 2 已完成“用户请求 → 结构化工具请求 → 服务端注册校验 → 三个只读工具 → SSE 反馈 → 最终响应”的最小闭环，并保留阶段 1 的车辆状态与安全基线。

本轮没有进入完整 DAG、Policy Gate、高风险确认、写工具执行、Verifier、真实地图行进、长期记忆或 MCP。天气/路线的真实适配器通过受控 HTTP 契约测试，当前环境没有使用用户 Key 或收费接口进行 Live 请求。

## 2. 已实现能力

### 2.1 工具运行时

- `RoadMindTool<I, O>`、`ToolDescriptor`、`ToolRuntime` 和统一 `ToolExecutionResult`；
- 服务启动时拒绝重复工具名；运行时拒绝未知工具；
- 工具输入通过 Jackson 严格反序列化与 Bean Validation；
- 单次工具参数最大 16 KiB；
- 每个工具声明版本、风险、超时、重试、幂等和 JSON Schema 资源；
- 仅外部可恢复错误允许最多一次重试；参数错误和非可恢复错误不重试；
- 结果记录 executionId、版本、尝试次数、耗时、错误分类和 traceId。

### 2.2 三个只读工具

| 工具 | 风险 | 默认来源 | Live 适配 |
| --- | --- | --- | --- |
| `vehicle.get_status` | `READ_ONLY` | `DIGITAL_TWIN` | 独立 Simulator |
| `weather.get_forecast` | `READ_ONLY` | `STUB` | 高德天气 Web 服务 |
| `route.plan` | `READ_ONLY` | `STUB` | 高德地理编码 + 驾车路径规划 |

Stub 与 Live 使用相同工具接口。Stub 输出固定写入 `sourceMode=STUB` 与免责声明；Live 输出写入 `sourceMode=LIVE` 和提供商。高德 Key 缺失不会被误报成天气或路线成功。

### 2.3 模型与结构化计划

- Maven 引入 Spring AI 1.1.8 BOM 和 OpenAI Starter；
- `LIVE_MODEL` 使用 Spring AI `ChatModel`；
- `RULE_STUB` 是无 Key 默认模式，并在 API、SSE、任务快照和 UI 中明确显示；
- Live 模式未生成 `ChatModel` 时任务返回 `MODEL_UNAVAILABLE`，不自动伪装成模型成功；
- 模型只可提出注册的三个只读工具，不能直接执行；
- 模型 JSON 允许一次受限修复：去除代码围栏、截取单个对象、删除尾随逗号；
- 修复后仍非法、包含未知字段、未知工具或超过 6 个调用时拒绝计划；
- 模型名和是否修复进入任务快照；当前简单 `ChatModel.call(String)` 路径未取得精确 Token 时保持为空，不伪造用量。

### 2.4 Agent API 与 SSE

- `POST /api/v1/conversations`；
- `POST /api/v1/conversations/{conversationId}/agent-requests`；
- `GET /api/v1/agent-tasks/{taskId}`；
- `GET /api/v1/agent-tasks/{taskId}/events`；
- `GET /api/v1/settings/capabilities`；
- `GET /api/v1/settings/model-status`。

会话与任务写请求均要求 Cookie 会话、CSRF 和 `Idempotency-Key`。相同 Key / 相同请求返回原资源；相同 Key / 不同请求返回 `409 IDEMPOTENCY_CONFLICT`。

阶段 2 SSE 保留最多 100 条内存事件，后连接也能重放。事件 ID 为 `{taskId}:{sequence}`，客户端按 sequence 去重。完整 Redis Stream、`Last-Event-ID` 跨实例恢复、心跳和超窗 snapshot 仍按计划留在阶段 4。

### 2.5 前端

- UI 唯一基线改为已确认的明亮“晨光智舱”风格；
- `/agent` 实现对话输入、识别条件、三工具时间线、SSE 状态、天气/路线/电量摘要、来源标签和错误态；
- `/vehicle` 保留阶段 1 数字孪生状态页；
- 页面和底栏持续显示“数字孪生模拟，不接入真实汽车”；
- 页面不会把尚未完成的空调、家居或高风险写操作显示为已执行。

## 3. 自动化验证

### 3.1 Java

当前容器使用 Java 17 临时覆盖 `java.version` 进行兼容验证；仓库仍锁定 Java 21。

```bash
mvn -Djava.version=17 test
```

结果：

```text
roadmind-server:     28 tests, 0 failures, 0 errors, 1 skipped
vehicle-simulator:  11 tests, 0 failures, 0 errors, 0 skipped
total:              39 tests, 0 failures, 0 errors, 1 skipped
```

跳过项仍是 `InfrastructureSmokeTest`，原因是当前环境无 Docker。其余覆盖：

- ToolRuntime 重名、未知工具、严格字段、校验、超时和有限重试；
- 结构化模型正常 JSON、一次修复与未知字段拒绝；
- 规则演示场景精确选择三个只读工具；
- Spring AI Live `ChatModel` 路径与模型缺失显式失败；
- 模型编造未注册工具时按无效计划拒绝，不进入工具执行；
- 高德天气、地理编码和路线 HTTP 响应映射；
- Agent API、幂等冲突、SSE 事件集合与相对顺序；
- 阶段 1 Simulator 网关、CSRF、车辆状态和基础设施测试回归。

### 3.2 Vue

```bash
npm run type-check
npm test -- --run
npm run build
```

结果：

```text
TypeScript / Vue type-check: passed
Test Files: 3 passed
Tests: 8 passed
Production build: 86 modules transformed, success
Production dependency audit: 0 known vulnerabilities
```

前端测试新增 SSE sequence 去重、工具时间线更新、最终完成态、会话创建、事件流连接和显式 Stub 能力显示。

## 4. 真实双进程 HTTP 验收

在同一隔离环境内启动实际可执行 JAR：

- Simulator：`18091`；
- Server：`18090`，`demo` Profile；
- 仅为本轮无 Docker 验收关闭数据源、Flyway 和 Redis 健康检查；
- 业务 Controller、Security、Agent、ToolRuntime、RestClient、SSE 和 Simulator 均为生产实现。

提交南京软件谷到无锡学院的完整演示需求后：

| 检查项 | 实际结果 |
| --- | --- |
| 会话 | `201/OK`，字符串 BIGINT ID |
| Agent 提交 | `202/ACCEPTED`，返回 taskId 与 eventsUrl |
| 最终状态 | `SUCCEEDED` |
| 规划入口 | `RULE_STUB`，`degraded=true` |
| 车辆工具 | `SUCCEEDED / DIGITAL_TWIN`，68%，412 km |
| 天气工具 | `SUCCEEDED / STUB`，多云转晴，15～24°C |
| 路线工具 | `SUCCEEDED / STUB`，169.0 km，138 分钟 |
| 写操作能力 | `writesEnabled=false` |
| trace | 三个工具共享同一请求 traceId |
| SSE | 11 条有序事件，sequence 1～11 |

实际 SSE 顺序：

```text
agent.task.updated
agent.plan.created
agent.task.updated
tool.call.started / tool.call.completed × 3
agent.response.ready
stream.complete
```

## 5. 未在当前环境完成的验收

- JDK 21 原生编译：当前容器只有 Java 17；
- Docker、MySQL、Redis 与 Testcontainers：当前无 Docker daemon；
- 用户自己的 OpenAI 兼容模型 Live 请求：没有使用或索取用户 Key；
- 用户自己的高德 Key Live 请求：没有使用或索取用户 Key；
- 浏览器像素级截图：当前容器没有 Chromium；已通过 Vue 类型、组件状态与生产构建检查。

回到 Windows 后先完成阶段 1 的 JDK 21 + Docker 清单，再按 README 分别验证默认 Stub、`LIVE_MODEL` 和高德 Live 配置。不得把缺少外部 Key 解释成外部 Live 已通过。

## 6. 下一阶段入口

阶段 3 才进入：

```text
Context Manager → Planner DAG → Policy Gate
→ 高风险逐项确认 → Executor → Verifier
```

开始前应保留本阶段工具注册表与 SSE 信封，不让 Spring AI 自动 Tool Calling 绕过 RoadMind 的风险注册、资源归属、确认和验证流程。
