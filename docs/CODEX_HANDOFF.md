# RoadMind Agent Codex Handoff

本文只保留当前 checkout 的真实遗留事项和复现边界。Live Model 契约测试、Smoke Runner、Docker 配置、CI 诊断和验证报告均已完成，不再作为待办重复列出。

## 当前可证明的能力

- `RULE_STUB` 是默认且可离线复现的 Agent 模式；`LIVE_MODEL` 通过 Spring AI `ChatModel` 生成候选计划。
- Live Model 输出必须是受限结构化 JSON；解析失败、空响应、异常和超时不会伪造后续工具计划。
- 模型只能提出已注册的只读工具；未知工具、非只读工具和超过数量上限的计划会 fail-closed。
- Tool Runtime 负责强类型转换、Bean Validation、参数大小上限、超时、有限重试和稳定错误码。
- 高风险写操作必须经服务端 Policy Gate、用户确认、计划版本和 payload hash 校验；模型和 MCP 不能自我授权。
- `scripts/smoke/` 提供 HTTP、SSE、MCP 和 Docker 全链路脚本；Docker Compose `full` profile 提供三个应用镜像和基础设施依赖，`smoke` profile 提供可信 loopback Runner。
- CI Push 与 PR 两条运行均通过：117 个 Maven 测试全部通过，前端 7 个测试文件的 18 个用例通过，30/30 离线 fixture 通过，HTTP/SSE/MCP/Docker Smoke 通过。
- Node/npm 已统一为 Node 24.18.1、npm 12.0.2，版本来源为 `roadmind-web/.node-version`、`package.json`、lockfile 和 CI。

## 当前遗留事项

### 1. 本机 Windows Docker 复现

当前 Windows Docker CLI 可用，但 Docker Desktop Linux daemon 未运行。因此本机仍有 23 个 Testcontainers 测试跳过，不能把本机 Docker Smoke 写成通过；CI 已在 Ubuntu 24.04 full profile 完成相同链路验证。

恢复 Docker daemon 后执行：

```bash
bash scripts/smoke/docker-smoke.sh
```

脚本生成的 MCP token 只存在于当前进程环境，不应改成仓库内固定密钥。

### 2. 非本轮范围的人工验证

- 真实 OpenAI-compatible provider 的人工 Live Model 试运行仍需用户提供并自行管理外部密钥；CI 和本地默认不调用付费模型；
- 双机 Windows 局域网、真实浏览器演示录制和作品集截图仍需在目标设备上人工完成；
- 当前 checkout 没有 `qa-artifacts/`，因此没有可安全迁移到 `docs/showcase/` 的图片；若未来出现该目录，先做引用扫描再删除或移动。

## 约束

- 不把 API key、个人路径、`.env`、运行日志、缓存或 `target/` 提交到仓库；
- 不连接真实车辆或厂商账号，不把 Stub/Fake 结果宣传成真实模型能力；
- 不关闭工具参数校验、Policy Gate、用户确认或审计来制造 Smoke 通过；
- Docker 清理只针对独立 Smoke Compose 项目，禁止对用户数据项目执行卷删除。
