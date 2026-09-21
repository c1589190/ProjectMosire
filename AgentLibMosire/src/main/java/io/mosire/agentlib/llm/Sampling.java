package io.mosire.agentlib.llm;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一次请求的<b>采样参数</b>（A2）：温度、输出上限，以及给私有扩展字段留的透传口。
 *
 * <p><b>为什么用可空包装类型而不是 {@code double} + 哨兵值</b>：{@code temperature=0} 与"没设温度"是两件事——前者是"要可复现的判决"
 * （simos 的判决场景就靠它），后者是"让供应商用自己的默认值"。用 0 当哨兵会把两者混成一个，正是本项目最忌讳的"安静地说错"。 组件本身就是 {@code null} 可空的，读
 * {@link #temperature()} 前先判空即可（或直接用 {@link #isEmpty()} 判"整组没设"）。
 *
 * <p><b>{@code extraBody} 的边界</b>：它是给 Ollama/vLLM/网关<b>私有字段</b>用的透传口（{@code top_k}、{@code
 * repeat_penalty}、{@code thinking} 等），<b>不是</b>"绕过本类字段"的后门。协议关键键一律在构造期<b>响亮拒绝</b>（见 {@link
 * #RESERVED_KEYS}）：静默接受会更糟——用户以为"我在 extraBody 里改了 model"，实际发出去的还是路由里的那个，两处真相不一致。
 *
 * <p><b>校验只管形态不管理念</b>：温度只要求"有限且非负"、{@code maxTokens} 只要求"为正"。不设 {@code 0..2} 这类上界——各供应商的
 * 合理区间不同（有的允许 &gt;2），本库没有权威可依，替用户设一个假权威比不设更坏（供应商会用自己的校验响亮拒绝）。
 */
public record Sampling(Double temperature, Integer maxTokens, Map<String, Object> extraBody) {

  /**
   * 不得经 {@code extraBody} 透传的键——它们要么由本库按协议写死，要么已有专门字段。
   *
   * <p>{@code temperature}/{@code max_tokens} 也在列：它们有专门的强类型字段（{@link #temperature()}/{@link
   * #maxTokens()}），两条通路会让"到底哪个生效"取决于拼接顺序。
   */
  public static final Set<String> RESERVED_KEYS =
      Set.of("model", "messages", "stream", "stream_options", "tools", "temperature", "max_tokens");

  /** 什么都不设：供应商全用自己的默认值（老调用点的行为逐字不变）。 */
  public static final Sampling DEFAULT = new Sampling(null, null, Map.of());

  public Sampling {
    if (temperature != null && (!Double.isFinite(temperature) || temperature < 0)) {
      throw new IllegalArgumentException("temperature 必须是非负有限数（未设请传 null）: " + temperature);
    }
    if (maxTokens != null && maxTokens <= 0) {
      throw new IllegalArgumentException("maxTokens 必须为正（未设请传 null）: " + maxTokens);
    }
    extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
    for (String key : extraBody.keySet()) {
      if (RESERVED_KEYS.contains(key)) {
        throw new IllegalArgumentException(
            "extraBody 不得包含 " + key + "：它是协议关键键（已由本库按协议写死，或已有专门字段），经 extraBody 透传会与真正发出去的内容不一致");
      }
    }
  }

  /** 只设温度（判决场景最常见的诉求：要可复现）。 */
  public static Sampling atTemperature(double temperature) {
    return new Sampling(temperature, null, Map.of());
  }

  /** 只设输出上限（约束推理模型的花费）。 */
  public static Sampling cappedAt(int maxTokens) {
    return new Sampling(null, maxTokens, Map.of());
  }

  /** 只透传扩展字段。 */
  public static Sampling withExtraBody(Map<String, Object> extraBody) {
    return new Sampling(null, null, extraBody);
  }

  /** 三样都没设（等价于 {@link #DEFAULT}，按值比较）。 */
  public boolean isEmpty() {
    return temperature == null && maxTokens == null && extraBody.isEmpty();
  }

  @Override
  public String toString() {
    // 只打印 extraBody 的键、不打印值：值可能很长（用户填的私有字段），而排查"哪个键透传了"只需要键
    return "Sampling[temperature="
        + temperature
        + ", maxTokens="
        + maxTokens
        + ", extraBody.keys="
        + new TreeSet<>(extraBody.keySet())
        + "]";
  }
}
