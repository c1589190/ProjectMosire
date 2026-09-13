# 设计：子 Agent 终局与"等/取"口（D28 完成语义 / U6 收口）

> 制定：2026-09-13。范围 = `开发计划-三期-第三小段.md` §二 的 **B 块**（终局语义）+ **C 块**（异常终局在模型面可见）。
> 现状事实来源 = 2026-09-13 只读调查（锚在**符号名**上，行号会漂；A 块正在改同目录的五个文件）。
> 与 A 块的关系：`close()` 的"由深到浅"与 `wait`/`list`/`read` 的判据**依赖 A 块的 `lineagePath` 与 `subtree` 判定** ⇒ 本块**排在 A 之后**。

---

## 一、问题（三条，都是实跑抓到的）

1. **子体跑完不退出**：`SubagentProcessMain.execute` 在链接形态下**主动阻塞**在 `transport.whenClosed().get()`（等一个没人会发的"关停信号"）；U6 实测答完后空转 ≥171 s，两次 `list_sub_agents` 都报 `RUNNING`。
2. **终局原因无处落**：`TurnResult(stopReason, turns, toolCalls, text)` 是 `SubagentProcessMain.execute` 的**局部变量**，离开方法即丢；父子两侧事件库都没有它。`StopReason` 取值：`FINISHED / TURN_LIMIT / TOOL_CALL_LIMIT / TIME_BUDGET / QUOTA / LLM_ERROR / CANCELLED`。
3. **父侧判不出"为什么没了"**：`LaunchedSubagent` 只透出 `isAlive()`（**没有退出码**，码只以字符串形式藏在 `exitDiagnostics()` 里）；`onChildExited` 对 `RUNNING→退出` 一律记 `FINISHED`，只在日志里 `abnormal=true` ⇒ **模型面看不到异常终局**（C 块要修的就是这条；D2 当初只修到运维面）。

---

## 二、设计

### 2.1 子体终局记录协议（B 块的前置依赖）

子体在 `chat()` 返回之后、退栈之前，把终局写进**自己的** `events.db`（`SqliteEventStore.append`，与既有口径同构）：

```
type    = agent.lifecycle
action  = "finished"
payload = {"action":"finished","stopReason":"<枚举名>","turns":N,"toolCalls":M}
```

- **先落库 flush、再退栈**（`AgentRuntime.close()` 之前；库没 flush 成功 ⇒ 记日志并按"未留下终局记录"处理，**不许**静默吞）。
- **为什么用子库**：跨进程读物**已经存在**（`AgentContextReader` / `SqliteEventStore.openReadOnly`），不需要新开一条线上契约（备选方案"新增 MCP 回报"被否：多一条契约、收益相同）。
- **兼容**：老库/老事件（无 `stopReason` 键）⇒ 父侧按"未留下终局记录"处理，不编造。

### 2.2 即退（去掉阻塞）

`SubagentProcessMain`：删掉链接形态的 `transport.whenClosed().get()` 阻塞，`chat()` 返回 ⇒ 落 2.1 ⇒ `return 0`。

- 今天**非链接形态本就即退**（`chat` 返回即 `return 0`）——本改动只是把链接形态拉齐，不是新语义。
- **`kill` 的竞态照实记**：父侧 `terminate` 先关 server 再走三层关停，子体**可能来不及落库** ⇒ 父侧按 `KILLED` 记、**不假装有 `stopReason`**（诚实边界）。注意：被杀的子体现在也 `return 0`（EOF 让 `whenClosed()` 正常返回）⇒ **退出码不能当判据**（这正是 2.3 用"事件有无"而不是"码是否为零"的原因）。
- `ParentWatchdog`（父死自杀，已有件、**今天零接线**）不在本块范围：父子同进程组，父退子会被组信号带走（账本已澄清"不是孤儿"）。

### 2.3 父侧终局判定（`onChildExited` 重写）

**判据 = 子库终局事件的有无**，不是退出码：

| # | 观测到 | 终态 | 理由 |
|---|---|---|---|
| 1 | `RUNNING` 退出 + 子库有终局事件 | `FINISHED` + **`stopReason`/`turns`/`toolCalls` 入档** | 子体自己交代了它为什么停 |
| 2 | `RUNNING` 退出 + **无**终局事件 | `FAILED`（reason `"未留下终局记录"`）+ 退出码 | 正常路径**一定**先落库后退栈；没有 ⇒ 异常退栈（崩了/被外部杀了） |
| 3 | `TERMINATING` 退出 | `KILLED`（**无 `stopReason`**） | 既有口径，逐字保留 |
| 4 | `SPAWNING` 退出 | `FAILED`（`"subagent 退出于确认存活之前"`） | 既有口径，逐字保留 |

- 与既有 javadoc 的冲突**当面说清**：原文担心"非零一律翻 FAILED 制造状态谎言"——那是**没有终局记录**的时代。本块把"子体先落库后退栈"变成协议义务，**缺失**才异常 ⇒ 谎言风险由协议消除，不是被忽略。
- **退出码需要透出**：`LaunchedSubagent` 增 `OptionalInt exitCode()`（生产实现 `SubProcessExecutor` 从 `ManagedProcess` 取；拿不到 ⇒ `OptionalInt.empty()`，**不许编 0**）。这是本块**唯一**的接口新增。
- LLM 失败**不算异常终局**：`AgentPipeline` 已把它收敛成 `StopReason#LLM_ERROR` 的正常回合 ⇒ 走规则 1，`stopReason=LLM_ERROR` 如实呈现（模型面能看到"模型坏了"，这正是 C 块要的）。
- **两条收束路径的竞态要写死**（`terminate()` 的 `awaitDead` 与 watcher 线程的 `onChildExited` 可能同刻到达）：**status 已是终态时不得重复迁移**（`canTransition` 里 `KILLED→FAILED` 是非法迁移，靠它兜底不算设计）；且**若子库有终局事件而状态停在 `TERMINATING`，以事件为准记 `FINISHED` + `stopReason`**——子体确实跑完了这一轮，谎报 `KILLED` 比"kill 请求落空"更糟。这条要配用例。

