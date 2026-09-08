package io.mosire.main.agent;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.mcp.McpSourceBridge;
import io.mosire.agentlib.mcp.McpToolSource;
import io.mosire.agentlib.mcp.PipeMcpClientTransport;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.TurnResult;
import io.mosire.brain.subagent.AgentTemplate;
import io.mosire.brain.subagent.AgentTemplateStore;
import io.mosire.main.app.BootConfig;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 子 Agent 进程引导（{@code Main agent --id <id> --template <t> --goal <g> [--templates-dir <d>]
 * [--data-dir <d>] [--parent-link]}）——计划 §4.4 的"子体进程即 runtime + MCP client"。
 *
 * <p><strong>进程形态</strong>：
 *
 * <ul>
 *   <li>无网关（不进 HTTP/AdminREST/A2A——任务口径"子进程内无网关"）；
 *   <li>模板装载（{@code --templates-dir}，缺省 {@code configs/agents}）→ 模板权限集构造 runtime（子体身份不是继承 父级——红线 R7
 *       的进程面：自身权限即模板三要素，父侧按同一模板权限集为子体服务）→ 事件库落在 {@code --data-dir/events.db}（父装配层经 {@code
 *       --data-dir <root>/<instanceId>} 隔离）；
 *   <li>LLM 固定为模板引导脚本（{@code template.scriptedFakeLlm()}，离线确定性——W3 E2E 的复现支点；M3 换真实模型）。
 * </ul>
 *
 * <p><strong>父链接（{@code --parent-link}）</strong>：stdin/stdout 是父进程建好的 MCP stdio 链路（标准换行帧、 一帧一
 * JSON-RPC），本进程以 <b>MCP client</b> 身份接管（{@link PipeMcpClientTransport}——官方 StdioClientTransport 只
 * spawn 不接管流），经 {@link McpSourceBridge}（按模板白名单过滤）把父级暴露面同步进本地 registry；跑完目标任务后<b>阻塞在 {@code
 * whenClosed()}</b>——父侧 kill 先关其 server（本进程 EOF 感知关停信号） 再走三层进程关停，父侧把"关停信号"当协议事件而非直接
 * SIGKILL，本进程可收尾落事件库。
 *
 * <p><strong>stdout 纪律</strong>：本进程 stdout = MCP 帧通道。所有可见输出只准走 stderr（启动即把 {@code System.out}
 * 与协议流解绑——帧用早期捕获的原始流，日志/错误经 stderr；使用期间任何窗口进程 若把信息写 {@code System.out} 都会污染协议帧，代码评审红线）。
 *
 * <p>并非「关闭语义」：本类失败路径一律 stderr + 非零退出码，<b>不</b>把异常焐成零退出（父侧观测到子体退出即按 FINISHED 收束——退出码用于人工/脚本排障）。
 */
public final class SubagentProcessMain {

  /** {@code --parent-link} 子命令形态里父侧 MCP server 自报的名字（本进程只作目录同步，不作身份依据）。 */
  static final String PARENT_LINK_SERVER_NAME = "mosire-parent";

  private SubagentProcessMain() {}

