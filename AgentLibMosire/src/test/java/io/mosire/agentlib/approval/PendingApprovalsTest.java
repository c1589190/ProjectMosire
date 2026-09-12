package io.mosire.agentlib.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 登记表用例：幂等决议（T9）、阻塞等待真的会醒（条件变量）、过期三处口径一致、会话键是二元组。
 *
 * <p>等待相关断言的<b>量级</b>是拉开了的（决议在 ~120 ms 写入，等待预算 3 s）：判"被叫醒 vs 等预算用完"， 两头差近 10 倍，不咬具体毫秒数。
 */
class PendingApprovalsTest {

  private static final String CALLER = "DEFAULT";
  private static final String TOOL = "bash";
  private static final String CLASS_KEY = "bash:ask:systemctl";

  private static ApprovalRequest submitFresh(PendingApprovals pending, long deadlineEpochMs) {
    return pending.submitRequest(CALLER, TOOL, CLASS_KEY, "给人看的一行", "sha256:abcd", deadlineEpochMs);
  }

  @Test
  void secondDecisionIsIgnoredAndNeverOverwritesTheFirst() {
    PendingApprovals pending = new PendingApprovals();
    ApprovalRequest req = submitFresh(pending, System.currentTimeMillis() + 5_000L);

    assertThat(req.id()).startsWith("ap-");
    assertThat(req.callerKey()).isEqualTo(CALLER); // 调用者键由宿主填，登记表原样留着（会话归类要它）
    assertThat(pending.pending()).hasSize(1);

    assertThat(pending.decide(req.id(), ApprovalDecision.APPROVE_ONCE, "tty")).isTrue();
    // ★ 判别性：同 id 第二次决议 false 且不覆盖首次（改"幂等"为"后到者胜"，本条转红）
    assertThat(pending.decide(req.id(), ApprovalDecision.DENY, "http")).isFalse();
    assertThat(pending.await(req.id(), Duration.ofMillis(200)))
        .contains(ApprovalDecision.APPROVE_ONCE);
    // 已决议 = 不再是"待裁决"，但在过期前仍取得到（B2 靠它把 404 与 409 分开）
    assertThat(pending.pending()).isEmpty();
    assertThat(pending.get(req.id())).isPresent();
  }

  @Test
  void awaitBlocksThenWakesOnADecisionFromAnotherThread() {
    PendingApprovals pending = new PendingApprovals();
    ApprovalRequest req = submitFresh(pending, System.currentTimeMillis() + 5_000L);
    AtomicReference<Optional<ApprovalDecision>> observed = new AtomicReference<>();
    AtomicLong waitedMs = new AtomicLong(-1L);

    Thread waiter =
        Thread.ofVirtual()
            .start(
                () -> {
                  long startedNanos = System.nanoTime();
                  Optional<ApprovalDecision> got = pending.await(req.id(), Duration.ofSeconds(3));
                  waitedMs.set((System.nanoTime() - startedNanos) / 1_000_000L);
                  observed.set(got);
                });

    try {
      Thread.sleep(120L);
      // ① 决议还没来 = 它还没返回（改成"立即返回空"这条转红）
      assertThat(waiter.isAlive()).isTrue();
      assertThat(observed.get()).isNull();

      assertThat(pending.decide(req.id(), ApprovalDecision.APPROVE_ONCE, "tty")).isTrue();
      waiter.join(4_000L);
      // ② 拿到的是真决议，不是"到点没人答"的空
      assertThat(observed.get()).contains(ApprovalDecision.APPROVE_ONCE);
      // ③ ★ 判别力所在：决议在 ~120 ms 写入，等待方要在 1 s 内<被叫醒>——去掉 signalAll 它只能在预算
      //    （3 s）到点时醒，本行转红（注意：读到的值仍然正确，只有耗时能区分"被唤醒"与"等预算用完"）
      assertThat(waitedMs.get()).isLessThan(1_000L);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("用例被中断", interrupted);
    }
  }

