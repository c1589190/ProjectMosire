package io.mosire.main.gateway.agui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.event.Event;
import io.mosire.brain.runtime.StopReason;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AG-UI 事件翻译层（D7：AG-UI 1.0 的变动只改这一层）——[Brain 事件词汇表] → [AG-UI 事件] 的纯映射。
 *
 * <p>映射表（计划 §5.2.2 子集，术语/字段名以 Research 配仓库 schema.json 的 AG-UI 1.0 Draft 为准）：
 *
 * <ul>
 *   <li>{@code conversation.turn}（output 非空）→
 *       TEXT_MESSAGE_START（messageId="m-&lt;seq&gt;"，role="assistant"，name=agent） →
 *       TEXT_MESSAGE_CONTENT（delta=output）→ TEXT_MESSAGE_END；output 为空（纯工具回合）→ 空表（§5.2.2
 *       只经文本产出消息事件）；
 *   <li>{@code tool.call} → TOOL_CALL_START（toolCallId，toolCallName）→ TOOL_CALL_ARGS（delta=args
 *       JSON）→ TOOL_CALL_END；
 *   <li>{@code tool.result} →
 *       TOOL_CALL_RESULT（messageId="tm-&lt;seq&gt;"，toolCallId，content=message，role="tool"；ok=false
 *       仍映射 message——失败信息对客户端同样有意义）；
 *   <li>其余类型（agent.lifecycle——主/子双生产者、decision、permission.denied——管线与 spawn 两种形态、以及任何子 Agent 事件族）→
 *       空表：显式不映射而非静默透传（决策见 README/报告）。
 * </ul>
 *
 * <p>会话级合成事件（运行桥终态——Brain 没有"回合结束"事件，终态由 {@link AgUiSessionRegistry} 从 TurnResult/StopReason
 * 合成）：{@link #runStarted}/{@link #runFinished}/{@link #runError}。帧形状对齐引用 schema：RUN_STARTED
 * {threadId, runId, protocolVersion="1.0", input}；RUN_FINISHED {threadId, runId, result}；RUN_ERROR
 * {code=StopReason 名, message}——RunErrorEvent 依据 schema 仅 type/message/code/usage，不携带 thread/run。
 */
public final class AgUiEventTranslator {

  private static final Logger LOG = LoggerFactory.getLogger(AgUiEventTranslator.class);

  /** W4 快速规格（计划 §5.2.2）要求的 AG-UI 客户端协议版本（Draft schema 快照）。 */
  public static final String PROTOCOL_VERSION = "1.0";

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 单事件 → AG-UI 事件序列（映射表；未命中/坏数据 → 空表，绝不抛错——坏事件不能压垮 SSE 流）。 */
  public List<AgUiEvent> translate(Event event) {
    ObjectNode payload = parseObject(event.payload());
    if (payload == null) {
      return List.of();
    }
    String type = event.type();
    if ("conversation.turn".equals(type)) {
      return translateTurn(event, payload);
    }
    if ("tool.call".equals(type)) {
      return translateToolCall(event, payload);
    }
    if ("tool.result".equals(type)) {
      return translateToolResult(event, payload);
    }
    // 显式不映射：agent.lifecycle（主 AgentRuntime / SubagentManager 双生产者——D7 边界）、decision
    // （终态由 TurnResult 合成，不采用事件驱动）、permission.denied（管线 {tool,reason} 与子 Agent spawn 拒绝
    // {template,childId,reason} 两种形态）、子 Agent 事件族（P2 暂缓，且保证不泄漏进主会话流）
    LOG.debug("AG-UI 翻译层忽略事件类型: {} (agent={}, seq={})", type, event.agent(), event.seq());
    return List.of();
  }

  /** 会话级合成（运行开始——在 SSE 连接建立、任何存储事件之前发出）。 */
  public AgUiEvent runStarted(String threadId, String runId, Map<String, Object> input) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("threadId", threadId);
    fields.put("runId", runId);
    fields.put("protocolVersion", PROTOCOL_VERSION);
    fields.put("input", input == null ? Map.of() : input);
    return new AgUiEvent("RUN_STARTED", fields);
  }

  /** 会话级合成（运行正常结束）；result 为空文本时省略该字段（schema 中 result 是可选对象）。 */
  public AgUiEvent runFinished(String threadId, String runId, String result) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("threadId", threadId);
    fields.put("runId", runId);
    if (result != null && !result.isEmpty()) {
      fields.put("result", result);
    }
    return new AgUiEvent("RUN_FINISHED", fields);
  }

  /** 会话级合成（回合未完成——StopReason≠FINISHED 即错误路径，计划 §5.2.2：RUN_ERROR 承载停止原因）。 */
  public AgUiEvent runError(String threadId, String runId, StopReason reason, String message) {
    String code = reason == null ? "RUNTIME_EXCEPTION" : reason.name();
    return runError(code, message);
  }

  /**
   * 会话级合成（无 StopReason 的异常路径——如执行器已关闭、管线意外异常，code 由调用方给出/errorCode 全权。
   *
   * <p>帧形状对齐引用 schema：RunErrorEvent 只声明 type/message/code/usage（unevaluatedProperties:false， 严格校验会拒
   * threadId/runId 等额外键）→ 不携带 thread/run 字段。
   */
  public AgUiEvent runError(String code, String message) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("code", code);
    fields.put("message", message == null ? "" : message);
    return new AgUiEvent("RUN_ERROR", fields);
  }

  /** 事件 → 线格式 JSON（{"type": ..., ...}，字段顺序：type 先）。 */
  public String toJson(AgUiEvent event) {
    try {
      return JSON.writeValueAsString(event.fields());
    } catch (JsonProcessingException e) {
      // fields 全为 Map/List/String 原语，理论上不可达；失败时回退为最小可读 JSON（不抛——SSE 帧必须可写）
      return "{\"type\":\"" + event.type().replace("\"", "") + "\",\"message\":\"序列化失败\"}";
    }
  }

  private List<AgUiEvent> translateTurn(Event event, ObjectNode payload) {
    String output = text(payload, "output");
    if (output == null || output.isEmpty()) {
      return List.of();
    }
    String messageId = "m-" + event.seq();
    Map<String, Object> start = new LinkedHashMap<>();
    start.put("messageId", messageId);
    start.put("role", "assistant");
    start.put("name", event.agent());
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("messageId", messageId);
    content.put("delta", output);
    Map<String, Object> end = new LinkedHashMap<>();
    end.put("messageId", messageId);
    return List.of(
        new AgUiEvent("TEXT_MESSAGE_START", start),
        new AgUiEvent("TEXT_MESSAGE_CONTENT", content),
        new AgUiEvent("TEXT_MESSAGE_END", end));
  }

  private List<AgUiEvent> translateToolCall(Event event, ObjectNode payload) {
    String toolName = text(payload, "tool");
    JsonNode rawArgs = payload.get("args");
    boolean hasArgs = rawArgs != null && !rawArgs.isNull();
    if (toolName == null && !hasArgs) {
      // 工具名与参数都缺失——视为坏数据（对齐单测契约：{"tool":null,"args":null} → 空表）
      return List.of();
    }
    String callId = text(payload, "callId");
    String toolCallId = callId == null || callId.isEmpty() ? "call-" + event.seq() : callId;
    String argsJson = rawArgs == null ? "{}" : (hasArgs ? rawArgs.toString() : "{}");
    Map<String, Object> start = new LinkedHashMap<>();
    start.put("toolCallId", toolCallId);
    start.put("toolCallName", toolName == null ? "" : toolName);
    Map<String, Object> argsEvent = new LinkedHashMap<>();
    argsEvent.put("toolCallId", toolCallId);
    argsEvent.put("delta", argsJson);
    Map<String, Object> end = new LinkedHashMap<>();
    end.put("toolCallId", toolCallId);
    return List.of(
        new AgUiEvent("TOOL_CALL_START", start),
        new AgUiEvent("TOOL_CALL_ARGS", argsEvent),
        new AgUiEvent("TOOL_CALL_END", end));
  }

  private List<AgUiEvent> translateToolResult(Event event, ObjectNode payload) {
    String toolCallId = text(payload, "callId");
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("messageId", "tm-" + event.seq());
    fields.put("toolCallId", toolCallId == null ? "" : toolCallId);
    // ok=false 也映射 message：失败原因由 TOOL_CALL_RESULT 承载（与计划 §5.2.2 一致——结果事件承载执行信息）
    fields.put("content", text(payload, "message") == null ? "" : text(payload, "message"));
    fields.put("role", "tool");
    return List.of(new AgUiEvent("TOOL_CALL_RESULT", fields));
  }

  /** payload 必须是一个 JSON 对象（否则视为坏数据→空表）。 */
  private ObjectNode parseObject(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    ObjectNode node = null;
    try {
      JsonNode parsed = JSON.readTree(payload);
      if (parsed instanceof ObjectNode objectNode) {
        node = objectNode;
      }
    } catch (Exception e) {
      return null;
    }
    return node;
  }

  private String text(ObjectNode node, String key) {
    JsonNode value = node.get(key);
    return value != null && value.isTextual() ? value.asText() : null;
  }
}
