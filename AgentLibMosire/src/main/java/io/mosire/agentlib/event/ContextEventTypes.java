package io.mosire.agentlib.event;

/**
 * 上下文注入相关事件词汇。
 *
 * <p>与 {@code ApprovalEventTypes} 同体例：AgentLib 持有标准字符串，Brain 的 {@code EventTypes} 以别名引用，不复制第二份字面量。
 */
public final class ContextEventTypes {

  /**
   * 一条上下文注入已<b>成功投递</b>到一次 LLM 请求之后发出。
   *
   * <p>payload：{@code
   * {"id","kind","priority","summary","revision"?}}——<b>不含完整正文</b>（正文可能较长；审计只需要“投递了什么类型、
   * 哪一条”）。投递失败/重试不落本事件。
   */
  public static final String CONTEXT_INJECTED = "context.injected";

  private ContextEventTypes() {
    throw new AssertionError("No instances");
  }
}
