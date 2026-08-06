# RoadMind Agent Codex Handoff

本文只保留当前 checkout 的真实遗留事项和复现边界。已经完成的整理、契约测试和脚本不再作为待办重复列出。

## 当前可证明的能力

- `RULE_STUB` 是默认且可离线复现的 Agent 模式；`LIVE_MODEL` 通过 Spring AI `ChatModel` 生成候选计划。
- Live Model 输出必须是受限结构化 JSON；解析失败、空响应、异常和超时不会伪造后续工具计划。
- 模型只能提出已注册的只读工具；未知工具、非只读工具和超过数量上限的计划会 fail-closed。
- Tool Runtime 负责强类型转换、Bean Validation、参数大小上限、超时、有限重试和稳定错误码。
- 高风险写操作属于独立 Core Workflow，必须经服务端 Policy Gate、用户确认、计划版本和 payload hash 校验；模型和 MCP 不能自我授权。
- `scripts/smoke/` 提供 HTTP、SSE、MCP 和 Docker 全链路脚本；Docker Compose `full` profile 提供三个应用镜像、健康检查和基础设施依赖。
- Node/npm 已统一为 Node 24.18.1、npm 12.0.2，版本来源为 `roadmind-web/.node-version`、`package.json`、lockfile 和 CI。

## 当前遗留事项

### 1. 在 Docker daemon 可用时完成真实全链路 Smoke

当前 Windows 环境的 Docker CLI 可用，但 Docker Desktop Linux daemon 的 named pipe 不存在，因此以下内容尚未在本机执行：

- 三个应用镜像构建；
- MySQL、Redis、车辆模拟器、主服务和 MCP 容器健康等待；
- HTTP、SSE、MCP 跨进程 Smoke；
- Docker 可用时被 Testcontainers 跳过的集成测试。

复现命令：

```bash
bash scripts/smoke/docker-smoke.sh
```

CI 已配置同一脚本的独立 Compose 项目。脚本生成的 MCP token 只存在于当前进程环境，不应改成仓库内固定密钥。

### 2. 最终验证报告需要在 Docker 可用后刷新

```powershell
.\mvnw.cmd -B -ntp clean test
Push-Location .\roadmind-web
npm ci
npm run test -- --run
npm run build
Pop-Location
python .\roadmind-evaluation\run_evaluation.py
python -m unittest discover -s .\roadmind-evaluation -p 'test_*.py'
```

将最新输出、跳过项和 Smoke 结果写入 `docs/current-verification.md`。不要复制旧交接文档中的测试数字，也不要把 `OFFLINE_RULE_STUB` 结果写成 Live Model 准确率。

### 3. 非本轮范围的人工验证

- 真实 OpenAI-compatible provider 的人工 Live Model 试运行仍需用户提供并自行管理外部密钥；CI 和本地默认不调用付费模型。
- 双机 Windows 局域网、真实浏览器演示录制和作品集截图仍需在目标设备上人工完成。
- 当前 checkout 没有 `qa-artifacts/`，因此没有可安全迁移到 `docs/showcase/` 的图片；若未来出现该目录，先做引用扫描再删除或移动。

## 约束

- 不把 API key、个人路径、`.env`、运行日志、缓存或 `target/` 提交到仓库。
- 不连接真实车辆或厂商账号，不把 Stub/Fake 结果宣传成真实模型能力。
- 不关闭工具参数校验、Policy Gate、用户确认或审计来制造 Smoke 通过。
- Docker 清理只针对独立 Smoke Compose 项目，禁止使用 `down -v` 删除数据卷。
