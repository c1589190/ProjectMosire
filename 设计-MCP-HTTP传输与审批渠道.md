# 设计：MCP Streamable HTTP 传输与通用审批渠道（2026-09-19）

> 基线：`AgentLibMosire` `main @ 15b00b8`；MCP SDK **2.0.1**（`mcp-core` + `mcp-json-jackson2`，Jackson 2 线）；Java 21。
> 输入：`~/SimulatorMosire/docs/superpowers/specs/2026-09-19-agentlib-extension-requirements.md`（下称"需求书"）的 P1-A / P1-B / P1-C 与 §四 硬约束。
> 上级纪律：`AGENTS.md`（模块边界、门禁、红线）、`设计-审批与工具内权限.md`（审批内核的语义来源）、`设计-插件系统与bash插件化.md`（体裁与"判据先行"惯例）。

**为什么要有这份文档。** SimulatorMosire 是第三个要"经网络用 MCP 调 AgentLib 工具"的消费方：它要把自身操作包装成 `AgentTool`，在 5715 端口暴露 MCP 服务，让外部 MCP 客户端完成 `initialize → tools/list → tools/call`，而 `tools/call` 仍必须经过 `ToolCallAuthorizer`（含审批路径）。今天 AgentLib 唯一的非 stdio 注入口是**包私有的测试钩子**（`AgentToMcpServer.startWith(...)`，javadoc 明写"供测试"），SDK 2.0.1 的服务端 HTTP transport 又**全是 servlet 系**（要拖 servlet-api + 容器），与两仓"JDK `com.sun.net.httpserver.HttpServer` + 零框架"的既有形态不符。同时，审批渠道的**接口**在 AgentLib、**实现**却在 MainMosire（`HttpApprovalChannel` + `ApprovalHttpServer`），simos 若自写就是第三份重复。本文把这三件事（P1-A 公开注入口、P1-B JDK-HttpServer 版 Streamable HTTP server transport、P1-C 通用审批渠道）的**结构与判据**一次定死。

**为什么它是硬门禁。** §一 的 verb→session→`Mcp-Session-Id` 路由表是本计划最高风险项：HTTP 的"一次请求"与 MCP 的"一个会话"之间没有天然对应，SDK 用 `McpStreamableServerSession` 把两者缝起来，缝错的表现是"客户端 initialize 成功、tools/list 却 404"这类**静默半死**状态。因此路由表与实现面（provider / transport 各自要实现哪几个方法）未落本文之前，**不得写 provider 代码**。

---

## 〇 结论先行（冻结决策）

| # | 问题 | 裁决 | 主要理由 |
|---|---|---|---|
| P1-A | 怎么公开 transport 注入口 | **方案 (a)**：直接公开既有三个 `startWith(...)` 重载 | 零新 API 面、零重载歧义；javadoc 从"供测试"改正式契约 + `@since`（§三） |
| P1-B | 5715 走网络还是 stdio | **做 JDK-HttpServer 版 Streamable HTTP**：`AgentToMcpServer.startHttp(HttpServer, String, ...)` 便捷工厂 + 独立 provider | 保持总纲拓扑；与两仓"零框架"形态一致；SDK 现成实现全是 servlet 系（§一、§四） |
| A3 | 审批端点用哪套路径 | 沿用 MainMosire 既有 `/api/approvals`（GET 列表）+ `/api/approvals/{id}`（POST 决议） | **不采用**需求书示例的 `/pending`、`/decide`：会破坏 MainMosire 既有界面（§五） |
| S6 | `/api/commands/mode` 归谁 | 留在 MainMosire 侧**薄包装**（只注册端点） | 档位逻辑不搬进 AgentLib 审批件；AgentLib 审批件只做审批（§五） |
| C4 | `McpWireCode` 承诺到哪 | **只承诺工具结果错误**（`toCallToolResult` 路径） | 未知工具错误维持既有裸消息形态，本轮不改（改它是行为变更，与"只做新增"冲突）（§六） |
| P1-C | 审批渠道怎么复用 | AgentLib 提供通用 HTTP 审批件（传输 + 登记表接入），UI 归消费方 | 消灭第三份重复；语义照现行实现固化（§七） |
| 兼容 | 下游依赖面 | 零 public API 签名/包名改动；公开类集合只增不减；继续 `mcp-core` + `mcp-json-jackson2` | simos 钉 13 个类 + JAR 类数 ≥118；禁切带 Jackson 3 的聚合 `mcp`（§八） |

---

