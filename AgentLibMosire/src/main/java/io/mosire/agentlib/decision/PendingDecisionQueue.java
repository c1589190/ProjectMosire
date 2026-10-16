package io.mosire.agentlib.decision;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>本轮待裁决队列</b>（设计 §5 裁定 1+2，{@code 设计-决策包作为唯一决策单元.md}）：一个 Agent 实例的<b>一轮</b>里命中的 全部 {@link
 * ToolGate.Ask} 调用先登记在这里，回合收尾时收成<b>一个</b>决策包。
 *
 * <p><b>为什么需要它</b>（V2/V3/V4 的落点）：进包口径改成"只要是需要决策不能 Agent 自己执行的，全部进包"之后，{@code Ask}
 * 不再是"当场问人、当场执行/拒"，而是"登记 + 当场把『已加入决策包』回给模型"。一轮里点 N 个敏感调用 ⇒ <b>一个包、N 条</b> （对比旧行为：N
 * 次分别打断人）。裁决结果本轮拿不到（汇总包的固有代价）——模型下一轮自己查。
 *
 * <p><b>作用域 = 一个 Agent 的一轮</b>（不是进程级、不是实例级、不跨轮继承）：由管线在回合开始时建新队列并 {@link
 * DecisionTurnScope#bind}、在回合收尾（含异常/取消路径）{@link DecisionTurnScope#clear}。见 {@link
 * DecisionTurnScope} 的"为什么是 ThreadLocal"。
 *
 * <p><b>线程安全</b>：同一轮里的工具调用可能落在不同线程上（管线线程执行模型给的调用；MCP 桥线程执行外部面进来的调用， 见 {@code
 * AgentToMcpServer}），所以登记动作走本类内部那把 {@link ReentrantLock}；{@code volatile} 的 {@code proposalMade} 让
 * "已提议" 这件事跨线程立即可见。
 *
 * <p><b>空队列 ⇒ 什么都不做</b>（设计 P1 验收判据 ④）：本类不建包、不发事件、不投递、不落任何记录——建包是<b>调用方</b>的动作 （{@code
 * DecisionApprovalBridge#flush}），空队列连 {@link #toProposeRequest()} 都拿不到值（见该方法的契约）。
 *
 * <p><b>★ 密钥纪律（红线 6）</b>：{@link PendingEntry#args()} 是参数<b>明文</b>，只准进包内 {@link PackageItem#args()}
 * 与提交口那条路 （{@link PackageItem} 的类注释是同一句话）。本类<b>不</b>在日志/异常消息里打印 {@code args}：日志只出现工具名、类键、条目数。
 *
 * <p><b>两个派生件不在本类里算</b>：{@code argsDigest} 与 {@code summary} 由 {@link DecisionItemDigest} 派生（裁定 6
 * 把"条目摘要派生" 搬进包层之后就这一个实现点）；本类只负责"登记那一刻就把它们算好"——包内条目一旦建出来，摘要口径就不可能事后分叉。
 *
 * <p><b>类键与 {@code AskKind} 留下来干什么</b>：{@link PendingEntry#classKey()} 与 {@link
 * PendingEntry#askKind()} 是 P2 的料—— 上级判定要按"这条为什么问"分流（{@link AskKind#SENSITIVE} 只到人 / {@link
 * AskKind#MODE_LIMITED} 可交上级）， 包级类键也要能从条目类键汇总出来。包层（{@link PackageItem}）装不下这两样（冻结形状），所以它们只活在本队列的条目里。
 */
public final class PendingDecisionQueue {

  private static final Logger LOG = LoggerFactory.getLogger(PendingDecisionQueue.class);

  /** 同一个 Agent 实例同一轮里最多能攒多少条：不是限额，是"有人忘了提议/忘了清"这类 bug 的响亮出口。 */
  private static final int MAX_ENTRIES = 4096;

  private final String proposerKey;

  /** 调用者令牌（{@code ProposeRequest.caller} 的取值来源；调用者是谁由宿主在 bind 时给定，模型给不出）。 */
  private final AccessToken caller;

  private final ReentrantLock lock = new ReentrantLock();
  private final List<PendingEntry> entries = new ArrayList<>();

  /** 已经产出过提议 ⇒ 本队列作废（登记新条目是调用方的顺序 bug，见 {@link #register}）。 */
  private volatile boolean proposalMade;

  /**
   * @param proposerKey 提议者实例键（{@code AgentIdentity#instanceId()}："一实例一未决包"的键，也是包与裁决历史的归属键）
   * @param caller 调用者等级桶（与 {@code proposerKey} 正交：桶答"什么等级"，键答"哪个实例"）
   */
  public PendingDecisionQueue(String proposerKey, AccessToken caller) {
    this.proposerKey = Objects.requireNonNull(proposerKey, "proposerKey");
    if (proposerKey.isBlank()) {
      throw new IllegalArgumentException("proposerKey 不得为空白（它是「一个实例一个未决包」的键）");
    }
    this.caller = Objects.requireNonNull(caller, "caller");
  }

  /** 提议者实例键（进 {@link ProposeRequest#proposerKey()}；包与历史跟着它走，设计 §5 裁定 3）。 */
  public String proposerKey() {
    return proposerKey;
  }

  /** 该 Agent 的令牌（进 {@link ProposeRequest#caller()}）。 */
  public AccessToken caller() {
    return caller;
  }

  /**
   * 登记一次命中的 {@code Ask} 调用（{@code ToolCallAuthorizer} 的唯一写口）。
   *
   * <p>参数明文在这里做<b>顶层不可变快照</b>（{@link PackageItem} 的口径），摘要与人读一行当场由 {@link DecisionItemDigest}
   * 派生——晚一步派生就可能有人换个口径再算一遍。
   *
   * <p>{@code args} 为空是合法的（工具可以没有参数）：条目摘要退化成纯工具名，与旧提议口的行为一致。
   *
   * @param toolName 工具名（宿主已知的真名，不是模型给的字符串）
   * @param args 本次调用的参数明文（只进包内条目；顶层不得有 null 键/值）
   * @param ask 工具自报的闸位（提供 {@code classKey} / 人读 {@code summary} / {@code kind}）
   * @throws IllegalStateException 本队列已经产出过提议（登记必须发生在收尾之前）
   */
  public void register(String toolName, Map<String, Object> args, ToolGate.Ask ask) {
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(ask, "ask");
    Map<String, Object> snapshot = Map.copyOf(args);
    AskKind kind = ask.kind() == null ? AskKind.SENSITIVE : ask.kind();
    lock.lock();
    try {
      if (proposalMade) {
        // 顺序 bug（收尾已经建包了，还有调用想登记）：响亮失败，不静默丢——丢掉的是一条待裁决调用
        throw new IllegalStateException(
            "本轮待裁决队列已经产出过提议，不能再登记：tool=" + toolName + " proposerKey=" + proposerKey);
      }
      if (entries.size() >= MAX_ENTRIES) {
        throw new IllegalStateException(
            "本轮待裁决队列超过 " + MAX_ENTRIES + " 条（几乎肯定是没人收尾或收尾路径写错了）：proposerKey=" + proposerKey);
      }
      int index = entries.size();
      PackageItem item =
          new PackageItem(
              index,
              toolName,
              snapshot,
              DecisionItemDigest.argsDigest(snapshot),
              DecisionItemDigest.itemSummary(toolName, snapshot));
      entries.add(new PendingEntry(toolName, snapshot, ask.classKey(), kind, item));
      LOG.debug(
          "待裁决调用已登记 index={} tool={} classKey={} kind={} proposerKey={}（本轮不执行、不阻塞、不走审批闸）",
          index,
          toolName,
          ask.classKey(),
          kind,
          proposerKey);
    } finally {
      lock.unlock();
    }
  }

  /** 本轮是否一条都没登记（回合收尾据此走"零副作用"那条路）。 */
  public boolean isEmpty() {
    lock.lock();
    try {
      return entries.isEmpty();
    } finally {
      lock.unlock();
    }
  }

  /** 已登记条数（日志/诊断用；不是限额）。 */
  public int size() {
    lock.lock();
    try {
      return entries.size();
    } finally {
      lock.unlock();
    }
  }

  /** 当刻条目的不可变快照（顺序 = 登记顺序；{@link PendingEntry#packageItem()} 的 {@code index} 与之同序）。 */
  public List<PendingEntry> snapshot() {
    lock.lock();
    try {
      return List.copyOf(entries);
    } finally {
      lock.unlock();
    }
  }

  /**
   * 收成本轮的提议请求（{@link DecisionPackages#propose(ProposeRequest)} 的入参）： {@code deadlineEpochMs = now +
   * }{@link DecisionPackages#DEFAULT_DEADLINE_MILLIS}（裁定 10：裁决等待走<b>包的</b> deadline）。
   *
   * <p><b>到点时刻在收尾这一刻算</b>，不是在第一条登记时算：本轮的裁决窗口应当从"包真的摆到确认者面前"起算，
   * 而不是从"模型第一次点敏感工具"起算（后者会让一个跑了很久的回合还没投递就先少掉一大截窗口）。
   *
   * <p><b>产出即作废</b>：本方法成功返回后，队列进入"已提议"态——再登记是顺序 bug（见 {@link #register}）。 调用方（桥的 {@code
   * flush}）拿到请求就应当建包；建包失败时不要把队列当"还没提议"接着用（重试要走新的一轮）。
   *
   * @return 提议请求；<b>空队列 ⇒ 空值</b>（空包不是"轻量包"，是调用方 bug——见 {@code DecisionPackages.propose}）
   */
  public Optional<ProposeRequest> toProposeRequest() {
    return toProposeRequest(System.currentTimeMillis());
  }

  /**
   * 同上，但由调用方给定当刻墙钟（可测）；窗口长度仍是 {@link DecisionPackages#DEFAULT_DEADLINE_MILLIS}。
   *
   * @param nowEpochMs 收尾当刻的墙钟毫秒
   */
  public Optional<ProposeRequest> toProposeRequest(long nowEpochMs) {
    List<PackageItem> items = new ArrayList<>();
    lock.lock();
    try {
      if (entries.isEmpty()) {
        return Optional.empty();
      }
      if (proposalMade) {
        throw new IllegalStateException("本轮待裁决队列已经产出过提议（一个队列只收一个包）：proposerKey=" + proposerKey);
      }
      for (PendingEntry entry : entries) {
        items.add(entry.packageItem());
      }
      proposalMade = true;
    } finally {
      lock.unlock();
    }
    ProposeRequest request =
        new ProposeRequest(
            proposerKey, caller, items, nowEpochMs + DecisionPackages.DEFAULT_DEADLINE_MILLIS);
    LOG.debug(
        "本轮待裁决队列收成一个包：proposerKey={} caller={} items={} deadlineEpochMs={}",
        proposerKey,
        caller,
        items.size(),
        request.deadlineEpochMs());
    return Optional.of(request);
  }

  /**
   * 队列里的一条：工具名 + <b>不可变 args 快照</b> + 派生该条所需的闸位（{@code classKey} 与 {@link AskKind}）+ 算好的包内条目。
   *
   * <p>{@code args} 与 {@code packageItem().args()} 是<b>同一份</b>顶层不可变快照（{@link Map#copyOf}
   * 的结果，不是深拷贝）： 两处各存一份拷贝只会让"包里那条"与"队列里这条"有机会分叉，而它们本来就该是同一条调用。
   *
   * @param toolName 工具名
   * @param args 参数明文（顶层不可变快照；密钥纪律：只进包内条目与提交口）
   * @param classKey 工具自报的闸位类键（会话级放行的归类键；P2 汇总包级类键要用）
   * @param askKind 为什么问（{@link AskKind#SENSITIVE} 只到人 / {@link AskKind#MODE_LIMITED} 可交上级；P2
   *     的上级判定要用）
   * @param packageItem 包内条目（index / toolName / args / argsDigest / summary）
   */
  public record PendingEntry(
      String toolName,
      Map<String, Object> args,
      String classKey,
      AskKind askKind,
      PackageItem packageItem) {

    public PendingEntry {
      Objects.requireNonNull(toolName, "toolName");
      Objects.requireNonNull(args, "args");
      Objects.requireNonNull(classKey, "classKey");
      Objects.requireNonNull(askKind, "askKind");
      Objects.requireNonNull(packageItem, "packageItem");
      args = Map.copyOf(args);
    }
  }
}
