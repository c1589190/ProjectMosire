package io.mosire.brain.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.context.ContextComposer;
import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.context.LlmRoundTrip;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.DecisionTurnScope;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmQuota;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.llm.QuotaExceededException;
import io.mosire.agentlib.llm.ToolDef;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.runtime.AgentLoop;
import io.mosire.agentlib.runtime.LoopState;
import io.mosire.agentlib.runtime.StopPolicy;
import io.mosire.agentlib.runtime.StopReason;
import io.mosire.agentlib.runtime.StopSignal;
import io.mosire.agentlib.runtime.ToolCallDispatcher;
import io.mosire.agentlib.runtime.TurnListener;
import io.mosire.agentlib.runtime.TurnResult;
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
import java.util.Optional;
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
 * <p><b>并发约定（R11 未尽项落定）</b>：{@link #window} 非线程安全——调用方必须保证任意时刻只跑一个回合： 每任务一个 runtime
 * 实例，或对外（A2A/AG-UI 等）按提交序串行化调度。选择"每任务一 runtime"= 会话隔离（各任务独立历史）； 选择"串行化"= 一个 Agent 实例的连续对话（A2A
 * 任务续接自然延续上下文）。{@link ConversationStore} 不是并发方案——它只保证"每次调用自身原子"， 上述契约不因持久化而放宽。
 *
 * <p><b>会话持久化（T16）</b>：带 {@code (store, conversationId)} 的构造把历史落到 {@link ConversationStore}——构造时
 * {@code load} 灌入内存工作集（重启续聊），每回合只把<b>新增的尾部</b>追加进去（prompt-cache 不变量：已落库的消息永不改写）。不带 store
 * 的构造委派到"不落库"实现，行为与持久化接入前逐字节一致。
 *
 * <p><b>上下文窗口（窗口下沉）</b>：会话状态自本批起由库侧的 {@link ContextWindow} 承载（一类一真相：窗口就是"此刻看得见的那串消息"
 * 本身，本类不再另建一份列表）；每次 LLM 往返都经 {@link LlmRoundTrip} 从窗口现组请求——请求永远派生自窗口，不缓存第二份真相。 该往返方法把"本轮输入 +
 * 答复"的写回当作往返的一部分（见 {@link AssemblerComposer} 与 {@link #saveHistory}）。
 *
 * <p><b>通用循环骨架（循环下沉）</b>：反过来，本类不再自己写 {@code while}——回合主循环已下沉到库侧 {@link AgentLoop}，本类是<b>骨架的
 * 使用者</b>：{@link StopPolicy} 表达七种终止原因里"何时停"的判定（顺序照旧），{@link TurnListener} 承载记账/事件/占位回填/落库， {@link
 * ToolCallDispatcher} 接既有工具执行入口。事件与落库顺序逐字节不变（设计 {@code 设计-通用循环骨架.md} §4.5）；"两份循环真相" 是这条边界要防的事（同稿 §八
 * 风险 1）。
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
   * <b>本会话的上下文窗口</b>（窗口下沉批）：它<b>同时承载会话标识与消息工作集</b>——{@link #conversationId()} 读的是 {@link
   * ContextWindow#id()}，历史读写走 {@link ContextWindow#messages()} /append/replace，落库走 {@link
   * ContextWindow#unsavedTail()}。为什么把这两件事并到一个对象里：它们本来就必须一起换（重置 = 换 id + 清内存），分两个字段就有
   * "换了一半"的中间态可写；合成一条后，「换会话」只有一个落点。
   *
   * <p><b>为什么不再需要 {@code volatile}</b>：前身是一个 {@code volatile String conversationId} +
   * 一个普通字段列表。窗口整体是一个 final 引用，而"重置与回合可能在不同线程上发生"这件事由调用方的<b>串行化点</b>保证（见类 Javadoc：
   * 每任务一实例，或按提交序串行调度）——串行执行器的任务提交本身就是 happens-before 边，交接可见性由它承担，不再是单字段的职责。 反过来，如果保留单字段 volatile
   * 而消息列表不 volatile，那才是不成立的可见性口径。
   *
   * <p><b>并发语义与既有口径一致</b>：非线程安全，调用方串行（同 {@link ContextWindow} 的类注）。
   */
  private final ContextWindow window;

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
   * <p><b>为什么 {@code decisionBridge}/{@code crossProcessSubmitter} 带 {@code EI_EXPOSE_REP2}
   * 抑制</b>：它们是<b>注入的协作者</b> （管线在回合收尾必须调它们：收成包、投递/提交、等裁决、执行被批子集），与本类既有的 {@code events}/{@code
   * bus}/{@code llm}/{@code guard} 完全同类——<b>故意的共享引用（依赖注入）</b>，不是"把内部状态漏出去"；字段是 {@code private
   * final}，本类不外传、不暴露取值口。
   *
   * <p><b>这条抑制今天来回过一次，把经过与口径写在这里，免得后人再来回</b>：它此前挂过同样一条，2026-10-17 一次「清 {@code target/classes} 后只跑
   * SpotBugs」的检查给出 {@code BugInstance size is 0}，于是按 AGENTS §三「抑制多余也是要修的告警」删掉，
   * 并留下「若将来真报了再按当时的事实加回来」的话；同日**上下文窗口下沉批次**（采纳 {@code ContextWindow}/{@code LlmRoundTrip}）
   * 完成后的<b>清源重编 + 全量 {@code verify}</b> 又报出 {@code EI_EXPOSE_REP2}（{@code BugInstance size is
   * 1}，此外无其它告警） ⇒ 按当时写下的话加回来。⇒ **口径：这条告警的可见性依赖构建状态**（增量 vs 全清、aux classpath 里 {@code
   * DecisionApprovalBridge} 是否被一并分析），一次「没报」不足以证明抑制多余——**要动它的状态，先做一次「清源重编 + 全量 {@code verify}」**，
   * 不要拿一次 {@code spotbugs:check} 的 0 就删。
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
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
    String effectiveId = this.store.currentConversationId().orElse(requestedId);
    // hydrate：既有会话（进程重启前落的库）先灌回内存工作集，否则"重启续聊"名存实亡。
    // 构造进来的内容在窗口口径里算"已处置"（unsavedTail 为空）——库里已有的那份不该被再 append 一遍。
    this.window = new ContextWindow(effectiveId, this.store.load(effectiveId));
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
   *   <li><b>窗口换 id</b>（{@link ContextWindow#reset(String)}）：此后落库/读库都按新 id 寻址（旧会话的行一条不动）；
   *   <li><b>窗口清空</b>（同一次 {@code reset}）：否则下一回合会把上一个会话的历史原样回灌给模型；
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
   * <p><b>并发约定</b>：与 {@link #run(String)} 一样只要求调用方<b>串行</b>——重置改的是实例级共享、非线程安全的 {@link #window}，
   * 必须与回合排在同一串行化点上（例如同一个单线程执行器），<b>不得</b>在另一个线程上与在途回合并发调用（见类 Javadoc 的 R11）。
   *
   * @param newConversationId 新会话标识（非 null、显式给定——管线不生成 id，也不猜）
   */
  public void resetConversation(String newConversationId) {
    Objects.requireNonNull(newConversationId, "newConversationId");
    // 指针先落库、内存后切换：写失败时宁可不切（"重置未生效"）也不切换（那会留下"运行期看着已重置、重启却复活旧历史"的分裂态）
    store.setCurrentConversationId(newConversationId);
    // 窗口的 reset 一次性完成"换 id + 清空"（见 ContextWindow#reset：它只负责内存那一半，存储指针归宿主，正是上面那一行）
    window.reset(newConversationId);
    uncoveredHiddenContent = false;
    // compactSummary 的非空由构造器的 requireNonNull 保证（本类没有第二条赋值路径），故无需空值分支
    compactSummary.clear();
  }

  /**
   * 当前会话标识（重置后即为新 id）——装配层据此向调用方回报"重置前的会话是哪个"。
   *
   * <p><b>本批的落点变化</b>：id 由 {@link ContextWindow#id()} 承载（会话标识与工作集是同一个对象的两面，见 {@link #window}
   * 的注释），本方法只是转发；签名与语义一字未动。
   *
   * <p>读的是窗口的即时值：在共享串行执行器上，它反映的是"最后一次已执行的重置/构造"。重置的<b>请求</b>排进执行器到真正执行之间， 本方法仍返回旧
   * id（切换是一个动作，不是一个声明）。
   */
  public String conversationId() {
    return window.id();
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
   * <p>约定：方法是同步的（网络/子进程调用在工具内自行发生）；回合间共享 {@link #window} （一个 Agent 实例的连续对话）。
   *
   * <p><b>组请求的形状（本批的关键）</b>：请求不再是"回合起点算一次、此后复用同一个列表"，而是<b>每次往返都从窗口现组</b>（{@link LlmRoundTrip#call}
   * 的契约）。头部由 {@link AssemblerComposer} 承担：它每回合算<b>一次</b> system 头（与旧行为逐字节一致—— 旧行为里 {@code
   * buildRequest} 也是每回合只调一次），此后每次往返复用同一个头 —— 因此请求恒为 {@code [头] + 窗口}， 本轮 user 只在第 1 轮的 {@code
   * turnInput} 里出现一次，不会被追加两遍。
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
    // 每回合一个编排器：头部只算一次、回合内复用（见 AssemblerComposer 的类注）；本轮 user 作为 turnInput 随第 1 轮往返写回
    ContextComposer composer = new AssemblerComposer(assembler, config, userMessage);
    // 本回合的输入（相对窗口新增的部分）：第 1 轮是这条 user，此后窗口里已经有了它（也含工具结果），故为空——
    // 若每轮都带上它，请求里就会出现两条同样的 user（这正是本批要避开的形状）
    List<LlmMessage> turnInput = List.of(LlmMessage.user(userMessage));

    try {
      return runTurn(composer, turnInput, userMessage, deadline, quota, tools);
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
   * 装配并运行库侧的通用循环骨架（「通用循环骨架下沉」批的 Brain 侧落点）：{@link AgentLoop} 负责结构（何时问停、组请求、
   * 发信、写回、解析工具调用、逐条分派），本方法只注入三件宿主策略——{@link StopPolicy}（七种终止原因里"何时停"的判定， 顺序照旧）、{@link
   * TurnListener}（记账/事件/占位回填/落库）、{@link ToolCallDispatcher}（既有工具执行入口）。
   *
   * <p><b>为什么循环体从这里消失</b>：同一个骨架要能被 Brain 以外的程序复用；两套循环语义就是两套真相（设计 §八 的风险 1）。 本方法因此只做装配，不再持有 {@code
   * while}——七个终止出口全部变成 {@link AgentLoop} 的回调，事件与落库顺序逐字不变 （设计 §4.5）。
   *
   * <p><b>装配粒度是"每回合一个 {@link AgentLoop}"</b>：{@code composer}/{@code turnInput}/{@code
   * deadline}/{@code quota} 都是回合级对象（{@link AssemblerComposer} 的头部每回合只算一次、由本回合共享），骨架实例不该跨回合复用。
   *
   * @param composer 本回合的编排器（头部只在它的首次 {@code compose} 里算一次，回合内复用同一个实例——见 {@link AssemblerComposer}）
   * @param turnInput 本回合的输入：第 1 轮是这条 user，此后逐轮传空（窗口里已经有了它，见 {@link #run(String)}）
   * @param userMessage 本轮用户原文（<b>只为事件 payload</b>）：与旧实现一样原样透传，不从窗口或 turnInput 反推—— {@code
   *     CONVERSATION_TURN} 的 {@code input} 与迁移前逐字节相同
   * @param deadline 本回合的时间预算截止时刻（由 {@link #run(String)} 在压缩之后计算；仍用 {@link Instant#now()} 判， 不用骨架的
   *     {@code elapsedMillis}——避免把"回合外耗时"算进预算）
   * @param quota 本回合的 token 配额账本；{@code null} = 未装配配额（{@code quotaMaxTokens=0}）
   * @param tools 本回合的可见工具面（注册表 ∩ 权限判定面，见 {@link #visibleTools()}）
   */
  private TurnResult runTurn(
      ContextComposer composer,
      List<LlmMessage> turnInput,
      String userMessage,
      Instant deadline,
      LlmQuota quota,
      List<AgentTool> tools) {
    // 判定顺序与改造前逐字一致：cancelled → maxTurns → deadline；工具批中间只查取消（设计 §4.2）
    StopPolicy stopPolicy = new TurnStopPolicy(deadline);
    // 工具执行唯一入口一字不改：判定/审批/事件/脱敏/结果写回全在 executeToolCall 里（设计 §4.4）
    ToolCallDispatcher dispatcher = (call, state) -> executeToolCall(call);
    // 每回合一个监听器：事件/落库顺序逐字照抄旧循环的七个出口（设计 §4.3 与 §4.5 对照表）
    TurnListener listener = new TurnEvents(userMessage, quota);
    return AgentLoop.builder()
        .window(window)
        .composer(composer)
        .llm(llm)
        .dispatcher(dispatcher)
        .stopPolicy(stopPolicy)
        .listener(listener)
        .maxToolCallsPerResponse(config.maxToolCallsPerTurn())
        .build()
        .runTurn(turnInput, tools);
  }

  /**
   * 本回合的 {@link StopPolicy}（设计 §4.2）：把旧循环的两处终止判定原样搬到骨架的两个判定点上。
   *
   * <p><b>两个判定点为什么不同</b>：往返前的 {@link #check} 保持旧实现的三判顺序（取消 → 轮数 → 墙钟）； 工具批中间的分发前检查（{@link
   * #checkToolCallBoundary}）<b>只判取消</b>——迁出前的循环在工具批里本来就只查 {@code
   * cancelled}。若批内也跑完整判定，墙钟恰在工具批中途到期就会提前终止、把本批剩余调用留成悬空 {@code tool_call}， 而旧实现会跑完这批、到下一个回合顶才以
   * {@code TIME_BUDGET} 退出——那是本批"行为逐字不变"判据之外的新悬空来源 （部分供应商会拒收下一回合请求）。
   *
   * <p>{@code deadline} 是回合级对象（由 {@link AgentPipeline#run(String)} 在压缩之后计算），故策略实例也按回合创建， 不跨回合复用。
   */
  private final class TurnStopPolicy implements StopPolicy {

    private final Instant deadline;

    private TurnStopPolicy(Instant deadline) {
      this.deadline = deadline;
    }

    @Override
    public Optional<StopSignal> check(LoopState state) {
      if (cancelled) {
        return Optional.of(new StopSignal(StopReason.CANCELLED, ""));
      }
      if (state.rounds() >= config.maxTurns()) {
        return Optional.of(new StopSignal(StopReason.TURN_LIMIT, ""));
      }
      if (Instant.now().isAfter(deadline)) {
        return Optional.of(new StopSignal(StopReason.TIME_BUDGET, ""));
      }
      return Optional.empty();
    }

    @Override
    public Optional<StopSignal> checkToolCallBoundary(LoopState state) {
      // 旧实现工具批内只判 cancelled；不在此处引入预算类终止（见类注：会造成新的悬空 tool_call）
      return cancelled ? Optional.of(new StopSignal(StopReason.CANCELLED, "")) : Optional.empty();
    }
  }

  /**
   * 本回合的 {@link TurnListener}（设计 §4.3）：把旧循环七个出口的<b>事件与落库顺序逐字</b>搬到骨架的回调点上。
   *
   * <p><b>三条容易写错的顺序</b>（改这里之前先读设计 §4.5）：
   *
   * <ol>
   *   <li>{@code LLM_CALL} 记账必须在配额判定<b>之前</b>落（D18：超限也不能丢"这次调用确实发生了"）；
   *   <li>{@code quota.record} 居中——它抛 {@link QuotaExceededException} 时返回 {@link StopReason#QUOTA}
   *       信号， 骨架随即走 {@link #onStop} 出口；
   *   <li>{@code CONVERSATION_TURN} 必须在配额判定<b>之后</b>发（超限那轮不落会话事件，与旧实现逐字一致）。
   * </ol>
   *
   * <p><b>为什么终止回调自己调 {@code saveHistory()}</b>：骨架的 exactly-once 契约保证一次回合只会有其中一个终止
   * 回调被调用，把落库放进回调里之后，"结果已返回、历史没保存"的窗口不存在；各出口仍各调一次、顺序仍是"先事件后落库"。
   */
  private final class TurnEvents implements TurnListener {

    private final String userMessage;
    private final LlmQuota quota;

    private TurnEvents(String userMessage, LlmQuota quota) {
      this.userMessage = userMessage;
      this.quota = quota;
    }

    @Override
    public Optional<StopSignal> onRoundTrip(LlmResponse response, long latencyMs, LoopState state) {
      // ① llm.call 记账（D18）在配额判定之前落库：超限终止也不能丢"这次调用确实发生了"的事实
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
              state.rounds()));
      // ② 配额判定居中：超限 ⇒ 返回信号，骨架转 onStop 落 decision 事件并落库
      if (quota != null) {
        try {
          quota.record(response.inputTokens(), response.outputTokens());
        } catch (QuotaExceededException e) {
          // ★ 已知行为变更（设计 §5.1，控制方裁定）：本出口<b>保留</b>模型答复。
          //   旧行为：配额判定发生在 messages.add(assistant) 之前 ⇒ 超限时答复既不进历史、也不落库，只留 llm.call 记账。
          //   新行为：写回是往返的一部分（LlmRoundTrip 在 chat 成功后立即 append）⇒ 终止时答复已在窗口内，
          //   下面的 saveHistory() 会把它落库。这不是疏漏，是有意选择的语义：token 已经花了、答复已经产生了，
          //   把它从历史里丢掉并不对称——其余所有"已产生答复"的出口（FINISHED / TOOL_CALL_LIMIT / TURN_LIMIT /
          //   TIME_BUDGET / CANCELLED）本来都保留答复，QUOTA 曾是唯一的例外。
          //   ★ 禁止用"把刚 append 的答复再删掉"之类反向 hack 去凑旧行为（那会把窗口的只追加语义破掉）。
          return Optional.of(new StopSignal(StopReason.QUOTA, e.getMessage()));
        }
      }
      // ③ 会话事件（答复的写回已由 LlmRoundTrip 完成，这里只是落事件）
      emit(
          EventTypes.CONVERSATION_TURN,
          Map.of(
              "turns",
              state.rounds(),
              "input",
              userMessage,
              "output",
              response.textPart().orElse(""),
              "model",
              response.model()));
      return Optional.empty();
    }

    @Override
    public void onFinished(String finalText, LoopState state) {
      saveHistory();
    }

    @Override
    public void onToolCallLimit(List<ContentPart.ToolCall> calls, LoopState state) {
      emitDecision(
          "TOOL_CALL_LIMIT",
          Map.of("toolCalls", calls.size(), "max", config.maxToolCallsPerTurn()));
      // 悬空 tool_call 修复：本回合一个都没执行，回灌前为每个调用补占位失败结果——
      // 否则历史里存在无应答的 tool_call，下一回合部分供应商会拒收请求
      for (ContentPart.ToolCall call : calls) {
        window.append(
            LlmMessage.tool(
                new ContentPart.ToolResult(call.id(), call.name(), null, "回合因单次响应工具调用数超限中止")));
      }
      saveHistory();
    }

    @Override
    public void onStop(StopSignal signal, LoopState state) {
      emitDecision(signal.reason().name(), stopDetail(signal));
      saveHistory();
    }

    @Override
    public void onLlmError(LlmException error, List<LlmMessage> roundInput, LoopState state) {
      // 失败路径的接线要求（设计 §5.2）：LlmRoundTrip 在 chat 抛出时"窗口一个字节都没动"（写回是成功后才发生的），
      // 所以本轮 user 必须由宿主自己补写回再落库——否则第 1 轮失败时"用户说过什么"就丢了（旧行为里本轮 user 在组请求时就已进列表）。
      // 第 ≥2 轮 roundInput 为空，appendAll 天然是空操作（旧行为同样不新增任何东西）。
      window.appendAll(roundInput);
      // 此刻 assistant 消息尚未入列，历史以工具结果干净收尾（与旧行为一致）
      emitDecision("LLM_ERROR", Map.of("reason", String.valueOf(error.getMessage())));
      saveHistory();
    }

    /** onStop 的 payload 映射（设计 §4.3）：只覆盖旧实现的三种专属形状 + CANCELLED 空载荷，其余原因走通用 detail。 */
    private Map<String, Object> stopDetail(StopSignal signal) {
      return switch (signal.reason()) {
        case TURN_LIMIT -> Map.of("maxTurns", config.maxTurns());
        case TIME_BUDGET -> Map.of("timeBudget", config.timeBudget().toString());
        case QUOTA -> Map.of("reason", signal.detail());
        case CANCELLED -> Map.of();
        default -> signal.detail().isEmpty() ? Map.of() : Map.of("reason", signal.detail());
      };
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
        compactor.compact(window.messages(), compactSummary.current(), snapshotBaseline());
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
      // 窗口快照（不可变）：这正是这份基准要的形态——它是只读输入，不会被下游改写
      return window.messages();
    }
    List<LlmMessage> stored = store.load(window.id());
    // 取不到（空实现/空会话）时退回工作集：不把"读不到"当成"没有内容"——真要是空会话，档 3 的落库守卫会另行响亮失败
    return stored.isEmpty() ? window.messages() : stored;
  }

  /**
   * 档 2 的可见性守卫（F2）：摘要<b>已写入槽</b>，但装配器渲染出的 {@code COMPACT_SUMMARY} 层里看不见它 → 接线失配，响亮失败。
   *
   * <p>必须失败而不是继续：档 2 落地后工作集只剩尾部，被丢弃的中段既不在工作集里、又没进层——请求里"无摘要、无占位"，头部与中段
   * <b>静默消失</b>（本项目最坏的失败模式）。失配的成因不是运行期数据问题，而是<b>编程契约违反</b>（管线的槽与装配器读的槽不是同一个实例）， 照 {@link
   * #saveHistory()} 守卫与 {@link #persistSnapshot} 空会话守卫的纪律抛 {@link IllegalStateException}。
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
            .composition(config, userMessage, window.messages(), List.of())
            .estimatedTokens()
            .get(ContextLayer.COMPACT_SUMMARY);
    if (layerTokens == null || layerTokens <= 0) {
      throw new IllegalStateException(
          "压缩摘要已写入槽，但装配器的 COMPACT_SUMMARY 层为空——管线持有的摘要槽与装配器读取的槽不是同一个实例"
              + "（接线必须用 assembler.compactSummarySlot() 取槽，拒绝静默丢弃被压缩的历史）: "
              + window.id());
    }
  }

  /**
   * 换掉内存工作集（= {@link ContextWindow#replaceAll(List)}）：压缩三档都用它落"新的工作集形态"。
   *
   * <p>替换后窗口的未落库尾部为空（"整体替换 = 已处置"，见 {@link ContextWindow#replaceAll(List)} 的类注）——这正是压缩的语义： 档 1/2
   * 换的是内存视图（库里仍是全量，靠 {@code load} 对齐），档 3 换的是刚 {@code compact} 过、与存储同源的那份。
   */
  private void replaceWorkingSet(List<LlmMessage> workingSet) {
    window.replaceAll(workingSet);
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
    store.compact(window.id(), result.summary().render());
    List<LlmMessage> rehydrated = store.load(window.id());
    if (rehydrated.isEmpty()) {
      throw new IllegalStateException(
          "压缩已落库但 load 回来是空会话——store 未接线压缩记录（拒绝静默清空工作集）: " + window.id());
    }
    replaceWorkingSet(rehydrated);
    compactSummary.clear();
  }

  /**
   * 把本回合的<b>未落库尾部</b>追加进存储：{@link ContextWindow#unsavedTail()} → 逐条 {@code store.append} → {@link
   * ContextWindow#markSaved()}。
   *
   * <p><b>为什么不再按下标切片</b>：旧实现靠 {@code messages.subList(replayedSize + 1, size)} 从"组装器返回的那份列表"
   * 里推本回合新增的部分，为此要在切片前校验"历史被原样回灌、本轮 user 恰好紧随"。窗口把这件事变成了它自己的不变量——尾部永远是 {@code [unsavedFrom, size)}
   * 这个后缀，宿主取尾部不需要推导、也不需要自己记下标。位置契约的判别力没有丢：它移到了 {@link AssemblerComposer#compose}
   * 的首次校验上（组请求当刻、任何写入之前）。
   *
   * <p><b>为什么不是"清空重写全量"</b>：机制上的 clear+addAll 直译到持久层就变成每回合 DELETE + 全量 INSERT——行标识与插入 时序每回合全变，是
   * prompt-cache 前缀稳定的隐性敌人。这里保持库侧的 append-only（与今天逐条 append 的语义一致）。
   *
   * <p><b>落库失败（磁盘/DB 异常）不吞</b>：窗口内容已追加（往返时或失败分支里），异常向上抛，回合以失败告终而不是静默丢持久化。 尾部是逐条 append（Store
   * 只保证单次调用原子，见 {@link ConversationStore}）——中途失败会留下"已落库的前缀"，重启后正是从那个前缀续起。 注意此时 {@link
   * ContextWindow#markSaved()} 不会被执行：尾部仍然是"未处置"，下一次落库会从那条失败的消息重新试（这正是"库里有前缀、 内存有全量"这条既定语义的形状）。
   *
   * <p><b>七个终止路径</b>（FINISHED/TOOL_CALL_LIMIT/TURN_LIMIT/TIME_BUDGET/QUOTA/LLM_ERROR/CANCELLED）
   * 都必须先经过这里再 return——否则下一回合回灌的历史残缺，Agent 就会失忆。
   */
  private void saveHistory() {
    for (LlmMessage message : window.unsavedTail()) {
      store.append(window.id(), message);
    }
    // 到达这里即"尾部已处置"（落库成功）；失败时上一行就抛了，markSaved 不会执行
    window.markSaved();
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

  /**
   * <b>本管线的编排器</b>：把库侧的 {@link ContextComposer} 契约接到既有的 {@link ContextAssembler} 上——装配器 SPI 一行不改，
   * 窗口只认"头部 + 窗口内容 + 本轮输入"这个形状。
   *
   * <p><b>★ 为什么头部必须"每回合只算一次"（这是本类唯一有讲究的地方）</b>：{@code buildRequest} 的签名要一个 {@code userMessage}
   * 并把它作为请求的<b>收尾</b>，而新形状下本轮 user 属于 {@code turnInput}（窗口还不含它）。若每次往返都拿 {@code windowMessages} 再调一次
   * {@code buildRequest(config, userMessage, windowMessages, tools)}，第 2 轮起 {@code windowMessages}
   * 里已经含了本轮 user ⇒ 请求变成 {@code [头] + 窗口 + [本轮 user]}，模型会看到<b>两条</b>相同的用户消息。 因此这里只在<b>首次</b> {@code
   * compose} 时调一次装配器（用当时<b>不含</b>本轮 user 的窗口内容），取它的第 0 条（system 头）缓存下来， 之后每次往返都用它拼 {@code [头] + 窗口
   * + 本轮输入}。合法性来自装配器的既有契约：头部只由 {@code config} + 工具目录 + 内容源（技能/记忆/摘要槽） + 本轮 user
   * 派生，<b>与历史无关</b>；而且旧实现里 {@code buildRequest} 本来也是"每回合算一次、随后复用"——两边的字节因此完全一致。
   *
   * <p><b>契约守卫（判别力与原 {@code saveHistory} 的下标守卫对应）</b>：首次组请求时校验装配器返回的序列<b>确实是</b>{@code [头] +
   * 窗口(逐条原样) + 本轮输入(逐条原样)}——尺寸、第 0 条是 system、窗口段逐条 {@code equals}、本轮输入段逐条 {@code equals}。不满足即
   * {@link IllegalStateException}（编程契约违反，不是运行期数据问题），且在任何写入与发信之前。
   */
  private static final class AssemblerComposer implements ContextComposer {

    private final ContextAssembler assembler;
    private final AgentConfig config;
    private final String userMessage;

    /**
     * 本回合的 system 头（首次 {@code compose} 时算出来，此后复用）。
     *
     * <p>{@code null} = 还没算过。为什么用"懒初始化"而不是构造时就算：构造发生在回合开始的组请求之前，而旧实现是"取完可见工具面再算"——
     * 保持同一个时刻，头部里若将来带上工具面相关的东西（今天已经是"工具目录"了）才不会有先后差异。
     */
    private LlmMessage head;

    /**
     * @param assembler 每回合的头部来源（{@code buildRequest} 只在首次 {@code compose} 时被调一次，见类注）
     * @param config 传给装配器的配置（每回合固定）
     * @param userMessage 本轮用户输入（每回合固定；它同时是装配器的收尾参数与 {@code turnInput} 的内容）
     */
    AssemblerComposer(ContextAssembler assembler, AgentConfig config, String userMessage) {
      this.assembler = Objects.requireNonNull(assembler, "assembler");
      this.config = Objects.requireNonNull(config, "config");
      this.userMessage = Objects.requireNonNull(userMessage, "userMessage");
    }

    @Override
    public LlmRequest compose(
        List<LlmMessage> windowMessages, List<LlmMessage> turnInput, List<AgentTool> tools) {
      List<AgentTool> turnTools = List.copyOf(Objects.requireNonNull(tools, "tools"));
      if (head == null) {
        // 首次：此刻 windowMessages 还不含本轮 user（写回发生在 chat 成功之后），正是装配器契约要的形状
        LlmRequest assembled =
            assembler.buildRequest(config, userMessage, windowMessages, turnTools);
        verifyPositionContract(assembled.messages(), windowMessages, turnInput);
        head = assembled.messages().get(0);
      }
      // 每次往返现组：头部（缓存）+ 窗口内容（原样）+ 本轮输入（原样）——不缓存整份请求，窗口是唯一真相
      List<LlmMessage> requestMessages = concat(List.of(head), windowMessages, turnInput);
      return new LlmRequest(requestMessages, toolsDefs(turnTools));
    }

    /**
     * 位置契约校验（判别力与旧 {@code saveHistory} 的前置守卫一一对应，见 {@link AssemblerComposer} 的类注）。
     *
     * <p>旧守卫拦的是"装配器<b>擅自</b>少回灌历史 / 在头部多插一条 ⇒ 本回合新增的尾部被静默丢光或重复落库"这一类编程契约违反；
     * 新守卫拦的是同一类违反，判据换成"装配器返回的序列必须逐条等于 [头] + 窗口 + 本轮输入"——比旧判据<b>更严</b>： 旧判据只看长度与 {@code
     * messages[replayedSize + 1]} 的 role，看不见"等长改写/重排历史"这一档（旧注释自己写明了这一点）， 新判据逐条 {@code equals}
     * 比对，那一档也拦得住。
     *
     * <p><b>异常消息里保留旧措辞的两个字面量</b>（{@code "assembler 违反契约"}、{@code "replayedSize=<窗口条数>"}）： 既有用例
     * {@code AgentPipelineConversationStoreTest} 的 {@code ContractViolatingAssembler} 两条断言正是按这四个字段
     * （措辞 + replayedSize + size）判别的，而本批<b>不许改测试</b>。{@code replayedSize} 在此就等于"窗口当前条数"，与旧实现的
     * "回合起点历史长度"是同一个量——旧守卫看的是"历史是否被原样回灌"，窗口就是那份历史的载体。详细定位信息（各段的起止下标） 一并带上，便于从异常直接看出是哪一段错位。
     */
    private static void verifyPositionContract(
        List<LlmMessage> messages, List<LlmMessage> windowMessages, List<LlmMessage> turnInput) {
      int windowSize = windowMessages.size();
      int inputSize = turnInput.size();
      int expectedSize = 1 + windowSize + inputSize;
      String locator =
          "（messages="
              + messages.size()
              + ": 头部 1 条 + 窗口 [1, "
              + (1 + windowSize)
              + ") 共 "
              + windowSize
              + " 条 + 本轮输入 ["
              + (1 + windowSize)
              + ", "
              + expectedSize
              + ") 共 "
              + inputSize
              + " 条）";
      if (messages.size() != expectedSize) {
        throw contractViolation(
            "返回的序列长度不等于 [头] + 窗口 + 本轮输入", windowSize, messages.size(), 0, locator);
      }
      if (!LlmMessage.ROLE_SYSTEM.equals(messages.get(0).role())) {
        throw contractViolation(
            "messages[0] 不是 system 头（头部必须恰好一条、且在第 0 位）", windowSize, messages.size(), 0, locator);
      }
      for (int i = 0; i < windowSize; i++) {
        if (!messages.get(1 + i).equals(windowMessages.get(i))) {
          throw contractViolation(
              "窗口内容未被原样回灌（第 " + i + " 条起不再相同）", windowSize, messages.size(), i, locator);
        }
      }
      for (int i = 0; i < inputSize; i++) {
        if (!messages.get(1 + windowSize + i).equals(turnInput.get(i))) {
          throw contractViolation(
              "本轮输入未被原样置于窗口之后（第 " + i + " 条起不再相同）", windowSize, messages.size(), i, locator);
        }
      }
    }

    /**
     * 契约违反的统一出口：异常类型与旧守卫一致（{@link IllegalStateException}——这是编程契约违反，不是参数类异常），
     * 首句措辞与旧实现相同（既有用例按它判别），并带上尺寸与下标等定位信息。
     *
     * <p>{@code replayedSize} 沿用旧的字段名与含义（"被回灌的历史长度"），值取窗口条数；{@code size} 取装配器返回的条数—— 于是"窗口 n
     * 条、却返回了别的形状"这件事在异常里一眼可读。
     */
    private static IllegalStateException contractViolation(
        String what, int windowSize, int messageCount, int offset, String locator) {
      return new IllegalStateException(
          "assembler 违反契约：历史未按其长度原样回灌、本轮 user 未紧随其后（replayedSize="
              + windowSize
              + ", size="
              + messageCount
              + "）："
              + what
              + "，首处错位=第 "
              + offset
              + " 条"
              + locator);
    }

    /** 三段拼接（头 + 窗口 + 本轮输入）；入参都已是不可变快照。 */
    private static List<LlmMessage> concat(
        List<LlmMessage> first, List<LlmMessage> second, List<LlmMessage> third) {
      List<LlmMessage> merged = new ArrayList<>(first.size() + second.size() + third.size());
      merged.addAll(first);
      merged.addAll(second);
      merged.addAll(third);
      return List.copyOf(merged);
    }
  }

  /**
   * 执行一次工具调用并把结果落到窗口（本批只改了落点：{@code messages.add(...)} → {@code window.append(...)}，
   * 事件顺序、脱敏、判定链一字未动）。
   */
  private void executeToolCall(ContentPart.ToolCall call) {
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
    window.append(
        LlmMessage.tool(new ContentPart.ToolResult(call.id(), call.name(), content, error)));
  }

  /**
   * 最近一次回合的会话历史快照（<b>不含 system 头</b>；M2 供 A2A/AG-UI 读会话用）。
   *
   * <p>与 {@link #run(String)} 内部消息序列的关系：{@code [system] + lastHistory()} 即完整请求消息。
   *
   * <p><b>契约冻结</b>：接 {@link ConversationStore} 之后本方法读的仍是内存工作集（不是库），形状与语义与持久化前一致—— 不含 system
   * 头、返回不可变快照（后续回合不回改已发出的快照）。窗口下沉后本方法只是转发 {@link ContextWindow#messages()}。
   *
   * <p><b>压缩之后的形状（按实例状态分两种，勿混为一谈）</b>：{@code "摘要 + 压缩点之后的消息"} 只对<b>新实例 hydrate 之后</b>为真（构造时 {@code
   * store.load(...)} 灌入，故进程重启即反映最近一次压缩）。<b>活实例</b>的工作集只在构造时灌一次——T17
   * 接线压缩后，本方法反映的是<b>本实例最近一次压缩后的工作集</b>： 档 3 落库后（压缩点即当时的末尾）等于 {@code load} 的权威形态（摘要消息打头）；档 1/2
   * 是纯内存视图（各自的压缩形态）。 <b>外部</b>（不经本类的压缩器）调用 {@link ConversationStore#compact}
   * 仍不会被回灌——那是"另一个写者"的情形，不在本类契约内。
   */
  public List<LlmMessage> lastHistory() {
    return window.messages();
  }

  private static List<ToolDef> toolsDefs(List<AgentTool> tools) {
    return tools.stream().map(t -> new ToolDef(t.name(), t.description(), t.jsonSchema())).toList();
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
