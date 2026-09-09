package io.mosire.brain.context;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 每层 token 预算（开发计划-二期.md §四 Token Economy，P2-3）。
 *
 * <p>数字是<b>估算</b>且<b>建议性</b>的：当前没有真实分词器，token ≈ 字符数 / 4（同 {@link ContextComposition}
 * 的估算法）；本类只声明"每层允许多大"，截断/淘汰由后续波次落地——预算超限在当前不产生任何行为。
 */
public final class ContextPolicy {

  /** SYSTEM 默认预算：恒前缀、prompt-cache 收益最大，故给足；其余层默认 0 = 未启用。 */
  static final int DEFAULT_SYSTEM_BUDGET = 4096;

  private final Map<ContextLayer, Integer> budgets;

  /** 空策略：所有层都未配置（预算一律 0）。 */
  public ContextPolicy() {
    this.budgets = Map.of();
  }

  private ContextPolicy(Map<ContextLayer, Integer> budgets) {
    this.budgets = Map.copyOf(budgets);
  }

  /** 默认策略：SYSTEM 慷慨，其余层 0（未启用）。 */
  public static ContextPolicy defaults() {
    return new ContextPolicy(Map.of(ContextLayer.SYSTEM, DEFAULT_SYSTEM_BUDGET));
  }

  /** 某层的 token 预算；未配置返回 0（0 = 未启用，不是"预算为零仍生效"）。 */
  public int budgetFor(ContextLayer layer) {
    return budgets.getOrDefault(Objects.requireNonNull(layer, "layer"), 0);
  }

  /** 返回携带新预算的不可变副本（原策略不变）；{@code tokens} 为 0 表示取消该层预算，负数即配置错误。 */
  public ContextPolicy withBudget(ContextLayer layer, int tokens) {
    Objects.requireNonNull(layer, "layer");
    if (tokens < 0) {
      throw new IllegalArgumentException("预算不能为负: " + tokens);
    }
    Map<ContextLayer, Integer> next = new EnumMap<>(ContextLayer.class);
    next.putAll(budgets);
    if (tokens == 0) {
      next.remove(layer);
    } else {
      next.put(layer, tokens);
    }
    return new ContextPolicy(next);
  }
}
