# D 子调查：既有 Java 实践盘查报告（previous-practice）

> 目标：只读盘查 `/home/cna/DevMosire` 下用户的既有 Java 项目，提炼可复用的架构模式与工程约定，回答"用户已有哪些可复用的零件和习惯"，并映射到目标软件（AgentLibMosire / BrainMosire / MainMosire）。
> 方法：read / grep / glob 只读检查，未执行任何构建，未修改 ~/DevMosire 下任何文件。
> 引用约定：文中相对路径均相对于 `/home/cna/DevMosire/`。

---

## 1. 项目总览表

| 项目 | 定位 | 模块 | 与目标软件的关联度 |
|---|---|---|---|
| **iBotMosire** | 自用智能网关：手搓 MCP 服务（Streamable HTTP，2026-07-28 规范优先）+ Agent 生命周期管理 + 多账号 NapCat/OneBot 桥接 + QQWS 独立终端进程 | `ibotmosire-core` / `ibotmosire-napcat` / `ibotmosire-agent` / `ibotmosire-gateway` / `ibotmosire-qqws`（5 模块） | **极高**。目标软件"主 Agent + 子 Agent + 权限 + 自管理 + 轻量"的最近似前身 |
| **GSimulator** | 多 Agent 回合制推演引擎：ToolLoop Agent 运行时 + 工具注册/权限/分组体系 + 手写 MCP stdio + 事件总线 + 子 Agent 派发 | `gsim-docslib` / `gsim-agentsmanager` / `gsim-core` / `gsim-app` / `gsim-map`（5 模块） | **极高**。`gsim-agentsmanager` 是事实上的"Agent 运行时库"，可直接作为 AgentLibMosire/BrainMosire 的底稿 |
| **GoatMosire** | 六边形地图编辑器 + MCP 桥：HTTP 编辑器 + 手写 MCP stdio + 内嵌 GSimulator API 三合一 | 单模块 fat JAR | **高**（MCP stdio 复用件、单进程多入口组装、轻量 HTTP 服务范式） |
| **PlayerMosire** | 纯 Java Minecraft 身体（bot）客户端研究项目：MCProtocolLib 路线 + 数据源证据登记 | `environment-api` / `minecraft-body` / `player-app`（3 模块） | 中（不直接复用代码，但"证据驱动研究 + 制品固定 + 许可清点"的方法论可迁移） |
| **WDMosire** | NapCat OneBot v11 QQ bot：Java-WebSocket、echo 关联器、MockNapCatServer、从手写 MCP 迁移到 GSimulator AbstractMcpServer | 单模块 `wdm-bot`（依赖 gsim-agentsmanager） | 中（连接管理/协议经验/MCP 版本协商经验） |
| **MCWDMosire** | Paper 服务端观察插件：把 MosireProbe 的动作用 JSONL 记录为 PlayerMosire 测试 oracle | 单模块 `mosire-observer`（paper-api provided） | 低（测试基建思想可参考） |
| **GameMosire** | 早期游戏 bot 骨架：ChatAdapter 接缝 + CommandDispatcher | 单模块 | 低（iBotMosire 架构文档点名借鉴其"干净接缝"） |
| **TanwenResearch** | 人物语料研究仓库：Agent-Skills 风格的 SKILL.md 实践 + 固定分析协议文档 | 非 Java | 中（skills 打包习惯、协议文档习惯可直接沿用） |

---

## 2. 共性工程约定（跨项目验证）

以下约定在至少两个项目中反复出现，可视为用户的"习惯"而非偶然：

### 2.1 语言与构建

