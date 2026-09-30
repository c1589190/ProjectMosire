package io.mosire.agentlib.llm;

import java.util.List;
import java.util.Objects;

/**
 * 一条 LLM 消息：role + 内容分片列表 + 思维链（内容 + <b>键是否存在</b>的三态）。
 *
 * <p>role 取 {@code system}/{@code user}/{@code assistant}/{@code tool} 四值（与主流 OpenAI 兼容 供应商一致）；除
 * {@link #ROLE_SYSTEM} 外的角色名不做强制校验，以便透传未见过的供应商扩展角色。
 *
 * <p><b>{@code reasoning} / {@code reasoningState}（A6 修复版：思维链的回传位）</b>——多轮对话里，思考模式的供应商要求把上一轮的
 * {@code reasoning_content} 原样带回来（simos 侧真 provider 实测：{@code HTTP 400 The "reasoning_content" in
 * the thinking mode must be passed back to the API.}）。第一版 A6 只用一个空串同时表示
 * “没有该字段”与“字段存在但值为空”，于是模型某次收口没有思维链时，历史 assistant 消息在线上缺键，第二轮必被思考模式供应商拒绝。
 *
 * <p><b>三态必须分开</b>：{@link ReasoningState#ABSENT} = 供应商没下发/调用方从未设置该字段；{@link ReasoningState#EMPTY} =
 * 字段明确存在，值为空串（思考模式必须原样回传）；{@link ReasoningState#TEXT} = 有思维链正文。 老调用点的 {@code new LlmMessage(role,
 * content)} 与三参构造在思维链为空时仍落到 {@code ABSENT}，保持既有行为；要表达“键存在但为空”请用 四参构造或 {@link #assistant(List,
 * String, ReasoningState)}。
 *
 * <p><b>发送规则不是“有才发”一刀切</b>：非思考路由只在 {@code TEXT} 时发该键；思考路由（{@link
 * LlmTransport#echoReasoningContent()}）对每条 assistant 消息恒发该键（{@code EMPTY} 发空串）。这样既不弄脏不认该字段的供应商，也不
 * 会在思考模式的历史里留下“缺键”的洞。
 */
public record LlmMessage(
    String role, List<ContentPart> content, String reasoning, ReasoningState reasoningState) {

  public static final String ROLE_SYSTEM = "system";
  public static final String ROLE_USER = "user";
  public static final String ROLE_ASSISTANT = "assistant";
  public static final String ROLE_TOOL = "tool";

  /**
   * 思维链字段的三态：{@link #ABSENT} = 键缺席；{@link #EMPTY} = 键存在、值为空串；{@link #TEXT} = 键存在、值非空。
   *
   * <p>为什么不是 {@code Optional<String>}：{@code Optional.empty()} 只能表达两种态中的一种；而思考模式下“字段在、值为空”必须原样回传，不能
   * 与“压根没有该字段”混同。三态在存储往返、线级发送两个方向上都有可断言的差别。
   */
  public enum ReasoningState {
    /** 没有该字段（非推理模型，或本条消息从未携带思维链）。 */
    ABSENT,
    /** 字段明确存在且为空串（思考模式要求原样回传的就是它）。 */
    EMPTY,
    /** 字段存在且非空。 */
    TEXT
  }

  public LlmMessage {
    Objects.requireNonNull(role, "role");
    content = List.copyOf(content);
    reasoning = reasoning == null ? "" : reasoning;
    Objects.requireNonNull(reasoningState, "reasoningState");
    if (reasoning.isEmpty() != (reasoningState != ReasoningState.TEXT)) {
      throw new IllegalArgumentException(
          "reasoning 与 reasoningState 不一致：非空 ⟺ TEXT，空串只能配 ABSENT/EMPTY（reasoning 长度="
              + reasoning.length()
              + ", state="
              + reasoningState
              + "）");
    }
  }

  /**
   * 两参兼容构造：等价于“这条消息没有思维链字段”。
   *
   * <p>保留它是<b>兼容性硬要求</b>：{@code SqliteConversationStore} 的解码路径与下游（Brain）的既有构造点都按两参写，加一个 record
   * 分量若不留这个口子，那些调用点会全部编译失败——而它们多数与思维链毫无关系。
   */
  public LlmMessage(String role, List<ContentPart> content) {
    this(role, content, "", ReasoningState.ABSENT);
  }

  /**
   * 三参兼容构造：思维链为空 ⇒ {@code ABSENT}，非空 ⇒ {@code TEXT}。要表达“键存在但为空”请用四参构造。
   *
   * <p>{@code null} 仍收口成空串（老调用点不该在这里吃 NPE）。
   */
  public LlmMessage(String role, List<ContentPart> content, String reasoning) {
    this(
        role,
        content,
        reasoning,
        reasoning == null || reasoning.isEmpty() ? ReasoningState.ABSENT : ReasoningState.TEXT);
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

  /** 带思维链的 assistant 消息（回灌历史时用；{@code null}/空串按“没有该字段”收口）。 */
  public static LlmMessage assistant(List<ContentPart> content, String reasoning) {
    return new LlmMessage(ROLE_ASSISTANT, List.copyOf(content), reasoning);
  }

  /** 显式指定思维链三态的 assistant 工厂：思考模式回传空串用 {@code EMPTY}。 */
  public static LlmMessage assistant(
      List<ContentPart> content, String reasoning, ReasoningState reasoningState) {
    return new LlmMessage(ROLE_ASSISTANT, List.copyOf(content), reasoning, reasoningState);
  }

  /** 思考模式下“没有思维链内容、但字段必须原样回传空串”的 assistant 工厂。 */
  public static LlmMessage assistantEchoingEmptyReasoning(List<ContentPart> content) {
    return new LlmMessage(ROLE_ASSISTANT, List.copyOf(content), "", ReasoningState.EMPTY);
  }

  public static LlmMessage tool(ContentPart.ToolResult result) {
    return new LlmMessage(ROLE_TOOL, List.of(result));
  }

  /**
   * 换一份思维链（其余组件不变）：非空 ⇒ {@code TEXT}，空 ⇒ {@code ABSENT}。
   *
   * <p>为什么给 {@code withX} 而不给“每参数一个重载构造”：调用点通常已经有一条消息（来自解析器），只想补上思维链——重载构造会逼它把 role/content 再抄一遍（同
   * {@link LlmRequest#withSampling} 的理由）。
   */
  public LlmMessage withReasoning(String newReasoning) {
    return new LlmMessage(role, content, newReasoning);
  }

  /** 换一份思维链并显式指定三态；用于存储解码/解析器把“存在但空”的事实带回来。 */
  public LlmMessage withReasoning(String newReasoning, ReasoningState newReasoningState) {
    return new LlmMessage(role, content, newReasoning, newReasoningState);
  }

  /** 换三态但保留当前内容：{@code TEXT} 要求当前内容非空，否则构造期响亮失败。 */
  public LlmMessage withReasoningState(ReasoningState newReasoningState) {
    return new LlmMessage(role, content, reasoning, newReasoningState);
  }

  /** 这条消息有没有思维链正文可回传（发送侧“非思考路由”判它，不直接比空串）。 */
  public boolean hasReasoning() {
    return !reasoning.isEmpty();
  }

  /** 这条消息是否带 {@code reasoning_content} 字段（含显式空串）。 */
  public boolean hasReasoningField() {
    return reasoningState != ReasoningState.ABSENT;
  }
}
