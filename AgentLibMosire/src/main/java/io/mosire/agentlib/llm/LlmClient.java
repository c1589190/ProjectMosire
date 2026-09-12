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

  /**
   * 本客户端实际在用的模型名（<b>观测用</b>：{@code agent.lifecycle} 事件据此记录"这个进程在跟谁说话"）。
   *
   * <p><b>实现约定：不许谎报</b>。说的必须是本客户端真会用的模型；说不出来就返回 {@code "unknown"}——"没声明模型"与"在用假模型"是两件事，
   * 混同会让"以为在用真模型 / 以为在用假模型"两个方向同时失去判据（D24 口径：宁可响亮地不确定，不可安静地说错）。
   */
  default String model() {
    return "unknown";
  }
}
