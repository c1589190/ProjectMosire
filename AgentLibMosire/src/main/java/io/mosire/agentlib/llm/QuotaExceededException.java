package io.mosire.agentlib.llm;

/** 配额超限——Brain 主循环捕获后按"防跑飞硬顶"语义终止回合（计划 D10 / §3.3）。 */
public class QuotaExceededException extends LlmException {

  public QuotaExceededException(String message) {
    super(message);
  }
}
