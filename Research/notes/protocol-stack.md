# 协议栈调查报告（子调查 A：protocol-stack）

> 调研日期：2026-09-08（模拟当前日期）。调研方式：web_search / web_fetch 官方规范站点与 GitHub 仓库页面为主，辅以工作区 `Research/repos/modelcontextprotocol` 本地克隆（仅作规范文本比对，结论一律以 Web 公开页面为准）。
> 对齐基准：`00-任务与原始资料摘要.md`。
> 结论口径：每条关键结论附来源链接；无法核实的明确写"未能核实"；与用户材料不符的明确标出。

---

## 0. 总览（一页结论）

| # | 协议 | 当前真实状态（2026-09-08） | 我们需要的角色 | Java 支持 | 总体判断 |
|---|------|--------------------------|----------------|-----------|----------|
| 1 | MCP | 规范 2026-07-28 版（无状态大改，已发布）；Java SDK 2.0.1（官方，**Tier 2**） | client + server | 官方 SDK，成熟 | 第一优先级，立即采用 |
| 2 | A2A | v1.0.0 于 2026-03-12 发布，当前 v1.0.1；a2a-java 1.3.1.Final | server + client | 官方 SDK，三传输齐全 | 第一优先级 |
| 3 | AG-UI | 1.0 规范仍是 **Draft（未定稿）**；Java SDK 存在但属"社区维护"，Central 上为 `com.ag-ui.community:*:0.1.0` | server（对用户 UI） | 社区 Java SDK 0.1.0（core/client/server+Spring） | 第二优先级，需兜底方案 |
| 4 | ACP | v1 为 Latest、v2 Draft；远程传输 WIP；官方 Pure Java SDK 0.17.0 | agent（若面向编辑器） | 官方纯 Java SDK（stdio+WebSocket） | 可选，第二阶段以后 |
| 5 | Agent Skills | 规范已定（frontmatter+目录布局+渐进式披露）；广泛采纳 | 消费方（+可产出） | 不需要 SDK（文件格式） | 基础能力，立即支持 |
| 6 | AGENTS.md | 开放约定，60k+ 仓库采用，LF/AAIF 托管 | 消费方+仓库内提供 | 不需要 SDK | 基础能力，立即支持 |
| 7 | MCP Apps | 2026-01-26 成为第一个正式 MCP Extension（production-ready） | server 侧返回 UI 资源（宿主端为可选） | 无官方 Java SDK（npm 有 ext-apps） | 可选，第二阶段 |
| 8 | A2UI | v0.9.1 = Current；**v1.0 仍为 Candidate** | 生产者（生成 JSON） | 不需要 SDK（纯 JSON 生成） | 可选，观察 |
| 9 | ANP | 1.1 版，早期阶段，采纳极少 | 不绑定 | 无 | 观察，不采用 |
| 10 | 老 IBM ACP / AutoGen / MAF | 老 ACP **2025-08-27 已归档并归并 A2A**；AutoGen **已 maintenance mode**，后继 Microsoft Agent Framework（.NET/Python/Go，无 Java） | 不用 | — | 不用 / 观察 |

---

## 1. MCP（Model Context Protocol）

### 1.1 规范版本与最近修订

