package io.mosire.brain.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.runtime.StopReason;
import io.mosire.agentlib.runtime.TurnResult;
import io.mosire.agentlib.store.ConversationStore;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.context.BasicContextAssembler;
import io.mosire.brain.context.CompactSummarySlot;
import io.mosire.brain.context.ContextAssembler;
import io.mosire.brain.decision.CrossProcessDecisionSubmitter;
import io.mosire.brain.decision.DecisionApprovalBridge;
import java.util.Map;
import java.util.Objects;

/**
 * 一个 Agent 实例的编排根（计划 §4.1）：装配配置/LLM/工具/权限/事件，派生主循环。
 *
 * <p>同一个类同时服务主 Agent 与子 Agent——差异 = 配置（id/工具集/权限集/配额），渲染成 运行时字段而不是类型分支（计划 §四契约）。
 *
 * <p><b>并发约定（R11 未尽项落定）</b>：一个实例的会话历史（{@link AgentPipeline} 内部）非线程安全——要么<b>每任务一个 runtime
 * 实例</b>（任务间完全隔离），要么<b>对同一实例的 {@link #chat} 调用串行化</b>（如 A2A 任务经单线程执行器按提交序执行， 语义 = 一个 Agent
 * 实例的连续对话）；跨任务并发调用是未定义行为。本类不自加锁。
 *
 * <p><b>会话持久化与重置（P3-3）</b>：带 {@code (store, conversationId)} 的构造把历史推到 {@link
 * ConversationStore}（重启续聊）， 其余构造保持"不落库"路径不变；{@link #resetSession(String)} 换新会话 id +
 * 清空内存工作集（D26，旧会话在库里留档）。两者都只是对 {@link AgentPipeline} 的转发，本类不持有任何会话状态。
 */
