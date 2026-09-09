package io.mosire.brain.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextCompositionTest {

  /** total() = 各层估算 token 之和。 */
  @Test
  void totalIsSumOfPerLayerEstimates() {
    Map<ContextLayer, Integer> tokens = new EnumMap<>(ContextLayer.class);
    tokens.put(ContextLayer.SYSTEM, 100);
    tokens.put(ContextLayer.MEMORY, 40);
    tokens.put(ContextLayer.CONVERSATION, 10);

    ContextComposition composition = new ContextComposition(tokens);

    assertThat(composition.total()).isEqualTo(150);
  }

  /** 缺失的层视为 0——只统计已报告的层。 */
  @Test
  void missingLayersCountAsZero() {
    Map<ContextLayer, Integer> tokens = new EnumMap<>(ContextLayer.class);
    tokens.put(ContextLayer.SYSTEM, 7);

    ContextComposition composition = new ContextComposition(tokens);

    assertThat(composition.estimatedTokens()).containsEntry(ContextLayer.SYSTEM, 7);
    assertThat(composition.total()).isEqualTo(7);
  }

  /** 空 composition 合法（全部层未启用）：total 为 0。 */
  @Test
  void emptyCompositionTotalsZero() {
    ContextComposition composition = new ContextComposition(Map.of());

    assertThat(composition.total()).isZero();
    assertThat(composition.estimatedTokens()).isEmpty();
  }

  /** 防御性拷贝 + 不可变：构造后再改传入 Map 不影响 composition，直接写 composition 也被拒绝。 */
  @Test
  void compositionIsAnImmutableSnapshot() {
    Map<ContextLayer, Integer> tokens = new EnumMap<>(ContextLayer.class);
    tokens.put(ContextLayer.SYSTEM, 100);
    ContextComposition composition = new ContextComposition(tokens);

    tokens.put(ContextLayer.SYSTEM, 999);

    assertThat(composition.estimatedTokens()).containsEntry(ContextLayer.SYSTEM, 100);
    assertThatThrownBy(
            () ->
                new ContextComposition(Map.of(ContextLayer.SYSTEM, 1))
                    .estimatedTokens()
                    .put(ContextLayer.RULES, 5))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
