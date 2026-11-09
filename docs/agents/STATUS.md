# ProjectMosire 当前状态

> **更新时间**：2026-11-09 21:00（Asia/Shanghai）
> **本文是实时状态入口。** `AGENTS.md` 只放常驻纪律并指向这里；`README.md` 的里程碑表、
> `开发计划.md` 的历史执行状态都不再充当实时台账。

## 1. 当前源码、版本与发布基线

| 项目 | 当前事实 | 证据/口径 |
|---|---|---|
| 当前本地版本 | **0.2.4** | 2026-10-24 用户要求作为 0.2.4 提交、推送并安装到本地 Maven 仓库；根 `pom.xml:9` 与 6 个模块 POM 的 `<parent>` 均为 `0.2.4`；**未打 tag、未发布 Pages**。 |
| 依赖基线（2026-11-09 复核） | MCP SDK **2.1.0**；Jackson **2.23.0**；json-schema-validator **2.0.8**；sqlite-jdbc **3.54.1.0**；PF4J **3.16.1**；Mockito **5.24.0** | 根 `pom.xml` properties；A2A 仍 `1.3.1.Final`；逐项理由与未跟版本见 §2.10。 |
| 当前 Pages 发布版本 | **0.2.3** | 2026-10-23 发布；tag `v0.2.3`；发布证据见 §4.4–§4.5。 |
| 上一发布版本 | **0.2.2** | 发布基线 commit `c46e777472bfc48c79d9dad0663c8a1c6c54574c`；tag `v0.2.2`；发布历史见 §4.1–§4.3。 |
| 最近 full gate 的源码 HEAD | **`6cf36d540140b151afaa4efa8c1f2804b98b75a6`** | 2026-10-24 默认上下文压缩功能提交；gate 时工作树 dirty（仅 0.2.4 版本号 bump 与 STATUS 待提交）；run `20261024T061503Z-91459-17843`。 |
| 最近依赖复核 gate | **run `20261109T114822Z-23830-12948`，`FULL VERIFY PASSED`，1667 tests** | 2026-11-09；source_sha `6d2a1a8`，工作树 dirty（仅根 `pom.xml` 依赖版本）；证据见 §2.10。 |
| 最近功能 full gate（A9） | **run `20261109T122504Z-64777-18242`，`FULL VERIFY PASSED`，1681 tests** | 2026-11-09；source_sha `6d2a1a8` + A9 未提交改动；证据见 §2.11。 |
| 最近功能 full gate（A2/A4/A5/A6） | **run `20261109T123803Z-81721-22467`，`FULL VERIFY PASSED`，1697 tests** | 2026-11-09；source_sha `a2d6741` + 本批未提交改动；证据见 §2.12。 |
| 最近功能 full gate（A3） | **run `20261109T124538Z-91298-28810`，`FULL VERIFY PASSED`，1702 tests** | 2026-11-09；source_sha `c252c5f` + A3 未提交改动；证据见 §2.13。 |
| 最近功能 full gate（A1） | **run `20261109T125439Z-105303-30944`，`FULL VERIFY PASSED`，1709 tests** | 2026-11-09；source_sha `ef1338b` + A1 未提交改动；证据见 §2.14。 |
| 0.2.3 发布提交 | **`4287752`**（`fix(agentlib): DECIDING 分段表态可回填`）+ **`4ebbf6a`**（`release: 0.2.3`） | 两个提交；代码与版本号均被上述 full gate 覆盖，提交后仅追加 STATUS 文本。tag 指向 `4ebbf6a`。 |
| 模块数 | **6** | 根 `pom.xml`：AgentLib、Workspace、Docs、Brain、Main、BashPlugin。 |
| 公开 API 兼容基线 | **0.2.2 起** | 用户 2026-10-17 裁定；后续公开 API 只增不减，变更时走下游消费方验证。 |
| 发布工作流 | **现有 `verify → deploy` 顺序与 `deploy` 继续跑测试的行为均不修改** | 用户 2026-10-17 裁定；本批未碰 `.github/workflows/**`。 |

## 2. Phase 2：scripts 最小检查闭环与 full gate

### 2.1 scripts 已提交

本次验证的源码基线 `1ed2372...` 已包含：

- `scripts/gate.sh`：run-bound gate；full 模式固定执行 `./mvnw -B -ntp verify`，focused 模式要求命名测试与模块范围；旧报告会归档，mtime 只作辅助。
- `scripts/test-summary.py`：解析 run manifest、Surefire XML 与 Maven 汇总；0 tests、failures、errors、缺失报告、mode 不匹配分别判红或 `EVIDENCE_INCOMPLETE`。
- `scripts/check-docs.py`：文档模块表/链接/STATUS/Pages 版本等一致性检查。
- `scripts/skills-lint.py`：工程 Skill 契约检查；当前 `.agents/skills` 不存在时按 0 active 通过。
- `.github/workflows/docs-skills.yml`：上述文档/脚本/Skill 检查的 CI 工作流；本身不跑 Maven。

### 2.2 scripts selftest 结果

控制方记录的最后一轮 selftest 证据为 `.work/task-10-evidence/final-checks.log`（当时 scripts 尚未提交；随后整体提交为 `1ed2372`）：