## 一 verb→session→`Mcp-Session-Id` 路由表（最高风险项）

### 1.1 路由表

下表是 provider 的**全部** HTTP 行为契约。`path` 是 `startHttp` 的路径形参（默认 `/mcp`）；`sessions` 是 `ConcurrentHashMap<String, McpStreamableServerSession>`；`closing` 是 provider 的关闭标志（volatile boolean）。

| HTTP 输入 | 前置条件 | 动作 | 响应 |
|---|---|---|---|
| POST `initialize` | 无会话头 | `sessionFactory.startSession(InitializeRequest)` → `initResult().block()` 取结果；`sessions.put(session.getId(), session)` | 200 + `Mcp-Session-Id` 响应头 + `application/json`（体 = `JSONRPCResponse.result(req.id, initResult)`） |
| POST 普通 request | `Mcp-Session-Id` 命中 `sessions` | `session.responseStream(request, exchangeTransport)` | 200 + `text/event-stream`（SSE 帧 `id:` / `event: message` / `data:` + 空行 + flush；请求处理完 transport 自关） |
| POST notification | 会话命中 | `session.accept(notification)` | 202 |
| POST response | 会话命中 | `session.accept(response)` | 202 |
| GET | 会话命中 + `Accept` 含 `text/event-stream` | `session.listeningStream(exchangeTransport)`，然后**停驻 handler 线程**（`CountDownLatch`，由 transport 的 `close()` 倒数） | 200 + `text/event-stream`（长连，周期 `: ping` 注释探活） |
| DELETE | 会话命中 | `session.delete()` + `sessions.remove(id)` | 200 |
| 路径不精确等于 `path` | — | — | 404（**注意**：`createContext` 是**前缀**匹配，必须显式精确守卫，见 §1.3） |
| 方法不在 GET/POST/DELETE | — | — | 405 + `Allow` 头 |
| `closing == true` | — | — | 503 |
| POST 非 initialize 但无/未知会话头 | — | — | 无头 → 400；未知 id → 404 |
| body 超过上限 | — | — | 413 |
| `Accept` 不合格 | POST 需**同时**含 `application/json` 与 `text/event-stream`；GET 需含 `text/event-stream` | — | 400 |

**校验顺序（写死，避免"该 404 却 400"这类不可辨失败）**：路径精确 → `closing` → body 上限（POST）→ `Accept` 合格性 → 会话头存在性 → 会话命中。initialize 是唯一豁免会话头的 POST（它在应答里**发放**会话头）。

### 1.2 实现面枚举（provider / transport 各要实现哪几个方法）

**provider 必须实现**（`McpStreamableServerTransportProvider`，继承 `McpServerTransportProviderBase`）：

- `setSessionFactory(McpStreamableServerSession.Factory)`
- `notifyClients(String, Object)`
- `notifyClient(String, String, Object)`
- `close()`
- `closeGracefully()`

**transport 必须实现**（`McpStreamableServerTransport`，继承 `McpServerTransport` → `McpTransport`）共 5 个方法：

- `sendMessage(JSONRPCMessage)`
- `sendMessage(JSONRPCMessage, String)`
- `unmarshalFrom(Object, TypeRef)`
- `close()`
- `closeGracefully()`

**明确不在实现面**：`McpTransportStream` / `consumeSseStream` **不属于服务端 transport 面**。它是 client 侧接口，2.0.1 全 SDK 只有 `io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport` 实现/使用它；服务端 provider 只需把 SSE 帧写出去，不消费 SSE 流。另：`McpStreamableServerSession.replay` 在 2.0.1 **返回 `Flux.empty()`**（源码注释记为"历史存储尚未实现"），所以服务端**没有**可重放的历史，见 §四 的"不承诺 resumability"。

### 1.3 三条易错点（每条都配判别性用例）

1. **`createContext` 是前缀匹配**：`createContext("/mcp", ...)` 会同时接住 `/mcp/extra`。路由表要求"路径不精确等于 `path` ⇒ 404"，因此 handler 第一句必须是 `exchange.getRequestURI().getPath()` 与 `path` 的**逐字相等**判定，不相等直接 404。变异：删掉该守卫 ⇒ `/mcp/x` 得到 200 而非 404，用例转红。
2. **GET 必须停驻线程**：JDK `HttpServer` 没有 servlet 的 `AsyncContext`；handler 方法一旦返回，`HttpServer` 就会收尾这次交换。因此 GET 建立 listening stream 后必须阻塞在 `CountDownLatch.await()` 上，由该 transport 的 `close()` 负责 `countDown()` 并关闭响应体。变异：把停驻换成"注册后立刻返回" ⇒ 客户端 GET 立刻 EOF，长连用例转红。
3. **`initialize` 是唯一的会话创建点**：只有 POST `initialize` 调 `sessionFactory.startSession`；任何其它请求都不允许"顺手建会话"。变异：让普通 POST 也能建会话 ⇒ "无会话头的普通 POST"用例得到 200 而非 400，转红。

