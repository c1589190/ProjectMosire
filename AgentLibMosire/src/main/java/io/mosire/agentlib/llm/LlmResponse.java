package io.mosire.agentlib.llm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 LLM 补全的结果：assistant 消息 + 记账信息 + 思维链。
 *
 * <p>token 计数允许缺失（{@code -1}）——不承诺精确记账的供应商填 -1 即可，配额子系统按 0 处理仍需调用方判断；这里保持"如实传递"。 缓存类计数（prompt
 * caching）仅供应商支持时才有值：缓存命中读计 {@code cacheReadTokens}、写入缓存计 {@code cacheWriteTokens}，缺失同样填 {@code
 * -1}（不编造 0，否则 token 经济会把免费命中算成真实消耗）。
 *
 * <p><b>思维链（A1 读到、A6 回传：{@code reasoning_content}）</b>——本记录对"推理模型答了但我们读到空"这个实测坑的正面回答，以及
 * 对"多轮对话里供应商要求把思维链带回来"这个实测坑的正面回答：
 *
 * <ul>
 *   <li>{@link #reasoning()}：思维链原文，<b>独立字段</b>，未下发 = 空串。它<b>永不丢失</b>：无论是否折叠进正文，原文都在这儿。
 *   <li><b>它还同时挂在助手消息上</b>（{@link LlmMessage#reasoning()}）：构造期自动挂。于是"把这条消息追加进对话历史"这种最普通的
 *       消费方式，天然就把思维链带回了下一轮请求（A6）。<b>挂的是消息的 reasoning 分量，不是正文</b>——正文仍只含 content。
 *   <li>{@link #reasoningDisposition()}：本次到底是"没有思维链"、"思维链与正文并存"还是"思维链被折叠进了正文"。这是一个
 *       <b>可观测标记</b>——调用方（与日志/事件）不必靠"正文里怎么有段怪话"去猜。
 * </ul>
 *
 * <p><b>折叠只在降级形态发生</b>（{@link ReasoningDisposition#FOLDED}）：正文为空、且<b>没有任何工具调用</b>、思维链非空时，把思维链
 * 作为正文交回。此时若不折叠，调用方拿到的就是空文本——正是那个"看起来像没输出"的极难排查的失败。有工具调用时<b>不</b>折叠：那种情况下
 * 模型的意图体现在工具调用里，把思维链塞进正文会污染事实（正文会被回填进下一轮对话历史）。
 *
 * <p>只把思维链当正文用会误判的调用方（例如拿它当"模型的回答"直接展示）请显式判 {@link #reasoningOnly()} 并自行响亮处理。
 */
public record LlmResponse(
    LlmMessage assistantMessage,
    String model,
    long inputTokens,
    long outputTokens,
    long cacheReadTokens,
    long cacheWriteTokens,
    String reasoning,
    ReasoningDisposition reasoningDisposition) {

  /** 缺失/未知数标记。 */
  public static final long UNKNOWN_TOKENS = -1L;

  /**
   * 思维链的去向（A1 的可观测表现）。
   *
   * <p>三值与"供应商是否下发了思维链"、"正文里有没有它"一一对应，且<b>互斥穷尽</b>——不存在"没下发却折叠了"这种态（构造期校验拦住）。
   */
  public enum ReasoningDisposition {

    /** 供应商没下发思维链（非推理模型，或本次没产出）。 */
    ABSENT,

    /**
     * 思维链与正文并存：它在 {@link #reasoning()} 里，<b>没有</b>混进 {@link #assistantMessage()} 的<b>正文</b>（正文只含
     * content）。
     */
    SEPARATE,

    /**
     * 思维链被折叠成了正文：本响应正文<b>为空且无工具调用</b>，若原样交回就是"空回答"——故取思维链作正文（原文同时保留在 {@link #reasoning()}）。
     *
     * <p>典型触发：推理模型在小 {@code max_tokens} 下把额度全花在思考上（实测：{@code max_tokens=16} 时 {@code
     * content=""}、内容全在 {@code reasoning_content}）。
     */
    FOLDED
  }

  public LlmResponse {
    Objects.requireNonNull(assistantMessage, "assistantMessage");
    Objects.requireNonNull(model, "model");
    reasoning = reasoning == null ? "" : reasoning;
    Objects.requireNonNull(reasoningDisposition, "reasoningDisposition");
    if (reasoning.isEmpty() != (reasoningDisposition == ReasoningDisposition.ABSENT)) {
      // 两个组件必须互证：否则"没有思维链"却标成 FOLDED，调用方按标记做分流会走进死胡同
      throw new IllegalArgumentException(
          "reasoning 与 reasoningDisposition 不一致：reasoning 为空 ⟺ disposition 必须是 ABSENT（实际 reasoning 长度="
              + reasoning.length()
              + ", disposition="
              + reasoningDisposition
              + "）");
    }
    // A6：把思维链**挂回助手消息**——消费方"把上一条 assistant 原样追加进历史"（simos DecisionAgentRunner、
    // brain AgentPipeline 都是这一行）于是天然把它回传给供应商，零改动。不挂的话，多轮对话的第二轮会被思考模式的
    // 供应商拒掉（实测 400：reasoning_content must be passed back）。
    // ★ 反向不做（不因 reasoning 为空而抹掉消息自带的思维链）：那是调用方放进来的事实，删掉等于篡改它的输入。
    if (!reasoning.isEmpty()) {
      assistantMessage = assistantMessage.withReasoning(reasoning);
    }
  }

  /** 旧签名便捷构造（不含缓存记账）：缓存 token 按"未知"补齐，既有调用点无需改动。 */
  public LlmResponse(
      LlmMessage assistantMessage, String model, long inputTokens, long outputTokens) {
    this(assistantMessage, model, inputTokens, outputTokens, UNKNOWN_TOKENS, UNKNOWN_TOKENS);
  }

  /** 六参便捷构造（不含思维链）：等价于"供应商没下发 reasoning"。 */
  public LlmResponse(
      LlmMessage assistantMessage,
      String model,
      long inputTokens,
      long outputTokens,
      long cacheReadTokens,
      long cacheWriteTokens) {
    this(
        assistantMessage,
        model,
        inputTokens,
        outputTokens,
        cacheReadTokens,
        cacheWriteTokens,
        "",
        ReasoningDisposition.ABSENT);
  }

  /** 七参便捷构造：给了思维链即视为"与正文并存"（{@link ReasoningDisposition#SEPARATE}）——折叠是解析器的判断，不在此猜。 */
  public LlmResponse(
      LlmMessage assistantMessage,
      String model,
      long inputTokens,
      long outputTokens,
      long cacheReadTokens,
      long cacheWriteTokens,
      String reasoning) {
    this(
        assistantMessage,
        model,
        inputTokens,
        outputTokens,
        cacheReadTokens,
        cacheWriteTokens,
        reasoning,
        reasoning == null || reasoning.isEmpty()
            ? ReasoningDisposition.ABSENT
            : ReasoningDisposition.SEPARATE);
  }

  /** 纯文本助手消息的便捷工厂（tool 参数无、无工具调用），模型名自动生成便于离线脚本。 */
  public static LlmResponse text(String assistantText) {
    return new LlmResponse(
        LlmMessage.assistant(List.of(new ContentPart.Text(assistantText))),
        "fake-model",
        UNKNOWN_TOKENS,
        UNKNOWN_TOKENS);
  }

  /** 单一工具调用的助手消息便捷工厂。 */
  public static LlmResponse toolCall(
      String toolCallId, String toolName, Map<String, Object> arguments) {
    LlmMessage message =
        LlmMessage.assistant(List.of(new ContentPart.ToolCall(toolCallId, toolName, arguments)));
    return new LlmResponse(message, "fake-model", UNKNOWN_TOKENS, UNKNOWN_TOKENS);
  }

  /** 降级形态的便捷工厂：正文为空、思维链被折叠成正文（离线脚本复现"推理模型只吐出思考"时用）。 */
  public static LlmResponse reasoningFolded(String reasoning) {
    return new LlmResponse(
        LlmMessage.assistant(List.of(new ContentPart.Text(reasoning))),
        "fake-model",
        UNKNOWN_TOKENS,
        UNKNOWN_TOKENS,
        UNKNOWN_TOKENS,
        UNKNOWN_TOKENS,
        reasoning,
        ReasoningDisposition.FOLDED);
  }

  /** 取助手消息里的第一条纯文本（无文本则空）。 */
  public Optional<String> textPart() {
    return assistantMessage.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(ContentPart.Text.class::cast)
        .map(ContentPart.Text::text)
        .findFirst();
  }

  /** 本次的正文是不是"思维链折叠来的"（{@link ReasoningDisposition#FOLDED} 的简写）：想拒绝思维链当答案的调用方判它。 */
  public boolean reasoningOnly() {
    return reasoningDisposition == ReasoningDisposition.FOLDED;
  }
}
