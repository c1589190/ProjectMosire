package io.mosire.main.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.ApprovalConfig;
import io.mosire.agentlib.approval.ApprovalConfigLoader;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalHttpEndpoint;
import io.mosire.agentlib.approval.AutoApproveGate;
import io.mosire.agentlib.approval.ConfirmGate;
import io.mosire.agentlib.approval.HttpApprovalChannel;
import io.mosire.agentlib.approval.LlmSuperiorJudgement;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.approval.SuperiorJudgeDetailRegistry;
import io.mosire.agentlib.approval.SuperiorJudgeGate;
import io.mosire.agentlib.config.ConfigStore;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.decision.DecisionCommitPort;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.DecisionPackagesHttpHandler;
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
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.RoleCatalogAudit;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.plugin.BuiltinToolSource;
import io.mosire.agentlib.plugin.HostServices;
import io.mosire.agentlib.plugin.PluginListener;
import io.mosire.agentlib.plugin.PluginToolSource;
import io.mosire.agentlib.proc.SubprocessManager;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.SandboxMask;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.context.BasicContextAssembler;
import io.mosire.brain.context.ContextPolicy;
import io.mosire.brain.context.ContextSources;
import io.mosire.brain.decision.DecisionApprovalBridge;
import io.mosire.brain.decision.DecisionCascade;
import io.mosire.brain.decision.DecisionHistoryTools;
import io.mosire.brain.permission.AgentRoles;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.AgentSpec;
import io.mosire.brain.runtime.EventTypes;
import io.mosire.brain.runtime.TurnResult;
import io.mosire.brain.skills.SkillAccess;
import io.mosire.brain.skills.SkillCatalog;
import io.mosire.brain.skills.SkillNamespaces;
import io.mosire.brain.skills.SkillPermissions;
import io.mosire.brain.skills.SkillSettings;
import io.mosire.brain.skills.SkillTools;
import io.mosire.brain.subagent.AgentCommand;
import io.mosire.brain.subagent.AgentContextReader;
import io.mosire.brain.subagent.AgentTemplate;
import io.mosire.brain.subagent.AgentTemplateStore;
import io.mosire.brain.subagent.SubProcessExecutor;
import io.mosire.brain.subagent.SubagentInstance;
import io.mosire.brain.subagent.SubagentLimits;
import io.mosire.brain.subagent.SubagentLimitsLoader;
import io.mosire.brain.subagent.SubagentManager;
import io.mosire.brain.subagent.SubagentOrchestrationTools;
import io.mosire.brain.tools.WorkingDirsLoader;
import io.mosire.docs.DocNamespaces;
import io.mosire.docs.DocsCapability;
import io.mosire.docs.DocsTools;
import io.mosire.main.Version;
import io.mosire.main.approval.CommandModeHttpHandler;
import io.mosire.main.approval.TtyApprovalChannel;
import io.mosire.main.decision.LocalDecisionCommitPort;
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
import io.mosire.main.setup.SetupHttpServer;
import io.mosire.main.setup.SetupLlmRouter;
import io.mosire.workspace.Capability;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.CapabilityCatalogs;
import io.mosire.workspace.DefaultWorkspaceRegistry;
import io.mosire.workspace.Kind;
import io.mosire.workspace.MaskEntry;
import io.mosire.workspace.Mount;
import io.mosire.workspace.MountName;
import io.mosire.workspace.Origin;
import io.mosire.workspace.Resolvers;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceEvent;
import io.mosire.workspace.WorkspacePermissions;
import io.mosire.workspace.WorkspaceRegistry;
import io.mosire.workspace.WorkspaceTools;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
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

  /** 技能工具的供给源 id（ToolRegistry 的来源归属；供审计与「按源整组卸载」用）。 */
  private static final String SKILL_TOOL_SOURCE_ID = "skills";

  /** Docs 工具（{@code doc_*} 七个）的供给源 id（R3b）。 */
  private static final String DOCS_TOOL_SOURCE_ID = "docs";

  /**
   * 工作区工具（{@code mount_workspace}/{@code unmount_workspace}/{@code list_workspaces}）的供给源 id（R3b）。
   */
  private static final String WORKSPACE_TOOL_SOURCE_ID = "workspace";

  /**
   * builtin 供给源 id（{@code BuiltinToolSource} 的来源归属）：既有编排工具（{@code wireSubagents}）与 RB2a
   * 的决策包提议工具共用它。
   *
   * <p>提成常量只为"同一个源名不在两处各写一遍"——值恒为 {@code "builtin"}，注册与"按源整组卸载"的口径逐字不变。
   */
  private static final String BUILTIN_TOOL_SOURCE_ID = "builtin";

  /**
   * 决策历史工具的供给源 id（P2b，{@code decision_history}）：与内置工具分开，便于按源整组卸载与审计。
   *
   * <p>它是<b>读类</b>工具（只读提出者自己的 {@code events.db}），却属于"决策包这一族"——源名按族切，不按读写切。
   */
  private static final String DECISION_HISTORY_TOOL_SOURCE_ID = "decision-history";

  /**
   * 技能根的能力 id（R2→R3b 交接项 2）：<b>只含 {@code skills: READ}</b>。
   *
   * <p>为什么不复用 {@code {skills, skills-edit}} 或直接声明 {@code skills} 能力：技能根的用途是"bash 侧只读/遮蔽"这一条， 而
   * {@code SandboxMask} 的 G7 是<b>逐条目求与</b>——多一条 {@code skills-edit} 条目就会把"可读不可改"的调用者一并遮成不可见 （R1
   * 之前的语义正是"读得到就只读 bind"）。名字带 {@code -read} 是刻意的：将来技能重基（R6）注册的 {@code docs} 能力 不会与它撞名（同名不同义会让
   * {@code CapabilityCatalogs} 响亮失败）。
   */
  private static final String SKILLS_READ_CAPABILITY_ID = "skills-read";

  /** 技能根的虚拟挂载名（技能树在 bash 遮蔽面里的那条路径由它承载）。 */
  private static final String SKILLS_MOUNT = "skills";

  /** 主 Agent 自己工作区的虚拟挂载名（子体的 {@code /workspace} 沿用同一个名字，W5：虚拟命名自洽）。 */
  private static final String WORKSPACE_MOUNT = "workspace";

  /** 子体工作区根的虚拟挂载名（装配期声明 ⇒ 整棵工作区树进遮蔽面）。 */
  private static final String WORKSPACES_MOUNT = "workspaces";

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
   * 决策包登记表（RB2a，设计 §5.9）：进程内<b>唯一</b>实例，装配层持有、注入提议工具。
   *
   * <p>RB2b（审批面投递）与 RB3（事件/HTTP 面）从本类取<b>同一个</b>实例——不造第二个表、不设静态单例： 两个表就会出现"这个包里有什么"的两个答案（同 {@code
   * PendingApprovals} 的装配口径）。
   */
  private final DecisionPackages decisionPackages;

  /**
   * 插件面（工作束四）：{@code null} = 未装配（插件目录未启用/不存在——"没装插件"是正常部署态，设计 D4.4）。
   *
   * <p>非 null 时持有 {@link PluginToolSource}：它装载的插件工具已登记进全局工具目录，生命周期归本类（close = 逐插件 摘工具 + 调插件 {@code
   * close()} + 卸载类加载器）。
   */
  private final PluginToolSource pluginTools;

  /**
   * 审批 HTTP 面（S4-B2）：{@code null} = 未启用（{@code approval.http=false}）。
   *
   * <p>迁移后（设计 §七 P1-C）用 AgentLib 的通用件 {@link ApprovalHttpEndpoint}——Main 只负责装配与 {@code
   * /api/commands/mode} 薄包装（{@link CommandModeHttpHandler}）。它与 {@link #approvalHttpChannel}
   * 是同一个面的两面——server 是"人的入口"，channel 是"编排器的等待口"，两者共享 {@code App} 里那<b>唯一</b>一份 {@code
   * PendingApprovals}。
   */
  private final ApprovalHttpEndpoint approvalServer;

  /** tty 审批通道（S4-B2）：无控制终端时 {@code available()==false}（装配照做，可用性由它自己如实报）。 */
  private final TtyApprovalChannel approvalTtyChannel;

  /** HTTP 审批面在编排器侧的那条通道（S4-B2；{@code approval.http=false} 时恒不可用）。 */
  private final HttpApprovalChannel approvalHttpChannel;

  /**
   * 配置引导面（{@code --setup}；未开旗标时 {@code null}）：{@code GET /api/setup/status} + {@code POST
   * /api/setup/llm}（探测 → 写盘 → 热切换，见 {@code 设计-配置引导.md} D4~D6）。
   *
   * <p>装配条件是<b>两连</b>：{@code config.setup()} 且注入的 LLM 是 {@link SetupLlmRouter}（未配置态）—— 防止"带 {@code
   * --setup} 却给了 {@code --fake}"之类的组合把引导面架在假模型上（那样 status 会谎报可引导）。
   */
  private final SetupHttpServer setupServer;

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
    // 只认<b>前缀白名单</b>（管线自己的 emit 已落库，不会双写）；落库失败只告警，不改判定（同 B1 的审计口径）。
    // ★ RB3（设计 §5.13(c)）：白名单必须含 decision.package.*——决策包的四个事件由桥投到 bus，
    //   而这里过去只收 approval.* ⇒ 事件会<b>静默消失</b>（不报错、没有用例会红），这是 RB3 的真缺陷之一。
    // ★ role.audit：装配期角色审计的留痕（同 §5.13(c)：它在装配失败抛异常<b>之前</b>经本订阅者同步落库）。
    bus.subscribe(event -> mirrorInfrastructureEvent(events, event));
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
    // S4-B2：审批面资源声明在 try 之外（同其余网关口径）——装配中途失败时 catch 里要回收已起的那部分
    ApprovalHttpEndpoint approvalServer = null;
    TtyApprovalChannel approvalTtyChannel = null;
    HttpApprovalChannel approvalHttpChannel = null;
    ExecutorService chatExecutor = null;
    SubagentManager subagentManager = null;
    SubprocessManager subagentProcesses = null;
    PluginToolSource pluginTools = null;
    SetupHttpServer setupServer = null;
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
      // 工作束四（插件化）：bash 工具的唯一供给路径 = PF4J 插件（PluginToolSource）。
      // 主 Agent 是 system() 权限集 ⇒ spec() 的 sensitive+destructive 位天然放行（用户裁决"全功能"）；
      // 子体拿不到它——出厂模板 allowedTools 不含 bash（S5 的裁决面）。工具未标 noExport ⇒ 会进 MCP 外发面
      // （有意的：子体的白名单挡着，启动日志会报出实际外发面）。bash 的 tools.bash.* 配置由插件在 init 里
      // 装载期校验（坏值 ⇒ 装载失败 ⇒ 启动响亮失败），宿主不再读它（D5：宿主不认识 bash 的配置 schema）。
      pluginTools = wirePlugins(configStore, tools, events);
      ApprovalConfig approvalConfig = ApprovalConfigLoader.load(configStore);
      PendingApprovals pendingApprovals = new PendingApprovals();
      approvalTtyChannel = new TtyApprovalChannel(pendingApprovals);
      approvalHttpChannel = new HttpApprovalChannel(pendingApprovals);
      // S6：LLM 的装配点<b>上移</b>到审批装配之前——上级判定闸（SuperiorJudgeGate）要用同一个客户端跑一次性判定。
      // 供应商客户端由 Main.selectLlm 经 llmOverride 注入（生产 = 真模型；App.start 直调无覆盖 = 离线骨架）。
      LlmClient llm = scriptedLlm(config, llmOverride);
      // 配置引导面（设计 D4）：仅 --setup 且注入的是未配置态路由器时装配（防 --setup --fake 组合谎报可引导）
      if (config.setup() && llmOverride instanceof SetupLlmRouter router) {
        setupServer = SetupHttpServer.start(config.setupPort(), config.dataDir(), router);
        LOG.info(
            "配置引导面已装配：http://127.0.0.1:{}（GET /api/setup/status，POST /api/setup/llm）",
            setupServer.port());
      }
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
      // 第 8 维（角色，设计 §4.5）：主 Agent 的角色来自配置 agent.roles——**缺省 ⊥（谁都不是，fail-closed）**：
      // 没配角色的部署拿不到"角色门"工具，而不是把它们全开；项存在但值非法（不是数组 / 含空白名）⇒ 启动期响亮失败（E_AGENT_ROLES）。
      // 挂在这一层而不是 AgentPermissionSet.system() 里：system() 表达"运行时自身、本维不表态"（语义不动），
      // 从配置读出的身份是**装配层**的事实。角色名不进日志（明文面纪律），这里只报项数。
      RoleSet agentRoles = AgentRoles.load(configStore);
      // ★ 外部 MCP 面的调用者令牌级别（2026-10-16 用户裁定「我想了一下还是GUEST吧，然后搞个配置文件，用户想配置System
      //   就配置System，System等级留着有用」）：缺省 GUEST = 既有行为逐字不变；配了就按配置（值非法 ⇒ 启动响亮失败）。
      AccessToken externalMcpToken = AgentExternalMcpToken.load(configStore);
      AgentPermissionSet permissionSet =
          AgentPermissionSet.system()
              .withResourceScopes(ResourceScopeMap.of(ResourceScopeMap.FS, workingDirs))
              .withRoles(agentRoles);
      LOG.info("主 Agent 工作目录围栏: {}（配置 agents.workingDirs；* = 不限，子体逐跳只减不增）", workingDirs.summary());
      LOG.info(
          "主 Agent 角色维: 角色数={}（配置 agent.roles；缺省 0 = 谁都不是 = 角色门工具全拒；角色名不落日志）",
          agentRoles.roles().size());
      // ★ 技能能力装配（本能力）：配置读取 → 目录快照 → 主 Agent 的两个技能面 → 工具面注册 → 上下文来源。
      //   主 Agent 是技能根的所有者：可见面与可编辑面都是配置的根（要收窄就在配置里换根/换 agents 权限，不在代码里另设缺省）。
      SkillSettings skillSettings =
          SkillSettings.load(configStore, config.dataDir().resolve("skills"));
      SkillCatalog skillCatalog = new SkillCatalog(skillSettings.listingBudget());
      // 宽容扫描 + WARN：坏技能只影响它自己（列表/检索结果的 broken 字段会点名披露，见 SkillTools），
      // 不把整个 runtime 的启动绑在一个内容文件上；写入路径有校验，正常路径造不出坏文件
      SkillCatalog.ScanReport scanReport = skillCatalog.scanTolerant(skillSettings.root());
      if (!scanReport.broken().isEmpty()) {
        LOG.warn("技能目录里有坏技能（已跳过，工具结果里会披露）: {}", scanReport.broken());
      }
      ResourceScope skillRoot = ResourceScope.ofDir(skillSettings.root());
      permissionSet = SkillPermissions.withScopes(permissionSet, skillRoot, skillRoot);
      for (AgentTool skillTool : SkillTools.all(skillCatalog, skillSettings.root())) {
        tools.register(SKILL_TOOL_SOURCE_ID, skillTool);
      }
      SkillAccess skillAccess = SkillAccess.of(permissionSet, skillCatalog, skillSettings.root());
      LOG.info(
          "技能面已装配: root={} 可见={} 可编辑={} 目录档预算={}字符 预载={} 工具={}",
          skillSettings.root(),
          skillRoot.summary(),
          skillRoot.summary(),
          skillSettings.listingBudget(),
          skillSettings.preloaded(),
          SkillTools.NAMES);
      // ★★ 工作区标准 + Docs 装配（R3b，设计 §4.7/§4.8/§5.7）★★
      // 顺序写死：主 Agent 的权限集先补 workspaces 前缀（R2→R3b 交接项 1）→ 能力目录 → 注册表 → 挂装配期挂载 → 落格
      // → 注册两族工具。原因：M1 对装配期挂载同样生效，"不表态 ⇒ 一个挂载都建不了"是有意的 fail-closed，
      // 所以 workspace 前缀必须在第一次 mount 之前就在权限集里。
      Path workspacesRoot = config.dataDir().resolve("workspaces").toAbsolutePath().normalize();
      Path mainWorkspace = workspacesRoot.resolve(agentConfig.id());
      Path skillsRoot = skillSettings.root().toAbsolutePath().normalize();
      // ① workspaces 面 = <b>装配期挂载的落点根集合</b>（工作区根 + 技能根）：
      //    a) 交接项 1 要求"显式表态"（不表态 ⇒ M1 拒掉一切挂载，含装配期挂载——有意的 fail-closed）；
      //    b) 只写 <dataDir>/workspaces 会把<b>技能根</b>的装配期挂载一并拒掉（实测命中 M1：技能根不在工作区根之下），
      //       所以面必须覆盖"部署声明的每一个静态根"，而不是一个想当然的公共父目录；
      //    c) 前缀集合只列这两个根——不把整个 dataDir（events.db / config.json / subagents 都在里面）放进来。
      ResourceScope assemblyMountRoots =
          ResourceScope.of(
              WorkspacePermissions.directoryPrefix(workspacesRoot),
              WorkspacePermissions.directoryPrefix(skillsRoot));
      // ② docs / docs-edit 面也要<b>先表态再挂载</b>：M1/M3 的第二半判的是"能力落格后仍 ⊆ 调用者面"，
      //    而主 Agent 的文档面在装配期本来就没有来源（它不是从谁那里继承来的）——装配方就是它的来源。
      //    面 = 工作区树根（主 Agent 自己的工作区在其下），与三个装配期挂载的落点一致（grant 时 A5 再核一遍：
      //    若这里有显式表态却不覆盖落点，挂载/落格会响亮拒，不会静默放大）。
      ResourceScope assemblyDocs =
          ResourceScope.of(WorkspacePermissions.directoryPrefix(workspacesRoot));
      permissionSet =
          WorkspacePermissions.withNamespace(
              permissionSet, WorkspacePermissions.WORKSPACES, assemblyMountRoots);
      permissionSet =
          WorkspacePermissions.withNamespace(permissionSet, DocNamespaces.DOCS, assemblyDocs);
      permissionSet =
          WorkspacePermissions.withNamespace(permissionSet, DocNamespaces.DOCS_EDIT, assemblyDocs);
      CapabilityCatalog capabilities = CapabilityCatalogs.inMemory();
      DocsCapability.register(capabilities);
      // R2→R3b 交接项 2：技能根的 bash 遮蔽语义靠"一个只含 skills: READ 的能力"保住——**不是**
      // {skills, skills-edit} 双命名空间：G7 是逐条目求与，双条目会把"可读不可改"的调用者一并遮成不可见。
      capabilities.register(
          Capability.of(
              SKILLS_READ_CAPABILITY_ID,
              Map.of(SkillNamespaces.SKILLS, Set.of(Operation.READ)),
              false,
              Set.of()));
      // 血缘真值住在 Brain（实例记录），工作区模块不得依赖 Brain（设计 §3）⇒ 装配层用一个索引把两者接起来：
      // 注册表读它判 M2/M5（"user 是不是自己或后代"），spawn 侧在挂载<b>之前</b>登记拟建子体的路径（见 SubagentManager）。
      Map<String, String> lineageIndex = new ConcurrentHashMap<>();
      lineageIndex.put(agentConfig.id(), agentConfig.id());
      DefaultWorkspaceRegistry workspaceRegistry =
          DefaultWorkspaceRegistry.create(
              capabilities, event -> recordWorkspaceEvent(events, event), lineageIndex::get);
      // 装配期挂载（Origin.ASSEMBLY）：只有这一档进 bash 遮蔽面（M6）。三个都是"部署声明的静态路径"：
      // ① 技能根（REFERENCE，只读引用：shell 只能只读看见 / 看不见，永不写）——它同时就是 R1 那条第 496 行字面的替代物；
      // ② 主 Agent 自己的工作区（FILE + docs 能力：文档经 doc_* 工具读写，shell 侧按 v1 遮蔽）；
      // ③ 子体工作区根（REFERENCE + docs：spawn 时子工作区就划在它下面，且整棵工作区树因此进入遮蔽面）。
      // ★ derivedFrom = empty（设计 §4.3 / §4.10 M10）：三个装配期挂载彼此<b>独立</b>——② 落在 ③ 的目录里只是"物理嵌套"，
      // 不是派生关系；级联失效只沿 derivedFrom 链传递，因此卸载 ③ 不会把 ② 一起带走（R5 实测过按物理包含判的误伤）。
      WorkspaceCaller mainCaller = new WorkspaceCaller(agentConfig.id(), permissionSet);
      workspaceRegistry.mount(
          new Mount(
              new MountName(SKILLS_MOUNT),
              agentConfig.id(),
              agentConfig.id(),
              skillsRoot,
              Resolvers.direct(),
              Kind.REFERENCE,
              Set.of(SKILLS_READ_CAPABILITY_ID),
              Origin.ASSEMBLY,
              Optional.empty()),
          mainCaller);
      workspaceRegistry.mount(
          new Mount(
              new MountName(WORKSPACE_MOUNT),
              agentConfig.id(),
              agentConfig.id(),
              mainWorkspace,
              Resolvers.direct(),
              Kind.FILE,
              Set.of(DocsCapability.ID),
              Origin.ASSEMBLY,
              Optional.empty()),
          mainCaller);
      workspaceRegistry.mount(
          new Mount(
              new MountName(WORKSPACES_MOUNT),
              agentConfig.id(),
              agentConfig.id(),
              workspacesRoot,
              Resolvers.direct(),
              Kind.REFERENCE,
              Set.of(DocsCapability.ID),
              Origin.ASSEMBLY,
              Optional.empty()),
          mainCaller);
      // 落格（§4.8：能力只在挂载/授予时落成"命名空间 + 前缀"）：docs ← <工作区>、docs-edit ← <工作区>、
      // workspaces ← <工作区根>、skills ← <技能根>。A5 会拒"已有显式表态却不覆盖落点"的命名空间（响亮，不静默放大）。
      permissionSet = workspaceRegistry.grant(agentConfig.id(), permissionSet);
      // 两族工具（Docs 8 个 + 工作区 4 个，含自述工具 doc_guide / workspace_info）：供给源 id 分开，便于审计与"按源整组卸载"
      for (AgentTool docTool : DocsTools.all(workspaceRegistry)) {
        tools.register(DOCS_TOOL_SOURCE_ID, docTool);
      }
      for (AgentTool workspaceTool : WorkspaceTools.all(workspaceRegistry)) {
        tools.register(WORKSPACE_TOOL_SOURCE_ID, workspaceTool);
      }
      LOG.info(
          "工作区与 Docs 已装配: 工作区根={} 主工作区={} 技能根={} 装配期挂载={} 遮蔽面条目={} 工具={}+{}",
          workspacesRoot,
          mainWorkspace,
          skillsRoot,
          workspaceRegistry.mounts(agentConfig.id()).size(),
          workspaceRegistry.maskEntries().size(),
          DocsTools.NAMES,
          WorkspaceTools.NAMES);
      // S4-C 接线：快判链从空表改成设计 §2.3 的形状（DenyGate 不装——没有配置黑名单来源，硬拒由分类器的 Block 档
      // 在 authorizer 里终局，不该有第二份清单）。AutoApproveGate 是"会话级放行第二次不再问"的<b>唯一</b>读取方：
      // 不装它，APPROVE_SESSION 只会被登记而永远不会被读（V4 会红），且没有任何用例会报警。
      // S6 追加 SuperiorJudgeGate：AutoApproveGate <b>在前</b>是刻意的——会话级放行是人批的口子（用户裁决
      // "LIMITED 档下只认人批的会话"），人批过的类别不该再让上级 Agent 判一遍；代批闸只覆盖剩下的档位提升请求，
      // 敏感区与外部面它自己会让开（落到 ConfirmGate，即到人）。
      // ★ RB3（设计 §5.13(d)(e)）：上级判定的<b>逐条明细缝</b>——它让上级判定看得见包内条目的明文参数（不是只看"几条、
      //   哪些工具"就批）。★ 同一个实例必须也交给 DecisionApprovalBridge（见下方 6 参构造）：桥按"明细缝在不在"
      //   决定要不要把包交给上级代批（只给判定面、不给桥 ⇒ 桥登记不了明细，包退回"只到人"；给了桥、没给判定面 ⇒ 上级盲判）。
      SuperiorJudgeDetailRegistry decisionJudgeDetails = new SuperiorJudgeDetailRegistry();
      ApprovalCoordinator approvalCoordinator =
          new ApprovalCoordinator(
              List.of(
                  new AutoApproveGate(pendingApprovals),
                  new SuperiorJudgeGate(
                      new LlmSuperiorJudgement(
                          llm, LlmSuperiorJudgement.DEFAULT_TIMEOUT, decisionJudgeDetails),
                      commandMode::get),
                  new ConfirmGate()),
              List.of(approvalTtyChannel, approvalHttpChannel),
              pendingApprovals,
              approvalConfig.timeout(),
              bus,
              // ★ P2b（设计 §5ter 裁定 13 的口径面）：会话放行的<b>收窄缝</b>——包级请求（callerKey = pkg:…）一律
              //   不接受会话放行："一个包一次的裁决"不许变成"以后每个包都免问"（见 {@link #sessionGrantable}）。
              //   这是 ApprovalCoordinator 既有构造器的既有参数（那个类一个字都不用改）。
              App::sessionGrantable);
      ToolCallAuthorizer approvalAuthorizer =
          ToolCallAuthorizer.of(new ToolExecutionGuard(), approvalCoordinator);
      if (approvalConfig.http()) {
        approvalServer =
            ApprovalHttpEndpoint.start(
                approvalConfig.httpPort(), pendingApprovals, approvalCoordinator);
        // S6 档位端点挂在<b>同一台</b> server、同一端口上（AgentLib 通用件只做审批；档位逻辑留 Main 薄包装，
        // 不另起第二台 server——设计 §五 A3 裁决）。
        CommandModeHttpHandler.register(approvalServer, commandMode);
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
      // ★ RB2a/RB2b（设计 §5.9 ① ② ③）：决策包<b>包层</b>（包登记表）与<b>审批面投递桥</b>
      //   （一个包 = 一条 ApprovalRequest，答复回填统一裁决）。
      //   ★ 模型面的提议工具 decision_propose 已随裁定 6 移除（2026-10-16）：本段只装配"包层 + 桥 + 决策面"。
      //   ★ P1b（裁定 1+2）：建包入口就是本段的桥 —— 下面把它交给 AgentRuntime（→ AgentPipeline），管线在回合收尾
      //     把本轮的待裁决队列收成一个包（decisionApprovalBridge#flush：propose 无校验器重载 → 同步投递 → 等裁决）。
      //   装配顺序刻意排在审批面<b>之后</b>：桥要拿的正是上面刚建好的那两个实例——同一份 PendingApprovals、
      //   同一个 ApprovalCoordinator（不新建第二张表 / 不新建通道 / 不另起 HTTP 端点族，设计 §5.4 的验收判据）。
      //   DecisionPackages 随 App 字段持有（见 App#decisionPackages），RB2b/RB3 取同一个实例——不造静态单例。
      DecisionPackages decisionPackages = new DecisionPackages();
      // ★ D30（P2a 发现 C 的落点）：工具自身配置经 ToolContext.config 注入，这条缝的两条路（管线 / 提交口）必须
      //   拿<b>同一份</b>——早先提交口传的是 Map.of()，于是它成了"技能遮蔽面 / 子库定位"的绕过点。挪到决策块之前建：
      //   提交口（LocalDecisionCommitPort）在下面就要用它。键名归 Brain（AgentContextReader 常量）与 AgentLib。
      Map<String, Object> toolConfig = new LinkedHashMap<>();
      toolConfig.put(
          AgentContextReader.CONFIG_SUBAGENTS_ROOT,
          config.dataDir().resolve("subagents").toString());
      toolConfig.put(
          AgentContextReader.CONFIG_SELF_EVENTS_DB,
          config.dataDir().resolve("events.db").toString());
      toolConfig.put(AgentContextReader.CONFIG_SELF_AGENT_ID, agentConfig.id());
      // S6：档位持有者经同一个运行时缝下发（AgentPipeline 建调用上下文时现读它定主 Agent 的身份/档位）
      toolConfig.put(CommandModeHolder.CONFIG_KEY, commandMode);
      // 遮蔽面（R3b 起由工作区注册表产出）：文件工具（bash 的 L3 沙箱）据此决定"这条路径遮蔽 / 只读暴露"——
      // 这是资源边界在文件工具一侧的唯一数据入口（判定见 SandboxMask，逐条目求与）。
      // ★ 这里不再写死 ("skills", <技能根>) 字面：技能根本身就是一个装配期 REFERENCE 挂载，它的条目由
      //   WorkspaceRegistry.maskEntries() 连命名空间一起产出（M6：只返回装配期条目；运行期挂载不进遮蔽面）。
      toolConfig.put(SandboxMask.CONFIG_KEY, maskConfigOf(workspaceRegistry));
      toolConfig = Map.copyOf(toolConfig);
      // ★ P2b（设计 §5ter 裁定 12 / §5quater）：逐级预裁定。本单只接<b>主进程内</b>那条链——提出者到主 Agent 这一级；
      //   子 Agent 跨进程那条路（每个子体链接一个实例级队列、父侧在其回合末 flush）<b>未做</b>，缺口写在 P2b 报告里。
      //   链上的每一级给的是"它的权限集 + 现读档位 + 父级 id"：能力判据（权限面 ∩ 资源面 ∩ FULL 档）全在这三者上。
      AgentPermissionSet cascadePermissions = permissionSet;
      DecisionCascade decisionCascade =
          new DecisionCascade(
              instanceId ->
                  AgentIdentity.MAIN_ID.equals(instanceId)
                      ? Optional.of(
                          new DecisionCascade.Level(
                              AgentIdentity.MAIN_ID, cascadePermissions, commandMode.get(), ""))
                      : Optional.empty(),
              tools::find);
      // ★ 本机形态的裁决包执行者（用户裁定 U10，设计 §5.3.1）：裁决之后由它逐条执行被批子集（调各条目自己的工具、
      //   走纯判定链 + 资源前置闸——裁决即授权，执行时不再过一次审批闸），把逐条执行结果收成一份统一回执。
      //   世界形态（隔壁 RC1）是同一个 SPI 的另一个实现：一次提交进世界 + 返回不透明 revisionRef。
      //   ★ 诚实边界：本机没有版本语义 ⇒ **无原子性保证**（逐条落、中途失败即部分落盘），逐条去向与原因写进回执。
      // ★ 取一次快照给执行者用：permissionSet / commandMode 在本装配块里后面还会被重新赋值（lambda 捕获不到非
      //   effectively-final 的局部变量）。"执行时以谁的身份跑"取的是装配完成后的那两份实例。
      AgentPermissionSet executingPermissions = permissionSet;
      CommandModeHolder executingMode = commandMode;
      Optional<DecisionCommitPort> decisionCommitPort =
          Optional.of(
              new LocalDecisionCommitPort(
                  tools,
                  // ★ P2a（"执行这一跳"）：执行只过纯判定链——裁决即授权，不再过一次审批闸（判定规则仍只有
                  //   ToolExecutionGuard 一处）。guard 由装配层注入、不在提交口里 new：那是本类看不见的隐藏依赖。
                  new ToolExecutionGuard(),
                  // ★ P2b（裁定 13 补）：判据链补齐资源面，并把<b>同一份</b> toolConfig 注入提交口（P2a 发现 A/C）
                  toolConfig,
                  // ★ P2b（裁定 11）：执行身份<b>按当次终局裁决者现取</b>，不是写死主 Agent。
                  packageId ->
                      executingIdentityOf(
                          packageId, executingPermissions, executingMode, decisionCascade)));
      // ★ RB3：6 参构造 = 上事件总线（不接 ⇒ decision.package.* 四类一条都不发）+ 上明细缝（不接 ⇒ 包退回"只到人"）。
      // ★ P1b（裁定 1+2+4）：这个桥是"Ask 自动进包"这条链路的最后一环——本块下面把它交给 AgentRuntime
      //   （→ AgentPipeline），管线在回合收尾调 bridge.flush(本轮队列)：propose（无校验器重载）→ 同步投递 → 等裁决
      //   → 被批子集执行。**不交给运行时**就等于"建了桥没人用"，那正是"敏感调用悄悄不执行"的形状。
      // ★ P2b：7 参构造 = 再上逐级预裁定（裁定 12 / §5quater）——桥按它决定"哪些条目还要问人"。
      DecisionApprovalBridge decisionApprovalBridge =
          new DecisionApprovalBridge(
              decisionPackages,
              approvalCoordinator,
              pendingApprovals,
              decisionCommitPort,
              bus,
              Optional.of(decisionJudgeDetails),
              Optional.of(decisionCascade));
      // ★ 裁定 6（2026-10-16，用户原话「一旦工具符合条件就是自动进包」）：显式提议口 decision_propose 已<b>移除</b>
      //   ——工具面不再有它。进包口径 = 全部 ToolGate.Ask（裁定 1）：命中那一刻由 ToolCallAuthorizer 登记进本轮队列
      //   （不执行、不阻塞），回合收尾由上面的桥收成一个包（裁定 2）+ 同步投递（裁定 4）。
      // ★ RB3（设计 §5.13(f)）：决策面挂在<b>同一台</b>审批 server、同一端口上（不新起 server、不新造端点族）。
      //   POST 走桥的回填路径（DecisionSubmissionPort）：事件照发、提交口照调——直调状态机会让这两件事都不发生。
      //   ★ P2b：多传一个"上报视图"——列表只列上报给人的条目、逐条打 escalated 标记（§5quater ④）。
      if (approvalServer != null) {
        DecisionPackagesHttpHandler.register(
            approvalServer,
            decisionPackages,
            decisionApprovalBridge,
            packageId -> decisionApprovalBridge.escalatedItems(packageId).orElse(null));
      }
      // ★ P2b（裁定 5 的底线「下一轮要看结果直接调工具看」）：查自己的决策历史——读提出者自己的 events.db，
      //   按 proposerKey 只列自己的包（别人的与不存在的返回同一个空结果，无存在性 oracle）。
      tools.register(DECISION_HISTORY_TOOL_SOURCE_ID, DecisionHistoryTools.tool());
      LOG.info(
          "决策包面已装配：裁决窗口={}ms（包缺省 deadline，裁定 10） 审批桥=已接（一条包=一条审批请求 classKey={}）"
              + " 提交口={} 事件面=已接（decision.package.* 四类） 决策面={} 上级代批={}",
          DecisionPackages.DEFAULT_DEADLINE_MILLIS,
          DecisionApprovalBridge.CLASS_KEY,
          decisionCommitPort.isPresent() ? "已装配（本机形态=就地执行被批子集）" : "未装配（裁决后包保持 DECIDED）",
          approvalServer == null
              ? "未启用（approval.http=false）"
              : "http://127.0.0.1:" + approvalServer.boundPort() + DecisionPackagesHttpHandler.PATH,
          "已装配（明细缝在，包可交上级代批）");
      // W3b：子 Agent 编排装配（模板目录非空时才启用——计划 §4.4 + W3；编排工具先于暴露快照注册进工具面）
      // ★ RA4（设计 §4.6）：模板库在这里<b>只装载一次</b>——既供 SubagentManager 用，也供紧随其后的装订期角色审计取
      //   declaredRoles。上移只让审计看得见同一份快照；装载时机与失败语义（坏模板 / 缺目录 ⇒ 启动响亮失败）逐字不变。
      AgentTemplateStore templateStore = null;
      if (config.templatesDir() != null) {
        templateStore = new AgentTemplateStore(config.templatesDir());
        templateStore.load();
        SubagentRig rig =
            wireSubagents(
                config,
                templateStore,
                tools,
                events,
                bus,
                agentConfig,
                permissionSet,
                subagentConfigDir,
                approvalAuthorizer,
                commandMode,
                subagentLimits,
                skillSettings.root(),
                // D2：技能目录档预算与技能根**同源**下发（同一个 skillSettings）——子体此前恒用 SkillCatalog 缺省 4096，
                // 父侧配的 skills.listingBudget 在子进程里失效
                skillSettings.listingBudget(),
                workspaceRegistry,
                // M2 要判"user 是不是调用者的后代"，而这份血缘真值住在 Brain（实例记录）；
                // 装配层把它接进注册表的取数缝（注册表不得依赖 Brain，设计 §3）
                lineageIndex::put);
        subagentManager = rig.manager();
        subagentProcesses = rig.processes();
      }
      // ★ RA4（设计 §4.6「装订期：派生 + 审计」，不变量 A-4 / A-5）：注册表在这一点已是"对外服务前的完整面貌"
      //   （插件 / 技能 / docs / 工作区 / 决策包提议工具 / 编排工具都已注册），把角色维扫一遍：
      //   ① 未登记角色名 ⇒ 下面响亮失败（拼错的角色 = 永远不可达的工具，"漂移无症状"）；
      //   ② 声明了角色但没有任何调用者配置能覆盖 ⇒ 一行报告（不失败）；
      //   ③ 派生 ⇔ 手抄对账只在宿主两样都给了时做——本装配今天不提供派生函数与手抄清单 ⇒ 跳过（不是"空跑通过"）。
      //   已知角色全集 = 主 Agent 角色（agent.roles）∪ 在场模板的 declaredRoles ∪ 外部 MCP 面角色（今天 = ⊥ = 空集）。
      Set<String> knownRoles = new TreeSet<>(agentRoles.roles());
      if (templateStore != null) {
        for (AgentTemplate template : templateStore.all().values()) {
          // null = 模板对本维不表态（不额外收窄），不是"谁都不是"——与 SubagentManager 的求交口径同源
          RoleSet declaredRoles = template.declaredRoles();
          if (declaredRoles != null) {
            knownRoles.addAll(declaredRoles.roles());
          }
        }
      }
      // 外部 MCP 面（下方 AgentToMcpServer 的 ToolContext）今天显式表态为 RoleSet.none()（⊥）⇒ 贡献空集。
      // 将来该面配了角色必须并进这里：否则声明了该角色的工具会被报成"没有调用者能覆盖"（报告失真，不是安全洞）。
      Set<String> externalMcpRoles = Set.of();
      knownRoles.addAll(externalMcpRoles);
      RoleCatalogAudit.Report roleAudit = RoleCatalogAudit.of(tools, knownRoles).run();
      // ★ RA4 补事件（2026-10-16 用户裁定「补上吧」；设计 §4.6 / §5.13(c)）：审计的结论要留痕，且必须在
      //   「失败 ⇒ 抛异常阻止启动」之前发——否则"启动没通过"这件事本身反而没有痕迹。
      //   ★ 走 events.append（同步）而不是 bus.publish：bus 是异步分派，而 App.close 的顺序是
      //   events.close() 先于 bus.close()，走总线会在关停窗口里丢；这条事件存在的唯一目的就是留痕。
      appendRoleAuditEvent(events, roleAudit);
      if (roleAudit.hasFailures()) {
        // 沿用本文件既有的装配失败口径：RuntimeException 原样上抛 → 主 catch 回收已装配资源 → CLI 非零退出（不新造一套）
        throw new IllegalStateException(
            "装订期角色审计失败（未登记角色名 / 目录对账不符；设计 §4.6 不变量 A-4/A-5）："
                + String.join("；", roleAudit.failures()));
      }
      // 成功恒一行 INFO（模板目录缺失时把"未并入模板角色"与摘要并进同一行，避免多一行噪音）；
      // 报告项（报告 = 不是失败）另起一行 WARN 并点名——角色名可进装订期审计 / 运维日志（§4.3.1 的面的分工）。
      if (templateStore == null) {
        LOG.info("{}（模板目录未配置，未并入模板角色）", roleAudit.summary());
      } else {
        LOG.info("{}", roleAudit.summary());
      }
      if (!roleAudit.uncoveredRoleFindings().isEmpty()) {
        LOG.warn("装订期角色审计（报告，不失败）: {}", roleAudit.uncoveredRoleFindings());
      }
      // ★ 可被提议面报告已随标记位一并移除（裁定 6；2026-10-16）：进包口径变成"工具符合条件就自动进包"，
      //   "哪些工具声明了 DecisionProposable"这一问不再存在（标记接口与 PROPOSABLE_NAMES 都已删）。
      //   残留的"整套决策包机制不可达却毫无报错"这个风险由 P1b 的队列装配覆盖（那里是唯一建包入口）。
      // W2 步骤 2：主 Agent 工具面经 stdio MCP server 暴露。
      // S4-B2：带 authorizer 的重载（V8 的结构前提——子体经 MCP 调父级工具时审批同样生效）；
      // ★ RB3/A-6（设计 §5.13(g)）：可见面与判定面**同源**——`include` 谓词只调 PermissionChecker.allows 这一个
      //   oracle（与子体桥 SubProcessExecutor 同一个谓词，不抄第二份判定式），被裁掉的数量落日志（不静默消失）。
      //   实测：缺省 GUEST 令牌下该面 19 个工具**全部**不通过——不是"收窄造成的损失"，而是它本来就一个都执行不了
      //   （生产工具逐个把级别提到 ToolSpec.level(AccessToken.DEFAULT, …)，卡在"身份级别不足"那道闸）。
      //   要让它能用，把 agent.externalMcp.token 配成 DEFAULT/SYSTEM；那**不改**本谓词的形状（可见面跟着判定面走）。
      // 注意 GUEST 桶 = 全体外部 MCP 客户端（桶粒度分不出实例）⇒ 人在 HTTP 上给 GUEST 批"本会话"会被收窄为一次，
      // "下次还问"是<b>设计</b>（S5 身份穿透前不许泛化），不是 bug——别为让 session 生效去动 callerKey。
      AgentPermissionSet externalMcpPermissions =
          AgentPermissionSet.unrestricted(externalMcpToken)
              // 第 8 维（角色，设计 §4.5）：外部 MCP 面**显式表态为 ⊥**（一个角色都不是）⇒ 角色门工具对该面全拒。
              // ★ 注意这不是该面"看不到工具"的死因：真实死因是**令牌级别**（角色维只是恰好也不相交）。
              .withRoles(RoleSet.none());
      mcpServer =
          config.mcpExpose()
              ? AgentToMcpServer.start(
                  tools,
                  "mosire-main",
                  Version.VERSION,
                  ToolContext.of(
                      externalMcpToken,
                      externalMcpPermissions,
                      // S6：外部面显式自我介绍（external-mcp + FULL）。档位照旧不变，身份<b>必须</b>显式——
                      // 判定闸据此认出"这不是我派的下级"，从而不为外部客户端的命令背书（到人，见 SuperiorJudgeGate）
                      AgentIdentity.external()),
                  // A-6 的可见面谓词（与判定面同一个 oracle；noExport 的求交在 AgentToMcpServer.exportable 里）
                  name ->
                      tools
                          .find(name)
                          .map(
                              tool ->
                                  PermissionChecker.allows(
                                      externalMcpPermissions, name, tool.spec(), tool.roles()))
                          .orElse(false),
                  approvalAuthorizer)
              : null;
      // D30：工具自身配置（toolConfig）在本方法<b>前面</b>已建好（P2b 把它挪到决策块之前：提交口与管线必须拿同一份）
      // P3-3：落库分叉只此一处——conversations == null（demo）走不落库的既有构造，非 demo 走 (store, conversationId)
      // S4-B2：审批编排器只接在<b>生产分叉</b>（落库那条，= run 的终态装配路径）；demo 分叉保持无审批面
      // （--demo 恒"零行为变化"是本仓既有约定：它只跑一条脚本回合，没有工具调用面可审批）
      // 上下文来源：技能目录档 + 预载正文（可见面已在 SkillAccess 内固定为本实例的权限集）
      BasicContextAssembler assembler =
          new BasicContextAssembler(
              ContextPolicy.defaults(),
              ContextSources.skills(skillAccess, skillSettings.preloaded()));
      AgentRuntime runtime =
          conversations == null
              ? new AgentRuntime(
                  agentSpec,
                  llm,
                  tools,
                  new ToolExecutionGuard(),
                  assembler,
                  events,
                  bus,
                  permissionSet)
              : new AgentRuntime(
                  agentSpec,
                  llm,
                  tools,
                  new ToolExecutionGuard(),
                  assembler,
                  events,
                  bus,
                  permissionSet,
                  conversations,
                  MAIN_CONVERSATION_ID,
                  toolConfig,
                  approvalCoordinator,
                  // P1b：桥交给管线 = "Ask 自动进包"这条链路的最后一环（没有它，管线不绑队列，Ask 走既有审批闸）
                  decisionApprovalBridge);
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
              pluginTools,
              setupServer,
              approvalServer,
              approvalTtyChannel,
              approvalHttpChannel,
              decisionPackages);

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
      if (pluginTools != null) {
        pluginTools.close();
      }
      if (setupServer != null) {
        setupServer.close();
      }
      closeLinks(links);
      // 事件存储最后关（与 App.close 的口径一致：其余组件仍可能向它写事件时不得先关它）
      events.close();
      throw e;
    }
  }

  /**
   * W3b 子 Agent 编排装配：模板库（由 {@code start} <b>装载一次</b>后传入——装载时机与 fail-fast 语义"坏模板/缺目录在启动期暴露"
   * 逐字不变，上移只为让装订期角色审计看到同一份快照）→ 子进程管理器 + 链接形态 {@link AgentCommand}（当前 java + 本进程 classpath + {@code
   * Main agent}，JVM 内存 cap -Xmx128m，目录注入，{@code --parent-link}）→ {@link SubagentManager}（父级 = 主
   * Agent：权限/深度 0 对照）→ 五个内置编排工具以 builtin 供给源注册进工具面（二期 L2：sourceId 归属）。
   *
   * <p>S5-A：那句"父级 = 主 Agent 对照"只覆盖 3 参 {@code spawn}（= 主 Agent 在要）；经 MCP 链接进来的下级调用走 {@code
   * spawn(request, identity, permissions)}，对照物是<b>调用者自己</b>。
   *
   * <p><b>子库根（§2.3）</b>：{@code config.dataDir().resolve("subagents")} —— 与 {@link #subagentCommand}
   * 交给子进程的 {@code --data-dir} <b>同源</b>（同一个表达式，两处各写一遍会分叉）。父侧按 {@code <root>/<实例 id>/events.db}
   * 读子体终局记录。
   */
  private static SubagentRig wireSubagents(
      BootConfig config,
      AgentTemplateStore templateStore,
      ToolRegistry tools,
      EventStore events,
      EventBus bus,
      AgentConfig parentConfig,
      AgentPermissionSet parentPermissions,
      Path subagentConfigDir,
      ToolCallAuthorizer authorizer,
      CommandModeHolder commandMode,
      SubagentLimits limits,
      Path skillsRoot,
      int skillsListingBudget,
      WorkspaceRegistry workspaceRegistry,
      BiConsumer<String, String> lineageRegistrar) {
    SubprocessManager processes = new SubprocessManager();
    SubProcessExecutor executor =
        new SubProcessExecutor(
            processes,
            subagentCommand(config, subagentConfigDir, skillsRoot, skillsListingBudget),
            tools,
            PARENT_SERVER_NAME,
            Version.VERSION,
            authorizer,
            skillsRoot,
            // R3b：子体链路的遮蔽面改由注册表产出（技能根 + 工作区树，判定按该子体自己的面逐条求与）
            workspaceRegistry);
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
            limits,
            config.dataDir().resolve("subagents"),
            // R3b：spawn 侧为每个子体挂一块自己的工作区（holder=父 / user=子 / 落点=父工作区下的真子目录）
            workspaceRegistry,
            // 血缘登记缝：工作区模块不得依赖 Brain，而"拟建子体"的血缘只有 spawn 侧算得出来
            lineageRegistrar);
    BuiltinToolSource builtin =
        new BuiltinToolSource(BUILTIN_TOOL_SOURCE_ID, SubagentOrchestrationTools.of(manager));
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
   *
   * <p><b>D2</b>：{@code skillsListingBudget} 与 {@code skillsRoot} 同源（都取自 {@code
   * skills.listingBudget}/{@code skills.dir} 那一份 {@code SkillSettings}）。等于缺省（4096）时 {@code
   * AgentCommand.argv} 不追加旗标——子进程本就回落同一个数，argv 因此逐字不变。
   */
  private static AgentCommand subagentCommand(
      BootConfig config, Path subagentConfigDir, Path skillsRoot, int skillsListingBudget) {
    String java = ProcessHandle.current().info().command().filter(c -> !c.isBlank()).orElse("java");
    return new AgentCommand(
        java,
        List.of("-Xmx128m"),
        List.of("-cp", System.getProperty("java.class.path", ""), "io.mosire.main.Main", "agent"),
        "--id",
        config.templatesDir().toString(),
        config.dataDir().resolve("subagents").toString(),
        true,
        subagentConfigDir == null ? null : subagentConfigDir.toString(),
        skillsRoot == null ? null : skillsRoot.toString(),
        skillsListingBudget);
  }

  /**
   * 遮蔽面清单（R3b）：把 {@link WorkspaceRegistry#maskEntries()} 的条目翻成 {@link SandboxMask} 要的 {@code
   * {namespace, path}} 形状。
   *
   * <p><b>为什么不在这里筛</b>：清单的内容（哪些路径、属于哪个命名空间、能不能进）是工作区模块与能力目录共同决定的事——注册表已经保证
   * "只返回装配期条目、去重、次序稳定"（M6），装配层再筛一遍就等于给"进不进 bash 面"造出第二个判定点。判定仍由 {@code SandboxMask.fence} 按
   * <b>调用者</b>的权限集逐条求与。
   */
  private static List<Map<String, Object>> maskConfigOf(WorkspaceRegistry registry) {
    List<Map<String, Object>> entries = new ArrayList<>();
    for (MaskEntry entry : registry.maskEntries()) {
      entries.add(
          Map.of(
              SandboxMask.NAMESPACE_KEY, entry.namespace(),
              SandboxMask.PATH_KEY, entry.path().toString()));
    }
    return List.copyOf(entries);
  }

  /**
   * 工作区审计事件落到事件库（M8 / §4.7：每次挂载 / 卸载 / 拒绝都留痕）。
   *
   * <p><b>{@code detail} 可以含物理路径</b>——它只进事件库（内部留痕），不进模型面；网关读事件时按既有口径原样回吐，因此这里不额外加工。
   * 落库失败只告警：审计失败不该改业务判定（同 B1 的既有口径）。
   */
  private static void recordWorkspaceEvent(EventStore events, WorkspaceEvent event) {
    String payload;
    try {
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("mount", event.mount());
      fields.put("detail", event.detail());
      payload = PLUGIN_PAYLOAD.writeValueAsString(fields);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // 两个字符串键写不出 JSON 属结构性缺陷：退化成不带 payload 也要留痕（丢的只是细节，不是事件）
      LOG.warn("工作区事件 payload 序列化失败（按无 payload 记）: type={}", event.type(), e);
      payload = "";
    }
    try {
      events.append(EventWrite.of(event.type(), event.holder(), payload, event.user()));
    } catch (RuntimeException e) {
      LOG.warn("工作区事件落库失败（审计失败不改业务判定）: type={} holder={}", event.type(), event.holder(), e);
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

  /**
   * 插件面装配（工作束四，设计 D4）：解析插件目录 → 装载其中的插件 JAR → 落启动行；生命周期事件经 {@link PluginListener} 写入事件库（{@code
   * plugin.lifecycle}，D6）。
   *
   * <p>返回 {@code null} = 未装配（开关关闭 / 缺省目录不存在——"没装插件"是正常部署态，记一行日志即可）；<b>显式 {@code plugins.dir}
   * 指向缺失目录不在此列</b>：那是硬要求落空 ⇒ 经 {@link PluginToolSource} 构造器响亮失败（语义见 {@link #resolvePluginsDir}）。任一
   * JAR 装载失败经 {@link PluginToolSource.PluginLoadException} 原样上抛 = 启动响亮失败（D4.5），
   * 半装配的本源在冒泡前就地回收（回收自身失败只告警，不顶掉原失败）。
   *
   * <p>THROWS_METHOD_THROWS_RUNTIMEEXCEPTION 抑制：装载失败原样冒泡（绝不静默跳过，D4.5）正是本方法的契约——由 {@code start}
   * 的既有装配失败路径统一回收资源后呈现（同 {@code start} 的 fail-fast 语义，无更窄类型）。
   */
  @SuppressFBWarnings("THROWS_METHOD_THROWS_RUNTIMEEXCEPTION")
  private static PluginToolSource wirePlugins(
      ConfigStore configStore, ToolRegistry tools, EventStore events) {
    PluginDirResolution resolution = resolvePluginsDir(configStore, codeLocation());
    if (resolution.dir() == null) {
      LOG.info("插件面：未启用（{}）", resolution.skipReason());
      return null;
    }
    PluginToolSource plugins =
        new PluginToolSource(
            resolution.dir(), tools, pluginLifecycleSink(events), new HostServices(configStore));
    try {
      List<String> loaded = plugins.loadAll();
      // 启动行（D4 的可观测锚点，照"审批面已装配"范式）：装载态与版本取自 PluginStatus，工具名按 sourceId 从目录反查
      List<PluginToolSource.PluginStatus> started =
          plugins.list().stream()
              .filter(status -> status.state() == PluginListener.State.STARTED)
              .toList();
      List<String> toolNames =
          started.stream()
              .flatMap(status -> tools.listBySource(status.sourceId()).stream())
              .map(AgentTool::name)
              .toList();
      LOG.info(
          "插件面已装配：dir={} 装载={} 个 [{}] 工具 {} 个 [{}]",
          resolution.dir(),
          loaded.size(),
          String.join(
              ", ",
              started.stream().map(status -> status.pluginId() + "@" + status.version()).toList()),
          toolNames.size(),
          String.join(", ", toolNames));
      return plugins;
    } catch (RuntimeException failure) {
      try {
        plugins.close();
      } catch (RuntimeException cleanupFailure) {
        // 回收失败不得顶掉原失败：PluginLoadException 点名坏 JAR 的诊断信息是 D4.5"响亮失败"的本体
        LOG.warn("插件面回收失败（原失败照常上抛）", cleanupFailure);
      }
      throw failure;
    }
  }

  /** 宿主侧的 {@link PluginListener} 落库实现（D6）：每次状态变更写一条 {@code plugin.lifecycle}；失败只告警不回滚。 */
  private static PluginListener pluginLifecycleSink(EventStore events) {
    return (pluginId, version, state) -> {
      try {
        events.append(
            EventWrite.of(
                EventTypes.PLUGIN_LIFECYCLE,
                MAIN_CONVERSATION_ID,
                pluginPayload(pluginId, version, state),
                ""));
      } catch (RuntimeException failure) {
        LOG.warn("plugin.lifecycle 落库失败（不影响装载） pluginId={} state={}", pluginId, state, failure);
      }
    };
  }

  /**
   * {@code plugin.lifecycle} 的 payload（字段顺序 pluginId → version → state； Jackson 序列化失败 = 装配缺陷，响亮）。
   */
  private static String pluginPayload(String pluginId, String version, PluginListener.State state) {
    try {
      Map<String, String> payload = new LinkedHashMap<>();
      payload.put("pluginId", pluginId);
      payload.put("version", version);
      payload.put("state", state.name());
      return PLUGIN_PAYLOAD.writeValueAsString(payload);
    } catch (IOException e) {
      throw new IllegalStateException("plugin.lifecycle payload 序列化失败", e);
    }
  }

  /** 插件目录解析结果：{@code dir == null} ⇒ 不装载（原因见 {@code skipReason}，原样进启动日志）。 */
  record PluginDirResolution(Path dir, String skipReason) {

    static PluginDirResolution skip(String reason) {
      return new PluginDirResolution(null, reason);
    }
  }

  /**
   * 插件目录解析（设计 D4，纯函数便于单测）：{@code plugins.enabled=false} ⇒ 不装载（缺省 {@code true}）； {@code plugins.dir}
   * ⇒ 用它（相对路径按进程 cwd 绝对化）；否则仅当<b>真 jar 启动</b>（代码位置是文件）时缺省 = {@code <jar 目录>/plugins}（与 D7
   * 的产物布局正好命中）。开发/测试的 classes 形态<b>不推导缺省目录</b>——从 {@code target/classes} 出发算父目录会命中 {@code
   * target/plugins}（打包产物），让测试面随打包状态漂移（2026-09-14 实测踩坑：AppMcpLinkTest 的子进程因此意外装载
   * bash）。要在这类形态启用插件请显式配 {@code plugins.dir}。键存在但值非法 ⇒ 响亮失败——本仓"配置项存在但值非法 ⇒ 启动响亮失败"的统一口径，不静默回退。
   *
   * <p><b>显式目录缺失 ≠ 缺省目录缺失（D4.4 的两读，此处定死）</b>：缺省目录不存在 = "没装插件"的正常部署态（软跳过）； 显式 {@code plugins.dir}
   * 指向缺失目录 = <b>硬要求落空</b> ⇒ 返回该路径、由 {@link PluginToolSource} 构造器响亮失败——
   * 用户点名了位置而那里什么都没有，静默跳过会得到"看起来配了、其实没生效"的部署。
   */
  static PluginDirResolution resolvePluginsDir(ConfigStore configStore, Path codeLocation) {
    Optional<JsonNode> enabled = configStore.get(PLUGINS_SECTION, PLUGINS_ENABLED_KEY);
    if (enabled.isPresent() && !enabled.get().isNull()) {
      JsonNode value = enabled.get();
      if (!value.isBoolean()) {
        throw new IllegalStateException(
            "配置不可用：" + PLUGINS_SECTION + "." + PLUGINS_ENABLED_KEY + " 必须是布尔（实际值: " + value + "）");
      }
      if (!value.asBoolean()) {
        return PluginDirResolution.skip(PLUGINS_SECTION + "." + PLUGINS_ENABLED_KEY + "=false");
      }
    }
    Optional<JsonNode> dir = configStore.get(PLUGINS_SECTION, PLUGINS_DIR_KEY);
    if (dir.isPresent() && !dir.get().isNull()) {
      JsonNode value = dir.get();
      if (!value.isTextual() || value.asText().isBlank()) {
        throw new IllegalStateException(
            "配置不可用：" + PLUGINS_SECTION + "." + PLUGINS_DIR_KEY + " 必须是非空白路径文本（实际值: " + value + "）");
      }
      return new PluginDirResolution(Path.of(value.asText()).toAbsolutePath(), null);
    }
    if (codeLocation == null || !Files.isRegularFile(codeLocation)) {
      return PluginDirResolution.skip("非 jar 启动（开发/测试形态），无缺省插件目录（要启用请配置 plugins.dir）");
    }
    Path codeParent = codeLocation.toAbsolutePath().getParent();
    if (codeParent == null) {
      return PluginDirResolution.skip("代码位置无父目录，缺省插件目录无从推导（要启用请配置 plugins.dir）");
    }
    Path defaultDir = codeParent.resolve("plugins");
    if (!Files.isDirectory(defaultDir)) {
      return PluginDirResolution.skip("目录不存在: " + defaultDir);
    }
    return new PluginDirResolution(defaultDir, null);
  }

  /** 本类代码位置（真进程 = mosire.jar；测试/开发 = target/classes；取不到 = null）。 */
  private static Path codeLocation() {
    try {
      return Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (RuntimeException | URISyntaxException e) {
      return null;
    }
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
      PluginToolSource pluginTools,
      SetupHttpServer setupServer,
      ApprovalHttpEndpoint approvalServer,
      TtyApprovalChannel approvalTtyChannel,
      HttpApprovalChannel approvalHttpChannel,
      DecisionPackages decisionPackages) {
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
    this.pluginTools = pluginTools;
    this.setupServer = setupServer;
    this.approvalServer = approvalServer;
    this.approvalTtyChannel = approvalTtyChannel;
    this.approvalHttpChannel = approvalHttpChannel;
    this.decisionPackages = decisionPackages;
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
   * 决策包登记表（RB2b 审批面投递 / RB3 事件与 HTTP 面的取用口；进程内唯一实例，见字段注释）。
   *
   * <p>返回本体而不是快照：调用方（RB2b/RB3）要<b>写</b>它（decide/commit/expire），不是只读； 这与 {@link #runtime()} 返回
   * Runtime 本体同一口径——装配层持有的对象由装配层交给需要它的那一层， 而不是让每一层各自去造一个（造出来就是第二处真相）。
   */
  public DecisionPackages decisionPackages() {
    return decisionPackages;
  }

  /**
   * 配置引导面实际监听端口（{@code --setup} 时有效；恒绑 127.0.0.1）。
   *
   * <p>返回 {@code 0} 表示本进程没有引导面——0 不是合法端口，不与"真的绑在 0"混淆（同 {@link #approvalPort()} 口径）。
   */
  public int setupPort() {
    return setupServer == null ? 0 : setupServer.port();
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
        "插件面",
        () -> {
          if (pluginTools != null) {
            pluginTools.close();
          }
        });
    closeQuietly(
        "配置引导面",
        () -> {
          if (setupServer != null) {
            setupServer.close();
          }
        });
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
   * 基础设施事件落库镜像：按<b>前缀白名单</b>收 {@code approval.} ∪ {@code decision.package.} ∪ {@code role.audit.}
   * （其余类型由管线自己的 emit 落库，这里再落就是双写），失败只告警。
   *
   * <p><b>为什么是白名单而不是全量</b>：管线的 {@code agent.lifecycle/llm.call/tool.call/...} 由管线自己 {@code emit}
   * 落库，全量订阅会双写。★ 白名单漏一族 ⇒ 那一族事件<b>静默消失</b>（不报错、没有用例会红）——RB3 的 {@code decision.package.*}
   * 差点就是这样丢的（设计 §5.13(c)）。
   *
   * <p><b>agent 字段为什么是 {@code main}</b>：这些事件以"基础设施事件"形态投出，不带调用者身份（bus 事件的 agent 为空串），而审批请求本身也不经过
   * AgentPipeline。本接线里能触发审批的唯一调用者是主 Agent（子体白名单拿不到 bash， 见 {@code ShellTool} 注册注释）⇒ 落 {@code
   * main}。若日后子体能触发审批，这里必须换成真身份，否则审计会张冠李戴。
   */
  private static void mirrorInfrastructureEvent(EventStore events, Event event) {
    if (event.type() == null || !isMirroredEventType(event.type())) {
      return;
    }
    try {
      events.append(
          EventWrite.of(
              event.type(), MAIN_CONVERSATION_ID, event.payload(), event.correlationId()));
    } catch (RuntimeException failure) {
      LOG.warn("基础设施事件落库失败（不影响判定） type={} seq={}", event.type(), event.seq(), failure);
    }
  }

  /** 镜像前缀白名单（设计 §5.13(c)）：审批 / 决策包 / 角色审计。 */
  private static boolean isMirroredEventType(String type) {
    return type.startsWith(APPROVAL_EVENT_PREFIX)
        || type.startsWith(DECISION_PACKAGE_EVENT_PREFIX)
        || type.startsWith(ROLE_AUDIT_EVENT_PREFIX);
  }

  /**
   * 装订期角色审计的留痕事件（RA4 补；设计 §4.6 / §5.13(c)）：成功与失败都发，且必须发在"失败 ⇒ 抛异常阻止启动"之前。
   *
   * <p><b>为什么用 {@code events.append} 而不是 {@code bus.publish}</b>：总线是异步分派，而 {@code App#close()} 的顺序是
   * {@code events.close()} 先于 {@code bus.close()} ⇒ 走总线会在关停窗口里丢事件。本事件存在的唯一目的就是留痕，
   * 所以走同步落库；落库失败只告警（不能因为审计写不进去就改变"启动是否通过"这个结论）。
   *
   * <p><b>payload 只放计数与结论，不放逐条 findings 全文</b>：全文可能很长（且随工具面增长），逐条明细留给同一轮的两行日志 （INFO 摘要 / WARN
   * 报告项）。{@code unknownRoles} 是角色名——它不是密钥（§4.3.1 的面的分工），所以可以落。
   */
  private static void appendRoleAuditEvent(EventStore events, RoleCatalogAudit.Report report) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("toolCount", report.toolCount());
    payload.put("roleDeclaringToolCount", report.roleDeclaringToolCount());
    payload.put("knownRoleCount", report.knownRoleCount());
    payload.put("catalogChecked", report.catalogChecked());
    List<String> unknownRoles = new ArrayList<>(report.unknownRoleFindings());
    Collections.sort(unknownRoles);
    payload.put("unknownRoles", unknownRoles);
    payload.put("unknownCount", report.unknownRoleFindings().size());
    payload.put("uncoveredCount", report.uncoveredRoleFindings().size());
    payload.put("catalogDiffCount", report.catalogDiffFindings().size());
    try {
      events.append(
          EventWrite.of(
              EventTypes.ROLE_AUDIT,
              MAIN_CONVERSATION_ID,
              PLUGIN_PAYLOAD.writeValueAsString(payload),
              ""));
    } catch (RuntimeException | JsonProcessingException failure) {
      LOG.warn("role.audit 落库失败（不影响装配结论）", failure);
    }
  }

  /** 审批事件的类型前缀（B1 的 {@code ApprovalEventTypes} 只有两个常量，这里按前缀收口，不复制一份类型表）。 */
  private static final String APPROVAL_EVENT_PREFIX = "approval.";

  /** 决策包事件前缀（RB3；常量在 AgentLib {@code DecisionEventTypes}，这里只做落库收口）。 */
  private static final String DECISION_PACKAGE_EVENT_PREFIX = "decision.package.";

  /**
   * 角色审计<b>子类型</b>前缀（RA4 补事件；完整类型常量在 {@link EventTypes#ROLE_AUDIT} = {@code "role.audit"}）。
   *
   * <p>★ 带点是有意的区别：完整类型 {@code role.audit} 本身<b>不</b>走镜像白名单——审计由装配层直接 {@code events.append} 同步落库（见
   * {@code appendRoleAuditEvent}），因为总线是异步的、关停窗口里会丢。 本前缀只认将来可能出现的 {@code role.audit.*}
   * 子类型（当前没有），留它是为了别让"新增子类型"变成静默丢失。RT 的核查（报告 §5-4）确认当前无影响。
   */
  private static final String ROLE_AUDIT_EVENT_PREFIX = "role.audit.";

  /** 配置段名与键名（设计 D4 的解析顺序输入；值非法一律响亮失败，同 {@code commands.mode} 那套口径）。 */
  private static final String PLUGINS_SECTION = "plugins";

  /** 开关键：{@code plugins.enabled=false} ⇒ 不装载（缺省 {@code true}）。 */
  private static final String PLUGINS_ENABLED_KEY = "enabled";

  /** 显式目录键：{@code plugins.dir} ⇒ 用它；缺省 = 代码位置（jar）同目录下的 {@code plugins/}。 */
  private static final String PLUGINS_DIR_KEY = "dir";

  /** {@code plugin.lifecycle} 事件的 payload 组装（Jackson 仅用于这一处序列化；字段顺序 pluginId→version→state）。 */
  private static final ObjectMapper PLUGIN_PAYLOAD = new ObjectMapper();

  /**
   * <b>会话级放行的资格判据</b>（{@code ApprovalCoordinator} 的 {@code sessionGrantable} 注入缝；P2b，设计 §5ter 裁定
   * 13）。
   *
   * <p><b>包级请求一律不放行会话</b>：包级 classKey 是冻结字面量（{@value DecisionApprovalBridge#CLASS_KEY}）， 若
   * callerKey 取令牌桶，人对<b>某一个包</b>批的"本会话"就会被 {@code AutoApproveGate} 命中、把以后<b>每一个</b>包都放行。 桥因此把包级请求的
   * callerKey 取成包级唯一桶（{@link DecisionApprovalBridge#packageCallerKey}），这里认出它、收成"一次"并落一行 WARN
   * ——"包级不放行会话"这条口径必须看得见（否则下次有人放开 {@code sessionGrantable} 时它会静默消失）。
   *
   * <p>其余调用者照抄 {@code ApprovalCoordinator} 的缺省口径（只认 {@code SYSTEM}）：身份唯一性那条 fail-closed 收窄
   * 与本单无关，一个字不改。
   */
  private static boolean sessionGrantable(String callerKey) {
    if (DecisionApprovalBridge.isPackageCallerKey(callerKey)) {
      LOG.warn("包级请求不接受会话放行（一个包一次的裁决不得复用为会话放行：不同范围的两次裁决不许互相放行） callerKey={}", callerKey);
      return false;
    }
    return AccessToken.SYSTEM.name().equals(callerKey);
  }

  /**
   * <b>该包以谁的身份执行</b>（设计 §5ter 裁定 11：执行者 = 裁决者）：按该包的<b>终局裁决者</b>现取身份，不是写死主 Agent。
   *
   * <p>三种取值各有出处：某一级 Agent 拍的板 ⇒ 用那一级的权限集与现读档位；<b>人</b>拍的板 ⇒ 用提出者/主 Agent 那一级
   * （人不是进程内的权限主体，而提出者那一级本来就是把这条调用送进包的那一级）；认不出的裁决者 ⇒ <b>fail-closed</b> 给一个无白名单的只读身份（逐条 {@code
   * PERMISSION_DENIED} → {@code NOT_COMMITTED}），绝不借用主 Agent 的权限兜底。
   *
   * <p>本单只接了主进程内那条链（链上只有主 Agent 一级），所以前两条今天取到的是同一份身份；区别在于取值是<b>每次现问</b>的 ——这正是"父 Agent 把窄权限包给子
   * Agent"在执行侧成立的前提（否则子体提的包会以主 Agent 的 SYSTEM 权限执行）。
   */
  private static LocalDecisionCommitPort.ExecutingIdentity executingIdentityOf(
      String packageId,
      AgentPermissionSet permissions,
      CommandModeHolder mode,
      DecisionCascade cascade) {
    String decider = cascade.finalizedBy(packageId).orElse(AgentIdentity.MAIN_ID);
    if (AgentIdentity.MAIN_ID.equals(decider)
        || DecisionCascade.HUMAN_DECIDER.equals(decider)
        || DecisionCascade.SUPERIOR_DECIDER.equals(decider)) {
      return new LocalDecisionCommitPort.ExecutingIdentity(
          permissions.grantedToken(), permissions, AgentIdentity.main(mode.get()));
    }
    LOG.warn(
        "终局裁决者不是本进程内已知的一级（按 fail-closed 用无权限身份执行，不借用主 Agent 的权限兜底）" + " decider={} packageId={}",
        decider,
        packageId);
    return new LocalDecisionCommitPort.ExecutingIdentity(
        AccessToken.GUEST,
        new AgentPermissionSet(AccessToken.GUEST, Set.of(), Set.of(), false, false, true),
        AgentIdentity.UNKNOWN);
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
