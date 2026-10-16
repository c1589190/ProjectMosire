package io.mosire.agentlib.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.AccessToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * 决策包状态机（RB1）的 §B 组判据（设计 §5.7 B-1…B-7）与"决策包 = 唯一决策单元"的三条不变量 （{@code 设计-决策包作为唯一决策单元.md} §3）。
 *
 * <p><b>为什么这些用例必须存在</b>：包层是"一次裁决 ⇒ 恰好一次提交 ⇒ 一份统一结果"的唯一收口点， 而它的三种错法在代码上都"看起来对"—— ①
 * 被驳条目也交给提交口（宿主漏看标志位就落盘）；② 被批子集拆成 N 次提交 （整批原子性降级成 N 次各自的成败）；③ 冲突之后重放又提交一次（退回的那批再落一次）。 三条各有用例钉着。
 *
 * <p><b>判别力（改坏哪里它会红）</b>：
 *
 * <ul>
 *   <li>把 {@code approvedCalls} 的 {@code approves} 过滤去掉（被驳条目也进 calls）⇒ {@link
 *       #rejectedItemsNeverReachTheCommitPort()} 与 {@link
 *       #anAllRejectedPackageNeverTouchesTheCommitPort()} 红；
 *   <li>把 {@code commit} 改成"每条各提一次" ⇒ {@link #approvedSubsetIsCommittedExactlyOnceAsOneBatch()} 红；
 *   <li>删掉 {@code commit} 里 {@code entry.receipt} 的重放早返回 ⇒ {@link
 *       #replayingTheSamePackageReturnsTheSameReceiptWithoutASecondCommit()} 红；
 *   <li>给 {@link CommitReceipt.Status} 补一个 {@code PARTIAL_FAILURE} ⇒ {@link
 *       #theSingleResultHasNoSecondErrorCode()} 红；
 *   <li>把 {@code commit} 的 {@code calls.isEmpty()} 早返回删掉（空子集也调提交口）⇒ {@link
 *       #anAllRejectedPackageNeverTouchesTheCommitPort()} 红。
 * </ul>
 */
class DecisionPackagesTest {

  private static final long DEADLINE = System.currentTimeMillis() + 60_000L;

  /** 记下每一次提交：{@code batches} 的长度就是"提交口被调了几次"（B-1 的判别点）。 */
  private static final class RecordingPort implements DecisionCommitPort {

    private final List<String> packageIds = new ArrayList<>();
    private final List<List<ResolvedCall>> batches = new ArrayList<>();
    private final BiFunction<String, List<ResolvedCall>, CommitReceipt> reply;

    RecordingPort(BiFunction<String, List<ResolvedCall>, CommitReceipt> reply) {
      this.reply = reply;
    }

    RecordingPort(CommitReceipt fixed) {
      this((packageId, calls) -> fixed);
    }

    @Override
    public CommitReceipt commit(String packageId, List<ResolvedCall> calls) {
      packageIds.add(packageId);
      batches.add(List.copyOf(calls));
      return reply.apply(packageId, calls);
    }
  }

  /** 全落定的回执（逐条给全，逐条理由未说明）。 */
  private static CommitReceipt committed(String revisionRef, int count) {
    List<CommitReceipt.ItemOutcome> outcomes = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      outcomes.add(CommitReceipt.ItemOutcome.COMMITTED);
    }
    return new CommitReceipt(CommitReceipt.Status.COMMITTED, revisionRef, outcomes, "");
  }

  private static DecisionPackage propose(DecisionPackages packages, String proposerKey, int count) {
    List<PackageItem> items = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      items.add(
          new PackageItem(
              i, "tool_" + i, Map.of("path", "f" + i + ".md"), "digest-" + i, "写第 " + i + " 个文件"));
    }
    return packages.propose(new ProposeRequest(proposerKey, AccessToken.SYSTEM, items, DEADLINE));
  }

  // ---------- B-1：一包 ⇒ 被批子集恰好一次提交 ----------

  @Test
  void approvedSubsetIsCommittedExactlyOnceAsOneBatch() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage p = propose(packages, "main", 5);
    RecordingPort port = new RecordingPort((id, calls) -> committed("batch:" + id, calls.size()));

    packages.decide(p.id(), new Decision(Set.of(0, 1, 2, 3), "case-human", ""));
    CommitReceipt receipt = packages.commit(p.id(), port);

    assertThat(port.batches).as("B-1：被批子集只提交一次（不是 4 次）").hasSize(1);
    assertThat(port.packageIds).as("B-6：提交口拿到的 id 与包 id 逐字相等").containsExactly(p.id());
    assertThat(port.batches.get(0))
        .as("被批子集按包内顺序，且只有被批的那些")
        .extracting(ResolvedCall::index)
        .containsExactly(0, 1, 2, 3);
    assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.COMMITTED);
    assertThat(receipt.revisionRef()).isEqualTo("batch:" + p.id());
    assertThat(receipt.outcomes())
        .as("B-7：批 4 驳 1 ⇒ 5 条各有去向（一份统一结果）")
        .containsExactly(
            CommitReceipt.ItemOutcome.COMMITTED,
            CommitReceipt.ItemOutcome.COMMITTED,
            CommitReceipt.ItemOutcome.COMMITTED,
            CommitReceipt.ItemOutcome.COMMITTED,
            CommitReceipt.ItemOutcome.REJECTED);
    assertThat(receipt.itemResults().get(4).reason())
        .as("被驳理由由包层填（宿主管不着被驳条目）")
        .isEqualTo(DecisionPackages.REJECTED_ITEM_REASON);
    assertThat(packages.find(p.id()).orElseThrow().state()).isEqualTo(PackageState.COMMITTED);
    assertThat(packages.find(p.id()).orElseThrow().decision().by())
        .as("裁决保留可回读")
        .isEqualTo("case-human");
  }

  // ---------- B-2：被驳零落盘 ----------

  @Test
  void rejectedItemsNeverReachTheCommitPort() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage p = propose(packages, "main", 3);
    RecordingPort port = new RecordingPort((id, calls) -> committed("batch:" + id, calls.size()));

    packages.decide(p.id(), new Decision(Set.of(0, 2), "case-human", ""));
    CommitReceipt receipt = packages.commit(p.id(), port);

    assertThat(port.batches).hasSize(1);
    assertThat(port.batches.get(0))
        .as("B-2：被驳的第 1 条不进 calls（它没有对应的 ResolvedCall）")
        .extracting(ResolvedCall::index)
        .containsExactly(0, 2);
    assertThat(port.batches.get(0))
        .extracting(ResolvedCall::toolName)
        .as("被驳那一条的工具名也不该出现在提交口")
        .doesNotContain("tool_1");
    assertThat(receipt.outcomes())
        .containsExactly(
            CommitReceipt.ItemOutcome.COMMITTED,
            CommitReceipt.ItemOutcome.REJECTED,
            CommitReceipt.ItemOutcome.COMMITTED);
    assertThat(packages.find(p.id()).orElseThrow().state()).isEqualTo(PackageState.COMMITTED);
  }

  @Test
  void anAllRejectedPackageNeverTouchesTheCommitPort() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage p = propose(packages, "main", 2);
    RecordingPort port = new RecordingPort(committed("should-not-be-used", 2));

    packages.decide(p.id(), new Decision(Set.of(), "case-human", ""));
    CommitReceipt receipt = packages.commit(p.id(), port);

    assertThat(port.batches).as("被批子集为空 ⇒ 根本不调提交口（零落盘是结构性的）").isEmpty();
    assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.REJECTED);
    assertThat(receipt.revisionRef()).as("零落盘 ⇒ 没有可对账的引用").isNull();
    assertThat(receipt.outcomes())
        .containsExactly(CommitReceipt.ItemOutcome.REJECTED, CommitReceipt.ItemOutcome.REJECTED);
    assertThat(receipt.reason()).contains("被批子集为空");
    assertThat(packages.find(p.id()).orElseThrow().state()).isEqualTo(PackageState.REJECTED);
  }

  @Test
  void missingHostOutcomesAreDerivedFromTheStatusNotInvented() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage p = propose(packages, "main", 2);
    // 宿主只报了第 0 条（少报第 1 条）：被批子集是原子的 ⇒ COMMITTED 之下缺失项按 status 推导为 COMMITTED
    RecordingPort port =
        new RecordingPort(
            new CommitReceipt(
                CommitReceipt.Status.COMMITTED,
                "batch:" + p.id(),
                List.of(CommitReceipt.ItemOutcome.COMMITTED),
                ""));

    packages.decide(p.id(), new Decision(Set.of(0, 1), "case-human", ""));
    CommitReceipt receipt = packages.commit(p.id(), port);

    assertThat(receipt.outcomes())
        .as("B-2 的读法：被批子集全落 ⇒ 缺失项推导为 COMMITTED，不是靠猜")
        .containsExactly(CommitReceipt.ItemOutcome.COMMITTED, CommitReceipt.ItemOutcome.COMMITTED);
    assertThat(receipt.itemResults().get(1).reason()).as("包层不替宿主编理由（未说明理由 = null）").isNull();
  }

  @Test
  void extraHostOutcomesAreIgnoredAndEveryItemStillHasADestination() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage p = propose(packages, "main", 2);
    RecordingPort port =
        new RecordingPort(
            new CommitReceipt(
                CommitReceipt.Status.COMMITTED,
                "batch:" + p.id(),
                List.of(
                    CommitReceipt.ItemOutcome.COMMITTED,
                    CommitReceipt.ItemOutcome.COMMITTED,
                    CommitReceipt.ItemOutcome.COMMITTED),
                ""));

    packages.decide(p.id(), new Decision(Set.of(0, 1), "case-human", ""));
    CommitReceipt receipt = packages.commit(p.id(), port);

    assertThat(receipt.outcomes()).as("多报被忽略（包层只认包内条目）").hasSize(2);
    assertThat(packages.find(p.id()).orElseThrow().items()).hasSize(2);
  }

  // ---------- B-3 / B-4：冲突整批退回 + 重放幂等 ----------

  @Test
  void hostConflictRollsBackTheWholeApprovedSubset() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage p = propose(packages, "main", 2);
    RecordingPort port =
        new RecordingPort(
            new CommitReceipt(
                CommitReceipt.Status.CONFLICTED,
                null,
                List.of(
                    CommitReceipt.ItemOutcome.NOT_COMMITTED,
                    CommitReceipt.ItemOutcome.NOT_COMMITTED),
                "当前版本摘要：revision=7，被改动的键发生冲突"));

    packages.decide(p.id(), new Decision(Set.of(0, 1), "case-human", ""));
    CommitReceipt receipt = packages.commit(p.id(), port);

    assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.CONFLICTED);
    assertThat(receipt.outcomes())
        .as("B-3：被批子集整批退回（零落盘）")
        .containsExactly(
            CommitReceipt.ItemOutcome.NOT_COMMITTED, CommitReceipt.ItemOutcome.NOT_COMMITTED);
    assertThat(receipt.reason()).as("当前版本以摘要形式交回确认者").contains("revision=7");
    assertThat(packages.find(p.id()).orElseThrow().state()).isEqualTo(PackageState.CONFLICTED);
    assertThat(packages.find(p.id()).orElseThrow().decision())
        .as("冲突后裁决保留（可解释'退回来的是哪次裁决'）")
        .isNotNull();
  }

  @Test
  void replayingTheSamePackageReturnsTheSameReceiptWithoutASecondCommit() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage p = propose(packages, "main", 2);
    RecordingPort port = new RecordingPort((id, calls) -> committed("batch:" + id, calls.size()));

    packages.decide(p.id(), new Decision(Set.of(0, 1), "case-human", ""));
    CommitReceipt first = packages.commit(p.id(), port);
    CommitReceipt second = packages.commit(p.id(), port);

    assertThat(port.batches).as("B-4：同包重放不再调提交口").hasSize(1);
    assertThat(second).as("重放拿到同一份回执（值相等）").isEqualTo(first);
    assertThat(second.revisionRef()).isEqualTo(first.revisionRef());
  }

  @Test
  void commitBeforeAnyDecisionIsRefused() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage p = propose(packages, "main", 1);
    RecordingPort port = new RecordingPort(committed("nope", 1));

    assertThatThrownBy(() -> packages.commit(p.id(), port))
        .as("未裁决的包不许提交：没有裁决就没有执行")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_NOT_DECIDED);
    assertThat(port.batches).isEmpty();
  }

  @Test
  void aHostFailureLeavesThePackageUndecidedAndFabricatesNoReceipt() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage p = propose(packages, "main", 1);
    RecordingPort port =
        new RecordingPort(
            (id, calls) -> {
              throw new IllegalStateException("提交口崩了（可能已经写了才抛）");
            });

    packages.decide(p.id(), new Decision(Set.of(0), "case-human", ""));
    assertThatThrownBy(() -> packages.commit(p.id(), port))
        .as("提交口抛异常 ⇒ COMMIT_FAILED（不报成落定，也不编一份'未落定'回执）")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.COMMIT_FAILED);
    assertThat(packages.find(p.id()).orElseThrow().state())
        .as("包保持 DECIDED（不确定就不说落定）")
        .isEqualTo(PackageState.DECIDED);
  }

  // ---------- B-7 / 裁决唯一 ----------

  @Test
  void theSingleResultHasNoSecondErrorCode() {
    assertThat(CommitReceipt.Status.values())
        .as("B-7：只有三个结局，不存在 PARTIAL_FAILURE 这类'部分失败'第二码")
        .extracting(Enum::name)
        .containsExactly("COMMITTED", "REJECTED", "CONFLICTED");
  }

  @Test
  void aSecondDecisionCannotOverwriteTheFirst() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage p = propose(packages, "main", 2);

    packages.decide(p.id(), new Decision(Set.of(0), "first-human", ""));
    assertThatThrownBy(() -> packages.decide(p.id(), new Decision(Set.of(1), "second-human", "")))
        .as("裁决唯一：一次终局，重复裁决不是'改主意'的入口")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_NOT_PROPOSED);
    assertThat(packages.find(p.id()).orElseThrow().decision().approvedIndexes()).containsExactly(0);
  }

  @Test
  void approvedIndexesOutsideThePackageAreRefused() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage p = propose(packages, "main", 2);

    assertThatThrownBy(() -> packages.decide(p.id(), new Decision(Set.of(0, 7), "case-human", "")))
        .as("批了不存在的条目 ⇒ 整条裁决拒（不静默忽略，否则'你没批第 7 条'说不清）")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.INVALID_DECISION);
    assertThat(packages.find(p.id()).orElseThrow().state())
        .as("整条裁决被拒 ⇒ 包仍是待裁决态（没有部分生效）")
        .isEqualTo(PackageState.PROPOSED);
  }

  @Test
  void oneOpenPackagePerProposerIsEnforcedAndReleasedAfterTerminalState() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage first = propose(packages, "sub-1", 1);
    RecordingPort port = new RecordingPort((id, calls) -> committed("batch:" + id, calls.size()));

    assertThatThrownBy(() -> propose(packages, "sub-1", 1))
        .as("同一实例同一时刻最多一个未决包（裁决单元唯一）")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_ALREADY_OPEN);
    assertThat(packages.pending()).extracting(DecisionPackage::id).containsExactly(first.id());

    packages.decide(first.id(), new Decision(Set.of(0), "case-human", ""));
    packages.commit(first.id(), port);

    DecisionPackage second = propose(packages, "sub-1", 1);
    assertThat(second.id()).as("终局后未决名额释放，新包拿到新 id").isNotEqualTo(first.id());
    assertThat(packages.pending()).extracting(DecisionPackage::id).containsExactly(second.id());
  }

  // ---------- 超时（fail-closed） ----------

  @Test
  void aPackageWhoseDeadlineAlreadyPassedIsBornExpiredAndCanNeverBeDecidedOrCommitted() {
    DecisionPackages packages = new DecisionPackages();
    List<PackageItem> items = List.of(new PackageItem(0, "tool_0", Map.of(), "digest-0", "写文件"));
    DecisionPackage p =
        packages.propose(
            new ProposeRequest(
                "main", AccessToken.SYSTEM, items, System.currentTimeMillis() - 1_000L));
    RecordingPort port = new RecordingPort(committed("nope", 1));

    assertThat(p.state())
        .as("到点时刻已过 ⇒ 建出来就是 EXPIRED（不许挂在'待裁决'上骗人）")
        .isEqualTo(PackageState.EXPIRED);
    assertThat(packages.pending()).as("EXPIRED 不在待裁决列表里").isEmpty();
    assertThatThrownBy(() -> packages.decide(p.id(), new Decision(Set.of(0), "case-human", "")))
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_EXPIRED);
    assertThatThrownBy(() -> packages.commit(p.id(), port))
        .as("超时零落盘：连提交口都不许碰")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_EXPIRED);
    assertThat(port.batches).isEmpty();
    assertThat(packages.find(p.id())).as("过期不等于不存在（查得到，且状态如实）").isPresent();
  }

  @Test
  void namedExpiryVoidsAPendingPackageAndReleasesTheSlot() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage p = propose(packages, "sub-1", 1);

    DecisionPackage expired = packages.expireNow(p.id(), System.currentTimeMillis());

    assertThat(expired.state()).isEqualTo(PackageState.EXPIRED);
    assertThat(expired.decision()).as("作废不带裁决（零落盘）").isNull();
    assertThat(packages.pending()).isEmpty();
    assertThat(propose(packages, "sub-1", 1).id()).as("作废释放未决名额").isNotEqualTo(p.id());
  }

  // ---------- 形状守卫 ----------

  @Test
  void emptyOrDuplicateIndexedPackagesAreRefusedBeforeAnyIdIsMinted() {
    DecisionPackages packages = new DecisionPackages();

    assertThatThrownBy(
            () ->
                packages.propose(
                    new ProposeRequest("main", AccessToken.SYSTEM, List.of(), DEADLINE)))
        .as("空包不是'轻量包'，是调用方 bug")
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                packages.propose(
                    new ProposeRequest(
                        "main",
                        AccessToken.SYSTEM,
                        List.of(
                            new PackageItem(0, "a", Map.of(), "d", "s"),
                            new PackageItem(0, "b", Map.of(), "d", "s")),
                        DEADLINE)))
        .as("index 是条目在包内的身份，重复就意味着'批第 2 条'有歧义")
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(packages.pending()).isEmpty();
  }
}
