package io.mosire.brain.decision;

import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.DecisionCommitPort;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PackageState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 决策包<b>审批桥</b>（设计 §5.9 ③，RB2b）：把"包已登记"变成审批面上<b>一条</b> {@link ApprovalRequest}， 等答复回来后把答复<b>回填</b>成
 * RB1 的统一裁决，再按装配决定是否走一次提交。
 *
 * <p><b>为什么是回调而不是轮询</b>：{@link DecisionProposeTool} 用构造器注入的 {@code onProposed} 把"我刚登记了一个包" 主动交给本桥
 * （见 {@code App} 的装配）。轮询 {@code DecisionPackages.pending()} 会发现"新包"，但轮询有固有延迟、会在无包时白烧 CPU，
 * 而且它答不出"这个包是不是已经投过了"——本桥按回调<b>一包一投</b>，投递动作有唯一的触发点。
 *
 * <h2>一个包 = 一条请求（§5.9 ③ 取代 §5.4 那句话）</h2>
 *
 * <p>决策单位是<b>包</b>：一次裁决、逐条例外、被批子集一次提交。若把一个包拆成 N 条请求，"统一裁决" 就要在 N 条上做二次归并， 等于把决策单位又切回条目（§5.9 ③
 * 的留痕）。所以本桥只发一条请求： {@code classKey} = {@value #CLASS_KEY}（会话放行按 {@code (callerKey, classKey)}
 * 归类，因此天然复用），{@code summary} = 人读一行（包 id + 条目数 + 工具名列表）， {@code digest} = 包级摘要（自己算，取前 {@link
 * DecisionProposeTool#DIGEST_HEX_CHARS} 个十六进制字符）。逐条细节<b>不进</b>请求载荷 ——那是包的提示面/HTTP 面（RB3）。
 *
 * <h2>复用既有审批链，不造第二套内核</h2>
 *
 * <p>投递走的是 {@code ToolCallAuthorizer} 今天在用的同一套：{@link ApprovalCoordinator}（含既有闸链与 fail-closed 口径）+
 * 装配层那<b>唯一</b>一份 {@link PendingApprovals} + 既有通道。本桥<b>不</b>新建登记表、<b>不</b>新建通道、 <b>不</b>新建 HTTP
 * 端点——§5.4 的验收判据（包路径下没有第二个 {@code PendingApprovals}、没有第二个 HTTP 端点族）由装配保证： 传进来的是装配层那两个实例。
 *
 * <h2>答复 → 裁决（本单只做整包批 / 整包驳）</h2>
 *
 * <p>答复 = {@link ApprovalDecision#APPROVE_ONCE}/{@link ApprovalDecision#APPROVE_SESSION} ⇒ {@code
 * approvedIndexes} = 全部条目；答复 = {@link ApprovalDecision#DENY} ⇒ {@code approvedIndexes} = 空集（包在提交时以
 * {@code REJECTED} 终局、零落盘）。<b>逐条例外</b>（选择性执行一部分）留给 RB3 的 HTTP 面：本单不做，也不假装能做。
 *
 * <p><b>怎么分辨"有人答了"与"没人答/没通道/编排故障"</b>：编排器把后者一律归为 {@code DENY} 并<b>摘掉登记项</b>（或不登记），
 * 而通道的硬约束是"答复必须先落进登记表再返回"（见 {@code ApprovalChannel}）。因此：{@code APPROVE_*} ⇒ 一定是答复； {@code DENY} ⇒
 * 只有 {@link PendingApprovals#get(String)} 还查得到这条请求时才算答复。查不到 ⇒ <b>没人答复</b>：见下面「没人答复 =
 * 当场作废」一节——本桥绝不会把"没人答" 写成"整包驳"。
 *
 * <h2>★ 没人答复 = 当场作废，不是"等包自己到点"（RB2c，设计 §5.12 裁定 B / §5.6）</h2>
 *
 * <p>没人答复的两条路——<b>审批窗口到期而无人答复</b>与<b>无可用通道</b>（编排器在投递前就拒）——都走 {@link
 * DecisionPackages#expireNow(String, long)}：包<b>当场</b>置 {@code EXPIRED}（零落盘、不静默执行），并落<b>一行
 * WARN</b>（含包 id 与原因）。这不是"提前过期"，而是 §5.6 本来就写的语义：「决策超时 / 无可用通道 ⇒ 整包 {@code EXPIRED}，零落盘」。
 *
 * <p><b>为什么必须当场作废</b>：审批窗口（缺省 300s）短于包的裁决窗口（缺省 30min，{@code
 * DecisionProposeTool#DEFAULT_DEADLINE_MILLIS}），而"一实例一个未决包"只数 {@code
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
 * <h2>线程与生命周期</h2>
 *
 * <p>投递要等人（{@code ApprovalCoordinator#decide} 阻塞到答复或超时），所以 {@link #onProposed} 只<b>登记</b>一个虚拟线程就返回：
 * 提议工具的这次调用不被人的思考时间绑住，不同实例的包也互不等候。虚拟线程是守护线程，进程退出不因在途投递挂住。
 *
 * <h2>密钥纪律（红线 6）</h2>
 *
 * <p>{@code summary}/{@code digest} 与全部日志只出现包 id、工具名、条目数、脱敏摘要——参数明文只活在包内 {@code args} 与提交口那条路上，
 * 本桥一行都不碰。
 */
public final class DecisionApprovalBridge {

  private static final Logger LOG = LoggerFactory.getLogger(DecisionApprovalBridge.class);

  /** 包级类键（设计 §5.9 ③ 的冻结字面量）：一条包一条请求，会话放行按 {@code (callerKey, 本键)} 归类。 */
  public static final String CLASS_KEY = "decision:package";

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

  /**
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
    this.packages = Objects.requireNonNull(packages, "packages");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.pending = Objects.requireNonNull(pending, "pending");
    this.commitPort = Objects.requireNonNull(commitPort, "commitPort");
  }

  /**
   * 提议成功回调（接进 {@link DecisionProposeTool} 的注入缝）：<b>只登记投递</b>、不阻塞提议线程。
   *
   * <p>非 {@code PROPOSED} 的包不投：{@code propose} 在"到点时刻已过"时会直接返回 {@code EXPIRED} 的包（RB1 的正常结果），
   * 给它发审批请求等于让提示面挂一条永远裁不动的项。
   */
  public void onProposed(DecisionPackage proposed) {
    Objects.requireNonNull(proposed, "proposed");
    if (proposed.state() != PackageState.PROPOSED) {
      LOG.warn(
          "包不在待裁决态，不投审批面 packageId={} state={}（到点建出来的包就是这个态；零落盘）", proposed.id(), proposed.state());
      return;
    }
    Thread.ofVirtual().name("decision-approval-" + proposed.id()).start(() -> deliver(proposed));
  }

  /** 投递 + 等待 + 回填：跑在自己的虚拟线程上（见类注释的线程一节）。 */
  private void deliver(DecisionPackage proposed) {
    try {
      ApprovalRequest request = requestFor(proposed);
      LOG.info(
          "决策包已投审批面 requestId={} packageId={} caller={} proposerKey={} items={} deadlineEpochMs={}",
          request.id(),
          proposed.id(),
          proposed.caller(),
          proposed.proposerKey(),
          proposed.items().size(),
          request.deadlineEpochMs());
      ApprovalDecision answer = coordinator.decide(request);
      if (!answered(request, answer)) {
        // RB2c（设计 §5.12 裁定 B / §5.6）：没人答复 ⇒ 当场具名作废，不是"等包自己到点"——
        // 不作废，这个早已无人可批的包会白占「一实例一个未决包」的名额到包自己的 30min 上限
        expireUnanswered(proposed, request, answer);
        return;
      }
      backfill(proposed, answer);
    } catch (RuntimeException failure) {
      // 投递线程不得静默死掉：包的状态由 RB1 自己兜底（到点 EXPIRED），但异常必须可见
      LOG.error("决策包审批投递异常（包仍按 RB1 状态机走，未被本桥改写） packageId={}", proposed.id(), failure);
    }
  }

  /**
   * 把包投成一条 {@link ApprovalRequest}：{@code tool}/{@code classKey}/{@code callerKey}/{@code
   * requesterId}/ {@code kind} 全部取自宿主已知的包（模型给不出也改不了），{@code summary}/{@code digest} 不含明文。
   *
   * <p>{@code tool} 用 {@link DecisionProposeTool#NAME}：这个面今天只有它一个入口，且它是工具目录里真实存在的名字 （与 {@code
   * ToolCallAuthorizer} 的 {@code tool = tool.name()} 同口径）；"这是哪一类请求"由 {@code classKey} 回答。
   *
   * <p>{@code kind} 取 {@link AskKind#SENSITIVE}（= 缺省，即"没说明理由 ⇒ 不上代批台"）：包不是"档位提升"这一类， 包里还可能有敏感条目，
   * 人批是保守的一侧。将来若要允许上级 Agent 代批包，那是显式裁定 + 改这一处的事，不是本单。
   *
   * <p>{@code deadlineEpochMs} 与编排器超时同源（{@code ApprovalRequest} 的口径），但<b>不晚于包自己的到点时刻</b>：
   * 人答在一个已经作废的包上只会得到 {@code PACKAGE_EXPIRED} 的具名拒，不如让提示面此刻就诚实。
   */
  private ApprovalRequest requestFor(DecisionPackage proposed) {
    long now = System.currentTimeMillis();
    Duration window = coordinator.timeout();
    long deadline = Math.min(proposed.deadlineEpochMs(), now + Math.max(0L, window.toMillis()));
    return new ApprovalRequest(
        // 与 ToolCallAuthorizer 同形（ap- + UUID）：请求 id 不得含命令明文/密钥
        "ap-" + UUID.randomUUID(),
        DecisionProposeTool.NAME,
        CLASS_KEY,
        summary(proposed),
        packageDigest(proposed),
        now,
        deadline,
        proposed.caller().name(),
        proposed.proposerKey(),
        AskKind.SENSITIVE,
        "");
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
   * <p><b>已知残差（如实记）</b>：将来若装上一条会终局 {@code DENY} 的快判闸（今天的链上没有，见类注释"已知边界"）， 它的即时 DENY
   * 也会落进"无可用通道"那一档——<b>动作不变</b>（一样当场作废），只是 WARN 的原因措辞不够精确。
   */
  private static String noAnswerReason(ApprovalRequest request, long nowEpochMs) {
    return nowEpochMs >= request.deadlineEpochMs() ? REASON_WINDOW_EXPIRED : REASON_NO_CHANNEL;
  }

  /**
   * 答复 → RB1 统一裁决 → （可选）一次提交。
   *
   * <p><b>放行方向写死</b>：只有显式的 {@code APPROVE_ONCE}/{@code APPROVE_SESSION} 才批（缺省/意外值 一律按驳处理）—— 与
   * {@code ToolCallAuthorizer} 对编排器违约（返回 null）的口径一致：没拿到显式批准就是拒，绝不把"没看懂"变成"执行"。
   */
  private void backfill(DecisionPackage proposed, ApprovalDecision answer) {
    boolean approved =
        answer == ApprovalDecision.APPROVE_ONCE || answer == ApprovalDecision.APPROVE_SESSION;
    int itemCount = proposed.items().size();
    Decision decision =
        approved
            ? new Decision(allIndexes(proposed), BY_APPROVAL, "审批链整包批准（" + itemCount + " 条）")
            : new Decision(Set.of(), BY_APPROVAL, "审批链整包驳回（" + itemCount + " 条）");
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
    LOG.info(
        "决策包裁决已回填 packageId={} approved={} rejected={} by={}",
        decided.id(),
        decision.approvedIndexes().size(),
        itemCount - decision.approvedIndexes().size(),
        decision.by());
    if (commitPort.isEmpty()) {
      LOG.warn(
          "提交口未装配（DecisionCommitPort 未注入）：包保持 DECIDED、不调用 commit、不落盘——"
              + "这不是「已落定」，等提交口装配后由宿主接上 packageId={} items={}",
          decided.id(),
          itemCount);
      return;
    }
    try {
      CommitReceipt receipt = packages.commit(proposed.id(), commitPort.orElseThrow());
      LOG.info(
          "决策包提交完成 packageId={} status={} outcomes={} revisionRef={}",
          decided.id(),
          receipt.status(),
          receipt.outcomes().size(),
          receipt.revisionRef());
    } catch (DecisionPackages.RejectionException refusal) {
      // COMMIT_FAILED（提交口抛异常/回执违约）⇒ 包保持 DECIDED、不记回执：绝不把"没落定"说成落定
      LOG.error(
          "决策包提交未落定（包保持 DECIDED）：packageId={} code={} 理由={}",
          decided.id(),
          refusal.code(),
          refusal.getMessage());
    }
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
   * 人读一行（提示面专用）：包 id + 条目数 + 工具名列表——<b>只含公开面信息</b>（工具名是公开的，设计 §5.10 的 {@code toolNames}
   * 同档），不含参数明文，也不含参数摘要以外的任何东西。
   *
   * <p>包内重复的工具名不折叠：一条条目就是一次待决调用，"3 条里两条是 bash"是确认者要看见的信息。
   */
  static String summary(DecisionPackage proposed) {
    List<String> tools = new ArrayList<>(proposed.items().size());
    for (PackageItem item : proposed.items()) {
      tools.add(item.toolName());
    }
    return "决策包 "
        + proposed.id()
        + "："
        + proposed.items().size()
        + " 条提议（"
        + String.join("、", tools)
        + "）待整包裁决";
  }

  /**
   * 包级摘要：对"条目顺序 + 工具名 + 该条已脱敏的 {@code argsDigest}"的规范化串取 SHA-256，再取前 {@link
   * DecisionProposeTool#DIGEST_HEX_CHARS} 个十六进制字符（小写）。
   *
   * <p><b>为什么复用条目已有的 {@code argsDigest} 而不是重算参数</b>：① 参数明文不进本层（密钥纪律的落点越少越好）； ②
   * 摘要的输入面必须与"包内到底有哪几条"一一对应，而条目摘要正是宿主算好的那份（统一实现点，不在这里造第二处）。
   *
   * <p>{@code index} 与 {@code toolName} 也进输入面：只有参数摘要的话，"同一个命令、换个工具"会得到同一个包摘要，对账时说不清区别。
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
    byte[] hash = sha256(material.toString());
    StringBuilder hex = new StringBuilder(DecisionProposeTool.DIGEST_HEX_CHARS);
    for (int i = 0; i < DecisionProposeTool.DIGEST_HEX_CHARS / 2; i++) {
      hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
      hex.append(Character.forDigit(hash[i] & 0xF, 16));
    }
    return hex.toString();
  }

  private static byte[] sha256(String material) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      // JVM 必须支持 SHA-256（JDK 规范要求）；真没有就是运行环境坏了，响亮失败
      throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
    }
  }
}