- **Java 21 全线统一**：`maven.compiler.source/target=21`（iBotMosire/pom.xml、GSimulator/pom.xml、WDMosire/pom.xml、GameMosire/pom.xml）或 `maven.compiler.release=21`（MCWDMosire/pom.xml）。iBotMosire 另加 `-Xlint:all,-processing,-options -Werror`（警告即失败）。
- **Maven 多模块 + 单向依赖**：父 POM `dependencyManagement` 统一版本；模块依赖方向被写成硬性禁令（如 iBotMosire/docs/ARCHITECTURE.md §3：`core ← napcat/agent ← gateway`，qqws 只依赖 core+napcat，"避免进程间共享 Agent 状态"；GSimulator/CLAUDE.md：core 不得依赖 agent，判定方式为"源码 import 零命中 + pom 无依赖边"）。
- **不上 Spring Boot**，纯 JDK：`com.sun.net.httpserver` + 虚拟线程是标准 HTTP 配方（GoatMosire/ARCHITECTURE.md 明确"no Spring"；iBotMosire/docs/ARCHITECTURE.md §13 "JDK HttpServer + 虚拟线程执行器（WDMosire 已验证模式）"）。
- **发行方式 = maven-shade-plugin 单 fat JAR**（GoatMosire/ARCHITECTURE.md；iBotMosire 父 POM pluginManagement），外部 `config/` 目录带 `.template` 模板文件、不随 jar 分发（iBotMosire/config/ibotmosire.json.template）。

### 2.2 依赖最小化（"轻量化"的具体体现）

跨项目依赖面收得很窄，核心三件套反复出现：

- **Jackson**（2.17.2 / 2.18.2）——唯一序列化方案；
- **OkHttp 4.12.0**——HTTP/WebSocket 客户端（唯一例外：WDMosire 用 vendored Java-WebSocket 直连 NapCat）；
- **SLF4J + Log4j2**（2.24.x）——唯一日志方案，外部 `log4j2.xml` + 系统属性覆盖（`${sys:log.level:-INFO}`、`${sys:ibot.log.dir:-logs}`，见 DevMosire/config/log4j2.xml 与 iBotMosire/core/.../logging/LogConfigBootstrap.java 的"WDMosire 模式"）。
- GSimulator 因业务需要额外引入 jline 3.27.1（CLI）、jsoup、sqlite-jdbc、thymeleaf（GSimulator/pom.xml）；iBotMosire 明确"claude-code-sdk、opencode 客户端仅挂在 agent module，core 零框架依赖"（iBotMosire/docs/DEVELOPMENT_PLAN.md P0）。

### 2.3 代码风格与质量门禁

- **三件套 verify 流水线**：Spotless（Palantir Java Format 2.50.0）→ Checkstyle（10.26.1，gsim_checks.xml/ibotmosire_checks.xml）→ SpotBugs（effort=Max，threshold=Medium）。GoatMosire/ARCHITECTURE.md 记载"0 Checkstyle 警告强制、0 SpotBugs High"；iBotMosire 父 POM 中 checkstyle `failOnViolation=false` 仅告警。
- **DTO 一律 Java record**，compact constructor 防御性冻结（`List.copyOf`/`Map.copyOf`）：GoatMosire MapData（GoatMosire/ARCHITECTURE.md §Data Model）、GSimulator GSimEvent（GSimulator/gsim-agentsmanager/.../event/GSimEvent.java）。
- **中文 Javadoc + 决策记录**：每个项目都有 docs/ARCHITECTURE.md + docs/DEVELOPMENT_PLAN.md 双文档；决策写进决策日志表（iBotMosire/docs/ARCHITECTURE.md §15"决策日志"，标注每个设计决定的来源）。
- **离线可测**：测试禁依赖外部服务——FakeAgentAdapter / FakeLlmManager / MockNapCatServer（iBotMosire-agent/adapter/FakeAgentAdapter.java、GSimulator 测试目录、WDMosire 的 MockNapCatServer）。

### 2.4 配置、健康检查与进程拓扑

- 配置 = 外部 JSON（`config/*.json`）+ 系统属性 `-D` 覆盖 + 环境变量兜底（GoatMosireConfig：`goatmosire.port`/`GOATMOSIRE_PORT` 双通道，GoatMosire/ARCHITECTURE.md §Configuration）。
- `/health` 端点暴露进程存活 + 连接/会话状态，"状态查询 = 最低权限"（iBotMosire/docs/ARCHITECTURE.md §13）。
- 关键进程隔离用**独立进程**实现：QQWS 与 Gateway 分离的理由是"system 级 Agent 不能接触 Client 终端账号连接"（iBotMosire/docs/ARCHITECTURE.md §2、§7.3"账号保留"）。

---

## 3. MCP 实践盘点

### 3.1 手写实现到什么程度

用户至少**三代手写 MCP**，全部"JSON-RPC 2.0 手搓、零官方 SDK 依赖"：

