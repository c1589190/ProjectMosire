# 参考项目架构调查报告（Reference Architecture）

> 子调查 B：对 12 个参考项目的架构层面调查分析。
> 调查日期：2026-09-08（模拟日期）。调查方法：GitHub 仓库页面 / README / docs / 源码树（GitHub API、raw.githubusercontent.com、jsDelivr CDN）+ web_search 补充；本调查开始时 `Research/repos/` 下仅存在 modelcontextprotocol 一个克隆（未完成/未使用），故未依赖本地仓库，全部基于网络抓取。
> 标注规则：凡未在本调查抓取到的仓库内容中直接看到、而是来自文档、社区或模型既有知识的说法，一律标注"据文档 / 据社区说法，未核实源码"；没有编造文件路径或功能。
> 本报告是调查报告，不是开发计划。

## 0. TL;DR（给 Java 多模块 Agent 软件的核心结论）

1. **许可证**：12 个项目中 9 个是纯 MIT / Apache-2.0（可放心借鉴设计）；**n8n（Sustainable Use License）、Dify（Apache-2.0 修改版 + 多租户/LOGO 附加条件）、Open WebUI（BSD-3 + 品牌保护条款）有附加条件**，只能借鉴设计不能抄代码。详见附录 A。
2. **Agent loop 主流范式**：自由 tool-calling 循环（OpenCode/Codex/Goose/Hermes）是个人 Agent runtime 的事实标准；OpenHands 用事件流（event-sourced）做状态源；n8n/Dify 用工作流 DAG；MetaGPT 用 SOP 流水线。**推荐主循环 = tool-calling 循环 + 事件流记录**。
3. **子 Agent 最成熟的范式**：OpenCode 的"agent 模板（JSON/Markdown）+ permission 映射（allow/ask/deny per 工具）+ child session 树 + 只读子 agent"——这几乎是"自由创建不同权限子 Agent"的现成答案。
4. **上下文管理**：compaction/summary 做成**隐藏的系统 agent**（OpenCode）、写入式记忆 memory blocks（Letta）、FTS 全文检索 + LLM 摘要跨会话召回（Hermes）。Hermes 的"prompt cache 不变量"（历史上下文只在压缩时可变）是成本工程的关键教训。
5. **UI 解耦最小集**：OpenCode 的 headless HTTP server + OpenAPI 3.1 + SSE 事件流 + 权限请求走 HTTP 端点是可直接照抄的架构。
6. **自管理有先例且可行**：Letta V2 把"agent 改写自身记忆/技能/提示词"当卖点（MemFS 用 git 管上下文，天然可回滚）；Codex/OpenCode 支持运行时增删 MCP；n8n 公开 REST API、Open WebUI 官方文档明言模型配置"可由 AI agent 通过脚本管理"。安全闭环 = schema 校验 + 原子写 + 版本化回滚 + 高风险变更走权限请求 + 审计。
7. **轻量化**：Rust/Bun 的单二进制红利 Java 无法完全照搬，但有对应物（jlink + AppCDS/CRaC + GraalVM native-image）；真正的轻量思路是**小 core + headless + 协议化扩展（MCP 子进程）+ 前端可选**。
8. **License 之外的现实提醒**：Letta 主仓库已从 Python V1 服务器转型为 TypeScript 的 letta-code（V1 归档在 archive 分支）——参考项目本身也在剧烈演化，抄"架构"比抄"代码"更安全。

---

## 1. Hermes Agent（NousResearch/hermes-agent）

