package io.mosire.agentlib.llm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次 LLM 补全的结果：assistant 消息 + 记账信息。
 *
 * <p>token 计数允许缺失（{@code -1}）——不承诺精确记账的供应商填 -1 即可，配额子系统按 0 处理仍需调用方判断；这里保持"如实传递"。 缓存类计数（prompt
 * caching）仅供应商支持时才有值：缓存命中读计 {@code cacheReadTokens}、写入缓存计 {@code cacheWriteTokens}，缺失同样填 {@code
 * -1}（不编造 0，否则 token 经济会把免费命中算成真实消耗）。
 */
public record LlmResponse(
    LlmMessage assistantMessage,
    String model,
    long inputTokens,
    long outputTokens,
    long cacheReadTokens,
    long cacheWriteTokens) {

  /** 缺失/未知数标记。 */
  public static final long UNKNOWN_TOKENS = -1L;

  public LlmResponse {
    Objects.requireNonNull(assistantMessage, "assistantMessage");
    Objects.requireNonNull(model, "model");
  }

  /** 旧签名便捷构造（不含缓存记账）：缓存 token 按"未知"补齐，既有调用点无需改动。 */
  public LlmResponse(
      LlmMessage assistantMessage, String model, long inputTokens, long outputTokens) {
    this(assistantMessage, model, inputTokens, outputTokens, UNKNOWN_TOKENS, UNKNOWN_TOKENS);
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

  /** 取助手消息里的第一条纯文本（无文本则空）。 */
  public Optional<String> textPart() {
    return assistantMessage.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(ContentPart.Text.class::cast)
        .map(ContentPart.Text::text)
        .findFirst();
  }
}