  @Test
  void expiredRequestsDisappearFromEveryReadPath() {
    PendingApprovals pending = new PendingApprovals();
    long now = System.currentTimeMillis();
    ApprovalRequest expiring = submitFresh(pending, now + 60L);
    assertThat(pending.pending()).hasSize(1); // 先在：下面它"不在"不能是"压根没登记"

    assertThat(pending.await(expiring.id(), Duration.ofMillis(300))).isEmpty(); // 到点没人答（不是放行）
    sleep(120L);
    assertThat(pending.pending()).isEmpty();
    assertThat(pending.get(expiring.id())).isEmpty();
    assertThat(pending.decide(expiring.id(), ApprovalDecision.APPROVE_ONCE, "tty")).isFalse();

    // ★ 对照：同一张表，未过期的请求三个读路径都在（证明上面"不在"来自过期，不是表坏了）
    ApprovalRequest fresh = submitFresh(pending, System.currentTimeMillis() + 5_000L);
    assertThat(pending.pending()).hasSize(1);
    assertThat(pending.get(fresh.id())).isPresent();
    assertThat(pending.decide(fresh.id(), ApprovalDecision.APPROVE_ONCE, "tty")).isTrue();
    assertThat(pending.await(fresh.id(), Duration.ofMillis(200)))
        .contains(ApprovalDecision.APPROVE_ONCE);
  }

  @Test
  void dropRemovesTheEntryAndWakesTheWaiterImmediately() {
    PendingApprovals pending = new PendingApprovals();
    ApprovalRequest req = submitFresh(pending, System.currentTimeMillis() + 5_000L);
    AtomicReference<Optional<ApprovalDecision>> observed = new AtomicReference<>();
    AtomicLong waitedMs = new AtomicLong(-1L);

    Thread waiter =
        Thread.ofVirtual()
            .start(
                () -> {
                  long startedNanos = System.nanoTime();
                  Optional<ApprovalDecision> got = pending.await(req.id(), Duration.ofSeconds(3));
                  waitedMs.set((System.nanoTime() - startedNanos) / 1_000_000L);
                  observed.set(got);
                });

    try {
      Thread.sleep(120L);
      assertThat(waiter.isAlive()).isTrue();

      // 摘除 = "这条已经不可能再有消费者"：等待者按"没人答"返回空（不是放行），而且是立刻醒
      assertThat(pending.drop(req.id())).isTrue();
      waiter.join(4_000L);
      assertThat(observed.get()).isEmpty();
      // ★ 判别性：决议/摘除都在 ~120 ms 发生，等待方要在 1 s 内被叫醒——去掉 drop 里的 signalAll，
      //    它只能在 3 s 预算到点时醒（读到的值仍然正确，只有耗时会变），本行转红
      assertThat(waitedMs.get()).isLessThan(1_000L);
      assertThat(pending.get(req.id())).isEmpty();
      assertThat(pending.pending()).isEmpty();
      // 摘掉之后人再来点"批准"：false（B2 据此返 404/409，而不是 200 一个没有消费者的决定）
      assertThat(pending.decide(req.id(), ApprovalDecision.APPROVE_ONCE, "tty")).isFalse();
      // 幂等：再摘一次 false（本来就没有）
      assertThat(pending.drop(req.id())).isFalse();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("用例被中断", interrupted);
    }
  }

  @Test
  void unknownIdIsNotDecidableAndReadsEmpty() {
    PendingApprovals pending = new PendingApprovals();
    assertThat(pending.decide("ap-nope", ApprovalDecision.APPROVE_ONCE, "tty")).isFalse();
    assertThat(pending.get("ap-nope")).isEmpty();
    assertThat(pending.await("ap-nope", Duration.ofMillis(50))).isEmpty();
  }

