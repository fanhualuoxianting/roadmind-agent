# RoadMind Agent 当前验证报告

> 本报告只记录当前 checkout 的实际命令结果。`RULE_STUB` 离线评测不是 Live Model 准确率，跳过的 Testcontainers 测试也不计入通过数。

## 运行元数据

| 项目 | 当前值 |
| --- | --- |
| 分支 | `agent/harden-ownership-and-repo-quality` |
| 验证基准 HEAD | `704d144 test: harden live model tool boundaries`（最终工程收口提交哈希见交付汇报） |
| 执行日期 | 2026-08-06 |
| 操作系统 | Windows 11 x64 |
| JDK / Maven | Java 21.0.9 / Maven 3.9.9 |
| Node / npm | Node 24.18.1 / npm 12.0.2 |
| Python | 3.11.9 |
| Docker CLI / Compose | Docker 29.6.2 / Compose v5.3.1 |

验证命令执行时生成的 `target/`、`node_modules/` 和离线评测报告均按 `.gitignore` 忽略；本报告与本轮代码改动随后一起提交。

## 后端测试

最终清理后命令：

```powershell
.\mvnw.cmd -B -ntp clean verify
```

结果：

| 模块 | 测试 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| `roadmind-server` | 98 | 0 | 0 | 23 |
| `vehicle-simulator` | 15 | 0 | 0 | 0 |
| `roadmind-mcp-server` | 4 | 0 | 0 | 0 |
| 合计 | 117 | 0 | 0 | 23 |

已执行测试 117 项，其中 94 项通过、0 失败、0 错误、23 项因 Docker daemon 不可用而跳过。统计来自清理后的 Surefire XML 和 Maven 输出，没有包含遗留报告。

## 前端测试与构建

命令：

```powershell
npm ci
npm run test -- --run
npm run build
```

- 测试文件：7 个；
- 测试用例：18 个；
- 结果：18/18 通过；
- 生产构建：通过，Vite 8.2.0 完成打包；
- `npm ci` 未再出现 `EBADENGINE`，仍有依赖 `glob` 的非阻断 deprecated 警告。

旧声明曾导致 `EBADENGINE`：Node `>=24.19.0`、npm `>=11.19.0`。本轮已统一到 `roadmind-web/.node-version`、`package.json`、lockfile 和 CI 声明的 Node 24.18.1 / npm 12.0.2，并通过本地 `npm ci`、测试和构建复验。

## Python 离线评测

命令：

```powershell
python .\roadmind-evaluation\run_evaluation.py
python -m unittest discover -s .\roadmind-evaluation -p 'test_*.py'
```

- 模式：`OFFLINE_RULE_STUB`；
- fixture：30；
- 通过：30；
- 失败：0；
- unittest：1 个测试通过；
- 该结果只证明确定性 fixture 和安全规则基线，不代表真实模型能力。

## Agent 模式与模型配置

- 当前仓库默认配置：`RULE_STUB`；
- 当前本地 `.env` 激活值：`ROADMIND_AGENT_MODE=RULE_STUB`、`SPRING_AI_MODEL_CHAT=none`；
- 可选模式：`LIVE_MODEL`，通过 Spring AI `ChatModel` 生成候选只读工具计划；
- 当前验证时没有启用真实 ChatModel provider，因此不能报告“真实可用模型配置”；
- API Key 是否存在不写入报告，也不输出任何密钥或个人配置值。

## Docker、HTTP、SSE、MCP

| 项目 | 状态 | 证据 / 阻塞 |
| --- | --- | --- |
| Compose 配置 | 通过 | `docker compose --env-file .env.example --profile full config --quiet` 通过 |
| Docker daemon | 未通过 | `docker desktop status` 报告 Docker Desktop 未运行；Linux engine named pipe 不存在 |
| Docker 镜像/容器 Smoke | 未执行 | 已补齐三个应用 Dockerfile 和 `full` profile，但 daemon 不可用 |
| HTTP Smoke | 阻塞 | 实际调用 `http-smoke.sh` 时 `127.0.0.1:8080/actuator/health` 返回 404 Apache Tomcat 页面，不是 RoadMind 主服务；未停止占用该端口的其他进程 |
| SSE Smoke | 阻塞 | 实际调用在同一主服务健康检查处返回 404，未建立 RoadMind SSE 连接 |
| MCP Smoke | 阻塞 | 脚本实际执行并因未提供 token 返回退出码 2；不会使用硬编码 token，MCP 协议单测已通过 |

Smoke 脚本已通过 `bash -n` 语法检查，Compose full 配置通过；当前已有的协议级自动化证据是 MCP `initialize`、`tools/list`、未知工具和超大参数测试均通过。这些不等同于跨进程 HTTP Smoke。

## 已知警告和未完成项

- Docker Desktop daemon 当前不可用，导致 23 个 Testcontainers 测试跳过；
- Java 测试输出包含 Mockito 动态加载 Java agent 的未来兼容性警告；
- MCP Spring context 输出无 resource/prompt/complete 方法的非阻断警告；
- Node/npm 版本已统一，本地复验通过；
- Docker 镜像构建和跨进程 Smoke 等待 Docker daemon 可用后执行；
- 当前 `8080`/`8081` 已有非本项目监听者，Smoke 未修改或停止它们；
- `qa-artifacts/` 在当前 checkout 中不存在，因此没有图片可安全整理；
- 真实付费模型未启用；Live Model 契约测试使用本地 Mock ChatModel，不调用外部服务。

## 复现命令

```powershell
.\mvnw.cmd -B -ntp clean verify
Push-Location .\roadmind-web
npm ci
npm run test -- --run
npm run build
Pop-Location
python .\roadmind-evaluation\run_evaluation.py
python -m unittest discover -s .\roadmind-evaluation -p 'test_*.py'
bash scripts/smoke/docker-smoke.sh
```

当 Docker daemon 不可用时，最后一条命令应返回阻塞状态 2；恢复 daemon 后按原命令执行并把真实结果补回本报告。
