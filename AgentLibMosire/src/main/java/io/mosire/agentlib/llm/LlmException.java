package io.mosire.agentlib.llm;

/** LLM 调用失败（网络/协议/认证/限额等）。 */
public class LlmException extends RuntimeException {

  public LlmException(String message) {
    super(message);
  }

  public LlmException(String message, Throwable cause) {
    super(message, cause);
  }
}
