package io.mosire.brain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalGate;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.approval.SuperiorJudgeDetailRegistry;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.DecisionCommitPort;
import io.mosire.agentlib.decision.DecisionEventTypes;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.decision.ResolvedCall;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.permission.AccessToken;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 审批桥（回合收尾的汇总投递口 + 包级请求）在 <b>Brain 层</b>的判据：一轮 N 条 ⇒ 恰好一个包、一条包级请求；空队列零副作用； 同步投递（flush
 * 返回时裁决与执行都已发生）；上报集合里只要有一条 {@link AskKind#SENSITIVE}，整条包级请求就必须取 {@code SENSITIVE} （防"上级闸一次批掉整包"）。
 *
 * <p><b>为什么必须在桥这一层验</b>：这些判据的判别点在桥的私有分支（{@code requestKind} / {@code deliver}），
 * 端到端用例只能从外面看结果；而"整包到人还是可交上级"这条恰好是端到端最难构造的一种形状（要同时凑出可预裁定的条目与被强制到人的条目）。
 *
 * <p><b>判别力（改坏哪里它会红）</b>：
 *
 * <ul>
 *   <li>把 {@code onProposed} 的同步 {@code deliver(...)} 换回 {@code Thread.ofVirtual().start(...)} ⇒
 *       {@link #deliveryIsSynchronousSoThePackageIsAlreadyTerminalWhenFlushReturns()} 红（flush
 *       返回时包还没落定）；
 *   <li>把 {@code requestKind} 改成恒返回 {@code MODE_LIMITED} ⇒ {@link
 *       #aMixedEscalationContainingASensitiveItemForcesTheWholeRequestToSensitive()} 红；
 *   <li>把 {@code flush} 的空队列早返回删掉（空队列也建包）⇒ {@link
 *       #anEmptyRoundFlushesToNothingWithZeroSideEffects()} 红；
 *   <li>把"一条包 = 一条请求"改成"每条各投一条请求" ⇒ {@link
 *       #oneRoundWithThreeAsksBecomesOnePackageAndOneApprovalRequest()} 红；
 *   <li>把事件载荷里的 {@code digests/outcomes} 换成参数明文 ⇒ {@link
 *       #eventPayloadsNeverCarryArgumentPlaintext()} 红。
 * </ul>
 */
class DecisionApprovalBridgeTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SECRET = "sk-live-SECRET123";

  private PendingApprovals pending;
  private RecordingChannel channel;
  private EventBus bus;
  private List<Event> events;
  private DecisionPackages packages;
  private DecisionApprovalBridge bridge;

  /** 记下"投了几条请求、有没有人真的被问"的桩通道。 */
  private static final class RecordingChannel implements ApprovalChannel {

    private final List<ApprovalRequest> published = new CopyOnWriteArrayList<>();
    private final Deque<ApprovalDecision> script = new ArrayDeque<>();
    private final AtomicInteger availableCalls = new AtomicInteger();
    private final AtomicInteger awaitCalls = new AtomicInteger();

    RecordingChannel answer(ApprovalDecision decision) {
      script.addLast(decision);
      return this;
    }

    @Override
    public String name() {
      return "case-channel";
    }

    @Override
    public boolean available() {
      availableCalls.incrementAndGet();
      return true;
    }

    @Override
    public void publish(ApprovalRequest request) {
      published.add(request);
    }

    @Override
    public Optional<ApprovalDecision> await(String id, Duration wait) {
      awaitCalls.incrementAndGet();
      return Optional.ofNullable(script.pollFirst());
    }
  }

  private void start(
      Duration window,
      Optional<DecisionCommitPort> commitPort,
      boolean withJudgementDetails,
      Optional<DecisionCascade> cascade) {
    startWithGates(List.of(), window, commitPort, withJudgementDetails, cascade);
  }

  /** 与 {@link #start} 同形，但可以给<b>闸链</b>塞快判闸（用来造"无人在环"那条路：闸链代批）。 */
  private void startWithGates(
      List<ApprovalGate> gates,
      Duration window,
      Optional<DecisionCommitPort> commitPort,
      boolean withJudgementDetails) {
    startWithGates(gates, window, commitPort, withJudgementDetails, Optional.empty());
  }

  private void startWithGates(
      List<ApprovalGate> gates,
      Duration window,
      Optional<DecisionCommitPort> commitPort,
      boolean withJudgementDetails,
      Optional<DecisionCascade> cascade) {
    pending = new PendingApprovals();
    channel = new RecordingChannel();
    bus = new EventBus();
    events = new CopyOnWriteArrayList<>();
    bus.subscribe(events::add);
    packages = new DecisionPackages();
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(gates, List.of(channel), pending, window, bus, key -> true);
    bridge =
        new DecisionApprovalBridge(
            packages,
            coordinator,
            pending,
            commitPort,
            bus,
            withJudgementDetails
                ? Optional.of(new SuperiorJudgeDetailRegistry())
                : Optional.empty(),
            cascade);
  }

  @AfterEach
  void tearDown() {
    if (bus != null) {
      bus.close();
    }
  }

  private static PendingDecisionQueue queue(String proposerKey) {
    return new PendingDecisionQueue(proposerKey, AccessToken.DEFAULT);
  }

  private static ToolGate.Ask ask(String classKey, AskKind kind) {
    return new ToolGate.Ask(classKey, "人读一行说明", kind);
  }

  private static CommitReceipt committed(String packageId, int count) {
    List<CommitReceipt.ItemOutcome> outcomes = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      outcomes.add(CommitReceipt.ItemOutcome.COMMITTED);
    }
    return new CommitReceipt(CommitReceipt.Status.COMMITTED, "batch:" + packageId, outcomes, "");
  }

  /** 等一条事件（总线是异步分派，落进本列表有微小延迟）。 */
  private Event awaitEvent(String type, Duration limit) throws InterruptedException {
    long deadline = System.currentTimeMillis() + limit.toMillis();
    while (System.currentTimeMillis() < deadline) {
      for (Event event : events) {
        if (type.equals(event.type())) {
          return event;
        }
      }
      Thread.sleep(10L);
    }
    throw new AssertionError("等不到事件 " + type + "，拿到：" + events.stream().map(Event::type).toList());
  }

  // ---------- C-1：一轮 N 条 ⇒ 一个包、一条请求 ----------

  @Test
  void oneRoundWithThreeAsksBecomesOnePackageAndOneApprovalRequest() throws Exception {
    List<List<ResolvedCall>> batches = new ArrayList<>();
    start(
        Duration.ofSeconds(30),
        Optional.of(
            (id, calls) -> {
              batches.add(List.copyOf(calls));
              return committed(id, calls.size());
            }),
        true,
        Optional.empty());
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    PendingDecisionQueue queue = queue("main");
    queue.register(
        "bash", Map.of("command", "systemctl restart a"), ask("bash:ask:a", AskKind.MODE_LIMITED));
    queue.register(
        "bash", Map.of("command", "systemctl restart b"), ask("bash:ask:b", AskKind.MODE_LIMITED));
    queue.register("doc_edit", Map.of("path", "x.md"), ask("docs:ask", AskKind.MODE_LIMITED));

    Optional<DecisionPackage> settled = bridge.flush(queue);

    assertThat(settled).isPresent();
    assertThat(settled.orElseThrow().items()).as("一轮三条 ⇒ 包内三条").hasSize(3);
    // ★★ 通道上的批准 = 人**自己的执行命令**（2026-10-17 控制方裁定 2，用户可推翻）：判据与跨进程网关的
    //   {@code byHuman} 同源（审批面上登记项还在 ⇒ 这是人答的，不是闸链代批）⇒ flush 返回时裁决与执行
    //   都已经发生。★ 判别力：去掉 {@code DecisionApprovalBridge#backfill} 的 {@code approved} 分支 ⇒
    //   这里退回 DECIDED、下面 batches 为空 ⇒ 当场红。
    assertThat(settled.orElseThrow().state())
        .as("flush 返回时人已批 ⇒ 被批子集已执行（人批的那个键就是执行命令）")
        .isEqualTo(PackageState.COMMITTED);
    assertThat(channel.published).as("一条包 = 一条包级请求（不是三条）").hasSize(1);
    assertThat(pending.pending()).as("审批面上同样只有一条（没有'不属于任何包的待裁决项'）").hasSize(1);
    ApprovalRequest request = channel.published.get(0);
    assertThat(request.classKey()).isEqualTo(DecisionApprovalBridge.CLASS_KEY);
    assertThat(request.tool()).as("tool 取包内首条的真工具名（不是常量）").isEqualTo("bash");
    assertThat(request.callerKey())
        .as("包级请求用它自己的桶（一次'本会话'不得放行以后每一个包）")
        .isEqualTo(DecisionApprovalBridge.packageCallerKey(settled.orElseThrow()))
        .satisfies(DecisionApprovalBridge::isPackageCallerKey);
    assertThat(request.kind())
        .as("上报条目全是 MODE_LIMITED 且明细缝在 ⇒ 可交上级代批")
        .isEqualTo(AskKind.MODE_LIMITED);
    assertThat(batches).as("B-1：被批子集只提交一次").hasSize(1);
    assertThat(batches.get(0)).extracting(ResolvedCall::index).containsExactly(0, 1, 2);

    JsonNode committed =
        JSON.readTree(awaitEvent(DecisionEventTypes.COMMITTED, Duration.ofSeconds(5)).payload());
    assertThat(committed.get("packageId").asText()).isEqualTo(settled.orElseThrow().id());
    assertThat(committed.get("outcomes")).as("逐条去向进事件载荷（下一轮查得到）").hasSize(3);
  }

  // ---------- C-8：SENSITIVE 强制到人（整条包级请求） ----------

  @Test
  void aMixedEscalationContainingASensitiveItemForcesTheWholeRequestToSensitive() {
    start(Duration.ofSeconds(30), Optional.empty(), true, Optional.empty());
    channel.answer(ApprovalDecision.DENY);
    PendingDecisionQueue queue = queue("main");
    queue.register(
        "bash", Map.of("command", "sudo apt install x"), ask("bash:ask:apt", AskKind.MODE_LIMITED));
    queue.register("net_probe", Map.of("url", "http://x"), ask("net:ask", AskKind.SENSITIVE));

    bridge.flush(queue);

    assertThat(channel.published).hasSize(1);
    assertThat(channel.published.get(0).kind())
        .as("★ 上报集合里有 SENSITIVE ⇒ 整条请求只到人，上级闸不许一次把整包批掉")
        .isEqualTo(AskKind.SENSITIVE);
  }

  @Test
  void limitedOnlyEscalationStaysModeLimitedAsThePositiveControl() {
    start(Duration.ofSeconds(30), Optional.empty(), true, Optional.empty());
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    PendingDecisionQueue queue = queue("main");
    queue.register(
        "bash", Map.of("command", "sudo apt install x"), ask("bash:ask:apt", AskKind.MODE_LIMITED));

    bridge.flush(queue);

    assertThat(channel.published).hasSize(1);
    assertThat(channel.published.get(0).kind())
        .as("对照：同样是'上报到人'，只有档位提升这一类才可交上级——上一条用例的红不是因为别的原因")
        .isEqualTo(AskKind.MODE_LIMITED);
  }

  /**
   * ★ <b>记录当前行为 + 标注边界（不是"这样就是对的"）</b>：{@code requestKind} 的 javadoc 承诺"没装配上级判定明细缝 ⇒ 退回 {@code
   * SENSITIVE}（看不见就不给批，只到人）"，但实现只在<b>本来就有</b>非 {@code MODE_LIMITED} 条目（或没料）时才返回 {@code
   * SENSITIVE}——全是 {@code MODE_LIMITED} 且明细缝缺席时，它照样返回 {@code MODE_LIMITED}， 只多落一行 WARN（{@code
   * DecisionApprovalBridge.java:555-557}）。本用例钉住<b>实际发生的</b>那一档，免得这条边界在 无人知晓的情况下被改来改去；修不修是 P2
   * 的事（生产装配里明细缝与 {@code SuperiorJudgeGate} 同进同退，故风险有限）。
   */
  @Test
  void withoutTheDetailSeamThePackageGoesToTheHumanEvenWhenEveryItemIsModeLimited() {
    start(Duration.ofSeconds(30), Optional.empty(), false, Optional.empty());
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    PendingDecisionQueue queue = queue("main");
    queue.register(
        "bash", Map.of("command", "sudo apt install x"), ask("bash:ask:apt", AskKind.MODE_LIMITED));

    bridge.flush(queue);

    // ★ 2026-10-16 更新（RT 报的 §5-1 已被控制方按 javadoc 修正）：明细缝缺席 ⇒ 上级判定只看得到工具名，
    //   那是"看一半批全部"⇒ 一律退回 SENSITIVE（只到人），不再返回 MODE_LIMITED。
    //   本断言因此从"记录旧行为（MODE_LIMITED）"改为"钉住修正后的契约（SENSITIVE）"。
    assertThat(channel.published.get(0).kind())
        .as("明细缝缺席 ⇒ 看不见内容就不给批，包只到人（修正后契约）")
        .isEqualTo(AskKind.SENSITIVE);
  }

  // ---------- 空队列零副作用（P1 验收判据 ③） ----------

  @Test
  void anEmptyRoundFlushesToNothingWithZeroSideEffects() throws Exception {
    AtomicInteger commits = new AtomicInteger();
    start(
        Duration.ofSeconds(5),
        Optional.of(
            (id, calls) -> {
              commits.incrementAndGet();
              return committed(id, calls.size());
            }),
        true,
        Optional.empty());

    Optional<DecisionPackage> result = bridge.flush(queue("main"));
    Thread.sleep(300L);

    assertThat(result).isEmpty();
    assertThat(packages.pending()).as("没有包").isEmpty();
    assertThat(pending.pending()).as("审批面上没有请求").isEmpty();
    assertThat(channel.published).as("没有投递").isEmpty();
    assertThat(channel.availableCalls).as("连通道都没被碰过").hasValue(0);
    assertThat(commits).as("没有提交").hasValue(0);
    assertThat(events).as("一条 decision.package.* 都不该有").isEmpty();
  }

  // ---------- C-3 / C-4：同步投递 ----------

  @Test
  void deliveryIsSynchronousSoThePackageIsAlreadyTerminalWhenFlushReturns() {
    List<List<ResolvedCall>> batches = new ArrayList<>();
    start(
        Duration.ofSeconds(120),
        Optional.of(
            (id, calls) -> {
              batches.add(List.copyOf(calls));
              return committed(id, calls.size());
            }),
        true,
        Optional.empty());
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    PendingDecisionQueue queue = queue("main");
    queue.register(
        "bash", Map.of("command", "sudo apt install x"), ask("bash:ask:apt", AskKind.MODE_LIMITED));

    long startedNanos = System.nanoTime();
    Optional<DecisionPackage> settled = bridge.flush(queue);
    long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

    // ★★ 同步投递的判据原样成立（裁定 4 + 控制方裁定 2）：人批的那个键就是执行命令 ⇒ flush 返回时
    //   **裁决与执行都已经发生**。判别力：去掉 backfill 的 approved 分支 ⇒ 本条退回 DECIDED、batches 为空。
    assertThat(settled.orElseThrow().state())
        .as("flush 返回时裁决与执行都已经发生（同步投递：不是'提了就走'）")
        .isEqualTo(PackageState.COMMITTED);
    assertThat(batches).as("提交口在 flush 返回之前就被调过").hasSize(1);
    assertThat(channel.awaitCalls).as("确实问过通道（不是绕开人等）").hasValue(1);
    assertThat(elapsedMs).as("窗口 120s，但人立刻答了 ⇒ 远早于窗口返回").isLessThan(10_000L);
  }

  // ---------- C-5：闸链代批（无人在环）⇒ 表态即执行 ----------

  /**
   * ★★ <b>闸链代批 = 无人在环 ⇒ 表态即执行</b>（2026-10-17 控制方裁定 1，用户可推翻）：快判链在<b>登记之前</b>跑并给出终局批准 ⇒
   * 审批面上从来没有过登记项（连 {@code approval.requested} 都没发）⇒ 没有人能看到这一包、也没有人能敲执行命令；若仍要求显式
   * commit，这一包只会白等到窗口结束再按"已批未执行"作废——自动路径等于失效。
   *
   * <p><b>与上一条的分界</b>：上一条是人在通道上答的（判据 {@code registered(request)} 为真）；本条在审批面上一条登记项都没有
   * （判据为假）——两条都当场执行，只是"谁拍的板"不同。
   *
   * <p><b>判别力</b>：去掉 {@code DecisionApprovalBridge#backfill} 的 {@code approved} 分支 ⇒ 本条 {@code
   * state} 退回 {@code DECIDED} 且 {@code batches} 为空 ⇒ 当场红。
   */
  @Test
  void gateChainApprovalWithoutAHumanAlsoCommitsImmediately() {
    List<List<ResolvedCall>> batches = new ArrayList<>();
    // 闸链代批（AutoApproveGate 形状的桩）：命中即终局，不登记、不碰通道
    ApprovalGate autoApprove =
        new ApprovalGate() {
          @Override
          public String name() {
            return "case-auto-approve";
          }

          @Override
          public Optional<ApprovalDecision> decide(ApprovalRequest request) {
            return Optional.of(ApprovalDecision.APPROVE_ONCE);
          }
        };
    startWithGates(
        List.of(autoApprove),
        Duration.ofSeconds(30),
        Optional.of(
            (id, calls) -> {
              batches.add(List.copyOf(calls));
              return committed(id, calls.size());
            }),
        true);
    PendingDecisionQueue queue = queue("main");
    queue.register(
        "bash", Map.of("command", "sudo apt install x"), ask("bash:ask:apt", AskKind.MODE_LIMITED));

    Optional<DecisionPackage> settled = bridge.flush(queue);

    assertThat(settled.orElseThrow().state())
        .as("闸链批准 ⇒ 无人在环 ⇒ 当场执行（自动路径不该白等一个不存在的人）")
        .isEqualTo(PackageState.COMMITTED);
    assertThat(batches).as("被批子集恰好提交一次").hasSize(1);
    assertThat(channel.awaitCalls).as("闸链命中 ⇒ 人一次都没被问（人不在环里）").hasValue(0);
    assertThat(channel.published).as("审批面上没有登记项 ⇒ 没有人能看到这一包、也没人能敲执行命令").isEmpty();
    assertThat(pending.pending()).as("登记表里同样一条都没有").isEmpty();
  }

  // ---------- B-3：冲突整批退回 ----------

  @Test
  void hostConflictRollsTheWholeBatchBackAndEmitsConflictedWithoutAnEmptyRevisionRef()
      throws Exception {
    List<List<ResolvedCall>> batches = new ArrayList<>();
    start(
        Duration.ofSeconds(30),
        Optional.of(
            (id, calls) -> {
              batches.add(List.copyOf(calls));
              return new CommitReceipt(
                  CommitReceipt.Status.CONFLICTED,
                  null,
                  List.of(
                      CommitReceipt.ItemOutcome.NOT_COMMITTED,
                      CommitReceipt.ItemOutcome.NOT_COMMITTED),
                  "当前版本摘要：revision=7");
            }),
        true,
        Optional.empty());
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    PendingDecisionQueue queue = queue("main");
    queue.register("doc_edit", Map.of("path", "a.md"), ask("docs:ask:a", AskKind.MODE_LIMITED));
    queue.register("doc_edit", Map.of("path", "b.md"), ask("docs:ask:b", AskKind.MODE_LIMITED));

    Optional<DecisionPackage> settled = bridge.flush(queue);

    assertThat(settled.orElseThrow().state())
        .as("人批的键 = 执行命令 ⇒ flush 返回时整批一次提交已被退回（CONFLICTED）")
        .isEqualTo(PackageState.CONFLICTED);
    assertThat(batches).as("整批一次提交后被退回（不是逐条重试）").hasSize(1);
    Event conflicted = awaitEvent(DecisionEventTypes.CONFLICTED, Duration.ofSeconds(5));
    JsonNode payload = JSON.readTree(conflicted.payload());
    assertThat(payload.get("revisionRef").asText()).as("冲突档没有可对账的引用 ⇒ 键在、值如实为空（不编一个）").isEmpty();
    assertThat(payload.get("outcomes")).hasSize(2);
    assertThat(payload.get("outcomes").get(0).get("outcome").asText()).isEqualTo("NOT_COMMITTED");
    assertThat(events.stream().map(Event::type).toList())
        .as("冲突不是提交：不发 committed")
        .doesNotContain(DecisionEventTypes.COMMITTED);
  }

  // ---------- 密钥纪律（事件载荷） ----------

  @Test
  void eventPayloadsNeverCarryArgumentPlaintext() throws Exception {
    start(Duration.ofSeconds(30), Optional.empty(), true, Optional.empty());
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    PendingDecisionQueue queue = queue("main");
    queue.register("http_probe", Map.of("token", SECRET), ask("net:ask", AskKind.SENSITIVE));

    bridge.flush(queue);

    Event proposed = awaitEvent(DecisionEventTypes.PROPOSED, Duration.ofSeconds(5));
    Event decided = awaitEvent(DecisionEventTypes.DECIDED, Duration.ofSeconds(5));
    assertThat(events).isNotEmpty();
    for (Event event : List.copyOf(events)) {
      assertThat(event.payload()).as("事件面只留摘要：明文一个字节都不许出现（红线 6 / §4 不变量 6）").doesNotContain(SECRET);
    }
    assertThat(JSON.readTree(proposed.payload()).get("digests").get(0).asText()).isNotEmpty();
    assertThat(JSON.readTree(decided.payload()).get("proposerKey").asText()).isEqualTo("main");
  }
}
