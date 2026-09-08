package io.mosire.brain.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.context.ContextAssembler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 */
public final class AgentPipeline {

  private static final Logger LOG = LoggerFactory.getLogger(AgentPipeline.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final AgentConfig config;
  private final LlmClient llm;
  private final ToolRegistry registry;
  private final ToolExecutionGuard guard;
  private final ContextAssembler assembler;
  private final EventStore events;
  private final EventBus bus;
  private final AccessToken caller;
  private final AgentPermissionSet permissionSet;

  /** 会话历史（进程内）；ConversationStore 持久化在 M3 接入。 */
  private final List<LlmMessage> history = new ArrayList<>();

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
    this.config = Objects.requireNonNull(config, "config");
    this.llm = Objects.requireNonNull(llm, "llm");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.guard = Objects.requireNonNull(guard, "guard");
    this.assembler = Objects.requireNonNull(assembler, "assembler");
    this.events = Objects.requireNonNull(events, "events");
    this.bus = Objects.requireNonNull(bus, "bus");
    this.caller = Objects.requireNonNull(caller, "caller");
    this.permissionSet = Objects.requireNonNull(permissionSet, "permissionSet");
  }

  /**
   * 跑一回合：用户输入 →（LLM 循环 + 工具调用）→ 终止。
   *
   * <p>约定：方法是同步的（网络/子进程调用在工具内自行发生）；回合间共享 {@code history} （一个 Agent 实例的连续对话）。
   */
  public TurnResult run(String userMessage) {
    Objects.requireNonNull(userMessage, "userMessage");

    LlmQuota quota = config.quotaMaxTokens() > 0 ? new LlmQuota(config.quotaMaxTokens()) : null;
    Instant deadline = Instant.now().plus(config.timeBudget());
    List<AgentTool> tools = registry.list();
    List<LlmMessage> messages =
        new ArrayList<>(assembler.buildRequest(config, userMessage, tools).messages());

    int turns = 0;
    int totalToolCalls = 0;
    String finalText = "";

    while (true) {
      if (turns >= config.maxTurns()) {
        emitDecision("TURN_LIMIT", Map.of("maxTurns", config.maxTurns()));
        return new TurnResult(StopReason.TURN_LIMIT, turns, totalToolCalls, finalText);
      }
      if (Instant.now().isAfter(deadline)) {
        emitDecision("TIME_BUDGET", Map.of("timeBudget", config.timeBudget().toString()));
        return new TurnResult(StopReason.TIME_BUDGET, turns, totalToolCalls, finalText);
      }

      LlmResponse response;
      try {
        response = llm.chat(new LlmRequest(List.copyOf(messages), toolsDefs(tools)));
      } catch (io.mosire.agentlib.llm.QuotaExceededException e) {
        emitDecision("QUOTA", Map.of("reason", e.getMessage()));
        return new TurnResult(StopReason.QUOTA, turns, totalToolCalls, finalText);
      }
      turns++;
      if (quota != null) {
        try {
          quota.record(response.inputTokens(), response.outputTokens());
        } catch (io.mosire.agentlib.llm.QuotaExceededException e) {
          emitDecision("QUOTA", Map.of("reason", e.getMessage()));
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
        history.clear();
        history.addAll(messages);
        return new TurnResult(
            StopReason.FINISHED, turns, totalToolCalls, response.textPart().orElse(""));
      }

      if (toolCalls.size() > config.maxToolCallsPerTurn()) {
        emitDecision(
            "TOOL_CALL_LIMIT",
            Map.of("toolCalls", toolCalls.size(), "max", config.maxToolCallsPerTurn()));
        history.clear();
        history.addAll(messages);
        return new TurnResult(
            StopReason.TOOL_CALL_LIMIT, turns, totalToolCalls, response.textPart().orElse(""));
      }

      for (ContentPart.ToolCall call : toolCalls) {
        totalToolCalls++;
        executeToolCall(messages, call);
      }
    }
  }

  private void executeToolCall(List<LlmMessage> messages, ContentPart.ToolCall call) {
    emit(
        EventTypes.TOOL_CALL,
        Map.of("tool", call.name(), "callId", call.id(), "args", call.arguments()));

    ToolContext context = new ToolContext(caller, permissionSet, Map.of(), call.arguments());
    ToolResult result;
    try {
      result = guard.execute(registry, call.name(), context);
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

  /** 最近一次回合的消息序列快照（M2 供 A2A/AG-UI 读会话用；M3 交 ConversationStore）。 */
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
