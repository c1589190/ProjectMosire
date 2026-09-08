# Java 技术栈调查报告 —— 用 Java 写"轻量 Agent 软件"需要注意的方面

> 子调查 C（对齐基准：`00-任务与原始资料摘要.md`）
> 调查日期：2026-09-08（模拟）
> 结论分级约定：
> - **✅ 稳定结论**：有官方/权威来源背书，可放心作为设计依据；
> - **🔶 预览/快速演进**：特性仍在 preview、或版本迭代快、或为社区实现，使用时需做隔离与降级预案；
> - **🖥️ 本机验证**：在本机（OpenJDK 21.0.12 / Ubuntu 24.04 / Maven 3.8.7）用 bash 实测得到的事实；
> - **❓ 无法核实**：未能找到可靠来源确认，仅作参考。
> 版本号未注明处均为 2026-09-08 在 Maven Central（repo1.maven.org）实抓（🖥️）。

---

## 0. TL;DR（一页结论）

| 主题 | 结论 |
|---|---|
| JDK 基线 | 代码基线 **Java 21**（与既有实践一致），运行时尽快上 **JDK 25 LTS**（2025-09-16 GA，支持至 2030-09）。JDK 26 非 LTS。✅ |
| 并发 | **virtual threads 直接用**（JDK 21 final）；synchronized pin 已在 JDK 24（JEP 491）修复，残留 pin 只在 native 帧/类加载。Structured Concurrency **至今仍是 preview**（JDK 26 仍第六次预览）🔶，Scoped Values 已在 **JDK 25 转正**（JEP 506）。✅ |
| 协议 SDK | **MCP：`io.modelcontextprotocol.sdk:mcp:2.0.1`**（官方，Java 17+，stdio + streamable HTTP client/server 全有，内置 JSON-RPC 2.0 层）。**A2A：`org.a2aproject.sdk:1.3.1.Final`**（三传输 client+server）。AG-UI 只有社区 Java 实现（`com.ag-ui.community:0.1.0`，🔶）。ACP 有官方 Pure Java SDK（`com.agentclientprotocol:acp-core:0.17.0`）。✅ |
| 网关 | jdk.httpserver 官方自证"仅限测试/调试"（JDK 25 javadoc），**无 HTTP/2/WebSocket**；stdio 优先时它够用。生产 HTTP 网关备选 **Vert.x 5（vertx-core）** 或 Helidon SE 4。核心模块保持**零 web 依赖 + 传输可替换**。✅ |
| JSON | 统一 Jackson（2.22.2 / 3.2.2 双线并存）；**不引入 jsonrpc4j**（停滞）；MCP SDK 自带 JSON-RPC 层可复用。✅ |
| 沙箱（重点） | SecurityManager 已死（JEP 411 → **JEP 486：JDK 24 起永久禁用**，官方明确建议用 OS 级机制）。**进程级 stdio 隔离 + 网关权限白名单 = Java 下最现实的沙箱**，与 MCP stdio 天然契合；bwrap/seccomp/容器做 OS 级加固。✅ |
| 发行 | 默认 **JVM 常驻 + jlink 定制运行时（~52MB）+ AppCDS**；GraalVM native image 对 MCP/A2A SDK 无官方支持（A2A 有 open bug 证实会破），仅作 CLI 小工具的可选路线。🔶 |
| 存储 | 事件存储首选 **SQLite（WAL）**（11.4MB 内嵌 native，🖥️ 实测通过）；纯 Java 洁癖选 H2（2.6MB）；DuckDB（81.5MB）用于事件分析。✅ |
| 配置 | JSON + **networknt json-schema-validator**（MCP SDK 自身也用它，一个依赖两用）；原子写 + 读回验证 + 回滚。✅ |
| 可观测 | OTel Java **1.65.0**（1.x 仍是主线）+ CloudEvents Java **5.0.0**。✅ |
| LLM 调用 | 核心用 **HttpClient + OpenAI 兼容 REST + SSE 手写**（~200 行，零依赖）；官方 SDK（openai-java 4.58 / anthropic-java 2.61）放可选模块；langchain4j（1.20.0）仅当需要大而全时再评估。✅ |

---

## 1. JDK 版本与语言特性

### 1.1 版本路线（核实结论）

