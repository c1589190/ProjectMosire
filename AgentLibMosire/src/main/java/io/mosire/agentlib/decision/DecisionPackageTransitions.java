package io.mosire.agentlib.decision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 决策包的<b>纯状态转移核心</b>（U1，上游契约 {@code docs/upstream/agentlib-decision-contract.md} §4/§5）。
 *
 * <p><b>它是什么</b>：把「提议 → 可编辑窗口 → 逐条/整包裁决 → 提交计划 → 回执落定」这套状态机写成一组 <b>无状态纯函数</b>。每个方法只吃"当前快照 +
 * 显式参数"、吐"新快照/计划"或抛具名拒；不碰墙钟、不落盘、不发事件、 不开线程。宿主因此可以把快照持久化到任意介质，在重启后用同一份数据继续跑。
 *
 * <p><b>为什么时间也必须注入</b>（{@link #propose} / {@link #append} 等签名里的 {@code nowEpochMs}）：到点判定若藏在读路径里，
 * "同一份快照读两次得到两个状态"会让宿主的持久化/审计对不上账。纯核心只认调用方给的时刻；{@link #expire} 是显式转移， 宿主应在 tick / 窗口边界调用它。
 *
 * <p><b>与 {@link DecisionPackages} 的关系</b>：旧内存适配器保留自己的系统时钟与一实例一未决包语义；它的宿主 id 重载把这些动作转进来，做"注册表 +
 * 锁"的一层薄壳。纯核心本身<b>不认识"包表"</b>——同 id 不覆盖由适配器在登记时保证， 见 {@link DecisionPackages#propose(String,
 * ProposeRequest)}。
 *
 * <p><b>不变量速查</b>（逐条对应契约 §5）：contentVersion 从 1 起且内容/裁决变更递增；append / replace / remove 只在 {@code
 * PROPOSED} 且无 verdict 时允许（首次逐条表态后 {@code PACKAGE_CONTENT_FROZEN}）；index 稳定、允许不连续， append
 * 只取更大值、remove 留洞；版本不匹配 {@code PACKAGE_VERSION_STALE}；被批 calls 只来自 approved indexes； 非法回执不得让快照进入
 * {@code COMMITTED}。
 *
 * <p><b>线程安全</b>：本类无可变状态，全部方法可并发调用；返回值都是不可变记录。
 */
public final class DecisionPackageTransitions {

  private DecisionPackageTransitions() {}

  /**
   * 用<b>宿主给的 id</b>建包（契约 §4 {@code propose}）。
   *
   * <p>形状校验（至少一条、index 互不相同）与旧 {@link DecisionPackages#propose(ProposeRequest)} 同口径；到点时刻已过 ⇒ 直接得到
   * {@code EXPIRED}（与旧适配器的"出生即过期"一致，不让它挂在待裁决上骗人）。
   *
   * <p><b>同 id 已存在</b>不可能在这里判——纯函数没有包表；由 {@link DecisionPackages#propose(String, ProposeRequest)}
   * 在登记时 具名拒 {@code PACKAGE_ALREADY_EXISTS}，不覆盖。
   *
   * @param packageId 宿主给的包 id（非空白；不得含明文参数/密钥是宿主的责任）
   * @param request 提议请求（条目至少一条、index 逐包唯一）
   * @param nowEpochMs 建包时刻（调用方给的墙钟毫秒；不读系统时钟）
   * @return 新快照（通常 {@code PROPOSED}；deadline 已过 ⇒ {@code EXPIRED}）
   */
  public static DecisionPackageSnapshot propose(
      String packageId, ProposeRequest request, long nowEpochMs) {
    Objects.requireNonNull(packageId, "packageId");
    if (packageId.isBlank()) {
      throw new IllegalArgumentException("packageId 不得为空白（它是包的幂等键与对账键）");
    }
    Objects.requireNonNull(request, "request");
    if (request.items().isEmpty()) {
      throw new IllegalArgumentException("items 不得为空：一个包至少要有一条调用（空包没有可裁决/可提交的东西）");
    }
    requireUniqueItemIndexes(request.items());
    DecisionPackage created =
        new DecisionPackage(
            packageId,
            request.proposerKey(),
            request.caller(),
            request.items(),
            nowEpochMs,
            request.deadlineEpochMs(),
            PackageState.PROPOSED,
            null);
    DecisionPackageSnapshot snapshot =
        new DecisionPackageSnapshot(created, 1L, List.of(), null, null);
    return nowEpochMs >= created.deadlineEpochMs() ? expire(snapshot, nowEpochMs) : snapshot;
  }

