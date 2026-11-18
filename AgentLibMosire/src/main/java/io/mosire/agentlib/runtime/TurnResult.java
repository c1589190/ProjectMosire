package io.mosire.agentlib.runtime;

import java.util.Optional;

/**
 * 一次用户输入到回合结束的结果摘要。
 *
 * @param stopReason 停因
 * @param turns 本用户回合中成功完成的模型往返次数
 * @param toolCalls 已派发的工具调用次数，超限后为历史补写的结果不计入
 * @param text 最终文字
 */
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
