package io.mosire.main.gateway.agui;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.event.Event;
import io.mosire.brain.runtime.StopReason;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * D7 翻译层单测：事件词汇表映射表（计划 §5.2.2 子集）——"事件 → AG-UI 事件"的纯映射契约。
 *
 * <p>覆盖：① 三个映射源（conversation.turn/tool.call/tool.result）的逐字段产出；② 不映射类型
 * （agent.lifecycle/decision/permission.denied 两种 payload/子 Agent 事件）显式返回空表（不得崩溃、不得误映射）； ③
 * 会话级合成事件（RUN_STARTED/RUN_FINISHED/RUN_ERROR）的字段。
 */
class AgUiEventTranslatorTest {

  private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");

  private static Event event(long seq, String type, String payload) {
    return event(seq, type, "main", payload, "");
  }

  private static Event event(
      long seq, String type, String agent, String payload, String correlationId) {
    return new Event(seq, NOW, type, agent, payload, correlationId);
  }

  private static List<String> types(List<AgUiEvent> events) {
    return events.stream().map(AgUiEvent::type).toList();
  }

  @Test
  void conversationTurnBecomesTextMessageThreeEventSequence() {
    Event turn =
        event(
            11,
            "conversation.turn",
            "{\"turns\":1,\"input\":\"hi\",\"output\":\"你好\",\"model\":\"fake\"}");

    List<AgUiEvent> mapped = new AgUiEventTranslator().translate(turn);

    assertThat(types(mapped))
        .containsExactly("TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END");
    assertThat(mapped.get(0).fields())
        .containsEntry("messageId", "m-11")
        .containsEntry("role", "assistant")
        .containsEntry("name", "main");
    assertThat(mapped.get(1).fields())
        .containsEntry("messageId", "m-11")
        .containsEntry("delta", "你好");
    assertThat(mapped.get(2).fields()).containsEntry("messageId", "m-11");
  }

  @Test
  void conversationTurnWithoutOutputIsSkipped() {
    Event toolOnlyTurn =
        event(
            12,
            "conversation.turn",
            "{\"turns\":1,\"input\":\"hi\",\"output\":\"\",\"model\":\"fake\"}");
    assertThat(new AgUiEventTranslator().translate(toolOnlyTurn)).isEmpty();
  }

  @Test
  void toolCallBecomesStartArgsEndSequence() {
    Event call =
        event(21, "tool.call", "{\"tool\":\"echo\",\"callId\":\"c-1\",\"args\":{\"x\":1}}");

    List<AgUiEvent> mapped = new AgUiEventTranslator().translate(call);

    assertThat(types(mapped)).containsExactly("TOOL_CALL_START", "TOOL_CALL_ARGS", "TOOL_CALL_END");
    assertThat(mapped.get(0).fields())
        .containsEntry("toolCallId", "c-1")
        .containsEntry("toolCallName", "echo");
    assertThat(mapped.get(1).fields())
        .containsEntry("toolCallId", "c-1")
        .containsEntry("delta", "{\"x\":1}");
    assertThat(mapped.get(2).fields()).containsEntry("toolCallId", "c-1");
  }

  @Test
  void toolCallWithoutCallIdFallsBackToSeq() {
    Event call = event(23, "tool.call", "{\"tool\":\"echo\",\"args\":{}}");
    List<AgUiEvent> mapped = new AgUiEventTranslator().translate(call);
    assertThat(mapped).hasSize(3);
    assertThat(mapped.get(0).fields()).containsEntry("toolCallId", "call-23");
  }

  @Test
  void toolResultBecomesToolCallResult() {
    Event result =
        event(
            25,
            "tool.result",
            "{\"tool\":\"echo\",\"callId\":\"c-1\",\"ok\":true,\"message\":\"ECHO\"}");

    List<AgUiEvent> mapped = new AgUiEventTranslator().translate(result);

    assertThat(types(mapped)).containsExactly("TOOL_CALL_RESULT");
    assertThat(mapped.get(0).fields())
        .containsEntry("messageId", "tm-25")
        .containsEntry("toolCallId", "c-1")
        .containsEntry("content", "ECHO");
  }

  @Test
  void failedToolResultStillMapsContentToMessage() {
    Event result =
        event(
            26,
            "tool.result",
            "{\"tool\":\"echo\",\"callId\":\"c-1\",\"ok\":false,\"message\":\"boom\"}");
    List<AgUiEvent> mapped = new AgUiEventTranslator().translate(result);
    assertThat(mapped).hasSize(1);
    assertThat(mapped.get(0).fields()).containsEntry("content", "boom");
  }

