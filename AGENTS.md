# 仓库约定（AGENTS.md）

> 本项目 = Java 21 + Maven 多模块的 Agent 软件：主 Agent（Brain）为核心，可自由创建/销毁多个权限各异的子 Agent；对外 MCP/A2A/AG-UI；对内统一事件存储 + 权限 + 配置；且运行时自身（配置、模块、skills、子 Agent）可被 Agent 管理。
> 开发依据见 `开发计划.md`（P0 计划）与 `Research/调查报告.md`（调查结论，2026-09-08）。

## 模块边界（不可违反）

```
MainMosire ──→ BrainMosire ──→ AgentLibMosire
（入口/网关）   （Agent 运行时）   （通用工具包）
```

- **AgentLibMosire**：通用 LLM 工具包/接口/协议（llm client、AgentTool 接口、MCP 适配、权限模型、事件存储、配置、进程管理）。**无 Agent 假设**：不得 import `io.mosire.brain.*` / `io.mosire.main.*`；可被其他程序单独依赖复用。
- **BrainMosire**：具体对话、自带 tool + 外部 MCP/原生 tool 的载入、上下文构造与压缩、子 Agent 编排、skills、记忆。只依赖 AgentLibMosire。
- **MainMosire**：进程组装、CLI、网关（AdminREST/A2A/AG-UI/MCP 入口）、健康检查、发行。**不含 Agent 行为逻辑**。
- 依赖方向检查（Maven 层 + import 层双重检查）：
  ```bash
  grep -rlE "io\.mosire\.(brain|main)" AgentLibMosire/src || echo "OK: AgentLib 无下游依赖"
  grep -rl "io\.mosire\.main" BrainMosire/src || echo "OK: Brain 无 Main 依赖"
  ```
- 包前缀：`io.mosire.agentlib.*` / `io.mosire.brain.*` / `io.mosire.main.*`；groupId `io.mosire`。

## 构建与门禁

- 工具链：JDK 21（`--release 21`）+ Maven Wrapper `./mvnw`（锁定 3.9.16；apt 的 maven 3.8.7 不满足 spotbugs 4.10.4.1 的 Maven ≥3.8.9 要求，勿用系统 `mvn`）。可选安装：`apt-get install openjdk-21-jdk-headless`、`/opt/apache-maven-3.9.16`。
- `./mvnw verify` = Spotless(google-java-format) → Checkstyle(`config/checkstyle.xml`) → SpotBugs(Max/Low) → Surefire(JUnit 6)，全绿才算完成。
- 改完代码先 `./mvnw -N spotless:apply` 再 `./mvnw verify`（避免格式门禁来回）。测试全离线（FakeLlmClient/假子进程），禁止依赖外部 LLM/网络的服务。

## 编码与文档

- 协议消息/数据用 record + sealed interface（封闭代数类型），穷尽 switch。
- **中文 Javadoc**：公开 API 必须；描述"为什么"优先于"是什么"。
- 决策记录：冻结决策表在 `开发计划.md §1`；架构决策变更先写文档再改代码（每模块遵循 ARCHITECTURE.md 惯例时同步）。

## 红线（任何模块、任何 PR 不可违反）

1. **权限单调性**：Agent 不能授予自己/子 Agent 超过自身的权限；spawn 与配置变更必须经过 `PermissionMonotonicityGuard`（brain.subagent 内）。
2. **自升级禁令**：Agent 可以管理配置/skills/子 Agent，不能替换自身二进制（无自更新工具；发行走外部流程）。
3. **MCP 新代码禁用**（2026-07-28 弃用清单）：Roots、Sampling、Logging、legacy HTTP+SSE。
4. **信任边界 = 进程边界**：子 Agent = 同 jar stdio 子进程；进程内"逻辑隔离"只放自己审过的插件。
5. 任何工具不能关进程（进程生命周期归 SubprocessManager/Main 管理）。

## 外部跟踪项（列入 开发计划.md §九，改代码前先查）

- MCP Java SDK 3.0（2026-07-28 规范）发布即评估升级（当前锁 2.0.1；2.0.1 无 server/discover/header 路由）。
- AG-UI 1.0 仍 Draft（事件模型可能再变）；A2A server 用官方 spec+jsonrpc-common 纯模块自写绑定（不用 server-common/CDI）。
- BOM 版本季度复核（2026-09-08 快照见根 pom properties）。