1. **GSimulator（最成熟）**：`gsim-agentsmanager/src/main/java/com/gsim/agentsmanager/mcp/` 是一套可复用框架：
   - `AbstractMcpServer.java`：stdio 主循环（行分隔 JSON）、`initialize`（协议版本 `2024-11-05` 硬编码）、`notifications/initialized`、`tools/list`、`tools/call`；JSON-RPC 标准错误码（-32700/-32600/-32601/-32602/-32603）+ 自定义码（-32000 工具执行异常、-32001 依赖未就绪）；模板方法 `getServerName/getServerVersion/getAllTools/executeTool`；多注册表按序路由（`UnknownToolException` 表路由失败）；内置 `[MCP-REQ]/[MCP-RES]/[MCP-TOOL]` 结构化日志。
   - `McpTransport.java` 接口 + `StdioMcpTransport.java`（`CloseShieldInputStream` 保护 System.in 不被关闭、测试可注入流）；另有 `McpHttpServer.java`（HTTP+SSE 传输）。
   - `ToolDef.java`（name/description/schema）、`McpToolRegistry.java` 接口、`CompositeMcpToolRegistry.java`、`ToolRegistryMcpAdapter.java`（把内部 ToolRegistry 适配为 MCP 面：自动注入 `worldId` 必填 schema、分页 `_page/_pageSize/_hasMore`、`gsim_` 前缀、`ToolResultOverflowHandler` 超限改写 + `McpResponseConfig.maxJsonBytes` 截断兜底）。
   - 兼容细节：处理 Claude Code 的**双层参数包裹** `{"arguments":"<json-string>"}`（AbstractMcpServer.java `handleToolCall` 注释）。
2. **GoatMosire**：独立重写版 `McpServer.java` + `McpToolRegistry.java`，同 2024-11-05，文档 MCP_GUIDE.md 完整记录了 51 个工具的协议交互。
3. **iBotMosire**：最激进的一代，手搓 **2026-07-28 规范**的 Streamable HTTP（`ibotmosire-core/src/main/java/com/ibotmosire/core/mcp/`）：单 POST 端点 + `Mcp-Method`/`Mcp-Name` 头校验（-32020 头体不一致拒绝）+ 无状态 Bearer 逐请求鉴权 + `subscriptions/listen` 真推送（SSE）+ `tools/list_changed` 动态注册推送；同时保留 2025 `initialize` 握手回退。总配置 `mcp.mode = hand-rolled | sdk` 预留一键切换（iBotMosire/docs/ARCHITECTURE.md §6）。

### 3.2 可复用件清单

| 可复用件 | 位置 | 迁移建议 |
|---|---|---|
| AgentTool 接口 + ToolRegistry（含变更监听） | GSimulator/.../tool/AgentTool.java、ToolRegistry.java；iBotMosire-core/.../tool/ 同名三件 | 直接进 AgentLibMosire（内部工具抽象） |
| ToolRegistryMcpAdapter（schema 注入/分页/前缀/溢出处理） | GSimulator/.../mcp/ToolRegistryMcpAdapter.java | 适配层模式保留；协议实现替换为官方 SDK |
| ToolResultOverflowHandler + McpResponseConfig | GSimulator/.../mcp/ | 保留（大结果转存 docId 是自研亮点） |
| StdioMcpTransport（CloseShieldInputStream、测试注入） | GSimulator/.../mcp/StdioMcpTransport.java | 若官方 SDK 传输不满足测试需求可借鉴 |
| iBotMosire 的 McpHttpServer + SubscriptionManager + 权限过滤 tools/list | iBotMosire-core/.../mcp/ | 2026 规范语义参考实现；建议最终换成官方 SDK |

### 3.3 与官方 Java SDK 的差距（重要）

