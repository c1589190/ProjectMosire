package io.mosire.agentlib.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.context.ContextComposer;
import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.tool.AgentTool;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 设计 {@code 设计-通用循环骨架.md} §6.1 L10：<b>第二宿主</b>（复用判据）——一个与 Brain 完全无关的最小程序，只实现 {@link StopPolicy} /
 * {@link TurnListener} / {@link ToolCallDispatcher} 三个 SPI，就能跑通两轮工具调用。
 *
 * <p>本文件刻意不引用任何 Brain/Main 类型：复用的是"库 + 三个策略对象"，不是"某个运行时".这是"骨架下沉有资格"的判据—— 如果第二宿主必须拖上 Brain
 * 的上下文/事件/存储才能跑，下沉就是假的。
 *
 * <p>最小宿主的形状：编排器 = {@code [头部] + 窗口 + 本轮输入}；停止策略 = 最多 3 轮；监听器 = 记回调；分派器 = 把工具结果写回窗口。
 */
class AgentLoopSecondHostTest {

  private static final LlmMessage HEAD = LlmMessage.system("第二宿主头部");

  // ================= L10 主用例：两轮工具调用后自然收尾 =================

  @Test
  void minimalHostIndependentOfBrainRunsTwoToolRoundsAndFinishes() {
    ContextWindow window = new ContextWindow("second-host");
    List<LlmMessage> turnInput = List.of(LlmMessage.user("跑两轮工具"));
    RecordingHostListener listener = new RecordingHostListener();
    // 第二宿主自己的预算语义：最多 3 轮（与 Brain 的 maxTurns 无关，只是一个 lambda）
    StopPolicy maxThreeRounds =
        state ->
            state.rounds() >= 3
                ? Optional.of(new StopSignal(StopReason.TURN_LIMIT, "最多 3 轮"))
                : Optional.empty();

    ScriptedLlm llm = new ScriptedLlm();
    ContentPart.ToolCall first = new ContentPart.ToolCall("c-1", "lookup", Map.of("q", "a"));
    ContentPart.ToolCall second = new ContentPart.ToolCall("c-2", "lookup", Map.of("q", "b"));
    LlmResponse firstResponse =
        new LlmResponse(LlmMessage.assistant(List.of(first)), "fake-model", 1, 1);
    LlmResponse secondResponse =
        new LlmResponse(LlmMessage.assistant(List.of(second)), "fake-model", 1, 1);
    LlmResponse finalResponse = LlmResponse.text("两轮工具跑完");
    llm.enqueue(firstResponse);
    llm.enqueue(secondResponse);
    llm.enqueue(finalResponse);
    LlmMessage firstResult =
        LlmMessage.tool(new ContentPart.ToolResult(first.id(), first.name(), "结果:a", null));
    LlmMessage secondResult =
        LlmMessage.tool(new ContentPart.ToolResult(second.id(), second.name(), "结果:b", null));

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(new MinimalComposer())
            .llm(llm)
            .dispatcher(
                (call, state) ->
                    window.append(
                        LlmMessage.tool(
                            new ContentPart.ToolResult(
                                call.id(), call.name(), "结果:" + call.arguments().get("q"), null))))
            .stopPolicy(maxThreeRounds)
            .listener(listener)
            .build()
            .runTurn(turnInput, List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(result.turns()).isEqualTo(3);
    assertThat(result.toolCalls()).isEqualTo(2);
    assertThat(result.text()).isEqualTo("两轮工具跑完");
    // 窗口形状：[user, assistant(tool_call c1), tool(c1), assistant(tool_call c2), tool(c2),
    // assistant(文本)]
    assertThat(window.messages())
        .containsExactly(
            turnInput.get(0),
            firstResponse.assistantMessage(),
            firstResult,
            secondResponse.assistantMessage(),
            secondResult,
            finalResponse.assistantMessage());
    // 本轮输入只在第 1 轮出现：第 2、3 轮请求 = [头部] + 窗口
    assertThat(llm.requests).hasSize(3);
    assertThat(llm.requests.get(0).messages()).containsExactly(HEAD, turnInput.get(0));
    assertThat(llm.requests.get(1).messages())
        .containsExactly(HEAD, turnInput.get(0), firstResponse.assistantMessage(), firstResult);
    assertThat(llm.requests.get(2).messages())
        .containsExactly(
            HEAD,
            turnInput.get(0),
            firstResponse.assistantMessage(),
            firstResult,
            secondResponse.assistantMessage(),
            secondResult);
    // 终止回调 exactly-once：只有 onFinished
    assertThat(listener.finishedTexts).containsExactly("两轮工具跑完");
    assertThat(listener.finishedStates).hasSize(1);
    assertThat(listener.finishedStates.get(0).rounds()).isEqualTo(3);
    assertThat(listener.finishedStates.get(0).toolCalls()).isEqualTo(2);
    assertThat(listener.roundTrips).isEqualTo(3);
    assertThat(listener.limitCalls).isZero();
    assertThat(listener.stops).isZero();
    assertThat(listener.llmErrors).isZero();
    assertThat(listener.terminalCallbacks()).isEqualTo(1);
  }

  // ================= L10 补充：第二宿主的停止策略真的会在上限处停 =================

  @Test
  void minimalHostStopPolicyStopsAtItsOwnRoundLimitBeforeCallingLlmAgain() {
    ContextWindow window = new ContextWindow("second-host-limit");
    RecordingHostListener listener = new RecordingHostListener();
    StopPolicy maxThreeRounds =
        state ->
            state.rounds() >= 3
                ? Optional.of(new StopSignal(StopReason.TURN_LIMIT, "最多 3 轮"))
                : Optional.empty();
    ScriptedLlm llm = new ScriptedLlm();
    LlmMessage user = LlmMessage.user("一直调工具");
    ContentPart.ToolCall first = new ContentPart.ToolCall("c-1", "lookup", Map.of("q", "1"));
    ContentPart.ToolCall second = new ContentPart.ToolCall("c-2", "lookup", Map.of("q", "2"));
    ContentPart.ToolCall third = new ContentPart.ToolCall("c-3", "lookup", Map.of("q", "3"));
    LlmResponse firstResponse =
        new LlmResponse(LlmMessage.assistant(List.of(first)), "fake-model", 1, 1);
    LlmResponse secondResponse =
        new LlmResponse(LlmMessage.assistant(List.of(second)), "fake-model", 1, 1);
    LlmResponse thirdResponse =
        new LlmResponse(LlmMessage.assistant(List.of(third)), "fake-model", 1, 1);
    llm.enqueue(firstResponse);
    llm.enqueue(secondResponse);
    llm.enqueue(thirdResponse);
    LlmMessage firstResult =
        LlmMessage.tool(new ContentPart.ToolResult(first.id(), first.name(), "结果:1", null));
    LlmMessage secondResult =
        LlmMessage.tool(new ContentPart.ToolResult(second.id(), second.name(), "结果:2", null));

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(new MinimalComposer())
            .llm(llm)
            .dispatcher(
                (call, state) ->
                    window.append(
                        LlmMessage.tool(
                            new ContentPart.ToolResult(
                                call.id(), call.name(), "结果:" + call.arguments().get("q"), null))))
            .stopPolicy(maxThreeRounds)
            .listener(listener)
            .build()
            .runTurn(List.of(user), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.TURN_LIMIT);
    assertThat(result.turns()).isEqualTo(3);
    // 第 3 轮的分发前检查默认委托 check（L12 语义）：此刻 rounds 已到 3，
    // 所以第 3 条 call 未派发，也就不计入 toolCalls。
    assertThat(result.toolCalls()).isEqualTo(2);
    // 没有第 4 次 LLM 请求：在回合顶就被策略叫停了
    assertThat(llm.requests).hasSize(3);
    assertThat(listener.roundTrips).isEqualTo(3);
    assertThat(listener.finishedTexts).isEmpty();
    assertThat(listener.limitCalls).isZero();
    assertThat(listener.llmErrors).isZero();
    assertThat(listener.stops).isEqualTo(1);
    assertThat(listener.stopDetails).containsExactly("最多 3 轮");
    // 第 3 条工具调用悬空（未派发即没有工具结果）——默认边界=完整 check 的已知形状
    assertThat(window.messages())
        .containsExactly(
            user,
            firstResponse.assistantMessage(),
            firstResult,
            secondResponse.assistantMessage(),
            secondResult,
            thirdResponse.assistantMessage());
    assertThat(listener.terminalCallbacks()).isEqualTo(1);
  }

  // ================= 第二宿主自己的最小实现 =================

  /** 最小编排器：[头部] + 窗口（原样、按序）+ 本轮输入（原样、按序）。 */
  private static final class MinimalComposer implements ContextComposer {

    @Override
    public LlmRequest compose(
        List<LlmMessage> windowMessages, List<LlmMessage> turnInput, List<AgentTool> tools) {
      List<LlmMessage> messages = new ArrayList<>(1 + windowMessages.size() + turnInput.size());
      messages.add(HEAD);
      messages.addAll(windowMessages);
      messages.addAll(turnInput);
      return new LlmRequest(messages, List.of());
    }
  }

  /** 与 {@code AgentLoopTest} 不共享的独立脚本桩：本文件自证第二宿主不需要测试夹具以外的任何宿主设施。 */
  private static final class ScriptedLlm implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    void enqueue(LlmResponse response) {
      script.addLast(response);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new LlmException("第二宿主脚本已耗空（第 " + requests.size() + " 次调用）");
      }
      return response;
    }
  }

  /** 记录四个终止回调与每次成功往返的宿主监听器。 */
  private static final class RecordingHostListener implements TurnListener {

    private final List<String> finishedTexts = new ArrayList<>();
    private final List<LoopState> finishedStates = new ArrayList<>();
    private final List<String> stopDetails = new ArrayList<>();
    private int roundTrips;
    private int limitCalls;
    private int stops;
    private int llmErrors;

    @Override
    public Optional<StopSignal> onRoundTrip(LlmResponse response, long latencyMs, LoopState state) {
      roundTrips++;
      return Optional.empty();
    }

    @Override
    public void onFinished(String finalText, LoopState state) {
      finishedTexts.add(finalText);
      finishedStates.add(state);
    }

    @Override
    public void onToolCallLimit(List<ContentPart.ToolCall> calls, LoopState state) {
      limitCalls++;
    }

    @Override
    public void onStop(StopSignal signal, LoopState state) {
      stops++;
      stopDetails.add(signal.detail());
    }

    @Override
    public void onLlmError(LlmException error, List<LlmMessage> roundInput, LoopState state) {
      llmErrors++;
    }

    int terminalCallbacks() {
      return finishedTexts.size() + limitCalls + stops + llmErrors;
    }
  }
}
