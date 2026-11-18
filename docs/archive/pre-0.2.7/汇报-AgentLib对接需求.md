# 汇报：simos 对接需求的实现

> **需求来源**：`~/SimulatorMosire/docs/superpowers/specs/2026-09-22-agentlib-llm-requirements.md`
> **实现位置**：`~/ProjectMosire/AgentLibMosire`
> **日期**：2026-09-22
> **配套文档**：`安装报告-AgentLib.md`（给 simos 侧直接照抄的那份）

---

## 〇、结论速览

| 类别 | 结论 |
|---|---|
| 必改项 | **A1 / A2 / A3 / A4 / A5 全部落地**（A5 是"裁定"而非"实现"，裁定结果见 §三） |
| 便利项 | **B1 / B2 / B3 / B4 全部提供** |
| 密钥红线 | **未破**：密钥值不进日志 / 异常 / 事件 / argv / stdio；读取只打印路径与长度 |
| 模块边界 | **未破**：改动全在 AgentLib；Brain / Main 未动（装配入口的重复实现由 simos 与宿主各自收敛） |

**与 simos 期望不同的两处，先说清楚**（都在 A1 上）：

1. **不是无条件并入文本**，而是"**只在 `content` 空且无工具调用时**并入正文"（§A1 详述理由）。
2. **不是抛错**：默认不抛（simos 要的就是"拿到那段文本"），但**同时**在 `LlmResponse` 上保留独立的 `reasoning()` 字段与 `ReasoningDisposition` 标记，且提供一个 `reasoningOnly()` 让调用方主动选择"当成未完成"的语义。**三种行为都可得，且每一种都可观测。**

---

## 一、必改项

### A1 ★★ `reasoning_content` 必须被处理

**落点**：`io.mosire.agentlib.llm.LlmResponse`（新增 `reasoning()` / `ReasoningDisposition` / `reasoningFolded()` / `reasoningOnly()`）、`OpenAICompatibleLlmClient` 的 SSE 累积与收尾段。

**实现的语义**（一句话）：**`content` 空、且没有工具调用、且 `reasoning_content` 非空 → 把 reasoning 折进正文**；其余情况 reasoning 一律原样留在独立字段里，绝不丢。

| 供应商返回 | `textPart()` | `reasoning()` | `reasoningDisposition()` |
|---|---|---|---|
| content 空 + reasoning 有 + 无工具调用 | **是那段 reasoning** | 同一段 | `FOLDED` |
| content 与 reasoning 都有 | 只有 content | 那段 reasoning | `SEPARATE` |
| content 空 + reasoning 有 + **有工具调用** | 不含 reasoning | 那段 reasoning | `SEPARATE` |
| 完全没有 reasoning | content | 空串 | `ABSENT` |

字段名同时认 `reasoning_content` 与 `reasoning`（不同供应商叫法不同）。**但同一个 delta 里只吃一个**（`reasoning_content` 优先）：两个键在同一帧里同时出现，是网关把字段照抄一份的形态——都吃就等于思维链累两遍，而它一旦折叠进正文，正文也就重复了一遍，**这是安静地错**。跨 delta 交替用两种写法仍然照吃（那才是"换个网关"的真实形态）。

**为什么是"条件折叠"而不是无条件折叠**（这是与 simos 倾向的唯一分歧，理由要说透）：

- `LlmResponse.assistantMessage()` 是要**回灌进对话历史**的。把思维链当正文回灌，会让下一轮模型看见"我上一条是这么说的"，而它其实没说过——污染后续轮次，且症状是"模型越来越绕"，极难归因。
- **有工具调用时更明显**：那时的"意图"在工具调用里，再折出一段假正文，等于替模型编了一句它没说的话。
- 折与不折的判据只有一条：**"调用方要的那段文本，供应商到底给没给"**。给了（content 有）就不折；没给而 reasoning 里有，才折。

**为什么默认不抛错**：simos 的诉求是"我要一段文本"。供应商明明产出了内容，抛错等于逼每个调用方写一层容错，而**绝大多数调用方其实乐意拿到 reasoning 当答案**。要"当成未完成"的调用方用 `reasoningOnly()`——它把选择权交回调用方，而不是替它决定。

**"绝不静默返回空文本"怎么保证**：`LlmClient.text()` 有三条 `NO_TEXT` 出口——① 只有工具调用（消息里点明：要一段文本就别给模型工具目录）；② **有文本部件但全是空白**（`LlmResponse.text("")` 这类手工构造可达，例如模板步文本为空）；③ 文本与工具调用都没有（供应商空产出）。判据是 `isBlank()`，**不存在返回空串或纯空白串的路径**；空白那条消息只报字符数、不回显内容。

