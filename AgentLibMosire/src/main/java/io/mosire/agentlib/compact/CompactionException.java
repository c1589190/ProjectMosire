package io.mosire.agentlib.compact;

import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmResponse;
import java.util.Optional;

/**
 * 默认上下文压缩的严格失败。
 *
 * <p>摘要调用失败、摘要为空、摘要请求预算不足，或压缩后仍达到触发线，都抛本异常；调用方不得静默退回未压缩请求。
 *
 * <p>{@link Kind#INTERNAL} 是刻意选择：压缩失败是本地上下文治理失败，不应被当成供应商可重试/可降级的普通调用错误。若摘要调用已经发生，
 * 本异常携带响应对象与实际耗时，宿主仍可在错误事件中记录这次成本；消息只含结构性原因，不含摘要、提示词或密钥原文。
 */
public final class CompactionException extends LlmException {

  private static final long serialVersionUID = 1L;

  /** 摘要响应只用于本次运行的宿主记账，不应成为异常序列化契约的一部分；因此显式 transient。 */
  private final transient Optional<LlmResponse> summarizerResponse;

  private final long latencyMs;

  /** 没有摘要响应可携带的严格失败。 */
  public CompactionException(String message) {
    this(message, null, null, 0L);
  }

  /** 没有摘要响应可携带、但需要保留原因的严格失败。 */
  public CompactionException(String message, Throwable cause) {
    this(message, cause, null, 0L);
  }

  /** 摘要调用已产生响应后发生的严格失败（如正文为空或后校验未通过）。 */
  public CompactionException(String message, LlmResponse summarizerResponse, long latencyMs) {
    this(message, null, summarizerResponse, latencyMs);
  }

  /** 完整构造：同时保留底层原因、已产生的摘要响应与耗时。 */
  public CompactionException(
      String message, Throwable cause, LlmResponse summarizerResponse, long latencyMs) {
    super(message, Kind.INTERNAL, cause);
    this.summarizerResponse = Optional.ofNullable(summarizerResponse);
    this.latencyMs = Math.max(0L, latencyMs);
  }

  /** 摘要调用已产生的响应（若有），供宿主记账。 */
  public Optional<LlmResponse> summarizerResponse() {
    return summarizerResponse;
  }

  /** 摘要调用墙钟耗时毫秒（没有发起摘要调用时为 0）。 */
  public long latencyMs() {
    return latencyMs;
  }
}
