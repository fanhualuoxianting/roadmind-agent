# 阶段 3 验收记录：Agent 核心工作流

日期：2026-08-04

## 交付范围

- 多轮 Context Manager：显式槽位来源、上下文版本、缺失信息追问和用户纠正覆盖。
- 版本化 DAG：依赖存在性、顺序、重复步骤和最大步骤数校验。
- 服务端 Policy Gate：风险由注册工具决定，不接受模型自报风险；车辆预热和家居控制被标记为 HIGH。
- 确认协议：确认与 `planVersion + payloadHash` 绑定，具有十分钟有效期；支持批准、拒绝、过期和重复决定幂等。
- Executor：确认后先产生 READY 执行意图，再按拓扑顺序领取；拒绝时写步骤不会执行。
- Verifier：每个关键写步骤都有回查说明，可用“演示验证失败/回查不一致”稳定触发一次工具成功但回查不匹配的 PARTIAL_SUCCESS。
- 前端：计划详情、槽位版本、步骤依赖、服务端风险、参数、确认面板和执行时间线。
- Flyway V2：conversation、message、agent_task、plan_step、tool_call、confirmation、confirmation_item、home_device、audit_event。

## 当前环境实际通过

| 检查 | 结果 |
| --- | --- |
| Vue TypeScript 类型检查 | 通过 |
| Vue/Vitest 回归 | 4 个测试文件，10 项通过 |
| Vite 生产构建 | 通过 |
| 阶段 3 Java 源码静态走读 | 通过 |
| Git diff 与敏感信息检查 | 交付前执行 |

## 当前环境无法执行

本次工作容器只有 Java Runtime 17，没有 `javac`；Maven Wrapper 需要重新下载 Maven，但 `repo.maven.apache.org` 不在当前网络白名单，旧 Maven 缓存也不在本轮可写文件系统中。因此本轮不能声称以下项目已通过：

- Java 21 编译和后端 JUnit（新增 4 项服务测试 + 1 项 HTTP 集成测试）；
- MySQL 8.4 上执行 Flyway V2；
- Docker/Testcontainers；
- Server、Simulator 与 Web 三进程 HTTP 联调。

回到具备 JDK 21 和 Docker 的环境后必须运行：

```powershell
.\mvnw.cmd clean test
docker compose up -d mysql redis
.\mvnw.cmd -pl roadmind-server spring-boot:run -Dspring-boot.run.profiles=demo
npm --prefix .\roadmind-web ci
npm --prefix .\roadmind-web run test -- --run
npm --prefix .\roadmind-web run build
```

## 手工演示脚本

1. 打开“计划”页面，发送“明天去苏州”，应进入 `WAITING_INPUT` 并追问出发地点和时间。
2. 补充“明天早上8点从南京软件谷出发去苏州”，应生成 V1、5 步 DAG，并进入 `WAITING_CONFIRMATION`。
3. 输入“不是学校，从南京南站出发去苏州，明天早上9点”，起点被覆盖，Context 与 Plan 均增加版本。
4. 批准后进入 `SUCCEEDED`；重复批准不会重复增加时间线事件。
5. 使用旧 planVersion 或错误 payloadHash 确认，应返回 `409 WORKFLOW_CONFLICT`。
6. 请求中加入“演示验证失败”，批准后进入 `PARTIAL_SUCCESS`，灯光步骤为 `VERIFICATION_FAILED`。

## 阶段边界

当前执行器是与数据库状态机语义一致的应用层实现，运行时仍使用内存仓储；V2 已冻结数据库结构，但 MyBatis 持久化适配器和崩溃后恢复领取必须在 MySQL 可用时完成最终验收。阶段 4 的真实地图、行程推进、倍速与低电量重规划未提前实现。
