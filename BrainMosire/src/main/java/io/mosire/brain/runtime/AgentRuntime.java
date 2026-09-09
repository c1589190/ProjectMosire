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
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.context.BasicContextAssembler;
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
 */
public final class AgentRuntime implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final AgentConfig config;
  private final AgentPermissionSet permissionSet;
  private final ToolRegistry registry;
  private final EventStore events;
  private final EventBus bus;
  private final AgentPipeline pipeline;
  private boolean closed;

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

  /** 全参装配（测试/自定义组装器）。 */
  public AgentRuntime(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AgentPermissionSet permissionSet) {
    this.config = Objects.requireNonNull(config, "config");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.events = Objects.requireNonNull(events, "events");
    this.bus = Objects.requireNonNull(bus, "bus");
    this.permissionSet = Objects.requireNonNull(permissionSet, "permissionSet");
    this.pipeline =
        new AgentPipeline(
            config,
            Objects.requireNonNull(llm, "llm"),
            registry,
            guard,
            assembler,
            events,
            bus,
            permissionSet.grantedToken(),
            permissionSet);

    // 起步即记录生命周期（事件词汇表 agent.lifecycle）
    emit(
        EventTypes.AGENT_LIFECYCLE,
        Map.of("action", "started", "agent", config.id(), "model", config.model()));
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

  /** 暴露实时注册表（有意为可变共享对象：调用方即管线装配者/运行时使用者；EI_EXPOSE_REP 为设计意图）。 */
  @SuppressFBWarnings("EI_EXPOSE_REP")
  public ToolRegistry registry() {
    return registry;
  }

  public AgentPermissionSet permissionSet() {
    return permissionSet;
  }

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
