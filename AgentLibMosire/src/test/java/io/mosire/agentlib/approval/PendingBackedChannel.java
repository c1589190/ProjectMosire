package io.mosire.agentlib.approval;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "真通道"形态的桩：{@link #await} <b>把等待交给登记表</b>（{@code pending.await(id, wait)}），而不是照脚本立刻返回。
 *
 * <p><b>为什么必须有这样一个桩</b>：{@code StubChannel.await} 忽略自己的 {@code wait} 参数，于是"窗口/到点"这件事
 * 全由编排器自己的预算在撑——把编排器传给通道的窗口改成 1 ms，用例照样全绿（独立评审的 M2 变异实测 22/22 绿）。 本桩把 {@code wait} 真的用起来（人通过 {@link
 * PendingApprovals#decide} 答复、由条件变量唤醒， 与 B2 的 tty/HTTP 通道同构），于是"窗口到底生效没有"变成可观测的：{@link
 * #awaitElapsedMs()} 记下每次 await 实际等了多久。
 *
 * <p>它同时是本文件所在包对 {@link ApprovalChannel#await} 那条硬约束的示范实现：等待有上界（{@code wait}）、可被中断 （{@code
 * pending.await} 会响应中断并恢复中断位）、不自旋。
 */
final class PendingBackedChannel implements ApprovalChannel {

  private final String name;
  private final PendingApprovals pending;
  private final AtomicInteger availableCalls = new AtomicInteger();
  private final AtomicInteger publishCalls = new AtomicInteger();
  private final AtomicInteger awaitCalls = new AtomicInteger();
  private final AtomicInteger awaitReturns = new AtomicInteger();
  private final AtomicLong awaitElapsedNanos = new AtomicLong();
  private final List<ApprovalRequest> published = Collections.synchronizedList(new ArrayList<>());

  PendingBackedChannel(String name, PendingApprovals pending) {
    this.name = name;
    this.pending = pending;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean available() {
    availableCalls.incrementAndGet();
    return true;
  }

  @Override
  public void publish(ApprovalRequest req) {
    publishCalls.incrementAndGet();
    published.add(req);
  }

  @Override
  public Optional<ApprovalDecision> await(String id, Duration wait) {
    awaitCalls.incrementAndGet();
    long startedNanos = System.nanoTime();
    try {
      return pending.await(id, wait);
    } finally {
      // 记账在 finally：被 interrupt 撤掉的那次也会记（"谁把它的等待结束了"正靠这个观测）
      awaitElapsedNanos.addAndGet(System.nanoTime() - startedNanos);
      awaitReturns.incrementAndGet();
    }
  }

  /** 等人/提示面把请求贴出来（即 publish 之后）——给"人在窗口内答复"的用例当同步点；到点还没有抛错。 */
  ApprovalRequest awaitPublished(Duration limit) {
    long deadlineNanos = System.nanoTime() + limit.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      List<ApprovalRequest> snapshot = published();
      if (!snapshot.isEmpty()) {
        return snapshot.get(0);
      }
      try {
        Thread.sleep(5L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError("通道没有被 publish（等了 " + limit + "）");
  }

  int availableCalls() {
    return availableCalls.get();
  }

  int publishCalls() {
    return publishCalls.get();
  }

  int awaitCalls() {
    return awaitCalls.get();
  }

  /** 每次 {@link #await} 的实际等待时长（累计，毫秒，向下取整）。单次调用的用例里它就等于那一次等了多久。 */
  long awaitElapsedMs() {
    return awaitElapsedNanos.get() / 1_000_000L;
  }

  /** 同上但纳秒精度（"等待是不是被瞬间撤掉的"这种断言不能咬毫秒取整后的 0，那会变成看运气）。 */
  long awaitElapsedNanos() {
    return awaitElapsedNanos.get();
  }

  /** {@link #await} 已经返回了几次（被 interrupt 撤掉的也算）——"等待结束了没有"的确定性观测点。 */
  int awaitReturns() {
    return awaitReturns.get();
  }

  List<ApprovalRequest> published() {
    return List.copyOf(published);
  }
}
