package io.mosire.brain.decision;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>逐级预裁定</b>（设计 §5ter 裁定 12 + §5quater 的实现形状，用户原话 V18「为什么不做最大兼容面，更小的 Agent 可以批的，可以先批掉，
 * 然后再把已经做过裁定的裁定包报上去，允许上一级 Agent 修改下一级 Agent 做的裁定」）。
 *
 * <p>它回答两个问题，别的一个都不回答：
 *
 * <ol>
 *   <li><b>谁有能力做这条</b>（§5quater ① 的三条判据，{@link #capable}）：权限面（身份/白名单/角色/敏感/破坏）∩ 资源面 ∩ 档位 = {@link
 *       CommandMode#FULL}；
 *   <li><b>这条该由谁拍板</b>（§5quater ② 的上溯顺序，{@link #run}）：从<b>提出者</b>开始沿 {@code parentInstanceId} 往上，
 *       先试下级——本级有能力且预裁定"批" ⇒ 记一条预裁定、<b>不再往上问这一条</b>；没能力或"驳/不表态" ⇒ 这条继续上报；到顶仍无人能批 ⇒ 这条<b>到人</b>。
 * </ol>
 *
 * <p><b>★ 它不执行、不改包</b>：级联跑完只产出一份 {@link DecisionTrail} 与两集合（预批准 / 上报给人）。执行被批子集、把终局裁决交给 {@code
 * DecisionPackages.decide} 都是<b>调用方</b>（审批桥）的事——裁定 12 明确「不是先批先执行」：包始终是唯一次提交单元，
 * <b>所有裁定做完才执行</b>。所以本类对 {@code DecisionPackages} / {@code Decision} / {@code CommitReceipt}
 * 的公开签名零改动（§5quater ③ 的硬要求）。
 *
 * <p><b>★ {@link AskKind#SENSITIVE} 仍然生效（2026-09-13 老红线的保留，不是被取代）</b>：该条<b>强制</b>留在"到人"那一侧， 任何
 * Agent（含完全权限的上级）都不得预裁定它——见 {@link #forcedToHuman}。判据从"这条敏不敏感"改成"谁有能力做这条"这一刀 <b>只切</b> {@link
 * AskKind#MODE_LIMITED}（档位提升）那一类；敏感区照旧只到人（裁定 12 的原文：本裁定在其上<b>叠加</b>"按能力逐级预裁定"）。
 *
 * <p><b>预裁定从哪来（{@link LevelJudgement}）</b>：本类只管"顺序与记录"，"某一级批不批"是注入的判定来源。本单（P2b）实现的是
 * <b>主进程内</b>形态，缺省判定 {@link #CAPABILITY_APPROVES} = "有能力即批"（有能力执行的级就拍板，不跑 LLM）； 把 LLM 判定（{@code
 * SuperiorJudgeGate} 那条"到上级 Agent 代批"的路）映射成级别的预裁定属于跨进程/上级判定那条路——<b>本单不做</b>， 缺口写进报告。
 *
 * <p><b>'驳/不表态'的语义</b>（最容易写错的一条）：本级驳回<b>不是</b>终结——这一条<b>继续上报</b>（用户 V18 明确"允许上一级 Agent 修改下一级 Agent
 * 做的裁定"）。轨迹把每一级的裁定都留着，所以"谁改了谁"可审计（{@link DecisionTrail#levels()}）。到顶仍被驳 ⇒ 到人。
 *
 * <p><b>密钥纪律</b>：本类只碰工具名、条目 index、实例 id 与档位，<b>不读</b> {@link PackageItem#args()}——轨迹会进事件载荷，
 * 明文一个字节都不许进来。
 *
 * <p><b>线程安全</b>：轨迹表是 {@link ConcurrentHashMap}，同一包的"跑一次"用原子占位（并发第二次调用拿回同一份轨迹，
 * 不重复走链），"人拍板补齐"在条目内同步。轨迹不淘汰（与 {@code DecisionPackages} 的取舍同源：包本身要能回答"后来怎么了"）。
 */
public final class DecisionCascade {

  private static final Logger LOG = LoggerFactory.getLogger(DecisionCascade.class);

  /**
   * "哪里都不许"的权限集（{@link AgentPermissionSet#builder} 的空白名单 = deny-default）：取不到提出者那一级时的兜底，
   * 只为让"上溯时带的那份提问者权限"永远不是一个"凭空放宽"的值（见 {@link Chain#level(String, AgentPermissionSet)}）。
   */
  private static final AgentPermissionSet NOTHING_ALLOWED =
      AgentPermissionSet.builder(AccessToken.GUEST).build();

  /**
   * "人"这一级的裁决者 id（进 {@link DecisionTrail#finalizedBy()} 与轨迹的一级）。
   *
   * <p>它<b>不是</b>进程内的权限主体：人批的包执行时取<b>提出者/主 Agent</b> 那一级的身份（本机形态见 {@code App} 的装配注释）。
   */
  public static final String HUMAN_DECIDER = "human";

  /**
   * 审批闸链上的<b>代批</b>（上级 Agent 的 LLM 判定 / 会话级快判）这一级的 id。
   *
   * <p>它与人分开记是因为两者是不同的事实：审批链给不出"是哪一级代批的"可编程出口（{@code ApprovalCoordinator} 只返回决定本身），
   * 所以这里<b>不细分成具体实例</b>——只如实记"这条不是人答的"。{@link #complete} 的调用方按"审批面上有没有留下登记项"区分两者。
   */
  public static final String SUPERIOR_DECIDER = "superior";

  /** 派生链上的<b>一级</b>（装配层提供；本类不认识 {@code SubagentManager} 的注册表，也不做跨进程查询）。 */
  public record Level(
      String instanceId,
      AgentPermissionSet permissions,
      CommandMode mode,
      String parentInstanceId) {

    public Level {
      Objects.requireNonNull(instanceId, "instanceId");
      if (instanceId.isBlank()) {
        throw new IllegalArgumentException("instanceId 不得为空白（它是「谁批的」那个键）");
      }
      Objects.requireNonNull(permissions, "permissions");
      Objects.requireNonNull(mode, "mode");
      parentInstanceId = parentInstanceId == null ? "" : parentInstanceId;
    }
  }

  /**
   * <b>派生链视角</b>：实例 id ⇒ 那一级（含它的父级）。找不到 ⇒ 空（上溯到此为止，剩下的条目全部到人——fail-closed）。
   *
   * <p>P2b 的装配只提供主进程内那一级（主 Agent）；子 Agent 的实例记录经 {@code SubagentManager} 的注册表接进来是 P3a 做的（跨进程那条路，见
   * {@link Chain#level(String, AgentPermissionSet)}）。
   */
  @FunctionalInterface
  public interface Chain {

    Optional<Level> level(String instanceId);

    /**
     * <b>P3a 追加的上溯形状</b>：带着<b>提问者那一级的权限集</b>再解析一次。
     *
     * <p><b>为什么需要它</b>：级联的第一跳是<b>子 Agent 实例</b>（P3a 跨进程场景），而下一跳是<b>主 Agent</b>—— 主 Agent
     * 的权限集住在装配层手里，注册表里<b>没有</b>它的条目（它不是一个 {@code SubagentInstance}）。装配层因此需要 {@code 子体的权限集 ∩ 主 Agent
     * 的权限集} 才能如实报出"主 Agent 那一级的权限面"：只用子体的权限集会 <b>低报</b>（主 Agent 能力比子体大），只用主 Agent
     * 的权限集会<b>越报</b>（把子体够不着的资源面挂到一个从子体上溯到的层级上）。
     *
     * <p><b>★ 权限单调性在这里是结构性的</b>：与 {@code SubagentManager} 的"逐层只减不增"同源—— 子体的权限集是主 Agent 权限集 ∩ 模板 ∩
     * 请求 的子集，所以 {@code covers(caller)} 在合法装配下恒成立； 不成立（权限集被人手工拼反了）⇒ 求交退化成子体那一级，<b>只会更窄、不会更宽</b>。
     *
     * <p><b>兼容口径</b>：缺省实现直接转发 {@link #level(String)} —— 已有实现（含 P2b 的 {@code App} 装配与
     * 全部既有用例）<b>一个字都不用改</b>，行为逐字不变。只有需要"按提问者的范围收窄上级那一级"的链才覆写它。
     *
     * @param instanceId 要解析的实例 id
     * @param callerPermissions <b>上溯到它之前那一级</b>的权限集（起点 = 提出者的权限集；不为 null）
     */
    default Optional<Level> level(String instanceId, AgentPermissionSet callerPermissions) {
      return level(instanceId);
    }
  }

  /** 某一级对它有能力的那些条目的预裁定。 */
  public enum Verdict {
    /** 批（记一条预裁定，这些条目不再上报）。 */
    APPROVE,
    /** 驳（记一条预裁定，但这些条目<b>继续上报</b>——不是终结）。 */
    REJECT,
    /** 不表态（不记裁决、继续上报；缺省的那一侧是保守的）。 */
    ABSTAIN
  }

  /** 某一级的预裁定来源（见类 javadoc 的"预裁定从哪来"）。 */
  @FunctionalInterface
  public interface LevelJudgement {

    /**
     * @param capableItems 这一级<b>有能力的、且还没被下级批掉的</b>条目（非空；顺序 = 包内顺序）
     */
    Verdict judge(Level level, List<PackageItem> capableItems);
  }

  /** 缺省判定（主进程内形态）：有能力即批——不跑 LLM，见类 javadoc。 */
  public static final LevelJudgement CAPABILITY_APPROVES = (level, capableItems) -> Verdict.APPROVE;

  /**
   * 一级预裁定（§5quater ③）：谁、用什么档位、对哪些条目、批还是驳、可空理由。
   *
   * <p>{@code approvedIndexes} 与 {@code rejectedIndexes} <b>互斥</b>且各自可为空集；{@code reason} 可空（未说明理由）。
   */
  /**
   * ★ <b>{@code EI_EXPOSE_REP} 的记录级抑制（写明理由）</b>：{@code approvedIndexes}/{@code rejectedIndexes}
   * 在规范构造里 已 {@code unmodifiableSet(new LinkedHashSet<>(...))} 冻结（防御性拷贝 + 不可变视图）；SpotBugs
   * 不认这个形状，且把告警钉在 <b>记录分量声明</b>那一行（record 的隐式成员不吃方法级注解）⇒ 抑制只能落在类型上。这里<b>不</b>为消误报去改数据形状。
   */
  @SuppressFBWarnings("EI_EXPOSE_REP")
  public record PreDecision(
      String deciderId,
      CommandMode deciderMode,
      Set<Integer> approvedIndexes,
      Set<Integer> rejectedIndexes,
      String reason) {

    public PreDecision {
      Objects.requireNonNull(deciderId, "deciderId");
      Objects.requireNonNull(deciderMode, "deciderMode");
      approvedIndexes =
          approvedIndexes == null
              ? Set.of()
              : Collections.unmodifiableSet(new LinkedHashSet<>(approvedIndexes));
      rejectedIndexes =
          rejectedIndexes == null
              ? Set.of()
              : Collections.unmodifiableSet(new LinkedHashSet<>(rejectedIndexes));
      reason = reason == null || reason.isBlank() ? null : reason;
    }
  }

  /**
   * 一次级联的完整轨迹（§5quater ③；记录义务 = 裁定 12「谁改了谁」要可审计）。
   *
   * @param levels 逐级预裁定（顺序 = 从提出者往上；人的那一级在人拍板之后追加在末尾）
   * @param escalatedToHuman 上报给人的条目 index（人面对的是<b>这些</b>条目，不是整包）
   * @param finalApproved 终局被批的条目 index（预批准 ∪ 人批准的上报条目）
   * @param finalizedBy 终局裁决者的 id（{@link #HUMAN_DECIDER} / 某一级实例 id）；<b>null = 还没拍板</b>（等人）
   */
  /** ★ 同上（{@code EI_EXPOSE_REP} 记录级抑制）：两个 {@code Set} 分量都在规范构造里冻结过。 */
  @SuppressFBWarnings("EI_EXPOSE_REP")
  public record DecisionTrail(
      List<PreDecision> levels,
      Set<Integer> escalatedToHuman,
      Set<Integer> finalApproved,
      String finalizedBy) {

    public DecisionTrail {
      levels = levels == null ? List.of() : List.copyOf(levels);
      escalatedToHuman =
          escalatedToHuman == null
              ? Set.of()
              : Collections.unmodifiableSet(new LinkedHashSet<>(escalatedToHuman));
      finalApproved =
          finalApproved == null
              ? Set.of()
              : Collections.unmodifiableSet(new LinkedHashSet<>(finalApproved));
      finalizedBy = finalizedBy == null || finalizedBy.isBlank() ? null : finalizedBy;
    }
  }

  private final Chain chain;
  private final Function<String, Optional<AgentTool>> toolLookup;
  private final LevelJudgement judgement;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  /**
   * @param chain 派生链视角（装配层提供；找不到的实例 ⇒ 那一条链到此为止）
   * @param toolLookup 工具名 ⇒ 工具（能力判据要读 {@code spec()/roles()/resources()}；取不到 ⇒ 视为"没人有能力"）
   */
  public DecisionCascade(Chain chain, Function<String, Optional<AgentTool>> toolLookup) {
    this(chain, toolLookup, CAPABILITY_APPROVES);
  }

  /**
   * @param judgement 级别的预裁定来源（缺省 {@link #CAPABILITY_APPROVES}；测试与将来的"上级 LLM 判定"在这里注入）
   */
  public DecisionCascade(
      Chain chain, Function<String, Optional<AgentTool>> toolLookup, LevelJudgement judgement) {
    this.chain = Objects.requireNonNull(chain, "chain");
    this.toolLookup = Objects.requireNonNull(toolLookup, "toolLookup");
    this.judgement = Objects.requireNonNull(judgement, "judgement");
  }

  /**
   * 跑一次级联（幂等：同一 packageId 并发第二次调用拿回同一份轨迹，不重复走链、不重复记）。
   *
   * @param pkg 刚登记、还在 {@code PROPOSED} 的包（条目顺序即记录顺序）
   * @param askKinds 条目 index ⇒ 工具自报的"为什么问"；<b>缺料按 {@link AskKind#SENSITIVE} 处理</b>（永远到人，最保守的一侧）。 传
   *     {@code null} = 完全没有料（例如不经队列的直投口）⇒ 全部条目按 SENSITIVE ⇒ 整包到人
   * @return 该包的轨迹（预批准已定、{@code finalizedBy} 在"有人上报"时为 null = 等人）
   */
  public DecisionTrail run(DecisionPackage pkg, Map<Integer, AskKind> askKinds) {
    Objects.requireNonNull(pkg, "pkg");
    Entry entry = entries.computeIfAbsent(pkg.id(), id -> walk(pkg, askKinds));
    return entry.trail();
  }

  /**
   * 人拍板之后补齐轨迹：把"人"记成最后一级预裁定，并给出终局（{@link DecisionTrail#finalizedBy()} = {@link #HUMAN_DECIDER}）。
   *
   * <p><b>只认上报给它的那些条目</b>：{@code humanApproved} 里落在外面的 index 会被剔除——
   * 人不能替下级已经批掉的条目改口（那要走"上一级修改下一级"的正规路：由上一级在级联里驳回，见 {@link LevelJudgement}）。 驳回的上报条目 = 上报集合里剩下的那些（缺席
   * = 驳回，与 {@link io.mosire.agentlib.decision.Decision} 的 fail-closed 方向一致）。
   *
   * <p><b>幂等</b>：同一包第二次调用返回第一次的终局轨迹（首次裁决永不被覆盖——同 {@code DecisionPackages.decide} 的口径）。
   *
   * @param packageId 包 id（没跑过级联 ⇒ 返回空，调用方按"没有轨迹"处理）
   * @param humanApproved 人在上报集合里批准的那些条目
   * @param reason 人给的理由（可空 = 未说明理由）
   */
  public Optional<DecisionTrail> complete(
      String packageId, Set<Integer> humanApproved, String reason) {
    Objects.requireNonNull(packageId, "packageId");
    Entry entry = entries.get(packageId);
    if (entry == null) {
      return Optional.empty();
    }
    return Optional.of(entry.complete(humanApproved, reason));
  }

  /** 同上，但显式指定"这一级是谁"（{@link #HUMAN_DECIDER} / {@link #SUPERIOR_DECIDER}）。 */
  public Optional<DecisionTrail> complete(
      String packageId, Set<Integer> humanApproved, String reason, String deciderId) {
    Objects.requireNonNull(deciderId, "deciderId");
    Entry entry = entries.get(packageId);
    if (entry == null) {
      return Optional.empty();
    }
    return Optional.of(entry.complete(humanApproved, reason, deciderId));
  }

  /** 该包的轨迹（没跑过 ⇒ 空）。 */
  public Optional<DecisionTrail> trailOf(String packageId) {
    Entry entry = packageId == null ? null : entries.get(packageId);
    return entry == null ? Optional.empty() : Optional.of(entry.trail());
  }

  /** 该包<b>上报给人</b>的条目（没跑过级联 ⇒ 空 = 调用方按"整包到人"处理）。 */
  public Optional<Set<Integer>> escalatedOf(String packageId) {
    Entry entry = packageId == null ? null : entries.get(packageId);
    return entry == null ? Optional.empty() : Optional.of(entry.escalated);
  }

  /** 该包<b>预批准</b>（下级/本级拍板、不等人）的条目（没跑过 ⇒ 空）。 */
  public Optional<Set<Integer>> preApprovedOf(String packageId) {
    Entry entry = packageId == null ? null : entries.get(packageId);
    return entry == null ? Optional.empty() : Optional.of(entry.preApproved);
  }

  /** 该包的终局裁决者（还没拍板 / 没跑过 ⇒ 空）。 */
  public Optional<String> finalizedBy(String packageId) {
    return trailOf(packageId).map(DecisionTrail::finalizedBy).filter(Objects::nonNull);
  }

  /**
   * 走一遍派生链（纯函数；不碰 {@link #entries}）。
   *
   * <p>顺序 = 从提出者往上（{@code
   * Level.parentInstanceId}），每一级只处理<b>还没被下级批掉</b>且<b>没被强制到人</b>且<b>它有能力</b>的条目；
   * 本级批掉的从"待解决"里移除，其余原样带到上一级。走完仍没被批的一律到人。
   */
  private Entry walk(DecisionPackage pkg, Map<Integer, AskKind> askKinds) {
    Set<Integer> resolved = new LinkedHashSet<>();
    Set<Integer> preApproved = new LinkedHashSet<>();
    List<PreDecision> levels = new ArrayList<>();
    String lastApprover = null;
    Set<String> visited = new LinkedHashSet<>();
    String cursor = pkg.proposerKey();
    // ★ P3a：上溯时带上"提问者那一级的权限集"（起点 = 提出者那一级）——链实现据此为上级收窄权限面，
    //   见 Chain#level(String, AgentPermissionSet)。取不到提出者那一级时退回"哪里都不许"（fail-closed，
    //   不给链实现任何凭空放宽的机会）。
    AgentPermissionSet callerPermissions =
        chain.level(cursor).map(Level::permissions).orElse(NOTHING_ALLOWED);
    while (cursor != null && !cursor.isBlank() && visited.add(cursor)) {
      Optional<Level> found = chain.level(cursor, callerPermissions);
      if (found.isEmpty()) {
        LOG.debug("级联上溯：派生链上没有实例 {} 这一级 ⇒ 剩下的条目全部到人 packageId={}", cursor, pkg.id());
        break;
      }
      Level level = found.get();
      List<PackageItem> capable = new ArrayList<>();
      for (PackageItem item : pkg.items()) {
        if (resolved.contains(item.index()) || forcedToHuman(item, askKinds)) {
          continue;
        }
        if (capable(level, item)) {
          capable.add(item);
        }
      }
      if (!capable.isEmpty()) {
        Verdict verdict = judgement.judge(level, List.copyOf(capable));
        if (verdict == null) {
          // 判定器违约（返回 null）：按"不表态"办（继续上报），绝不把"没看懂"变成"批了"
          LOG.warn(
              "级联预裁定判定器返回 null（按不表态处理，继续上报） level={} packageId={}", level.instanceId(), pkg.id());
          verdict = Verdict.ABSTAIN;
        }
        switch (verdict) {
          case APPROVE -> {
            Set<Integer> approved = indexes(capable);
            levels.add(
                new PreDecision(
                    level.instanceId(),
                    level.mode(),
                    approved,
                    Set.of(),
                    "本级有能力执行这 " + approved.size() + " 条（本级预裁定批准）"));
            resolved.addAll(approved);
            preApproved.addAll(approved);
            lastApprover = level.instanceId();
            LOG.info(
                "级联预裁定：{} 批准 {} 条（不再上报） packageId={} indexes={}",
                level.instanceId(),
                approved.size(),
                pkg.id(),
                approved);
          }
          case REJECT ->
              levels.add(
                  new PreDecision(
                      level.instanceId(),
                      level.mode(),
                      Set.of(),
                      indexes(capable),
                      "本级预裁定驳回（该条继续上报，等待上一级或人改判）"));
          case ABSTAIN -> {
            // 不表态：不记裁决、继续上报（"没表态"与"驳"是两种取值，记录面不该混）
          }
        }
      }
      // 下一跳的"提问者"就是本级：它的权限集成为上一级收窄的依据（见 Chain#level(String, AgentPermissionSet)）
      callerPermissions = level.permissions();
      cursor = level.parentInstanceId();
    }
    Set<Integer> escalated = new LinkedHashSet<>();
    for (PackageItem item : pkg.items()) {
      if (!resolved.contains(item.index())) {
        escalated.add(item.index());
      }
    }
    String finalizedBy = escalated.isEmpty() ? lastApprover : null;
    DecisionTrail trail = new DecisionTrail(levels, escalated, preApproved, finalizedBy);
    LOG.info(
        "级联跑完 packageId={} proposerKey={} 预批准={} 上报到人={} 终局裁决者={}",
        pkg.id(),
        pkg.proposerKey(),
        preApproved,
        escalated,
        finalizedBy == null ? "（等人）" : finalizedBy);
    return new Entry(preApproved, escalated, trail);
  }

  /**
   * 能力判据（§5quater ① 的三条，<b>全部</b>成立才算"有能力做这条"）：
   *
   * <ol>
   *   <li>{@link PermissionChecker#allows}（身份级别 / 白名单 / 角色 / 敏感 / 破坏）；
   *   <li>资源面：{@link ResourceAuthorizer#of} 的 {@code denial()} 为空（够得着工具声明的命名空间）；
   *   <li>候选档位 = {@link CommandMode#FULL}（只有完全权限档才谈得上"替别人拍板"，与 {@code SuperiorJudgeGate} 同源）。
   * </ol>
   *
   * <p>工具不在目录里 ⇒ 没人有能力（fail-closed：这条到人）。三条判据<b>全部复用既有件</b>，不新增权限维度（设计 §5ter 裁定 13）。
   */
  private boolean capable(Level level, PackageItem item) {
    if (level.mode() != CommandMode.FULL) {
      return false;
    }
    Optional<AgentTool> found = toolLookup.apply(item.toolName());
    if (found == null || found.isEmpty()) {
      return false;
    }
    AgentTool tool = found.get();
    if (!PermissionChecker.allows(
        level.permissions(), item.toolName(), tool.spec(), tool.roles())) {
      return false;
    }
    return ResourceAuthorizer.of(level.permissions(), tool.resources()).denial().isEmpty();
  }

  /**
   * 该条是否<b>强制到人</b>：只有 {@link AskKind#MODE_LIMITED} 才是"可以逐级预裁定"的那一类。
   *
   * <p>{@link AskKind#SENSITIVE} = 系统敏感区：<b>任何 Agent 都不得预裁定它</b>（2026-09-13 老裁决「敏感区只能到人」的保留， 设计
   * §5quater ② 末尾那段）；缺料（{@code askKinds} 里没有这一条）同样按 SENSITIVE 办—— "没说明为什么问"落进人手里，不落进 Agent 的代批面（与
   * {@code AskKind} 的缺省口径同源）。
   */
  private static boolean forcedToHuman(PackageItem item, Map<Integer, AskKind> askKinds) {
    AskKind kind = askKinds == null ? null : askKinds.get(item.index());
    return kind != AskKind.MODE_LIMITED;
  }

  /** 条目的 index 集合（顺序 = 传入顺序）。 */
  private static Set<Integer> indexes(List<PackageItem> items) {
    Set<Integer> indexes = new LinkedHashSet<>();
    for (PackageItem item : items) {
      indexes.add(item.index());
    }
    return Collections.unmodifiableSet(indexes);
  }

  /**
   * 一个包的级联状态：预批准集合 + 上报集合 + 当刻轨迹（人拍板后原地替换）。
   *
   * <p>{@link #complete} 只认<b>上报集合内</b>的 index（见该方法 javadoc），并且一次终局（第二次调用返回第一次的结果）。
   */
  private static final class Entry {

    private final Set<Integer> preApproved;
    private final Set<Integer> escalated;
    private volatile DecisionTrail trail;
    private volatile boolean completed;

    Entry(Set<Integer> preApproved, Set<Integer> escalated, DecisionTrail trail) {
      this.preApproved = Collections.unmodifiableSet(new LinkedHashSet<>(preApproved));
      this.escalated = Collections.unmodifiableSet(new LinkedHashSet<>(escalated));
      this.trail = trail;
    }

    DecisionTrail trail() {
      return trail;
    }

    synchronized DecisionTrail complete(Set<Integer> humanApproved, String reason) {
      return complete(humanApproved, reason, HUMAN_DECIDER);
    }

    synchronized DecisionTrail complete(
        Set<Integer> humanApproved, String reason, String deciderId) {
      if (completed) {
        return trail;
      }
      Set<Integer> approvedWithin = new LinkedHashSet<>();
      if (humanApproved != null) {
        for (Integer index : humanApproved) {
          if (index != null && escalated.contains(index)) {
            approvedWithin.add(index);
          }
        }
      }
      Set<Integer> rejectedWithin = new LinkedHashSet<>(escalated);
      rejectedWithin.removeAll(approvedWithin);
      List<PreDecision> levels = new ArrayList<>(trail.levels());
      if (!escalated.isEmpty()) {
        // 人的那一级也要留在轨迹里（"谁改了谁"必须可审计）；理由可空（V14）
        levels.add(
            new PreDecision(
                deciderId,
                CommandMode.FULL,
                approvedWithin,
                rejectedWithin,
                reason == null || reason.isBlank()
                    ? (approvedWithin.isEmpty() ? "整包驳回" : "批准 " + approvedWithin.size() + " 条")
                    : reason));
      }
      Set<Integer> finalApproved = new LinkedHashSet<>(preApproved);
      finalApproved.addAll(approvedWithin);
      String finalizedBy = escalated.isEmpty() ? trail.finalizedBy() : deciderId;
      trail = new DecisionTrail(levels, escalated, finalApproved, finalizedBy);
      completed = true;
      return trail;
    }
  }
}
