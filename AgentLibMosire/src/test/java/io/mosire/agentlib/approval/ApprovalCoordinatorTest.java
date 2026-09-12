package io.mosire.agentlib.approval;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.permission.AccessToken;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 编排器自身的判定顺序用例（不经 authorizer，直接打 {@link ApprovalCoordinator#decide}）。
 *
 * <p>判别力来源与 {@code ToolCallAuthorizerApprovalTest} 同：桩通道计数是确定性证据，事件面只作佐证。
 */
class ApprovalCoordinatorTest {

  private static final String CALLER = "DEFAULT";
  private static final String TOOL = "bash";

  private EventBus bus;
  private EventSink events;

  @BeforeEach
  void subscribeBus() {
    bus = new EventBus();
    events = new EventSink(bus);
  }

  @AfterEach
  void releaseBus() {
    events.close();
    bus.close();
  }

  private static ApprovalRequest request(String id, String classKey) {
    return request(id, classKey, CALLER);
  }

  private static ApprovalRequest request(String id, String classKey, String callerKey) {
    long now = System.currentTimeMillis();
    return new ApprovalRequest(
        id, TOOL, classKey, "给人看的一行", "sha256:abcd", now, now + 5_000L, callerKey);
  }

  /** 本组用例都接同一条总线的编排器（{@code bus == null} 的形态单独在下面那条用例里建）。 */
  private ApprovalCoordinator coordinator(
      PendingApprovals pending,
      List<ApprovalGate> gates,
      List<ApprovalChannel> channels,
      Duration timeout) {
    return new ApprovalCoordinator(gates, channels, pending, timeout, bus);
  }

  @Test
  void gateChainFirstNonEmptyWinsAndStopsBeforeTheChannels() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty = new StubChannel("tty").thenAnswer(ApprovalDecision.APPROVE_ONCE);
    ApprovalRequest blocked = request("ap-black", "bash:blocked:rm-rf");

    // ① 链首黑名单命中 ⇒ DENY，通道一次没碰
    ApprovalCoordinator withBlacklist =
        coordinator(
            pending,
            List.of(
                new DenyGate(r -> r.classKey().startsWith("bash:blocked")),
                new AutoApproveGate(pending),
                new ConfirmGate()),
            List.of(tty),
            Duration.ofSeconds(2));
    assertThat(withBlacklist.decide(blocked)).isEqualTo(ApprovalDecision.DENY);
    assertThat(tty.availableCalls()).isZero();
    assertThat(tty.publishCalls()).isZero();
    assertThat(pending.pending()).isEmpty();

    // ② 会话已放行 ⇒ 链上第二个闸命中，通道仍没碰（positive control：闸链确实能放行）
    //    用唯一身份（SYSTEM）：会话级放行只对它成立，否则编排器按 fail-closed 收窄降级为 once（见
    //    sessionApprovalFromANonUniqueIdentityDegradesToOnce），链上的 APPROVE_SESSION 就透不出来了
    pending.grantSession(AccessToken.SYSTEM.name(), "bash:ask:systemctl");
    assertThat(
            withBlacklist.decide(
                request("ap-session", "bash:ask:systemctl", AccessToken.SYSTEM.name())))
        .isEqualTo(ApprovalDecision.APPROVE_SESSION);
    assertThat(tty.availableCalls()).isZero();

    // ③ 快判链都不命中 ⇒ 才落到通道（同一夹具，通道确实活着）
    assertThat(withBlacklist.decide(request("ap-ask", "bash:ask:apt")))
        .isEqualTo(ApprovalDecision.APPROVE_ONCE);
    assertThat(tty.awaitCalls()).isEqualTo(1);
  }

  @Test
  void configuredBlacklistBeatsAStaleSessionGrant() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty = new StubChannel("tty").thenAnswer(ApprovalDecision.APPROVE_ONCE);
    // 唯一身份（SYSTEM）才可能真的持有会话放行：换 DEFAULT 会被 fail-closed 收窄降级，链上的 SESSION 透不出来
    pending.grantSession(AccessToken.SYSTEM.name(), "bash:ask:curl-sh"); // "上次同意过"
    ApprovalRequest blocked = request("ap-1", "bash:ask:curl-sh", AccessToken.SYSTEM.name());
    DenyGate blacklist = new DenyGate(r -> r.classKey().equals("bash:ask:curl-sh"));

    // 链顺序 = 优先级：黑名单在前 ⇒ 陈旧的会话放行压不过配置黑名单
    ApprovalCoordinator configFirst =
        coordinator(
            pending,
            List.of(blacklist, new AutoApproveGate(pending), new ConfirmGate()),
            List.of(tty),
            Duration.ofSeconds(2));
    assertThat(configFirst.decide(blocked)).isEqualTo(ApprovalDecision.DENY);
    assertThat(tty.availableCalls()).isZero();

    // ★ 对照：同一授权、同一黑名单，只把链顺序倒过来 ⇒ 会话放行胜出（证明"黑名单赢"来自顺序，不是因为会话没生效）
    ApprovalCoordinator sessionFirst =
        coordinator(
            pending,
            List.of(new AutoApproveGate(pending), blacklist, new ConfirmGate()),
            List.of(tty),
            Duration.ofSeconds(2));
    assertThat(sessionFirst.decide(blocked)).isEqualTo(ApprovalDecision.APPROVE_SESSION);
    assertThat(tty.availableCalls()).isZero();
  }

  @Test
  void noAvailableChannelDeniesImmediatelyAndQuietly() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty = new StubChannel("tty").available(false);
    StubChannel http =
        new StubChannel("http").available(false).thenAnswer(ApprovalDecision.APPROVE_ONCE);
    ApprovalCoordinator none =
        coordinator(
            pending,
            List.of(new AutoApproveGate(pending), new ConfirmGate()),
            List.of(tty, http),
            Duration.ofSeconds(5)); // 超时给 5 s：本条若"挂到超时"就会转红

    long startedNanos = System.nanoTime();
    ApprovalDecision decision = none.decide(request("ap-1", "bash:ask:systemctl"));
    long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

    assertThat(decision).isEqualTo(ApprovalDecision.DENY);
    assertThat(tty.publishCalls()).isZero();
    assertThat(http.publishCalls()).isZero();
    assertThat(tty.awaitCalls()).isZero();
    assertThat(http.awaitCalls()).isZero();
    assertThat(pending.pending()).isEmpty();
    assertThat(elapsedMs).isLessThan(1_000L);
    assertThat(events.quietWithout(ApprovalEventTypes.REQUESTED, Duration.ofMillis(300))).isTrue();
    Event decided =
        events.awaitType(ApprovalEventTypes.DECIDED, Duration.ofSeconds(2)).orElseThrow();
    assertThat(ApprovalStubs.payload(decided).get("by").asText())
        .isEqualTo(ApprovalCoordinator.SOURCE_NO_CHANNEL);

    // ★ 对照：同一夹具有通道（http 可用且有人答）⇒ 放行（证明"拒"来自无可用通道，不是夹具坏）
    http.available(true);
    assertThat(none.decide(request("ap-2", "bash:ask:systemctl")))
        .isEqualTo(ApprovalDecision.APPROVE_ONCE);
    assertThat(http.awaitCalls()).isEqualTo(1);
    assertThat(tty.awaitCalls()).isZero();
  }

  @Test
  void twoChannelsSeeTheSameRequestAndTheAnswererIsNamed() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty = new StubChannel("tty").thenSilence();
    StubChannel http = new StubChannel("http").thenAnswer(ApprovalDecision.APPROVE_ONCE);
    ApprovalCoordinator coordinator =
        coordinator(
            pending,
            List.of(new AutoApproveGate(pending), new ConfirmGate()),
            List.of(tty, http),
            Duration.ofSeconds(2));

    assertThat(coordinator.decide(request("ap-42", "bash:ask:systemctl")))
        .isEqualTo(ApprovalDecision.APPROVE_ONCE);

    // 两条通道看到同一 id（人从哪条答都算同一次）
    assertThat(tty.published()).hasSize(1);
    assertThat(http.published()).hasSize(1);
    assertThat(tty.published().get(0).id()).isEqualTo("ap-42");
    assertThat(http.published().get(0).id()).isEqualTo("ap-42");

    Event decided =
        events.awaitType(ApprovalEventTypes.DECIDED, Duration.ofSeconds(2)).orElseThrow();
    assertThat(ApprovalStubs.payload(decided).get("by").asText()).isEqualTo("http");
    assertThat(ApprovalStubs.payload(decided).get("scope").asText()).isEqualTo("once");
  }

  @Test
  void nullRequestAndNullBusAreHandledWithoutNpe() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty = new StubChannel("tty").thenAnswer(ApprovalDecision.APPROVE_ONCE);
    // bus == null 合法：不落事件，判定语义逐字不变
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(new AutoApproveGate(pending), new ConfirmGate()),
            List.of(tty),
            pending,
            Duration.ofSeconds(2),
            null);

    assertThat(coordinator.decide(null)).isEqualTo(ApprovalDecision.DENY);
    assertThat(tty.availableCalls()).isZero();
    assertThat(coordinator.decide(request("ap-1", "bash:ask:systemctl")))
        .isEqualTo(ApprovalDecision.APPROVE_ONCE);
  }

  // ------------------------------------------------- 窗口（F2）：等待上界真的传到通道

  @Test
  void pendingBackedChannelWaitsOutTheWholeWindowWhenNobodyAnswers() {
    PendingApprovals pending = new PendingApprovals();
    Duration window = Duration.ofMillis(400);
    PendingBackedChannel tty = new PendingBackedChannel("tty", pending);
    ApprovalCoordinator coordinator =
        coordinator(
            pending,
            List.of(new AutoApproveGate(pending), new ConfirmGate()),
            List.of(tty),
            window);

    long startedNanos = System.nanoTime();
    ApprovalDecision decision = coordinator.decide(request("ap-nobody", "bash:ask:systemctl"));
    long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

    assertThat(decision).isEqualTo(ApprovalDecision.DENY);
    // 通道侧那次等待的记账写在它自己的 finally 里，而它是被编排器 finally 的 interrupt() 撤下之后才返回的
    // （晚于 decide 返回本线程几毫秒）：先有界地等这笔账落下来，别把"还没记"读成"没等满"
    long recordDeadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (System.nanoTime() < recordDeadlineNanos && tty.awaitReturns() == 0) {
      ApprovalStubs.sleepQuietly(2L);
    }
    assertThat(tty.awaitReturns()).isEqualTo(1);
    // ★ 判别性：<通道侧>真的把窗口等满了（编排器传给 await 的 wait 被改成 1 ms，本行转红）
    // （本机 200 次实测：min 397 ms / median 400 ms / max 402 ms，见探针记录）
    assertThat(tty.awaitElapsedMs()).isGreaterThanOrEqualTo((long) (window.toMillis() * 0.8));
    // 调用侧总耗时同样不短于窗口八成：编排器自己的预算口径（与上一条各自独立，改哪一边都能各自转红）
    assertThat(elapsedMs).isGreaterThanOrEqualTo((long) (window.toMillis() * 0.8));
    // 到点没人答 = 非人工终局：登记项不留幽灵（见 channelFailureLeavesNoOrphanInThePendingList）
    assertThat(pending.pending()).isEmpty();
    assertThat(pending.get("ap-nobody")).isEmpty();
    Event decided =
        events.awaitType(ApprovalEventTypes.DECIDED, Duration.ofSeconds(2)).orElseThrow();
    assertThat(ApprovalStubs.payload(decided).get("by").asText())
        .isEqualTo(ApprovalCoordinator.SOURCE_TIMEOUT);
  }

  // ------------------------------------------------- 人答完/到点后，别的通道等待要被撤掉（F3 的编排器侧）

  @Test
  void afterTheAnswerTheOtherChannelsWaitsAreInterrupted() {
    PendingApprovals pending = new PendingApprovals();
    // http 立刻答；tty 把等待交给登记表（没人从它那边答），窗口 3 s
    StubChannel http = new StubChannel("http").thenAnswer(ApprovalDecision.APPROVE_ONCE);
    PendingBackedChannel tty = new PendingBackedChannel("tty", pending);
    ApprovalCoordinator coordinator =
        coordinator(
            pending,
            List.of(new AutoApproveGate(pending), new ConfirmGate()),
            List.of(tty, http),
            Duration.ofSeconds(3));

    assertThat(coordinator.decide(request("ap-race", "bash:ask:systemctl")))
        .isEqualTo(ApprovalDecision.APPROVE_ONCE);

    // 等到 tty 那条等待真的结束（它没人答，只可能被 interrupt() 撤掉）；等不到就是"没被撤掉"
    long deadlineNanos = System.nanoTime() + Duration.ofMillis(600).toNanos();
    while (System.nanoTime() < deadlineNanos && tty.awaitReturns() == 0) {
      ApprovalStubs.sleepQuietly(5L);
    }
    // ★ 判别性：http 一答，编排器的 finally 就该 interrupt() 掉 tty 的等待线程——删掉那个 finally，tty 的等待
    //    会一直挂到窗口（3 s）才返回，本行读到的还是 0 ⇒ 转红
    assertThat(tty.awaitReturns()).isEqualTo(1);
    // 而且是被"立刻"撤掉的，不是等到窗口（3 s）到点：纳秒精度，不咬毫秒取整（那会变成看运气）
    assertThat(tty.awaitElapsedNanos()).isLessThan(Duration.ofMillis(500).toNanos());
  }

  // ------------------------------------------------- 非人工终局：登记项不许留幽灵（F4）

  @Test
  void channelFailureLeavesNoOrphanInThePendingList() throws InterruptedException {
    PendingApprovals pending = new PendingApprovals();
    StubChannel broken = new StubChannel("http").failingAwait();
    ApprovalCoordinator coordinator =
        coordinator(
            pending,
            List.of(new AutoApproveGate(pending), new ConfirmGate()),
            List.of(broken),
            Duration.ofMillis(200));

    long startedNanos = System.nanoTime();
    ApprovalDecision decision = coordinator.decide(request("ap-err", "bash:ask:systemctl"));
    long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

    assertThat(decision).isEqualTo(ApprovalDecision.DENY);
    assertThat(elapsedMs).isLessThan(1_000L); // 拒得很快 ⇒ 下面那两条断言面对的窗口还长着（本用例 deadline = now+5 s）
    // ★ 判别性：通道故障是非人工终局——编排器已经拒了，登记项若还留着，提示面就会展示一条"点了批准也没有消费者"的
    //   幽灵项（评审探针：decide 0 ms 返拒，待裁决里留着 1 条、最长 300 s）。删掉编排器的 drop 调用，本段转红。
    assertThat(pending.pending()).isEmpty();
    assertThat(pending.get("ap-err")).isEmpty();

    // ★ 反面对照：同一夹具、同一 classKey，人来答"本次放行"⇒ 登记项<b>保留</b>（B2 靠 get 把"未知 id"与"重复决议"分开）——
    //   证明上面的"空"来自 drop 这条非人工终局路径，不是登记表整体不记账。
    //   人走登记表答复（真实通道的答复就是这么产生的：decide 写决议、通道的 await 被唤醒），
    //   所以这里用 PendingBackedChannel 而不是脚本桩——脚本桩的答复不经过 decide，登记项会停在"未决议"。
    PendingApprovals answered = new PendingApprovals();
    PendingBackedChannel tty = new PendingBackedChannel("tty", answered);
    ApprovalCoordinator working =
        coordinator(
            answered,
            List.of(new AutoApproveGate(answered), new ConfirmGate()),
            List.of(tty),
            Duration.ofSeconds(2));
    Thread human = humanAnswering(tty, answered, ApprovalDecision.APPROVE_ONCE);
    assertThat(working.decide(request("ap-ok", "bash:ask:systemctl")))
        .isEqualTo(ApprovalDecision.APPROVE_ONCE);
    human.join(2_000L);
    assertThat(answered.pending()).isEmpty(); // 已决议 = 不再是"待裁决"
    assertThat(answered.get("ap-ok")).isPresent(); // 但它仍取得到（人答过的项不被摘掉）
  }

  /** 起一个"人"：通道把请求贴出来后，把答复写进登记表（真实通道的答复就是这么来的）。 */
  private static Thread humanAnswering(
      PendingBackedChannel channel, PendingApprovals pending, ApprovalDecision decision) {
    return Thread.ofVirtual()
        .start(
            () -> {
              ApprovalRequest published = channel.awaitPublished(Duration.ofSeconds(2));
              pending.decide(published.id(), decision, channel.name());
            });
  }

  // ------------------------------------------------- 会话级放行的 fail-closed 收窄（F5）

  @Test
  void sessionApprovalFromANonUniqueIdentityDegradesToOnce() {
    // 缺省谓词是字面量 "SYSTEM"；它与身份名的耦合钉在这里（AccessToken 改名/改成别的字面量，本条转红）
    assertThat(AccessToken.SYSTEM.name()).isEqualTo("SYSTEM");

    PendingApprovals pending = new PendingApprovals();
    StubChannel tty =
        new StubChannel("tty")
            .thenAnswer(ApprovalDecision.APPROVE_SESSION) // ① 人对 DEFAULT 桶说"本会话都行"
            .thenAnswer(ApprovalDecision.DENY) // ② 同桶的另一个实例再问一次，人这次拒
            .thenAnswer(ApprovalDecision.APPROVE_SESSION); // ③ 唯一身份（SYSTEM）拿"本会话"
    ApprovalCoordinator coordinator =
        coordinator(
            pending,
            List.of(new AutoApproveGate(pending), new ConfirmGate()),
            List.of(tty),
            Duration.ofSeconds(2));
    String classKey = "bash:ask:apt";

    // ① DEFAULT 桶（= 所有子 Agent 共用一个身份）：批准照跑，但不记会话键
    assertThat(coordinator.decide(request("ap-a", classKey, AccessToken.DEFAULT.name())))
        .isEqualTo(ApprovalDecision.APPROVE_ONCE);
    assertThat(tty.awaitCalls()).isEqualTo(1);
    assertThat(pending.isSessionGranted(AccessToken.DEFAULT.name(), classKey)).isFalse();

    // ★ ② 同一 callerKey 字符串、换了实例（桶粒度分不出来）：必须仍然问人——"子 Agent 之间不继承"这条红线
    //   不能靠一个假的不变式撑着（去掉收窄 ⇒ awaitCalls 停在 1、本段转红）
    assertThat(coordinator.decide(request("ap-b", classKey, AccessToken.DEFAULT.name())))
        .isEqualTo(ApprovalDecision.DENY);
    assertThat(tty.awaitCalls()).isEqualTo(2);

    // ★ ③ 正面对照：唯一身份（SYSTEM = 主 Agent 单实例）拿"本会话" ⇒ 记键、返回 SESSION
    assertThat(coordinator.decide(request("ap-c", classKey, AccessToken.SYSTEM.name())))
        .isEqualTo(ApprovalDecision.APPROVE_SESSION);
    assertThat(tty.awaitCalls()).isEqualTo(3);
    assertThat(pending.isSessionGranted(AccessToken.SYSTEM.name(), classKey)).isTrue();
    // ④ 唯一身份的同类调用不再问人（证明收窄是按身份，不是"会话级这条路整体坏了"）
    assertThat(coordinator.decide(request("ap-d", classKey, AccessToken.SYSTEM.name())))
        .isEqualTo(ApprovalDecision.APPROVE_SESSION);
    assertThat(tty.awaitCalls()).isEqualTo(3);
  }

  /**
   * 收窄规则的<b>唯一公开取法</b>——B2 的 HTTP 回执要报"实际生效的 scope"（人说的"本会话"未必等于生效的）， 没有这个口子，人面就只能自己复制 {@code
   * "SYSTEM"} 字面量，收窄一改响应就对说谎（review2 的 MED-1）。
   */
  @Test
  void effectiveDecisionIsTheSingleSourceOfTheNarrowingRule() {
    PendingApprovals pending = new PendingApprovals();
    StubChannel tty =
        new StubChannel("tty")
            .thenAnswer(ApprovalDecision.APPROVE_SESSION) // ① 人对 DEFAULT 桶说"本会话"
            .thenAnswer(ApprovalDecision.APPROVE_SESSION); // ② 人对唯一身份说"本会话"
    ApprovalCoordinator coordinator =
        coordinator(
            pending,
            List.of(new AutoApproveGate(pending), new ConfirmGate()),
            List.of(tty),
            Duration.ofSeconds(2));
    String classKey = "bash:ask:apt";

    // ★ 真值表（判别性：缺省谓词从 "SYSTEM"::equals 改成恒真 ⇒ 第 2 行转红；改成恒假 ⇒ 第 1 行转红）
    assertThat(
            coordinator.effectiveDecision(
                AccessToken.SYSTEM.name(), ApprovalDecision.APPROVE_SESSION))
        .isEqualTo(ApprovalDecision.APPROVE_SESSION);
    assertThat(
            coordinator.effectiveDecision(
                AccessToken.DEFAULT.name(), ApprovalDecision.APPROVE_SESSION))
        .isEqualTo(ApprovalDecision.APPROVE_ONCE);
    // 收窄只管 SESSION：其余决议原样透传（"顺手把 DENY 也收窄了"这种改法会在这里转红）
    assertThat(
            coordinator.effectiveDecision(
                AccessToken.DEFAULT.name(), ApprovalDecision.APPROVE_ONCE))
        .isEqualTo(ApprovalDecision.APPROVE_ONCE);
    assertThat(coordinator.effectiveDecision(AccessToken.DEFAULT.name(), ApprovalDecision.DENY))
        .isEqualTo(ApprovalDecision.DENY);

    // ★ "唯一实现"不变式：人答 SESSION 时 decide 的返回必须与该取法一致——
    //    若有人把降级规则重新写回 settle（两处实现），两处一旦分叉本行转红（谓词本身由上面真值表钉住）
    assertThat(coordinator.decide(request("ap-eff-1", classKey, AccessToken.DEFAULT.name())))
        .isEqualTo(
            coordinator.effectiveDecision(
                AccessToken.DEFAULT.name(), ApprovalDecision.APPROVE_SESSION));
    assertThat(coordinator.decide(request("ap-eff-2", classKey, AccessToken.SYSTEM.name())))
        .isEqualTo(
            coordinator.effectiveDecision(
                AccessToken.SYSTEM.name(), ApprovalDecision.APPROVE_SESSION));
  }

  // ------------------------------------------------- 生效 id（F6）：submit 补发的 id 才是事件/等待用的那个

  @Test
  void blankIdRequestIsAnsweredOnTheIdTheRegistryMinted() throws InterruptedException {
    PendingApprovals pending = new PendingApprovals();
    PendingBackedChannel tty = new PendingBackedChannel("tty", pending);
    ApprovalCoordinator coordinator =
        coordinator(
            pending,
            List.of(new AutoApproveGate(pending), new ConfirmGate()),
            List.of(tty),
            Duration.ofSeconds(3));

    // 人面只认登记表里的 id（B2 的 GET /api/approvals 就是这么拿 id 的）
    AtomicReference<String> minted = new AtomicReference<>();
    AtomicBoolean answered = new AtomicBoolean();
    Thread answerer =
        Thread.ofVirtual()
            .start(
                () -> {
                  long deadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                  while (System.nanoTime() < deadlineNanos && pending.pending().isEmpty()) {
                    ApprovalStubs.sleepQuietly(5L);
                  }
                  List<ApprovalRequest> open = pending.pending();
                  if (!open.isEmpty()) {
                    minted.set(open.get(0).id());
                    answered.set(
                        pending.decide(open.get(0).id(), ApprovalDecision.APPROVE_ONCE, "tty"));
                  }
                });

    // 请求 id 为空：只有登记表知道它最终叫什么
    ApprovalDecision decision = coordinator.decide(request("", "bash:ask:systemctl"));
    answerer.join(2_000L);
    assertThat(answered.get()).isTrue();

    // ★ 判别性：编排器必须拿 submit 补发的 id 去 publish/await/发事件——人就答在那个 id 上；
    //   继续用原（空）id 的话这次会一路挂到超时（DENY），本案转红
    assertThat(decision).isEqualTo(ApprovalDecision.APPROVE_ONCE);
    assertThat(minted.get()).isNotBlank();
    assertThat(tty.published()).hasSize(1);
    assertThat(tty.published().get(0).id()).isEqualTo(minted.get());
    Event requested =
        events.awaitType(ApprovalEventTypes.REQUESTED, Duration.ofSeconds(2)).orElseThrow();
    Event decided =
        events.awaitType(ApprovalEventTypes.DECIDED, Duration.ofSeconds(2)).orElseThrow();
    assertThat(ApprovalStubs.payload(requested).get("id").asText()).isEqualTo(minted.get());
    assertThat(ApprovalStubs.payload(decided).get("id").asText()).isEqualTo(minted.get());
    assertThat(requested.correlationId()).isEqualTo(minted.get());
    assertThat(decided.correlationId()).isEqualTo(minted.get());
  }

  // ------------------------------------------------- 通道没名字时 by 不落 JSON null（F9）

  @Test
  void channelWithoutANameStillGetsANameInTheEvent() {
    PendingApprovals pending = new PendingApprovals();
    ApprovalChannel unnamed =
        new ApprovalChannel() {
          @Override
          public String name() {
            return null;
          }

          @Override
          public boolean available() {
            return true;
          }

          @Override
          public void publish(ApprovalRequest req) {
            // 无外部面可外发：登记表就是它的呈现面
          }

          @Override
          public Optional<ApprovalDecision> await(String id, Duration wait) {
            // 契约示范（见 ApprovalChannel#await 的硬约束）：自带上界 + 响应中断，答"本次放行"
            try {
              Thread.sleep(Math.min(wait.toMillis(), 50L));
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              return Optional.empty();
            }
            return Optional.of(ApprovalDecision.APPROVE_ONCE);
          }
        };
    ApprovalCoordinator coordinator =
        coordinator(
            pending,
            List.of(new AutoApproveGate(pending), new ConfirmGate()),
            List.of(unnamed),
            Duration.ofSeconds(2));

    assertThat(coordinator.decide(request("ap-noname", "bash:ask:systemctl")))
        .isEqualTo(ApprovalDecision.APPROVE_ONCE);

    Event decided =
        events.awaitType(ApprovalEventTypes.DECIDED, Duration.ofSeconds(2)).orElseThrow();
    // ★ 判别性：name() == null 时 by 必须是确定的字符串，不是 JSON null（去掉兜底名，本段转红）
    assertThat(ApprovalStubs.payload(decided).get("by").isNull()).isFalse();
    assertThat(ApprovalStubs.payload(decided).get("by").asText())
        .isEqualTo(ApprovalCoordinator.UNNAMED_CHANNEL);
  }
}
