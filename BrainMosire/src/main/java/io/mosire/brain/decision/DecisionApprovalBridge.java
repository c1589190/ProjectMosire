package io.mosire.brain.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.JudgementItem;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.approval.SuperiorJudgeDetailRegistry;
import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.DecisionCommitPort;
import io.mosire.agentlib.decision.DecisionEventTypes;
import io.mosire.agentlib.decision.DecisionItemDigest;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.DecisionSubmission;
import io.mosire.agentlib.decision.DecisionSubmissionPort;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.decision.ProposeRequest;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import java.time.Instant;
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
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 决策包<b>审批桥</b>（设计 §5.9 ③，RB2b）：把"包已登记"变成审批面上<b>一条</b> {@link ApprovalRequest}， 等答复回来后把答复<b>回填</b>成
 * RB1 的统一裁决，再按装配决定是否走一次提交。
 *
 * <p><b>为什么是回调而不是轮询</b>：建包方（P1 的回合收尾——{@link #flush}）在包登记成功后调 {@link #onProposed}，把"我刚登记了一个包"
 * 主动交给本桥 （见 {@code App} 的装配）。轮询 {@code DecisionPackages.pending()} 会发现"新包"，但轮询有固有延迟、会在无包时白烧 CPU，
 * 而且它答不出"这个包是不是已经投过了"——本桥按回调<b>一包一投</b>，投递动作有唯一的触发点。
 *
 * <h2>一个包 = 一条请求（§5.9 ③ 取代 §5.4 那句话）</h2>
 *
 * <p>决策单位是<b>包</b>：一次裁决、逐条例外、被批子集一次提交。若把一个包拆成 N 条请求，"统一裁决" 就要在 N 条上做二次归并， 等于把决策单位又切回条目（§5.9 ③
 * 的留痕）。所以本桥只发一条请求： {@code classKey} = {@value #CLASS_KEY}（会话放行按 {@code (callerKey, classKey)}
 * 归类，因此天然复用），{@code summary} = 人读一行（包 id + 条目数 + 工具名列表）， {@code digest} = 包级摘要（自己算，取前 {@link
 * DecisionItemDigest#DIGEST_HEX_CHARS} 个十六进制字符）。逐条细节<b>不进</b>请求载荷 ——那是包的提示面/HTTP 面（RB3）。
 *
 * <h2>复用既有审批链，不造第二套内核</h2>
 *
 * <p>投递走的是 {@code ToolCallAuthorizer} 今天在用的同一套：{@link ApprovalCoordinator}（含既有闸链与 fail-closed 口径）+
 * 装配层那<b>唯一</b>一份 {@link PendingApprovals} + 既有通道。本桥<b>不</b>新建登记表、<b>不</b>新建通道、 <b>不</b>新建 HTTP
 * 端点——§5.4 的验收判据（包路径下没有第二个 {@code PendingApprovals}、没有第二个 HTTP 端点族）由装配保证： 传进来的是装配层那两个实例。
 *
 * <h2>答复 → 裁决（整包批 / 整包驳；★ P2b 起只作用于"上报给人的那些条目"）</h2>
 *
 * <p>答复 = {@link ApprovalDecision#APPROVE_ONCE}/{@link ApprovalDecision#APPROVE_SESSION} ⇒
 * 上报集合里的条目全批； 答复 = {@link ApprovalDecision#DENY} ⇒ 上报集合里的条目全驳（那些条目零落盘）。<b>下级已经预裁定批准的条目不在此列</b>
 * ——人不能替它们改口（要改要走"上一级修改下一级"的级联路）。逐条例外（选择性执行一部分）走 RB3 的 HTTP 面。
 *
 * <p><b>怎么分辨"有人答了"与"没人答/没通道/编排故障"</b>：编排器把后者一律归为 {@code DENY} 并<b>摘掉登记项</b>（或不登记），
 * 而通道的硬约束是"答复必须先落进登记表再返回"（见 {@code ApprovalChannel}）。因此：{@code APPROVE_*} ⇒ 一定是答复； {@code DENY} ⇒
 * 只有 {@link PendingApprovals#get(String)} 还查得到这条请求时才算答复。查不到 ⇒ <b>没人答复</b>：见下面「没人答复 =
 * 当场作废」一节——本桥绝不会把"没人答" 写成"整包驳"。★ 同一条判据（登记项在不在）还用来把"人答的"与"闸链代批的"分开， 记进轨迹的那一级（人 / 代批）。
 *
 * <h2>★ P2b：逐级预裁定（设计 §5ter 裁定 12 / §5quater）</h2>
 *
 * <p>装配了 {@link DecisionCascade} ⇒ 投递之前先跑一次级联：从提出者往上逐级问"谁有能力做这条"（{@link AskKind#MODE_LIMITED}
 * 才可被预裁定；{@link AskKind#SENSITIVE} <b>强制到人</b>）。跑完分两条路：
 *
 * <ul>
 *   <li><b>一条都没上报</b> ⇒ 本桥直接 {@code decide}（{@code by} = 终局裁决者那一级）+ 一次提交，<b>不投审批面</b>（没有人要问）；
 *   <li><b>有上报</b> ⇒ 照旧投<b>一条</b>包级请求，但人裁的只是上报的那些条目；请求的 {@code tool}/{@code summary} 也只取上报的条目。
 * </ul>
 *
 * <p>轨迹（{@link DecisionCascade.DecisionTrail}）落两处：{@code decision.package.decided} 事件的 {@code
 * trail} 字段 （逐级"谁批/驳了哪些条、谁改了谁"）与"查自己历史"的读口。★ 两条路都<b>只调一次</b> {@code decide}——级联从不自己改状态机。
 *
 * <h2>★ P2b：包级请求<b>不接受会话放行</b>（设计 §5ter 裁定 13 的口径面）</h2>
 *
 * <p>包级请求的 {@code callerKey} 取<b>包级唯一桶</b>（{@link #packageCallerKey}），装配层在 {@code
 * ApprovalCoordinator} 的 {@code sessionGrantable} 缝上认出它、把 {@code APPROVE_SESSION} <b>降级为一次</b>并落一行
 * WARN—— 否则人对一个包批的"本会话"会把以后<b>每一个</b>包都放行（{@code AutoApproveGate} 按 {@code (callerKey, classKey)}
 * 命中， 而包级 classKey 是冻结字面量）。不同范围的两次裁决不得互相放行，这条就是那个口子。
 *
 * <h2>★ 没人答复 = 当场作废，不是"等包自己到点"（RB2c，设计 §5.12 裁定 B / §5.6）</h2>
 *
 * <p>没人答复的两条路——<b>审批窗口到期而无人答复</b>与<b>无可用通道</b>（编排器在投递前就拒）——都走 {@link
 * DecisionPackages#expireNow(String, long)}：包<b>当场</b>置 {@code EXPIRED}（零落盘、不静默执行），并落<b>一行
 * WARN</b>（含包 id 与原因）。这不是"提前过期"，而是 §5.6 本来就写的语义：「决策超时 / 无可用通道 ⇒ 整包 {@code EXPIRED}，零落盘」。
 *
 * <p><b>为什么必须当场作废</b>：审批窗口（缺省 300s）短于包的裁决窗口（缺省 30min，{@link
 * DecisionPackages#DEFAULT_DEADLINE_MILLIS}），而"一实例一个未决包"只数 {@code
 * PROPOSED}——不作废，这个早已无人可批的包会白占该实例的名额约 25 分钟（§5.12 裁定 B 的问题陈述）。作废 ⇒ 该 {@code proposerKey} 的未决名额即刻释放。
 *
 * <p><b>包的 deadline 仍是上限</b>：本桥只把"没人会答"的包提前收掉；{@link DecisionPackages#expire(long)} 那条按 deadline 扫的
 * 惰性路径照旧，不由本桥替代。
 *
 * <p><b>两条路的区分只进日志</b>：{@code ApprovalCoordinator#decide} 只返回决定本身，{@code SOURCE_TIMEOUT} / {@code
 * SOURCE_NO_CHANNEL} / {@code SOURCE_ERROR} 只进 {@code approval.decided} 事件的 {@code by}
 * 字段（没有可编程出口，见下条 边界）。本桥按可观测的形状分：编排器在<b>请求窗口结束前</b>就返回"没人答复" ⇒ 它没等到窗口（无可用通道 / 编排兜底异常）； 到/过窗口才返回 ⇒
 * 窗口到期而无人答复（判据与残差写在 {@link #noAnswerReason}）。
 *
 * <p><b>已知边界（如实记）</b>：闸链上终局的 {@code DENY}（例如将来装上一个按 classKey 拒的 DenyGate）同样不会留下登记项 ⇒
 * 与超时一样按"没人答复"处理（<b>当场作废</b>，只是 WARN 的原因文字会落进"无可用通道"那一档）：fail-closed 的一侧是"不执行"， 而它今天不触发——{@link
 * AskKind#SENSITIVE} 走到人的那一段没有会出 {@code DENY} 的快判闸（AutoApproveGate 只会放行）。
 * 要把它精确地归因成"谁拒的"，得让审批层给出这个可编程出口 —— 那是改审批链的公开面，不在本单范围。
 *
 * <h2>裁决之后：提交口是可选的</h2>
 *
 * <p>装配注入了 {@link DecisionCommitPort} ⇒ 立刻调一次 {@link DecisionPackages#commit(String,
 * DecisionCommitPort)} （被批子集一次提交、一份统一回执；被驳条目永不进 calls）。<b>没注入</b> ⇒ 不调 commit，落一行 WARN、包保持 {@code
 * DECIDED} ——"批了但没人接盘"必须看得见，不能被静默说成"已落定"。今天 RC1 的世界写口尚未实现，生产装配就走这条分支。
 *
 * <h2>★ 谁在给上级 Agent 代批：明细缝在不在（RB3，用户 2026-10-16 裁定）</h2>
 *
 * <p>用户 2026-10-16 的裁定是「上级 Agent 代批……<b>肯定展示全部内容啊</b>」：包<b>可以</b>交上级判定（{@link
 * AskKind#MODE_LIMITED}），但前提是上级看得见逐条内容。所以 {@link #requestFor} 按<b>明细缝在不在</b>决定 kind： 装配了 {@link
 * SuperiorJudgeDetailRegistry}（判定面能读到逐条明细 + 明文参数）⇒ {@code MODE_LIMITED}； 没装配 ⇒ 退回 {@link
 * AskKind#SENSITIVE}（只到人）并落一行 WARN。<b>看不见就不给批</b>——让上级只看"决策包 dp-1：5 条提议（doc_edit、…）"
 * 就点头，是把人的判断力换成一句谎言，本项目不做。
 *
 * <h2>★ 事件面（RB3，设计 §5.10① / §5.13(a)）：四类事件都从本桥发</h2>
 *
 * <p>本桥是"提议 → 裁决 → 提交"那条链的<b>唯一写者</b>，所以四个事件也只有这一个发射点（状态机 {@link DecisionPackages}
 * 保持纯，不在它里面注入总线——否则 HTTP 面的裁决会让 {@code approvedCount} 一类派生值出现两个来源）：
 *
 * <ul>
 *   <li>{@code decision.package.proposed}：{@link #onProposed} 通过状态检查之后（= 包真的进入待裁决态；到点建出来的包 {@code
 *       EXPIRED} 不算，它从来没有进入过决策面）；
 *   <li>{@code decision.package.decided}：{@link #backfill} 与 {@link #submitDecision} 里 {@code
 *       packages.decide(...)} 返回之后（<b>全驳也发</b>，{@code approvedCount=0}）；
 *   <li>{@code decision.package.committed} / {@code .conflicted}：{@link #commitAfterDecision} 拿到
 *       port 回执<b>之后</b>。 ★ <b>port 抛异常 ⇒ 一条 {@code committed} 都不发</b>（宁可少一条事件，也不写一条假成功）； "被批子集为空 ⇒
 *       REJECTED"那条路也不发（零落盘，没有提交这回事）。
 * </ul>
 *
 * <p>载荷的骨架严格只此六项（顺序即组装顺序）：{@code packageId / itemCount / approvedCount / rejectedCount / toolNames
 * / digests}；P2b 另加三项（都不含明文）：{@code proposerKey}（归属键，历史读口按它过滤"只能查自己的"）、{@code trail}（级联轨迹， §5quater
 * ③）、{@code outcomes}（逐条去向 + 可空理由，只在拿到回执之后）。{@code committed}/{@code conflicted} 另加 {@code
 * revisionRef}（不透明字符串；conflicted 无引用可言，记空串）。{@code expired}（P2b 第五条）另加 {@code reason}。
 * <b>绝不写明文参数</b>：{@code digests} 是逐条脱敏摘要——{@code outcomes} 的 {@code reason} 是拒码/异常类名/包层理由，不是参数。
 * 投递失败只 {@code LOG.warn}，<b>不</b>改变状态机与裁决 （同 {@link ApprovalCoordinator} 的口径）。
 *
 * <h2>★ 决策面 HTTP 的裁决回填（设计 §5.13(e)）：{@link DecisionSubmissionPort}</h2>
 *
 * <p>HTTP 面的 {@code POST /api/decision-packages/{id}} <b>不</b>直调状态机，而是调本桥的 {@link
 * #submitDecision}——三件事因此都发生：{@code decided} 事件落总线、提交口被调一次（{@code COMMITTED}/{@code CONFLICTED}
 * 才有出处）、审批面上那条包级请求被<b>结算</b>（P2b：提出者线程立即放行，不必等满窗口——P2a 发现 B）。 状态机的具名拒（未知 / 已过期 / 不可决议 /
 * 非法裁决）<b>原样上抛</b>，由 HTTP 面按 {@code code()} 映射状态码（设计 §5.11 第 11 条）。
 *
 * <h2>线程与生命周期（P1b 起：同步）</h2>
 *
 * <p>投递要等人（{@code ApprovalCoordinator#decide} 阻塞到答复或窗口结束）——本桥<b>在调用方的线程上</b>等（设计 §5 裁定 4）： {@link
 * #onProposed} 不再起虚拟线程，{@link #flush} 返回时裁决与执行都已经发生。★ 代价照实记：慢裁决会占住那个提出者 （主 Agent 的回合收尾 ⇒
 * 它的对话线程在窗口内被占住）。这是"同步投递"的固有代价，也是 V2「下一轮直接调工具看结果」的前提。
 *
 * <h2>密钥纪律（红线 6）</h2>
 *
 * <p>{@code summary}/{@code digest} 与全部日志只出现包 id、工具名、条目数、脱敏摘要——参数明文只活在包内 {@code args} 与提交口那条路上，
 * 本桥一行都不碰。
 */
public final class DecisionApprovalBridge implements DecisionSubmissionPort {

  private static final Logger LOG = LoggerFactory.getLogger(DecisionApprovalBridge.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 包级类键（设计 §5.9 ③ 的冻结字面量）：一条包一条请求，会话放行按 {@code (callerKey, 本键)} 归类。 */
  public static final String CLASS_KEY = "decision:package";

  /**
   * 包级请求的 {@code callerKey} 前缀（P2b）：{@code pkg:<提出者>#<包 id>} = <b>一个包一个桶</b>。
   *
   * <p>理由见 {@link #packageCallerKey}：包级 classKey 是冻结字面量，若 callerKey 取令牌桶，一次"本会话"放行就会把以后所有的包都放行。
   * 装配层用 {@link #isPackageCallerKey} 认出它、在那里把会话语义收成"一次"（{@code ApprovalCoordinator} 的 {@code
   * sessionGrantable} 缝，一个字都不用改那个类）。
   */
  public static final String PACKAGE_CALLER_KEY_PREFIX = "pkg:";

  /** 决策面 HTTP 结算审批面请求时的 {@code by}（审计：这条请求是被"决策面已经答过了"结算掉的）。 */
  public static final String DECISION_FACE_SOURCE = "decision-packages";

  /**
   * 回填进 {@link Decision#by()} 的裁决人标识。
   *
   * <p><b>为什么不是通道名/闸名</b>：{@code ApprovalCoordinator#decide} 只返回决定本身，"谁答的"（{@code by}）留在它的 {@code
   * approval.decided} 事件里，没有可编程出口——本层拿不到就<b>不编</b>。精确到通道的审计仍以那条事件为准； 这里只诚实记"审批面答的"。
   */
  public static final String BY_APPROVAL = "approval";

  /** 没人答复的原因文字（RB2c）：审批窗口到期而无人答复——编排器的 {@code SOURCE_TIMEOUT} 那一档。只进 WARN。 */
  private static final String REASON_WINDOW_EXPIRED = "审批窗口到期而无人答复";

  /**
   * 没人答复的原因文字（RB2c）：无可用通道——编排器在投递前就拒（{@code SOURCE_NO_CHANNEL}）那一档。只进 WARN。
   *
   * <p>文字里带上"窗口结束前即拒"这条判据本身，读日志的人才知道这个结论是<b>推出来的</b>，而不是编排器报的（见 {@link #noAnswerReason}）。
   */
  private static final String REASON_NO_CHANNEL = "无可用通道（审批面在窗口结束前即拒：投递前就没有通道）";

  private final DecisionPackages packages;
  private final ApprovalCoordinator coordinator;
  private final PendingApprovals pending;

  /** 世界提交口：{@code Optional.empty()} = 未装配（裁决后只落 WARN、包保持 {@code DECIDED}，见类注释）。 */
  private final Optional<DecisionCommitPort> commitPort;

  /** 事件总线；<b>可 null</b>（null = 不发事件，状态机与裁决一字不变——同 {@code ApprovalCoordinator} 的构造口径）。 */
  private final EventBus bus;

  /**
   * 上级判定的逐条明细缝（{@code Optional.empty()} = 未装配）：既决定"包能不能交上级代批"（见类注释）， 也是判定面读明文的唯一入口。装配层把它同时交给本桥与
   * {@code LlmSuperiorJudgement}——两边必须是<b>同一个实例</b>。
   */
  private final Optional<SuperiorJudgeDetailRegistry> judgeDetails;

  /**
   * 逐级预裁定（P2b，设计 §5ter 裁定 12 / §5quater）：{@code Optional.empty()} = 未装配 ⇒ 包一律"整包到人"
   * （本单之前的行为，逐字不变）。★ 它<b>只</b>决定"哪些条目还需要问人"——级联跑完仍然只调一次 {@code packages.decide}。
   */
  private final Optional<DecisionCascade> cascade;

  /**
   * 包 id ⇒ 审批面上那条包级请求的 id（在职期间 = 提出者线程正阻塞在那条请求上）。
   *
   * <p><b>为什么要有它</b>（P2a 发现 B）：人在<b>决策面</b>答完之后，审批面那条请求还挂在登记表上等人，提出者线程要等满 {@code
   * approval.timeoutSeconds} 才返回。决策面答复成功回填之后要把它<b>结算</b>掉（{@link #settleApprovalRequest}），
   * 而结算需要那条请求的 id——请求 id 是在 {@link #deliver} 里现造的，只有这里记着。
   *
   * <p>投递线程结束时移除（那时没人再等它了）：映射缺失 = "没有在等的提出者"，结算自然成为空操作。
   */
  private final Map<String, String> openRequests = new ConcurrentHashMap<>();

  /**
   * 4 参构造（RB2b 的既有形状逐字保留）：不发事件、不走上级代批（包只到人）。
   *
   * <p>保留它不是兼容洁癖：没有总线与明细缝的部署（离线夹具、只验投递/回填的用例）行为必须与 RB2b 逐字相同。
   *
   * @param packages 决策包登记表（装配层持有的<b>那一个</b>实例；本桥不建单例）
   * @param coordinator 既有审批编排器（含闸链与 fail-closed 口径；本桥不新建第二套）
   * @param pending 装配层那<b>唯一</b>一份待审批登记表——既供编排器投递（它自己 submit），也供本桥分辨"有人答了" 与"没人答"（见类注释）
   * @param commitPort 世界提交口（{@code Optional.empty()} = 未装配；今天生产装配就是 empty。★ 用 Optional 而不是可空引用：
   *     装配层今天只可能传 null，可空写法会被 SpotBugs 判成「恒为 null 的加载 + 冗余判空」（NP_LOAD_OF_KNOWN_NULL_VALUE /
   *     RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE），而那两条报的其实是**真的**——所以这里把「未装配」变成类型上的一等状态）
   */
  public DecisionApprovalBridge(
      DecisionPackages packages,
      ApprovalCoordinator coordinator,
      PendingApprovals pending,
      Optional<DecisionCommitPort> commitPort) {
    this(packages, coordinator, pending, commitPort, null, Optional.empty());
  }

  /**
   * 5 参构造（RB3）：在既有四件之外接上事件总线。
   *
   * @param bus 事件总线（<b>可 null</b> = 不发事件：{@code decision.package.*} 四类事件经它出去，落库由装配面的订阅负责）
   */
  public DecisionApprovalBridge(
      DecisionPackages packages,
      ApprovalCoordinator coordinator,
      PendingApprovals pending,
      Optional<DecisionCommitPort> commitPort,
      EventBus bus) {
    this(packages, coordinator, pending, commitPort, bus, Optional.empty());
  }

  /**
   * 6 参构造（RB3 完整形状）：事件总线 + 上级判定的明细缝。
   *
   * @param judgeDetails 逐条明细登记表（{@code Optional.empty()} = 明细缝未装配 ⇒ 包退回"只到人"，见类注释）。 ★
   *     装配层必须把<b>同一个实例</b>交给 {@code LlmSuperiorJudgement}：交给桥、却没交给判定面，等于让上级盲判；
   *     只交给判定面、没给桥，则桥登记不了明细（同样退回到人）。两边是同一个对象这件事，由装配层保证。
   */
  public DecisionApprovalBridge(
      DecisionPackages packages,
      ApprovalCoordinator coordinator,
      PendingApprovals pending,
      Optional<DecisionCommitPort> commitPort,
      EventBus bus,
      Optional<SuperiorJudgeDetailRegistry> judgeDetails) {
    this(packages, coordinator, pending, commitPort, bus, judgeDetails, Optional.empty());
  }

  /**
   * 7 参构造（P2b 完整形状）：在既有六件之外接上<b>逐级预裁定</b>。
   *
   * @param cascade 级联控制器（{@code Optional.empty()} = 不做级联 ⇒ 包一律"整包到人"，与本单之前逐字一致）。 ★ 装配层必须把"工具名 ⇒
   *     工具"的查表也给它（能力判据要读工具的 spec/roles/resources）， 否则它只会把每一条都判成"没人有能力"——那不会出错，但级联等于没接（判据见 {@link
   *     DecisionCascade}）。
   */
  public DecisionApprovalBridge(
      DecisionPackages packages,
      ApprovalCoordinator coordinator,
      PendingApprovals pending,
      Optional<DecisionCommitPort> commitPort,
      EventBus bus,
      Optional<SuperiorJudgeDetailRegistry> judgeDetails,
      Optional<DecisionCascade> cascade) {
    this.packages = Objects.requireNonNull(packages, "packages");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.pending = Objects.requireNonNull(pending, "pending");
    this.commitPort = Objects.requireNonNull(commitPort, "commitPort");
    this.bus = bus;
    this.judgeDetails = Objects.requireNonNull(judgeDetails, "judgeDetails");
    this.cascade = Objects.requireNonNull(cascade, "cascade");
  }

  /**
   * 提议成功回调（建包方——P1 的回合收尾——的注入缝）：发 {@code decision.package.proposed}（四类事件的第一个发射点，见类注释） 并<b>同步</b>投递
   * + 等裁决 + 回填（裁定 4）；返回该包的<b>最终</b>快照（投递走完那几段之后现取）。
   *
   * <p><b>为什么变成同步的</b>（设计 §5 裁定 4，用户 2026-10-16：「投递根据之前的裁定，很显然应当是同步的」）：提议工具已随裁定 6
   * 移除，调用方改成本管线的回合收尾——它本来就要等这批裁决的结果才能继续（被批子集在裁决之后立刻执行）。 ★ 代价照实记：<b>慢裁决会占住调用方的线程</b>（人迟迟不答 ⇒
   * 等到请求窗口结束才返回）。这是有意选择，不是缺陷。
   *
   * <p><b>返回值的两处读法</b>：{@link #flush} 关心"这一轮到底怎么了"（终态快照）；非 {@code PROPOSED} 的包原样返回（见下）。
   *
   * <p>非 {@code PROPOSED} 的包不投、也不发 {@code proposed} 事件：{@code propose} 在"到点时刻已过"时会直接返回 {@code
   * EXPIRED} 的包（RB1 的正常结果），给它发审批请求等于让提示面挂一条永远裁不动的项，给它发"包已提议"的事件则是 一句假话——它从来没有进入过决策面。
   */
  public Optional<DecisionPackage> onProposed(DecisionPackage proposed) {
    // 没有条目 AskKind 的直投口：级联拿不到"为什么问"⇒ 一律按 SENSITIVE 办（整包到人，fail-closed）
    return onProposed(proposed, null);
  }

  /**
   * 提议成功回调 + <b>逐级预裁定</b>（P2b，设计 §5ter 裁定 12 / §5quater）。
   *
   * <p><b>顺序就是裁定的顺序</b>：先跑级联（谁有能力、谁拍板、哪些到人）⇒ 发 {@code proposed}（轨迹随载荷出去）⇒
   * 分岔——<b>没人被上报</b>（全部被某一级预裁定批准）⇒ 本桥直接调一次 {@link DecisionPackages#decide} + 一次提交，<b>不投审批面</b>
   * （没有人要问）；<b>有人被上报</b> ⇒ 照既有形状投一条包级请求，人裁的只是那几条。
   *
   * <p>★ 两条路都<b>只调一次</b> {@code decide}：级联从不自己改状态机（§5quater ③ 的硬要求）。
   *
   * @param askKinds 条目 index ⇒ 工具自报的 {@link AskKind}（{@code null} = 没有料 ⇒ 全部按 SENSITIVE = 到人）
   */
  private Optional<DecisionPackage> onProposed(
      DecisionPackage proposed, Map<Integer, AskKind> askKinds) {
    Objects.requireNonNull(proposed, "proposed");
    if (proposed.state() != PackageState.PROPOSED) {
      LOG.warn(
          "包不在待裁决态，不投审批面、也不发 proposed 事件 packageId={} state={}（到点建出来的包就是这个态；零落盘）",
          proposed.id(),
          proposed.state());
      return Optional.of(proposed);
    }
    DecisionCascade.DecisionTrail trail = cascade.map(c -> c.run(proposed, askKinds)).orElse(null);
    Set<Integer> escalated = trail == null ? allIndexes(proposed) : trail.escalatedToHuman();
    emit(DecisionEventTypes.PROPOSED, payload(proposed, null), proposed.id());
    if (escalated.isEmpty()) {
      // 级联自己拍板：全部条目都被某一级预裁定批准 ⇒ 不投审批面（裁定 12：到顶无人上报 ⇒ 才到人；这里一个都没有）
      Decision decision =
          new Decision(
              trail.finalApproved(),
              trail.finalizedBy(),
              "级联预裁定整包批准（" + proposed.items().size() + " 条，无条目上报到人）");
      DecisionPackage decided;
      try {
        decided = packages.decide(proposed.id(), decision);
      } catch (DecisionPackages.RejectionException refusal) {
        // 到点/已被别的路径裁掉：具名拒 = 不回填、不落盘（与审批面那条路同一口径）
        LOG.warn(
            "级联裁决回填被拒（零落盘）：packageId={} code={} 理由={}",
            proposed.id(),
            refusal.code(),
            refusal.getMessage());
        return packages.find(proposed.id());
      }
      emitDecided(decided);
      LOG.info(
          "级联预裁定整包批准 packageId={} finalizedBy={} items={}（未投审批面）",
          proposed.id(),
          trail.finalizedBy(),
          proposed.items().size());
      commitAfterDecision(decided);
      return packages.find(proposed.id());
    }
    // ★ 竞态护栏（P2b）：人在决策面<b>抢先</b>答了（包已不是待裁决态）⇒ 不投审批面——
    //   否则会挂出一条永远不会被人消费的请求，而提出者要白等到窗口结束（结算映射那时还没登记）。
    if (packages
        .find(proposed.id())
        .map(current -> current.state() != PackageState.PROPOSED)
        .orElse(false)) {
      LOG.info("包在投递前已被裁决（决策面抢先）⇒ 不投审批面 packageId={}", proposed.id());
      return packages.find(proposed.id());
    }
    // ★ 设计 §5 裁定 4（用户 2026-10-16 原话「投递根据之前的裁定，很显然应当是同步的」）：这里<b>同步</b>投递并等裁决。
    //   ★ 代价照实记：慢裁决<b>会占住提出者的线程</b>（人迟迟不答 ⇒ 等到窗口结束才返回）。这是裁定 4 的有意选择，不是缺陷；
    //     额度上限仍是包的 deadline（裁定 10）；人在决策面答完会<b>结算</b>审批面那条请求（见 settleApprovalRequest）。
    deliver(proposed, escalated, askKinds);
    return packages.find(proposed.id());
  }

  /**
   * <b>回合收尾的汇总投递口</b>（P1b，设计 §5 裁定 1+2+4；P2b 起带逐级预裁定）：把本轮攒下的待裁决队列收成<b>一个包</b>、 跑一次级联、把剩下的投给人，返回该包
   * <b>最终</b>快照。
   *
   * <p><b>这一条方法的顺序就是裁定的顺序</b>：{@link PendingDecisionQueue#toProposeRequest()}（空队列 ⇒ 空值，<b>不建包</b>）⇒
   * {@link DecisionPackages#propose(ProposeRequest)}（<b>无校验器</b>重载：进包口径已是"工具符合条件就自动进包"，校验发生在
   * 命中那一刻的调用链上，见裁定 6 与那个重载的注释）⇒ {@link DecisionCascade#run}（P2b：按能力逐级预裁定）⇒ 到人/自裁两条路 ⇒ {@link
   * DecisionPackages#find}（终态快照：{@code EXPIRED} / {@code DECIDED} / {@code COMMITTED} / {@code
   * REJECTED} / {@code CONFLICTED} 都如实返回）。
   *
   * <p><b>级联的料来自队列本身</b>：{@link PendingDecisionQueue.PendingEntry#askKind()} 是"这条为什么问"的唯一出处
   * （包内条目装不下它——冻结形状），而它决定该条能否被预裁定（{@link AskKind#SENSITIVE} 永远到人）。
   *
   * <p><b>空队列 = 零副作用</b>（验收判据 ④）：不建包、不发 {@code decision.package.*} 事件、不投审批请求、不落任何记录——返回 {@code
   * Optional.empty()}。这条不是"优化"，是"没命中敏感调用的回合，事件库里一条决策包的痕迹都不该有"。
   *
   * <p><b>为什么返回值是"重新 find 一次"而不是 propose/onProposed 的返回值</b>：{@code propose} 给的是裁决<b>之前</b>的快照，
   * {@code onProposed} 内部还要经过投递、裁决、提交三段——只有 {@code find} 读得到提交之后那个真值（同 {@link #commitAfterDecision}
   * 里"回执要报实际生效的终态"那条注释）。取不到（理论上到不了：终局包不淘汰）⇒ 退回投递前的快照。
   *
   * <p><b>失败语义</b>（调用方 {@code AgentPipeline} 的收尾怎么处理它）：{@code PACKAGE_ALREADY_OPEN}
   * 这类状态机具名拒<b>原样上抛</b> （{@link DecisionPackages.RejectionException}）——那是"这一轮没投出去"的响亮形态，由管线的收尾记
   * ERROR 并保留本回合的既有出口 （不把正常回合变成崩溃）。投递过程内部的异常已由 {@link #deliver} 自己接住（包按 RB1 状态机走到点 {@code
   * EXPIRED}）。
   *
   * @param queue 本轮待裁决队列（非 null；由管线在回合开始时建、收尾时交到这里）
   * @return 该包的最终快照；空队列 ⇒ {@code Optional.empty()}（什么都没有发生）
   */
  public Optional<DecisionPackage> flush(PendingDecisionQueue queue) {
    Objects.requireNonNull(queue, "queue");
    List<PendingDecisionQueue.PendingEntry> entries = queue.snapshot();
    Map<Integer, AskKind> askKinds = new LinkedHashMap<>();
    for (PendingDecisionQueue.PendingEntry entry : entries) {
      askKinds.put(entry.packageItem().index(), entry.askKind());
    }
    return queue
        .toProposeRequest()
        .flatMap(
            request -> {
              DecisionPackage proposed = packages.propose(request);
              return onProposed(proposed, askKinds);
            });
  }

  /** 投递 + 等待 + 回填：跑在<b>调用方</b>的线程上并阻塞到裁决结束（裁定 4，见 {@link #onProposed} 与类注释的线程一节）。 */
  private void deliver(
      DecisionPackage proposed, Set<Integer> escalated, Map<Integer, AskKind> askKinds) {
    ApprovalRequest request = requestFor(proposed, escalated, askKinds);
    openRequests.put(proposed.id(), request.id());
    try {
      // 明细登记必须早于 coordinator.decide：上级判定闸就在那条调用的调用栈上（读不到明细 = 盲判）；
      // 只登记<b>上报的那些条目</b>（下级已经预裁定过的条目不该再让上级判一遍）
      judgeDetails.ifPresent(
          details -> details.register(request.id(), judgementItems(proposed, escalated)));
      LOG.info(
          "决策包已投审批面 requestId={} packageId={} caller={} proposerKey={} items={} 上报到人={} deadlineEpochMs={} kind={} 明细={}",
          request.id(),
          proposed.id(),
          proposed.caller(),
          proposed.proposerKey(),
          proposed.items().size(),
          escalated,
          request.deadlineEpochMs(),
          request.kind(),
          judgeDetails.isPresent() ? "已登记（上级可代批）" : "未装配（包只到人）");
      ApprovalDecision answer = coordinator.decide(request);
      String decider =
          registered(request) ? DecisionCascade.HUMAN_DECIDER : DecisionCascade.SUPERIOR_DECIDER;
      if (!answered(request, answer)) {
        // RB2c（设计 §5.12 裁定 B / §5.6）：没人答复 ⇒ 当场具名作废，不是"等包自己到点"——
        // 不作废，这个早已无人可批的包会白占「一实例一个未决包」的名额到包自己的 30min 上限
        expireUnanswered(proposed, request, answer);
        return;
      }
      backfill(proposed, answer, escalated, decider);
    } catch (RuntimeException failure) {
      // 投递线程不得静默死掉：包的状态由 RB1 自己兜底（到点 EXPIRED），但异常必须可见
      LOG.error("决策包审批投递异常（包仍按 RB1 状态机走，未被本桥改写） packageId={}", proposed.id(), failure);
    } finally {
      // ★ 明文（args）的生命周期到此为止：判定已读完，立刻释放（密钥纪律；释放幂等，跑在 finally 里）
      judgeDetails.ifPresent(details -> details.release(request.id()));
      // 提出者线程已经（或将）不再等这条请求：映射到此为止（决策面答复的结算以它为准，缺了就是空操作）
      openRequests.remove(proposed.id(), request.id());
    }
  }

  /**
   * 把包投成一条 {@link ApprovalRequest}：{@code tool}/{@code classKey}/{@code callerKey}/{@code
   * requesterId}/{@code kind} 全部取自宿主已知的包（模型给不出也改不了），{@code summary}/{@code digest} 不含明文。
   *
   * <p><b>{@code tool} 取"上报条目"里的第一条真工具名</b>（不是常量）：包可以装<b>任意</b>工具的调用（裁定 1：全部 {@code Ask}
   * 进包），写死一个"提议工具"的名字就是让审批面看到假工具名。P2b 起改取<b>上报给人的第一条</b>——人这次要裁的就是那几条， 首条的被批状态与被预裁定的条目无关。
   *
   * <p><b>首条一定存在</b>：{@code escalated} 非空是本方法的调用前提（一次都不上报的包根本不投递，见 {@link #onProposed}）；
   * 真出现"上报集合与包内条目不一致" ⇒ 这里当场抛，不编造一个工具名——投递线程上抛、包在包层到点 {@code EXPIRED}（零落盘），fail-closed 的一侧。
   *
   * <p><b>{@code kind} 由 {@link #requestKind} 定</b>（敏感区条目 ⇒ 只到人；全是档位提升 ⇒ 才可能交上级代批）。
   *
   * <p><b>{@code deadlineEpochMs} 直接用包自己的到点时刻</b>（设计 §5 裁定 10，用户 2026-10-16：「用包的，300s 有点太短了」）： 不再与
   * {@code coordinator.timeout()} 取较小者——裁决等待窗口 = 包的 {@code deadlineEpochMs}。人答在一个已经作废的包上只会得到 {@code
   * PACKAGE_EXPIRED} 的具名拒绝，所以包自己的 deadline 仍是硬上限。
   *
   * <p><b>{@code callerKey} 取包级唯一桶</b>（{@link #packageCallerKey}）：包级请求<b>不接受会话放行</b>（裁定 13
   * 的收窄，见那个方法）。
   *
   * <p>⚠️ <b>遗留口径（本单不改）</b>：{@link ApprovalCoordinator} 的 {@code timeout} 仍是<b>通道侧</b>的等待预算 （{@code
   * awaitFirst} 的总预算，缺省 300s）。若它比包 deadline 短，人还是会提前等到 {@code SOURCE_TIMEOUT}。P2b 把"人在决策面答完"
   * 那条路的等待收掉了（{@link #settleApprovalRequest}），但"没人答"那条路仍受窗口钳制。
   */
  private ApprovalRequest requestFor(
      DecisionPackage proposed, Set<Integer> escalated, Map<Integer, AskKind> askKinds) {
    long now = System.currentTimeMillis();
    long deadline = proposed.deadlineEpochMs();
    AskKind kind = requestKind(proposed, escalated, askKinds);
    PackageItem first = firstEscalated(proposed, escalated);
    return new ApprovalRequest(
        // 与 ToolCallAuthorizer 同形（ap- + UUID）：请求 id 不得含命令明文/密钥
        "ap-" + UUID.randomUUID(),
        // ★ 真工具名，不是常量：包可能装任意工具的调用（裁定 1），常量会撒谎（裁定 6 已把那个工具删了）。
        //   P2b：取<b>上报条目</b>里的第一条——人这次要裁的就是那几条
        first.toolName(),
        CLASS_KEY,
        summary(proposed, escalated),
        packageDigest(proposed),
        now,
        deadline,
        // ★ P2b：包级请求的 callerKey 是<b>它自己一个桶</b>（见 packageCallerKey 的注释）——同一个 (callerKey, classKey)
        //   的会话放行因此<b>绝不</b>跨包复用：一次包级裁决不可能变成"以后所有包都免问"
        packageCallerKey(proposed),
        proposed.proposerKey(),
        kind,
        "");
  }

  /**
   * 请求的 {@link AskKind}（谁有资格批这条包级请求）。
   *
   * <p><b>★ 敏感区永远到人（2026-09-13 老红线的保留）</b>：只要上报给人的条目里有<b>任何一条</b> 是 {@link
   * AskKind#SENSITIVE}，整条请求就取 {@code SENSITIVE}——否则上级判定闸（{@code SuperiorJudgeGate}）会一次把整包批掉， 那里面那条
   * SENSITIVE 条目就<b>绕过</b>了"只能到人"。P2a 之前"任一条 SENSITIVE ⇒ 整包到人"的<b>包级</b>读法已由裁定 12
   * 作废，但"这一条必须由人裁"这条没有作废（§5quater ② 末尾）。
   *
   * <p>上报的条目全都是 {@code MODE_LIMITED}（没有被强制到人的）⇒ 照既有分流：装配了上级判定明细缝 ⇒ {@code MODE_LIMITED}
   * （可交上级代批）；没装配 ⇒ {@code SENSITIVE}（看不见就不给批，只到人）。
   *
   * <p>没有料（{@code askKinds == null}）⇒ 按 SENSITIVE 办（fail-closed：不知道"为什么问"就不上代批台）。
   */
  private AskKind requestKind(
      DecisionPackage proposed, Set<Integer> escalated, Map<Integer, AskKind> askKinds) {
    // ★ 明细缝未装配 ⇒ 一律 SENSITIVE（**不是**只落 WARN 再交上级）：上级判定只会看到工具名就拍板，
    //   那是"看一半批全部"。本方法此前与自己的 javadoc 不符（javadoc 承诺退回、实现却返回 MODE_LIMITED），
    //   由 RT 的判据核查发现（报告 §5-1），此处按 javadoc 的承诺改正。
    if (judgeDetails.isEmpty()) {
      LOG.warn("本包只到人（AskKind=SENSITIVE）：上级判定明细缝未装配（看不见内容就不给批） packageId={}", proposed.id());
      return AskKind.SENSITIVE;
    }
    boolean anySensitive =
        askKinds == null
            || escalated.stream().anyMatch(index -> askKinds.get(index) != AskKind.MODE_LIMITED);
    if (anySensitive) {
      if (askKinds == null) {
        LOG.warn(
            "本包只到人（AskKind=SENSITIVE）：没有料（askKinds 为空）⇒ 不知道「为什么问」就不上代批台 packageId={}",
            proposed.id());
      } else {
        LOG.warn("本包只到人（AskKind=SENSITIVE）：上报条目里有敏感区条目 packageId={}", proposed.id());
      }
      return AskKind.SENSITIVE;
    }
    return AskKind.MODE_LIMITED;
  }

  /**
   * 包级请求的 {@code callerKey}：<b>一个包一个桶</b>（前缀 {@value #PACKAGE_CALLER_KEY_PREFIX} + 提出者 + 包 id）。
   *
   * <p><b>为什么不能用提出者的令牌桶</b>（P2b 的核心收窄之一）：会话放行的键是 {@code (callerKey, classKey)}，而包级 classKey 是 冻结字面量
   * {@value #CLASS_KEY}——若 callerKey 取令牌桶（主 Agent 是 {@code SYSTEM}），
   * 人对<b>某一个包</b>批的"本会话"就会把<b>以后每一个包</b>都放行（{@code AutoApproveGate} 直接命中）。那正是设计 §5ter 裁定 13
   * 要堵的口子：「不同范围的两次裁决不得互相放行」。
   *
   * <p>取包级唯一键之后，{@link ApprovalCoordinator} 的 {@code sessionGrantable} 缝（装配层注入的那条收窄判据）会把它判为
   * "不允许会话放行" ⇒ 人给的 {@code APPROVE_SESSION} <b>降级为一次</b>（{@code App} 在那里落一行 WARN，理由 = 包级不放行会话）。
   */
  public static String packageCallerKey(DecisionPackage pkg) {
    Objects.requireNonNull(pkg, "pkg");
    return PACKAGE_CALLER_KEY_PREFIX + pkg.proposerKey() + "#" + pkg.id();
  }

  /** 这个 {@code callerKey} 是不是包级请求的桶键（装配层的会话收窄判据用它认出来，见 {@link #packageCallerKey}）。 */
  public static boolean isPackageCallerKey(String callerKey) {
    return callerKey != null && callerKey.startsWith(PACKAGE_CALLER_KEY_PREFIX);
  }

  /** 上报条目里的第一条（人这次要裁的条目；包内条目至少一条，且 {@code escalated} 非空是调用前提）。 */
  private static PackageItem firstEscalated(DecisionPackage proposed, Set<Integer> escalated) {
    for (PackageItem item : proposed.items()) {
      if (escalated.contains(item.index())) {
        return item;
      }
    }
    throw new IllegalStateException(
        "上报集合与包内条目不一致（上报=" + escalated + " 包=" + proposed.id() + "）——不编造一个条目");
  }

  /**
   * "有人答了"的判别（合并进类注释的同一件事，这里是唯一实现点）。
   *
   * <p>查登记表失败按<b>没人答</b>处理：拿不准就不回填、不执行（fail-closed 朝向零落盘的那一侧）。
   */
  private boolean answered(ApprovalRequest request, ApprovalDecision answer) {
    if (answer == ApprovalDecision.APPROVE_ONCE || answer == ApprovalDecision.APPROVE_SESSION) {
      // 通道答的、或会话级放行闸（人先前批过的「本会话」）答的——都是答复
      return true;
    }
    return registered(request);
  }

  /**
   * 审批面上还留不留着这条请求的登记项——用来把"人经通道答的"与"闸链代批的"分开（两者在 {@link ApprovalDecision} 上长得一样：都是 {@code
   * APPROVE_ONCE}/{@code DENY}）。
   *
   * <p><b>为什么能分开</b>：闸链在登记<b>之前</b>跑（{@code ApprovalCoordinator#decide} 的顺序：快判链 ⇒ 无通道即拒 ⇒ {@code
   * pending.submit} ⇒ 通道），所以"闸链给了终局决定"那条路上登记项根本不存在；而通道的硬约束是"答复必须先落进登记表再返回"。 判定用途 = 轨迹里那一级的 id（人 /
   * 代批，见 {@code DecisionCascade} 的两个常量）。
   */
  private boolean registered(ApprovalRequest request) {
    try {
      return pending.get(request.id()).isPresent();
    } catch (RuntimeException failure) {
      LOG.warn("查审批登记项失败，按「无人答复」处理（fail-closed：不回填、不执行） requestId={}", request.id(), failure);
      return false;
    }
  }

  /**
   * 没人答复 ⇒ 具名作废（RB2c，设计 §5.12 裁定 B / §5.6）：{@code PROPOSED ⇒ EXPIRED}、零落盘，并落<b>一行 WARN</b>（包 id +
   * 原因）。
   *
   * <p><b>谁调它</b>：{@link #deliver} 里 {@link #answered} 判为"没人答"的那一处——它同时覆盖两条路：审批窗口到期而无人答复，
   * 与无可用通道（编排器在投递前就拒）。两条路的差别只在 WARN 的原因文字（见 {@link #noAnswerReason}），动作是同一个：作废。
   *
   * <p><b>为什么可以放心重复调</b>：{@link DecisionPackages#expireNow} 只动 {@code PROPOSED}，其余状态原样返回、不抛；
   * 本方法对返回状态再判一次，非 {@code EXPIRED} 就只落 WARN、不声称做过什么（超时路径与别的作废路径可能同时走到这里）。
   *
   * <p><b>绝不报成已落定</b>：作废 = 零落盘（{@code expireNow} 没有提交口参数，结构上就落不了盘）；本方法也<b>不</b>调 {@code
   * commit}——"没人批"不等于"被驳"，更不等于"落了什么"。
   */
  private void expireUnanswered(
      DecisionPackage proposed, ApprovalRequest request, ApprovalDecision answer) {
    long now = System.currentTimeMillis();
    String reason = noAnswerReason(request, now);
    DecisionPackage after;
    try {
      after = packages.expireNow(proposed.id(), now);
    } catch (DecisionPackages.RejectionException refusal) {
      // 本桥手里的包就是 propose 登记过的那个，且 v1 不淘汰终局包 ⇒ 这里理论上到不了；真到了就是"包没了"，
      // 同样不报成已落定（包状态一个字也没被本桥改写）
      LOG.warn(
          "决策包没人答复 ⇒ 具名作废被拒（包状态未被本桥改写，也未被报成已落定） packageId={} reason={} code={} 理由={}",
          proposed.id(),
          reason,
          refusal.code(),
          refusal.getMessage());
      return;
    }
    if (after.state() == PackageState.EXPIRED) {
      // ★ P2b（裁定 8）：作废也是结果、也必须可回读 ⇒ 落一条 decision.package.expired（原因随载荷出去）
      emitExpired(after, reason);
      LOG.warn(
          "决策包没人答复 ⇒ 当场具名作废（EXPIRED、零落盘、不静默执行；该 proposerKey 的未决名额已释放）"
              + " packageId={} reason={} answer={}",
          proposed.id(),
          reason,
          answer);
      return;
    }
    // 竞态：这个包已经被别的路径裁决/终局（expireNow 只动 PROPOSED）⇒ 原样保留，绝不覆盖既有的裁决或回执
    LOG.warn(
        "决策包没人答复，但包已不是 PROPOSED ⇒ 不作废、原样保留（不覆盖既有裁决/回执，也不报成已落定）"
            + " packageId={} reason={} state={} answer={}",
        proposed.id(),
        reason,
        after.state(),
        answer);
  }

  /**
   * 没人答复的原因文字（只进 WARN，不参与任何判定）。
   *
   * <p><b>为什么不直接问编排器"是谁拒的"</b>：{@code ApprovalCoordinator#decide} 只返回决定本身，{@code SOURCE_TIMEOUT} /
   * {@code SOURCE_NO_CHANNEL} / {@code SOURCE_ERROR} 它们只进 {@code approval.decided} 事件的 {@code by}
   * 字段——没有可编程出口（要它就得改审批链的公开面，RB2b 已把这条记成边界，本单不改）。本层可观测的只有两件事：编排器判定的<b>先后顺序</b> （无任何可用通道 ⇒ <b>立即</b>
   * DENY；{@code await} 到点没人答 ⇒ DENY），以及这条请求自己的 {@code deadlineEpochMs}。
   *
   * <p><b>判据</b>：编排器在请求的窗口<b>结束前</b>就返回"没人答复" ⇒ 它没等到窗口 ⇒ {@link #REASON_NO_CHANNEL} （无可用通道 /
   * 编排兜底异常）；到/过窗口才返回 ⇒ {@link #REASON_WINDOW_EXPIRED}。窗口 = {@code min(包的 deadline, 投递时刻 +
   * coordinator.timeout())}（见 {@link #requestFor}），而超时路径必然等满 {@code coordinator.timeout()} 才返回 ⇒
   * 超时绝不会落进"无可用通道"那一档（反过来说：窗口结束前返回的不可能是超时）。
   *
   * <p><b>已知残差（如实记）</b>：① 将来若装上一条会终局 {@code DENY} 的快判闸（今天的链上没有，见类注释"已知边界"）， 它的即时 DENY
   * 也会落进"无可用通道"那一档——<b>动作不变</b>（一样当场作废），只是 WARN 的原因措辞不够精确。
   *
   * <p>★★ <b>P1b 实测新增的偏报（如实记，本单不改）</b>：请求上的 deadline 是<b>包的</b> deadline（30min，裁定 10）， 而 {@code
   * ApprovalCoordinator} 的实际等待上限仍是它自己的 {@code timeout}（缺省 300s，见 {@code requestFor} 的遗留口径）—— 于是"等满
   * 300s 仍无人答"这条路上，本方法看到的是 {@code nowEpochMs < request.deadlineEpochMs()} ⇒ 判成 {@link
   * #REASON_NO_CHANNEL}（"无可用通道"），而事实是<b>窗口到期而无人答复</b>。这个偏报在 RB2c 的异步投递下已经存在 （当时人只从日志里看到一行 WARN），P1b
   * 改成同步之后它更显眼（提出者线程真的等了 5 分钟，然后日志说"没通道"）。 修它要动 {@code ApprovalCoordinator}
   * 的公开面（拿到"是超时还是没通道"的可编程出口）——那是 P2 的第 ⑤ 项，本单不碰。
   */
  private static String noAnswerReason(ApprovalRequest request, long nowEpochMs) {
    return nowEpochMs >= request.deadlineEpochMs() ? REASON_WINDOW_EXPIRED : REASON_NO_CHANNEL;
  }

  /**
   * 答复 → RB1 统一裁决 → （可选）一次提交（审批链这条路的入口；HTTP 面走 {@link #submitDecision}）。
   *
   * <p><b>放行方向写死</b>：只有显式的 {@code APPROVE_ONCE}/{@code APPROVE_SESSION} 才批（缺省/意外值 一律按驳处理）—— 与
   * {@code ToolCallAuthorizer} 对编排器违约（返回 null）的口径一致：没拿到显式批准就是拒，绝不把"没看懂"变成"执行"。
   *
   * <p><b>P2b：人的答复<b>只作用于上报给他的那些条目</b></b>（§5quater ②「人面对的是剩下的这些条目，不是整包」）：
   * 下级已经预裁定批准的条目照旧被批（人<b>不</b>替它们改口），人批的只是上报集合里那几条。终局裁决 = 预批准 ∪ 人批准的上报条目， <b>一次</b> {@code decide}
   * 交给状态机。
   *
   * <p>{@code decided} 事件在 {@code packages.decide(...)} 成功返回之后发（全驳也发：{@code approvedCount=0}）。
   */
  private void backfill(
      DecisionPackage proposed, ApprovalDecision answer, Set<Integer> escalated, String deciderId) {
    boolean approved =
        answer == ApprovalDecision.APPROVE_ONCE || answer == ApprovalDecision.APPROVE_SESSION;
    Set<Integer> humanApproved = approved ? escalated : Set.of();
    Set<Integer> finalApproved = completeTrail(proposed, humanApproved, deciderId);
    int itemCount = proposed.items().size();
    Decision decision =
        approved
            ? new Decision(
                finalApproved,
                BY_APPROVAL,
                "审批链批准上报的 "
                    + escalated.size()
                    + " 条（另有 "
                    + (finalApproved.size() - escalated.size())
                    + " 条由下级预裁定批准）")
            : new Decision(
                finalApproved,
                BY_APPROVAL,
                "审批链驳回上报的 " + escalated.size() + " 条（下级预裁定批准的 " + finalApproved.size() + " 条不受影响）");
    if (!preCheckDecidable(proposed, deciderId)) {
      return;
    }
    DecisionPackage decided;
    try {
      decided = packages.decide(proposed.id(), decision);
    } catch (DecisionPackages.RejectionException refusal) {
      // 答得太晚（包已到点 ⇒ PACKAGE_EXPIRED）或包已被别的路径裁掉：具名拒 = 不回填、不落盘
      LOG.warn(
          "裁决回填被拒（零落盘）：packageId={} code={} 理由={}",
          proposed.id(),
          refusal.code(),
          refusal.getMessage());
      return;
    }
    emitDecided(decided);
    LOG.info(
        "决策包裁决已回填 packageId={} approved={} rejected={} by={} 上报={}",
        decided.id(),
        decision.approvedIndexes().size(),
        itemCount - decision.approvedIndexes().size(),
        decision.by(),
        escalated);
    commitAfterDecision(decided);
  }

  /**
   * 回填之前的<b>状态预检</b>：包已经不是待裁决态 ⇒ 不回填（{@code false}）。
   *
   * <p><b>为什么要有它</b>（P2b 新增的语义）：人在<b>决策面</b>答完会结算审批面上那条请求（{@link #settleApprovalRequest}），
   * 于是阻塞在编排器里的提出者线程会立刻醒来、走到这里——而那个包<b>已经被决策面裁掉了</b>。这不是异常，是设计： 这里记一行 INFO
   * 就返回，绝不用一次迟到的答复去覆盖首次裁决（状态机本来也会拒，但"预期之内的正常路径"不该长成 WARN 的样子）。
   */
  private boolean preCheckDecidable(DecisionPackage proposed, String deciderId) {
    Optional<DecisionPackage> current = packages.find(proposed.id());
    if (current.isEmpty() || current.get().state() == PackageState.PROPOSED) {
      return true;
    }
    PackageState state = current.get().state();
    if (state == PackageState.EXPIRED) {
      LOG.warn("审批面答复到得太晚（包已到点作废，零落盘，不回填） packageId={} decider={}", proposed.id(), deciderId);
    } else {
      LOG.info(
          "包已不是待裁决态（多半已由决策面裁决）：审批面这条答复不回填、不落盘 packageId={} state={} decider={}",
          proposed.id(),
          state,
          deciderId);
    }
    return false;
  }

  /**
   * 把人/代批的这一级补齐进轨迹，返回<b>终局被批集合</b>（= 下级预批准 ∪ 上报集合内被批准的那些）。
   *
   * <p>没装配级联 ⇒ 没有轨迹，此时"人的答复 = 整包"（本单之前的行为，逐字不变）。
   */
  private Set<Integer> completeTrail(
      DecisionPackage proposed, Set<Integer> humanApproved, String deciderId) {
    if (cascade.isEmpty()) {
      return humanApproved;
    }
    return cascade
        .get()
        .complete(proposed.id(), humanApproved, null, deciderId)
        .map(DecisionCascade.DecisionTrail::finalApproved)
        .orElse(humanApproved);
  }

  /**
   * 决策面 HTTP 的裁决回填口（设计 §5.13(e)；{@link DecisionSubmissionPort} 的实现）。
   *
   * <p><b>为什么必须是这一条路</b>：HTTP 处理器直调 {@link DecisionPackages#decide(String, Decision)} 会漏掉两件事——
   * {@code decision.package.decided} 事件不落、提交口不被调（{@code COMMITTED}/{@code CONFLICTED} 永远不产生）， 而
   * HTTP 面还会回一个 200。本方法把"回填 → 发事件 → 提交 → 结算审批面请求 → 回执"串成一条，与审批链那条路共用 {@link
   * #commitAfterDecision}（同一个提交口径，不写第二套）。
   *
   * <p><b>★ P2b：裁决面与级联合并</b>——人从决策面批的仍然只是<b>上报给他的那些条目</b>：{@code preApproved ∪ (人的批准 ∩ 上报集合)}
   * 才是终局。包外 index（body 里写了个不存在的条目）原样带进裁决，交给状态机具名拒（{@code INVALID_DECISION}）——本层不另造第二处校验。
   *
   * <p><b>★ P2b：答复成功回填后结算审批面上那条包级请求</b>（{@link #settleApprovalRequest}）：不作废也不覆盖裁决，
   * 只是把"人在决策面已经答过了"这件事告诉正阻塞在窗口里的提出者线程，让它<b>立即</b>继续（P2a 发现 B：此前管线要等满 {@code
   * approval.timeoutSeconds}）。
   *
   * <p>状态机的具名拒<b>原样上抛</b>（HTTP 面按 {@code code()} 映射 404/409/400）；提交侧的失败不是异常， 而是 {@link
   * DecisionSubmission#commitNote()}（裁决已经生效，包只是没落定——两件事不许混成一句"失败"）。
   */
  @Override
  public DecisionSubmission submitDecision(String packageId, Decision decision) {
    Objects.requireNonNull(packageId, "packageId");
    Objects.requireNonNull(decision, "decision");
    DecisionPackage proposed =
        packages.find(packageId).orElseThrow(() -> unknownPackage(packageId));
    Set<Integer> escalated = escalatedOf(proposed);
    Set<Integer> merged = mergeWithEscalation(proposed, decision.approvedIndexes(), escalated);
    completeTrail(
        proposed, intersect(decision.approvedIndexes(), escalated), DecisionCascade.HUMAN_DECIDER);
    Decision effective = new Decision(merged, decision.by(), decision.note());
    DecisionPackage decided = packages.decide(packageId, effective);
    emitDecided(decided);
    LOG.info(
        "决策包裁决已回填（决策面 HTTP） packageId={} approved={} rejected={} by={} 上报={}",
        decided.id(),
        decided.decision().approvedIndexes().size(),
        decided.items().size() - decided.decision().approvedIndexes().size(),
        decided.decision().by(),
        escalated);
    DecisionSubmission submission = commitAfterDecision(decided);
    // ★ 结算审批面上那条包级请求（P2a 发现 B）：必须在 decide 成功之后——先结算会让提出者线程抢在决策面前面回填，
    //   那样决策面的 POST 反而会拿到 409（人明明答对了）。放在提交之后：提出者醒来时读到的是已落定的终态。
    settleApprovalRequest(packageId, !merged.isEmpty());
    return submission;
  }

  /** 未知包 id 的具名拒（与状态机同码：HTTP 面据此回 404）。 */
  private static DecisionPackages.RejectionException unknownPackage(String packageId) {
    return new DecisionPackages.RejectionException(
        DecisionPackages.PACKAGE_NOT_FOUND, "未知的决策包 id=" + packageId);
  }

  /**
   * 把"人的裁决"与级联的预批准合并成终局被批集合（没跑过级联 ⇒ 人的裁决就是全部）。
   *
   * <p>两条细节都是刻意的：① 只有<b>上报集合内</b>的 index 才能被人加进来（下级已批的条目不许被人改口）； ② <b>包外 index
   * 原样保留</b>并带进裁决——让状态机照既有口径抛 {@code INVALID_DECISION}，不在这一层另写一份校验。
   */
  private Set<Integer> mergeWithEscalation(
      DecisionPackage proposed, Set<Integer> humanApproved, Set<Integer> escalated) {
    Set<Integer> merged = new LinkedHashSet<>();
    preApprovedOf(proposed).ifPresent(merged::addAll);
    for (Integer index : humanApproved) {
      if (index == null) {
        continue;
      }
      if (escalated.contains(index) || !allIndexes(proposed).contains(index)) {
        merged.add(index);
      }
    }
    return Collections.unmodifiableSet(merged);
  }

  /** 该包上报给人的条目（没装配级联 / 没跑过 ⇒ 整包——那是"整包到人"的老形状）。 */
  private Set<Integer> escalatedOf(DecisionPackage pkg) {
    return cascade.flatMap(c -> c.escalatedOf(pkg.id())).orElseGet(() -> allIndexes(pkg));
  }

  /** 该包被下级预裁定批准的条目（没跑过级联 ⇒ 空集）。 */
  private Optional<Set<Integer>> preApprovedOf(DecisionPackage pkg) {
    return cascade.flatMap(c -> c.preApprovedOf(pkg.id()));
  }

  /**
   * 该包<b>上报给人</b>的条目（决策面 HTTP 的读口：列表只列这些 + 逐条打 {@code escalated} 标记）。
   *
   * <p>没跑过级联 ⇒ 空 {@code Optional}（调用方按"整包到人"渲染，与本单之前逐字一致）。
   */
  public Optional<Set<Integer>> escalatedItems(String packageId) {
    return cascade.flatMap(c -> c.escalatedOf(packageId));
  }

  /** 集合交集（顺序 = 左集合的迭代顺序；{@code null} 视作空集）。 */
  private static Set<Integer> intersect(Set<Integer> left, Set<Integer> right) {
    Set<Integer> result = new LinkedHashSet<>();
    if (left != null && right != null) {
      for (Integer index : left) {
        if (right.contains(index)) {
          result.add(index);
        }
      }
    }
    return Collections.unmodifiableSet(result);
  }

  /**
   * 结算审批面上那条包级请求（P2b；P2a 发现 B 的落点）。
   *
   * <p><b>为什么要结算</b>：人在<b>决策面</b>答完之后，审批面那条请求还挂在登记表上等人，提出者线程阻塞在 {@code ApprovalCoordinator#decide}
   * 里要等满整条窗口才返回（P2a 实测：POST 已经 200、包已经 COMMITTED，管线的回合还在等）。
   *
   * <p><b>为什么用 {@code pending.decide} 而不是 {@code pending.drop}</b>（两个都是既有口）：{@code drop} 只把登记项摘掉，
   * 通道的 {@code await} 会按"没这条了"返回<b>空</b>，而编排器的 {@code awaitFirst} 把空读作"该通道到点没答"——它<b>继续等别的通道</b>，
   * 总预算不变，提出者线程照样要等满窗口。改用"以同一结论结算这条请求"，通道拿到的是一个<b>决定</b>，编排器立刻返回； 提出者随后走 {@link #backfill}，而 {@link
   * #preCheckDecidable} 看出包已不是待裁决态 ⇒ 不回填（首次裁决永不被覆盖）。
   *
   * <p><b>不改 {@code ApprovalCoordinator}</b>：这里用的是它既有的登记表口，会话闸与闸链一个字都没动。
   *
   * @param approved 人在决策面到底批没批到东西（决定结算成 {@code APPROVE_ONCE} 还是 {@code DENY}）
   */
  private void settleApprovalRequest(String packageId, boolean approved) {
    String requestId = openRequests.get(packageId);
    if (requestId == null) {
      // 没人还在等这条包级请求（投递已经走完它的窗口，或这一包根本没投过审批面）——空操作是正确结果
      return;
    }
    boolean settled;
    try {
      settled =
          pending.decide(
              requestId,
              approved ? ApprovalDecision.APPROVE_ONCE : ApprovalDecision.DENY,
              DECISION_FACE_SOURCE);
    } catch (RuntimeException failure) {
      LOG.warn(
          "结算审批面包级请求失败（提出者侧将等它自己的窗口结束；不影响已生效的裁决） packageId={} requestId={}",
          packageId,
          requestId,
          failure);
      return;
    }
    if (settled) {
      LOG.info(
          "决策面答复已结算审批面上那条包级请求（提出者线程立即放行，不再等窗口） packageId={} requestId={} decision={}",
          packageId,
          requestId,
          approved ? "APPROVE_ONCE" : "DENY");
    } else {
      LOG.warn(
          "结算审批面包级请求落空（登记项已过期/已被摘除）：提出者侧等它自己的窗口 packageId={} requestId={}", packageId, requestId);
    }
  }

  /**
   * 裁决之后的提交段（审批链与 HTTP 面<b>共用</b>）：没装配提交口 ⇒ 只落 WARN、包保持 {@code DECIDED}； 装了 ⇒ 恰好一次 {@link
   * DecisionPackages#commit}，拿到回执之后才发 {@code committed}/{@code conflicted}。
   *
   * <p>★ <b>port 抛异常 ⇒ 一条 {@code committed} 都不发</b>（设计 §5.10①）：{@code packages.commit} 把 port 的异常包成
   * {@code COMMIT_FAILED}，这里只记 ERROR 并把"没落定"如实写进 {@code commitNote}——绝不补发一条"已提交"的事件来配平叙事。 回执是
   * {@code REJECTED}（被批子集为空 ⇒ 零落盘）时也不发：表里没有"被驳的提交"这一类事件。
   *
   * <p><b>返回值的两处读者</b>：HTTP 面拿它拼回执（{@link DecisionSubmission}）；审批链那条路只要副作用（事件与提交），
   * 所以忽略返回值——两条路的状态机后果完全一样。
   */
  private DecisionSubmission commitAfterDecision(DecisionPackage decided) {
    int itemCount = decided.items().size();
    if (commitPort.isEmpty()) {
      String note = "提交口未装配（DecisionCommitPort 未注入）：包保持 DECIDED、不调用 commit、不落盘——这不是「已落定」";
      LOG.warn(note + " packageId={} items={}", decided.id(), itemCount);
      return new DecisionSubmission(decided, null, note);
    }
    CommitReceipt receipt;
    try {
      receipt = packages.commit(decided.id(), commitPort.orElseThrow());
    } catch (DecisionPackages.RejectionException refusal) {
      // COMMIT_FAILED（提交口抛异常/回执违约）⇒ 包保持 DECIDED、不记回执：绝不把"没落定"说成落定；
      // ★ 也绝不发 committed/conflicted（这里到不了 emitReceipt，结构上就发不出去）
      String note = "提交未落定（包保持 DECIDED）：code=" + refusal.code() + " 理由=" + refusal.getMessage();
      LOG.error(
          "决策包提交未落定（包保持 DECIDED；一条 committed/conflicted 都不发）：packageId={} code={} 理由={}",
          decided.id(),
          refusal.code(),
          refusal.getMessage());
      return new DecisionSubmission(decided, null, note);
    }
    LOG.info(
        "决策包提交完成 packageId={} status={} outcomes={} revisionRef={}",
        decided.id(),
        receipt.status(),
        receipt.outcomes().size(),
        receipt.revisionRef());
    emitReceipt(decided, receipt);
    // ★ 回执要报"实际生效"的终态：decided 是提交<b>之前</b>的快照（DECIDED），提交之后包已变成
    //   COMMITTED/REJECTED/CONFLICTED。拿旧快照回执，HTTP 面就会在"已经落定"的包上写着 state=DECIDED
    //   （临时探针当场抓到过这一条）。刷新取一次真值；取不到（理论上到不了）就退回旧快照，不编状态。
    DecisionPackage settled = packages.find(decided.id()).orElse(decided);
    return new DecisionSubmission(settled, receipt, "");
  }

  /** {@code decided} 事件（裁决回填之后，含全驳）。 */
  private void emitDecided(DecisionPackage decided) {
    emit(DecisionEventTypes.DECIDED, payload(decided, null), decided.id());
  }

  /** 提交回执 → {@code committed}/{@code conflicted}（{@code REJECTED} = 零落盘，没有这一类事件）。 */
  private void emitReceipt(DecisionPackage decided, CommitReceipt receipt) {
    switch (receipt.status()) {
      case COMMITTED ->
          emit(
              DecisionEventTypes.COMMITTED,
              payloadWithOutcomes(decided, receipt.revisionRef(), receipt),
              decided.id());
      case CONFLICTED ->
          // 冲突：被批子集整批退回（零落盘）。回执在这一档没有可对账的引用（COMMITTED 才强制非空），
          // 所以 revisionRef 记空串——键在（形状稳定），值如实为空（不编一个引用）
          emit(
              DecisionEventTypes.CONFLICTED,
              payloadWithOutcomes(decided, "", receipt),
              decided.id());
      case REJECTED ->
          LOG.info("决策包提交结局=REJECTED（零落盘）：不发 committed/conflicted packageId={}", decided.id());
    }
  }

  /** 载荷 + 逐条去向（P2b）：只在拿到回执之后才有逐条结局可说。 */
  private Map<String, Object> payloadWithOutcomes(
      DecisionPackage decided, String revisionRef, CommitReceipt receipt) {
    Map<String, Object> payload = payload(decided, revisionRef);
    payload.put("outcomes", outcomeRows(decided, receipt));
    return payload;
  }

  /**
   * 作废事件（P2b，设计 §5 裁定 8）：{@code decision.package.expired} + 一行原因。
   *
   * <p>作废也是结果、也必须可回读：不给这条事件，事件库里的包会永远停在"已提议"（下一轮查历史会把它当成还在等人）。 载荷沿用 {@link #payload} 的形状（不含明文），另加
   * {@code reason}（人读一行：窗口到点 / 无可用通道）。
   */
  private void emitExpired(DecisionPackage expired, String reason) {
    Map<String, Object> payload = payload(expired, null);
    payload.put("reason", reason);
    emit(DecisionEventTypes.EXPIRED, payload, expired.id());
  }

  /**
   * 四类事件载荷（设计 §5.10①）：严格只此六项，顺序即组装顺序（Jackson 保 {@link LinkedHashMap} 的迭代序）， {@code
   * committed}/{@code conflicted} 另加 {@code revisionRef}。
   *
   * <p><b>还没裁决的包（{@code proposed} 事件）两个计数都是 0</b>：{@code decision == null} ⇒ 一条都还没被批、也一条都还没被驳。 用
   * {@code itemCount - approvedCount} 去凑 rejectedCount 会把 proposed 事件写成 {@code
   * rejectedCount=itemCount}——读起来 就是"全被驳了"，与事实相反（临时探针当场抓到过这一条）。只有裁决存在之后，才谈得上"被驳 = 条目数 − 被批数"。
   *
   * <p><b>绝不写明文参数</b>：{@code digests} 是逐条的脱敏摘要（{@link PackageItem#argsDigest()}），{@code toolNames}
   * 是 工具名（本来就公开）。包 id 与工具名都不是密钥；事件载荷这一路<b>没有任何一处</b>读 {@link PackageItem#args()}——明文只走判定明细缝 （{@link
   * #judgementItems}，内存态、判完即释放）与决策面 HTTP 的详情响应。
   */
  private Map<String, Object> payload(DecisionPackage pkg, String revisionRef) {
    int itemCount = pkg.items().size();
    Decision decision = pkg.decision();
    int approvedCount = decision == null ? 0 : decision.approvedIndexes().size();
    int rejectedCount = decision == null ? 0 : itemCount - approvedCount;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("packageId", pkg.id());
    // ★ P2b：归属键（裁定 3）。历史读口按它过滤"只能查自己的"——没有它，读侧只能猜归属
    payload.put("proposerKey", pkg.proposerKey());
    payload.put("itemCount", itemCount);
    payload.put("approvedCount", approvedCount);
    payload.put("rejectedCount", rejectedCount);
    payload.put("toolNames", toolNames(pkg));
    payload.put("digests", digests(pkg));
    // ★ P2b：逐级预裁定的轨迹（§5quater ③ 的落点之一；不含明文）
    trailRow(pkg.id()).ifPresent(trail -> payload.put("trail", trail));
    if (revisionRef != null) {
      payload.put("revisionRef", revisionRef);
    }
    return payload;
  }

  /**
   * 轨迹行（§5quater ③）：逐级 "谁、什么档位、批/驳了哪些条目、可空理由" + 上报集合 + 终局。
   *
   * <p>没装配级联 / 没跑过 ⇒ 空（载荷里就没有这个键——比写一个空对象诚实：它回答不了"谁裁的"）。
   */
  private Optional<Map<String, Object>> trailRow(String packageId) {
    return cascade
        .flatMap(c -> c.trailOf(packageId))
        .map(
            trail -> {
              List<Map<String, Object>> levels = new ArrayList<>(trail.levels().size());
              for (DecisionCascade.PreDecision level : trail.levels()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("decider", level.deciderId());
                row.put("mode", level.deciderMode().name());
                row.put("approved", List.copyOf(level.approvedIndexes()));
                row.put("rejected", List.copyOf(level.rejectedIndexes()));
                row.put("reason", level.reason());
                levels.add(row);
              }
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("levels", levels);
              row.put("escalatedToHuman", List.copyOf(trail.escalatedToHuman()));
              row.put("finalApproved", List.copyOf(trail.finalApproved()));
              row.put("finalizedBy", trail.finalizedBy());
              return row;
            });
  }

  /**
   * 逐条去向行（P2b，设计 P2 ③ / 裁定 8）：{@code index} / {@code toolName} / {@code outcome} / {@code
   * reason}（可空）。
   *
   * <p><b>为什么进事件载荷</b>：裁定 5 的底线是"下一轮查得到逐条去向"，而下一轮能读的是<b>提出者自己的事件库</b>；不给逐条结果，
   * 历史读口就只能报计数。"不含明文"这条不变：{@code reason} 是拒码/异常类名/包层理由，<b>不</b>是参数。
   */
  private static List<Map<String, Object>> outcomeRows(DecisionPackage pkg, CommitReceipt receipt) {
    List<CommitReceipt.ItemResult> results = receipt.itemResults();
    List<Map<String, Object>> rows = new ArrayList<>(pkg.items().size());
    for (int i = 0; i < pkg.items().size(); i++) {
      PackageItem item = pkg.items().get(i);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("index", item.index());
      row.put("toolName", item.toolName());
      if (i < results.size()) {
        row.put("outcome", results.get(i).outcome().name());
        row.put("reason", results.get(i).reason());
      } else {
        row.put("outcome", CommitReceipt.ItemOutcome.NOT_COMMITTED.name());
        row.put("reason", "回执未覆盖该条（宿主少报）");
      }
      rows.add(row);
    }
    return rows;
  }

  /** 包内工具名（顺序 = 条目顺序；重复不折叠——"3 条里两条是 bash"是审计要看的信息）。 */
  private static List<String> toolNames(DecisionPackage pkg) {
    List<String> names = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      names.add(item.toolName());
    }
    return List.copyOf(names);
  }

  /** 包内逐条脱敏摘要（顺序 = 条目顺序）。 */
  private static List<String> digests(DecisionPackage pkg) {
    List<String> digests = new ArrayList<>(pkg.items().size());
    for (PackageItem item : pkg.items()) {
      digests.add(item.argsDigest());
    }
    return List.copyOf(digests);
  }

  /**
   * 事件投递（与 {@code ApprovalCoordinator#emit} 同口径）：<b>失败只 WARN</b>——审计面故障不得改变状态机与裁决。
   *
   * <p>{@code bus == null} = 没接总线（离线夹具/未装配），直接不发；{@code agent} 记空串（同审批事件的口径：本层不知道 agent
   * 身份，归属由装配面在落库时决定）；{@code correlationId} 用包 id——它是整条链的键。
   */
  private void emit(String type, Map<String, Object> payload, String packageId) {
    if (bus == null) {
      return;
    }
    try {
      bus.publish(
          new Event(0L, Instant.now(), type, "", JSON.writeValueAsString(payload), packageId));
    } catch (Throwable failure) {
      // 审计面故障不得改变状态机与裁决（更不能把一次拒变成异常穿透）
      LOG.warn("决策包事件投递失败（不影响状态机与裁决） type={} packageId={}", type, packageId, failure);
    }
  }

  /** 包内条目 → 判定明细（工具名 + 人读摘要 + 参数明文）：只进内存态登记表，判定读完即释放。 */
  private static List<JudgementItem> judgementItems(
      DecisionPackage proposed, Set<Integer> escalated) {
    List<JudgementItem> items = new ArrayList<>(proposed.items().size());
    for (PackageItem item : proposed.items()) {
      if (escalated.contains(item.index())) {
        items.add(new JudgementItem(item.toolName(), item.summary(), item.args()));
      }
    }
    return List.copyOf(items);
  }

  /** 整包批的全集（顺序 = 包内条目顺序；{@link Decision} 自己会做不可变拷贝）。 */
  private static Set<Integer> allIndexes(DecisionPackage proposed) {
    Set<Integer> indexes = new LinkedHashSet<>();
    for (PackageItem item : proposed.items()) {
      indexes.add(item.index());
    }
    return indexes;
  }

  /**
   * 人读一行（提示面专用）：包 id + 条目数 + <b>上报给人的条数</b> + 上报条目的工具名列表——<b>只含公开面信息</b>（工具名是公开的， 设计 §5.10 的 {@code
   * toolNames} 同档），不含参数明文，也不含参数摘要以外的任何东西。
   *
   * <p><b>P2b：只列上报的那些条目</b>（§5quater ②「人面对的是剩下的这些条目，不是整包」）：下级已经预裁定批准过的条目不在这一行里， 人不会以为自己要替它们负责。
   *
   * <p>包内重复的工具名不折叠：一条条目就是一次待决调用，"3 条里两条是 bash"是确认者要看见的信息。
   */
  static String summary(DecisionPackage proposed, Set<Integer> escalated) {
    List<String> tools = new ArrayList<>();
    int total = proposed.items().size();
    for (PackageItem item : proposed.items()) {
      if (escalated.contains(item.index())) {
        tools.add(item.toolName());
      }
    }
    return "决策包 "
        + proposed.id()
        + "：共 "
        + total
        + " 条提议，其中 "
        + tools.size()
        + " 条上报到人（"
        + String.join("、", tools)
        + "）待裁决";
  }

  /**
   * 包级摘要：对"条目顺序 + 工具名 + 该条已脱敏的 {@code argsDigest}"的规范化串取 SHA-256，再取前 {@link
   * DecisionItemDigest#DIGEST_HEX_CHARS} 个十六进制字符（小写）。
   *
   * <p><b>为什么复用条目已有的 {@code argsDigest} 而不是重算参数</b>：① 参数明文不进本层（密钥纪律的落点越少越好）； ②
   * 摘要的输入面必须与"包内到底有哪几条"一一对应，而条目摘要正是宿主算好的那份（统一实现点，不在这里造第二处）。
   *
   * <p>{@code index} 与 {@code toolName} 也进输入面：只有参数摘要的话，"同一个命令、换个工具"会得到同一个包摘要，对账时说不清区别。
   *
   * <p><b>截断与哈希都走 {@link DecisionItemDigest#hexDigest}</b>：与条目摘要同源（同一个 SHA-256 + 同一种小写十六进制
   * 截断），本类不再自己写"取前 N 个 hex"的分块循环（那是两处真相，且曾与条目摘要的实现漂移）。
   */
  static String packageDigest(DecisionPackage proposed) {
    StringBuilder material = new StringBuilder();
    for (PackageItem item : proposed.items()) {
      material
          .append(item.index())
          .append('|')
          .append(item.toolName())
          .append('|')
          .append(item.argsDigest())
          .append('\n');
    }
    return DecisionItemDigest.hexDigest(material.toString());
  }
}
