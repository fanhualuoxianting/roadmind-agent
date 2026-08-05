# 阶段 5 验收记录：持久化、授权延后动作与可靠事件

> 日期：2026-08-05
> 范围：上下文恢复、缓存分层、定时任务、Workflow 授权、家居/车辆数字孪生和 Trip 事件可靠投递。

## 1. 本阶段实现

- V4：`user_preference`、`scheduled_task` 和本地演示用户；
- V5：会话摘要、最后消息时间、过期时间和自增消息 ID；
- V6：Agent 任务用户归属、请求幂等键和请求 hash；
- V7：Core Workflow 版本化计划、待确认摘要和过期状态快照；
- V8：延后动作授权字段，以及 `domain_event_outbox` 可靠事件表；
- V9：Trip `owner_user_id` 和用户范围索引；
- Redis 分层缓存：会话、Workflow 状态/活跃槽位/确认摘要、Agent 任务/幂等投影分别使用独立 Key；
- MySQL 仍是事实源，Redis 用于加速和受限恢复后备；缓存不可用时不能凭空创建任务；
- `REMINDER` 持久化普通提醒：到期扫描、`FOR UPDATE SKIP LOCKED` 领取、30 秒租约、过期租约恢复、取消和幂等冲突；
- 车辆原生定时气候命令：服务端通过模拟器调度接口创建，使用幂等键并保证执行一次；
- 高风险延后动作：`APPROVE_DEFERRED` 只保存授权意图，真正创建任务时重新校验 workflow、step、confirmation、planVersion 和 payloadHash；
- 家居数字孪生：受控的 `HOME_CONTROL` 延后任务执行后回写设备状态和 Workflow 状态；
- Trip 事件 Outbox：Trip 快照和事件在同一数据库事务追加，Worker 通过租约领取，支持重试、过期租约恢复和重复投递幂等；
- 新建 Trip 保存用户归属，查询、命令和事件 SSE 按用户范围检查；历史 `owner_user_id IS NULL` 数据保留兼容读取策略。

## 2. 自动化测试证据

仓库根目录执行：

```powershell
.\mvnw.cmd -B test
```

当前结果：

- `roadmind-server`：60 项测试，0 失败，0 错误；
- `vehicle-simulator`：15 项测试，0 失败，0 错误；
- `roadmind-mcp-server`：4 项协议/策略测试，0 失败，0 错误；
- Flyway：V1→V9 迁移脚本可加载，V8/V9 覆盖延后授权、Outbox 和 Trip 归属；
- `PhaseFivePersistenceTest`：普通提醒、延后授权、审计脱敏和 Worker 竞争通过；
- `PhaseFiveTripPersistenceTest`：Trip 快照、Outbox 同事务追加、单 Worker 领取和标记投递幂等通过；
- `CoreWorkflowServiceTest`、`VehicleStateServiceTest`：车辆原生调度、确认和执行一次语义通过；
- `TripServiceTest`：不同用户不能读取或操作其他用户新建的内存 Trip；
- 前端：7 个测试文件、17 项测试通过，类型检查和 Vite 生产构建通过。

## 3. 真实 Docker/HTTP 验收结果

本次使用现有 Docker 服务 MySQL `127.0.0.1:3308`、Redis `127.0.0.1:6381`，并启动最新 JAR：车辆模拟器 `8081`、主 Server `8082`、MCP `8090`。三个健康端点均返回 `UP`。

已完成：

1. 模拟器原生定时命令：`demo-vehicle-001` 创建 `PENDING` 命令，重复使用 `live-native-schedule-20260805` 返回同一 `commandId`，列表中只有 1 条；
2. 主 Workflow `APPROVE`：Workflow `9c5f1534-2bd7-4ff6-bdb6-feae83eb29e6` 返回 `SUCCEEDED`，`s4` 车辆预热步骤成功，并在模拟器中出现带 Workflow/确认引用的原生 scheduled task；
3. 延后家居动作：Workflow `8358961b-8c46-4642-9bf8-dc8b6c02934b` 经 `APPROVE_DEFERRED` 进入 `WAITING_SCHEDULE`，任务 ID `6` 到期后为 `SUCCEEDED`，客厅灯回查为 `on=false`；
4. Trip/Outbox：Trip `ed1a89bc-a54c-4f3b-a6b3-d95b55996afb` 创建并 `START` 后为 `DRIVING`，数据库查询显示该 Trip 有 3 条 `domain_event_outbox` 记录、3 条均为 `DELIVERED`；重启主 Server 后原 Trip 仍可读取并继续被轮询推进；
5. 重启恢复：主 Server 重新启动时 Flyway 报告 schema 已在 V9，原 Workflow 可读取为 `SUCCEEDED`，活动 Trip 自动恢复；
6. Agent/审计：真实只读 Agent 任务为 `SUCCEEDED`、完成 2 个工具调用，`audit_event` 出现 `agent.request.accepted`、`agent.plan.created`、2 条 `tool.call.completed` 和 `agent.response.ready`；
7. 限流与内部工具：主 Server 内部只读工具真实响应 `200`，返回 `X-RateLimit-Limit: 60` 和 `X-Trace-ID`；demo 管理接口真实错误响应仍返回 `X-RateLimit-Limit: 30`；越权隔离由 `TripServiceTest` 覆盖，跨用户的新建 Trip 不可读/不可操作；
8. 说明：HomeDeviceService 当前是进程内数字孪生，重启后设备初始状态恢复；Workflow、授权任务和 Trip 事实数据已持久化，设备状态持久化不在本阶段宣称范围。

## 4. 明确边界

- 车辆和家居能力仍是数字孪生模拟，不连接真实硬件；
- Outbox 当前保证本地数据库事务内的可靠追加和 Worker 重试，不宣称跨公网消息系统的 exactly-once；
- Redis 本地回退限流是进程级有界窗口，多实例生产环境应使用共享 Redis；
- 未引入向量记忆、语义搜索、真实模型成绩或生产级多租户审计平台。
