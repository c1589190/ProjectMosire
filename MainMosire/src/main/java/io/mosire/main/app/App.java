package io.mosire.main.app;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.mcp.AgentToMcpServer;
import io.mosire.agentlib.mcp.McpServerLinkConfig;
import io.mosire.agentlib.mcp.McpSourceBridge;
import io.mosire.agentlib.mcp.McpToolSource;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.plugin.BuiltinToolSource;
import io.mosire.agentlib.proc.SubprocessManager;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.AgentSpec;
import io.mosire.brain.runtime.TurnResult;
import io.mosire.brain.subagent.AgentCommand;
import io.mosire.brain.subagent.AgentTemplateStore;
import io.mosire.brain.subagent.SubProcessExecutor;
import io.mosire.brain.subagent.SubagentInstance;
import io.mosire.brain.subagent.SubagentManager;
import io.mosire.brain.subagent.SubagentOrchestrationTools;
import io.mosire.main.Version;
import io.mosire.main.gateway.AdminHttpServer;
import io.mosire.main.gateway.StatusSnapshot;
import io.mosire.main.gateway.a2a.A2aAgentRunner;
import io.mosire.main.gateway.a2a.A2aHttpServer;
import io.mosire.main.gateway.a2a.A2aJsonRpcHandler;
import io.mosire.main.gateway.a2a.A2aTaskService;
import io.mosire.main.gateway.a2a.EventStoreA2aTaskStore;
import io.mosire.main.gateway.agui.AgUiHttpServer;
import io.mosire.main.gateway.agui.AgUiSessionRegistry;
import io.mosire.main.gateway.debug.DebugChatHttpServer;
import io.mosire.main.gateway.debug.DebugChatService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程组装（计划 §5.1 启动时序的 M1+W2a 子集）：事件存储 → 事件总线 → 工具注册表 → MCP 链接（外部工具接入）→ 主 Agent registry 经 stdio MCP
 * server 暴露 → 装配主 AgentRuntime → AdminREST（/health 挂起）→ 等待关停。
 *
 * <p>M1 的 LLM 固定为 {@link FakeLlmClient}（离线骨架，真实供应商客户端在 M3 接入 {@code ModelRoute}）；{@code --demo}
 * 在启动时先跑一条脚本问答（验收：一回合、事件入库）。
 *
 * <p>W2a（本类新增）：{@code --mcp-link} 配置驱动外部 MCP stdio server 工具接入（W2 步骤 1）；主 Agent 的 ToolRegistry 经
 * {@link AgentToMcpServer} 以 stdio 暴露（W2 步骤 2，默认启用）——注意主进程 stdout 即 MCP 流， 进程日志必须只走 stderr。
 *
 * <p>W2b（本类新增）：A2A 网关接入——{@code A2aTaskService} + {@link A2aAgentRunner}（任务消息 → 主 Agent {@code
 * chat}）+ {@link A2aHttpServer}（Agent Card + JSON-RPC + SSE，地址/端口来自 {@code
 * --a2a-address/--a2a-port}， 默认 127.0.0.1 + 空闲端口）。任务执行串行化（单线程执行器——Rul B：不引入 A2A 并发任务； {@code
 * AgentPipeline.history} 实例级共享且非线程安全，串行化即不变量，见 Brain Javadoc）。 关停顺序：A2A 任务服务先行合成
 * FAILED（在订阅的客户端收终态）→ A2A 网关关闭 → 既有关停链（原因见 {@link #close()} 说明）。
 *
 * <p>W3b（本类新增）：子 Agent 编排装配——{@code BootConfig.templatesDir} 非空时装载模板库、注入 {@link
 * SubagentManager}（{@code parentDepth=0}、父权限=主 Agent 自身权限集），编排工具（spawn/kill/list）注册进主 Agent
 * 工具面；子进程命令按"当前 java + 本进程 classpath + {@code Main agent}"注入（Rul C），链接形态 （{@code --parent-link}）+
 * 内存 cap {@code -Xmx128m}，子 Agent 数据目录隔离在 {@code <dataDir>/subagents/<instanceId>}。{@code
 * templatesDir} 为空 = 本进程不启用子 Agent 编排。
 *
 * <p>S1-A2（本类新增）：{@code start} 的 4 参重载带 {@code subagentConfigDir}——非 null 时经 {@code
 * wireSubagents}/{@code subagentCommand} 透传到子进程 argv 的 {@code --config-dir <路径>}（子体据此走真模型）；2/3
 * 参重载委托 {@code null}（既有测试入口零改动）。<b>只传路径</b>：密钥值不进 argv/env/系统属性/stdio（D23）。
 *
 * <p>W4（本类新增）：AG-UI 网关——{@code AgUiSessionRegistry}（POST /sessions 建会话 + 运行桥）+ {@link
 * AgUiHttpServer}（GET /sessions/{id}/events SSE）。主 Agent 回合执行器与 A2A 共享<b>同一个单线程执行器</b> （Rul B 串行化 =
 * AgentPipeline.history 非线程安全的不变量，也是 AG-UI 会话 seq 区间隔离的前提——见 AgUiSessionRegistry 说明）。关停顺序：A2A
 * 任务服务（合成 FAILED + 关停共享执行器）→ A2A 网关 → AG-UI 网关 （stop(1) 给在途流排空）→ AG-UI 注册表（封创建口）→ 既有关停链。
 *
 * <p>P3-3（本类新增）：<b>会话历史落盘</b>——非 {@code --demo} 时多开一个 {@link SqliteConversationStore}（与事件库同一文件，计划
 * §六 一份 DDL），主 Agent 的多轮对话因此跨进程存活，初始会话 id 恒为 {@link #MAIN_CONVERSATION_ID}（稳态 id 是"重启续聊"的前提）；
 * {@code --demo} 恒不落库（零行为变化）。重置入口不在本类：{@code POST /api/chat/session} → {@link
 * io.mosire.main.gateway.debug.DebugChatService#resetSession()}（切换动作排在共享 chat 执行器上，R11）。
 */
public final class App implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(App.class);

  /** 子 Agent 链接的父侧 MCP server 自报名（子体按"parent"源名识别——仅日志/审计语义）。 */
  private static final String PARENT_SERVER_NAME = "mosire-parent";

  /**
   * 主 Agent 的初始会话 id（P3-3）：恒为 {@code "main"}（= 主 Agent 的 {@code AgentConfig} id，同源）。
   *
   * <p><b>初始 id 必须稳定</b>——"重启进程后历史仍在"靠的就是每段进程用同一个 id 去库里寻址；若每次启动取随机 id，每次都是全新会话，
   * 落盘等于白落。代价是：重置后重启会回到 {@code "main"} 这条初始会话（而不是上次重置出的新会话）——"当前会话指针"机制不在 P3-3 范围。
   */
  public static final String MAIN_CONVERSATION_ID = "main";

  public static final String DEFAULT_SYSTEM_PROMPT =
      "你是 Mosire 主 Agent——一个模块化、可自管理、受权限约束的 Agent。" + "保持诚实：无工具可用时直接说明，不虚构执行过程。";
  public static final String DEMO_USER_MESSAGE = "你好，请确认你的骨架已就绪。";

  /** 非 demo 模式下 FakeLlmClient 的默认骨架回复（A2A 等调用面无脚本时也能完成一回合；真实模型 M3 接入）。 */
  public static final String DEFAULT_LLM_REPLY = "我是 Mosire 主 Agent（M1 骨架 LLM，离线占位回复）。消息已收到。";

  private final EventStore events;

  /**
   * 会话历史存储（P3-3）：{@code null} = 未接线（demo 分叉——不落库，行为与落盘接入前逐字节一致）。
   *
   * <p>与 {@link #events} 同口径：归 App 持有、App 关停；两者是<b>同一个 db 文件</b>上的两个连接（各自自建，计划 §六 一份 DDL）。
   * 字段类型是具体实现而非 {@link io.mosire.agentlib.store.ConversationStore}：装配层持有的是"可关停的那个打开结果"（接口本身没有
   * close——它只描述存取语义），同 {@link SqliteEventStore} 之于 {@code EventStore}。
   */
  private final SqliteConversationStore conversations;

  private final EventBus bus;
  private final AgentRuntime runtime;
  private final AdminHttpServer http;
  private final AgentToMcpServer mcpServer;
  private final List<McpToolSource> mcpSources;
  private final List<McpSourceBridge> mcpBridges;
  private final A2aHttpServer a2aServer;
  private final A2aTaskService a2aTaskService;
  private final AgUiHttpServer aguiServer;
  private final AgUiSessionRegistry aguiRegistry;
  private final DebugChatService debugChatService;
  private final DebugChatHttpServer debugChatServer;

  /** W3b 子 Agent 编排（templatesDir 未配置时为 null——进程内无该能力）。 */
  private final SubagentManager subagentManager;

  /** 子进程生命周期兜底（同一条件装配：与 subagentManager 同生共死；红线 5：归装配层持有）。 */
  private final SubprocessManager subagentProcesses;

  private final CountDownLatch terminated = new CountDownLatch(1);
  private volatile boolean closed;

  /** 启动：拉起存储/运行时/MCP（链接+暴露）/网关（AdminREST/A2A），注册 SIGTERM/SIGINT 优雅关停钩子（计划 §5.1）。 */
  public static App start(BootConfig config) {
    return start(config, null);
  }

  /**
   * 启动（LLM 覆盖入口——测试注入脚本化 {@link FakeLlmClient} 用；{@code llmOverride == null} 时按配置脚本化）。
   *
   * <p>覆盖仅影响主 AgentRuntime 装配，不改变生命周期行为。子 Agent 配置根不注入（= null，R-A2-4）。
   */
  public static App start(BootConfig config, LlmClient llmOverride) {
    return start(config, llmOverride, List.of(), null);
  }

  /**
   * 启动（3 参入口——测试注入额外工具用）：子 Agent 配置根不注入（{@code subagentConfigDir = null}），行为与 S1-A2 之前逐字一致
   * （R-A2-4）。
   */
  public static App start(BootConfig config, LlmClient llmOverride, List<AgentTool> extraTools) {
    return start(config, llmOverride, extraTools, null);
  }

  /**
   * 启动（额外工具注入入口——测试注入 echo 等演示工具用；{@code extraTools} 在主 Agent 工具面与 MCP 暴露快照之前注册）。
   *
   * <p>THROWS_METHOD_THROWS_RUNTIMEEXCEPTION 抑制：装配失败=快速失败契约（端口占用/坏配置等一律 RuntimeException 原样上抛， 由
   * CLI（Main）以非零退出与 stderr 呈现——既有 M0/M1 启动语义）；中间已回收启动期网关/进程资源与已打开的存储连接（事件/会话库）后重抛， 无更窄的异常类型。
   *
   * @param subagentConfigDir 子 Agent 的配置根（三期 S1-A2，R-A2-4）：非 null 时经 {@link #wireSubagents} →
   *     {@link #subagentCommand} 落到子进程 argv 的 {@code --config-dir
   *     <路径>}（子体据此走真模型）。<b>只传路径</b>——密钥值仍由子进程 自己读该目录下的 {@code config.json}（D23）；null =
   *     不传（离线/测试形态，子体走模板脚本假 LLM，逐字保持原行为）
   */
  @SuppressFBWarnings("THROWS_METHOD_THROWS_RUNTIMEEXCEPTION")
  public static App start(
      BootConfig config,
      LlmClient llmOverride,
      List<AgentTool> extraTools,
      Path subagentConfigDir) {
    try {
      Files.createDirectories(config.dataDir());
    } catch (IOException e) {
      throw new IllegalStateException("无法创建数据目录: " + config.dataDir(), e);
    }
    EventStore events = SqliteEventStore.open(config.dataDir().resolve("events.db"));
    // P3-3 落盘：主 Agent 的会话历史落到 events.db 同一文件（SqliteConversationStore 的既定约定——计划 §六 一份 DDL）。
    // demo 分叉恒不落库（--demo 行为零变化）：store 为 null 即走 AgentRuntime 的既有 6 参构造（不落库路径逐字节不变）
    SqliteConversationStore conversations =
        config.demo() ? null : SqliteConversationStore.open(config.dataDir().resolve("events.db"));
    EventBus bus = new EventBus();
    ToolRegistry tools = new ToolRegistry();
    McpLinks links;
    try {
      // 主 try 之前会抛非致命异常的两处调用（工具注册同名/空元素、链接装配）都在本兜底内：抛错时控制流直接逃出
      // start()，主 catch 够不着——就地回收前面已打开的两条连接（回收口径同主 catch，且用 quiet 关闭保证原异常原样上抛
      // ——失败原因不被"关闭也失败"顶掉）。
      tools.registerAll(extraTools == null ? List.of() : extraTools);
      // W2 步骤 1：MCP 链接装配（在暴露快照与主 Agent 使用之前连入 registry——计划 §5.1 "MCP links → 创建主 AgentRuntime"）
      links = wireMcpLinks(config.mcpLinks(), tools);
    } catch (RuntimeException e) {
      closeQuietly(
          "会话存储",
          () -> {
            if (conversations != null) {
              conversations.close();
            }
          });
      closeQuietly("事件存储", events::close);
      throw e;
    }
    AgentToMcpServer mcpServer = null;
    A2aHttpServer a2aServer = null;
    A2aTaskService a2aTaskService = null;
    AgUiHttpServer aguiServer = null;
    AgUiSessionRegistry aguiRegistry = null;
    DebugChatService debugChatService = null;
    DebugChatHttpServer debugChatServer = null;
    ExecutorService chatExecutor = null;
    SubagentManager subagentManager = null;
    SubprocessManager subagentProcesses = null;
    try {
      AgentPermissionSet permissionSet = AgentPermissionSet.system();
      AgentConfig agentConfig =
          AgentConfig.builder("main")
              .systemPrompt(DEFAULT_SYSTEM_PROMPT)
              .description("主 Agent（M1 骨架）")
              .build();
      // D17：主 Agent 以 AgentSpec 装配（组合既有 AgentConfig，二期字段暂取默认值——P2-2 Task 6 仅承载不接线）
      AgentSpec agentSpec = AgentSpec.builder(agentConfig).build();
      // W3b：子 Agent 编排装配（模板目录非空时才启用——计划 §4.4 + W3；编排工具先于暴露快照注册进工具面）
      if (config.templatesDir() != null) {
        SubagentRig rig =
            wireSubagents(
                config, tools, events, bus, agentConfig, permissionSet, subagentConfigDir);
        subagentManager = rig.manager();
        subagentProcesses = rig.processes();
      }
      // W2 步骤 2：主 Agent 工具面经 stdio MCP server 暴露（R7 警示下的当前默认：GUEST 身份，收窄属 M3）
      mcpServer =
          config.mcpExpose() ? AgentToMcpServer.start(tools, "mosire-main", Version.VERSION) : null;
      // P3-3：落库分叉只此一处——conversations == null（demo）走不落库的既有构造，非 demo 走 (store, conversationId)
      LlmClient llm = scriptedLlm(config, llmOverride);
      AgentRuntime runtime =
          conversations == null
              ? new AgentRuntime(agentSpec, llm, tools, events, bus, permissionSet)
              : new AgentRuntime(
                  agentSpec,
                  llm,
                  tools,
                  events,
                  bus,
                  permissionSet,
                  conversations,
                  MAIN_CONVERSATION_ID);
      // W5：AdminREST 数据面——agents=编排器快照、tools=registry 名单、events=Store 只读查询（经 App::queryEvents
      // 相同的入参形态）
      SubagentManager subagentSource = subagentManager;
      AdminHttpServer http =
          AdminHttpServer.start(
              config.port(),
              () ->
                  StatusSnapshot.healthy(
                      Version.ARTIFACT_ID, Version.VERSION, agentConfig.id(), events.count()),
              () -> subagentSource == null ? List.of() : subagentSource.list(),
              () -> tools.list().stream().map(AgentTool::name).toList(),
              events::query);

      // W2 步骤 3：A2A 接入——状态存储=EventStore（Task 快照）+ runner 桥 → 主 Agent chat；
      // 任务执行串行化（Rul B：不引入并发任务——单线程执行器 + Pipeline history 实例级共享（非线程安全），串行即不变量）；
      // W4：主 Agent 回合执行器与 A2A 共享同一实例（AG-UI 会话隔离的前提——见 AgUiSessionRegistry）
      chatExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat-").factory());
      a2aTaskService =
          new A2aTaskService(
              new EventStoreA2aTaskStore(events), new A2aAgentRunner(runtime), chatExecutor);
      a2aServer =
          A2aHttpServer.start(
              new InetSocketAddress(config.a2aHost(), config.a2aPort()),
              port -> a2aCard(a2aBaseUrl(config.a2aHost(), port)),
              // handler 卡仅用于版本协商（URL 无关——真实绑定后的卡片经 cardFactory 构建）
              new A2aJsonRpcHandler(
                  a2aCard(a2aBaseUrl(config.a2aHost(), config.a2aPort())), a2aTaskService));

      // W4：AG-UI 接入——会话注册表（运行桥）+ SSE 网关（POST /sessions + GET /sessions/{id}/events）
      aguiRegistry = new AgUiSessionRegistry(runtime, events, bus, chatExecutor);
      aguiServer =
          AgUiHttpServer.start(
              new InetSocketAddress(config.aguiHost(), config.aguiPort()),
              aguiRegistry,
              events,
              bus);

      // P2-1 Task 3：调试对话——与 A2A/AG-UI 共用同一 chat 执行器（串行化不变量），HTTP 面恒绑 127.0.0.1
      debugChatService = new DebugChatService(runtime, chatExecutor);
      debugChatServer = DebugChatHttpServer.start(config.debugPort(), debugChatService);

      App app =
          new App(
              events,
              conversations,
              bus,
              runtime,
              http,
              mcpServer,
              links.sources(),
              links.bridges(),
              a2aServer,
              a2aTaskService,
              aguiServer,
              aguiRegistry,
              debugChatService,
              debugChatServer,
              subagentManager,
              subagentProcesses);

      if (config.demo()) {
        String message = config.demoMessage().isEmpty() ? DEMO_USER_MESSAGE : config.demoMessage();
        runDemo(app, runtime, message);
      }
      Runtime.getRuntime().addShutdownHook(new Thread(app::close, "mosire-shutdown"));
      return app;
    } catch (RuntimeException e) {
      // 装配中途失败（如端口占用）：回收已起的网关/MCP 子进程/子 Agent 编排，避免启动失败后残留僵尸进程
      if (aguiServer != null) {
        aguiServer.close();
      }
      if (aguiRegistry != null) {
        aguiRegistry.close();
      }
      if (a2aServer != null) {
        a2aServer.close();
      }
      if (a2aTaskService != null) {
        a2aTaskService.close();
      }
      if (debugChatServer != null) {
        debugChatServer.close();
      }
      if (debugChatService != null) {
        debugChatService.close();
      }
      if (conversations != null) {
        conversations.close();
      }
      if (chatExecutor != null) {
        chatExecutor.shutdownNow();
      }
      if (mcpServer != null) {
        mcpServer.close();
      }
      if (subagentManager != null) {
        subagentManager.close();
      }
      if (subagentProcesses != null) {
        subagentProcesses.close();
      }
      closeLinks(links);
      // 事件存储最后关（与 App.close 的口径一致：其余组件仍可能向它写事件时不得先关它）
      events.close();
      throw e;
    }
  }

  /**
   * W3b 子 Agent 编排装配：模板库装载（fail-fast——坏模板/缺目录在启动期暴露）→ 子进程管理器 + 链接形态 {@link AgentCommand}（当前 java +
   * 本进程 classpath + {@code Main agent}，JVM 内存 cap -Xmx128m，目录注入，{@code --parent-link}）→ {@link
   * SubagentManager}（父级 = 主 Agent：权限/深度 0 对照）→ 三个内置编排工具以 builtin 供给源注册进工具面（二期 L2：sourceId 归属）。
   */
  private static SubagentRig wireSubagents(
      BootConfig config,
      ToolRegistry tools,
      EventStore events,
      EventBus bus,
      AgentConfig parentConfig,
      AgentPermissionSet parentPermissions,
      Path subagentConfigDir) {
    AgentTemplateStore templateStore = new AgentTemplateStore(config.templatesDir());
    templateStore.load();
    SubprocessManager processes = new SubprocessManager();
    SubProcessExecutor executor =
        new SubProcessExecutor(
            processes,
            subagentCommand(config, subagentConfigDir),
            tools,
            PARENT_SERVER_NAME,
            Version.VERSION);
    SubagentManager manager =
        new SubagentManager(
            templateStore, executor, events, bus, parentConfig, parentPermissions, 0);
    BuiltinToolSource builtin =
        new BuiltinToolSource("builtin", SubagentOrchestrationTools.of(manager));
    for (AgentTool tool : builtin.listTools()) {
      tools.register(builtin.id(), tool);
    }
    return new SubagentRig(manager, processes);
  }

  /**
   * 子 Agent 启动命令（Rul C：装配层注入，Brain 不做文件系统假设——java 与 classpath 都是"当前进程自带信息"）。
   *
   * <p>S1-A2：{@code subagentConfigDir} 非 null 时追加 {@code --config-dir <该路径>}（子体据此走真模型）。<b>只把路径交给
   * Brain</b>：本方法可见的全部信息里没有任何密钥值（密钥由子进程自己读 {@code <configDir>/config.json}，D23）。
   */
  private static AgentCommand subagentCommand(BootConfig config, Path subagentConfigDir) {
    String java = ProcessHandle.current().info().command().filter(c -> !c.isBlank()).orElse("java");
    return new AgentCommand(
        java,
        List.of("-Xmx128m"),
        List.of("-cp", System.getProperty("java.class.path", ""), "io.mosire.main.Main", "agent"),
        "--id",
        config.templatesDir().toString(),
        config.dataDir().resolve("subagents").toString(),
        true,
        subagentConfigDir == null ? null : subagentConfigDir.toString());
  }

  /**
   * MCP 链接装配：每链接 {@code connect} → {@code bind}；任一链接失败则先回收已建立的链接再抛出（声明的能力必须
   * 在启动时齐备，不做逐链接静默跳过——重连策略不在本期范围）。
   */
  private static McpLinks wireMcpLinks(Path linksFile, ToolRegistry registry) {
    if (linksFile == null) {
      return McpLinks.empty();
    }
    List<McpServerLinkConfig> configs = McpLinkLoader.load(linksFile);
    List<McpToolSource> sources = new ArrayList<>();
    List<McpSourceBridge> bridges = new ArrayList<>();
    try {
      for (McpServerLinkConfig link : configs) {
        McpToolSource source = new McpToolSource(link);
        source.connect();
        sources.add(source);
        bridges.add(McpSourceBridge.bind(source, registry));
      }
    } catch (RuntimeException e) {
      closeLinks(new McpLinks(List.copyOf(sources), List.copyOf(bridges)));
      throw new IllegalStateException("MCP 链接装配失败（" + linksFile + "）: " + e.getMessage(), e);
    }
    return new McpLinks(List.copyOf(sources), List.copyOf(bridges));
  }

  private static void runDemo(App app, AgentRuntime runtime, String message) {
    TurnResult result = runtime.chat(message);
    LOG.info(
        "demo 回合完成: stop={} turns={} toolCalls={} final=\"{}\"（事件共 {} 条）",
        result.stopReason(),
        result.turns(),
        result.toolCalls(),
        result.finalText().orElse(""),
        app.events.count());
  }

  /** A2A Agent Card（卡片 URL = 实际监听地址；handler 侧版本协商只用 supportedInterfaces）。 */
  private static AgentCard a2aCard(String url) {
    return AgentCard.builder()
        .name("mosire-main")
        .description("ProjectMosire 主 Agent（A2A 网关）")
        .version(Version.VERSION)
        .capabilities(AgentCapabilities.builder().streaming(true).build())
        .defaultInputModes(List.of("application/json"))
        .defaultOutputModes(List.of("application/json"))
        .skills(List.of())
        .url(url)
        .supportedInterfaces(List.of(new AgentInterface("JSONRPC", url)))
        .build();
  }

  private static String a2aBaseUrl(String host, int port) {
    return "http://" + host + ":" + port;
  }

  private static LlmClient scriptedLlm(BootConfig config, LlmClient llmOverride) {
    if (llmOverride != null) {
      return llmOverride;
    }
    // M1：只支持脚本 LLM；非 demo 模式给默认骨架回复（供 A2A 等调用面直接应答，真实供应商在 M3 按 ModelRoute 接入）
    if (!config.demo()) {
      return FakeLlmClient.with(LlmResponse.text(DEFAULT_LLM_REPLY));
    }
    return FakeLlmClient.with(
        LlmResponse.text("你好！Mosire 主 Agent 骨架已就绪，事件存储与权限门禁在线。" + "这是我首次运行的自检回复。"));
  }

  private App(
      EventStore events,
      SqliteConversationStore conversations,
      EventBus bus,
      AgentRuntime runtime,
      AdminHttpServer http,
      AgentToMcpServer mcpServer,
      List<McpToolSource> mcpSources,
      List<McpSourceBridge> mcpBridges,
      A2aHttpServer a2aServer,
      A2aTaskService a2aTaskService,
      AgUiHttpServer aguiServer,
      AgUiSessionRegistry aguiRegistry,
      DebugChatService debugChatService,
      DebugChatHttpServer debugChatServer,
      SubagentManager subagentManager,
      SubprocessManager subagentProcesses) {
    this.events = events;
    this.conversations = conversations;
    this.bus = bus;
    this.runtime = runtime;
    this.http = http;
    this.mcpServer = mcpServer;
    this.mcpSources = List.copyOf(mcpSources);
    this.mcpBridges = List.copyOf(mcpBridges);
    this.a2aServer = a2aServer;
    this.a2aTaskService = a2aTaskService;
    this.aguiServer = aguiServer;
    this.aguiRegistry = aguiRegistry;
    this.debugChatService = debugChatService;
    this.debugChatServer = debugChatServer;
    this.subagentManager = subagentManager;
    this.subagentProcesses = subagentProcesses;
  }

  /** 阻塞直到 close()（由 shutdown 钩子触发）。 */
  public void awaitTermination() throws InterruptedException {
    terminated.await();
  }

  /** 事件库只读查询口（未来 AdminREST {@code /api/events} 的数据来源）；不暴露 Store 本体（SpotBugs EI_EXPOSE_REP）。 */
  public List<Event> queryEvents(EventQuery query) {
    return events.query(query);
  }

  /** 事件总数（StatusSnapshot 数据来源之一）。 */
  public long eventCount() {
    return events.count();
  }

  public AgentRuntime runtime() {
    return runtime;
  }

  public int boundPort() {
    return http.boundPort();
  }

  /** A2A 网关实际监听端口（0 = 自动分配场景下由调用方取实际值；未装配时为空）。 */
  public int a2aPort() {
    return a2aServer.port();
  }

  /** AG-UI 网关实际监听端口（0 = 自动分配场景下由调用方取实际值）。 */
  public int aguiPort() {
    return aguiServer.port();
  }

  /** 调试对话网关实际监听端口（恒绑 127.0.0.1；0 = 自动分配场景下由调用方取实际值）。 */
  public int debugPort() {
    return debugChatServer.port();
  }

  /** 子 Agent 实例快照（按实例 id 升序；未装配子 Agent 编排时为空表）。 */
  public List<SubagentInstance> subagents() {
    return subagentManager == null ? List.of() : subagentManager.list();
  }

  /**
   * 优雅关停（计划 §5.1）：A2A 任务服务先行（合成 FAILED 让在订阅的客户端收到终态——必须赶在 HTTP 服务断连之前）→ A2A 网关关闭 （停止接入）→ 网关 drain →
   * MCP 暴露闭（先摘 registry 订阅，避免桥下架工具的变更流进已闭 server）→ 各链接 bridge.close（整组下架工具）→ source.close（回收子进程）→ 子
   * Agent 编排（逐个终止 + launcher 关闭）→ 子进程管理器兜底收割 → 运行时 → 会话存储（P3-3 开的那个）→ 事件存储 checkpoint。
   *
   * <p>（顺序说明）任务要求表述为"A2A server 先行关闭"，但 {@code HttpServer.stop(0)} 会即时掐断在途 SSE 连接——若先关 server，周迟合的
   * FAILED 时序事件早已不可达订阅者（R8 验收不成立）；故先合成终态、后断连接；“A2A 先行于既有关停链” 的本意（网关先于
   * runtime/事件库关闭）保持不变。服务关闭窗口内到达的新任务按 R8 走 RejectedExecutionException → 合成 FAILED。
   *
   * <p>（W4 关停语义——与 R8 的差异）AG-UI 没有可合成"诚实终态"的持久状态机（A2A 的 FAILED 合成基于任务状态机；AG-UI 会话终态来自运行桥的
   * TurnResult），故不仿照 R8 强制合成：共享执行器关停（A2A 任务服务先行）后，在途 AG-UI 回合被中断 → 运行桥 catch 后置 RUN_ERROR 终态（{@code
   * RUNTIME_EXCEPTION}），随后 AG-UI 网关 {@code stop(1)} 排空在途流—— 排空窗口内仍可收到终态；窗口过后未收到的客户端按"重放 +
   * 会话记录终态"兜底（GET 永远从 EventStore 回读，终态是会话 记录而不是幂等状态机，因此重连即收齐——这是相对 R8 的实现差异，见 Task-7 报告）。
   *
   * <p>每项关闭单独 try/catch：单项失败只记录日志（警告），close 链必须走完（{@code terminated.countDown()} 保证释放 等待方）。
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    LOG.info(
        "正在停止：A2A 任务/网关 → AG-UI 网关/注册表 → 调试对话 → 网关 drain → MCP 暴露/链接 → 子 Agent 编排 → 运行时 → 会话存储/事件存储 checkpoint");
    closeQuietly("A2A 任务服务", () -> a2aTaskService.close());
    closeQuietly("A2A 网关", () -> a2aServer.close());
    closeQuietly("AG-UI 网关", () -> aguiServer.close());
    closeQuietly("AG-UI 会话注册表", () -> aguiRegistry.close());
    closeQuietly("调试对话服务", debugChatServer::close);
    closeQuietly("调试对话", debugChatService::close);
    closeQuietly("AdminREST 网关", () -> http.close());
    closeQuietly(
        "MCP 暴露",
        () -> {
          if (mcpServer != null) {
            mcpServer.close();
          }
        });
    closeQuietly("MCP 链接", () -> closeLinks(new McpLinks(mcpSources, mcpBridges)));
    closeQuietly(
        "子 Agent 编排",
        () -> {
          if (subagentManager != null) {
            subagentManager.close();
          }
        });
    closeQuietly(
        "子进程管理器",
        () -> {
          if (subagentProcesses != null) {
            subagentProcesses.close();
          }
        });
    closeQuietly("Agent 运行时", () -> runtime.close());
    closeQuietly(
        "会话存储",
        () -> {
          if (conversations != null) {
            conversations.close();
          }
        });
    closeQuietly("事件存储", () -> events.close());
    closeQuietly("事件总线", () -> bus.close());
    terminated.countDown();
  }

  /** 单项资源关闭：异常只记日志（继续执行后续关闭——close 链不因单项失败中断）。 */
  private static void closeQuietly(String what, Runnable closer) {
    try {
      closer.run();
    } catch (RuntimeException e) {
      LOG.warn("关闭 {} 失败（继续执行后续关闭）", what, e);
    }
  }

  /** 链接回收（共享给关停与装配失败路径）：先 bridge（整组下架工具），再 source（回收子进程）；逐项异常不中断整组回收。 */
  private static void closeLinks(McpLinks links) {
    for (McpSourceBridge bridge : links.bridges()) {
      try {
        bridge.close();
      } catch (RuntimeException e) {
        LOG.warn("关闭 MCP 桥失败（继续回收其余桥/源）", e);
      }
    }
    for (McpToolSource source : links.sources()) {
      try {
        source.close();
      } catch (RuntimeException e) {
        LOG.warn("关闭 MCP 源失败（继续回收其余源）", e);
      }
    }
  }

  /** 链接装配产物（生命周期均归 App 持有：close 顺序 bridge → source）。 */
  private record McpLinks(List<McpToolSource> sources, List<McpSourceBridge> bridges) {

    static McpLinks empty() {
      return new McpLinks(List.of(), List.of());
    }
  }

  /** 子 Agent 编排装配产物（管理核心 + 进程兜底，生命周期均归 App，close 顺序 manager → processes）。 */
  private record SubagentRig(SubagentManager manager, SubprocessManager processes) {}
}