- **协议版本滞后**：GSimulator/GoatMosire 停在 2024-11-05（AbstractMcpServer.java:439 硬编码，无版本协商）；iBotMosire 手搓 2026-07-28 但仅覆盖 tools 面（initialize/tools/list/tools/call/subscriptions），**未实现 prompts、resources、sampling、roots、logging、completion、elicitation、streamable HTTP 会话管理**等完整能力面。
- **客户端侧**：iBotMosire 只有自写 `McpClient`（core/.../mcp/McpClient.java）供 QQWS 用，无通用 MCP 客户端 SDK。
- **无官方 SDK 依赖**：三个项目 pom 中均无 `io.modelcontextprotocol` 系坐标（MCP 官方 Java SDK 自 2025 末起已可用，且 00 号摘要确认其为 Tier 官方路线）。
- **教训沉淀**：WDMosire/docs/MIGRATION_PLAN.md 记录了"手写协议栈如何迁移"——新建 `WdmMcpServer extends AbstractMcpServer`，override `handleInitialize` 做**版本封顶回显**（客户端 ≤2025-11-25 回显、更高回 2025-11-25），批评 GSimulator "硬编码 2024-11-05 且无协商"。iBotMosire/docs/ARCHITECTURE.md §16 亦记录"官方 Java SDK 更新到 2026 规范后切 sdk"的既定计划。

**结论**：手写 MCP 是历史负担而非资产——可复用的是"Registry 与协议层解耦"的**架构原则**（iBotMosire 明言"内部 Agent 与外部 Agent 走同一条 MCP 路，不做进程内兼容层"，docs/ARCHITECTURE.md §1），协议实现本身应换成官方 SDK。

---

## 4. 权限与工具管理盘点

### 4.1 GSimulator：四类分类 + 确认门 + 最终可信边界（三代演进）

- **第一代 `ToolCategoryRegistry`**：集中静态 Map 维护"工具名→分类"（READ_ONLY / MUTATING / DESTRUCTIVE / CONTROL），未知工具保守归 MUTATING（GSimulator/.../ToolCategoryRegistry.java）。
- **第二代 `ToolExecutionPolicy`**：弃用静态表，改为**工具自带声明** `AgentTool.permission()`，枚举 `SELF < READ < WRITE < SYSTEM`（默认 READ 保守）；规则链：不在 allowedTools→REJECT → SELF 始终允许 → READ 放行 → SYSTEM 需确认（MCP 入口免确认）→ WRITE 需确认（除非本轮已授权全部）；拒绝/回灌 prompt 有统一文本模板（GSimulator/.../ToolExecutionPolicy.java）。
- **第三代 `ToolExecutionGuard`**：真正的**执行入口最终可信边界**——检查存在性 → `mcpExposed`（未暴露视为不存在）→ `alwaysAvailable` → 工具组激活状态 → `AgentConfig.allowedToolGroups`（防权限提升）→ Permission 校验；区分 AGENT surface 与 MCP surface 两条检查路径（GSimulator/.../tool/ToolExecutionGuard.java，注释明言"不能认为没注入给模型就等于无法执行"）。
- **`CliToolPermissionGate`**：确认门接口 `ToolPermissionGate.askConfirmation(ToolConfirmationRequest)` 的 CLI 实现——终端阻塞式菜单，Y=允许一次 / A=本轮全部允许（仅写入类）/ N=拒绝，默认回车=Y（GSimulator/.../CliToolPermissionGate.java）；另有 `AutoApprovePermissionGate`。**这是 GSimulator 的"审批流"，与 iBotMosire 的"无审批流"恰成对照**。
- **工具分组（ToolGroup）**：工具可声明属多组（`AgentTool.toolGroups()`），`activate_tool_groups` 是 CONTROL 类自管理工具；AgentConfig 有 `defaultActiveToolGroups`（GSimulator/.../ToolGroupManager.java、AgentConfig.java）。

### 4.2 iBotMosire：三级身份 × 三要素工具声明，无审批流

- 客户端身份 = Bearer token → `guest/default/system` 三级（总配置 tokens 映射，逐请求校验）；工具声明三要素：`requiredLevel` + `sensitive`（guest 不可见不可调）+ `destructive`（仅 system 可调）（iBotMosire-core/.../tool/AgentTool.java、PermissionGuard.java）。
- **拒绝即终局**：无暂停/人工确认；拒绝文本模板明示"不要重试此工具。若无法完成任务，请结束当前对话"（iBotMosire/docs/ARCHITECTURE.md §5.3）。tools/list 按等级过滤（可见性优化），tools/call 才是唯一授权边界。
- **硬规则**：不存在任何能关闭 iBotMosire 进程的工具，即使 system 级也没有（§5.1）。

### 4.3 对目标软件的启示