| 检查 | 结果 |
|---|---|
| `gate.sh selftest` | **23/23 PASS**（selftest 不调用真实 Maven） |
| `test-summary.py --selftest` | **10/10 PASS** |
| `check-docs.py --selftest` | **8/8 PASS** |
| `skills-lint.py --selftest` | **14/14 PASS** |
| `check-docs.py` 真实仓库 | PASS（modules=6, files=4, links=12） |
| `skills-lint.py` 真实仓库 | PASS（0 active skills；`.agents/skills` 不存在） |

> 本次 STATUS 修正后，又在当前工作树运行 `python3 scripts/check-docs.py` 与 `python3 scripts/skills-lint.py`：均 PASS（非 Maven 检查）。

### 2.3 最近一次 full gate

| 项目 | 事实 |
|---|---|
| 命令 | `./scripts/gate.sh full`（内部 Maven 命令：`./mvnw -B -ntp verify`）。 |
| 开始时间 | `2026-10-17 22:04:47 +08:00`。 |
| `source_sha` | **`33b97e28286c26d29f82d0a6ba9ff6102f6f6b43`**（gate 时工作树 dirty；本批实现随后提交为 `0481401`）。 |
| `run_id` | **`20261017T140447Z-245064-28641`**。 |
| 退出码 / 输出 | `rc=0`；输出 **`FULL VERIFY PASSED`**。 |
| 测试结果 | **1623 tests / 0 failures / 0 errors / 6 skipped**（AgentLib 679、Workspace 62、Docs 81、Brain 472、Bash 104 + 6 skipped、Main 225）。 |
| SpotBugs | 6 个模块均为 `BugInstance size is 0`。 |
| Maven / crosscheck | `MAVEN build=SUCCESS`；`CROSSCHECK log=1623 xml=1623 status=MATCH`。 |
| run-bound 证据 | manifest 记录 source SHA、开始时间、mode=full、scope=repo、命令、worktree=`dirty(tracked=10,untracked=8)`；`ADVISORY mtime_before_start=0`，mtime 仅辅助。 |
| 证据路径 | `.work/gate/20261017T140447Z-245064-28641/summary.txt`。 |

> 更早的 `1ed2372` 全绿证据（run `20261017T130949Z-218934-4482`，1618 tests）保留为历史证据，不冒充本批读取端口改动。
>
> 0.2.3 发布前的全量门禁见 §4.4（run `20261023T143755Z-70901-26515`，**1625 tests**）。

### 2.4 focused 判定样本

| 样本 | 结果 |
|---|---|
| 本批 `DecisionPackagesTest,DecisionPackageToolsTest,DecisionPackageReadPortToolsTest` | run `20261017T140440Z-244762-28701`：39 tests / 0 failures / 0 errors / 0 skipped；输出 `FOCUSED TEST PASSED (NOT a full gate)`。 |
| `VersionTest`（历史 run `20261017T130336Z-211215-31313`，source SHA `996f1fb...`） | 2 tests / 0 failures / 0 errors / 0 skipped；输出 `FOCUSED TEST PASSED (NOT a full gate)`。 |
| `DefinitelyNoSuchTestXYZ`（历史 run `20261017T130338Z-211526-15162`，source SHA `996f1fb...`） | 无 Surefire 报告；输出 `EVIDENCE_INCOMPLETE`，不假绿。 |

### 2.5 Phase 3：`mosire-verify` pilot 结论（不激活）

| 项目 | 结果 |
|---|---|
| 场景 A：`VersionTest` | baseline 与 skill 均得到 `2/0/0/0`；skill 额外输出 gate run manifest/summary 与 `FOCUSED TEST PASSED (NOT a full gate)`。 |
| 场景 B：`NoSuchPilotTestXYZ` | baseline 与 skill 均拒绝把 0 tests 说成通过；skill 通过 gate 得到 `EVIDENCE_INCOMPLETE` 与 run-bound 证据。 |
| RED 基线 | **未出现**；baseline 两个场景都正确。 |
| 决定 | **不创建 active `mosire-verify`**；按“没有 RED 不建 Skill”原则，只把“验证优先用 `scripts/gate.sh` + run-bound summary”写入 `AGENTS.md`。 |
| 候选位置 | `.work/skill-candidates/mosire-verify/`（本机、未入库、宿主不可发现）；评估记录见 `docs/agents/skills-eval/2026-10-17-mosire-verify.md`。 |

### 2.6 AGENTS 压缩（2026-10-17）

- `AGENTS.md` 从 **476 行 / 46.7KB 压到 174 行 / 14.1KB**；只保留常驻红线、六模块边界、并发/验证/文档/提交核心规则与路由。
- 详细派单/评审/自查迁入 `docs/agents/WORKFLOW.md`；并发/门禁/证据/变异/时钟迁入 `docs/agents/VERIFICATION.md`；本机环境迁入 `docs/agents/ENVIRONMENT.md`。

### 2.7 决策包读取端口（2026-10-17，本批）