**构造期不变式**：`reasoning` 为空 ⟺ `disposition == ABSENT`，两者不一致直接 `IllegalArgumentException`（防止有人构造出"有标记没内容"的假象）。

---

### A2 ★ `temperature` / `maxTokens` / `extraBody`

**落点**：新增 `io.mosire.agentlib.llm.Sampling`；`LlmRequest` 变为 `(messages, tools, sampling)`（保留 `(messages, tools)` 与 `(messages)` 两个兼容构造器，老调用点逐字不用改）。

```java
new LlmRequest(messages)
    .withTemperature(0)                       // 判决场景要可复现
    .withMaxTokens(256)                       // 约束推理模型的花费
    .withSampling(Sampling.withExtraBody(Map.of("top_k", 20)));  // 网关私有字段
```

- **`temperature = 0` 会被真的写进请求体**——这是需求点名的验收条款，实现上它是一个合法值而**不是"未设置"**，有用例逐字断言请求体里有 `"temperature":0`。
- `extraBody` **原样透传**（值经 Jackson `valueToTree` 保结构）。
- **保留键响亮拒绝**：`model` / `messages` / `stream` / `stream_options` / `tools` / `temperature` / `max_tokens` 出现在 `extraBody` 里 → **构造期** `IllegalArgumentException`。理由：静默接受会更糟——用户以为"我在 extraBody 里改了 model"，实际发出去的还是路由里那个，**两处真相不一致**。
- 形态校验只管"有限且非负"（温度）、"为正"（maxTokens），**不设 0..2 这类上界**：各供应商合理区间不同，本库没有权威可依，替用户设一个假权威比不设更坏（供应商会用自己的校验响亮拒绝）。

---

### A3 ★ Provider 配置的表达力：`timeout` 与协议

**落点**：新增 `LlmTransport(protocol, connectTimeout, readTimeout)` 与 `LlmProtocol`；`ModelRoute` 变为 `(name, baseUrl, model, credentialsRef, transport)`（4 参构造器保留，老调用点不用改）。**超时从客户端构造器参数搬到了路由上**——它本来就该跟着 provider 走，而不是跟着"这一个客户端实例"走。

```json
{
  "llm": {
    "routes": {
      "deepseek": {
        "baseUrl": "https://api.deepseek.com/v1",
        "model": "deepseek-chat",
        "credentialsRef": "keys.deepseek",
        "protocol": "openai-compatible",
        "timeoutMs": 120000,
        "connectTimeoutMs": 10000,
        "capabilities": { "toolCalling": true, "reasoning": true, "maxContext": 65536 }
      }
    }
  }
}
```

| 配置键 | 缺省 | 说明 |
|---|---|---|
| `protocol` | `openai-compatible` | 写成别的（如 `anthropic`）→ `E_LLM_PROTOCOL_UNKNOWN`，**不回落默认**（按错方言发出去 = 用错误的协议砸供应商） |
| `timeoutMs` | 60s | 整段交换的读超时；必须是正整数毫秒，`0` 响亮拒绝 |
| `connectTimeoutMs` | 10s | 建连超时 |
| `capabilities.*` | 保守全关 | 键名**就是** `ModelCapabilities` 的组件名，不另立一套命名 |

**`baseUrl` 语义（需求点名要求"写进文档/校验"）——三处一起兜**：

1. **文档**：`ModelRoute` 与 `LlmRouteLoader` 的 Javadoc 都写明「`baseUrl` 是 **API 根**；请求打到 `POST {baseUrl}/chat/completions`；**`/v1` 由调用方写进 baseUrl**」。
2. **构造期校验**：`baseUrl` 以 `/chat/completions` 结尾 → 当场 `IllegalArgumentException`（把端点当根用是最常见的踩法，而且它的失败形态是 404，看起来像"密钥不对"）。错误消息**不回显该 URL**（它可能带凭据）。
3. **运行期提示**：404 的异常消息里直接点名"检查 baseUrl 是否漏了 `/v1`"；3xx 归 `CONFIG` 并提示"本客户端不跟随重定向"（跟随重定向会把 `Authorization` 带去别的主机）。

---

### A4 ★★ Provider 配置的读写

**① `ConfigStore` 可写 —— 结论：本来就写得了，本次补齐了"删"。**

