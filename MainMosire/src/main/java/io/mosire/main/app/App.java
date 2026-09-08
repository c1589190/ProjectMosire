package io.mosire.main.app;

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
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.TurnResult;
import io.mosire.main.Version;
import io.mosire.main.gateway.AdminHttpServer;
import io.mosire.main.gateway.StatusSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
 */
public final class App implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(App.class);

  public static final String DEFAULT_SYSTEM_PROMPT =
      "你是 Mosire 主 Agent——一个模块化、可自管理、受权限约束的 Agent。" + "保持诚实：无工具可用时直接说明，不虚构执行过程。";
  public static final String DEMO_USER_MESSAGE = "你好，请确认你的骨架已就绪。";

  private final EventStore events;
  private final EventBus bus;
  private final AgentRuntime runtime;
  private final AdminHttpServer http;
  private final AgentToMcpServer mcpServer;
  private final List<McpToolSource> mcpSources;
  private final List<McpSourceBridge> mcpBridges;
  private final CountDownLatch terminated = new CountDownLatch(1);
  private volatile boolean closed;

  /** 启动：拉起存储/运行时/MCP（链接+暴露）/AdminREST，注册 SIGTERM/SIGINT 优雅关停钩子（计划 §5.1）。 */
  public static App start(BootConfig config) {
    try {
      Files.createDirectories(config.dataDir());
    } catch (IOException e) {
      throw new IllegalStateException("无法创建数据目录: " + config.dataDir(), e);
    }
    EventStore events = SqliteEventStore.open(config.dataDir().resolve("events.db"));
    EventBus bus = new EventBus();
    ToolRegistry tools = new ToolRegistry();
    // W2 步骤 1：MCP 链接装配（在暴露快照与主 Agent 使用之前连入 registry——计划 §5.1 "MCP links → 创建主 AgentRuntime"）
    McpLinks links = wireMcpLinks(config.mcpLinks(), tools);
    AgentToMcpServer mcpServer = null;
    try {
      // W2 步骤 2：主 Agent 工具面经 stdio MCP server 暴露（R7 警示下的当前默认：GUEST 身份，收窄属 M3）
      mcpServer =
          config.mcpExpose() ? AgentToMcpServer.start(tools, "mosire-main", Version.VERSION) : null;
      AgentPermissionSet permissionSet = AgentPermissionSet.system();
      AgentConfig agentConfig =
          AgentConfig.builder("main")
              .systemPrompt(DEFAULT_SYSTEM_PROMPT)
              .description("主 Agent（M1 骨架）")
              .build();
      AgentRuntime runtime =
          new AgentRuntime(agentConfig, scriptedLlm(config), tools, events, bus, permissionSet);
      AdminHttpServer http =
          AdminHttpServer.start(
              config.port(),
              () ->
                  StatusSnapshot.healthy(
                      Version.ARTIFACT_ID, Version.VERSION, agentConfig.id(), events.count()));

      App app = new App(events, bus, runtime, http, mcpServer, links.sources(), links.bridges());

      if (config.demo()) {
        String message = config.demoMessage().isEmpty() ? DEMO_USER_MESSAGE : config.demoMessage();
        runDemo(app, runtime, message);
      }
      Runtime.getRuntime().addShutdownHook(new Thread(app::close, "mosire-shutdown"));
      return app;
    } catch (RuntimeException e) {
      // 装配中途失败（如端口占用）：回收已起的 MCP 子进程，避免启动失败后残留僵尸进程
      if (mcpServer != null) {
        mcpServer.close();
      }
      closeLinks(links);
      throw e;
    }
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

  private static LlmClient scriptedLlm(BootConfig config) {
    // M1：只支持脚本 LLM；非 demo 模式仅占位（真实供应商在 M3 按 ModelRoute 接入）
    if (!config.demo()) {
      return FakeLlmClient.with();
    }
    return FakeLlmClient.with(
        LlmResponse.text("你好！Mosire 主 Agent 骨架已就绪，事件存储与权限门禁在线。" + "这是我首次运行的自检回复。"));
  }

  private App(
      EventStore events,
      EventBus bus,
      AgentRuntime runtime,
      AdminHttpServer http,
      AgentToMcpServer mcpServer,
      List<McpToolSource> mcpSources,
      List<McpSourceBridge> mcpBridges) {
    this.events = events;
    this.bus = bus;
    this.runtime = runtime;
    this.http = http;
    this.mcpServer = mcpServer;
    this.mcpSources = List.copyOf(mcpSources);
    this.mcpBridges = List.copyOf(mcpBridges);
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

  /**
   * 优雅关停（计划 §5.1）：网关 drain → MCP 暴露闭（先摘 registry 订阅，避免桥下架工具的变更流进已闭 server）→ 各链接
   * bridge.close（整组下架工具）→ source.close（回收子进程）→ 运行时 → 事件存储 checkpoint。
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    LOG.info("正在停止：网关 drain → MCP 暴露/链接 → 运行时 → 事件存储 checkpoint");
    try {
      http.close();
    } finally {
      try {
        if (mcpServer != null) {
          mcpServer.close();
        }
        closeLinks(new McpLinks(mcpSources, mcpBridges));
      } finally {
        runtime.close();
      }
    }
    events.close();
    bus.close();
    terminated.countDown();
  }

  /** 链接回收（共享给关停与装配失败路径）：先 bridge（整组下架工具），再 source（回收子进程）。 */
  private static void closeLinks(McpLinks links) {
    for (McpSourceBridge bridge : links.bridges()) {
      bridge.close();
    }
    for (McpToolSource source : links.sources()) {
      source.close();
    }
  }

  /** 链接装配产物（生命周期均归 App 持有：close 顺序 bridge → source）。 */
  private record McpLinks(List<McpToolSource> sources, List<McpSourceBridge> bridges) {

    static McpLinks empty() {
      return new McpLinks(List.of(), List.of());
    }
  }
}
