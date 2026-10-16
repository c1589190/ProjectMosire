package io.mosire.brain.decision;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.DecisionReleaseMarker;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.decision.SubagentDecisionLedger;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>父侧的「该子体本轮队列」簿子</b>（设计 §5sexies③，P3d 的件）：按<b>子体实例 id</b> 记下这一轮里被 defer 的 {@link ToolGate.Ask}
 * 调用，收尾结算完即清；另记"被批条目的一次性放行"，重复使用即拒。
 *
 * <p><b>为什么键是子体实例 id</b>（用户 2026-10-16 指出的一对多事实）：一个父 Agent 可以有多个子 Agent，但一个子 Agent 只有一个父 ⇒ 链上绑死的
 * {@link AgentIdentity#instanceId()} 就把"这一轮该谁结算、该谁被问"唯一钉死，不需要会话号/轮次号。 它由父侧在建 MCP 链时写死（{@code
 * SubProcessExecutor}），模型给不出、子进程改不了。
 *
 * <p><b>三件事刻意如此</b>：
 *
 * <ol>
 *   <li><b>不进 {@code pending()}</b>、不落父侧历史、不发 {@code decision.package.*} 事件——正式记录（包 + 裁决 + 事件）只在提出者
 *       （子体）自己的包表与 {@code events.db} 里（不变量 1/5、V5）。这份簿子是<b>临时</b>的，只为"不丢条目 + 能对账 + 一次性放行"存在；
 *   <li><b>惰性建</b>：该子体第一条 {@code Ask} 到达时建，<b>不需要"回合开始"信号</b>（跨进程拿不到那种信号）；
 *   <li><b>三条清空路径</b>：① 收尾结算完成（{@link #settled}）/ ② 子体进程结束或被回收（{@link #recycle}，由装配层订阅 {@code
 *       agent.lifecycle} 终局事件接）/ ③ 到点作废（内部 TTL，{@link #TURN_TTL_MILLIS}）。
 * </ol>
 *
 * <p><b>谁能 defer</b>：只有"本进程派出去的子体"——判据是链上身份 {@code depth > 0} 且身份已穿透（不是 {@link
 * AgentIdentity#UNKNOWN}）。主 Agent（{@code depth = 0}）、外部 MCP 面（{@code external-mcp}，{@code depth =
 * 0}）与手工拼的 无身份上下文都<b>不</b>走这条路（{@code defer} 返回假 ⇒ 调用方走既有审批闸）。
 *
 * <p><b>有界</b>：跟踪的实例数与放行条数都有上限（LRU 淘汰最久未用的一条）。淘汰意味着"那条放行没了"⇒ 子体重调会拿 {@link
 * ToolCallAuthorizer#DECISION_RELEASE_UNKNOWN} 具名拒（fail-closed，且逐条记进它自己的回执），<b>绝不</b>放行。
 *
 * <p><b>密钥纪律</b>：{@link PendingDecisionQueue} 的条目带参数明文（裁决者要看全文）——它只活在本簿子的内存里，转发给 {@link
 * CrossProcessDecisionGateway} 建临时包，日志里只有实例 id、包 id、条目序号与条数。
 */
public final class ParentSubagentDecisionLedger implements SubagentDecisionLedger {

  private static final Logger LOG = LoggerFactory.getLogger(ParentSubagentDecisionLedger.class);

  /**
   * 一轮簿子的存活上限：与包的缺省 deadline <b>同源</b>（设计 §5sexies③③「到点（包 deadline）作废」）。
   *
   * <p>口径：从<b>第一条 defer</b> 起算。正常回合是"秒级攒条目 ⇒ 收尾建包 ⇒ 30min 的裁决窗口"，所以这个上限对正常路径没有任何影响；
   * 它的作用只有一个——"子体再也不会来收尾"时，那份簿子不会永远留着。
   */
  public static final long TURN_TTL_MILLIS = DecisionPackages.DEFAULT_DEADLINE_MILLIS;

  /** 同时跟踪的实例上限（LRU）：不是限额，是"没人回收"这类 bug 的响亮出口，顺带把内存钉在上界。 */
  public static final int MAX_TRACKED_INSTANCES = 256;

  /** 放行条上限（LRU）：一条 = 一个"已批尚未执行"的条目身份；超上限 ⇒ 最久未用的那条作废（重调将具名拒）。 */
  public static final int MAX_GRANTS = 4096;

  /** 键的分隔（实例 id / 包 id / 序号都不会含它：前者是实例与包 id，后者是十进制数）。 */
  private static final char KEY_SEPARATOR = '\u0000';

  private final Map<String, Turn> turns = Collections.synchronizedMap(new TurnCache());
  private final Map<String, Grant> grants = Collections.synchronizedMap(new GrantCache());

  /**
   * 父侧工具注册表（判断"这台子体到底能不能提交"用：{@code submit_decision_package} 在不在目录里）。
   *
   * <p><b>为什么父侧要自己判一次"子体有没有这条缝"</b>（本类最要紧的一条防线）：父侧并不知道子体那一轮<b>绑没绑</b>队列。 若父侧对一台<b>根本提交不了</b>的子体（模板
   * {@code allowedTools} 里没有 {@code submit_decision_package}）也照记不误， 那条调用就既没执行、也没人去裁——子体拿 {@code
   * DECISION_QUEUED} 回去、本地队列却是空的（缝不可用 ⇒ 不绑队列 ⇒ 不登记）， 回合收尾也没有包可提交 ⇒ <b>敏感调用悄悄消失</b>。那正是本仓最贵的假绿。
   *
   * <p>判据与子体侧的 {@code CrossProcessDecisionSubmitter#available()} <b>同一把 oracle</b>（目录命中 + {@link
   * PermissionChecker#allows}），数据来源换成父侧手里那份——红线 R7 说父侧本来就持有该子体的权限集（建链用的就是它）。
   */
  private final ToolRegistry parentTools;

  /** 子体实例 id ⇒ 它的有效权限集（装配层接；查不到 ⇒ 不记账——拿不准就不 defer，退回逐条问人）。 */
  private final Function<String, Optional<AgentPermissionSet>> subagentPermissions;

  /** 到点上界（缺省 = {@link #TURN_TTL_MILLIS}）。 */
  private final long turnTtlMillis;

  /**
   * 生产装配。
   *
   * @param parentTools 父侧工具注册表（判"子体能不能提交"用；就是建子体 MCP 桥的那一张）
   * @param subagentPermissions 子体实例 id ⇒ 有效权限集（装配层从子 Agent 注册表现取；空值 = 查不到）
   */
  public ParentSubagentDecisionLedger(
      ToolRegistry parentTools,
      Function<String, Optional<AgentPermissionSet>> subagentPermissions) {
    this(parentTools, subagentPermissions, TURN_TTL_MILLIS);
  }

  /**
   * 指定到点上界的装配（用例用它把"到点作废"这条路径变成可测的；生产装配走两参构造）。
   *
   * @param turnTtlMillis 一轮簿子的存活上限（毫秒，必须为正）
   */
  ParentSubagentDecisionLedger(
      ToolRegistry parentTools,
      Function<String, Optional<AgentPermissionSet>> subagentPermissions,
      long turnTtlMillis) {
    this.parentTools = Objects.requireNonNull(parentTools, "parentTools");
    this.subagentPermissions = Objects.requireNonNull(subagentPermissions, "subagentPermissions");
    if (turnTtlMillis <= 0) {
      throw new IllegalArgumentException("turnTtlMillis 必须为正: " + turnTtlMillis);
    }
    this.turnTtlMillis = turnTtlMillis;
  }

  /** 一个子体这一轮的簿子：队列 + 到点时刻（到点即作废，见 {@link #TURN_TTL_MILLIS}）。 */
  private record Turn(PendingDecisionQueue queue, long deadlineEpochMs) {

    boolean expired(long nowEpochMs) {
      return nowEpochMs > deadlineEpochMs;
    }
  }

  /**
   * 一张放行条：某实例的包 P 的第 i 条<b>已批且尚未执行</b>。
   *
   * <p>{@code used} 是"一次性"的落点：消费（{@link #consumeRelease}）之后重调同一张条 ⇒ 具名拒（设计 §5sexies⑥）。
   */
  private record Grant(boolean used) {}

  /** 有界 LRU（实例表）：满了淘汰最久未用的那一台子体的簿子。 */
  private static final class TurnCache extends LinkedHashMap<String, Turn> {

    private static final long serialVersionUID = 1L;

    TurnCache() {
      super(16, 0.75f, true);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Turn> eldest) {
      boolean evict = size() > MAX_TRACKED_INSTANCES;
      if (evict) {
        LOG.warn(
            "父侧本轮队列超过上限 {} 台实例 ⇒ 淘汰最久未用的那一台（它的下一条 Ask 会重新建簿子；已批未执行的放行仍按放行条计） instance={}",
            MAX_TRACKED_INSTANCES,
            eldest.getKey());
      }
      return evict;
    }
  }

  /** 有界 LRU（放行条）：满了淘汰最久未用的那一张。 */
  private static final class GrantCache extends LinkedHashMap<String, Grant> {

    private static final long serialVersionUID = 1L;

    GrantCache() {
      super(16, 0.75f, true);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Grant> eldest) {
      boolean evict = size() > MAX_GRANTS;
      if (evict) {
        LOG.warn("父侧放行条超过上限 {} 条 ⇒ 淘汰最久未用的那一张（它对应的重调会具名拒）", MAX_GRANTS);
      }
      return evict;
    }
  }

  /** {@inheritDoc} 惰性建（该实例第一条 Ask 到达时）；登记失败 ⇒ 返回假（调用方退回既有审批闸，不静默丢调用）。 */
  @Override
  public boolean defer(AgentTool tool, ToolContext context, ToolGate.Ask ask) {
    Objects.requireNonNull(tool, "tool");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(ask, "ask");
    String instanceId = subagentInstanceId(context);
    if (instanceId == null) {
      return false;
    }
    if (!canSubmit(instanceId)) {
      // ★ 这台子体没有"提交回执"的那条缝 ⇒ 不记账（记账就等于把这条调用变成了"既不执行也没人裁"）：
      //   返回假，调用方走既有审批闸（逐条问人）——行为与接线前逐字一致。
      LOG.info(
          "子体没有跨进程提交的缝（模板 allowedTools 缺 {}？）⇒ 本条不进父侧本轮队列，退回既有审批闸 tool={} instance={}",
          SubmitDecisionPackageTools.NAME,
          tool.name(),
          instanceId);
      return false;
    }
    long now = System.currentTimeMillis();
    PendingDecisionQueue queue;
    try {
      queue = queueFor(instanceId, now);
      queue.register(tool.name(), context.arguments(), ask);
    } catch (RuntimeException failure) {
      // 簿子自己炸了（极少：超上限 / 已产出提议）⇒ 不静默丢这条调用：返回假，调用方走既有审批闸（问人 / fail-closed 拒）
      LOG.error(
          "父侧本轮队列登记失败（本条不 defer，退回既有审批闸——不会静默消失） instance={} tool={} class={}",
          instanceId,
          tool.name(),
          ask.classKey(),
          failure);
      return false;
    }
    LOG.debug(
        "父侧记下一条待裁决调用（本轮不执行、不投审批面） instance={} tool={} class={} kind={} 本轮已记={}",
        instanceId,
        tool.name(),
        ask.classKey(),
        ask.kind(),
        queue.size());
    return true;
  }

  /**
   * 这台子体<b>能不能</b>把本轮条目交回来（= 它那一侧有没有这条缝）。
   *
   * <p>与子体侧 {@code CrossProcessDecisionSubmitter#available()} 同一把 oracle，只为"父侧别把一条没人收的账记下来"：
   * ①目录里查得到 {@value SubmitDecisionPackageTools#NAME}（父侧外发面 = 该子体的判定面，noExport 的工具根本到不了它）；
   * ②该子体的权限集放行它（{@link PermissionChecker#allows}，与可见面同源）。
   *
   * <p>查不到这台子体（已被回收 / 不认识）⇒ 假：宁可退回"逐条问人"（旧行为），也不记一条可能没人收的账。
   */
  private boolean canSubmit(String instanceId) {
    Optional<AgentPermissionSet> permissions = subagentPermissions.apply(instanceId);
    if (permissions.isEmpty()) {
      return false;
    }
    Optional<AgentTool> tool = parentTools.find(SubmitDecisionPackageTools.NAME);
    if (tool.isEmpty()) {
      return false;
    }
    return PermissionChecker.allows(
        permissions.get(), SubmitDecisionPackageTools.NAME, tool.get().spec(), tool.get().roles());
  }

  /** {@inheritDoc} 只读探测；{@code arguments} 给的是<b>剥掉标记后</b>的那一份。 */
  @Override
  public Optional<Release> release(ToolContext context) {
    Objects.requireNonNull(context, "context");
    String instanceId = subagentInstanceId(context);
    if (instanceId == null) {
      return Optional.empty();
    }
    Optional<DecisionReleaseMarker.Claim> claim = DecisionReleaseMarker.read(context.arguments());
    if (claim.isEmpty()) {
      return Optional.empty();
    }
    String packageId = claim.get().packageId();
    int index = claim.get().index();
    Grant grant = grants.get(key(instanceId, packageId, index));
    if (grant == null) {
      LOG.warn(
          "放行标记查不到对应的条目（具名拒；可能是伪造、端口/包 id 对不上、簿子已清或放行条已被淘汰） instance={} packageId={} index={}",
          instanceId,
          packageId,
          index);
      return Optional.of(
          Release.denied(
              ToolCallAuthorizer.DECISION_RELEASE_UNKNOWN,
              "这条调用没有对应的放行记录（包 " + packageId + " 第 " + index + " 条未获批准、或这条放行已作废）——本次不执行",
              instanceId,
              packageId,
              index));
    }
    if (grant.used()) {
      LOG.warn(
          "放行标记被重复使用（具名拒：一次性放行只放行一次） instance={} packageId={} index={}",
          instanceId,
          packageId,
          index);
      return Optional.of(
          Release.denied(
              ToolCallAuthorizer.DECISION_RELEASE_USED,
              "这条放行已经用过了（包 " + packageId + " 第 " + index + " 条只放行一次）——本次不执行",
              instanceId,
              packageId,
              index));
    }
    return Optional.of(
        Release.allowed(
            DecisionReleaseMarker.strip(context.arguments()), instanceId, packageId, index));
  }

  /** {@inheritDoc} 幂等：同一条放行消费两次与一次等价（第二次本来就拿不到 {@code allowed} 了）。 */
  @Override
  public void consumeRelease(Release release) {
    Objects.requireNonNull(release, "release");
    String key = key(release.instanceId(), release.packageId(), release.itemIndex());
    grants.computeIfPresent(key, (ignored, grant) -> grant.used() ? grant : new Grant(true));
  }

  /** {@inheritDoc} 到点即作废（惰性判，不做后台清理）；作废时记一行 WARN（理由如实说）。 */
  @Override
  public List<PendingDecisionQueue.PendingEntry> pendingEntries(String instanceId) {
    Objects.requireNonNull(instanceId, "instanceId");
    Turn turn = turns.get(instanceId);
    if (turn == null) {
      return List.of();
    }
    if (turn.expired(System.currentTimeMillis())) {
      turns.remove(instanceId);
      LOG.warn(
          "父侧本轮队列到点作废（该子体在窗口内没有来收尾结算） instance={} 条目数={} 存活上限={}ms",
          instanceId,
          turn.queue().size(),
          turnTtlMillis);
      return List.of();
    }
    return turn.queue().snapshot();
  }

  /** {@inheritDoc} 被批条目记成放行条（键 = 实例 + 包 id + 序号），本轮队列清掉。 */
  @Override
  public void settled(String instanceId, String packageId, Set<Integer> approvedIndexes) {
    Objects.requireNonNull(instanceId, "instanceId");
    Turn turn = turns.remove(instanceId);
    if (turn == null) {
      return;
    }
    if (packageId == null || packageId.isBlank()) {
      // 没有包 id 就没有"这条属于哪个包"的答案 ⇒ 不记放行（子体的重调会拿 DECISION_RELEASE_UNKNOWN），响亮说出来
      LOG.warn(
          "父侧本轮队列结算：包 id 为空 ⇒ 不记任何放行条（该子体的重调会具名拒） instance={} 条目数={}",
          instanceId,
          turn.queue().size());
      return;
    }
    Set<Integer> approved = approvedIndexes == null ? Set.of() : approvedIndexes;
    int granted = 0;
    for (PendingDecisionQueue.PendingEntry entry : turn.queue().snapshot()) {
      int index = entry.packageItem().index();
      if (approved.contains(index)) {
        grants.put(key(instanceId, packageId, index), new Grant(false));
        granted++;
      }
    }
    LOG.info(
        "父侧本轮队列已结算：本轮队列清掉、记下 {} 条一次性放行 instance={} packageId={} 本轮条目={} 被批={}",
        granted,
        instanceId,
        packageId,
        turn.queue().size(),
        approved.size());
  }

  /** {@inheritDoc} 提交未被受理 ⇒ 清簿子、不记放行。 */
  @Override
  public void discard(String instanceId) {
    Objects.requireNonNull(instanceId, "instanceId");
    Turn turn = turns.remove(instanceId);
    if (turn != null) {
      LOG.warn("父侧本轮队列被清（这次提交未被受理：不记放行，条目零执行） instance={} 条目数={}", instanceId, turn.queue().size());
    }
  }

  /** {@inheritDoc} 子体进程结束 / 被回收：队列与<b>尚未使用</b>的放行一起清（已用的那些本来就无效了）。 */
  @Override
  public void recycle(String instanceId) {
    Objects.requireNonNull(instanceId, "instanceId");
    Turn turn = turns.remove(instanceId);
    List<String> mine = new ArrayList<>();
    int unused = 0;
    synchronized (grants) {
      for (Map.Entry<String, Grant> entry : grants.entrySet()) {
        if (entry.getKey().startsWith(instanceId + KEY_SEPARATOR)) {
          mine.add(entry.getKey());
          if (!entry.getValue().used()) {
            unused++;
          }
        }
      }
      for (String key : mine) {
        grants.remove(key);
      }
    }
    if (turn != null || !mine.isEmpty()) {
      LOG.info(
          "子体进程结束/被回收 ⇒ 清掉它的本轮队列与放行条款 instance={} 队列条目={} 放行条={}（其中未用={}）",
          instanceId,
          turn == null ? 0 : turn.queue().size(),
          mine.size(),
          unused);
    }
  }

  // ---------- 内部 ----------

  /** 取/建该实例的簿子（到点的旧簿子当场换掉——不给"跨轮复用"留机会）。 */
  private PendingDecisionQueue queueFor(String instanceId, long nowEpochMs) {
    synchronized (turns) {
      Turn existing = turns.get(instanceId);
      if (existing != null && !existing.expired(nowEpochMs)) {
        return existing.queue();
      }
      if (existing != null) {
        LOG.warn("父侧本轮队列已到点，重建（上一轮没有被收尾） instance={}", instanceId);
      }
      // 这份队列**只**用来登记与读条目（父侧结算口拿它的快照自建临时包）⇒ 它从不产出 ProposeRequest，
      // 那个 caller 分量在这里是惰性的（写 DEFAULT 只为构造合法，不代表任何权限判断）。
      PendingDecisionQueue fresh = new PendingDecisionQueue(instanceId, AccessToken.DEFAULT);
      turns.put(instanceId, new Turn(fresh, nowEpochMs + turnTtlMillis));
      return fresh;
    }
  }

  /**
   * 这次调用是不是"本进程派出去的子体"发的：是 ⇒ 返回它的实例 id；不是 ⇒ {@code null}。
   *
   * <p>判据全部来自<b>链上身份</b>（模型给不出、子进程改不了）：深度 &gt; 0（子体 = 调用者深度 + 1，主 Agent 与外部面都是 0） 且身份已穿透。
   */
  private static String subagentInstanceId(ToolContext context) {
    AgentIdentity identity = context.identity();
    if (identity == null || identity.isUnknown() || identity.depth() <= 0) {
      return null;
    }
    return identity.instanceId();
  }

  /** 放行条的键（实例 + 包 id + 条目序号）。 */
  private static String key(String instanceId, String packageId, int index) {
    return instanceId + KEY_SEPARATOR + packageId + KEY_SEPARATOR + index;
  }

  // ---------- 诊断面（测试与装配日志用；不参与任何判定） ----------

  /** 当前跟踪着簿子的实例 id（顺序不保证）；只读快照。 */
  public Set<String> trackedInstances() {
    synchronized (turns) {
      return new LinkedHashSet<>(turns.keySet());
    }
  }

  /** 当前放行条张数（含已用的；只读快照）。 */
  public int grantCount() {
    synchronized (grants) {
      return grants.size();
    }
  }
}