- 需求文档 §五 说"没读到写方法"：`FileConfigStore.put(...)` **一直都在**（含前缀授权、schema 校验、原子写 + fsync + `.bak`、读回校验 + 回滚、落盘后回调）。推测是只看了布局段没往下读。
- 本次**新增 `ConfigStore.remove(...)`**（两个重载，与 `put` 对称地吃 schema）：CRUD 现在齐了。
  - 与 `put` **走同一条流水线**（授权 → 摘除 → 写前校验 → 原子写 → 读回校验 → 回滚 → 回调）——删除是另一种写，没理由走一条更弱的路径。删掉一个 schema 要求的必填键会被拦下。
  - **幂等**：删一个本就不存在的键 = 无操作（文件逐字节不动、不回调、不抛）。DELETE 的语义是"确保它不在"，重复点删除按钮不该失败。
  - **沿途被删空的对象一并摘掉**：删 `llm.routes.glm` 后不会留下 `"routes": {}` 这种空壳（否则配置页渲染原始 JSON 时会显示一个已经不存在的分组，用户删了却看见它还在）。
  - **删不掉根**：空键 / 空段 / `.` / `..` 一律 `E_PREFIX_DENIED`。
  - **删除照样走变更回调**，`newValue` 是 JSON 空节点（`NullNode`），不是 Java `null`——这样以 lambda 注册的宿主不会静默漏掉删除事件。

**② `availableNames()` 确认为权威枚举入口** —— 是。它**只列名字、不校验内容**，正是配置页"先看到全部（含写坏的那条）再逐条报错"需要的语义。装配入口则相反：**全有或全无**（见下）。两个口径的分工是刻意的：**枚举要给全**，**装配要全对**。

**③ 官方装配入口 —— 新增 `io.mosire.agentlib.llm.LlmRouteAssembler`**（需求要的"3~5 行官方示例"）：

```java
ConfigStore store = new FileConfigStore(configRoot);
LlmClient llm = LlmRouteAssembler.client(store, "deepseek", AccessToken.SYSTEM);
String answer = llm.text(new LlmRequest(messages).withTemperature(0));
```

```java
ModelProvider provider = LlmRouteAssembler.provider(store);   // 全部路由 + 能力描述，不需要密钥
```

`client(...)` 里那个 `switch (route.transport().protocol())` **没有 `default` 分支**：以后新增方言时**这里编译不过**，而不是运行时悄悄按 OpenAI 协议发出去。

**④ 还有一个重载：`client(ModelRoute, ApiKeySource)`** ——给"**配置还没写盘**"的场景用（设置向导 / 配置页的"测试连接"探的是用户刚敲的候选配置，它此刻不在 `ConfigStore` 里）。加上它是因为宿主普查发现：`LlmProviderProbe` 探的正是"尚未存在于 store 的路由"，只给 `client(store, ...)` 的话它只能自己 `new` 客户端——**协议开关就有第二个副本**，将来新增方言必漏。把开关收到一处，"新增方言 ⇒ 只有一个地方编译不过"才对**所有**装配路径成立。

---

### A5 ★ 密钥形式的裁定

**裁定：① 只认 `keys.*`。** 逃生口：**自己实现 `OpenAICompatibleLlmClient.ApiKeySource`**（官方 SPI，构造客户端时注入）。

理由：AgentLib 侧的红线（T18 R2）是「**读环境变量取密钥是被禁止的第二条密钥通路**」——一旦两条路并存，"谁改的密钥/哪条生效"就没有单一答案。而"密钥从哪来"与"怎么发请求"本来就是两件事，`ApiKeySource` 已经把缝留好了：simos 要 ENV / FILE / KMS，实现这个接口即可，**不需要 AgentLib 支持**。

**不可协商约束（全程未破）**：密钥值绝不进日志 / 异常 / 事件 / argv / stdio；读配置只打印**路径 + 长度**。具体落在：

- `ConfigApiKeySource` 的异常消息只给引用名，且引用名要过保守白名单（`[A-Za-z0-9_-]{1,64}`）才回显，否则 `<不可显示的引用名>`——因为引用名本身可能就是"含凭据的 URL"。
- `FileConfigStore` 的 schema 校验错误只报"关键字名 + 错误数 + schema 名"，**不含**库消息原文与实例路径（networknt 的 `getInstanceLocation` 会嵌入键名、`getMessage` 可能嵌入值）。
- baseUrl 相关异常不回显 URL。
- `Sampling.toString()` 只打印 `extraBody` 的**键名**，不打印值。

---

## 二、便利项

### B1 `default String text(LlmRequest)`

