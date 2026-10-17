package io.mosire.agentlib.decision;

import java.util.List;
import java.util.Objects;

/**
 * 一次提交的计划（U1，上游契约 {@code docs/upstream/agentlib-decision-contract.md} §3.4）： 提交前快照 + 被批 calls。
 *
 * <p><b>为什么把 {@code before} 一起交出去</b>：宿主需要把"效果、outcome、最终包状态"写进<b>同一事务</b>； 只给 calls
 * 的话，宿主要么在提交口里回调包层（重入/死锁风险），要么自己再查一次包（读到的可能已经 不是被批的那一版）。把提交前快照随 calls 一起冻结，事务里就有了唯一一份"这一批是基于什么裁的"。
 *
 * <p><b>{@code calls} 是被批子集，非空</b>：被驳条目永不进来（见 {@link ResolvedCall}）。被批子集为空的包在 纯核心直接终局 {@link
 * PackageState#REJECTED}，不产生本计划。
 *
 * @param before 提交前快照（{@code receipt == null}；宿主据此在同一事务里写效果）
 * @param calls 被批子集（非空；顺序 = 包内条目顺序；只含 {@link Decision#approves} 为真的条目）
 */
public record DecisionCommitPlan(DecisionPackageSnapshot before, List<ResolvedCall> calls) {

  public DecisionCommitPlan {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(calls, "calls");
    if (calls.isEmpty()) {
      throw new IllegalArgumentException("calls 不得为空：被批子集为空时包直接终局 REJECTED，不产生提交计划（零落盘）");
    }
    for (int i = 0; i < calls.size(); i++) {
      Objects.requireNonNull(calls.get(i), "calls[" + i + "]");
    }
    calls = List.copyOf(calls);
  }
}