- 实现提交：`0481401`（`feat(agentlib): 决策包五工具支持宿主读取端口`）。
- 新增 `DecisionPackageReadPort`：`find/pending/all/verdicts/expiryReason`。
- `DecisionPackages implements DecisionPackageReadPort`；旧 `itemVerdicts`、旧构造器/工厂和旧行为保留。
- `DecisionPackageTools` 的读路径改为端口；新增全部 `of/all/tools` 端口重载；工具名、schema、权限、错误码和 JSON 字段不变。
- `find` 可读历史/终局包；`pending()/all()` 仍由宿主按当前 tick/领域状态裁剪。
- `DecisionPackages.verdicts` 为兼容适配器：保留旧 `itemVerdicts` 的 `DECIDING` 投影规则（包括既有把累积裁决摊到未表态条目的行为）；纯累计语义由 Simos core 直接返回 `DecisionPackageSnapshot.verdicts()`。
- 设计和证据见 `设计-决策包读取端口.md`。

### 2.8 0.2.3 发布前门禁（2026-10-23）

| 项目 | 事实 |
|---|---|
| 命令 | `./scripts/gate.sh full`（内部 `./mvnw -B -ntp verify`）。 |
| `run_id` | **`20261023T143755Z-70901-26515`**。 |
| `source_sha` | **`790faa6`**；gate 时工作树 `dirty(tracked=12,untracked=5)`，包含 0.2.3 版本号与 DECIDING 修正。 |
| 结果 | `rc=0`，**`FULL VERIFY PASSED`**。 |
| 测试 | **1625 tests / 0 failures / 0 errors / 6 skipped**（AgentLib 681、Workspace 62、Docs 81、Brain 472、Bash 104 + 6 skipped、Main 225）。 |
| SpotBugs | 6 个模块均为 `BugInstance size is 0`。 |
| 证据 | `.work/gate/20261023T143755Z-70901-26515/summary.txt`；`CROSSCHECK log=1625 xml=1625 status=MATCH`。 |

### 2.9 默认上下文压缩（2026-10-24，已提交，0.2.4）

- 设计与开发计划：`设计-默认上下文压缩.md`、`开发计划-默认上下文压缩.md`。
- 功能提交：**`6cf36d5`**（`feat(agentlib): 增加默认上下文压缩与 user checkpoint`）。
- AgentLib 新增 `ContextWindow` compact 标记/自动投影、`io.mosire.agentlib.compact` 可覆写压缩 SPI 与 `DefaultContextCompactor`，并在 `AgentLoop` 每次 LLM 往返前提供可选压缩钩子。
- Brain 接线事件/严格失败/手动 `requestCompaction()`；Main 与子 Agent 真模型路径默认装配；setup/CLI 写入 `capabilities.maxContext/maxOutput`；缺容量严格失败。
- 0.2.4 full gate `20261024T061503Z-91459-17843`：`rc=0`，**FULL VERIFY PASSED**；**1667 tests / 0 failures / 0 errors / 6 skipped**；6 模块 SpotBugs 0；`CROSSCHECK log=1667 xml=1667 status=MATCH`。
- 证据：`.work/gate/20261024T061503Z-91459-17843/summary.txt`。
- **真实 LLM 验证 PASSED（2026-10-24，`deepseek-flash`）**：
  - AgentLib 引擎级：真实摘要调用 + 主模型续答 `COMPACT-OK`；
  - AgentRuntime 级：第 6 轮自动触发，`beforeTokens=3177 → afterTokens=592`，`droppedMessages=10`，`conversation.compact reason=threshold`，摘要调用 `llm.call phase=compact` 落库；压缩后真实续聊 `AFTER-COMPACT-OK`；
  - 证据：`.work/real-compact-probe/run.log`、`.work/real-compact-probe/runtime-run-4.log`、`.work/real-compact-probe/runtime-agent/events.db`；日志/事件库密钥扫描 0 命中。
- 未验证：provider 精确 tokenizer、`~/SimulatorMosire` 下游消费方检查。

### 2.10 依赖复核与 MCP SDK 2.1.0（2026-11-09）