### 1.1 语言/技术栈与许可证
- Python 3.11 + uv（Rust 包管理器）打包；SQLite（WAL）+ FTS5；LLM 接入三种 API 模式（chat_completions / codex_responses / anthropic_messages）；MCP 客户端；25+ 消息平台适配器；ACP 适配 VS Code/Zed/JetBrains。
- **许可证：MIT**（Copyright 2025 Nous Research），无附加条件。[LICENSE](https://github.com/NousResearch/hermes-agent/blob/main/LICENSE)

### 1.2 运行时架构
- 同步编排引擎 `AIAgent`（循环在 `agent/conversation_loop.py` + `agent/turn_*.py`）：每轮组装 system prompt（stable→context→volatile 三层）→ 可中断 API 调用（后台线程+中断事件）→ tool_calls 经工具注册表分发（多调用 ThreadPoolExecutor 并发、结果按原序回填）→ 回填历史并循环（据官方架构文档，源码未逐行核实）。
- 状态建模：SQLite `~/.hermes/state.db`（sessions/messages/三张 FTS5 表等，schema v23+；写竞争 BEGIN IMMEDIATE + 抖动重试）。
- 上下文管理：>50% 窗口预压缩、gateway 85% 自动压缩；有损摘要保留末 N 条（`compression.protect_last_n` 默认 20）、tool 对不拆分、压缩产生子会话 lineage；Anthropic 前缀缓存（`prompt_caching.py`）。
- 设计铁律见 [AGENTS.md](https://github.com/NousResearch/hermes-agent/blob/main/AGENTS.md)：**"core is a narrow waist; capability lives at the edges"**；prompt 缓存神圣不可侵犯（唯一例外是压缩）。

### 1.3 子 Agent / 委派模型
- `delegate_task` 工具生成子 `AIAgent`：全新会话（对父上下文零知识，只靠 goal/context，但注入 AGENTS.md/CLAUDE.md 等工作区上下文）；继承父 toolsets 但**硬禁** delegate_task（防递归叶子）/clarify/memory/send_message/cronjob；独立迭代预算（默认 50）与独立终端会话；默认 3 并发；支持 orchestrator 嵌套委派（`max_spawn_depth` 限深）；父 agent 可 list/steer/stop 子 agent；后台子 agent 有 stall 监视。
- 隔离：**同进程线程池**（非沙箱）；实际隔离取决于 terminal backend（local/docker/ssh/singularity/modal/daytona/vercel_sandbox 七种，docker 后端时父子共享同一持久容器）。[delegation 文档](https://hermes-agent.nousresearch.com/docs/user-guide/features/delegation)

### 1.4 权限与批准模型
- `approvals.mode` = smart（辅助 LLM 判险）/manual/off；YOLO 模式绕过除硬黑名单外全部检查；`UNRECOVERABLE_BLOCKLIST` 常开（rm -rf /、fork bomb 等，无覆盖开关）；CLI 四选项 once/session/always/deny；超时默认 300s **fail-closed**；cron/无人值守默认 deny。
- 文件写入硬阻断凭据目录（~/.ssh、.env、auth.json、mcp-tokens 等）；可选 `HERMES_WRITE_SAFE_ROOT` 写沙箱；Docker 后端加固：--cap-drop ALL、no-new-privileges、pids-limit 256。[security 文档](https://hermes-agent.nousresearch.com/docs/user-guide/security)

### 1.5 事件/流式设计
- **无内部 SSE/事件总线**，用回调面解耦（tool_progress/thinking/stream_delta/status 等 callbacks），同一 `AIAgent` 服务 CLI/gateway/ACP/batch/API server——官方称"平台无关核心"。
- gateway 是长驻进程：各平台适配器把原始事件归一化为 MessageEvent → 统一 `_handle_message()` 路由（session key：`agent:main:{platform}:{chat_type}:{chat_id}`）→ `gateway/delivery.py` 跨平台投递；实验性 relay 走 WebSocket。[gateway 文档](https://hermes-agent.nousresearch.com/docs/developer-guide/gateway-internals)

### 1.6 配置与自管理
- `~/.hermes/config.yaml` + `.env`（密钥）+ `auth.json`；优先级 CLI > config.yaml > .env > 默认；`hermes config get/set/edit`（密钥自动路由到 .env）；配置 mtime 键控缓存、修改即时生效。
- MCP 在 `config.yaml` 的 `mcp_servers` 下，编辑后自动重连 + `/reload-mcp`（默认需确认）；`hermes mcp catalog/install` 一键安装经 Nous 审核的 MCP（manifest 在 `optional-mcps/`，PR 合入即审核）。
- 上下文文件自动发现：.hermes.md > AGENTS.md > CLAUDE.md > .cursorrules；skills 兼容 agentskills.io；MEMORY.md/USER.md 持久记忆；cron jobs 必须经 cronjob 工具改。
- **Agent 能否改自身配置**：config.yaml 可改（无写保护），但 .env/auth.json/mcp-tokens 等凭据文件被写保护硬阻断。

### 1.7 扩展机制
- `tools/*.py` 导入时自注册；插件三发现源（`~/.hermes/plugins/`、`.hermes/plugins/`、pip entry points），三类插件（工具/hook、记忆提供者、上下文引擎）；gateway hooks（HOOK.yaml + handler.py）；平台插件 `plugins/platforms/<name>/`；任意 MCP server。

### 1.8 轻量化手段
- uv 自包含安装（一行 curl）；README 称"可跑在 $5 VPS、不绑定笔记本、可跨 Telegram 使用"；Windows 自带便携 Git Bash。未找到官方启动时间/内存数据。

### 1.9 引用
[README](https://github.com/NousResearch/hermes-agent/blob/main/README.md)、[架构文档](https://hermes-agent.nousresearch.com/docs/developer-guide/architecture)、[Agent Loop](https://hermes-agent.nousresearch.com/docs/developer-guide/agent-loop)、[session 存储](https://hermes-agent.nousresearch.com/docs/developer-guide/session-storage)、[功能总览](https://hermes-agent.nousresearch.com/docs/user-guide/features/overview)、[MCP](https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp)、[配置](https://hermes-agent.nousresearch.com/docs/user-guide/configuration)

## 2. OpenCode（anomalyco/opencode）

### 2.1 语言/技术栈与许可证
- TypeScript monorepo（packages/ 约 40 个包：opencode、core、server、client、sdk、plugin、protocol、tui、web、console、desktop、enterprise…）；Effect 函数式生态 + Drizzle ORM + SQLite；运行时 **Bun**；HTTP/SSE + OpenAPI 3.1。
- **许可证：MIT**（Copyright 2025 opencode），无附加条件；注意默认分支为 dev。[LICENSE](https://github.com/anomalyco/opencode/blob/dev/LICENSE)

### 2.2 运行时架构
- **client/server 拆分是核心架构**：`opencode` 同时启动 TUI（客户端）与本地 HTTP server（默认 127.0.0.1:4096）；`opencode serve` 可 headless 独立运行；server 发布 OpenAPI 3.1 spec（`/doc`）并用它生成 SDK。[Server 文档](https://opencode.ai/docs/server/)
- 会话模型：`/session` CRUD、parentID 子会话树、fork、summarize、revert/unrevert、diff、share；消息 = info + parts。
- Agent loop：由 server 内 session/message 流程驱动，模型连续工具调用直到停止或 agent `steps` 上限（达上限注入"总结已完成工作"的系统提示）。
- 上下文管理：自动 compaction 由**隐藏的 compaction 系统 agent** 执行（插件 `experimental.session.compacting` hook 可注入/替换压缩 prompt），另有 title/summary 两个隐藏系统 agent。[Agents 文档](https://opencode.ai/docs/agents/)

### 2.3 子 Agent / 委派模型
- 两类 agent：**primary**（内置 build=全工具、plan=只读/ask）与 **subagent**（内置 general=除 todo 外全工具、explore=只读、scout=只读外部文档研究）。
- 自定义 agent 是**数据不是代码**：opencode.json 的 `agent` 段（description/mode/model/prompt/permission/steps/temperature）或 Markdown frontmatter（`~/.config/opencode/agents/`、`.opencode/agents/`）；`@mention` 手动调用或主 agent 按 description 自动路由。
- 子 agent 跑在**同进程的独立 child session**（session 树，TUI 键位可导航）；默认继承调用方主 agent 的模型（可覆盖）；`permission.task` 控制是否允许启动 subagent；文档未提及沙箱/容器隔离。

### 2.4 权限与批准模型
- `permission` 三态 **allow/ask/deny**；按工具键（read/edit/glob/grep/bash/task/skill/lsp/question/webfetch/websearch）+ 安全键（external_directory、doom_loop）；对象语法支持按输入通配匹配（bash "git *"、edit 路径 glob），最后匹配规则生效；*.env 系列默认 deny。
- `--auto` auto-approve 模式自动批准未被显式 deny 的请求；ask 时 UI 给 once/always/reject 三选项；**无内置 OS 沙箱**（bash 在本机执行，靠 permission 把关）。[Permissions 文档](https://opencode.ai/docs/permissions/)

### 2.5 事件/流式设计（重点）
- `GET /event`（会话级）与 `/global/event`（全局）为 **SSE 事件流**，首事件 `server.connected`；插件事件面覆盖 session.* / message.part.updated / tool.execute.before|after / permission.asked|replied 等。
- **权限请求是普通 HTTP 交互**：客户端 `POST /session/:id/permissions/:permissionID` 回 `{response, remember?}`；TUI/Web/Desktop/IDE 都是纯 HTTP client；`/tui/*` 端点可远程驱动 TUI；mDNS 发现 + basic auth 可选。
- 动态 MCP：`POST /mcp {name, config}` 运行时增删。

### 2.6 配置与自管理
- opencode.json/jsonc 带 `$schema` 在线校验；**8 层配置全部合并**：远程组织默认 → 全局 → 环境变量 → 项目 → `.opencode/` → 系统管理目录 → MDM 托管（最高，不可覆盖）。[Config 文档](https://opencode.ai/docs/config/)
- AGENTS.md（项目根）+ 全局 AGENTS.md + CLAUDE.md 回退；`/init` 分析仓库生成 AGENTS.md；MCP local（command 子进程）/remote（HTTP+OAuth RFC 7591）；插件目录 + npm 插件（启动时 bun install）；`PATCH /config` 可运行时更新。
- Agent 能否修改自身配置：文档未明确说明（但 API 层存在 `PATCH /config`）。

### 2.7 扩展机制
- 插件（JS/TS 导出 hook，可注册自定义 tool：Zod schema + execute，同名时插件优先）；MCP server；由 OpenAPI spec 生成的 SDK；自定义斜杠命令（commands/ 目录）。

### 2.8 轻量化手段
- Bun 免编译执行 TS；curl/npm/homebrew/scoop/pacman/AUR/nix/docker 多通道分发；core 是库、TUI/Web/Desktop 是可选 client（按需装配）。未找到官方启动时间数据。

### 2.9 引用
[仓库](https://github.com/anomalyco/opencode)、[docs](https://opencode.ai/docs)、[Server](https://opencode.ai/docs/server/)、[Agents](https://opencode.ai/docs/agents/)、[Permissions](https://opencode.ai/docs/permissions/)、[Config](https://opencode.ai/docs/config/)、[MCP](https://opencode.ai/docs/mcp-servers/)、[Plugins](https://opencode.ai/docs/plugins/)、[packages 结构](https://github.com/anomalyco/opencode/tree/dev/packages)

## 3. Codex CLI（openai/codex）

### 3.1 语言/技术栈与许可证
- 核心 **Rust**（codex-rs 工作区，约 100 个 crate：core、exec、tui、cli、protocol、sandboxing、config、codex-mcp、rmcp-client、hooks、skills、thread-store、app-server* 等）；TUI 用 ratatui；TypeScript 薄壳 codex-cli（npm 包 @openai/codex 只负责下载并拉起原生二进制）；构建 Bazel + Cargo；编辑器集成走 JSON-RPC app-server 协议（v2）；存储 SQLite（thread-store）+ rollout JSONL 会话历史。
- **许可证：Apache-2.0**（Copyright 2025 OpenAI），代码无额外使用限制，但**贡献需签 CLA**（docs/CLA.md 存在）。[LICENSE](https://github.com/openai/codex/blob/main/LICENSE)

### 3.2 运行时架构
- Rust 进程内 loop：`core/thread_manager.rs`、`codex_thread.rs` 管理线程/会话；流程为 Responses API（SSE 流式）→ 解析工具调用（shell 等）→ 执行并回填 → 下一轮，直至模型停止或 rollout budget 用尽（rollout_budget.rs）。
- 上下文硬约束见其 [AGENTS.md](https://github.com/openai/codex/blob/main/AGENTS.md)："不重写历史、注入项必须有界且设硬上限、单条 >10k token 不允许、注入片段必须定义为 struct 并实现 ContextualUserFragment trait"。
- 压缩：compact.rs / compact_remote_v2.rs，存在 SubAgentCompact 线程来源与 compact_model_fallback（压缩可用独立模型）；会话持久化 rollout JSONL + SQLite，支持 resume/fork；worktree 支持（--worktree/--ephemeral）。

### 3.3 子 Agent / 委派模型
- 协议层线程来源枚举含 SubAgent / SubAgentReview / SubAgentCompact / SubAgentThreadSpawn / SubAgentOther；有 codex_delegate.rs、thread fork/parent_thread_id 机制——**子 agent 以"线程派生"方式实现、同进程运行**；具体权限收缩/上下文隔离细节未在本次抓取内容中找到（据源码目录推断，未核实行为）。

### 3.4 权限与批准模型
- `approval_policy` = AskForApproval 等；`--dangerously-bypass-approvals-and-sandbox` 为 YOLO 开关（映射为 SandboxMode::DangerFullAccess）。
- **`sandbox_mode` 三档：ReadOnly / WorkspaceWrite / DangerFullAccess**（exec/lib.rs 有从权限配置推导档次的逻辑）——这个三分法可直接搬到任何 agent 权限设计。
- OS 级沙箱运行时：macOS Seatbelt（.sbpl 策略文件 + sandbox-exec）、Linux Landlock + bubblewrap、Windows 沙箱服务；网络沙箱策略（CODEX_SANDBOX_NETWORK_DISABLED=1）；exec policy 机制（execpolicy crate，--ignore-rules 忽略用户/项目策略）；MCP 工具批准模板；secrets/keyring-store 凭据存储。

### 3.5 事件/流式设计
- 对模型用 Responses API SSE 流；**进程间用 app-server JSON-RPC**（通知如 thread/started、turn/started|completed、item/started|completed、hook started/completed、turn/diff、turn/plan 等）；`codex exec` 双输出模式：人类可读 stdout 或 `--json` 逐行 JSONL 事件；stdio→UDS 桥；app-server-daemon/client 支撑编辑器（VS Code 等）嵌入与多客户端复用。

### 3.6 配置与自管理
- config.toml 分层加载（load_config_toml_with_layer_stack、profile v2、find_codex_home）；config schema 文件 codex-rs/core/config.schema.json；`requirements.toml` 可设 `allow_managed_hooks_only = true`（只有受管 hooks 生效）。
- 生命周期 hooks（hook_runtime.rs + HookStarted/HookCompleted 通知）；AGENTS.md 规则（core/src/agents_md.rs）；skills（core/src/skills.rs + docs/skills.md）；MCP 配置与管理（codex-mcp/src/mcp_connection_manager.rs，含 McpServerElicitation 用户输入请求机制）；支持 OSS 提供商 LMStudio/Ollama。
- Agent 能否修改自身配置：未在抓取内容中找到明确说明。

### 3.7 扩展机制
- MCP server（且 `codex mcp-server` 可反向作为 MCP server 被 Hermes 等接入——Hermes 文档列有 codex preset）；插件（codex-rs/plugin、core-plugins crate）；SDK（仓库 sdk/ 目录）；公开 JSON-RPC app-server 协议供第三方 client/IDE 嵌入。

### 3.8 轻量化手段
- Rust 单二进制、Linux musl 静态构建（release 资产如 codex-x86_64-unknown-linux-musl.tar.gz）；README 定位 "Lightweight coding agent that runs in your terminal"；npm/Homebrew 安装（npm 包只是拉取二进制的 shim）。未找到官方启动时间/内存数据。

### 3.9 引用
[README](https://github.com/openai/codex/blob/main/README.md)、[codex-rs 目录](https://github.com/openai/codex/tree/main/codex-rs)、[AGENTS.md（内部架构规范）](https://github.com/openai/codex/blob/main/AGENTS.md)、[sandboxing 源码](https://github.com/openai/codex/tree/main/codex-rs/sandboxing/src)、[exec lib.rs](https://github.com/openai/codex/blob/main/codex-rs/exec/src/lib.rs)、[docs 目录](https://github.com/openai/codex/tree/main/docs)、[docs/config.md](https://github.com/openai/codex/blob/main/docs/config.md)

## 4. Goose（aaif-goose/goose，原 block/goose）

### 4.1 语言/技术栈与许可证
- **核心 Rust**（README 原文 "Built in Rust for performance and portability"）；产物：桌面 App（Electron + React/TS，据文档示例）+ CLI + API；crates 工作区含 goose（核心）、goose-cli、goose-mcp、goose-agent、goose-context-management、goose-sdk、goose-acp-macros 等（本次已核实 crates 目录）；15+ 模型提供商；经 ACP 可复用 Claude/ChatGPT/Gemini 订阅。
- **许可证：Apache-2.0**（Copyright 2024 Block, Inc.），无附加条件；2026-04-07 项目治理迁移至 Linux Foundation AAIF（原 block/goose → aaif-goose/goose）。[LICENSE](https://raw.githubusercontent.com/aaif-goose/goose/main/LICENSE)、[迁移公告](https://goose-docs.ai/blog/2026/04/07/goose-moves-to-aaif)

### 4.2 运行时架构
- 三组件模型：**Interface（桌面/CLI）— Agent（核心交互循环）— Extensions（工具来源，即 MCP 服务器）**。[架构文档](https://goose-docs.ai/docs/goose-architecture/)
- 交互循环六步：人类请求 → Provider Chat（请求+工具清单给 LLM）→ Model Extension Call（goose 代执行工具）→ Response to Model（结果回传，可迭代多轮）→ Context Revision（删旧/无关信息控 token）→ Model Response。
- 上下文管理：默认 80% 阈值自动压缩（`GOOSE_AUTO_COMPACT_THRESHOLD`）、旧工具输出后台摘要化（`GOOSE_TOOL_CUTOFF`）、compaction.md 模板可自定义、`GOOSE_MAX_TURNS`（默认 1000）防失控。Session 持久化本地数据库，Desktop 与 CLI 共用可互恢复，支持导出/导入、fork、跨会话搜索。

### 4.3 子 Agent / 委派模型
- **内部子代理 = 独立 goose 实例**：继承主会话上下文与扩展，默认 25 turns / 5 分钟超时，可顺序/并行；安全约束：禁止再派生子代理（防递归）、禁止管理扩展、禁止管理调度；可用自然语言收缩权限（如"只给 developer 扩展"）。
- **外部子代理 = 任意 MCP 服务器注册为 subagent**（官方文档示例：把 OpenAI Codex CLI 作为 stdio MCP subagent，配 approval_policy="never" + workspace-write sandbox——即用别的项目的沙箱做自己的隔离）。
- custom agent = Markdown + YAML frontmatter（name/description/model + body 指令），存 `~/.agents/agents/` 或 `<project>/.agents/agents/`，兼容 .goose/agents、.claude/agents。[subagents 文档](https://goose-docs.ai/docs/guides/context-engineering/subagents/)

### 4.4 权限与批准模型
- 四模式：Completely Autonomous（**默认**，可直接改/删文件）、Manual Approval（逐次确认）、Smart Approval（风险分级自动批低风险）、Chat Only；粒度化工具权限（permission.yaml + tool_permissions.json）。
- 经 ACP 桥接的第三方代理（如 Claude Code）敏感操作提示会路由到 goose 界面统一确认；扩展激活前自动恶意软件检查；扩展白名单 `GOOSE_ALLOWLIST`；prompt 注入检测默认关闭。**未见内建 OS 级沙箱**（扩展在宿主进程环境运行；沙箱仅见于外接 Codex 的配置示例）。[permissions 文档](https://goose-docs.ai/docs/guides/managing-tools/goose-permissions)

### 4.5 事件/流式设计
- **ACP 双向**：`goose acp` 以 stdio 起 ACP server（JetBrains/Zed 可直连）；也可把外部 ACP 代理当 provider 委派，扩展透传为 MCP server；ACP 服务端含 HTTP/WebSocket 实时通道（据文档示例）。
- 扩展传输：builtin/platform/stdio/streamable_http；**明确不支持 SSE**（旧 SSE 配置需迁移到 streamable_http）；支持 MCP Sampling/Elicitation/Roots/Apps。UI 与 core 解耦：同一 core 服务 Desktop/CLI/API/ACP。

### 4.6 配置与自管理
- `~/.config/goose/config.yaml` + permission.yaml + secrets.yaml（明文，keyring 不可用时回退）；优先级 env > config > 默认；API key 不入 config.yaml。
- 扩展在 `extensions:` 块声明（type/cmd/args/envs/timeout/available_tools 工具过滤）；远程 streamable_http 扩展支持 OAuth。
- **agent 可动态改自身配置**：Extension Manager 扩展可在会话中发现/启用/禁用扩展；Smart Extension Recommendation 按任务自动建议并启用扩展（仅当前会话）；多数直接改文件需重启生效。

### 4.7 扩展机制
- **Extensions = MCP 服务器**（官方文档明确"goose 的内置扩展本身就是 MCP 服务器，可被其他 agent 复用"）；Rust 侧 `Extension` trait + `Tool`（proc macro `goose_macros::tool`），执行错误作为 prompt 回传模型。
- 内置扩展：Developer（默认）、Computer Controller、Memory、Tutorial 等；平台扩展：Extension Manager、Skills、Summon、Todo 等；安装途径：目录 UI / CLI（`goose session --with-extension`）/ deeplink。[extensions 设计文档](https://goose-docs.ai/docs/goose-architecture/extensions-design)

### 4.8 轻量化手段
- Rust 单二进制 CLI（curl|bash 一键装）；选 Rust 是为 performance and portability（README）；ACP stdio 模式与编辑器零 HTTP 集成；启动时间数据未找到。桌面 App 为 Electron（非轻量路径）。

### 4.9 引用
[仓库](https://github.com/aaif-goose/goose)、[README](https://raw.githubusercontent.com/aaif-goose/goose/HEAD/README.md)、[架构](https://goose-docs.ai/docs/goose-architecture/)、[扩展设计](https://goose-docs.ai/docs/goose-architecture/extensions-design)、[权限](https://goose-docs.ai/docs/guides/managing-tools/goose-permissions)、[配置](https://goose-docs.ai/docs/guides/config-files)、[会话与上下文](https://goose-docs.ai/docs/guides/sessions/smart-context-management/)、[subagents](https://goose-docs.ai/docs/guides/context-engineering/subagents/)

## 5. Letta（letta-ai/letta → letta-code）

### 5.1 语言/技术栈与许可证
- **重要现状（与常见印象不同）**：letta-ai/letta（24.6k stars，f.k.a. MemGPT）现在只是落地页；**实际代码在 letta-ai/letta-code**（TypeScript，npm 包 @letta-ai/letta-code）：agent harness + TUI + App Server + channels + Desktop/Web。V1 Python 服务器已归档在 letta 仓库的 `archive` 分支，不再维护。[letta README](https://raw.githubusercontent.com/letta-ai/letta/main/README.md)、[letta-code README](https://github.com/letta-ai/letta-code)
- **许可证：Apache-2.0**（Copyright 2025, Letta authors），**附带 Brand Assets Exclusion**：Letta 名称/logo/图片等品牌资产不在 Apache-2.0 许可内，派生作品使用需书面许可；代码本身无其他附加条件。[LICENSE](https://raw.githubusercontent.com/letta-ai/letta-code/main/LICENSE)

### 5.2 运行时架构（memory-first 的现状）
- 核心抽象 = **状态化 agent（持久 AI 身份）**：名字/人格/长期记忆（存 MemFS）/模型与工具配置/多个 conversations；**记忆跨会话共享**。Agent loop 由 harness 驱动（连 LLM 与持久上下文、管理 turns、执行工具）。
- **MemFS**：git 托管的记忆文件系统——所有上下文（含 memory blocks）经 git 版本管理，可同步到自定义 GitHub 仓库（`/memory-repository set`）。记忆按路径寻址：`system/persona.md`、`system/human.md` **每次 turn 进系统提示词**；system/ 之外的树不进上下文直到被读取，但整个文件树始终在系统提示中充当"路标"——保持活跃上下文精简（上下文外存储）。
- **写入式记忆**：agent 学到持久事实时自行改写记忆并 commit（`/remember`、`/init`、`/doctor` 审计、`/palace` 查看）；**dreaming**：后台 subagent 定期回顾对话、整合教训、更新记忆（`/sleeptime` 配置）。
- 经典三层记忆（core memory persona/human 块 + recall 对话检索 + archival 外部存储）来自 MemGPT/V1 设计（V1 已归档）；V2 对应物：system/ 块 ≈ core memory、会话搜索 ≈ recall（Cloud 支持全文/向量/混合）、MemFS 树 ≈ archival。[concepts](https://docs.letta.com/concepts/stateful-agents)、[memfs](https://docs.letta.com/concepts/memfs)

### 5.3 子 Agent / 委派模型
- 7 种内置 subagent：fork（fork 父会话全上下文）、general-purpose、history-analyzer、init、memory、recall（只读）、reflection（后台记忆整合）。
- 机制：经 subagent 工具调用后在**子进程**中启动，各自 system prompt/tools/model，最终消息回传主 agent（保持主上下文干净）；默认阻塞，可 background（完成通知自动注入）；**agent 可把任意其他 agent（包括自己）作为 subagent 调用**。
- 自定义 subagent = `.letta/agents/*.md`（项目）/`~/.letta/agents/`（全局），YAML frontmatter：tools 白名单或 all、model、**memoryBlocks**（具体列表/all/none）、skills；隔离与权限收缩：tools 白名单、memoryBlocks 限制、recall 只读。[subagents 文档](https://docs.letta.com/configuration/subagents)

### 5.4 权限与批准模型
- 四模式：unrestricted（yolo，**交互 CLI 默认**，除显式 deny 与安全守卫外全自动）、standard（shell/edits/subagents 需询问）、acceptEdits、strict（每次工具调用都问，含读操作）；Shift+Tab 切换，--yolo 简写。
- 细粒度：`--allowedTools/--disallowedTools` 模式（如 `Bash(rm -rf:*)`），持久规则 `.letta/settings.json`（allow/deny）。
- **跨 agent 记忆守卫**：即使 unrestricted 也拒绝触碰其他 agent 的记忆目录（`--disable-memory-guard` 仅父进程可关、子代理恒启用）。
- 沙箱：本地模式直接在本机执行 Bash 等工具；隔离执行靠"computers"概念（BYOM 自有机器或 Letta Cloud 托管 sandbox）；未找到本地容器化沙箱。[permissions 文档](https://docs.letta.com/configuration/permissions)

### 5.5 事件/流式设计
- App Server 用**单一双向 WebSocket**（ws://127.0.0.1:4500/ws）：所有 command/response/approval/tool callback/streamed delta/state update 都是带 type 的 JSON 走同一 socket；runtime_start 订阅 {agent_id, conversation_id} scope；多客户端/多运行时订阅；非回环监听强制 --ws-auth（capability-token/signed-bearer-token）。Letta Cloud REST API 另有 SSE 流式。
- UI 与 core 解耦：CLI/Desktop/Web/App Server/Agent SDK/ACP adapter 均为 harness 不同入口；mods 可订阅 conversation_open/close、tool_start/end、turn_start/end、compact_start/end 等事件。[protocol lifecycle](https://docs.letta.com/platform/app-server/protocol-lifecycle)

### 5.6 配置与自管理（本项目最激进）
- 全局 `~/.letta/`（mods/skills/agents），项目 `.letta/`（agents/settings.json）与 `.agents/skills/`；大量配置经 slash 命令（/connect、/model、/sleeptime、/memory-repository、/login）。
- **核心卖点即自管理**：README 明示 "Letta Code agents are designed to be self-configuring——想配置什么，直接让 agent 自己做"；agent 可改写自身记忆、技能、提示词，甚至通过 **mods** 改写 harness 本身。
- **mods**：`~/.letta/mods/*.js|.mjs|.ts|.tsx` 作为**全信任进程内代码**加载，`export activate(letta)` 注册命令/工具/事件钩子/权限策略/provider 适配器/终端 UI，`/reload` 热重载，可打包为 npm mod（`letta install npm:...`），`--no-mods` 干净启动。[mods 文档](https://docs.letta.com/configuration/mods)

### 5.7 扩展机制
- **skills 为主要扩展机制**：实现开放 Agent Skills 标准（agentskills.io，与 Codex/Claude Code/Hermes/OpenClaw 互移植）；三级 scope：agent 级（MemFS 内，随身份走）/项目级（.agents/skills/）/机器级（~/.letta/skills/）+ 内置技能；**agent 可自己创建技能**（内置 skill-creator）；`letta skills install` 支持 GitHub/ClawHub/Hermes 源；技能可含可执行脚本、可经脚本连 MCP server。[skills 文档](https://docs.letta.com/configuration/skills)

### 5.8 轻量化手段
- 从 V1 的 Python 服务端转向 TypeScript/Node 本地 harness（npm 全局安装即用，无自建 LLM 服务）；"状态与执行分离"：身份/记忆存云端（Letta Cloud），工具在本地/远程机器执行（--computer 路由、teleportation）。语言选型原因与启动时间数据未在文档中给出。

### 5.9 引用
[letta 落地页](https://raw.githubusercontent.com/letta-ai/letta/main/README.md)、[letta-code README](https://cdn.jsdelivr.net/gh/letta-ai/letta-code@main/README.md)、[stateful agents](https://docs.letta.com/concepts/stateful-agents)、[MemFS](https://docs.letta.com/concepts/memfs)、[memory](https://docs.letta.com/configuration/memory)、[subagents](https://docs.letta.com/configuration/subagents)、[permissions](https://docs.letta.com/configuration/permissions)、[mods](https://docs.letta.com/configuration/mods)、[skills](https://docs.letta.com/configuration/skills)、[App Server 协议](https://docs.letta.com/platform/app-server/protocol-lifecycle)

## 6. MetaGPT（FoundationAgents/MetaGPT）

### 6.1 语言/技术栈与许可证
- Python（3.9~3.12），asyncio 并发；pip install metagpt；CLI 用 fire/typer；生成前端代码需另装 node/pnpm；可选 Docker。
- **许可证：MIT**（Copyright 2024 Chenglin Wu），无附加条件。[LICENSE](https://cdn.jsdelivr.net/gh/FoundationAgents/MetaGPT@main/LICENSE)

### 6.2 运行时架构
- 核心哲学 **Code = SOP(Team)**：把软件公司 SOP 物化到 LLM 团队，一条需求输入 → 用户故事/竞争分析/需求/数据结构/API/文档。概念公式：Agent = LLM + Observation + Thought + Action + Memory；MultiAgent = Agents + Environment + SOP + Communication + Economy。
- 核心类：**Role**（set_actions 装备 Action、`_watch([MessageType])` 订阅上游消息、react_mode="by_order" 等策略）、**Action**（PROMPT_TEMPLATE + _aask 调 LLM；也可纯本地如 subprocess 执行代码）、**Message**（cause_by 是订阅路由键）、**Memory**（短期消息列表，取最近 k 条）、**Environment**（共享空间 publish/订阅）、**Team**（n_round 轮次 + investment 预算）。
- SOP 流水线示例：SimpleCoder _watch(UserRequirement) → SimpleTester _watch(SimpleWriteCode) → SimpleReviewer _watch(SimpleWriteTest)——**下游只消费上游 Action 类型的消息**。[concepts 教程](https://docs.deepwisdom.ai/main/en/guide/tutorials/concepts.html)

### 6.3 子 Agent / 委派模型
- 多 agent = **同一进程内多个 Role 实例**（asyncio 协程）经 Environment 消息发布/订阅协作；Team 编排轮次与投资预算。
- **权限传递/收缩与进程级隔离均未在该项目文档中找到**——各 Role 共享进程与文件系统权限；"economy/investment"只是示例中的模拟参数，无强制资源配额。隔离粒度 = 消息路由隔离（谁 watch 谁），不是权限隔离。

### 6.4 权限与批准模型
- **未在该项目文档中找到** auto-approve/permission request/sandbox 类机制；框架默认直接执行 Action（含 subprocess 跑代码），未见批准流描述。

### 6.5 事件/流式设计
- 框架内部基于 asyncio 消息发布/订阅（Environment 中介 + Message.cause_by 类型路由）；**未找到内置 SSE/WebSocket 服务端**——以 Python 库/CLI 形态运行，UI 解耦程度低；事件总线/UI 协议设计在文档中基本缺席。

### 6.6 配置与自管理
- `metagpt --init-config` 生成 `~/.metagpt/config2.yaml`（llm.api_type/model/base_url/api_key；输出 ./workspace）；**agent 修改自身配置的机制未找到**（静态 YAML，无自改写能力）。

### 6.7 扩展机制
- 自定义 Action 子类 + set_actions 注册；**工具注册**：`metagpt/tools/libs` 放函数 + Google-style docstring + `@register_tool` 装饰器（tags 分类），`DataInterpreter(tools=[...])` 挂载。[工具教程](https://docs.deepwisdom.ai/main/en/guide/tutorials/create_and_use_tools.html)
- **MCP 支持未在抓取到的文档中找到**。

### 6.8 轻量化手段
- 纯 Python 库/CLI，无编译；asyncio 单进程多协程即多 agent 运行时；无桌面应用、无单二进制分发；未给出启动时间数据。

### 6.9 引用
[仓库](https://github.com/FoundationAgents/MetaGPT)、[README](https://raw.githubusercontent.com/FoundationAgents/MetaGPT/master/README.md)、[concepts](https://docs.deepwisdom.ai/main/en/guide/tutorials/concepts.html)、[agent 101](https://docs.deepwisdom.ai/main/en/guide/tutorials/agent_101.html)、[multi agent 101](https://docs.deepwisdom.ai/main/en/guide/tutorials/multi_agent_101.html)

## 7. Browser Use（browser-use/browser-use）

### 7.1 语言/技术栈与许可证
- Python（>=3.11，异步 asyncio，当前 0.13.x）；pydantic、事件总线 bubus、aiohttp、LLM SDK 全家桶（openai/anthropic/google/groq/ollama/litellm…）、MCP、cdp-use（CDP 直连 Chromium）。[pyproject.toml](https://raw.githubusercontent.com/browser-use/browser-use/main/pyproject.toml)
- **许可证：MIT**（Copyright 2024 Gregor Zunic），无附加条件。[LICENSE](https://raw.githubusercontent.com/browser-use/browser-use/HEAD/LICENSE)

### 7.2 运行时架构
- Agent loop（`browser_use/agent/service.py` 的 `Agent.step()`）：Phase1 取浏览器状态摘要（DOM 可点击元素、截图、可用动作）→ Phase2 LLM 调用（`llm_timeout=60s`、`step_timeout=180s`、fallback LLM 切换）→ `multi_act` 一次执行动作列表（`max_actions_per_step=5`）→ Phase3 后处理。
- 多轮上下文由 `MessageManager` 管理：每步一条 state 消息，历史只保留首条 + 最近 N 条，中间以占位省略；**compaction 为 LLM 摘要式**（每 25 步 + 40k 字符阈值触发，`summary_max_chars=6000`，保留首条 + 最近 6 条）；另有 read_state 截断、敏感数据占位符、ActionLoopDetector 循环检测、plan/replan nudge。
- 状态在 `AgentState`（n_steps、consecutive_failures、plan、paused/stopped、loop_detector）。

### 7.3 子 Agent / 委派模型
- **无内置 subagent 委派机制**。并行 = 用户层 asyncio：每个 Agent 建独立 Browser 实例（user_data_dir 隔离）+ `asyncio.gather` 并发（官方标注 experimental，agents 可能互相冲突）。[并行模板](https://docs.browser-use.com/open-source/examples/templates/parallel-browser)

### 7.4 权限与批准模型
- 无 auto-approve/确认审批流（OSS 库默认可执行动作空间）。安全边界在 `SecurityWatchdog`：`allowed_domains`/`prohibited_domains`（glob）+ IP 拦截（兼容非标准 IPv4 编码防绕过）；另有 permissions/captcha/downloads 等十余个 watchdog。
- 敏感数据用 `<secret>` 占位符避免进入 LLM 上下文；OSS 库无容器沙箱（`browser_use/sandbox/` 是 Browser Use Cloud 远程沙箱的客户端封装）。

### 7.5 事件/流式设计
- 浏览器层基于 `bubus.EventBus`：`BrowserSession` 派发 NavigateToUrlEvent/ScreenshotEvent 等，watchdog 以 LISTENS_TO/EMITS 契约订阅（事件驱动而非回调链）。
- 用户侧 hook 只有 `on_step_start`/`on_step_end` 两个 async 回调（可读写 agent 状态、pause/resume、访问 CDP）；OSS 无内置 SSE/WebSocket 服务。

### 7.6 配置与自管理
- `.env` 环境变量（pydantic-settings）+ 结构化 JSON 配置（browser_profile/llm/agent 三组）；hook 内可在运行期改 `agent.settings`、`agent.sensitive_data`、`add_new_task` 追加任务；未发现 agent 自行改磁盘配置的机制。

### 7.7 扩展机制
- `Tools()` + `@tools.action(description=...)` 装饰器注册自定义动作；`ToolRegistry` 按页面 URL 生成动作提示；支持排除内置动作、structured-output。
- 技能系统：`browser-use skill install` 装 SKILL.md 给 Claude Code/Cursor 等；MCP 双向（可作 stdio MCP server，也可作 client 接入外部 MCP 工具）。

### 7.8 轻量化手段
- 纯 Python + CDP 直连 Chromium（不依赖 Selenium/Playwright）；`browser-use-core` 为可选平台二进制 extra；`flash_mode` 关闭 evaluation/thinking 提速；`use_vision='auto'` 按需截图省 token；官方承认 "Chrome can consume a lot of memory"，大规模并行推给云。

### 7.9 引用
[仓库](https://github.com/browser-use/browser-use)、[docs](https://docs.browser-use.com/open-source/introduction)、[hooks](https://docs.browser-use.com/open-source/customize/hooks)、[MCP](https://docs.browser-use.com/open-source/customize/integrations/mcp-server)

## 8. OpenHands（OpenHands/OpenHands）

### 8.1 语言/技术栈与许可证（重要现状）
- **注意**：截至 2026-09-08，`OpenHands/OpenHands` 主仓库已重构为 **Agent Canvas**（自托管编码 agent 控制台，Node.js 22.12.x + npm，默认端口 8000）；原 OpenHands agent 核心已拆分到 **OpenHands/software-agent-sdk**（Python 四包架构：`openhands-sdk` 核心框架、`openhands-tools`、`openhands-workspace`（Docker/Apptainer/Cloud/RemoteAPI）、`openhands-agent-server`（FastAPI + WebSocket 多租户）），另有 OpenHands/automation、OpenHands-CLI 等仓库。[Agent Canvas README](https://raw.githubusercontent.com/OpenHands/OpenHands/HEAD/README.md)
- **许可证：MIT**（Copyright 2025 OpenHands contributors；software-agent-sdk 同为 MIT），无附加条件。[LICENSE](https://raw.githubusercontent.com/OpenHands/OpenHands/HEAD/LICENSE)

### 8.2 运行时架构
- 两种部署模式同一套 agent 代码：**本地**（LocalWorkspace 同进程，无 Docker，官方警告 agent 拥有完整文件系统访问）与**生产/沙箱**（DockerWorkspace：拉预构建镜像起容器，容器内起 agent server、宿主机只暴露 HTTP；RemoteAPIWorkspace 连远端）。
- 会话建模：`Conversation`（state、events、run()、send_message()），**事件即状态——append-only 事件树**；多轮上下文由 **Context Condenser** 管理（默认 LLMSummarizingCondenser：事件数超阈值时 LLM 摘要旧事件、keep_first 保留开头；可自定义 RollingCondenser）。[context condenser](https://docs.openhands.dev/sdk/guides/context-condenser)

### 8.3 子 Agent / 委派模型
- 内建 **delegate 工具**：`DelegateAction` 支持 `spawn`（按 ids 初始化子 agent，agent_types 如 'researcher'/'programmer'）与 `delegate`（tasks 字典派任务、阻塞等结果）；`max_children=5`；子 agent 遇 `WAITING_FOR_CONFIRMATION` 时回调父级 confirmation_handler（**权限上收到父流程**）。
- 预设 file-based subagents（bash_runner.md、code_explorer.md）；隔离级别：独立 LocalConversation（会话级隔离、共享宿主 workspace、同一进程；未见于进程/容器级隔离的实现，据源码所见）。

### 8.4 权限与批准模型
- 三层：① **确认策略** AlwaysConfirm / NeverConfirm / ConfirmRisky（需 security analyzer）；会话进入 WAITING_FOR_CONFIRMATION 状态，可 reject_pending_actions 带反馈拒绝；② **安全分析器**：shell 语法解析、LLM 风险判定（llm_analyzer.py）、ToolShield/GraySwan 集成、defense-in-depth 策略栅栏；③ **沙箱**：新 SDK 的 DockerWorkspace（容器内起 agent server）；旧文档仍描述的 Docker Runtime 客户端-服务器架构（三标签镜像、命名卷、runtime 插件）路径已变。[security](https://docs.openhands.dev/sdk/guides/security)

### 8.5 事件/流式设计（重点）
- 继承原 OpenHands action/observation 事件模型：`Event`（id/timestamp/source/parent_id，frozen），类型 action | observation | message | system_prompt | agent_error，来源 agent | user | environment | hook；`LLMConvertibleEvent.to_llm_message()` 把事件投影成 LLM 消息流。
- 持久化：**EventLog——每个事件一个 JSON 文件、append-only、文件锁（.eventlog.lock）支持跨进程并发写、parent_id 树 + path_to_root() 取活动分支（时间旅行的基础）**。
- 流式：进程内 PubSub（StreamingDeltaEvent 按 token 率投递需显式 opt-in）；Agent Server 提供 REST 事件检索 + WebSocket 会话协议（SyncFrame/DeltaFrame/TransientFrame/ItemStartedFrame 等帧类型）。[event_store.py](https://cdn.jsdelivr.net/gh/OpenHands/software-agent-sdk@main/openhands-sdk/openhands/sdk/conversation/event_store.py)

### 8.6 配置与自管理
- 旧版 config.toml 在当前仓库中已不存在；现在 Agent Server 用 JSON 配置（`openhands_agent_server_config.json`）+ `OH_*` 环境变量（pydantic 模型含 session keys/webhook/telemetry）。
- 仓库级自管理：`.openhands/hooks.json` + hooks 脚本（见下）；`~/.openhands/mcp.json`；AGENTS.md/CLAUDE.md/GEMINI.md 自动加载。agent 不能修改服务器配置文件本身（未见此类机制）。

### 8.7 扩展机制
- **Hooks**（Claude Code 兼容 .openhands/hooks.json）：PreToolUse（可阻止）/PostToolUse/UserPromptSubmit（可阻止）/Stop（可阻止，如强制 lint 通过）/SessionStart/SessionEnd；脚本 stdin 收 JSON、exit 2 或 deny 决定阻止，matcher 通配/正则/工具名。
- **Skills**：扩展版 AgentSkills 标准（SKILL.md + 关键词 trigger，渐进披露 + invoke_skill()）；公开 marketplace OpenHands/extensions。
- **Plugins**（sdk/plugin/）、**MCP**（SSE/SHTTP/stdio 三 transport，SDK 支持程序化动态注册）、**subagents**（文件定义）。旧版 "microagents" 概念在当前文档/源码树中未再出现。[hooks](https://docs.openhands.dev/openhands/usage/customization/hooks)

### 8.8 轻量化手段
- SDK 设计原则明确为"statelessness、composability、清晰边界"；本地模式仅需 openhands-sdk + openhands-tools 两包、无需 Docker；Agent Canvas 是轻量 Node 前端 + 本地栈编排，重活按需接远端 agent server（"同一前端切换多后端"）。

### 8.9 引用
[仓库](https://github.com/OpenHands/OpenHands)、[SDK 仓库](https://github.com/OpenHands/software-agent-sdk)、[SDK 架构](https://docs.openhands.dev/sdk/arch/overview)、[runtime 架构](https://docs.openhands.dev/openhands/usage/architecture/runtime)、[技术报告](https://arxiv.org/abs/2511.03690)

## 9. Microsoft Agent Framework（microsoft/agent-framework）

### 9.1 语言/技术栈与许可证
- 多语言框架：**Python**（monorepo python/packages/：core + 按厂商拆的 provider 包 + workflows/a2a/ag-ui/hosting/declarative/devui/lab）、**C#/.NET**（Microsoft.Agents.AI NuGet，基于 Microsoft.Extensions.AI 的 IChatClient）、**Go**（独立仓库 microsoft/agent-framework-go，public preview）；Durable 扩展（Durable Task/Azure Functions）独立仓库。
- AutoGen 现状已核实：microsoft/autogen README 顶部标注 **Maintenance Mode**（不再加新功能、转社区维护），官方指引迁移到 MAF。[autogen README](https://cdn.jsdelivr.net/gh/microsoft/autogen@main/README.md)
- **许可证：MIT**（Copyright Microsoft Corporation），无附加条件；README 有"用于第三方系统/非 Azure 模型时自行承担风险"的免责声明。[LICENSE](https://raw.githubusercontent.com/microsoft/agent-framework/HEAD/LICENSE)

### 9.2 运行时架构
- `Agent`（Python）/`AIAgent`（.NET，ChatClientAgent/A2AAgent/FoundryAgent 等实现）统一 run 接口（普通/流式）；框架核心是库而非常驻服务。
- 会话状态：`AgentSession`——JSON 可序列化续跑，支持服务托管存储（service_session_id）或本地存储（Redis/Cosmos/InMemory）；context provider 注入指令/工具/记忆。
- 上下文压缩（ADR-0019）：三触发点（run 内/写盘前/存量存储）、策略链式组合、**硬约束"tool_call 与 tool result 必须成组保留"**。
- **Harness**（可选电池全含层）：chat client + pipeline + context providers（todo/planning、plan/execute 模式、file memory、web search）+ 中间件（approval/observability/有界 loop），每次模型调用后持久化、按 token 上限压缩。
- **Workflows**：图式（executor/edge/event/state 原语、superstep 边界 checkpointing、fan-out/fan-in、可 AsAIAgent() 包成 agent）+ Python 实验性函数式 @workflow（原生控制流 + asyncio.gather、@step 事件缓存、ctx.request_info() 人机协同）。[harness](https://learn.microsoft.com/en-us/agent-framework/concepts/harness)、[workflows](https://learn.microsoft.com/en-us/agent-framework/concepts/workflows/)

### 9.3 子 Agent / 委派模型
- Harness 的 Background agents（实验性）：向命名子 agent 并行委派；workflows 支持 handoff 与 group collaboration、sub-workflows 组合；跨进程/跨网络经 **A2A**（A2AAgent 远端代理）。子 agent 权限继承策略未找到专章。

### 9.4 权限与批准模型
- **用户批准**（ADR-0006）：`ApprovalRequestContent/ApprovalResponseContent` 内容类型——agent 返回批准请求即暂停本次 run，调用方下次在同一 thread 续跑（适合远端托管场景挂起/恢复），批准记录入日志审计。[ADR-0006](https://raw.githubusercontent.com/microsoft/agent-framework/main/docs/decisions/0006-userapproval.md)
- 工具批准：FunctionTool 的 approval_mode="always_require"/"never_require"；Harness 默认 standing approvals/don't-ask-again（可 DisableToolAutoApproval）。
- **Prompt 注入防御 FIDES**（ADR-0024，proposed）：信息流控制标签（Integrity TRUSTED/UNTRUSTED、Confidentiality PUBLIC/PRIVATE）+ 中间件强制 + 隔离执行（quarantined_llm）+ MCP 工具自动标注；未标注内容默认 UNTRUSTED。
- **CodeAct 沙箱**（ADR-0038）：模型写代码经 execute_code 在受控运行时执行（初始后端 **Hyperlight** 轻量 VM），provider 拥有工具注册表/文件挂载/出网 allow-list，approval 为执行前捆绑式批准。

### 9.5 事件/流式设计
- 框架核心为库，流式经各 SDK run-streaming API；中间件可拦截 invocation/function call/approval/error 上下文（ADR-0007）；OpenTelemetry 内建追踪。
- 远程场景由 hosting 包实现协议：**A2A、AG-UI**（python/packages/ag-ui，含 sse_helpers.py 与 approval 生命周期）、OpenAI Responses 协议（.NET SseJsonResult）、MCP、Telegram 通道。

### 9.6 配置与自管理
- 环境变量驱动（**不自动加载 .env**，需手动 load_dotenv）；**Declarative Agents**：YAML 定义 agent（declarative 包，declarative workflows 为 stable）；未发现 agent 运行期修改自身框架配置的机制（session 内可 extend_instructions/extend_tools）。[declarative README](https://raw.githubusercontent.com/microsoft/agent-framework/main/python/packages/declarative/README.md)

### 9.7 扩展机制
- **Middleware**（FunctionMiddleware 管线）；**Context Providers**（session 级指令/工具/记忆注入）；**Agent Skills**（ADR-0037：SKILL.md/内联代码/类库多来源统一抽象，模型侧 load_skill/read_skill_resource/run_skill_script 渐进披露）；MCP（托管 MCP 工具、SecureMCPToolProxy）；CodeAct provider 可插拔后端。

### 9.8 轻量化手段
- 多语言选型主因是覆盖 Python/.NET/Go 企业生态；包粒度细（按厂商/provider 拆包按需装）；CodeAct/技能等重功能为可选包（官方原话：不需要 CodeAct 的用户不必承担其安装与依赖 footprint）；Harness 能力矩阵各项可 disable。

### 9.9 引用
[仓库](https://github.com/microsoft/agent-framework)、[overview](https://learn.microsoft.com/en-us/agent-framework/overview/)、[harness](https://learn.microsoft.com/en-us/agent-framework/concepts/harness)、[workflows](https://learn.microsoft.com/en-us/agent-framework/concepts/workflows/)、[ADR 目录](https://github.com/microsoft/agent-framework/tree/main/docs/decisions)

## 10. n8n（n8n-io/n8n）

### 10.1 语言/技术栈与许可证
- TypeScript monorepo（pnpm + turbo，v2.37.0，Node >=24）；packages/cli（主进程，webhook/worker 入口）、packages/core（执行引擎 workflow-execute.ts）、packages/workflow（图模型）、packages/nodes-base（内置节点）、@n8n/n8n-nodes-langchain、@n8n/agents、@n8n/engine、@modelcontextprotocol/*、@ai-sdk/*、bull + ioredis（队列）；前端 editor-ui（Vue）；数据库默认 SQLite、可选 PostgreSQL。
- **许可证：Sustainable Use License v1.0**（fair-code，非 OSI 开源）：仅限**内部业务目的或非商业/个人用途**使用或修改；分发/提供给他人必须免费且非商业；专利条款带报复终止。**文件名含 ".ee." 的源码需 n8n Enterprise License**（生产使用需订阅）。2022-03-17 前曾是 Apache-2.0 + Commons Clause。[LICENSE.md](https://cdn.jsdelivr.net/gh/n8n-io/n8n@master/LICENSE.md)、[LICENSE_EE.md](https://cdn.jsdelivr.net/gh/n8n-io/n8n@master/LICENSE_EE.md)

### 10.2 运行时架构
- 工作流 = 节点图（Workflow 模型），执行引擎 packages/core/src/execution-engine/workflow-execute.ts：节点级调度、paired items 数据流、表达式求值。
- 两种执行模式：默认 main 单进程；**queue 模式**：主实例收定时器/webhook → Redis（Bull）→ worker 进程（`n8n worker`）执行 → 结果写库经 Redis 通知主实例；主/worker 必须共享 N8N_ENCRYPTION_KEY。v2 新增 scaling 模块（leader-election、pubsub、redis-lock）与 engine-v2（control plane / data plane 分离）。[queue mode](https://docs.n8n.io/hosting/scaling/queue-mode/)
- 多轮 LLM 上下文不在引擎层持久化，靠 Memory 子节点（memorybufferwindow 等）实现（据文档，未核实源码）。

### 10.3 子 Agent / 委派模型
- **Execute Sub-workflow 节点**：子工作流可从数据库（按 ID）、本地 JSON、参数 JSON 或 URL 加载，在运行 n8n 的主机上执行；子工作流与主工作流共享同实例凭证与 RBAC，无独立沙箱隔离（据文档）。[executeworkflow](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.executeworkflow/)
- AI Agent 节点（LangChain 系）可挂接工具/子节点群（Agent/Chain/Vector Store/Memory 等）。

### 10.4 权限与批准模型
- RBAC 两级：实例角色（owner/admin/member）+ 项目角色（Admin/Editor/Viewer，部分需企业版）；外部密钥按项目授权。
- **工具调用审批（HITL）**：AI Agent 工具级 human review，工作流暂停等 approve/deny，审批通道可选 Slack/Telegram/n8n Chat。[HITL tools](https://docs.n8n.io/advanced-ai/human-in-the-loop-tools/)

### 10.5 事件/流式设计
- 编辑器实时更新走 push 通道；Chat Trigger 支持 SSE 流式响应；worker 暴露 /healthz、/metrics。
- **AG-UI：官方文档与源码中未发现原生支持**（社区在 ag-ui#369 请求支持，另有社区节点）。[ag-ui#369](https://github.com/ag-ui-protocol/ag-ui/issues/369)

### 10.6 配置与自管理
- 环境变量为主（N8N_*，EXECUTIONS_MODE、QUEUE_BULL_REDIS_*、DB_TYPE）。
- **公开 REST API /api/v1（API key 认证，OpenAPI 生成）可编程创建/修改 workflow、credential、execution 等**——即 agent 可经 API 自举修改自身工作流的先例。[API reference](https://docs.n8n.io/api/api-reference/)

### 10.7 扩展机制
- 1500+ 集成与 9000+ 模板（README 口径）；社区节点走 npm 包（n8n-nodes-*）+ verified community nodes 机制；**MCP 支持**（mcp trigger/mcpClient 节点 + @modelcontextprotocol SDK）；新版 agent 运行时 @n8n/agents + AI SDK；chat adapters（Slack/Telegram/Discord/Linear）。

### 10.8 轻量化手段
- 单容器 `docker run -p 5678:5678 n8nio/n8n` + SQLite 起步；但 Node 运行时 + 前端构建产物的多进程应用，queue 模式需 Redis + PostgreSQL——明显重于 Rust/Go 单二进制。

### 10.9 引用
[仓库](https://github.com/n8n-io/n8n)、[README](https://cdn.jsdelivr.net/gh/n8n-io/n8n@master/README.md)、[Sustainable Use License FAQ](https://docs.n8n.io/hosting/sustainable-use-license/)、[RBAC](https://docs.n8n.io/user-management/rbac/role-types/)、[API](https://docs.n8n.io/api/api-reference/)

## 11. Dify（langgenius/dify）

### 11.1 语言/技术栈与许可证
- 后端 Python 3.12（Flask 3 + Celery 5.6 + SQLAlchemy，api/pyproject.toml，v1.17.0）；前端 Next.js（web/）；基础设施 PostgreSQL/MySQL + Redis + nginx + 可选向量库。
- Go 辅助服务：dify-sandbox（代码执行沙箱，经 squid 代理出网）、dify-plugin-daemon（插件运行时）、新 dify-agent-backend + dify-agent-local-sandbox（独立内部网络强制经 squid 出网）。
- **许可证：Dify Open Source License = Apache-2.0 修改版 + 附加条件**：①未经书面授权**不得运营多租户环境**（一 tenant = 一 workspace）；②**不得移除/修改前端 LOGO 与版权信息**（不用其前端则不受限）；③贡献者同意官方可单方收紧/放宽协议、贡献代码可商用。交互设计受外观专利保护（© 2025 LangGenius）。[LICENSE](https://cdn.jsdelivr.net/gh/langgenius/dify@main/LICENSE)

### 11.2 运行时架构
- 服务拓扑（docker-compose）：api（Flask/gunicorn）、worker（Celery 全部队列）、worker_beat（周期调度）、web（Next.js）、sandbox、plugin_daemon、agent_backend、api_websocket（Socket.IO 协作编辑）、nginx、ssrf_proxy（squid）。
- 工作流引擎：api/core/workflow/（workflow_entry.py、graph_topology.py、node_factory.py、node_runtime.py、variable_pool_initializer.py、llm_node.py、human_input_*.py 等，源码树确认），节点类型齐全（llm/code/http-request/ifelse/iteration/loop/agent/tools/human-input 等）。
- 状态：PostgreSQL 持久化（Conversation/Message/App/Workflow，SQLAlchemy）+ Redis 缓存与 broker。
- 多轮上下文：TokenBufferMemory（按 token 窗口裁剪会话历史，extract_thread_messages）；新 Agent（beta）的"记忆"= 持久 note 文件 + 单会话上下文窗口。

### 11.3 子 Agent / 委派模型
- 工具节点可挂接内置/自定义工具与子应用；Agent runners 三种：base / cot / fc（function calling）；**多 agent 编排以 agent-strategy plugin 形式提供**（即把编排策略做成插件）。
- 新 Agent（beta）：拥有独立沙箱（可运行命令、装程序、读写文件），一次构建两用——独立聊天应用或工作流内节点；沙箱网络强制经 squid 白名单代理、JWE token 认证（compose 注释，安全细节未核实源码）。[new-agent 文档](https://docs.dify.ai/en/cloud/use-dify/build/new-agent/overview)
- 隔离级别：workspace 即 tenant；子调用权限传递细节未在抓取材料核实。

### 11.4 权限与批准模型
- 工作区成员角色 Owner/Admin/Editor（Owner 全权、Admin 管成员与模型供应商、Editor 增删改应用与知识库）；Service API 每应用独立 API key。
- **HITL**：Human Input 节点暂停工作流投递表单（Web/Email + 超时策略）；API 流程：SSE 收 human_input_required 事件与 form_token → Get/Submit Human Input Form → 恢复；源码 human_input_policy.py 定义 RecipientType 与 ApprovalChannel。[human-input 文档](https://docs.dify.ai/en/api-reference/guides/human-input-flow)

### 11.5 事件/流式设计
- SSE：response_mode=streaming，事件如 workflow_started/node_finished/ping 心跳（~10s），断线凭 workflow_run_id 重连。[streaming 文档](https://docs.dify.ai/en/api-reference/guides/streaming)
- WebSocket（Socket.IO + geventwebsocket）用于工作流协作编辑。**AG-UI：未发现官方支持**。

### 11.6 配置与自管理
- docker/.env.example + envs/ 分层（core-services/security/databases 等）；运行应用走 Service API；**通过 API 管理/修改应用与知识库的能力主要在 Console 侧（据社区说法，未核实）**；另有 Dify CLI；最低配置 2C4G。

### 11.7 扩展机制
- 插件市场 marketplace.dify.ai；插件由独立 Go 服务 plugin_daemon 隔离运行（签名校验）；插件类型 tool/model/agent-strategy/extension；50+ 内置工具；MCP（api/core/mcp）；可观测性对接 Langfuse/Opik/Arize Phoenix。

### 11.8 轻量化手段
- Docker Compose 约 10 个核心容器 + 可选向量库，2C4G 起步——是 12 个项目里部署形态最"重"的之一（多服务编排，与单二进制方案差距最大）。

### 11.9 引用
[仓库](https://github.com/langgenius/dify)、[docker-compose](https://cdn.jsdelivr.net/gh/langgenius/dify@main/docker/docker-compose.yaml)、[streaming](https://docs.dify.ai/en/api-reference/guides/streaming)、[human-input](https://docs.dify.ai/en/api-reference/guides/human-input-flow)、[new agent](https://docs.dify.ai/en/cloud/use-dify/build/new-agent/overview)、[agent-strategy plugin](https://docs.dify.ai/en/develop-plugin/dev-guides-and-walkthroughs/agent-strategy-plugin)、[成员管理](https://docs.dify.ai/en/cloud/use-dify/workspace/team-members-management)

## 12. Open WebUI（open-webui/open-webui）

### 12.1 语言/技术栈与许可证
- 前端 **Svelte**；后端 Python **FastAPI**（v0.11.3：uvicorn、pydantic 2、SQLAlchemy[asyncio] + aiosqlite/psycopg + alembic、python-socketio、pycrdt、redis、APScheduler、RestrictedPython、mcp 1.27.x）；后端 20+ routers（chats/ollama/openai/auths/users/models/knowledge/tools/functions/pipelines/tasks/automations/channels…）。
- **许可证：Open WebUI License（BSD-3 式条款 + 品牌保留条款）**：禁止在部署/分发中移除/遮蔽/替换 "Open WebUI" 品牌（名称/logo/视觉/文字标识），例外：(i) 30 天滚动期内终端用户 ≤50；(ii) 版权方书面许可；(iii) 企业许可；违反构成实质违约。
- **许可证演变史（已核实）**：MIT（©2023）→ 2025-01-10 改 BSD-3-Clause → 2025-04-18 加入品牌条款（当时含 50 用户豁免、fork grandfather 豁免、版权方可单方修订条款）；**现行文本合并为单条第 4 款，不再含 grandfather/可修订条款，保留 50 用户豁免**。贡献者需签 CLA。[LICENSE](https://cdn.jsdelivr.net/gh/open-webui/open-webui@main/LICENSE)、[LICENSE_HISTORY](https://cdn.jsdelivr.net/gh/open-webui/open-webui@main/LICENSE_HISTORY)

### 12.2 运行时架构
- 轻量后端 + 前端：FastAPI 聚合代理 Ollama 与任意 OpenAI 兼容 API（aiohttp 流式转发），自身也实现 OpenAI 兼容端点。
- **无 DAG 引擎**：agent loop 由上游模型的 function calling 驱动，Open WebUI 负责工具注册与执行；官方有 server-side tool calling 参考实现。
- 状态：SQLite（可加密）/PostgreSQL + Alembic；Redis 用于会话与多 worker 扩展；多轮上下文以持久化 chats/messages 表建模 + 跨会话 persistent memory + 知识库 RAG（9 种向量库、混合检索）。
- WebSocket 层：python-socketio + pycrdt（Yjs CRDT 协作）。

### 12.3 子 Agent / 委派模型
- **无原生 sub-agent/workflow 编排**；"Agents" = 基础模型 + 自定义指令/工具/知识叠加。委派靠扩展：Pipe Function 注册自定义"模型/agent"（含 manifold 多模型）、Workspace Tools、外部 MCP/OpenAPI 工具服务器、legacy Pipelines。

### 12.4 权限与批准模型
- RBAC：admin/user/pending + Groups + 细粒度 permissions；模型访问控制开关。
- API key / JWT 认证；Functions/Tools 在服务端执行任意 Python、**创建仅限管理员**，文档附严重安全警告；README 提及插件可构建 approval flows（细节未核实）。

### 12.5 事件/流式设计
- SSE：上游流式响应经 StreamingResponse 透传；WebSocket：socketio 事件总线（events.py 发布/订阅）。
- **AG-UI：原生不支持**（源码与官方文档 grep 均无）；社区有 Pipe 桥接项目 ag-ui-compatible-openwebui（据社区，未核实源码）。[ag-ui#477](https://github.com/ag-ui-protocol/ag-ui/issues/477)

### 12.6 配置与自管理
- 环境变量配置（OLLAMA_BASE_URL、WEBUI_SECRET_KEY、DATABASE_URL、ENABLE_API_KEYS 等）。
- **后端为完整 REST API**：官方文档原话——模型可"以声明方式管理（export/import/sync），**甚至可由 AI agent 通过脚本管理**"（/api/v1/models 段）；chats/knowledge 等路由亦可编程调用。[API 文档](https://docs.openwebui.com/reference/api-endpoints)

### 12.7 扩展机制
- **Functions 四种原语：Pipe / Filter / Action / Event**（按类名自动识别类型，Python 源码存库、运行时加载）；Tools（内置系统工具 + Workspace 社区工具）；**MCP 原生支持**（v0.6.31+，Streamable HTTP，经 MCPO 接 stdio）；OpenAPI 工具服务器；**Pipelines（独立服务）已被官方标记 legacy，新部署改用 Functions/Tools**。

### 12.8 轻量化手段
- pip/uv/Docker 单容器（ghcr.io/open-webui/open-webui:main），SQLite 默认零外部依赖起步；比 n8n/Dify 轻，但仍重于 Rust/Go 单二进制。

### 12.9 引用
[仓库](https://github.com/open-webui/open-webui)、[pyproject](https://cdn.jsdelivr.net/gh/open-webui/open-webui@main/pyproject.toml)、[functions](https://docs.openwebui.com/features/extensibility/plugin/functions/)、[MCP](https://docs.openwebui.com/features/extensibility/mcp)、[pipelines](https://docs.openwebui.com/features/extensibility/pipelines/)、[API](https://docs.openwebui.com/reference/api-endpoints)、[RBAC](https://docs.openwebui.com/features/authentication-access/rbac/roles)

---

## 13. 综合对比

### 13.1 Agent loop 设计模式对比与各自取舍

12 个项目可以归为 7 类 loop 模式：

**（1）自由 tool-calling 循环（OpenCode、Codex、Goose、Hermes、Letta Code）**
每次循环：组装消息（system prompt + 历史 + 工具定义）→ 调用 LLM → 若模型返回工具调用则执行并追加 observation → 下一轮；直到模型输出纯文本或达到 step 上限。
- 取舍：灵活性最高、模型能力释放最充分；代价是成本与步数不可控。各项目的约束手段：OpenCode 的 agent 级 `steps` 上限（到达上限强制输出总结，见 [Agents 文档](https://opencode.ai/docs/agents)）、Codex 的 turn 数限制与 approval 分级。
- 这是个人 Agent runtime 的**事实标准**，本目标项目主循环应选此模式。

**（2）网关化循环（Hermes）**
同一个 agent core 同时服务 CLI / TUI / 桌面 / Telegram / Discord / Slack / WhatsApp / Signal 等 20+ 个渠道，由单一 gateway 进程统一收发；外加内置 cron 调度器支持无人值守循环。设计不变量见其 [AGENTS.md](https://github.com/NousResearch/hermes-agent/blob/main/AGENTS.md)："core is a narrow waist; capability lives at the edges"（核心是窄腰，能力在边缘）。
- 取舍：一次实现多渠道复用，把"人在哪"与"agent 在哪"解耦；代价是网关层的事件/消息建模复杂度。对本目标（多前端 + 自管理）是最直接的组织样板。

**（3）事件流循环（OpenHands）**
循环不是函数调用栈，而是 event-sourced 流：agent 产出 Action 事件 → runtime 执行 → Observation 事件回流 → agent 继续。状态 = 事件日志的回放。
- 取舍：可审计、可恢复、可多前端消费；代价是事件模式（schema）设计负担重。与目标软件"Universal Event Store"构想天然契合，详见第 8 节。

**（4）角色/SOP 流水线（MetaGPT）**
把公司 SOP 建模为 Role 装配成的有向流水线（产品经理→架构师→工程师…），Action 是可观察步骤，按装配图确定性执行。
- 取舍：可预测、可复现，适合做"子任务流水线"；但自由对话能力弱、架构偏老旧（串行、内存态），不适合做主循环。

**（5）工作流 DAG 引擎（n8n、Dify）**
主循环不是 LLM 循环而是图执行引擎：节点图 → 队列调度 → worker 执行 → 分支/循环；LLM 只是其中一类节点。
- 取舍：可编排性强、执行可靠、可观测；代价是"自主性"受图拓扑约束。适合作为子 Agent 任务的实现载体，不适合作为自主主 Agent。

**（6）记忆优先循环（Letta V1/V2）**
循环的每一步先处理记忆：agent 把长期事实**写入** memory blocks（即系统提示词片段），会话历史放外部存储，每次推理只装配"当前相关"的上下文。V2（letta-code）延续该思想并加入"agent 改写自身记忆/技能/prompt"的自改进闭环。
- 取舍：上下文窗口压力最小、跨会话人格一致；代价是记忆编辑/召回需要专门机制与质量审计（`/doctor`）。详见第 5 节。

**（7）动作空间循环（Browser Use）**
循环发生在浏览器 DOM 动作空间内：模型输出结构化的浏览器动作（点击/输入/滚动/提取），框架执行并返回 DOM 状态 + 截图作为观察。
- 取舍：把不可控的浏览器交互变成可控动作空间；代价是延迟高、token 消耗大（DOM+截图）。只当目标软件需要浏览器能力时借鉴。

**（8）框架托管循环（Microsoft Agent Framework）**
框架提供 run 接口与会话生命周期，开发者用 **middleware 管线 + context provider + 可选 Harness**（电池全含层：todo/planning、agent modes、file memory、approval/observability 中间件）挂载业务逻辑；Workflows 提供图式/函数式两种编排（checkpointing、fan-out/fan-in），可把 workflow 包成 agent（`AsAIAgent()`）。与 AutoGen（已 maintenance mode）一脉相承但设计现代化得多（详见第 9 节）。
- 取舍：上手快、框架替你管循环与持久化；代价是被框架抽象约束。其"循环做成可插拔中间件管线"与"workflow 可降级为 agent"两个思路可借鉴到 Java。

### 13.2 子 Agent 创建与权限收缩的设计选项

**模板化 agent 定义（推荐照抄 OpenCode 模式）**
- OpenCode 的 agent 是**数据而非代码**：JSON（`opencode.json` 的 `agent` 块，带 `$schema`）或 Markdown 文件（`~/.config/opencode/agents/` 与 `.opencode/agents/`），字段包括 `mode`（primary/subagent）、`description`（供主 agent 路由）、`prompt`、`model`、`temperature`、`steps`、`permission`、`hidden` 等（[Agents 文档](https://opencode.ai/docs/agents)）。内置两个 primary（build 全权、plan 受限）与三个 subagent（general 除 todo 外全权、explore 只读、scout 只读）。
- 这意味着"按需求自由创建不同权限子 Agent"= 往目录里写一个带 frontmatter 的 Markdown 文件。对 Java 版：AgentDef 用 JSON Schema 校验的配置文件 + 目录扫描加载，成本极低。

**权限收缩的四种手段（按强度递增）**
1. 显式 permission 映射：每个 agent 对每类工具（edit/bash/webfetch…）声明 `allow | ask | deny`（OpenCode [Permissions](https://opencode.ai/docs/permissions/)；Codex 的 approval 分级类似）。
2. 工具白名单：只把部分工具注册给子 agent（OpenCode 的 task permissions；Hermes 的 subagent 工具集收缩——据文档）。
3. 只读 agent：read-only 是权限最小化最简单可靠的形式（OpenCode explore/scout；Codex 的 read-only approval）。
4. 隔离升级：同进程线程 → 子进程（Hermes 的子 agent + Python RPC；Letta 的 subagent 子进程；Goose 扩展即 MCP 子进程）→ 容器/沙箱（OpenHands 的 DockerWorkspace；Codex 的 sandbox 运行时；Hermes 的七种终端后端：local/Docker/SSH/Singularity/Modal/Daytona/Vercel Sandbox，见其 [README](https://github.com/NousResearch/hermes-agent#readme)）。

**关键结论**：
- **默认不需要进程级隔离**——同进程 child session + permission 映射即可覆盖绝大多数场景（OpenCode 的 subagent 就是同 server 内的 child session，session 树可导航）。
- 但架构上必须预留 **Executor 抽象**（本地执行 / 子进程 / 容器三种实现可切换），把"危险工具升级到沙箱"变成配置而非重构。Java 侧对应 ProcessBuilder / Docker API / 远程 SSH 三类实现。
- Hermes 的"Python 脚本经 RPC 调用工具、把多步流水线压成零上下文成本的一步"是子 Agent 上下文经济学的好设计（README 原话 "collapsing multi-step pipelines into zero-context-cost turns"）。
- 权限传递语义要明确：**默认继承父级、可逐项覆盖为 deny**（OpenCode 子 agent 默认继承主 agent 的 model 配置、权限可显式收紧，据文档）；"收缩"必须单向——子 agent 不能给自己提权。

### 13.3 上下文窗口管理（增量 compaction、external memory、Letta 的 memory-first）

**compaction 的一等公民化（OpenCode）**
OpenCode 把 compaction、title、summary 做成**隐藏系统 agent**（`hidden: true`、不可被用户在 UI 选择、自动触发）：长上下文由 compaction agent 压成摘要继续对话；session 摘要由 summary agent 生成。文档见 [Agents 文档](https://opencode.ai/docs/agents)。要点：压缩是一段普通 LLM 调用流水线，用配置而非硬编码。

**prompt-cache 不变量（Hermes）**
Hermes 的工程铁律（[AGENTS.md](https://github.com/NousResearch/hermes-agent/blob/main/AGENTS.md)）：长对话每轮复用缓存前缀，**任何对历史上下文、工具集、system prompt 的中途变更都会让缓存失效**；唯一的例外是 context compression。因此 slash command 对配置的修改默认"延迟生效（下次会话）+ 显式 `--now`"。这解释了"配置自管理"与"成本"的耦合——自管理必须缓存友好。

**external memory 三件套**
- Letta V1 的 memory-first 分层：core memory（persona/human 两段写入式块，随对话被 agent 编辑）、archival memory（外部库，按需 recall 检索）、recall memory（会话历史）；每步推理前装配，而不是全量重放（V2 在 letta-code 中延续并强化为"agent 编程式改写自身上下文"，见 [letta-code README](https://github.com/letta-ai/letta-code#readme)）。
- Hermes 的跨会话召回：FTS5 全文检索 + LLM 摘要（对应源码文件 `hermes_state_fts.py` / `hermes_state_compression.py`，本次调查已见文件存在，内部逻辑据 README 描述）+ Honcho 用户画像。
- Goose 有独立的 `goose-context-management` crate（Rust 工作区，本次调查已确认目录存在）。

**给 Java 版的结论**：分层记忆（热记忆写入式 + 温记忆摘要 + 冷记忆 FTS 检索）是语言无关的设计；SQLite FTS5（JVM 有成熟绑定）即可复刻 Hermes 方案；Letta 的"记忆即文件、文件进 git"（V2 的 MemFS）直接可抄且顺带解决回滚问题。

### 13.4 事件流与前端解耦的最小事件集设计

**最佳范本：OpenCode 的 client/server 拆分**（本次调查已核实 [Server 文档](https://opencode.ai/docs/server) 全部端点）
- `opencode` 启动 = TUI 客户端 + 本地 HTTP server 两个进程；`opencode serve` 可独立 headless 运行（默认 127.0.0.1:4096，支持 basic auth 与 CORS）。
- server 发布 **OpenAPI 3.1 spec**（`/doc`），SDK 由 spec 生成；TUI / Web / Desktop / IDE 插件全部是纯客户端。
- 事件流：`GET /event` 即 SSE 总线（首事件 `server.connected`，之后是 bus events）；`GET /global/event` 提供全局流。
- **权限请求是普通 HTTP 交互**：server 发 permission request（经 SSE 事件/会话状态），客户端 `POST /session/:id/permissions/:permissionID` 回 `{response, remember?}`——`remember` 让"批准一次"升级为"记住策略"，这恰好是 auto-approve 与逐次询问之间的连续谱。
- 动态 MCP：`POST /mcp {name, config}` 运行时增删；会话树：`/session/:id/children`、`fork`、`summarize`、`revert/unrevert` 全在 API 层。
- TUI 专属控制走 `/tui/control/next + /tui/control/response` 通道（control request），说明"通用事件流 + 客户端私有控制通道"两层结构。

**最小事件集建议**（综合 OpenCode / OpenHands event stream / AG-UI 规范思想，供目标项目参考）：
`session.*`（created/updated/closed）、`message.*`（assistant 消息 delta、完整消息、tool_call 开始/结果）、`permission.request / permission.response`、`state.snapshot`（断线重连对齐）、`error`、`heartbeat`。传输选 **SSE 单向流 + REST 写操作**（OpenCode 模式），比全双工 WebSocket 更简单、可缓存、可过代理；需要双向交互（如用户打断）时用 POST abort 即可。Java 侧：JAX-RS `SseEventSink` 或 Spring WebFlux，OpenAPI 单一事实源 + codegen 生成 SDK。

**一个值得注意的缺口**：本次调查中 n8n / Dify / Open WebUI **均无原生 AG-UI 支持**（各自的文档与源码检索均未发现；n8n、Open WebUI 仅有社区 issue/桥接项目，见 [ag-ui#369](https://github.com/ag-ui-protocol/ag-ui/issues/369)、[ag-ui#477](https://github.com/ag-ui-protocol/ag-ui/issues/477)）。即：现成平台反而没有覆盖目标软件的 AG-UI 边界——自研 core 的事件面若设计成 AG-UI 兼容，反而是差异化优势；OpenCode 的 SSE 事件面与 AG-UI 事件集的映射关系可作为子调查 A（协议栈）的输入。

### 13.5 配置自管理（agent 修改自身配置的安全闭环）

**先例盘点**（"agent 能否改自己的配置"这个问题的答案是：能，而且头部项目都在做）
- Letta V2 最激进：agent 被**设计为自配置**——改写自己的 memory blocks / skills / prompts，甚至通过 mods 改写 harness 自身；MemFS 把全部上下文放 git（可推送到 GitHub repo），变更天然可审计可回滚；`/doctor` 审计记忆质量（[letta-code README](https://github.com/letta-ai/letta-code#readme)）。
- OpenCode：配置是带 `$schema` 的 opencode.json；server 提供 `PATCH /config` 与 `POST /mcp`（动态增删 MCP）。
- Codex：config.toml 分层加载 + config.schema.json；hooks（hook_runtime.rs，源码级已确认文件存在）；skills/AGENTS.md 模块（core/src/skills.rs、agents_md.rs，源码级已确认）；MCP 连接管理（codex-mcp/src/mcp_connection_manager.rs，含 McpServerElicitation 用户输入请求）。
- Hermes：`hermes config set/get`、`hermes tools`；配置变更走"延迟生效 + --now"缓存友好路径（见 13.3）。
- Goose：Extension Manager 扩展可在会话中动态发现/启用/禁用扩展，Smart Extension Recommendation 按任务自动启用（仅当前会话）。
- n8n：公开 REST API `/api/v1` 可编程创建/修改 workflow、credential 等（本次已核实 API 文档资源列表）——"用 API 自举"的先例。
- Open WebUI：官方 API 文档原话——模型可"以声明方式管理（export/import/sync），甚至可由 AI agent 通过脚本管理"。
- Dify：运行应用走 Service API；管理/修改应用与知识库的能力主要在 Console 侧（据社区说法，未核实）。

**安全闭环六要素**（综合各项目做法，是设计建议而非某项目成品）：
1. **分层 + 合并规则**：全局 / 项目 / 会话三层配置，低层覆盖高层（OpenCode 的 config 层级，据文档）。
2. **schema 校验**：所有配置带 JSON Schema（OpenCode 的 `$schema` 指向官方 schema），Java 用 networknt json-schema-validator 等现成库。
3. **原子写 + 版本化回滚**：写临时文件 + 原子 rename；更进一步用 git 管配置目录（Letta MemFS 思路），每次自修改是一次 commit，可 diff 可 revert。
4. **生效策略**：默认延迟生效（下次会话 / 重启），高风险变更要求显式确认——既是缓存友好（Hermes 教训），也构成天然的"软审批"。
5. **高风险变更必须走权限通道**：增删 MCP server = 执行任意第三方代码，绝不允许静默通过；走 permission request（OpenCode 的 permissionID 端点模式），且支持 remember 策略。
6. **审计与自检**：变更日志 + 健康检查命令（Letta /doctor、Hermes hermes doctor）+ 并发保护（JVM `FileChannel.tryLock` 防多进程同时改）。

### 13.6 轻量化在 Java 语境下的启示

**各项目的轻量手段**：
- Codex：Rust 单二进制 + TUI，冷启动毫秒级（语言红利）。
- Goose：Rust crates 工作区，CLI 单二进制，桌面 UI 为可选物。
- OpenCode：TypeScript + **Bun** 运行时，npm 单包分发；core 是库，TUI/Web/Desktop 是可选 client——按需加载（Bun 与 npm 均已在仓库文件 bun.lock / package.json 确认）。
- Hermes：Python + uv 自包含安装；README 强调"跑在 $5 VPS / 近零成本的 serverless 上"，七种终端后端按需选用。

**哪些是语言红利、Java 不能照搬**：
- Rust/Go/Bun 的毫秒级冷启动与单二进制分发。JVM 常态启动 100-300ms+，但**有对应物**：jlink 定制运行时（砍掉 90% 无用模块）、AppCDS（类数据共享归档）、CRaC（checkpoint/restore，适合长驻服务的快速恢复）、GraalVM native-image（注意：反射/动态类加载是 AOT 大敌，插件体系必须用显式 SPI 而不是运行时扫类）、单文件 jar 与 jpackage。2026 年 Project Leyden 若已交付 AOT 优化（premain），也是红利（状态据社区说法，未核实）。
- Python/JS 的"用户脚本即工具"生态（Hermes 让用户写 Python 脚本经 RPC 调工具）——Java 里 jshell 太重，替代方案：嵌入式表达式（MVEL/Janino 慎用）或干脆保留"子进程脚本执行器"（Python/Node 作为可选依赖）。

**Java 语境下"轻"的正确姿势**（综合结论）：
1. **core 极小 + headless**：核心是一个没有 UI 的服务进程（OpenCode serve 模式），UI 全部外置；Java 版用 JAX-RS/Netty 单 jar + SSE。
2. **能力在边缘**：Hermes "窄腰"原则——新能力不进 core，做成 MCP server 子进程按需拉起、空闲即关（Goose 的 MCP-first 同思路）。
3. **启动预算留给可选项**：TUI（Lanterna/JLine）、Web UI（静态资源 + HTTP）都做成可选模块，多模块 Maven 工程按需装配。
4. **JVM 稳态红利**：virtual threads（Java 21）对应 Rust/JS 的 async runtime，且心智负担更小；GC 成熟度省掉大量内存工程。

### 13.7 可直接借鉴/抄袭的设计点清单 vs 需要为 Java 重造的设计点清单

**可直接借鉴（设计层，格式/协议可原样复用）**
| # | 设计点 | 出处 | 说明 |
|---|---|---|---|
| 1 | agent 模板化定义 + permission 映射 + 隐藏系统 agent | OpenCode | JSON Schema 校验 + 目录扫描，Java 零障碍 |
| 2 | headless server + OpenAPI + SSE + 权限请求 HTTP 端点 + remember | OpenCode | 整个 client/server 拓扑可照抄 |
| 3 | 审批/沙箱三分级 | Codex | read-only / auto-edit(workspace-write) / full-auto(danger-full-access) 近似分级 |
| 4 | 网关单进程多渠道 + cron | Hermes | 多前端 + 无人值守的组织样板 |
| 5 | 窄腰架构 + footprint ladder | Hermes | 扩展优先级：改现有→CLI+skill→服务门控工具→插件→MCP→新 core 工具 |
| 6 | prompt-cache 意识（延迟生效 + --now） | Hermes | 自管理与成本耦合的正确解法 |
| 7 | 写入式记忆 blocks + 记忆 git 版控 + 自配置设计 | Letta | MemFS 思路顺带解决回滚/审计 |
| 8 | event-sourced Action/Observation 循环 | OpenHands | 与目标"Universal Event Store"构想同构 |
| 9 | MCP-first 扩展（扩展即 MCP server） | Goose | 与协议栈结论完全一致 |
| 10 | workflow 即数据（JSON 可导出/版本化/API 自修改）+ 子流程节点 | n8n | 只抄设计不抄代码（许可证限制） |
| 11 | SOP/角色装配思想 | MetaGPT | 流程写成数据 |
| 12 | 浏览器动作空间抽象 | Browser Use | 仅当需要浏览器能力时 |

**需要为 Java 重造（语言生态差异，无法直接照搬）**
| # | 差距 | 说明与 Java 对策 |
|---|---|---|
| 1 | 单二进制/毫秒冷启动 | jlink + AppCDS + CRaC；GraalVM native-image 需为反射做 SPI 化改造 |
| 2 | 脚本即工具生态（Python RPC / Bun 脚本） | 子进程脚本执行器 + MCP；或嵌入式受限表达式语言 |
| 3 | TypeScript 前后端同源类型 | OpenAPI/JSON Schema 单一事实源 + codegen（Java 生态成熟） |
| 4 | Rust/JS 的 async 生态 | Java 21 virtual threads 直接对应 |
| 5 | npm 生态的"插件即包" | Maven 模块化 + ServiceLoader SPI（插件进程外化用 MCP，规避类路径问题） |
| 6 | 前端（Svelte/React） | 不变：前端照常用 JS，core 只出 REST+SSE（AG-UI 协议桥接） |

---

## 14. 各项目借鉴优先级排序表

针对目标软件（Java、多模块、MCP/A2A/AG-UI 兼容、可自由创建不同权限子 Agent、可自管理、轻量化）：

| 优先级 | 项目 | 许可证 | 最值得借鉴的点 | 原因 |
|---|---|---|---|---|
| ★★★ 高 | OpenCode | MIT | 整体 client/server 架构、agent 模板+权限映射、隐藏系统 agent、SSE 事件流 | 与目标形态重合度最高，MIT 可放心 |
| ★★★ 高 | Letta（letta-code） | Apache-2.0 | 自管理闭环（改写自身记忆/配置）、memory blocks、MemFS git 版控 | "agent 自管理"的先驱，思想可直接落地 |
| ★★★ 高 | Hermes Agent | MIT | gateway 多渠道、prompt-cache 不变量、窄腰+footprint ladder、subagent+多终端后端 | 个人 Agent runtime 的完整样板 |
| ★★★ 高 | Codex CLI | Apache-2.0 | 审批/沙箱分级、MCP 动态管理、skills/AGENTS.md 生态 | 权限模型最克制实用，协议生态对齐 |
| ★★ 中 | Goose | Apache-2.0 | MCP-first 扩展、permission modes、Rust 模块划分（goose-agent / goose-context-management / goose-mcp） | 模块边界划分可直接映射到 Maven 多模块 |
| ★★ 中 | OpenHands | MIT | event-sourced 循环、Docker 沙箱运行时 | 沙箱隔离的实现参考；整体偏重 |
| ★★ 中 | Microsoft Agent Framework | MIT | 框架托管循环 + middleware/harness、A2A/AG-UI 多协议接入、ADR 安全设计（批准内容类型、FIDES、CodeAct 沙箱） | 与目标协议栈相关；安全 ADR 有前瞻性；项目较新（~13k stars） |
| ★ 低-中 | MetaGPT | MIT | SOP/角色装配思想 | 架构偏老旧，只取思想 |
| ★ 低-中 | n8n | Sustainable Use License | workflow 即数据、子流程节点、API 自修改 | **只能抄设计不能抄代码**；许可证不可用于商业分发场景 |
| ★ 低 | Dify | Apache-2.0 修改版+条件 | 编排功能清单、HITL 审批 UX | 附加条件（禁多租户运营、禁去 LOGO）限制借鉴方式 |
| ★ 低 | Open WebUI | Open WebUI License 2025 | AG-UI 类前端交互、工具审批 UX | 品牌保护条款 + 许可证有过变更史，只做交互参考 |
| ★ 低 | Browser Use | MIT | 浏览器动作空间抽象 | 仅当目标需要浏览器能力时再看 |

---

## 附录 A：许可证一览（本调查核实，2026-09-08）

| 项目 | 仓库 | 许可证 | 附加条件 / 备注 | 依据 |
|---|---|---|---|---|
| Hermes Agent | NousResearch/hermes-agent | MIT | 无（Copyright 2025 Nous Research） | [LICENSE](https://github.com/NousResearch/hermes-agent/blob/main/LICENSE) |
| OpenCode | anomalyco/opencode | MIT | 无（Copyright 2025 opencode；默认分支 dev） | [LICENSE](https://github.com/anomalyco/opencode/blob/dev/LICENSE) |
| Codex CLI | openai/codex | Apache-2.0 | 无（Copyright 2025 OpenAI） | [LICENSE](https://github.com/openai/codex/blob/main/LICENSE) |
| Goose | aaif-goose/goose | Apache-2.0 | 无 | GitHub API 元数据 + 仓库 LICENSE |
| Letta / Letta Code | letta-ai/letta（落地页）、letta-ai/letta-code（实际代码） | Apache-2.0 | **附带 Brand Assets Exclusion**：Letta 名称/logo 等品牌资产不在 Apache-2.0 许可内，派生作品使用需书面许可；代码本身无附加条件 | [LICENSE](https://raw.githubusercontent.com/letta-ai/letta-code/main/LICENSE) |
| MetaGPT | FoundationAgents/MetaGPT | MIT | 无 | GitHub API 元数据 |
| Browser Use | browser-use/browser-use | MIT | 无 | GitHub API 元数据 |
| OpenHands | OpenHands/OpenHands | MIT | 无 | GitHub API 元数据 |
| Microsoft Agent Framework | microsoft/agent-framework | MIT | 无 | GitHub API 元数据 |
| n8n | n8n-io/n8n | **Sustainable Use License v1.0**（非 OSI 开源） | 仅限自有内部业务 / 非商业 / 个人用途；分发须免费且非商业；`*.ee.*` 文件需企业许可 | [LICENSE.md](https://github.com/n8n-io/n8n/blob/master/LICENSE.md) |
| Dify | langgenius/dify | **Apache-2.0 修改版** | ①不得运营多租户服务（一租户=一工作区，商用多租户需另获授权）；②不得移除/修改前端 LOGO 与版权信息；③贡献者同意官方可收紧/放宽协议 | [LICENSE](https://github.com/langgenius/dify/blob/main/LICENSE) |
| Open WebUI | open-webui/open-webui | **Open WebUI License**（BSD-3 式 + 品牌条款） | 禁止移除/修改 "Open WebUI" 品牌（任意部署规模）；例外：30 天滚动期内终端用户 ≤50、官方书面许可、企业许可；违反构成实质违约。演变史：MIT（2023）→ BSD-3（2025-01-10）→ 加品牌条款（2025-04-18，当时含 grandfather 条款，现行文本已去除） | [LICENSE](https://github.com/open-webui/open-webui/blob/main/LICENSE)、[LICENSE_HISTORY](https://cdn.jsdelivr.net/gh/open-webui/open-webui@main/LICENSE_HISTORY) |

**结论**：前 9 个（MIT/Apache-2.0）可放心借鉴设计乃至直接复用代码片段（注意：Letta 代码虽是 Apache-2.0，但其品牌资产被排除在许可之外，复用代码时勿带其品牌标识）；n8n 只能借鉴设计且注意自身分发场景合规；Dify 借鉴设计时避开其受限条款覆盖的形态（多租户 SaaS、去品牌化前端）；Open WebUI 只做交互/UX 参考。

## 附录 B：仓库规模与活跃度快照（GitHub API，2026-09-08）

| 项目 | stars | 主语言 | 默认分支 | 最近推送 |
|---|---|---|---|---|
| Hermes Agent | 242,954 | Python | main | 2026-09-07 |
| OpenCode | 205,620 | TypeScript | dev | 2026-09-07 |
| n8n | 203,640 | TypeScript | master | 2026-09-07 |
| Dify | 154,731 | TypeScript(web) | main | 2026-09-07 |
| Open WebUI | 151,223 | Python | main | 2026-09-07 |
| Codex CLI | 122,206 | Rust | main | 2026-09-07 |
| Browser Use | 112,908 | Python | main | 2026-09-05 |
| OpenHands | 86,438 | TypeScript（Agent Canvas 前端；核心 SDK 为 Python） | main | 2026-09-07 |
| MetaGPT | 70,251 | Python | main | 2026-01-21（活跃度下降） |
| Goose | 53,999 | Rust | main | 2026-09-07 |
| Letta | 24,641 | TypeScript（letta-code） | main | 2026-08-23 |
| Microsoft Agent Framework | 13,371 | Python | main | 2026-09-07 |

> 注 1：stars 只代表采用度代理指标；MetaGPT 主仓库近 8 个月无推送，研判时注意。
> 注 2（与任务书用户材料的核对，材料口径 2026-09-07）：Hermes ~242k、OpenCode ~205k、n8n ~203k、Dify ~153k、Open WebUI ~151k、Codex ~122k、OpenHands ~86k、MetaGPT ~69.5k、Letta ~24.6k 均与当前 API 数据一致或微涨；**Goose 明显高于材料口径（材料 ~46k，现 54k）**；Browser Use 材料 ~108k、现 112.9k。另已核实：block/goose 已迁移至 aaif-goose/goose（LF AAIF，2026-04-07）；microsoft/agent-framework 仓库存在（~13.4k stars）且 AutoGen 已进入 Maintenance Mode。
