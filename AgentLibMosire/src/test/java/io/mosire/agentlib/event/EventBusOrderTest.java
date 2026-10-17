package io.mosire.agentlib.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * EventBus 的有序 / 有界 / drain 契约。
 *
 * <p>这些用例刻意用 {@link EventBus#close()} 当"已投递完"的同步屏障：按设计 close 必须 drain 完已入队事件才返回， 因此不需要靠 sleep
 * 猜时序（AGENTS §四.11：涉及时序的用例不许真实等待到点）。
 */
class EventBusOrderTest {

  @Test
  void deliversFiveThousandEventsPerSubscriberInPublishOrder() {
    EventBus bus = new EventBus();
    List<Long> first = new ArrayList<>();
    List<Long> second = new ArrayList<>();
    bus.subscribe(event -> first.add(event.seq()));
    bus.subscribe(event -> second.add(event.seq()));

    List<Long> expected = new ArrayList<>();
    for (long seq = 0; seq < 5000; seq++) {
      expected.add(seq);
      bus.publish(event(seq));
    }
    bus.close();

    // 同一订阅者零反转：收到的序列与 publish 顺序逐项相等。
    assertThat(first).containsExactlyElementsOf(expected);
    assertThat(second).containsExactlyElementsOf(expected);
    assertThat(bus.subscriberCount()).isZero();
  }

  @Test
  void closeDrainsEventsEnqueuedBeforeCloseThenStopsAccepting() throws Exception {
    int total = 200;
    // 容量 > 批次：所有事件在 close 之前已确定入队（订阅方被第一场回调卡住，尚未消费）。
    EventBus bus = new EventBus(256, EventBus.OverflowPolicy.BLOCK);
    CountDownLatch release = new CountDownLatch(1);
    List<Long> received = new ArrayList<>();
    bus.subscribe(
        event -> {
          awaitQuietly(release);
          received.add(event.seq());
        });

    for (long seq = 0; seq < total; seq++) {
      bus.publish(event(seq));
    }

    // 关闭线程进入 close()（isClosed 先置位）后才放行订阅方：证明 close 确实在等 drain，而不是把线程一关了之。
    Thread releaser =
        Thread.ofVirtual()
            .start(
                () -> {
                  awaitBusClosed(bus);
                  release.countDown();
                });
    bus.close();
    releaser.join(TimeUnit.SECONDS.toMillis(5));

    assertThat(received).containsExactlyElementsOf(expectedSeqs(total));
    assertThat(bus.subscriberCount()).isZero();

    // 关闭后停收：publish 静默丢弃（不抛异常、不再投递），close 幂等，subscribe 拒绝。
    bus.publish(event(9999));
    bus.close();
    assertThat(received).hasSize(total);
    assertThatThrownBy(() -> bus.subscribe(event -> {})).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void subscriberExceptionDoesNotBreakLaterEventsForAnySubscriber() {
    EventBus bus = new EventBus();
    AtomicInteger failingAttempts = new AtomicInteger();
    List<Long> received = new ArrayList<>();
    bus.subscribe(
        event -> {
          failingAttempts.incrementAndGet();
          throw new IllegalStateException("on purpose");
        });
    bus.subscribe(event -> received.add(event.seq()));

    for (long seq = 0; seq < 50; seq++) {
      bus.publish(event(seq));
    }
    bus.close();

    // 抛异常的订阅者每条都被调用（说明异常没有打死它的分派线程），健康订阅者一条不少且有序。
    assertThat(failingAttempts.get()).isEqualTo(50);
    assertThat(received).containsExactlyElementsOf(expectedSeqs(50));
  }

  @Test
  void dropOldestKeepsQueueBoundedAndRetainsNewestEvent() {
    int capacity = 8;
    EventBus bus = new EventBus(capacity, EventBus.OverflowPolicy.DROP_OLDEST);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    List<Long> received = new ArrayList<>();
    bus.subscribe(
        event -> {
          started.countDown();
          awaitQuietly(release);
          received.add(event.seq());
        });

    bus.publish(event(0));
    assertThat(awaitQuietly(started)).isTrue();

    // 消费方被卡住期间狂灌 1000 条：有界队列必须丢最旧、保最新，绝不能无界增长。
    for (long seq = 1; seq <= 1000; seq++) {
      bus.publish(event(seq));
    }
    release.countDown();
    bus.close();

    assertThat(received).isNotEmpty();
    // 1 条在回调中（seq=0）+ 最多 capacity 条在队列里。
    assertThat(received.size()).isLessThanOrEqualTo(capacity + 1);
    assertThat(received.get(0)).isEqualTo(0L);
    assertThat(received.get(received.size() - 1)).isEqualTo(1000L);
    // 有损但不乱序：保留下来的事件严格递增。
    for (int i = 1; i < received.size(); i++) {
      assertThat(received.get(i)).isGreaterThan(received.get(i - 1));
    }
  }

  @Test
  void defaultConstructorKeepsBlockPolicyAndBoundedQueue() {
    EventBus bus = new EventBus();
    try {
      assertThat(bus.overflowPolicy()).isEqualTo(EventBus.OverflowPolicy.BLOCK);
      assertThat(bus.queueCapacity()).isEqualTo(EventBus.DEFAULT_QUEUE_CAPACITY);
      // 有界是硬约束：旧构造不得退化成无界队列。
      assertThat(bus.queueCapacity()).isPositive();
      assertThat(bus.isClosed()).isFalse();
    } finally {
      bus.close();
    }
    assertThat(bus.isClosed()).isTrue();
  }

  private static Event event(long seq) {
    return new Event(seq, Instant.now(), "test", "agent", "", "");
  }

  private static List<Long> expectedSeqs(int count) {
    List<Long> expected = new ArrayList<>(count);
    for (long seq = 0; seq < count; seq++) {
      expected.add(seq);
    }
    return expected;
  }

  private static boolean awaitQuietly(CountDownLatch latch) {
    try {
      return latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待被中断", e);
    }
  }

  private static void awaitBusClosed(EventBus bus) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!bus.isClosed() && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
  }
}
