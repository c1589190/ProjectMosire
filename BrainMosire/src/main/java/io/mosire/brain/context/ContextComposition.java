package io.mosire.brain.context;

import java.util.EnumMap;
import java.util.Map;

/**
 * 分层 token 构成诊断（后续 {@code context} CLI 的数据源）：报告 {@code buildRequest} 在同一输入下将产出的每层估算 token。
 *
 * <p><b>估算法（明确声明）</b>：当前没有真实分词器，token ≈ 文本字符数 / 4（整数除法、向下取整）——对英文粗略可用，对中文明显偏低（1 汉字 ≈ 1~2
 * token）。真实分词器（JTokkit）在后续波次接入，届时只替换估算函数，本记录结构与 {@link ContextLayer} 层序契约不变。
 */
public record ContextComposition(Map<ContextLayer, Integer> estimatedTokens) {

  public ContextComposition {
    Map<ContextLayer, Integer> snapshot = new EnumMap<>(ContextLayer.class);
    if (estimatedTokens != null) {
      snapshot.putAll(estimatedTokens);
    }
    estimatedTokens = Map.copyOf(snapshot);
  }

  /** 各层估算之和（缺失层计 0）。 */
  public int total() {
    return estimatedTokens.values().stream().mapToInt(Integer::intValue).sum();
  }
}
