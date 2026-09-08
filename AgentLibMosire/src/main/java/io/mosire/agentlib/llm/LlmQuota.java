package io.mosire.agentlib.llm;

/**
 * token 配额账本（线程安全）。
 *
 * <p>用法：调用前查 {@link #record} 累计，超出即抛 {@link QuotaExceededException}； 调用方（Brain）捕获并渲染成"终止 +
 * 事件"而不是把异常漏给工具/网关。
 */
public final class LlmQuota {

  private final long maxTokens;
  private long usedTokens;

  public LlmQuota(long maxTokens) {
    if (maxTokens <= 0) {
      throw new IllegalArgumentException("maxTokens 必须为正: " + maxTokens);
    }
    this.maxTokens = maxTokens;
  }

  /** 记账一次调用；超过上限抛 {@link QuotaExceededException}（先记后抛，金额仍算数）。 */
  public synchronized void record(long inputTokens, long outputTokens) {
    if (inputTokens < 0) {
      inputTokens = 0;
    }
    if (outputTokens < 0) {
      outputTokens = 0;
    }
    usedTokens = Math.addExact(usedTokens, Math.addExact(inputTokens, outputTokens));
    if (usedTokens > maxTokens) {
      throw new QuotaExceededException("token 配额超限: 已用 " + usedTokens + " / 上限 " + maxTokens);
    }
  }

  public synchronized long usedTokens() {
    return usedTokens;
  }

  public long maxTokens() {
    return maxTokens;
  }
}
