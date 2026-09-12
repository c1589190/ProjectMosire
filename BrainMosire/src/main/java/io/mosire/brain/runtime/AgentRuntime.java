package io.mosire.brain.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.store.ConversationStore;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.context.BasicContextAssembler;
import io.mosire.brain.context.CompactSummarySlot;
import io.mosire.brain.context.ContextAssembler;
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
        Map.of());
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
        toolConfig);
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
        toolConfig);
  }

  private AgentRuntime(
      AgentSpec spec,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet) {
    this(spec, llm, registry, guard, assembler, events, bus, permissionSet, null, null, Map.of());
  }

  /**
   * 全参装配（{@code store == null} = 不落库、{@code conversationId} 随之缺省）：两种形态在装配上的<b>唯一</b>差别是 {@link
   * AgentPipeline} 的构造重载——不落库走既有的 9 参构造（行为与持久化接入前逐字节一致），落库走 11 参构造。
   */
  private AgentRuntime(
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
      Map<String, Object> toolConfig) {
    this.spec = Objects.requireNonNull(spec, "spec");
    AgentConfig config = spec.core();
    this.config = Objects.requireNonNull(config, "config");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.events = Objects.requireNonNull(events, "events");
    this.bus = Objects.requireNonNull(bus, "bus");
    this.permissionSet = Objects.requireNonNull(permissionSet, "permissionSet");
    LlmClient client = Objects.requireNonNull(llm, "llm");
    this.pipeline =
        store == null
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
                store,
                conversationId,
                null,
                CompactSummarySlot.empty(),
                toolConfig);

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
