# RoadMind Agent 开发计划

> 文档状态：阶段 0 审查通过（开发基线）
> 目标周期：10～14 个高强度开发日
> 策略：每阶段保持可运行、可测试、可回退，不并行堆空模块

## 1. 总体节奏

| 阶段 | 主题 | 建议时间 | 核心可见结果 |
| --- | --- | --- | --- |
| 0 | 架构规划 | 0.5～1 天 | 需求、架构、API、数据、安全和地图文档 |
| 1 | 基础工程与车辆模拟器 | 1.5 天 | 可运行双后端骨架、车辆状态 API、Vue 骨架 |
| 2 | 基础工具调用闭环 | 1.5 天 | 模型/规则入口、3 个只读工具、基础 SSE |
| 3 | Agent 核心工作流 | 2 天 | Context→Planner→Policy→Executor→Verifier→确认 |
| 4 | 地图与行程模拟 | 2～2.5 天 | 真实道路、车辆平滑行进、暂停/倍速/低电量重规划 |
| 5 | 记忆与定时任务 | 1 天 | Redis 会话、偏好、持久化计划任务与恢复 |
| 6 | MCP 服务 | 0.75～1 天 | 复用工具注册表的 MCP Server |
| 7 | 稳定性、安全与审计 | 1～1.5 天 | 限流、熔断、幂等、脱敏和审计页面 |
| 8 | 评测与开源整理 | 1.5～2 天 | 30～50 用例、CI、README、GIF/视频与简历材料 |

如果时间不足，优先保证阶段 0～4、阶段 7 的关键安全项和阶段 8 的基础评测。MCP 和高级设置页面可以减小范围，但不能删除 Policy Gate、Verifier、确认或免责声明。

## 2. 开发前置条件

阶段 0 临时工作区检查结果（2026-08-03）。这不是用户 Windows 主机的验收结果，迁移到目标目录后必须重新执行全部命令：

| 工具 | 当前状态 | 阶段 1 前要求 |
| --- | --- | --- |
| Java | OpenJDK 17.0.19 | 安装并切换到 JDK 21 |
| Maven | 未安装 | 安装 Maven 3.9.16；项目再生成 Maven Wrapper |
| Node.js | 24.14.0 | 可用；建议与锁定基线 24.19.0 LTS 对齐 |
| npm | 11.9.0 | 可用；基线 11.19.0，并修复当前 `http-proxy` 未知配置警告 |
| Docker | 未安装 | 安装 Docker Desktop 与 Compose v2 |
| Git | 2.51.1 | 可用 |
| Git 仓库 | 当前目录不是仓库 | 阶段 1 经用户确认后初始化 |

Windows PowerShell 开发建议：

- 使用 `java --version`、`mvn --version`、`node --version`、`npm --version`、`docker version`、`docker compose version` 重新验收；
- `JAVA_HOME` 指向 JDK 21，不在项目中写本机绝对路径；
- Docker Desktop 使用 WSL2 后端时先验证端口与卷权限；
- 项目启动命令最终写成 PowerShell 可复制形式。

## 3. 阶段 0：架构规划

### 阶段目标

冻结首版边界，选择能在个人周期内完成的架构，避免阶段 1 反复推倒。

### 具体任务

- 盘点当前目录、Git 和本机工具链；
- 明确核心场景、非目标和完成标准；
- 选择模块化单体 + 独立车辆模拟器；
- 核对稳定依赖和官方兼容关系；
- 设计 Agent、工具、策略、验证、数据、API、SSE、地图、安全和测试；
- 记录 Architecture Decisions；
- 只创建允许的 7 份文档。

### 验收标准

- 七份文档内容互相一致；
- 明确不接真实车辆；
- 所有阶段均有验收与风险；
- 版本无 SNAPSHOT、`latest` 或动态范围；
- 当前目录除 `docs` 外无新业务工程。

### 建议测试

- Markdown 链接、Mermaid 语法和文档交叉检查；
- 文件清单核对；
- 人工走读核心演示场景是否覆盖每个系统边界。

### 不在本阶段完成

- 任何 Java/Vue/Maven/Docker 业务代码；
- 依赖安装、Git 初始化、构建和测试执行。

### 推荐 Git 提交节点

用户确认并初始化仓库后：`docs: define RoadMind phase-0 architecture`。

### 主要风险

- 需求过宽；通过 P0/P1/P2 和阶段验收控制。
- 版本在阶段 1 前更新；脚手架创建当日再核对一次官方版本。

