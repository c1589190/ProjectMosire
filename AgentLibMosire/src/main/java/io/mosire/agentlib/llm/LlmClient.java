package io.mosire.agentlib.llm;

/**
 * LLM 模型连接接口——agentlib 对外暴露的"模型最小契约"。
 *
 * <p>实现约定：{@link #chat} 必须是纯同步、一次请求一次返回的调用；流式（P0 之前）由其他 接口承担/暂缓。Brain 的主循环只依赖本接口（离线测试注入 {@link
 * FakeLlmClient}）。
 */
@FunctionalInterface
public interface LlmClient {

  /** 发起一次补全；实现应保证耗时且失败抛 {@link LlmException}，不吞错、不重试（策略在调用方）。 */
  LlmResponse chat(LlmRequest request) throws LlmException;
}