  @Test
  void lifecycleDecisionAndPermissionDeniedShapesAreExplicitlyNotMapped() {
    AgUiEventTranslator translator = new AgUiEventTranslator();
    // ① 主 Agent 生命周期（AgentRuntime：started/stopped，无 correlationId）
    assertThat(
            translator.translate(
                event(31, "agent.lifecycle", "{\"action\":\"started\",\"agent\":\"main\"}")))
        .isEmpty();
    // ② 子 Agent 生命周期（SubagentManager：action=configured/spawning/...，agent=correlationId=子 id）
    assertThat(
            translator.translate(
                event(
                    32,
                    "agent.lifecycle",
                    "sub-1",
                    "{\"action\":\"running\",\"template\":\"t1\",\"depth\":1}",
                    "sub-1")))
        .isEmpty();
    // ③ decision（终态由 TurnResult 合成，不支持 event 驱动）
    assertThat(
            translator.translate(
                event(
                    33, "decision", "{\"decision\":\"LLM_ERROR\",\"detail\":{\"reason\":\"x\"}}")))
        .isEmpty();
    // ④ permission.denied —— AgentPipeline 路径：{tool, reason}
    assertThat(
            translator.translate(
                event(34, "permission.denied", "{\"tool\":\"rm\",\"reason\":\"DENIED\"}")))
        .isEmpty();
    // ⑤ permission.denied —— SubagentManager 路径（spawn 拒绝）：{template, childId, reason}，agent=父 id
    assertThat(
            translator.translate(
                event(
                    35,
                    "permission.denied",
                    "main",
                    "{\"template\":\"t1\",\"childId\":\"sub-1\",\"reason\":\"not subset\"}",
                    "sub-1")))
        .isEmpty();
  }

  @Test
  void malformedPayloadDoesNotCrash() {
    AgUiEventTranslator translator = new AgUiEventTranslator();
    assertThat(translator.translate(event(41, "conversation.turn", "not-json"))).isEmpty();
    assertThat(translator.translate(event(42, "tool.call", "{\"tool\":null,\"args\":null}")))
        .isEmpty();
    assertThat(translator.translate(event(43, "tool.result", ""))).isEmpty();
  }

  @Test
  void runStartedCarriesThreadRunIdProtocolAndInputEcho() {
    AgUiEvent started =
        new AgUiEventTranslator()
            .runStarted("s-1", "s-1", Map.of("threadId", "s-1", "messages", List.of()));

    assertThat(started.type()).isEqualTo("RUN_STARTED");
    assertThat(started.fields())
        .containsEntry("threadId", "s-1")
        .containsEntry("runId", "s-1")
        .containsEntry("protocolVersion", "1.0")
        .containsEntry("input", Map.of("threadId", "s-1", "messages", List.of()));
  }

  @Test
  void runFinishedCarriesResultWhenPresent() {
    AgUiEventTranslator translator = new AgUiEventTranslator();
    AgUiEvent withText = translator.runFinished("s-1", "s-1", "最终答案");
    assertThat(withText.type()).isEqualTo("RUN_FINISHED");
    assertThat(withText.fields())
        .containsEntry("threadId", "s-1")
        .containsEntry("runId", "s-1")
        .containsEntry("result", "最终答案");

    AgUiEvent noText = translator.runFinished("s-1", "s-1", "");
    assertThat(noText.fields()).containsOnlyKeys("type", "threadId", "runId");
  }

  @Test
  void runErrorCarriesStopReasonCodeAndMessage() {
    AgUiEvent error =
        new AgUiEventTranslator().runError("s-1", "s-1", StopReason.LLM_ERROR, "LLM 调用失败: x");
    assertThat(error.type()).isEqualTo("RUN_ERROR");
    assertThat(error.fields())
        .containsEntry("threadId", "s-1")
        .containsEntry("runId", "s-1")
        .containsEntry("code", "LLM_ERROR")
        .containsEntry("message", "LLM 调用失败: x");

    AgUiEvent limit = new AgUiEventTranslator().runError("s-1", "s-1", StopReason.TURN_LIMIT, "0");
    assertThat(limit.fields()).containsEntry("code", "TURN_LIMIT");
  }

  @Test
  void serializationProducesTypeDiscriminator() throws Exception {
    AgUiEvent event = new AgUiEventTranslator().runFinished("s-1", "s-1", "ok");
    String json = new AgUiEventTranslator().toJson(event);
    assertThat(json).contains("\"type\":\"RUN_FINISHED\"");
    assertThat(json).contains("\"threadId\":\"s-1\"");
  }
}
