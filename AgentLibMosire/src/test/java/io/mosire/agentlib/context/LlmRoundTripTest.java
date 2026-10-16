package io.mosire.agentlib.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 设计 §3.3 的往返语义：{@link LlmRoundTrip#call} = 组请求 → 发信 → 回灌，四步一条线。
 *
 * <p>三件必须成立的事（{@code 设计-上下文窗口下沉.md} §3.3 与 §5.2）：①成功时返回前窗口<b>已含</b>本轮输入与这次答复（末尾是 assistant）， 且请求恰为
 * {@code [头部] + 窗口 + 本轮输入}；②{@code llm.chat} 抛 {@link LlmException}
 * 时窗口<b>一个字节都没动</b>（本轮输入也没写回——失败算不算历史由宿主决定， 见 §5.2 的接线要求）；③本轮输入 0 条（宿主已把输入 append 进窗口的用法）同样成立。
 *
 * <p>编排器用最朴素的 {@code [头部] + 窗口原样 + 本轮输入原样} 实现：本类测的是往返，不是装配策略，头部内容因此取一个可逐字断言的常量。
 */
class LlmRoundTripTest {

  private static final String HEAD_TEXT = "宿主头部";

  private static final LlmMessage HEAD = LlmMessage.system(HEAD_TEXT);

  /** 与宿主同形状的编排器：[头部] + 窗口（原样、按序）+ 本轮输入（原样、按序）。 */
  private static final ContextComposer COMPOSER =
      (windowMessages, turnInput, tools) -> {
        List<LlmMessage> messages = new ArrayList<>(1 + windowMessages.size() + turnInput.size());
        messages.add(HEAD);
        messages.addAll(windowMessages);
        messages.addAll(turnInput);
        return new LlmRequest(messages, List.of());
      };

  @Test
  void successfulCallWritesTurnInputAndAnswerBackAndSendsHeadPlusWindowPlusInput() {
    List<LlmMessage> history =
        List.of(
            LlmMessage.user("历史提问"), LlmMessage.assistant(List.of(new ContentPart.Text("历史回答"))));
    ContextWindow window = new ContextWindow("c", history);
    RecordingLlmClient llm = new RecordingLlmClient();
    LlmResponse scripted =
        new LlmResponse(
            LlmMessage.assistant(List.of(new ContentPart.Text("新回答"))), "fake-model", 1, 2);
    llm.enqueue(scripted);
    List<LlmMessage> turnInput = List.of(LlmMessage.user("新提问"));

    LlmResponse response = LlmRoundTrip.call(llm, window, COMPOSER, turnInput, List.of());

    assertThat(response).isSameAs(scripted);
    // 请求 = [头部] + 窗口（调用前的快照）+ 本轮输入，逐条按序
    assertThat(llm.requests).hasSize(1);
    assertThat(llm.requests.get(0).messages())
        .containsExactly(
            HEAD,
            LlmMessage.user("历史提问"),
            LlmMessage.assistant(List.of(new ContentPart.Text("历史回答"))),
            LlmMessage.user("新提问"));
    // 返回时窗口已含本轮输入 + 答复，末尾是 assistant
    assertThat(window.messages())
        .containsExactly(
            LlmMessage.user("历史提问"),
            LlmMessage.assistant(List.of(new ContentPart.Text("历史回答"))),
            LlmMessage.user("新提问"),
            scripted.assistantMessage());
    assertThat(window.messages().get(window.size() - 1).role())
        .isEqualTo(LlmMessage.ROLE_ASSISTANT);
    assertThat(window.unsavedTail())
        .as("本轮输入与答复都落进未落库尾部（宿主据此落库）")
        .containsExactly(LlmMessage.user("新提问"), scripted.assistantMessage());
  }

  @Test
  void failedCallLeavesTheWindowByteForByteUntouched() {
    ContextWindow window = new ContextWindow("c", List.of(LlmMessage.user("历史提问")));
    window.append(LlmMessage.user("未落库尾部"));
    List<LlmMessage> before = window.messages();
    List<LlmMessage> tailBefore = window.unsavedTail();
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.failWith(new LlmException("模拟模型调用失败"));
    List<LlmMessage> turnInput = List.of(LlmMessage.user("本轮输入"));

    assertThatThrownBy(() -> LlmRoundTrip.call(llm, window, COMPOSER, turnInput, List.of()))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("模拟模型调用失败");

    // 请求确实发出去了（失败发生在 chat 内，不是组请求之前）——但窗口与尾部一个字节都没动
    assertThat(llm.requests).hasSize(1);
    assertThat(llm.requests.get(0).messages())
        .containsExactly(
            HEAD, LlmMessage.user("历史提问"), LlmMessage.user("未落库尾部"), LlmMessage.user("本轮输入"));
    assertThat(window.size()).isEqualTo(2);
    assertThat(window.messages()).containsExactlyElementsOf(before);
    assertThat(window.unsavedTail()).containsExactlyElementsOf(tailBefore);
    assertThat(window.messages()).doesNotContain(LlmMessage.user("本轮输入"));
    assertThat(window.messages())
        .noneMatch(message -> LlmMessage.ROLE_ASSISTANT.equals(message.role()));
  }

  @Test
  void emptyTurnInputStillWritesBackTheAnswerAndSendsWindowAsIs() {
    // 0 条形态：宿主已把本轮输入 append 进窗口（连同"每轮现插、刻意不落库"的提醒），窗口即请求
    ContextWindow window = new ContextWindow("c");
    window.append(LlmMessage.user("每轮现插的提醒"));
    window.markSaved();
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(LlmResponse.text("答复"));

    LlmResponse response = LlmRoundTrip.call(llm, window, COMPOSER, List.of(), List.of());

    assertThat(llm.requests.get(0).messages()).containsExactly(HEAD, LlmMessage.user("每轮现插的提醒"));
    assertThat(window.messages())
        .containsExactly(LlmMessage.user("每轮现插的提醒"), response.assistantMessage());
    assertThat(window.unsavedTail()).containsExactly(response.assistantMessage());
  }

  /** 记录每次收到的 {@link LlmRequest} 的脚本桩；脚本耗空或显式注入失败时抛 {@link LlmException}（= LlmClient 契约的失败面）。 */
  private static final class RecordingLlmClient implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();
    private LlmException failure;

    void enqueue(LlmResponse response) {
      script.addLast(response);
    }

    void failWith(LlmException exception) {
      failure = exception;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      if (failure != null) {
        throw failure;
      }
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new LlmException("RecordingLlmClient 脚本已耗空");
      }
      return response;
    }
  }
}
