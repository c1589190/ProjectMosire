# 二轮调查：版本快照与 A2A-CDI 专制（2026-09-08）

> 本轮动机：用户确认模块划分后要求"继续调查"。针对开发计划仍卡住的点：
> ① 各依赖坐标最新版本（Maven Central repo1 实测，非搜索索引——索引明显滞后）；
> ② MCP Java SDK 3.x（2026-07-28 规范）是否已发布；
> ③ a2a-java server 脱离 CDI 的可行性（报告遗留核实项 #4）。
> 证据：【本机验证】repo1.maven.org metadata + GitHub releases/commits API + a2a-java 浅克隆源码（Research/repos/a2a-java，本次重建）。

## 0. 版本快照表（2026-09-08 实测，开发计划 BOM 以此为准）

| 坐标 | 最新版 | 说明 |
|---|---|---|
| `io.modelcontextprotocol.sdk:mcp-core` | **2.0.1**（2026-08-19） | 追踪 2025-11-25 规范；3.x 未发布（见 §1） |
| `io.modelcontextprotocol.sdk:mcp-json-jackson2` / `mcp-json-jackson3` | 2.0.1 | 2.x 线模块化产物 |
| `io.modelcontextprotocol.sdk:mcp-json` | 0.17.2 | ⚠️ 旧线遗留模块，**勿用于 2.x 坐标示例** |
| `io.modelcontextprotocol.sdk:client-jdk-http-client` | 0.18.4 | ⚠️ 同样是旧线，2.x 里没有对应模块 |
| `org.a2aproject.sdk:a2a-java-sdk-bom` / `a2a-java-sdk-server-common` | **1.3.1.Final**（2026-09-03） | 模块全名见 §2 |
| `com.agentclientprotocol:acp-core` | **0.17.0**（2026-08-28） | `acp-model` 独立版本线 0.30.1 |
| `com.ag-ui.community:java-*` 全套 | **0.1.0** | java-core/java-client/java-server/java-ag-ui/kotlin-* 均 0.1.0 |
| `com.fasterxml.jackson.core:jackson-databind` | **2.22.2** | （搜索服务索引滞后显示 2.19.0，以 repo1 为准） |
| `com.fasterxml.jackson:jackson-bom` | **2.22.2** | ⚠️ **不能逐个坐标 pin**：2.20 起 `jackson-annotations` 不再带补丁号（最新 2.22，无 2.22.2）；直接 pin `jackson-annotations 2.22.2` 在 central 找不到 → 必须 import BOM（build 实测踩坑，2026-09-08） |
| `com.networknt:json-schema-validator` | **3.0.7** | |
| `org.xerial:sqlite-jdbc` | **3.53.4.0** | |

## 1. MCP Java SDK 3.x 状态

- **未发布**。ROADMAP.md 明示：3.x 承载 2026-07-28 规范（`server/discover` + SEP-2575 stateless 生命周期），"首个 3.0.0 milestone 计划 **2026 年 9 月**"。
- main 分支正在进行 3.0 准备工作（近期提交）：2026-09-04 "Remove 2024-11-05 protocol from Streamable HTTP transport"；2026-09-07 "ServerHttpHeaderValidator"（对应 2026-07-28 授权硬化）→ **发布在即，需在计划中列为"发布即评估"跟踪项**。
- 1.1.x / 0.18.x 仅安全修复（CHANGELOG 明确）。
- 计划策略不变也证实：锁 2.0.1 起步；协议形态按 2026-07-28 设计、保留 hand-rolled 开关；3.0 发布后升级 SDK 并复用官方 stateless 传输。

## 2. A2A CDI 问题：定案证据与结论

### 证据链
- **client 侧零 jakarta**：`common/`、`client/` 的 pom 只依赖 slf4j（+junit test）；`a2a-java-sdk-client-transport-jsonrpc` 依赖 jsonrpc-common（spec+gson）——**client 角色无任何 CDI 负担**。
- **纯数据层零 CDI**：`a2a-java-sdk-spec`（模型：AgentCard/Task/Artifact 等）= common + gson；`a2a-java-sdk-jsonrpc-common` = spec + gson，提供 A2ARequest/A2AResponse/A2AErrorResponse 及全部方法请求响应类（sendTask/getTask/cancelTask/pushNotificationConfig/…）——**信封与协议协商都在纯模块里**。
- **server-common 全链绑 CDI**：117 个类，compile 依赖 `jakarta.enterprise.cdi-api` + `jakarta.inject-api`，代码里 @Inject 遍布（TaskAuthorizationProvider、PushNotificationSender、RequestHandler、事件处理器…）；`http-client-vertx` 是 provided；quarkus-arc 仅在 test scope；官方示例 helloworld/server 直接用 quarkus-bom。
- **`a2a-java-sdk-transport-jsonrpc`（服务端传输）也 compile 依赖 server-common** → 官方"server-common + transport-jsonrpc"路径整体 CDI/Quarkus 耦合，**无法低于 Quarkus 或等重 CDI 容器使用**。

### 结论：A2A server 走"官方纯模块 + 自写薄绑定"
- 用 `a2a-java-sdk-spec` + `a2a-java-sdk-jsonrpc-common`（官方、Apache-2.0、TCK 同线）承载模型与信封；
- 自写 JSON-RPC HTTP 服务：方法路由（sendTask/sendSubscribeTask/getTask/cancelTask + Agent Card 发现/协商），HTTP 用 jdk.httpserver（虚拟线程）；
- Task 状态机（9 态）与 push notification 语义参照 server-common 实现（Apache-2.0 可合法借鉴并署名）；
- client 角色直接用官方 `a2a-java-sdk-client` + `client-transport-jsonrpc`；
- 合规保障：`a2a-java-sdk-tck` / `itk` 进 CI。
- **备选（B 方案，仅当 TCK 或生态互操作要求 server-common 形态时**：Weld SE（纯 JVM CDI 容器，不用 Quarkus）——比 Quarkus 轻，但仍比 A 重；C 方案 = 接受 Quarkus，与"禁 Spring/轻量化"路线冲突，仅作最后手段。

## 3. 其他确认
- AG-UI 仓库仍活跃（2026-09-07 提交），规范 1.0 仍 Draft（沿用调查报告判断）；社区 Java 全套 0.1.0 → 薄适配层 + 手写 SSE 兜底的策略不变。
- ACP：server 侧官方存在 `acp-ktor-server-jvm` 与 `acp-websocket-jetty` 两条现成路线（P2 用），acp-core 0.17.0。
- MCP 2.0.1 的 HTTP server 绑定仍走 Spring WebMVC/WebFlux 或 server-servlet（实测 repo 模块清单），与我们"核心零 web 依赖"路线不一致 → 自己的 jdk.httpserver 薄适配（POST JSON-RPC + SSE 两条路由）+ stdio 优先，策略不变。

## 4. 对开发计划的直接输入
1. BOM 锁定表 = §0 全部坐标（另 MCP 侧用 mcp-core + mcp-json-jackson2 + mcp-bom）。
2. A2A server 自研范围 = 模型/信封复用官方，实现部分 = 路由 + 状态机语义 + HTTP 绑定；client = 官方 SDK。
3. 计划中列"**MCP 3.0 发布即评估**"（预计 2026-09 内）与"AG-UI 1.0 逐版跟上"两个外部跟踪项。
4. repos/ 已重建（当前仅 a2a-java 259MB 浅克隆）；其余参考仓库（MCP java-sdk 等）待按需补充，不影响计划。