- 复核来源：`repo1.maven.org` 各坐标 `maven-metadata.xml`（2026-11-09 实测）。
- 升级：MCP SDK **2.0.1 → 2.1.0**（稳定线，2026-10-16；2.2.0 仍是 milestone，不跟）；Jackson **2.22.2 → 2.23.0**；json-schema-validator **2.0.4 → 2.0.8**；sqlite-jdbc **3.53.4.0 → 3.54.1.0**；PF4J **3.15.1 → 3.16.1**；Mockito **5.23.0 → 5.24.0**。
- 保持：A2A SDK **1.3.1.Final**（1.5.0 可用，Main 直接依赖 `a2a-java-sdk-*`，另做迁移评估）；log4j **2.26.1**（3.0 为 beta）；slf4j **2.0.19**（2.1 为 alpha）；AssertJ **3.27.7**（4.0 为 milestone）；JUnit **6.1.3**（已最新）。
- **不跟 json-schema-validator 3.x**：3.0.9 已切到 Jackson 3（`tools.jackson.*`），与全仓 Jackson 2 树模型不兼容；2.0.8 是 2.x 最新。
- MCP 2.1.0 的行为变化（release notes）：Streamable HTTP 不再支持 2024-11-05 协议、拒绝非 `application/json` 的 POST；本仓 provider + SDK 2.1.0 客户端测试通过，**外部旧客户端未验证**。
- 编译：`./mvnw -B -ntp test-compile` rc=0（7 模块，4 min 40 s）。
- full gate：run **`20261109T114822Z-23830-12948`**，`FULL VERIFY PASSED`；**1667 tests / 0 failures / 0 errors / 6 skipped**（AgentLib 709、Workspace 62、Docs 81、Brain 479、Bash 104 + 6 skipped、Main 232）；6 模块 SpotBugs `BugInstance size is 0`；`CROSSCHECK log=1667 xml=1667 status=MATCH`。
- 证据：`.work/gate/20261109T114822Z-23830-12948/summary.txt`；source_sha `6d2a1a8`，worktree `dirty(tracked=1,untracked=5)`（tracked 仅根 `pom.xml`）。
- 未做：下游 `~/SimulatorMosire` 消费方检查；japicmp（依赖升级不改公开类集合，仍无机器级比对）；MCP 2.2.0-M2 / 3.0 未采用。

### 2.11 上下文注入与动态工具面（A9，2026-11-09）

- 设计：**[设计-上下文注入与动态工具面.md](../../设计-上下文注入与动态工具面.md)**（用户裁定：system 提示词与工具面分离；不预设硬注入；注入一次性投递；role/信封完全自定义；事件只记摘要）。
- AgentLib 新增：
  - `io.mosire.agentlib.context.ContextInjection` / `ContextInjectionKinds` / `ContextInjectionQueue` / `ContextInjectionRenderer`；
  - `ContextWindow` 新增 `inject` / `pendingInjections` / `commitInjections` / `clearInjections`（消息面仍调用方串行，注入队列自身线程安全）；
  - `io.mosire.agentlib.runtime.ToolSurface`（revision + 当前工具面）与 `ToolSurfaceNoticePolicy`（standard / none / 自定义）；
  - `AgentLoop` 新增 `runTurn(turnInput, ToolSurface)`；旧 `runTurn(turnInput, List<AgentTool>)` 保留并委托固定面；
  - `TurnListener.onContextInjected`（default）；`ContextEventTypes.CONTEXT_INJECTED`；
  - `ToolRegistry.revision()`（成功增删 +1，失败/空操作不变）。
- Brain 接线：
  - `AgentPipeline` 用 `PipelineToolSurface`（按 `registry.revision()` 缓存 `visibleTools()`）；
  - `TurnEvents.onContextInjected` 落 `context.injected`（payload 只含 id/kind/priority/summary/revision，不含正文）；
  - `BasicContextAssembler.systemText` **删除工具目录**，只留稳定提示“可用工具以本次请求的 tools 字段为准”；`ContextAssembler` 契约同步更新。
- 行为变更（有意）：system 文本不再列工具名/描述；`BasicContextAssemblerTest` / `SourcesTest` / `CompactSummaryTest` 的逐字节断言与 `composition()` SYSTEM 估算同步更新。工具定义仍经 `LlmRequest.tools` 完整发送。
- 兼容：旧 `runTurn(List)`、旧 `TurnListener` 实现、`ContextWindow` 消息语义均不变；新增面只增不减。
- full gate：run **`20261109T122504Z-64777-18242`**，`FULL VERIFY PASSED`；**1681 tests / 0 failures / 0 errors / 6 skipped**（AgentLib 723、Workspace 62、Docs 81、Brain 479、Bash 104 + 6 skipped、Main 232）；6 模块 SpotBugs 0；`CROSSCHECK log=1681 xml=1681 status=MATCH`；证据 `.work/gate/20261109T122504Z-64777-18242/summary.txt`。
- 新增测试：`ContextInjectionQueueTest` 7、`AgentLoopContextInjectionTest` 5、`ToolRegistryRevisionTest` 2。
- 未做：A2 `registerAll(sourceId)` / A4 `resync()` / A5-A6 元数据与 `tool_catalog` / A1 TLS+认证 / A3 硬超时；A9 未接真实 LLM 会话与 GUI。

### 2.12 工具面扩展与查看（A2/A4/A5/A6，2026-11-09）

