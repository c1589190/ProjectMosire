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
 * 决策包登记表（设计 §5.8 冻结形状 + RB2c 按 §5.12 裁定 B 追加的具名作废口 + U1 宿主 id/编辑/快照入口）：内存态、线程安全。旧动作 {@code propose
 * / decide / commit / find / pending / expire / expireNow} 行为不变；U1 新增 {@code propose(packageId,
 * ...) / append / replaceItem / removeItem / decide(expectedVersion) / itemDecided(expectedVersion)
 * / snapshot}，它们走 {@link DecisionPackageTransitions} 纯转移核心（本类只负责注册表、锁与旧内存适配语义）。
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
 *   <li><b>提议即校验、任一条不过整包不建</b>：{@link #propose(ProposeRequest, ItemValidator)} 先逐条问 {@link
 *       ItemValidator}， 全过才发编号（具名拒 {@code ITEM_DENIED}）。★ {@link #propose(ProposeRequest)}
 *       是<b>无校验器</b>的重载（建包方已在校验，见该方法的注释），它逐字复用同一条路径。
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
public final class DecisionPackages implements DecisionPackageReadPort {

  private static final Logger LOG = LoggerFactory.getLogger(DecisionPackages.class);

  /** 拒码：同一 {@code proposerKey} 已有未决包（设计 §5.8 红线 2 点名的字面量，不要改）。 */
  public static final String PACKAGE_ALREADY_OPEN = "PACKAGE_ALREADY_OPEN";

  /** 拒码：包 id 未知（已终局的包也查得到，见 {@link #find}——所以这个码只表示"真的没这个 id"）。 */
  public static final String PACKAGE_NOT_FOUND = "PACKAGE_NOT_FOUND";

  /** 拒码：包不在待裁决态（重复裁决、已裁决、已终局）。 */
  public static final String PACKAGE_NOT_PROPOSED = "PACKAGE_NOT_PROPOSED";

  /**
   * 拒码：包<b>已经进入分段裁决</b>（{@code DECIDING}），整包一次性裁决不再可用。
   *
   * <p><b>为什么不让整包裁决去"推翻"已表态的条目</b>：逐条表态是<b>一次裁决</b>的分段表达（用户裁定 15 ②），
   * 中途来一次整包裁决就必须回答"已表态的那几条还算不算数"——两个答案都会让"这一条到底谁定的"出现两处真相。 所以分段开始之后，路只有两条：继续逐条表态，或等到点（未表态按裁定 14 =
   * 拒绝）。fail-closed 且没有歧义。
   */
  public static final String PARTIAL_DECISION = "PARTIAL_DECISION";

  /**
   * 拒码：包里的条目<b>一条都还没表态</b>，却在不表态就要执行的口上（用户裁定 15）。
   *
   * <p><b>为什么它与 {@link #PACKAGE_NOT_DECIDED} 分开</b>：{@code PACKAGE_NOT_DECIDED} 说的是"这个包还没进入裁决"
   * （整包裁决未回填 / 逐条裁决未开始），本码说的是"逐条裁决<b>已经开始了</b>，但一条批准都没有"。 两者在行为上都是零落盘（fail-closed
   * 同侧），分开是为了让人/模型读到的理由准确：前者要"先裁"，后者要"先批至少一条， 或直说不批"——把后者报成前者，等于说"你没裁过"，而事实是你裁过、且一条没批。
   */
  public static final String NO_ITEM_DECIDED = "NO_ITEM_DECIDED";

  /** 拒码：重复对<b>同一条</b>表态（裁决唯一：一条一次，重复表态不是"改主意"的入口）。 */
  public static final String ITEM_ALREADY_DECIDED = "ITEM_ALREADY_DECIDED";

  /** 拒码：表态引用了包内不存在的条目 index（不静默忽略——同 {@link #INVALID_DECISION} 的方向）。 */
  public static final String INVALID_ITEM_INDEX = "INVALID_ITEM_INDEX";

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

  /**
   * 被驳条目的逐条理由（<b>包层填</b>；设计 §5.3 + 裁定 8 补充 V14）。
   *
   * <p><b>为什么由包层填</b>：裁决只表达"哪几条被批"（{@link Decision#approvedIndexes()}），逐条"为什么被驳"的归属面在包层
   * ——宿主（提交口）只报"落没落"，它根本不认识被驳的条目（被驳条目<b>永不</b>进 {@code calls}）。所以这条理由的文字是包层的一处常量， 不是宿主回执的一部分；{@code
   * null}（未说明理由）留给"确实没有理由可说"的情形（V14 的可空语义），而被驳是一个<b>有原因</b>的去向。
   */
  public static final String REJECTED_ITEM_REASON = "该条未被裁决批准（被驳，零落盘）";

  /**
   * 逐条表态组装出的裁决人标识（用户裁定 15）：{@code Decision.by} 记的是"这一次裁决是谁拍板的"， 而分段表态没有单一的拍板人——每一条各有其人（{@link
   * ItemVerdict#by()}）。 本常量只出现在<b>终局快照</b>里，逐条那一位不会被它盖掉。
   */
  public static final String DECIDED_BY_ITEM_VERDICTS = "item-decisions";

  /**
   * 分段裁决里"没表态"的条目的被驳理由（用户裁定 15 ④ + 裁定 14）：<b>到点 / 显式执行</b>时才把未决条目归成被驳，
   * 且理由要如实说清是"没表态"而不是"被投了反对票"——两者在记录里必须分得开（否则"我没看到那一条"会被读成"我否了它"）。
   */
  public static final String UNSTATED_ITEM_REASON = "分段裁决终结时仍未表态（静默 = 拒绝，零落盘）";

  /**
   * 到点原因：<b>裁决已作出，但没人下执行命令</b>（用户裁定 15 ④）。
   *
   * <p>它与"没人裁"必须分得开：前者是"批了没跑"（记录面要把它单列一档，别让它看不见），后者是"从来没人管"。 两者都零落盘、都不静默执行，但读记录的人要能一眼看出区别。
   */
  public static final String EXPIRED_DECIDED_NOT_EXECUTED =
      "到点作废：裁决已作出但未执行（没有人下执行命令）——被批子集零落盘，不静默执行";

  /** 到点原因：窗口内<b>一条都没裁</b>（{@code PROPOSED}）——与"批了没跑"是两件事。 */
  public static final String EXPIRED_NO_DECISION = "到点作废：窗口内没有任何裁决（一条都没裁）——零落盘";

  /**
   * 具名作废原因：<b>分段裁到一半</b>就没人会接着答了（提出者被回收、审批窗口先到点、无可用通道）。
   *
   * <p>它与前两条的区别是"<b>作废不是裁决</b>"：没表态的条目<b>不</b>被写成驳回（{@link #UNSTATED_ITEM_REASON} 只用在 "终结成
   * DECIDED"那两条路上），历史里读到的是"这个包没裁完就被作废了"。
   */
  public static final String EXPIRED_DECIDING_VOIDED =
      "具名作废：分段裁决进行中而裁决者不再答复（提出者被回收 / 审批窗口先到点 / 无可用通道）——零落盘，未表态的条目不算被驳";

  /**
   * 包缺省的裁决窗口（毫秒）：{@code deadline = now + 这个值}。30 分钟——够人看一眼，也不让包挂到天荒地老。
   *
   * <p><b>为什么这个常量住在包层</b>：它描述的是<b>包</b>能等多久（设计 §5 裁定 10：「用包的」），不是某个工具的参数。 显式提议口 {@code
   * decision_propose} 移除后，建包方（P1 的待裁决队列 / 回合收尾）与审批桥都以本常量为唯一出处， 不再从工具类借常量。
   */
  public static final long DEFAULT_DEADLINE_MILLIS = 30L * 60L * 1000L;

  /**
   * 拒码（U1）：包内容已冻结——append / replaceItem / removeItem 只允许在 {@code PROPOSED} 且<b>还没有任何逐条表态</b>时进行。
   *
   * <p>首次 {@code itemDecided} 之后包进入 {@code DECIDING}，此后内容不再可改：允许编辑就会让"这一条到底被裁的是什么"出现两处真相。
   */
  public static final String PACKAGE_CONTENT_FROZEN = "PACKAGE_CONTENT_FROZEN";

  /**
   * 拒码（U1）：内容版本不匹配（"看旧包、批新包"的防线）。
   *
   * <p>不带版本的旧签名（{@link #decide(String, Decision)} / {@link #itemDecided(String, int, boolean,
   * String, String)}）按当前版本执行， 仍兼容 Brain；带版本的新入口在版本不匹配时零状态变更地拒绝。
   */
  public static final String PACKAGE_VERSION_STALE = "PACKAGE_VERSION_STALE";

  /** 拒码（U1）：removeItem 不得把包删空（空包没有可裁决/可提交的东西）。 */
  public static final String PACKAGE_EMPTY = "PACKAGE_EMPTY";

  /** 拒码（U1）：宿主给的包 id 已被登记过（同一 id 不覆盖；id 是幂等键与对账键）。 */
  public static final String PACKAGE_ALREADY_EXISTS = "PACKAGE_ALREADY_EXISTS";

  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  /**
   * 包 id ⇒ <b>最近一次到点</b>的原因（{@link #expiryReason} 的落点；用户裁定 15 ④）。
   *
   * <p>只增不删：包表本身也不淘汰终局包（见类注释），两者同寿命。值只有 {@link #EXPIRED_DECIDED_NOT_EXECUTED} 与 {@link
   * #EXPIRED_NO_DECISION} 两种，都不是明文（密钥纪律：它们是固定文案，不含参数）。
   */
  private final Map<String, String> expiredReasons = new LinkedHashMap<>();

  private final AtomicLong minted = new AtomicLong();

  /** 建一张空表（进程内单例由宿主装配决定；本类无静态状态）。 */
  public DecisionPackages() {}

  /**
   * 提议（<b>无校验器</b>）：把一批调用收成一个包并发编号——建包方已在校验，包层不再问第二个口。
   *
   * <p><b>为什么有这个重载</b>（设计 §5 裁定 1/6）：进包口径改成"工具符合条件就自动进包"之后，条目的结构与权限校验发生在 <b>命中那一刻的调用链上</b>（P1
   * 的待裁决队列，那里本来就已经过了闸链判定）——校验责任不在包层，也没有第二个校验时机。
   * 于是建包方不该被迫构造一个"永远放行"的校验器来表达"我这边已经判过了"（那会让"谁在校验"这件事看起来有两处）。
   *
   * <p><b>与带校验器的重载的关系</b>：本重载<b>逐字复用</b>同一条建包路径（{@link ItemValidator} 恒放行），所以 "一实例一个未决包"、index
   * 唯一性、空包拒绝、到点即 {@code EXPIRED} 这些不变量两处完全一致——不是第二份实现。 带校验器的重载保留原签名不变（§5.8 冻结形状；仍有调用方），两者并存不是两条真相。
   *
   * @return 新建包的快照（通常 {@code PROPOSED}；deadline 已过 ⇒ {@code EXPIRED}）
   * @throws RejectionException {@code PACKAGE_ALREADY_OPEN}（同 proposerKey 已有未决包）
   */
  public DecisionPackage propose(ProposeRequest request) {
    return propose(request, (toolName, args) -> Optional.empty());
  }

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
   * 用<b>宿主给的 id</b>提议（U1，契约 §6 新增重载）：登记进同一张包表，但不发 {@code dp-N}。
   *
   * <p><b>为什么保留旧 {@link #propose(ProposeRequest)}</b>：Brain 的默认内存适配器仍要 {@code dp-N} 与系统时钟；旧签名与旧行为
   * 一字不改。本重载是"宿主想自己命名包"的入口——id 由宿主给（不含明文参数/密钥是宿主责任），其余形状校验与旧入口同口径。
   *
   * <p><b>同 id 已存在 ⇒ {@code PACKAGE_ALREADY_EXISTS}</b>，不覆盖：id 是幂等键与对账键，覆盖会让旧回执对上一个新包。
   * <b>"一实例一个未决包"照旧生效</b>：本类仍是"一实例一未决包"的内存适配器，宿主若要别的粒度应自带持久化表。
   *
   * <p>到点时刻已过 ⇒ 返回 {@code EXPIRED}（与旧入口"出生即过期"一致），并记下到点原因供 {@link #snapshot(String)} 读出。
   *
   * @param packageId 宿主给的包 id（非空白）
   * @param request 提议请求（条目至少一条、index 互不相同）
   * @return 新建包快照（通常 {@code PROPOSED}；deadline 已过 ⇒ {@code EXPIRED}）
   * @throws RejectionException {@code PACKAGE_ALREADY_EXISTS} / {@code PACKAGE_ALREADY_OPEN}
   */
  public DecisionPackage propose(String packageId, ProposeRequest request) {
    Objects.requireNonNull(packageId, "packageId");
    Objects.requireNonNull(request, "request");
    lock.lock();
    try {
      long now = System.currentTimeMillis();
      expireLocked(now);
      // 先做形状校验（空包/重复 index 在发号之前拒），再判注册表冲突
      DecisionPackageSnapshot candidate =
          DecisionPackageTransitions.propose(packageId, request, now);
      if (entries.containsKey(packageId)) {
        throw new RejectionException(
            PACKAGE_ALREADY_EXISTS, "包 id 已被登记：id=" + packageId + "——同一 id 不得覆盖（id 是幂等键与对账键）");
      }
      for (Entry candidateEntry : entries.values()) {
        DecisionPackage open = candidateEntry.current;
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
      Entry entry = new Entry(candidate.pkg());
      entry.contentVersion = candidate.contentVersion();
      entry.snapshotVerdicts.addAll(candidate.verdicts());
      entries.put(packageId, entry);
      if (candidate.expiryReason() != null) {
        expiredReasons.put(packageId, candidate.expiryReason());
      }
      LOG.debug(
          "决策包登记（宿主 id） id={} proposerKey={} caller={} items={} deadlineEpochMs={} state={}",
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
   * 追加条目（U1，契约 §6 新增重载）：走纯转移核心，只允许 {@code PROPOSED} 且尚无逐条表态的包。
   *
   * <p>index 只增、允许不连续；remove 留下的洞不会被填上。首次 {@code itemDecided} 之后 ⇒ {@code PACKAGE_CONTENT_FROZEN}。
   *
   * @param packageId 包 id
   * @param items 新条目（非空；index 严格递增且大于当前最大值）
   * @return 追加后的包快照
   * @throws RejectionException {@code PACKAGE_NOT_FOUND} / {@code PACKAGE_CONTENT_FROZEN} / {@code
   *     INVALID_ITEM_INDEX} / {@code PACKAGE_EXPIRED}
   */
  public DecisionPackage append(String packageId, List<PackageItem> items) {
    Objects.requireNonNull(packageId, "packageId");
    lock.lock();
    try {
      long now = System.currentTimeMillis();
      expireLocked(now);
      Entry entry = require(packageId);
      DecisionPackageSnapshot next =
          DecisionPackageTransitions.append(snapshotOf(entry), items, entry.contentVersion, now);
      applySnapshot(entry, next);
      LOG.debug(
          "决策包追加条目 id={} items={} contentVersion={}",
          entry.current.id(),
          entry.current.items().size(),
          entry.contentVersion);
      return entry.current;
    } finally {
      lock.unlock();
    }
  }

  /**
   * 原位替换条目的内容（U1，契约 §6 新增重载）：必须保持同一 index，且只能改尚未开始逐条表态的包。
   *
   * @param packageId 包 id
   * @param index 目标条目的稳定身份
   * @param replacement 新条目（{@code replacement.index() == index}）
   * @return 替换后的包快照
   * @throws RejectionException {@code PACKAGE_NOT_FOUND} / {@code PACKAGE_CONTENT_FROZEN} / {@code
   *     INVALID_ITEM_INDEX} / {@code PACKAGE_EXPIRED}
   */
  public DecisionPackage replaceItem(String packageId, int index, PackageItem replacement) {
    Objects.requireNonNull(packageId, "packageId");
    lock.lock();
    try {
      long now = System.currentTimeMillis();
      expireLocked(now);
      Entry entry = require(packageId);
      DecisionPackageSnapshot next =
          DecisionPackageTransitions.replaceItem(
              snapshotOf(entry), index, replacement, entry.contentVersion, now);
      applySnapshot(entry, next);
      LOG.debug(
          "决策包替换条目 id={} index={} contentVersion={}",
          entry.current.id(),
          index,
          entry.contentVersion);
      return entry.current;
    } finally {
      lock.unlock();
    }
  }

  /**
   * 删除条目（U1，契约 §6 新增重载）：不重排剩余 index（留洞）；不得删空包。
   *
   * @param packageId 包 id
   * @param index 目标条目的稳定身份
   * @return 删除后的包快照
   * @throws RejectionException {@code PACKAGE_NOT_FOUND} / {@code PACKAGE_CONTENT_FROZEN} / {@code
   *     INVALID_ITEM_INDEX} / {@code PACKAGE_EMPTY} / {@code PACKAGE_EXPIRED}
   */
  public DecisionPackage removeItem(String packageId, int index) {
    Objects.requireNonNull(packageId, "packageId");
    lock.lock();
    try {
      long now = System.currentTimeMillis();
      expireLocked(now);
      Entry entry = require(packageId);
      DecisionPackageSnapshot next =
          DecisionPackageTransitions.removeItem(
              snapshotOf(entry), index, entry.contentVersion, now);
      applySnapshot(entry, next);
      LOG.debug(
          "决策包删除条目 id={} index={} items={} contentVersion={}",
          entry.current.id(),
          index,
          entry.current.items().size(),
          entry.contentVersion);
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
      entry.contentVersion++;
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
   * 带<b>预期内容版本</b>的整包裁决（U1，契约 §6 新增重载）：走纯转移核心，版本不匹配 ⇒ {@code PACKAGE_VERSION_STALE}、零状态变更。
   *
   * <p>旧 {@link #decide(String, Decision)} 保留并按当前版本执行（Brain 兼容）；本重载给宿主提供"看旧包、批新包"的防线。
   *
   * @param packageId 包 id
   * @param expectedContentVersion 调用方看到的 {@link DecisionPackageSnapshot#contentVersion()}
   * @param decision 统一裁决
   * @return 裁决后的包快照（{@code DECIDED}；被批子集为空 ⇒ {@code REJECTED}）
   * @throws RejectionException {@code PACKAGE_NOT_FOUND} / {@code PACKAGE_VERSION_STALE} / {@code
   *     PACKAGE_EXPIRED} / {@code PACKAGE_NOT_PROPOSED} / {@code PARTIAL_DECISION} / {@code
   *     INVALID_DECISION}
   */
  public DecisionPackage decide(String packageId, long expectedContentVersion, Decision decision) {
    Objects.requireNonNull(packageId, "packageId");
    Objects.requireNonNull(decision, "decision");
    lock.lock();
    try {
      long now = System.currentTimeMillis();
      expireLocked(now);
      Entry entry = require(packageId);
      DecisionPackageSnapshot next =
          DecisionPackageTransitions.decide(
              snapshotOf(entry), expectedContentVersion, decision, now);
      applySnapshot(entry, next);
      // 整包裁决已经覆盖全部条目：旧的逐条累积 map 不再需要（快照累计在 applySnapshot 里已冻结）
      entry.verdicts.clear();
      LOG.debug(
          "决策包裁决（带版本） id={} expected={} by={} approved={} state={}",
          next.pkg().id(),
          expectedContentVersion,
          decision.by(),
          decision.approvedIndexes().size(),
          next.pkg().state());
      return next.pkg();
    } finally {
      lock.unlock();
    }
  }

  /**
   * 条目级表态（用户裁定 15 ②⑤：用户口中的"event 命令看一条、裁一条"）。
   *
   * <p><b>它为什么不是第二条决策路径</b>：它<b>不建包</b>、不投递、不执行——只把"这一条怎么定"记进<b>这个包</b>的累积里。
   * 逐条表态是<b>一次裁决</b>的分段表达（用户原话「裁决是自动归进裁决包」）：包始终只有一个，状态机也只有这一处写口。
   *
   * <p><b>三种状态迁移</b>（其余一律具名拒）：
   *
   * <ol>
   *   <li>{@code PROPOSED} + 本条表态 ⇒ {@code DECIDING}（裁决累积中）；
   *   <li>{@code DECIDING} + 本条表态 ⇒ 还有条目没表态就留在 {@code DECIDING}，全部表态完 ⇒ {@code DECIDED}；
   *   <li>{@code DECIDED} 及一切终局 ⇒ 拒（{@link #PACKAGE_NOT_PROPOSED}）：裁决唯一，重复表态不是"改主意"的入口。
   * </ol>
   *
   * <p><b>★ 未表态 ≠ 拒绝</b>（用户裁定 15 ④）：本方法<b>绝不</b>把没表态的条目写进"被驳"——{@link
   * ItemDecisionResult#undecidedCount()} 如实报还剩几条。把它们归成被驳只发生在两个显式时刻：{@link #commit}（人下执行命令）与 {@link
   * #expire(long)}（到点）。
   *
   * <p><b>为什么不做"累加式追认"的第二次表态</b>：一条条目只有一次表态（{@link #ITEM_ALREADY_DECIDED}）。允许改口就要回答
   * "以哪一次为准"，而两次表态之间的那个"已生效但被推翻"的状态没有落点——本版把它挡在门外，fail-closed。
   *
   * @param packageId 包 id
   * @param index 条目在包内的身份
   * @param approve {@code true} = 批准这一条，{@code false} = 驳回这一条
   * @param by 表态人（空白 ⇒ 拒：查不出表态人的记录等于没有审计面）
   * @param note 可空理由
   * @return 表态后的包快照 + 已表态条目 + 是否已终结 + 还剩几条未表态
   * @throws RejectionException {@code PACKAGE_NOT_FOUND} / {@code PACKAGE_EXPIRED} / {@code
   *     PACKAGE_NOT_PROPOSED} / {@code INVALID_ITEM_INDEX} / {@code ITEM_ALREADY_DECIDED}
   */
  public ItemDecisionResult itemDecided(
      String packageId, int index, boolean approve, String by, String note) {
    Objects.requireNonNull(packageId, "packageId");
    Objects.requireNonNull(by, "by");
    ItemVerdict verdict = new ItemVerdict(index, approve, by, note);
    lock.lock();
    try {
      expireLocked(System.currentTimeMillis());
      Entry entry = require(packageId);
      DecisionPackage current = entry.current;
      if (current.state() != PackageState.PROPOSED && current.state() != PackageState.DECIDING) {
        throw itemDecideRefusal(current);
      }
      if (!knownIndexes(current).contains(index)) {
        throw new RejectionException(
            INVALID_ITEM_INDEX,
            "表态引用了包内不存在的条目 index="
                + index
                + "（包 "
                + current.id()
                + " 共 "
                + current.items().size()
                + " 条）");
      }
      if (entry.verdicts.containsKey(index)) {
        throw new RejectionException(
            ITEM_ALREADY_DECIDED,
            "条目 index=" + index + " 已经表过态（裁决唯一：一条一次，重复表态不是改主意）包=" + current.id());
      }
      entry.recordVerdict(verdict);
      entry.current =
          withState(current, PackageState.DECIDING, accumulated(current, entry.verdicts));
      boolean finalized = false;
      int undecided = undecidedCount(current, entry.verdicts);
      if (undecided == 0) {
        // 全部条目都已表态 ⇒ 包自动进 DECIDED（用户裁定 15 ③）。注：分段表态没有"填未表态"这回事，所以理由位留空
        finalized = finalizeLocked(entry, "");
      }
      LOG.debug(
          "决策包条目级表态 id={} index={} approve={} by={} 已表态={} 未表态={} state={}",
          current.id(),
          index,
          approve,
          by,
          entry.verdicts.size(),
          undecided,
          entry.current.state());
      return new ItemDecisionResult(
          entry.current, verdictsOf(current, entry.verdicts), finalized, undecided);
    } finally {
      lock.unlock();
    }
  }

  /**
   * 带<b>预期内容版本</b>的条目级表态（U1）：走纯转移核心，返回完整快照（含新 contentVersion）。
   *
   * <p>旧 {@link #itemDecided(String, int, boolean, String, String)} 保留并返回 {@link
   * ItemDecisionResult}；本重载返回 {@link DecisionPackageSnapshot}，因为它额外的价值正是"表态同时拿到新版本"，宿主可据此继续做乐观并发。
   *
   * @param packageId 包 id
   * @param expectedContentVersion 调用方看到的版本
   * @param index 条目稳定身份
   * @param approve {@code true} = 批准，{@code false} = 驳回
   * @param by 表态人（空白 ⇒ {@link IllegalArgumentException}）
   * @param note 可空理由
   * @return 表态后的完整快照（{@code DECIDING} / {@code DECIDED}；全驳 ⇒ {@code REJECTED}）
   * @throws RejectionException {@code PACKAGE_NOT_FOUND} / {@code PACKAGE_VERSION_STALE} / {@code
   *     PACKAGE_EXPIRED} / {@code PACKAGE_NOT_PROPOSED} / {@code INVALID_ITEM_INDEX} / {@code
   *     ITEM_ALREADY_DECIDED}
   */
  public DecisionPackageSnapshot itemDecided(
      String packageId,
      long expectedContentVersion,
      int index,
      boolean approve,
      String by,
      String note) {
    Objects.requireNonNull(packageId, "packageId");
    lock.lock();
    try {
      long now = System.currentTimeMillis();
      expireLocked(now);
      Entry entry = require(packageId);
      DecisionPackageSnapshot next =
          DecisionPackageTransitions.itemDecided(
              snapshotOf(entry), expectedContentVersion, index, approve, by, note, now);
      applySnapshot(entry, next);
      // 与新入口混用旧入口时，旧的逐条 map 必须看到同一条表态（否则旧入口会允许重复表态）
      entry.verdicts.put(index, new ItemVerdict(index, approve, by, note));
      if (next.pkg().state() != PackageState.DECIDING) {
        entry.verdicts.clear();
      }
      LOG.debug(
          "决策包条目级表态（带版本） id={} index={} approve={} state={} contentVersion={}",
          next.pkg().id(),
          index,
          approve,
          next.pkg().state(),
          next.contentVersion());
      return next;
    } finally {
      lock.unlock();
    }
  }

  /**
   * 显式执行被批子集（用户裁定 15 ③：用户口中的"裁决包执行命令"）。
   *
   * <p><b>与"裁决即执行"的分界</b>：本方法<b>只</b>在有人下执行命令（HTTP {@code POST /{id}/commit} / 工具 {@code
   * commit_decision_package}）时被调到。裁决回填（{@link #decide}）与逐条表态（{@link #itemDecided}）都<b>不</b>调它——
   * 这就是用户要"两条命令分开"的直接含义。
   *
   * <p><b>可执行的两种态</b>：
   *
   * <ul>
   *   <li>{@code DECIDED}：照旧（被批子集恰好一次提交，B-1/B-2 一个字不变）；
   *   <li>{@code DECIDING}：<b>显式执行命令即"就这些了"</b>——没表态的条目按用户裁定 14「静默 = 拒绝」归成被驳（零落盘，理由用 {@link
   *       #UNSTATED_ITEM_REASON}，如实说明是"没表态"而不是"被投了反对票"），已批准的那些照常提交。 一个批准都没有 ⇒ 具名拒 {@link
   *       #NO_ITEM_DECIDED}，<b>不碰提交口</b>——"执行命令"不是"替人批一条"的入口。
   * </ul>
   *
   * <p>{@code PROPOSED}（一条都没表态）⇒ 拒：没有裁决就没有执行。终局态 ⇒ 照旧拿回同一份回执（{@link #commit} 的幂等，B-4）。
   *
   * @return 统一结果：一份回执，逐条给去向（不出现"部分失败"的第二个错误码，B-7）
   * @throws RejectionException {@code PACKAGE_NOT_FOUND} / {@code PACKAGE_NOT_DECIDED} / {@code
   *     NO_ITEM_DECIDED} / {@code PACKAGE_EXPIRED} / {@code COMMIT_FAILED}
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
      if (current.state() == PackageState.DECIDING) {
        // ★ 用户裁定 15 ③④：显式执行命令 = "就这些了"。没表态的按裁定 14 归成被驳（零落盘），已批准的照常提交。
        //   一个批准都没有 ⇒ 具名拒且不碰 port：执行命令不是"替人批一条"的入口（fail-closed 同侧，但理由说清楚）
        if (entry.verdicts.values().stream().noneMatch(ItemVerdict::approved)) {
          throw new RejectionException(
              NO_ITEM_DECIDED,
              "包里没有一条被批准（已表态 " + entry.verdicts.size() + " 条、全部驳回）⇒ 不执行、零落盘：包 id=" + current.id());
        }
        finalizeLocked(entry, UNSTATED_ITEM_REASON);
        current = entry.current;
        LOG.info(
            "决策包执行命令先终结分段裁决 id={} 未表态按拒绝（零落盘）已批={}",
            current.id(),
            current.decision().approvedIndexes().size());
      }
      if (current.state() != PackageState.DECIDED) {
        throw commitRefusal(current);
      }
      List<ResolvedCall> calls = approvedCalls(current);
      if (calls.isEmpty()) {
        // 红线 5：被批子集为空 ⇒ 直接 REJECTED，根本不调 port
        List<CommitReceipt.ItemOutcome> outcomes = allRejected(current);
        CommitReceipt receipt =
            new CommitReceipt(
                CommitReceipt.Status.REJECTED,
                null,
                outcomes,
                allReasons(outcomes.size(), REJECTED_ITEM_REASON),
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
   * 该包<b>已表态</b>的条目（用户裁定 15 ②⑤的读口）：按包内条目顺序，<b>未表态的条目不在其中</b>。
   *
   * <p>它是给决策面/历史面拼"这一条定了没有、谁定的"用的只读投影：{@code DECIDED} 之后累积已并进裁决，本方法改答"按裁决反推的表态" ——
   * 两个阶段对同一条目给出同一结论，读的人不会看到"刚裁完的包突然没有逐条记录了"。
   *
   * <p>未知 id ⇒ 空列表（{@link #find} 那一路才回答"有没有这个包"）；不抛。
   */
  public List<ItemVerdict> itemVerdicts(String packageId) {
    Objects.requireNonNull(packageId, "packageId");
    lock.lock();
    try {
      expireLocked(System.currentTimeMillis());
      Entry entry = entries.get(packageId);
      if (entry == null) {
        return List.of();
      }
      return verdictsOf(entry.current, entry.verdicts);
    } finally {
      lock.unlock();
    }
  }

  /**
   * 读取端口形状的逐条表态（{@link DecisionPackageReadPort#verdicts(String)}）。
   *
   * <p>旧 {@link #itemVerdicts(String)} 是 Brain 内存适配器的冻结签名，本方法只做同形转换，不改变任何状态机行为。为了与旧读口保持逐字一致，
   * 这里仍复用旧方法的惰性过期兼容口径；{@code DECIDING} 包的旧投影也会按旧适配器的既有规则摊开累积裁决。需要纯累计语义的新宿主应直接使用 {@link
   * DecisionPackageSnapshot#verdicts()}，不要照搬本适配器。
   *
   * @param packageId 包 id（非 null）
   * @return 顶层协议形状的已表态条目；未知 id ⇒ 空表
   */
  @Override
  public List<DecisionItemVerdict> verdicts(String packageId) {
    List<ItemVerdict> legacy = itemVerdicts(packageId);
    if (legacy.isEmpty()) {
      return List.of();
    }
    List<DecisionItemVerdict> converted = new ArrayList<>(legacy.size());
    for (ItemVerdict verdict : legacy) {
      converted.add(
          new DecisionItemVerdict(
              verdict.index(), verdict.approved(), verdict.by(), verdict.note()));
    }
    return List.copyOf(converted);
  }

  /** 单条表态的内存态展开（按包内条目顺序；未表态的条目不出现在结果里）。 */
  private static List<ItemVerdict> verdictsOf(
      DecisionPackage pkg, Map<Integer, ItemVerdict> accumulated) {
    List<ItemVerdict> verdicts = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      ItemVerdict verdict = accumulated.get(item.index());
      if (verdict != null) {
        verdicts.add(verdict);
        continue;
      }
      Decision decision = pkg.decision();
      if (decision == null) {
        // 还在分段中且这一条没表过态 ⇒ 它未决（不编一条"驳回"——未表态不是驳回）
        continue;
      }
      verdicts.add(
          new ItemVerdict(
              item.index(), decision.approves(item.index()), decision.by(), decision.note()));
    }
    return List.copyOf(verdicts);
  }

  /**
   * 分段累积 → 一份裁决快照（用户裁定 15 ①：包内每一条只有三档——已批准 / 已驳回 / 未表态）。
   *
   * <p>累积态下的 {@code approvedIndexes} 只含<b>已批准</b>的条目：没表态的条目既不在批准集合里，也不在任何"被驳"名单里 （"缺席 = 驳回"这条
   * fail-closed 方向只在<b>终结</b>时生效，见 {@link #commit} 与 {@link #expireLocked}）。
   */
  private static Decision accumulated(DecisionPackage current, Map<Integer, ItemVerdict> verdicts) {
    Set<Integer> approved = new LinkedHashSet<>();
    String by = DECIDED_BY_ITEM_VERDICTS;
    long approvedCount = 0;
    String lastNote = "";
    for (PackageItem item : current.items()) {
      ItemVerdict verdict = verdicts.get(item.index());
      if (verdict == null) {
        continue;
      }
      if (verdict.approved()) {
        approved.add(item.index());
        approvedCount++;
      }
      by = verdict.by();
      if (!verdict.note().isBlank()) {
        lastNote = verdict.note();
      }
    }
    StringBuilder summary = new StringBuilder();
    summary
        .append("分段裁决累积中：已表态 ")
        .append(verdicts.size())
        .append('/')
        .append(current.items().size())
        .append(" 条，其中批准 ")
        .append(approvedCount)
        .append(" 条");
    if (!lastNote.isBlank()) {
      summary.append("；最近一条理由：").append(lastNote);
    }
    return new Decision(approved, by, summary.toString());
  }

  /** 未表态的条目数（{@code index → 表态} 里没有的条目数）。 */
  private static int undecidedCount(DecisionPackage pkg, Map<Integer, ItemVerdict> verdicts) {
    int undecided = 0;
    for (PackageItem item : pkg.items()) {
      if (!verdicts.containsKey(item.index())) {
        undecided++;
      }
    }
    return undecided;
  }

  /** 包内全部条目 index（顺序 = 条目顺序）。 */
  private static Set<Integer> knownIndexes(DecisionPackage pkg) {
    Set<Integer> known = new LinkedHashSet<>();
    for (PackageItem item : pkg.items()) {
      known.add(item.index());
    }
    return known;
  }

  /**
   * 把分段累积<b>终结</b>成一份 {@link Decision} 并置 {@code DECIDED}（调用方持锁）。
   *
   * <p>未表态的条目在这里被归成<b>被驳</b>——这是"静默 = 拒绝"（裁定 14）的唯一落点，只在<b>终结</b>时刻发生： ① 全部条目都表了态（{@code
   * finalizeNote} 为空）；② 有人下了执行命令 / 包到点（{@code finalizeNote} = {@link #UNSTATED_ITEM_REASON}，如实记因）。
   *
   * @return 是否<b>本次</b>真的发生了迁移（{@code false} = 调用方的前提不成立，包已不在分段态）
   */
  private boolean finalizeLocked(Entry entry, String finalizeNote) {
    DecisionPackage current = entry.current;
    if (current.state() != PackageState.DECIDING && current.state() != PackageState.PROPOSED) {
      return false;
    }
    Set<Integer> approved = new LinkedHashSet<>();
    for (PackageItem item : current.items()) {
      ItemVerdict verdict = entry.verdicts.get(item.index());
      if (verdict != null && verdict.approved()) {
        approved.add(item.index());
      }
    }
    String note = finalizeNote == null ? "" : finalizeNote;
    Decision decision = new Decision(approved, DECIDED_BY_ITEM_VERDICTS, note);
    entry.current = withState(current, PackageState.DECIDED, decision);
    entry.verdicts.clear();
    LOG.debug(
        "决策包分段裁决终结 id={} approved={} 未表态按拒绝={} 理由={}",
        current.id(),
        approved.size(),
        current.items().size() - approved.size(),
        note.isBlank() ? "（无：全部条目都已表态）" : note);
    return true;
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
   * 完整运行态快照（U1，契约 §6 新增重载）：{@link DecisionPackage} + contentVersion + 逐条累计 + 回执 + 到点原因。
   *
   * <p>这是宿主持久化的入口；未知 id ⇒ {@code Optional.empty()}（与 {@link #find} 同口径）。读口照旧先做一次惰性到点扫描， 所以已到点的包返回的是
   * {@code EXPIRED} 快照，而不是"消失"。
   *
   * <p>没有走宿主 id 新入口、仍由旧方法建的包也读得到：contentVersion 从 1 起、旧 {@code decide}/{@code itemDecided} 成功也会递增；旧
   * {@code itemDecided} 的逐条表态会被累计进快照（终局后也不丢）。
   *
   * @param packageId 包 id
   * @return 完整快照；未知 id ⇒ 空
   */
  public Optional<DecisionPackageSnapshot> snapshot(String packageId) {
    Objects.requireNonNull(packageId, "packageId");
    lock.lock();
    try {
      expireLocked(System.currentTimeMillis());
      Entry entry = entries.get(packageId);
      return entry == null ? Optional.empty() : Optional.of(snapshotOf(entry));
    } finally {
      lock.unlock();
    }
  }

  /**
   * 全表快照（按登记顺序）：终局包也在其中——{@link #find} 的会话式读口。
   *
   * <p><b>为什么决策面列表需要它</b>（用户裁定 15 ④）：{@code pending()} 只列"还没人裁"的包，而"<b>已批未执行</b>"的包 （{@code
   * DECIDED}）既不在 pending、也不在"已落盘"那一档——没有这个方法，决策面就无法把它<b>单列一档</b>， 于是"批了没跑"在人的工作清单上彻底看不见。
   *
   * <p>只读：不改变任何状态（惰性到点扫描照旧先跑一次，到期的一样如实进 {@code EXPIRED}）。
   */
  public List<DecisionPackage> all() {
    lock.lock();
    try {
      expireLocked(System.currentTimeMillis());
      List<DecisionPackage> snapshot = new ArrayList<>(entries.size());
      for (Entry entry : entries.values()) {
        snapshot.add(entry.current);
      }
      return List.copyOf(snapshot);
    } finally {
      lock.unlock();
    }
  }

  /**
   * 该包<b>最近一次到点</b>的原因（{@code Optional.empty()} = 不是到点终局、或本进程还没观测到那次到点）。
   *
   * <p><b>为什么原因要留在包表里</b>（用户裁定 15 ④）：{@link DecisionPackage} 的冻结形状里没有"为什么过期"这个字段，
   * 而记录面必须如实写出「<b>已批未执行</b>」与「<b>没人裁</b>」的区别——两者都零落盘、都不静默执行，但读记录的人要能一眼看出是哪一种。
   * 所以原因留在包表的一张<b>只增</b>小表里：它只服务读口（事件载荷与决策面列表），不参与任何判定——判定永远只看状态机。
   *
   * <p>取值只有三种固定文案（{@link #EXPIRED_DECIDED_NOT_EXECUTED} / {@link #EXPIRED_NO_DECISION} / {@link
   * #EXPIRED_DECIDING_VOIDED}），都不是明文（密钥纪律）。
   */
  public Optional<String> expiryReason(String packageId) {
    Objects.requireNonNull(packageId, "packageId");
    lock.lock();
    try {
      expireLocked(System.currentTimeMillis());
      return Optional.ofNullable(expiredReasons.get(packageId));
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
   * 惰性过期（调用方持锁）：只动"还没人裁完"的包——{@code PROPOSED}（一条都没裁）与 {@code DECIDING}（裁到一半）。
   *
   * <p><b>★ 用户裁定 15 ④：{@code DECIDING} 到点不是"作废了事"</b>——没表态的条目按裁定 14「静默 = 拒绝」归成被驳、
   * 已表态的按表态，包在这一刻<b>终结为 {@code DECIDED}</b>；紧接着 {@code DECIDED} 那一档生效：<b>已批但没人下执行命令</b> ⇒ 当场 {@code
   * EXPIRED} + 如实记因"审批已作出但未执行"，被批子集<b>零落盘</b>。 两条规则在同一次扫描里顺序生效，所以"批了没跑"既不会被悄悄执行，也不会被说成"从没人裁过"。
   *
   * <p><b>零落盘是结构性的</b>：本方法没有提交口参数，它<b>不可能</b>触发任何落盘（§5.6 的 "超时 ⇒ 整包 EXPIRED，零落盘"因此不靠自觉）。
   *
   * <p>{@link #propose}/{@link #decide}/{@link #commit}/{@link #find}/{@link #pending}/{@link
   * #itemDecided} 在各自入口都做 <b>同一次</b>惰性扫描，所以"过期项不在 pending 里、decide/commit 得到具名拒"几处口径一致。
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
   * 具名作废口（RB2c，设计 §5.12 裁定 B）：把<b>指定的</b>包当场作废（{@code PROPOSED}/{@code DECIDING} ⇒ {@code
   * EXPIRED}）。
   *
   * <p><b>它是干什么的</b>：「审批窗口到期而无人答复」与「无可用通道」这两条路上，包已经<b>没人会裁</b>了， 由调用方（审批桥 {@code
   * DecisionApprovalBridge}）具名作废它。作废 = 零落盘、不静默执行——这正是 §5.6 失败语义表的口径： 「决策超时 / 无可用通道 ⇒ 整包 {@code
   * EXPIRED}，零落盘（不静默执行）」。 没有本口时（RB2b 的现状）桥只能"零动作"， 让这个早已无人可批的包继续挂在 {@code PROPOSED} 上。
   *
   * <p><b>它不是「到点惰性过期」的替代品</b>：按 {@code deadlineEpochMs} 扫全表是 {@link #expire(long)} 的事 （那个出口由
   * deadline 决定谁该过期）；本口是调用方<b>已经知道</b>这个包没救了之后的显式动作，与 deadline 无关。 包的 {@code deadlineEpochMs}
   * 仍是<b>上限</b>（§5.12 裁定 B 第 3 条），不是唯一出口。
   *
   * <p><b>只动 {@code PROPOSED} / {@code DECIDING}</b>：已裁决/已终局的包带着裁决或回执，作废它们等于销毁证据。其它状态 ⇒
   * <b>原样返回当前包</b>、不抛、不改写——超时路径与别的作废路径可能都会走到这里，幂等是调用方敢重复调它的前提（不抛， 好让竞争里的第二次调用无害）。 ★ <b>{@code
   * DECIDING} 也在动之内</b>（用户裁定 15）：分段裁到一半而"没人会答"了，包不能挂着——它当场作废（零落盘），
   * 未表态的条目<b>不</b>被写成驳回（作废不是裁决），历史里那条 {@code expired} 事件负责说清原因。
   *
   * <p><b>作废 ⇒ 释放该 {@code proposerKey} 的未决名额</b>：{@link #propose} 的「一实例一个未决包」只数 {@code PROPOSED}
   * （§5.11 第 4 条），所以包一进 {@code EXPIRED}，同一 {@code proposerKey} 立刻就能再提议。这正是本条要修的东西： 审批窗口（缺省
   * 300s）短于包的裁决窗口（缺省 30min，{@link #DEFAULT_DEADLINE_MILLIS}）， 不作废的话那个"早已无人可批"的包会白占名额约 25 分钟（§5.12
   * 裁定 B 的问题陈述）。
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
      if (current.state() != PackageState.PROPOSED && current.state() != PackageState.DECIDING) {
        LOG.debug(
            "决策包具名作废是空操作（非 PROPOSED/DECIDING，原样返回）id={} state={} nowEpochMs={}",
            current.id(),
            current.state(),
            nowEpochMs);
        return current;
      }
      boolean deciding = current.state() == PackageState.DECIDING;
      entry.current = asExpired(current);
      // 具名作废的原因：分段态与非分段态要分得开（前者是"裁到一半没人答/被回收"，后者是"窗口内没人裁"）
      expiredReasons.put(current.id(), deciding ? EXPIRED_DECIDING_VOIDED : EXPIRED_NO_DECISION);
      LOG.info(
          "决策包具名作废 id={} proposerKey={} deadlineEpochMs={} nowEpochMs={} 原状态={} —— 零落盘，未决名额已释放",
          current.id(),
          current.proposerKey(),
          current.deadlineEpochMs(),
          nowEpochMs,
          current.state());
      return entry.current;
    } finally {
      lock.unlock();
    }
  }

  /** 惰性过期（调用方持锁）：只动"还没人裁完"的包（口径见 {@link #expire(long)}）。 */
  private int expireLocked(long now) {
    int expired = 0;
    for (Entry entry : entries.values()) {
      DecisionPackage current = entry.current;
      if (current.state() == PackageState.DECIDING && now >= current.deadlineEpochMs()) {
        // ★ 裁定 15 ④：没表态的按裁定 14 = 拒绝，已表态的按表态 ⇒ 这一刻终结为 DECIDED；
        //   下面 DECIDED 那一档会在同一次扫描里接着把它判成 EXPIRED（已批未执行，零落盘）
        finalizeLocked(entry, UNSTATED_ITEM_REASON);
        current = entry.current;
      }
      if ((current.state() == PackageState.PROPOSED || current.state() == PackageState.DECIDED)
          && now >= current.deadlineEpochMs()) {
        // ★ 裁定 15 ④：DECIDED 到点 = "已批但没人下执行命令" ⇒ EXPIRED + 如实记因 + 被批子集零落盘。
        //   既不悄悄执行一个没人管的批次，也不假装它执行过（回执面不会出现 COMMITTED）
        boolean approvedButNotExecuted = current.state() == PackageState.DECIDED;
        entry.current = asExpired(current);
        expiredReasons.put(
            current.id(),
            approvedButNotExecuted ? EXPIRED_DECIDED_NOT_EXECUTED : EXPIRED_NO_DECISION);
        expired++;
        LOG.debug(
            "决策包超时 id={} proposerKey={} deadlineEpochMs={} 原状态={} —— 零落盘",
            current.id(),
            current.proposerKey(),
            current.deadlineEpochMs(),
            current.state());
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
      case DECIDING ->
          // {@link #commit} 在分段态上先终结再走 DECIDED 那一档；走到这里说明终结没发生（内部不变量被破坏）
          throw new IllegalStateException(
              "DECIDING 应当已被 commit 终结为 DECIDED，不该走到这里（包 id=" + current.id() + "）");
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
      case DECIDING ->
          new RejectionException(
              PARTIAL_DECISION,
              "决策包已进入分段裁决（DECIDING）：id=" + current.id() + "——整包裁决不再可用，请按条目继续表态（或等到点 ⇒ 未表态按拒绝）");
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

  /** 条目级表态不可用的态 ⇒ 具名拒（穷尽 switch，理由同 {@link #commitRefusal}）。 */
  private static RejectionException itemDecideRefusal(DecisionPackage current) {
    return switch (current.state()) {
      case PROPOSED, DECIDING ->
          throw new IllegalStateException(
              "PROPOSED / DECIDING 是可表态态，不该走到这里（包 id=" + current.id() + "）");
      case EXPIRED ->
          new RejectionException(
              PACKAGE_EXPIRED,
              "决策包已超时（EXPIRED）：id="
                  + current.id()
                  + " deadlineEpochMs="
                  + current.deadlineEpochMs()
                  + "——超时零落盘，不再接受表态");
      case DECIDED, COMMITTED, REJECTED, CONFLICTED ->
          new RejectionException(
              PACKAGE_NOT_PROPOSED,
              "决策包已终结，不再接受条目级表态：id="
                  + current.id()
                  + " state="
                  + current.state()
                  + "（裁决唯一：一条一次，重复表态不是改主意）");
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

  /** 等长的理由表（同一条文字重复 {@code count} 次；{@code reason} 为 null 时得到全 null = 未说明理由）。 */
  private static List<java.util.Optional<String>> allReasons(int count, String reason) {
    List<java.util.Optional<String>> reasons = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      reasons.add(java.util.Optional.of(reason));
    }
    return List.copyOf(reasons);
  }

  /**
   * 把宿主回执（按 {@code calls} 顺序）与"被驳条目"合成<b>一份对整包</b>的回执： 逐条一一对应、与 {@code items()} 同序、长度 = 包内条目数。
   *
   * <p>宿主少报的条目按整批 {@code status} 推导（被批子集是原子的：{@code COMMITTED} ⇒ 整批落定， 其余 ⇒ 整批未落）——这是 B-2
   * 的读法，不是猜测；但少报会让"逐条去向"退化，所以告警。 多报/自相矛盾（{@code status=COMMITTED} 却报某条
   * NOT_COMMITTED）都告警但以宿主的逐条结论为准： 世界侧才是权威，包层不替它改口。
   *
   * <p><b>逐条理由（P2b，裁定 8 补充）</b>：宿主报的那几条带<b>它自己给的理由</b>（{@code null} = 未说明理由，包层<b>不</b>替它编）， 被驳的那几条带
   * {@link #REJECTED_ITEM_REASON}（包层填——被驳条目根本没进 {@code calls}，宿主无从表态）。
   */
  private static CommitReceipt merge(
      DecisionPackage current, List<ResolvedCall> calls, CommitReceipt host) {
    List<CommitReceipt.ItemResult> hostResults = host.itemResults();
    if (hostResults.size() > calls.size()) {
      LOG.warn(
          "提交口回执 outcomes 多于 calls：id={} calls={} outcomes={}（多出的被忽略——包层只认包内条目）",
          current.id(),
          calls.size(),
          hostResults.size());
    }
    Map<Integer, CommitReceipt.ItemResult> byIndex = new LinkedHashMap<>();
    for (int i = 0; i < calls.size(); i++) {
      CommitReceipt.ItemOutcome outcome;
      String itemReason;
      if (i < hostResults.size()) {
        outcome = hostResults.get(i).outcome();
        itemReason = hostResults.get(i).reason();
      } else {
        outcome =
            host.status() == CommitReceipt.Status.COMMITTED
                ? CommitReceipt.ItemOutcome.COMMITTED
                : CommitReceipt.ItemOutcome.NOT_COMMITTED;
        // 宿主没逐条给 ⇒ 理由保持"未说明"（不替它编一句话）
        itemReason = null;
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
      byIndex.put(calls.get(i).index(), new CommitReceipt.ItemResult(outcome, itemReason));
    }
    List<CommitReceipt.ItemOutcome> outcomes = new ArrayList<>(current.items().size());
    List<java.util.Optional<String>> reasons = new ArrayList<>(current.items().size());
    for (PackageItem item : current.items()) {
      CommitReceipt.ItemResult result = byIndex.get(item.index());
      if (result == null) {
        // 不在被批子集里 ⇒ 被驳（包层判的，零落盘），理由由包层填
        outcomes.add(CommitReceipt.ItemOutcome.REJECTED);
        reasons.add(java.util.Optional.of(REJECTED_ITEM_REASON));
      } else {
        outcomes.add(result.outcome());
        reasons.add(java.util.Optional.ofNullable(result.reason()));
      }
    }
    return new CommitReceipt(
        host.status(), host.revisionRef(), outcomes, List.copyOf(reasons), host.reason());
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

  /**
   * 登记项 ⇒ 完整快照（调用方持锁）。
   *
   * <p>逐条累计优先用 {@link Entry#snapshotVerdicts}（旧 {@code itemDecided} 也会累计，且终局清 map 时不清它）；
   * 若这个包是整包裁决出来的（没有逐条表态），则按 {@link Decision#approvedIndexes()} 摊成逐条口径——两个阶段对同一条给出同一结论。
   */
  private DecisionPackageSnapshot snapshotOf(Entry entry) {
    List<DecisionItemVerdict> verdicts;
    if (!entry.snapshotVerdicts.isEmpty()) {
      verdicts = List.copyOf(entry.snapshotVerdicts);
    } else {
      verdicts = verdictsFromDecision(entry.current);
    }
    return new DecisionPackageSnapshot(
        entry.current,
        entry.contentVersion,
        verdicts,
        entry.receipt,
        expiredReasons.get(entry.current.id()));
  }

  /** 把一份整包裁决摊成逐条累计（按包内条目顺序；整包裁决 = 每条都有明确去向）。 */
  private static List<DecisionItemVerdict> verdictsFromDecision(DecisionPackage pkg) {
    Decision decision = pkg.decision();
    if (decision == null) {
      return List.of();
    }
    List<DecisionItemVerdict> verdicts = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      verdicts.add(
          new DecisionItemVerdict(
              item.index(), decision.approves(item.index()), decision.by(), decision.note()));
    }
    return List.copyOf(verdicts);
  }

  /** 把纯转移核心返回的新快照写回登记项（调用方持锁；只在纯转移成功后调用）。 */
  private void applySnapshot(Entry entry, DecisionPackageSnapshot next) {
    entry.current = next.pkg();
    entry.contentVersion = next.contentVersion();
    entry.snapshotVerdicts.clear();
    entry.snapshotVerdicts.addAll(next.verdicts());
    entry.receipt = next.receipt();
    if (next.expiryReason() != null) {
      expiredReasons.put(next.pkg().id(), next.expiryReason());
    }
  }

  private String nextId() {
    // 包 id 会进事件/HTTP/回执：不得含明文参数或密钥（密钥纪律），序号足够
    return "dp-" + minted.incrementAndGet();
  }

  /** 登记项：包的当刻版本 + 已落定回执（{@code null} = 还没落定）。全部字段只在持锁时读写。 */
  private static final class Entry {

    private DecisionPackage current;
    private CommitReceipt receipt;

    /** U1 内容版本（从 1 起；旧路径的 decide/itemDecided 成功也递增，旧入口不校验它）。 */
    private long contentVersion = 1L;

    /**
     * U1 逐条表态的<b>只增</b>累计：进 {@link DecisionPackageSnapshot#verdicts()}，终局后也不丢（审计面要能回答"每一条是谁定的"）。
     *
     * <p>与 {@link #verdicts} 的分工：后者是旧适配器的分段裁决工作区（终局时会被清空），前者是快照的累计真相。
     */
    private final List<DecisionItemVerdict> snapshotVerdicts = new ArrayList<>();

    /**
     * 逐条表态的累积（用户裁定 15）：index ⇒ 表态。{@code PROPOSED} 态为空；表态一条就多一条； 包一进终局就清空（终局态读的是 {@link #current}
     * 里的 {@link Decision}）。
     */
    private final Map<Integer, ItemVerdict> verdicts = new LinkedHashMap<>();

    Entry(DecisionPackage initial) {
      this.current = initial;
    }

    /** 旧路径记一条表态：同时维护旧工作区 map 与 U1 快照累计，并推进内容版本。 */
    private void recordVerdict(ItemVerdict verdict) {
      verdicts.put(verdict.index(), verdict);
      snapshotVerdicts.add(
          new DecisionItemVerdict(
              verdict.index(), verdict.approved(), verdict.by(), verdict.note()));
      contentVersion++;
    }
  }

  /**
   * 一条条目上的表态（用户裁定 15 ②：{@code itemDecided} 的输入与读口形状）。
   *
   * <p><b>为什么它是独立记录</b>：{@link Decision} 的冻结形状只有"哪些被批"，答不出"这一条是谁批的、为什么"——
   * 而分段表态必须逐条可审计（每一条都可能是不同的人在不同时刻拍的板）。本记录只进<b>内存态</b>与回执面，不进事件载荷 （事件只带 index/verdict/by，见 {@code
   * DecisionEventTypes#ITEM_DECIDED}）。
   *
   * @param index 条目在包内的身份（非负）
   * @param approved {@code true} = 批准，{@code false} = 驳回（没有第三种取值：静默不属于表态，它由"根本没有本记录"表达）
   * @param by 表态人（通道名 / 实例 id / 工具名；只进审计，不参与判定）
   * @param note 可空理由（只进内存态与回执；无理由 = 空串）
   */
  public record ItemVerdict(int index, boolean approved, String by, String note) {

    public ItemVerdict {
      if (index < 0) {
        throw new IllegalArgumentException("index 不得为负（它是条目在包内的身份）：" + index);
      }
      Objects.requireNonNull(by, "by");
      if (by.isBlank()) {
        throw new IllegalArgumentException("by 不得为空白（「谁表的态」必须能对账）");
      }
      note = note == null ? "" : note;
    }
  }

  /**
   * 一次条目级表态的结果（{@link #itemDecided} 的返回值）：把"投递方要不要发事件、要不要接着提交"所需的四件事一次给全。
   *
   * @param pkg 表态后的包快照
   * @param verdicts 该包<b>已表态</b>的条目（按包内条目顺序；未表态的条目<b>不在</b>其中——"缺席 ≠ 驳回"）
   * @param decidedNow 这一次表态是否把包推到了 {@link PackageState#DECIDED}（投递方据此发 {@code decided} 并走提交）
   * @param undecidedCount 此刻仍未表态的条目数（{@code DECIDED} 态恒为 0）
   */
  public record ItemDecisionResult(
      DecisionPackage pkg, List<ItemVerdict> verdicts, boolean decidedNow, int undecidedCount) {

    public ItemDecisionResult {
      Objects.requireNonNull(pkg, "pkg");
      Objects.requireNonNull(verdicts, "verdicts");
      verdicts = List.copyOf(verdicts);
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