已提供（`LlmClient` 的 default 方法）。simos 的 `String complete(LlmRequest)` **不需要适配器**：方法引用 `llm::text` 即可。语义见 A1 末段——拿不到文本时抛 `NO_TEXT`，绝不返回空串。

### B2 `FakeLlmClient` 用法示例

```java
FakeLlmClient llm =
    FakeLlmClient.with(
        LlmResponse.text("第一轮回答"),
        LlmResponse.reasoningFolded("只有推理内容的一轮"));
assertThat(llm.text(request)).isEqualTo("第一轮回答");   // 第二条是 reasoning-only 的形态，专门用来测 A1 的折叠
```

它的价值是"**脚本耗尽即响亮抛**"：simos 现在的 lambda 假客户端很容易写成"耗尽后返回默认值"，那会把"调用次数不对"这类 bug 伪装成正常。

### B3 哪些异常"该降级"、哪些"该炸"（simos 的 N13 失败降级）

`LlmException` 新增 `Kind` 枚举与两个判据方法，**不要再捕 `RuntimeException` 一刀切**：

| Kind | 触发 | `retryable()` | `degradable()` |
|---|---|---|---|
| `TRANSPORT` | 连不上、读到一半断 | ✔ | ✔ |
| `TIMEOUT` | 建连/响应/整段交换超时 | ✔ | ✔ |
| `RATE_LIMIT` | HTTP 429 | ✔ | ✔ |
| `PROVIDER_ERROR` | HTTP 5xx | ✔ | ✔ |
| `AUTH` | HTTP 401 / 403 | ✘ | ✔ |
| `REQUEST_REJECTED` | 其余 4xx（模型不存在、参数非法、余额不足） | ✘ | ✔ |
| `PROTOCOL` | SSE 不合法、`[DONE]` 前被截断、工具参数不是合法 JSON | ✘ | ✔ |
| `CONFIG` | 本地配置/装配错（baseUrl 非法、取密钥失败） | ✘ | ✘ |
| `NO_TEXT` | 要一段文本却没拿到（只有工具调用 / 什么都没产出） | ✘ | ✔ |
| `CANCELLED` | 调用线程被 interrupt / 本次调用已被取消 | ✘ | ✘ |
| `INTERNAL` | 未分类——**保守判"该炸"** | ✘ | ✘ |

**判据怎么用**：
- `degradable() == true` → 可以走 N13 降级（换 provider / 用兜底策略）。
- `degradable() == false`（`CONFIG` / `CANCELLED` / `INTERNAL`）→ **该炸**。`CONFIG` 尤其重要：**配置错不是供应商的问题，降级会把"配错了"变成"换个 provider 好像就行了"，把真正的故障藏起来**。
- `retryable()` 只表示"重试可能有用"，**不等于**该降级——例如 `RATE_LIMIT` 是 retryable + degradable，但重试前要退避。
- `QuotaExceededException`（本地配额）**既不可重试也不可降级**：它是本进程自己设的闸，不是外部故障。

### B4 `AgentTool` → `ToolDef` 的官方转换点

**落点**：新增 `io.mosire.agentlib.tool.ToolDefs`：

```java
public static ToolDef of(AgentTool tool)                                  // 逐字直传，不加工、不补默认值
public static List<ToolDef> ofAll(Collection<? extends AgentTool> tools)  // 保序；空集合 ⇒ 空列表
```

**为什么放 `tool` 包而不是让 `ToolDef` 自己转**：`tool` 包可以依赖 `llm` 包，反向不行——`ToolDef` 刻意不反向依赖整个工具抽象（否则 llm 包会被拖进工具/权限那一坨）。**转换点因此只能住在 `tool` 侧。**

**映射表（三项逐字直传）**：`name ← name`、`description ← description`（缺省空串）、`jsonSchema ← jsonSchema`（缺省"无参数"对象 schema）。`spec` / `gate` / `resources` / `ledgerArgs` **不进 `ToolDef`** —— 它们不是模型可见面，这是设计而非遗漏。

**为什么 `ofAll` 收 `Collection<? extends AgentTool>`**：Brain 那两处调用点传的是 `List<AgentTool>`，drop-in 即可替换，不必先转集合。

**保序是硬要求**：工具顺序就是模型看到的顺序，也是请求 JSON 里 function 数组的顺序——顺序一变，prompt-cache 前缀失效（与 `BasicContextAssembler` 原样回灌 history 同一条理由）。故 `ToolDefs` 不排序、不去重、不按名字归一。