- 设计：**[设计-工具面扩展与查看.md](../../设计-工具面扩展与查看.md)**。
- A2：`ToolRegistry.registerAll(String sourceId, List<AgentTool>)`——空白 sourceId 拒绝、全量校验零部分写入、失败回滚、成功后一次 onChange/revision；与 `unregisterAllBySource` 对称，不隐式替换。
- A4：`AgentToMcpServer.resync()` 公开；与 onChange 共用 `ReentrantLock`；只按当前 include 谓词重算暴露面，无变化不发通知，不刷新同名工具 schema。
- A5：`AgentTool.title()/group()/tags()` default（空串/空集）；**不进 MCP `tools/list` 额外字段**，完整信息走 `tool_catalog`。
- A6：`ToolSourceInfo` + `ToolRegistry.registerSourceInfo/sourceInfo/sourceInfos/unregisterSourceInfo/sourceOf`；来源元数据不 bump revision、不广播；按源卸载保留 last-known。
- 新增 `ToolCatalogTool`（`tool_catalog`，只读）：summary/full、source/group/tag 过滤、limit、可见面用 `PermissionChecker.allows` 同一 oracle；失败参数返回 `INVALID_ARGUMENTS`。Main `App` 注册 `tool_catalog` 为 builtin 源并登记 `ToolSourceInfo("builtin", …)`；内置工具面 22 → 23，外部 MCP GUEST 面可见 `tool_catalog`（只列自己有权调用的工具）。
- 兼容：旧 `registerAll(List)` / `register(sourceId, tool)` / `AgentToMcpServer` 入口、`ToolSpec`/`ToolDef` 形状均不变。
- full gate：run **`20261109T123803Z-81721-22467`**，`FULL VERIFY PASSED`；**1697 tests / 0 failures / 0 errors / 6 skipped**（AgentLib 739、Workspace 62、Docs 81、Brain 479、Bash 104 + 6 skipped、Main 232）；6 模块 SpotBugs 0；`CROSSCHECK log=1697 xml=1697 status=MATCH`；证据 `.work/gate/20261109T123803Z-81721-22467/summary.txt`。
- 新增测试：`ToolRegistrySourceBatchTest` 5、`ToolRegistrySourceInfoTest` 4、`ToolCatalogToolTest` 5、`AgentToMcpServerResyncTest` 1；`AppMcpLinkTest` 预期清单/计数 22→23。
- 未做：未通过真实 MCP 客户端调用 `tool_catalog`（只验证了注册/列表）；未做 GUI/管理面；A1 TLS+认证、A3 硬超时仍未开始。

### 2.13 工具执行硬超时（A3，2026-11-09）

- 设计：**[设计-工具硬超时.md](../../设计-工具硬超时.md)**（用户裁定：整个可配置硬超时，默认 10 分钟；超时在工具结果里提醒 Agent 超时未完成）。
- `ToolCallAuthorizer` 在 `tool.execute` 外包 `FutureTask` + 虚拟线程：默认 **600000 ms**，配置键 `tool.executionTimeoutMs`（`ToolContext.config()`；非正/不可解析回落默认 + WARN）。
- 超时返回 `TIMEOUT`，消息「工具执行超时未完成（超过 N ms 已放弃等待）」；`interrupt()` worker、丢弃晚到结果并 WARN；**不杀进程**。
- 只包工具本体，不包审批/决策等待；工具内 `ResourceDeniedException` 仍转 `RESOURCE_DENIED`，其它 RuntimeException/Error 原样上抛。
- 新增测试：`ToolCallAuthorizerTimeoutTest` 5（慢工具、正常工具、资源拒绝拆包、运行时异常传播、配置解析/回落）。
- full gate：run **`20261109T124538Z-91298-28810`**，`FULL VERIFY PASSED`；**1702 tests / 0 failures / 0 errors / 6 skipped**（AgentLib 744、Workspace 62、Docs 81、Brain 479、Bash 104 + 6 skipped、Main 232）；6 模块 SpotBugs 0；`CROSSCHECK log=1702 xml=1702 status=MATCH`；证据 `.work/gate/20261109T124538Z-91298-28810/summary.txt`。
- 未做：MCP 客户端 `notifications/cancelled` → 具体 execute 的链路（SDK 2.1.0 无服务端取消 API）；未用真实慢工具/真实模型验收；未在配置文件中接线 `tool.executionTimeoutMs`（只走 `ToolContext.config()` 通道）。

### 2.14 MCP HTTP TLS + 显式 opt-in（A1，2026-11-09）

- 设计：**[设计-MCP-HTTP安全.md](../../设计-MCP-HTTP安全.md)**；旧 `设计-MCP-HTTP传输与审批渠道.md` §四已追加“被取代”标注（不删原文）。
- 新增：`McpHttpAuth`（method/path/headers 的传输无关钩子）、`BearerTokenVerifier.constantTime`、`McpHttpTlsConfig`（PKCS12/JKS，`toString` 脱敏）、`McpHttpSecurity`（tls/auth/allowInsecure + 工厂）。
- `JdkHttpStreamableServerTransportProvider`：路径精确 → **认证（401 + `WWW-Authenticate: Bearer`）** → closing → 方法分派；覆盖 POST（含 initialize）/GET/DELETE。
- `AgentToMcpServer.startHttp`：新增带 `McpHttpSecurity` 的 owned / caller-owned 重载；旧重载委托 `McpHttpSecurity.none()`。owned 在 TLS 时自建 `HttpsServer` + `HttpsConfigurator`；caller-owned TLS 要求传入 `HttpsServer`。
- 安全矩阵：回环默认允许无认证/无 TLS；**非回环 + 无认证 ⇒ 启动失败**（TLS 不算认证），除非显式 `allowInsecure=true`；非回环 + Bearer 无 TLS 只 WARN（明文 token）。
- **行为变更（有意）**：旧 `startHttp(host, ...)` 在非回环上不再“只 WARN”，而是启动失败；Simos 默认 `127.0.0.1` 不受影响，配 `0.0.0.0` 需改用带 security 的重载或显式 allowInsecure。
- 测试：`McpHttpAuthTest` 4、`AgentToMcpServerSecurityTest` 3（非回环启动失败、Bearer 401/放行、PKCS12 TLS 真握手 + 401）；`keytool` 生成临时自签证书。
- full gate：run **`20261109T125439Z-105303-30944`**，`FULL VERIFY PASSED`；**1709 tests / 0 failures / 0 errors / 6 skipped**（AgentLib 751、Workspace 62、Docs 81、Brain 479、Bash 104 + 6 skipped、Main 232）；6 模块 SpotBugs 0；`CROSSCHECK log=1709 xml=1709 status=MATCH`；证据 `.work/gate/20261109T125439Z-105303-30944/summary.txt`。
- 未做：未跑 `~/SimulatorMosire` 消费方（其 `config.bindAddress()` 配 0.0.0.0 时需改调用）；未做 OAuth/mTLS/token 轮换；未从配置文件接线 TLS/认证；未用真实 MCP 客户端带 Bearer 连接（测试用 JDK HttpClient）。