两套权限模型的组合拳值得直接吸收：**AgentTool 接口自声明权限（Permission/requiredLevel+sensitive+destructive）+ 执行入口统一 Guard + 配置化 allowedToolGroups/allowList/maxPermission + 可插拔 PermissionGate**（CLI 确认门 / AutoApprove / 未来的 AG-UI HITL 门）。"最终可信边界在执行入口而非注入面"是 GSimulator 用测试钉死的一条原则（OrchestratorPermissionTest、ToolExecutionGuardTest）。

---

## 5. 事件总线与配置管理盘点

### 5.1 GSimulator 事件总线（可直接映射 AG-UI 的底子）

- **`EventBus`**：线程安全发布/订阅，`CopyOnWriteArrayList<EventSink>`；`publish` 先 `sink.accepts(event)` 过滤再 `accept`，避免全局广播；单 sink 异常隔离；`shutdown` 依次 close（GSimulator/.../event/EventBus.java）。
- **`GSimEvent`**：统一事件 record `(sessionId, taskId, type, time, data)`，data 防御性拷贝；**事件词汇表已接近 AG-UI**：`command_started/done/error`、`log`、`run_stage`、`tool_started/tool_done/tool_error`、`llm_started/llm_delta/llm_reasoning_delta/llm_done`、`result`、`done`（GSimEvent.java 注释）。
- **CLI 与 HTTP SSE 消费同一条流**：`ConsoleEventSink` / `SseEventSink` / `EventBusAgentProgressSink` / `TaggedAgentProgressSink`（按 instanceId/taskId 打标签转发）。
- iBotMosire 侧更轻：`AgentEvent`（status/output/turn_end）+ `AgentManager.setEventForwarder` 推流给 QQWS（iBotMosire-agent/AgentManager.java）。

### 5.2 AgentConfigManager：自管理子 Agent 的直接先例

- Agent = `agents/*.json` 一个文件；字段：`agentId / llmProvider / staticSystemPrompt / userTemplate({{变量}}) / toolFilter{mode:all|read_only|none|custom:allow:deny} / maxToolRounds / temperature / maxTokens / maxPermission / allowList / defaultActiveToolGroups / queryScope`（GSimulator/.../AgentConfig.java、agents/_README.txt）。
- `AgentConfigManager`：运行时 CRUD + **原子写入**（写 `.tmp` → `ATOMIC_MOVE` → `configStore.reload` 立即生效）；字段校验（temperature 0-2、agentId 正则）；**内置 orchestrator 配置禁止删除**（GSimulator/.../config/AgentConfigManager.java）。
- 对应 MCP 工具组：`create/update/delete/list_sub_agent_config`、`list_llm_providers` —— **主 Agent 通过工具创建/修改/删除子 Agent 配置，这正是"Agent 自管理"的现成实现**（GSimulator/.../tool/ 下 CreateSubAgentConfigTool.java 等）。
- 子 Agent 运行：`dispatch_sub_agent` 异步派发（后台虚拟线程）→ `collect_sub_agent_results` / `view_sub_agent_output` / `stop_sub_agent`；`AgentsManager` 门面用 `CompletableFuture` + `ConcurrentHashMap` 管生命周期，`parentInstanceId` 区分父子（GSimulator/.../management/AgentsManager.java、DispatchSubAgentTool.java）。

### 5.3 LLM 接入与记忆

- **LLM 统一封装**：`LlmClient` 接口（依赖反转，agentsmanager 定义、core 实现）+ `LlmProviderRegistry` + `llms.json` 配置；**手写 SseParser** 兼容 OpenAI 式流（content/reasoning_content/tool_calls）+ 国产变体（message/text）+ JSON fallback（GSimulator/.../llm/SseParser.java）。
- **ToolLoop 反幻觉护栏**：native tool_calls 优先、文本 fallback 提取；`validateFinishActionMessage` 拦截伪造 `[TOOL_RESULT]`/占位符；空输出连续 3 轮中止；缓存回放时剥离孤儿 tool_call 消息（GSimulator/.../core/AbstractAgent.java）。
- **工具结果溢出治理**：snippet 超限先尝试 `DocStaging` 暂存为临时文档、给 LLM 回"已暂存 docId"提示，失败才截断（AbstractAgent.buildToolFeedback + ToolResultPolicy）——防上下文爆炸的成熟做法。
- **记忆/会话**：SubAgent 对话缓存 `CacheSession/CacheStore`（write-through messageSaver，每条新消息即时落盘）；iBotMosire 则是"Agent 自管上下文"——靠 claude `--resume` + 从 stdout 捕获 `session_id` 实现续接（iBotMosire-agent/adapter/SubprocessAgentAdapter.java `SESSION_ID_PATTERN`）。
- **子进程监督**（进程级 Agent 集成）：ProcessBuilder + stdout/stderr 各一虚拟线程 + SIGTERM→超时→`descendants().destroyForcibly()` 进程树清理 + maxTurns/timeBudget 防跑飞（SubprocessAgentAdapter.java）；ClaudeCodeAdapter 注入 `--mcp-config` 指向自家网关形成闭环。