**一处诚实的边界（D24）**：`AgentTool.jsonSchema()` **允许**返回顶层带 `null` 值的 Map——`McpToolAdapter.jsonSchema()` 就明确产出这种 schema（外部 MCP 服务器的 inputSchema 是任意 JSON，它的代码注释直言"不能 `Map.copyOf`"）。而 `ToolDef` 顶层不收 `null` 键/值，所以这类工具转换时会**响亮抛 NPE**。

- **这不是 B4 引入的**：B4 之前 Brain 的两份实现就是 `new ToolDef(name, desc, jsonSchema)`，撞的是同一个构造器，接受/拒绝集合一字未变——B4 只是把两份抄本收成一处。
- **不兜底是有意的**：抹掉 `null` 或换一个默认值，是替模型编造契约，比响亮失败更坏。要动的是上游 schema。
- **诊断补在规则的所有者**：`Map.copyOf` 撞 `null` 只抛**无消息**的 NPE，工具一多就无从知道坏的是哪条。故在 `ToolDef` 构造器里 `copyOf` 之前手扫一遍顶层，让消息点名**是哪个键**（如 `jsonSchema.properties 的值为 null`）。约束未变、只是把"拒了"补成"拒了并点名"；**Brain 那两处既存调用点也一并受益**。
- **`ToolDefs` 刻意不 catch 后再包一层**（那会带出工具名）：抄校验/包异常都会把"谁的规则"搅浑，下一个字段加进来时又多一处要跟着改——正是本类要消灭的东西。消息**不带工具名**是已知代价，`ToolDefs` 的类注释里写明了。

**顺带**：Brain 里有**两份逐字重复**的同类转换（`BasicContextAssembler.java:161-162` 与 `AgentPipeline.java:902-905`）。本次**没有动 Brain**（模块边界 + "本次只加 AgentLib 侧入口"的约定），但那两处可以用一行 `ToolDefs.ofAll(tools)`（同参同返）收敛掉。

---

## 三、回答 §四 的四个待裁定

| # | 问题 | 本次裁定 |
|---|---|---|
| **D-A** | 配置存储用 AgentLib 的还是 simos 自带的？ | **用 AgentLib 的**。A4 的两个前提（可写 ConfigStore、装配入口）现在都成立。simos 的 `llm-providers.json` 可以退化成"缓存/迁移来源" |
| **D-B** | 密钥形式：只 `keys.*` 还是也支持 env/file？ | **只 `keys.*`**（A5 ①）。simos 若确实需要 ENV/FILE，**实现 `ApiKeySource` SPI**，不要改 AgentLib，也不要绕过 `ConfigStore` 读 env |
| **D-C** | `reasoning_content` 三选一？ | **① 并入文本的条件版** + ② 独立字段 + 标记（见 A1）。**注意不是无条件并入**，理由见 A1 |
| **D-D** | 依赖装在哪一层？ | **由 simos 决定，与 AgentLib 无关**：AgentLib 是普通 Maven 依赖（`io.mosire:agentlib-mosire`），装在哪一侧都行——安装方式见 `安装报告-AgentLib.md` |

---

## 四、回应 §五「我未能核实的」

| # | 原问题 | 结论 |
|---|---|---|
| 1 | `FileConfigStore` 是否可写？ | **可写**。`put` 一直存在（本次只补了 `remove`）。写路径含：前缀授权 → 合并 → schema 校验 → 原子写（tmp + fsync + `.bak` + `ATOMIC_MOVE`）→ 读回校验 → 失败回滚 |
| 2 | `LlmRouteLoader.load` 是否被宿主实际调用？ | **被调用**（不是"只有测试"）：`Main.java:167/270`、`SubagentProcessMain.java:213` 三处。但 `availableNames` / `capabilities` / `ModelCapabilities` 宿主**零调用**——详见 §六 |
| 3 | `~/.m2` 的 jar 与源码是否一致？ | 本次**重新构建并安装**，逐个核对 jar 内的类（见 `安装报告-AgentLib.md` 的产物校验一节） |
| 4 | GSimulator 的 `llms.json` / `gsim.properties` 与 AgentLib 的配置是两套 | **确认是两套，且本次不动**。统一配置格式是另一个话题；`llms.json` 里**明文内嵌 key** 这一点尤其与本项目的凭据契约冲突，建议单独立项 |

---

## 五、simos 侧照抄即可的迁移路径

