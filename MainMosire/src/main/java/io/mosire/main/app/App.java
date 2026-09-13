package io.mosire.main.app;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.ApprovalConfig;
import io.mosire.agentlib.approval.ApprovalConfigLoader;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.AutoApproveGate;
import io.mosire.agentlib.approval.ConfirmGate;
import io.mosire.agentlib.approval.LlmSuperiorJudgement;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.approval.SuperiorJudgeGate;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.mcp.AgentToMcpServer;
import io.mosire.agentlib.mcp.McpServerLinkConfig;
import io.mosire.agentlib.mcp.McpSourceBridge;
import io.mosire.agentlib.mcp.McpToolSource;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandModeHolder;
import io.mosire.agentlib.permission.CommandModeLoader;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.plugin.BuiltinToolSource;
import io.mosire.agentlib.proc.SubprocessManager;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.AgentSpec;
import io.mosire.brain.runtime.TurnResult;
import io.mosire.brain.subagent.AgentCommand;
import io.mosire.brain.subagent.AgentContextReader;
import io.mosire.brain.subagent.AgentTemplateStore;
import io.mosire.brain.subagent.SubProcessExecutor;
import io.mosire.brain.subagent.SubagentInstance;
import io.mosire.brain.subagent.SubagentLimits;
import io.mosire.brain.subagent.SubagentLimitsLoader;
import io.mosire.brain.subagent.SubagentManager;
import io.mosire.brain.subagent.SubagentOrchestrationTools;
import io.mosire.brain.tools.BashToolConfig;
import io.mosire.brain.tools.BashToolConfigLoader;
import io.mosire.brain.tools.ShellTool;
import io.mosire.brain.tools.WorkingDirsLoader;
import io.mosire.main.Version;
import io.mosire.main.approval.ApprovalHttpServer;
import io.mosire.main.approval.HttpApprovalChannel;
import io.mosire.main.approval.TtyApprovalChannel;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * §六 一份 DDL），主 Agent 的多轮对话因此跨进程存活；启动会话 id 由<b>库里的会话指针</b>决定（首次启动才落到 {@link
 * #MAIN_CONVERSATION_ID}，重置写下的新 id 经指针跨重启有效）。{@code --demo} 恒不落库（零行为变化）。重置入口不在本类：{@code POST
 * /api/chat/session} → {@link
 * io.mosire.main.gateway.debug.DebugChatService#resetSession()}（切换动作排在共享 chat 执行器上，R11）。
 */
public final class App implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(App.class);

  /** 子 Agent 链接的父侧 MCP server 自报名（子体按"parent"源名识别——仅日志/审计语义）。 */
  private static final String PARENT_SERVER_NAME = "mosire-parent";

  /**
   * 主 Agent 的初始会话 id（P3-3）：恒为 {@code "main"}（= 主 Agent 的 {@code AgentConfig} id，同源）。
   *
   * <p><b>初始 id 必须稳定</b>——"重启进程后历史仍在"靠的就是每段进程用同一个 id 去库里寻址；若每次启动取随机 id，每次都是全新会话， 落盘等于白落。
   *
   * <p><b>它只是"没有会话指针时的缺省"</b>：{@code conversations} 非空时，管线构造会优先采用库里的会话指针（{@code
   * ConversationStore#currentConversationId}），重置写下的新 id 因此跨重启有效；本常量只在首次启动（指针尚未写过）时生效。
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

  /**
   * 审批 HTTP 面（S4-B2）：{@code null} = 未启用（{@code approval.http=false}）。
   *
   * <p>它与 {@link #approvalHttpChannel} 是同一个面的两面——server 是"人的入口"，channel 是"编排器的等待口"，两者共享 {@code App}
   * 里那<b>唯一</b>一份 {@code PendingApprovals}。
   */
  private final ApprovalHttpServer approvalServer;

  /** tty 审批通道（S4-B2）：无控制终端时 {@code available()==false}（装配照做，可用性由它自己如实报）。 */
  private final TtyApprovalChannel approvalTtyChannel;

  /** HTTP 审批面在编排器侧的那条通道（S4-B2；{@code approval.http=false} 时恒不可用）。 */
  private final HttpApprovalChannel approvalHttpChannel;

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
    // S4-C：审批事件的<b>落账</b>面。B1 的 ApprovalCoordinator 只把 approval.requested/decided 投到 bus
    // （B2 收口"用已有的那个总线"），而这条总线目前唯一的订阅者是 AG-UI 的<b>唤醒信号</b>
    // （AgUiHttpServer:212 只 signal；AG-UI 的数据面是从 events 回读的）⇒ 审批事件在进程里<b>没有任何可回读的面</b>
    // （实测数据目录 events.db：distinct type 只有
    // agent.lifecycle/llm.call/conversation.turn/tool.call/tool.result，
    // 无 approval.*）。V3 判据要求 "approval.decided{scope=once} 落账" ⇒ 在这里把审批事件镜像进事件库。
    // 只认 approval.* 前缀（管线自己的 emit 已落库，不会双写）；落库失败只告警，不改判定（同 B1 的审计口径）。
    bus.subscribe(event -> mirrorApprovalEvent(events, event));
    ToolRegistry tools = new ToolRegistry();
    McpLinks links;
    try {
      // 主 try 之前会抛非致命异常的两处调用（工具注册同名/空元素、链接装配）都在本兜底内：抛错时控制流直接逃出
      // start()，主 catch 够不着——就地回收前面已打开的两条连接（回收口径同主 catch，且用 quiet 关闭保证原异常原样上抛
      // ——失败原因不被"关闭也失败"顶掉）。
      tools.registerAll(extraTools == null ? List.of() : extraTools);
      // S4-C：bash 给主 Agent（`ShellTool` 二期早已写好、从未注册）。主 Agent 是 system() 权限集 ⇒ spec() 的
      // sensitive+destructive 位天然放行（用户裁决"全功能"）；子体拿不到它——出厂模板 allowedTools 不含 bash（下放归 S5）。
      // 它未标 noExport ⇒ 会进 MCP 外发面（有意的：子体的白名单挡着，启动日志会报出实际外发面）。
      // 基准工作目录 = 进程 CWD（工具的缺省构造语义，见 ShellTool 类 javadoc）；命令的三档分流与落账脱敏在工具内。
      tools.register(new ShellTool());
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
    // S4-B2：审批面资源声明在 try 之外（同其余网关口径）——装配中途失败时 catch 里要回收已起的那部分
    ApprovalHttpServer approvalServer = null;
    TtyApprovalChannel approvalTtyChannel = null;
    HttpApprovalChannel approvalHttpChannel = null;
    ExecutorService chatExecutor = null;
    SubagentManager subagentManager = null;
    SubprocessManager subagentProcesses = null;
    try {
      AgentConfig agentConfig =
          AgentConfig.builder("main")
              .systemPrompt(DEFAULT_SYSTEM_PROMPT)
              .description("主 Agent（M1 骨架）")
              .build();
      // D17：主 Agent 以 AgentSpec 装配（组合既有 AgentConfig，二期字段暂取默认值——P2-2 Task 6 仅承载不接线）
      AgentSpec agentSpec = AgentSpec.builder(agentConfig).build();
      // ★ S4-B2 审批装配：必须排在子 Agent 编排与 MCP 暴露<b>之前</b>——那两处的工具调用入口都要拿同一个编排器。
      //   同一进程内只能有一个 PendingApprovals（两通道必须看到同一 id，H8 判据）：它由本装配层建、逐层当参数传下去，
      //   不设任何静态/全局单例。
      FileConfigStore configStore = new FileConfigStore(config.dataDir());
      ApprovalConfig approvalConfig = ApprovalConfigLoader.load(configStore);
      // S4-C：bash 工具配置（追加清单 + 落账口径）同一处读——两者的共同点是"启动期读、项存在但值非法即响亮失败"
      BashToolConfig bashConfig = BashToolConfigLoader.load(configStore);
      PendingApprovals pendingApprovals = new PendingApprovals();
      approvalTtyChannel = new TtyApprovalChannel(pendingApprovals);
      approvalHttpChannel = new HttpApprovalChannel(pendingApprovals);
      // S6：LLM 的装配点<b>上移</b>到审批装配之前——上级判定闸（SuperiorJudgeGate）要用同一个客户端跑一次性判定。
      // 供应商客户端由 Main.selectLlm 经 llmOverride 注入（生产 = 真模型；App.start 直调无覆盖 = 离线骨架）。
      LlmClient llm = scriptedLlm(config, llmOverride);
      // S6：主 Agent 的<b>实时档位</b>——配置 {@code commands.mode} 给初值（缺省 FULL = 本功能引入前的行为），
      // 运行时经 HTTP 断点（GET/POST /api/commands/mode）可改。读方每次现读：主 Agent 自己的工具调用装配处
      // （AgentPipeline 建 ToolContext 时）、上级判定闸的"本级是不是完全权限"、派生子 Agent 时的档位单调性守卫。
      CommandModeHolder commandMode = new CommandModeHolder(CommandModeLoader.load(configStore));
      LOG.info("命令档位: {}（配置 commands.mode；运行时可经 HTTP 断点改）", commandMode.get().wireName());
      // S5-A：繁殖预算（深度/直系/全局三个额度）同一处读——坏值启动即响亮失败，别让它变成"看起来配了、其实按缺省办"
      SubagentLimits subagentLimits = SubagentLimitsLoader.load(configStore);
      LOG.info(
          "子 Agent 预算: 总层数={}（子深度≤{}）单实例直系≤{} 全局在管≤{}（配置 subagents.*）",
          subagentLimits.maxDepth(),
          subagentLimits.maxChildDepth(),
          subagentLimits.maxChildrenPerInstance(),
          subagentLimits.maxInstances());
      // S5-B：主 Agent 的 fs 作用域（工作目录围栏）——配置 agents.workingDirs，<b>缺省不限</b>（裁决 ①，逐跳只减不增）。
      // 本键只有配置文件/环境层能写：ConfigAuth 的 agents.* 语法是 agents.<id>.<key>（三段），两段的这条写不进去（账本记档）。
      ResourceScope workingDirs = WorkingDirsLoader.load(configStore);
      AgentPermissionSet permissionSet =
          AgentPermissionSet.system()
              .withResourceScopes(ResourceScopeMap.of(ResourceScopeMap.FS, workingDirs));
      LOG.info("主 Agent 工作目录围栏: {}（配置 agents.workingDirs；* = 不限，子体逐跳只减不增）", workingDirs.summary());
      // S4-C 接线：快判链从空表改成设计 §2.3 的形状（DenyGate 不装——没有配置黑名单来源，硬拒由分类器的 Block 档
      // 在 authorizer 里终局，不该有第二份清单）。AutoApproveGate 是"会话级放行第二次不再问"的<b>唯一</b>读取方：
      // 不装它，APPROVE_SESSION 只会被登记而永远不会被读（V4 会红），且没有任何用例会报警。
      // S6 追加 SuperiorJudgeGate：AutoApproveGate <b>在前</b>是刻意的——会话级放行是人批的口子（用户裁决
      // "LIMITED 档下只认人批的会话"），人批过的类别不该再让上级 Agent 判一遍；代批闸只覆盖剩下的档位提升请求，
      // 敏感区与外部面它自己会让开（落到 ConfirmGate，即到人）。
      ApprovalCoordinator approvalCoordinator =
          new ApprovalCoordinator(
              List.of(
                  new AutoApproveGate(pendingApprovals),
                  new SuperiorJudgeGate(new LlmSuperiorJudgement(llm), commandMode::get),
                  new ConfirmGate()),
              List.of(approvalTtyChannel, approvalHttpChannel),
              pendingApprovals,
              approvalConfig.timeout(),
              bus);
      ToolCallAuthorizer approvalAuthorizer =
          ToolCallAuthorizer.of(new ToolExecutionGuard(), approvalCoordinator);
      if (approvalConfig.http()) {
        approvalServer =
            ApprovalHttpServer.start(
                approvalConfig.httpPort(), pendingApprovals, approvalCoordinator, commandMode);
        // HTTP 面真的绑定成功了才算可用通道（"配置说要开"不等于"端口在监听"）
        approvalHttpChannel.markUp();
      }
      LOG.info(
          "审批面已装配：approvals={} tty通道={} 超时={}s（HTTP 面恒绑 127.0.0.1，无鉴权，与 AdminREST 同基线）",
          approvalServer == null
              ? "未启用（approval.http=false）"
              : "http://127.0.0.1:" + approvalServer.boundPort(),
          approvalTtyChannel.available() ? "可用" : "不可用（无控制终端）",
          approvalConfig.timeout().toSeconds());
      // W3b：子 Agent 编排装配（模板目录非空时才启用——计划 §4.4 + W3；编排工具先于暴露快照注册进工具面）
      if (config.templatesDir() != null) {
        SubagentRig rig =
            wireSubagents(
                config,
                tools,
                events,
                bus,
                agentConfig,
                permissionSet,
                subagentConfigDir,
                approvalAuthorizer,
                commandMode,
                subagentLimits);
        subagentManager = rig.manager();
        subagentProcesses = rig.processes();
      }
      // W2 步骤 2：主 Agent 工具面经 stdio MCP server 暴露（R7 警示下的当前默认：GUEST 身份，收窄属 M3）。
      // S4-B2：改用带 authorizer 的 5 参重载（V8 的结构前提——子体经 MCP 调父级工具时审批同样生效）；
      // 调用者逐字保持 3 参重载的缺省（GUEST + unrestricted(GUEST)，红线 5：既有行为不变）。
      // 注意 GUEST 桶 = 全体外部 MCP 客户端（桶粒度分不出实例）⇒ 人在 HTTP 上给 GUEST 批"本会话"会被收窄为一次，
      // "下次还问"是<b>设计</b>（S5 身份穿透前不许泛化），不是 bug——别为让 session 生效去动 callerKey。
      mcpServer =
          config.mcpExpose()
              ? AgentToMcpServer.start(
                  tools,
                  "mosire-main",
                  Version.VERSION,
                  ToolContext.of(
                      AccessToken.GUEST,
                      AgentPermissionSet.unrestricted(AccessToken.GUEST),
                      // S6：外部面显式自我介绍（external-mcp + FULL）。档位照旧不变，身份<b>必须</b>显式——
                      // 判定闸据此认出"这不是我派的下级"，从而不为外部客户端的命令背书（到人，见 SuperiorJudgeGate）
                      AgentIdentity.external()),
                  approvalAuthorizer)
              : null;
      // D30：工具自身配置经 ToolContext.config 注入（运行时缝，不走构造器全局）——read_agent_context 据此定位
      // 子库根（与 subagentCommand 的 --data-dir 同源）与自身事件库；键名归 Brain（AgentContextReader 常量）
      // S4-C：bash 工具的三项配置也走这条缝（键 = 配置里的完整点分路径，见 BashToolConfig）
      Map<String, Object> toolConfig = new LinkedHashMap<>();
      toolConfig.put(
          AgentContextReader.CONFIG_SUBAGENTS_ROOT,
          config.dataDir().resolve("subagents").toString());
      toolConfig.put(
          AgentContextReader.CONFIG_SELF_EVENTS_DB,
          config.dataDir().resolve("events.db").toString());
      toolConfig.put(AgentContextReader.CONFIG_SELF_AGENT_ID, agentConfig.id());
      toolConfig.putAll(bashConfig.toToolConfig());
      // S6：档位持有者经同一个运行时缝下发（AgentPipeline 建调用上下文时现读它定主 Agent 的身份/档位）
      toolConfig.put(CommandModeHolder.CONFIG_KEY, commandMode);
      toolConfig = Map.copyOf(toolConfig);
      // P3-3：落库分叉只此一处——conversations == null（demo）走不落库的既有构造，非 demo 走 (store, conversationId)
      // S4-B2：审批编排器只接在<b>生产分叉</b>（落库那条，= run 的终态装配路径）；demo 分叉保持无审批面
      // （--demo 恒"零行为变化"是本仓既有约定：它只跑一条脚本回合，没有工具调用面可审批）
      AgentRuntime runtime =
          conversations == null
              ? new AgentRuntime(agentSpec, llm, tools, events, bus, permissionSet, toolConfig)
              : new AgentRuntime(
                  agentSpec,
                  llm,
                  tools,
                  events,
                  bus,
                  permissionSet,
                  conversations,
                  MAIN_CONVERSATION_ID,
                  toolConfig,
                  approvalCoordinator);
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
              subagentProcesses,
              approvalServer,
              approvalTtyChannel,
              approvalHttpChannel);

      if (config.demo()) {
        String message = config.demoMessage().isEmpty() ? DEMO_USER_MESSAGE : config.demoMessage();
        runDemo(app, runtime, message);
      }
      Runtime.getRuntime().addShutdownHook(new Thread(app::close, "mosire-shutdown"));
      return app;
    } catch (RuntimeException e) {
      // 装配中途失败（如端口占用）：回收已起的网关/MCP 子进程/子 Agent 编排，避免启动失败后残留僵尸进程
      if (approvalServer != null) {
        approvalServer.close();
      }
      if (approvalTtyChannel != null) {
        approvalTtyChannel.close();
      }
      if (approvalHttpChannel != null) {
        approvalHttpChannel.close();
      }
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
   *
   * <p>S5-A：那句"父级 = 主 Agent 对照"只覆盖 3 参 {@code spawn}（= 主 Agent 在要）；经 MCP 链接进来的下级调用走 {@code
   * spawn(request, identity, permissions)}，对照物是<b>调用者自己</b>。
   */
  private static SubagentRig wireSubagents(
      BootConfig config,
      ToolRegistry tools,
      EventStore events,
      EventBus bus,
      AgentConfig parentConfig,
      AgentPermissionSet parentPermissions,
      Path subagentConfigDir,
      ToolCallAuthorizer authorizer,
      CommandModeHolder commandMode,
      SubagentLimits limits) {
    AgentTemplateStore templateStore = new AgentTemplateStore(config.templatesDir());
    templateStore.load();
    SubprocessManager processes = new SubprocessManager();
    SubProcessExecutor executor =
        new SubProcessExecutor(
            processes,
            subagentCommand(config, subagentConfigDir),
            tools,
            PARENT_SERVER_NAME,
            Version.VERSION,
            authorizer);
    // S6：父级档位取数缝传的是<b>持有者</b>（现读）——HTTP 断点把主 Agent 降到 LIMITED 后，接着派子 Agent 也拿不到 FULL
    SubagentManager manager =
        new SubagentManager(
            templateStore,
            executor,
            events,
            bus,
            parentConfig,
            parentPermissions,
            0,
            commandMode::get,
            limits);
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
      SubprocessManager subagentProcesses,
      ApprovalHttpServer approvalServer,
      TtyApprovalChannel approvalTtyChannel,
      HttpApprovalChannel approvalHttpChannel) {
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
    this.approvalServer = approvalServer;
    this.approvalTtyChannel = approvalTtyChannel;
    this.approvalHttpChannel = approvalHttpChannel;
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

  /**
   * 审批 HTTP 面实际监听端口（S4-B2；恒绑 127.0.0.1，0 = 自动分配场景下由调用方取实际值）。
   *
   * <p>返回 {@code 0} 表示本进程<b>没有</b>审批 HTTP 面（{@code approval.http=false}）——0 不是合法端口，故它不会与"真的绑在 0"
   * 混淆。
   */
  public int approvalPort() {
    return approvalServer == null ? 0 : approvalServer.boundPort();
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
        "正在停止：A2A 任务/网关 → AG-UI 网关/注册表 → 调试对话 → 审批面 → 网关 drain → MCP 暴露/链接 → 子 Agent 编排 → 运行时 → 会话存储/事件存储 checkpoint");
    closeQuietly("A2A 任务服务", () -> a2aTaskService.close());
    closeQuietly("A2A 网关", () -> a2aServer.close());
    closeQuietly("AG-UI 网关", () -> aguiServer.close());
    closeQuietly("AG-UI 会话注册表", () -> aguiRegistry.close());
    closeQuietly("调试对话服务", debugChatServer::close);
    closeQuietly("调试对话", debugChatService::close);
    // S4-B2：审批面与既有网关同批关闭（先于 runtime/存储）。理由：它只服务"待人裁决"，关掉后不再接收新决议——
    // 在途的等待随 runtime 一起结束（最多按超时拒，fail-closed 语义不因关停而变化）；先关它也不会卡住后面的关停链
    // （HttpServer.stop(0) 立即返回，不等待在途 handler）。
    closeQuietly(
        "审批 HTTP 面",
        () -> {
          if (approvalServer != null) {
            approvalServer.close();
          }
        });
    closeQuietly("审批 tty 通道", approvalTtyChannel::close);
    closeQuietly("审批 HTTP 通道", approvalHttpChannel::close);
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

  /**
   * 审批事件落库镜像：只收 {@code approval.*}（其余类型由管线自己的 emit 落库，这里再落就是双写），失败只告警。
   *
   * <p><b>agent 字段为什么是 {@code main}</b>：审批事件在 B1 里以"基础设施事件"形态投出，不带调用者身份（bus 事件的 agent
   * 为空串），而审批请求本身也不经过 AgentPipeline。本接线里能触发审批的唯一调用者是主 Agent（子体白名单拿不到 bash， 见 {@code ShellTool}
   * 注册注释）⇒ 落 {@code main}。若日后子体能触发审批，这里必须换成真身份，否则审计会张冠李戴。
   */
  private static void mirrorApprovalEvent(EventStore events, Event event) {
    if (event.type() == null || !event.type().startsWith(APPROVAL_EVENT_PREFIX)) {
      return;
    }
    try {
      events.append(
          EventWrite.of(
              event.type(), MAIN_CONVERSATION_ID, event.payload(), event.correlationId()));
    } catch (RuntimeException failure) {
      LOG.warn("审批事件落库失败（不影响判定） type={} seq={}", event.type(), event.seq(), failure);
    }
  }

  /** 审批事件的类型前缀（B1 的 {@code ApprovalEventTypes} 只有两个常量，这里按前缀收口，不复制一份类型表）。 */
  private static final String APPROVAL_EVENT_PREFIX = "approval.";

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
