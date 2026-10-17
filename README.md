# ProjectMosire

一个 **Java 21 + Maven 多模块、轻量化、可自管理** 的 Agent 软件：以一个大 Agent（Brain）为核心，可自由创建/销毁多个权限各异的子 Agent；对外用 **MCP**（工具/数据）、**A2A**（Agent 间互操作）、**AG-UI**（用户界面）说话；对内用**统一事件存储 + 个人信息模型 + 权限/身份**管理一切状态；且**运行时自身（配置、模块、skills、子 Agent）可被运行中的 Agent 管理**。

## 模块

根 `pom.xml` 当前包含 **6 个模块**：

| 模块 | 职责 | 允许依赖 / 要点 |
|---|---|---|
| `AgentLibMosire` | 通用 LLM 工具包：LLM 客户端/接口、AgentTool、MCP 适配、权限、事件存储、配置、进程管理；并含 **Agent 描述符**（`AgentConfig`）、**上下文窗口结构**（`ContextWindow`/`ContextComposer`/`LlmRoundTrip`）与**通用循环骨架**（`AgentLoop` + 策略 SPI） | —；不得 import `io.mosire.brain.*` / `io.mosire.main.*`。**含骨架/描述符，不含具体策略**：何时停、记账、事件形状、持久化、技能、记忆、子 Agent、重试、沙箱、权限判定仍留 Brain。 |
| `WorkspaceMosire` | 工作区虚拟路径、挂载、能力/权限与安全路径解析（`resolveForIo`） | `agentlib-mosire` |
| `DocsMosire` | 文档/日志存储、编辑、查询与文档工具 | `agentlib-mosire` + `workspace-mosire` |
| `BrainMosire` | Agent 运行时：对话、工具载入、上下文构造/压缩、子 Agent 编排、skills、记忆 | `agentlib-mosire`、`workspace-mosire`、`docs-mosire`（+ Jackson/SLF4J）；不得依赖 `MainMosire` |
| `MainMosire` | 入口/网关：组装、CLI、AdminREST、A2A/AG-UI/MCP 入口、健康检查、发行 | `brain-mosire`、`agentlib-mosire`、`workspace-mosire`、`docs-mosire`；`bash-plugin-mosire` 仅 provided，用于构建顺序/插件拷贝，主代码不得 import/使用 |
| `BashPluginMosire` | PF4J 插件：bash 工具与沙箱实现 | main 只依赖 `agentlib-mosire`（PF4J provided）；测试面可依赖 Brain；宿主 main 不 import 它 |

依赖方向与模块红线见 [`AGENTS.md`](AGENTS.md) §0.1；验证入口见 `scripts/gate.sh`（规则见 [`docs/agents/VERIFICATION.md`](docs/agents/VERIFICATION.md)）；当前状态见 [`docs/agents/STATUS.md`](docs/agents/STATUS.md)。

## 文档

