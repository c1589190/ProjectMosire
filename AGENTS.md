# 仓库约定（AGENTS.md）

> 本项目 = Java 21 + Maven 多模块的 Agent 软件：主 Agent（Brain）为核心，可自由创建/销毁多个权限各异的子 Agent；对外 MCP/A2A/AG-UI；对内统一事件存储 + 权限 + 配置；且运行时自身（配置、模块、skills、子 Agent）可被 Agent 管理。
> 开发依据见 `开发计划.md`（**2026-09-14 重写版**；权威实时状态在 `.superpowers/sdd/2026-09-12-三期/` 的账本）
> 与 `Research/调查报告.md`（调查结论，2026-09-08）。

## 模块边界（不可违反）

```
MainMosire ──→ BrainMosire ──→ AgentLibMosire
（入口/网关）   （Agent 运行时）   （通用工具包）
                                     ▲
BashPluginMosire ────────────────────┘   （PF4J 插件：只依赖 AgentLib；宿主不依赖它）
```

- **AgentLibMosire**：通用 LLM 工具包/接口/协议（llm client、AgentTool 接口、MCP 适配、权限模型、事件存储、配置、进程管理）。**无 Agent 假设**：不得 import `io.mosire.brain.*` / `io.mosire.main.*`；可被其他程序单独依赖复用。
- **BrainMosire**：具体对话、自带 tool + 外部 MCP/原生 tool 的载入、上下文构造与压缩、子 Agent 编排、skills、记忆。只依赖 AgentLibMosire。
- **MainMosire**：进程组装、CLI、网关（AdminREST/A2A/AG-UI/MCP 入口）、健康检查、发行。**不含 Agent 行为逻辑**。
- 依赖方向检查（Maven 层 + import 层双重检查）：
  ```bash
  grep -rlE "io\.mosire\.(brain|main)" AgentLibMosire/src || echo "OK: AgentLib 无下游依赖"
  grep -rl "io\.mosire\.main" BrainMosire/src || echo "OK: Brain 无 Main 依赖"
  ```
- 包前缀：`io.mosire.agentlib.*` / `io.mosire.brain.*` / `io.mosire.main.*`（插件模块用 `io.mosire.bash.*` 一类**独立命名空间**）；
  groupId `io.mosire`。
- **插件模块（`BashPluginMosire`）**：只依赖 `AgentLibMosire`；**宿主（Brain/Main）不得依赖它**——插件 JAR 由
  PF4J 从 `plugins/` 装载（**唯一装载路径**，不 shade 进 fat jar），宿主通过 `ToolSource` 接口与它对话。
  设计见 `设计-插件系统与bash插件化.md`。

## 构建与门禁

- 工具链：JDK 21（`--release 21`）+ Maven Wrapper `./mvnw`（锁定 3.9.16；apt 的 maven 3.8.7 不满足 spotbugs 4.10.4.1 的 Maven ≥3.8.9 要求，勿用系统 `mvn`）。可选安装：`apt-get install openjdk-21-jdk-headless`、`/opt/apache-maven-3.9.16`。
- `./mvnw verify` = Spotless(google-java-format) → Checkstyle(`config/checkstyle.xml`) → SpotBugs(Max/Low) → Surefire(JUnit 6)，全绿才算完成。
- 改完代码先 `./mvnw spotless:apply` 再 `./mvnw verify`（避免格式门禁来回）。**勿加 `-N`**：`-N` 只构建根聚合器，对模块源码是空操作，`spotless:check` 会假绿（2026-09-11 实测：模块内放一个明显违规文件，`./mvnw -N spotless:check` 退出 0，`./mvnw spotless:check` 正确报违规）。- **验证一律先上真模型**（2026-09-13 用户裁决）：功能的"能不能用 / 对不对"**默认**在**使用模式**里判——真进程 + 真模型 + HTTP 对话 / HTTP 审批，以 HTTP 结果 + 日志 + 事件库三方互证（D29，见 `开发计划-三期-第二小段.md`）。
  写一套**假 LLM 装置是例外**：只有真模型造不出的**确定性**（精确帧序列、超时 / 重试、离线复现）才写，且要写明理由。**"默认不用真 LLM"不再成立**——假装置的成本实测是真跑的数倍。
- 存量离线用例保留、仍可跑（`./mvnw verify` 现状离线），定位是**回归钉**：改动只跑相关单条用例，**不新增**"为了进离线门禁"而造的假 LLM 装置，**不动辄全量**。

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

## 外部跟踪项（改代码前先查；原列于 P0 计划 §九，P0 已归档 ⇒ **以本节为准**）

- MCP Java SDK 3.0（2026-07-28 规范）发布即评估升级（当前锁 2.0.1；2.0.1 无 server/discover/header 路由）。
- AG-UI 1.0 仍 Draft（事件模型可能再变）；A2A server 用官方 spec+jsonrpc-common 纯模块自写绑定（不用 server-common/CDI）。
- BOM 版本季度复核（2026-09-08 快照见根 pom properties）。