### 1.4 会话头的读写口径

- **读**：从 `HttpExchange.getRequestHeaders().getFirst("Mcp-Session-Id")`（大小写不敏感，JDK `Headers` 已归一）。
- **写**：仅 `initialize` 应答写 `Mcp-Session-Id`；其余响应不写。
- 头缺失/空白 = 未提供；头存在但 `sessions` 未命中 = 未知会话（404，与"未提供"的 400 分开，理由：客户端能据此判断"我的会话被服务端丢了"而不是"我忘了带头"）。

---

## 二 生命周期所有权与关闭次序

`startHttp` 有两种所有权形态，关闭语义**不同且必须写进 javadoc**：

| 形态 | 谁拥有 `HttpServer` / `Executor` | `AgentToMcpServer.close()` 的行为 |
|---|---|---|
| **caller-owned**（调用方传已建好的 `HttpServer`，含 `setExecutor` 过的） | 调用方 | **只** `removeContext(path)`；**永不** `stop()` / `shutdown()` 调用方的 server 或 executor |
| **owned**（`startHttp` 自己 `HttpServer.create(...)` 并自建 executor） | 本对象 | 依序：① 取消 registry 订阅 → ② `server.close()`（级联 provider `closeGracefully()`，逐个关会话）→ ③ `HttpServer.stop(0)` → ④ `executor.shutdown()` |

**硬约束**：`HttpServer.stop(int delay)` 只停服务、**不会**关用户传进来的 `Executor`。因此 caller-owned 形态下若实现里顺手调了 `executor.shutdown()`，会**静默拆掉调用方共享的线程池**（表现是调用方其它模块的请求突然全部挂起）。这条单独写进代码注释与本文。

owned 形态的 ②→③ 顺序不能颠倒：先 `server.close()` 让 provider 有机会 `closeGracefully()` 把每个会话的 listening stream 收掉（唤醒 §1.3 停驻的 handler 线程），再 `stop(0)`；若先 `stop(0)`，停驻线程会被强制中断在 `latch.await()` 上，SSE 客户端看到的是裸断连而不是有序收尾。

---

## 三 P1-A 形态：公开既有 `startWith(...)` 三重载（方案 a）

**裁决：方案 (a)**，不新增 `startWithTransport(...)` 公名工厂。理由：三个重载已在，语义稳定、测试在跑；新增公名会引入第二套命名与重载选择成本，且与既有 `start(...)` 家族产生"哪个才是推荐入口"的歧义。

公开后，javadoc 从"供测试注入自定义 transport"改为**正式稳定契约**，补 `@since`，并写清：

1. **两个 provider 接口是兄弟**：`McpServerTransportProvider` 与 `McpStreamableServerTransportProvider` **都只继承 `McpServerTransportProviderBase`，互不继承**（2.0.1 源码实测）。
   ⇒ `startWith(ToolRegistry, ..., McpServerTransportProvider, ...)` **收不到** streamable provider（`McpStreamableServerTransportProvider` 不是 `McpServerTransportProvider` 的子类型）。
2. **误传必须响亮**：若调用方把 streamable provider 传进只收 `McpServerTransportProvider` 的重载（Java 编译器通常已挡下；运行期反射/泛型擦除路径仍可能发生），实现必须抛 `IllegalArgumentException`，**带可读中文原因**（如"本重载只接受 McpServerTransportProvider；Streamable HTTP 请改用 AgentToMcpServer.startHttp(...)"），**不得**让它退化成 `ClassCastException`（那会丢掉"该用哪个入口"的线索）。
3. **streamable 走 `startHttp`**：`startHttp` 是 `McpStreamableServerTransportProvider` 的便捷工厂，内部委托到公开后的 `startWith` 全参重载（transport 形参收 `McpServerTransportProviderBase`，或新增一个收 streamable provider 的重载；落地时以"不新增重名歧义"为准）。

