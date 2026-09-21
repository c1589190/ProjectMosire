package io.mosire.agentlib.llm;

import java.util.List;
import java.util.Objects;

/**
 * 一次 LLM 补全请求：消息序列 + 可选工具目录 + 采样参数。
 *
 * <p><b>采样参数为什么是嵌套的 {@link Sampling} 而不是三个平铺组件</b>：它们是"这一次调用怎么采"的一组完整设定，与"发给谁、发什么"
 * （messages/tools）正交——嵌套后：①老调用点 {@code new LlmRequest(messages, tools)} 逐字不动；②将来若从路由配置读"每 provider
 * 的默认采样"，传的是同一个类型，不需要再长参数表。
 *
 * <p>{@code sampling} 允许传 null（按 {@link Sampling#DEFAULT} 补齐），与 {@code tools} 同一口径：都能省。
 */
public record LlmRequest(List<LlmMessage> messages, List<ToolDef> tools, Sampling sampling) {

  public LlmRequest {
    Objects.requireNonNull(messages, "messages");
    messages = List.copyOf(messages);
    tools = List.copyOf(tools == null ? List.of() : tools);
    sampling = sampling == null ? Sampling.DEFAULT : sampling;
  }

  /** 不指定采样参数（= 供应商默认值）。 */
  public LlmRequest(List<LlmMessage> messages, List<ToolDef> tools) {
    this(messages, tools, Sampling.DEFAULT);
  }

  public LlmRequest(List<LlmMessage> messages) {
    this(messages, List.of(), Sampling.DEFAULT);
  }

  /** 工具目录为空的便捷工厂。 */
  public static LlmRequest ofMessages(List<LlmMessage> messages) {
    return new LlmRequest(messages, List.of(), Sampling.DEFAULT);
  }

  /**
   * 换一份采样参数（其余组件不变）。
   *
   * <p>为什么给 {@code withX} 而不给"每参数一个重载构造"：调用点通常已经有一份 {@code LlmRequest}（来自上下文装配），只想改温度—— 重载构造会逼它把
   * messages/tools 再抄一遍。
   */
  public LlmRequest withSampling(Sampling newSampling) {
    return new LlmRequest(messages, tools, newSampling);
  }

  /** 便捷：带上温度（判决场景要可复现时最常用的一条）。 */
  public LlmRequest withTemperature(double temperature) {
    return withSampling(new Sampling(temperature, sampling.maxTokens(), sampling.extraBody()));
  }

  /** 便捷：带上输出上限（约束推理模型花费最常用的一条）。 */
  public LlmRequest withMaxTokens(int maxTokens) {
    return withSampling(new Sampling(sampling.temperature(), maxTokens, sampling.extraBody()));
  }
}
