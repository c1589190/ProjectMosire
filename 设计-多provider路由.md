# 设计：多 provider 路由（GLM + DeepSeek 并存）

> 制定：2026-09-14。范围 = `开发计划.md` §二。前置实证：GLM 侧**零代码改动即通**
> （`.work/glm-live/events.db`：`llm.call` 报 `model=glm-5.3-flash`、第二轮 `cacheReadTokens=448`、
> `conversation.turn` 回述 `3141`）⇒ 本设计**不是**"接 GLM"，而是**把只有一条的路由结构改成按名选的多条**。
> 事实锚在**符号名**上（行号会漂）；下列每条现状都经 2026-09-14 控制者亲读源码核实。

---

## 一、现状（四条，各自决定了下面的一条设计）

1. **加载器只认一条、名字硬编码**：`LlmRouteLoader.load(ConfigStore)` 只读 `llm.baseUrl` / `llm.model` /
   `llm.credentialsRef`（`SECTION = "llm"`），`name` 恒为 `"default"`。类内注释**已经预告**了本设计：
   "派生值会把模型名与路由名混为一谈，**等到真出现多路由（按名选择）时两者语义不同**"。
2. **配置层不做跨前缀回退**（`FileConfigStore.get`）：每一层"整键命中即返回"。所以
   `store.get("agents.bob", "llm.baseUrl")` 只会查 `agents/bob.json` 的 `llm.baseUrl`（再落空则查全局的
   `agents.bob.llm.baseUrl`），**不会**回落到全局 `llm.baseUrl`。⇒ 任何"按 agent 覆盖、没配就用全局"的语义
   **必须自己写回退**，不能指望配置层。
3. **`agents.<id>.json` 的入口是现成的**：`ConfigAuth.agentIdOf` 认 `agents.<id>.<rest>`（id 字符集
   `[A-Za-z0-9][A-Za-z0-9_-]*`），`FileConfigStore.agentFile` = `<configRoot>/agents/<id>.json`；
   写授权侧 `agents.<id>.*` 归该 agent 自己（≥DEFAULT）。⇒ 按 agent 选路由**不需要新增任何传递面**。
4. **两个天然静默点**（D24 的敌人）：`ModelProvider.resolve(name)` 未注册 → `Optional.empty()`；
   `capabilities(name)` 未注册 → 保守 `defaults()`（**设计成不报错**）。任何写成
   `resolve(name).orElse(默认路由)` 的路径都会把"名字写错"变成"静默跑另一条路由"。

**顺带记下：谁依赖扁平的 `llm.*` 形状**（改形状要同批改，否则真模型验收链第一步就断）：
`scripts/smoke-live.sh:106-113,120-121`（python 直读 `llm.baseUrl`/`llm.model`/`llm.credentialsRef`）；
`scripts/s1b-explore.sh:146-153`（只判 `config.json` 存在性，不解析内容 ⇒ 不阻塞）；
`LlmRouteLoaderTest` / `MainRealLlmTest` / `SubagentRealModelTest` 多处以配置形状为夹具。

---

## 二、设计

### 2.1 配置形态：**加法**，不是替换

```jsonc
{
  "llm": {
    "route": "glm",                              // 选哪条（可省，默认 "default"）
    "baseUrl": "…", "model": "…", "credentialsRef": "keys.deepseek",   // 扁平形态 = 名为 default 的路由
    "routes": {                                  // 新增：具名路由表
      "glm":   { "baseUrl": "https://open.bigmodel.cn/api/coding/paas/v4",
                 "model": "glm-5.3-flash", "credentialsRef": "keys.glm" },
      "deepseek": { "baseUrl": "…", "model": "deepseek-flash", "credentialsRef": "keys.deepseek" }
    }
  },
  "keys": { "glm": "…", "deepseek": "…" },
  "agents": { "orch-probe": { "llm": { "route": "deepseek" } } }   // 按 agent 覆盖
}
```

**解析规则**（`LlmRouteLoader.load(store, name)`，逐条响亮）：

| 情况 | 结果 |
|---|---|
| `llm.routes.<name>` 存在且为对象 | 用它的三字段（`credentialsRef` 缺失 = 匿名，同现状） |
| `llm.routes` 非空、`name == "default"`、且有扁平 `llm.baseUrl`+`llm.model` | 用扁平形态（**兼容层**：老配置一字不改照跑） |
| `llm.routes` 非空、但 `name` 不在表里 | **`E_LLM_ROUTE_UNKNOWN`**，消息带请求的名字 + 可用名字列表 |
| `llm.routes` 缺失/为空 | 扁平形态；`name != "default"` ⇒ `E_LLM_ROUTE_UNKNOWN`（**用户写了名字却没这表，不许静默忽略**） |
| `llm.routes.<name>` 存在但非对象 / 缺 `baseUrl`/`model` | `E_LLM_CONFIG_MISSING`，消息点名**具体是哪个键**（含路由名） |