1. **删** `HttpLlmClient` → 换 `LlmRouteAssembler.client(...)`。
2. **收窄** `LlmProvider` / `LlmProviderRegistry` → 换 `ModelProvider`（`LlmRouteAssembler.provider(...)`）+ `availableNames()` 做列表。只保留 simos 特有：文件持久化 / GUI CRUD / 掩码视图。
3. **`ENV|FILE`** 若保留 → 写一个 `ApiKeySource` 实现（约 20 行），**不要**动 `ConfigApiKeySource`。
4. **失败降级**：把 `catch (RuntimeException)` 换成 `catch (LlmException e) { if (e.degradable()) ... }`。
5. **测试**：`FakeLlmClient` 换掉自造 lambda。

---

## 六、宿主侧现状（本次全仓普查，只读）

### 6.1 "配置 → 客户端"的装配在本仓有 **4 份**实现

| # | 位置 | 形态 |
|---|---|---|
| ① | `Main.java:267-273` | 完整三步：`new FileConfigStore` → `LlmRouteLoader.load` → `new OpenAICompatibleLlmClient` + `ConfigApiKeySource` |
| ② | `SubagentProcessMain.java:210-218` | 与 ① **逐字同构**（只多一行密钥预检 `keys.apiKey()`） |
| ③ | `SetupHttpServer.java:137-141` | 变体：**绕开 `LlmRouteLoader`**，用 HTTP 请求体字段手搓 `ModelRoute` |
| ④ | `LlmProviderProbe.java:34-41` | 变体：手搓 `ModelRoute` + **内联 lambda 密钥源**（绕过 `ConfigApiKeySource`） |

这正是 `LlmRouteAssembler` 要消灭的"N 份实现"。① 与 ② 可立即收敛到 `client(store, routeName, token)`；③ ④ 是"**未落盘的路由**"（向导/配置页正在试的候选配置），用新加的 `client(ModelRoute, ApiKeySource)` 重载。

**迁移 ② 时有一个行为不能丢**：它在装配后立刻调 `keys.apiKey()` 做密钥预检（子进程必须"起不来就响亮"，`SubagentProcessMain.java:200-205`）。`LlmRouteAssembler` **刻意不在装配期解析密钥**（轮换/过期感知要求每次 `chat` 现取），所以那一次预检要**显式保留**在调用点。

### 6.2 Brain / Bash 的生产代码零改动

- **Brain 不 new 任何客户端**：全部经构造器注入（`App.java:344` 取到，`:462/:465` 交给 `AgentRuntime`）。
- **BashPluginMosire 生产代码完全不碰 LLM**（只有测试用 `FakeLlmClient`）。

### 6.3 密钥通路干净（红线复核结论）

宿主侧取密钥**只有一条路**：`ConfigApiKeySource` + `ConfigStore`，三个生产点写法一致。全仓 `System.getenv` 只有一处读 `PATH`（`BashSandbox.java:356`，与密钥无关）；无 `.env`、无密钥文件、无 dotenv；`FileConfigStore` 也显式不读进程环境。**唯一不经过 `ConfigApiKeySource` 的地方是 ④ 的探测路径**，但它用的是"用户刚敲进来、尚未写盘的候选密钥"，属探测语义，且不落盘、不入日志。

### 6.4 ⚠ `availableNames` / `capabilities` 在宿主侧**零调用**

宿主从未枚举过路由、也从未读过能力描述——"多 provider 并存"目前只落地到"按名字取一条"。**simos 的 provider 配置页将是这两个 API 的第一个真实调用方**：它们有单测，但**没有生产路径跑过**。这一条对接方需要心里有数。

### 6.5 建议宿主侧改（不在本次交付范围内）

把 `Main.java` / `SubagentProcessMain.java` 的装配换成 `LlmRouteAssembler.client(...)`——顺带让 `availableNames` / `capabilities` 进入生产路径（有真实调用方才谈得上被验证）。

---

## 七、已知边界与坑（对接前务必知道）

