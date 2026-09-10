package io.mosire.brain.runtime;

/**
 * 事件词汇表第一批（计划 §3.6；命名对齐 AG-UI 习惯）。
 *
 * <p>各类型 payload 的 schema 由生产端固化（代码内文档 + M3 的 schema 校验），消费端按类型 语义解释，不要猜。
 */
public final class EventTypes {

  /** 生命周期。payload: {@code {"action":"started","agent":"main"}}。 */
  public static final String AGENT_LIFECYCLE = "agent.lifecycle";

  /** 一次 LLM 回合。payload: {@code {"turns":1,"input":"...","output":"..."}}。 */
  public static final String CONVERSATION_TURN = "conversation.turn";

  /**
   * 一次成功 LLM 调用的记账（D18：token 经济走 EventStore，不走 OTel）。payload: {@code
   * {"inputTokens":..,"outputTokens":..,"cacheReadTokens":..,"cacheWriteTokens":..,"model":"..",
   * "latencyMs":..,"turns":..}}；未知 token 记 {@code -1}。
   *
   * <p>{@code Compactor} 发起的摘要调用也落本类型（R7：压缩成本必须可见），额外带 {@code "phase":"compact"} 且<b>不带</b> {@code
   * turns}——它不是主循环的回合，是系统级维护调用。
   */
  public static final String LLM_CALL = "llm.call";

  /**
   * 一次会话压缩（{@code Compactor} 的产物；{@code 开发计划.md:101}）。payload: {@code
   * {"tier":"micro|summary|snapshot","droppedMessages":..,"keptMessages":..,"summaryTokens":..}}——{@code
   * droppedMessages} 是本次被移出请求的会话消息数（档 1 另有占位消息在会话里披露同一数字），{@code summaryTokens} 为 {@code -1}
   * 表示本次没有摘要（档 1）。
   *
   * <p>全零成本档（未触门 / 无安全切点）不落本事件；降级（摘要不可用→退回档 1）额外落 {@code decision} 带 {@code COMPACT_DEGRADED}。
   */
  public static final String CONVERSATION_COMPACT = "conversation.compact";

  /** 一次工具调用（请求）。payload: {@code {"tool":"...","args":{...}}}。 */
  public static final String TOOL_CALL = "tool.call";

  /** 一次工具调用（结果）。payload: {@code {"tool":"...","ok":true,"message":"..."}}。 */
  public static final String TOOL_RESULT = "tool.result";

  /** 中间决策（硬顶、权限等）。payload: {@code {"decision":"TURN_LIMIT",...}}。 */
  public static final String DECISION = "decision";

  /** 权限拒绝。payload: {@code {"tool":"...","reason":"..."}}。 */
  public static final String PERMISSION_DENIED = "permission.denied";

  private EventTypes() {
    throw new AssertionError("No instances");
  }
}
