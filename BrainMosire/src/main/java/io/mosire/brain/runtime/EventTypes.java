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
   * {"tier":"micro|summary|snapshot","droppedMessages":..,"keptMessages":..,"baselineMessages":..,"summaryTokens":..}}——{@code
   * droppedMessages} 是本次被移出请求的会话消息数（档 1 另有占位消息在会话里披露同一数字），{@code summaryTokens} 为 {@code -1}
   * 表示本次没有摘要（档 1）。
   *
   * <p><b>条数口径（恒等式）</b>：{@code keptMessages + droppedMessages == baselineMessages}，三档都成立。{@code
   * baselineMessages} 是本次压缩的<b>作用域原长</b>——档 1/2 = 压缩前的工作集长度；档 3 = 压缩点将要隐藏的全部内容
   * （工作集曾丢失内容时为存储的权威全量视图，见 {@code AgentPipeline#snapshotBaseline}）。{@code keptMessages}
   * 只数<b>逐字保留的原消息</b>：档 1 的占位消息与档 3 的摘要消息都<b>不</b>计入（它们是压缩产物，不是原消息——把它们算进去会让 "保留条数"与工作集长度混为一谈，档 1
   * 曾因此报 9+10=19 而作用域只有 18 条）。
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

  /**
   * 请求了审批（S4 审批内核；AgentLib 侧常量见 {@code io.mosire.agentlib.approval.ApprovalEventTypes}）。 payload:
   * {@code {"id","tool","classKey","digest"}}——<b>不含完整命令</b>（D23/D24）。
   *
   * <p><b>为什么两层各有一个常量</b>：AgentLib 不依赖 Brain（模块边界），Brain 也不反过来依赖 AgentLib 的
   * 审批包来写事件名；两层的<b>字符串值必须逐字一致</b>——由 {@code EventTypesApprovalValueTest} 钉住 （改任何一侧的值 ⇒ 该用例转红）。
   */
  public static final String APPROVAL_REQUESTED = "approval.requested";

  /**
   * 审批有了终局决定。payload: {@code {"id","tool","classKey","digest","decision","scope","by","latencyMs"}}
   * ——{@code scope} ∈ {@code once|session|none}，{@code by} = 决议来源（通道名 / 闸名 / timeout / no-channel /
   * error）。
   */
  public static final String APPROVAL_DECIDED = "approval.decided";

  /**
   * 插件生命周期（插件系统接线，2026-09-14；词汇约定见 AgentLib {@code PluginListener} 的 javadoc——事件字符串
   * 只在本表与宿主装配处出现）。payload: {@code {"pluginId","version","state"}}——{@code state} ∈ {@code
   * STARTED|STOPPED|FAILED}；描述符阶段失败的 {@code pluginId} 退化为 JAR 文件名、{@code version} 为空串 （标识口径见 {@code
   * PluginListener#onStateChanged}）。
   *
   * <p>装配点在 {@code App.wirePlugins}（宿主把 {@code PluginListener} 回调落库）；AG-UI 翻译层对未映射类型 已是"记 debug
   * 并忽略"，不新增映射（设计 D6）。
   */
  public static final String PLUGIN_LIFECYCLE = "plugin.lifecycle";

  private EventTypes() {
    throw new AssertionError("No instances");
  }
}
