package io.mosire.agentlib.approval;

import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件收集器：订阅 {@link EventBus}，把收到的 {@link Event} 攒起来。
 *
 * <p>{@link EventBus#publish} 是<b>异步</b>投递（每个订阅方一条虚拟线程，无 flush 口），所以：
 *
 * <ul>
 *   <li>"某类事件<b>到了</b>"用 {@link #awaitType}（有期限轮询，到了就返回）；
 *   <li>"某类事件<b>没来</b>"用 {@link #quietWithout}（静默窗口内为空）——它只是佐证，判别力靠同用例里的 <b>结构性</b>证据（桩通道计数、{@code
 *       pending()} 快照），以及本类里配对的正事件断言。
 * </ul>
 */
final class EventSink implements EventBus.ProcessEventListener, AutoCloseable {

  private final List<Event> events = new CopyOnWriteArrayList<>();
  private final EventBus.Subscription subscription;

  EventSink(EventBus bus) {
    this.subscription = bus.subscribe(this);
  }

  @Override
  public void onEvent(Event event) {
    events.add(event);
  }

  List<Event> ofType(String type) {
    return events.stream().filter(event -> event.type().equals(type)).toList();
  }

  List<Event> all() {
    return List.copyOf(events);
  }

  /** 有期限等一条某类型事件（到了就返回；到点仍未到返回空）。 */
  Optional<Event> awaitType(String type, Duration timeout) {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() - deadlineNanos < 0) {
      List<Event> matched = ofType(type);
      if (!matched.isEmpty()) {
        return Optional.of(matched.get(0));
      }
      try {
        Thread.sleep(2L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
    }
    return ofType(type).stream().findFirst();
  }

  /** 有期限等某类型事件攒到 {@code count} 条（到点仍不够就返回当时拿到的）。 */
  List<Event> awaitAtLeast(String type, int count, Duration timeout) {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() - deadlineNanos < 0) {
      List<Event> matched = ofType(type);
      if (matched.size() >= count) {
        return matched;
      }
      try {
        Thread.sleep(2L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return ofType(type);
      }
    }
    return ofType(type);
  }

  /** 静默窗口内某类型事件一条都没来 ⇒ true。 */
  boolean quietWithout(String type, Duration window) {
    try {
      Thread.sleep(Math.max(0L, window.toMillis()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    return ofType(type).isEmpty();
  }

  @Override
  public void close() {
    subscription.close();
  }
}
