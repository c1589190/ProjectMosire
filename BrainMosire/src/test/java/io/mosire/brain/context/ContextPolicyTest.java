package io.mosire.brain.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextPolicyTest {

  /** 每层默认预算：SYSTEM 慷慨（恒前缀、缓存收益最大），其余层 0 = 未启用。 */
  @Test
  void defaultsGiveGenerousSystemBudgetAndZeroOthers() {
    ContextPolicy policy = ContextPolicy.defaults();

    assertThat(policy.budgetFor(ContextLayer.SYSTEM)).isPositive();
    for (ContextLayer layer : ContextLayer.values()) {
      if (layer != ContextLayer.SYSTEM) {
        assertThat(policy.budgetFor(layer)).as("默认策略下 %s 应未启用（预算 0）", layer).isZero();
      }
    }
  }

  /** 空策略（无任何配置）对任意层都返回 0——"未配置 = 未启用"。 */
  @Test
  void budgetForUnconfiguredLayerReturnsZero() {
    ContextPolicy empty = new ContextPolicy();

    for (ContextLayer layer : ContextLayer.values()) {
      assertThat(policyBudgetOf(empty, layer)).isZero();
    }
  }

  /** withBudget 不可变：原策略不变，返回的新实例携带新预算。 */
  @Test
  void withBudgetReturnsNewInstanceAndLeavesOriginalUntouched() {
    ContextPolicy original = ContextPolicy.defaults();
    int originalSystem = original.budgetFor(ContextLayer.SYSTEM);

    ContextPolicy updated = original.withBudget(ContextLayer.RULES, 512);

    assertThat(original.budgetFor(ContextLayer.RULES)).isZero();
    assertThat(original.budgetFor(ContextLayer.SYSTEM)).isEqualTo(originalSystem);
    assertThat(updated).isNotSameAs(original);
    assertThat(updated.budgetFor(ContextLayer.RULES)).isEqualTo(512);
    assertThat(updated.budgetFor(ContextLayer.SYSTEM)).isEqualTo(originalSystem);
  }

  /** 预算不允许为负：负数是配置错误，构造期即拒绝。 */
  @Test
  void withBudgetRejectsNegativeTokens() {
    assertThatThrownBy(() -> ContextPolicy.defaults().withBudget(ContextLayer.MEMORY, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static int policyBudgetOf(ContextPolicy policy, ContextLayer layer) {
    Map<ContextLayer, Integer> snapshot = new EnumMap<>(ContextLayer.class);
    for (ContextLayer l : ContextLayer.values()) {
      snapshot.put(l, policy.budgetFor(l));
    }
    return snapshot.get(layer);
  }
}
