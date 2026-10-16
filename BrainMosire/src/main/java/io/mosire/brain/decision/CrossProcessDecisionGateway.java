package io.mosire.brain.decision;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.decision.DecisionItemDigest;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PackageState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>父侧的跨进程"提交决策包"结算口</b>（设计 §5quinquies ② 的父侧那一半，P3a）：子 Agent 进程送来的"待裁定的条目 + kind"
 * 在这里被<b>结算成一份终局裁决</b>——跑级联、必要时投既有审批面等人、把结果如实交回子体。
 *
 * <p><b>它做什么、不做什么</b>：本类<b>只回裁决</b>。它<b>不</b>执行被批子集（执行在子体，用子体自己的权限集与身份—— 设计 §5quinquies
 * ①「父侧不得替子体执行」；父侧回传的裁决者只是<b>裁决者身份</b>，供记录与审计）， <b>不</b>建持久状态、<b>不</b>落库、<b>不</b>发 {@code
 * decision.package.*} 事件。
 *
 * <h2>★ 临时包：建即裁、裁完即弃（不变量 1 的落点）</h2>
 *
 * <p>{@link DecisionPackages}（包登记表）<b>一次都不碰</b>：本类只构造一个<b>在内存里活不过这一次调用</b>的 {@link DecisionPackage}
 * 值对象，把它交给 {@link DecisionCascade#run}——级联在结构上<b>只要</b>一个包值 （{@code id / proposerKey /
 * items}），从不需要它在表里。于是：
 *
 * <ul>
 *   <li>它<b>落不了事件库</b>（{@code decision.package.*} 事件是 {@code DecisionApprovalBridge} 发的，本类不碰总线）；
 *   <li>它<b>不进 {@code pending()} 可见面</b>（那张表里根本没有它）；
 *   <li>它<b>不占"一实例一个未决包"的名额</b>（那个不变量由 {@code DecisionPackages.propose} 判，本类不调它），
 *       所以子体在自己进程里的那个包（真正的决策单元）与父侧这一次结算不会互相挡住；
 *   <li>子体<b>不会</b>因此在父侧留下第二份历史——父侧的审计走 {@code approval.requested/decided} 事件 （装配层已把它们镜像进主 Agent
 *       的事件库），子体的历史仍在子体自己的 {@code events.db}。
 * </ul>
 *
 * <p><b>代价照实记</b>：{@link DecisionCascade} 的轨迹表不淘汰（P2b 的既有取舍），所以每次提交会在父进程里留下
 * 一条<b>只有轨迹、没有包</b>的记录（key = 本次的临时包 id，形如 {@code xp-…}）。它不是历史、不落盘、没有读口
 * ——"父侧不留存临时包"仍然成立，但那块内存要等进程退出才回收。
 *
 * <h2>★ 失败语义：三条路都归零批准（fail-closed）</h2>
 *
 * <p>设计 §5quinquies ② 的失败语义表在 {@link #failClosed} 的三个调用点落地：
 *
 * <ol>
 *   <li><b>无可用通道</b>（编排器在投递前就拒）：{@link #APPROVAL_UNAVAILABLE}；
 *   <li><b>无人答复</b>（窗口到期）：同样 {@link #APPROVAL_UNAVAILABLE}——本类分不出"没人答"与"没通道" （编排器只返回决定本身，可编程出口不存在，同
 *       {@code DecisionApprovalBridge} 的边界），所以两者共用一个码， 由 WARN 里的观测依据说明是哪一条；
 *   <li><b>判定失败</b>（级联抛异常 / 编排抛异常 / 轨迹补齐失败）：{@link #SETTLEMENT_FAILED}。
 * </ol>
 *
 * <p>★ <b>与审批桥有一处刻意的不同</b>：审批桥在"没人答复"时把包<b>当场作废</b>（它手里有一个 {@code PROPOSED} 的包）；
 * 本类没有包可作废，它落的是<b>零批准</b>——子体据此<b>零落盘</b>，方向一致（不静默执行）。
 *
 * <h2>谁会到人：只有级联上报的那些条目</h2>
 *
 * <p>级联（{@link DecisionCascade}，父侧才有派生链知识）先跑：{@link AskKind#MODE_LIMITED} 的条目可能被某一级预裁定批准， {@link
 * AskKind#SENSITIVE} 的<b>强制到人</b>。剩下的那些才组<b>一条</b>审批请求（一个包 = 一条请求，不拆）。
 *
 * <p><b>★ 请求的 {@code kind} 恒取 {@link AskKind#SENSITIVE}</b>（理由写在 {@link #requestFor}）：请求只到人，
 * <b>不</b>交上级判定闸代批。代价是 {@code MODE_LIMITED} 的条目在跨进程这条路上拿不到"上级 LLM 代批"； 它们由级联按能力逐级预裁定，剩下的到人。
 *
 * <h2>★ 幂等键 {@code requestId}（P3b，设计 §5quinquies ⑤-3）：重试不得再问人一次</h2>
 *
 * <p><b>它解决的是什么</b>：子体"提交成功、落库前崩掉"⇒ 重试。没有幂等键的话，父侧会现造一条新的审批请求（{@code ap-…}）， 人<b>再答一遍</b>同一个包。有了
 * {@code requestId}（= 子体本地包的 id），父侧按它缓存"上一次的裁决"，命中即 <b>原样返回</b>、<b>不再投审批面</b>。
 *
 * <p><b>缓存的形状（它是"裁决结果"，不是"留存临时包"——不变量 1 的落点）</b>：
 *
 * <ul>
 *   <li>键 = <b>{@code proposerKey + 分隔符 + requestId}</b>（不是裸 {@code requestId}）：包 id 是每个进程各自 {@code
 *       dp-N} 编号的，"两个子体都有一个 dp-1"是常态——裸键会让 A 的回执被 B 命中，那是<b>错裁决</b>，比"多问一次人"危险得多；
 *   <li>值 = <b>内容指纹（12 位十六进制摘要）+ {@link Verdict}</b>：不存 {@link DecisionPackage}、不存条目、不存 {@link
 *       PackageItem#args()}，也不进 {@link DecisionPackages} 那张表、不落盘、不进程外可见；
 *   <li>有界：上限 {@value #IDEMPOTENCY_CACHE_LIMIT} 条，<b>LRU</b>（满了淘汰最久未用的一条，{@link LinkedHashMap} 访问序
 *       + {@code removeEldestEntry}）；
 *   <li><b>只缓存"返回过的裁决"</b>：抛出的（形状错 / {@code PROPOSER_UNKNOWN}）不入缓存——它们不是"一次没被受理的提交"， 而是调用方
 *       bug，下次仍应响亮。
 * </ul>
 *
 * <p>★ <b>同键不同内容 ⇒ 具名拒</b>（{@link #INVALID_ITEMS} 那一档）：同一个 {@code requestId} 带着另一批条目回来时，
 * 把旧裁决交回去等于用一份针对 A 的裁决去回答 B，也等于"重放一次已经问过人的请求"。两者都不做——拒，且<b>不再投</b>审批面。 正常路径下这不可能是调用方 bug
 * 以外的情形：{@code requestId} 就是那个包的 id，条目与它同源。
 *
 * <p>★ <b>诚实边界（如实记）</b>：子体的包表是<b>内存态</b>（{@code DecisionPackages} 不落盘），进程崩溃重启后 新一轮会铸一个新包 id ⇒ 新
 * {@code requestId} ⇒ 幂等缓存<b>保护不了跨重启的重试</b>。它保护的是"同一次运行里的重试"
 * （工具调用超时重发、落库失败重试）。跨崩溃幂等要有持久的包表或一个持久的幂等键来源，不在本单范围。
 *
 * <h2>★ 身份不信入参（防冒充）</h2>
 *
 * <p>{@code proposerKey} 由子体<b>自报</b>，但本类不接受它作为"身份声明"：它在 {@link LevelVerifier}（装配层接的
 * 实例注册表视角）里必须<b>解析得出</b>一个真实的 {@link DecisionCascade.Level}，否则<b>响亮具名拒</b> （{@link
 * #PROPOSER_UNKNOWN}，消息里只有那个 key，不含参数明文）。解析得出之后，级联用的权限集/档位/父级 <b>全部来自注册表那一份</b>，入参里任何"我是谁 /
 * 我有什么权限"的字段都不参与判定。
 *
 * <p><b>另一层在工具侧</b>（{@code SubmitDecisionPackageTools}）：调用者身份（{@code ToolContext.identity()}） 必须与
 * {@code proposerKey} 一致——子体只能替<b>自己</b>提交，主 Agent 用自己的 id 提交。本类不做这一步（它拿不到调用上下文），
 * 两者合起来才是完整的"不许替别人提"。
 *
 * <h2>密钥纪律（红线 6）</h2>
 *
 * <p>参数明文只活在 {@link Request.Item#args()} 与临时包内 {@link PackageItem#args()} 上<b>这一趟调用</b>里：摘要经 {@link
 * DecisionItemDigest} 派生一次（唯一实现点），日志与返回值只出现包 id、工具名、条目 index、级联轨迹与理由文字， <b>没有任何一处</b>把 {@code args}
 * 写进消息或日志。
 */
public final class CrossProcessDecisionGateway {

  private static final Logger LOG = LoggerFactory.getLogger(CrossProcessDecisionGateway.class);

  /**
   * 具名拒：{@code proposerKey} 在实例注册表里查不到（防冒充；消息里只有那个 key）。
   *
   * <p>它同时覆盖"这个 id 从来不存在"与"这个 id 不是你能自报的"——两者返回<b>同一个</b>码与同一种消息形状， 不给调用者一个"存在性 oracle"（与 {@code
   * ResourceDeniedException} 那条口径同源）。
   */
  public static final String PROPOSER_UNKNOWN = "PROPOSER_UNKNOWN";

  /** 入参形状错（条目为空 / 工具名空白 / index 重复或为负）。 */
  public static final String INVALID_ITEMS = "INVALID_ITEMS";

  /**
   * fail-closed：<b>无可用通道</b>或<b>无人答复</b>（或人明确拒）——零批准。
   *
   * <p>前两者共用一个码是刻意的：审批编排器只返回决定本身，"是超时还是没通道"没有可编程出口（RB2b 已把这条记成边界， {@code
   * DecisionApprovalBridge#noAnswerReason} 也只在日志里分）。本类<b>不编</b>一个它区分不出的原因码。
   */
  public static final String APPROVAL_UNAVAILABLE = "APPROVAL_UNAVAILABLE";

  /** fail-closed：判定过程本身失败（级联 / 编排 / 轨迹补齐抛异常）——零批准，且如实说"没判出来"。 */
  public static final String SETTLEMENT_FAILED = "SETTLEMENT_FAILED";

  /** {@link Verdict#deciderLevel()}：整包由级联预裁定批准，没问过人（{@code finalizedBy} = 拍板的那一级）。 */
  public static final String DECIDER_AUTO = "AUTO";

  /**
   * {@link Verdict#deciderLevel()}：人拍的板（{@code finalizedBy} = {@link
   * DecisionCascade#HUMAN_DECIDER}）。
   */
  public static final String DECIDER_HUMAN = "HUMAN";

  /**
   * {@link Verdict#deciderLevel()}：没有终局裁决者——零批准（fail-closed 那几条路）。
   *
   * <p>它不是"某一级拒的"，而是"<b>没人裁成</b>"：{@code finalizedBy} 为 null、{@code trail} 为 null。
   */
  public static final String DECIDER_NONE = "NONE";

  /** 逐条理由：被级联预裁定批准。 */
  private static final String REASON_PRE_APPROVED = "级联预裁定批准（该级有能力执行这条）";

  /** 逐条理由：人批准的上报条目。 */
  private static final String REASON_HUMAN_APPROVED = "人批准（该条上报到人）";

  /** 逐条理由：没被批准。 */
  private static final String REASON_REJECTED = "未获批准（零批准，子体据此零落盘）";

  /** 人拍板的理由（进临时包那份轨迹的人那一级；与 {@code DecisionApprovalBridge} 的口径同源）。 */
  private static final String HUMAN_NOTE = "审批链批准上报到人的条目";

  /** 临时包 id 的前缀（看着就知道它<b>不是</b>包登记表里那个 {@code dp-N} 序列的一员）。 */
  private static final String TRANSIENT_PACKAGE_PREFIX = "xp-";

  /**
   * 幂等缓存的上限（条数）：满了淘汰最久未用的那一条（见类注释的"有界"）。
   *
   * <p>为什么是 256：一次提交一条，条目本身很小（几个 index + 一份轨迹）；256 覆盖"一个部署里最近 256 次提交"， 足够挡住重试重放，也不会让内存随运行时长增长。
   */
  public static final int IDEMPOTENCY_CACHE_LIMIT = 256;

  /**
   * {@code requestId} 的长度上限（字符）——它是<b>调用方给的键</b>，无上限地收会让一个超长字符串既进日志又当缓存键。
   *
   * <p>128 对"子体包 id（{@code dp-N}）"绰绰有余；超限 ⇒ 具名拒（{@link #INVALID_ITEMS}）。
   */
  public static final int MAX_REQUEST_ID_CHARS = 128;

  /** 幂等键的两段分隔（{@code proposerKey} 与 {@code requestId} 都不会含它：两者都是实例/包 id）。 */
  private static final char IDEMPOTENCY_KEY_SEPARATOR = '\u0000';

  /**
   * <b>实例注册表视角</b>（装配层接）：{@code proposerKey} ⇒ 那一级（权限集 / 档位 / 父级）。
   *
   * <p>它与 {@link DecisionCascade.Chain} 是<b>同一份</b>注册表的两种用法：本类用它核对"提出者确实在册"（防冒充），
   * 级联用它上溯派生链。两件事必须看同一份表，否则会出现"核对了 A、级联读了 B"。
   *
   * <p>{@code Optional.empty()} = 查不到：拒（<b>不是</b>"按空权限集放行"）。
   */
  @FunctionalInterface
  public interface LevelVerifier {

    Optional<DecisionCascade.Level> level(String instanceId);
  }

  private final LevelVerifier levelLookup;
  private final DecisionCascade cascade;
  private final ApprovalCoordinator coordinator;
  private final PendingApprovals pending;

  /**
   * 幂等缓存（{@code proposerKey + requestId ⇒ 内容指纹 + 裁决}）：有界 LRU，见类注释。
   *
   * <p>用 {@link Collections#synchronizedMap} 包一层：工具面可能被多个子体的调用线程并发进入（每个子进程一条 MCP 链）， 而 {@link
   * LinkedHashMap} 的访问序在读时也会改结构。锁的粒度=一次查/一次写，缓存不参与判定（判定仍在 {@link #settle} 里）。
   */
  private final Map<String, Cached> idempotent =
      Collections.synchronizedMap(new IdempotencyCache());

  /**
   * @param levelLookup 实例注册表视角（装配层接；见 {@link LevelVerifier}）
   * @param cascade 逐级预裁定控制器（父侧持有派生链知识）；<b>可 null</b> = 未装配 ⇒ 不做级联 ⇒ <b>整包到人</b> （与 P2b
   *     之前的口径一致：没有级联就一条都不预批准）
   * @param coordinator 既有审批编排器（含闸链与 fail-closed 口径；本类<b>不</b>新建第二套）
   * @param pending 装配层那<b>唯一</b>一份待审批登记表——用来分辨"审批面上留下过登记项（人答的）"与 "闸链 / 无通道 / 超时"（同 {@code
   *     DecisionApprovalBridge#registered} 的判据）
   */
  public CrossProcessDecisionGateway(
      LevelVerifier levelLookup,
      DecisionCascade cascade,
      ApprovalCoordinator coordinator,
      PendingApprovals pending) {
    this.levelLookup = Objects.requireNonNull(levelLookup, "levelLookup");
    this.cascade = cascade;
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.pending = Objects.requireNonNull(pending, "pending");
  }

  /**
   * 一次提交（{@code items} 顺序 = 子体包内顺序；{@code index} 由子体定，本类原样回传）。
   *
   * @param proposerKey 子体<b>自报</b>的实例 id（父侧与注册表核对；认不出 ⇒ 具名拒）
   * @param items 待裁定的条目（至少一条；{@code index} 互不相同）
   * @param requestId <b>幂等键</b>（可选，P3b）：子体本地那个包的 id。空白 = 没给 ⇒ <b>每次都是新请求</b> （保持 P3a 的向后可用）；给了 ⇒
   *     命中缓存即原样返回上次裁决、不再投审批面（见类注释）。
   */
  public record Request(String proposerKey, List<Item> items, String requestId) {

    public Request {
      proposerKey = proposerKey == null ? "" : proposerKey.strip();
      items = items == null ? List.of() : List.copyOf(items);
      requestId = requestId == null ? "" : requestId.strip();
    }

    /** P3a 的两参形状（{@code requestId} 缺省 = 不幂等）：既有调用方与用例逐字不变。 */
    public Request(String proposerKey, List<Item> items) {
      this(proposerKey, items, "");
    }
  }

  /**
   * 幂等缓存里的一条：<b>内容指纹 + 裁决</b>（没有条目、没有明文、没有包——见类注释）。
   *
   * @param fingerprint 那一次提交的条目指纹（{@link #fingerprint}）：同键再来时用它认"是不是同一批条目"
   * @param verdict 那一次的裁决（原样返回，不重算、不再投审批面）
   */
  private record Cached(String fingerprint, Verdict verdict) {}

  /**
   * 有界 LRU 缓存（{@link LinkedHashMap} 访问序 + 满即淘汰最久未用）。
   *
   * <p>写成具名嵌套类而不是匿名类：匿名子类会让 SpotBugs 报 {@code SE_NO_SERIALVERSIONID}（{@code LinkedHashMap} 是 {@code
   * Serializable}）——那条告警是真的，加一个显式 {@code serialVersionUID} 才是它的正当处理（缓存本身不该被序列化， 这里只是把"我确实考虑过"写下来）。
   */
  private static final class IdempotencyCache extends LinkedHashMap<String, Cached> {

    private static final long serialVersionUID = 1L;

    IdempotencyCache() {
      super(16, 0.75f, true);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
      return size() > IDEMPOTENCY_CACHE_LIMIT;
    }
  }

  /**
   * 子体送来的一条待裁定条目（{@code args} 是<b>明文</b>——只进临时包内条目与本趟调用，见类注释的密钥纪律）。
   *
   * @param index 条目在子体包内的身份（本类原样回传；同一请求内唯一）
   * @param toolName 工具名
   * @param args 调用参数明文（顶层不可变快照；顶层不得含 null 键/值）
   * @param classKey 子体侧工具自报的闸位类键（<b>不</b>参与本类的判定：父侧这次请求恒用包级 classKey，见 {@link #requestFor}）
   * @param askKind 为什么问（{@code null} = 没给 ⇒ 按 {@link AskKind#SENSITIVE} 办 = 强制到人，最保守的一侧）
   */
  public record Item(
      int index, String toolName, Map<String, Object> args, String classKey, AskKind askKind) {

    public Item {
      if (index < 0) {
        throw new IllegalArgumentException("index 不得为负（它是条目在子体包内的身份）：" + index);
      }
      toolName = toolName == null ? "" : toolName.strip();
      if (toolName.isBlank()) {
        throw new IllegalArgumentException("toolName 不得为空白（它决定这条走哪条闸链）");
      }
      args = args == null ? Map.of() : Map.copyOf(args);
      classKey = classKey == null ? "" : classKey;
    }
  }

  /**
   * 结算结果（工具据此拼出参；<b>不含参数明文</b>）。
   *
   * @param approvedIndexes 终局被批的条目（子体只准执行这些）
   * @param rejectedIndexes 没被批准的条目（fail-closed 时 = 全部条目）
   * @param perItemReasons 逐条理由（{@code index ⇒ 一行理由}；fail-closed 时为 {@code null} = 未逐条说明）
   * @param trail 级联轨迹（{@code null} = fail-closed——不编一份空轨迹）
   * @param finalizedBy 拍板者（{@link DecisionCascade#HUMAN_DECIDER} / 某一级实例 id；{@code null} = 没人拍板）
   * @param deciderLevel {@link #DECIDER_AUTO} / {@link #DECIDER_HUMAN} / {@link #DECIDER_NONE}
   */
  @SuppressFBWarnings("EI_EXPOSE_REP")
  public record Verdict(
      Set<Integer> approvedIndexes,
      Set<Integer> rejectedIndexes,
      Map<Integer, String> perItemReasons,
      DecisionCascade.DecisionTrail trail,
      String finalizedBy,
      String deciderLevel) {

    /**
     * ★ <b>{@code EI_EXPOSE_REP} 的记录级抑制（写明理由，同 {@code DecisionCascade.PreDecision} 的处置）</b>： 两个
     * {@code Set} 与那个 {@code Map} 在规范构造里已做防御性拷贝 + 不可变视图；SpotBugs 不认这个形状，且把告警钉在
     * <b>记录分量声明</b>那一行（record 的隐式成员不吃方法级注解）⇒ 抑制只能落在类型上。这里<b>不</b>为消误报改数据形状。
     */
    public Verdict {
      approvedIndexes =
          approvedIndexes == null
              ? Set.of()
              : Collections.unmodifiableSet(new LinkedHashSet<>(approvedIndexes));
      rejectedIndexes =
          rejectedIndexes == null
              ? Set.of()
              : Collections.unmodifiableSet(new LinkedHashSet<>(rejectedIndexes));
      perItemReasons =
          perItemReasons == null
              ? null
              : Collections.unmodifiableMap(new LinkedHashMap<>(perItemReasons));
    }
  }

  /**
   * 结算一次提交：核对提出者 ⇒ 建临时包 ⇒ 跑级联 ⇒（必要时）投审批面等人 ⇒ 交回终局裁决。
   *
   * <p><b>{@code requestId} 在场时先查幂等缓存</b>（P3b，见类注释）：命中且内容一致 ⇒ <b>原样返回</b>上次裁决，
   * <b>不再投审批面</b>（重试不得再问人一次）；同键但内容不同 ⇒ 具名拒 {@link #INVALID_ITEMS}。 没给 {@code requestId} ⇒
   * 每次都走完整结算（P3a 的行为逐字不变）。
   *
   * <p><b>业务上的"判不出来"不抛异常</b>：那些情形归到<b>零批准的 {@link Verdict}</b>（fail-closed 那三条路），
   * 由调用方决定怎么表达。抛出的只有<b>入参形状错误</b>（{@link IllegalArgumentException}）——那是调用方 bug， 不是一种结算结果。
   *
   * @param request 子体送来的条目（非空、index 唯一）
   * @return 终局裁决（{@code approvedIndexes} 空 = 零批准）
   * @throws IllegalArgumentException 入参形状错、幂等键复用且内容不同，或提出者不在实例注册表里（{@link #PROPOSER_UNKNOWN}）
   */
  public Verdict submit(Request request) {
    Objects.requireNonNull(request, "request");
    validate(request);
    if (request.requestId().isEmpty()) {
      // 缺省不传 = 每次都是新请求（向后可用；P3a 的形状逐字保留）
      return settle(request);
    }
    String key = idempotencyKey(request);
    String fingerprint = fingerprint(request);
    Cached hit = idempotent.get(key);
    if (hit != null) {
      if (!hit.fingerprint().equals(fingerprint)) {
        // ★ 同键不同内容：把旧裁决交回去等于用一份针对 A 的裁决回答 B；重放一次已经问过人的请求同样不做
        LOG.warn(
            "跨进程决策包提交被拒（requestId 复用但条目不同，按入参形状错办）proposerKey={} requestId={} items={}",
            request.proposerKey(),
            request.requestId(),
            request.items().size());
        throw new IllegalArgumentException(
            INVALID_ITEMS
                + "：requestId="
                + request.requestId()
                + " 已经被另一批条目用过（幂等键复用必须内容一致；要提新的一批请用新的 requestId）");
      }
      LOG.info(
          "跨进程决策包提交命中幂等缓存（原样返回上次裁决，不再投审批面）proposerKey={} requestId={} deciderLevel={} approved={}",
          request.proposerKey(),
          request.requestId(),
          hit.verdict().deciderLevel(),
          hit.verdict().approvedIndexes().size());
      return hit.verdict();
    }
    Verdict verdict = settle(request);
    idempotent.put(key, new Cached(fingerprint, verdict));
    return verdict;
  }

  /**
   * 一次真实结算（幂等缓存<b>之外</b>的那一段；P3a 的 {@code submit} 逐字搬到这里）。
   *
   * <p>{@link #submit} 是它唯一的调用方：查缓存 / 写缓存都在那一层，本方法只管"判一次"。
   */
  private Verdict settle(Request request) {
    Optional<DecisionCascade.Level> found = levelLookup.level(request.proposerKey());
    if (found.isEmpty()) {
      // ★ 防冒充：自报的 key 在注册表里解析不出 ⇒ 响亮具名拒（不再往下走；消息里只有 key，不含参数明文）
      LOG.warn(
          "跨进程决策包提交被拒（提出者不在实例注册表里，疑似冒充）：proposerKey={} items={}",
          request.proposerKey(),
          request.items().size());
      throw new IllegalArgumentException(
          PROPOSER_UNKNOWN + "：proposerKey=" + request.proposerKey() + " 不在实例注册表里");
    }
    DecisionPackage transientPackage = transientPackage(request, found.get());
    Optional<DecisionCascade.DecisionTrail> trail;
    try {
      trail =
          cascade == null
              ? Optional.empty()
              : Optional.of(cascade.run(transientPackage, askKinds(request.items())));
    } catch (RuntimeException failure) {
      // 判定失败 ⇒ 零批准（fail-closed 第 ③ 条）。不把"没判出来"洗成"没人上报 ⇒ 整包到人"
      LOG.error(
          "跨进程决策包级联判定失败 ⇒ 零批准（fail-closed）：proposerKey={} packageId={}",
          request.proposerKey(),
          transientPackage.id(),
          failure);
      return failClosed(request.items(), SETTLEMENT_FAILED, "级联判定失败");
    }
    Set<Integer> escalated =
        trail.map(DecisionCascade.DecisionTrail::escalatedToHuman).orElse(null);
    if (escalated == null) {
      // 没有级联（cascade == null）⇒ 整包到人（本类之前的口径），上报集合就是全部条目
      escalated = allIndexes(request.items());
    }
    if (escalated.isEmpty()) {
      DecisionCascade.DecisionTrail complete = trail.orElseThrow();
      Set<Integer> approved = complete.finalApproved();
      LOG.info(
          "跨进程决策包由级联整包批准（未投审批面）proposerKey={} packageId={} approved={} finalizedBy={}",
          request.proposerKey(),
          transientPackage.id(),
          approved,
          complete.finalizedBy());
      return new Verdict(
          approved,
          complement(request.items(), approved),
          reasons(request.items(), approved, Set.of()),
          complete,
          complete.finalizedBy(),
          DECIDER_AUTO);
    }
    ApprovalRequest approvalRequest = requestFor(transientPackage, escalated);
    ApprovalDecision answer;
    try {
      answer = coordinator.decide(approvalRequest);
    } catch (RuntimeException failure) {
      LOG.error(
          "跨进程决策包投审批面异常 ⇒ 零批准（fail-closed）：proposerKey={} requestId={}",
          request.proposerKey(),
          approvalRequest.id(),
          failure);
      return failClosed(request.items(), SETTLEMENT_FAILED, "审批面投递异常");
    }
    boolean answered =
        answer == ApprovalDecision.APPROVE_ONCE || answer == ApprovalDecision.APPROVE_SESSION;
    if (!answered) {
      boolean registered = registered(approvalRequest);
      LOG.warn(
          "跨进程决策包零批准（fail-closed）：proposerKey={} requestId={} answer={} 审批面上登记项={}"
              + "（有登记项 ⇒ 人答复为拒；没有 ⇒ 无可用通道或无人答复）",
          request.proposerKey(),
          approvalRequest.id(),
          answer,
          registered ? "在（人答的）" : "不在（无通道/超时/闸链终局拒）");
      return failClosed(request.items(), APPROVAL_UNAVAILABLE, "没有拿到放行答复");
    }
    // 人的答复 = 上报集合里的条目全批（本类不提供逐条例外的第二套形状：逐条例外走决策面 HTTP 那条既有路）
    Set<Integer> humanApproved = escalated;
    if (trail.isEmpty()) {
      // 没有级联 ⇒ 没有轨迹可补齐，"人批了上报的那些"就是终局（与 P2b 之前"整包到人"的行为同形）
      LOG.info(
          "跨进程决策包由人整包批准（无级联）proposerKey={} packageId={} requestId={} approved={}",
          request.proposerKey(),
          transientPackage.id(),
          approvalRequest.id(),
          humanApproved);
      return new Verdict(
          humanApproved,
          complement(request.items(), humanApproved),
          reasons(request.items(), humanApproved, humanApproved),
          null,
          DecisionCascade.HUMAN_DECIDER,
          DECIDER_HUMAN);
    }
    DecisionCascade.DecisionTrail completed =
        completeTrail(transientPackage.id(), humanApproved, approvalRequest);
    if (completed == null) {
      // 轨迹补不齐 ⇒ 不编一份"看起来被批了"的结果（零批准；fail-closed 第 ③ 条那一档）
      return failClosed(request.items(), SETTLEMENT_FAILED, "裁决轨迹补齐失败");
    }
    Set<Integer> approved = completed.finalApproved();
    LOG.info(
        "跨进程决策包裁决已回填 proposerKey={} packageId={} requestId={} approved={} 上报={} finalizedBy={}",
        request.proposerKey(),
        transientPackage.id(),
        approvalRequest.id(),
        approved,
        escalated,
        completed.finalizedBy());
    return new Verdict(
        approved,
        complement(request.items(), approved),
        reasons(request.items(), approved, humanApproved),
        completed,
        completed.finalizedBy(),
        DECIDER_HUMAN);
  }

  /**
   * 临时包（<b>值对象，不进任何表</b>）：{@code id} 每次现造，{@code proposerKey} 用注册表里那个 key， {@code caller}
   * 取<b>注册表里那一级的令牌桶</b>——不是入参里的任何东西（入参根本没有身份字段）。
   *
   * <p>{@code caller} 不参与级联的能力判定（判据只看权限集 / 档位 / 资源面），它只是让这个值对象的身份面与它声称的 提出者一致。
   *
   * <p>{@code argsDigest} / {@code summary} 由 {@link DecisionItemDigest} 派生（裁定 6 之后的唯一实现点，
   * 本类不另写一份算法）。
   */
  private static DecisionPackage transientPackage(Request request, DecisionCascade.Level proposer) {
    List<PackageItem> items = new ArrayList<>(request.items().size());
    for (Item item : request.items()) {
      items.add(
          new PackageItem(
              item.index(),
              item.toolName(),
              item.args(),
              DecisionItemDigest.argsDigest(item.args()),
              DecisionItemDigest.itemSummary(item.toolName(), item.args())));
    }
    long now = System.currentTimeMillis();
    return new DecisionPackage(
        TRANSIENT_PACKAGE_PREFIX + UUID.randomUUID(),
        request.proposerKey(),
        proposer.permissions().grantedToken(),
        items,
        now,
        // 到点时刻必须为正（包的形状要求）。实际等待上限仍由编排器自己的 timeout 决定——同
        // DecisionApprovalBridge 的遗留口径，本单不改审批链的公开面
        now + DecisionPackages.DEFAULT_DEADLINE_MILLIS,
        PackageState.PROPOSED,
        null);
  }

  /**
   * 条目 index ⇒ {@link AskKind}（级联据此判"这条能否被预裁定"）。
   *
   * <p><b>没给 ⇒ {@link AskKind#SENSITIVE}</b>（强制到人）：与 {@link DecisionCascade#run} 的缺料口径同源
   * ——"没说明为什么问"落进人手里，不落进任何 Agent 的代批面。
   */
  private static Map<Integer, AskKind> askKinds(List<Item> items) {
    Map<Integer, AskKind> kinds = new LinkedHashMap<>();
    for (Item item : items) {
      kinds.put(item.index(), item.askKind() == null ? AskKind.SENSITIVE : item.askKind());
    }
    return kinds;
  }

  /**
   * 把上报给人的那些条目投成<b>一条</b>审批请求（一个包 = 一条请求；{@link DecisionApprovalBridge#CLASS_KEY}
   * 这个冻结字面量与包级唯一桶键照旧，所以"包级裁决不接受会话放行"这条收窄在这里也成立）。
   *
   * <p><b>为什么 {@code kind} 恒取 {@link AskKind#SENSITIVE}</b>：本仓的 {@code MODE_LIMITED} 分流是给主进程内那条
   * 审批链用的，它的"上级判定闸"要读 {@code SuperiorJudgeDetailRegistry} 里登记的逐条<b>明文</b>明细才谈得上代批 （用户 2026-10-16
   * 裁定「肯定展示全部内容啊」）。跨进程这条路上，明文是另一个进程送进来的<b>不可信数据</b>； 为了"让上级看得见"而把它登记进父侧的判定明细面，等于为它开一条新的数据通路——本单不开。取
   * {@code SENSITIVE} 的后果只是<b>更保守</b>：请求只到人，{@code SuperiorJudgeGate} 自己会让开。
   *
   * <p><b>摘要不含明文</b>：{@code summary} 只有包 id、提出者、条数与工具名；{@code digest} 复用 {@code
   * DecisionApprovalBridge#packageDigest}（对包内已脱敏的 {@code argsDigest} 再取一次短摘要， <b>唯一实现点</b>）。
   */
  private static ApprovalRequest requestFor(
      DecisionPackage transientPackage, Set<Integer> escalated) {
    PackageItem first = firstEscalated(transientPackage, escalated);
    return new ApprovalRequest(
        "ap-" + UUID.randomUUID(),
        first.toolName(),
        DecisionApprovalBridge.CLASS_KEY,
        summary(transientPackage, escalated),
        DecisionApprovalBridge.packageDigest(transientPackage),
        System.currentTimeMillis(),
        transientPackage.deadlineEpochMs(),
        DecisionApprovalBridge.packageCallerKey(transientPackage),
        // 提出者 = 子体实例：裁决者要看得见"谁在问"（同一个 id 也进轨迹的归属面）
        transientPackage.proposerKey(),
        AskKind.SENSITIVE,
        "");
  }

  /** 上报条目里的第一条（包内条目至少一条、{@code escalated} 非空是本方法的调用前提）。 */
  private static PackageItem firstEscalated(DecisionPackage pkg, Set<Integer> escalated) {
    for (PackageItem item : pkg.items()) {
      if (escalated.contains(item.index())) {
        return item;
      }
    }
    throw new IllegalStateException(
        "上报集合与包内条目不一致（上报=" + escalated + " 包=" + pkg.id() + "）——不编造一个条目");
  }

  /** 人读一行（只含公开面信息：包 id、提出者、上报条数、工具名列表）。 */
  private static String summary(DecisionPackage pkg, Set<Integer> escalated) {
    List<String> tools = new ArrayList<>();
    for (PackageItem item : pkg.items()) {
      if (escalated.contains(item.index())) {
        tools.add(item.toolName());
      }
    }
    return "子 Agent 提交的决策包 "
        + pkg.id()
        + "（提出者 "
        + pkg.proposerKey()
        + "）：共 "
        + pkg.items().size()
        + " 条，其中 "
        + tools.size()
        + " 条上报到人（"
        + String.join("、", tools)
        + "）待裁决";
  }

  /**
   * 审批面上还留不留着这条请求的登记项（人答的 ⇒ 在；闸链终局 / 无通道 / 超时 ⇒ 不在）。
   *
   * <p>查登记表失败按<b>不在</b>处理：拿不准就不声称有人答过——fail-closed 朝向"零批准"那一侧，同 {@code
   * DecisionApprovalBridge#registered} 的口径。
   */
  private boolean registered(ApprovalRequest request) {
    try {
      return pending.get(request.id()).isPresent();
    } catch (RuntimeException failure) {
      LOG.warn("查审批登记项失败，按「无人答复」处理（fail-closed） requestId={}", request.id(), failure);
      return false;
    }
  }

  /**
   * 把人那一级补齐进临时包的轨迹（人批的<b>只</b>是上报的那些条目）。补不齐 / 没有级联 ⇒ {@code null}，
   * 调用方按<b>零批准</b>处理——绝不退回"没有人那一级"的旧轨迹（那会把一次没记全的裁决说成"级联自己批的"）。
   */
  private DecisionCascade.DecisionTrail completeTrail(
      String packageId, Set<Integer> humanApproved, ApprovalRequest request) {
    if (cascade == null) {
      return null;
    }
    try {
      return cascade
          .complete(packageId, humanApproved, HUMAN_NOTE, DecisionCascade.HUMAN_DECIDER)
          .orElse(null);
    } catch (RuntimeException failure) {
      LOG.error("跨进程决策包轨迹补齐失败（零批准）requestId={} packageId={}", request.id(), packageId, failure);
      return null;
    }
  }

  /**
   * 逐条理由（{@code index ⇒ 一行}；顺序 = 条目顺序）。
   *
   * @param approved 终局被批的条目
   * @param humanApproved 其中由人批准的那些（用来区分"级联预裁定"与"人批"两种理由）
   */
  private static Map<Integer, String> reasons(
      List<Item> items, Set<Integer> approved, Set<Integer> humanApproved) {
    Map<Integer, String> reasons = new LinkedHashMap<>();
    for (Item item : items) {
      if (!approved.contains(item.index())) {
        reasons.put(item.index(), REASON_REJECTED);
      } else if (humanApproved.contains(item.index())) {
        reasons.put(item.index(), REASON_HUMAN_APPROVED);
      } else {
        reasons.put(item.index(), REASON_PRE_APPROVED);
      }
    }
    return reasons;
  }

  /**
   * fail-closed 的结算结果：<b>零批准</b>（{@code approvedIndexes} 空、全部条目进 {@code rejectedIndexes}），
   * 没有轨迹、没有裁决者，逐条理由为 {@code null}（未逐条说明）。
   */
  private static Verdict failClosed(List<Item> items, String code, String why) {
    LOG.warn("跨进程决策包零批准（{}：{}）items={}——子体据此零落盘", code, why, items.size());
    return new Verdict(Set.of(), allIndexes(items), null, null, null, DECIDER_NONE);
  }

  /** 入参形状校验（形状错误 = 调用方 bug，不是一种结算结果 ⇒ 抛给工具转成参数错误）。 */
  private static void validate(Request request) {
    if (request.proposerKey().isBlank()) {
      throw new IllegalArgumentException("proposerKey 不得为空白（它是「谁提的」那个键）");
    }
    if (request.requestId().length() > MAX_REQUEST_ID_CHARS) {
      throw new IllegalArgumentException(
          "requestId 超过上限 " + MAX_REQUEST_ID_CHARS + " 字符（它是幂等键，不是数据通道）");
    }
    if (request.items().isEmpty()) {
      throw new IllegalArgumentException("items 不得为空：一个包至少要有一条待裁定的调用");
    }
    Set<Integer> indexes = new LinkedHashSet<>();
    for (Item item : request.items()) {
      if (!indexes.add(item.index())) {
        throw new IllegalArgumentException("items 的 index 必须互不相同：重复 index=" + item.index());
      }
    }
  }

  /**
   * 幂等键 = {@code proposerKey + 分隔符 + requestId}（<b>不是裸 requestId</b>，见类注释）。
   *
   * <p>{@code proposerKey} 与 {@code requestId} 都是实例/包 id，构造期已 strip 且不含分隔符 （{@code \u0000} 在
   * HTTP/JSON/模型文本里都进不来）。
   */
  private static String idempotencyKey(Request request) {
    return request.proposerKey() + IDEMPOTENCY_KEY_SEPARATOR + request.requestId();
  }

  /**
   * 一次提交的<b>内容指纹</b>（同键复用时认"是不是同一批条目"）：逐条 {@code index|toolName|argsDigest|askKind|classKey}
   * 规范化后取短摘要。
   *
   * <p>为什么含 {@code askKind}/{@code classKey}：它们虽然不参与"谁有能力做这条"的判定，却参与<b>级联与请求形状</b> （{@code
   * SENSITIVE} 强制到人、{@code MODE_LIMITED} 才可能被预裁定）——"同一批工具调用、换了为什么问"必须算另一批内容。
   *
   * <p>摘要算法复用 {@link DecisionItemDigest}（唯一实现点）：本类不另造一种哈希，也不把明文放进来。
   */
  private static String fingerprint(Request request) {
    StringBuilder material = new StringBuilder();
    for (Item item : request.items()) {
      material
          .append(item.index())
          .append('|')
          .append(item.toolName())
          .append('|')
          .append(DecisionItemDigest.argsDigest(item.args()))
          .append('|')
          .append(item.askKind() == null ? AskKind.SENSITIVE.name() : item.askKind().name())
          .append('|')
          .append(item.classKey())
          .append('\n');
    }
    return DecisionItemDigest.hexDigest(material.toString());
  }

  /** 条目表里全部条目的 index（顺序 = 条目顺序）。 */
  private static Set<Integer> allIndexes(List<Item> items) {
    Set<Integer> indexes = new LinkedHashSet<>();
    for (Item item : items) {
      indexes.add(item.index());
    }
    return indexes;
  }

  /** 请求里除 {@code approved} 之外的条目（顺序 = 条目顺序）。 */
  private static Set<Integer> complement(List<Item> items, Set<Integer> approved) {
    Set<Integer> rest = new LinkedHashSet<>();
    for (Item item : items) {
      if (!approved.contains(item.index())) {
        rest.add(item.index());
      }
    }
    return rest;
  }
}
