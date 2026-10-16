package io.mosire.agentlib.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 「本轮待裁决队列」的判据（设计 §5 裁定 1+2、P1 验收判据 ①②③；{@code 设计-决策包作为唯一决策单元.md}）。
 *
 * <p><b>为什么这些用例必须存在</b>：汇总包的整条链上，队列是"N 次 {@code Ask} ⇒ 一个包 N 条"的唯一收口点。 它的错法都是"少收/多收"：① 每次调用各建一包（N
 * 个包、各 1 条）；② 空队列也建包（零副作用那条被破坏）； ③ 登记后改 args 还能改到包里的条目（摘要与提交口看到的东西分叉）。三条各有用例钉着。
 *
 * <p><b>判别力（改坏哪里它会红）</b>：
 *
 * <ul>
 *   <li>把 {@link PendingDecisionQueue#toProposeRequest()} 改成"每条各产一个请求" ⇒ {@link
 *       #threeAskCallsBecomeOneProposalWithThreeItemsInRegistrationOrder()} 红；
 *   <li>把空队列那条早返回删掉（空队列也产出请求）⇒ {@link #anEmptyQueueYieldsNoProposalAtAll()} 红；
 *   <li>把 {@code register} 里的 {@code Map.copyOf(args)} 换成直接持有引用 ⇒ {@link
 *       #argsAreSnapshottedAtRegistration()} 红；
 *   <li>把 {@code ask.kind() == null} 的缺省从 {@link AskKind#SENSITIVE} 改成 {@link AskKind#MODE_LIMITED}
 *       ⇒ {@link #aToolThatDoesNotSayWhyFallsBackToSensitive()} 红。
 * </ul>
 */
class PendingDecisionQueueTest {

  private static final String PROPOSER = "sub-1";

  private static ToolGate.Ask ask(String classKey, AskKind kind) {
    return new ToolGate.Ask(classKey, "人读一行说明", kind);
  }

  @Test
  void threeAskCallsBecomeOneProposalWithThreeItemsInRegistrationOrder() {
    PendingDecisionQueue queue = new PendingDecisionQueue(PROPOSER, AccessToken.DEFAULT);
    queue.register(
        "bash", Map.of("command", "systemctl restart nginx"), ask("bash:ask:a", AskKind.SENSITIVE));
    queue.register(
        "doc_edit", Map.of("path", "a.md", "text", "x"), ask("docs:ask", AskKind.MODE_LIMITED));
    queue.register(
        "bash", Map.of("command", "rm -rf /tmp/x"), ask("bash:ask:b", AskKind.SENSITIVE));

    assertThat(queue.size()).as("三次调用都登记下来了").isEqualTo(3);
    long now = System.currentTimeMillis();
    Optional<ProposeRequest> request = queue.toProposeRequest(now);

    assertThat(request).as("一轮 ⇒ 恰好一个提议请求（不是三个）").isPresent();
    assertThat(request.orElseThrow().proposerKey()).isEqualTo(PROPOSER);
    assertThat(request.orElseThrow().caller()).isEqualTo(AccessToken.DEFAULT);
    assertThat(request.orElseThrow().deadlineEpochMs())
        .as("裁定 10：裁决窗口 = 收尾当刻 + 包的缺省 deadline")
        .isEqualTo(now + DecisionPackages.DEFAULT_DEADLINE_MILLIS);
    List<PackageItem> items = request.orElseThrow().items();
    assertThat(items).as("一个请求里装 N 条").hasSize(3);
    assertThat(items).extracting(PackageItem::index).containsExactly(0, 1, 2);
    assertThat(items)
        .extracting(PackageItem::toolName)
        .as("顺序 = 登记顺序（回执逐条 outcomes 与它同序）")
        .containsExactly("bash", "doc_edit", "bash");

    // 收到状态机里也仍然是一个包：条目数 = 3（"一个包 N 条"的端到端形状）
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage pkg = packages.propose(request.orElseThrow());
    assertThat(packages.pending()).as("本轮只建了一个包").hasSize(1);
    assertThat(pkg.items()).as("包内 N 条").hasSize(3);
    assertThat(pkg.state()).isEqualTo(PackageState.PROPOSED);
  }

  @Test
  void anEmptyQueueYieldsNoProposalAtAll() {
    PendingDecisionQueue queue = new PendingDecisionQueue(PROPOSER, AccessToken.DEFAULT);

    assertThat(queue.isEmpty()).as("一条都没登记").isTrue();
    assertThat(queue.size()).isZero();
    assertThat(queue.snapshot()).isEmpty();
    assertThat(queue.toProposeRequest(System.currentTimeMillis()))
        .as("空队列拿不到提议请求：建包是调用方的动作，空队列什么都不会发生")
        .isEmpty();
  }

  @Test
  void aQueueYieldsAtMostOneProposalAndRefusesLateRegistrations() {
    PendingDecisionQueue queue = new PendingDecisionQueue(PROPOSER, AccessToken.DEFAULT);
    queue.register("bash", Map.of("command", "whoami"), ask("bash:ask", AskKind.SENSITIVE));

    assertThat(queue.toProposeRequest(System.currentTimeMillis())).isPresent();
    assertThatThrownBy(() -> queue.toProposeRequest(System.currentTimeMillis()))
        .as("一个队列只收一个包（产出即作废）")
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                queue.register("bash", Map.of("command", "id"), ask("bash:ask", AskKind.SENSITIVE)))
        .as("收尾之后还登记 = 顺序 bug，必须响亮失败（不许静默丢一条待裁决调用）")
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void argsAreSnapshottedAtRegistration() {
    PendingDecisionQueue queue = new PendingDecisionQueue(PROPOSER, AccessToken.DEFAULT);
    Map<String, Object> mutable = new HashMap<>();
    mutable.put("command", "原始命令");
    queue.register("bash", mutable, ask("bash:ask", AskKind.SENSITIVE));

    mutable.put("command", "登记之后被改掉的命令");

    assertThat(queue.snapshot().get(0).args())
        .as("登记即快照：调用方改不动队列里那一条")
        .containsEntry("command", "原始命令");
    assertThat(
            queue.toProposeRequest(System.currentTimeMillis()).orElseThrow().items().get(0).args())
        .as("包里那条与队列里那条是同一份快照")
        .containsEntry("command", "原始命令");
  }

  @Test
  void digestAndSummaryAreDerivedAtRegistration() {
    PendingDecisionQueue queue = new PendingDecisionQueue(PROPOSER, AccessToken.DEFAULT);
    Map<String, Object> args = Map.of("token", "sk-live-SECRET123");
    queue.register("http_probe", args, ask("net:ask", AskKind.SENSITIVE));

    PackageItem item = queue.snapshot().get(0).packageItem();

    assertThat(item.argsDigest())
        .as("脱敏摘要在登记那一刻派生（与包层用同一个实现点）：事件面/日志只允许出现它")
        .isEqualTo(DecisionItemDigest.argsDigest(args))
        .doesNotContain("sk-live-SECRET123");
    assertThat(item.summary())
        .as("人读一行由 args 派生（裁定 6：不再是工具自报的 Ask.summary）；它进决策面，按 V12「肯定全文」口径")
        .isEqualTo(DecisionItemDigest.itemSummary("http_probe", args))
        .contains("http_probe");
  }

  @Test
  void aToolThatDoesNotSayWhyFallsBackToSensitive() {
    PendingDecisionQueue queue = new PendingDecisionQueue(PROPOSER, AccessToken.DEFAULT);
    queue.register("bash", Map.of("command", "whoami"), new ToolGate.Ask("bash:ask", "没写理由"));

    assertThat(queue.snapshot().get(0).askKind())
        .as("没有'为什么问'的料 ⇒ 按 SENSITIVE（永远到人），fail-closed")
        .isEqualTo(AskKind.SENSITIVE);
  }

  @Test
  void classKeyAndKindSurviveForTheCascadeToConsume() {
    PendingDecisionQueue queue = new PendingDecisionQueue(PROPOSER, AccessToken.DEFAULT);
    queue.register(
        "bash", Map.of("command", "sudo apt install x"), ask("bash:ask:apt", AskKind.MODE_LIMITED));

    PendingDecisionQueue.PendingEntry entry = queue.snapshot().get(0);

    assertThat(entry.classKey()).as("类键是会话放行归类的键，包内条目装不下它").isEqualTo("bash:ask:apt");
    assertThat(entry.askKind()).as("为什么问：决定这一条能不能被逐级预裁定").isEqualTo(AskKind.MODE_LIMITED);
    assertThat(entry.packageItem().index()).isZero();
  }

  @Test
  void blankProposerKeyIsRefusedAtConstruction() {
    assertThatThrownBy(() -> new PendingDecisionQueue("  ", AccessToken.DEFAULT))
        .as("proposerKey 是「一实例一未决包」与历史归属的键，空白 = 拒")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * <b>裁决窗口可注入</b>（2026-10-17 用户指令「太变态了直接等30分钟，改！」）：包 deadline 同时是"投递后等人答复"的 等待上限（裁定 10），写死成编译期常量 ⇒
   * 一条"没人答复"的用例会真挂 30 分钟。本用例钉住"注多少就是多少"。
   */
  @Test
  void injectedDeadlineWindowIsHonouredExactly() {
    long now = 1_700_000_000_000L;
    PendingDecisionQueue queue = new PendingDecisionQueue(PROPOSER, AccessToken.DEFAULT, 250L);
    queue.register("bash", Map.of("command", "x"), ask("bash:ask:x", AskKind.SENSITIVE));

    ProposeRequest request = queue.toProposeRequest(now).orElseThrow();

    assertThat(queue.deadlineMillis()).as("窗口就是注入的那个值").isEqualTo(250L);
    assertThat(request.deadlineEpochMs())
        .as("包 deadline = 收尾当刻 + 注入窗口（毫秒级，用例不必真等）")
        .isEqualTo(now + 250L);
  }

  /** 缺省窗口必须**逐字保持** 30 分钟（裁定 10 的原值）——注入能力不许改变既有行为。 */
  @Test
  void defaultDeadlineWindowStaysAtThirtyMinutes() {
    PendingDecisionQueue queue = new PendingDecisionQueue(PROPOSER, AccessToken.DEFAULT);
    queue.register("bash", Map.of("command", "x"), ask("bash:ask:x", AskKind.SENSITIVE));

    long now = 1_700_000_000_000L;

    assertThat(queue.deadlineMillis())
        .as("缺省 = DecisionPackages.DEFAULT_DEADLINE_MILLIS（30 分钟），行为逐字不变")
        .isEqualTo(DecisionPackages.DEFAULT_DEADLINE_MILLIS);
    assertThat(queue.toProposeRequest(now).orElseThrow().deadlineEpochMs())
        .isEqualTo(now + DecisionPackages.DEFAULT_DEADLINE_MILLIS);
  }

  /** 非法窗口（0/负）响亮拒——"窗口为零"会造出一建包即到点的假象，必须挡在构造期。 */
  @Test
  void nonPositiveDeadlineWindowIsRefused() {
    assertThatThrownBy(() -> new PendingDecisionQueue(PROPOSER, AccessToken.DEFAULT, 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PendingDecisionQueue(PROPOSER, AccessToken.DEFAULT, -1L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