## 4. 阶段 1：基础工程与车辆模拟器

### 阶段目标

建立最小可运行工程，完成车辆数字孪生的查询与受控修改，不依赖模型 API。

### 具体任务

1. 初始化 Git、根 README、MIT `LICENSE`、`.gitignore` 和 `.env.example`。
2. 创建 Maven 聚合根工程、`roadmind-server` 与 `vehicle-simulator` 两个真实可执行模块；固定 Java 21、Spring Boot 3.5.16，并生成 Maven Wrapper。
3. 创建 Vue 3 + TypeScript + Vite 工程，锁定 Node 24.19.0、npm 11.19.0、依赖版本和 `package-lock.json`。
4. 创建 Docker Compose，仅 MySQL 8.4.11、Redis 8.10.0；配置健康检查和非默认本地密码占位。
5. 模拟器实现内存车辆状态、状态版本、查询、白名单修改和 reset。
6. Server 实现 `VehicleGateway` 与模拟器 HTTP Adapter，提供统一对外车辆状态接口。
7. 引入 Flyway（Boot BOM 管理，含 MySQL 支持），只创建阶段 1 实际使用的迁移；关闭运行时自动建表。
8. 加入 Bean Validation、统一响应、错误码、traceId、固定 demo 身份和基础日志脱敏。Demo 身份仅在 `demo` Profile、回环地址/本地环境启用。
9. 为浏览器访问预留会话 Cookie + CSRF 契约；阶段 1 的 demo 修改接口也必须通过 CSRF 或明确限定为仅测试调用。
10. 补充单元测试、模拟器接口测试、Flyway 迁移测试和 Server→Simulator 契约测试。
11. README 提供 Windows PowerShell 启动、停止、测试和常见问题。

默认端口：Web 5173、Server 8080、Simulator 8081、MySQL 主机 3307、Redis 主机 6380；全部支持环境变量覆盖。Compose 项目名固定为 `roadmind-agent`。

### 验收标准

- `mvnw.cmd test` 实际通过；
- 前端 `npm run type-check` 与 `npm run build` 实际通过；
- `docker compose up -d` 后 MySQL、Redis 健康；
- Flyway 能在空库完成迁移，重复启动不重复建表；
- 模拟器可独立启动；
- HTTP 可查询车辆状态并修改至少一个字段；
- 越界电量/温度被拒绝；并发版本冲突返回 409；
- Server 在模型 Key 缺失时仍可启动；
- 仓库扫描无密钥、密码和本地绝对路径。

### 建议测试

- `VehicleStateService` 单元测试；
- MockMvc/WebTestClient 参数校验；
- 相同幂等键重放、不同参数冲突；
- 模拟器离线时 Server 返回明确 503；
- MySQL/Redis Testcontainers 冒烟测试。
- 未带/错误 CSRF Token 的 demo 写请求被拒绝（若阶段 1 已开放浏览器写接口）。

### 不在本阶段完成

- Planner、Tool Calling、完整会话、地图、SSE 遥测、MCP。

### 推荐 Git 提交节点

1. `chore: bootstrap java and vue workspaces`
2. `feat(simulator): add versioned vehicle state API`
3. `feat(server): add simulator vehicle gateway`
4. `test: cover vehicle state validation and contracts`
5. `docs: add windows development guide`

### 主要风险

- Windows 环境的 JDK/Maven/Docker 未准备；先完成前置检查再脚手架。
- 一开始拆太多 Maven 模块；只保留两个可执行应用，业务边界先用包。

## 5. 阶段 2：基础工具调用闭环

### 阶段目标

完成“用户请求 → 结构化工具请求 → 只读工具执行 → SSE 反馈”的最小闭环。

### 具体任务

1. 引入 Spring AI 1.1.8 BOM 和 OpenAI 兼容模型配置；无 Key 时禁用模型 Bean 或使用明确 Stub。
2. 定义 `RoadMindTool`、`ToolDescriptor`、`ToolRegistry`、输入 Schema 和统一结果。
3. 实现 `vehicle.get_status`、`weather.get_forecast`、`route.plan` 三个只读工具。
4. 天气和路线适配器提供真实模式与测试 Stub，严禁把 Stub 结果标成真实调用。
5. 完成模型结构化输出到 DTO 的最小转换和一次非法 JSON 修复。
6. 实现 Agent 请求异步任务和基础 SSE：分析、工具开始、工具完成、最终响应。
7. 记录模型名、Token（若提供）、耗时、工具执行和 trace。