  /**
   * 追加条目（契约 §5.3）：只允许 {@code PROPOSED} 且还没有任何逐条表态。
   *
   * <p><b>index 只增</b>：每条新 index 必须严格大于当前最大 index；允许不连续（{@code [0,2,5]} 合法）。本批内也必须严格 递增——"append
   * 只增"一旦在批内乱序，包内顺序就不再是稳定身份顺序。
   *
   * @param current 当前快照
   * @param items 新条目（非空；index 严格递增且大于当前最大值）
   * @param expectedContentVersion 调用方看到的版本（不匹配 ⇒ {@code PACKAGE_VERSION_STALE}）
   * @param nowEpochMs 调用时刻（到点 ⇒ {@code PACKAGE_EXPIRED}；不读系统时钟）
   * @return 新快照（状态仍为 {@code PROPOSED}；contentVersion+1）
   */
  public static DecisionPackageSnapshot append(
      DecisionPackageSnapshot current,
      List<PackageItem> items,
      long expectedContentVersion,
      long nowEpochMs) {
    requireCurrent(current);
    requireVersion(current, expectedContentVersion);
    requireBeforeDeadline(current, nowEpochMs);
    requireEditable(current);
    Objects.requireNonNull(items, "items");
    if (items.isEmpty()) {
      throw new IllegalArgumentException("items 不得为空：append 至少追加一条（空追加不是内容变更）");
    }
    int previousMax = maxItemIndex(current.pkg());
    Set<Integer> seen = new LinkedHashSet<>();
    for (PackageItem item : items) {
      Objects.requireNonNull(item, "items 含 null");
      if (!seen.add(item.index())) {
        throw reject(
            DecisionPackages.INVALID_ITEM_INDEX,
            "append 的 index 在同一批里重复：index=" + item.index() + "（条目身份必须唯一）");
      }
      if (item.index() <= previousMax) {
        throw reject(
            DecisionPackages.INVALID_ITEM_INDEX,
            "append 的 index 必须严格大于当前最大 index：新 index="
                + item.index()
                + " 当前最大="
                + previousMax
                + "（包 "
                + current.pkg().id()
                + "）");
      }
      previousMax = item.index();
    }
    List<PackageItem> merged = new ArrayList<>(current.pkg().items());
    merged.addAll(items);
    DecisionPackage appended = withItems(current.pkg(), merged);
    return new DecisionPackageSnapshot(
        appended, current.contentVersion() + 1L, List.of(), null, null);
  }

  /**
   * 原位替换一条（契约 §5.3）：必须保持同一 index，且只允许还没有任何逐条表态的 {@code PROPOSED} 包。
   *
   * @param current 当前快照
   * @param index 被替换条目的稳定身份
   * @param replacement 新条目（{@code replacement.index() == index}）
   * @param expectedContentVersion 调用方看到的版本
   * @param nowEpochMs 调用时刻（到点 ⇒ {@code PACKAGE_EXPIRED}）
   * @return 新快照（contentVersion+1）
   */
  public static DecisionPackageSnapshot replaceItem(
      DecisionPackageSnapshot current,
      int index,
      PackageItem replacement,
      long expectedContentVersion,
      long nowEpochMs) {
    requireCurrent(current);
    requireVersion(current, expectedContentVersion);
    requireBeforeDeadline(current, nowEpochMs);
    requireEditable(current);
    Objects.requireNonNull(replacement, "replacement");
    if (replacement.index() != index) {
      throw reject(
          DecisionPackages.INVALID_ITEM_INDEX,
          "replaceItem 必须保持同一 index：目标="
              + index
              + " 替换条目="
              + replacement.index()
              + "（index 是稳定身份，不是可改字段）");
    }
    int position = indexOf(current.pkg(), index);
    if (position < 0) {
      throw unknownIndex(current.pkg(), "replaceItem", index);
    }
    List<PackageItem> items = new ArrayList<>(current.pkg().items());
    items.set(position, replacement);
    DecisionPackage replaced = withItems(current.pkg(), items);
    return new DecisionPackageSnapshot(
        replaced, current.contentVersion() + 1L, List.of(), null, null);
  }

  /**
   * 删除一条（契约 §5.3）：不重排剩余 index（留洞）；只允许还没有任何逐条表态的 {@code PROPOSED} 包。 删空包具名拒 {@code
   * PACKAGE_EMPTY}（空包没有可裁决/可提交的东西）。
   *
   * @param current 当前快照
   * @param index 被删除条目的稳定身份
   * @param expectedContentVersion 调用方看到的版本
   * @param nowEpochMs 调用时刻（到点 ⇒ {@code PACKAGE_EXPIRED}）
   * @return 新快照（contentVersion+1）
   */
  public static DecisionPackageSnapshot removeItem(
      DecisionPackageSnapshot current, int index, long expectedContentVersion, long nowEpochMs) {
    requireCurrent(current);
    requireVersion(current, expectedContentVersion);
    requireBeforeDeadline(current, nowEpochMs);
    requireEditable(current);
    int position = indexOf(current.pkg(), index);
    if (position < 0) {
      throw unknownIndex(current.pkg(), "removeItem", index);
    }
    if (current.pkg().items().size() == 1) {
      throw reject(
          DecisionPackages.PACKAGE_EMPTY,
          "removeItem 不得删空包：id=" + current.pkg().id() + " 只剩 index=" + index + "（空包没有可裁决/可提交的东西）");
    }
    List<PackageItem> items = new ArrayList<>(current.pkg().items());
    items.remove(position);
    DecisionPackage removed = withItems(current.pkg(), items);
    return new DecisionPackageSnapshot(
        removed, current.contentVersion() + 1L, List.of(), null, null);
  }