**authorizer 形参必填**：`startHttp` 的 `ToolCallAuthorizer` 形参是**必填**，**不得**缺省为 `ToolCallAuthorizer.standard()`。理由：`standard()` 只做三要素判定、**不含审批闸**，缺省它等于"网络入口静默绕过审批"——这正是 S4 判定点统一要消灭的失败形态。既有 `start(...)` 重载保留 `standard()` 缺省（不破兼容），但 `startHttp` 这个**新**入口不给该缺省。

---

## 四 协议与安全边界

| 项 | 裁决 | 依据 / 后果 |
|---|---|---|
| resumability | **不承诺** | `McpStreamableServerSession.replay` 在 2.0.1 返回 `Flux.empty()`，服务端没有历史可重放；带 `Last-Event-ID` 的 GET 不做特殊处理（落到普通 GET 路径，建立新 listening stream）。SSE 帧仍带 `id:`（协议兼容），但**不保证**重连后补齐。 |
| `MCP-Protocol-Version` 请求头 | **接受但不强制校验** | 照抄 SDK 2.0.1 的 servlet 参考实现行为（`HttpServletStreamableServerTransportProvider` 不读该头；它只用 `Accept` 与会话头）。不新增"版本不符就拒"的逻辑。 |
| 绑定地址 | **默认恒绑 `127.0.0.1`** | 非回环绑定须**显式 opt-in**（显式 host 形参/配置），且启动时打**响亮告警**："本面无鉴权无 TLS，绑定非回环地址 = 任何人可调工具与审批"。 |
| 鉴权 / TLS | **无**（本轮） | 与既有 AdminREST、审批 HTTP 面同基线。对外提供服务必须先有鉴权，属另一个包的事。 |
| `startHttp` 的 authorizer | **必填** | 见 §三；防静默绕过审批。 |
| SSE 传输 | **chunked**：`sendResponseHeaders(200, 0)` | 长度 0 = chunked，长连必须用它；写 JSON 响应才用 `sendResponseHeaders(200, bytes.length)`。 |
| 写路径并发 | **`ReentrantLock`，不用 `synchronized`** | 虚拟线程下 `synchronized` 会 pin 住载体线程（carrier pinning），长连写场景放大该代价。 |
| 编码 | **显式 `StandardCharsets.UTF_8`** | 不依赖平台缺省编码（`Content-Type` 也显式带 `charset=utf-8`）。 |
| 请求体上限 | 有（默认与 SDK 同量级，见 §1.1 的 413） | 不做流式解析；超限直接 413。 |
| 探活 | GET 长连周期写 `: ping` 注释行 | SSE 注释帧不产生事件，只保活；周期可配。 |

---

## 五 A3 裁决：审批端点沿用 MainMosire 既有路径

**裁决**：审批端点沿用 MainMosire 既有 `/api/approvals`（GET 列表）与 `/api/approvals/{id}`（POST 决议）。**不采用**需求书示例里的 `/pending`、`/decide`。

理由：MainMosire 的 `ApprovalHttpServer` 已把 `/api/approvals` 系列定死并有 UI/脚本消费（`GET /api/approvals` 返回 `{"pending":[...]}`；`POST /api/approvals/{id}` body `{"decision":"approve|deny","scope":"once|session","by":"..."}`，200 / 404 / 409 三态可辨）。改名会破坏 MainMosire 既有界面，而 P1-C 的目标是**消灭重复**、不是**改协议**。

**`/api/commands/mode`（S6 档位端点）留在 MainMosire 侧薄包装**：

- 该端点（`GET` 读 / `POST` 改 `full|limited`）只**注册**在 MainMosire 的审批 HTTP 面上，其处理逻辑继续调用 `CommandModeHolder`。
- **不把**档位逻辑搬进 AgentLib 的通用审批件：AgentLib 审批件只做"审批传输 + 登记表接入"，档位是主 Agent 的命令策略，属 Brain/Main 面。搬进去会让 AgentLib 认识一个它本不该有的概念。
- 未装配 `CommandModeHolder` 时该端点不存在（一律 404），与现行实现一致。

---

## 六 C4 裁决：`McpWireCode` 的承诺范围

**裁决**：`McpWireCode` 的承诺范围 = **工具结果错误**（即 `AgentToMcpServer.toCallToolResult(ToolResult)` 路径，`AgentToMcpServer.java:358-361`）。`ToolResult` 经该路径编码为 `[mosire:code=<code>]\n<message>`，`McpToolSource.map` 对称解码。

**未知工具错误维持既有裸消息形态**：`AgentToMcpServer` 的 `error(String)`（`AgentToMcpServer.java:363-367`）对"工具不存在"直接返回裸文本 `"工具不存在: <name>"`，**不带** `[mosire:code=…]` 标记，解码端回退为通用码 `MCP_TOOL_ERROR`。