### 验收标准

- 模型配置可用时能根据请求选择只读工具；
- 模型不可用时返回明确降级状态，不伪造规划；
- 未注册工具被拒绝；
- 三个工具均有参数校验、超时和错误映射；
- SSE 能按顺序显示至少 4 类事件并正常完成；
- 测试不依赖外部收费 API。

### 建议测试

- ToolRegistry 重名/未知工具；
- DTO/JSON Schema 正常、缺字段、未知字段、超大结构；
- WireMock 外部成功、超时、无效 JSON；
- SSE 事件顺序与完成关闭；
- 模型 Stub 的工具选择契约测试。

### 不在本阶段完成

- 高风险确认、完整 DAG、Verifier、真实地图行进、长期记忆。

### 推荐 Git 提交节点

1. `feat(tool): add typed tool registry and execution result`
2. `feat(adapter): add vehicle weather and route tools`
3. `feat(agent): add structured planning entrypoint`
4. `feat(sse): stream basic agent lifecycle events`
5. `test: add tool and external adapter contracts`

### 主要风险

- OpenAI 兼容端点对 Tool Calling/JSON Schema 支持不一致；将模型输出适配封装，测试原始响应。
- 地图 Key 暂不可用；允许显式 Stub，但 UI/日志标注来源。

## 6. 阶段 3：Agent 核心工作流

### 阶段目标

实现完整 `Context Manager → Planner → Policy Gate → Executor → Verifier`，支持多轮修正与人工确认。

### 具体任务

1. 通过 Flyway 建立 conversation、message、agent_task、plan_step、tool_call、confirmation、confirmation_item、home_device、audit 最小表与迁移。
2. Context Manager 实现槽位来源、覆盖、版本、摘要和缺失信息判断。
3. Planner 输出版本化 DAG；实现循环、依赖、步骤数和参数校验。
4. Policy Gate 实现风险注册、资源归属、参数白名单、确认创建与过期。
5. Executor 按拓扑顺序执行，加入错误分类、依赖停止和同 Key 有限重试；确认批准事务先持久化可恢复的 `READY` 执行意图，再由 Executor 领取。
6. Verifier 为车辆计划任务、锁车、家居状态和持久化提醒实现回查。
7. 前端实现计划详情、执行时间线和高风险确认弹窗。
8. 处理成功、部分成功、验证失败、等待补充和等待确认。

### 验收标准

- “明天去苏州”会询问时间和起点；补充后形成计划；
- “不是学校，从南京南站”只保留新起点并增加版本；
- 模型声明错误风险不影响服务端真实风险；
- 未确认高风险步骤不会调用下游；
- 确认绑定 planVersion 与 payload hash，重复确认不重复执行；
- Verifier 能演示一次工具返回成功但回查不匹配；
- 任务最终状态正确区分成功、部分成功和失败。

### 建议测试

- 槽位优先级和污染防护；
- DAG 循环、缺失依赖、未知工具；
- 风险矩阵与资源越权；
- 确认批准/拒绝/过期/重复/计划变化；
- 幂等执行并发测试；
- Verifier mismatch 与依赖步骤停止；
- 核心流程集成测试。

### 不在本阶段完成

- 真实行程推进、长期偏好 UI、MCP、完整限流和评测页面。

### 推荐 Git 提交节点

1. `feat(context): add versioned conversation slots`
2. `feat(agent): validate versioned plan dag`
3. `feat(policy): enforce server-side risk and confirmation`
4. `feat(executor): add idempotent step execution`
5. `feat(verifier): verify critical write results`
6. `feat(web): add plan timeline and confirmation flow`
7. `test: cover agent safety workflow`

### 主要风险

- 状态机分散导致竞争条件；所有迁移集中在聚合服务并使用乐观锁。
- 把 Spring AI 自动 Tool Calling 当成授权；只让它提出候选调用，实际执行走自有运行时。

## 7. 阶段 4：车辆地图与行程模拟

### 阶段目标

让车辆数字孪生沿高德真实道路路线平滑行驶，支持控制、倍速、断线恢复和低电量重规划。

### 具体任务

