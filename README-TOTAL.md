# RoadMind Agent 总交付说明

交付基线：2026-08-05
累计实现：阶段 0–8 主线
Git 提交：本工作区尚未创建提交，当前变更保留在工作区供审核。

## 目录说明

- `roadmind-server/`：主业务服务、Agent、Policy Gate、Workflow、Trip、持久化与审计；
- `vehicle-simulator/`：车辆与家庭设备数字孪生模拟器；
- `roadmind-mcp-server/`：独立的无状态 MCP 协议适配服务；
- `roadmind-web/`：Vue 前端与阶段 4–5 管理页面；
- `roadmind-evaluation/`：30 条可复现离线评测 fixture 与运行器；
- `docs/`：需求、架构、数据库、安全、API、阶段验收和 MCP 说明；
- `ui-reference/`：6 张正式 UI 视觉基准图；
- `SHA256SUMS.txt`：原交付包的文件校验记录。

## 当前完成范围

1. 阶段 0：需求、架构、数据库、安全、API、地图与开发计划文档；
2. 阶段 1：Spring Boot Server、车辆数字孪生模拟器、Vue 前端、MySQL/Redis Compose、安全基线与车辆状态闭环；
3. 阶段 2：Spring AI/规则双规划入口、三个强类型只读工具、异步 Agent 任务和 SSE；
4. 阶段 3：多轮上下文、版本化 DAG、服务端 Policy Gate、高风险确认、Executor 与 Verifier；
5. 阶段 4：道路路线、确定性 Trip Engine、行程状态机、倍速/暂停/取消、低电量重规划、遥测 SSE 与实时行程页面；
6. 阶段 5：Redis/MySQL 恢复分层、Agent/Workflow/Trip 持久化、车辆原生定时命令、高风险延后授权、家居数字孪生、可靠 Trip Outbox、Trip 归属校验；
7. 阶段 6：令牌保护的无状态 MCP 服务，桥接 `vehicle.get_status`、`weather.get_forecast`、`route.plan` 三项只读工具；
8. 阶段 7：Resilience4j 依赖隔离、Redis/本地限流、提示注入拦截、脱敏审计、SSE 连接上限和跨用户 Trip 访问隔离；
9. 阶段 8：30 条离线评测 fixture、可复算报告、GitHub Actions CI、Windows 本地验证脚本和发布边界说明。

## 真实性与安全边界

- 车辆、家庭设备和外部工具均使用数字孪生、Stub 或显式标记的 Live 适配器，不接入真实车辆；
- MCP 只负责协议适配和入口认证，工具风险级别、Schema、超时、依赖错误和 Policy Gate 仍由主 Server 控制；
- 高风险写操作不会通过 MCP 暴露；车辆写入与调度不配置自动重试，避免副作用重复；
- 离线评测是 `OFFLINE_RULE_STUB` 基线，不代表真实模型质量或生产性能；
- 未接入公网 MCP 托管、多租户开发者平台、生产 SOC/SIEM/HSM 或自动发布；
- 不要将模型、高德、数据库或 MCP 令牌提交到 Git，仅写入本机环境变量或未跟踪的 `.env`。

## 验证状态

自动化验证已完成：

- Maven：Server 60 项、车辆模拟器 15 项、MCP 4 项测试全部通过；
- 前端：7 个测试文件、17 项测试通过，TypeScript 类型检查和 Vite 生产构建通过；
- 评测：30/30 fixture 通过，`planningSuccessRate=1.0`、`promptInjectionBlockRate=1.0`，平均 fixture 延迟 108.33 ms；
- 数据库：Flyway V1→V9 迁移包含可靠事件 Outbox、延后动作授权字段和 Trip 归属字段；
- CI 与本地入口：`.github/workflows/ci.yml` 和 `scripts/verify-project.ps1` 已覆盖后端、前端、评测和 secret scan。

真实 Docker/HTTP 验收记录持续写入：

- [`docs/phase5-validation.md`](docs/phase5-validation.md)：持久化、恢复、原生调度、延后授权、Outbox 和 Trip 归属；
- [`docs/phase6-8-validation.md`](docs/phase6-8-validation.md)：MCP、稳定性、安全、审计、评测与边界；
- [`docs/mcp-integration.md`](docs/mcp-integration.md)：MCP 启动与 JSON-RPC 调用约束。

## 本机继续验收

环境要求：JDK 21、Node.js 24.19.0+、npm 11.19.0+、Docker Desktop/Compose v2、Git。

在 Windows PowerShell 中进入仓库根目录执行：

```powershell
docker compose up -d
.\scripts\verify-project.ps1
```

若要单独启动 MCP，先启动主 Server，再按 [`docs/mcp-integration.md`](docs/mcp-integration.md) 设置本机令牌和 `ROADMIND_SERVER_BASE_URL`。

## 已锁定 UI 页面

1. Agent 出行工作台
2. 执行计划
3. 安全确认
4. 实时行程
5. 车辆数字孪生
6. 任务审计

界面统一采用明亮“晨光智舱”视觉方案。

## 项目声明

项目与小米汽车及其他汽车厂商无官方关联；所有车辆能力均为数字孪生模拟，不执行真实车辆控制。
