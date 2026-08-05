# 阶段 1 验收记录

日期：2026-08-04
范围：最小可运行工程、车辆数字孪生、Server 网关、浏览器安全基线、MySQL/Redis 配置、Vue 车辆状态页。

> 本项目为智能座舱 Agent 工程演示项目，与小米汽车及其他汽车厂商无官方关联。车辆控制、车辆位置和车辆状态均通过数字孪生模拟服务实现。

## 1. 本轮交付结论

阶段 1 的源代码和自动化测试已经形成闭环，可交给具备 JDK 21 与 Docker Desktop 的本机做最终环境验收。当前运行环境只有 Java 17 且没有 Docker，因此没有将“Java 21 编译”和“真实 MySQL/Redis 容器联调”标记为已通过。

本轮没有提前实现 Planner、模型调用、地图、行程模拟、MCP 或真实车辆接口。

## 2. 已通过检查

| 检查项 | 结果 | 说明 |
| --- | --- | --- |
| Maven Wrapper | 通过 | Wrapper 3.3.4，Maven 3.9.16 |
| Java 编译与单元/接口测试 | 通过（临时 Java 17 覆盖） | 仓库仍锁定 Java 21；两个模块共 22 项通过 |
| Testcontainers 基础设施烟测 | 已发现、按条件跳过 1 项 | 当前无 Docker；没有使用 H2 伪装通过 |
| Vue 类型检查 | 通过 | `vue-tsc --build --force` |
| Vue 单元测试 | 通过 | 2 个测试文件、5 项测试 |
| Vue 生产构建 | 通过 | Vite 8.2.0，83 个模块转换成功 |
| 前端生产依赖审计 | 通过 | `npm audit --omit=dev`：0 个已知漏洞 |
| 可执行 JAR 打包 | 通过 | Server 与 Simulator 均完成 Spring Boot repackage |
| 真实双进程 HTTP 闭环 | 通过 | 使用实际 JAR，不是 MockMvc |
| 敏感信息与绝对路径扫描 | 通过 | 只保留 `.env.example` 占位值，无工作区路径残留 |

## 3. 自动化测试结果

后端执行命令（当前容器仅用于兼容验证）：

```bash
MAVEN_USER_HOME=/tmp/roadmind-m2 \
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
./mvnw -Djava.version=17 test
```

结果：

```text
roadmind-server:     11 tests, 0 failures, 0 errors, 1 skipped
vehicle-simulator:  11 tests, 0 failures, 0 errors, 0 skipped
reactor:             BUILD SUCCESS
```

跳过项是 `InfrastructureSmokeTest`。它使用 `mysql:8.4.11` 与 `redis:8.10.0` Testcontainers，在 Docker 可用时会验证空库 Flyway 可重复执行和 Redis PING。

前端执行命令：

```bash
npm run type-check
npm test -- --run
npm run build
```

结果：

```text
Test Files: 2 passed
Tests:      5 passed
Build:      83 modules transformed, success
```

## 4. 真实 HTTP 闭环结果

由于当前没有 MySQL/Redis，HTTP 闭环启动 Server 时仅关闭了 Flyway、数据源自动配置和 Redis 健康指标；车辆网关、Spring Security、Cookie、CSRF、Controller、RestClient 和独立 Simulator 都是生产实现。

| 场景 | 实际结果 |
| --- | --- |
| 获取 CSRF | 返回 `X-XSRF-TOKEN`，同时建立会话 Cookie |
| 查询车辆 | `mode=DIGITAL_TWIN`，初始电量 68%，`stateVersion=1` |
| 前端可见车辆 ID | JSON 字符串，避免 JavaScript BIGINT 精度丢失 |
| 修改电量 | 电量变为 61%，`stateVersion` 单调增加至 2 |
| 同 Key、同参数重放 | 返回相同版本与相同结果，没有重复修改 |
| 同 Key、不同参数 | HTTP 409，`IDEMPOTENCY_CONFLICT` |
| Simulator 停止 | Server 返回 HTTP 503，`VEHICLE_SIMULATOR_UNAVAILABLE` |
| Simulator 恢复 | Server 重新返回 HTTP 200 与 `DIGITAL_TWIN` 状态 |
| 可观测性 | 上述业务与错误响应均包含 `traceId` |

恢复 Simulator 后状态回到 68%，这是阶段 1 内存型数字孪生的预期行为，不冒充持久化车辆状态。

## 5. 本机最终验收清单

在 Windows 电脑上进入项目根目录，先确认：

```powershell
java --version
node --version
npm --version
docker version
docker compose version
```

然后执行：

```powershell
Copy-Item .env.example .env
# 替换 .env 中所有 replace-with-... 占位值

docker compose config
docker compose up -d
docker compose ps

Get-Content .env |
  Where-Object { $_ -match '^[^#][^=]*=' } |
  ForEach-Object {
    $name, $value = $_.Split('=', 2)
    Set-Item -Path "Env:$name" -Value $value
  }

.\mvnw.cmd test
npm --prefix .\roadmind-web install
npm --prefix .\roadmind-web run type-check
npm --prefix .\roadmind-web run test -- --run
npm --prefix .\roadmind-web run build
```

最终通过条件：JDK 21 编译成功、`InfrastructureSmokeTest` 不再跳过、MySQL/Redis 健康、Flyway 迁移成功、README 中 HTTP 闭环脚本成功。

## 6. 下一阶段入口

阶段 2 应在本文件第 5 节全部通过后开始。下一阶段优先实现对话、上下文和任务事实模型，不应直接跳到模型自由调用工具。