1. 定义并持久化 `RoutePlan`、坐标系、累计距离、路线 hash 和 routeVersion，补充 `route_plan` Flyway 迁移。
2. 调用高德路线服务并归一化 GCJ-02 数据；缓存带来源和时间。
3. 模拟器实现单车单写者 Trip Engine、位置、电量、速度、heading 和故障注入。
4. Server 实现 Trip Coordinator、状态机、Redis Stream、最新快照和降采样入库。
5. SSE 支持 sequence、eventId、`Last-Event-ID`、心跳、重放和超窗快照。
6. 前端实现 MapProvider、路线、车辆 Marker、平滑插值和跟车。
7. 实现 start/pause/resume/cancel/reset 与 1x/5x/20x。
8. 实现低电量检测、充电站/策略工具和 routeVersion 更新。
9. 实现地图 Key 缺失/加载失败降级视图。

### 验收标准

- 地图显示真实底图与真实规划路线；
- 页面始终可见 `SIMULATION`/`DIGITAL TWIN`；
- 车辆沿路线移动，heading 合理，无明显 1 秒跳动；
- 暂停不推进，继续从同一点恢复；倍速不改变总距离/总基础能耗；
- SSE 断开后重连不重复应用事件；
- 低电量触发一次重规划，未行驶路线动态更新；
- 地图失败时其余界面仍可使用。

### 建议测试

- 距离、二分定位、heading、插值和能耗单测；
- 状态机合法/非法迁移；
- Redis Stream 重放、去重、超窗快照；
- 并发 pause/cancel；
- 高德适配器契约和无效坐标；
- 前端地图降级和 SSE 延迟状态；
- 完整南京→苏州演示 E2E。

### 不在本阶段完成

- Three.js、真实 GPS、车企 API、复杂交通仿真、生产导航。

### 推荐 Git 提交节点

1. `feat(route): normalize amap driving routes`
2. `feat(simulator): add deterministic trip engine`
3. `feat(trip): stream resumable trip telemetry`
4. `feat(web): render route and interpolated vehicle pose`
5. `feat(trip): add controls speed and low-battery replanning`
6. `test: cover trip state and stream recovery`

### 主要风险

- 高德配额/Key/域名限制；先用固定契约测试，真实模式单独验证。
- 浏览器插值和后端模拟重复推进；后端负责事实，前端只渲染插值。
- 坐标系错误；所有坐标携带 `coordinateSystem` 并做契约测试。

## 8. 阶段 5：上下文、长期记忆与定时任务

### 阶段目标

在服务重启和多轮对话中恢复工作状态，并真实执行持久化的模拟定时任务。

### 具体任务

1. Redis 保存活跃槽位、最近消息、待确认、幂等和 TTL。
2. MySQL 保存分类偏好，提供查看、修改、删除接口。
3. 只按任务取用常用地点、温度、充电偏好、默认车辆和家庭设备。
4. 实现 `scheduled_task`、到期扫描、Worker 租约、取消和恢复。
5. 固定调度边界：模拟器执行车辆原生定时命令；Server 只调度提醒、家居、启动行程等跨域任务，不重复创建车辆预热副作用。
6. `schedule.create_task` 只允许普通提醒白名单；延后高风险动作仍引用原 plan step、确认 item、payload hash 和幂等键。
7. 服务重启后恢复未执行任务、待确认摘要和活跃行程快照。
8. 前端实现偏好与定时任务的最小可用查看/管理。

### 验收标准

- “学校”能映射到已保存地点；用户修正后本轮使用新值；
- 用户可以删除长期偏好，删除后不再进入规划；
- 确认过期后不能执行；
- 服务重启后未来任务仍被领取一次；
- 两个 Worker 竞争同一任务只有一个执行；
- Redis 清空后关键任务和审计仍可从 MySQL 恢复。

### 建议测试

- TTL、上下文恢复和偏好最小读取；
- Worker 租约、崩溃恢复和并发领取；
- DST/时区和“明天 8 点”解析；
- 取消与执行竞态；
- Redis 不可用降级。

### 不在本阶段完成

- 向量记忆、语义搜索、自动长期记忆一切内容。

### 推荐 Git 提交节点

1. `feat(memory): persist scoped user preferences`
2. `feat(context): cache recoverable session state`
3. `feat(schedule): execute durable leased tasks`
4. `test: cover memory expiry and scheduler recovery`

### 主要风险

- 过度保存隐私；长期记忆显式分类、可删、最小注入。
- 简单 `@Scheduled` 内存任务重启丢失；以数据库任务为事实源。

## 9. 阶段 6：MCP 服务