  /**
   * 整包统一裁决（契约 §5.4）：只允许 {@code PROPOSED}，首次裁决终局；{@code approvedIndexes} 只能引用当前存在的 index。
   *
   * <p><b>被批子集为空</b> ⇒ 当场终局 {@code REJECTED}（零落盘）并合成一份全 {@code REJECTED} 回执——不产生提交计划，
   * 宿主也不需要为一个没有落盘动作的包碰提交口（契约 §5.5）。
   *
   * @param current 当前快照
   * @param expectedContentVersion 调用方看到的版本（不匹配 ⇒ {@code PACKAGE_VERSION_STALE}）
   * @param decision 统一裁决
   * @param nowEpochMs 调用时刻（到点 ⇒ {@code PACKAGE_EXPIRED}）
   * @return 新快照（{@code DECIDED}；被批子集为空 ⇒ {@code REJECTED}；contentVersion+1）
   */
  public static DecisionPackageSnapshot decide(
      DecisionPackageSnapshot current,
      long expectedContentVersion,
      Decision decision,
      long nowEpochMs) {
    requireCurrent(current);
    requireVersion(current, expectedContentVersion);
    requireBeforeDeadline(current, nowEpochMs);
    requireDecidable(current.pkg());
    Objects.requireNonNull(decision, "decision");
    Set<Integer> known = knownIndexes(current.pkg());
    List<Integer> unknown =
        decision.approvedIndexes().stream()
            .filter(approvedIndex -> !known.contains(approvedIndex))
            .sorted()
            .toList();
    if (!unknown.isEmpty()) {
      throw reject(
          DecisionPackages.INVALID_DECISION,
          "裁决引用了包内不存在的条目 index="
              + unknown
              + "（包 "
              + current.pkg().id()
              + " 共 "
              + current.pkg().items().size()
              + " 条）");
    }
    List<DecisionItemVerdict> verdicts = verdictsFromDecision(current.pkg(), decision);
    DecisionPackage decided = withState(current.pkg(), PackageState.DECIDED, decision);
    DecisionPackageSnapshot next =
        new DecisionPackageSnapshot(decided, current.contentVersion() + 1L, verdicts, null, null);
    return decision.approvedIndexes().isEmpty() ? rejectEmptyApproval(next) : next;
  }

  /**
   * 条目级表态（契约 §5.4）：只允许 {@code PROPOSED} / {@code DECIDING}；同一条只允许一次；全部条目表态完 ⇒ {@code DECIDED}。
   *
   * <p><b>未表态不是拒绝</b>：分段状态下没表态的条目保持"未决"，只有显式 {@link #prepareCommit} 或 {@link #expire} 才按 "静默 =
   * 拒绝"终结它们。全部表态且一条都没批 ⇒ 当场终局 {@code REJECTED}（被批子集为空，零落盘）。
   *
   * @param current 当前快照
   * @param expectedContentVersion 调用方看到的版本（不匹配 ⇒ {@code PACKAGE_VERSION_STALE}）
   * @param index 条目稳定身份
   * @param approve {@code true} = 批准，{@code false} = 驳回
   * @param by 表态人（空白 ⇒ 参数期 {@link IllegalArgumentException}）
   * @param note 可空理由
   * @param nowEpochMs 调用时刻（到点 ⇒ {@code PACKAGE_EXPIRED}）
   * @return 新快照（{@code DECIDING} / {@code DECIDED}；全驳 ⇒ {@code REJECTED}；contentVersion+1）
   */
  public static DecisionPackageSnapshot itemDecided(
      DecisionPackageSnapshot current,
      long expectedContentVersion,
      int index,
      boolean approve,
      String by,
      String note,
      long nowEpochMs) {
    requireCurrent(current);
    requireVersion(current, expectedContentVersion);
    requireBeforeDeadline(current, nowEpochMs);
    requireItemDecidable(current.pkg());
    if (indexOf(current.pkg(), index) < 0) {
      throw unknownIndex(current.pkg(), "itemDecided", index);
    }
    if (current.verdicts().stream().anyMatch(verdict -> verdict.index() == index)) {
      throw reject(
          DecisionPackages.ITEM_ALREADY_DECIDED,
          "条目 index=" + index + " 已经表过态（裁决唯一：一条一次，重复表态不是改主意）包=" + current.pkg().id());
    }
    DecisionItemVerdict verdict = new DecisionItemVerdict(index, approve, by, note);
    List<DecisionItemVerdict> verdicts = new ArrayList<>(current.verdicts());
    verdicts.add(verdict);
    if (verdicts.size() < current.pkg().items().size()) {
      DecisionPackage deciding = withState(current.pkg(), PackageState.DECIDING, null);
      return new DecisionPackageSnapshot(
          deciding, current.contentVersion() + 1L, verdicts, null, null);
    }
    Decision decision = accumulateDecision(current.pkg(), verdicts, "");
    DecisionPackage decided = withState(current.pkg(), PackageState.DECIDED, decision);
    DecisionPackageSnapshot next =
        new DecisionPackageSnapshot(decided, current.contentVersion() + 1L, verdicts, null, null);
    return decision.approvedIndexes().isEmpty() ? rejectEmptyApproval(next) : next;
  }