**本轮不改**：把未知工具错误也带上 `[mosire:code=TOOL_NOT_FOUND]` 属**行为变更**，与 §四 硬约束"只做新增，不改现有 public API 的行为契约"冲突（下游可能已按裸消息形态匹配）。因此本轮**只固化现状、不改代码**；若将来要统一，须单独提案并配"解码端兼容两种形态"的用例。

---

## 七 P1-C 语义表与 MainMosire 迁移

### 7.1 语义表（照现行实现固化，每条都要有判别性用例）

| 语义 | 固化内容 | 出处 |
|---|---|---|
| `publish` 可 no-op | HTTP 面不推送：待裁决列表直接读登记表快照，人自己来取 | `HttpApprovalChannel.publish`（`MainMosire/.../HttpApprovalChannel.java:60-63`） |
| `decide` 幂等 | 同一 id 只认第一次；重复决议返回 `false`，HTTP 层据此返 409，**不覆盖**首次决定 | `PendingApprovals.decide`（`:101-118`） |
| 超时 ⇒ `DENY` | 到点没人答 ⇒ DENY，`by == ApprovalCoordinator.SOURCE_TIMEOUT`（字面量 `"timeout"`）；并摘除登记项 | `ApprovalCoordinator.java:52,162-165` |
| 无渠道 ⇒ `SOURCE_NO_CHANNEL` | 没有任何 `available()` 的通道 ⇒ **立即** DENY，`by == "no-channel"`，**fail-closed 绝不放行** | `ApprovalCoordinator.java:55,147-149` |
| `APPROVE_SESSION` 的会话键 | 键 = `(callerKey, classKey)`；受 `sessionGrantable` 约束（缺省只认 `"SYSTEM"`，非唯一身份调用者降级为一次） | `ApprovalCoordinator.java:68,188-197,207-214`；`PendingApprovals.grantSession`（`:225-238`） |
| 审计事件 | `approval.requested` payload `{"id","tool","classKey","digest"}`；`approval.decided` payload `{"id","tool","classKey","digest","decision","scope","by","latencyMs"}`，`scope ∈ once / session / none`。**行为与 payload schema 不变** | `ApprovalEventTypes.java:10-19`；`ApprovalCoordinator.finish/emitRequested` |
| 安全基线 | 恒绑回环（`127.0.0.1` 硬写死）、无鉴权、无 TLS | `ApprovalHttpServer.java:27-33,118` |
| `effectiveDecision` | 回执里的 `scope` 必须是**实际生效**的那个（收窄的唯一实现，不得在 HTTP 层复制 `"SYSTEM"` 字面量或降级规则） | `ApprovalHttpServer.java:51-54,267-269` |

### 7.2 迁移方案

1. **AgentLib 新增通用 HTTP 审批件**（包 `io.mosire.agentlib.approval`，命名落地时定）：把 MainMosire 的 `ApprovalHttpChannel` 形态（`ApprovalChannel` 实现，`available()` 由装配层 `markUp()` 打开）与"最小 HTTP 端点"提炼进来，依赖只有 AgentLib 自己的 `PendingApprovals` / `ApprovalCoordinator` / `CommandMode`（**不含** `CommandModeHolder` 的档位端点，见 §五）。端点路径**沿用** `/api/approvals` 与 `/api/approvals/{id}`。
2. **MainMosire 改为消费 AgentLib 件**：删除本地重复实现（`MainMosire/src/main/java/io/mosire/main/approval/` 下与 AgentLib 重复的类），`App.java` 的装配（`:335-400`）改为构造 AgentLib 件。`/api/commands/mode` 的注册留 MainMosire 薄包装（§五）。
3. **simos 兜底不变**：即使不做 P1-C，simos 也能自写（~百行：实现 `ApprovalChannel` + 两个 JSON 端点）。

### 7.3 删除范围

**只删** MainMosire 本地与 AgentLib 新件重复的类（即非附录 A 锚点的本地重复实现）。**明确保留**：

- `TtyApprovalChannel`（tty 通道，非 HTTP，不重复）；
- `/api/commands/mode` 的注册与档位处理（薄包装，S6 端点）；
- 所有附录 A 锚点类（§八）。

删除的判据是"该类在 AgentLib 新件里有逐字等价物"；不能确定等价的，保留并记为待办，不猜。

---

## 八 兼容性硬约束

