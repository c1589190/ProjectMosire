package io.mosire.agentlib.approval;

/**
 * 人给出的终局决定（S4 审批内核）。
 *
 * <p><b>「审批 ≠ 授权」的第一落点</b>：本枚举只回答"这一次调用放不放行"——它<b>不写回</b> {@code
 * AgentPermissionSet}、<b>不被子体继承</b>、不改变任何 {@code ToolSpec}（设计 §2.3 红线）。{@link #APPROVE_SESSION}
 * 也不是"改了权限"：它只是往进程内的登记表里记一个 {@code (调用者, classKey)} 二元组， 进程退出即失效（跨重启继承一个"上次同意过"是错的）。
 *
 * <ul>
 *   <li>{@link #APPROVE_ONCE}：就这一次；
 *   <li>{@link #APPROVE_SESSION}：本进程内，同调用者 × 同 {@code classKey} 不再问（键<b>不是</b>工具名、 也<b>不是</b>整条命令）；
 *   <li>{@link #DENY}：拒（超时、无可用通道、通道故障也都归到这一档——fail-closed）。
 * </ul>
 */
public enum ApprovalDecision {
  APPROVE_ONCE,
  APPROVE_SESSION,
  DENY
}