### 阶段目标

把 RoadMind 的受控能力通过 MCP 暴露，展示标准化 Agent 工具集成，同时复用现有安全边界。

### 具体任务

1. 核对 Spring AI 1.1.8 MCP Server Starter 官方文档和传输方式。
2. 创建 `roadmind-mcp-server` 可执行模块，不复制工具实现。
3. 通过受认证的 Server API 或共享只读契约暴露工具描述。
4. 首批暴露只读工具；高风险工具若暴露，结果必须是 confirmation required，而不是直接执行。
5. 限制客户端、工具集合、参数大小、超时和并发。
6. 记录 MCP requestId、client、tool、trace、策略结果和错误。
7. 编写连接、工具列表、调用和错误处理文档。

### 验收标准

- MCP 客户端可列出并调用至少 3 个工具；
- 未知工具和非法参数被拒绝；
- MCP 无法绕过 Policy Gate；
- 高风险调用不会在无用户确认时执行；
- MCP 服务故障不影响主 Web 演示。

### 建议测试

- 初始化/工具列表/工具调用契约；
- 鉴权失败、超时、未知工具、超大参数；
- 高风险确认流程；
- trace 贯穿 MCP→Server→Simulator。

### 不在本阶段完成

- 公网 MCP 托管、多租户开发者平台、任意第三方动态工具安装。

### 推荐 Git 提交节点

1. `feat(mcp): expose controlled roadmind tool catalog`
2. `feat(mcp): enforce auth policy and tracing`
3. `docs: add mcp client integration guide`

### 主要风险

- 框架传输配置变化；按锁定版本官方文档实现并加契约测试。
- MCP 重复业务规则；只做协议适配，策略仍在主 Server。

## 10. 阶段 7：稳定性、安全与审计

### 阶段目标

让关键失败可控、写操作可追溯、安全规则可自动验证。

### 具体任务

1. 按适配器配置 Resilience4j timeout、retry、circuit breaker 和 bulkhead。
2. Redis 限流：用户消息、工具、确认、SSE、demo 管理接口。
3. 完善幂等唯一索引、同 Key 参数冲突、执行结果回放。
4. Prompt Injection 风险信号、外部工具数据隔离和上下文最小化。
5. 结构化日志、trace、模型 Token、脱敏工具摘要和追加审计。
6. 完善安全响应头、精确 CORS、Cookie、CSRF、SSE ticket（仅 Cookie 不满足场景时）与连接限制。
7. 故障注入：模型、地图、天气、Redis、模拟器超时与 Verifier mismatch。
8. 前端完善错误、部分成功、断线、地图不可用和依赖降级状态。

### 验收标准

- 每种依赖故障都有明确状态和恢复路径；
- 只读有限重试，高风险写不产生重复副作用；
- 同一确认并发提交只消费一次；
- 审计可按 taskId/traceId 复盘完整路径；
- 日志和 API 响应不包含密钥/Token/密码；
- 限流和 SSE 连接上限可测试；
- Prompt Injection 用例不能绕过工具授权。

### 建议测试

- Toxiproxy 或 WireMock 延迟/断连；
- 熔断打开/半开/恢复；
- 并发幂等和确认；
- 越权 IDOR；
- 日志脱敏断言；
- Redis/MySQL 故障行为；
- 基础依赖漏洞与 secret scanning。

### 不在本阶段完成

- 生产 SOC、SIEM、HSM、零信任网络或高可用集群。

### 推荐 Git 提交节点

1. `feat(resilience): isolate external dependency failures`
2. `feat(security): add rate limits and prompt risk signals`
3. `feat(audit): add redacted traceable audit trail`
4. `test: add failure injection and authorization cases`

### 主要风险

- 过度重试放大故障；按工具明确配置，最多一次。
- 审计存储敏感原文；使用摘要和字段白名单。

## 11. 阶段 8：Agent 评测与开源整理

### 阶段目标

产生可复现证据并把仓库整理为可公开展示的求职作品。

### 具体任务