1. **零 public API 签名/包名改动**：只做新增。特别地 `AgentTool` / `AgentToMcpServer` 现有 `start(...)` 重载 / `ToolCallAuthorizer.execute` / `ApprovalCoordinator.decide` / `PendingApprovals` 的全部公开方法保持不变。
2. **`AgentLibMosire` 公开类集合只增不减**：JAR 类数现状**恰好 118**（`AgentLibMosire/target/agentlib-mosire-0.1.0-SNAPSHOT.jar` 实测 `118`），simos 的 `AgentLibAvailabilityTest.MIN_EXPECTED_CLASSES = 118` 是**下限**。因为 118 是**贴边**值，只看"≥118"挡不住"新增若干类后删掉一两个"（总数仍 ≥118 但被删的类可能正是 simos 依赖的）⇒ 必须额外做 **FQN 集合 diff**：改动前后各导出一次 JAR 内 `.class` 的 FQN 全集，断言 `before ⊆ after`（只增不减）。
3. **13 个被 simos 钉住的类不得改名/挪包**（`AgentLibAvailabilityTest` 的 `@ValueSource`，逐字列出）：
   - `io.mosire.agentlib.tool.ToolCallAuthorizer`
   - `io.mosire.agentlib.tool.Digest`
   - `io.mosire.agentlib.permission.ResourceAuthorizer`
   - `io.mosire.agentlib.permission.ResourceScope`
   - `io.mosire.agentlib.permission.ResourceScopeMap`
   - `io.mosire.agentlib.approval.ApprovalCoordinator`
   - `io.mosire.agentlib.approval.AskKind`
   - `io.mosire.agentlib.plugin.HostServices`
   - `io.mosire.agentlib.plugin.PluginToolSource`
   - `io.mosire.agentlib.llm.OpenAICompatibleLlmClient`
   - `io.mosire.agentlib.llm.LlmRouteLoader`
   - `io.mosire.agentlib.event.SqliteEventStore`
   - `io.mosire.agentlib.config.FileConfigStore`
4. **继续 `mcp-core` + `mcp-json-jackson2`**（Jackson 2 线）：**禁**切聚合 `mcp` 构件（它带 Jackson 3，与 simos 的 Jackson 2.22 / jackson-bom 冲突）。
5. **门禁**：改动后 `./mvnw -pl AgentLibMosire -am install` 产出的 SNAPSHOT 必须让 simos 的 `AgentLibAvailabilityTest` 全绿（13 类可加载 + JAR 类数 ≥118 + FQN 只增不减）。

---

## 九 判据（怎么知道成了）

1. **路由表逐行可辨**：对 §1.1 每一行有一条用例，断言 HTTP 状态码（+ 关键响应头），变异守卫即转红（§1.3 三条各配一条）。
2. **实现面完整**：provider 5 方法、transport 5 方法全部实现且被用例覆盖；`McpTransportStream` / `consumeSseStream` **不出现**在服务端实现类里。
3. **真客户端跑通**：官方 MCP 客户端（或 Inspector）经 `http://127.0.0.1:5715/mcp` 完成 `initialize → tools/list → tools/call`；错误码仍走 `McpWireCode` 的 `[mosire:code=…]` 约定（工具结果错误路径）；`ToolSpec.noExport` 的工具不出现；`tools/call` 仍经 `ToolCallAuthorizer`（含审批路径）。
4. **审批闭环**：`pending → decide → await` 走通；重复 decide 不炸（409）；超时 DENY（`SOURCE_TIMEOUT`）；无渠道 fail-closed（`SOURCE_NO_CHANNEL`）。
5. **关闭次序**：owned 形态按 ①→④ 次序收尾且无残留线程；caller-owned 形态下调用方的 executor **未被 shutdown**（判别性用例：传一个自建 executor，`close()` 后断言它仍能执行任务）。
6. **兼容**：simos `AgentLibAvailabilityTest` 全绿 + FQN 集合 diff 断言 `before ⊆ after`。

---

## 十 不做（边界）

- **不做** P2-D（cap 硬上限语义）、P2-E（`Operation` 扩展）、P2-F（MCP 按会话身份）——需求书明确暂不做。
- **不承诺** resumability；**不校验** `MCP-Protocol-Version`。
- **不做**审批 UI（UI 归各消费方）；**不做**鉴权 / TLS（本轮）。
- **不把** S6 档位端点逻辑搬进 AgentLib；**不改**未知工具错误的裸消息形态（§六）。
- **不切**聚合 `mcp` 构件（Jackson 3）。
