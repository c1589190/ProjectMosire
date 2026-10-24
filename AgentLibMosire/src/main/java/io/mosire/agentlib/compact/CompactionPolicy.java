package io.mosire.agentlib.compact;

import java.util.Objects;

/**
 * 默认上下文压缩的容量与预算策略。
 *
 * <p>触发线严格由调用方给出的模型容量计算：{@code maxContextTokens - maxOutputTokens - promptTokens -
 * headroomTokens}。库不猜测缺失容量，也不提供固定常数兜底——容量未知时应在装配期响亮失败，而不是在运行时悄悄关掉压缩。
 *
 * @param maxContextTokens 模型最大上下文 token（必须为正）
 * @param maxOutputTokens 本次 Agent 的有效最大输出 token（必须为正；调用方应先与 Agent 级上限取更小值）
 * @param summaryMaxTokens 摘要调用允许的最大输出 token（必须为正且不超过 {@code maxOutputTokens}）
 * @param headroomTokens 额外安全余量（必须非负；缺省 0）
 * @param compactionPrompt 固定的压缩提示词（非空白；其估算 token 参与触发线计算）
 */
public record CompactionPolicy(
    int maxContextTokens,
    int maxOutputTokens,
    int summaryMaxTokens,
    int headroomTokens,
    String compactionPrompt) {

  public CompactionPolicy {
    if (maxContextTokens <= 0) {
      throw new IllegalArgumentException("maxContextTokens 必须为正: " + maxContextTokens);
    }
    if (maxOutputTokens <= 0) {
      throw new IllegalArgumentException("maxOutputTokens 必须为正: " + maxOutputTokens);
    }
    if (summaryMaxTokens <= 0) {
      throw new IllegalArgumentException("summaryMaxTokens 必须为正: " + summaryMaxTokens);
    }
    if (summaryMaxTokens > maxOutputTokens) {
      throw new IllegalArgumentException(
          "summaryMaxTokens 不得大于 maxOutputTokens: summaryMaxTokens="
              + summaryMaxTokens
              + ", maxOutputTokens="
              + maxOutputTokens);
    }
    if (headroomTokens < 0) {
      throw new IllegalArgumentException("headroomTokens 不得为负: " + headroomTokens);
    }
    Objects.requireNonNull(compactionPrompt, "compactionPrompt");
    if (compactionPrompt.isBlank()) {
      throw new IllegalArgumentException("compactionPrompt 不得为空白");
    }
  }
}
