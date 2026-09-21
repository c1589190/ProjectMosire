package io.mosire.agentlib.llm;

import static io.mosire.agentlib.llm.FakeChatEndpoint.contentFrame;
import static io.mosire.agentlib.llm.FakeChatEndpoint.deltaFrame;
import static io.mosire.agentlib.llm.FakeChatEndpoint.reasoningContentFrame;
import static io.mosire.agentlib.llm.FakeChatEndpoint.toolCallFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.llm.LlmResponse.ReasoningDisposition;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A1 验收</b>：推理模型的 {@code reasoning_content}（思维链）在协议解析层的去向——{@code jdk.httpserver} 假端点 （绑
 * 127.0.0.1 + 临时端口），全离线、不依赖任何真实供应商。
 *
 * <p>要钉住的是一件事：<b>绝不允许"供应商答了、我们读到空文本"</b>。供应商把内容全放 {@code reasoning_content} 而 {@code content}
 * 恒空（实测形态：deepseek-flash + {@code max_tokens=16}）时，实现必须把思维链折叠成正文——这个选择是否
 * 发生不靠读代码确认，而是在<b>可观测面</b>上判：{@link LlmResponse#textPart()} 有内容、{@link
 * LlmResponse#reasoningDisposition()} 标 {@code FOLDED}、{@link LlmResponse#reasoning()} 原文仍在。
 *
 * <p>折叠<b>只</b>在降级形态发生：有工具调用时不折叠（模型的意图在工具调用里，把思维链塞进正文会污染事实——正文要被回填进下一轮 对话历史）。四个象限（有/无 content ×
 * 有/无工具调用）各有独立用例。
 */
class OpenAICompatibleLlmClientReasoningTest {

  private static final String ROUTE_MODEL = "reasoning-route-model-fake";

  private FakeChatEndpoint endpoint;

  @BeforeEach
  void startFakeEndpoint() throws IOException {
    endpoint = FakeChatEndpoint.start();
  }

  @AfterEach
  void stopFakeEndpoint() {
    endpoint.close();
  }

  // ---------- A1 四个象限 ----------

  /**
   * A1 核心 pin：只有 {@code reasoning_content}、{@code content} 全空、无工具调用 → 思维链<b>折叠</b>成正文。
   *
   * <p>判别性：思维链分三块吐出（块间 flush + 停顿，逼出"跨 chunk 累积"），并<b>显式</b>发一个 {@code "content":""} 帧 ——供应商确实"发过
   * content"只是空串，与"根本没发"是两件事。只取末块、或把"见过 content 键"当成"有正文"的实现，本用例必红。
   */
  @Test
  void foldsReasoningIntoTextWhenContentStaysEmptyAndNoToolCalls() {
    endpoint
        .sayJson(reasoningContentFrame("额度全花在"))
        .sayJson(reasoningContentFrame("思考上了"))
        .sayJson(contentFrame("")) // 供应商发过 content，但是空串——不是"没发"
        .sayJson("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"length\"}]}")
        .done();

    LlmResponse response = endpoint.client(ROUTE_MODEL).chat(oneUserTurn());

    // 核心断言：正文读得到那段文本，且非空——绝不允许静默返回空文本
    String folded = response.textPart().orElseThrow();
    assertThat(folded).isNotBlank().isEqualTo("额度全花在思考上了");
    assertThat(response.reasoningDisposition()).isEqualTo(ReasoningDisposition.FOLDED);
    assertThat(response.reasoningOnly()).isTrue();
    // 原文永不丢失：折叠动作不消耗 reasoning 字段本身
    assertThat(response.reasoning()).isEqualTo(folded);
  }

  /** 正文与思维链都有 → {@code SEPARATE}：正文<b>只</b>含 content，思维链独立可读、不混进正文。 */
  @Test
  void keepsReasoningSeparateAndOutOfTextWhenContentAlsoPresent() {
    endpoint.sayJson(reasoningContentFrame("先想：用户问在吗")).sayJson(contentFrame("在的")).done();

    LlmResponse response = endpoint.client(ROUTE_MODEL).chat(oneUserTurn());

    assertThat(response.textPart()).contains("在的");
    // 正文里不得混进思维链（正文会被回填进下一轮对话历史）
    assertThat(response.textPart().orElseThrow()).doesNotContain("先想");
    assertThat(response.reasoning()).isEqualTo("先想：用户问在吗");
    assertThat(response.reasoningDisposition()).isEqualTo(ReasoningDisposition.SEPARATE);
    assertThat(response.reasoningOnly()).isFalse();
  }

  /**
   * 有工具调用 + content 空 + reasoning 非空 → <b>不折叠</b>：模型的意图在工具调用里，把思维链当正文会污染事实。故 disposition 是 {@code
   * SEPARATE}、正文为空，思维链仍独立可读，工具调用参数完整。
   */
  @Test
  void doesNotFoldReasoningWhenToolCallsPresent() {
    endpoint
        .sayJson(reasoningContentFrame("该调 echo 工具了"))
        .sayJson(toolCallFrame("call_1", "echo", "{\"text\":\"桥上风光\"}"))
        .done();

    LlmResponse response = endpoint.client(ROUTE_MODEL).chat(oneUserTurn());

    assertThat(response.textPart()).isEmpty();
    assertThat(response.reasoningDisposition()).isEqualTo(ReasoningDisposition.SEPARATE);
    assertThat(response.reasoning()).isEqualTo("该调 echo 工具了");
    assertThat(response.reasoningOnly()).isFalse();
    List<ContentPart.ToolCall> calls = toolCallsOf(response);
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0).id()).isEqualTo("call_1");
    assertThat(calls.get(0).name()).isEqualTo("echo");
    // 参数必须完整（"不折叠"不能靠丢掉工具调用来实现）
    Map<String, Object> arguments = calls.get(0).arguments();
    assertThat(arguments).containsExactlyInAnyOrderEntriesOf(Map.of("text", "桥上风光"));
  }

  /** 完全没有 {@code reasoning_content}（非推理模型）→ {@code ABSENT}、{@code reasoning()} 是空串。 */
  @Test
  void reportsAbsentAndEmptyReasoningWhenVendorSendsNoReasoningField() {
    endpoint.sayJson(contentFrame("普通模型的普通回答")).done();

    LlmResponse response = endpoint.client(ROUTE_MODEL).chat(oneUserTurn());

    assertThat(response.reasoningDisposition()).isEqualTo(ReasoningDisposition.ABSENT);
    assertThat(response.reasoning()).isEmpty();
    assertThat(response.reasoningOnly()).isFalse();
    assertThat(response.textPart()).contains("普通模型的普通回答");
  }

  /**
   * 字段别名 {@code reasoning}（不带 {@code _content}，OpenRouter 一系）与 {@code reasoning_content} 同等累积：
   * 只认一种写法的实现，换个网关就重踩同一个"答了却读到空"的坑。
   */
  @Test
  void accumulatesReasoningAliasFieldWithoutContentSuffix() {
    endpoint.sayJson(deltaFrame("{\"reasoning\":\"别名也要吃\"}")).done();

    LlmResponse response = endpoint.client(ROUTE_MODEL).chat(oneUserTurn());

    assertThat(response.reasoning()).isEqualTo("别名也要吃");
    assertThat(response.reasoningDisposition()).isEqualTo(ReasoningDisposition.FOLDED);
    assertThat(response.textPart()).contains("别名也要吃");
  }

  // ---------- LlmResponse 自身的契约（无 HTTP） ----------

  /**
   * 构造期不变式：{@code reasoning 为空 ⟺ disposition == ABSENT}——两个组件必须互证。否则"明明没有思维链却标了
   * FOLDED"，调用方按标记分流（如"这段正文不可信"）会走进死胡同。
   */
  @Test
  void rejectsInconsistentReasoningAndDisposition() {
    assertThatThrownBy(() -> withReasoning("", ReasoningDisposition.SEPARATE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不一致");
    assertThatThrownBy(() -> withReasoning("", ReasoningDisposition.FOLDED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> withReasoning("思考", ReasoningDisposition.ABSENT))
        .isInstanceOf(IllegalArgumentException.class);

    // 反面对照：合法组合照常构造（否则上面三条可能因"构造恒抛"而空转）
    assertThat(withReasoning("", ReasoningDisposition.ABSENT).reasoning()).isEmpty();
    assertThat(withReasoning("思考", ReasoningDisposition.SEPARATE).reasoning()).isEqualTo("思考");
  }

  /** {@code null} 思维链按"没下发"收口成空串（不许把 null 传下去让调用方吃 NPE）。 */
  @Test
  void normalizesNullReasoningToEmptyString() {
    LlmResponse response = withReasoning(null, ReasoningDisposition.ABSENT);

    assertThat(response.reasoning()).isEmpty();
    assertThat(response.reasoningDisposition()).isEqualTo(ReasoningDisposition.ABSENT);
  }

  /** 七参便捷构造：给了思维链即"与正文并存"，空串即"没下发"——折叠是解析器的判断，不在这里猜。 */
  @Test
  void sevenArgumentConstructorInfersDispositionFromReasoning() {
    LlmMessage assistant = LlmMessage.assistant(List.of(new ContentPart.Text("正文")));

    assertThat(new LlmResponse(assistant, "m", 1, 1, 1, 1, "思考").reasoningDisposition())
        .isEqualTo(ReasoningDisposition.SEPARATE);
    assertThat(new LlmResponse(assistant, "m", 1, 1, 1, 1, "").reasoningDisposition())
        .isEqualTo(ReasoningDisposition.ABSENT);
  }

  /**
   * 降级形态的便捷工厂自洽：正文＝思维链、disposition＝{@code FOLDED}（离线脚本复现"推理模型只吐出思考"时用）。
   *
   * <p>它同时也是 {@link io.mosire.agentlib.llm.LlmClient#text} 在 A1 路径上的行为样本：调用方拿到的是一段真实文本， 而非空串。
   */
  @Test
  void reasoningFoldedFactoryIsSelfConsistent() {
    LlmResponse folded = LlmResponse.reasoningFolded("只有思考，没有正文");

    assertThat(folded.textPart()).contains("只有思考，没有正文");
    assertThat(folded.reasoning()).isEqualTo("只有思考，没有正文");
    assertThat(folded.reasoningDisposition()).isEqualTo(ReasoningDisposition.FOLDED);
    assertThat(folded.reasoningOnly()).isTrue();
  }

  // ---------- 辅助 ----------

  /** 八参构造的简写（token 一律"未上报"）：只为让非法组合的断言保持一行、读起来是"什么组合非法"。 */
  private static LlmResponse withReasoning(String reasoning, ReasoningDisposition disposition) {
    return new LlmResponse(
        LlmMessage.assistant(List.of(new ContentPart.Text("正文"))),
        "m",
        LlmResponse.UNKNOWN_TOKENS,
        LlmResponse.UNKNOWN_TOKENS,
        LlmResponse.UNKNOWN_TOKENS,
        LlmResponse.UNKNOWN_TOKENS,
        reasoning,
        disposition);
  }

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