## 3. 工作树状态

0.2.3 发布提交后，开工前已有的 5 个未跟踪文件：

```text
?? AgentLibMosire/.factorypath
?? BashPluginMosire/.factorypath
?? BrainMosire/.factorypath
?? MainMosire/.factorypath
?? configs/agents/bash-agent.json
```

当前 0.2.4 本地版本由两个关注点提交组成：
1. `6cf36d5`：默认上下文压缩功能与测试；
2. 0.2.4 版本 bump + 本文状态收口（release commit，本文件所在提交）。

0.2.4 只做本地 Maven 安装，**未打 tag、未发布 Pages**；当前 Pages 发布版本仍是 0.2.3。

- 2026-11-09 依赖复核（§2.10）、A9（§2.11）、A2/A4/A5/A6（§2.12）、A3（§2.13）已提交/推送；A1（§2.14）待提交；带改动文件见 `git status`；上述 5 个未跟踪本机文件未动。
- `.work/gate/**` 为 run-bound 证据，默认不入库；
- 本版未修改 `docs/archive/**`；未修改 HTTP 审批面或发布工作流；没有修改 Simos 仓库。
- 0.2.3 的 Pages 核验见 §4.5。

## 4. 发布历史与 Pages

### 4.1 v0.2.2 发布证据

| 项目 | 事实 |
|---|---|
| 发布 tag | `v0.2.2`，tagger date `2026-10-17 19:50:30 +08:00`；release commit `c46e777...` 的 author/commit date 为 `2026-10-17 19:50:27 +08:00`。 |
| 发布前门禁 | `.work/release-0.2.2-verify.log`，结束于 2026-10-17 19:50:16 +08:00，`BUILD SUCCESS`。 |
| 测试总数 | **1618 tests / 0 failures / 0 errors / 6 skipped**（这是 0.2.2 发布提交的历史证据，不是当前工作树实时测试数）。 |
| 分模块 | AgentLib 674、Workspace 62、Docs 81、Brain 472、Bash 104（6 skipped）、Main 225。 |
| SpotBugs | 6 个模块均为 `BugInstance size is 0`；见同一日志。 |
| 发布范围 | Pages 只发布父 pom + `agentlib-mosire` / `workspace-mosire` / `docs-mosire`；Brain/Main/Bash 不发 Pages。 |

### 4.2 Pages（GitHub Pages 只读 Maven 仓库）

| 项目 | 事实 |
|---|---|
| 仓库地址 | <https://c1589190.github.io/ProjectMosire/> |
| `gh-pages` 最新提交（本批核对） | `ca9d83b4b10bcd9e7f11db8a40c39ce7a21b2289`，提交时间 `2026-10-17 11:56:22 UTC`（`19:56:22 +08:00`），消息 `deploy: c46e777472bfc48c79d9dad0663c8a1c6c54574c`。 |
| 已发布版本目录 | `0.1.0`、`0.2.0`、`0.2.1`、`0.2.2`。 |
| 0.2.2 制品 | 父 pom + AgentLib/Workspace/Docs 三库的 `jar`、`sources.jar`、`pom` 及 `md5`/`sha1`。 |
| 落地页（0.2.2 历史快照） | `index.html` 当时显示 **0.2.2**，链接指向上述三库与父 pom。 |
| 核对方式 | `git ls-remote origin refs/heads/gh-pages` 后 `git fetch --depth=1 origin gh-pages`，再用 `git ls-tree` / `git show FETCH_HEAD:index.html` 核对；**未在浏览器里逐个下载 jar**。 |

### 4.3 发布工作流的已知边界

- `.github/workflows/publish-maven.yml` 在 tag `v*` 或手动 dispatch 时运行；先 `./mvnw -B verify`，再 `deploy` 到 Pages 暂存目录。
- 工作流拒绝 `-SNAPSHOT`、拒绝覆盖 gh-pages 上已存在的版本。
- 用户已裁定**不修改“deploy 再跑测试”的行为**；STATUS 只记录事实，不提出工作流改动。