- **名字校验**：`name` 必须匹配 `[A-Za-z0-9][A-Za-z0-9_-]*`（与 `ConfigAuth.AGENT_ID` 同族）。不匹配 ⇒
  `E_LLM_ROUTE_UNKNOWN`——名字是往配置树里寻址的段，带点的名字会指到别的节点上，**不许**。
- **`E_LLM_ROUTE_UNKNOWN` 是新码**（不复用 `E_LLM_CONFIG_MISSING`）："配置缺失/配错"与"点名了一条不存在的路由"
  是**两种修法**，运维要能一眼分开（D24 精神：失败必须可分辨）。
- 现有单参 `load(store)` **保留**，语义 = `load(store, "default")` ⇒ 4 处既有用例与两个脚本逐字不变。

### 2.2 按名选择的落点：**装配层**，不进 `AgentSpec`

选择发生在**已经决定"用哪个 LlmClient"的那一处**，与现有"唯一 LLM 语义入口"约定一致（`Main.selectLlm`）；
`AgentSpec.provider` 与 `ModelProvider` **本轮不接线**，理由：

- `ModelProvider.Entry` 只以 `name` 为键，**没有 provider 维度**——"provider+model 选路由"的语义从未定死；
  现在接线等于把没想清楚的概念固化进生产路径。它继续留在"零生产调用者"的登记项里（不新增误导）。
- 子进程侧根本没有 `AgentSpec`（走 `AgentConfig`），接它还得先造一条跨进程通道。

**选择函数**（新增，唯一实现，主/子共用）：

```java
LlmRouteLoader.routeName(ConfigStore store, String ownerId)   // ① agents/<ownerId>.llm.route
                                                              // ② llm.route
                                                              // ③ "default"
```

- ①命中即用；②是全局缺省；③是兜底。**回退是显式写的**（现状 §一.2 保证了配置层不会替我们做）。
- `ownerId`：主 Agent 传 `"main"`（与 `App.java` 的 `AgentConfig.builder("main")` 同名）；
  子 Agent 传 **`templateId`**（`SubagentProcessMain.Options` 已有，稳定、可按模板配）。
  `instanceId` **不用**：同一模板的多个实例应走同一条路由，且实例名是易变的一次性标识。
- **写权限提醒**（设计开口项，见 §四）：`agents.<id>.llm.route` 按 `ConfigAuth` 归该 agent 自己可写。
  当前**没有任何配置写工具**（`config_put` 属二期 P2-7 欠账、零命中）⇒ 今天不可被 agent 自改；
  真接上自管理写入前必须重新评估"agent 能不能给自己换模型"。

### 2.3 两处 `realLlm` 必须同批改（已知陷阱）

`Main.realLlm` 与 `SubagentProcessMain.realLlm` 是**重复实现**（前者不预检密钥、后者预检——这个差异是
**有意的**，见 `SubagentProcessMain` 的 javadoc：不预检则取密钥失败会被 `AgentPipeline` 收敛成
`StopReason.LLM_ERROR` 的**正常回合**、退出码 0，父侧再也分辨不出"子体压根没跑起来"）。
⇒ 抽的是**选路**，不是装配：

- 共用：`LlmRouteLoader.load(store, routeName(store, ownerId))`（§2.1 + §2.2）；
- 各自保留：客户端构造与**是否预检**。子体预检必须对**它实际选中的那条**路由做（否则出"预检 A、实跑 B"
  的新盲区）；`Main.realLlm` 的现有形态不动。

### 2.4 D24 语义如何保住

1. **未知名 = 响亮**：`E_LLM_ROUTE_UNKNOWN`（§2.1 表）——绝不 `orElse(默认)`。
2. **不碰 `ModelProvider` 的静默 API**：本轮不接线（§2.2），静默点不进生产路径。
3. **绝不回退假模型**：`--config-dir` 缺席才走模板假 LLM（现状不变）；选了路由却解析失败 ⇒ 引导期抛，
   与今天 `E_LLM_CONFIG_MISSING` 同级。
4. **既有判据全部保留**：`LlmRouteLoaderTest` 的四类缺失、`MainRealLlmTest.nonDemoRunWithoutConfigDiesLoudlyInsteadOfFakeReplies`、
   `SubagentRealModelTest` 的缺配置/缺密钥两条——**一字不改**，新增路由用例与它们并存。

---

## 三、验收（真模型使用模式，D29）

**夹具**：一个配置根、两条真路由、两个不同回复的真端点不需要——用**真供应商**：

1. `config.json`：`llm.route = "glm"` + `llm.routes.glm`（→ `glm-5.3-flash`）+ `llm.routes.deepseek`
   （→ `deepseek-flash`）+ `keys.glm` / `keys.deepseek`；另给某模板配 `agents.<templateId>.llm.route = "deepseek"`。
2. **主 Agent 走 glm**：真进程 `run` 一轮对话 ⇒ `events.db` 的 `llm.call.model` = `glm-5.3-flash`。
3. **子 Agent 走 deepseek**：同一次运行里 `spawn_sub_agent` 那个模板 ⇒ **子体自己的** `events.db` 里
   `llm.call.model` = `deepseek-flash`（父库对应记录为同一 correlationId 的终局事件）。
