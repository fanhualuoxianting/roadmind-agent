# RoadMind Agent 工程证据与验收口径

本文档用于统一 README、简历和技术面试中的项目表述。只记录能够从当前代码、配置、测试或可复现运行结果中核验的能力；历史阶段报告仅作为当时版本的记录，不自动代表当前分支状态。

## 1. 项目边界

RoadMind Agent 是一个面向“人—车—家”协同出行场景的任务型 Agent 工程演示，核心目标是展示：

- 可解释规划；
- 强类型工具调用；
- 服务端 Policy Gate；
- 高风险确认与授权绑定；
- Executor / Verifier 闭环；
- 车辆与家居数字孪生；
- 行程状态机和实时遥测；
- 持久化、恢复、审计与离线评测。

**明确不属于本项目的能力：**

- 不接入小米汽车或其他厂商真实车辆；
- 不控制真实家庭设备；
- 不代表任何汽车厂商官方产品；
- 不提供生产级自动驾驶、远程控车或安全认证；
- 离线 `RULE_STUB` fixture 不代表真实模型质量或线上性能。

## 2. Agent 执行链路

```text
用户请求
  -> Planner Router（RULE_STUB / LIVE_MODEL）
  -> 结构化计划解析
  -> Tool Registry 与参数校验
  -> Policy Gate / 风险分级
  -> 必要时等待确认
  -> Executor 执行工具或 Workflow Step
  -> Verifier 回查事实
  -> SSE / 审计 / 持久化结果
```

### 2.1 Planner 模式

- `RULE_STUB` 是无模型密钥时的显式安全默认值；
- `LIVE_MODEL` 只有在配置模型提供方、Base URL、模型名与 Key 后才启用；
- 模型只负责提出候选工具计划，不能直接访问数据库、车辆模拟器或文件系统；
- 非法 JSON 只允许一次受限修复；
- 未知工具、超长参数和不满足 Schema 的调用在执行前拒绝；
- 响应应持续暴露实际 `agentMode` / 来源模式，不得把 Stub 包装成真实模型。

### 2.2 工具运行时

当前核心只读工具：

- `vehicle.get_status`；
- `weather.get_forecast`；
- `route.plan`。

运行时负责：

- 工具名唯一性；
- 严格 JSON 与 Bean Validation；
- 参数体积上限；
- 超时、依赖错误与有限重试；
- 风险元数据；
- traceId 与审计摘要。

车辆写操作和调度类操作不配置自动重试，避免副作用重复。

## 3. Policy Gate 与高风险确认

高风险动作不能仅依赖前端弹窗。服务端确认至少绑定：

- workflow；
- step；
- confirmation；
- planVersion；
- payloadHash；
- 过期时间和当前用户。

延后执行不会把一次确认永久复用。任务真正创建或执行前重新校验绑定关系，防止计划变化、参数替换或跨用户复用确认。

面试时可以解释为：**确认的是特定版本计划中的特定动作，不是“以后都同意这个操作”。**

## 4. 车辆数字孪生与 Trip Engine

### 4.1 车辆状态

数字孪生车辆提供：

- 固定演示车辆标识；
- 电量、续航、车内温度、锁车等状态；
- 单调 `stateVersion`；
- 乐观版本检查；
- `Idempotency-Key` 重放与参数冲突检测；
- 模拟器离线时显式返回依赖错误，不伪造车辆状态。

### 4.2 行程状态机

Trip Engine 支持：

- create / start / pause / resume / cancel；
- 1× / 5× / 20× 模拟速度；
- 基于累计距离的路线位置推进；
- heading、ETA、电量和遥测序号；
- 低电量触发一次重规划与路线版本升级；
- Trip 归属校验；
- 重启后的快照恢复和继续推进。

数字孪生的确定性状态机用于验证 Agent 编排，不代表真实车辆控制协议。

## 5. SSE 与恢复语义

Agent 与 Trip 使用 SSE 输出生命周期和遥测事件。可核验设计包括：

- 单调事件序号；
- `Last-Event-ID` 重放；
- 重放窗口外返回最新快照；
- 心跳；
- 前端乱序 / 重复事件去重；
- 活动连接数限制；
- 跨用户资源与流访问隔离。

断线重连不应触发同一副作用重新执行；恢复来源是持久化任务 / Trip 状态与事件序号，而不是前端猜测。

## 6. 持久化与可靠事件

- MySQL 是 Agent、Workflow、Trip 和授权任务的事实源；
- Redis 用于缓存、活跃状态和限流，故障时只能降级，不能凭空创建成功结果；
- Flyway 迁移覆盖会话、任务归属、幂等、版本化 Workflow、延后动作授权、Outbox 和 Trip 归属；
- Trip 快照与 Outbox 事件在同一数据库事务中追加；
- Worker 通过租约领取、重试和过期租约恢复处理事件；
- 该设计不宣称跨公网消息系统的 exactly-once。