1. **`/v1` 要自己写进 `baseUrl`**（本库只补 `/chat/completions`）。写错了会 404，而 404 看起来像"密钥不对"——所以异常消息里直接点了这一条。
2. **`baseUrl` 里不要带端点**（`.../chat/completions`），构造期即拒。
3. **不跟随重定向**（`followRedirects(NEVER)`）：3xx 归 `CONFIG` 并响亮报错。这是有意的——跟随重定向会把 `Authorization` 带去另一个主机。
4. **`extraBody` 不是后门**：写 `model` / `messages` 这类协议关键键会在构造期被拒。
5. **`reasoning` 的折叠只发生在"content 空且无工具调用"时**；有工具调用时 reasoning 只出现在独立字段里（不要指望正文里有它）。
6. **删除是幂等的**：删不存在的键不报错、不回调。配置页的删除按钮不该因此报"删除失败"。
7. **配置写入是"整文件校验"**：schema 管的是**合并后的整个目标文件**。所以"删一个键"也可能因为"删完文档不满足 schema"被拒——这是有意的（宁可当场拒，不要留一份下次读取时才炸的配置）。
8. **`keys.*` 只存引用、只由 SYSTEM 身份写**；Agent 身份写不了 `llm.*` / `keys.*` / `runtime.*`。
9. **工具 schema 的顶层不能有 `null` 值**（B4）：外部 MCP 服务器的 `inputSchema` 是任意 JSON，顶层带 `null` 的工具过 `ToolDefs.of(...)` 会抛 NPE（消息点名叫哪个键）。**嵌套层里的 `null` 是合法的**，照常通过。这是既存约束，不是本次新增——但 simos 若要接 MCP 工具，这是第一个会撞上的地方。
10. **`chat()` 如实报告供应商返回了什么，"空"是一个事实而不是异常**：流里只要有 data 帧，"模型答了个空"就是一次**成功**的调用（`content()` 为空列表、`disposition() == ABSENT`）。判据在"有没有 data 帧"——**零 data 帧**才算协议违约（`PROTOCOL`，文案是"空完成"）。分层是这样切的：`chat()` 是协议层（如实回报），`text()` 是语义层（"我要一段文本"，拿不到就抛 `NO_TEXT`）。**直接走 `chat()` 的调用方（如 AgentPipeline 主循环）要自己检查 `content()` 是否为空**——那里不会有人替你抛。

---

## 八、验证证据

验证命令：`./mvnw -o verify`（5 模块全反应堆，spotless → checkstyle → spotbugs(Max/Low) → surefire），2026-09-22 01:32 起。

| 模块 | 用例 | 失败 | 错误 | 跳过 |
|---|---|---|---|---|
| **AgentLibMosire** | **476** | **0** | **0** | **0** |
| BrainMosire | 291 | 0 | 0 | 0 |
| BashPluginMosire | 100 | 0 | 0 | 6（既存跳过） |
| MainMosire | 未跑完 | 见下 | | |

**AgentLibMosire 全绿，且这次运行已包含本次全部代码改动**——包括最后补的三处（`text()` 的空白判据、同 delta 别名去重、`ToolDef` 顶层 null 扫描）。

本次新增/改动的测试类，逐个实测（全部 0 失败）：

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `ConfigStoreRemoveTest` | 14 | A4 删除 |
| `RouteTransportAndAssemblyTest` | 17 | A3 / A4 / 端到端 |
| `LlmExceptionKindTest` | 24 | B3 异常分类真值表 + 真 HTTP 状态码映射 |
| `OpenAICompatibleLlmClientSamplingTest` | 17 | A2 采样参数 |
| `OpenAICompatibleLlmClientReasoningTest` | 9 | A1 思维链 |
| `LlmClientTextConvenienceTest` | 7 | B1 `text()`（含后补的"空白正文"那条） |
| `ToolDefsTest` | 9 | B4 转换点 |

**BrainMosire 的 291 个用例在改完 AgentLib 之后仍然全绿**——这是"模块边界单向、不反引上层"的可执行证据，不是口头承诺。

**产物**：`~/.m2` 里的 `agentlib-mosire-0.1.0-SNAPSHOT.jar` 已更新（131 个类，本次涉及的 7 个类名逐一核对在场）。明细见 `安装报告-AgentLib.md` 第五节。

### 未能跑完的一环（诚实说明）

`MainMosire` 那一轮**没能跑完**，原因是**环境而非代码**：

- `DebugChatServiceTest` 5 个 `NoClassDefFoundError: io/mosire/main/gateway/debug/DebugChatRun$Status`；
- `DebugChatSessionHttpTest` 随后**永久阻塞**在 `HttpClient.send()`（服务端 handler 因同一个 `NoClassDefFoundError` 死掉，而该测试的客户端没设超时），构建挂住，我手动终止。

判据有五条：① 缺的是 **MainMosire 自己的嵌套枚举**，AgentLib 碰不到它；② MainMosire 源码本次**零改动**（`git status` 里只有未跟踪的 `MainMosire/.factorypath`）；③ 该 `.class` **现在好好躺在 `MainMosire/target/classes` 里**——说明它是在 fork 运行期间被删掉又重建的；④ 机器上同时跑着 **4 个 Eclipse JDT 语言服务器**（serena 起的 3 个 + IDE 自己的 1 个）；⑤ **直接证据**：本次会话的 IDE 诊断里出现了 `org.eclipse.m2e.core.internal.builder.MavenBuilderImpl` —— **Eclipse 的 m2e 正在对同一个 `target/` 执行 Maven 构建**，同时它还在报 `MainStartAppTestSeam` 找不到 `io.mosire.agentlib.event.Event`（IDE 手里是一份过期的 agentlib）。⑤ 把①②③④串成了一条完整的因果链，不再是推测。