### 4.4 v0.2.3 发布（2026-10-23）

| 项目 | 事实 |
|---|---|
| 版本 | **0.2.3**（非 SNAPSHOT）；根 pom 与 6 个模块 `<parent>` 一致。 |
| 发布范围 | 父 pom + `agentlib-mosire` / `workspace-mosire` / `docs-mosire`；Brain/Main/Bash 不发 Pages。 |
| 发布前门禁 | run `20261023T143755Z-70901-26515`：1625 tests / 0 failures / 0 errors / 6 skipped；SpotBugs 6 模块 0；`FULL VERIFY PASSED`。 |
| 门禁源码基线 | `790faa6`，worktree `dirty(tracked=12,untracked=5)`；提交后的代码树与该工作树一致，仅追加 STATUS 文本。 |
| 本版变更 | ① `DecisionPackageReadPort` + 五工具端口重载（`0481401`）；② DECIDING 分段表态可回填、`decide_item` 回执只报真实表态（`设计-决策包读取端口.md` §10）。 |
| tag | `v0.2.3`（annotated，指向 release 提交）。 |
| Pages | **已核验**：见 §4.5（gh-pages `3018c3c`，三库 0.2.3 可下载、校验和一致、消费方解析成功）。 |

### 4.5 v0.2.3 Pages 核验（2026-10-23 实测）

| 项目 | 事实 |
|---|---|
| 发布工作流 | run `39972051690`（`publish-maven`，tag `v0.2.3`）全部步骤 success；`verify` run `39972052202`、`docs-skills` run `39972051502`、`pages build and deployment` run `39972893170` 均 success。 |
| `gh-pages` 提交 | `3018c3cc9b7a79402bed67743c68d8b9a9c65452`，`2026-10-23T14:49:11Z`（22:49:11 +08），消息 `deploy: 4ebbf6a02178570744c2eaa7ee25b8211bd80189`。 |
| 0.2.3 制品 | 父 pom + 三库的 `jar` / `sources.jar` / `pom` 及 `md5` / `sha1`，共 31 个文件。 |
| HTTP | 父 pom 与三库 `jar`/`pom`/`sources.jar` 共 10 个 URL 全部 **200**。 |
| 校验和 | 三库 jar/pom 下载后 sha1 与远端 `.sha1` **逐一相等**。 |
| 内容 | 三库 `sources.jar` 与 tag `v0.2.3` 源码 `diff` 无差异（仅生成物 `META-INF`、`version.properties`）；类集合与源码一一对应（143 / 24 / 30，0 差额）。 |
| 版本资源 | `agentlib-mosire-0.2.3.jar` 内 `version.properties` 为 `0.2.3`，且含 `DecisionPackageReadPort.class`。 |
| 公开面 | `javap` 对比 0.2.2 → 0.2.3：只有新增（端口构造器与 `of/all/tools` 重载、`DecisionPackages.verdicts(String)`、`implements DecisionPackageReadPort`），无删除。 |
| 独立消费方 | 独立本地仓库 + 仅 Pages 仓库，`dependency:resolve` 三库 0.2.3：**BUILD SUCCESS**（退出码 0）。 |
| 落地页 | `index.html` 显示 **0.2.3**，链接指向三库与父 pom 的 0.2.3 制品。 |
| 元数据 | 三库 + 父 pom 的 `maven-metadata.xml`：`release=0.2.3`；仍只列当前版本（历史目录保留，metadata 不累积，见 §4.3）。 |

### 4.6 v0.2.4 本地版本与 Maven 本地仓库安装（2026-10-24）

| 项目 | 事实 |
|---|---|
| 版本 | **0.2.4**；根 `pom.xml:9` 与 6 个模块 POM 的 `<parent>` 一致。 |
| 功能提交 | `6cf36d5`（默认上下文压缩 + 测试）。 |
| release commit | 本文件所在的 0.2.4 版本 bump 提交（不另打 tag）。 |
| full gate | run `20261024T061503Z-91459-17843`：`FULL VERIFY PASSED`；1667 tests / 0 failures / 0 errors / 6 skipped；6 模块 SpotBugs 0；`CROSSCHECK log=1667 xml=1667 status=MATCH`。 |
| 本地安装 | `./mvnw -B -ntp -DskipTests -Dspotbugs.skip=true install`：rc=0；父 pom + 6 模块的 `0.2.4` `pom`/`jar`/`sources.jar` 均已进入 `~/.m2/repository/io/mosire/`。 |
| 制品核验 | 7 个坐标目录均存在；`agentlib-mosire-0.2.4.jar`、`brain-mosire-0.2.4.jar`、`main-mosire-0.2.4.jar` 等均存在。 |
| Pages / tag | **未打 tag、未发布 Pages**；当前 Pages 发布版本仍是 0.2.3。 |

## 5. 未验证 / 未做事项