  @Test
  void sessionGrantIsKeyedByCallerTimesClassKey() {
    PendingApprovals pending = new PendingApprovals();
    pending.grantSession(CALLER, CLASS_KEY);

    assertThat(pending.isSessionGranted(CALLER, CLASS_KEY)).isTrue();
    // ★ 判别性：键改成工具名/全局，两行立刻转红
    assertThat(pending.isSessionGranted("SYSTEM", CLASS_KEY)).isFalse(); // 换调用者仍要问
    assertThat(pending.isSessionGranted(CALLER, "bash:ask:apt")).isFalse(); // 换 classKey 仍要问
    // 查询口径：空白/空 = false（不抛）
    assertThat(pending.isSessionGranted(null, CLASS_KEY)).isFalse();
    assertThat(pending.isSessionGranted(" ", CLASS_KEY)).isFalse();
    assertThat(pending.isSessionGranted(CALLER, "")).isFalse();
    // 记录口径：空白键在构造期就响亮拒（退化成全局放行的入口不留）
    assertThatThrownBy(() -> pending.grantSession(" ", CLASS_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pending.grantSession(CALLER, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void submitMintsBlankIdsAndKeepsTheFirstEntryForAnId() {
    PendingApprovals pending = new PendingApprovals();
    long now = System.currentTimeMillis();
    ApprovalRequest blankId =
        new ApprovalRequest("", TOOL, CLASS_KEY, "第一条", "sha256:aaaa", now, now + 5_000L, CALLER);

    String minted = pending.submit(blankId);
    assertThat(minted).startsWith("ap-");
    assertThat(pending.get(minted).orElseThrow().id()).isEqualTo(minted); // 补发的 id 真进了登记项

    // 同 id 重复登记保留首条（登记是幂等的：同一次请求不该变成两条待裁决）
    ApprovalRequest duplicate =
        new ApprovalRequest(
            minted, TOOL, CLASS_KEY, "第二条", "sha256:bbbb", now, now + 5_000L, CALLER);
    pending.submit(duplicate);
    assertThat(pending.pending()).hasSize(1);
    assertThat(pending.get(minted).orElseThrow().summary()).isEqualTo("第一条");
  }

  /**
   * {@code submit} 首行的 prune 有一个<b>完全不经读路径</b>的观测面：新登记的 id 撞上一条<b>已过期</b>的登记项时，
   * 留下的是"新那条"（过期那条已被清）还是"旧那条"（没清 ⇒ {@code putIfAbsent} 把它保住了）。
   *
   * <p>这条补的正是 review2 靶 4 证伪的那半句：F7 修复<b>不是</b>"无判别性且不可造"——造法就是本用例。
   *
   * <p><b>诚实边界</b>：本条证的是"submit 会清过期项"，<b>不</b>证"表大小有上限"（那个面只能反射读私有字段，脆，故不测）； 过期项的清理仍主要靠读路径，{@code
   * submit} 这次是兜底。
   */
  @Test
  void submitPrunesExpiredEntriesEvenWithNoReadPathInBetween() {
    PendingApprovals pending = new PendingApprovals();
    long now = System.currentTimeMillis();
    ApprovalRequest stale =
        new ApprovalRequest(
            "ap-same",
            TOOL,
            CLASS_KEY,
            "已过期那条",
            "sha256:aaaa",
            now - 60_000L,
            now - 59_000L,
            CALLER);
    ApprovalRequest fresh =
        new ApprovalRequest(
            "ap-same", TOOL, CLASS_KEY, "未过期那条", "sha256:bbbb", now, now + 5_000L, CALLER);

    pending.submit(stale);
    pending.submit(fresh); // 同 id：过期那条若没被清，它就顶在前面，新那条进不去

    // ★ 判别性：去掉 submit 首行的 prune ⇒ 这条被过期项占住，`get` 走到"过期即摘"把 id 清空，本行转红
    assertThat(pending.get("ap-same")).isPresent();
    assertThat(pending.get("ap-same").orElseThrow().summary()).isEqualTo("未过期那条");
    // 由此它仍可被人裁决（登记项还活着，不是"看着在、点不动"）
    assertThat(pending.decide("ap-same", ApprovalDecision.APPROVE_ONCE, "tty")).isTrue();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("用例被中断", interrupted);
    }
  }
}
