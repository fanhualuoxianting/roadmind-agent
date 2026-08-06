# RoadMind Agent 当前验证报告

> 本报告记录当前分支代码在本机和 GitHub Actions 的真实执行结果。`RULE_STUB` 离线评测不是 Live Model 准确率；本机跳过的 Testcontainers 测试不计入通过数。

## 运行元数据

| 项目 | 当前值 |
| --- | --- |
| 分支 | `agent/harden-ownership-and-repo-quality` |
| 验证代码 HEAD | `87b8ec2 fix: expose simulator on compose network` |
| 执行日期 | 2026-08-06 |
| 本机环境 | Windows 11 x64；Java 21.0.9；Maven Wrapper 3.9.9；Node 24.18.1；npm 12.0.2；Python 3.11.9 |
| 本机 Docker | Docker CLI 29.6.2 / Compose v5.3.1；Linux daemon 未运行 |
| CI 环境 | GitHub Actions Ubuntu 24.04；Java 21；Node 24 / npm 12；Python 3.13.14；Docker Compose full profile |
| CI 运行 | [Push 31097208167](https://github.com/fanhualuoxianting/roadmind-agent/actions/runs/31097208167)；[PR 31097211316](https://github.com/fanhualuoxianting/roadmind-agent/actions/runs/31097211316) |

本机验证生成的 `target/`、`node_modules/` 和离线评测报告均被 `.gitignore` 忽略，未写入本报告。

## 后端测试

本机命令：

```powershell
.\mvnw.cmd -B -ntp clean verify
```

| 执行环境 | 发现 | 成功 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 本机 Windows（Docker daemon 不可用） | 117 | 94 | 0 | 0 | 23 |
| CI full profile（Docker daemon 可用） | 117 | 117 | 0 | 0 | 0 |

模块合计为 `roadmind-server` 98、`vehicle-simulator` 15、`roadmind-mcp-server` 4。CI 日志显示三模块全部成功；本机 23 项 Testcontainers 集成测试因 Docker daemon 不可用而跳过，不能写成通过。

## 前端测试与构建

命令：

```powershell
Push-Location .\roadmind-web
npm ci
npm run test -- --run
npm run build
Pop-Location
```

- 测试文件：7 个；
- 测试用例：18 个，18/18 通过；
- 生产构建：通过，Vite 8.2.0 完成打包；
- CI 与本机均未出现 `EBADENGINE`；Node/npm 已统一为 Node 24.18.1 / npm 12.0.2。

## Python 离线评测

```powershell
python .\roadmind-evaluation\run_evaluation.py
python -m unittest discover -s .\roadmind-evaluation -p 'test_*.py'
```

- CI fixture：30，总计 30；通过 30；失败 0；
- CI Python unittest：1/1 通过；
- CI 输出的 `planningSuccessRate`、`recoveryRate` 和 `promptInjectionBlockRate` 均为 1.0；
- 该结果只证明确定性 fixture 和安全规则基线，不代表真实模型能力。

## Agent 模式与模型配置

- 默认模式：`RULE_STUB`；
- `LIVE_MODEL`：代码支持通过 Spring AI `ChatModel` 生成候选计划，契约测试使用本地 Mock，不调用外部模型；
- 本轮验证环境：`ROADMIND_AGENT_MODE=RULE_STUB`、`SPRING_AI_MODEL_CHAT=none`；
- 真实可用模型配置：否。本轮没有启用或验证真实付费 provider；报告不写入 API key、token 或个人配置值。

## Docker、HTTP、SSE、MCP

| 项目 | 本机 | CI 证据 |
| --- | --- | --- |
| Compose 配置 | 通过 | `full` 和 `full + smoke` 配置检查通过 |
| 三个应用镜像 | Docker daemon 不可用，未在本机构建 | `vehicle-simulator`、`roadmind-server`、`roadmind-mcp-server` 均构建成功 |
| 容器健康 | 本机未执行 | MySQL、Redis、模拟器、主服务、MCP 均 healthy；Smoke Runner 执行后自动移除 |
| HTTP Smoke | 本机受 Docker daemon 和宿主机端口占用影响，未完成 | 通过：capabilities、模拟器状态、conversation、agent acceptance、terminal task lookup |
| SSE Smoke | 本机未完成 | 通过：收到 `agent.response.ready` 和 `stream.complete` |
| MCP Smoke | 本机未完成 | 通过：`initialize`、`tools/list`、`vehicle.get_status` |
| 诊断与清理 | 本机未执行 | 失败时诊断步骤和 teardown 均成功，未泄露 token/cookie/CSRF |

Docker Smoke Runner 使用 `network_mode: service:roadmind-server` 在可信 loopback 边界内访问主服务；模拟器仅在 Compose 环境绑定 `0.0.0.0`，宿主机端口仍绑定 `127.0.0.1`。Smoke token 每次由脚本动态生成，不写入仓库。

## 已知警告、未完成项和边界

- 当前 Windows Docker Desktop Linux daemon 未运行，所以本机仍有 23 个 Testcontainers 测试跳过，且本机 Docker Smoke 尚未复现；CI full profile 已全部通过；
- Mockito 在测试中自附加 Java agent，输出未来 JDK 兼容性警告；
- Flyway 提示 MySQL 8.4 高于当前已测试支持版本 8.1；
- MCP Spring context 提示没有 resource/prompt/complete 方法，属于当前无此类方法的非阻断警告；
- GitHub Actions 提示 `actions/setup-java@v4` 和面向 Node 20 的旧 action 兼容性迁移警告；
- `qa-artifacts/` 在当前 checkout 中不存在，没有图片可安全迁移或删除；
- 真实付费模型、双机局域网演示和 60–90 秒作品集演示仍需人工完成。

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

Windows 可通过 Git Bash 或 WSL 执行最后一条命令。Docker daemon 不可用时，Docker Smoke 应返回阻塞状态 2；daemon 可用后按原命令执行。CI 使用同一脚本和独立 Compose 项目。
