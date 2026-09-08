package io.mosire.agentlib.event;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内事件总线（publish/subscribe，虚拟线程分派）。
 *
 * <p>用途（计划 §3.6）：事件的旁路通知——EventBus 不参与事件落库（那是 {@link EventStore} 的 事），publish
 * 语义为"尽力投递"（订阅方异常只记日志，不回滚调用方）。
 */
public final class EventBus implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

  private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
  private final ExecutorService dispatcher = Executors.newVirtualThreadPerTaskExecutor();

  /** 订阅一次，返回可撤销的句柄（close 幂等）。 */
  public Subscription subscribe(ProcessEventListener listener) {
    Subscription subscription = new Subscription(this, listener);
    subscriptions.add(subscription);
    return subscription;
  }

  /** 异步分发（每个订阅方一个虚拟线程），订阅方异常仅记日志。 */
  public void publish(Event event) {
    subscriptions.forEach(
        subscription ->
            dispatcher.execute(
                () -> {
                  try {
                    subscription.listener().onEvent(event);
                  } catch (RuntimeException e) {
                    LOG.warn("EventBus 订阅方处理事件失败 type={} seq={}", event.type(), event.seq(), e);
                  }
                }));
  }

  @Override
  public void close() {
    subscriptions.clear();
    dispatcher.shutdown();
  }

  /** 事件监听。 */
  @FunctionalInterface
  public interface ProcessEventListener {
    void onEvent(Event event);
  }

  /** 订阅句柄；close 幂等。 */
  public static final class Subscription implements AutoCloseable {
    private final EventBus bus;
    private final ProcessEventListener listener;

    Subscription(EventBus bus, ProcessEventListener listener) {
      this.bus = bus;
      this.listener = listener;
    }

    public ProcessEventListener listener() {
      return listener;
    }

    @Override
    public void close() {
      bus.subscriptions.remove(this);
    }
  }

  /** 当前订阅数（测试用）。 */
  public int subscriberCount() {
    return subscriptions.size();
  }
}
