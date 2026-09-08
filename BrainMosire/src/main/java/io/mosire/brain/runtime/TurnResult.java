package io.mosire.brain.runtime;

import java.util.Optional;

/** 一次回合（用户输入 → 终止）的结果摘要。 */
public record TurnResult(StopReason stopReason, int turns, int toolCalls, String text) {

  public TurnResult {
    if (stopReason == null) {
      throw new IllegalArgumentException("stopReason 不能为空");
    }
  }

  public TurnResult(StopReason stopReason, int turns, int toolCalls) {
    this(stopReason, turns, toolCalls, "");
  }

  /** 最终纯文本（可能为空字符串——如纯工具回合后终止）。 */
  public Optional<String> finalText() {
    return text == null || text.isEmpty() ? Optional.empty() : Optional.of(text);
  }
}