- **JDK 25 是 LTS**：2025-09-16 GA，多数厂商提供长期支持（Oracle 路线图标至 2030-09）。JDK 25 一并交付了 JEP 486（SecurityManager 永久禁用）、JEP 491（virtual threads 不再 pin）、Scoped Values 转正（JEP 506）等。来源：[openjdk.org/projects/jdk/25](https://openjdk.org/projects/jdk/25/)、[Oracle Java Support Roadmap](https://www.oracle.com/bz/java/technologies/jvp-support-roadmap.html) ✅
- **JDK 26 非 LTS**：2026-03-17 GA，Oracle 路线图标为 non-LTS（支持至 2026-09）。带来 JEP 517（HttpClient 支持 HTTP/3）、JEP 525（Structured Concurrency 第六次预览）、JEP 516（Leyden AOT 对象缓存）。来源：[openjdk.org/projects/jdk/26](https://openjdk.org/projects/jdk/26/) ✅
- **下一个 LTS 是 JDK 29**（计划 2027-09），其间 27、28 均为 non-LTS（Oracle 路线图标注 subject to change）。✅
- 本机是 **OpenJDK 21.0.12**（2026-07 构建，Ubuntu 24.04 包）🖥️。
- **建议**：编译目标 `--release 21`（保证 21/25/26 全部可跑，且与用户既有实践一致）；运行基线尽快迁移 **25 LTS**。不要以 26 为基线。

### 1.2 Virtual Threads（虚拟线程）—— agent runtime 的核心并发模型

**价值（✅ + 🖥️）**：agent runtime 是典型的"极高 I/O 并发、极低 CPU 密集"负载——每个 MCP 客户端会话、每条 SSE 推送、每个并发 LLM 流式请求、每次阻塞式 JDBC 调用，都可以天然地写成"一任务一线程"的同步代码，由虚拟线程承载。JDK 21 起 virtual threads 已是正式特性（JEP 444）。本机验证：JDK 21 上 `Executors.newVirtualThreadPerTaskExecutor()` 无需任何开关直接可用；`jdk.httpserver` 换成虚拟线程 executor 后，**100 个并发 SSE 长连接全部在 1.6s 内完成**（🖥️ 实测，见 §2.1）。

**Pin 问题（✅）**：
- JDK ≤23 上，虚拟线程进入 `synchronized` 块后阻塞会 **pin 住载体线程**（JEP 444 原文定义）。很多第三方库因此被建议改用 `ReentrantLock`。
- **JDK 24 起（JEP 491）已从实现上修复**：改造 monitor 实现，虚拟线程独立于 carrier 持有 monitor，`synchronized` 内阻塞不再 pin。残留 pin 场景只剩：native 帧（JNI 方法 / FFM upcall 中阻塞）、类加载与类初始化期阻塞。`jdk.tracePinnedThreads` 已移除；JFR 的 `jdk.VirtualThreadPinned` 事件保留并携带 pin 原因。来源：[JEP 491](https://openjdk.org/jeps/491) ✅
- **实践含义**：① 目标运行时若在 JDK 24+，`synchronized` 可以放心用；② 若先跑 JDK 21，注意热路径锁（尤其含 I/O 的）优先 `ReentrantLock`/无锁结构；③ 无论哪个版本，**不要在 JNI/native 回调里做阻塞 I/O**（如 sqlite-jdbc 的原生调用路径上不要叠加大锁）。

**工程注意点**：虚拟线程池基本无界，所以限流要放在"外部资源"层——LLM API 的并发上限、子进程同时运行数上限、内存预算，而不是线程池大小。

### 1.3 Structured Concurrency / Scoped Values 状态（重要，勿轻信二手信息）

| 特性 | JDK 21 | JDK 24 | JDK 25 | JDK 26 |
|---|---|---|---|---|
| Structured Concurrency | preview（JEP 453）| 第四预览（JEP 499）| **第五预览（JEP 505）** | 第六预览（JEP 525）|
| Scoped Values | preview（JEP 446）| 第四预览（JEP 487）| **转正 final（JEP 506）** | final |

- **Structured Concurrency 至今没有转正**（截至 JDK 26 仍是 preview；JDK 25 里 API 被重构为 `StructuredTaskScope.open()` 工厂 + `Joiner` 风格）。🖥️ 本机验证：JDK 21 上编译 `StructuredTaskScope` 必须带 `--enable-preview`（不带则报 "StructuredTaskScope is a preview API and is disabled by default"）。🔶 来源：[JEP 505](https://openjdk.org/jeps/505)、[JEP 525](https://openjdk.org/jeps/525)
- **Scoped Values 在 JDK 25 转正**（JEP 506，唯一实质变化：`orElse` 不再接受 null）。它在"请求/Agent 上下文传播（agentId、traceId、权限上下文）"上比 ThreadLocal 更适合虚拟线程（不可变、按调用树绑定）。🖥️ 本机验证：JDK 21 上同样需要 `--enable-preview`。✅ 来源：[JEP 506](https://openjdk.org/jeps/506)
- **建议**：JDK 21 上两者都别进生产路径（预览开关）；JDK 25 起 Scoped Values 可用（作为上下文传播主方案）；Structured Concurrency 只做"并行 fan-out + 取消传播"的**可选实验代码**（编译隔离），不要在核心协议代码里依赖它——它还会变。⚠️ 注意 JEP 512 是 "Compact Source Files and Instance Main Methods"，与并发无关（易混淆）。
- **String Templates 已撤销**：JEP 430/459/465 三连预览后 Closed/Withdrawn，JDK 23 起移除，目前无官方替代；拼接用 `+`/`StringBuilder`/`String.formatted` 即可。✅ 来源：[JEP 465](https://openjdk.org/jeps/465)、[StackOverflow 讨论](https://stackoverflow.com/questions/79003380/why-is-string-template-not-available)

### 1.4 records / sealed interfaces / pattern matching 对协议消息建模的价值

- **价值（✅）**：Agent 协议消息模型本质是"封闭的代数数据类型"：JSON-RPC 请求/响应/通知、MCP 消息、A2A Task/Artifact 状态机、AG-UI 事件流。用 `sealed interface` + `record` 建模 + `switch` 穷尽模式匹配，可以：
  - 编译器强制覆盖所有消息变体（新增协议消息类型时编译报错提醒处理）；
  - 消息对象天然不可变 + 值语义（适合并发传递、缓存、去重）；
  - Jackson 2.12+ 原生支持 record 序列化/反序列化（用户的既有 Jackson 实践无缝衔接）。
- **坑**：record 的紧凑构造器 + Jackson 反序列化注意默认值/多态 `@JsonTypeInfo` 的配合；官方 MCP/A2A SDK 内部正是用大量嵌套 record/POJO 建模消息（🖥️ 已解包 `mcp-core-2.0.1.jar` 确认 `McpSchema.JSONRPCResponse` 等类）。
- **结论**：核心协议模块应"sealed + record + 穷尽 switch"建模，这是 Java 21 时代写协议代码最该用的三个特性。

### 1.5 JDK HttpClient

- **HTTP/2**：JDK 11 起内置支持（final）。🖥️ 本机验证：`java.net.http.HttpClient` 对 `repo1.maven.org`、`central.sonatype.com`、`github.com` 三主机握手均返回 `HTTP_2`。JDK 26 起增加 **HTTP/3**（JEP 517）。✅
- **SSE 手动解析**：`BodyHandlers.ofLines()` 返回 `Stream<String>`，逐行解析 `event:`/`data:` 即可实现 SSE 客户端——这是"零依赖 LLM 流式调用"和"MCP streamable HTTP 客户端"的轻量实现路径。注意背压：`ofLines` 的 onNext 阻塞会传导到请求线程。✅
- **WebSocket**：`java.net.http.WebSocket` 是**客户端**实现（RFC 6455），无 permessage-deflate 等扩展；**JDK 没有任何 WebSocket 服务端 API**（`jdk.httpserver` 亦无，见 §2.1）。✅
- **结论**：LLM 调用、MCP 客户端、ACP stdio/WS 客户端、健康检查，全部可以用 HttpClient 覆盖；服务端 WS 需求才需要引入 Netty/Vert.x。

---

## 2. 轻量 HTTP/协议网关层选型

### 2.1 jdk.httpserver 的能力上限（官方已自证定位）

- **官方定性（✅）**：JDK 25 起 `jdk.httpserver` 模块 javadoc 明文写道——"designed and implemented to support a minimal HTTP server and simple HTTP semantics primarily"，"intended for simple usages like local testing, development, and debugging… does not intend to be a full-featured, high performance HTTP server"。来源：[JDK 25 javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/module-summary.html)、[JDK-8347348](https://bugs.openjdk.org/browse/JDK-8347348)
- **无 HTTP/2、无 WebSocket**：OpenJDK 邮件列表明确无计划支持。来源：[net-dev 2019-12](https://mail.openjdk.org/pipermail/net-dev/2019-December/013383.html)。JEP 408（JDK 18）只是加了 `jwebserver` 命令行工具，未提升能力。✅
- **🖥️ 本机实测**（OpenJDK 21 + 虚拟线程 executor）：
  - SSE 可用：`Content-Type: text/event-stream` + 每次 `write+flush`，curl `-N` 能收到渐进输出（tick 0→4 逐条到达）；
  - 无 HTTP/2：`curl --http2` 请求自动回退 HTTP/1.1（无 ALPN）；
  - 长连接并发可用：**100 个并发 SSE 客户端 1.6s 全部完成**（虚拟线程 executor 下）；
  - `HttpsServer` 可配 TLS（SSLContext），本地/内网跑 HTTPS 无问题。
- **适用边界**：stdio 优先架构里，HTTP 只是"控制面"（本地回环的 REST 管理口、健康检查、低并发 A2A REST、AG-UI 本地桥）时，jdk.httpserver **零依赖 + 单 jar** 完全够用；一旦对外服务、并发几百以上、需要 WebSocket/HTTP/2，就换网关模块。**不要**在它上面堆生产级多租户 HTTP 服务。

### 2.2 备选网关对比（版本均 🖥️ Central 实抓）

| 方案 | 当前版本 | 模型 | 轻量度 | 备注 |
|---|---|---|---|---|
| **Vert.x** | 5.1.7（5.0 GA 2025-05-15） | 事件循环（Netty 4 底座） | 中（`vertx-core` 即可用） | 单模块覆盖 HTTP/1.x+HTTP/2+WS+SSE；Eclipse 基金会，活跃。来源：[Vert.x 5 发布](https://vertx.io/blog/eclipse-vert-x-5-released/) |
| **Netty** | 4.2.17.Final / 4.1.137.Final（5.0 仍 Alpha） | 事件循环 | 中 | 底层砖头，HTTP/2/WS 都要自己拼；4.2 于 2025-04 GA。来源：[netty.io/news](https://netty.io/news/) |
| **Undertow** | 2.4.3.Final | XNIO（阻塞+NIO 混合） | 中 | WildFly 体系（WildFly 36 活跃），SSE/WS 支持好，但生态聚焦应用服务器。来源：[WildFly 36](https://www.wildfly.org/news/2025/04/10/WildFly-36-is-released/) |
| **Helidon SE** | 4.5.x（4.5.4 Central） | **纯 virtual threads（Níma）** | 中 | "世界首个完全基于虚拟线程的 WebServer"；官方支持 native-image 与 jlink；Oracle 维护。Helidon 5 ❓ 截至调查日无法核实已发布。来源：[helidon.io](https://helidon.io/) |
| **Spring Boot** | 4.0.0（2025-11-20 GA）→ 4.1.1 | Servlet/Reactive 全家桶 | 重 | 与 Spring AI（1.1，内置 MCP starter + AOT hints）集成最好；轻量目标下仅作"重路线"对照。来源：[Spring Boot 4.0 发布](https://spring.io/blog/2025/11/20/spring-boot-4-0-0-available-now)、[Spring AI 1.1](https://spring.io/blog/2025/11/12/spring-ai-1-1-GA-released) |

**建议**：需要生产 HTTP 网关时，**Vert.x 5（只引 `vertx-core`）** 或 **Helidon SE 4（虚拟线程模型与 agent 代码心智一致）** 二选一；Netty 只在你需要自定义协议栈时考虑；Undertow 留给已有 WildFly 依赖的场景。

### 2.3 核心论据："核心模块零 web 依赖，网关层可替换"

这是本报告最重要的架构边界，证据如下：

1. **MCP stdio 传输天然零 web 依赖**：🖥️ 解包官方 `mcp-core-2.0.1.jar` 可见 `StdioServerTransportProvider`/`StdioClientTransport` 直接基于 stdin/stdout，其 pom 仅依赖 Jackson 注解（JSON 绑定可插拔）。也就是说：**核心 + MCP stdio = 没有任何 servlet/Netty/HTTP 依赖**。
2. **官方 SDK 的 HTTP 服务端反而绑定容器**：MCP SDK 2.x 的 HTTP 服务端传输是 `HttpServletStreamableServerTransportProvider`/`HttpServletSseServerTransportProvider`（需要 servlet 容器）或 spring-webflux/webmvc 模块——即官方也没有"裸 jdk.httpserver"的 MCP 服务端。若坚持 jdk.httpserver 做 MCP streamable HTTP 服务端，需要**自写薄适配**（JSON-RPC POST + SSE GET 两条路由，工作量小且可控，与用户手写 MCP stdio 的风格一致）。
3. **A2A 官方 SDK 本身就是"传输可替换"的实证**：`a2a-java-sdk-client-transport-{jsonrpc,grpc,rest}` 三个并列 transport + `-spi` 模块；参考服务器绑 Quarkus（重），但客户端可只用 Vert.x HTTP 客户端（`a2a-java-sdk-http-client-vertx`，extras 之一）。来源：[a2a-java README](https://raw.githubusercontent.com/a2aproject/a2a-java/main/README.md)
4. **AG-UI 是纯事件协议**：任何能推 SSE/WS 的东西都能承载，不需要特定 web 框架。
5. **设计推论**：核心模块只依赖"传输 SPI"（`send/receive JSON-RPC 或事件`接口）+ stdio 实现；HTTP 网关做成**独立模块**（默认 jdk.httpserver 适配器，需要时换 Vert.x），主程序 shade 时按需并入。这样"轻量化"与"可升级"两不误。

---

## 3. JSON / JSON-RPC 处理

### 3.1 Jackson 双线版本（重要，2026 年的现实）

- **Jackson 2.x**：最新 `com.fasterxml.jackson.core:jackson-databind:2.22.2`（🖥️ Central）。绝大多数既有生态（含 ACP Java SDK、networknt 校验器）仍在这条线。
- **Jackson 3.x**：`tools.jackson.core:jackson-databind` 最新 **3.2.2**（🖥️，2026-08-14），包名换为 `tools.jackson`，与 2.x 可同进程共存（包名不同）。
- **MCP Java SDK 2.x 的 JSON 绑定可插拔**：`mcp-json-jackson2`（固定 jackson-databind 2.21.1）/ `mcp-json-jackson3`（固定 3.1.4）；`io.modelcontextprotocol.sdk:mcp` 聚合包默认带 **jackson3**。🖥️ 已实抓 pom 验证。
- **建议**：用户既有代码是 Jackson 2.x → 用 `mcp-core + mcp-json-jackson2`（不引聚合 `mcp` 包），把整个工程统一在 Jackson 2.22.x（`dependencyManagement` 收敛版本）。不要无意中同时引入 2.x 和 3.x 两套（可共存但翻倍认知成本）。✅

### 3.2 jsonrpc4j：不引入

- `com.github.briandilley.jsonrpc4j:jsonrpc4j` 最新 **1.7**（🖥️ Central，2025-05-10），仓库未归档但实质停滞（最后实质提交 2025-09 是 dependabot，38 个 open issues）。✅ 来源：[GitHub 仓库](https://github.com/briandilley/jsonrpc4j)
- 结论：**不引入**。通用 JSON-RPC 需求三选一：① 手写 ~100 行 JSON-RPC 2.0 编解码（用户既有实践，最轻）；② 复用 MCP Java SDK 内置 JSON-RPC 2.0 层（`McpSchema.JSONRPCRequest/Notification/Response`，JSON 绑定可插拔）；③ 若用 A2A Java SDK，注意其 JSON-RPC 层是 **Gson**（非 Jackson）且官方标注 internal、不当通用库用。

### 3.3 各协议 SDK 的 JSON 现状速查（🖥️ Central + 官方仓库核实）

| SDK | JSON 库 | 说明 |
|---|---|---|
| MCP Java SDK 2.0.1 | Jackson 2 / Jackson 3 可插拔 | 官方 Tier 路线，内置 JSON-RPC 2.0 层 ✅ |
| A2A Java SDK 1.3.1.Final | **Gson 2.14.0** | 三传输 client+server；gRPC-java 1.81 + protobuf 4.35；Java 17+ ✅ |
| ACP Java SDK 0.17.0 | Jackson 2.22.2 | stdio + WebSocket（Jetty 12 服务端）；Java 17+ ✅ |
| AG-UI Java 0.1.0 | 可插拔 Serializer | 社区实现（见下），Java 17+，Apache-2.0 🔶 |

---

## 4. 子 Agent 隔离与沙箱（重点章节）

### 4.1 SecurityManager 已死——Java 内没有进程内权限沙箱了

- **JEP 411（JDK 17）**：Security Manager 标记 deprecated for removal。来源：[JEP 411](https://openjdk.org/jeps/411)
- **JEP 486（JDK 24，Closed/Delivered）**："Permanently Disable the Security Manager"：
  - `-Djava.security.manager=...`（含 `allow`、`default`、自定义类）**启动即报错退出**（不可抑制）；
  - 运行时 `System.setSecurityManager()` 抛 `UnsupportedOperationException`；
  - API 保留但全部退化（`getSecurityManager()` 返回 null、`check*` 一律抛 `SecurityException`、`Policy::setPolicy` 抛 UOE 等），**将来某版会彻底移除 API**。✅ 来源：[JEP 486](https://openjdk.org/jeps/486)、[Oracle 安全指南](https://docs.oracle.com/en/java/javase/25/security/security-manager-is-permanently-disabled.html)
- **JEP 486 官方指明的出路**：像沙箱原生应用一样用 **容器、虚拟机监控器、OS 机制（macOS App Sandbox、Linux seccomp）**——这等于官方背书"进程/OS 级隔离"路线。
- **推论**：任何"在同一个 JVM 里按权限约束子 Agent"的方案，在 Java 24+ 没有官方机制支撑，不要自研假安全边界。

### 4.2 首选：进程级隔离（stdio 子进程）——与 MCP stdio 天然契合

**为什么是它**：

- MCP 的 stdio transport 规范本身就是"父进程 spawn 子进程，stdin/stdout 跑 JSON-RPC"——**"子 Agent = 一个 stdio 子进程" 恰好就是 MCP stdio 标准用法**，用户已有手写实现经验，官方 SDK 的 `StdioServerTransportProvider` 零依赖（🖥️ 已验证）。✅
- 隔离面：内存空间、崩溃、`System.exit`、资源耗尽都不会拖垮主进程；进程可被 `ProcessHandle.destroyForcibly()` 硬杀；输出大小、运行时长、CPU 份额都可在父进程侧限额。
- 权限模型落在**网关**而非进程内：父进程（主 Agent）决定给子进程暴露哪些 MCP 工具/资源（工具列表 allowlist）、哪些环境变量、哪个工作目录、能否联网（配合 §4.3）。这是"自由创建不同权限子 Agent"最直接的实现：**权限 = spawn 参数 + 网关白名单**，二者都是数据、都可被 Agent 自管理。
- 成本：🖥️ 实测**空闲 JVM 进程 RSS ≈ 38.7MB**（vs bash 1.6MB）。所以：子进程**尽量用原生工具**（脚本、CLI 二进制）；必须跑 Java 逻辑时用"少量常驻工作进程 + 复用"而非每次冷起。启动时间 JVM 冷起 ~0.3-1s，对"跑一次任务"型子 Agent 可接受。
- 分级设计（推荐直接采用）：
  - L0（默认）：stdio 子进程 + 超时 + 输出上限 + 环境白名单；
  - L1：+ 独立 OS 用户 / 受限目录（chroot 或 bwrap 只读根）；
  - L2：+ bwrap/容器网络隔离（见 §4.3）。

### 4.3 OS 级加固：bwrap / seccomp / 容器

- **bubblewrap（bwrap）**：Flatpak 同款，**无特权** user-namespace 沙箱（内核 ≥4.19），支持只读根文件系统、私有 tmpfs、`--unshare-net` 断网、`--die-with-parent` 等；Ubuntu 下 `apt install bubblewrap` 即可。🖥️ 本机未装（未验证），属发行版标准包。来源：[containers/bubblewrap](https://github.com/containers/bubblewrap)
- **seccomp**：系统调用过滤，bwrap `--seccomp` 或 docker 默认 profile 可挡掉大部分危险 syscall；适合"工具可能被 prompt 注入操纵"的场合。
- **容器**：🖥️ 本机装有 docker（cgroup v2 生效）；rootless podman 更轻。镜像管理/启动开销明显大于 bwrap，适合需要完整发行版环境的场景（如"跑任意 Python 代码"的 sandbox）。
- **平台差异**：macOS 用 `sandbox-exec`；Windows 用作业对象（Job Objects）——若承诺跨平台，需把"沙箱后端"做成可插拔（stdio 隔离本身全平台可用，OS 加固按平台适配）。
- **结论**：默认 stdio 子进程；给"低可信工具"套 bwrap（文件只读 + 断网 + tmpfs 工作区）；容器只在需要完整镜像生态时用。

### 4.4 JPMS ModuleLayer 与 ClassLoader 插件——进程内隔离的取舍

| 方案 | 隔离强度 | 成本 | 适用 |
|---|---|---|---|
| **ClassLoader 隔离** | 仅类命名空间 + 静态状态隔离；**无任何安全边界**（可反射/可 `System.exit`/可吃光堆） | 极低 | 受信任的一手插件（自己写的） |
| **JPMS ModuleLayer** | 强封装（未 export 包反射即失败）+ 模块依赖白名单；但**同 JVM 无 CPU/内存/退出隔离**，恶意代码可 DoS 整个 JVM | 中（每个插件要规范 module-info） | 受信任、要求加载边界干净的插件 |
| **进程级（§4.2）** | 完整边界（内存/崩溃/资源） | 中低（spawn + IPC 而已） | **默认路线，尤其不可信代码** |
| **bwrap/容器（§4.3）** | OS 级边界 | 中高 | 低可信、需要文件/网络隔离的工具 |
| **WASM（Chicory，前沿 🔶）** | 纯 Java 的 WASM 解释器（零 native 依赖），有 CPU 中断/限额、host functions 白名单 | 中 | in-JVM 跑不可信 WASM 模块（限计算类逻辑） |

- Chicory：dylibso 维护的纯 Java WASM runtime，[CPU 限额/中断](https://chicory.dev/docs/usage/cpu/#interrupts)、[host functions](https://chicory.dev/docs/usage/host-functions/) 提供了"进程内但受限"的新选项，可关注（🔶 前沿观察，勿作默认）。来源：[InfoQ 报道](https://www.infoq.com/news/2024/05/chicory-wasm-java-interpreter/)
- **核心结论**：**信任边界 = 进程边界**。进程内（ClassLoader/ModuleLayer）只放"自己写、自己审"的插件；一切来自 LLM 生成、第三方、用户环境的执行体，一律 stdio 子进程（+可选 bwrap）。这既是 SecurityManager 移除后的官方方向，也是 MCP stdio 的原生形态。

---

## 5. 轻量化发行与启动

### 5.1 GraalVM native image（核实结论：不首选）

- **版本与许可（✅）**：当前 GraalVM 基于 JDK 25（25.0 为 LTS 线、25.3 为 Innovation 线；Community 最新 25.3.4.1；❓ 未见 JDK 26 版本线）。Oracle GraalVM 用 **GFTC**（免费商用/生产，但分发不得收费）；Community Edition 为 GPLv2+CE。来源：[release notes](https://www.graalvm.org/release-notes/)、[FAQ](https://www.graalvm.org/faq/)
- **库兼容现状**：
  - Jackson：官方 [reachability-metadata 仓库](https://github.com/oracle/graalvm-reachability-metadata) 覆盖 `jackson-databind`（测试至 2.22.2）→ 基本开箱可用。✅
  - Netty：覆盖 `netty-common`（至 4.2.17.Final），Netty 自身已内置 native-image 配置（PR #14928）；epoll/kqueue native transport 可用但建议默认 NIO。✅
  - **MCP Java SDK：无任何官方 native-image 支持声明**（repo 内搜 "native image" 0 条、graalvm 仅 1 条无关 PR）；仅靠 Spring AI `McpHints` / Quarkus 框架层适配。❓→按"未声明支持"处理。
  - **A2A Java SDK：官方 jar 无 reachability metadata，有 open bug [#1032](https://github.com/a2aproject/a2a-java/issues/1032) 证实 native-image 构建会破**。
  - sqlite-jdbc：内置了 GraalVM 配置（PR #823），但 native 库 JNI 加载仍需额外处理（issue #1294 有报障）。🔶
- **closed-world 的致命限制（✅ 官方文档原文）**：native image 依赖 closed-world 假设——"**运行时不再加载任何新代码**"，反射/资源/序列化/JNI/动态代理必须构建期声明。来源：[Native Image Compatibility](https://www.graalvm.org/latest/reference-manual/native-image/metadata/Compatibility/)、[Dynamic Features](https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/) → **"Agent 可被 Agent 自管理"所要求的动态装载模块/插件（ClassLoader 加载新 jar）在 native image 下基本不可行**。
- **结论**：主 Agent runtime **不选 native image**；需要毫秒启动的小 CLI（如一次性 smoke 工具、健康探针）可以试，但别把协议栈（MCP/A2A）塞进去。

### 5.2 AppCDS（类数据共享）

- 默认状态：JDK 12 起默认映射 JDK 自带共享归档（JEP 341）；🖥️ 本机验证启动日志 "Opened archive classes.jsa"；`-XX:+AutoCreateSharedArchive` 默认 **false**（🖥️ 本机 flag 实查 + CSR JDK-8272331）。
- 应用级归档：`java -XX:ArchiveClassesAtExit=app.jsa ...` 生成，`-XX:SharedArchiveFile=app.jsa` 使用。🖥️ 本机验证：生成 389KB `app.jsa` 并成功 "Mapped static region" 加载。
- 价值：缩短启动 + 减少 RSS（配合 jlink 是"JVM 常驻"路线的主要优化手段）。✅

### 5.3 jlink + jpackage

- 🖥️ 本机验证：`jlink --add-modules java.base --strip-debug --no-header-files --no-man-pages` 产物 **52MB**（完整 JDK 286MB）；定制运行时可直接运行（本机用 `./jrt/bin/java` 跑通虚拟线程程序）。实际 agent 需加 `java.logging`、`java.sql`、`jdk.httpserver` 等，预计 60-80MB。
- jpackage 做各平台安装包/单目录发行（JDK 21 内含，🖥️ 本机版本 21.0.12 可用）。来源：[jlink 手册](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jlink.html)、[jpackage 指南](https://docs.oracle.com/en/java/javase/25/jpackage/packaging-tool-user-guide.html)
- **注意**：jlink 要求模块化（module-info）才最省心；`jdeps --print-module-deps` 可自动算最小模块集（🖥️ 本机验证 VT 程序只依赖 `java.base`）。多模块 Maven 工程要逐步补 module-info。

### 5.4 两条路线对比与推荐

| 维度 | JVM 常驻 + 子进程工具（推荐） | AOT 单二进制（native image） |
|---|---|---|
| 启动 | 0.3-1s（AppCDS/jlink 可优化） | ms 级 ✅ |
| 常驻内存 | ~40MB 基线 + 堆（🖥️ 实测 38.7MB 空转） | 低（几十 MB 内） |
| 动态加载插件 | 完全支持（ClassLoader/ModuleLayer）✅ | 基本不可行（closed-world） |
| 协议 SDK 兼容 | 零摩擦 ✅ | MCP/A2A 无官方支持、需自维护元数据 🔶 |
| 构建成本 | 低（shade 单 jar 既有实践） | 高（反射配置、JNI、构建时间长） |
| 适用 | 主 Agent 常驻服务 | 被高频 spawn 的小 CLI 工具 |

**推荐混合路线**：主脑 = JVM 常驻（shade 单 jar + 可选 jlink 运行时 + AppCDS）；子 Agent/工具 = 原生工具或少量 JVM 工作进程；需要秒起的 CLI = 才考虑 native image。这与用户既有"shade 单 jar + jdk.httpserver"实践完全兼容。

---

## 6. 持久化 / 事件存储（嵌入式 append-only event log + 状态快照）

### 6.1 候选对比（版本 🖥️ Central 实抓；前三个 🖥️ 本机 JDBC 实测建表/写入/查询通过）

| 方案 | 版本 | 体积 | 原生依赖 | 特点 | 事件存储适配 |
|---|---|---|---|---|---|
| **SQLite (sqlite-jdbc)** | 3.53.4.0 | 11.4MB（内嵌全平台 native，🖥️ 已列 jar 内 .so/.dll 清单） | 是（运行时解压临时目录） | 单写多读、WAL、一个文件、备份=拷贝/VACUUM INTO | **首选** ✅ |
| **H2** | 2.5.250 | 2.6MB | **无（纯 Java）** | MVStore、embedded、BACKUP 命令 | 纯 Java 洁癖/跨平台零摩擦之选 ✅ |
| **DuckDB Java** | 1.5.5.1 | **81.5MB**（含 60.8MB linux .so，🖥️ 已列） | 是 | 列式分析强、Parquet 导入导出、in-process | 事件日志的**分析侧**（只读附挂）✅ |
| RocksDB | 10.10.1 | **83.6MB**（rocksdbjni，🖥️ HEAD 实测） | 是 | LSM、写吞吐高 | 不需要（见下） |
| LMDB (lmdbjava) | 0.9.3 | 小 | 是 | mmap B+tree、读快 | 小众，不必 |

- 🖥️ **实测**：sqlite / h2 / duckdb 三者在同一台机器上 `CREATE TABLE events(seq BIGINT PRIMARY KEY, ts TEXT, type TEXT, payload TEXT)` + 插入 + count 全部通过；SQLite `PRAGMA journal_mode=WAL`、`busy_timeout` 生效。
- 🖥️ **实测（shade fat-jar）**：用 maven-shade-plugin 3.6.0 把 sqlite-jdbc 打进 12MB fat-jar（内含 14 个平台 native .so），`java -cp fat.jar` 运行 JDBC 全流程成功——**native 库会从 fat jar 自动解压到临时目录加载**，无需额外配置（代价是 fat jar 平白多 ~10MB，可按平台裁剪）。
- 🖥️ **跨方言坑（实测踩到）**：SQLite 的 `INTEGER PRIMARY KEY` 自带自动递增（rowid 别名），H2/DuckDB 的 `PRIMARY KEY` 不自动生成——建表语句要写显式 IDENTITY/显式 seq 插入，否则迁移/测试换引擎就炸。
- **建议**：主事件存储 = **SQLite（WAL + busy_timeout）**：单文件可运维性最好（个人 Agent 场景写并发天然低）；需要"零 native"洁癖或 JNI 反感者用 H2；DuckDB 作为**只读分析**副引擎（导 Parquet 喂给它）可选引入，注意 81.5MB 体积；RocksDB/LMDB 在"个人级 append-only 事件流"上收益为负（运维成本 > 收益）。事件表 schema 建议：`events(seq, ts, type, agent, payload_json)` + `snapshots(agent, version, state_json)`，事件即事实、快照即状态。

---

## 7. 配置即数据（Agent 可编辑配置的安全闭环）

### 7.1 格式选型

| 格式 | 库/版本（🖥️ Central） | 评价 |
|---|---|---|
| JSON + JSON Schema | Jackson 2.22.2 + `com.networknt:json-schema-validator:3.0.7` | **首选**：机器可编辑、schema 校验成熟；且 **MCP Java SDK 2.x 自己也依赖 networknt 校验器**（🖥️ pom 实抓）——一个依赖两用 |
| YAML | snakeyaml 2.7 | 人读友好；1.x 有 CVE 史，务必用 2.x + SafeConstructor；机器编辑易踩缩进坑 |
| HOCON | typesafe config 1.4.9 | 人写最友好（`#` 注释、变量替换），但库活跃度低，仅作人类手工配置文件的可选前端 |

- **建议**：内部统一 JSON 数据模型（Agent 读写的唯一形态）；人类手工入口可选 HOCON/YAML 但只在加载时转换一次。

### 7.2 安全闭环（必须全部落地的机制）

1. **Schema 校验**：一切配置写入（无论来自人还是 Agent）先过 JSON Schema 校验，非法即拒。
2. **原子写**：同目录临时文件 → `FileChannel.force`/fsync → `ATOMIC_MOVE` 改名；绝不边写边改原文件。
3. **读回验证 + 自动回滚**：写后读回重新解析校验；失败则回滚到 `.bak`（保留最近 N 份带版本号备份）。
4. **权限 allowlist**：配置空间按"键前缀"分级授权——某个子 Agent 只许改自己的 `agents.<id>.*`，不许碰 `runtime.*`（与 §4 的进程隔离权限模型同源，全部是数据）。
5. **生效路径**：落盘 → 校验 → 广播 reload 事件（事件存储一条 `config.changed` 事件）→ 运行时只读最新版快照；**永不**让外部输入直接改运行时对象。
6. **审计**：每次配置变更都是一条 append-only 事件（§6），谁改的、改了什么、回滚过没有，全可查。

---

## 8. 可观测性

- **OpenTelemetry Java**：稳定主线仍是 **1.65.0**（🖥️ Central，无 2.x 版本存在）。两条接入路径：
  - `javaagent`（`opentelemetry-javaagent.jar`，`-javaagent` 一行接入，自动埋 HTTP/JDBC 等）；
  - **SDK 手动埋点**（`opentelemetry-api` + `opentelemetry-sdk` + OTLP exporter）——轻量项目推荐，只为 Agent 核心路径（LLM 调用、工具执行、事件写入）建 Span/Metric，避免 agent 全量字节码改写。
  - 输出走 OTLP 到本地 collector 或直接 file/console exporter，不强制起服务。
  - **GenAI 语义约定**（`gen_ai.*` span/metric）在独立仓库维护，状态属 🔶 演进中（gen-ai 约定仓库活跃、尚未完全稳定），埋点建议先用通用 `gen_ai` 前缀但不要深绑字段名。来源：[semantic-conventions-genai](https://github.com/open-telemetry/semantic-conventions-genai)
- **CloudEvents Java**：`io.cloudevents:cloudevents-api` 等 **5.0.0**（🖥️ Central，2026-07-16，CNCF 维护活跃）。建议：事件存储对外导出/路由时用 CloudEvents 包装（与 A2A 事件生态互操作）；A2A Java SDK extras 已自带 OpenTelemetry 集成（README 确认）。
- 结论：两者都成熟、都在发版，可放心进"可选模块"。

---

## 9. LLM 调用

### 9.1 现状速查（🖥️ Central，均 2026-09 近一周内有更新）

| SDK | 坐标/版本 | 状态 |
|---|---|---|
| OpenAI 官方 | `com.openai:openai-java:4.58.0`（2026-09-04 更新） | 极活跃，SSE 流式 + tool calls 完整 |
| Anthropic 官方 | `com.anthropic:anthropic-java:2.61.0`（2026-09-04） | 极活跃 |
| Google 官方 | `com.google.genai:google-genai:1.70.0` | Gemini 官方 |
| langchain4j | `dev.langchain4j:langchain4j:1.20.0`（2026-09-07） | 1.x 自 2025-05 GA 后已 20 个小版本——能力大而全但**升级节奏快**（churn 风险）🔶 |

### 9.2 取舍：直连 HttpClient vs 官方 SDK vs langchain4j

- **直连 `HttpClient` + OpenAI 兼容 REST + SSE 手写**（推荐核心方案）：
  - 零新依赖（HttpClient 是 JDK 内置，🖥️ 已验证 HTTP/2 可用；SSE 用 `ofLines` 手写解析，§1.5）；
  - 适配一切 OpenAI 兼容端点（Ollama / vLLM / DeepSeek / 本地模型），协议锁死在"你控制的 ~200 行"里；
  - 与用户既有"手写 MCP stdio + Jackson"风格完全一致；**真正的复杂度在 tool-call 循环、上下文构建、限流重试，而不在 HTTP 层**——这层本就不该交给 SDK。
- **官方 SDK**（openai-java / anthropic-java）：需要官方高级特性（严格 typed structured outputs、beta API 及时跟进）时，放**独立适配模块**、接口化引入；注意它们自带自己的 HTTP/JSON 栈。
- **langchain4j**：需要统一接入大量模型/向量库/RAG 组件、或想要 AI Services 声明式编程时再评估；轻量目标下默认不引入。
- 结论：核心模块 = 自写 OpenAI 兼容薄客户端；官方 SDK 可选模块；langchain4j 观望。

---

## 10. 构建与模块化（与既有实践的工程化要点）

### 10.1 Maven 多模块布局建议

与用户既有实践（Java 21 + Maven 多模块 + shade 单 jar + jdk.httpserver + Jackson）对齐的草图：

```
mosire-parent           # BOM/dependencyManagement/enforcer/checkstyle 聚合
├── mosire-core         # 协议消息模型(sealed+record)、事件模型、JSON-RPC 编解码、传输 SPI —— 零 web 依赖
├── mosire-json         # Jackson 2.x 统一配置（收敛版本）
├── mosire-mcp          # MCP server/client（官方 SDK，stdio 优先）
├── mosire-a2a          # A2A client/server（可选）
├── mosire-agui / acp   # AG-UI / ACP 适配（可选，社区实现标注 🔶）
├── mosire-storage      # SQLite 事件存储（可换 H2）
├── mosire-config       # JSON Schema 校验 + 原子写闭环
├── mosire-sandbox      # stdio 子进程管理 + 权限白名单 + bwrap 适配（可插拔）
├── mosire-llm          # OpenAI 兼容薄客户端（HttpClient+SSE）
├── mosire-gateway      # jdk.httpserver 适配（默认）/ vertx 适配（可选）—— 唯一"可替换"层
└── mosire-main         # 装配 + shade 单 jar 发行
```

### 10.2 工程化要点

- **版本收敛**：parent 的 `dependencyManagement` 统一 Jackson 2.22.x（⚠️ 若用 MCP `mcp` 聚合包会拉 jackson3，建议拆用 `mcp-core + mcp-json-jackson2`）；A2A SDK 会传递引入 **Gson**——接受或 `exclusion`，别让它成为第二个 JSON 惯例。
- **maven-enforcer**：`requireJavaVersion [21,26)`、`dependencyConvergence`、`bannedDependencies`（ban 掉 jsonrpc4j、老 snakeyaml 1.x、log4j1 等）。
- **shade**：单 jar 保持；注意 `ServicesResourceTransformer`（MCP/A2A/OTel 都可能用 ServiceLoader SPI）、排除 `META-INF/*.SF|RSA|DSA` 签名、`minimizeJar` 慎用（反射类会被剪掉）；sqlite-jdbc 的 native 库随 fat jar 自动解压运行（🖥️ 实测通过，见 §6；发布时留意全平台 native 带来的体积，可按平台裁剪 classifier）。
- **可复现构建**：`project.build.outputTimestamp`；**风格**：checkstyle/spotless + `mvn verify` 卡门禁。
- **jlink 预留**：核心模块尽早补 `module-info`（`jdeps --print-module-deps` 辅助，🖥️ 验证可用），为将来 60MB 级定制运行时铺路；JPMS 模块化与 shade 单 jar 二选一发行、不冲突。

---

## 11. 依赖最小化清单（"核心模块最小编译依赖"候选）

> 版本均为 2026-09-08 Maven Central 实抓（🖥️）。原则：核心模块只留"协议必需"，网关/存储/观测按需可选。

| 依赖 | 坐标 | 版本 | 用途 | 必选？ |
|---|---|---|---|---|
| MCP Java SDK（拆用） | `io.modelcontextprotocol.sdk:mcp-core` + `:mcp-json-jackson2` | 2.0.1 | MCP client/server（stdio + streamable HTTP client）、内置 JSON-RPC 2.0 层、McpSchema 消息模型 | ✅ 核心 |
| Jackson | `com.fasterxml.jackson.core:jackson-databind`（+core/annotations） | 2.22.2 | 一切 JSON 序列化；与 mcp-json-jackson2、ACP SDK、networknt 共用一套 | ✅ 核心 |
| 日志门面 | `org.slf4j:slf4j-api`（+运行时 `slf4j-simple` 或 logback） | 2.0.x | 所有 SDK 都依赖的门面，必须显式给实现 | ✅ 核心 |
| A2A Java SDK | `org.a2aproject.sdk:a2a-java-sdk-client-transport-jsonrpc`（+`-common`，server 按需） | 1.3.1.Final | Agent↔Agent 协议（JSON-RPC transport 最轻；注意传递依赖 Gson/gRPC） | 按需（A2A 模块） |
| 配置校验 | `com.networknt:json-schema-validator` | 3.0.7 | Agent 可编辑配置的 schema 校验闭环（MCP SDK 同款校验器） | ✅ 核心（config 模块） |
| 事件存储 | `org.xerial:sqlite-jdbc`（或 `com.h2database:h2`） | 3.53.4.0 / 2.5.250 | append-only 事件日志 + 快照 | ✅ 核心（storage 模块） |
| 网关（可选升级） | `io.vertx:vertx-core` | 5.1.7 | HTTP/1+2、WebSocket、SSE 生产网关（默认可先用 jdk.httpserver 零依赖） | 可选 |
| ACP | `com.agentclientprotocol:acp-core` | 0.17.0 | Editor↔Agent 协议（stdio/WebSocket） | 可选 |
| AG-UI | `com.ag-ui.community:java-core` | 0.1.0 | Agent↔UI 事件协议（**社区实现**，官方管道发布、文档滞后） | 可选 🔶 |
| 可观测 | `io.opentelemetry:opentelemetry-api` + `-sdk`、`io.cloudevents:cloudevents-*` | 1.65.0 / 5.0.0 | 埋点 + 事件互通 | 可选 |
| 显式**不**引入 | jsonrpc4j（停滞）、Spring Boot/Spring AI（重）、langchain4j（默认不引）、Guice 等 DI 框架 | — | — | — |

**最小闭环版本**（单机 stdio 优先）：`mcp-core + mcp-json-jackson2 + jackson-databind + slf4j + networknt-json-schema-validator + sqlite-jdbc` —— 六个坐标即可让"主 Agent + 多个 stdio 子 Agent + 事件存储 + 自管理配置"跑起来，编译依赖树极浅，与用户"轻！量！化！"诉求一致。

---

## 附录：验证环境与证据说明

- **本机验证环境**：OpenJDK 21.0.12（2026-07-21 构建，Ubuntu 24.04）、Maven 3.8.7、curl 直连 repo1.maven.org；docker 已装（cgroup2fs），bwrap/podman/native-image 未装。
- **本机实测清单（🖥️）**：虚拟线程开箱可用；StructuredTaskScope/ScopedValue 在 JDK 21 必须 `--enable-preview`；jdk.httpserver SSE 渐进输出 + 100 并发长连接 1.6s + `--http2` 回退 HTTP/1.1；HttpClient 对三主机 HTTP/2 握手；空闲 JVM RSS 38.7MB；AppCDS 默认归档映射 + `ArchiveClassesAtExit` 生成/加载应用归档；`AutoCreateSharedArchive` 默认 false；jlink java.base 运行时 52MB（全 JDK 286MB）且可运行；sqlite-jdbc 3.53.4.0（jar 内嵌多平台 native）/H2 2.5.250（纯 Java）/DuckDB 1.5.5.1（81.5MB）JDBC 建表写入查询全通过 + SQLite WAL 生效 + shade fat-jar 内嵌 sqlite-jdbc 运行正常；MCP SDK 2.0.1 jar/pom 拆包（JSON 可插拔、stdio/HttpServlet 传输类）；A2A SDK 1.3.1.Final 模块与依赖。
- **外部核查清单**：openjdk.org 官方 JEP 页（25/26/486/491/505/506/525/517）、Oracle Java 支持路线图、Oracle GraalVM 发布页与 GFTC、graalvm-reachability-metadata 仓库、modelcontextprotocol/java-sdk、a2aproject/a2a-java、agentclientprotocol/java-sdk、ag-ui-protocol/ag-ui、Vert.x/Netty/Helidon/Spring 官方博客、chicory.dev。
- **❓ 无法核实项汇总**：Helidon 5 是否已发布；GraalVM 是否有 JDK 26 版本线；AG-UI 官方文档站何时补 Java 页面；ACP 的 schema 版本号（v1.21.0）与 spec 版本号（v0.13.6）并存的口径；各第三方 stars/用户数（见子调查 A）。
- **风险提示**：本报告所有版本号在快速演进生态中会过期（MCP 2.x、AG-UI 0.1、ACP 0.17 均为 2026 下半年状态）；建议把"依赖版本"维护成独立 BOM 文件，随生态季度复核。
