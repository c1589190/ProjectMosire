# 设计草案：子 Agent 上下文读取（D30 候选）

> 提出：2026-09-12 用户（"每个子 Agent 有识别 id 吗？按 id 让程序内 Agent 直接看对应子 Agent 的上下文，
> 也允许看自己的；读法要多——分条目、只看结果、按关键词筛选"）。
> 定位：**B 档（架构级）**——它是 U6 抓到的"子 Agent 结果回传通道缺失"的候选解，按 S1 §5.1 纪律
> **先合成一份设计再动手**，不分别打补丁。
>
> **取代指针（2026-09-13）**：读侧隔离口径已被 `设计-身份与血缘.md` §四取代（`noExport` 去掉、改由 subtree 判定守）；
> 本文其余部分仍是该工具视图契约的现行说明。

---

## 一、事实核对（代码 + U6 实跑，非推测）

| 事实 | 证据 |
|---|---|
| **每个子 Agent 确实有识别 id**：`instanceId`（实测形态 `general-assistant-d8231100fd948a4d` = 模板 id + 短随机后缀） | `SubagentManager.java:130` 生成；`SubagentInstance.instanceId` |
| 该 id **已是全链路主键**：子体事件的 `agent` 列 = 它、`correlation_id` = 它 | U6 实跑：子库事件 `agent=general-assistant-d8231100fd948a4d` |
| **子体的上下文落在子自己的库**：`<dataDir>/subagents/<instanceId>/events.db` 的 `events` 表 | `App.java:423`（子目录根）+ `AgentCommand.argv`（`--id`）；U6 实测路径存在 |
| 子体上下文**不是**会话库形态：`SubagentProcessMain` 用 6 参 `AgentRuntime`（**不接** `ConversationStore`）⇒ 无 `messages` 行 | `SubagentProcessMain.java:121-122`；U6 实测子库仅 3 条事件 |
| 子体上下文里有什么 | `conversation.turn`（**input/output 原文**）、`llm.call`（model/token）、`tool.call`/`tool.result`、`agent.lifecycle` |
| 父侧如今**只有 4 字段快照**（`list_sub_agents` → instanceId/templateId/depth/status），**没有**指向子体上下文的任何通道 | U6 实跑原文 + `SubagentOrchestrationTools` |

⇒ 用户提案的可行性：**成立**。id 已存在、上下文已落库、缺的只是"一条读的路"。

---

## 二、工具面设计：**一个工具、多视图**（拉模式）

工具名候选 `read_agent_context`：

| 参数 | 取值 |
|---|---|
| `target` | `self` 或某个 `instanceId`（**允许读自己**——用户明确要求；`self` 恒允许） |
| `view` | `summary`（缺省）/ `turns` / `results` / `search` / `events` |
| 分页 | `limit` + `sinceSeq`（返回体附 `nextSinceSeq`，供翻页） |
| 筛选 | `q`（关键词，substring——**CJK 不指望分词**）、`type`（事件类型过滤） |

**五个视图各自的用途**：

| view | 返回 | 用途 |
|---|---|---|
| `summary` | 状态/深度/模板/goal/回合数/**最后一条 output**/token 汇总/首末时间 | 父的"一眼看结论"（U6 缺的就是它） |
| `turns` | 逐条：seq / 时间 / 输入 / 输出 | 分条目回看全过程 |
| `results` | **只看结果**：逐条 `conversation.turn` 的 output 文本 | "只要答案" |
| `search` | 关键词命中的条目（可选按 `type` 限定） | 长上下文里捞点东西 |
| `events` | 按类型取原始事件 | 排查（工具调用、记账、失败路径） |

**返回纪律**：结构化 + 超长文本按字节截断并标注截断；**不返回**任何可能含密钥的原始请求体（D23/D24 不变）。

---

## 三、权限判定（**已查到硬阻塞，降级为"主子句柄"**）

**先查证，再定案**（2026-09-12 控制者查码，非推测）：

| 想要的东西 | 代码里的实际情况 |
|---|---|
| 判"是不是我子树里的" | `SubagentInstance` **只有 `depth`、没有 `parentId`**（`SubagentInstance.java`）⇒ **血缘不可算**：知道层级，不知道谁生的谁 |
| 判"调用者是谁" | `ToolContext.caller` 是 `AccessToken` 三级枚举（GUEST/DEFAULT/SYSTEM），**主 Agent 与子 Agent 同为 `DEFAULT`**（`AccessToken.java` 类注释明写）⇒ **调用者身份不可辨** |
| D27 两维落地了吗 | **没有**：全仓 `grep subtree\|capabilities(` 只命中无关的 `ModelCapabilities`（模型能力表） |

⇒ `op ∈ capabilities(caller) ∧ target ∈ subtree(caller)` 这条判据**今天判不了**（第二维无输入、第一维的 caller 也认不出）。
更糟的是**读工具一旦注册就会被外发**：`AgentToMcpServer.start(tools, …)`（`App.java:252`）把**整个 ToolRegistry** 交给子体当 MCP 桥，
`--mcp-expose` 一开，子体手里就有 `list_sub_agents` 这类编排工具的副本（U6 实测：`orch-probe` 子体列出了桥接进来的 3 个工具）。
⇒ 直接注册 `read_agent_context` = **每个子体都能读别家子体的上下文**，正是 D27 读侧明令禁止的事。

**因此本工具的判定降级为「主子句柄」，并把 D27 的缝留在一处**：

