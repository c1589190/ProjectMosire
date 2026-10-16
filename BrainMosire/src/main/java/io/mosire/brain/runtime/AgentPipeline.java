package io.mosire.brain.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.DecisionTurnScope;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmQuota;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.store.ConversationStore;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.context.CompactSummarySlot;
import io.mosire.brain.context.Compactor;
import io.mosire.brain.context.ContextAssembler;
import io.mosire.brain.context.ContextLayer;
import io.mosire.brain.decision.CrossProcessDecisionSubmitter;
import io.mosire.brain.decision.DecisionApprovalBridge;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 主循环（Brain 内部核心）：
 *
 * <pre>{@code
 * assembler → LlmClient → 解析工具调用 → Guard → ToolRegistry 执行 → 事件 → 循环
 * }</pre>
 *
 * <p>终止条件：模型无工具调用（FINISHED）或任一硬顶（计划 D10）。反幻觉护栏（伪造 [TOOL_RESULT]/finish_action/结果溢出暂存）为 GSimulator
 * 现成件，P0 下半程（M3）接入。
 *
 * <p><b>并发约定（R11 未尽项落定）</b>：{@link #history} 非线程安全——调用方必须保证任意时刻只跑一个回合： 每任务一个 runtime
 * 实例，或对外（A2A/AG-UI 等）按提交序串行化调度。选择"每任务一 runtime"= 会话隔离（各任务独立历史）； 选择"串行化"= 一个 Agent 实例的连续对话（A2A
 * 任务续接自然延续上下文）。{@link ConversationStore} 不是并发方案——它只保证"每次调用自身原子"， 上述契约不因持久化而放宽。
 *
 * <p><b>会话持久化（T16）</b>：带 {@code (store, conversationId)} 的构造把历史落到 {@link ConversationStore}——构造时
 * {@code load} 灌入内存工作集（重启续聊），每回合只把<b>新增的尾部</b>追加进去（prompt-cache 不变量：已落库的消息永不改写）。不带 store
 * 的构造委派到"不落库"实现，行为与持久化接入前逐字节一致。
 *
 * <p><b>会话重置（D26）</b>：{@link #resetConversation(String)} 换新会话 id +
 * 清空内存工作集，旧会话在库里原样留档。重置<b>不是</b>重建管线： 本实例持有的 store/装配器/摘要槽一律沿用，"换会话"只落在会话级状态上。与回合一样受串行调用约定约束（R11）。
 *
 * <p><b>重置跨进程有效（会话指针）</b>：重置同时把新 id 写进 {@link
 * ConversationStore#setCurrentConversationId}，构造时优先按该指针寻址（无指针才用调用方给的
 * id）——否则"重置后重启"会退回固定初始会话，把用户已清掉的历史重新灌回工作集。
 *
 * <p><b>会话压缩（T17）</b>：带 {@code (compactor, compactSummary)} 的构造在<b>每个回合的边界</b>（组请求之前）跑一次 {@link
 * Compactor}：超预算时按 micro → 局部摘要 → 快照续接三档压缩会话工作集，并落 {@code conversation.compact} 事件（摘要调用另落 {@code
 * llm.call} 记账，R7）。压缩只改<b>内存工作集</b>（档 1/2）或经显式 {@code compact} 记录落库（档 3）——持久层的 append-only
 * 不变量不因压缩而放宽。不接 {@code Compactor} 的构造行为与压缩接线前逐字节一致。
 *
 * <p><b>压缩侧的两条硬约束（F1–F4 的落点）</b>：①摘要器只要返回过 response，那笔调用就必须记账（含"解析不出 → 退回不动"这条路径， 见 {@code
 * compactIfNeeded} 的 {@code llm.call} 分支）；②档 2 的摘要必须真的进得了请求（{@link
 * #requireSummaryVisibleInAssembler} 守卫），档 3 的摘要必须覆盖压缩点将隐藏的全部内容（{@link #snapshotBaseline} 在档 1
 * 曾隐藏内容时回退到存储视图）。
 */
public final class AgentPipeline {

  private static final Logger LOG = LoggerFactory.getLogger(AgentPipeline.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * <b>{@code decision.package.deadlineMs} 在 {@code toolConfig} 里的键</b>（用户 2026-10-17 指令
   * 「这玩意他妈不是可配置项吗？」）：等人答复的窗口（= 本轮包的 {@code deadlineEpochMs}，裁定 10），由<b>装配层</b>从配置读出来
   * 放进工具配置表，本类在绑定本轮队列时取它（见 {@link #bindPendingDecisions}）。
   *
   * <p><b>为什么走 {@code toolConfig} 而不是给构造加参数</b>：{@code toolConfig} 正是本仓既有的"装配层 → 管线工具面" 配置通道（先例
   * {@code SandboxMask.CONFIG_KEY}），而且它已经是<b>所有</b>管线构造（含子体进程那条最朴素的）都收的参数——
   * 加一个构造参数要同时改十几条构造与全部调用点，且子体进程那几条<b>本来就没有</b>配置来源。缺省（键不在表里）= {@link PendingDecisionQueue}
   * 自己的缺省（30 分钟），行为逐字不变。
   *
   * <p>值非法（非整数 / ≤ 0）不在这里兜底：装配层读配置时已经<b>响亮失败</b>（{@code DecisionWindows.load}）。本类只认 {@link
   * Number}，其余类型按"没配"办并留一条 WARN——管线不该在回合中间抛出一个启动期就该报的错。
   */
  public static final String CONFIG_DECISION_DEADLINE_MS = "decision.package.deadlineMs";

  private final AgentConfig config;
  private final LlmClient llm;
  private final ToolRegistry registry;
  private final ToolExecutionGuard guard;

  /**
   * 工具调用唯一入口（S4）：判定 →（审批闸）→ 执行。本管线与 MCP 桥（{@code AgentToMcpServer}）共用同一条入口， 避免"新判定只落一条路"（{@code
   * guard} 字段保留：它是本类既有装配契约的一部分，{@link ToolCallAuthorizer} 由它装配）。
   */
  private final ToolCallAuthorizer authorizer;

  /**
   * 本轮待裁决队列的出口（P1b，设计 §5 裁定 1+2+4）：回合收尾时把队列收成一个包、<b>同步</b>投递并等裁决。
   *
   * <p><b>{@code null} = 未接线</b>（本类既有构造、离线夹具、本单未接线的子 Agent 路径）：此时管线<b>不绑定</b>队列 （{@code
   * DecisionTurnScope} 里没有当前轮），{@code ToolGate.Ask} 因此走 {@link ToolCallAuthorizer} 的既有那一跳 （问人 /
   * 未装配编排器则 fail-closed 拒）——"不接线"不会静默变成"敏感调用不进包、也不执行"。
   *
   * <p><b>为什么是"有桥才绑"而不是"总是绑"</b>：绑了队列却没有桥 ⇒ {@code Ask} 调用既没执行、也没人去裁（队列无人收尾），
   * 表现为"敏感调用悄悄消失了"。故队列的绑定与桥的接线绑成一个开关：要么整条链路都在，要么整条链路保持旧行为。
   */
  private final DecisionApprovalBridge decisionBridge;

  /**
   * <b>跨进程收尾缝</b>（P3b，设计 §5quinquies ①/⑤）：子体这一侧的回合收尾口——把本轮队列交回<b>父进程</b>结算， 回执落进自己的包表与事件库（{@code
   * CrossProcessDecisionSubmitter}）。
   *
   * <p><b>{@code null} = 未接线</b>（主 Agent 的终态装配、离线夹具、本单之前的全部路径）：收尾走 {@link #decisionBridge}
   * 那条既有路，行为逐字不变。
   *
   * <p><b>与 {@code decisionBridge} 的关系（"有缝就走，没有就退回既有"）</b>：两者都是"回合收尾口"，都决定"本轮队列绑不绑"。
   * 收尾时优先走本缝（{@link #flushPendingDecisions}）；缝不在（{@link CrossProcessDecisionSubmitter#available()}
   * 为假）而桥在 ⇒ 走桥。两个都没接线 ⇒ 不绑队列，{@code ToolGate.Ask} 保持既有那一跳（问人 / fail-closed 拒）
   * ——"没接线"绝不静默变成"敏感调用既不执行也没人裁"。
   *
   * <p><b>为什么必须显式注入</b>：本缝要的那几件（子体自己的包表、本机执行者、实例身份与权限集）只有<b>子体进程的装配点</b> 拿得到（主 Agent 的 {@code App}
   * 不造它）：{@code Brain} 不自己 new 一份——那会变成"第二个真相"（包表/身份/执行者各两份）。
   */
  private final CrossProcessDecisionSubmitter crossProcessSubmitter;

  private final ContextAssembler assembler;
  private final EventStore events;
  private final EventBus bus;
  private final AccessToken caller;
  private final AgentPermissionSet permissionSet;
  private final ConversationStore store;

  /**
   * 当前会话标识：<b>非 final</b>——{@link #resetConversation(String)}（D26：重置 = 换新会话 id）要在实例存活期间改掉它。
   * 持久化按它寻址（{@link #saveHistory} 的 {@code append}、{@link #snapshotBaseline} 的 {@code
   * load}），故切换即"此后一切读写都算新会话"。
   *
   * <p>{@code volatile} 与 {@link #uncoveredHiddenContent} 同口径：本类的并发约定只要求调用方<b>串行</b>（不要求同一线程——见类
   * Javadoc），重绑（重置）与回合可能发生在<b>不同</b>线程上（管线被共享执行器串行驱动），跨线程交接由它保证可见性；这也是 {@code
   * AT_STALE_THREAD_WRITE_OF_PRIMITIVE} 的正当处理——把一个跨回合必须存活的判据消掉才是错的。
   */
  private volatile String conversationId;

  /** 会话压缩器；{@code null} = 未接线（既有构造的取法，行为与压缩接线前逐字节一致）。 */
  private final Compactor compactor;

  /**
   * 当前生效摘要的槽：<b>必须与装配器共用同一个</b>（否则档 2 的摘要在请求里不可见）——本类只写不读（除回传给 Compactor 做并集）。
   *
   * <p><b>取槽的正确来源是装配器自己</b>（{@code BasicContextAssembler#compactSummarySlot()}）：槽是可变的共享对象，自建一个
   * {@link CompactSummarySlot#empty()} 在类型上完全合法却会让档 2 静默失效。构造之后无法再核对（{@link ContextAssembler} 接口里
   * 没有取槽方法），故由 {@link #requireSummaryVisibleInAssembler} 在压缩落地当刻用同源的 {@code composition} 反向验证。
   */
  private final CompactSummarySlot compactSummary;

  /** 会话历史（不含 system 头，进程内累积 + 每回合把新增尾部落库，供下一回合原样回灌）；构造时由 {@link #store} 灌入。 */
  private final List<LlmMessage> history = new ArrayList<>();

  /**
   * 工具自身配置（D30）：每次工具调用经 {@link ToolContext#config()} 下发（空 map = 什么都不注入，既有装配不变）。
   * 装配层（Main）用它把"子库根目录"这类运行时路径交给工具——Brain 不持有全局路径、不做文件系统假设。
   */
  private final Map<String, Object> toolConfig;

  /**
   * 工作集里是否有"只被占位代表"的内容（档 1 移出工作集、且此后没有落库重新同步过）——{@link #snapshotBaseline} 的判据（F3）。
   *
   * <p>只在回合边界读写。{@code volatile} 与 {@link #cancelled} 同口径：本类的并发约定只要求调用方<b>串行</b>（不要求同一线程——见类
   * Javadoc 的"每任务一 runtime 实例，或对外按提交序串行化调度"），跨回合的线程交接由它保证可见性；这也是 {@code
   * AT_STALE_THREAD_WRITE_OF_PRIMITIVE} 的正当处理（而不是把一个跨回合必须存活的判据消掉）。
   */
  private volatile boolean uncoveredHiddenContent;

  /** 取消请求标志（volatile 保证跨线程可见；只在 run() 入口重置——取消只对"正在跑的回合"生效）。 */
  private volatile boolean cancelled;

  /**
   * 不落库构造（M2 起的既有签名，行为逐字节不变）：委派到 {@link DiscardingConversationStore}。
   *
   * <p>生产侧要持久化请用 {@link #AgentPipeline(AgentConfig, LlmClient, ToolRegistry, ToolExecutionGuard,
   * ContextAssembler, EventStore, EventBus, AccessToken, AgentPermissionSet, ConversationStore,
   * String)}。
   */
  public AgentPipeline(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AccessToken caller,
      AgentPermissionSet permissionSet) {
    this(
        config,
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        caller,
        permissionSet,
        DiscardingConversationStore.INSTANCE,
        conversationIdOf(config));
  }

  /**
   * 不落库构造 + <b>工具配置注入</b>（D30）：除最后一个参数外与 9 参构造逐字节一致（委派到 {@link #AgentPipeline(AgentConfig,
   * LlmClient, ToolRegistry, ToolExecutionGuard, ContextAssembler, EventStore, EventBus,
   * AccessToken, AgentPermissionSet, ConversationStore, String, Compactor, CompactSummarySlot,
   * Map)}）。
   */
  public AgentPipeline(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AccessToken caller,
      AgentPermissionSet permissionSet,
      Map<String, Object> toolConfig) {
    this(
        config,
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        caller,
        permissionSet,
        DiscardingConversationStore.INSTANCE,
        conversationIdOf(config),
        null,
        CompactSummarySlot.empty(),
        toolConfig);
  }

  /**
   * 不落库构造 + 工具配置注入 + <b>跨进程收尾缝</b>（P3b）：除最后一个参数外与上一条构造逐字节一致。
   *
   * <p><b>为什么需要它</b>：子体进程的 runtime 就是"不落库"那一档（它没有 {@code ConversationStore}），而 P3b 的跨进程缝
   * 恰恰只能接在<b>子体</b>这一侧——没有这条构造，子体要么接不上缝，要么被迫先造一个它并不需要的会话库。
   *
   * @param crossProcessSubmitter 跨进程收尾缝（非 null；{@code null} 请用上一条构造，语义等价但少一次分叉）
   */
  public AgentPipeline(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AccessToken caller,
      AgentPermissionSet permissionSet,
      Map<String, Object> toolConfig,
      CrossProcessDecisionSubmitter crossProcessSubmitter) {
    this(
        config,
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        caller,
        permissionSet,
        DiscardingConversationStore.INSTANCE,
        conversationIdOf(config),
        null,
        CompactSummarySlot.empty(),
        toolConfig,
        null,
        null,
        crossProcessSubmitter);
  }

  /**
   * 全参装配 + 会话持久化：历史落 {@code conversationId} 名下的 {@link ConversationStore}。
   *
   * <p><b>hydrate</b>：构造即 {@code store.load(conversationId)} 灌入内存工作集——同一库文件上新建实例（进程重启）能接着
   * 上一段对话聊，靠的就是这一步。会话应只被一个 pipeline 实例驱动（并发约定见类 Javadoc）。
   *
   * @param store 会话存储（非 null；用 {@link #AgentPipeline(AgentConfig, LlmClient, ToolRegistry,
   *     ToolExecutionGuard, ContextAssembler, EventStore, EventBus, AccessToken,
   *     AgentPermissionSet)} 表示不落库）
   * @param conversationId 会话标识（非 null、显式给定；生产接线由 {@code AgentRuntime} 传 {@code config.id()}）
   */
  public AgentPipeline(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AccessToken caller,
      AgentPermissionSet permissionSet,
      ConversationStore store,
      String conversationId) {
    this(
        config,
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        caller,
        permissionSet,
        store,
        conversationId,
        null,
        CompactSummarySlot.empty());
  }

  /**
   * 全参装配 + 会话持久化 + 会话压缩（T17）：每回合边界按 token 预算门跑一次 {@link Compactor}。
   *
   * <p><b>压缩的落库只用本实例持有的那个 {@link ConversationStore}</b>（档 3）：这是"撕裂读"（R11）的结构性回避——管线存活期间不存在第二个 store
   * 实例/第二条连接，{@code load()} 的两条查询之间不可能插进另一次 {@code compact()}。因此 {@code compactor} 与 {@code store}
   * 必须成对给出，且压缩只在回合边界串行发生（本类的并发约定已然：同一实例任意时刻只跑一个回合）。
   *
   * @param store 会话存储（非 null）
   * @param conversationId 会话标识（非 null）
   * @param compactor 压缩器；{@code null} = 不压缩（与既有构造同行为）
   * @param compactSummary 摘要槽；<b>必须与 {@code assembler} 共用同一个实例</b>——<b>取槽的正确来源是装配器自己</b> （{@code
   *     BasicContextAssembler#compactSummarySlot()}），自建槽会让档 2 的摘要进不了 COMPACT_SUMMARY 层；未接线压缩时给
   *     {@link CompactSummarySlot#empty()} 即可。本构造<b>不去核对</b>这件事（接口里没有取槽方法）， 改由压缩落地当刻的守卫 {@link
   *     #requireSummaryVisibleInAssembler} 响亮失败兜底
   */
  public AgentPipeline(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AccessToken caller,
      AgentPermissionSet permissionSet,
      ConversationStore store,
      String conversationId,
      Compactor compactor,
      CompactSummarySlot compactSummary) {
    this(
        config,
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        caller,
        permissionSet,
        store,
        conversationId,
        compactor,
        compactSummary,
        Map.of());
  }

  /**
   * 全参装配 + <b>工具配置注入</b>（D30）：除最后一个参数外与 13 参构造逐字节相同。
   *
   * <p>{@code toolConfig} 是工具<b>自身</b>的配置（路径等），经 {@link ToolContext#config()} 交给每次工具调用—— 与 LLM 填的
   * {@code arguments} 严格分开（{@code ToolContext} 的既定缝：工具只能读、不能反向索取调用者数据）。 既有装配不传 = 空
   * map，行为与注入接线前逐字节一致。
   */
  public AgentPipeline(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AccessToken caller,
      AgentPermissionSet permissionSet,
      ConversationStore store,
      String conversationId,
      Compactor compactor,
      CompactSummarySlot compactSummary,
      Map<String, Object> toolConfig) {
    this(
        config,
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        caller,
        permissionSet,
        store,
        conversationId,
        compactor,
        compactSummary,
        toolConfig,
        null);
  }

  /**
   * 全参装配 + <b>审批编排器</b>（S4-B2）：除最后一个参数外与 14 参构造逐字节相同。
   *
   * <p>{@code coordinator} 非 null 时，工具自报 {@link io.mosire.agentlib.approval.ToolGate.Ask}
   * 的调用会真的去问人（tty/HTTP 通道），并只按人的答复继续； <b>null = 无审批面</b>：{@code Ask} 一律 fail-closed 拒（{@link
   * ToolCallAuthorizer#of(ToolExecutionGuard, ApprovalCoordinator)} 的既定语义）——既有装配（不传）行为因此逐字节不变。
   *
   * <p>一份 {@code PendingApprovals} 必须<b>逐层当参数</b>传进来（编排器持有它）；本类<b>不</b>持有任何静态/全局单例——
   * 同一进程内只能有一份登记表，由装配层（{@code App}）建并往下传，这样"两条通道看到同一 id"才是结构性的（H8 判据）。
   */
  public AgentPipeline(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AccessToken caller,
      AgentPermissionSet permissionSet,
      ConversationStore store,
      String conversationId,
      Compactor compactor,
      CompactSummarySlot compactSummary,
      Map<String, Object> toolConfig,
      ApprovalCoordinator coordinator) {
    this(
        config,
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        caller,
        permissionSet,
        store,
        conversationId,
        compactor,
        compactSummary,
        toolConfig,
        coordinator,
        null);
  }

  /**
   * 全参装配 + 审批编排器 + <b>决策包桥</b>（P1b，设计 §5 裁定 1+2+4）：除最后一个参数外与上一条构造逐字相同。
   *
   * <p><b>{@code decisionBridge} 决定"本轮待裁决队列绑不绑"</b>：非 null ⇒ {@link #run(String)} 在回合开始时 {@link
   * DecisionTurnScope#bind} 一个新队列，{@code ToolGate.Ask} 的调用改为"登记 + 当场回『已加入决策包』"
   * （不执行、不阻塞、不走审批闸），回合收尾由桥收成一个包、<b>同步</b>投递并等裁决；{@code null} ⇒ 不绑队列， {@code Ask}
   * 走既有那一跳（问人）。两者同进同退的理由写在 {@link #decisionBridge} 字段的注释里。
   *
   * <p><b>为什么必须是显式参数而不是装配层回头 set</b>：管线要在<b>回合边界</b>拿到它（绑/收尾都在那里），
   * 而"装配完成后才塞进去"会让第一次回合有窗口期用不到它——那种间歇态最难查。装配层（{@code App}）在构造时就给全。
   *
   * <p>★ {@code EI_EXPOSE_REP2} 的抑制落在下面那条<b>终态构造</b>上（只有它真正持有这两个注入的协作者）：挂在本条只做委托的 重载上会被 SpotBugs
   * 判成"无用抑制"。
   */
  public AgentPipeline(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AccessToken caller,
      AgentPermissionSet permissionSet,
      ConversationStore store,
      String conversationId,
      Compactor compactor,
      CompactSummarySlot compactSummary,
      Map<String, Object> toolConfig,
      ApprovalCoordinator coordinator,
      DecisionApprovalBridge decisionBridge) {
    this(
        config,
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        caller,
        permissionSet,
        store,
        conversationId,
        compactor,
        compactSummary,
        toolConfig,
        coordinator,
        decisionBridge,
        null);
  }

  /**
   * 全参装配 + 审批桥 + <b>跨进程收尾缝</b>（P3b，设计 §5quinquies）：除最后一个参数外与上一条构造逐字相同。
   *
   * <p><b>{@code crossProcessSubmitter} 非 null</b> ⇒ 子体这一侧：回合开始时若缝<b>真的可用</b> （{@link
   * CrossProcessDecisionSubmitter#available()}：父侧那个工具在 registry 里且本实例判定面放行它）就绑本轮队列， 收尾走跨进程提交；缝不可用 ⇒
   * 一个字段都不改语义（不绑队列，{@code Ask} 走既有那一跳）。{@code null} ⇒ 与接线前逐字节一致。
   *
   * <p><b>为什么与桥并存而不是二选一</b>：装配层可能同时给两者（子体进程里挂一条本进程的桥做兜底，或反过来）； 谁走哪条路由 {@link
   * #flushPendingDecisions} 现判（缝优先），不由构造顺序暗示。
   *
   * <p><b>为什么不给 {@code decisionBridge}/{@code crossProcessSubmitter} 挂 {@code EI_EXPOSE_REP2}
   * 抑制</b>： 它们是<b>注入的协作者</b>（管线在回合收尾必须调它们：收成包、投递/提交、等裁决、执行被批子集），与本类既有的 {@code events}/{@code
   * bus}/{@code llm}/{@code guard} 完全同类——<b>故意的共享引用（依赖注入）</b>，不是"把内部状态漏出去"。 ★
   * 这里此前挂过一条抑制，<b>2026-10-17 clean 重编（先 {@code rm -rf target/classes}）实测它是多余的</b>： 去掉抑制、只跑
   * SpotBugs（跳开 checkstyle）⇒ {@code BugInstance size is 0}，没有任何 {@code EI_EXPOSE_REP2} 告警 ⇒ 按
   * AGENTS §三"抑制多余也是要修的告警"删掉它。若将来真报了，再按当时的事实重新加回来并写明理由。
   */
  public AgentPipeline(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AccessToken caller,
      AgentPermissionSet permissionSet,
      ConversationStore store,
      String conversationId,
      Compactor compactor,
      CompactSummarySlot compactSummary,
      Map<String, Object> toolConfig,
      ApprovalCoordinator coordinator,
      DecisionApprovalBridge decisionBridge,
      CrossProcessDecisionSubmitter crossProcessSubmitter) {
    this.crossProcessSubmitter = crossProcessSubmitter;
    this.decisionBridge = decisionBridge;
    this.toolConfig = Map.copyOf(Objects.requireNonNull(toolConfig, "toolConfig"));
    this.compactor = compactor;
    this.compactSummary = Objects.requireNonNull(compactSummary, "compactSummary");
    this.config = Objects.requireNonNull(config, "config");
    this.llm = Objects.requireNonNull(llm, "llm");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.guard = Objects.requireNonNull(guard, "guard");
    // coordinator 为 null 时本装配与 ToolCallAuthorizer.of(guard) 逐字等价（见该工厂 javadoc）：不传协调器的既有路径行为不变
    this.authorizer = ToolCallAuthorizer.of(this.guard, coordinator);
    this.assembler = Objects.requireNonNull(assembler, "assembler");
    this.events = Objects.requireNonNull(events, "events");
    this.bus = Objects.requireNonNull(bus, "bus");
    this.caller = Objects.requireNonNull(caller, "caller");
    this.permissionSet = Objects.requireNonNull(permissionSet, "permissionSet");
    this.store = Objects.requireNonNull(store, "store");
    String requestedId = Objects.requireNonNull(conversationId, "conversationId");
    // 会话指针优先（D26 续）：库上记着"上次活动会话"就用它——重置后重启必须接着<b>重置出的新会话</b>，而不是用固定初始 id
    // 回到重置前那条（那等于把用户已经清掉的历史重新灌回工作集，正是 D26 拒绝"只清内存"的理由）。无指针（首次启动/不持久化实现）⇒ 用调用方给的 id。
    this.conversationId = this.store.currentConversationId().orElse(requestedId);
    // hydrate：既有会话（进程重启前落的库）先灌回内存工作集，否则"重启续聊"名存实亡
    history.addAll(this.store.load(this.conversationId));
  }

  /** 不落库构造的会话 id 取法：Store 是空实现，id 只用于占位，取配置里的 Agent id（与生产接线同源）。 */
  private static String conversationIdOf(AgentConfig config) {
    return Objects.requireNonNull(config, "config").id();
  }

  /**
   * 请求取消当前运行中的回合（幂等、线程安全）。
   *
   * <p>不做硬中断——进行中的 LLM 调用与工具执行照常完成，管线在下一个检查点（循环顶/工具执行前） 就地以 {@link StopReason#CANCELLED}
   * 终止，终止前保存历史供后续对话回灌。无运行中的回合时调用是空操作 （标志在 {@link #run(String)} 入口重置）。
   */
  public void cancel() {
    cancelled = true;
  }

  /**
   * 重置会话（D26：{@code 清空内存工作集 + 切到一个全新 conversationId}）：旧会话在库里<b>原样保留</b>（不删——可追溯），新对话从零开始。
   *
   * <p><b>五件事一件都不能少</b>（会话级状态就这五处，漏掉任何一处"重置"都名不副实）：
   *
   * <ol>
   *   <li><b>会话指针落库</b>（{@link ConversationStore#setCurrentConversationId}）：这是"重置跨进程有效"的载体——指针不落库，
   *       重启就回到固定初始 id，把用户已清掉的历史重新灌回来（"重置被重启撤销"）。<b>先落库再切换内存</b>，见下"失败方向"；
   *   <li>{@link #conversationId} ← {@code newConversationId}：此后落库/读库都按新 id 寻址（旧会话的行一条不动）；
   *   <li>{@link #history} 清空（就地 {@code clear()}——列表是 final，就地改是本类既定口径，见 {@link
   *       #replaceWorkingSet}）： 否则下一回合会把上一个会话的历史原样回灌给模型；
   *   <li>{@link #uncoveredHiddenContent} 归 {@code false}：该判据描述的是"<b>本会话</b>工作集有覆盖缺口"，会话换了即失效——
   *       留着会让下一个会话的档 3 摘要基准回退到 {@code store.load(新 id)}（空）而不是工作集；
   *   <li>{@link #compactSummary} 清空：槽里挂的是上一个会话的摘要，留着它新会话就会带着一段描述旧对话的记忆开场（槽与装配器共用同一实例， {@code
   *       COMPACT_SUMMARY} 层随之归空）。
   * </ol>
   *
   * <p><b>不 {@code load} 新会话</b>：重置语义是"从零开始"，不是"续聊某个既有会话"。调用方负责给一个<b>全新</b> id（生产侧由 Main 生成）； 若传入的
   * id 恰好已有落库内容，本方法<b>不会</b>把它灌进工作集——新会话的工作集恒为空，直到下一回合把新增尾部追加进去（那时的库内容与内存**不同源**，
   * 是调用方越过了本方法的约定，不是本方法可以静默兜底的场景）。
   *
   * <p><b>失败方向（指针写在前）</b>：指针写入失败 ⇒ 直接抛出、内存<b>不切</b>——运行期与重启后一致地停在旧会话（"重置没生效"，用户可重试）；
   * 反过来先把内存切了再写指针，一旦写失败就得到最难排查的那种态：重启前看着已经重置，重启后旧历史复活。
   *
   * <p><b>并发约定</b>：与 {@link #run(String)} 一样只要求调用方<b>串行</b>——重置改的是实例级共享、非线程安全的 {@code history}，
   * 必须与回合排在同一串行化点上（例如同一个单线程执行器），<b>不得</b>在另一个线程上与在途回合并发调用（见类 Javadoc 的 R11）。
   *
   * @param newConversationId 新会话标识（非 null、显式给定——管线不生成 id，也不猜）
   */
  public void resetConversation(String newConversationId) {
    Objects.requireNonNull(newConversationId, "newConversationId");
    // 指针先落库、内存后切换：写失败时宁可不切（"重置未生效"）也不切换（那会留下"运行期看着已重置、重启却复活旧历史"的分裂态）
    store.setCurrentConversationId(newConversationId);
    this.conversationId = newConversationId;
    history.clear();
    uncoveredHiddenContent = false;
    // compactSummary 的非空由构造器的 requireNonNull 保证（本类没有第二条赋值路径），故无需空值分支
    compactSummary.clear();
  }

  /**
   * 当前会话标识（重置后即为新 id）——装配层据此向调用方回报"重置前的会话是哪个"。
   *
   * <p>读的是 {@code volatile} 字段的即时值：在共享串行执行器上，它反映的是"最后一次已执行的重置/构造"。重置的<b>请求</b>排进执行器到真正执行之间， 本方法仍返回旧
   * id（切换是一个动作，不是一个声明）。
   */
  public String conversationId() {
    return conversationId;
  }

  /**
   * 本回合的可见工具面（设计 §4.8「一次计算，两条面消费」）。
   *
   * <p>注册表全量 ∩ 权限判定面：这里与判定面（{@code ToolCallAuthorizer}）共用 {@link PermissionChecker#allows} 唯一
   * oracle，不另写一份过滤式——可见面必须是判定面的子集（不变量 A-6），否则模型会看见"一调就吃 {@code PERMISSION_DENIED}"的工具（烧 token
   * 烧回合，而且那正是"第二处真相"的形状）。主/子进程共用这一处代码。
   *
   * <p>被裁掉的数量落一条 INFO（每回合至多一次：本方法只在 {@link #run(String)} 的回合起点调用）——"工具悄悄没了"是最难查的账， 数量至少留下痕迹（设计 §4.8
   * 第 4 条）。
   */
  private List<AgentTool> visibleTools() {
    List<AgentTool> all = registry.list();
    List<AgentTool> visible =
        all.stream()
            .filter(
                tool ->
                    PermissionChecker.allows(permissionSet, tool.name(), tool.spec(), tool.roles()))
            .toList();
    int dropped = all.size() - visible.size();
    if (dropped > 0) {
      LOG.info("本回合工具可见面按权限裁剪: 可见={} 总数={} 裁掉={}", visible.size(), all.size(), dropped);
    }
    return visible;
  }

  /**
   * 跑一回合：用户输入 →（LLM 循环 + 工具调用）→ 终止。
   *
   * <p>约定：方法是同步的（网络/子进程调用在工具内自行发生）；回合间共享 {@code history} （一个 Agent 实例的连续对话）。
   *
   * <p><b>P1b 追加的回合作用域</b>（设计 §5 裁定 1+2）：回合开始时把本轮的待裁决队列绑到当前线程，收尾时（{@code finally}，
   * 含取消/超限/异常各条出口）把队列收成一个决策包、<b>同步</b>投递并等裁决——所以"回合返回"意味着裁决与执行都已经发生 （代价：慢裁决会占住这条线程，见 {@link
   * #decisionBridge}）。
   */
  public TurnResult run(String userMessage) {
    Objects.requireNonNull(userMessage, "userMessage");
    // ★ P1b（设计 §5 裁定 1+2）：本轮的待裁决队列——回合开始时绑到当前线程，回合收尾（含异常/取消路径）解绑。
    //   "绑了队列"和"有人能收尾"是同一个开关（见 decisionBridge / crossProcessSubmitter 的注释）：
    //   没有任何收尾口就不绑，Ask 保持既有行为。
    // ★ P3b：跨进程缝在场时以它为准——缝"真的可用"（父侧工具在 registry 里且判定面放行）才绑。
    //   缝不在 ⇒ 不绑队列 ⇒ Ask 走既有那一跳（子体进程里就是它本来的 fail-closed 判/拒），这是回归安全网。
    PendingDecisionQueue pendingDecisions =
        decisionBridge == null && !crossProcessSeamAvailable() ? null : bindPendingDecisions();
    // 上一次回合的取消请求到此为止：cancel() 只对当时正在跑的回合负责
    cancelled = false;
    // 压缩触发点：回合边界（组请求之前）。此处既没有在途的落库（上一回合早已 return），也没有并发的第二个 store
    // 实例——档 3 的 compact/load 用的是本实例持有的同一个 store，撕裂读在结构上不可达
    compactIfNeeded(userMessage);

    LlmQuota quota = config.quotaMaxTokens() > 0 ? new LlmQuota(config.quotaMaxTokens()) : null;
    Instant deadline = Instant.now().plus(config.timeBudget());
    // 可见面 = 注册表 ∩ 判定面（唯一 oracle，A-6）：被裁掉的数量落 INFO，不静默消失
    List<AgentTool> tools = visibleTools();
    // 回合起点：历史长度在组请求前取定——saveHistory 用同一份 messages 列表按下标推出本回合新增的尾部（不靠猜、
    // 不比对内容）；快照同时让"本回合"与历史后续变化解耦
    List<LlmMessage> replayedHistory = List.copyOf(history);
    int replayedSize = replayedHistory.size();
    // 历史回灌：assembler 契约保证 messages[0] 是 system、history 紧随其后、本轮 user 收尾
    List<LlmMessage> messages =
        new ArrayList<>(
            assembler.buildRequest(config, userMessage, replayedHistory, tools).messages());

    try {
      return runTurn(userMessage, deadline, quota, tools, messages, replayedSize);
    } finally {
      // ★ 先解绑取数口，再收包——顺序不能反（2026-10-18 实测踩到）：
      //   收包那条路会**同步执行被批子集**（提交口在同一个线程上跑各条目自己的工具）。若此刻队列还绑着，
      //   被批条目里任何一条自报 Ask 的工具都会**再登记进这个已经产出过提议的队列** ⇒ 命中"已提议不得再登记"
      //   的守卫、该条被判成执行失败（实测：包被审批放行，提交结局却是 REJECTED）。
      //   解绑之后，执行段看到的是"没有本轮队列" ⇒ 它走 authorizer 的既有行为（`Ask` 无队列 ⇒ 审批闸），
      //   而"裁决即授权"这条口径由提交口自己保证（它用的是纯判定链 + 资源前置闸，见 LocalDecisionCommitPort；
      //   P2b 前的注释写的是 `ToolCallAuthorizer.of(guard)`——那个形状早就不是现状，改准）。
      clearPendingDecisions(pendingDecisions);
      flushPendingDecisions(pendingDecisions);
    }
  }

  /**
   * 本回合的主循环（P1b 从 {@link #run(String)} 原样搬出来）：用户输入 →（LLM 循环 + 工具调用）→ 终止。
   *
   * <p>搬出来只为一件事：让回合级的作用域（本轮待裁决队列）由 {@link #run(String)} 的 {@code try/finally} 统一收尾—— 主循环里那<b>七个</b>
   * {@code return} 出口（{@code CANCELLED} ×2 / {@code TURN_LIMIT} / {@code TIME_BUDGET} / {@code
   * LLM_ERROR} / {@code QUOTA} / {@code TOOL_CALL_LIMIT} / 正常 {@code FINISHED}）一个都不用改，
   * 也不可能漏掉某个新增出口（新增出口自动落进同一个 finally）。
   */
  private TurnResult runTurn(
      String userMessage,
      Instant deadline,
      LlmQuota quota,
      List<AgentTool> tools,
      List<LlmMessage> messages,
      int replayedSize) {
    int turns = 0;
    int totalToolCalls = 0;
    String finalText = "";

    while (true) {
      if (cancelled) {
        emitDecision("CANCELLED", Map.of());
        saveHistory(messages, replayedSize);
        return new TurnResult(StopReason.CANCELLED, turns, totalToolCalls, finalText);
      }
      if (turns >= config.maxTurns()) {
        emitDecision("TURN_LIMIT", Map.of("maxTurns", config.maxTurns()));
        saveHistory(messages, replayedSize);
        return new TurnResult(StopReason.TURN_LIMIT, turns, totalToolCalls, finalText);
      }
      if (Instant.now().isAfter(deadline)) {
        emitDecision("TIME_BUDGET", Map.of("timeBudget", config.timeBudget().toString()));
        saveHistory(messages, replayedSize);
        return new TurnResult(StopReason.TIME_BUDGET, turns, totalToolCalls, finalText);
      }

      LlmResponse response;
      long startedNanos = System.nanoTime();
      try {
        response = llm.chat(new LlmRequest(List.copyOf(messages), toolsDefs(tools)));
      } catch (io.mosire.agentlib.llm.LlmException e) {
        // LlmClient 契约只抛 LlmException；此刻 assistant 消息尚未入列，历史以工具结果干净收尾
        emitDecision("LLM_ERROR", Map.of("reason", String.valueOf(e.getMessage())));
        saveHistory(messages, replayedSize);
        return new TurnResult(StopReason.LLM_ERROR, turns, totalToolCalls, finalText);
      }
      long latencyMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
      turns++;
      // llm.call 记账（D18）在配额判定之前落库：超限终止也不能丢"这次调用确实发生了"的事实
      emit(
          EventTypes.LLM_CALL,
          Map.of(
              "inputTokens",
              response.inputTokens(),
              "outputTokens",
              response.outputTokens(),
              "cacheReadTokens",
              response.cacheReadTokens(),
              "cacheWriteTokens",
              response.cacheWriteTokens(),
              "model",
              response.model(),
              "latencyMs",
              latencyMs,
              "turns",
              turns));
      if (quota != null) {
        try {
          quota.record(response.inputTokens(), response.outputTokens());
        } catch (io.mosire.agentlib.llm.QuotaExceededException e) {
          emitDecision("QUOTA", Map.of("reason", e.getMessage()));
          saveHistory(messages, replayedSize);
          return new TurnResult(StopReason.QUOTA, turns, totalToolCalls, finalText);
        }
      }

      // 追加 assistant 消息 + 回合事件
      messages.add(response.assistantMessage());
      emit(
          EventTypes.CONVERSATION_TURN,
          Map.of(
              "turns",
              turns,
              "input",
              userMessage,
              "output",
              response.textPart().orElse(""),
              "model",
              response.model()));

      List<ContentPart.ToolCall> toolCalls =
          response.assistantMessage().content().stream()
              .filter(ContentPart.ToolCall.class::isInstance)
              .map(ContentPart.ToolCall.class::cast)
              .toList();

      if (toolCalls.isEmpty()) {
        saveHistory(messages, replayedSize);
        return new TurnResult(
            StopReason.FINISHED, turns, totalToolCalls, response.textPart().orElse(""));
      }

      if (toolCalls.size() > config.maxToolCallsPerTurn()) {
        emitDecision(
            "TOOL_CALL_LIMIT",
            Map.of("toolCalls", toolCalls.size(), "max", config.maxToolCallsPerTurn()));
        // 悬空 tool_call 修复：本回合一个都没执行，回灌前为每个调用补占位失败结果——
        // 否则历史里存在无应答的 tool_call，下一回合部分供应商会拒收请求
        for (ContentPart.ToolCall call : toolCalls) {
          messages.add(
              LlmMessage.tool(
                  new ContentPart.ToolResult(call.id(), call.name(), null, "回合因单次响应工具调用数超限中止")));
        }
        saveHistory(messages, replayedSize);
        return new TurnResult(
            StopReason.TOOL_CALL_LIMIT, turns, totalToolCalls, response.textPart().orElse(""));
      }

      for (ContentPart.ToolCall call : toolCalls) {
        if (cancelled) {
          emitDecision("CANCELLED", Map.of());
          saveHistory(messages, replayedSize);
          return new TurnResult(StopReason.CANCELLED, turns, totalToolCalls, finalText);
        }
        totalToolCalls++;
        executeToolCall(messages, call);
      }
    }
  }

  /**
   * 绑上本轮的待裁决队列（设计 §5 裁定 1+2）：队列的身份取自<b>宿主已知</b>的调用者与实例 （{@link AgentIdentity#instanceId()}
   * 是"一个实例一个未决包"的键、也是包与历史的归属键，裁定 3），模型给不出也改不了。
   *
   * <p><b>P3b：接上跨进程缝时，实例身份以缝里那一份为准</b>。理由是一个真实的口子：子体进程的 {@code toolConfig} 不带自身身份 （{@code
   * SubagentProcessMain} 走的是最朴素的装配），{@link AgentIdentity#mainFrom(Map)} 于是算出 {@code main}
   * ——本地包会记成"主 Agent 提的"，而父侧核注册表用的是子体自报的实例 id，两边对不上（更要紧的是归属错人）。缝里那份身份是
   * 装配层按本进程实例绑的（模型给不出、本类不猜），所以它是唯一来源。
   *
   * @return 刚绑上的队列（收尾要用它按身份解绑，见 {@link #clearPendingDecisions}）
   */
  private PendingDecisionQueue bindPendingDecisions() {
    // 实例身份与每次工具调用上下文里的那份<b>同源</b>（AgentIdentity.mainFrom(toolConfig)：两者若各算一份，
    // "包是谁提的"（proposerKey）就会与"那次调用是谁发的"（ApprovalRequest.requesterId）对不上。
    String instanceId =
        crossProcessSubmitter == null
            ? AgentIdentity.mainFrom(toolConfig).instanceId()
            : crossProcessSubmitter.proposerKey();
    return DecisionTurnScope.bind(
        new PendingDecisionQueue(instanceId, caller, decisionDeadlineMillis()));
  }

  /**
   * 本轮包的裁决窗口（毫秒），取自装配层放进 {@link #toolConfig} 的 {@link #CONFIG_DECISION_DEADLINE_MS}。
   *
   * <p><b>为什么让装配层读配置、而不是管线自己读配置库</b>：Brain 层不认识配置根（{@code ConfigStore} 由 {@code App} /
   * 子体进程装配点持有），而"读一个键、值非法就响亮失败"这条口径归装配层（与 {@code SubagentLimitsLoader} 等同一条路）。 本类只做一次取值 + 形状判断。
   *
   * <p><b>缺省 = 队列自己的缺省</b>（30 分钟）：键不在表里、值为 null、或值不是正数 ⇒ 不传窗（走二参语义）。值非法时装配层
   * 早已响亮失败，所以这里的分支实际只服务"离线夹具手搓 toolConfig""子体进程没配"这两档。
   */
  private long decisionDeadlineMillis() {
    Object configured = toolConfig.get(CONFIG_DECISION_DEADLINE_MS);
    if (configured instanceof Number number && number.longValue() > 0L) {
      return number.longValue();
    }
    if (configured != null) {
      LOG.warn(
          "本轮决策窗口配置不可用（按缺省 {}ms 办，配置键 {}）值={}——装配层应当在启动期就为非法值响亮失败",
          DecisionPackages.DEFAULT_DEADLINE_MILLIS,
          CONFIG_DECISION_DEADLINE_MS,
          configured);
    }
    return defaultDecisionDeadlineMillis;
  }

  /**
   * <b>本进程的缺省决策窗口</b>（毫秒）：默认 {@link DecisionPackages#DEFAULT_DEADLINE_MILLIS}（30 分钟， 行为与接线前逐字相同）。
   *
   * <p><b>为什么存在这个进程级旋钮</b>：主 Agent 那条路把配置经 {@code toolConfig} 传进来就够了；<b>子体进程没有这条缝</b>
   * ——它走的是"最朴素的装配"（{@code AgentRuntime} 的无 {@code toolConfig} 构造），而 {@code AgentRuntime} 不在本单的改动面
   * 内。子体要的只是"它本地那个包自己的 deadline"（跨进程结算的窗口在父侧的网关上，不在它这里），所以给一个 <b>进程级</b>缺省：子体装配点在 {@code new
   * AgentRuntime(...)} 之前设一次即可（一个子体进程只有一个 Agent， 这条缝不存在"两个实例用不同窗口"的场景）。
   *
   * <p>非法值（≤ 0）在这里被忽略并留 WARN：真正的"值非法 ⇒ 响亮失败"发生在装配层读配置时 （{@code
   * DecisionWindows#load}），本字段只承载已经校验过的那一份。
   */
  private static volatile long defaultDecisionDeadlineMillis =
      DecisionPackages.DEFAULT_DEADLINE_MILLIS;

  /**
   * 设置本进程的缺省决策窗口（毫秒；≤ 0 忽略并留 WARN）。见 {@link #defaultDecisionDeadlineMillis} 的理由。
   *
   * @return 是否生效
   */
  public static boolean useDecisionDeadlineMillis(long deadlineMillis) {
    if (deadlineMillis <= 0L) {
      LOG.warn("忽略非正的决策窗口（毫秒）: {}——保持 {}", deadlineMillis, defaultDecisionDeadlineMillis);
      return false;
    }
    defaultDecisionDeadlineMillis = deadlineMillis;
    return true;
  }

  /**
   * 跨进程缝此刻可不可用（判据在 {@link CrossProcessDecisionSubmitter#available()}：现读 registry 与判定面）。
   *
   * <p>没接线（{@code null}）⇒ 假；接线了但缝不可用 ⇒ 也假——两者在 {@link #run(String)} 那里是同一个后果： 不绑队列。
   */
  private boolean crossProcessSeamAvailable() {
    return crossProcessSubmitter != null && crossProcessSubmitter.available();
  }

  /**
   * 回合收尾的汇总口（设计 §5 裁定 2 的落点）：本轮攒下的待裁决调用收成<b>一个包</b>，<b>同步</b>投递并等裁决（裁定 4）， 被批子集由提交口执行（装配层已接的那个执行者）。
   *
   * <p><b>调用时机 = 队列已解绑之后</b>（见 {@link #run(String)} 的 {@code finally}）：本方法会同步执行被批子集， 那一步不能再看见本轮的队列。
   *
   * <p><b>五个出口共用这一处</b>：主循环的七个 {@code return}（{@code CANCELLED} ×2 / {@code TURN_LIMIT} / {@code
   * TIME_BUDGET} / {@code LLM_ERROR} / {@code QUOTA} / {@code TOOL_CALL_LIMIT} / 正常 {@code
   * FINISHED}）全部经过 {@link #run(String)} 的 {@code finally}——所以"回合被打断"也收包（裁定
   * 8：打断不是"包不算数"，被打断的包照常走它的投递与作废语义）。
   *
   * <p><b>空队列 ⇒ 什么都不做</b>（验收判据 ④：零副作用）：不建包、不发事件、不投递、不落任何记录。 判空在桥里（{@code
   * DecisionApprovalBridge#flush}）也做了一次：那条路是"队列→提议请求"的唯一出口， 由它守"空队列不建包"比让每个调用方自己记得更可靠（桥的 flush 空队列
   * ⇒ 返回 {@code Optional.empty()}）。
   *
   * <p><b>失败不改写出口</b>（见 {@link #run(String)} 的 finally 注释）：建包被拒（{@code PACKAGE_ALREADY_OPEN}
   * 等）或投递异常 只落 ERROR 日志——本回合的 {@link TurnResult} 已经定了，收尾失败不该把 {@code TURN_LIMIT} 变成崩溃。 {@code
   * Error} 不吞（OOM 这类系统级故障照常上抛）。
   */
  private void flushPendingDecisions(PendingDecisionQueue queue) {
    if (queue == null) {
      // 未接线（没有桥也没有跨进程缝）：本回合本来就没绑队列，Ask 走的是既有审批闸——这里什么都不该做
      return;
    }
    // ★ P3b：优先走跨进程（"有缝就走"）。两条判据：
    //   ① 缝此刻可用 ⇒ 走它（子体的正常形态）；
    //   ② 缝不可用但**根本没有桥** ⇒ 仍然走它：close() 自己 fail-closed 处理"缝没了"（TOOL_NOT_FOUND ⇒
    //      当场作废 + WARN + expired 事件）。少了这一条，"绑队列时缝还在、收尾时缝没了"会变成"敏感调用悄悄消失"
    //      ——那正是本仓最不想要的形状。
    //   桥在场且缝不可用 ⇒ 退回桥那一条既有路（主 Agent 的终态装配就是"只有桥"这一档，行为逐字不变）。
    if (crossProcessSubmitter != null && (crossProcessSeamAvailable() || decisionBridge == null)) {
      try {
        crossProcessSubmitter
            .close(queue)
            .ifPresent(
                pkg ->
                    LOG.info(
                        "回合收尾：本轮待裁决调用已跨进程交回父侧并回填本实例（同步提交已结束） id={} state={} items={} proposerKey={}",
                        pkg.id(),
                        pkg.state(),
                        pkg.items().size(),
                        crossProcessSubmitter.proposerKey()));
      } catch (RuntimeException failure) {
        // 收尾不得改写本回合的出口（同下面桥那条路的口径）：本回合的 TurnResult 已经定了。
        // ★ 不回退到桥：包可能已经建出来并提交出去了，再走一遍桥等于对同一批条目投第二次（可能再问人一次）。
        LOG.error(
            "回合收尾的跨进程提交失败（本回合的出口不变；本轮待裁决调用零落盘——既不执行也不静默放行）" + " proposerKey={}",
            crossProcessSubmitter.proposerKey(),
            failure);
      }
      return;
    }
    if (decisionBridge == null) {
      // 理论上到不了（绑队列的前提是两者之一在场）：真到了就响亮说出来，别静默丢一批待裁决调用
      LOG.error("回合收尾：本轮有待裁决调用，但既没有跨进程缝也没有审批桥 ⇒ 零落盘（这两者应当在装配期同进同退） items={}", queue.size());
      return;
    }
    try {
      decisionBridge
          .flush(queue)
          .ifPresent(
              pkg ->
                  LOG.info(
                      "回合收尾：本轮待裁决调用已收成决策包 id={} state={} items={} caller={}（同步投递已结束）",
                      pkg.id(),
                      pkg.state(),
                      pkg.items().size(),
                      caller));
    } catch (RuntimeException failure) {
      LOG.error("回合收尾的决策包投递失败（本回合的出口不变；本轮待裁决调用零落盘——既不执行也不静默放行） caller={}", caller, failure);
    }
  }

  /** 解绑本轮的取数口（{@code finally} 里必到）：只清自己绑的那一个，绝不动别人的绑定（见 {@link DecisionTurnScope}）。 */
  private static void clearPendingDecisions(PendingDecisionQueue queue) {
    if (queue != null) {
      DecisionTurnScope.clear(queue);
    }
  }

  /**
   * 会话压缩的接线点（T17）：回合边界跑一次 {@link Compactor}，把结果落到工作集/槽/存储与事件上。
   *
   * <p><b>档 1/2 只改内存工作集</b>（{@link #replaceWorkingSet}）：档 1 把中段换成披露占位；档 2 把中段摘要写进 {@link
   * CompactSummarySlot}（装配器据此渲染 {@code COMPACT_SUMMARY} 层），工作集只剩尾部。两者都<b>不碰</b>存储——已落库的行照旧
   * append-only，重启后 {@code load} 回来的是完整历史（档 1/2 的压缩只活在进程内存里，跨不过重启——R1 档 3 的存在意义正是让压缩基线跨重启存活）。
   *
   * <p><b>档 3 落库</b>（{@link #persistSnapshot}）：摘要经显式 {@code compact} 记录进入会话，随后工作集按 {@code load}
   * 的权威形态重灌，槽清空（摘要已进入会话本身，层里再留一份就是同一内容出现两遍）。
   *
   * <p><b>档 3 的摘要基准</b>（{@link #snapshotBaseline}，F3）：工作集里只要还有"只被占位代表"的内容，基准就必须回到 {@code
   * store.load} 的权威全量视图——否则那段内容既不进摘要、又被新压缩点划进"之前"，重启后从基线里静默消失。
   *
   * <p><b>事件（R4/R7/披露）</b>：{@code conversation.compact}（档位/丢弃条数/保留条数/作用域原长/摘要 token）、摘要调用的 {@code
   * llm.call} 记账、以及降级与"无可切点"的 {@code decision}——压缩这件事在事件流里全程可见。
   *
   * @param userMessage 本轮用户输入：仅用于档 2 落地后的可见性守卫（{@link #requireSummaryVisibleInAssembler}）—— {@code
   *     composition} 与 {@code buildRequest} 同源，入参必须与本回合组请求时一模一样，否则验的不是同一个东西
   */
  private void compactIfNeeded(String userMessage) {
    if (compactor == null) {
      return;
    }
    Compactor.Result result =
        compactor.compact(history, compactSummary.current(), snapshotBaseline());
    if (result.summarizerCall() != null) {
      // R7：摘要是额外 LLM 调用——不记账的话，P2-3 的 token 汇总看不见压缩成本（D18 名不副实）
      Compactor.SummarizerCall call = result.summarizerCall();
      LlmResponse response = call.response();
      emit(
          EventTypes.LLM_CALL,
          Map.of(
              "phase",
              "compact",
              "inputTokens",
              response.inputTokens(),
              "outputTokens",
              response.outputTokens(),
              "cacheReadTokens",
              response.cacheReadTokens(),
              "cacheWriteTokens",
              response.cacheWriteTokens(),
              "model",
              response.model(),
              "latencyMs",
              call.latencyMs()));
    }
    if (result.note() != null) {
      // 降级（摘要不可用→退回档 1）或无可切点：绝不静默——档位与原因都落 decision
      emitDecision(
          result.compacted() ? "COMPACT_DEGRADED" : "COMPACT_SKIPPED",
          Map.of("reason", result.note()));
    }
    switch (result.tier()) {
      case NONE -> {
        // 未压缩：工作集原样（含"摘要器给了 response 但解析不出 → 退回不动"这条路：只记账，不动工作集）
      }
      case MICRO, SUMMARY -> {
        if (result.summary() != null) {
          compactSummary.set(result.summary());
          // 摘要已进槽：此刻必须能在装配器渲染的 COMPACT_SUMMARY 层里看见它（看不见就是接线失配，见守卫）。
          // 求值在换工作集之前——失败时不留下"工作集已换、上下文却残缺"的半截状态
          requireSummaryVisibleInAssembler(userMessage);
        }
        replaceWorkingSet(result.workingSet());
        if (result.tier() == Compactor.Tier.MICRO) {
          // 档 1 把中段换成了占位：工作集自此不再包含全部内容——下次档 3 的摘要基准必须回到存储（F3）
          uncoveredHiddenContent = true;
        }
      }
      case SNAPSHOT -> {
        persistSnapshot(result);
        // 工作集已按 store.load 的权威形态重灌：内存与存储重新同源，基准可以退回工作集
        uncoveredHiddenContent = false;
      }
    }
    if (result.compacted()) {
      emit(
          EventTypes.CONVERSATION_COMPACT,
          Map.of(
              "tier",
              result.tier().name().toLowerCase(Locale.ROOT),
              "droppedMessages",
              result.droppedMessages(),
              // 保留的"原消息"条数 = 作用域原长 - 丢弃条数：占位与摘要都不是原消息，不计入（恒等式对三档都成立）
              "keptMessages",
              result.baselineMessages() - result.droppedMessages(),
              "baselineMessages",
              result.baselineMessages(),
              "summaryTokens",
              result.summary() == null ? -1 : result.summary().render().length() / 4));
    }
  }

  /**
   * 档 3 的摘要基准（F3）：压缩点将要隐藏的<b>全部</b>内容。
   *
   * <p>工作集与存储同源时它就是工作集（内存里那份就是权威视图，多读一次库没有意义）。但档 1 会把中段换成一行"已丢弃 N 条"占位——此后工作集
   * <b>不再</b>包含压缩点将要隐藏的全部内容（见 {@link #uncoveredHiddenContent}）。此时基准回退到 {@code store.load} 的权威形态，
   * 让那段只被占位代表的内容重新进入摘要器输入。
   *
   * <p>为什么不用"永远取 store"：工作集与存储同源时两者内容相同，而 {@code load} 是每回合多两条查询的额外成本；且工作集里还带着本回合尚未
   * 落库的尾部（正常情况下与存储一致，但"以工作集为准"是本来的语义）。只有出现覆盖缺口时才值得升级到存储视图。
   *
   * <p>{@code load} 走的是本实例持有的那个 store，仍在 R11 的结构性回避之内（管线存活期间不存在第二个 store 实例，且此处是回合边界—— 上一回合的落库早已
   * return）。
   */
  private List<LlmMessage> snapshotBaseline() {
    if (!uncoveredHiddenContent) {
      return history;
    }
    List<LlmMessage> stored = store.load(conversationId);
    // 取不到（空实现/空会话）时退回工作集：不把"读不到"当成"没有内容"——真要是空会话，档 3 的落库守卫会另行响亮失败
    return stored.isEmpty() ? history : stored;
  }

  /**
   * 档 2 的可见性守卫（F2）：摘要<b>已写入槽</b>，但装配器渲染出的 {@code COMPACT_SUMMARY} 层里看不见它 → 接线失配，响亮失败。
   *
   * <p>必须失败而不是继续：档 2 落地后工作集只剩尾部，被丢弃的中段既不在工作集里、又没进层——请求里"无摘要、无占位"，头部与中段
   * <b>静默消失</b>（本项目最坏的失败模式）。失配的成因不是运行期数据问题，而是<b>编程契约违反</b>（管线的槽与装配器读的槽不是同一个实例）， 照 {@code
   * saveHistory} 守卫与 {@link #persistSnapshot} 空会话守卫的纪律抛 {@link IllegalStateException}。
   *
   * <p><b>判据为什么用 {@code composition} 而不是 {@code buildRequest}</b>：接口把 {@code composition} 定为与
   * {@code buildRequest} <b>同源</b>（同一输入 → 同一组段落），装配器读哪个槽它就反映哪个槽，且不必解析消息文本、不必猜头部/尾部（那条路
   * 会误伤"摘要内容恰好与尾部重合"的合法情形）。层估算为 {@code 0} 或缺失即"该层没有内容"。
   *
   * <p><b>求值时机在写入工作集之前</b>：不满足时不留下"工作集已换、上下文却残缺"的半截状态——失败时工作集一个字节都没动。
   */
  private void requireSummaryVisibleInAssembler(String userMessage) {
    Integer layerTokens =
        assembler
            .composition(config, userMessage, List.copyOf(history), List.of())
            .estimatedTokens()
            .get(ContextLayer.COMPACT_SUMMARY);
    if (layerTokens == null || layerTokens <= 0) {
      throw new IllegalStateException(
          "压缩摘要已写入槽，但装配器的 COMPACT_SUMMARY 层为空——管线持有的摘要槽与装配器读取的槽不是同一个实例"
              + "（接线必须用 assembler.compactSummarySlot() 取槽，拒绝静默丢弃被压缩的历史）: "
              + conversationId);
    }
  }

  /** 换掉内存工作集（{@link #history} 是 final 字段，只能就地替换内容）。 */
  private void replaceWorkingSet(List<LlmMessage> workingSet) {
    history.clear();
    history.addAll(workingSet);
  }

  /**
   * 档 3（快照续接）的落库：用本实例持有的 {@link #store} 先把摘要写成显式 {@code compact} 记录，再按 {@code load} 的权威形态重灌工作集。
   *
   * <p><b>顺序有讲究</b>：落库失败时异常向上抛、工作集<b>一个字节都没动</b>（先清空再落库的话，失败就会留下空工作集）。
   *
   * <p><b>空会话守卫</b>：落库成功后 {@code load} 必然至少返回摘要本身（{@code render()} 恒非空）——回来是空列表说明这个 store 根本
   * 没接受压缩（例如"不落库"的空实现被误接了压缩器）→ 响亮失败，绝不静默把工作集清空。
   */
  private void persistSnapshot(Compactor.Result result) {
    store.compact(conversationId, result.summary().render());
    List<LlmMessage> rehydrated = store.load(conversationId);
    if (rehydrated.isEmpty()) {
      throw new IllegalStateException(
          "压缩已落库但 load 回来是空会话——store 未接线压缩记录（拒绝静默清空工作集）: " + conversationId);
    }
    replaceWorkingSet(rehydrated);
    compactSummary.clear();
  }

  /**
   * 把本回合<b>新增的尾部</b>追加进历史：内存 {@link #history} 与 {@link #store} 都只追加，绝不重写已有前缀。
   *
   * <p>为什么不是"清空重写全量"：机制上的 clear+addAll 直译到持久层就变成每回合 DELETE + 全量 INSERT——行标识与插入 时序每回合全变，是
   * prompt-cache 前缀稳定的隐性敌人。这里改成内容与机制一致的 append-only。
   *
   * <p><b>增量怎么算</b>：{@code messages} 的结构恒为 {@code [system] + 回合起点历史 + 本回合新增...}（assembler 契约：第一条必为
   * system、回合起点历史<b>原样</b>紧随其后、本轮 user 收尾），故 {@code messages[replayedSize + 1]} 起就是本回合新增的部分——
   * 用同一份列表按下标推出，不比对内容（{@code replayedSize} 是组请求前取定的历史长度）。推导式本身是精确算术，不靠猜。
   *
   * <p><b>但下标推导有条件</b>：本轮 user 必须<b>恰好</b>落在 {@code replayedSize + 1}。assembler 少回灌历史（该下标落到别处）或在头部
   * 多插一条（下标整体前移）都会让切片错位，表现从"静默丢弃本回合尾部"到"把已落库的历史当新增重复追加"（append-only 库里留下重复前缀）不等；
   * 且错位多数<b>不</b>落在越界上——不抛任何异常，只是写错（越界时 {@code ArrayList.subList} 抛的也是 {@link
   * IllegalArgumentException}，不是 {@link IndexOutOfBoundsException}）。故切片前先按契约校验（见下），把静默错写一律变成响亮失败。
   *
   * <p><b>前置守卫不误伤合法压缩</b>：合法压缩（T17 三档都算：档 3 走 {@link ConversationStore#compact} 落库后重灌，档 1/2
   * 只换内存工作集）改的都是 {@code history} 本身，而 {@code replayedSize} 取的是<b>组请求前</b>的实际长度——回灌长度随之一同变短，
   * 守卫只看"回灌长度是否被原样尊重"、不看长短，故不响。被守卫拦下的是"assembler <b>擅自</b>改回灌长度/本轮 user 位置"，与压缩无关。
   *
   * <p><b>守卫的辨识范围（别把它想得比实际宽）</b>：它<b>只</b>校验两点——{@code size ≥ replayedSize + 2}，且 {@code
   * messages[replayedSize + 1]} 这条的 role 是 {@code user}。落在其外的情形<b>不</b>被辨识：等长的内容改写/重排（与"不比对内容"的
   * 设计取舍相抵）、在本轮 user <b>之后</b>再插消息（回合内本就会追加 assistant/tool，无法与之区分）。它拦的是"assembler
   * <b>擅自</b>改变回灌长度或本轮 user 位置"这一类<b>编程契约违反</b>，不是运行期数据状态，故抛 {@link IllegalStateException}
   * 而非参数类异常。守卫在任何内存/落库写入<b>之前</b>求值——不满足时不留下半截写入。
   *
   * <p>七条终止路径（FINISHED/TOOL_CALL_LIMIT/TURN_LIMIT/TIME_BUDGET/QUOTA/LLM_ERROR/CANCELLED）都必须先经过这里再
   * return——否则下一回合回灌的历史残缺，Agent 就会失忆。
   *
   * <p>落库失败（磁盘/DB 异常）不吞：内存历史已追加、异常向上抛，回合以失败告终而不是静默丢持久化。尾部是逐条 append（Store 只保证 单次调用原子，见 {@link
   * ConversationStore}）——中途失败会留下"已落库的前缀"，重启后正是从那个前缀续起。
   */
  private void saveHistory(List<LlmMessage> messages, int replayedSize) {
    // assembler 契约校验：本轮 user 必须恰好是 messages[replayedSize + 1]。最小合法长度 = [system] + 回灌历史
    // （replayedSize 条）+ 本轮 user = replayedSize + 2（此刻本回合尚未产生任何新消息）；长度不足或该位不是
    // user → 下标推导失去意义，响亮失败
    if (messages.size() < replayedSize + 2
        || !LlmMessage.ROLE_USER.equals(messages.get(replayedSize + 1).role())) {
      throw new IllegalStateException(
          "assembler 违反契约：历史未按其长度原样回灌、本轮 user 未紧随其后（replayedSize="
              + replayedSize
              + ", size="
              + messages.size()
              + "）");
    }
    List<LlmMessage> appended = messages.subList(replayedSize + 1, messages.size());
    history.addAll(appended);
    for (LlmMessage message : appended) {
      store.append(conversationId, message);
    }
  }

  /**
   * "不落库"的默认实现：9 参构造（{@code AgentRuntime} 与既有测试）委派到它，行为与持久化接入前逐字节一致。
   *
   * <p>{@code load} 返回空 → 构造时不 hydrate；{@code append}/{@code compact} 皆空操作。放在本类内部而非 AgentLib 公开
   * API：它只是"没有 Store"这一情形的表达，没有独立复用价值。
   */
  private static final class DiscardingConversationStore implements ConversationStore {

    static final DiscardingConversationStore INSTANCE = new DiscardingConversationStore();

    @Override
    public void append(String conversationId, LlmMessage message) {
      // 有意为空：不落库
    }

    @Override
    public List<LlmMessage> load(String conversationId) {
      return List.of();
    }

    @Override
    public void compact(String conversationId, String summary) {
      // 有意为空：不落库
    }
  }

  private void executeToolCall(List<LlmMessage> messages, ContentPart.ToolCall call) {
    // ★ S4-C 脱敏（红线 4：命令明文不进事件库）：tool.call 的 args 取工具自报的参数视图（AgentTool.ledgerArgs）。
    //   ToolContext 的构造<b>上移</b>到 emit 之前——它是纯的（只做 requireNonNull + Map.copyOf，无副作用），
    //   于是"先建上下文、再按上下文脱敏、最后写事件"这个顺序成立，且既有工具（不覆写 ledgerArgs）行为逐字不变。
    //   工具不存在时退回原始 args（未知工具名不该把事件写崩，也不该让这一行成为 NPE 源）。
    // S6：调用者身份 = 主 Agent（实例 id + <b>现读的档位</b>）——档位从运行时缝取（装配层放进 toolConfig 的
    // CommandModeHolder），没有该缝时按 FULL = 本功能引入前的行为。身份由宿主在这里装配：模型给不出、改不了。
    ToolContext context =
        new ToolContext(
            caller,
            permissionSet,
            toolConfig,
            call.arguments(),
            AgentIdentity.mainFrom(toolConfig));
    // 另一次 findBy：authorizer 内部还会各查一次。这是<b>有意</b>的重复查——判定不搬到这个 emit 点来（见 ToolCallAuthorizer 的类注释）。
    AgentTool tool = registry.find(call.name()).orElse(null);
    Map<String, Object> ledgerArgs = call.arguments();
    if (tool != null) {
      Map<String, Object> reported = tool.ledgerArgs(context);
      // reported 为 null 只可能是实现违约：退回原始 args（既有行为）而不是让 Map.of 抛 NPE 把事件写崩
      if (reported != null) {
        ledgerArgs = reported;
      }
    }
    emit(
        EventTypes.TOOL_CALL, Map.of("tool", call.name(), "callId", call.id(), "args", ledgerArgs));

    ToolResult result;
    try {
      result = authorizer.execute(registry, call.name(), context);
    } catch (RuntimeException e) {
      LOG.error("工具执行抛出未捕获异常 tool={}", call.name(), e);
      result = ToolResult.error("TOOL_CRASH", "工具内部异常: " + e.getMessage());
    }

    if (ToolExecutionGuard.DENIED.equals(result.code())) {
      emit(EventTypes.PERMISSION_DENIED, Map.of("tool", call.name(), "reason", result.message()));
    }
    emit(
        EventTypes.TOOL_RESULT,
        Map.of(
            "tool", call.name(),
            "callId", call.id(),
            "ok", result.success(),
            "message", result.message()));

    // ToolResult 契约：成功时 message 为给 LLM 的文本，失败时 message 为错误信息（二者都非 null）——
    // ContentPart.ToolResult 要求 content 与 error 恰好一个非空：成功走 content（error 置空）、失败走 error（content 置空）
    String content = result.success() ? result.message() : null;
    String error = result.success() ? null : result.message();
    messages.add(
        LlmMessage.tool(new ContentPart.ToolResult(call.id(), call.name(), content, error)));
  }

  /**
   * 最近一次回合的会话历史快照（<b>不含 system 头</b>；M2 供 A2A/AG-UI 读会话用）。
   *
   * <p>与 {@link #run(String)} 内部消息序列的关系：{@code [system] + lastHistory()} 即完整请求消息。
   *
   * <p><b>契约冻结</b>：接 {@link ConversationStore} 之后本方法读的仍是内存工作集（不是库），形状与语义与持久化前一致—— 不含 system
   * 头、返回不可变快照（后续回合不回改已发出的快照）。
   *
   * <p><b>压缩之后的形状（按实例状态分两种，勿混为一谈）</b>：{@code "摘要 + 压缩点之后的消息"} 只对<b>新实例 hydrate 之后</b>为真（构造时 {@code
   * store.load(...)} 灌入，故进程重启即反映最近一次压缩）。<b>活实例</b>的工作集只在构造时灌一次——T17
   * 接线压缩后，本方法反映的是<b>本实例最近一次压缩后的工作集</b>： 档 3 落库后（压缩点即当时的末尾）等于 {@code load} 的权威形态（摘要消息打头）；档 1/2
   * 是纯内存视图（各自的压缩形态）。 <b>外部</b>（不经本类的压缩器）调用 {@link ConversationStore#compact}
   * 仍不会被回灌——那是"另一个写者"的情形，不在本类契约内。
   */
  public List<LlmMessage> lastHistory() {
    return List.copyOf(history);
  }

  private static List<io.mosire.agentlib.llm.ToolDef> toolsDefs(List<AgentTool> tools) {
    return tools.stream()
        .map(t -> new io.mosire.agentlib.llm.ToolDef(t.name(), t.description(), t.jsonSchema()))
        .toList();
  }

  private void emit(String type, Object payload) {
    EventWrite write = EventWrite.of(type, config.id(), toJson(payload));
    Event event = events.append(write);
    bus.publish(event);
  }

  private void emitDecision(String decision, Map<String, Object> extra) {
    emit(
        EventTypes.DECISION,
        extra.isEmpty()
            ? Map.of("decision", decision)
            : Map.of("decision", decision, "detail", extra));
  }

  private static String toJson(Object payload) {
    try {
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      LOG.error("事件 payload 序列化失败 type={}", payload, e);
      return "{}";
    }
  }
}