---

## 6. 其他项目要点

### 6.1 PlayerMosire：证据驱动研究方法论

- docs/RESEARCH.md：严格区分"用户已定要求 / 源码与构建事实 / 现场实测 / 设计建议"，不把参考项目 README 的能力声明当测试结果；制品**固定时间戳快照**而非漂移 SNAPSHOT；**许可清点**（MCProtocolLib MIT 但传递依赖 MinecraftAuth LGPL-3.0，Baritone LGPL-3.0，SoulFire AGPL-3.0+Java25 → 排除）。
- docs/data-sources.md：静态数据**唯一事实登记表**（URL 固定到版本 + 实测 SHA-256 + 许可证列），大文件不入库、按需下载校验后加载；明确禁止"registry 数据当完整语义""未知方块静默兜底为空气"。

### 6.2 TanwenResearch：Agent Skills 与协议文档实践

- `skills/tanweng-persona/SKILL.md`：**标准 Agent Skills 结构**——YAML frontmatter（name/description）+ `references/`（渐进式披露：persona_brief/style_guide/correction_ledger）+ `scripts/`（query_corpus.py、validate_persona.py 自检脚本）+ 硬边界声明（不做什么）+ 输出自检流程。GSimulator 的 `skills/`（dual-character-sim、gsim-integration-test 的 SKILL.md 带 category/tags frontmatter）同样是此类实践。
- `logs/protocol.md`：把"分析协议"写成**固定参照文档**（总原则 + 强制输出栏 + 15 步流程 + 证据分级），让多个批次的 Agent 工作有一致约束——这是"用协议文档治理 Agent"的习惯。

### 6.3 WDMosire / MCWDMosire / GoatMosire 补充

- WDMosire：echo 关联器（AtomicLong seq + ConcurrentHashMap<Long, CompletableFuture> + 超时清扫 + 断连 failAllPending）被 iBotMosire 直接升级复用；docs/DEVELOPMENT_PLAN.md 是 NapCat 协议实地探测记录（message_id/real_seq、forward 消息递归展开、群历史滚动窗口）。
- MCWDMosire：服务端观察者把客户端动作用 **JSONL 记录为 oracle**，供客户端测试对账——"只读对账器"思路。
- GoatMosire：单进程三入口组装（HTTP 编辑器 + MCP stdio + 内嵌 GSimulator API 后台线程，`--http-only/--mcp-only/--noGsim` 开关）；LRU 缓存套路（LinkedHashMap + removeEldestEntry + synchronizedMap）；设计原则"anti-CLI-as-HTTP（handler 直调 Service 不拼命令）""@Deprecated 保留废弃代码"（GoatMosire/docs/TECHNICAL.md）。

---

## 7. 对目标软件的启示

### 7.1 可直接迁移为底子的部分

