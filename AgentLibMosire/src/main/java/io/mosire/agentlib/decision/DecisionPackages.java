package io.mosire.agentlib.decision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 决策包登记表（设计 §5.8 冻结形状 + RB2c 按 §5.12 裁定 B 追加的具名作废口）：内存态、线程安全，七个动作 {@code propose / decide / commit
 * / find / pending / expire / expireNow}。
 *
 * <p><b>为什么不落库</b>（与 {@link io.mosire.agentlib.approval.PendingApprovals} 同口径）：包是
 * "当下这批调用的一次决策"，提议者与确认者都活在<b>当前进程</b>里——进程退出即失效是<b>对的</b>； 跨重启继承一个"上次批过"的包是错的。事件的留痕（{@code
 * decision.package.*}）与 HTTP 面属于 RB3， 本表只管内存态这一层。
 *
 * <p><b>v1 边界（如实记）</b>：本表<b>不含</b>审批面投递（把条目投进 {@code PendingApprovals}/闸链/通道 = RB2）、 <b>不含</b> HTTP
 * 与事件面（RB3）、<b>不含</b>提议工具。包在这里只完成「提议 → 统一裁决 → 一次提交 → 一份统一结果」。
 *
 * <h2>八条红线落在哪（设计 §5.8）</h2>
 *
 * <ol>
 *   <li><b>提议即校验、任一条不过整包不建</b>：{@link #propose} 先逐条问 {@link ItemValidator}， 全过才发编号（具名拒 {@code
 *       ITEM_DENIED}）。
 *   <li><b>一实例一个未决包</b>：{@link #propose} 见同一 {@code proposerKey} 已有 {@code PROPOSED} 包 ⇒ 具名拒 {@code
 *       PACKAGE_ALREADY_OPEN}（不覆盖、不排队）。
 *   <li><b>decide 一次终局</b>：只落在 {@code PROPOSED} 上；重复裁决 / 已终局 ⇒ {@code PACKAGE_NOT_PROPOSED}， 到点之后 ⇒
 *       {@code PACKAGE_EXPIRED}——首次裁决<b>永不被覆盖</b>。
 *   <li><b>commit 恰好一次</b>：见 {@link #commit} 的长注释（为什么"只调一次 port"）。
 *   <li><b>被驳条目永不进 calls</b>：{@link #commit} 只把 {@link Decision#approves} 为真的条目做成 {@link
 *       ResolvedCall}；被批子集为空 ⇒ 直接 {@code REJECTED}，<b>根本不碰 port</b>。
 *   <li><b>port 抛异常不得记成 COMMITTED</b>：保持 {@code DECIDED}、不记回执，把失败包成 {@code COMMIT_FAILED}
 *       抛出——不把未知说成成功，也不说成失败。
 *   <li><b>不引入世界类型</b>：{@code revisionRef} 只有不透明字符串（见 {@link CommitReceipt}）。
 *   <li><b>依赖方向</b>：本包只依赖 {@code permission}（{@link io.mosire.agentlib.permission.AccessToken}） 与
 *       JDK/SLF4J，不依赖 {@code brain}/{@code main}（AGENTS §0.1）。
 * </ol>
 *
 * <p><b>本表不做的判定</b>：谁有资格提议、谁有资格做确认者——完全沿用既有审批分流 （{@code AskKind} + 闸链 + 通道，§5.2），由 RB2/RB3
 * 在调用本表之前完成；本表只认"这个包现在是什么状态"。
 *
 * <p><b>线程安全</b>：全部读写走同一把 {@link ReentrantLock}（表小、操作 O(1)），返回的都是不可变快照。 唯一一处"持锁调外部"是 {@link
 * #commit} 里的提交口调用，取舍与代价写在那个方法的注释里。
 *
 * <p><b>不淘汰（已知取舍）</b>：终局包留在表里不清理——{@link #find} 要能回答"那个包后来怎么了"，
 * 重放要能拿回同一份回执（B-4）；淘汰会把"重放"变成"未知包"，把幂等保证推回给调用方。 代价是长驻进程的内存随包数线性增长，需要保留策略时由宿主/RB3 收口（本版不提供隐性上限）。
 */
public final class DecisionPackages {

  private static final Logger LOG = LoggerFactory.getLogger(DecisionPackages.class);

  /** 拒码：同一 {@code proposerKey} 已有未决包（设计 §5.8 红线 2 点名的字面量，不要改）。 */
  public static final String PACKAGE_ALREADY_OPEN = "PACKAGE_ALREADY_OPEN";

  /** 拒码：包 id 未知（已终局的包也查得到，见 {@link #find}——所以这个码只表示"真的没这个 id"）。 */
  public static final String PACKAGE_NOT_FOUND = "PACKAGE_NOT_FOUND";

  /** 拒码：包不在待裁决态（重复裁决、已裁决、已终局）。 */
  public static final String PACKAGE_NOT_PROPOSED = "PACKAGE_NOT_PROPOSED";

  /** 拒码：包还没裁决就想提交。 */
  public static final String PACKAGE_NOT_DECIDED = "PACKAGE_NOT_DECIDED";

  /** 拒码：包已超时（超时包零落盘，不静默执行）。 */
  public static final String PACKAGE_EXPIRED = "PACKAGE_EXPIRED";

  /** 拒码：裁决引用了包内不存在的条目 index（不静默忽略）。 */
  public static final String INVALID_DECISION = "INVALID_DECISION";

  /** 拒码：某条目没过 {@link ItemValidator} ⇒ 整包不建（连包 id 都不发）。 */
  public static final String ITEM_DENIED = "ITEM_DENIED";

  /** 拒码：提交口抛异常或回执违反契约 ⇒ 包保持 {@code DECIDED}（没落定，也没被说成落定）。 */
  public static final String COMMIT_FAILED = "COMMIT_FAILED";

  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, Entry> entries = new LinkedHashMap<>();
  private final AtomicLong minted = new AtomicLong();

  /** 建一张空表（进程内单例由宿主装配决定；本类无静态状态）。 */
  public DecisionPackages() {}

  /**
   * 提议：把一批调用收成一个包，逐条校验通过后发编号。
   *
   * <p>顺序是刻意的：<b>先结构、再逐条校验、再查"有没有未决包"、最后才建包</b>。红线 1 要求 "任一条不过 ⇒
   * 整包不建"，所以校验必须在<b>发编号之前</b>——先建后拆会留下一个已经对外宣告过的 id （事件面/等待面可能已经看见它了）。
   *
   * <p>校验在<b>锁外</b>跑（宿主校验可能碰权限面/资源面，不该占着锁）；"一实例一个未决包"的判定在锁内， 所以并发提议也只会有一个赢家。{@link ItemValidator}
   * 抛异常 ⇒ 包不建、异常上抛（校验不了 ≠ 通过）。
   *
   * <p>到点时刻<b>已经过去</b>的包：建出来就是 {@code EXPIRED}（不让它挂在"待裁决"上骗人）， 本方法返回的就是这个终局态——它是一条正常结果，不是异常。
   *
   * @return 新建包的快照（通常 {@code PROPOSED}；deadline 已过 ⇒ {@code EXPIRED}）
   * @throws RejectionException {@code ITEM_DENIED}（某条没过校验；整包不建）、 {@code PACKAGE_ALREADY_OPEN}（同
   *     proposerKey 已有未决包）
   */
  public DecisionPackage propose(ProposeRequest request, ItemValidator validator) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(validator, "validator");
    if (request.items().isEmpty()) {
      // 空包没有可裁决的东西，也没有可提交的子集：它不是"轻量包"，是调用方 bug
      throw new IllegalArgumentException("items 不得为空：一个包至少要有一条调用");
    }
    Set<Integer> indexes = new LinkedHashSet<>();
    for (PackageItem item : request.items()) {
      if (!indexes.add(item.index())) {
        throw new IllegalArgumentException(
            "items 的 index 必须互不相同（它是条目在包内的身份）：重复 index=" + item.index());
      }
    }
    // 红线 1：逐条校验，任一条不过 ⇒ 整包不建（连包 id 都不发）
    for (PackageItem item : request.items()) {
      Optional<String> denied =
          Objects.requireNonNull(
              validator.deny(item.toolName(), item.args()),
              "ItemValidator.deny 必须返回非 null（Optional.empty() = 放行）");
      if (denied.isPresent()) {
        throw new RejectionException(
            ITEM_DENIED,
            "条目未过提议校验，整包不建：index="
                + item.index()
                + " tool="
                + item.toolName()
                + " 理由="
                + denied.get());
      }
    }
    lock.lock();
    try {
      long now = System.currentTimeMillis();
      expireLocked(now);
      // 红线 2：同一实例同一时刻最多一个未决包——再提是具名拒，不是覆盖，也不是排队
      for (Entry candidate : entries.values()) {
        DecisionPackage open = candidate.current;
        if (open.state() == PackageState.PROPOSED
            && open.proposerKey().equals(request.proposerKey())) {
          throw new RejectionException(
              PACKAGE_ALREADY_OPEN,
              "proposerKey="
                  + request.proposerKey()
                  + " 已有未决包 "
                  + open.id()
                  + "（一个实例同一时刻最多一个未决包：先裁它，或等它超时）");
        }
      }
      DecisionPackage created =
          new DecisionPackage(
              nextId(),
              request.proposerKey(),
              request.caller(),
              request.items(),
              now,
              request.deadlineEpochMs(),
              PackageState.PROPOSED,
              null);
      Entry entry = new Entry(created);
      entries.put(created.id(), entry);
      if (now >= created.deadlineEpochMs()) {
        entry.current = asExpired(created);
      }
      LOG.debug(
          "决策包登记 id={} proposerKey={} caller={} items={} deadlineEpochMs={} state={}",
          entry.current.id(),
          entry.current.proposerKey(),
          entry.current.caller(),
          entry.current.items().size(),
          entry.current.deadlineEpochMs(),
          entry.current.state());
      return entry.current;
    } finally {
      lock.unlock();
    }
  }

  /**
   * 统一裁决：<b>整包一次裁、逐条例外</b>（不在 {@link Decision#approvedIndexes()} 里 = 被驳）。
   *
   * <p><b>一次终局</b>（红线 3）：只落在 {@code PROPOSED} 上，首次裁决<b>永不被覆盖</b>—— 重复 decide
   * 不是"改主意"的入口，改主意要走新的包。到点之后再裁 ⇒ 包已被惰性置为 {@code EXPIRED} ⇒ 具名拒（超时不静默执行，§5.6）。
   *
   * <p><b>裁决不许引用包外条目</b>：{@code approvedIndexes} 里出现包内不存在的 index ⇒ 整条裁决拒 （{@code
   * INVALID_DECISION}），不静默忽略——忽略会让"我批了第 7 条"变成"你以为你批了"，而第 7 条 根本不存在。反过来，<b>一条都不批是合法裁决</b>：包在 {@link
   * #commit} 时以 {@code REJECTED} 终局、零落盘。
   *
   * @return 裁决后的包快照（{@code state=DECIDED}，{@code decision} 保留）
   * @throws RejectionException {@code PACKAGE_NOT_FOUND} / {@code PACKAGE_EXPIRED} / {@code
   *     PACKAGE_NOT_PROPOSED} / {@code INVALID_DECISION}
   */
  public DecisionPackage decide(String packageId, Decision decision) {
    Objects.requireNonNull(packageId, "packageId");
    Objects.requireNonNull(decision, "decision");
    lock.lock();
    try {
      expireLocked(System.currentTimeMillis());
      Entry entry = require(packageId);
      DecisionPackage current = entry.current;
      if (current.state() != PackageState.PROPOSED) {
        throw decideRefusal(current);
      }
      Set<Integer> known = new LinkedHashSet<>();
      for (PackageItem item : current.items()) {
        known.add(item.index());
      }
      List<Integer> unknown =
          decision.approvedIndexes().stream()
              .filter(index -> !known.contains(index))
              .sorted()
              .toList();
      if (!unknown.isEmpty()) {
        throw new RejectionException(
            INVALID_DECISION,
            "裁决引用了包内不存在的条目 index="
                + unknown
                + "（包 "
                + current.id()
                + " 共 "
                + current.items().size()
                + " 条）");
      }
      entry.current = withState(current, PackageState.DECIDED, decision);
      LOG.debug(
          "决策包裁决 id={} by={} approved={} rejected={}",
          current.id(),
          decision.by(),
          decision.approvedIndexes().size(),
          current.items().size() - decision.approvedIndexes().size());
      return entry.current;
    } finally {
      lock.unlock();
    }
  }

  /**
   * 提交：把<b>被批子集</b>当<b>一次</b>提交交给 {@link DecisionCommitPort}，返回<b>一份</b>统一结果。
   *
   * <p><b>为什么 port 恰好只调一次</b>（红线 4 / B-1）：原子性的作用域 = 被批子集（U6）。N 条被批的调用必须 落成<b>一条</b>
   * revision，宿主才能把它们当同一个命令处理；把 N 条拆成 N 次调用，就把"整批要么全落 要么全不落"降级成"N 次各自的成败"——那正是隔壁 #1/#2
   * 两笔出血的形状。所以：{@code DECIDED} 且被批子集 非空 ⇒ 调一次；拿到回执后<b>记账</b>（{@link Entry#receipt}），此后同一包再 commit
   * <b>不再调 port</b>、 直接返回同一份回执（B-4：同包重放不重复落盘）。
   *
   * <p><b>为什么被驳条目不进 calls</b>（红线 5 / B-2）：被驳 = 零落盘。把被驳条目也交给 port，
   * 就得指望宿主"记得别落它们"——宿主漏看一个标志位，被驳的条目就落盘了，而包层还以为它被拒了。 从<b>数据结构</b>上让被驳条目到不了 port（它们没有对应的 {@link
   * ResolvedCall}）才是 fail-closed。 若一条都没批：直接 {@code REJECTED}，<b>根本不碰 port</b>。
   *
   * <p><b>port 抛异常怎么办</b>（红线 6）：<b>不</b>记成 {@code COMMITTED}，也<b>不</b>编一份"未落定"的回执——
   * 宿主可能已经写了才抛（契约违规），把不确定说成"没落"同样是假话。本方法保持 {@code DECIDED}、 不记回执，把失败包成 {@code COMMIT_FAILED}
   * 抛给调用方；之后可以再 commit （提交口的幂等键是 {@code packageId}，见 {@link DecisionCommitPort} 契约第 4 条）。
   *
   * <p><b>持锁调用 port（本类唯一一处）</b>：不持锁就无法保证"同一包不会被两个线程各提交一次"， 而"恰好一次"是 B-1。代价是 port
   * 慢会阻塞本表其它读写——原子性优先于延迟是这个责任区的取舍； 提交口不得从<b>另一个线程</b>回调本实例（会自堵），同线程重入是安全的（可重入锁）。
   *
   * <p><b>回执里的逐条 outcomes</b>：与包内 {@code items()} 一一对应、同序（B-7：批 4 驳 1 ⇒ 5 条各有去向）。 宿主回执的 outcomes 按
   * {@code calls} 顺序对回被批条目；被驳条目的 {@link CommitReceipt.ItemOutcome#REJECTED} 由本层填（设计
   * §5.3）。宿主少报的条目按回执 {@code status} 推导（{@code COMMITTED} ⇒ 该条 COMMITTED，其余 ⇒
   * NOT_COMMITTED）并告警；多报的条目忽略并告警。
   *
   * <p><b>冲突后怎么办</b>：{@code CONFLICTED} 是终局——红线 4 规定 commit 只在 {@code DECIDED} 上调 port， 而冲突后的包已不是
   * {@code DECIDED}，所以同一个包再 commit 只会拿回同一份冲突回执（裁决与冲突摘要都在， 见 {@link
   * #find}）。要重提，由调用方在解决冲突后<b>重新提议</b>（包 id 由本表发放，{@code propose} 不接受外部 id）——"可同 id 重提"的取舍登记在 RB1
   * 报告里，本版以红线 4 为准。
   *
   * @return 统一结果：一份回执，逐条给去向（不出现"部分失败"的第二个错误码，B-7）
   * @throws RejectionException {@code PACKAGE_NOT_FOUND} / {@code PACKAGE_NOT_DECIDED} / {@code
   *     PACKAGE_EXPIRED} / {@code COMMIT_FAILED}
   */
  public CommitReceipt commit(String packageId, DecisionCommitPort port) {
    Objects.requireNonNull(packageId, "packageId");
    Objects.requireNonNull(port, "port");
    lock.lock();
    try {
      expireLocked(System.currentTimeMillis());
      Entry entry = require(packageId);
      DecisionPackage current = entry.current;
      if (entry.receipt != null) {
        // B-4：重放直接返回第一次的回执，不再碰 port——幂等不该由调用方负责
        return entry.receipt;
      }
      if (current.state() != PackageState.DECIDED) {
        throw commitRefusal(current);
      }
      List<ResolvedCall> calls = approvedCalls(current);
      if (calls.isEmpty()) {
        // 红线 5：被批子集为空 ⇒ 直接 REJECTED，根本不调 port
        CommitReceipt receipt =
            new CommitReceipt(
                CommitReceipt.Status.REJECTED,
                null,
                allRejected(current),
                "被批子集为空（全部条目被驳）：零落盘，未调用提交口");
        entry.receipt = receipt;
        entry.current = withState(current, PackageState.REJECTED, current.decision());
        LOG.info(
            "决策包终局：被批子集为空 id={} items={} by={}",
            current.id(),
            current.items().size(),
            current.decision().by());
        return receipt;
      }
      CommitReceipt host;
      try {
        host = port.commit(packageId, calls);
      } catch (RuntimeException failure) {
        // 红线 6：不记成 COMMITTED、不编"未落定"回执。保持 DECIDED（可重试；幂等键在宿主侧兜底）
        LOG.error(
            "决策包提交口抛异常：id={} calls={} —— 保持 DECIDED、不记回执（提交口契约要求「写后不许抛」）",
            current.id(),
            calls.size(),
            failure);
        throw new RejectionException(
            COMMIT_FAILED,
            "提交口抛异常，包保持 DECIDED（未落定，也未报成落定）：id=" + current.id() + " calls=" + calls.size(),
            failure);
      }
      if (host == null) {
        throw new RejectionException(
            COMMIT_FAILED, "提交口返回 null 回执（契约违规），包保持 DECIDED：id=" + current.id());
      }
      CommitReceipt receipt;
      try {
        receipt = merge(current, calls, host);
      } catch (RuntimeException contractViolation) {
        LOG.error("决策包回执违反契约：id={} —— 保持 DECIDED、不记回执", current.id(), contractViolation);
        throw new RejectionException(
            COMMIT_FAILED,
            "提交口回执违反契约（包保持 DECIDED）：id=" + current.id() + "：" + contractViolation.getMessage(),
            contractViolation);
      }
      entry.receipt = receipt;
      entry.current = withState(current, stateOf(receipt.status()), current.decision());
      LOG.info(
          "决策包提交完成 id={} status={} calls={} outcomes={}",
          current.id(),
          receipt.status(),
          calls.size(),
          receipt.outcomes().size());
      return receipt;
    } finally {
      lock.unlock();
    }
  }

  /**
   * 按 id 取包快照；未知 id 返回空。
   *
   * <p><b>终局包也查得到</b>（这与 {@code PendingApprovals#get} 对已决议项的处理同向）：B-4 的 "重放拿同一份回执"、B-7 的"统一结果"、RB3
   * 的列表/详情面都要求包在终局之后仍然可查。
   *
   * <p>已到点的包在这里被<b>惰性</b>置为 {@code EXPIRED}（查得到，且状态是 {@code EXPIRED}）——
   * "过期"不会伪装成"不存在"：前者能解释为什么没落盘，后者只会让人以为 id 打错了。
   */
  public Optional<DecisionPackage> find(String packageId) {
    Objects.requireNonNull(packageId, "packageId");
    lock.lock();
    try {
      expireLocked(System.currentTimeMillis());
      Entry entry = entries.get(packageId);
      return entry == null ? Optional.empty() : Optional.of(entry.current);
    } finally {
      lock.unlock();
    }
  }

  /**
   * 待裁决快照：只有 {@code PROPOSED}（未到点）的包，按登记顺序；已裁决/已终局的包不在其中。
   *
   * <p>与 {@code PendingApprovals#pending()} 同口径：待裁决 = "还没人裁"，不是"包还在"。 已裁但未提交的包（{@code DECIDED}）请用
   * {@link #find}——把它列进"待裁决"会让人以为还要再裁一次。
   */
  public List<DecisionPackage> pending() {
    lock.lock();
    try {
      expireLocked(System.currentTimeMillis());
      List<DecisionPackage> snapshot = new ArrayList<>(entries.size());
      for (Entry entry : entries.values()) {
        if (entry.current.state() == PackageState.PROPOSED) {
          snapshot.add(entry.current);
        }
      }
      return List.copyOf(snapshot);
    } finally {
      lock.unlock();
    }
  }

  /**
   * 扫一遍超时（{@code nowEpochMs >= deadlineEpochMs} 即到点，与 {@code PendingApprovals#prune} 同口径），
   * 把<b>还没人裁</b>的包置为 {@link PackageState#EXPIRED}。
   *
   * <p><b>零落盘是结构性的</b>：本方法没有提交口参数，它<b>不可能</b>触发任何落盘（§5.6 的 "超时 ⇒ 整包 EXPIRED，零落盘"因此不靠自觉）。
   *
   * <p><b>为什么不把已裁决的包也一起过期</b>：裁决一旦作出就该保留可查（§5.6 冲突行的"裁决保留"），
   * 而且冲突之后的重走流程需要那份裁决还在。过期只回答"还没人裁的包，到点就不算了"—— 与审批内核"到点没人答 = 不是放行"同向。
   *
   * <p>{@link #propose}/{@link #decide}/{@link #commit}/{@link #find}/{@link #pending} 在各自入口都做
   * <b>同一次</b>惰性扫描，所以"过期项不在 pending 里、decide/commit 得到具名拒"几处口径一致。
   *
   * @param nowEpochMs 判定用的墙钟毫秒（由调用方注入，便于测试与时钟漂移排查）
   * @return 本次<b>新</b>置为 EXPIRED 的包数（本来就是 EXPIRED 的不重复计数）
   */
  public int expire(long nowEpochMs) {
    lock.lock();
    try {
      return expireLocked(nowEpochMs);
    } finally {
      lock.unlock();
    }
  }

  /**
   * 具名作废口（RB2c，设计 §5.12 裁定 B）：把<b>指定的</b>包当场作废（{@code PROPOSED ⇒ EXPIRED}）。
   *
   * <p><b>它是干什么的</b>：「审批窗口到期而无人答复」与「无可用通道」这两条路上，包已经<b>没人会裁</b>了， 由调用方（审批桥 {@code
   * DecisionApprovalBridge}）具名作废它。作废 = 零落盘、不静默执行——这正是 §5.6 失败语义表的口径： 「决策超时 / 无可用通道 ⇒ 整包 {@code
   * EXPIRED}，零落盘（不静默执行）」。 没有本口时（RB2b 的现状）桥只能"零动作"， 让这个早已无人可批的包继续挂在 {@code PROPOSED} 上。
   *
   * <p><b>它不是「到点惰性过期」的替代品</b>：按 {@code deadlineEpochMs} 扫全表是 {@link #expire(long)} 的事 （那个出口由
   * deadline 决定谁该过期）；本口是调用方<b>已经知道</b>这个包没救了之后的显式动作，与 deadline 无关。 包的 {@code deadlineEpochMs}
   * 仍是<b>上限</b>（§5.12 裁定 B 第 3 条），不是唯一出口。
   *
   * <p><b>只动 {@code PROPOSED}</b>：已裁决/已终局的包带着裁决或回执，作废它们等于销毁证据。非 {@code PROPOSED} ⇒
   * <b>原样返回当前包</b>、不抛、不改写——超时路径与别的作废路径可能都会走到这里，幂等是调用方敢重复调它的前提（不抛， 好让竞争里的第二次调用无害）。
   *
   * <p><b>作废 ⇒ 释放该 {@code proposerKey} 的未决名额</b>：{@link #propose} 的「一实例一个未决包」只数 {@code PROPOSED}
   * （§5.11 第 4 条），所以包一进 {@code EXPIRED}，同一 {@code proposerKey} 立刻就能再提议。这正是本条要修的东西： 审批窗口（缺省
   * 300s）短于包的裁决窗口（缺省 30min，{@code DecisionProposeTool#DEFAULT_DEADLINE_MILLIS}），
   * 不作废的话那个"早已无人可批"的包会白占名额约 25 分钟（§5.12 裁定 B 的问题陈述）。
   *
   * <p><b>不顺带扫别的包</b>（那是 {@link #expire(long)} 的事）：具名口只回答"这个包作废了没有"。
   * <b>零落盘是结构性的</b>：本方法没有提交口参数，<b>不可能</b>触发任何落盘。
   *
   * @param packageId 包 id（{@link #find} 取到的那个）
   * @param nowEpochMs 调用方观测到的墙钟毫秒（只进日志与审计：它既不判定 deadline——那是 {@link #expire(long)} 的口径——也不改动作）
   * @return 作废后的包快照（{@code EXPIRED}）；本来就是其它状态 ⇒ 原样返回当前包快照（幂等、不抛）
   * @throws RejectionException {@code PACKAGE_NOT_FOUND}（表里真的没有这个 id；已终局的包查得到，见 {@link #find}）
   */
  public DecisionPackage expireNow(String packageId, long nowEpochMs) {
    Objects.requireNonNull(packageId, "packageId");
    lock.lock();
    try {
      Entry entry = require(packageId);
      DecisionPackage current = entry.current;
      if (current.state() != PackageState.PROPOSED) {
        LOG.debug(
            "决策包具名作废是空操作（非 PROPOSED，原样返回）id={} state={} nowEpochMs={}",
            current.id(),
            current.state(),
            nowEpochMs);
        return current;
      }
      entry.current = asExpired(current);
      LOG.info(
          "决策包具名作废 id={} proposerKey={} deadlineEpochMs={} nowEpochMs={} —— 零落盘，未决名额已释放",
          current.id(),
          current.proposerKey(),
          current.deadlineEpochMs(),
          nowEpochMs);
      return entry.current;
    } finally {
      lock.unlock();
    }
  }

  /** 惰性过期（调用方持锁）：只动"还没人裁"的包。 */
  private int expireLocked(long now) {
    int expired = 0;
    for (Entry entry : entries.values()) {
      DecisionPackage current = entry.current;
      if (current.state() == PackageState.PROPOSED && now >= current.deadlineEpochMs()) {
        entry.current = asExpired(current);
        expired++;
        LOG.debug(
            "决策包超时 id={} proposerKey={} deadlineEpochMs={} —— 零落盘",
            current.id(),
            current.proposerKey(),
            current.deadlineEpochMs());
      }
    }
    return expired;
  }

  /** 按 id 取登记项；未知 ⇒ 具名拒（调用方持锁）。 */
  private Entry require(String packageId) {
    Entry entry = entries.get(packageId);
    if (entry == null) {
      throw new RejectionException(PACKAGE_NOT_FOUND, "未知的决策包 id=" + packageId);
    }
    return entry;
  }

  /** 不可提交态 ⇒ 具名拒（穷尽 switch：以后新增状态时，编译器会在<b>这里</b>要求表态， 而不是静静地把新状态当成"可以提交"）。 */
  private static RejectionException commitRefusal(DecisionPackage current) {
    return switch (current.state()) {
      case PROPOSED ->
          new RejectionException(
              PACKAGE_NOT_DECIDED, "决策包尚未裁决：id=" + current.id() + " state=PROPOSED——未裁决的包不许提交");
      case EXPIRED ->
          new RejectionException(
              PACKAGE_EXPIRED,
              "决策包已超时（EXPIRED）：id="
                  + current.id()
                  + " deadlineEpochMs="
                  + current.deadlineEpochMs()
                  + "——超时零落盘，不静默执行");
      case DECIDED ->
          throw new IllegalStateException("DECIDED 是可提交态，不该走到这里（包 id=" + current.id() + "）");
      case COMMITTED, REJECTED, CONFLICTED ->
          // 这三个态在本表里必然已有回执（见 commit 的记账点）；没有回执就到不了这里
          throw new IllegalStateException(
              "决策包已终局却没有回执（内部不变量被破坏）：id=" + current.id() + " state=" + current.state());
    };
  }

  /** 不可裁决态 ⇒ 具名拒（穷尽 switch，理由同 {@link #commitRefusal}）。 */
  private static RejectionException decideRefusal(DecisionPackage current) {
    return switch (current.state()) {
      case PROPOSED ->
          throw new IllegalStateException("PROPOSED 是可裁决态，不该走到这里（包 id=" + current.id() + "）");
      case EXPIRED ->
          new RejectionException(
              PACKAGE_EXPIRED,
              "决策包已超时（EXPIRED）：id="
                  + current.id()
                  + " deadlineEpochMs="
                  + current.deadlineEpochMs()
                  + "——超时零落盘，不静默执行");
      case DECIDED, COMMITTED, REJECTED, CONFLICTED ->
          new RejectionException(
              PACKAGE_NOT_PROPOSED,
              "决策包不在待裁决态：id="
                  + current.id()
                  + " state="
                  + current.state()
                  + "（decide 一次终局，重复裁决不覆盖首次）");
    };
  }

  /** 被批子集：只含 {@link Decision#approves} 为真的条目，按包内 {@code items()} 顺序 （红线 5：被驳条目<b>永不</b>进来）。 */
  private static List<ResolvedCall> approvedCalls(DecisionPackage current) {
    List<ResolvedCall> calls = new ArrayList<>();
    for (PackageItem item : current.items()) {
      if (current.decision().approves(item.index())) {
        calls.add(new ResolvedCall(item.index(), item.toolName(), item.args()));
      }
    }
    return List.copyOf(calls);
  }

  /** 全驳回执的逐条去向：与 {@code items()} 同序，每条都是"被驳"（包层判的，零落盘）。 */
  private static List<CommitReceipt.ItemOutcome> allRejected(DecisionPackage current) {
    List<CommitReceipt.ItemOutcome> outcomes = new ArrayList<>(current.items().size());
    for (int i = 0; i < current.items().size(); i++) {
      outcomes.add(CommitReceipt.ItemOutcome.REJECTED);
    }
    return List.copyOf(outcomes);
  }

  /**
   * 把宿主回执（按 {@code calls} 顺序）与"被驳条目"合成<b>一份对整包</b>的回执： 逐条一一对应、与 {@code items()} 同序、长度 = 包内条目数。
   *
   * <p>宿主少报的条目按整批 {@code status} 推导（被批子集是原子的：{@code COMMITTED} ⇒ 整批落定， 其余 ⇒ 整批未落）——这是 B-2
   * 的读法，不是猜测；但少报会让"逐条去向"退化，所以告警。 多报/自相矛盾（{@code status=COMMITTED} 却报某条
   * NOT_COMMITTED）都告警但以宿主的逐条结论为准： 世界侧才是权威，包层不替它改口。
   */
  private static CommitReceipt merge(
      DecisionPackage current, List<ResolvedCall> calls, CommitReceipt host) {
    List<CommitReceipt.ItemOutcome> hostOutcomes = host.outcomes();
    if (hostOutcomes.size() > calls.size()) {
      LOG.warn(
          "提交口回执 outcomes 多于 calls：id={} calls={} outcomes={}（多出的被忽略——包层只认包内条目）",
          current.id(),
          calls.size(),
          hostOutcomes.size());
    }
    Map<Integer, CommitReceipt.ItemOutcome> byIndex = new LinkedHashMap<>();
    for (int i = 0; i < calls.size(); i++) {
      CommitReceipt.ItemOutcome outcome;
      if (i < hostOutcomes.size()) {
        outcome = hostOutcomes.get(i);
      } else {
        outcome =
            host.status() == CommitReceipt.Status.COMMITTED
                ? CommitReceipt.ItemOutcome.COMMITTED
                : CommitReceipt.ItemOutcome.NOT_COMMITTED;
        LOG.warn(
            "提交口回执缺第 {} 条的 outcome：id={} status={} ⇒ 按 status 推导为 {}（宿主应逐条给全）",
            i,
            current.id(),
            host.status(),
            outcome);
      }
      if (host.status() == CommitReceipt.Status.COMMITTED
          && outcome == CommitReceipt.ItemOutcome.NOT_COMMITTED) {
        LOG.warn(
            "提交口回执自相矛盾：id={} status=COMMITTED 但 index={} 报 NOT_COMMITTED（原子性被破坏？）",
            current.id(),
            calls.get(i).index());
      }
      byIndex.put(calls.get(i).index(), outcome);
    }
    List<CommitReceipt.ItemOutcome> outcomes = new ArrayList<>(current.items().size());
    for (PackageItem item : current.items()) {
      CommitReceipt.ItemOutcome outcome = byIndex.get(item.index());
      outcomes.add(outcome == null ? CommitReceipt.ItemOutcome.REJECTED : outcome);
    }
    return new CommitReceipt(host.status(), host.revisionRef(), outcomes, host.reason());
  }

  /** 回执三结局 ⇒ 包状态（穷尽 switch：新增结局时编译器会提醒）。 */
  private static PackageState stateOf(CommitReceipt.Status status) {
    return switch (status) {
      case COMMITTED -> PackageState.COMMITTED;
      case REJECTED -> PackageState.REJECTED;
      case CONFLICTED -> PackageState.CONFLICTED;
    };
  }

  /**
   * 换状态（逐字段拷贝，与 {@code PendingApprovals#withId} 同一用意）：新增字段时编译器会在<b>这里</b>
   * 提醒——漏拷一个字段不会报错，只会表现为"某个面看到的包少了身份"。
   */
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

  /** 置为超时终局（零落盘：不带裁决，也不带回执）。 */
  private static DecisionPackage asExpired(DecisionPackage current) {
    return withState(current, PackageState.EXPIRED, null);
  }

  private String nextId() {
    // 包 id 会进事件/HTTP/回执：不得含明文参数或密钥（密钥纪律），序号足够
    return "dp-" + minted.incrementAndGet();
  }

  /** 登记项：包的当刻版本 + 已落定回执（{@code null} = 还没落定）。全部字段只在持锁时读写。 */
  private static final class Entry {

    private DecisionPackage current;
    private CommitReceipt receipt;

    Entry(DecisionPackage initial) {
      this.current = initial;
    }
  }

  /**
   * 具名拒（fail-closed 的响亮形态）：{@link DecisionPackages} 的<b>全部</b>拒绝都带一个稳定的 {@link
   * #code()}（取值见本类常量），调用方据此分流（HTTP 状态码 / 工具错误码 / 模型提示）， <b>不要去解析消息文字</b>。
   *
   * <p><b>为什么是异常而不是返回值</b>：与 {@code ResourceDeniedException} 同一理由——"忘了处理"
   * 必须变成响亮的失败，而不是被当成"没有这个包/没有裁决"继续往下走。参数形状错误（null / 空白 / 负 index）仍抛 {@link
   * IllegalArgumentException}；本异常专指<b>状态机与校验面的拒绝</b>。
   *
   * <p><b>消息面向人/模型</b>：它要能据此改路（先裁再提交、换个 proposal、等超时）。 消息里只放 id / 状态 / index /
   * 工具名与校验器给的理由，<b>不放</b>参数明文（密钥纪律）。
   */
  public static final class RejectionException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /** 无因具名拒（{@code PACKAGE_ALREADY_OPEN} / {@code ITEM_DENIED} 这类）。 */
    public RejectionException(String code, String message) {
      super(message);
      this.code = Objects.requireNonNull(code, "code");
    }

    /** 带因具名拒（{@code COMMIT_FAILED} 用：提交口抛异常时把原因一路带出去）。 */
    public RejectionException(String code, String message, Throwable cause) {
      super(message, cause);
      this.code = Objects.requireNonNull(code, "code");
    }

    /** 稳定拒码（见 {@link DecisionPackages} 的常量；不要改已发布的字面量）。 */
    public String code() {
      return code;
    }
  }
}
