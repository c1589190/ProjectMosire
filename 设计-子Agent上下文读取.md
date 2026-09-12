# 设计草案：子 Agent 上下文读取（D30 候选）

> 提出：2026-09-12 用户（"每个子 Agent 有识别 id 吗？按 id 让程序内 Agent 直接看对应子 Agent 的上下文，
> 也允许看自己的；读法要多——分条目、只看结果、按关键词筛选"）。
> 定位：**B 档（架构级）**——它是 U6 抓到的"子 Agent 结果回传通道缺失"的候选解，按 S1 §5.1 纪律
> **先合成一份设计再动手**，不分别打补丁。

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

## 三、权限判定（不新发明，落在 D27 两维上）

- 判据：`op ∈ capabilities(caller) ∧ target ∈ subtree(caller)`。
- **读侧同判**（2026-09-12 已裁）：`a2` 看不见 `a1-1`——否则"兄弟互不侵犯"只在写侧成立。
- 只读面：不引入"父改写子上下文"的写路径（要改写就是另一件事）。

---

## 四、跨进程怎么读（**待裁决**：两条路）

| 方案 | 做法 | 优点 | 代价 |
|---|---|---|---|
| **A 父直读子库** | 父按 `<dataDir>/subagents/<id>/events.db` **只读**打开（WAL 支持并发读） | 简单；**子体被 kill / 父重启后历史仍可读** | 父要碰文件路径；子体正写时读到的是快照 |
| **B 经 MCP 问子体** | 父向子体发"取上下文"请求，子体读自己的库返回 | 走协议、无共享文件假设 | 子体必须活着且应答；要扩协议面；子体卡住就没救 |

**建议 A 先行**（读侧无注入面、kill 后仍可追溯），B 留给"实时/在线"需求。**这条请裁**。

---

## 五、与 push 通道的取舍

- 拉（本设计）：不改子体、不加协议面 —— 代价是**父要自己决定何时去读**（无"完成通知"语义）。
- 推（子体完成时主动报告）：及时，但要扩协议 + 生命周期钩子。
- **建议先拉后推**；若日后要把"父等子完成"做成一等语义，再与 D27/D28（身份/血缘/预算）合一份设计。

---

## 六、附带要裁的三小项

1. **子体目录保留策略**：子体被 kill / 父退出后 `<dataDir>/subagents/<id>/` 是否保留（建议保留——读侧才有历史可读；清理策略另议）。
2. **读是否单独占一个能力位（capability）**：建议**占一个**——读与执行是两种权力，模板可以只授"读"。
3. **`summary` 的 token 汇总口径**：U6 见 `cacheWriteTokens:-1` 哨兵，需与二期 `usage` 报表口径统一（别各算各的）。

---

## 七、落地顺序（等 §四/§六 裁决后开工）

1. **Brain**：`AgentContextReader`（读子库 `events` 表；五视图 + 分页 + 筛选） + 离线单测；
2. **Brain**：注册为工具 + 接 D27 两维判定（若 D27 未落地，先做"主 Agent 读子树"的最小判定，或与 D27 同批）；
3. **Main**：装配（子库路径解析）；
4. **使用模式验收（U6 回归场景）**：父 spawn 子 → 父
   `read_agent_context(target=<id>, view=results)` → **拿到子体原文**；`view=summary` 能一眼看出"已完成/结果是什么"。
