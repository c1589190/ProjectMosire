package io.mosire.agentlib.decision;

import static io.mosire.agentlib.decision.CommitReceipt.ItemOutcome.COMMITTED;
import static io.mosire.agentlib.decision.CommitReceipt.ItemOutcome.NOT_COMMITTED;
import static io.mosire.agentlib.decision.CommitReceipt.ItemOutcome.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * U1 决策包纯转移核心的判据（{@code docs/upstream/agentlib-decision-contract.md} §5 / §8）。
 *
 * <p><b>为什么这些用例必须存在</b>：纯核心是宿主持久化的地基，四种错法都能"看起来对"—— ① 偷偷读墙钟（同一份快照两轮结果不同）； ② 内容冻结失效（裁到一半又
 * append，已裁的内容被换掉）；③ 版本防线缺失（看旧包、批新包）； ④ 提交计划把被驳条目塞进 calls（ 宿主漏看就落盘）。每条错法在下面都有会红的用例。
 *
 * <p><b>判别力（改坏哪里它会红）</b>：
 *
 * <ul>
 *   <li>把 {@link DecisionPackageTransitions#propose} 的 {@code createdAtEpochMs} 改成 {@code
 *       System.currentTimeMillis()} ⇒ {@link
 *       #pureTransitionsAreDeterministicAndNeverReadTheWallClock()} 红；
 *   <li>把 {@code requireEditable} 的 {@code verdicts.isEmpty()} 去掉 ⇒ {@link
 *       #firstItemVerdictFreezesAppendReplaceAndRemove()} 红；
 *   <li>把 {@code requireVersion} 删掉 ⇒ {@link #staleExpectedVersionIsRefusedWithZeroStateChange()}
 *       红；
 *   <li>把 {@link DecisionPackageTransitions#approvedCalls} 改成"全部条目" ⇒ {@link
 *       #prepareCommitCarriesOnlyTheApprovedSubsetAndAValidReceiptSettlesIt()} 与 {@link
 *       #prepareCommitOnPartialDecisionRejectsUnstatedItemsAndCommitsOnlyApprovedOnes()} 红；
 *   <li>把 {@code validateHostReceipt} 删掉 ⇒ {@link
 *       #illegalReceiptsCannotSettleThePackageAsCommitted()} 红。
 * </ul>
 */
class DecisionPackageTransitionsTest {

  private static final String PACKAGE_ID = "pkt-dm-1-360";
  private static final String PROPOSER = "sub-1";
  private static final long NOW = 1_000_000L;
  private static final long DEADLINE = NOW + 60_000L;

  private static final ObjectMapper JSON = new ObjectMapper();

  private static PackageItem item(int index) {
    return new PackageItem(
        index,
        "tool_" + index,
        Map.of("path", "f" + index + ".md"),
        "digest-" + index,
        "写第 " + index + " 个文件");
  }

  private static ProposeRequest request(PackageItem... items) {
    return new ProposeRequest(PROPOSER, AccessToken.SYSTEM, List.of(items), DEADLINE);
  }

  private static DecisionPackageSnapshot proposed(PackageItem... items) {
    return DecisionPackageTransitions.propose(PACKAGE_ID, request(items), NOW);
  }

  private static DecisionPackageSnapshot append(
      DecisionPackageSnapshot current, List<PackageItem> items, long expectedVersion) {
    return DecisionPackageTransitions.append(current, items, expectedVersion, NOW);
  }

  private static DecisionPackageSnapshot itemDecided(
      DecisionPackageSnapshot current,
      long expectedVersion,
      int index,
      boolean approve,
      String by,
      String note) {
    return DecisionPackageTransitions.itemDecided(
        current, expectedVersion, index, approve, by, note, NOW);
  }

  private static DecisionPackageSnapshot decide(
      DecisionPackageSnapshot current, long expectedVersion, Set<Integer> approved) {
    return DecisionPackageTransitions.decide(
        current, expectedVersion, new Decision(approved, "human", ""), NOW);
  }

  // ---------- 建包 / 时间注入 ----------

  @Test
  void proposeUsesHostIdInjectedNowAndStartsAtVersionOne() {
    DecisionPackageSnapshot first =
        DecisionPackageTransitions.propose(PACKAGE_ID, request(item(0)), NOW);

    assertThat(first.pkg().id()).isEqualTo(PACKAGE_ID);
    assertThat(first.pkg().state()).isEqualTo(PackageState.PROPOSED);
    assertThat(first.pkg().createdAtEpochMs()).as("建包时刻来自 nowEpochMs，不读系统时钟").isEqualTo(NOW);
    assertThat(first.pkg().deadlineEpochMs()).isEqualTo(DEADLINE);
    assertThat(first.contentVersion()).as("contentVersion 从 1 起").isEqualTo(1L);
    assertThat(first.verdicts()).isEmpty();
    assertThat(first.receipt()).as("还没提交 ⇒ 回执为空").isNull();
    assertThat(first.expiryReason()).isNull();

    assertThatThrownBy(() -> DecisionPackageTransitions.propose("  ", request(item(0)), NOW))
        .as("宿主 id 不得为空白")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DecisionPackageTransitions.propose(PACKAGE_ID, request(), NOW))
        .as("空包沿用既有口径：拒")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aProposalPastItsDeadlineIsBornExpiredAndCannotBeDecided() {
    DecisionPackageSnapshot bornExpired =
        DecisionPackageTransitions.propose(
            PACKAGE_ID,
            new ProposeRequest(PROPOSER, AccessToken.SYSTEM, List.of(item(0)), NOW - 1L),
            NOW);

    assertThat(bornExpired.pkg().state()).isEqualTo(PackageState.EXPIRED);
    assertThat(bornExpired.expiryReason()).isEqualTo(DecisionPackages.EXPIRED_NO_DECISION);
    assertThatThrownBy(() -> itemDecided(bornExpired, 1L, 0, true, "human", ""))
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_EXPIRED);
  }

  @Test
  void pureTransitionsAreDeterministicAndNeverReadTheWallClock() {
    long injectedNow = 12_345L;
    long injectedDeadline = 99_999L;
    ProposeRequest request =
        new ProposeRequest(PROPOSER, AccessToken.SYSTEM, List.of(item(0)), injectedDeadline);

    DecisionPackageSnapshot first =
        DecisionPackageTransitions.propose(PACKAGE_ID, request, injectedNow);
    DecisionPackageSnapshot second =
        DecisionPackageTransitions.propose(PACKAGE_ID, request, injectedNow);

    assertThat(first).as("相同输入 + 相同 nowEpochMs ⇒ 完全相同").isEqualTo(second);
    assertThat(first.pkg().createdAtEpochMs())
        .as("createdAt 逐字等于调用方注入的时刻（墙钟会远大于它）")
        .isEqualTo(injectedNow);
    assertThat(DecisionPackageTransitions.itemDecided(first, 1L, 0, true, "human", "", injectedNow))
        .isEqualTo(
            DecisionPackageTransitions.itemDecided(second, 1L, 0, true, "human", "", injectedNow));
  }

  // ---------- append / replace / remove ----------

  @Test
  void appendGrowthKeepsStableConsecutiveIndexesAndBumpsVersion() {
    DecisionPackageSnapshot current = proposed(item(0));
    for (int nextIndex = 1; nextIndex <= 3; nextIndex++) {
      current = append(current, List.of(item(nextIndex)), current.contentVersion());
    }

    assertThat(current.pkg().items())
        .extracting(PackageItem::index)
        .as("3 次 append 后 index = [0,1,2,3]（稳定、连续）")
        .containsExactly(0, 1, 2, 3);
    assertThat(current.contentVersion()).isEqualTo(4L);

    current = append(current, List.of(item(4)), current.contentVersion());
    assertThat(current.pkg().items()).hasSize(5);
    assertThat(current.contentVersion()).as("第 4 次 append 再 +1").isEqualTo(5L);
  }

  @Test
  void removeLeavesAHoleAndTheNextAppendTakesAGreaterIndex() {
    DecisionPackageSnapshot current = proposed(item(0), item(1), item(2));
    current = DecisionPackageTransitions.removeItem(current, 1, current.contentVersion(), NOW);

    assertThat(current.pkg().items())
        .extracting(PackageItem::index)
        .as("删除只留洞，不重排")
        .containsExactly(0, 2);
    assertThat(current.contentVersion()).isEqualTo(2L);

    DecisionPackageSnapshot withHole = append(current, List.of(item(3)), current.contentVersion());
    assertThat(withHole.pkg().items())
        .extracting(PackageItem::index)
        .as("后续 append 取大于当前最大值的 index")
        .containsExactly(0, 2, 3);
    assertThatThrownBy(() -> append(withHole, List.of(item(1)), withHole.contentVersion()))
        .as("remove 留下的洞不会被填")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.INVALID_ITEM_INDEX);
  }

  @Test
  void appendRequiresIndexesStrictlyGreaterThanTheCurrentMaximum() {
    DecisionPackageSnapshot current = proposed(item(0), item(2));

    assertThatThrownBy(() -> append(current, List.of(item(1)), 1L))
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.INVALID_ITEM_INDEX);
    assertThatThrownBy(() -> append(current, List.of(item(3), item(3)), 1L))
        .as("同一批里重复 index 也拒")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.INVALID_ITEM_INDEX);
    assertThatThrownBy(() -> append(current, List.of(item(4), item(3)), 1L))
        .as("批内必须严格递增")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.INVALID_ITEM_INDEX);

    DecisionPackageSnapshot appended = append(current, List.of(item(3), item(4)), 1L);
    assertThat(appended.pkg().items()).extracting(PackageItem::index).containsExactly(0, 2, 3, 4);
  }

  @Test
  void replaceItemKeepsTheSameIndexAndRefusesIdentityChanges() {
    DecisionPackageSnapshot current = proposed(item(0), item(1));
    PackageItem replacement =
        new PackageItem(1, "tool_new", Map.of("path", "new.md"), "digest-new", "新说明");

    DecisionPackageSnapshot replaced =
        DecisionPackageTransitions.replaceItem(
            current, 1, replacement, current.contentVersion(), NOW);

    assertThat(replaced.pkg().items()).extracting(PackageItem::index).containsExactly(0, 1);
    assertThat(replaced.pkg().items().get(1).toolName()).isEqualTo("tool_new");
    assertThat(replaced.contentVersion()).isEqualTo(2L);

    assertThatThrownBy(
            () ->
                DecisionPackageTransitions.replaceItem(
                    current, 1, item(2), current.contentVersion(), NOW))
        .as("替换不得改 index（稳定身份）")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.INVALID_ITEM_INDEX);
    assertThatThrownBy(
            () ->
                DecisionPackageTransitions.replaceItem(
                    current, 9, item(9), current.contentVersion(), NOW))
        .as("未知 index ⇒ 具名拒")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.INVALID_ITEM_INDEX);
  }

  @Test
  void removingTheLastItemIsRefusedAsAnEmptyPackage() {
    DecisionPackageSnapshot current = proposed(item(0));

    assertThatThrownBy(
            () -> DecisionPackageTransitions.removeItem(current, 0, current.contentVersion(), NOW))
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_EMPTY);
  }

  // ---------- 冻结 / 版本 ----------

  @Test
  void firstItemVerdictFreezesAppendReplaceAndRemove() {
    DecisionPackageSnapshot current = proposed(item(0), item(1));
    DecisionPackageSnapshot deciding =
        itemDecided(current, current.contentVersion(), 0, true, "alice", "第一条先批");

    assertThat(deciding.pkg().state()).isEqualTo(PackageState.DECIDING);
    assertThat(deciding.verdicts()).hasSize(1);
    assertThat(deciding.contentVersion()).isEqualTo(2L);

    assertThatThrownBy(() -> append(deciding, List.of(item(2)), deciding.contentVersion()))
        .as("首次表态后 append 冻结")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_CONTENT_FROZEN);
    assertThatThrownBy(
            () ->
                DecisionPackageTransitions.replaceItem(
                    deciding, 1, item(1), deciding.contentVersion(), NOW))
        .as("首次表态后 replace 冻结")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_CONTENT_FROZEN);
    assertThatThrownBy(
            () ->
                DecisionPackageTransitions.removeItem(deciding, 1, deciding.contentVersion(), NOW))
        .as("首次表态后 remove 冻结")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_CONTENT_FROZEN);

    assertThat(deciding.verdicts()).as("被拒的编辑不改原快照").hasSize(1);
  }

  @Test
  void staleExpectedVersionIsRefusedWithZeroStateChange() {
    DecisionPackageSnapshot current = proposed(item(0), item(1));

    assertThatThrownBy(() -> decide(current, 99L, Set.of(0)))
        .as("整包裁决必须带对版本")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_VERSION_STALE);
    assertThatThrownBy(() -> itemDecided(current, 99L, 0, true, "alice", ""))
        .as("逐条表态必须带对版本")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_VERSION_STALE);
    assertThatThrownBy(() -> append(current, List.of(item(2)), 99L))
        .as("append 同样走版本防线")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_VERSION_STALE);

    assertThat(current.pkg().state()).isEqualTo(PackageState.PROPOSED);
    assertThat(current.verdicts()).isEmpty();
    assertThat(current.contentVersion()).isEqualTo(1L);
  }

  @Test
  void decisionCannotReferenceUnknownIndexes() {
    DecisionPackageSnapshot current = proposed(item(0), item(1));

    assertThatThrownBy(() -> decide(current, 1L, Set.of(0, 7)))
        .as("批了不存在的条目 ⇒ 整条裁决拒，不静默忽略")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.INVALID_DECISION);
    assertThat(current.pkg().state()).isEqualTo(PackageState.PROPOSED);
  }

  @Test
  void itemVerdictsRefuseUnknownIndexesAndDuplicateVotes() {
    DecisionPackageSnapshot current = proposed(item(0), item(1));

    assertThatThrownBy(() -> itemDecided(current, 1L, 9, true, "alice", ""))
        .as("未知 index ⇒ 具名拒，不静默忽略")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.INVALID_ITEM_INDEX);

    DecisionPackageSnapshot deciding =
        itemDecided(current, current.contentVersion(), 0, true, "alice", "");
    assertThatThrownBy(() -> itemDecided(deciding, deciding.contentVersion(), 0, false, "bob", ""))
        .as("同一条只允许一次表态")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.ITEM_ALREADY_DECIDED);

    assertThatThrownBy(() -> decide(deciding, deciding.contentVersion(), Set.of(0)))
        .as("分段裁决开始后整包裁决不再可用")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PARTIAL_DECISION);
  }

  @Test
  void aVerdictWithoutAnAuthorIsRefused() {
    DecisionPackageSnapshot current = proposed(item(0), item(1));

    assertThatThrownBy(() -> itemDecided(current, 1L, 0, true, " ", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- 裁决累计 / 提交计划 ----------

  @Test
  void itemVerdictsAccumulateAndFinalizeWhenEveryItemIsVoted() {
    DecisionPackageSnapshot current = proposed(item(0), item(1));
    DecisionPackageSnapshot deciding =
        itemDecided(current, current.contentVersion(), 0, true, "alice", "ok");

    assertThat(deciding.pkg().state()).isEqualTo(PackageState.DECIDING);
    assertThat(deciding.pkg().decision()).as("整包裁决还没作出").isNull();
    assertThat(deciding.verdicts()).extracting(DecisionItemVerdict::index).containsExactly(0);

    DecisionPackageSnapshot decided =
        itemDecided(deciding, deciding.contentVersion(), 1, false, "bob", "no");

    assertThat(decided.pkg().state()).isEqualTo(PackageState.DECIDED);
    assertThat(decided.pkg().decision().approvedIndexes()).containsExactly(0);
    assertThat(decided.pkg().decision().by()).isEqualTo(DecisionPackages.DECIDED_BY_ITEM_VERDICTS);
    assertThat(decided.verdicts()).hasSize(2);

    DecisionCommitPlan plan = DecisionPackageTransitions.prepareCommit(decided, NOW);
    assertThat(plan.calls()).extracting(ResolvedCall::index).containsExactly(0);
    DecisionPackageSnapshot committed =
        DecisionPackageTransitions.completeCommit(
            plan,
            new CommitReceipt(CommitReceipt.Status.COMMITTED, "rev-i", List.of(COMMITTED), ""));
    assertThat(committed.receipt().outcomes()).containsExactly(COMMITTED, REJECTED);
  }

  @Test
  void prepareCommitCarriesOnlyTheApprovedSubsetAndAValidReceiptSettlesIt() {
    DecisionPackageSnapshot decided = decide(proposed(item(0), item(1), item(2)), 1L, Set.of(0, 2));
    assertThat(decided.pkg().state()).isEqualTo(PackageState.DECIDED);
    assertThat(decided.contentVersion()).isEqualTo(2L);

    DecisionCommitPlan plan = DecisionPackageTransitions.prepareCommit(decided, NOW);

    assertThat(plan.before()).isEqualTo(decided);
    assertThat(plan.calls())
        .extracting(ResolvedCall::index)
        .as("calls 只来自 approved indexes，顺序 = 包内条目顺序")
        .containsExactly(0, 2);
    assertThat(plan.calls()).extracting(ResolvedCall::toolName).containsExactly("tool_0", "tool_2");

    DecisionPackageSnapshot committed =
        DecisionPackageTransitions.completeCommit(
            plan,
            new CommitReceipt(
                CommitReceipt.Status.COMMITTED, "rev-1", List.of(COMMITTED, COMMITTED), "ok"));

    assertThat(committed.pkg().state()).isEqualTo(PackageState.COMMITTED);
    assertThat(committed.receipt().revisionRef()).isEqualTo("rev-1");
    assertThat(committed.receipt().outcomes())
        .as("整包统一结果：批 2 驳 1 ⇒ 3 条各有去向")
        .containsExactly(COMMITTED, REJECTED, COMMITTED);
    assertThat(committed.contentVersion())
        .as("提交不是内容/裁决变更，contentVersion 不变")
        .isEqualTo(decided.contentVersion());
  }

  @Test
  void prepareCommitOnPartialDecisionRejectsUnstatedItemsAndCommitsOnlyApprovedOnes() {
    DecisionPackageSnapshot current = proposed(item(0), item(1), item(2));
    current = itemDecided(current, current.contentVersion(), 0, true, "alice", "");

    DecisionCommitPlan plan = DecisionPackageTransitions.prepareCommit(current, NOW);

    assertThat(plan.before().pkg().state()).isEqualTo(PackageState.DECIDED);
    assertThat(plan.before().contentVersion())
        .as("DECIDING ⇒ 显式终结为 DECIDED 也是一次裁决变更")
        .isEqualTo(3L);
    assertThat(plan.before().pkg().decision().approvedIndexes()).containsExactly(0);
    assertThat(plan.before().pkg().decision().note())
        .isEqualTo(DecisionPackages.UNSTATED_ITEM_REASON);
    assertThat(plan.calls()).extracting(ResolvedCall::index).containsExactly(0);

    DecisionPackageSnapshot committed =
        DecisionPackageTransitions.completeCommit(
            plan,
            new CommitReceipt(CommitReceipt.Status.COMMITTED, "rev-p", List.of(COMMITTED), ""));
    assertThat(committed.receipt().outcomes())
        .as("未表态的按静默 = 拒绝，首条按批准")
        .containsExactly(COMMITTED, REJECTED, REJECTED);
  }

  @Test
  void prepareCommitWithNoApprovedVerdictIsRefusedWithoutAPlan() {
    DecisionPackageSnapshot deciding =
        itemDecided(proposed(item(0), item(1)), 1L, 0, false, "alice", "");

    assertThat(deciding.pkg().state()).isEqualTo(PackageState.DECIDING);
    assertThatThrownBy(() -> DecisionPackageTransitions.prepareCommit(deciding, NOW))
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.NO_ITEM_DECIDED);
    assertThat(deciding.pkg().state()).as("具名拒不改快照").isEqualTo(PackageState.DECIDING);
  }

  @Test
  void prepareCommitRequiresADecision() {
    DecisionPackageSnapshot current = proposed(item(0));

    assertThatThrownBy(() -> DecisionPackageTransitions.prepareCommit(current, NOW))
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_NOT_DECIDED);

    DecisionPackageSnapshot expired = DecisionPackageTransitions.expire(current, DEADLINE);
    assertThatThrownBy(() -> DecisionPackageTransitions.prepareCommit(expired, DEADLINE))
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_EXPIRED);
  }

  @Test
  void anEmptyApprovedSubsetBecomesTerminalRejectedWithoutACommitPlan() {
    DecisionPackageSnapshot rejected =
        DecisionPackageTransitions.decide(
            proposed(item(0), item(1)), 1L, new Decision(Set.of(), "human", "全驳"), NOW);

    assertThat(rejected.pkg().state()).isEqualTo(PackageState.REJECTED);
    assertThat(rejected.receipt()).isNotNull();
    assertThat(rejected.receipt().status()).isEqualTo(CommitReceipt.Status.REJECTED);
    assertThat(rejected.receipt().outcomes()).containsExactly(REJECTED, REJECTED);
    assertThat(rejected.receipt().revisionRef()).isNull();
    assertThat(rejected.contentVersion()).isEqualTo(2L);
    assertThatThrownBy(() -> DecisionPackageTransitions.prepareCommit(rejected, NOW))
        .as("终局 REJECTED 不再产生提交计划")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.PACKAGE_NOT_DECIDED);
  }

  // ---------- 非法回执 / 幂等 ----------

  @Test
  void illegalReceiptsCannotSettleThePackageAsCommitted() {
    DecisionPackageSnapshot decided = decide(proposed(item(0), item(1), item(2)), 1L, Set.of(0, 1));
    DecisionCommitPlan plan = DecisionPackageTransitions.prepareCommit(decided, NOW);

    assertThatThrownBy(
            () ->
                DecisionPackageTransitions.completeCommit(
                    plan,
                    new CommitReceipt(
                        CommitReceipt.Status.COMMITTED,
                        "rev-x",
                        List.of(COMMITTED, NOT_COMMITTED),
                        "")))
        .as("COMMITTED 却有一条未落定 ⇒ 自相矛盾")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.COMMIT_FAILED);

    assertThatCode(
            () ->
                DecisionPackageTransitions.completeCommit(
                    plan,
                    new CommitReceipt(
                        CommitReceipt.Status.COMMITTED, "rev-y", List.of(COMMITTED), "")))
        .as("少报按整批 status 推导（旧提交口契约允许），不是非法")
        .doesNotThrowAnyException();

    assertThatThrownBy(
            () ->
                DecisionPackageTransitions.completeCommit(
                    plan,
                    new CommitReceipt(
                        CommitReceipt.Status.COMMITTED,
                        "rev-z",
                        List.of(COMMITTED, COMMITTED, COMMITTED),
                        "")))
        .as("多报 outcome ⇒ 对不齐，非法")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.COMMIT_FAILED);

    assertThatThrownBy(
            () ->
                DecisionPackageTransitions.completeCommit(
                    plan,
                    new CommitReceipt(
                        CommitReceipt.Status.REJECTED, null, List.of(COMMITTED, COMMITTED), "x")))
        .as("零落盘结局却报 COMMITTED ⇒ 非法")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.COMMIT_FAILED);

    assertThatThrownBy(
            () ->
                DecisionPackageTransitions.completeCommit(
                    plan,
                    new CommitReceipt(
                        CommitReceipt.Status.CONFLICTED,
                        "rev-c",
                        List.of(NOT_COMMITTED, NOT_COMMITTED),
                        "x")))
        .as("冲突是零落盘，不得带回 revisionRef")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.COMMIT_FAILED);

    ResolvedCall unapprovedItem = new ResolvedCall(2, "tool_2", Map.of("path", "f2.md"));
    assertThatThrownBy(
            () ->
                DecisionPackageTransitions.completeCommit(
                    new DecisionCommitPlan(
                        plan.before(),
                        List.of(plan.calls().get(0), plan.calls().get(1), unapprovedItem)),
                    new CommitReceipt(
                        CommitReceipt.Status.COMMITTED,
                        "rev-u",
                        List.of(COMMITTED, COMMITTED, COMMITTED),
                        "")))
        .as("计划里的 calls 必须逐条等于被批子集（被驳条目永不进 calls）")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.COMMIT_FAILED);

    assertThat(decided.pkg().state()).as("非法回执不让快照进入 COMMITTED").isEqualTo(PackageState.DECIDED);
    assertThat(decided.receipt()).isNull();
  }

  @Test
  void completingWithTheSameReceiptIsIdempotentAndADifferentReceiptIsRefused() {
    DecisionPackageSnapshot decided = decide(proposed(item(0), item(1)), 1L, Set.of(0, 1));
    DecisionCommitPlan plan = DecisionPackageTransitions.prepareCommit(decided, NOW);
    CommitReceipt receipt =
        new CommitReceipt(
            CommitReceipt.Status.COMMITTED, "rev-idem", List.of(COMMITTED, COMMITTED), "ok");

    DecisionPackageSnapshot committed = DecisionPackageTransitions.completeCommit(plan, receipt);
    assertThat(DecisionPackageTransitions.completeCommit(plan, receipt))
        .as("同一计划 + 同一回执重放 ⇒ 同一份落定快照")
        .isEqualTo(committed);

    DecisionCommitPlan replay = new DecisionCommitPlan(committed, plan.calls());
    assertThat(DecisionPackageTransitions.completeCommit(replay, receipt)).isEqualTo(committed);
    assertThatThrownBy(
            () ->
                DecisionPackageTransitions.completeCommit(
                    replay,
                    new CommitReceipt(
                        CommitReceipt.Status.COMMITTED,
                        "rev-other",
                        List.of(COMMITTED, COMMITTED),
                        "")))
        .as("同一包不允许覆盖成第二份回执")
        .isInstanceOf(DecisionPackages.RejectionException.class)
        .hasFieldOrPropertyWithValue("code", DecisionPackages.COMMIT_FAILED);
  }

  // ---------- 到点 ----------

  @Test
  void expireOnlyMovesUnfinishedPackagesAtTheDeadlineAndRecordsWhy() {
    DecisionPackageSnapshot proposed = proposed(item(0), item(1));
    assertThat(DecisionPackageTransitions.expire(proposed, DEADLINE - 1L))
        .as("未到点 ⇒ 原样返回")
        .isSameAs(proposed);

    DecisionPackageSnapshot expired = DecisionPackageTransitions.expire(proposed, DEADLINE);
    assertThat(expired.pkg().state()).isEqualTo(PackageState.EXPIRED);
    assertThat(expired.expiryReason()).isEqualTo(DecisionPackages.EXPIRED_NO_DECISION);
    assertThat(expired.pkg().decision()).isNull();
    assertThat(expired.contentVersion()).as("到点不是内容/裁决变更").isEqualTo(1L);

    DecisionPackageSnapshot decided = decide(proposed, 1L, Set.of(0));
    DecisionPackageSnapshot expiredDecided = DecisionPackageTransitions.expire(decided, DEADLINE);
    assertThat(expiredDecided.pkg().state()).isEqualTo(PackageState.EXPIRED);
    assertThat(expiredDecided.expiryReason())
        .isEqualTo(DecisionPackages.EXPIRED_DECIDED_NOT_EXECUTED);
    assertThat(expiredDecided.pkg().decision())
        .as("已作出的裁决保留可查（EXPIRED_DECIDED_NOT_EXECUTED 的解释面）")
        .isNotNull();

    DecisionPackageSnapshot deciding = itemDecided(proposed, 1L, 0, true, "alice", "");
    DecisionPackageSnapshot expiredDeciding = DecisionPackageTransitions.expire(deciding, DEADLINE);
    assertThat(expiredDeciding.pkg().state()).isEqualTo(PackageState.EXPIRED);
    assertThat(expiredDeciding.pkg().decision().approvedIndexes()).containsExactly(0);
    assertThat(expiredDeciding.pkg().decision().note())
        .isEqualTo(DecisionPackages.UNSTATED_ITEM_REASON);
    assertThat(expiredDeciding.expiryReason())
        .isEqualTo(DecisionPackages.EXPIRED_DECIDED_NOT_EXECUTED);

    DecisionCommitPlan plan = DecisionPackageTransitions.prepareCommit(decided, NOW);
    DecisionPackageSnapshot committed =
        DecisionPackageTransitions.completeCommit(
            plan,
            new CommitReceipt(CommitReceipt.Status.COMMITTED, "rev-e", List.of(COMMITTED), ""));
    assertThat(DecisionPackageTransitions.expire(committed, Long.MAX_VALUE))
        .as("已终局（COMMITTED）不因时间改变")
        .isSameAs(committed);
  }

  // ---------- 快照恢复 ----------

  @Test
  void aSnapshotSurvivesAJsonRoundTripAndTheStateMachineContinues() throws Exception {
    DecisionPackageSnapshot current = proposed(item(0), item(1), item(2));
    current = append(current, List.of(item(3)), current.contentVersion());
    current = itemDecided(current, current.contentVersion(), 0, true, "alice", "");

    String json = JSON.writeValueAsString(current);
    DecisionPackageSnapshot restored = JSON.readValue(json, DecisionPackageSnapshot.class);

    assertThat(restored).as("JSON 往返后快照逐字段相等").isEqualTo(current);
    assertThat(restored.contentVersion()).isEqualTo(current.contentVersion());

    DecisionPackageSnapshot deciding =
        itemDecided(restored, restored.contentVersion(), 2, false, "bob", "");
    assertThat(deciding.pkg().state()).isEqualTo(PackageState.DECIDING);

    DecisionCommitPlan plan = DecisionPackageTransitions.prepareCommit(deciding, NOW);
    assertThat(plan.calls()).extracting(ResolvedCall::index).containsExactly(0);
    DecisionPackageSnapshot committed =
        DecisionPackageTransitions.completeCommit(
            plan,
            new CommitReceipt(
                CommitReceipt.Status.COMMITTED, "rev-json", List.of(COMMITTED), "ok"));
    assertThat(committed.pkg().state()).isEqualTo(PackageState.COMMITTED);
    assertThat(committed.receipt().outcomes())
        .containsExactly(COMMITTED, REJECTED, REJECTED, REJECTED);
  }
}