**结论：IDE 与 Maven 抢同一个 `target/classes` 的竞态。** 与 AgentLib 无关，也不是"测试本身写错了"。

但有一件事**确实该修**，且与本次改动无关：那个 HTTP 测试**没设超时**，任何抖动都会让构建无限挂住，而不是失败退出。

---

## 九、还差什么（诚实清单）

**1. 真实供应商一次都没打过。** 全部验证都跑在 `127.0.0.1` 的假端点上（自建 SSE 流）。A1 折叠策略依据的是**你方**给出的实测现象（`deepseek-flash` + `max_tokens=16` → `content=""`、内容全在 `reasoning_content`），我**没有独立复现**过任何真实供应商。第一次接真 key 时重点看三样：`reasoning` 的字段名、`stream_options` 的回显、`/v1` 有没有写进 `baseUrl`（写错是 404，而 404 看起来像密钥不对）。

**2. "整个仓库全绿"这句话我不能说。** AgentLibMosire 自己那轮 `verify` 是**绿**的（476/0/0/0，含 spotless + checkstyle + spotbugs），BrainMosire（291）与 BashPluginMosire（100）也绿；但 **MainMosire 那一轮因上面的 IDE 竞态中断，没跑完**。请在你自己机器上补一轮干净的 `./mvnw -o verify` 再定论。

**3. 宿主侧 4 份装配副本仍在**（§六），本次只在 AgentLib 侧建了官方入口，**没有动 Main**。迁移时有一处**不能丢**：`SubagentProcessMain` 的密钥预检（`keys.apiKey()`）——`LlmRouteAssembler` 是**故意**把密钥解析推迟到调用期的，直接替换会把那个预检丢掉。

**4. Brain 的两处重复转换没收敛**（`BasicContextAssembler.java:161-162`、`AgentPipeline.java:902-905`）。同参同返，`ToolDefs.ofAll(tools)` 一行即可替换；本次没动 Brain（模块边界 + 范围约定）。

**5. B4 的顶层 null 缺口只补了诊断，没改判据。** `ToolDef` 仍然拒顶层 null（既存约束，B4 之前就是这样）。我补的是"拒了并点名哪个键"。**抹平它**（让 MCP 那种带 null 的 schema 也能过）是 llm 层的契约决定，会牵动 `ToolCall` 等同类，本次**没做**。

**6. `DebugChatSessionHttpTest` 没有超时**（既存缺陷，非本次范围）。核实过：`HttpClient.newHttpClient()`，全文件没有任何 `timeout` ⇒ `send()` 会**无限等**。这次就是这么把构建挂死的——任何抖动都会让它挂住而不是失败退出。建议补一个请求超时。

**7. `availableNames` / `capabilities` 至今没有生产调用方**（§六.4）。本次给了权威入口与全套用例，但**没人用**——simos 的配置页会是第一个。真实性还没经生产检验。

**8. 版本定版未决。** simos 侧已写明"待 ProjectMosire 在途工作落地后升为固定版本 `0.2.0`"（`simos/pom.xml:42-45`）。本次交付**没有**定版，仍是 `0.1.0-SNAPSHOT` 覆盖安装。

**9. 三处我改了实现、但你没点名要求的地方**（列出来供你否决——每处都补了用例，都在上面的 476 里）：

| 改动 | 原行为 | 现行为 |
|---|---|---|
| `LlmClient.text()` 空白判据 | 有文本部件但全是空白时**返回 `""`**（与它自己的 Javadoc"绝不返回空串"矛盾） | 抛 `NO_TEXT`，消息只报字符数 |
| 同 delta 里 `reasoning_content` + `reasoning` 同时出现 | 两个都吃 ⇒ **思维链追加两遍**（折叠进正文后正文重复） | 只吃 `reasoning_content`；跨 delta 交替仍照吃 |
| `ToolDef` 顶层 null 的 NPE | `Map.copyOf` 抛**无消息**的 NPE，工具一多无从定位 | 复制前手扫一遍，消息点名是哪个键（判据未动） |

三处互相独立，回退点很干净。**第 2 条（重复累计）是我认为最该留的**——它是唯一一处"安静地错"。
