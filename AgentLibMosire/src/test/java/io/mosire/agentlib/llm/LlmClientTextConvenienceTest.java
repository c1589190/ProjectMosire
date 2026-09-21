package io.mosire.agentlib.llm;

import static io.mosire.agentlib.llm.FakeChatEndpoint.reasoningContentFrame;
import static io.mosire.agentlib.llm.FakeChatEndpoint.toolCallFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.llm.LlmException.Kind;
import io.mosire.agentlib.llm.LlmResponse.ReasoningDisposition;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>B1 验收</b>：{@link LlmClient#text} 这个便利方法的两条契约——"有文本就给文本"、"拿不到文本就 <b>响亮抛</b>{@link
 * Kind#NO_TEXT}，绝不返回空串"。
 *
 * <p>为什么不返回空串是硬要求：调用方用本方法的前提就是"我要的是一段文本"；返回 {@code ""} 会把"模型调了工具"、"模型什么都没说"
 * 与"模型答了一个空字符串"三种完全不同的情况混成同一个值——正是 A1 那个坑（LLM 明明答了、我们读到空）的形态。
 *
 * <p>前三个用例用 lambda 直接造 {@link LlmClient}（本接口是函数式接口，测的是便利方法的分支）；后两个跑真实 SSE 流，证明"只有工具
 * 调用"与"只有思维链"这两种供应商侧形态在这条路径上的实际结果。
 */
class LlmClientTextConvenienceTest {

  private static final String ROUTE_MODEL = "text-route-model-fake";

  private FakeChatEndpoint endpoint;

  @BeforeEach
  void startFakeEndpoint() throws IOException {
    endpoint = FakeChatEndpoint.start();
  }

  @AfterEach
  void stopFakeEndpoint() {
    endpoint.close();
  }

  // ---------- 分支（lambda 造的客户端） ----------

  /** B1：有文本就返回文本，且请求原样交给底层 {@code chat}（便利方法不得换掉调用方的请求对象）。 */
  @Test
  void returnsTextWhenResponseHasText() {
    AtomicReference<LlmRequest> seen = new AtomicReference<>();
    LlmClient client =
        request -> {
          seen.set(request);
          return LlmResponse.text("答案在这里");
        };
    LlmRequest request = oneUserTurn();

    assertThat(client.text(request)).isEqualTo("答案在这里");
    assertThat(seen.get()).isSameAs(request);
  }

  /** B1：只有工具调用（模型行为正常，但"只想要文本"的调用方要不到）→ {@code NO_TEXT}，消息指向真实原因。 */
  @Test
  void throwsNoTextKindWhenResponseOnlyHasToolCalls() {
    LlmClient client = request -> LlmResponse.toolCall("call_1", "echo", Map.of("text", "x"));

    LlmException failure =
        catchThrowableOfType(LlmException.class, () -> client.text(oneUserTurn()));

    assertThat(failure.kind()).isEqualTo(Kind.NO_TEXT);
    assertThat(failure.retryable()).isFalse();
    assertThat(failure.getMessage()).contains("只有工具调用");
    // 两类"拿不到正文"的文案必须分得开（排查方向不同：一个改调用方，一个查供应商）
    assertThat(failure.getMessage()).doesNotContain("空产出");
  }

  /** B1：正文与工具调用都空（供应商空产出）→ 同样是 {@code NO_TEXT}，但文案与上面那条分开。 */
  @Test
  void throwsNoTextKindWhenResponseIsCompletelyEmpty() {
    LlmClient client = request -> new LlmResponse(LlmMessage.assistant(List.of()), "m", -1, -1);

    LlmException failure =
        catchThrowableOfType(LlmException.class, () -> client.text(oneUserTurn()));

    assertThat(failure.kind()).isEqualTo(Kind.NO_TEXT);
    assertThat(failure.getMessage()).contains("空产出");
    assertThat(failure.getMessage()).doesNotContain("只有工具调用");
  }

  /**
   * B1 的第三类"拿不到正文"：有文本部件但<b>全是空白</b>。真实流式路径产不出这种响应（累积器只在非空时才加 {@code Text} 部件），但手工构造可达——{@code
   * AgentTemplate} 就是拿 {@code LlmResponse.text(step.text())} 造响应，模板步文本为空即触发。 此时若返回 {@code
   * ""}，就是把"答了一段空白"与"什么都没说"混成同一个值，正是本方法要消灭的东西。
   *
   * <p>消息里<b>只报字符数、不回显内容</b>（与全库"不回显可疑内容"的口径一致）。三类原因的文案必须互相分得开：排查方向各不同 （改调用方 / 查模型 / 查供应商）。
   */
  @Test
  void throwsNoTextKindWhenResponseTextIsBlank() {
    LlmClient client = request -> LlmResponse.text("   ");

    LlmException failure =
        catchThrowableOfType(LlmException.class, () -> client.text(oneUserTurn()));

    assertThat(failure.kind()).isEqualTo(Kind.NO_TEXT);
    assertThat(failure.getMessage()).contains("空白").contains("3 个字符");
    assertThat(failure.getMessage()).doesNotContain("空产出").doesNotContain("只有工具调用");
  }

  /**
   * B1 与 A1 的交界：正文由思维链折叠而来时，{@code text()} <b>照常返回</b>那段文本（A1 的默认策略），调用方自行判 {@code reasoningOnly()}
   * 决定采信。
   */
  @Test
  void returnsFoldedReasoningTextInsteadOfFailing() {
    LlmClient client = request -> LlmResponse.reasoningFolded("只有思考，没有正文");

    assertThat(client.text(oneUserTurn())).isEqualTo("只有思考，没有正文");
  }

  // ---------- 真实 SSE 流（端到端） ----------

  /** B1 端到端：真实流只吐出工具调用 → {@code chat()} 本身是成功返回（工具调用是正常行为），而 {@code text()} 抛 {@code NO_TEXT}。 */
  @Test
  void toolOnlyStreamThrowsNoTextEndToEnd() {
    endpoint.sayJson(toolCallFrame("call_1", "echo", "{\"text\":\"x\"}")).done();
    OpenAICompatibleLlmClient client = endpoint.client(ROUTE_MODEL);

    LlmResponse response = client.chat(oneUserTurn());
    assertThat(response.textPart()).isEmpty();
    assertThat(toolCallsOf(response)).hasSize(1);

    LlmException failure =
        catchThrowableOfType(LlmException.class, () -> client.text(oneUserTurn()));

    assertThat(failure.kind()).isEqualTo(Kind.NO_TEXT);
    assertThat(failure.getMessage()).contains("只有工具调用");
  }

  /**
   * B1 端到端 + A1 核心：供应商只发 {@code reasoning_content}（{@code content} 全空）——{@code text()} 返回<b>折叠后</b>
   * 的文本，绝不静默返回空串。
   *
   * <p>这正是 simos 实测那条路径（推理模型 + 小 {@code max_tokens}）的正面证据：调用方拿到的是一段真实文本，且同一份响应上 {@code disposition}
   * 明确标了 {@code FOLDED}（"这段正文是思考来的"可观测，不必去猜）。
   */
  @Test
  void reasoningOnlyStreamYieldsFoldedTextInsteadOfEmptyString() {
    endpoint.sayJson(reasoningContentFrame("额度全花在思考上了")).done();
    OpenAICompatibleLlmClient client = endpoint.client(ROUTE_MODEL);

    assertThat(client.text(oneUserTurn())).isNotBlank().isEqualTo("额度全花在思考上了");
    assertThat(client.chat(oneUserTurn()).reasoningDisposition())
        .isEqualTo(ReasoningDisposition.FOLDED);
  }

  // ---------- 辅助 ----------

  private static LlmRequest oneUserTurn() {
    return LlmRequest.ofMessages(List.of(LlmMessage.user("在吗")));
  }

  private static List<ContentPart.ToolCall> toolCallsOf(LlmResponse response) {
    return response.assistantMessage().content().stream()
        .filter(ContentPart.ToolCall.class::isInstance)
        .map(ContentPart.ToolCall.class::cast)
        .toList();
  }
}