- 官方 spec 仓库：[modelcontextprotocol/modelcontextprotocol](https://github.com/modelcontextprotocol/modelcontextprotocol)；规范采用**日期版本**（YYYY-MM-DD）。
- **当前发布版本：`2026-07-28`**（2026-07-28 发布）；上一版为 `2025-11-25`。历史版本：2024-11-05 / 2025-03-26 / 2025-06-18 / 2025-11-25 / 2026-07-28；`draft` 为进行中的下一版。来源：[规范目录](https://modelcontextprotocol.io/specification/2026-07-28)、[官方发布博客](https://blog.modelcontextprotocol.io/posts/2026-07-28/)。
- 2026-07-28 是"史上最大更新"，核心变化（[官方博客](https://blog.modelcontextprotocol.io/posts/2026-07-28/) + [规范 changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog)）：
  1. **无状态核心（stateless core）**：移除 `initialize`/`notifications/initialized` 握手与 `Mcp-Session-Id`；每个请求自带协议版本与客户端能力（`_meta.io.modelcontextprotocol/protocolVersion`、`clientCapabilities`、`clientInfo`），服务器在结果 `_meta` 中返回 `serverInfo`。版本不匹配返回 `UnsupportedProtocolVersionError`（code -32022，含 `supported` 列表）。
  2. **`server/discover`**：服务器必须实现，用于宣告支持的协议版本/能力/身份；客户端可选先调用。
  3. **MRTR（Multi Round-Trip Requests）**：取代服务端反向请求（`roots/list`、`sampling/createMessage`、`elicitation/create`）。服务器返回 `resultType: "input_required"` + `inputRequests`；客户端携带 `inputResponses` 重试原请求。所有结果必须带 `resultType`（`"complete"` / `"input_required"`）。
  4. **Header 路由**：Streamable HTTP 请求必须带 `Mcp-Method`、`Mcp-Name` 头；工具参数可带 `x-mcp-header` 自定义头。
  5. **可缓存列表**：`tools/list`、`prompts/list`、`resources/list`、`resources/read`、`resources/templates/list` 结果带 `ttlMs` + `cacheScope`（`public`/`private`），列表要求确定性排序。
  6. **订阅改版**：删除 HTTP GET 端点与 `resources/subscribe`/`unsubscribe`，改为单一 `subscriptions/listen` 长连接流；删除 SSE 断点续传（`Last-Event-ID`）。
  7. **授权加固**：OAuth 授权服务器须按 [RFC 9207](https://datatracker.ietf.org/doc/html/rfc9207) 返回 `iss` 且客户端必须校验；DCR 增加 `application_type`；客户端凭证绑定签发者；**DCR 正式弃用，转向 CIMD（Client ID Metadata Documents）**。
  8. **正式 extensions 框架**：能力协商增加 `extensions` 字段；Tasks 移出核心成为官方扩展 `io.modelcontextprotocol/tasks`（`tasks/get` 轮询 + `tasks/update`）；MCP Apps（`io.modelcontextprotocol/ui`）、Enterprise-Managed Authorization 等均为官方扩展。
  9. **特性生命周期政策**：Active/Deprecated/Removed，最少 12 个月弃用窗口（[policy](https://modelcontextprotocol.io/community/feature-lifecycle)）。
  10. 删除：`ping`、`logging/setLevel`、`notifications/roots/list_changed`；资源 not-found 错误码 -32002 → -32602。

### 1.2 核心概念现状（按用户清单逐项）

- **lifecycle**：旧版的 initialize 握手生命周期**已废除**；新模型是"每请求自带版本+能力" + 可选 `server/discover`（[versioning](https://modelcontextprotocol.io/specification/draft/basic/versioning)）。SDK 层面仍保留 McpServer/McpClient 对象生命周期管理。
- **tools / resources / prompts**：仍是核心三件套（server features），未变。
- **roots**：**已弃用**（SEP-2577，最早 2027-07-28 起可能移除）；迁移路径是"通过工具参数/资源 URI/服务器配置传目录"。
- **sampling**：**已弃用**（同 SEP-2577）；建议直接集成 LLM provider API。
- **elicitation**：仍存在（client feature），但机制改为 **MRTR**：`elicitation/create` 作为 `inputRequests` 的一部分；`notifications/elicitation/complete` 与 URL 模式的 `elicitationId` 已删除，服务器用 `requestState` 自编码关联。
- **logging**：**已弃用**；改为 stderr / OpenTelemetry。

### 1.3 Transport 现状

- **stdio**：保留，本地进程通信的主力。
- **Streamable HTTP**：主推远程传输；单端点 POST，可选 SSE 响应流；**支持 stateless 部署模式**（无会话，任何请求可落到任意实例，可过普通负载均衡）。
- **HTTP+SSE（2024/2025 旧版传输）**：**正式弃用**，一年过渡期（[deprecated registry](https://modelcontextprotocol.io/specification/2026-07-28/deprecated)）。

### 1.4 官方 Java SDK（重点核实项 #1）

- 仓库：[modelcontextprotocol/java-sdk](https://github.com/modelcontextprotocol/java-sdk)（"The official Java SDK … Maintained in collaboration with Spring AI"）。
- **当前 release：`v2.0.1`（2026-08-19 发布）**；2.0.0 为 2026-07-28 规范对应的大版本（有 [MIGRATION-2.0.md](https://github.com/modelcontextprotocol/java-sdk/blob/v2.0.1/MIGRATION-2.0.md)）。Central 上最新 2.0.1（[maven-metadata](https://repo1.maven.org/maven2/io/modelcontextprotocol/sdk/mcp/maven-metadata.xml)）。
- **Maven 坐标**：groupId `io.modelcontextprotocol.sdk`。BOM `mcp-bom`；模块：
  - `mcp`（聚合/核心 SDK）、`mcp-core`（协议模型+传输+服务器，**无 JSON 库绑定**）、`mcp-json-jackson2`、`mcp-json-jackson3`（JSON 适配）、`mcp-test`（测试工具）。
  - Spring AI 提供 Boot starters（client/server starter、MCP Annotations、MCP Security）——[Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)。
- **Tier 定位（重要修正）**：官方 [SDK 列表页](https://modelcontextprotocol.io/docs/2026-07-28/sdk) 将 **Java 列为 Tier 2**（Tier 1 为 TypeScript/Python/C#/Go/Rust；Tier 2 还有 Ruby；Tier 3 为 Swift/PHP/Kotlin）。Tier 2 = "积极维护、向完整协议支持迈进"（80% conformance、新特性 6 个月内跟进、issue 一个月内 triage）。**用户材料中"正式 Tier 路线"属实，但 Java 不是 Tier 1。**
- **Conformance 状态**（[VALIDATION_RESULTS.md](https://github.com/modelcontextprotocol/java-sdk/blob/main/conformance-tests/VALIDATION_RESULTS.md)）：Server 40/40（100%）；Client 3/4 场景、9/10 检查；Auth（Spring）12/14 场景（98.9%）。Java 17+。
- **Transport 支持**：
  - Client：`StdioClientTransport`、`HttpClientStreamableHttpTransport`、`HttpClientSseClientTransport`（旧 SSE）。
  - Server：`StdioServerTransportProvider`、`HttpServletStreamableServerTransportProvider`、`HttpServletStatelessServerTransport`（无状态模式）、`HttpServletSseServerTransportProvider`（旧 SSE）；WebFlux/WebMvc 经 Spring AI starter。
- **API 风格**：`McpServer.sync(transport).serverInfo(...).capabilities(...).build()` → `McpSyncServer`；`McpServer.async(...)` → `McpAsyncServer`（Reactor）；然后 `addTool/addResource/addPrompt`。最小示例形态（[官方 server 文档](https://java.sdk.modelcontextprotocol.io/latest/server/)）：

```java
McpSyncServer server = McpServer.sync(transportProvider)
    .serverInfo("my-server", "1.0.0")
    .capabilities(ServerCapabilities.builder().tools(true).prompts(true).build())
    .build();
server.addTool(syncToolSpecification);
```

  - 2.0 变更要点：`Tool.builder(name, inputSchemaMap)` 必填优先构造器；wire 记录必填字段构造期强校验；反序列化宽松+WARN 兜底。

### 1.5 对目标软件的落点

- **角色**：两个都要。主 Agent（BrainMosire）作为 **MCP client** 调用外部工具/数据；我们把自身模块能力包装成 **MCP server**（tools 为主，resources 次之）供外部生态调用。
- **Java 可用性**：官方 SDK 2.0.1，直接可用；注意 Tier 2 意味着新规范特性有 6 个月滞后窗口（2.0.1 已覆盖 stateless/MRTR 等核心）。
- **成熟度风险**：**中**。规范刚经历破坏性大改（stateless 转型），生态尚在迁移期（老 SSE/会话式客户端仍大量存在）；建议同时兼容 2025-11-25 legacy 模式（SDK 支持双 era）。
- **工作量（粗粒度）**：低-中。客户端+服务端各约 1-2 人周（含 stateless server 部署与 OAuth/CIMD 接入）；若叠加自研 sub-agent 权限模型与工具注册表，再 +1-2 人周。
- **注意**：不要在新实现中采用 Roots/Sampling/Logging/HTTP+SSE（均弃用）。

---

## 2. A2A（Agent2Agent）

### 2.1 规范状态（重点核实项 #2）

- 仓库：[a2aproject/A2A](https://github.com/a2aproject/A2A)；规范站点 [a2a-protocol.org](https://a2a-protocol.org/)。
- **v1.0.0 于 2026-03-12 正式发布（与用户材料一致，已核实）**；当前为 **v1.0.1（2026-05-28）**。历史：v0.1.0…v0.3.0（2025-07-30）。来源：[GitHub Releases](https://github.com/a2aproject/A2A/releases)。
- 治理：2026-08-27 起 A2A 加入 **Agentic AI Foundation（Linux Foundation）**（[公告](https://a2a-protocol.org/latest/blog/2026/08/27/a-new-chapter-for-a2a-joining-the-agentic-ai-foundation/)）。
- 用户材料声称"150+ 组织支持"：**已核实**——官方 [Partners 页](https://a2a-protocol.org/latest/partners/) 列出 **172 家组织**（AWS、Google、Microsoft、Salesforce、SAP、ServiceNow、Atlassian、IBM Research、阿里云、Zoom 等）。

### 2.2 Agent Card 必要字段

按 a2a-java `AgentCard` 记录（[AgentCard.java](https://github.com/a2aproject/a2a-java/blob/main/spec/src/main/java/org/a2aproject/sdk/spec/AgentCard.java)）与[规范定义](https://a2a-protocol.org/latest/definitions/)：
- **必填**：`name`、`description`、`version`、`capabilities`、`defaultInputModes`、`defaultOutputModes`、`skills`、`supportedInterfaces`。
- 可选：`provider`、`documentationUrl`、`securitySchemes`、`securityRequirements`、`iconUrl`、`signatures`（签名 Agent Card）、`url`、`preferredTransport`、`additionalInterfaces`（v0.3 兼容）。
- 另有 `getExtendedAgentCard`（扩展卡）与多租户路由（`/{tenant}/...`）。

### 2.3 Task 生命周期状态

`TaskState`（[TaskState.java](https://github.com/a2aproject/a2a-java/blob/main/spec/src/main/java/org/a2aproject/sdk/spec/TaskState.java)）：
- 过渡态：`submitted`、`working`；
- 中断态（需外部动作）：`input-required`、`auth-required`；
- 终态：`completed`、`canceled`、`failed`、`rejected`、`unspecified`。

### 2.4 Artifact 与消息

- Task 有 `artifacts`（由 `Part` 组成：`TextPart`/`FilePart`(FileWithBytes/FileWithUri)/`DataPart`）、`history`、`contextId`；`SendMessageConfiguration` 支持 `acceptedOutputModes`、`historyLength`、`returnImmediately`、push notification 配置。
- 事件：`TaskStatusUpdateEvent`、`TaskArtifactUpdateEvent`；流式（SSE）与非流式均有。

### 2.5 Transport 与 Push Notification

- 三种绑定：**JSON-RPC**、**gRPC**（`a2a.proto` + google.api.http 注释）、**HTTP+JSON(REST)**（同一 proto 生成的 REST 绑定）。
- **Push notification**：`TaskPushNotificationConfig`（webhook 订阅）+ `ListTaskPushNotificationConfigs` 等；SDK 有 `PushNotificationSender`/`InMemoryPushNotificationConfigStore`。

### 2.6 官方 Java SDK（重点核实项 #2）

- 仓库：[a2aproject/a2a-java](https://github.com/a2aproject/a2a-java)（"Official Java SDK for the Agent2Agent (A2A) Protocol"）。Apache-2.0，Java 17+。
- **当前版本：`1.3.1.Final`（2026-09-03 发布）**；里程碑：1.0.0.Final（2026-06-10）、1.2.0.Final（2026-08-07）、1.3.0.Final（2026-08-27）。来源：[Releases](https://github.com/a2aproject/a2a-java/releases)、[Central BOM](https://repo1.maven.org/maven2/org/a2aproject/sdk/a2a-java-sdk-bom/maven-metadata.xml)。
- **Maven 坐标**：groupId `org.a2aproject.sdk`。核心模块（Central 上均可查）：
  - 模型：`a2a-java-sdk-common`、`spec`（spec 数据模型）、`spec-grpc`（生成 proto 类）、`jsonrpc-common`（JSON-RPC 包装）。
  - Client：`a2a-java-sdk-client` + `a2a-java-sdk-client-transport-{grpc,jsonrpc,rest}`。
  - Server：`server-common`（AgentExecutor、TaskManager、事件总线、多租户、鉴权）、`transport/{grpc,jsonrpc,rest}`；**三传输的参考服务器实现** `reference/*`（基于 Quarkus）；社区另有 Jakarta EE 实现 [a2a-jakarta](https://github.com/wildfly-extras/a2a-jakarta)。
  - 附加：`extras`（JPA TaskStore、Kafka 复制队列、OpenTelemetry、Vert.x/Android HTTP client）、`compat-0.3`（v0.3 兼容）、`tck`、`a2a-java-sdk-bom`。
- **client+server 三传输齐全：已核实**（README 原文："providing client and server support for A2A agent communication over JSON-RPC, gRPC, and REST transports"；[README](https://github.com/a2aproject/a2a-java/blob/main/README.md)、[文档站](https://a2aproject.github.io/a2a-java/)）。
- 最小形态：参考服务器依赖 `org.a2aproject.sdk:a2a-java-sdk-reference-jsonrpc`，实现 `AgentCard` 生产者 + `AgentExecutor`（详见 [Server Guide](https://a2aproject.github.io/a2a-java/server)）；客户端用 `a2a-java-sdk-client` + 对应 transport。

### 2.7 对目标软件的落点

- **角色**：server + client 都要。主 Agent 对外暴露为 A2A server（Agent Card 描述能力）；子 Agent 间及对外部 Agent 的调用走 A2A **client**。
- **Java 可用性**：官方 SDK 1.3.1.Final，功能全（多租户、鉴权、push、任务存储抽象齐备）。
- **成熟度风险**：**低-中**。规范 v1.0 稳定（已出 1.0.1 小修）；SDK 迭代快（近 3 个月 4 个 Final 版本，API 或有小幅漂移）；参考实现绑定 Quarkus，若我们用 Spring Boot 需自行接线（server-common 是 transport 无关的，工作量可控）。
- **工作量（粗粒度）**：中。server 端（AgentCard + AgentExecutor + 任务状态机对接我们自己的执行引擎）约 2-3 人周；client 端 1 人周；push notification 可选。
- **建议**：与 MCP 同属第一阶段；先 JSON-RPC 传输（生态最通用），gRPC/REST 按需后补。

---

## 3. AG-UI（Agent User Interaction Protocol）

### 3.1 规范状态（重点核实项 #3）

- 仓库：[ag-ui-protocol/ag-ui](https://github.com/ag-ui-protocol/ag-ui)；文档站 [docs.ag-ui.com](https://docs.ag-ui.com)。
- **状态：`1.0 Specification (Draft)` —— 草案，未定稿**。规范首页原文："**Draft — not yet ratified.** This document is published for review. It describes the intended 1.0 behaviour… nothing here is covered by a compatibility promise until a version is frozen."（[spec draft](https://docs.ag-ui.com/spec/draft)）。
- 仓库按日期发 release（最近 `release/2026-08-31`，节奏密集）；1.0 draft 相对 0.x 的关键变化见 [Key Changes](https://docs.ag-ui.com/spec/draft/changelog)：outcome/interrupt-resume、Subagents 事件族、THINKING_*→Reasoning 族、Activity 事件（JSON Patch）、HTTP+Protobuf 二进制绑定等。

### 3.2 事件模型与状态机

1.0 draft 定义 **8 个事件族**（[spec](https://docs.ag-ui.com/spec/draft)）：
- **Lifecycle**：`RUN_STARTED`、`RUN_FINISHED`（可带 `outcome`）、`RUN_ERROR`、`STEP_STARTED/STEP_FINISHED`；
- **Text Messages**：`TEXT_MESSAGE_START` / `TEXT_MESSAGE_CONTENT`（delta）/ `TEXT_MESSAGE_END`；
- **Tool Calls**：`TOOL_CALL_START` / `TOOL_CALL_ARGS` / `TOOL_CALL_END` / `TOOL_CALL_RESULT`；
- **Reasoning**（替代 0.x THINKING_*）：reasoning spans + `REASONING_ENCRYPTED_VALUE`；
- **State**：`STATE_SNAPSHOT` / `STATE_DELTA`（含 `MESSAGES_SNAPSHOT`），快照-增量模式；
- **Activity**：`ACTIVITY_SNAPSHOT` / `ACTIVITY_DELTA`（结构化进度，JSON Patch 增量）；
- **Subagents**：`SUBAGENT_STARTED` / `SUBAGENT_FINISHED` / `SUBAGENT_ERROR`，`subagentRunId` 归属；
- **Passthrough**：Raw/Custom 事件。
- 关于用户材料中的 **MetaEvents**：0.x 的 META 事件在 1.0 draft 中并入基础协议的 **Metadata**（合并语义规范化，`ag-ui` 键保留）；"Meta Events" 现在只是 docs 里的一个 Draft Proposal（[/drafts/meta-events](https://docs.ag-ui.com/drafts/meta-events)）。
- 交互模型：一次 `RunAgentInput`（POST）→ 一条有序事件流（snapshot-delta、interrupt-resume、truncation 规则）。

### 3.3 传输（对用户材料的修正）

- **标准绑定只有两个**：**HTTP+SSE**（实现 HTTP 的必须支持）与 **HTTP+Protobuf**（可选，长度前缀帧）。
- **WebSocket 不是标准绑定**：只被允许作为"自定义传输"（须满足绑定契约：有序完整投递、先传 RunAgentInput、终止信号、错误路径）。用户材料"SSE/WebSocket"说法不准确（[transports](https://docs.ag-ui.com/spec/draft/basic/transports)）。

### 3.4 官方 Java SDK？（重点核实项 #3——结论：存在，但非一等）

- **存在**。文档站有完整 Java 页面（[Java SDK Overview](https://docs.ag-ui.com/sdk/java/overview) / core / client / **server（含 spring.mdx，Spring Boot 集成）**），且 Overview 文案称 "the official Java SDK"。
- 但两层"折扣"必须说明：
  1. **一等 SDK 只有 TypeScript / Python / .NET**。规范原文："AG-UI is maintained with three first-party SDKs — TypeScript, Python and .NET… **Other language bindings are community-maintained**"（[spec draft](https://docs.ag-ui.com/spec/draft)）。
  2. **仓库位置是 `sdks/community/java/`**（与 community/dart、community/rust、community/ruby 同列）；代码包名为 `com.agui.community.*`（core/client/server 三模块，server 含 LocalAgent/EventFactory/AgentStreamer）。
- **Maven Central 实际坐标**（重点）：`com.ag-ui.community:java-core / java-client / java-server / java-ag-ui`，**当前版本 0.1.0**（[Central](https://repo1.maven.org/maven2/com/ag-ui/community/)）。**注意：docs 页面写的 `com.ag-ui:core/client/http:0.0.1` 坐标已过时/与实际发布不符**。
- SDK 形态：client = `HttpAgent`（JDK HttpClient，SSE 解码为 `Flow.Publisher<Event>`）；server = `LocalAgent` 继承实现 `run(RunAgentInput, AgentSubscriber)` + `EventFactory.runStartedEvent/textMessageStartEvent/...`；Spring 集成 = `AgUiService` 输出 `SseEmitter` + 自动配置。
- 用户材料"官方文档称已列有 Java SDK/integrations"：**属实**（[integrations 页](https://docs.ag-ui.com/integrations) SDK 表含 Java 链接），但准确表述应为"社区维护的 Java SDK，官方文档收录"。

### 3.5 对目标软件的落点

- **角色**：我们需要 **server**（Agent→用户 App/UI 的出口）。client 侧是前端/桌面壳，不在本项目范围内。
- **Java 可用性**：有社区 SDK（0.1.0，client+server+Spring），但版本极早期、非一等、规范本身还是 draft。
- **成熟度风险**：**高**（三条叠加：规范未定稿 + SDK 0.1.0 + 社区维护）。
- **建议策略（两段式）**：
  1. 首选：试用 `com.ag-ui.community:java-server` + Spring 集成，但**隔离在薄适配层**后面；
  2. 兜底：AG-UI 事件流本质是"一条 JSON SSE 流"，手工映射成本低。**最小可行事件子集建议**（供兜底实现）：`RUN_STARTED` → `TEXT_MESSAGE_START` → `TEXT_MESSAGE_CONTENT`* → `TEXT_MESSAGE_END` → （可选 `TOOL_CALL_START/ARGS/END`、`STATE_SNAPSHOT/DELTA`）→ `RUN_FINISHED`，加 `RunAgentInput` 解析与 `RUN_ERROR`。这一子集即可兼容主流前端渲染器。
- **工作量（粗粒度）**：用 SDK 1-2 人周；手工兜底子集 0.5-1 人周。
- **建议阶段**：第二阶段（P1），在 MCP/A2A 跑通后接入。

---

## 4. ACP（Agent Client Protocol，agentclientprotocol.com，非 IBM 老 ACP）

### 4.1 规范状态

- 站点：[agentclientprotocol.com](https://agentclientprotocol.com/)；仓库：[agentclientprotocol/agent-client-protocol](https://github.com/agentclientprotocol/agent-client-protocol)。发起方为 **Zed Industries + JetBrains**。
- 定位：Editor/IDE ↔ Coding Agent 的标准化（类 LSP 之于语言服务器）；Markdown 为默认文本格式；复用 MCP 的部分 JSON 表示。
- 版本：**v1 = Latest**，**v2 = Draft**（[导航](https://agentclientprotocol.com/protocol/v1/overview)）。
- 传输：本地 = **JSON-RPC 2.0 over stdio（Agent 必须支持）**；远程 = HTTP/WebSocket，但官方明示 **"Full support for remote agents is a work in progress"**（[Introduction](https://agentclientprotocol.com/get-started/introduction)）。
- 消息流：`initialize`（版本/能力协商）→ 可选 `authenticate` → `session/new` 或 `session/load` → `session/prompt` → Agent 发 `session/update` 通知（进度/消息块/工具调用/plan）→ 可 `session/cancel` → 响应带 stop reason。

### 4.2 核心方法（v1 schema 实测）

从官方 [schema.json](https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/v1/schema.json) 提取：
- 会话：`initialize`、`session/new`、`session/load`、`session/prompt`、`session/update`(通知)、`session/cancel`、`session/list`、`session/delete`、`session/set_mode`、会话配置选项、slash commands。
- 文件系统：`fs/read_text_file`、`fs/write_text_file`（及 read/write/update 系列）。
- 终端：`terminal/create`、`terminal/output`、`terminal/kill`、`terminal/wait_for_exit`、`terminal/release`。
- 权限：**`session/request_permission`**（`PermissionOption` + 选项结果）——注意：用户材料写的 "permission_request" 不是实际方法名。
- 计划：v1 中 plan 以 `session/update` 通知携带的 **Plan/PlanEntry**（create_plan、research_codebase 等命令）呈现；**v1 schema 中不存在独立的 "plan/streaming" 方法**（可能属 v2 draft 或材料有误）。
- 其他：authenticate、elicitation、tool calls、cancellation。

### 4.3 官方 Pure Java SDK（重点核实项 #4——已核实存在）

- 仓库：[agentclientprotocol/java-sdk](https://github.com/agentclientprotocol/java-sdk)（"Pure Java implementation of the ACP specification for building both clients and agents"）。
- **Maven 坐标**：groupId `com.agentclientprotocol`：
  - `acp-core`（当前 Central 最新 **0.17.0**；README 示例写 0.16.0）、`acp-agent-support`（注解式）、`acp-annotations`、`acp-websocket-jetty`（WebSocket server 支持）；同 group 下另有 Kotlin Multiplatform 产物（acp-jvm/acp-js/acp-ktor 等）。来源：[README](https://github.com/agentclientprotocol/java-sdk/blob/main/README.md)、[Central](https://repo1.maven.org/maven2/com/agentclientprotocol/acp-core/maven-metadata.xml)。
- **三种 API 风格**：annotation-based（`@AcpAgent`/`@Prompt`）、sync（阻塞 handler）、async（Project Reactor `Mono`）。
- **传输**：stdio + WebSocket（Jetty）。Java 17+。
- 用户材料"官方 Pure Java SDK（client+agent、stdio+WebSocket、sync+async、annotation-based agent）"：**全部属实**。

### 4.4 对目标软件的落点

- **角色**：仅当我们的软件要作为**编码 Agent 被编辑器驱动**时才需要实现 **agent** 角色。对"通用多模块 Agent 软件"而言 ACP 是**可选**的（若未来想被 Zed/JetBrains/VS Code 类编辑器接入则启用）。
- **Java 可用性**：官方纯 Java SDK 0.17.0，直接可用。
- **成熟度风险**：**中**。协议 v1 可用但 v2 draft 在途；远程传输（HTTP/WebSocket）官方标注 WIP；SDK 0.x 仍在快速迭代。
- **工作量**：中（agent 侧 1-2 人周，若做编辑器级体验如 diff 渲染再 +1 周）。
- **建议阶段**：P2（第二阶段以后按需）。

---

## 5. Agent Skills

### 5.1 规范（重点核实项 #5）

- 站点：[agentskills.io/specification](https://agentskills.io/specification)；组织仓库：[agentskills/agentskills](https://github.com/agentskills/agentskills)。
- **目录布局**：一个 skill = 一个目录，必须含 `SKILL.md`；可选 `scripts/`（可执行代码）、`references/`（文档）、`assets/`（模板/资源）。
- **SKILL.md frontmatter**（YAML）：
  - 必填：`name`（≤64 字符，小写字母/数字/连字符）、`description`（≤1024 字符）；
  - 可选：`license`、`compatibility`（≤500 字符，环境要求）、`metadata`（string→string map）、`allowed-tools`（预授权工具，**Experimental**）。
- **渐进式披露（三档）**（[client implementation](https://agentskills.io/client-implementation/adding-skills-support)）：
  1. Catalog：会话开始只载入 name+description（约 50-100 token/技能）；
  2. Instructions：技能被激活时载入完整 SKILL.md 正文（建议 <5000 token）；
  3. Resources：正文引用到 scripts/references/assets 时才按需读取。
- **采纳情况**：格式源自 Anthropic Claude 生态，现由 agentskills 组织维护并有官方 Discord；采纳面广（各大 coding agent 产品普遍支持；A2A v1.0 的 Agent Card 也内嵌 `skills` 字段形成呼应）。具体采纳清单页以客户端渲染为主、未能在本次调研中逐条复核——**采纳者名单未能逐一核实**。

### 5.2 对目标软件的落点

- **角色**：消费方为主（加载/解析/按需注入），将来也可产出（把能力打包成 skill 分发）。
- **Java 支持**：不需要 SDK——它是纯文件格式；实现 = 目录扫描 + YAML frontmatter 解析 + 三档加载策略。
- **风险**：低。`allowed-tools` 是实验字段；其余稳定。
- **工作量**：极小（0.5-1 人周）。
- **建议阶段**：P0 基础能力（与"子 Agent 权限模型"直接相关：skill 声明的 allowed-tools 可映射为子 Agent 权限）。

---

## 6. AGENTS.md 约定

- 定义：[agents.md](https://agents.md/)——"README for agents"：仓库根目录的 Markdown 文件，描述 build/test 命令、代码风格、约定、禁改清单等。**无必填字段**，纯 Markdown；支持嵌套（最近文件的 AGENTS.md 优先；用户显式指令最高优先级）。
- **采纳**：60k+ 开源项目（[GitHub 搜索口径](https://github.com/search?q=path%3AAGENTS.md+NOT+is%3Afork+NOT+is%3Aarchived&type=code)）；支持者：OpenAI Codex、Google Jules、Cursor、GitHub Copilot coding agent、Gemini CLI、Aider、goose、opencode、Zed、Warp、VS Code、Devin、Windsurf 等。
- 治理：已移交 **Agentic AI Foundation（Linux Foundation）**托管（[公告](https://openai.com/index/agentic-ai-foundation/)）。
- **落点**：① 我们的每个模块仓库应提供 AGENTS.md（自管理目标的一部分）；② 我们的 Agent 在操作第三方仓库时必须**优先读取并遵守其 AGENTS.md**。无 SDK，工作量≈0；P0。

---

## 7. MCP Apps

### 7.1 状态与规范要点（重点核实项 #6）

- **状态**：2026-01-26 官方（MCP Core Maintainers）宣布 "MCP Apps are now live as an **official MCP extension**… This is the **first official MCP extension**, and it's ready for production"——用户材料的日期与表述**已核实**（官方博客：[2026-01-26 公告](https://blog.modelcontextprotocol.io/posts/2026-01-26-mcp-apps/)；原始提案见 [2025-11-21](https://blog.modelcontextprotocol.io/posts/2025-11-21-mcp-apps/)）。
- 扩展标识：**`io.modelcontextprotocol/ui`**；官方仓库 [modelcontextprotocol/ext-apps](https://github.com/modelcontextprotocol/ext-apps)；完整规范站 [apps.extensions.modelcontextprotocol.io](https://apps.extensions.modelcontextprotocol.io)。
- **机制**：工具在 `_meta.ui.resourceUri` 声明 `ui://` UI 资源 → 宿主拉取 HTML 资源包（可带 `_meta.ui.csp` 白名单外部源、`permissions` 请求摄像头等能力）→ **在沙箱 iframe 中渲染** → App 与宿主间经 **JSON-RPC over postMessage** 双向通信（`ui/` 前缀方法 + 复用 `tools/call`；App 可调用服务器工具、更新模型上下文、接收工具结果）。
- **宿主支持矩阵**（[client matrix](https://modelcontextprotocol.io/extensions/client-matrix)）：Claude web/Desktop、VS Code Copilot、Microsoft 365 Copilot、Goose、Postman、MCPJam、ChatGPT、Cursor、Archestra.AI、PostHog Code 已支持。
- 注意：扩展协商通过 2026-07-28 的 `extensions` 能力字段进行；SDK 目前仅有 npm `@modelcontextprotocol/ext-apps`（App 端），**无官方 Java 库**。

### 7.2 对目标软件的落点

- **角色**：我们是 **MCP server 侧**（让某些工具返回 `ui://` 资源即可，含 HTML 打包与 CSP 声明）；若未来自建宿主 UI 才需要 iframe 沙箱渲染端。
- **Java 支持**：无官方 Java SDK；server 侧只需在 MCP 工具元数据里加 `_meta.ui` + 提供 `ui://` 资源读取端点，可基于现有 MCP Java SDK 手工实现。
- **风险**：中（宿主覆盖不全、App 端 API 还在演进；对我们 server 侧风险低）。
- **工作量**：低-中（0.5-1.5 人周）。
- **建议阶段**：P2。

---

## 8. A2UI

### 8.1 状态（重点核实项 #6）

- 站点：[a2ui.org](https://a2ui.org/)；仓库：[a2ui-project/a2ui](https://github.com/a2ui-project/a2ui)。Apache-2.0，Google 发起，CopilotKit 等贡献。
- 版本矩阵（[官网版本表](https://a2ui.org/)）：**v0.9.1 = Current（生产）**、v0.9 = 旧稳定、v0.8 = Legacy；**v1.0 = Candidate（仍未正式发布——用户材料"仍为 Candidate"已核实）**。v1.0 Candidate 新增 client→server RPC（`actionResponse`）、action IDs，`theme` 改名 `surfaceProperties`。
- 消息类型（[message reference](https://a2ui.org/reference/messages/)）：v0.8 为 `beginRendering`/`surfaceUpdate`/`dataModelUpdate`/`deleteSurface`；**v0.9 起为 `createSurface`/`updateComponents`/`updateDataModel`/`deleteSurface`**（JSONL 流式，`application/a2ui+json`，带 `version` 字段）。用户材料中 "surfaceUpdate/dataModelUpdate" 是 **v0.8 旧命名**。
- Renderer 生态：Angular、Flutter、Lit、Markdown 等官方/社区 renderer（[renderers](https://a2ui.org/reference/renderers/)）；传输：A2A Extension、A2UI over MCP、AG-UI harness 等；配套 A2UI Composer/Theater 工具。

### 8.2 对目标软件的落点

- **角色**：我们是**生产者**（Agent 生成声明式 UI JSON 流，交给前端 renderer 渲染）——不需要 Java SDK，就是"LLM 输出 JSONL"加校验。
- **风险**：中（v1.0 未定稿、v0.9→v1.0 有命名变化；对我们的影响仅是输出 schema 版本选择）。
- **工作量**：低（按 v0.9.1 schema 生成即可，约 0.5 人周）。
- **建议阶段**：P2 可选，观察 v1.0 定稿。

---

## 9. ANP（Agent Network Protocol）

- 站点：[agent-network-protocol.com](https://agent-network-protocol.com/)；仓库：[agent-network-protocol/AgentNetworkProtocol](https://github.com/agent-network-protocol/AgentNetworkProtocol)。
- 版本：**ANP 1.1 = Latest**（1.0 存档）。
- 组成部分：技术白皮书、**DID:WBA**（did:wba 去中心化身份）、**WNS**（可读名称空间）、Agent Description、Agent Discovery、**即时消息 P1-P9**（core binding/身份发现/单聊/群聊/端到端加密/附件/联邦/提及）、Agent Payment Protocol（AP2 适配）。
- 成熟度判断：**早期**。规范齐整但组件较新（消息协议刚升级）、采用面窄、无主流产品背书；属"Agent Internet"愿景型协议。用户材料"1.1 版但部分 draft"——1.1 为当前版属实，站点未对各组件逐一标注 draft 状态，**"部分 draft"的说法未能精确核实**。
- **落点**：**不绑定、仅观察**（用户材料判断正确）。若未来出现跨域身份/发现需求，只取 DID:WBA/Discovery 等基础件再评估。

---

## 10. 老 IBM ACP、AutoGen 与 Microsoft Agent Framework

### 10.1 老 IBM ACP（i-am-bee/ACP）——归并 A2A：已核实

- 仓库 [i-am-bee/ACP](https://github.com/i-am-bee/ACP) 页面显示："**This repository was archived by the owner on Aug 27, 2025. It is now read-only.**"，README 顶部横幅："🚀 IMPORTANT UPDATE — **ACP is now part of A2A under the Linux Foundation!**"。
- 与用户材料"2025-08-27 归并 A2A、仓库归档"**完全一致**；A2A Partners 列表中 IBM Research 条目引用的正是 LFAI 归并公告（2025-08-29，[链接](https://lfaidata.foundation/communityblog/2025/08/29/acp-joins-forces-with-a2a-under-the-linux-foundations-lf-ai-data/)）。新项目禁用。

### 10.2 AutoGen → maintenance mode：已核实

- [microsoft/autogen](https://github.com/microsoft/autogen) README 带 "**Maintenance Mode**" 徽章："AutoGen is now in maintenance mode. It will not receive new features or enhancements and is community managed going forward. New users should start with Microsoft Agent Framework…"（含官方迁移指南）。

### 10.3 Microsoft Agent Framework（microsoft/agent-framework）

- 状态：AutoGen 的**企业级后继**，**1.0 production-ready**（README）；语言：**.NET + Python** 全功能、**Go SDK** 独立仓库（microsoft/agent-framework-go）；**无 Java**。
- 关键能力：多 Agent 编排（图式工作流）、多 provider、**跨运行时互操作 "via A2A and MCP"（即 A2A/MCP 原生支持）**、Foundry Hosted Agents、OpenTelemetry、声明式 Agent（YAML）、Agent Skills、HITL/checkpointing。2026-08-27 新闻：[Agent Framework Adds Support for MCP, A2A, and Multiple Channels](https://adtmag.com/articles/2026/08/27/microsoft-agent-framework-adds-support.aspx)；Python 包已至 1.13.0（[releases](https://github.com/microsoft/agent-framework/releases/tag/python-1.13.0)）。
- **落点**：观察即可（参考其编排/技能设计）；我们不用其代码。

---

## 11. 用户材料声明逐条核查（重点核实项 #11）

| # | 用户材料声明 | 核查结论 | 依据 |
|---|-------------|----------|------|
| 1 | MCP 2026-07-28 规范大改：stateless core | ✅ 已核实 | [官方博客](https://blog.modelcontextprotocol.io/posts/2026-07-28/)、[changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog) |
| 2 | 同上：MRTR | ✅ 已核实 | changelog #7（SEP-2322） |
| 3 | 同上：header routing | ✅ 已核实 | changelog #4（Mcp-Method/Mcp-Name/x-mcp-header） |
| 4 | 同上：cacheable lists | ✅ 已核实 | changelog #5（ttlMs/cacheScope，SEP-2549） |
| 5 | 同上：authorization hardening | ✅ 已核实 | changelog #7-9（RFC9207 iss、DCR→CIMD、application_type） |
| 6 | 同上：正式 extensions framework | ✅ 已核实 | changelog minor#1 + [extensions overview](https://modelcontextprotocol.io/extensions/overview) |
| 7 | MCP "Tier-1 SDK 月下载近 5 亿" | ✅ 已核实（口径近似） | 官方博客："close to half-a-billion downloads a month"（Tier 1 SDKs 合计；TS/Python 累计均破 10 亿） |
| 8 | MCP "有官方 Java SDK（正式 Tier 路线）" | ⚠️ 部分核实 | Java SDK 是官方且纳入 Tier 体系，但为 **Tier 2**（非 Tier 1）。[SDK 列表](https://modelcontextprotocol.io/docs/2026-07-28/sdk) |
| 9 | A2A v1.0 于 2026-03-12 发布、production-ready | ✅ 已核实 | [GitHub Releases](https://github.com/a2aproject/A2A/releases)（v1.0.0, 2026-03-12；现 v1.0.1） |
| 10 | A2A 150+ 组织支持 | ✅ 已核实 | [Partners 页](https://a2a-protocol.org/latest/partners/) 计 172 家 |
| 11 | A2A 官方 Java SDK，三 transport 均有 client+server | ✅ 已核实 | [a2a-java README](https://github.com/a2aproject/a2a-java)（1.3.1.Final；client/transport 与 reference server 模块齐全） |
| 12 | AG-UI 官方文档列有 Java SDK/integrations | ✅ 已核实（但非一等） | [Java SDK Overview](https://docs.ag-ui.com/sdk/java/overview)、[integrations](https://docs.ag-ui.com/integrations)；规范称 TS/Python/.NET 为一等，其余社区维护 |
| 13 | AG-UI 传输 = SSE/WebSocket | ⚠️ 与当前公开信息不符 | 标准绑定 = HTTP+SSE（必）+ HTTP+Protobuf（可选）；WebSocket 仅允许作自定义传输。[transports](https://docs.ag-ui.com/spec/draft/basic/transports) |
| 14 | AG-UI 事件含 MetaEvents | ⚠️ 部分过时 | 1.0 draft 中并入基础 Metadata；"Meta Events"仅为 draft proposal。[changelog](https://docs.ag-ui.com/spec/draft/changelog)、[/drafts/meta-events](https://docs.ag-ui.com/drafts/meta-events) |
| 15 | ACP 官方 Pure Java SDK（client+agent、stdio+WebSocket、sync+async、annotation） | ✅ 已核实 | [java-sdk README](https://github.com/agentclientprotocol/java-sdk)（acp-core 0.17.0 等） |
| 16 | ACP 核心方法含 permission_request、plan/streaming | ⚠️ 与当前公开信息不符 | 实际为 `session/request_permission`；plan 以 `session/update` 中的 Plan 条目呈现，v1 schema 无 `plan/streaming`。[schema.json](https://github.com/agentclientprotocol/agent-client-protocol/blob/main/schema/v1/schema.json) |
| 17 | A2UI v1.0 仍为 Candidate | ✅ 已核实 | [a2ui.org 版本表](https://a2ui.org/)（v0.9.1 Current；v1.0 Candidate） |
| 18 | A2UI 消息 surfaceUpdate/dataModelUpdate | ⚠️ 部分过时 | 那是 v0.8 命名；v0.9+ 为 updateComponents/updateDataModel。[message reference](https://a2ui.org/reference/messages/) |
| 19 | MCP Apps 2026-01-26 成为第一个正式 Extension 且 production-ready | ✅ 已核实 | [官方博客公告（2026-01-26）](https://blog.modelcontextprotocol.io/posts/2026-01-26-mcp-apps/) |
| 20 | Agent Skills 规范（frontmatter/scripts/references/assets/渐进式披露） | ✅ 已核实 | [specification](https://agentskills.io/specification)、[client-implementation](https://agentskills.io/client-implementation/adding-skills-support) |
| 21 | AGENTS.md 定义与采纳 | ✅ 已核实 | [agents.md](https://agents.md/)（60k+ 仓库；LF/AAIF 托管） |
| 22 | 老 IBM ACP 2025-08-27 归档并归并 A2A | ✅ 已核实 | [i-am-bee/ACP](https://github.com/i-am-bee/ACP)（archived 2025-08-27；"ACP is now part of A2A"） |
| 23 | AutoGen 已 maintenance mode，后继 MS Agent Framework | ✅ 已核实 | [autogen README](https://github.com/microsoft/autogen)、[agent-framework](https://github.com/microsoft/agent-framework) |
| 24 | MS Agent Framework A2A/MCP 原生 | ✅ 已核实 | agent-framework README（"cross-runtime interoperability via A2A and MCP"）+ [ADTmag 2026-08-27](https://adtmag.com/articles/2026/08/27/microsoft-agent-framework-adds-support.aspx) |
| 25 | ANP 1.1 版、部分 draft | ⚠️ 部分核实 | 1.1 为当前版属实；"部分 draft"的逐组件标注未能核实。[站点](https://agent-network-protocol.com/) |
| 26 | Hermes Agent ~242k stars（清单第 9 项） | ❌ 未能核实 | 该个人 Agent runtime 确实存在（[教程/报道](https://www.tmtpost.com/7960448.html)），但第三方数字互相矛盾（47k / 100k+ 均有报道），242k 未能核实；建议以 GitHub 页实测为准（本次受 API 限流未取到）。 |
| 27 | 流行产品 MAU/用户量数字（Gemini 10 亿 MAU、Copilot 5000 万等） | ❌ 未能核实 | 属采纳度代理指标，超出本协议调查范围；本次未收集权威口径，不背书。 |

---

## 12. 协议优先级与依赖清单（汇总表）

| 协议 | 我们需要的角色 | Java 支持现状 | 风险 | 建议阶段 |
|------|----------------|---------------|------|----------|
| **MCP** | client（主 Agent 调工具）+ server（把模块暴露为工具） | 官方 SDK `io.modelcontextprotocol.sdk:*:2.0.1`（Tier 2；stdio+Streamable HTTP+stateless） | 中（新规范迁移期；Tier 2 有 6 个月特性滞后） | **P0 第一阶段** |
| **A2A** | server（对外）+ client（调子/外部 Agent） | 官方 SDK `org.a2aproject.sdk:*:1.3.1.Final`（JSON-RPC/gRPC/REST，client+server） | 低-中（参考实现绑 Quarkus，自接线可控） | **P0 第一阶段**（与 MCP 并行或紧随） |
| **AG-UI** | server（Agent→用户 UI） | 社区 SDK `com.ag-ui.community:java-server:0.1.0`（+Spring 集成）；非一等 | 高（规范 Draft、SDK 0.1.0） | **P1 第二阶段**，薄适配层 + 手工事件子集兜底 |
| **ACP** | agent（仅当面向编辑器场景） | 官方纯 Java SDK `com.agentclientprotocol:acp-core:0.17.0`（stdio+WebSocket） | 中（0.x、v2 draft、远程传输 WIP） | **P2 可选** |
| **Agent Skills** | 消费方（+将来产出） | 无需 SDK（文件格式，自实现解析） | 低 | **P0 基础能力** |
| **AGENTS.md** | 消费方 + 仓库内提供 | 无需 SDK | 低 | **P0 基础能力** |
| **MCP Apps** | server 侧返回 `ui://` 资源 | 无官方 Java SDK（基于 MCP Java SDK 手工加 `_meta.ui`） | 中（宿主覆盖不齐） | **P2** |
| **A2UI** | 生产者（生成声明式 JSON 流） | 无需 SDK | 中（v1.0 Candidate） | **P2 可选** |
| **ANP** | 不绑定 | 无 | 高（早期、采纳少） | **观察，不采用** |
| 老 IBM ACP | 不用 | — | — | **禁用** |
| AutoGen / MS Agent Framework | 不用（无 Java） | — | — | **仅参考** |

### 依赖关系小结

1. **第一层（P0）**：MCP + A2A + Agent Skills + AGENTS.md。四个都是成熟/稳定的现成标准，Java 路线明确（MCP/A2A 有官方 SDK），且直接支撑"多模块、多子 Agent、自管理"的核心形态：模块=MCP tools，子 Agent=A2A peers，能力=Skills，仓库自述=AGENTS.md。
2. **第二层（P1）**：AG-UI（用户界面出口）。唯一高风险项，用适配层隔离 + 最小事件子集兜底。
3. **第三层（P2 及以后）**：MCP Apps（Tool→UI）、A2UI（Agent→声明式 UI）、ACP（编辑器接入）。
4. **不碰**：ANP、老 ACP；**参考**：Microsoft Agent Framework 的编排与技能设计。

### 对本项目"自研核心"边界判断的印证

调研支持 `00-任务与原始资料摘要.md` 的边界判断：MCP/A2A/AG-UI 之外确实没有协议解决"个人数字世界的统一描述"（Person/Event/Claim/Decision/WorldState 关系），自研 Personal Information Model + Event Store + Permission/Identity 的定位成立；其中 **Permission/Identity** 部分应尽量复用 MCP 2026-07-28 授权栈（OAuth 2.0 + CIMD + 官方 auth 扩展）与 A2A 的 SecurityScheme/TaskAuthorization，避免自造。
