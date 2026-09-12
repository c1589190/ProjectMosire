package io.mosire.agentlib.approval;

import java.util.Optional;

/**
 * 非阻塞的<b>分流闸</b>（链式快判）。<b>只做"快判"</b>：给出终局决定，或 {@code empty} = 交给后面的人。
 *
 * <p>三种实现（随链顺序生效，{@code DenyGate} 在 {@code AutoApproveGate} 之前 = 配置的黑名单压过一条已过期的会话放行）：
 *
 * <ul>
 *   <li>{@link DenyGate}：配置的黑名单，命中直接 {@code DENY}；
 *   <li>{@link AutoApproveGate}：本进程内已 {@code APPROVE_SESSION} 的 {@code (调用者, classKey)} ⇒ {@code
 *       APPROVE_SESSION}；
 *   <li>{@link ConfirmGate}：永远 {@code empty} = 落到人（链的显式终点：让"没人接管"不可能是静默放行）。
 * </ul>
 *
 * <p><b>闸链不接触通道</b>：命中即终局，不产生 {@code approval.requested}（快拒不打扰人）； 只有整条链都交出 {@code empty}，编排器才会去问人。
 */
public interface ApprovalGate {

  /** 闸名（决议事件里的 {@code by} 取它——审计要能回答"谁判的"）。 */
  String name();

  /** 快判：终局决定，或 {@code empty} = 交给后面的人（不是放行）。 */
  Optional<ApprovalDecision> decide(ApprovalRequest req);
}
