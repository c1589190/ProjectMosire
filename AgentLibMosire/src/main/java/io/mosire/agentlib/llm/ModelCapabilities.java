package io.mosire.agentlib.llm;

/**
 * 模型能力描述：供应商注册时声明、调用方按能力选路/降级。
 *
 * <p>静态工厂 {@link #defaults()} 保守全关（一切能力视为不存在）； {@code maxContext}/{@code maxOutput} 为 0
 * 表示未知——只影响“能否”判断， 不作为截断或预算决策的依据。能力缺失宁可保守拒绝，也不要乐观假设导致运行期失败。
 *
 * <p><b>{@code echoReasoningContent}</b>（A6 修复版）：该路由的思考模式是否要求历史 assistant 消息回传 {@code
 * reasoning_content} 键（内容为空时回传 {@code ""}）。它既是发送侧的线级开关（权威值住 {@link
 * LlmTransport#echoReasoningContent()}），也在这里供配置页/选择面如实展示——两处由 {@link LlmRouteLoader} 从同一份 {@code
 * capabilities.echoReasoningContent} 配置读出，不让“能力页显示”和“发送行为”各拿一个来源。
 */
public record ModelCapabilities(
    boolean toolCalling,
    boolean parallelToolCalls,
    boolean reasoning,
    boolean promptCaching,
    boolean vision,
    int maxContext,
    int maxOutput,
    boolean echoReasoningContent) {

  /**
   * 七参兼容构造：{@code echoReasoningContent=false}，既有调用点（测试/宿主）逐字不动。
   *
   * <p>参数顺序就是 A6 修复版之前的组件顺序；新组件追加在末尾，避免“加一个能力位却让所有位置参数错位”。
   */
  public ModelCapabilities(
      boolean toolCalling,
      boolean parallelToolCalls,
      boolean reasoning,
      boolean promptCaching,
      boolean vision,
      int maxContext,
      int maxOutput) {
    this(
        toolCalling,
        parallelToolCalls,
        reasoning,
        promptCaching,
        vision,
        maxContext,
        maxOutput,
        false);
  }

  /** 保守默认：能力全关、上下文/输出上限未知（0）、不回传空思维链键。 */
  public static ModelCapabilities defaults() {
    return new ModelCapabilities(false, false, false, false, false, 0, 0, false);
  }
}
