package io.mosire.brain.subagent;

/**
 * 繁殖预算耗尽（深度以外的两个额度：单调用者直系、全局在管；见 {@link SubagentLimits}）。
 *
 * <p><b>为什么与 {@link SubagentRejectedException} 分开</b>：两者对模型的含义不同——权限/档位越权是"你不该要这个" （ {@code
 * PERMISSION_DENIED}），预算是"现在不行，等等或换个做法"（{@code BUDGET_EXHAUSTED}）。合成一个码会让模型把
 * "额度用光了"读成"我没权限"，进而去试别的提权路径（最坏情况是它换个模板接着撞）。
 *
 * <p>消息里必须带<b>需求 / 剩余 / 上限</b>三个数（裁决口径）：只说"超限"的话，模型无法知道差距是 1 还是 100， 也就无法自己收敛（少派一个 vs 换整体策略）。
 */
public final class SubagentBudgetExceededException extends SubagentRejectedException {

  private static final long serialVersionUID = 1L;

  public SubagentBudgetExceededException(String message) {
    super(message);
  }
}
