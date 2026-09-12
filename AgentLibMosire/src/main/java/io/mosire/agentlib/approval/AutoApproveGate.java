package io.mosire.agentlib.approval;

import java.util.Objects;
import java.util.Optional;

/**
 * 会话级放行的快判闸：本进程内 {@code (调用者, classKey)} 已被 {@code APPROVE_SESSION} ⇒ 直接放行，不再打扰人。
 *
 * <p><b>键是二元组，不是工具名、不是整条命令</b>（设计 §2.3 红线）：换 {@code classKey}（另一类命令/另一个工具档）
 * 仍要问，换调用者（另一份身份）也仍要问；不跨进程——进程退出即失效是正确的语义。
 *
 * <p>它读的是 {@link PendingApprovals} 这张进程内登记表，<b>不</b>读配置、<b>不</b>写权限集：审批 ≠ 授权。
 */
public final class AutoApproveGate implements ApprovalGate {

  /** 闸名（决议事件里的 {@code by}）。 */
  public static final String NAME = "AutoApproveGate";

  private final PendingApprovals pending;

  public AutoApproveGate(PendingApprovals pending) {
    this.pending = Objects.requireNonNull(pending, "pending");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Optional<ApprovalDecision> decide(ApprovalRequest req) {
    return pending.isSessionGranted(req.callerKey(), req.classKey())
        ? Optional.of(ApprovalDecision.APPROVE_SESSION)
        : Optional.empty();
  }
}
