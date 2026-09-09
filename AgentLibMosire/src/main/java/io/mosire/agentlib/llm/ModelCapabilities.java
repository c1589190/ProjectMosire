package io.mosire.agentlib.llm;

/**
 * 模型能力描述：供应商注册时声明、调用方按能力选路/降级。
 *
 * <p>静态工厂 {@link #defaults()} 保守全关（一切能力视为不存在）； {@code maxContext}/{@code maxOutput} 为 0
 * 表示未知——只影响"能否"判断， 不作为截断或预算决策的依据。能力缺失宁可保守拒绝，也不要乐观假设导致运行期失败。
 */
public record ModelCapabilities(
    boolean toolCalling,
    boolean parallelToolCalls,
    boolean reasoning,
    boolean promptCaching,
    int maxContext,
    int maxOutput) {

  /** 保守默认：能力全关、上下文/输出上限未知（0）。 */
  public static ModelCapabilities defaults() {
    return new ModelCapabilities(false, false, false, false, 0, 0);
  }
}