家居设备服务目前仍是进程内数字孪生，设备状态重启持久化不在当前宣称范围。

## 7. MCP 边界

`roadmind-mcp-server` 是独立、无状态、令牌保护的协议适配模块，只桥接三项只读工具。MCP 层不能绕过主 Server：

- 主 Server 继续执行工具注册、Schema、参数上限、超时和 Policy Gate；
- 高风险写工具不通过 MCP 暴露；
- 缺少内部令牌应返回 401；
- 工具声明保持只读 / 非破坏性提示；
- MCP 不复制业务工具实现，避免协议层和主服务规则漂移。

## 8. 稳定性、安全与审计

当前工程包含：

- Resilience4j Retry / CircuitBreaker / Bulkhead，用于合适的只读依赖；
- Redis 固定窗口限流和有界本地降级；
- 高信号提示注入检测；
- 外部工具结果以强类型数据进入响应，不作为下一轮系统指令；
- 审计事件记录 actor、trace、taskId 与白名单摘要；
- token、password、secret、API key、cookie、authorization 等字段脱敏；
- Agent / Trip SSE 连接上限；
- 资源 owner 校验与跨用户隔离。

提示注入检测是分层防护，不应描述成“可以识别所有越狱攻击”。本地限流降级是进程级窗口，不代表多实例强一致限流。

## 9. 离线评测

`roadmind-evaluation/cases.json` 固定 30 条可复算 fixture，覆盖多工具、缺失信息、多轮修正、依赖失败、非法计划、幂等、确认、注入、SSE、低电量、模型 / 地图不可用、Verifier、MCP、审计和限流等场景。

```powershell
python .\roadmind-evaluation\run_evaluation.py
python -m unittest discover -s .\roadmind-evaluation -p 'test_*.py'
```

报告必须保留：

- cases SHA-256；
- runner / mode / seed / temperature；
- 每条失败明细；
- 可复算指标；
- `OFFLINE_RULE_STUB` 或真实模型标记。

不要把 Stub 100% 通过率写成“模型准确率 100%”。

## 10. 自动化验证

### 后端与协议模块

```powershell
.\mvnw.cmd -B clean test
```

### 前端

```powershell
cd roadmind-web
npm ci
npm run type-check
npm run test -- --run
npm run build
```

### 离线评测与统一入口

```powershell
cd ..
.\scripts\verify-project.ps1
```

### 基础设施

```powershell
Copy-Item .env.example .env
# 将示例密码替换为本地随机值
docker compose up -d
docker compose ps
```

GitHub Actions 当前覆盖 Maven 测试、前端类型检查 / 测试 / 构建、离线评测与基础 secret scan。完整 Docker / HTTP 链路仍应在本地或独立 smoke workflow 中验证。

## 11. 简历表述与代码证据

| 可写表述 | 主要证据 |
| --- | --- |
| 设计 Planner、Tool Runtime、Policy Gate、确认、Executor、Verifier 的受控 Agent 链路 | Agent / Workflow / Tool / Policy 相关模块与测试 |
| 使用数字孪生车辆和确定性 Trip Engine 验证状态机、低电量重规划与实时遥测 | `vehicle-simulator`、Trip service / engine、Trip tests |
| 通过 SSE 序号、重放、快照和前端去重处理断线恢复 | Agent / Trip event hub、SSE controller、前端 stream 逻辑与测试 |
| 使用 MySQL / Redis 分层持久化，并通过 Outbox 处理可靠事件 | Flyway V4–V9、持久化服务、Outbox worker 与测试 |
| 提供令牌保护的 MCP 只读工具桥，主 Server 继续执行策略校验 | `roadmind-mcp-server`、内部工具 API 与 MCP tests |
| 增加限流、提示注入拦截、审计脱敏和 30 条离线 fixture | security / audit / evaluation 代码、CI 和评测报告 |

测试数量、fixture 延迟和构建体积只能使用当前分支重新运行后的数字。

## 12. 当前限制

- 没有真实车辆或厂商 API；
- 没有公开在线 Demo；
- Live Model 需要用户自行提供兼容接口与密钥；
- 高德 Live 路线 / 天气需要用户自行提供 Key；
- 家居数字孪生状态尚未完整持久化；
- MCP 只暴露只读工具；
- 离线评测不等于真实模型 Benchmark；
- 未提供生产 SOC / SIEM / HSM、多租户开发者平台或自动发布；
- 真实容量、长时间稳定性和多实例一致性尚未做公开压测。

剩余任务与 Codex 验收要求见 [CODEX_HANDOFF.md](CODEX_HANDOFF.md)。