- 0.2.3 的 CI 与 Pages 证据已回填 §4.5（发布工作流、gh-pages、下载校验、独立消费方解析均为实测）。
- 0.2.4 只安装到本机 `~/.m2`；未打 tag、未发布 Pages，故 Pages 上的 `maven-metadata.xml` 仍是 0.2.3。
- **未配置/未运行 japicmp**；0.2.3/0.2.4 的公开面变化未做机器级兼容比对，只由编译、测试与下游源码使用约束覆盖。
- 2026-11-09 依赖复核（§2.10）只验证了本仓编译 + full gate；未跑下游 `~/SimulatorMosire` 消费方，未做 japicmp；MCP 2.2.0-M2 / 3.0 未采用。
- A9 只做了单元/门禁验证：**未接真实 LLM 会话验证注入投递与工具面中途变化**，未验证 GUI/DSH 侧消费；`context.injected` 事件的真实落库顺序只由单测覆盖。
- A2/A4/A5/A6 的 `tool_catalog` 未通过真实 MCP 客户端调用（只有注册/列表与单测）；`resync()` 未在真实 HTTP MCP 会话上验证 `tools/list_changed` 的客户端侧收包。
- A3 的 10 分钟默认值没有真实等待验证（单测用 50ms 配置）；未验证真实浏览器/网络工具被中断后的资源收尾；`tool.executionTimeoutMs` 尚未从配置文件接线。
- A1 未跑 Simos 消费方；Simos 若把 `config.bindAddress()` 配成 `0.0.0.0` 会从“WARN 放行”变成“启动失败”，需显式传 `McpHttpSecurity` 或 `allowInsecure`。未做 OAuth/mTLS/token 轮换；TLS/认证尚未从配置文件接线。
- 未重新跑下游 `~/SimulatorMosire` 消费方测试；Simos 仍钉 `0.2.3-SNAPSHOT`，可在本机改用 `0.2.4` 后验证，且其 `CoreDecisionPackageReadPort` / `CoreDecisionSubmissionPort` 尚未实现。
- **宿主 Skill 的发现/显式触发/隐式触发均未验证**；`.agents/skills/` 不存在，active 工程 Skill 仍为 0。
- 0.2.3 的 full gate 在 `790faa6` + dirty 工作树上通过；manifest 的 `source_sha` 不等于 release 提交 SHA，提交只搬运同一棵树（STATUS 文本除外）。CI 又在 tag `4ebbf6a` 上跑了一轮 `verify` 并通过（run `39972052202`）。
- 迁移台账中的 pending 规则尚未裁定，见 `docs/archive/2026-10-17-agents-rule-migration.md`。

## 6. 下一步

1. Simos 可先把 `agentlib-mosire.version` 从 `0.2.3-SNAPSHOT` 改为本机已安装的 `0.2.4`，实现 `CoreDecisionPackageReadPort`（`find` 读历史、`verdicts` 直接返回 `DecisionPackageSnapshot.verdicts()`、`pending/all` 按 tick 投影）与 `CoreDecisionSubmissionPort`，跑重启/持久化验收。
2. 若要把 0.2.4 发布到 Pages，需用户另行授权打 tag；公开 API 兼容基线从 0.2.2 起，0.2.4 只增不减。
3. `mosire-verify` 已完成 pilot 并判定不激活；不再创建 active Skill。后续只有出现真实 RED 时，才重新提议候选。
4. 评估 handoff 时先看 `STATUS.md` + 模板 + `check-docs.py` 是否足够；不够再考虑候选，不按数量配额创建。
5. 处理规则迁移台账中的 pending 条目；涉及 AGENTS/门禁/发布工作流的变化先走设计或用户裁定。
6. BMA3 的 A1/A2/A3/A4/A5/A6/A9 已实现并各自过了 full gate；A7（outputSchema/structured content）与 A8（异步工具）仍为 P2，未做。

## 7. 当前文档权威关系

| 文档 | 角色 |
|---|---|
| `AGENTS.md` | 常驻纪律、模块边界、红线；顶部指向本文。 |
| `docs/agents/STATUS.md`（本文） | **实时状态入口**；最近验证源码基线、Phase 2 gate、dirty、发布/Pages、未验证、下一步。 |
| `docs/agents/SKILLS.md` | 工程 Skill v2 方案；当前 **0 个 active 工程 Skill**。 |
| `docs/agents/WORKFLOW.md` | 控制方、派单、责任区、评审与收工自查的操作手册。 |
| `docs/agents/VERIFICATION.md` | 并发、gate、Surefire/SpotBugs、run-bound 证据与变异规则。 |
| `docs/agents/ENVIRONMENT.md` | 本机环境、EOL、工具差异与临时数据约束。 |
| `docs/agents/skills-eval/2026-10-17-mosire-verify.md` | `mosire-verify` pilot 的 baseline/skill 对照与“不激活”结论；不是 active Skill。 |
| `设计-决策包读取端口.md` | 本批读口设计、用户原话、验收判据与免责边界。 |
| `开发计划.md` | 范围/顺序与验收判据；不重复实时记账。 |
| 根 `设计-*.md` + `docs/upstream/**` | 机制与上游契约的权威设计。 |
| `docs/archive/**` | 历史归档；不得当实时状态。 |
| `.superpowers/**` | 历史/本机工具路径；**不是权威实时状态**，本仓不依赖它记账。 |