  /**
   * 显式到点（契约 §5.6）：{@code nowEpochMs >= deadlineEpochMs} 时把未终结的包推到 {@code EXPIRED}；否则原样返回。
   *
   * <p>到点原因与旧适配器 {@link DecisionPackages#expiryReason(String)} 同语义：{@code PROPOSED} ⇒ {@code
   * EXPIRED_NO_DECISION}；{@code DECIDED}（含 {@code DECIDING} 先按"静默 = 拒绝"终结）⇒ {@code
   * EXPIRED_DECIDED_NOT_EXECUTED}。已终局（COMMITTED/REJECTED/CONFLICTED）的包不因时间改变——终局不可改写。
   *
   * @param current 当前快照
   * @param nowEpochMs 调用方给的墙钟毫秒（不读系统时钟）
   * @return 新快照（到点 ⇒ {@code EXPIRED}；未到点或已终局 ⇒ 原快照）
   */
  public static DecisionPackageSnapshot expire(DecisionPackageSnapshot current, long nowEpochMs) {
    requireCurrent(current);
    DecisionPackage pkg = current.pkg();
    return switch (pkg.state()) {
      case EXPIRED, COMMITTED, REJECTED, CONFLICTED -> current;
      case PROPOSED, DECIDING, DECIDED -> {
        if (nowEpochMs < pkg.deadlineEpochMs()) {
          yield current;
        }
        yield switch (pkg.state()) {
          case PROPOSED ->
              new DecisionPackageSnapshot(
                  withState(pkg, PackageState.EXPIRED, null),
                  current.contentVersion(),
                  current.verdicts(),
                  null,
                  DecisionPackages.EXPIRED_NO_DECISION);
          case DECIDING -> {
            Decision decision =
                accumulateDecision(pkg, current.verdicts(), DecisionPackages.UNSTATED_ITEM_REASON);
            yield new DecisionPackageSnapshot(
                withState(pkg, PackageState.EXPIRED, decision),
                current.contentVersion(),
                current.verdicts(),
                null,
                DecisionPackages.EXPIRED_DECIDED_NOT_EXECUTED);
          }
          case DECIDED ->
              new DecisionPackageSnapshot(
                  withState(pkg, PackageState.EXPIRED, pkg.decision()),
                  current.contentVersion(),
                  current.verdicts(),
                  null,
                  DecisionPackages.EXPIRED_DECIDED_NOT_EXECUTED);
          default -> throw new IllegalStateException("不可达：外层已限定 PROPOSED/DECIDING/DECIDED");
        };
      }
    };
  }

  /**
   * 准备提交（契约 §5.5）：只在 {@code DECIDED} 或"可终结的 {@code DECIDING}"上成功，返回<b>被批 calls + 提交前快照</b>。
   *
   * <p><b>{@code DECIDING} 的终结</b>：显式执行命令 = "就这些了"——没表态的条目按"静默 = 拒绝"归成被驳（零落盘）， 已批准的那些进 calls；{@code
   * before} 是终结后的 {@code DECIDED} 快照（contentVersion+1），不是半成品分段态。 一条批准都没有 ⇒ 具名拒 {@code
   * NO_ITEM_DECIDED}，<b>不产生计划、不碰提交口</b>——执行命令不是"替人批一条"的入口。
   *
   * @param current 当前快照
   * @param nowEpochMs 调用时刻（到点 ⇒ {@code PACKAGE_EXPIRED}）
   * @return 提交计划（calls 非空，只含被批 index）
   */
  public static DecisionCommitPlan prepareCommit(DecisionPackageSnapshot current, long nowEpochMs) {
    requireCurrent(current);
    requireBeforeDeadline(current, nowEpochMs);
    return switch (current.pkg().state()) {
      case DECIDED -> planFor(current);
      case DECIDING -> {
        boolean anyApproved = current.verdicts().stream().anyMatch(DecisionItemVerdict::approved);
        if (!anyApproved) {
          throw reject(
              DecisionPackages.NO_ITEM_DECIDED,
              "包里没有一条被批准（已表态 "
                  + current.verdicts().size()
                  + " 条、全部驳回）⇒ 不执行、零落盘：包 id="
                  + current.pkg().id());
        }
        Decision decision =
            accumulateDecision(
                current.pkg(), current.verdicts(), DecisionPackages.UNSTATED_ITEM_REASON);
        DecisionPackage finalized = withState(current.pkg(), PackageState.DECIDED, decision);
        DecisionPackageSnapshot before =
            new DecisionPackageSnapshot(
                finalized, current.contentVersion() + 1L, current.verdicts(), null, null);
        yield planFor(before);
      }
      case PROPOSED ->
          throw reject(
              DecisionPackages.PACKAGE_NOT_DECIDED,
              "决策包尚未裁决：id=" + current.pkg().id() + " state=PROPOSED——没有裁决就没有执行");
      case EXPIRED ->
          throw reject(
              DecisionPackages.PACKAGE_EXPIRED,
              "决策包已超时（EXPIRED）：id=" + current.pkg().id() + "——超时零落盘，不静默执行");
      case COMMITTED, REJECTED, CONFLICTED ->
          throw reject(
              DecisionPackages.PACKAGE_NOT_DECIDED,
              "决策包已终局，不能再准备提交：id="
                  + current.pkg().id()
                  + " state="
                  + current.pkg().state()
                  + "（重复提交应拿回同一份回执，而不是再生成一个计划）");
    };
  }