1. 创建 `roadmind-evaluation`，先 30 条、建议 50 条 JSON/YAML 用例。
2. 覆盖单/多工具、缺失信息、多轮修正、时间、失败、非法 JSON、重复、确认、注入、SSE、低电量、地图/模型不可用和 Verifier。
3. 实现离线 Stub 模式与可选真实模型模式，运行元数据区分来源。
4. 计算规划成功、工具选择、参数、拦截、非法计划、延迟、恢复和验证指标。
5. GitHub Actions 运行后端测试、前端 type-check/test/build、secret scan；真实模型评测不在普通 PR 中自动消费额度。
6. 完善 Docker Compose、README、架构图、API 示例、演示脚本和故障演示。
7. 录制 GIF/视频和高质量截图，清楚显示模拟标识。
8. 根据实际执行结果写简历项目描述和面试讲解。

### 验收标准

- 不少于 30 条用例可重复运行；
- 报告包含版本、模型、参数、时间和失败明细；
- 所有公开百分比可由保存的运行结果复算；
- CI 在干净环境通过；
- README 从零启动命令实际验证；
- 免责声明醒目，不暗示厂商授权；
- 仓库无密钥、本机路径、伪造结果和无用空模块。

### 建议测试

- 评测指标计算自身单元测试；
- 固定种子/温度下的回归；
- README 命令在全新目录验证；
- Docker Compose 健康与关闭；
- 演示脚本按计时完整走一遍。

### 不在本阶段完成

- 为了提高百分比手工删除失败用例；
- 把 Stub 结果包装成真实模型/真实地图/真实性能数据；
- 自动 Git push 或发布，除非用户明确确认。

### 推荐 Git 提交节点

1. `feat(evaluation): add reproducible agent benchmark`
2. `ci: verify server web and repository safety`
3. `docs: publish architecture and demo guide`
4. `docs: add verified evaluation results`
5. `chore: prepare public release candidate`

### 主要风险

- 评测结果受模型漂移影响；保存模型名、参数、日期和原始分类结果。
- 演示视频掩盖错误；保留失败与降级演示，增强可信度。

## 12. 每日建议安排（12 天示例）

| 天 | 工作 |
| --- | --- |
| Day 1 | 阶段 0 确认；准备 JDK/Maven/Docker；初始化仓库 |
| Day 2 | 阶段 1 Java/DB/Redis/模拟器 |
| Day 3 | 阶段 1 Vue/测试/README；阶段 2 工具契约 |
| Day 4 | 阶段 2 模型、天气、路线、基础 SSE |
| Day 5 | 阶段 3 上下文、Planner、计划校验 |
| Day 6 | 阶段 3 Policy、确认、Executor、Verifier |
| Day 7 | 阶段 4 路线归一化、模拟器 Trip Engine |
| Day 8 | 阶段 4 SSE、地图、插值和行程控制 |
| Day 9 | 阶段 4 低电量重规划；阶段 5 记忆/调度 |
| Day 10 | 阶段 6 MCP；阶段 7 限流/熔断/审计 |
| Day 11 | 阶段 7 故障测试；阶段 8 评测与 CI |
| Day 12 | README、截图/GIF、演示视频、简历与发布前检查 |

预留 Day 13～14 处理高德 Key、Windows/Docker 差异、模型兼容和 UI 打磨，不新增核心范围。

## 13. 统一完成定义

每个功能只有同时满足以下条件才算完成：

- 有真实可运行实现，不是空类或伪响应；
- 正常、失败和权限/参数边界有测试；
- 用户可见状态与实际后端状态一致；
- 日志和审计包含 trace 且已脱敏；
- Windows PowerShell 命令可用；
- 文档与实现同步；
- 未实际运行的测试、构建、API 和指标不写成“已通过”。

## 14. 阶段 1 开始前的确认清单

- [ ] 用户明确确认进入阶段 1。
- [ ] 目标开发目录正确且无需要保留的冲突文件。
- [ ] JDK 21 可用且 `JAVA_HOME` 正确。
- [ ] Maven 3.9.16 或等价稳定 3.9.x 可用。
- [ ] Docker Desktop 与 Compose v2 可用。
- [ ] Node 24.19.0 LTS 与 npm 11.19.0 可用，npm proxy 警告已处理。
- [ ] 重新核对 Spring Boot 3.5.16 / Spring AI 1.1.8 官方兼容页。
- [ ] 确认是否初始化 Git；不自动创建 GitHub 仓库、不 commit、不 push。
- [ ] 确认高德 Key 和模型 Key 均可后置，不阻塞基础服务启动。
- [ ] 确认阶段 1 使用 Flyway，且不一次性创建阶段 2～8 的空表。
- [ ] 确认浏览器认证基线为会话 Cookie + CSRF，demo 身份不会在非 demo Profile 自动启用。
