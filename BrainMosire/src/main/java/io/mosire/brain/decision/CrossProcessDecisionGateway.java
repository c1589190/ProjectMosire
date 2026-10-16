package io.mosire.brain.decision;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.JudgementItem;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.approval.SuperiorJudgeDetailRegistry;
import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.DecisionItemDigest;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.DecisionSubmission;
import io.mosire.agentlib.decision.InFlightDecisionActions;
import io.mosire.agentlib.decision.InFlightDecisionPackages;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.decision.SubagentDecisionLedger;
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
 * <h2>★ 请求的 {@code kind} 取<b>父侧那一刻算出来的那一份</b>（P3e，设计 §5sexies⑦）</h2>
 *
 * <p>{@link #requestFor} 不再恒取 {@link AskKind#SENSITIVE}：kind 取父侧在本轮队列里 defer 那一刻记下的那份（= 真工具 {@code
 * ToolGate.Ask.kind()}，子体送不出、也不必送），并按 {@code DecisionApprovalBridge#requestKind} 的<b>同一条判据</b>分流：
 *
 * <ul>
 *   <li>上报给（这次请求的）条目里<b>任何一条</b>不是 {@link AskKind#MODE_LIMITED} ⇒ {@code SENSITIVE}（敏感区永远到人； {@code
 *       SuperiorJudgeGate} 自己也会让开）；缺料（老形态：子体提交里没有 kind）与<b>父侧本轮队列未接线</b>同样按 {@code SENSITIVE} 办；
 *   <li>全是 {@code MODE_LIMITED} <b>且</b>明细缝（{@link SuperiorJudgeDetailRegistry}）在 ⇒ {@code
 *       MODE_LIMITED}： 上级判定闸<b>真能看见逐条明文</b>（本类把父侧自己那份 {@link JudgementItem} 登记进去）才谈得上代批；
 *   <li>★ <b>fail-closed</b>：明细缝不在 ⇒ 退回 {@code SENSITIVE}（看不见就不给批）。<b>不许</b>"没明细还让上级代批"。
 * </ul>
 *
 * <p>★ <b>红线不动</b>：{@code SENSITIVE} 永远到人；{@code MODE_LIMITED} 只在上级真看得见逐条明文时才可能被代批。
 *
 * <h2>★ 在途明文面（P3e，设计 §5sexies⑤★★）</h2>
 *
 * <p>本类实现 {@link InFlightDecisionPackages}：临时包在"正等人裁"的那段窗口里可被决策面 {@code GET
 * /api/decision-packages/{id}} 按 {@code xp-…} 取到，逐条带<b>父侧自己收到的明文</b>（人与上级看得见"这条到底改什么"）。
 * 它<b>只读</b>：不进 {@link DecisionPackages}（不变量 1）、不进 {@code pending()}、不落盘、不发事件，投递返回（或异常返回）即消失。
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
public final class CrossProcessDecisionGateway
    implements InFlightDecisionPackages, InFlightDecisionActions {

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
   * ★ <b>两侧核对不一致</b>（P3d，设计 §5sexies⑤）：子体提交的 {@code items} 与父侧"该子体本轮队列"对不上 （条数 / 序号 / 工具名 /
   * 参数摘要任一处不同，或父侧这边<b>根本没有</b>这一轮的队列）⇒ 具名拒 + <b>零批准</b>。
   *
   * <p><b>为什么不"按父侧那份悄悄补一个裁决"</b>：那样等于用一个请求去回答另一批条目——正常路径下这不可能发生（两边同源）， 撞上就是 bug 或伪造，两者都该响亮。
   */
  public static final String ITEMS_MISMATCH = "ITEMS_MISMATCH";

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
   * {@link Verdict#deciderLevel()}：<b>上级 Agent（代批闸）</b>拍的板（P3e；{@code finalizedBy} = {@link
   * DecisionCascade#SUPERIOR_DECIDER}）。
   *
   * <p>它只有在"上报条目全是 {@link AskKind#MODE_LIMITED} 且明细缝在（上级看得见逐条明文）"时才可能出现（见类注释）。
   * 新增这一档是为了让记录<b>说真话</b>：一次由上级代批的裁决若记成 {@code HUMAN}，事后读历史的人会以为 是人拍的板——那是把"谁负责"记错，与 {@code
   * DecisionApprovalBridge} 记 {@link DecisionCascade#SUPERIOR_DECIDER} 的口径同源。
   */
  public static final String DECIDER_SUPERIOR = "SUPERIOR";

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

  /** 上级代批闸拍板的理由（P3e；进临时包轨迹的那一级，与 {@code DecisionApprovalBridge} 的 {@code SUPERIOR_DECIDER} 同源）。 */
  private static final String SUPERIOR_NOTE = "上级代批闸批准上报到人的条目（该级看得见逐条明文）";

  /** 临时包 id 的前缀（看着就知道它<b>不是</b>包登记表里那个 {@code dp-N} 序列的一员）。 */
  private static final String TRANSIENT_PACKAGE_PREFIX = "xp-";

  /**
   * 在途明文面的上限（条数）：一条 = 一次"正等人裁"的提交。正常情况下并发的在途提交 = 同时在等的人裁的提交数（个位数）， 256 是"有人忘了释放"这类 bug
   * 的响亮出口（满了淘汰最久未用的那一条并落 WARN——被淘汰的只是"人少看一个包的全文"，不影响任何判定）。
   */
  public static final int MAX_IN_FLIGHT = 256;

  /**
   * 幂等缓存的上限（条数）：满了淘汰最久未用的那一条（见类注释的"有界"）。
   *
   * <p>为什么是 256：一次提交一条，条目本身很小（几个 index + 一份轨迹）；256 覆盖"一个部署里最近 256 次提交"， 足够挡住重试重放，也不会让内存随运行时长增长。
   */
  /**
   * 条目级表态在<b>临时包视图</b>里的裁决人标识（用户裁定 15 ②）：临时包不进包登记表，所以它没有 {@code
   * DecisionPackages#DECIDED_BY_ITEM_VERDICTS} 那条累积路径，由本类自己拼一个同形的 {@link Decision}。
   */
  public static final String DECIDED_BY_ITEM_VERDICTS = "item-decisions";

  /** 临时包视图的终结说明（父侧只回裁决、不执行：真正的执行在子体，见类注释）。 */
  private static final String TRANSIENT_COMMIT_NOTE =
      "临时包（跨进程）的门面：裁决已交回提出者；执行在提出者进程里（父侧不替子体执行，设计 §5quinquies ①）";

  /** 临时包视图的逐条表态说明（累积中，不是终局）。 */
  private static final String TRANSIENT_DECIDING_NOTE =
      "临时包（跨进程）的门面：逐条表态累积中——全部条目都表了态、或有人下执行命令之后才终结";

  /** 幂等：临时包结算之后仍保留在在途面里的条数上限（见 {@link #inFlight}）。 */
  private static final int MAX_SETTLED_IN_FLIGHT = 64;

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
   * <b>父侧「该子体本轮队列」簿子</b>（P3d，设计 §5sexies③⑤⑥）：<b>可 null</b> = 未接线 ⇒ 本类行为与接线前<b>逐字不变</b> （{@code
   * items} 自带的明文参数就是唯一来源，P3a/P3b 的形状）。
   *
   * <p>接线之后，结算<b>以父侧队列为准</b>：子体提交的条目只作对账（{@code index + toolName + digest}），明文与 {@code
   * classKey}/{@code askKind} 全部取<b>父侧自己收到的那一份</b>（设计 §5sexies⑤：子体不必送、也送不出可信的副本）；
   * 结算完成时给被批条目记一次性放行（设计 §5sexies⑥）。
   */
  private final SubagentDecisionLedger ledger;

  /**
   * <b>上级判定的逐条明细缝</b>（P3e，设计 §5sexies⑦）：{@code Optional.empty()} = 未装配 ⇒ 跨进程请求一律退回 {@link
   * AskKind#SENSITIVE}（只看得到工具名就点头 = "看一半批全部"，本仓不做）。
   *
   * <p>装配层必须把它交给<b>同一个实例</b>的 {@code DecisionApprovalBridge}、{@code LlmSuperiorJudgement} 与本类
   * （桥登记进程内包的明细、本类登记跨进程包的明细、判定面读它）——三处各一份的话，"上级看得见"就成了假的。
   */
  private final Optional<SuperiorJudgeDetailRegistry> judgeDetails;

  /**
   * 第二段"等执行命令"的窗口（毫秒，缺省 {@link #DEFAULT_COMMIT_WAIT_MILLIS}）——见 {@link #awaitAnswer} 里那段
   * 注释：它与"等人答复"的包窗口（30 分钟，裁定 10）**分开**，否则任何没下 commit 的调用都会白挂半小时。
   */
  private final long commitWaitMillis;

  /**
   * <b>在途明文面</b>（P3e）：{@code xp-…} ⇒ 那个"正等人裁"的临时包（见类注释）。 {@link Collections#synchronizedMap}
   * 包一层：每个子体一条 MCP 传输线程 ⇒ 多台子体可并发结算；锁的粒度 = 一次放/取/删。
   */
  private final Map<String, Waiting> inFlight = Collections.synchronizedMap(new InFlightCache());

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
    this(levelLookup, cascade, coordinator, pending, null, Optional.empty());
  }

  /**
   * 全参装配（P3d 新增）：在既有四参之上接<b>父侧「该子体本轮队列」簿子</b>——接了它之后，{@code items} 里的明文参数不再被采信 （只用它做对账），权威的那一份是父侧自己
   * defer 时收到的（见 {@link #ledger} 与 {@link #submit(Request)}）。
   *
   * @param ledger 父侧队列簿；{@code null} ⇒ 与本类既有四参构造逐字等价
   */
  public CrossProcessDecisionGateway(
      LevelVerifier levelLookup,
      DecisionCascade cascade,
      ApprovalCoordinator coordinator,
      PendingApprovals pending,
      SubagentDecisionLedger ledger) {
    this(levelLookup, cascade, coordinator, pending, ledger, Optional.empty());
  }

  /**
   * 全参装配（P3e 新增）：在 P3d 的五参之上接<b>上级判定的逐条明细缝</b>——接了它之后，上报条目全是 {@link AskKind#MODE_LIMITED}
   * 的请求才可能交给上级代批（本类把父侧自己那份逐条明文登记进去），否则退回 {@code SENSITIVE}（见类注释）。
   *
   * @param judgeDetails 明细缝（{@code Optional.empty()} = 未装配 ⇒ 跨进程请求一律只到人）；装配层必须把它交给<b>同一个实例</b>的
   *     {@code LlmSuperiorJudgement} 与 {@code DecisionApprovalBridge}
   *     <p><b>为什么带 {@code EI_EXPOSE_REP2} 抑制</b>：{@code ledger} 是<b>注入的协作者</b>（接口 + 生产实现 {@code
   *     ParentSubagentDecisionLedger}），不是数据持有者——本类只调它、从不把它交给任何调用方；对簿子做防御性拷贝
   *     既无意义（它自己就是唯一写者）也会拆掉"唯一写者"这条不变量。与 {@code AgentPipeline} 的 16 参终态构造同一条口径。 ★
   *     这条抑制<b>不是</b>为了"让它变绿"：clean 重编后 SpotBugs **真报过**它——它一度被增量编译留下的旧 class 误判成"抑制多余"而删掉（AGENTS
   *     §三那条"判 SpotBugs 前先确证编译产物是新的"就是这么来的）。
   */
  public CrossProcessDecisionGateway(
      LevelVerifier levelLookup,
      DecisionCascade cascade,
      ApprovalCoordinator coordinator,
      PendingApprovals pending,
      SubagentDecisionLedger ledger,
      Optional<SuperiorJudgeDetailRegistry> judgeDetails) {
    this(
        levelLookup,
        cascade,
        coordinator,
        pending,
        ledger,
        judgeDetails,
        DEFAULT_COMMIT_WAIT_MILLIS);
  }

  /**
   * 同上，但显式给定<b>执行等待窗</b>（毫秒）——即"表态完只差下执行命令"那一段的上界（仍受包 deadline 兜底）。
   *
   * <p><b>用例必须用这个构造</b>：注入毫秒级窗口，别让任何用例按缺省等（2026-10-17 用户指令 「太变态了直接等30分钟，改！」，AGENTS §四 第 11
   * 条）。生产装配用六参构造即可（缺省 {@link #DEFAULT_COMMIT_WAIT_MILLIS}）。
   *
   * @param commitWaitMillis 执行等待窗（毫秒，必须为正）
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public CrossProcessDecisionGateway(
      LevelVerifier levelLookup,
      DecisionCascade cascade,
      ApprovalCoordinator coordinator,
      PendingApprovals pending,
      SubagentDecisionLedger ledger,
      Optional<SuperiorJudgeDetailRegistry> judgeDetails,
      long commitWaitMillis) {
    this.levelLookup = Objects.requireNonNull(levelLookup, "levelLookup");
    this.cascade = cascade;
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.pending = Objects.requireNonNull(pending, "pending");
    this.ledger = ledger;
    this.judgeDetails = Objects.requireNonNull(judgeDetails, "judgeDetails");
    if (commitWaitMillis <= 0) {
      throw new IllegalArgumentException("commitWaitMillis 必须为正（毫秒）: " + commitWaitMillis);
    }
    this.commitWaitMillis = commitWaitMillis;
  }

  /**
   * {@inheritDoc}
   *
   * <p><b>★ 只读</b>：不建包、不改状态、不进 {@link DecisionPackages#pending()}、不发事件——它只把人此刻有权看的那份全文取出来。 登记与释放在
   * {@link #settle} 里成对出现（投递返回即消失）；{@code null} / 不认识 / 已结算 ⇒ 空值。
   */
  @Override
  public Optional<DecisionPackage> find(String packageId) {
    return find(packageId, true);
  }

  /**
   * {@inheritDoc}
   *
   * <p><b>★ 两档查看（用户裁定 15 ①）</b>：{@code full = false} ⇒ 逐条的 {@code args} <b>在这里就被抹掉</b>（渲染侧拿到的东西
   * 本身就没有明文——"读到了再假装没读"不算脱敏）。{@code full = true} ⇒ 原样给父侧自己收到的那一份明文。
   */
  @Override
  public Optional<DecisionPackage> find(String packageId, boolean full) {
    if (packageId == null) {
      return Optional.empty();
    }
    Waiting waiting = inFlight.get(packageId);
    if (waiting == null) {
      return Optional.empty();
    }
    return Optional.of(viewOf(waiting, full));
  }

  /**
   * {@inheritDoc}
   *
   * <p><b>★ 用户裁定 15：人要先"看到这个包"才能逐条裁它</b>——临时包只活在等待窗口里，列表是它唯一的入口（id 形如 {@code
   * xp-…}）。这里返回的是<b>概览</b>（逐条无明文），与 {@link #find(String, boolean)} 的 {@code full} 那一档分工明确。
   */
  @Override
  public List<DecisionPackage> list() {
    List<Waiting> snapshot;
    synchronized (inFlight) {
      snapshot = new ArrayList<>(inFlight.values());
    }
    List<DecisionPackage> rows = new ArrayList<>(snapshot.size());
    for (Waiting waiting : snapshot) {
      rows.add(viewOf(waiting, false));
    }
    return List.copyOf(rows);
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
   * 在途明文面的有界 LRU（P3e）：满了淘汰最久未用的那一个<b>只是</b>让人少看一个包的全文（不影响判定、不影响放行条）—— 它在正常情况下恒为空（登记与释放在同一次 {@link
   * #settle} 里成对），所以"满"本身就是异常信号，落 WARN。
   */
  private static final class InFlightCache extends LinkedHashMap<String, Waiting> {

    private static final long serialVersionUID = 1L;

    InFlightCache() {
      super(16, 0.75f, true);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Waiting> eldest) {
      boolean evict = size() > MAX_IN_FLIGHT;
      if (evict) {
        LOG.warn(
            "在途明文面超过上限 {} 条 ⇒ 淘汰最久未用的那一个（登记与释放本应成对；这不影响任何判定） packageId={}",
            MAX_IN_FLIGHT,
            eldest.getKey());
      }
      return evict;
    }
  }

  /**
   * 子体送来的一条待裁定条目。
   *
   * <p><b>P3d 起 {@code args} 在接线了父侧队列时不再被采信</b>（设计 §5sexies⑤）：权威的那一份是父侧自己在 defer 那一刻收到的明文， 本记录里的
   * {@code args} 只在<b>未接线</b>的老形态（P3a/P3b：父侧没有队列可对账）里当数据源。{@code classKey}/{@code askKind}
   * 同理——它们由父侧按参数现算（闸位知识跟着真工具走）。
   *
   * @param index 条目在子体包内的身份（本类原样回传；同一请求内唯一）
   * @param toolName 工具名
   * @param args 调用参数明文（顶层不可变快照；顶层不得含 null 键/值）——{@link #ledger} 在场时只作参考
   * @param classKey 子体侧工具自报的闸位类键（<b>不</b>参与本类的判定：父侧这次请求恒用包级 classKey，见 {@link #requestFor}）
   * @param askKind 为什么问（{@code null} = 没给 ⇒ 按 {@link AskKind#SENSITIVE} 办 = 强制到人，最保守的一侧）。 <b>P3e
   *     起它参与一条判定</b>：上报条目全是 {@code MODE_LIMITED} 时请求才可能是 {@code MODE_LIMITED}（见 {@link
   *     #requestKind}）——接线了父侧队列时这里装的是父侧 defer 那一刻记下的那份，未接线时是调用方自报的那份
   * @param digest ★ <b>参数摘要</b>（P3d 的对账键，设计 §5sexies⑤）：两侧同一段代码（{@link
   *     DecisionItemDigest#argsDigest}） 算出来的 {@value DecisionItemDigest#DIGEST_HEX_CHARS} 位十六进制；空白
   *     = 老形态没给（未接线时合法）。
   */
  public record Item(
      int index,
      String toolName,
      Map<String, Object> args,
      String classKey,
      AskKind askKind,
      String digest) {

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
      digest = digest == null ? "" : digest.strip();
    }

    /** P3a/P3b 的五参形状（没有摘要）：既有调用方与用例逐字不变。 */
    public Item(
        int index, String toolName, Map<String, Object> args, String classKey, AskKind askKind) {
      this(index, toolName, args, classKey, askKind, "");
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
   * @param deciderLevel {@link #DECIDER_AUTO} / {@link #DECIDER_HUMAN} / {@link #DECIDER_SUPERIOR}
   *     / {@link #DECIDER_NONE}
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
    if (ledger != null) {
      // ★ P3d：接线了父侧队列 ⇒ 结算以**父侧自己的队列**为准（设计 §5sexies⑤⑥）
      return submitAgainstLedger(request);
    }
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
   * ★ <b>以父侧本轮队列为准的结算</b>（P3d，设计 §5sexies⑤⑥）：核对 ⇒ 用父侧那份条目结算 ⇒ 记一次性放行 + 清本轮队列。
   *
   * <p><b>顺序是刻意的</b>：
   *
   * <ol>
   *   <li>幂等缓存照旧先查（同键同内容 ⇒ 原样返回上次裁决、<b>不再</b>问人第二遍；同键不同内容 ⇒ 具名拒）；
   *   <li>取父侧队列：空（没建过 / 已被清 / 已到点）⇒ 具名拒 {@link #ITEMS_MISMATCH}——<b>不</b>编一份裁决；
   *   <li>逐条对账（序号 + 工具名 + 参数摘要）：任一处不同 ⇒ 具名拒 {@link #ITEMS_MISMATCH} + <b>零执行</b>；
   *   <li>用父侧那份条目（<b>带父侧自己收到的明文 args</b>）走既有结算（级联 ⇒ 必要时投审批面 ⇒ 终局裁决）；
   *   <li>结算成功后给被批条目记一次性放行，并把该子体本轮队列清掉（设计 §5sexies③①）。
   * </ol>
   *
   * <p>★ <b>裁决者照旧看全文</b>（设计 §5sexies⑤★★）：临时包里的 {@code args} 是父侧自己收到的那一份 ⇒ 级联、审批提示与
   * 记录面都拿得到明文；子体的提交里<b>只有</b> {@code index/toolName/digest}。
   *
   * <p>THROWS_METHOD_THROWS_RUNTIMEEXCEPTION 抑制：本方法的失败契约就是"<b>具名拒 = IllegalArgumentException
   * 原样上抛</b>" （工具面据它回具名错误码，见 {@link SubmitDecisionPackageTools}）。两处 {@code throw refused}
   * 是<b>刻意的原样重抛</b> ——先清掉这一轮的对账账本，再让调用方看到同一条异常（不包装、不改类型、不改消息），否则"对不上账"会被洗成另一个码。
   */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification = "具名拒必须原样上抛（类型与消息都不许被包装改掉）：这里是'清账本 ⇒ 重抛同一条'，显式 throw 是设计意图。")
  private Verdict submitAgainstLedger(Request request) {
    if (request.requestId().isEmpty()) {
      // 一次性放行的键（实例 + 包 id + 序号）少一段就没法记 ⇒ 响亮拒，不让"批了却执行不了"变成静默
      throw new IllegalArgumentException(
          INVALID_ITEMS + "：接线了父侧队列时 requestId 必填（它是包 id，也是一次性放行的键）");
    }
    String key = idempotencyKey(request);
    String fingerprint = ledgerFingerprint(request);
    Cached hit = idempotent.get(key);
    if (hit != null) {
      if (!hit.fingerprint().equals(fingerprint)) {
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
          "跨进程决策包提交命中幂等缓存（原样返回上次裁决，不再投审批面、也不重复记放行）proposerKey={} requestId={} deciderLevel={}",
          request.proposerKey(),
          request.requestId(),
          hit.verdict().deciderLevel());
      return hit.verdict();
    }
    List<PendingDecisionQueue.PendingEntry> mine = ledger.pendingEntries(request.proposerKey());
    if (mine.isEmpty()) {
      LOG.warn(
          "跨进程决策包提交被拒（父侧本轮队列里没有这个实例的待裁条目，疑似伪造或队列已被清）proposerKey={} requestId={} items={}",
          request.proposerKey(),
          request.requestId(),
          request.items().size());
      ledger.discard(request.proposerKey());
      throw new IllegalArgumentException(
          ITEMS_MISMATCH + "：父侧本轮队列里没有 " + request.proposerKey() + " 的待裁条目（对不上账 ⇒ 零批准、零执行）");
    }
    List<Item> authoritative;
    try {
      authoritative = reconcile(request, mine);
    } catch (RuntimeException refused) {
      // ★ 对不上账的那一轮**不留账**：队列是按实例复用的（同一子体的下一轮会往同一份簿子里续记），
      //   把一份"对不上"的旧条目留着，会让此后每一轮都对不上——那不是 fail-closed，那是把子体的决策路径卡死。
      ledger.discard(request.proposerKey());
      throw refused;
    }
    Verdict verdict;
    try {
      verdict = settle(new Request(request.proposerKey(), authoritative, request.requestId()));
    } catch (RuntimeException refused) {
      // 结算层响亮拒（提出者认不出 / 形状）：本轮队列作废（不记放行）——不留一份"下次还能用"的账
      ledger.discard(request.proposerKey());
      throw refused;
    }
    ledger.settled(request.proposerKey(), request.requestId(), verdict.approvedIndexes());
    idempotent.put(key, new Cached(fingerprint, verdict));
    return verdict;
  }

  /**
   * 逐条对账（子体提交的 {@code index/toolName/digest} ↔ 父侧队列的条目）⇒ 返回<b>父侧那份</b>条目。
   *
   * <p>对账用摘要而不是明文：子体不该、也不必把明文再送一遍（设计 §5sexies⑤）；{@link DecisionItemDigest} 两侧同一段代码 ⇒
   * 同一条调用两侧同摘要。任一处对不上就<b>不返回任何条目</b>（不是"返回能对上的那些"）——半个包不是决策单元。
   */
  private static List<Item> reconcile(
      Request request, List<PendingDecisionQueue.PendingEntry> mine) {
    Map<Integer, PendingDecisionQueue.PendingEntry> byIndex = new LinkedHashMap<>();
    for (PendingDecisionQueue.PendingEntry entry : mine) {
      byIndex.put(entry.packageItem().index(), entry);
    }
    if (byIndex.size() != request.items().size()) {
      throw mismatch(
          request, "条数不同：父侧本轮队列 " + byIndex.size() + " 条、提交 " + request.items().size() + " 条");
    }
    List<Item> authoritative = new ArrayList<>(mine.size());
    for (Item item : request.items()) {
      PendingDecisionQueue.PendingEntry entry = byIndex.get(item.index());
      if (entry == null) {
        throw mismatch(request, "提交的 index=" + item.index() + " 不在父侧本轮队列里");
      }
      if (item.digest().isBlank()) {
        throw mismatch(request, "index=" + item.index() + " 没有带参数摘要（无法对账）");
      }
      String expected = entry.packageItem().argsDigest();
      if (!expected.equals(item.digest())) {
        throw mismatch(request, "index=" + item.index() + " 的参数摘要与父侧收到的不一致（同一条调用两侧应同摘要）");
      }
      if (!entry.toolName().equals(item.toolName())) {
        throw mismatch(
            request,
            "index=" + item.index() + " 的工具名不同：父侧 " + entry.toolName() + "、提交 " + item.toolName());
      }
      authoritative.add(
          new Item(
              entry.packageItem().index(),
              entry.toolName(),
              entry.args(),
              entry.classKey(),
              entry.askKind(),
              expected));
    }
    return List.copyOf(authoritative);
  }

  /** 对账失败：一行 WARN（只有 id/序号/工具名，没有明文）+ 具名拒。 */
  private static IllegalArgumentException mismatch(Request request, String why) {
    LOG.warn(
        "跨进程决策包提交被拒（{}，两侧核对不一致 ⇒ 零批准、零执行）proposerKey={} requestId={} items={}",
        ITEMS_MISMATCH,
        request.proposerKey(),
        request.requestId(),
        request.items().size());
    return new IllegalArgumentException(
        ITEMS_MISMATCH + "：提交的条目与父侧本轮队列对不上账（" + why + "）——父侧以自己那份为准，且不会替你补一个裁决");
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
    // 没有级联（cascade == null）⇒ 整包到人（本类之前的口径），上报集合就是全部条目。
    // ★ 写成一次赋值（不在下面 if 里再赋）是刻意的：它是 effectively final，才能被下面那段明细登记的 lambda 捕获。
    Set<Integer> escalated =
        trail
            .map(DecisionCascade.DecisionTrail::escalatedToHuman)
            .orElseGet(() -> allIndexes(request.items()));
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
    ApprovalRequest approvalRequest = requestFor(transientPackage, escalated, request.items());
    // ★ P3e + 裁定 15：把这张"正等人裁"的临时包挂进在途面——此时它才真的摆在人（或上级判定闸）面前，
    //   决策面按 xp-… 取得到概览 / 逐条明文，也能对它做**条目级表态**（InFlightDecisionActions）。
    //   它是**门面**：不进 DecisionPackages、不进 pending()、不落盘、不发 decision.package.* 事件（不变量 1 一个字不动）。
    Waiting waiting =
        new Waiting(transientPackage, approvalRequest.id(), escalated, request.proposerKey());
    inFlight.put(transientPackage.id(), waiting);
    ApprovalDecision answer;
    try {
      // ★ 明细登记必须早于 coordinator.decide：上级判定闸就在那条调用的调用栈上读它（读不到 = 盲判）。
      //   只登记**上报的那些条目**（下级已经预裁定过的条目不该再让上级判一遍）；登记的是**父侧自己那份**明文。
      judgeDetails.ifPresent(
          details ->
              details.register(approvalRequest.id(), judgementItems(transientPackage, escalated)));
      LOG.info(
          "跨进程决策包已投审批面 requestId={} packageId={} proposerKey={} items={} 上报={} kind={} 明细={}",
          approvalRequest.id(),
          transientPackage.id(),
          request.proposerKey(),
          request.items().size(),
          escalated,
          approvalRequest.kind(),
          judgeDetails.isPresent() ? "已登记（上级判定面读得到逐条明文）" : "未装配（明细缝不在 ⇒ 只到人）");
      answer = awaitAnswer(waiting, approvalRequest);
    } catch (RuntimeException failure) {
      LOG.error(
          "跨进程决策包投审批面异常 ⇒ 零批准（fail-closed）：proposerKey={} requestId={}",
          request.proposerKey(),
          approvalRequest.id(),
          failure);
      return failClosed(request.items(), SETTLEMENT_FAILED, "审批面投递异常");
    } finally {
      // ★ 明文（args）的生命周期到此为止：判定已读完，立刻释放（密钥纪律；释放幂等，跑在 finally 里）。
      //   ★ 裁定 15：在途面**不立刻摘除**——刚被裁完的包要能被"再 GET 一次 / 再 commit 一次（幂等）"读到；
      //     移除只发生在结算真正返回之后（见 finally 之外的 inFlight.remove，在保留窗口里由 resolve 标记）。
      judgeDetails.ifPresent(details -> details.release(approvalRequest.id()));
    }
    if (waiting.committed) {
      // ★ 裁定 15 ③：人（或父侧 Agent）下了**执行命令** ⇒ 这就是终局裁决：已批准的那些按表态，没表态的按裁定 14 = 拒绝。
      //   本类**不执行**（执行在子体，用子体自己的权限与身份——设计 §5quinquies ①）。
      return committedVerdict(request, waiting, transientPackage);
    }
    inFlight.remove(transientPackage.id());
    boolean answered =
        answer == ApprovalDecision.APPROVE_ONCE || answer == ApprovalDecision.APPROVE_SESSION;
    // ★ P3e：这条答复是**谁**给的（与 DecisionApprovalBridge 同一把判据）——登记项还在 = 人答的（通道答复不摘登记项）；
    //   不在 = 上级代批闸答的（它命中即终局、根本不到登记那一步）。记录必须说真话：不让代批被记成"人拍的板"。
    boolean byHuman = registered(approvalRequest);
    if (!answered) {
      LOG.warn(
          "跨进程决策包零批准（fail-closed）：proposerKey={} requestId={} answer={} 审批面上登记项={}"
              + "（有登记项 ⇒ 人答复为拒；没有 ⇒ 无可用通道或无人答复）",
          request.proposerKey(),
          approvalRequest.id(),
          answer,
          byHuman ? "在（人答的）" : "不在（无通道/超时/闸链终局拒）");
      return failClosed(request.items(), APPROVAL_UNAVAILABLE, "没有拿到放行答复");
    }
    // 走到这里 byHuman 必为假（人答的那条路上面就返回了）——保留变量是为了让"谁拍的板"只有一处表达。
    String decider = DecisionCascade.SUPERIOR_DECIDER;
    if (byHuman) {
      // ★★ 审批面上的"批准" = 人自己的执行命令（用户裁定 15 ③ 在跨进程这条路上的落点，2026-10-17 收口）。
      //   人按下审批面那个批准键，就是在说"这一包可以做"——它本来就是**第二段**的执行命令，只是形状是审批答复
      //   而不是 POST /{id}/commit。⇒ 记成 committed（{@link Waiting#commit}），终局统一由 {@link
      // #committedVerdict}
      //   产出：① 与决策面那条路**同一套**终局语义（已批按表态、未表态按裁定 14 = 拒绝），不再"两条路两种算法"；
      //   ② 在途门面快照从此与人看到的动作一致——人批过之后决策面立刻显示"这一条被批"（此前 waiting.verdicts
      //      恒空 ⇒ 同一时刻决策面还显示 PROPOSED/未表态，"动作做完了、门面说没做"）；
      //   ③ 与无人在环那条路（{@link #isAutoApproval}）的分界保持不变：那里没有人能敲任何键，由闸链的批准代为
      //      commit；这里是人亲手敲的（判别力：去掉这一段 ⇒ 人批了但没再敲 commit 的包按"已批未执行"作废，
      //      而提出者线程恰恰是被这次批准放行的——放行了却什么都不执行）。
      //   ★ 记法统一：**被批准的那些**逐条写进 {@link Waiting#verdicts}（与"人在决策面逐条表态 + 下执行命令"
      //     那条路**同一个表示**），再置 committed ⇒ 终局装配只有一处算法（{@link #committedVerdict} 读 verdicts）。
      //     不这么做就会得到一份"committed 但一条都没表态"的终局，被裁定 14 读成"零批准"。
      waiting.commitApproved(escalated, DECIDER_HUMAN);
      LOG.info(
          "跨进程决策包由人在审批面批准（= 执行命令）⇒ 交回提出者执行 proposerKey={} packageId={} requestId={} approved={}",
          request.proposerKey(),
          transientPackage.id(),
          approvalRequest.id(),
          escalated);
      return committedVerdict(request, waiting, transientPackage);
    }
    // 答复 = 上报集合里的条目全批（本类不提供逐条例外的第二套形状：逐条例外走决策面 HTTP 那条既有路）
    Set<Integer> humanApproved = escalated;
    if (trail.isEmpty()) {
      // 没有级联 ⇒ 没有轨迹可补齐，"批了上报的那些"就是终局（与 P2b 之前"整包到人"的行为同形）
      LOG.info(
          "跨进程决策包由{}整包批准（无级联）proposerKey={} packageId={} requestId={} approved={}",
          "上级代批闸",
          request.proposerKey(),
          transientPackage.id(),
          approvalRequest.id(),
          humanApproved);
      return new Verdict(
          humanApproved,
          complement(request.items(), humanApproved),
          reasons(request.items(), humanApproved, humanApproved),
          null,
          decider,
          DECIDER_SUPERIOR);
    }
    DecisionCascade.DecisionTrail completed =
        completeTrail(transientPackage.id(), humanApproved, approvalRequest, decider);
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
        DECIDER_SUPERIOR);
  }

  /**
   * <b>等裁决：人/上级答一次，或有人在决策面下了执行命令</b>（用户裁定 15 ③ 的协调点）。
   *
   * <p><b>为什么不是简单地"调一次 coordinator.decide 就完"</b>：裁定 15 让人可以<b>逐条表态</b>、最后再下执行命令。
   * 若这里一直阻塞在协调器上，人的逐条表态就永远等不到"可以终结"的那一刻——而"执行命令"根本投不进协调器（它不是审批答复，
   * 是一条<b>新的命令</b>）。所以等法是"<b>一段一段地问，每段之间可以被表态/执行命令唤醒</b>"：
   *
   * <ol>
   *   <li>{@link #step} 已经拿到终局（人答了、上级代批了、没人答 ⇒ 零批准）⇒ 直接用它；
   *   <li>有人在决策面下了<b>执行命令</b> ⇒ {@link Waiting#committed}（终局裁决按表态算，见 {@link #committedVerdict}）；
   *   <li>都还没有 ⇒ 调一次 {@code coordinator.decide}（它自己会在通道上等一段），返回后回到 ①。
   * </ol>
   *
   * <p><b>窗口上界 = 包自己的 deadline</b>（裁定 10）：到点就停问（还没人答 ⇒ 零批准；已经逐条批过但没有执行命令 ⇒
   * 同样按"没拿到执行命令"办，与临时包"人不执行就不落盘"同侧）。
   *
   * <p><b>★★ 无人在环的自动裁决 ⇒ 表态即 commit</b>（2026-10-17 控制方裁定，用户可推翻；落地见 {@link #isAutoApproval}）：
   * <b>上级代批闸 / AutoApprove</b> 给出的批准没有任何"人"参与（审批面上从来没有过登记项，也就没有人能看到这条、能敲 commit）⇒
   * 若也要求显式执行命令，每一个自动批准的包都要白等到窗口结束再按"已批未执行"作废，自动路径等于失效。 故：闸链批准 ⇒ 当场置 committed（{@link
   * Waiting#autoCommitted}），裁决者如实记 {@link #DECIDER_SUPERIOR}； <b>有人参与（上报到人、人经通道答）⇒ 仍然只表态、等显式
   * commit</b>——那是用户裁定 15 ③ 的原意。
   */
  private ApprovalDecision awaitAnswer(Waiting waiting, ApprovalRequest request) {
    ApprovalDecision answer = null;
    boolean everAnswered = false;
    // 第二段（等执行命令）的独立上界：第一次进入第二段时定下来（min(执行等待窗, 包 deadline)）。
    long commitDeadlineEpochMs = Long.MAX_VALUE;
    while (true) {
      if (waiting.committed || waiting.expired()) {
        return answer == null ? ApprovalDecision.DENY : answer;
      }
      if (answer == null) {
        // 第一段：交给既有协调器（闸链 ⇒ 通道 ⇒ 等人）。明细登记已由 settle 在调用本方法之前做完。
        try {
          answer = coordinator.decide(request);
        } catch (RuntimeException failure) {
          LOG.error(
              "跨进程决策包投审批面异常 ⇒ 零批准（fail-closed）：proposerKey={} requestId={}",
              waiting.proposerKey,
              request.id(),
              failure);
          return ApprovalDecision.DENY;
        }
        Optional<ApprovalDecision> evidence = evidenceOf(request, answer);
        if (evidence.isPresent()) {
          waiting.record(evidence);
          everAnswered = true;
        }
        if (everAnswered) {
          return answer;
        }
        if (isAutoApproval(answer, request)) {
          // ★★ 无人在环的自动裁决 ⇒ **表态即 commit**（2026-10-17 控制方裁定，用户可推翻；见本方法 javadoc 里那一段）：
          //   这条答复来自闸链（上级代批 / AutoApprove），审批面上**从来没有过登记项**（approval.requested 都没发），
          //   所以要等的那位"人"不存在——再等一等就等于把窗口白等满，最后按"已批未执行"收尾，等于让自动路径失去意义。
          //   ⇒ 当场把上报集合记成已批 + 置 committed，由 settle 交回提出者执行（**执行仍在提出者**，本类不执行）。
          //   ★ 与"人的路径"的分界就是 registered(...)：人答的那条路登记项一直在（通道答复不摘登记项）。
          waiting.commitApproved(waiting.escalated, DECIDER_SUPERIOR);
          LOG.info(
              "跨进程决策包由闸链自动裁决 ⇒ 表态即 commit（无人在环：没有登记项、没有人可敲执行命令）"
                  + " proposerKey={} packageId={} requestId={} approved={}",
              waiting.proposerKey,
              waiting.pkg.id(),
              request.id(),
              waiting.escalated);
          return answer;
        }
        continue;
      }
      // ★ 第二段（裁定 15）：协调器已经返回"不是答复"（无通道 / 超时 / 闸链终局拒）。这时**不再重进协调器**
      //   ——同一条 requestId 已经结算过，第二次投递没有意义（还会把"没人答"多问一遍）。改为等人下**执行命令**：
      //   人可以在决策面继续逐条表态，最后 commit；commit 会把正阻塞在协调器里的线程结算掉（见 commitPackage），
      //   所以把等待切成小段是为了"到点就走"这条上界，而不是为了轮询。
      // ★★ 2026-10-17 用户指令「太变态了直接等30分钟，改！」：这一段等的是"人已经在表态、**只差下执行命令**"，
      //   不能继承"等人答复"的 30 分钟包窗口（裁定 10）——否则每个没 commit 的用例/调用都白挂 30 分钟
      //   （实测：`CrossProcessDecisionKindAndPlaintextTest:194` 就卡在这里，线程停在 Waiting.awaitCommand）。
      //   ⇒ 用**独立、短、可注入**的执行等待窗（缺省 {@link #DEFAULT_COMMIT_WAIT_MILLIS}），仍受包 deadline 兜底；
      //   窗口内没等到 commit ⇒ 按裁定 15 ④ 的"已批未执行"收尾（**零落盘**，绝不替人执行）。
      if (commitDeadlineEpochMs == Long.MAX_VALUE) {
        commitDeadlineEpochMs =
            Math.min(waiting.deadlineEpochMs(), System.currentTimeMillis() + commitWaitMillis);
      }
      long nowMs = System.currentTimeMillis();
      if (nowMs >= commitDeadlineEpochMs) {
        LOG.warn(
            "等执行命令超时 ⇒ 已批未执行（零落盘，裁定 15 ④）：proposerKey={} requestId={} 执行等待窗={}ms",
            waiting.proposerKey,
            request.id(),
            commitWaitMillis);
        return ApprovalDecision.DENY;
      }
      waiting.awaitCommand(
          Math.min(WAIT_SLICE_MILLIS, Math.max(1L, commitDeadlineEpochMs - nowMs)));
    }
  }

  /** 等执行命令的切片长度：够短（到点能及时收），够长（不空转）。 */
  private static final long WAIT_SLICE_MILLIS = 500L;

  /**
   * 第二段"等执行命令"的**缺省**窗口（毫秒）——与人答复窗口（包 deadline，缺省 30 分钟， {@link
   * DecisionPackages#DEFAULT_DEADLINE_MILLIS}）**分开**：人答复可能要离开一会儿，而"表态完只差敲一下
   * 执行命令"不该按小时算。真要用长窗的可经七参构造覆盖。
   */
  public static final long DEFAULT_COMMIT_WAIT_MILLIS = 30_000L;

  /** 这一次协调器调用<b>算不算一次答复</b>（与 {@link #settle} 原先那段判据同源：登记项还在 = 人答的）。 */
  private Optional<ApprovalDecision> evidenceOf(ApprovalRequest request, ApprovalDecision answer) {
    if (answer == ApprovalDecision.APPROVE_ONCE || answer == ApprovalDecision.APPROVE_SESSION) {
      return Optional.of(answer);
    }
    return registered(request) ? Optional.of(answer) : Optional.empty();
  }

  /**
   * 这一次答复是不是<b>无人在环的自动裁决</b>（闸链给的批准，没有任何"人"参与）。
   *
   * <p><b>判据 = 批准 + 审批面上没有登记项</b>：{@code ApprovalCoordinator} 只在"确实要去问人"那条路上 {@code
   * pending.submit(...)}（发 {@code approval.requested}）；闸链命中（{@link
   * io.mosire.agentlib.approval.SuperiorJudgeGate} 代批、{@code AutoApproveGate}）与"无通道"都<b>不登记</b>。
   * 于是"批准 + 没有登记项"这个组合只可能是闸链给的——人答的那条路登记项一直在（通道答复不摘登记项，见 {@link #registered}）。
   *
   * <p>它<b>不</b>包含 {@code DENY}：闸链终局拒 / 到点没人答 / 无通道一律走 {@link #failClosed}（零批准、零落盘）， 与裁定 14
   * 同侧——这里判的是"谁来敲那一下执行命令"，不是"批没批"。
   */
  private boolean isAutoApproval(ApprovalDecision answer, ApprovalRequest request) {
    boolean approved =
        answer == ApprovalDecision.APPROVE_ONCE || answer == ApprovalDecision.APPROVE_SESSION;
    return approved && !registered(request);
  }

  /**
   * <b>执行命令之后的终局裁决</b>（用户裁定 15 ③ + 裁定 14）：已批准的那些按表态执行，没表态的按"静默 = 拒绝"归成被驳。
   *
   * <p>本类<b>不执行</b>任何东西：它把裁决交回提出者（子体），由子体用自己的权限与身份执行被批子集（设计 §5quinquies ①）。 轨迹按"人拍板"补齐（{@link
   * DecisionCascade#HUMAN_DECIDER}）——是人的表态把这一包定下来的，记录必须说真话。
   */
  private Verdict committedVerdict(
      Request request, Waiting waiting, DecisionPackage transientPackage) {
    Set<Integer> approved = new LinkedHashSet<>();
    Set<Integer> humanApproved = new LinkedHashSet<>();
    for (Item item : request.items()) {
      if (Boolean.TRUE.equals(waiting.verdicts.get(item.index()))) {
        approved.add(item.index());
        if (waiting.escalated.contains(item.index())) {
          humanApproved.add(item.index());
        }
      }
    }
    // ★ 那一级是谁：人下的执行命令 ⇒ 人；闸链自动裁决的"表态即 commit" ⇒ 上级（见 Waiting#autoCommitted）。
    //   轨迹跟着同一个值走：把代批记成"人拍的板"就是记录说谎（与 settle 里 byHuman 那条判据同源）。
    String decider = waiting.deciderNow();
    DecisionCascade.DecisionTrail completed = null;
    if (!waiting.escalated.isEmpty()) {
      completed = completeTrail(transientPackage.id(), humanApproved, null, decider);
    }
    if (completed != null) {
      approved = new LinkedHashSet<>(completed.finalApproved());
    }
    int rejected = request.items().size() - approved.size();
    if (rejected > 0) {
      LOG.info(
          "跨进程决策包由执行命令终结：{} 条未获明确同意（未表态按裁定 14 = 拒绝，零落盘）proposerKey={} packageId={} approved={} decider={}",
          rejected,
          request.proposerKey(),
          transientPackage.id(),
          approved.size(),
          decider);
    } else {
      LOG.info(
          "跨进程决策包由执行命令终结：全部 {} 条都被明确同意 proposerKey={} packageId={} decider={}",
          approved.size(),
          request.proposerKey(),
          transientPackage.id(),
          decider);
    }
    return new Verdict(
        approved,
        complement(request.items(), approved),
        reasons(request.items(), approved, humanApproved),
        completed,
        // ★ 用 equals 而不是 ==：decider 是**值**（String），比引用会把代批记成"人拍的板"
        DECIDER_SUPERIOR.equals(decider)
            ? DecisionCascade.SUPERIOR_DECIDER
            : DecisionCascade.HUMAN_DECIDER,
        decider);
  }

  /**
   * 临时包的<b>门面快照</b>（用户裁定 15 ②）：把内存态的逐条表态映射成一个 {@link DecisionPackage}，于是决策面的渲染、
   * "已表态/未表态"标记、状态机词汇（{@code PROPOSED}/{@code DECIDING}/{@code DECIDED}）三处与真包<b>同形</b>——
   * 人看到的两个面不是两套东西。
   *
   * <p>{@code full = false} ⇒ 逐条 {@code args} 在这里就被换掉（概览面<b>结构上</b>拿不到明文）。
   */
  private static DecisionPackage viewOf(Waiting waiting, boolean full) {
    DecisionPackage pkg = waiting.pkg;
    List<PackageItem> items = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      items.add(
          full
              ? item
              : new PackageItem(
                  item.index(), item.toolName(), Map.of(), item.argsDigest(), item.summary()));
    }
    PackageState state;
    if (waiting.committed) {
      state = PackageState.DECIDED;
    } else if (waiting.verdicts.isEmpty()) {
      state = PackageState.PROPOSED;
    } else if (waiting.allDecided()) {
      state = PackageState.DECIDED;
    } else {
      state = PackageState.DECIDING;
    }
    return new DecisionPackage(
        pkg.id(),
        pkg.proposerKey(),
        pkg.caller(),
        items,
        pkg.createdAtEpochMs(),
        pkg.deadlineEpochMs(),
        state,
        // ★ {@code PROPOSED} 态**必须**是 null decision（{@link DecisionPackage} 的构造期不变量：两个字段表达同一件事
        //   "裁没裁"，不一致就会让"谁在等裁决"出现两个答案）。这里过去无条件填 {@code decisionOf()} ⇒ 一个"还没有任何
        //   表态、正等人裁"的在途包一被门面快照到就抛 IllegalArgumentException——而快照的调用方正是**上级判定**：
        //   它在自己那条回调里读一次在途明文面（人/上级"此刻能查到的那一份"），异常顺着判定线程上抛 ⇒
        //   LlmSuperiorJudgement 按"判定调用失败"降级为拒 ⇒ 整个 MODE_LIMITED 包变成零批准。
        //   实测（2026-10-17）：CrossProcessDecisionKindAndPlaintextTest 的第 ① 条就是这么红的（它才是那条红的**根因**，
        //   而不是"代批没执行"）。DECIDING / DECIDED / 已下执行命令三档照旧给 {@code decisionOf()}。
        state == PackageState.PROPOSED ? null : waiting.decisionOf());
  }

  /** {@inheritDoc}（裁定 15 ②：决策面按它渲染"这一条定了没有、谁定的"）。 */
  @Override
  public List<DecisionPackages.ItemVerdict> itemVerdicts(String packageId) {
    if (packageId == null) {
      return List.of();
    }
    Waiting waiting = inFlight.get(packageId);
    return waiting == null ? List.of() : waiting.verdictRows();
  }

  /**
   * {@inheritDoc}
   *
   * <p><b>裁定 15 ②：在途包的条目级表态</b>——人可以从决策面按 {@code index} 一条一条地看、一条一条地裁， 而包始终只有这一个（不进 {@code
   * DecisionPackages}、不落盘、不发事件）。全部条目都表了态 ⇒ 门面快照变 {@code DECIDED}， 但<b>裁决还没交出去</b>：要等执行命令（见 {@link
   * #commitPackage}）——两段式在跨进程这条路上同样成立。
   */
  @Override
  public DecisionSubmission itemDecided(
      String packageId, int index, boolean approve, String by, String note) {
    Objects.requireNonNull(packageId, "packageId");
    Waiting waiting = inFlight.get(packageId);
    if (waiting == null) {
      throw new DecisionPackages.RejectionException(
          DecisionPackages.PACKAGE_NOT_FOUND, "未知或已结算的在途决策包 id=" + packageId);
    }
    if (!waiting.has(index)) {
      throw new DecisionPackages.RejectionException(
          DecisionPackages.INVALID_ITEM_INDEX,
          "表态引用了包内不存在的条目 index="
              + index
              + "（包 "
              + packageId
              + " 共 "
              + waiting.pkg.items().size()
              + " 条）");
    }
    if (waiting.verdicts.containsKey(index)) {
      throw new DecisionPackages.RejectionException(
          DecisionPackages.ITEM_ALREADY_DECIDED,
          "条目 index=" + index + " 已经表过态（裁决唯一：一条一次）包=" + packageId);
    }
    waiting.recordItem(index, approve, by);
    LOG.info(
        "跨进程在途包条目级表态 packageId={} index={} approve={} 已表态={}/{} by={}",
        packageId,
        index,
        approve,
        waiting.verdicts.size(),
        waiting.pkg.items().size(),
        by);
    DecisionPackage view = viewOf(waiting, false);
    return new DecisionSubmission(view, null, "逐条表态已记下（还不是执行）：全部条目表态完、或有人下执行命令之后，裁决才会交回提出者");
  }

  /**
   * {@inheritDoc}
   *
   * <p><b>裁定 15 ③：执行命令 = "就这些了"</b>——没表态的条目按裁定 14（静默 = 拒绝）归成被驳，已批准的照常交回提出者执行 （执行在子体，父侧不替它做）。重复下命令 ⇒
   * <b>幂等</b>（第二次拿到同一份门面快照，不重复结算、不再问人一次）。
   */
  @Override
  public DecisionSubmission commitPackage(String packageId, String by) {
    Objects.requireNonNull(packageId, "packageId");
    Waiting waiting = inFlight.get(packageId);
    if (waiting == null) {
      throw new DecisionPackages.RejectionException(
          DecisionPackages.PACKAGE_NOT_FOUND, "未知或已结算的在途决策包 id=" + packageId);
    }
    boolean anyApproved = waiting.verdicts.containsValue(Boolean.TRUE);
    if (!anyApproved && !waiting.committed) {
      throw new DecisionPackages.RejectionException(
          DecisionPackages.NO_ITEM_DECIDED, "包里没有一条被批准（在途包 " + packageId + "）⇒ 不下执行命令、零落盘");
    }
    if (waiting.committed) {
      // 重复下执行命令 ⇒ 幂等：不重复结算、不再问人一次（裁定 15 ⑪）
      LOG.info("跨进程在途包重复收到执行命令（幂等：不重复结算）packageId={} by={}", packageId, by);
      return new DecisionSubmission(viewOf(waiting, false), null, TRANSIENT_COMMIT_NOTE);
    }
    waiting.commit();
    // ★ 把正阻塞在审批通道上的提出者线程<b>立刻</b>放行（与 DecisionApprovalBridge#settleApprovalRequest 同一手法）：
    //   执行命令不是"审批通道上的答复"，协调器不会自己醒；不结算的话这一包要等满窗口才交得回去。
    settleApproval(waiting.requestId, anyApproved);
    LOG.info(
        "跨进程在途包收到执行命令 packageId={} 已批={} 未表态按拒绝={} by={}",
        packageId,
        waiting.verdicts.values().stream().filter(Boolean.TRUE::equals).count(),
        waiting.pkg.items().size() - waiting.verdicts.size(),
        by);
    DecisionPackage view = viewOf(waiting, false);
    return new DecisionSubmission(view, null, TRANSIENT_COMMIT_NOTE);
  }

  /**
   * 用"同一结论"结算审批面上那条请求（同 {@code DecisionApprovalBridge#settleApprovalRequest} 的手法与理由）： {@code
   * pending.decide} 让通道拿到一个<b>决定</b>（而不是"这条没了"），编排器因此立刻返回。
   *
   * <p>失败只 WARN：结算不成功最多让提出者多等一段（它的窗口仍由包 deadline 兜底），不该把一次已经生效的执行命令变成异常。
   */
  private void settleApproval(String requestId, boolean approved) {
    if (requestId == null || requestId.isBlank()) {
      return;
    }
    try {
      boolean settled =
          pending.decide(
              requestId,
              approved ? ApprovalDecision.APPROVE_ONCE : ApprovalDecision.DENY,
              DecisionApprovalBridge.DECISION_FACE_SOURCE);
      LOG.info(
          "跨进程在途包结算审批面请求（提出者线程立即放行，不再等窗口）requestId={} decision={} settled={}",
          requestId,
          approved ? "APPROVE_ONCE" : "DENY",
          settled);
    } catch (RuntimeException failure) {
      LOG.warn("结算审批面请求失败（提出者侧将等它自己的窗口） requestId={}", requestId, failure);
    }
  }

  /**
   * <b>一张正在等人裁的临时包</b>（裁定 15 起它多了"逐条表态的累积"）：
   *
   * <ul>
   *   <li>{@link #pkg} = 父侧自己收到的那份条目（含明文，只在内存里，结算完即释放）；
   *   <li>{@link #verdicts} = 逐条表态（{@code index ⇒ 批准？}；<b>没表态的条目不在其中</b>——未表态 ≠ 拒绝）；
   *   <li>{@link #decision} / {@link #evidence} = "协调器这一次给的答复"与"逐条表态是否已经把它定下来了"（见 {@link #step}）；
   *   <li>{@link #committed} = 有人下过执行命令（终局：裁决按表态算完交回提出者）。
   * </ul>
   *
   * <p><b>锁的边界</b>：本类只锁自己的三个字段；{@code coordinator.decide} 在锁<b>外</b>跑（在 {@link #awaitAnswer} 里）——
   * 否则人的一次条目级 POST 会被"正阻塞在审批通道上的线程"挡住，裁定 15 想要的操作就不成立了。
   */
  private final class Waiting {

    private final DecisionPackage pkg;
    private final String requestId;
    private final Set<Integer> escalated;
    private final String proposerKey;
    private final Map<Integer, Boolean> verdicts = new LinkedHashMap<>();
    private Optional<ApprovalDecision> evidence = Optional.empty();
    private boolean committed;

    /**
     * 这一包的<b>终局裁决者</b>（{@link #DECIDER_HUMAN} / {@link #DECIDER_SUPERIOR}）：缺省"人"——执行命令由人下；
     * 闸链自动裁决那条路（{@link #autoCommitted}）改记 {@link #DECIDER_SUPERIOR}。
     */
    private String decider = DECIDER_HUMAN;

    Waiting(DecisionPackage pkg, String requestId, Set<Integer> escalated, String proposerKey) {
      this.pkg = pkg;
      this.requestId = requestId;
      this.escalated = Set.copyOf(escalated);
      this.proposerKey = proposerKey;
    }

    synchronized boolean has(int index) {
      for (PackageItem item : pkg.items()) {
        if (item.index() == index) {
          return true;
        }
      }
      return false;
    }

    synchronized void recordItem(int index, boolean approve, String by) {
      verdicts.put(index, approve);
      notifyAll();
    }

    synchronized void commit() {
      committed = true;
      notifyAll();
    }

    /**
     * <b>"这些条目被批准了，而且执行命令也算下过了"</b>——两个调用方共用同一套表示（这正是本方法存在的理由）：
     *
     * <ol>
     *   <li><b>无人在环的自动裁决</b>（上级代批闸 / AutoApprove；2026-10-17 控制方裁定，用户可推翻）：把上报集合记成 已批准并置
     *       committed——没有人能来敲执行命令，再等只会白等满窗口、最后按"已批未执行"作废；
     *   <li><b>人在审批面按下批准</b>（用户裁定 15 ③ 在跨进程这条路上的落点）：那个键本来就是第二段的执行命令， 它还负责放行正阻塞在提交口上的提出者线程。
     * </ol>
     *
     * <p><b>为什么必须把逐条表态写进 {@link #verdicts}</b>（而不是只置 committed）：{@link #committedVerdict} 是终局的
     * <b>唯一</b>装配处，它按 {@code verdicts} 逐条算"哪些被明确同意"（裁定 14）。一份"committed 但 verdicts 为空"的
     * 状态会被读成<b>零批准</b>——包被静默作废，而提出者线程还恰恰是被这次批准放行的。实测（2026-10-17）： {@code
     * aHumanApprovalOnTheApprovalFaceIsTheCommitCommand} 就是这么红的。
     *
     * @param approvedIndexes 被批准的那些条目（闸链 = 上报集合；人批 = 上报集合）
     * @param by {@link #DECIDER_SUPERIOR} / {@link #DECIDER_HUMAN}——记录必须说真话（谁拍的板）
     */
    synchronized void commitApproved(Set<Integer> approvedIndexes, String by) {
      for (Integer index : approvedIndexes) {
        verdicts.put(index, Boolean.TRUE);
      }
      decider = by;
      committed = true;
      notifyAll();
    }

    /** 这一包的终局裁决者（持锁读：写全在 synchronized 方法里，读也必须持锁，否则就是"半同步"字段）。 */
    synchronized String deciderNow() {
      return decider;
    }

    synchronized boolean allDecided() {
      return verdicts.size() >= pkg.items().size();
    }

    /** 这一包的到点时刻（墙钟毫秒）——等待上界（裁定 10：等待窗口 = 包自己的 deadline）。 */
    synchronized long deadlineEpochMs() {
      return pkg.deadlineEpochMs();
    }

    /**
     * 等人下执行命令（一小段；超时 / 被 {@link #commit} 唤醒都会返回）。
     *
     * <p><b>这里的 {@code WA_NOT_IN_LOOP} 是误报（显式抑制，写明理由）</b>：本方法有意<b>只等一小段</b>——循环在调用方 （{@link
     * #awaitAnswer} 的 {@code while (true)}），那段循环每轮都要重新判断"执行命令窗到点没有"；把 {@code wait}
     * 套进本方法的循环里会让"到点就走"这条上界失效（那正是 2026-10-17 之前"白挂 30 分钟"的形状）。
     */
    @SuppressFBWarnings(
        value = "WA_NOT_IN_LOOP",
        justification = "有意只等一段：循环（含到点判断）在调用方 awaitAnswer 的 while(true) 里，见方法注释")
    synchronized void awaitCommand(long millis) {
      try {
        wait(Math.max(1L, millis));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    /** 窗口到点（上界 = 包自己的 deadline；协调器自己的 timeout 只决定它每一段等多久）。 */
    synchronized boolean expired() {
      return System.currentTimeMillis() >= pkg.deadlineEpochMs();
    }

    /** 记下一次协调器答复（{@code evidence} 为空 = "这次不算有人答"）⇒ true 表示这就是终局答复。 */
    synchronized boolean record(Optional<ApprovalDecision> answer) {
      evidence = answer;
      notifyAll();
      return answer.isPresent();
    }

    /**
     * 拿"此刻能不能终结"：
     *
     * <ol>
     *   <li>执行命令下过 ⇒ 空（由 {@link #committedVerdict} 按逐条表态产出终局）；
     *   <li>协调器答复在 ⇒ 直接用（人批准/驳回、上级代批、无人答 ⇒ 零批准）；
     *   <li>否则 ⇒ 空（继续等）。
     * </ol>
     */
    synchronized Optional<ApprovalDecision> step() {
      if (committed) {
        return Optional.empty();
      }
      return evidence;
    }

    /** 门面快照里的裁决快照：累积中就给"已批准哪些"的临时形；执行命令下过就按裁定 14 归成终局形。 */
    synchronized Decision decisionOf() {
      Set<Integer> approved = new LinkedHashSet<>();
      for (Map.Entry<Integer, Boolean> entry : verdicts.entrySet()) {
        if (Boolean.TRUE.equals(entry.getValue())) {
          approved.add(entry.getKey());
        }
      }
      if (!committed && !allDecided()) {
        return new Decision(approved, DECIDED_BY_ITEM_VERDICTS, TRANSIENT_DECIDING_NOTE);
      }
      return new Decision(approved, DECIDED_BY_ITEM_VERDICTS, TRANSIENT_COMMIT_NOTE);
    }

    /** 逐条表态行（决策面按 index 渲染"这一条定了没有、谁定的"）。 */
    synchronized List<DecisionPackages.ItemVerdict> verdictRows() {
      List<DecisionPackages.ItemVerdict> rows = new ArrayList<>(verdicts.size());
      for (PackageItem item : pkg.items()) {
        Boolean approved = verdicts.get(item.index());
        if (approved != null) {
          rows.add(
              new DecisionPackages.ItemVerdict(
                  item.index(), approved, DECIDED_BY_ITEM_VERDICTS, ""));
        }
      }
      return List.copyOf(rows);
    }
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
   * <p><b>{@code kind} 由 {@link #requestKind} 定</b>（P3e，设计 §5sexies⑦）：取<b>父侧那一刻算出来的那份</b> {@link
   * AskKind}——敏感区 / 缺料 ⇒ {@code SENSITIVE}（只到人）；上报条目全是 {@code MODE_LIMITED} 且明细缝在 ⇒ {@code
   * MODE_LIMITED}（上级看得见逐条明文才谈得上代批）；缝不在 ⇒ 退回 {@code SENSITIVE}（fail-closed）。
   *
   * <p><b>摘要不含明文</b>：{@code summary} 只有包 id、提出者、条数与工具名；{@code digest} 复用 {@code
   * DecisionApprovalBridge#packageDigest}（对包内已脱敏的 {@code argsDigest} 再取一次短摘要， <b>唯一实现点</b>）。
   */
  private ApprovalRequest requestFor(
      DecisionPackage transientPackage, Set<Integer> escalated, List<Item> items) {
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
        requestKind(items, escalated),
        "");
  }

  /**
   * 这条请求的 {@link AskKind}（谁有资格批它）——<b>与 {@code DecisionApprovalBridge#requestKind} 同一条判据</b>（P3e）：
   *
   * <ol>
   *   <li>★ <b>{@link #ledger} 未接线</b> ⇒ {@link AskKind#SENSITIVE}：那条路上 {@link Item#askKind()}
   *       是<b>调用方自报</b>的（老形态里由子体经 MCP 送进来），不是"父侧那一刻算出来的那一份"——没有权威来源就不上代批台；
   *   <li>明细缝未装配 ⇒ {@link AskKind#SENSITIVE}（**不是**只落 WARN 再交上级）：上级只看得到工具名与包摘要就拍板，
   *       那是"看一半批全部"——看不见就不给批；
   *   <li>上报条目里有<b>任何一条</b>不是 {@link AskKind#MODE_LIMITED} ⇒ {@code SENSITIVE}（敏感区永远到人；缺料同样落这一侧）；
   *   <li>剩下的情形（全 {@code MODE_LIMITED} + 明细缝在 + 父侧队列在）⇒ {@code MODE_LIMITED}：{@link #settle}
   *       会把父侧自己那份逐条明文 登记进明细缝，上级判定闸因此<b>真看得见</b>要改什么。
   * </ol>
   *
   * <p>判据的料来自<b>父侧本轮队列</b>（{@link Item#askKind()} 由 {@code reconcile} 从队列条目拷来）——不是子体送的：子体既送不出可信副本，
   * 也不必送（闸位知识跟着真工具走）。
   */
  private AskKind requestKind(List<Item> items, Set<Integer> escalated) {
    if (ledger == null) {
      LOG.warn(
          "本请求只到人（AskKind=SENSITIVE）：父侧本轮队列未接线 ⇒ 没有「父侧那一刻算出来的那份 kind」（不采信调用方自报的 kind）items={} 上报={}",
          items.size(),
          escalated);
      return AskKind.SENSITIVE;
    }
    if (judgeDetails.isEmpty()) {
      LOG.warn(
          "本请求只到人（AskKind=SENSITIVE）：上级判定明细缝未装配（看不见内容就不给批，fail-closed）items={} 上报={}",
          items.size(),
          escalated);
      return AskKind.SENSITIVE;
    }
    for (Item item : items) {
      if (!escalated.contains(item.index())) {
        continue;
      }
      if (item.askKind() != AskKind.MODE_LIMITED) {
        LOG.warn(
            "本请求只到人（AskKind=SENSITIVE）：上报条目里有敏感区条目或缺料（index={} kind={}）上报={}",
            item.index(),
            item.askKind(),
            escalated);
        return AskKind.SENSITIVE;
      }
    }
    return AskKind.MODE_LIMITED;
  }

  /**
   * 上报条目 → 判定明细（工具名 + 人读摘要 + 参数<b>明文</b>）：与 {@code DecisionApprovalBridge#judgementItems}
   * <b>同一形状</b>，料取<b>父侧自己那份</b>临时包条目（{@link DecisionItemDigest#itemSummary} 在登记那一刻已算好）。
   *
   * <p>只进内存态登记表（{@link SuperiorJudgeDetailRegistry}），判定读完由 {@link #settle} 的 {@code finally} 立刻释放；
   * 本类不打印、不落库、不进任何载荷。
   */
  private static List<JudgementItem> judgementItems(
      DecisionPackage transientPackage, Set<Integer> escalated) {
    List<JudgementItem> items = new ArrayList<>(transientPackage.items().size());
    for (PackageItem item : transientPackage.items()) {
      if (escalated.contains(item.index())) {
        items.add(new JudgementItem(item.toolName(), item.summary(), item.args()));
      }
    }
    return List.copyOf(items);
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
   * 把拍板的那一级补齐进临时包的轨迹（人批的 / 上级代批闸批的<b>只</b>是上报的那些条目）。补不齐 / 没有级联 ⇒ {@code null}，
   * 调用方按<b>零批准</b>处理——绝不退回"没有人那一级"的旧轨迹（那会把一次没记全的裁决说成"级联自己批的"）。
   *
   * @param decider 这一级的 id（{@link DecisionCascade#HUMAN_DECIDER} / {@link
   *     DecisionCascade#SUPERIOR_DECIDER}；由"登记项在不在"判出来，见 {@link #settle}）
   */
  private DecisionCascade.DecisionTrail completeTrail(
      String packageId, Set<Integer> humanApproved, ApprovalRequest request, String decider) {
    if (cascade == null) {
      return null;
    }
    try {
      return cascade
          .complete(
              packageId,
              humanApproved,
              DecisionCascade.SUPERIOR_DECIDER.equals(decider) ? SUPERIOR_NOTE : HUMAN_NOTE,
              decider)
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
   * 接线的父侧队列形态下的一次提交<b>内容指纹</b>（P3d）：逐条 {@code index|toolName|digest} 规范化后取短摘要。
   *
   * <p><b>为什么不含 {@code args}/{@code classKey}/{@code askKind}</b>：接线之后这三样<b>不参与</b>结算（权威的那份在父侧队列里，
   * 见 {@link #ledger}），把它们算进指纹只会让"重试同一次提交"因为子体多送/少送一个不参与判定的字段而变成"同键不同内容"
   * ——那会把一次正常重试误判成具名拒。指纹要认的是"是不是同一批条目"，那正是 {@code index/toolName/digest} 三件。
   */
  private static String ledgerFingerprint(Request request) {
    StringBuilder material = new StringBuilder();
    for (Item item : request.items()) {
      material
          .append(item.index())
          .append('|')
          .append(item.toolName())
          .append('|')
          .append(item.digest())
          .append('\n');
    }
    return DecisionItemDigest.hexDigest(material.toString());
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
