package io.mosire.agentlib.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 「当前回合的待裁决队列」取数口的判据（设计 §5 裁定 1+2 的装配缝；{@code ToolCallAuthorizer} 的 {@code Ask} 分支靠它分流）。
 *
 * <p><b>为什么这些用例必须存在</b>：这条缝决定"这次 {@code Ask} 是进包、还是走既有单条审批闸"。它错在两处： ①
 * 没绑定也当成有队列（未接线装配下敏感调用会悄悄不执行——最贵的假绿）；② 收尾时无脑清别人的绑定 （MCP 桥线程/下一轮被连坐，表现为"有时进包、有时当场问人"的间歇态）。
 *
 * <p><b>判别力（改坏哪里它会红）</b>：
 *
 * <ul>
 *   <li>把 {@link DecisionTurnScope#current()} 的"没绑定 ⇒ 空"改成"没绑定 ⇒ 新建一个" ⇒ {@link
 *       #anUnboundThreadHasNoQueueSoAskKeepsTheOldPath()}（以及子线程那条）红；
 *   <li>把 {@link DecisionTurnScope#clear(PendingDecisionQueue)} 的 CAS 改成无条件 {@code clear()} ⇒
 *       {@link #clearByIdentityNeverTouchesSomebodyElsesBinding()} 红。
 * </ul>
 */
class DecisionTurnScopeTest {

  private static PendingDecisionQueue queue(String key) {
    return new PendingDecisionQueue(key, AccessToken.DEFAULT);
  }

  @AfterEach
  void tearDown() {
    DecisionTurnScope.clear();
  }

  @Test
  void anUnboundThreadHasNoQueueSoAskKeepsTheOldPath() {
    assertThat(DecisionTurnScope.current())
        .as("没绑定 ⇒ 空值 = '这一轮不收集待裁决项'，Ask 走既有审批闸（回归安全网）")
        .isEmpty();
  }

  @Test
  void bindReturnsTheQueueItBoundAndClearByIdentityUnbindsIt() {
    PendingDecisionQueue mine = queue("sub-1");

    assertThat(DecisionTurnScope.bind(mine)).as("bind 返回刚绑上的那一个（收尾的身份守卫）").isSameAs(mine);
    assertThat(DecisionTurnScope.current()).contains(mine);

    assertThat(DecisionTurnScope.clear(mine)).as("清自己绑的那一个 ⇒ 成功").isTrue();
    assertThat(DecisionTurnScope.current()).isEmpty();
    // ★ 2026-10-16 更新（RT 报的 §5-3 已被控制方按 javadoc 修正）：清到空时实现会一并移除 ThreadLocal 条目，
    //   于是"重复清"仍回答 true —— 与 `clear(PendingDecisionQueue)` 的幂等语义一致（"本来就没绑定也算清干净了"）。
    //   本断言因此从"记录旧行为（false）"改为"钉住修正后的契约（true）"。
    assertThat(DecisionTurnScope.clear(mine)).as("重复清 ⇒ 幂等：已经没绑定也算清干净（修正后契约）").isTrue();
    assertThat(DecisionTurnScope.current()).as("但结果仍是干净的").isEmpty();
  }

  @Test
  void clearByIdentityNeverTouchesSomebodyElsesBinding() {
    PendingDecisionQueue nextRound = queue("sub-1");
    PendingDecisionQueue mine = queue("sub-2");
    DecisionTurnScope.bind(nextRound);

    assertThat(DecisionTurnScope.clear(mine)).as("当前绑的是别人的 ⇒ 什么都不做（下一轮已经开始，别连坐）").isFalse();
    assertThat(DecisionTurnScope.current()).as("别人的绑定还在").contains(nextRound);
  }

  @Test
  void theScopeIsAnchoredToTheBindingThreadAndNotInherited() throws Exception {
    PendingDecisionQueue bound = queue("main");
    DecisionTurnScope.bind(bound);
    AtomicReference<Optional<PendingDecisionQueue>> seenElsewhere = new AtomicReference<>();

    Thread other = new Thread(() -> seenElsewhere.set(DecisionTurnScope.current()));
    other.start();
    other.join();

    assertThat(seenElsewhere.get()).as("别的线程（MCP 桥线程/工具起的线程）看不到本轮的队列 ⇒ 它退回既有行为，不静默丢调用").isEmpty();
    assertThat(DecisionTurnScope.current()).as("本线程的绑定不受影响").contains(bound);
  }

  @Test
  void rebindingReplacesTheCurrentQueue() {
    PendingDecisionQueue first = queue("sub-1");
    PendingDecisionQueue second = queue("sub-1");
    DecisionTurnScope.bind(first);

    DecisionTurnScope.bind(second);

    assertThat(DecisionTurnScope.current()).contains(second);
    assertThat(DecisionTurnScope.clear(first)).as("旧绑定已经不是当前的那个").isFalse();
    assertThat(DecisionTurnScope.current()).contains(second);
  }
}
