package io.mosire.agentlib.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventBusTest {

  @Test
  void deliversToSubscribersAndSupportsUnsubscribe() throws Exception {
    EventBus bus = new EventBus();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger received = new AtomicInteger();
    EventBus.Subscription subscription =
        bus.subscribe(
            event -> {
              received.incrementAndGet();
              latch.countDown();
            });

    Event event = new Event(1, Instant.now(), "t", "main", "", "");
    bus.publish(event);
    assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(received.get()).isEqualTo(1);

    subscription.close();
    bus.close();
  }

  @Test
  void subscriberExceptionDoesNotBreakDispatch() throws Exception {
    EventBus bus = new EventBus();
    CountDownLatch latch = new CountDownLatch(1);
    bus.subscribe(
        event -> {
          throw new IllegalStateException("监听器故障");
        });
    bus.subscribe(event -> latch.countDown());
    bus.publish(new Event(1, Instant.now(), "t", "main", "", ""));
    assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    bus.close();
  }
}
