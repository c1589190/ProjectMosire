package io.mosire.main.agent;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ConfigApiKeySource;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmRouteLoader;
import io.mosire.agentlib.llm.ModelRoute;
import io.mosire.agentlib.llm.OpenAICompatibleLlmClient;
import io.mosire.agentlib.mcp.McpSourceBridge;
import io.mosire.agentlib.mcp.McpToolSource;
import io.mosire.agentlib.mcp.PipeMcpClientTransport;
import io.mosire.agentlib.permission.AccessToken;
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
 * [--data-dir <d>] [--config-dir <d>] [--parent-link]}）——计划 §4.4 的"子体进程即 runtime + MCP client"。
 *
 * <p><strong>进程形态</strong>：
 *
 * <ul>
 *   <li>无网关（不进 HTTP/AdminREST/A2A——任务口径"子进程内无网关"）；
 *   <li>模板装载（{@code --templates-dir}，缺省 {@code configs/agents}）→ 模板权限集构造 runtime（子体身份不是继承 父级——红线 R7
 *       的进程面：自身权限即模板三要素，父侧按同一模板权限集为子体服务）→ 事件库落在 {@code --data-dir/events.db}（父装配层经 {@code
 *       --data-dir <root>/<instanceId>} 隔离）；
 *   <li>LLM 形态由 {@code --config-dir} 二分（R-A2-1）：<b>缺席</b> = 离线/测试形态，逐字不变地走模板引导脚本（{@code
 *       template.scriptedFakeLlm()}，W3 E2E 与 smoke.sh 的复现支点）；<b>在场</b> = 真模型形态，按 {@code
 *       <config-dir>/config.json} 的 {@code llm.*} 构造 {@link OpenAICompatibleLlmClient}（见 {@link
 *       #realLlm}）。 两种形态下模板的 {@code maxTurns}/{@code maxToolCallsPerTurn}/{@code
 *       timeBudgetSeconds}/{@code quotaMaxTokens} 硬顶一律生效（R-A2-8：硬顶属于运行时，与 LLM 形态无关）。
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
 * <p><strong>退出码语义（如实二分，勿写成"失败一律非零"）</strong>：<b>引导期</b>装配失败 ⇒ stderr + <b>非零退出</b>，绝不把异常焐成零退出 ——缺
 * {@code llm.*}（{@code E_LLM_CONFIG_MISSING}）、缺密钥（{@code E_KEY_MISSING}）、密钥引用形态不支持（{@code
 * E_REF_FORM_UNSUPPORTED}），见 {@link #realLlm} 的取密钥预检。而 <b>chat 期</b>的 LLM 失败（端点不可达 / 401 / 超时）由
 * {@link io.mosire.brain.runtime.AgentPipeline}（{@code :289-293}）收敛成 {@link
 * io.mosire.brain.runtime.StopReason#LLM_ERROR} 的<b>正常回合</b>——本类照常打印"回合完成"并 <b>exit
 * 0</b>，退出码不区分"模型坏了"。 这是<b>既有语义</b>（与主 Agent 一致；脚本假 LLM 排空时同样走这条路），父侧只能从回合停因 {@code stop=LLM_ERROR}
 * 分辨；改退出码属另一件事。
 */
public final class SubagentProcessMain {

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

    McpToolSource source = null;
    McpSourceBridge bridge = null;
    PipeMcpClientTransport transport = null;
    AgentRuntime runtime = null;
    SqliteEventStore events = null;
    EventBus bus = null;
    try {
      // 模板库是只读快照（进程级无需释放），局部使用即可（无 finally 回收——避免死存储）
      AgentTemplateStore store = new AgentTemplateStore(options.templatesDir());
      store.load();
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

      // R-A2-1：--config-dir 缺席 ⇒ 逐字不变地走模板脚本假 LLM；在场 ⇒ 真模型（R-A2-2/R-A2-7：路由与密钥一律取自配置）
      LlmClient llm =
          options.configDir() == null ? template.scriptedFakeLlm() : realLlm(options.configDir());
      AgentRuntime agent =
          new AgentRuntime(config, llm, registry, eventStore, eventBus, permissions);
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

  /**
   * 真模型装配（R-A2-2/R-A2-7）：<b>只</b>读 {@code <configRoot>/config.json} 的 {@code llm.*}——{@code
   * baseUrl}/模型名/密钥三者全部来自这条 {@link ModelRoute}，模板里的 {@code model}（{@code AgentConfig} 默认字面量 {@code
   * "fake"}）不参与。<b>只传路径</b>：父进程给的是配置根，密钥值由本进程自己读出（D23/R-A2-3）。
   *
   * <p><b>失败一律响亮（D24），绝不回退 {@code scriptedFakeLlm()}</b>：
   *
   * <ul>
   *   <li>缺 {@code llm.baseUrl}/{@code llm.model} ⇒ {@link LlmRouteLoader#load} 在引导期抛 {@code
   *       E_LLM_CONFIG_MISSING}；
   *   <li>缺密钥 / 引用形态不支持 ⇒ 由下面这次"取密钥预检"在引导期抛 {@link ConfigApiKeySource#E_KEY_MISSING}/{@code
   *       E_REF_FORM_UNSUPPORTED}。<b>为什么预检</b>：不预检的话，取密钥失败要等到首次 {@code chat} 才发生，而 {@code
   *       AgentPipeline} 会把 {@code LlmException} 收敛成 {@code StopReason.LLM_ERROR} 的<b>正常回合</b>（退出码
   *       0）——父侧就再也分辨不出"子体压根没跑起来"。预检只多取一次值、不固化任何东西：{@link ConfigApiKeySource} 每次 {@code chat}
   *       仍现读配置，轮换/过期感知的 SPI 契约不变。
   * </ul>
   *
   * <p>异常消息不含密钥值（引用名也只经 {@link ConfigApiKeySource} 的白名单回显），可直接进子进程 stderr。
   */
  private static LlmClient realLlm(Path configRoot) {
    FileConfigStore store = new FileConfigStore(configRoot);
    ModelRoute route = LlmRouteLoader.load(store);
    ConfigApiKeySource keys =
        new ConfigApiKeySource(store, route.credentialsRef(), AccessToken.SYSTEM);
    keys.apiKey(); // 预检：结果有意丢弃（只要"取得到"这一事实；密钥值不留在本方法里）
    return new OpenAICompatibleLlmClient(route, keys);
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

  /**
   * {@code agent} 子命令的进程级参数（Main 解析后的不可变快照）。
   *
   * @param configDir 父的配置根（{@code --config-dir}；null = 离线/测试形态 ⇒ 模板脚本假 LLM）。只放路径——密钥值由本进程自己从 {@code
   *     <configDir>/config.json} 读（D23）
   */
  public record Options(
      String instanceId,
      String templateId,
      String goal,
      Path templatesDir,
      Path dataDir,
      boolean parentLink,
      Path configDir) {

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
