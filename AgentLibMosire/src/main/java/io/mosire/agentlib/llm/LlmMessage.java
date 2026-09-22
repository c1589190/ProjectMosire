package io.mosire.agentlib.llm;

import java.util.List;
import java.util.Objects;

/**
 * 一条 LLM 消息：role + 内容分片列表 +（可选）思维链。
 *
 * <p>role 取 {@code system}/{@code user}/{@code assistant}/{@code tool} 四值（与主流 OpenAI 兼容 供应商一致）；除
 * {@link #ROLE_SYSTEM} 外的角色名不做强制校验，以便透传未见过的供应商扩展角色。
 *
 * <p><b>{@code reasoning}（A6：思维链的回传位）</b>——多轮对话里，思考模式的供应商要求把上一轮的 {@code reasoning_content}
 * 原样带回来（simos 侧真 provider 实测：{@code HTTP 400 The "reasoning_content" in the thinking mode must be
 * passed back to the API.}）。A1 只把"读到"那半做全了（{@link LlmResponse#reasoning()}），而<b>消息里没有承载它的位置</b>⇒
 * 发送侧无论如何发不出去。 这个分量就是那个位置。
 *
 * <p><b>空串 = 没有思维链</b>（与 {@link LlmResponse} 同一口径，且 {@code null} 也收口成空串）：发送侧据此决定"发不发 {@code
 * reasoning_content} 这个键"。user/system/tool 消息以及非推理模型的一切消息都是空串——它们不该被塞一个空字段（对不认该字段的供应商 是纯风险）。它<b>只对
 * assistant 消息有意义</b>：思维链是模型上一轮的产出。
 */
public record LlmMessage(String role, List<ContentPart> content, String reasoning) {

  public static final String ROLE_SYSTEM = "system";
  public static final String ROLE_USER = "user";
  public static final String ROLE_ASSISTANT = "assistant";
  public static final String ROLE_TOOL = "tool";

  public LlmMessage {
    Objects.requireNonNull(role, "role");
    content = List.copyOf(content);
    reasoning = reasoning == null ? "" : reasoning;
  }

  /**
   * 两参兼容构造：等价于"这条消息没有思维链"。
   *
   * <p>保留它是<b>兼容性硬要求</b>（记为 A6 的一部分）：{@code SqliteConversationStore} 的解码路径与下游（Brain）的既有构造点都按两参
   * 写，加一个 record 分量若不留这个口子，那些调用点会全部编译失败——而它们多数与思维链毫无关系。
   */
  public LlmMessage(String role, List<ContentPart> content) {
    this(role, content, "");
  }

  public static LlmMessage system(String text) {
    return new LlmMessage(ROLE_SYSTEM, List.of(new ContentPart.Text(text)));
  }

  public static LlmMessage user(String text) {
    return new LlmMessage(ROLE_USER, List.of(new ContentPart.Text(text)));
  }

  public static LlmMessage assistant(List<ContentPart> content) {
    return new LlmMessage(ROLE_ASSISTANT, List.copyOf(content));
  }

  /** 带思维链的 assistant 消息（回灌历史时用；{@code null} 按"没有思维链"收口）。 */
  public static LlmMessage assistant(List<ContentPart> content, String reasoning) {
    return new LlmMessage(ROLE_ASSISTANT, List.copyOf(content), reasoning);
  }

  public static LlmMessage tool(ContentPart.ToolResult result) {
    return new LlmMessage(ROLE_TOOL, List.of(result));
  }

  /**
   * 换一份思维链（其余组件不变）。
   *
   * <p>为什么给 {@code withX} 而不给"每参数一个重载构造"：调用点通常已经有一条消息（来自解析器），只想补上思维链——重载构造会逼它把 role/content 再抄一遍（同
   * {@link LlmRequest#withSampling} 的理由）。
   */
  public LlmMessage withReasoning(String newReasoning) {
    return new LlmMessage(role, content, newReasoning);
  }

  /** 这条消息有没有思维链可回传（发送侧就判它，不直接比空串）。 */
  public boolean hasReasoning() {
    return !reasoning.isEmpty();
  }
}