  /**
   * 用宿主回执落定提交计划（契约 §5.5）。
   *
   * <p><b>校验不过 ⇒ 具名拒 {@code COMMIT_FAILED}，快照一步也不动</b>（纯函数没有"改了一半"的中间态）。非法回执包括： 与 calls
   * 长度/顺序对不上、{@code COMMITTED} 却带未落定/被驳 outcome、{@code REJECTED/CONFLICTED} 却带 COMMITTED
   * outcome、非提交结局却带回 revisionRef、calls 不等于 {@code before} 的被批子集等。
   *
   * <p><b>幂等</b>：同一份计划 + 同一份回执重复调用得到<b>同一个</b>落定快照；若 {@code before} 已经带另一份回执， 具名拒——同一包不允许两份可对账的回执。
   *
   * @param plan 提交计划（来自 {@link #prepareCommit} 或宿主持久化后恢复的等价值）
   * @param receipt 宿主回执（三结局之一）
   * @return 落定快照（{@code COMMITTED} / {@code REJECTED} / {@code CONFLICTED}；contentVersion 不变）
   */
  public static DecisionPackageSnapshot completeCommit(
      DecisionCommitPlan plan, CommitReceipt receipt) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(receipt, "receipt");
    DecisionPackageSnapshot before = plan.before();
    if (before.receipt() != null) {
      if (before.receipt().equals(receipt)) {
        // 同一份回执的重放：原样拿回落定快照，不产生第二次落盘语义。
        return before;
      }
      throw reject(
          DecisionPackages.COMMIT_FAILED,
          "该包已经落定另一份回执，拒绝覆盖：id=" + before.pkg().id() + "（同一包只允许一份可对账的回执）");
    }
    requireCommittable(before.pkg());
    List<ResolvedCall> expectedCalls = approvedCalls(before.pkg());
    if (expectedCalls.isEmpty()) {
      throw reject(
          DecisionPackages.NO_ITEM_DECIDED,
          "提交计划没有可落盘的被批子集：包 id=" + before.pkg().id() + "（被批子集为空应直接终局 REJECTED，不产生提交计划）");
    }
    if (!expectedCalls.equals(plan.calls())) {
      throw reject(
          DecisionPackages.COMMIT_FAILED,
          "提交计划的 calls 必须逐条等于 before 的被批子集（顺序 = 包内条目顺序）：包 id="
              + before.pkg().id()
              + " 计划="
              + plan.calls().size()
              + " 条 应批="
              + expectedCalls.size()
              + " 条");
    }
    validateHostReceipt(receipt, plan.calls());
    CommitReceipt merged = mergeReceipt(before.pkg(), plan.calls(), receipt);
    return new DecisionPackageSnapshot(
        withState(before.pkg(), stateOf(receipt.status()), before.pkg().decision()),
        before.contentVersion(),
        before.verdicts(),
        merged,
        before.expiryReason());
  }

  // ---------------------------------------------------------------------------------------------
  // 内部校验 / 组装
  // ---------------------------------------------------------------------------------------------

  private static void requireCurrent(DecisionPackageSnapshot current) {
    Objects.requireNonNull(current, "current");
  }

  /** 版本冲突必须零状态变更：纯函数天然满足（原快照不可变），这里只负责具名拒。 */
  private static void requireVersion(DecisionPackageSnapshot current, long expectedContentVersion) {
    if (current.contentVersion() != expectedContentVersion) {
      throw reject(
          DecisionPackages.PACKAGE_VERSION_STALE,
          "内容版本不匹配（可能基于旧包裁决）：包="
              + current.pkg().id()
              + " 期望="
              + expectedContentVersion
              + " 当前="
              + current.contentVersion()
              + "——零状态变更");
    }
  }

  /**
   * 纯转移的到点拒绝：不替宿主做惰性过期（快照不变），只拒绝"到点之后还继续改/裁/提交"的写动作。
   *
   * <p>{@link #propose} 是唯一例外：建包时 deadline 已过 ⇒ 直接返回 {@code EXPIRED}（与旧适配器同语义）。
   */
  private static void requireBeforeDeadline(DecisionPackageSnapshot current, long nowEpochMs) {
    DecisionPackage pkg = current.pkg();
    switch (pkg.state()) {
      case PROPOSED, DECIDING, DECIDED -> {
        if (nowEpochMs >= pkg.deadlineEpochMs()) {
          throw reject(
              DecisionPackages.PACKAGE_EXPIRED,
              "决策包已到点（nowEpochMs="
                  + nowEpochMs
                  + " >= deadlineEpochMs="
                  + pkg.deadlineEpochMs()
                  + "）：id="
                  + pkg.id()
                  + "——请先显式 expire（纯转移不替宿主改状态）");
        }
      }
      case EXPIRED, COMMITTED, REJECTED, CONFLICTED -> {
        // 终局态的时间判定已无意义；具体拒码由后续状态检查给出。
      }
    }
  }

  /** 编辑窗口：只允许 PROPOSED 且尚无任何逐条表态；首次逐条表态后内容冻结。 */
  private static void requireEditable(DecisionPackageSnapshot current) {
    DecisionPackage pkg = current.pkg();
    if (pkg.state() == PackageState.EXPIRED) {
      throw reject(
          DecisionPackages.PACKAGE_EXPIRED, "决策包已超时（EXPIRED）：id=" + pkg.id() + "——到点后内容不再接受编辑");
    }
    if (pkg.state() != PackageState.PROPOSED || !current.verdicts().isEmpty()) {
      throw reject(
          DecisionPackages.PACKAGE_CONTENT_FROZEN,
          "包内容已冻结：id="
              + pkg.id()
              + " state="
              + pkg.state()
              + " verdicts="
              + current.verdicts().size()
              + "——append/replace/remove 只允许在 PROPOSED 且尚无逐条表态时进行");
    }
  }

  /** 整包裁决只落在 PROPOSED（分段裁决开始后由 PARTIAL_DECISION 拒，终局由 PACKAGE_NOT_PROPOSED 拒）。 */
  private static void requireDecidable(DecisionPackage pkg) {
    switch (pkg.state()) {
      case PROPOSED -> {
        return;
      }
      case DECIDING ->
          throw reject(
              DecisionPackages.PARTIAL_DECISION,
              "决策包已进入分段裁决（DECIDING）：id=" + pkg.id() + "——整包裁决不再可用，请按条目继续表态（或等到点/显式执行）");
      case EXPIRED ->
          throw reject(
              DecisionPackages.PACKAGE_EXPIRED, "决策包已超时（EXPIRED）：id=" + pkg.id() + "——超时零落盘，不静默执行");
      case DECIDED, COMMITTED, REJECTED, CONFLICTED ->
          throw reject(
              DecisionPackages.PACKAGE_NOT_PROPOSED,
              "决策包不在待裁决态：id=" + pkg.id() + " state=" + pkg.state() + "（decide 一次终局，重复裁决不覆盖首次）");
    }
  }

  /** 逐条表态只落在 PROPOSED / DECIDING。 */
  private static void requireItemDecidable(DecisionPackage pkg) {
    switch (pkg.state()) {
      case PROPOSED, DECIDING -> {
        return;
      }
      case EXPIRED ->
          throw reject(
              DecisionPackages.PACKAGE_EXPIRED,
              "决策包已超时（EXPIRED）：id=" + pkg.id() + "——超时零落盘，不再接受表态");
      case DECIDED, COMMITTED, REJECTED, CONFLICTED ->
          throw reject(
              DecisionPackages.PACKAGE_NOT_PROPOSED,
              "决策包已终结，不再接受条目级表态：id="
                  + pkg.id()
                  + " state="
                  + pkg.state()
                  + "（裁决唯一：一条一次，重复表态不是改主意）");
    }
  }

  /** 提交只允许落在 DECIDED（分段态必须先经 prepareCommit 显式终结）。 */
  private static void requireCommittable(DecisionPackage pkg) {
    switch (pkg.state()) {
      case DECIDED -> {
        return;
      }
      case EXPIRED ->
          throw reject(
              DecisionPackages.PACKAGE_EXPIRED, "决策包已超时（EXPIRED）：id=" + pkg.id() + "——超时零落盘，不静默执行");
      case PROPOSED, DECIDING, COMMITTED, REJECTED, CONFLICTED ->
          throw reject(
              DecisionPackages.PACKAGE_NOT_DECIDED,
              "提交计划必须基于已裁决（DECIDED）快照：id=" + pkg.id() + " state=" + pkg.state());
    }
  }

  /**
   * 宿主回执的逐条对齐校验（fail-closed）：calls 只含被批条目，因此宿主只能报"落没落"（COMMITTED / NOT_COMMITTED），
   * 不许报被驳（REJECTED）；结局与逐条去向也必须自洽。
   *
   * <p>允许宿主少报逐条 outcome（与旧 {@link DecisionCommitPort} 契约一致：缺失项按整批 status 推导），但多报 = 对不齐。
   */
  private static void validateHostReceipt(CommitReceipt receipt, List<ResolvedCall> calls) {
    List<CommitReceipt.ItemResult> hostResults = receipt.itemResults();
    if (hostResults.size() > calls.size()) {
      throw reject(
          DecisionPackages.COMMIT_FAILED,
          "回执 outcomes 多于 calls：outcomes="
              + hostResults.size()
              + " calls="
              + calls.size()
              + "（包层只认被批子集，多报说明提交口对错了包）");
    }
    if (receipt.status() == CommitReceipt.Status.COMMITTED
        && (receipt.revisionRef() == null || receipt.revisionRef().isBlank())) {
      throw reject(
          DecisionPackages.COMMIT_FAILED,
          "status=COMMITTED 必须带回非空白 revisionRef（恰好一条 revision 可对账）");
    }
    if (receipt.status() != CommitReceipt.Status.COMMITTED
        && receipt.revisionRef() != null
        && !receipt.revisionRef().isBlank()) {
      throw reject(
          DecisionPackages.COMMIT_FAILED,
          "status=" + receipt.status() + " 是零落盘结局，不得带回 revisionRef（否则等于声称写了一条不存在的 revision）");
    }
    for (int i = 0; i < hostResults.size(); i++) {
      CommitReceipt.ItemOutcome outcome = hostResults.get(i).outcome();
      if (outcome == CommitReceipt.ItemOutcome.REJECTED) {
        throw reject(
            DecisionPackages.COMMIT_FAILED,
            "回执第 " + i + " 条把被批调用报成 REJECTED：被驳条目不进 calls，宿主管不着它们（逐条去向只报落没落）");
      }
      if (receipt.status() == CommitReceipt.Status.COMMITTED
          && outcome != CommitReceipt.ItemOutcome.COMMITTED) {
        throw reject(
            DecisionPackages.COMMIT_FAILED,
            "回执自相矛盾：status=COMMITTED 但第 " + i + " 条 outcome=" + outcome + "（被批子集必须整批落定）");
      }
      if (receipt.status() != CommitReceipt.Status.COMMITTED
          && outcome == CommitReceipt.ItemOutcome.COMMITTED) {
        throw reject(
            DecisionPackages.COMMIT_FAILED,
            "回执自相矛盾：status=" + receipt.status() + " 是零落盘结局，但第 " + i + " 条 outcome=COMMITTED");
      }
    }
  }

  /** 把宿主回执（按 calls 顺序）与"被驳条目"合成一份对整包的回执：逐条同序、长度 = 包内条目数。 */
  private static CommitReceipt mergeReceipt(
      DecisionPackage pkg, List<ResolvedCall> calls, CommitReceipt host) {
    List<CommitReceipt.ItemResult> hostResults = host.itemResults();
    Map<Integer, CommitReceipt.ItemResult> byIndex = new LinkedHashMap<>();
    for (int i = 0; i < calls.size(); i++) {
      if (i < hostResults.size()) {
        byIndex.put(calls.get(i).index(), hostResults.get(i));
      } else {
        CommitReceipt.ItemOutcome derived =
            host.status() == CommitReceipt.Status.COMMITTED
                ? CommitReceipt.ItemOutcome.COMMITTED
                : CommitReceipt.ItemOutcome.NOT_COMMITTED;
        byIndex.put(calls.get(i).index(), new CommitReceipt.ItemResult(derived, null));
      }
    }
    List<CommitReceipt.ItemOutcome> outcomes = new ArrayList<>(pkg.items().size());
    List<Optional<String>> reasons = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      CommitReceipt.ItemResult result = byIndex.get(item.index());
      if (result == null) {
        outcomes.add(CommitReceipt.ItemOutcome.REJECTED);
        reasons.add(Optional.of(DecisionPackages.REJECTED_ITEM_REASON));
      } else {
        outcomes.add(result.outcome());
        reasons.add(Optional.ofNullable(result.reason()));
      }
    }
    return new CommitReceipt(
        host.status(), host.revisionRef(), outcomes, List.copyOf(reasons), host.reason());
  }

  /** 被批子集：只含 {@code decision.approves} 为真的条目，按包内条目顺序（被驳条目永不进 calls）。 */
  private static List<ResolvedCall> approvedCalls(DecisionPackage pkg) {
    Decision decision = pkg.decision();
    if (decision == null) {
      return List.of();
    }
    List<ResolvedCall> calls = new ArrayList<>();
    for (PackageItem item : pkg.items()) {
      if (decision.approves(item.index())) {
        calls.add(new ResolvedCall(item.index(), item.toolName(), item.args()));
      }
    }
    return List.copyOf(calls);
  }

  /** {@code DECIDED} ⇒ calls 非空的提交计划；被批子集为空属于不一致状态，具名拒而不是造一个空计划。 */
  private static DecisionCommitPlan planFor(DecisionPackageSnapshot decided) {
    List<ResolvedCall> calls = approvedCalls(decided.pkg());
    if (calls.isEmpty()) {
      throw reject(
          DecisionPackages.NO_ITEM_DECIDED,
          "决策包没有被批子集：id=" + decided.pkg().id() + "——被批子集为空应终局 REJECTED，不产生提交计划（零落盘）");
    }
    return new DecisionCommitPlan(decided, calls);
  }

  /**
   * 被批子集为空 ⇒ 终局 {@code REJECTED} + 一份全 {@code REJECTED} 合成回执（零落盘）。
   *
   * <p>为什么在纯核心里合成回执：没有落盘动作就没有宿主要对账的 revision，但调用方仍需要一份"统一结果"（B-7）； 这份回执的 id 由快照里的包 id 承担，不涉及宿主世界类型。
   */
  private static DecisionPackageSnapshot rejectEmptyApproval(DecisionPackageSnapshot decided) {
    DecisionPackage pkg = decided.pkg();
    List<CommitReceipt.ItemOutcome> outcomes = new ArrayList<>(pkg.items().size());
    List<Optional<String>> reasons = new ArrayList<>(pkg.items().size());
    for (int i = 0; i < pkg.items().size(); i++) {
      outcomes.add(CommitReceipt.ItemOutcome.REJECTED);
      reasons.add(Optional.of(DecisionPackages.REJECTED_ITEM_REASON));
    }
    CommitReceipt receipt =
        new CommitReceipt(
            CommitReceipt.Status.REJECTED,
            null,
            outcomes,
            List.copyOf(reasons),
            "被批子集为空（全部条目被驳）：零落盘，未调用提交口");
    return new DecisionPackageSnapshot(
        withState(pkg, PackageState.REJECTED, pkg.decision()),
        decided.contentVersion(),
        decided.verdicts(),
        receipt,
        decided.expiryReason());
  }

  /** 按条目顺序把一份整包裁决摊成逐条累计（表决/整包裁决共用同一口径）。 */
  private static List<DecisionItemVerdict> verdictsFromDecision(
      DecisionPackage pkg, Decision decision) {
    List<DecisionItemVerdict> verdicts = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      verdicts.add(
          new DecisionItemVerdict(
              item.index(), decision.approves(item.index()), decision.by(), decision.note()));
    }
    return List.copyOf(verdicts);
  }

  /**
   * 把逐条累计终结成一份整包裁决：未出现在 verdicts 里的条目按"静默 = 拒绝"处理（缺席 = 驳回）。
   *
   * @param finalizeNote 终结原因（空串 = 全部条目都已表态；{@code UNSTATED_ITEM_REASON} = 显式执行/到点）
   */
  private static Decision accumulateDecision(
      DecisionPackage pkg, List<DecisionItemVerdict> verdicts, String finalizeNote) {
    Set<Integer> approved = new LinkedHashSet<>();
    for (DecisionItemVerdict verdict : verdicts) {
      if (verdict.approved()) {
        approved.add(verdict.index());
      }
    }
    return new Decision(
        approved,
        DecisionPackages.DECIDED_BY_ITEM_VERDICTS,
        finalizeNote == null ? "" : finalizeNote);
  }

  /** 回执三结局 ⇒ 包状态（穷尽 switch：新增结局时编译器会提醒）。 */
  private static PackageState stateOf(CommitReceipt.Status status) {
    return switch (status) {
      case COMMITTED -> PackageState.COMMITTED;
      case REJECTED -> PackageState.REJECTED;
      case CONFLICTED -> PackageState.CONFLICTED;
    };
  }

  /** 换状态（逐字段拷贝：新增字段时编译器在这里提醒，漏拷不会静默丢身份）。 */
  private static DecisionPackage withState(
      DecisionPackage current, PackageState state, Decision decision) {
    return new DecisionPackage(
        current.id(),
        current.proposerKey(),
        current.caller(),
        current.items(),
        current.createdAtEpochMs(),
        current.deadlineEpochMs(),
        state,
        decision);
  }

  /** 换条目（状态/裁决按原样保留：只用于编辑窗口内的 PROPOSED 包）。 */
  private static DecisionPackage withItems(DecisionPackage current, List<PackageItem> items) {
    return new DecisionPackage(
        current.id(),
        current.proposerKey(),
        current.caller(),
        items,
        current.createdAtEpochMs(),
        current.deadlineEpochMs(),
        current.state(),
        current.decision());
  }

  private static int indexOf(DecisionPackage pkg, int index) {
    for (int position = 0; position < pkg.items().size(); position++) {
      if (pkg.items().get(position).index() == index) {
        return position;
      }
    }
    return -1;
  }

  private static int maxItemIndex(DecisionPackage pkg) {
    int max = -1;
    for (PackageItem item : pkg.items()) {
      max = Math.max(max, item.index());
    }
    return max;
  }

  private static Set<Integer> knownIndexes(DecisionPackage pkg) {
    Set<Integer> known = new LinkedHashSet<>();
    for (PackageItem item : pkg.items()) {
      known.add(item.index());
    }
    return known;
  }

  private static void requireUniqueItemIndexes(List<PackageItem> items) {
    Set<Integer> indexes = new LinkedHashSet<>();
    for (PackageItem item : items) {
      if (!indexes.add(item.index())) {
        throw new IllegalArgumentException(
            "items 的 index 必须互不相同（它是条目在包内的稳定身份）：重复 index=" + item.index());
      }
    }
  }

  /** 具名拒的构造收敛点：纯核心只抛 {@link DecisionPackages.RejectionException}，不造第二种失败形状。 */
  private static DecisionPackages.RejectionException reject(String code, String message) {
    return new DecisionPackages.RejectionException(code, message);
  }

  private static DecisionPackages.RejectionException unknownIndex(
      DecisionPackage pkg, String action, int index) {
    return reject(
        DecisionPackages.INVALID_ITEM_INDEX,
        action
            + " 引用了包内不存在的条目 index="
            + index
            + "（包 "
            + pkg.id()
            + " 共 "
            + pkg.items().size()
            + " 条）");
  }
}
