# 阶段 4 验收记录：地图与行程模拟

> 日期：2026-08-04
> 提交前状态：源码完成，前端真实回归通过；Java 仅完成全仓语法解析，待 JDK 21 / Maven / Docker 验收。

## 1. 本阶段交付

- `RoutePlan` 携带 `routeVersion`、`routeHash`、`sourceMode`、`coordinateSystem` 和归一化折线；
- 高德 Live 适配器分别地理编码起终点，读取驾车 `steps.polyline`，过滤连续重复点并生成 SHA-256；
- Stub 路线是固定契约数据，始终以 `STUB` 和免责声明展示；
- Simulator `TripEngine` 是单车单写者，按累计距离二分定位并计算 heading、电量与 ETA；
- 合法状态迁移：READY→DRIVING↔PAUSED，及 CANCELLED / COMPLETED；
- 1×、5×、20× 只改变模拟时间，不改变单位里程基础能耗；
- Server 协调创建、控制、轮询、一次性低电量重规划和路线 V2；
- SSE 带单调 eventId/sequence、心跳、历史重放和超窗快照；
- 前端保存最后事件 ID，主动重连后不重复应用旧遥测；
- `MapProvider` 封装高德 JS API；无 Key/加载失败时回到明确标注的降级视图；
- 已实现最终 UI 基线中的实时行程页面与控制条；
- Flyway V3 定义 `route_plan`、`trip`、`trip_event` 表与必要唯一索引。

## 2. 当前环境真实验证

### 前端

执行：

```text
npm run type-check
npm run test -- --run
npm run build
```

结果：

- TypeScript 类型检查通过；
- 7 个测试文件、17 项测试全部通过；
- Vite 生产构建通过；
- 新增测试覆盖坐标/heading 插值、遥测乱序丢弃、SSE 重放去重、低电量事件和行程 Store。

### Java 静态语法

使用 `java-parser 2.3.3` 对仓库 153 个 Java 文件解析：

```text
Parsed 153 Java files; failures=0
```

这只能证明 Java 语法树可解析，不能替代 Maven 的依赖解析、类型检查、Spring 上下文启动或测试运行。

## 3. 当前未验证，禁止写成通过

- 当前环境仅有 OpenJDK 17 JRE，没有 `javac`；
- Maven / Wrapper 分发包不可用；
- Docker、MySQL、Redis 不可用；
- 新增 Simulator / Server 单元测试尚未实际运行；
- Flyway V2/V3 尚未在 MySQL 8.4 空库与重复启动场景执行；
- Server→Simulator→Web 三进程 HTTP/SSE 尚未真实联调；
- 未提供用户高德 Web 服务 Key 与 JS API Key，因此 Live 路线和真实底图尚未请求；
- Redis Stream 持久重放仍未接入，当前事件窗口为应用内存实现。

## 4. 回到电脑后的阻断验收

```powershell
java --version
.\mvnw.cmd test
docker compose up -d
docker compose ps
npm --prefix .\roadmind-web run type-check
npm --prefix .\roadmind-web run test -- --run
npm --prefix .\roadmind-web run build
```

必须额外验证：

1. `TripEngineTest` 的暂停不推进、倍速能耗一致和非法迁移；
2. `TripServiceTest` 的一次性低电量重规划和创建幂等冲突；
3. `TripApiTest` 的 CSRF、创建、START 与路线 V2；
4. `AmapGatewayContractTest` 的完整折线归一化；
5. SSE 断开后带最后事件 ID 重连，不重复应用事件；
6. MySQL 空库执行 V1→V2→V3，重复启动无漂移；
7. 模拟器离线时保留最后快照并展示遥测延迟；
8. 使用个人高德 Key 单独验收 Live 路线和浏览器底图，不提交 Key。

## 5. 停止点

本记录形成时，阶段 5 尚未进入实现；随后已在 JDK 21、Maven、Docker、MySQL 和 Redis 可用后开始推进。阶段 5 第一条垂直链路的实际结果见 [`phase5-validation.md`](phase5-validation.md)，不要把本历史停止点与当前仓库状态混用。
