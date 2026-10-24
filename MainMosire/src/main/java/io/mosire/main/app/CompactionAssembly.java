package io.mosire.main.app;

import io.mosire.agentlib.compact.CompactionPolicy;
import io.mosire.agentlib.compact.ContextCompactor;
import io.mosire.agentlib.compact.DefaultContextCompactor;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmRouteLoader;
import io.mosire.agentlib.llm.ModelCapabilities;
import java.util.Objects;

/**
 * Main 与 Subagent 共用的默认上下文压缩装配入口。
 *
 * <p>生产真模型路径必须先读到 route 的 {@link ModelCapabilities}，再由本类把严格能力校验与默认策略构造收在同一处： {@code
 * capabilities.maxContext}/{@code capabilities.maxOutput} 任一缺失或非正都会变成带 {@link
 * LlmRouteLoader#E_LLM_CONFIG_MISSING} 的 {@link ConfigException}，消息点名具体配置键。这里不猜容量、不自动关闭压缩， 避免主
 * Agent 与子 Agent 各写一份校验而漂移。
 *
 * <p>离线 fake/demo/setup 未配置路径不应调用本类；它们没有可信的模型容量，也不装 compactor。
 */
public final class CompactionAssembly {

  private CompactionAssembly() {}

  /**
   * 构造默认上下文压缩器。
   *
   * @param llm 真模型客户端；同时作为摘要调用的 summarizer
   * @param capabilities 路由声明的模型能力
   * @param agentMaxOutputTokens Agent 级最大输出 token；0 = 仅使用 {@code capabilities.maxOutput}
   * @return 默认压缩器
   * @throws ConfigException 能力缺失/非法，或派生压缩策略不合法（错误码复用 {@code E_LLM_CONFIG_MISSING}）
   */
  public static ContextCompactor defaultCompactor(
      LlmClient llm, ModelCapabilities capabilities, int agentMaxOutputTokens) {
    Objects.requireNonNull(llm, "llm");
    if (capabilities == null) {
      throw new ConfigException(
          LlmRouteLoader.E_LLM_CONFIG_MISSING,
          "默认上下文压缩装配失败：缺少模型能力，必须配置 capabilities.maxContext 与 capabilities.maxOutput");
    }
    try {
      CompactionPolicy policy =
          DefaultContextCompactor.defaultPolicy(capabilities, agentMaxOutputTokens);
      return new DefaultContextCompactor(llm, policy);
    } catch (IllegalArgumentException invalidPolicy) {
      // 不包成新的宽泛异常：装配层需要稳定错误码，同时消息保留 defaultPolicy 对具体键（maxContext/maxOutput）的点名。
      throw new ConfigException(
          LlmRouteLoader.E_LLM_CONFIG_MISSING,
          "默认上下文压缩装配失败：" + invalidPolicy.getMessage(),
          invalidPolicy);
    }
  }
}
