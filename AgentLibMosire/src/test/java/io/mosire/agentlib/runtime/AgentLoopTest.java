package io.mosire.agentlib.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 设计 {@code 设计-通用循环骨架.md} §6.1 的库侧判据 L1–L9、L11、L12：按判据写，不按代码反推。
 *
 * <p>夹具刻意只有三件：①记录每次 {@code (windowMessages, turnInput)} 的编排器（L2 要证明"第 2 轮请求 = [头] + 窗口"）；②脚本化假
 * LLM（按序出队；要精确帧序列与失败注入，真模型造不出这种确定性）；③记录型 {@link TurnListener}（每个终止回调 单独计数，用来断言 exactly-once）。窗口用真实的
 * {@link ContextWindow}，不 mock——写回语义本身就在被测范围内。
 */
class AgentLoopTest {

  private static final LlmMessage HEAD = LlmMessage.system("宿主头部");

  // ================= L1 自然收尾 =================

  @Test
  void naturalFinishReturnsFinishedWithModelTextAndWritesUserAndAnswerBack() {
    ContextWindow window = new ContextWindow("l1");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    RecordingListener listener = new RecordingListener();
    List<ContentPart.ToolCall> dispatched = new ArrayList<>();
    LlmResponse response = LlmResponse.text("你好，我是模型");
    llm.enqueue(response);
    LlmMessage user = LlmMessage.user("在吗");

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher((call, state) -> dispatched.add(call))
            .listener(listener)
            .build()
            .runTurn(List.of(user), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(result.turns()).isEqualTo(1);
    assertThat(result.toolCalls()).isZero();
    assertThat(result.text()).isEqualTo("你好，我是模型");
    assertThat(result.finalText()).contains("你好，我是模型");
    // 窗口 = [user, assistant]：本轮输入与答复都写回（写回是往返的一部分）
    assertThat(window.messages()).containsExactly(user, response.assistantMessage());
    // 请求 = [头部] + 窗口（此刻窗口为空）+ 本轮输入
    assertThat(llm.requests).hasSize(1);
    assertThat(llm.requests.get(0).messages()).containsExactly(HEAD, user);
    assertThat(composer.windows).hasSize(1);
    assertThat(composer.windows.get(0)).isEmpty();
    assertThat(composer.turnInputs).containsExactly(List.of(user));
    assertThat(dispatched).isEmpty();
    // 终止回调：onFinished 恰好 1 次，其余 0 次（exactly-once）
    assertThat(listener.finishedTexts).containsExactly("你好，我是模型");
    assertThat(listener.finishedStates).hasSize(1);
    assertThat(listener.finishedStates.get(0).rounds()).isEqualTo(1);
    assertThat(listener.finishedStates.get(0).toolCalls()).isZero();
    assertThat(listener.finishedStates.get(0).elapsedMillis()).isGreaterThanOrEqualTo(0L);
    assertThat(listener.roundTrips).hasSize(1);
    assertThat(listener.latencies).hasSize(1);
    assertThat(listener.latencies.get(0)).isGreaterThanOrEqualTo(0L);
    assertThat(listener.roundTripStates.get(0).rounds()).isEqualTo(1);
    assertThat(listener.stops).isEmpty();
    assertThat(listener.limitCalls).isEmpty();
    assertThat(listener.errors).isEmpty();
    assertThat(listener.terminalCallbacks()).isEqualTo(1);
  }

  // ================= L2 两轮工具往返 =================

  @Test
  void twoRoundToolTripSendsHeadPlusWindowAndKeepsUserExactlyOnce() {
    ContextWindow window = new ContextWindow("l2");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    RecordingListener listener = new RecordingListener();
    List<ContentPart.ToolCall> dispatched = new ArrayList<>();
    List<LoopState> dispatchStates = new ArrayList<>();
    ContentPart.ToolCall call = new ContentPart.ToolCall("call-1", "echo", Map.of("text", "hi"));
    LlmResponse toolResponse =
        new LlmResponse(LlmMessage.assistant(List.of(call)), "fake-model", 1, 2);
    LlmResponse finalResponse = LlmResponse.text("工具结果收到");
    llm.enqueue(toolResponse);
    llm.enqueue(finalResponse);
    LlmMessage user = LlmMessage.user("帮我 echo");
    LlmMessage toolResult =
        LlmMessage.tool(new ContentPart.ToolResult("call-1", "echo", "结果:hi", null));

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher(
                (dispatchedCall, state) -> {
                  dispatched.add(dispatchedCall);
                  dispatchStates.add(state);
                  // 工具结果写回窗口是宿主的职责（骨架不改窗口）
                  window.append(toolResult);
                })
            .listener(listener)
            .build()
            .runTurn(List.of(user), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(result.turns()).isEqualTo(2);
    assertThat(result.toolCalls()).isEqualTo(1);
    assertThat(result.text()).isEqualTo("工具结果收到");
    // dispatcher 恰好 1 次，收到的就是响应里那条 call
    assertThat(dispatched).containsExactly(call);
    assertThat(dispatchStates).hasSize(1);
    assertThat(dispatchStates.get(0).rounds()).isEqualTo(1);
    assertThat(dispatchStates.get(0).toolCalls()).isEqualTo(1);
    // 窗口 = [user, assistant(tool_call), tool 结果, assistant]，user 只出现一次
    assertThat(window.messages())
        .containsExactly(
            user, toolResponse.assistantMessage(), toolResult, finalResponse.assistantMessage());
    assertThat(window.messages())
        .filteredOn(message -> LlmMessage.ROLE_USER.equals(message.role()))
        .containsExactly(user);
    // 第 2 轮请求 = [头部] + 窗口（本轮输入为空）——不是 [头部] + 窗口 + 重复的 user
    assertThat(composer.windows).hasSize(2);
    assertThat(composer.windows.get(1))
        .containsExactly(user, toolResponse.assistantMessage(), toolResult);
    assertThat(composer.turnInputs).hasSize(2);
    assertThat(composer.turnInputs.get(0)).containsExactly(user);
    assertThat(composer.turnInputs.get(1)).isEmpty();
    assertThat(llm.requests).hasSize(2);
    List<LlmMessage> expectedSecondRequest = new ArrayList<>();
    expectedSecondRequest.add(HEAD);
    expectedSecondRequest.addAll(composer.windows.get(1));
    assertThat(llm.requests.get(1).messages()).containsExactlyElementsOf(expectedSecondRequest);
    assertThat(llm.requests.get(1).messages())
        .filteredOn(message -> LlmMessage.ROLE_USER.equals(message.role()))
        .containsExactly(user);
    // 两次成功往返 ⇒ onRoundTrip 两次；终止回调只有 onFinished
    assertThat(listener.roundTrips).hasSize(2);
    assertThat(listener.finishedTexts).containsExactly("工具结果收到");
    assertThat(listener.finishedStates.get(0).rounds()).isEqualTo(2);
    assertThat(listener.finishedStates.get(0).toolCalls()).isEqualTo(1);
    assertThat(listener.stops).isEmpty();
    assertThat(listener.limitCalls).isEmpty();
    assertThat(listener.errors).isEmpty();
    assertThat(listener.terminalCallbacks()).isEqualTo(1);
  }

  // ================= L3 往返前的终止 =================

  @Test
  void stopPolicySignalBeforeFirstRoundReturnsImmediatelyWithoutCallingLlm() {
    ContextWindow window = new ContextWindow("l3");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    RecordingListener listener = new RecordingListener();
    List<ContentPart.ToolCall> dispatched = new ArrayList<>();
    StopSignal signal = new StopSignal(StopReason.CANCELLED, "用户取消");

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher((call, state) -> dispatched.add(call))
            .stopPolicy(state -> Optional.of(signal))
            .listener(listener)
            .build()
            .runTurn(List.of(LlmMessage.user("不该发出去")), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.CANCELLED);
    assertThat(result.turns()).isZero();
    assertThat(result.toolCalls()).isZero();
    assertThat(result.text()).isEmpty();
    assertThat(result.finalText()).isEmpty();
    // LLM 零调用、组请求零次、窗口零改动
    assertThat(llm.requests).isEmpty();
    assertThat(composer.windows).isEmpty();
    assertThat(window.messages()).isEmpty();
    assertThat(dispatched).isEmpty();
    // onStop 恰好 1 次、携带同一 signal；其余终止回调 0
    assertThat(listener.stops).containsExactly(signal);
    assertThat(listener.stopStates).hasSize(1);
    assertThat(listener.stopStates.get(0).rounds()).isZero();
    assertThat(listener.stopStates.get(0).toolCalls()).isZero();
    assertThat(listener.roundTrips).isEmpty();
    assertThat(listener.finishedTexts).isEmpty();
    assertThat(listener.limitCalls).isEmpty();
    assertThat(listener.errors).isEmpty();
    assertThat(listener.terminalCallbacks()).isEqualTo(1);
  }

  // ================= L4 工具批中间取消 =================

  @Test
  void cancelAtSecondToolCallBoundaryDispatchesAtMostOneCallAndDoesNotEnterNextRound() {
    ContextWindow window = new ContextWindow("l4");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    RecordingListener listener = new RecordingListener();
    List<ContentPart.ToolCall> dispatched = new ArrayList<>();
    AtomicInteger boundaryChecks = new AtomicInteger();
    StopSignal signal = new StopSignal(StopReason.CANCELLED, "批内取消");
    ContentPart.ToolCall first = new ContentPart.ToolCall("c-1", "echo", Map.of("n", 1));
    ContentPart.ToolCall second = new ContentPart.ToolCall("c-2", "echo", Map.of("n", 2));
    ContentPart.ToolCall third = new ContentPart.ToolCall("c-3", "echo", Map.of("n", 3));
    llm.enqueue(
        new LlmResponse(LlmMessage.assistant(List.of(first, second, third)), "fake-model", 1, 2));
    StopPolicy policy =
        new StopPolicy() {
          @Override
          public Optional<StopSignal> check(LoopState state) {
            return Optional.empty();
          }

          @Override
          public Optional<StopSignal> checkToolCallBoundary(LoopState state) {
            return boundaryChecks.incrementAndGet() >= 2 ? Optional.of(signal) : Optional.empty();
          }
        };

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher(
                (call, state) -> {
                  dispatched.add(call);
                  window.append(
                      LlmMessage.tool(
                          new ContentPart.ToolResult(call.id(), call.name(), "ok", null)));
                })
            .stopPolicy(policy)
            .listener(listener)
            .build()
            .runTurn(List.of(LlmMessage.user("跑三个工具")), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.CANCELLED);
    assertThat(result.turns()).isEqualTo(1);
    assertThat(result.toolCalls()).isEqualTo(1);
    // ≤1：只派发了第一条；第二条在分发前被叫停，不计数
    assertThat(dispatched).containsExactly(first);
    assertThat(boundaryChecks).hasValue(2);
    // 不进入下一轮（脚本只有一条响应；若进入会以 LLM_ERROR 结束，reason 会变）
    assertThat(llm.requests).hasSize(1);
    assertThat(listener.stops).containsExactly(signal);
    assertThat(listener.stopStates.get(0).rounds()).isEqualTo(1);
    assertThat(listener.stopStates.get(0).toolCalls()).isEqualTo(1);
    assertThat(listener.finishedTexts).isEmpty();
    assertThat(listener.limitCalls).isEmpty();
    assertThat(listener.errors).isEmpty();
    assertThat(listener.terminalCallbacks()).isEqualTo(1);
  }

  // ================= L5 LLM 失败（负向） =================

  @Test
  void llmExceptionEndsWithLlmErrorAndSkeletonDoesNotAppendRoundInputToWindow() {
    ContextWindow window = new ContextWindow("l5");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    RecordingListener listener = new RecordingListener();
    List<ContentPart.ToolCall> dispatched = new ArrayList<>();
    LlmException failure = new LlmException("网络层失败", LlmException.Kind.TRANSPORT);
    llm.failWith(failure);
    LlmMessage user = LlmMessage.user("失败的这一轮");

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher((call, state) -> dispatched.add(call))
            .listener(listener)
            .build()
            .runTurn(List.of(user), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.LLM_ERROR);
    // 失败的那一轮不计数
    assertThat(result.turns()).isZero();
    assertThat(result.toolCalls()).isZero();
    assertThat(result.text()).isEmpty();
    // 请求确实组好并发出（失败发生在 chat 内）
    assertThat(llm.requests).hasSize(1);
    assertThat(llm.requests.get(0).messages()).containsExactly(HEAD, user);
    // 骨架没有替宿主 append roundInput：窗口仍然为空
    assertThat(window.messages()).isEmpty();
    assertThat(dispatched).isEmpty();
    // onLlmError 恰好 1 次，roundInput 就是本轮输入、rounds 未加一
    assertThat(listener.errors).containsExactly(failure);
    assertThat(listener.errorInputs).containsExactly(List.of(user));
    assertThat(listener.errorStates).hasSize(1);
    assertThat(listener.errorStates.get(0).rounds()).isZero();
    assertThat(listener.errorStates.get(0).toolCalls()).isZero();
    assertThat(listener.roundTrips).isEmpty();
    assertThat(listener.finishedTexts).isEmpty();
    assertThat(listener.limitCalls).isEmpty();
    assertThat(listener.stops).isEmpty();
    assertThat(listener.terminalCallbacks()).isEqualTo(1);
  }

  // ================= L6 单响应工具调用超限（负向） =================

  @Test
  void toolCallCapExceededFiresLimitCallbackAndExecutesNoTool() {
    ContextWindow window = new ContextWindow("l6");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    RecordingListener listener = new RecordingListener();
    List<ContentPart.ToolCall> dispatched = new ArrayList<>();
    ContentPart.ToolCall first = new ContentPart.ToolCall("c-1", "echo", Map.of("n", 1));
    ContentPart.ToolCall second = new ContentPart.ToolCall("c-2", "echo", Map.of("n", 2));
    LlmResponse response =
        new LlmResponse(
            LlmMessage.assistant(List.of(new ContentPart.Text("先调两个"), first, second)),
            "fake-model",
            1,
            2);
    llm.enqueue(response);

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher((call, state) -> dispatched.add(call))
            .listener(listener)
            .maxToolCallsPerResponse(1)
            .build()
            .runTurn(List.of(LlmMessage.user("调工具")), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_CALL_LIMIT);
    assertThat(result.turns()).isEqualTo(1);
    assertThat(result.toolCalls()).isZero();
    // 整批不执行
    assertThat(dispatched).isEmpty();
    // onToolCallLimit 收到原序的 2 条 call，toolCalls 不含未执行的这批
    assertThat(listener.limitCalls).hasSize(1);
    assertThat(listener.limitCalls.get(0)).containsExactly(first, second);
    assertThat(listener.limitStates).hasSize(1);
    assertThat(listener.limitStates.get(0).rounds()).isEqualTo(1);
    assertThat(listener.limitStates.get(0).toolCalls()).isZero();
    assertThat(listener.finishedTexts).isEmpty();
    assertThat(listener.stops).isEmpty();
    assertThat(listener.errors).isEmpty();
    assertThat(listener.roundTrips).hasSize(1);
    assertThat(listener.terminalCallbacks()).isEqualTo(1);
    // 响应里的正文原样带回（TOOL_CALL_LIMIT 的 text 语义）
    assertThat(result.text()).isEqualTo("先调两个");
    // 窗口 = [user, assistant]，没有工具结果
    assertThat(window.messages())
        .containsExactly(LlmMessage.user("调工具"), response.assistantMessage());
  }

  // ================= L7 记账回调返回信号（QUOTA 形态） =================

  @Test
  void roundTripSignalStopsBeforeAnyToolDispatch() {
    ContextWindow window = new ContextWindow("l7");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    RecordingListener listener = new RecordingListener();
    List<ContentPart.ToolCall> dispatched = new ArrayList<>();
    StopSignal signal = new StopSignal(StopReason.QUOTA, "token 超限");
    llm.enqueue(LlmResponse.toolCall("c-1", "echo", Map.of("n", 1)));
    listener.roundTripSignal = Optional.of(signal);

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher((call, state) -> dispatched.add(call))
            .listener(listener)
            .build()
            .runTurn(List.of(LlmMessage.user("调工具")), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.QUOTA);
    assertThat(result.turns()).isEqualTo(1);
    assertThat(result.toolCalls()).isZero();
    // LLM 恰好 1 次；onRoundTrip 返回信号后不派发工具
    assertThat(llm.requests).hasSize(1);
    assertThat(dispatched).isEmpty();
    assertThat(listener.roundTrips).hasSize(1);
    assertThat(listener.stops).containsExactly(signal);
    assertThat(listener.stopStates.get(0).rounds()).isEqualTo(1);
    assertThat(listener.stopStates.get(0).toolCalls()).isZero();
    assertThat(listener.finishedTexts).isEmpty();
    assertThat(listener.limitCalls).isEmpty();
    assertThat(listener.errors).isEmpty();
    assertThat(listener.terminalCallbacks()).isEqualTo(1);
  }

  // ================= L8 cap=0 ⇒ 不限 =================

  @Test
  void zeroCapMeansUnlimitedAndDispatchesAllToolCalls() {
    ContextWindow window = new ContextWindow("l8");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    RecordingListener listener = new RecordingListener();
    List<ContentPart.ToolCall> dispatched = new ArrayList<>();
    ContentPart.ToolCall first = new ContentPart.ToolCall("c-1", "echo", Map.of("n", 1));
    ContentPart.ToolCall second = new ContentPart.ToolCall("c-2", "echo", Map.of("n", 2));
    ContentPart.ToolCall third = new ContentPart.ToolCall("c-3", "echo", Map.of("n", 3));
    llm.enqueue(
        new LlmResponse(LlmMessage.assistant(List.of(first, second, third)), "fake-model", 1, 2));
    llm.enqueue(LlmResponse.text("都跑完了"));

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher(
                (call, state) -> {
                  dispatched.add(call);
                  window.append(
                      LlmMessage.tool(
                          new ContentPart.ToolResult(call.id(), call.name(), "ok", null)));
                })
            .listener(listener)
            .maxToolCallsPerResponse(0)
            .build()
            .runTurn(List.of(LlmMessage.user("跑三个")), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(result.turns()).isEqualTo(2);
    assertThat(result.toolCalls()).isEqualTo(3);
    assertThat(dispatched).containsExactly(first, second, third);
    assertThat(listener.limitCalls).isEmpty();
    assertThat(listener.finishedTexts).containsExactly("都跑完了");
    assertThat(listener.terminalCallbacks()).isEqualTo(1);
  }

  // ================= L9 参数校验 =================

  @Test
  void builderRejectsEachMissingRequiredDependencyWithItsName() {
    ContextWindow window = new ContextWindow("l9");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    ToolCallDispatcher dispatcher = (call, state) -> {};

    assertThatThrownBy(
            () -> AgentLoop.builder().composer(composer).llm(llm).dispatcher(dispatcher).build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("window");
    assertThatThrownBy(
            () -> AgentLoop.builder().window(window).llm(llm).dispatcher(dispatcher).build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("composer");
    assertThatThrownBy(
            () ->
                AgentLoop.builder()
                    .window(window)
                    .composer(composer)
                    .dispatcher(dispatcher)
                    .build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("llm");
    assertThatThrownBy(() -> AgentLoop.builder().window(window).composer(composer).llm(llm).build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("dispatcher");
  }

  @Test
  void negativeMaxToolCallsPerResponseIsRejected() {
    assertThatThrownBy(() -> AgentLoop.builder().maxToolCallsPerResponse(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxToolCallsPerResponse");
  }

  @Test
  void loopStateRejectsNegativeProgress() {
    assertThatThrownBy(() -> new LoopState(-1, 0, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rounds");
    assertThatThrownBy(() -> new LoopState(0, -1, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("toolCalls");
    assertThatThrownBy(() -> new LoopState(0, 0, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("elapsedMillis");
  }

  // ================= L11 工具调用顺序 =================

  @Test
  void dispatcherReceivesAllCallsInOriginalOrderWithMonotonicCounts() {
    ContextWindow window = new ContextWindow("l11");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    RecordingListener listener = new RecordingListener();
    List<ContentPart.ToolCall> dispatched = new ArrayList<>();
    List<LoopState> dispatchStates = new ArrayList<>();
    ContentPart.ToolCall first = new ContentPart.ToolCall("c-1", "echo", Map.of("n", 1));
    ContentPart.ToolCall second = new ContentPart.ToolCall("c-2", "echo", Map.of("n", 2));
    ContentPart.ToolCall third = new ContentPart.ToolCall("c-3", "echo", Map.of("n", 3));
    llm.enqueue(
        new LlmResponse(LlmMessage.assistant(List.of(first, second, third)), "fake-model", 1, 2));
    llm.enqueue(LlmResponse.text("完成"));

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher(
                (call, state) -> {
                  dispatched.add(call);
                  dispatchStates.add(state);
                  window.append(
                      LlmMessage.tool(
                          new ContentPart.ToolResult(call.id(), call.name(), "ok", null)));
                })
            .listener(listener)
            .build()
            .runTurn(List.of(LlmMessage.user("按序调三个")), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(result.turns()).isEqualTo(2);
    assertThat(result.toolCalls()).isEqualTo(3);
    assertThat(dispatched).containsExactly(first, second, third);
    assertThat(dispatchStates).hasSize(3);
    assertThat(dispatchStates.get(0).toolCalls()).isEqualTo(1);
    assertThat(dispatchStates.get(1).toolCalls()).isEqualTo(2);
    assertThat(dispatchStates.get(2).toolCalls()).isEqualTo(3);
    assertThat(listener.terminalCallbacks()).isEqualTo(1);
  }

  // ================= L12 默认边界 = 委托 check =================

  @Test
  void lambdaOnlyStopPolicyAlsoStopsInTheMiddleOfAToolBatchThroughDefaultBoundaryCheck() {
    ContextWindow window = new ContextWindow("l12");
    RecordingComposer composer = new RecordingComposer();
    ScriptedLlm llm = new ScriptedLlm();
    RecordingListener listener = new RecordingListener();
    List<ContentPart.ToolCall> dispatched = new ArrayList<>();
    ContentPart.ToolCall first = new ContentPart.ToolCall("c-1", "echo", Map.of("n", 1));
    ContentPart.ToolCall second = new ContentPart.ToolCall("c-2", "echo", Map.of("n", 2));
    ContentPart.ToolCall third = new ContentPart.ToolCall("c-3", "echo", Map.of("n", 3));
    llm.enqueue(
        new LlmResponse(LlmMessage.assistant(List.of(first, second, third)), "fake-model", 1, 2));
    // 只用 lambda 实现 check：没有覆写 checkToolCallBoundary。
    // 若 default 委托不成立（例如缺省永不终止），第二批起仍会继续派发，本用例当场红。
    StopPolicy lambdaOnly =
        state ->
            state.toolCalls() >= 1
                ? Optional.of(new StopSignal(StopReason.CANCELLED, "批内取消"))
                : Optional.empty();

    TurnResult result =
        AgentLoop.builder()
            .window(window)
            .composer(composer)
            .llm(llm)
            .dispatcher(
                (call, state) -> {
                  dispatched.add(call);
                  window.append(
                      LlmMessage.tool(
                          new ContentPart.ToolResult(call.id(), call.name(), "ok", null)));
                })
            .stopPolicy(lambdaOnly)
            .listener(listener)
            .build()
            .runTurn(List.of(LlmMessage.user("跑三个")), List.of());

    assertThat(result.stopReason()).isEqualTo(StopReason.CANCELLED);
    assertThat(result.turns()).isEqualTo(1);
    assertThat(result.toolCalls()).isEqualTo(1);
    assertThat(dispatched).containsExactly(first);
    assertThat(llm.requests).hasSize(1);
    assertThat(listener.stops).hasSize(1);
    assertThat(listener.stops.get(0).reason()).isEqualTo(StopReason.CANCELLED);
    assertThat(listener.stopStates.get(0).toolCalls()).isEqualTo(1);
    assertThat(listener.finishedTexts).isEmpty();
    assertThat(listener.terminalCallbacks()).isEqualTo(1);
  }

  // ================= 夹具 =================

  /** 与宿主同形状的编排器：[头部] + 窗口内容（原样、按序）+ 本轮输入（原样、按序）；同时记录每次收到的两段。 */
  private static final class RecordingComposer implements ContextComposer {

    private final List<List<LlmMessage>> windows = new ArrayList<>();
    private final List<List<LlmMessage>> turnInputs = new ArrayList<>();
    private final List<List<AgentTool>> toolsSeen = new ArrayList<>();

    @Override
    public LlmRequest compose(
        List<LlmMessage> windowMessages, List<LlmMessage> turnInput, List<AgentTool> tools) {
      windows.add(List.copyOf(windowMessages));
      turnInputs.add(List.copyOf(turnInput));
      toolsSeen.add(List.copyOf(tools));
      List<LlmMessage> messages = new ArrayList<>(1 + windowMessages.size() + turnInput.size());
      messages.add(HEAD);
      messages.addAll(windowMessages);
      messages.addAll(turnInput);
      return new LlmRequest(messages, List.of());
    }
  }

  /**
   * 脚本化假 LLM：按序出队 {@link LlmResponse} 或预先注入的 {@link LlmException}；脚本耗空即抛（跑飞的循环会立刻变成 LLM_ERROR，
   * 而不是静默复用最后一条）。同时记录每次请求，供"请求由窗口派生"类断言使用。
   */
  private static final class ScriptedLlm implements LlmClient {

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    void enqueue(LlmResponse response) {
      script.addLast(response);
    }

    void failWith(LlmException exception) {
      script.addLast(exception);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      Object next = script.pollFirst();
      if (next == null) {
        throw new LlmException("脚本已耗空（第 " + requests.size() + " 次调用）——疑似循环跑飞");
      }
      if (next instanceof LlmException exception) {
        throw exception;
      }
      return (LlmResponse) next;
    }
  }

  /** 记录型监听器：四个终止回调分别记账，用来断言 exactly-once；onRoundTrip 可注入终止信号（QUOTA 形态）。 */
  private static final class RecordingListener implements TurnListener {

    private final List<LlmResponse> roundTrips = new ArrayList<>();
    private final List<Long> latencies = new ArrayList<>();
    private final List<LoopState> roundTripStates = new ArrayList<>();
    private final List<String> finishedTexts = new ArrayList<>();
    private final List<LoopState> finishedStates = new ArrayList<>();
    private final List<List<ContentPart.ToolCall>> limitCalls = new ArrayList<>();
    private final List<LoopState> limitStates = new ArrayList<>();
    private final List<StopSignal> stops = new ArrayList<>();
    private final List<LoopState> stopStates = new ArrayList<>();
    private final List<LlmException> errors = new ArrayList<>();
    private final List<List<LlmMessage>> errorInputs = new ArrayList<>();
    private final List<LoopState> errorStates = new ArrayList<>();
    private Optional<StopSignal> roundTripSignal = Optional.empty();

    @Override
    public Optional<StopSignal> onRoundTrip(LlmResponse response, long latencyMs, LoopState state) {
      roundTrips.add(response);
      latencies.add(latencyMs);
      roundTripStates.add(state);
      return roundTripSignal;
    }

    @Override
    public void onFinished(String finalText, LoopState state) {
      finishedTexts.add(finalText);
      finishedStates.add(state);
    }

    @Override
    public void onToolCallLimit(List<ContentPart.ToolCall> calls, LoopState state) {
      limitCalls.add(List.copyOf(calls));
      limitStates.add(state);
    }

    @Override
    public void onStop(StopSignal signal, LoopState state) {
      stops.add(signal);
      stopStates.add(state);
    }

    @Override
    public void onLlmError(LlmException error, List<LlmMessage> roundInput, LoopState state) {
      errors.add(error);
      errorInputs.add(List.copyOf(roundInput));
      errorStates.add(state);
    }

    int terminalCallbacks() {
      return finishedTexts.size() + limitCalls.size() + stops.size() + errors.size();
    }
  }
}