| 目标模块 | 迁移来源 | 内容 |
|---|---|---|
| **AgentLibMosire** | GSimulator/gsim-agentsmanager 的 `tool/` + `event/` 包；iBotMosire-core 的 `tool/` 包 | AgentTool（含 Permission 自声明 / requiredLevel+sensitive+destructive 三要素）、ToolRegistry（含变更监听）、ToolExecutionGuard（最终可信边界）、ToolGroupManager、ToolExecutionPolicy、EventBus/GSimEvent/AgentProgressSink（AG-UI 事件词汇表的雏形） |
| **BrainMosire** | gsim-agentsmanager 的 `core/AbstractAgent.java`（ToolLoop）+ `OrchestratorAgent.java` + `management/AgentsManager.java` + `llm/` 包 + iBotMosire-agent | ToolLoop 反幻觉护栏、LLM 依赖反转封装、异步子 Agent 派发/收集/停止、AgentConfigManager 原子写入自管理配置、子进程 Agent 适配（ClaudeCode/OpenCode/Fake 三例）、防跑飞限额、工具结果溢出暂存 |
| **MainMosire** | iBotMosire-gateway（组装/启动时序/health）+ iBotMosire-core（ConfigLoader/LogConfigBootstrap/McpHttpServer）+ GoatMosire（StaticFileHandler、开关式入口） | 进程拓扑、外部配置 + 模板 + 系统属性覆盖、日志引导、token→权限映射、健康检查、单 fat JAR 发行 |
| **工程习惯** | 全项目 | Java 21 + 纯 JDK + 虚拟线程；Jackson/SLF4J+Log4j2/OkHttp 最小依赖集；Spotless→Checkstyle→SpotBugs 门禁；record + 防御性拷贝；中文 Javadoc + ARCHITECTURE/DEVELOPMENT_PLAN 双文档 + 决策日志表 |
| **Skills/AGENTS.md 实践** | TanwenResearch/skills、GSimulator/skills、GSimulator/CLAUDE.md、GSimulator/AGENTS.md | SKILL.md frontmatter + references 渐进披露 + 校验脚本；AGENTS.md 作为外部 Agent 接入指南 |

### 7.2 必须新做 / 不能直接照搬的缺口

1. **A2A、AG-UI、ACP 完全空白**：三个项目均无任何实现；GSimEvent 的事件词汇表只能算 AG-UI 的语义底稿，需按规范映射事件类型/协议层。A2A 的 Agent Card/任务/artifact、ACP 的编辑器会话面需从零引入官方 SDK（iBotMosire/docs/ARCHITECTURE.md §16 仅把 ACP 列为"v2 预留"）。
2. **官方 SDK 替换手写 MCP**：手写协议停在 2024-11-05（GSimulator/GoatMosire）或 2026-07-28 的 tools 子集（iBotMosire）；缺 prompts/resources/sampling/完整 streamable HTTP。按用户既定"mcp.mode=sdk 可切换"思路，用官方 Java SDK 替换传输与协议层，保留 ToolRegistry→SDK 适配器。
3. **进程级沙箱**：现有隔离 = 独立进程 + 权限门（QQWS 模式），但 `claude -p` 子进程仍以同用户权限跑在主机上，GSimulator 子 Agent 甚至是进程内虚拟线程——没有 OS 级沙箱（容器/bwrap/目录 jail/网络限制）。目标软件"可自管理 + 不同权限子 Agent"需要把 GSimulator 的进程内子 Agent 升级为可隔离的进程级执行。
4. **身份与授权**：只有静态 Bearer token → 等级映射（iBotMosire）；无 OAuth/OIDC、无细粒度 per-Agent 凭证、无审计追踪。若做多租户/多子 Agent 需要新设计。
5. **持久化与记忆**：均为 JSON 文件/环形缓冲/会话缓存级别的轻方案；没有统一事件存储（00 号摘要自研核心"Universal Event Store"在此是空白），sqlite-jdbc 仅在 GSimulator 部分业务使用。
6. **自管理闭环的权限缺口**：GSimulator 的子 Agent 配置 CRUD 工具本身没有"谁可以改谁的配置"的边界（改动即全量 reload）；目标软件需要把 AgentConfigManager 的 CRUD 纳入 Guard 分级。
7. **多进程协同基础设施**：iBotMosire 双进程靠 MCP + 自研推流，无进程间生命周期管理（拉起/心跳/优雅退出）通用件——MainMosire 需要一套进程监督层。

### 7.3 一句话总结

用户手里已经有一个**可运行的"主 Agent + 权限化子 Agent + 手写 MCP + 事件流 + 自管理配置"的原型集群**（GSimulator 的 ToolLoop/权限/子 Agent 体系 + iBotMosire 的进程拓扑/2026 MCP/CLI-Agent 适配）；把它提炼成 AgentLibMosire/BrainMosire/MainMosire 的主要工作是**剥离业务耦合、以官方 SDK 替换手写协议、补上 A2A/AG-UI/ACP 与进程级沙箱**，而不是从零设计。
