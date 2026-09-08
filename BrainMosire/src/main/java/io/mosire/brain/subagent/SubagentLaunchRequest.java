package io.mosire.brain.subagent;

import java.util.Set;

/**
 * 一次 spawn 请求（调用方意图，不是最终配置——最终配置由模板∩收紧推导，见 {@link SubagentManager}）。
 *
 * <p>收紧语义：所有 cap 只能比模板更严（取 min；更松的 cap 被忽略而非报错——"拒绝收紧失败" 不算错误）；{@code extraDenied} 只增不减 denied
 * 名单；白名单永不放大。
 *
 * @param templateId 目标模板 id（必填）
 * @param goal 任务目标（必填；事件里只记长度不记全文，防上下文泄漏）
 * @param extraDenied 追加拒绝的工具名；null 视为空集
 * @param maxTurnsCap 轮次上限收紧；null = 不收紧
 * @param timeBudgetSecondsCap 时间预算（秒）收紧；null = 不收紧
 * @param quotaMaxTokensCap token 配额收紧；null = 不收紧
 */
public record SubagentLaunchRequest(
    String templateId,
    String goal,
    Set<String> extraDenied,
    Integer maxTurnsCap,
    Long timeBudgetSecondsCap,
    Long quotaMaxTokensCap) {

  public SubagentLaunchRequest {
    if (templateId == null || templateId.isBlank()) {
      throw new IllegalArgumentException("templateId 不能为空");
    }
    if (goal == null || goal.isBlank()) {
      throw new IllegalArgumentException("goal 不能为空");
    }
    extraDenied = extraDenied == null ? Set.of() : Set.copyOf(extraDenied);
  }
}