- [`docs/agents/STATUS.md`](docs/agents/STATUS.md) — **当前状态入口**：版本、源码基线、工作树、发布/Pages 事实、未验证项与下一步。
- [`docs/agents/SKILLS.md`](docs/agents/SKILLS.md) — **工程 Skill v2 方案**：五层分流、状态机、契约、工程 Skill 与 Brain 运行时 Skill 隔离、宿主差异、gate/report、维护预算。当前 **0 个 active 工程 Skill**。
- [`docs/agents/WORKFLOW.md`](docs/agents/WORKFLOW.md) — 控制方、派单粒度、任务书/测试 Agent、设计文档门槛、评审与等待、收工自查。
- [`docs/agents/VERIFICATION.md`](docs/agents/VERIFICATION.md) — 并发、`scripts/gate.sh`、Surefire/SpotBugs、run-bound 证据、变异自证、时钟约束。
- [`docs/agents/ENVIRONMENT.md`](docs/agents/ENVIRONMENT.md) — JDK/Maven、内存、`~/.m2`、LF/CRLF、grep/find、真模型脚本、临时目录。
- [`开发计划.md`](开发计划.md) — **范围/顺序与验收判据**；不重复实时记账，实时状态以 STATUS 为准。
- `设计-*.md`（仓库根）— 现行设计文档索引：身份与血缘 / 子 Agent 终局与等取口 / 子 Agent 上下文读取 / Bash 工具与工作目录围栏 / 审批与工具内权限 / 多 provider 路由 / 插件系统 / 配置引导 / MCP HTTP 传输 / Skill 运行时 / 决策包作为唯一决策单元 / 上下文窗口下沉 / 通用循环骨架等。数量随设计演进，不在此写死。
- [`docs/upstream/`](docs/upstream/) — 上游三库契约：AgentLib 决策契约、Workspace 路径契约、Docs 存储契约。
- [`docs/archive/2026-09-14-旧计划/`](docs/archive/2026-09-14-旧计划/) — **已归档弃用**的三代计划与 SDD 拆解；目录 README 列明历史与仍开着的接续项。历史文档不回头改。
- [`docs/archive/2026-10-17-agents-rule-migration.md`](docs/archive/2026-10-17-agents-rule-migration.md) — 2026-10-17 的 AGENTS 规则迁移台账：keep / migrate / script / invalid / pending 及用户原话。
- [`Research/调查报告.md`](Research/调查报告.md) — 2026-09-08 调查结论（协议选型、架构参考、技术栈、风险；**不是**开发计划）。
- [`AGENTS.md`](AGENTS.md) — **常驻内核与路由**：六模块边界、红线、并发/验证/文档/提交核心规则；长文在 `docs/agents/`。

> `.superpowers/**` 是历史/本机工具路径，**不是权威实时状态**；本仓不再依赖它记账。

## 快速开始

```bash
# 依赖：JDK 21（Maven 由仓库内 wrapper 提供，./mvnw 锁定 3.9.16）
./mvnw spotless:apply      # 首次：统一格式（勿加 -N，会漏掉模块源码）
./mvnw verify              # 门禁：Spotless → Checkstyle → SpotBugs → 测试
```

> 以上是命令示例；实际执行前先确认本 checkout 没有并发 Maven。文档任务不需要跑 Maven 时，不要声称跑过。

## 许可证

本项目使用 **Apache License 2.0** 开源。完整文本见根目录 [`LICENSE`](LICENSE)；SPDX 标识为
`Apache-2.0`。

第三方依赖（MCP SDK、A2A SDK、Jackson、SQLite JDBC、PF4J、Log4j 等）各自保留其上游许可证，
不因本项目采用 Apache-2.0 而改变。分发二进制或源码时请同时遵守各依赖的许可证与 NOTICE 要求。

## 里程碑状态（历史快照）

> ⚠️ **本表是 P0 视角的历史快照（2026-09-11 前后），数字与模块口径已陈旧，仅作历史记录。**
> **当前进度、版本、发布/Pages 事实以 [`docs/agents/STATUS.md`](docs/agents/STATUS.md) 为准；**
> 不要用下表数字推断当前测试结果。

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 仓库与构建骨架 | ✅ 完成（当时 `./mvnw verify` 全绿） |
| M1 | 骨架 + 主循环 demo（FakeLlmClient 一回合） | ✅ 完成（当时一回合事件入库、curl /health OK） |
| M2 | MCP 双向 + A2A server/client + AG-UI SSE 子集 + 子 Agent 子进程 | ✅ 完成（当时 MCP 双向、A2A server/client、AG-UI SSE 子集、子 Agent 子进程编排均已实现；三模块 188 测试为历史数字；离线验收 `scripts/smoke.sh`） |
| M3 | 自管理闭环 + 事件存储 + 记忆 + skills | 部分落地（历史状态；欠账见归档目录接续项） |
| M4 | 后续：MCP 3.0 评估、ACP、jlink/AppCDS、OTel | 未开始 |
