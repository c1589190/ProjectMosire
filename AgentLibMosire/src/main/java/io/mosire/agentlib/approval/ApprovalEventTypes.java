package io.mosire.agentlib.approval;

/**
 * 审批事件类型常量（S4 内核）。
 *
 * <p><b>两层各有常量是有意的</b>：AgentLib 不依赖 Brain，Brain 不反向被依赖，所以 Brain 的 {@code EventTypes} 里另有一组 {@code
 * APPROVAL_REQUESTED}/{@code APPROVAL_DECIDED}。<b>字符串值必须逐字一致</b>—— 别写成两套（别名用例 {@code
 * EventTypesApprovalValueTest} 钉住这一点）。
 *
 * <p>payload schema（生产端固化，消费端按类型语义解释）：
 *
 * <ul>
 *   <li>{@link #REQUESTED}：{@code {"id","tool","classKey","digest"}}——<b>不含完整命令</b>、不含 {@code
 *       summary}、不含 {@code args}；
 *   <li>{@link #DECIDED}：{@code
 *       {"id","tool","classKey","digest","decision","scope","by","latencyMs"}}， {@code scope} ∈
 *       {@code once|session|none}，{@code by} = 决议来源（通道名 / 闸名 / {@code timeout} / {@code no-channel}
 *       / {@code error}）。
 * </ul>
 */
public final class ApprovalEventTypes {

  /** 请求了审批（真的去问人了才发）。 */
  public static final String REQUESTED = "approval.requested";

  /** 审批有了终局决定（含快判链与 fail-closed 的那些路径）。 */
  public static final String DECIDED = "approval.decided";

  private ApprovalEventTypes() {
    throw new AssertionError("No instances");
  }
}