public final class AgentRuntime implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final AgentConfig config;
  private final AgentSpec spec;
  private final AgentPermissionSet permissionSet;
  private final ToolRegistry registry;
  private final EventStore events;
  private final EventBus bus;
  private final AgentPipeline pipeline;

  // ★ 决策包审批桥（P1b）**不是本类的字段**：它只在构造时透传给管线（管线才是唯一读者）。
  //   早先存成字段被 SpotBugs 判 URF_UNREAD_FIELD（真发现：存了从不读），故删字段、留这条说明防"顺手加回来"。

  private boolean closed;

  /** 便捷装配（AgentSpec 形态——D17：一个 runtime 类 + 不同 Spec，见 {@link AgentSpec}）；默认守卫/上下文组装。 */
  public AgentRuntime(
      AgentSpec spec,
      LlmClient llm,
      ToolRegistry registry,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet) {
    this(
        spec,
        llm,
        registry,
        new ToolExecutionGuard(),
        new BasicContextAssembler(),
        events,
        bus,
        permissionSet);
  }

  /** 便捷装配：默认守卫/上下文组装；文件存储由调用方（Main）持有并统一关停。 */
  public AgentRuntime(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet) {
    this(
        config,
        llm,
        registry,
        new ToolExecutionGuard(),
        new BasicContextAssembler(),
        events,
        bus,
        permissionSet);
  }

  /** 全参装配（测试/自定义组装器）——既有 {@link AgentConfig} 形态保持兼容，内部包装为默认 {@link AgentSpec}。 */
  public AgentRuntime(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet) {
    this(new AgentSpec(config), llm, registry, guard, assembler, events, bus, permissionSet);
  }

  /**
   * 全参装配（{@code AgentConfig} 形态 + 自定义守卫/上下文装配器）+ <b>跨进程收尾缝</b>（P3b）：除最后一个参数外与 上一条构造逐字相同，内部把 {@link
   * AgentConfig} 包装为默认 {@link AgentSpec}。
   *
   * <p>子体进程接技能目录档时走的正是这一条（它手里是 {@code AgentConfig}，不是 {@code AgentSpec}）。
   */
  public AgentRuntime(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet,
      CrossProcessDecisionSubmitter crossProcessSubmitter) {
    this(
        new AgentSpec(config),
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        permissionSet,
        crossProcessSubmitter);
  }

  /**
   * 便捷装配 + <b>跨进程收尾缝</b>（P3b）：默认守卫/上下文组装、不落库；除最后一个参数外与 6 参构造逐字相同。
   *
   * <p><b>子体进程走的就是这一条</b>（{@code SubagentProcessMain}）：它没有会话库（不落库那一档），但需要"回合收尾把队列 跨进程交回父侧"这条缝——见
   * {@link io.mosire.brain.decision.CrossProcessDecisionSubmitter}。
   *
   * @param crossProcessSubmitter 跨进程收尾缝（非 null；未接线请用 6 参构造，语义等价但少一次分叉）
   */
  public AgentRuntime(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet,
      CrossProcessDecisionSubmitter crossProcessSubmitter) {
    this(
        new AgentSpec(config),
        llm,
        registry,
        new ToolExecutionGuard(),
        new BasicContextAssembler(),
        events,
        bus,
        permissionSet,
        crossProcessSubmitter);
  }

  /**
   * 全参装配（{@link AgentSpec} + 自定义守卫/上下文装配器）+ <b>跨进程收尾缝</b>（P3b）：不落库形态。
   *
   * <p>为什么公开：子体进程接技能目录档时会传自己的 {@link ContextAssembler}，而它同时需要跨进程缝——两条需求要在同一条
   * 构造上满足（否则装配层只能在"有组装器"与"有缝"之间二选一）。
   */
  public AgentRuntime(
      AgentSpec spec,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet,
      CrossProcessDecisionSubmitter crossProcessSubmitter) {
    this(
        spec,
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        permissionSet,
        null,
        null,
        Map.of(),
        null,
        null,
        crossProcessSubmitter);
  }

  /**
   * 便捷装配 + <b>会话持久化</b>（P3-3 落盘）：默认守卫/上下文组装，会话历史落 {@code store} 的 {@code conversationId} 名下——构造即
   * {@code load} 灌回内存工作集（重启续聊），此后每回合只追加新增尾部。
   *
   * <p><b>store 的生命周期归调用方</b>（同 {@link EventStore}：装配层持有并统一关停），本类不替它兜底 close。
   *
   * @param store 会话存储（非 null）
   * @param conversationId 初始会话标识（非 null、显式给定——初始 id 的稳定性决定"重启续聊"能不能续上）
   */
  public AgentRuntime(
      AgentSpec spec,
      LlmClient llm,
      ToolRegistry registry,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet,
      ConversationStore store,
      String conversationId) {
    this(
        spec,
        llm,
        registry,
        new ToolExecutionGuard(),
        new BasicContextAssembler(),
        events,
        bus,
        permissionSet,
        Objects.requireNonNull(store, "store"),
        Objects.requireNonNull(conversationId, "conversationId"),
        Map.of(),
        null);
  }

  /**
   * 便捷装配 + 会话持久化 + <b>工具配置注入</b>（D30）：除最后一个参数外与上一构造逐字节相同。
   *
   * <p>{@code toolConfig} 经 {@link AgentPipeline} 落到每次工具调用的 {@code ToolContext.config()}——装配层
   * （Main）用它把"子库根目录"这类运行时路径交给工具，Brain 因此不必持有全局路径、也不做文件系统假设。
   */
  public AgentRuntime(
      AgentSpec spec,
      LlmClient llm,
      ToolRegistry registry,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet,
      ConversationStore store,
      String conversationId,
      Map<String, Object> toolConfig) {
    this(spec, llm, registry, events, bus, permissionSet, store, conversationId, toolConfig, null);
  }

  /**
   * 便捷装配 + 会话持久化 + 工具配置注入 + <b>审批编排器</b>（S4-B2）：除最后一个参数外与上一构造逐字节相同。
   *
   * <p>这是 {@code App} 真正走的<b>终态装配路径</b>（非 demo 分叉）——工具自报 {@code ToolGate.Ask} 的调用会经它去问人； 其余重载一律传
   * {@code null}（= 无审批面，{@code Ask} fail-closed 拒），既有装配行为逐字不变。
   *
   * <p>{@code coordinator} 由装配层建好当参数传进来（含进程内唯一的 {@code PendingApprovals}）；本类<b>不</b>持有任何静态单例。
   */
  public AgentRuntime(
      AgentSpec spec,
      LlmClient llm,
      ToolRegistry registry,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet,
      ConversationStore store,
      String conversationId,
      Map<String, Object> toolConfig,
      ApprovalCoordinator coordinator) {
    this(
        spec,
        llm,
        registry,
        new ToolExecutionGuard(),
        new BasicContextAssembler(),
        events,
        bus,
        permissionSet,
        Objects.requireNonNull(store, "store"),
        Objects.requireNonNull(conversationId, "conversationId"),
        toolConfig,
        coordinator);
  }

  /** 便捷装配 + <b>工具配置注入</b>（D30，不落库形态）：除最后一个参数外与 6 参构造逐字节相同。 */
  public AgentRuntime(
      AgentSpec spec,
      LlmClient llm,
      ToolRegistry registry,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet,
      Map<String, Object> toolConfig) {
    this(
        spec,
        llm,
        registry,
        new ToolExecutionGuard(),
        new BasicContextAssembler(),
        events,
        bus,
        permissionSet,
        null,
        null,
        toolConfig,
        null);
  }

  /**
   * 全参装配（不落库形态）：{@link AgentSpec} + 自定义守卫/上下文装配器。
   *
   * <p>为什么公开：装配层接线内容源（技能目录档/预载、记忆检索）时必须自己造 {@link ContextAssembler}（内容源是构造期注入的， 见 {@code
   * BasicContextAssembler}），而既有 {@link AgentSpec} 公开重载都把装配器固定成默认件。本构造即该组合入口； 参数语义与其它重载逐字一致（{@code
   * store} 缺省 = 不落库）。
   */
  public AgentRuntime(
      AgentSpec spec,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet) {
    this(
        spec,
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        permissionSet,
        null,
        null,
        Map.of(),
        null);
  }

  /**
   * 全参装配（{@code store == null} = 不落库、{@code conversationId} 随之缺省）：两种形态在装配上的<b>唯一</b>差别是 {@link
   * AgentPipeline} 的构造重载——不落库走既有的 9 参构造（行为与持久化接入前逐字节一致），落库走 11 参构造。
   *
   * <p><b>为什么公开</b>：装配层需要同时给出自定义 {@link ContextAssembler}（如接线技能来源的 {@code
   * BasicContextAssembler}）与会话存储/审批编排器，而其余公开重载都把 {@code assembler} 固定成 {@code new
   * BasicContextAssembler()}。本构造是该组合的唯一入口；参数语义与上面几条重载逐字一致。
   *
   * <p>{@code coordinator} 为 {@code null} = 无审批面（{@code ToolGate.Ask} fail-closed 拒）——除 S4-B2
   * 的终态装配路径外， 所有既有重载都传 {@code null}，行为逐字不变。
   *
   * <p><b>P1b 追加的 {@code decisionBridge}（决策包桥）</b>：本构造不带它（{@code null} = 不绑本轮待裁决队列、 {@code Ask}
   * 走既有审批闸）。要接"Ask 自动进包"的装配请用下面带桥的那条构造——只有装配层拿得到桥实例 （{@code Main} 的 {@code App}），Brain 不造它。
   */
  public AgentRuntime(
      AgentSpec spec,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet,
      ConversationStore store,
      String conversationId,
      Map<String, Object> toolConfig,
      ApprovalCoordinator coordinator) {
    this(
        spec,
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        permissionSet,
        store,
        conversationId,
        toolConfig,
        coordinator,
        null);
  }

  /**
   * 全参装配 + <b>决策包桥</b>（P1b，设计 §5 裁定 1+2）：除最后一个参数外与上一条构造逐字相同。
   *
   * <p><b>{@code decisionBridge} 是什么</b>：Pipeline 在回合收尾把本轮攒下的待裁决调用收成一个决策包、<b>同步</b>投递并等裁决 （裁定
   * 4），出口就是这个桥（{@link io.mosire.brain.decision.DecisionApprovalBridge#flush}）。<b>{@code null} =
   * 不接线</b>： 管线不绑定本轮队列，{@code ToolGate.Ask} 保持既有行为（问人）。两者必须同进同退——只绑队列不给桥，等于让敏感调用 既没执行、也没人去裁（见
   * {@code AgentPipeline#decisionBridge} 的注释）。
   *
   * <p><b>为什么由装配层注入而不是 Brain 自建</b>：桥要的实例（{@code DecisionPackages} / {@code ApprovalCoordinator} /
   * {@code PendingApprovals} / 提交口 / 事件总线）全是装配层那<b>唯一</b>一份（设计 §5.4：不新建第二张表、不新建通道）。
   *
   * @param decisionBridge 决策包审批桥（{@code null} = 未接线：不绑队列、{@code Ask} 走既有审批闸）
   */
  public AgentRuntime(
      AgentSpec spec,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet,
      ConversationStore store,
      String conversationId,
      Map<String, Object> toolConfig,
      ApprovalCoordinator coordinator,
      DecisionApprovalBridge decisionBridge) {
    this(
        spec,
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        permissionSet,
        store,
        conversationId,
        toolConfig,
        coordinator,
        decisionBridge,
        null);
  }

  /**
   * 全参装配 + 审批桥 + <b>跨进程收尾缝</b>（P3b，设计 §5quinquies）：除最后一个参数外与上一条构造逐字相同。
   *
   * <p><b>{@code crossProcessSubmitter} 是什么</b>：子体这一侧的回合收尾口——把本轮队列<b>跨进程</b>交回父侧结算， 回执落进自己的包表与事件库（见
   * {@link io.mosire.brain.decision.CrossProcessDecisionSubmitter}）。{@code null} = 未接线（主 Agent
   * 的终态装配就是这一档）：收尾走桥那条既有路，行为逐字不变。
   *
   * <p><b>为什么由装配层注入而不是 Brain 自建</b>：缝要的那几件（子体自己的包表、本机执行者、实例身份与权限集）只有 <b>子体进程的装配点</b>拿得到；Brain
   * 侧自建一份就会与装配层那一份分叉（身份/包表各两份）。
   *
   * <p><b>不落库分叉也接得进来</b>：子体进程的 runtime 正是 {@code store == null} 那一档，所以这条构造把缝同时透传给 "不落库 + 工具配置 +
   * 缝"那条管线构造（没有它，子体要么接不上缝，要么被迫造一个不需要的会话库）。
   */
  public AgentRuntime(
      AgentSpec spec,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet,
      ConversationStore store,
      String conversationId,
      Map<String, Object> toolConfig,
      ApprovalCoordinator coordinator,
      DecisionApprovalBridge decisionBridge,
      CrossProcessDecisionSubmitter crossProcessSubmitter) {
    this.spec = Objects.requireNonNull(spec, "spec");
    AgentConfig config = spec.core();
    this.config = Objects.requireNonNull(config, "config");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.events = Objects.requireNonNull(events, "events");
    this.bus = Objects.requireNonNull(bus, "bus");
    this.permissionSet = Objects.requireNonNull(permissionSet, "permissionSet");
    // P1b/P3b：桥与跨进程缝都只转发给管线（决定"本轮队列绑不绑、谁来收尾"），本类不读它们的状态；null = 未接线
    LlmClient client = Objects.requireNonNull(llm, "llm");
    this.pipeline =
        store == null
            // 不落库分叉不接审批面（与 App 的 demo 分叉同口径）：{@code Ask} 在那里一律 fail-closed 拒——少一条路，不是放行。
            // ★ P3b：跨进程缝接得进来（子体进程就是这一档）——没有它，子体的敏感调用在"没接审批面"之外还多一条"没人收尾"。
            ? crossProcessSubmitter == null
                ? new AgentPipeline(
                    config,
                    client,
                    registry,
                    guard,
                    assembler,
                    events,
                    bus,
                    permissionSet.grantedToken(),
                    permissionSet,
                    toolConfig)
                : new AgentPipeline(
                    config,
                    client,
                    registry,
                    guard,
                    assembler,
                    events,
                    bus,
                    permissionSet.grantedToken(),
                    permissionSet,
                    toolConfig,
                    crossProcessSubmitter)
            : new AgentPipeline(
                config,
                client,
                registry,
                guard,
                assembler,
                events,
                bus,
                permissionSet.grantedToken(),
                permissionSet,
                store,
                conversationId,
                null,
                CompactSummarySlot.empty(),
                toolConfig,
                coordinator,
                decisionBridge,
                crossProcessSubmitter);

    // 起步即记录生命周期（事件词汇表 agent.lifecycle）
    // model 报的是【客户端实际在用的模型】（client.model()），不是装配层标签 config.model()：后者由
    // AgentConfig/模板给出，真模型形态下（S1-A2 的 --config-dir）它仍是缺省字面量 "fake"——事件会谎报，
    // 凡"靠 agent.lifecycle 判这个进程是真模型还是假模型"的检查都会被带偏（2026-09-12 U6 实跑抓到）。
    emit(
        EventTypes.AGENT_LIFECYCLE,
        Map.of("action", "started", "agent", config.id(), "model", client.model()));
  }

  /**
   * 跑一回合（用户输入 → 终止）；多轮连续对话共享 history。
   *
   * <p><b>调用约定</b>：同步、重入不设防——同一实例的 {chat} 调用必须串行（或每任务一个实例），详见类 Javadoc 并发约定。
   */
  public TurnResult chat(String userMessage) {
    ensureOpen();
    return pipeline.run(userMessage);
  }

  /**
   * 重置会话（D26：换新会话 id）：清空内存工作集 + 把 {@link #chat} 后的落库/回灌切到 {@code newConversationId}；旧会话在库里原样保留
   * （不删——可追溯），新对话从零开始。等价于"这个 Agent 换了个人聊"，而不是"抹掉聊过的内容"。
   *
   * <p>{@link #pipeline} 保持 {@code private final}：重置只是转发，<b>不重建</b>管线（store/守卫/装配器/摘要槽一律沿用），
   * 故本类的装配语义在重置前后完全一致。
   *
   * <p><b>并发约定（R11，硬约束）</b>：重置改的是管线里实例级共享、非线程安全的工作集，必须与 {@link #chat} 排在同一串行化点上（如
   * 同一个单线程执行器），<b>不得</b>在另一个线程上与在途回合并发调用——详见 {@link AgentPipeline#resetConversation(String)} 与类
   * Javadoc。
   *
   * @param newConversationId 新会话标识（非 null、全新 id 由调用方生成——Brain 不生成 id）
   */
  public void resetSession(String newConversationId) {
    ensureOpen();
    pipeline.resetConversation(newConversationId);
  }

  /**
   * 当前会话标识（未重置过即装配时的初始 id）——调用方据此回报"重置前的会话是哪个"。
   *
   * <p>非线程安全语义见 {@link AgentPipeline#conversationId()}：重置已排进串行执行器但尚未执行时，这里仍返回旧 id。
   */
  public String conversationId() {
    return pipeline.conversationId();
  }

  /**
   * 请求取消当前运行中的 {@link #chat}（转发给主循环，幂等、线程安全）。
   *
   * <p>不硬中断进行中的 LLM/工具调用：管线在下一个检查点以 {@link StopReason#CANCELLED} 就地终止， 历史已保存，后续 {@link #chat}
   * 可正常继续。无运行中的回合时调用是空操作，详见 {@link AgentPipeline#cancel()}。
   */
  public void cancel() {
    pipeline.cancel();
  }

  public AgentConfig config() {
    return config;
  }

  /** 本实例的规格（D17）；既有 AgentConfig 构造路径包装为默认 Spec，恒非 null。 */
  public AgentSpec spec() {
    return spec;
  }

  /** 暴露实时注册表（有意为可变共享对象：调用方即管线装配者/运行时使用者；EI_EXPOSE_REP 为设计意图）。 */
  @SuppressFBWarnings("EI_EXPOSE_REP")
  public ToolRegistry registry() {
    return registry;
  }

  public AgentPermissionSet permissionSet() {
    return permissionSet;
  }

  /**
   * 关停：发 {@code agent.lifecycle stopped} 并封住 {@link #chat}/{@link #resetSession}。
   *
   * <p><b>不 close 注入件</b>：{@link EventStore} 与会话存储都是装配层持有的共享资源（同生共死归调用方），本类只发事件、不替它们兜底。
   */
  @Override
  public void close() {
    if (!closed) {
      closed = true;
      emit(EventTypes.AGENT_LIFECYCLE, Map.of("action", "stopped", "agent", config.id()));
    }
  }

  private void emit(String type, Map<String, Object> payload) {
    Event event = events.append(EventWrite.of(type, config.id(), toJson(payload)));
    bus.publish(event);
  }

  static String toJson(Map<String, ?> payload) {
    try {
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("Agent 已关闭: " + config.id());
    }
  }
}