1. **不可外发**：工具带"禁外发"标记，MCP 桥不转发它（子体拿不到句柄 = 读权力不外包）。**这一条是新增的小改**（`ToolSpec`/`AgentToMcpServer` 各加一个布尔）。
2. **能力位**：`AgentPermissionSet.isToolAllowed("read_agent_context")` 判一次（DEFAULT 下模板可只授读、不授读 ⇒ §六.2 的答案是"占"）。
3. **单一判定点**：`ContextAccessJudge.judge(caller, target)` —— **D27 落地时只改这一处**（补 lineage + 真身份），工具与视图层不动。
4. **诚实口径**：工具描述里写明"当前仅对**同进程已知子体**开放；跨 Agent 血缘判定待 D27 落地"——不许把"暂不支持"写成"已按权限模型保护"。

> **读侧同判**（用户 2026-09-12 已裁）：`a2` 看不见 `a1-1`——在本降级方案里由第 1 条**结构性地**保证（子体根本没有这个工具），
> 而不是靠判定；D27 落地后第 3 条会把它升级成真判定。
> **只读面**：不引入"父改写子上下文"的写路径（要改写就是另一件事）。

---

## 四、跨进程怎么读（**待裁决**：两条路）

| 方案 | 做法 | 优点 | 代价 |
|---|---|---|---|
| **A 父直读子库** | 父按 `<dataDir>/subagents/<id>/events.db` **只读**打开（WAL 支持并发读） | 简单；**子体被 kill / 父重启后历史仍可读** | 父要碰文件路径；子体正写时读到的是快照 |
| **B 经 MCP 问子体** | 父向子体发"取上下文"请求，子体读自己的库返回 | 走协议、无共享文件假设 | 子体必须活着且应答；要扩协议面；子体卡住就没救 |

**裁：A 先行**（读侧无注入面、kill 后仍可追溯），B 留给"实时/在线"需求。
A 的实现纪律：**只读连接**（`jdbc:sqlite:file:<path>?mode=ro`，不建表、不迁移、不写任何东西——子体可能正在写同一文件；
现有 `SqliteEventStore.open()` 会跑 schema 初始化，故须新增只读工厂或跳过 initialize 的路径）；子库不存在 → 响亮报错，不静默返回空。

---

## 五、与 push 通道的取舍

- 拉（本设计）：不改子体、不加协议面 —— 代价是**父要自己决定何时去读**（无"完成通知"语义）。
- 推（子体完成时主动报告）：及时，但要扩协议 + 生命周期钩子。
- **建议先拉后推**；若日后要把"父等子完成"做成一等语义，再与 D27/D28（身份/血缘/预算）合一份设计。

---

## 六、附带要裁的三小项

1. **子体目录保留策略**：**裁：保留**（子体被 kill / 父退出后 `<dataDir>/subagents/<id>/` 不删）——读侧才有历史可读；
   清理留作独立话题（增长无界，须在 D27/D28 合批时给配额）。
2. **读是否单独占一个能力位（capability）**：**裁：占一个**（`read_agent_context` 一个工具名，走 `isToolAllowed`）——读与执行是两种权力，模板可只授"读"。
3. **`summary` 的 token 汇总口径**：U6 见 `cacheWriteTokens:-1` 哨兵，**须与二期 `usage` 报表口径统一**（别各算各的）；
   `-1` 是"无此缓存档"而非"少写"，`summary` 要么标注口径、要么按同一套换算，**不许直接求和**（求出来是负数）。
   ⚠ 落地时以二期报表现有实现为准对齐，本稿不新发明口径。

---

## 七、落地顺序（§四/§六 已按推荐值裁定，用户可否决）

**已查明的实现接口**（照抄，省得再找）：

| 要用到的东西 | 在哪 |
|---|---|
| 子库路径 | `<dataDir>/subagents/<instanceId>/events.db`（`App.java:423` 已按此缀拼给子进程） |
| 工具注册点 | `App.java:402` `new BuiltinToolSource("builtin", SubagentOrchestrationTools.of(manager))` |
| 工具形态 | `AgentTool`：`name()/description()/jsonSchema()/spec()/execute(ToolContext)`（`SubagentOrchestrationTools:176-197`） |
| 调用者身份/权限 | `ToolContext.caller`（只有三级 `AccessToken`）+ `ToolContext.permissions`（`AgentPermissionSet.isToolAllowed`） |
| 工具配置注入点 | `ToolContext.config`（**这是运行时给工具注入自身配置的既定缝**，子库根路径走它，别走构造器全局） |
| 事件读取 | `EventQuery(agent,type,correlationId,beforeSeq,limit)` → 按 seq **倒序**返回；**没有正向游标**（本稿承诺的 `sinceSeq/nextSinceSeq` 需要加法式扩展，或读侧自行过滤+反转） |
| 外发面 | `AgentToMcpServer.start(tools, …)` 把整个 ToolRegistry 交给子体（`App.java:252`）⇒ 必须加"禁外发"标记 |

**步骤**：

1. **AgentLib**：`SqliteEventStore` 加**只读**打开路径（`mode=ro`，跳过 schema 初始化）；
   `ToolSpec` 加"禁外发"位；`AgentToMcpServer` 尊重该位（**这一条是安全必需**——不加，子体就能读别家上下文）。
2. **Brain**：`AgentContextReader`（五视图 + 分页 + 筛选 + 截断）+ `ContextAccessJudge`（唯一判定点）+ 离线单测
   （判别性：把"禁外发"标记拿掉 → 桥接用例必须转红）；
3. **Brain**：注册 `read_agent_context` 进 `SubagentOrchestrationTools`；
4. **Main**：装配（子库根路径经 `ToolContext.config` 注入）；
5. **使用模式验收（U6 回归场景，用 `scripts/usage-test.sh` 跑）**：父 spawn 子 → 父
   `read_agent_context(target=<id>, view=results)` → **拿到子体原文**；`view=summary` 能一眼看出"已完成/结果是什么"；
   **并验一条反向**：子体那侧 `read_agent_context` **不可见**（禁外发生效）。
