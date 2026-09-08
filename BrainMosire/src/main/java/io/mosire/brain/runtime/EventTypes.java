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