4. **两个口径别当 bug**：`agent.lifecycle.model` 是**客户端声明**口径（`OpenAICompatibleLlmClient.model()`
   返回 `route.model()`），`llm.call.model` 是**服务端自报**口径——两者不同是设计，不是谎报
   （`LlmClient` javadoc 有明写；三期已有一次虚警先例）。
5. **反向判据**（与正向同等重要）：`llm.route = "glmm"`（拼错）⇒ 进程**非零退出** + stderr 含
   `E_LLM_ROUTE_UNKNOWN` + **无任何假回复**；`llm.route = "a.b"` ⇒ 同码（名字非法）。

---

## 四、本轮不做 / 开口项

1. `AgentSpec.provider` 与 `ModelProvider` 接线（§2.2 理由）——登记，不在本轮。
2. **`--route` CLI 开关**：不新增 argv（子体侧"只传路径"的边界不动）。主 Agent 换路由 = 改 `llm.route`。
3. **agent 自选路由的权限边界**（§2.2 提醒）——真接上配置写工具前必须裁决。
4. `ModelCapabilities` / 上下文预算按路由区分（1M 上下文 vs 128k）——本设计只做**选路**，不做按路由调参。
5. 密钥轮换/多密钥池——`ConfigApiKeySource` 每次现读已支持"换值即生效"，但**不**支持一条路由多个 key。
6. **路由名不进事件库**：`llm.call` 的 payload 仍只有服务端自报的 `model`。两条路由共用同一个模型名时，事件库分不出走的
   哪条（运维排查要靠 `config.json` 反推）。闭合要给 `LlmClient` 加"路由名"面并按线落到事件——**不在本轮**
   （避免在 D 块验收前动 `AgentPipeline` 的事件形状）。当前只有错误消息带名字（`E_LLM_ROUTE_UNKNOWN`）。
7. **`scripts/smoke-live.sh` 的选路是 Java 侧的镜像**：脚本用 python 复刻了 `LlmRouteLoader` 的解析规则（`llm.route` →
   `llm.routes.<name>` → 扁平兼容层），因为脚本要**先**知道扫哪条路由的密钥值。改 Java 规则必须同批改脚本，否则
   "预检通过的配置"与"实跑用的路由"分叉（本轮修的就是这个：修前多路由配置在预检处直接 die，扫错密钥还能打 PASS）。
   根治办法是给 `Main` 加配置自检子命令，属新增 CLI 面，不在本轮。

---

## 五、评审与修复轮（2026-09-14，对 `547e2ce`）

独立评审未能证伪产品代码（D24 七个出口逐条走查 + 进程级实测均无静默回落），但抓到**三条必改**，全部有可构造证据：

| # | 缺陷 | 证据（修前） | 修法 | 复验 |
|---|---|---|---|---|
| 1 | 路由名合法性校验的用例**零判别力** | 删掉 `SAFE_NAME` 校验后 17/17 仍全绿（样本名在表里都不存在，两种实现都落"未知名 ⇒ UNKNOWN"） | 新增"表里**真有一个**字面含点/空白的键"的用例 | 变异后新用例**红**、旧的仍绿 |
| 2a | `Main.realLlm` 的选路接线**无自动化判据** | 忽略选名后 20/20 全绿 | `MainRealLlmTest` 加两条（正/反向），夹具让"扁平那条缺密钥、被点名那条可用"两个后果**互斥** | 变异后 2 条**红** |
| 2b | `SubagentProcessMain` 的**模板作用域**无判据 | 把 `templateId` 换成 `"main"` 后 20/20 全绿 | `SubagentRealModelTest` 加"请求体里的模型名必须是被点名那条的" | 变异后 1 条**红** |
| 3 | `smoke-live.sh` 的预检与密钥提取指向扁平那条 | 多路由配置在预检处直接 die（提交者自己的 `.work/multi-route/config.json` 无法被该脚本复跑）；两条都在场时**扫错密钥仍打 PASS** | 预检与取密钥合并为**一次解析**，规则与 `LlmRouteLoader` 逐条对齐 | 三向复跑：多路由取到该条、老扁平配置不变、坏名字响亮 |

**顺手改**（评审建议，均已落地）：`routeName(store, null)` 现在 `requireNonNull`（原先会去读 `agents/null.json`）；
`normalizeName` 的文案与正则对齐（原说"不以连字符开头"，实测也拒首下划线）；`DEFAULT_ROUTE_NAME` 的 javadoc
删掉"事件里作为路由标识用"这句**不成立**的断言，改为如实登记（见 §四.6）。

**未验**（如实登记）：本轮修复没跑真模型、没跑 `smoke-live.sh` 全脚本、没跑全量门禁 / SpotBugs——真模型验收归 D 块与
smoke-live 复跑。
