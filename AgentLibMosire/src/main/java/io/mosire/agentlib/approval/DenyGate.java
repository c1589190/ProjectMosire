package io.mosire.agentlib.approval;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 配置黑名单闸：命中即 {@code DENY}（快判，<b>不碰通道、不产生 {@code approval.requested}</b>）。
 *
 * <p><b>为什么黑名单闸必须排在会话级放行闸之前</b>：黑名单是宿主的配置（人对"机器能干什么"的静态约束）， 会话级放行是一时一事的（可能已经过期、可能属于别的任务）。让一条陈旧的
 * session 放行压过配置黑名单， 等于"上次同意过"可以洗白"这次配了不许"。链顺序即优先级——本包在 {@code ApprovalGate} 的类注释里写死这条。
 *
 * <p>判定内容是宿主给的 {@link Predicate}（B2 从配置装载，C 包接 bash 时与硬拒清单同源）；
 * 本类<b>不</b>内置任何清单——内置清单属于分类器（工具侧），不在这里。
 */
public final class DenyGate implements ApprovalGate {

  /** 闸名（决议事件里的 {@code by}）。 */
  public static final String NAME = "DenyGate";

  private final String name;
  private final Predicate<ApprovalRequest> blacklist;

  public DenyGate(Predicate<ApprovalRequest> blacklist) {
    this(NAME, blacklist);
  }

  public DenyGate(String name, Predicate<ApprovalRequest> blacklist) {
    this.name = Objects.requireNonNull(name, "name");
    this.blacklist = Objects.requireNonNull(blacklist, "blacklist");
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Optional<ApprovalDecision> decide(ApprovalRequest req) {
    return blacklist.test(req) ? Optional.of(ApprovalDecision.DENY) : Optional.empty();
  }
}
