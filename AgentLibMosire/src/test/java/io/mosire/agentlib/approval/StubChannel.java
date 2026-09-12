package io.mosire.agentlib.approval;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 桩审批通道（离线用例专用，不碰 tty/HTTP）：可脚本化"人怎么答"，并记录编排器到底碰过它没有。
 *
 * <p>记录面是 T1/T6/T10 的<b>确定性</b>证据来源（"硬拒/无通道时通道连被问过都没有"不靠等事件、不靠计时）： {@code availableCalls}/{@code
 * publishCalls}/{@code awaitCalls} 三个计数各自对应一次真实交互。
 *
 * <p>应答脚本：每次 {@link #await} 取一条，脚本空 = 到点没人答（返回空）。
 *
 * <p><b>脚本答复不经过 {@link PendingApprovals#decide}</b>（它模拟的是"通道侧已经拿到决定"这一步，不模拟人写决议）。 真实通道的答复一律先落登记表（见
 * {@link ApprovalChannel#await} 的硬约束），所以脚本答复的用例里登记项仍停在"未决议"： 要断言 {@code pending()} / {@link
 * PendingApprovals#get} 的用例请用 {@link PendingBackedChannel}（人走登记表答复）。
 */
final class StubChannel implements ApprovalChannel {

  private final String name;
  private final AtomicInteger availableCalls = new AtomicInteger();
  private final AtomicInteger publishCalls = new AtomicInteger();
  private final AtomicInteger awaitCalls = new AtomicInteger();
  private final List<ApprovalRequest> published = Collections.synchronizedList(new ArrayList<>());
  private final Deque<Optional<ApprovalDecision>> script = new ArrayDeque<>();

  private volatile boolean available = true;
  private volatile boolean throwOnPublish;
  private volatile boolean throwOnAwait;

  StubChannel(String name) {
    this.name = name;
  }

  /** 置可用性（{@code false} = 模拟"无 /dev/tty、端口未开"）。 */
  StubChannel available(boolean value) {
    available = value;
    return this;
  }

  /** 每次被 publish 时抛异常（模拟通道故障）。 */
  StubChannel failingPublish() {
    throwOnPublish = true;
    return this;
  }

  /** 每次被 await 时抛异常（模拟通道故障）。 */
  StubChannel failingAwait() {
    throwOnAwait = true;
    return this;
  }

  /** 追加一条"人这么答"。 */
  StubChannel thenAnswer(ApprovalDecision decision) {
    script.addLast(Optional.of(decision));
    return this;
  }

  /** 追加一条"这次没人答"（该次 await 返回空）。 */
  StubChannel thenSilence() {
    script.addLast(Optional.empty());
    return this;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean available() {
    availableCalls.incrementAndGet();
    return available;
  }

  @Override
  public void publish(ApprovalRequest req) {
    publishCalls.incrementAndGet();
    if (throwOnPublish) {
      throw new IllegalStateException("桩通道 publish 故障");
    }
    published.add(req);
  }

  @Override
  public Optional<ApprovalDecision> await(String id, Duration wait) {
    awaitCalls.incrementAndGet();
    if (throwOnAwait) {
      throw new IllegalStateException("桩通道 await 故障");
    }
    Optional<ApprovalDecision> next = script.pollFirst();
    return next == null ? Optional.empty() : next;
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

  List<ApprovalRequest> published() {
    return List.copyOf(published);
  }
}