  /**
   * 运行一次子 Agent 进程。
   *
   * @return 进程退出码：0 = 目标回合完成且（链接形态下）收到父侧关停信号并正常退栈；1 = 引导/运行失败
   */
  public static int execute(Options options) {
    Objects.requireNonNull(options, "options");
    // 帧通道 = 启动时的 stdout 原流；此后 System.out 一律重定向到 stderr——详见类注释 stdout 纪律
    OutputStream protocolOut = System.out;
    PrintStream diagnostics = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    System.setOut(diagnostics);

    AgentTemplateStore templateStore = null;
    McpToolSource source = null;
    McpSourceBridge bridge = null;
    PipeMcpClientTransport transport = null;
    AgentRuntime runtime = null;
    SqliteEventStore events = null;
    EventBus bus = null;
    try {
      AgentTemplateStore store = new AgentTemplateStore(options.templatesDir());
      store.load();
      templateStore = store;
      AgentTemplate template =
          store
              .get(options.templateId())
              .orElseThrow(
                  () -> new IllegalArgumentException("子 Agent 模板不存在: " + options.templateId()));

      Files.createDirectories(options.dataDir());
      SqliteEventStore eventStore = SqliteEventStore.open(options.dataDir().resolve("events.db"));
      events = eventStore;
      EventBus eventBus = new EventBus();
      bus = eventBus;

      AgentConfig config = template.toAgentConfig(options.instanceId());
      AgentPermissionSet permissions = template.toPermissionSet();
      ToolRegistry registry = new ToolRegistry();
      if (options.parentLink()) {
        // 父侧建好的管道 → MCP client（握手/工具目录/调用全经此传输；父侧永远在同一对管道上服务）
        McpToolSource parentSource = new McpToolSource("parent");
        source = parentSource;
        transport = new PipeMcpClientTransport(McpJsonDefaults.getMapper(), System.in, protocolOut);
        parentSource.connect(transport);
        // 白名单过滤：模板声名的工具集 ∩ 父侧暴露面——未命中的工具对子体不可见（同步即授权）
        bridge = McpSourceBridge.bind(parentSource, registry, filterFor(permissions));
      }

      AgentRuntime agent =
          new AgentRuntime(
              config, template.scriptedFakeLlm(), registry, eventStore, eventBus, permissions);
      runtime = agent;
      TurnResult result = agent.chat(options.goal());
      diagnostics.println(
          String.format(
              "子 Agent 回合完成: id=%s stop=%s turns=%d toolCalls=%d",
              options.instanceId(), result.stopReason(), result.turns(), result.toolCalls()));

      if (transport != null) {
        // 链接形态：目标跑完后阻塞——父侧关停 = 对本进程的"协议关停信号"（EOF），先收尾再退
        try {
          transport.whenClosed().get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          diagnostics.println("子 Agent 等待父侧关停被中断: " + options.instanceId());
        }
      }
      return 0;
    } catch (RuntimeException | java.io.IOException | java.util.concurrent.ExecutionException e) {
      diagnostics.println("子 Agent 引导/运行失败: " + e.getMessage());
      return 1;
    } finally {
      // 模板库是只读快照（进程级无需释放）；资源按创建反序回收
      closeQuietly(bridge);
      closeQuietly(source);
      if (transport != null) {
        // 优雅关停管道：释放内部调度线程（非守护线程残留会拖住 JVM 退出——父侧 kill 无需等宽限超时兜底）
        try {
          transport.closeGracefully().block();
        } catch (Exception e) {
          System.err.println("子 Agent 父链接关停失败: " + e.getMessage());
        }
      }
      closeQuietly(runtime);
      closeQuietly(bus);
      closeQuietly(events);
    }
  }

  /** 模板权限集 → 桥同步过滤器：白名单通配（{@code "*"}）不过滤，否则按名命中。 */
  private static Predicate<String> filterFor(AgentPermissionSet permissions) {
    if (permissions.allowedTools().contains(AgentPermissionSet.ALL_TOOLS)) {
      return name -> true;
    }
    return permissions.allowedTools()::contains;
  }

  private static void closeQuietly(AutoCloseable resource) {
    if (resource == null) {
      return;
    }
    try {
      resource.close();
    } catch (Exception e) {
      System.err.println("子 Agent 资源关闭失败: " + e.getMessage());
    }
  }

  /** {@code agent} 子命令的进程级参数（Main 解析后的不可变快照）。 */
  public record Options(
      String instanceId,
      String templateId,
      String goal,
      Path templatesDir,
      Path dataDir,
      boolean parentLink) {

    public Options {
      if (instanceId == null || instanceId.isBlank()) {
        throw new IllegalArgumentException("--id 不能为空");
      }
      if (templateId == null || templateId.isBlank()) {
        throw new IllegalArgumentException("--template 不能为空");
      }
      if (goal == null || goal.isBlank()) {
        throw new IllegalArgumentException("--goal 不能为空");
      }
      templatesDir = Objects.requireNonNullElse(templatesDir, BootConfig.DEFAULT_TEMPLATES_DIR);
      dataDir = Objects.requireNonNullElse(dataDir, BootConfig.DEFAULT_DATA_DIR);
    }
  }
}