### 2.4 落两处：内存 + 事件

- **内存**：`SubagentManager` 侧 `Map<String, TerminalOutcome>`（`record TerminalOutcome(String stopReason, Integer turns, Integer toolCalls, Integer exitCode)`，无值的字段为 `null` ⇒ 序列化时不写键，**不填假值**）。
- **事件（父库）**：`lifecyclePayload` 增可选键 `stopReason` / `turns` / `toolCalls` / `exitCode`（有才写）。
- **`SubagentInstance` 不加字段**：终局是"父观测到的结果"，不是实例的配置身份；`status` 仍是唯一状态来源（避免第二个状态来源），也避免再churn 一个带兼容构造的 record（A 块刚加过 `lineagePath`）。

### 2.5 `wait_sub_agent(instanceId, timeoutMs)`

- 语义：等到**终态**或超时返回。返回体：`status` + 终局字段（有则带）+ `waitedMillis`。**超时不是错误**（返回当前 `status`，模型自己决定再等还是 kill）。
- 实现：把 `awaitDead` 的轮询骨架升成公开口 `awaitTerminal(instanceId, timeoutMillis)`（100 ms 轮询，**不占锁**），`awaitDead` 保留为它的特例（kill 路径继续用）。
- 判据：**A 块的 `subtree` 判定**（能否等一个实例 = 能不能管它），`main` 看全部。

### 2.6 `kill` 与 `list` 的终局可见

- `kill`：今天返回"已发出子 Agent 终止请求: id"（无终态）。改为**等一小段确认**（复用 `KILL_CONFIRM_MILLIS`）后返回：已 `KILLED` ⇒ 报 `KILLED`；仍 `TERMINATING` ⇒ 照实说"已请求，尚未确认"（**不谎报**）。
- `list`：每行加终局字段（`stopReason` 等，有才带）——**这是 C 块"模型面区分正常/异常终局"的落点**（今天只有 `status` 的 `name()`）。

### 2.7 `close()` 由深到浅

`close()` 现在按 `instances.values()` 的 `HashMap` 序遍历（**无序**）。改为按 `lineagePath` 段数**降序**（深的先关；`lineagePath` 为空的老记录排在**最后**，不与深层抢序）。理由：先关子孙再关祖辈，别让"祖辈先死 ⇒ 子孙的父侧链路断在半路"。仍是 `terminate(id)` 同一路径，异常吞成日志（既有口径）。

---

## 三、不做（边界）

1. **不做保活/复用/再派活**（子体即退是既定裁决，见计划 §〇）。
2. **不做"父给子体发消息"**（无双向控制通道）——`wait` 是**只读等待**，不是交互。
3. **不做预算落库强一致**（M3）。
4. **不改退出码语义**（子体正常路径一律 0；异常路径 1；`Main.agent` 参数错 2）——判据是事件而不是码。
5. **不动 `ParentWatchdog` 接线**（组信号已覆盖"父死子亡"）。

---

## 四、判别性用例（每条"变异即转红"）

1. **子体即退**：真链接形态跑一个短目标 ⇒ 进程在 `chat` 返回后**自行退出**（对比：变异回"保留 `whenClosed` 阻塞" ⇒ 用例超时转红）。
2. **终局事件先落库**：子体退栈后，子库里有 `action=finished` 且**带 `stopReason`/`turns`/`toolCalls`**；把落库挪到退栈之后（或删掉）⇒ 用例转红。
3. **异常终局可分**（两个混淆项分开验）：
   - 子体**正常跑完** ⇒ 父侧 `FINISHED` + `stopReason=FINISHED`（**不是** FAILED）；
   - 子体**被杀/异常**（无终局事件）⇒ 父侧 `FAILED`/`KILLED`，模型可见面**能区分**"跑完了"与"没留下记录"。
4. **`wait` 三态**：等到终态（有 stopReason）/ 超时（返回当前 status，**不报错**）/ 判据外（不同血脉 ⇒ `SUBTREE_DENIED`）。
5. **`close()` 顺序**：两个实例（父 + 孙）都活着时 `close()` ⇒ **孙先于父**收到关停（变异：改回无序 ⇒ 用例转红）。
6. **退出码透出**：`exitCode()` 拿不到时**不编 0**（`OptionalInt.empty()` ⇒ 事件里不写键）。

## 五、风险

1. **子体即退会改变"桥的存活期"**：父侧任何"以为子进程还活着"的假设（例如将来加的双向通道）都要重查——本块只改这一处，风险面清。
2. **A/B 交界**：`wait`/`list` 判据依赖 A 块的判定；A 未落地前 B 不能独立验收。
3. **`stopReason` 是模型可写面吗？**——不是。它由子体**运行时**写（`TurnResult` 的来源是流水线自己的计数器），模型只能间接影响（多跑几轮）；但**别把它当可信输入的对外契约**（跨 Agent 面若日后复用，需重判）。
