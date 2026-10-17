package io.mosire.agentlib.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内事件总线（publish/subscribe，每个订阅者一条有序有界队列 + 一个分派线程）。
 *
 * <p>用途（计划 §3.6）：事件的旁路通知——EventBus 不参与事件落库（那是 {@link EventStore} 的事），publish
 * 语义为"尽力投递"（订阅方异常只记日志，不回滚调用方）。
 *
 * <p><b>为什么不是每个事件一个线程</b>：把每条事件直接扔进共享线程池，同一订阅者会并发收到事件、顺序不可预期； 审计、事件库镜像、AG-UI 唤醒等消费方都默认"publish
 * 顺序即投递顺序"。故本实现为每个订阅者建一条容量固定的队列， 由该订阅者唯一的虚拟线程串行消费：同一订阅者严格按 publish 顺序收到事件，且线程数随订阅数（而非事件数）增长。
 *
 * <p><b>溢出策略</b>（{@link OverflowPolicy}）：默认 {@link OverflowPolicy#BLOCK}——队列满时 publish
 * 阻塞等待空位（背压，不丢事件）；{@link OverflowPolicy#DROP_OLDEST}——队列满时丢弃队首最旧事件，保证最新事件总能入队。
 *
 * <p><b>关闭</b>：{@link #close()} 幂等；先停止接收新事件，再 drain 各订阅者队列中已入队的事件，等分派线程终止后才返回 （关闭后 publish
 * 静默丢弃）。订阅句柄 {@link Subscription#close()} 同样是"停收 → drain → 分派线程退出"。
 */
public final class EventBus implements AutoCloseable {

  /** 默认队列容量：够吸收一次装配/一轮对话的突发，又不至于把背压拖成内存问题。 */
  public static final int DEFAULT_QUEUE_CAPACITY = 1024;

  private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

  private final int queueCapacity;
  private final OverflowPolicy overflowPolicy;
  private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  /** 旧构造：默认容量 + {@link OverflowPolicy#BLOCK}，既有装配零改动。 */
  public EventBus() {
    this(DEFAULT_QUEUE_CAPACITY, OverflowPolicy.BLOCK);
  }

  /** 指定溢出策略、使用默认容量。 */
  public EventBus(OverflowPolicy overflowPolicy) {
    this(DEFAULT_QUEUE_CAPACITY, overflowPolicy);
  }

  /** 指定队列容量、使用 {@link OverflowPolicy#BLOCK}。 */
  public EventBus(int queueCapacity) {
    this(queueCapacity, OverflowPolicy.BLOCK);
  }

  /**
   * 指定队列容量与溢出策略。
   *
   * @param queueCapacity 每订阅者队列容量，必须为正数
   * @param overflowPolicy 队列满时的行为，不能为 null
   */
  public EventBus(int queueCapacity, OverflowPolicy overflowPolicy) {
    if (queueCapacity <= 0) {
      throw new IllegalArgumentException("queueCapacity 必须为正数: " + queueCapacity);
    }
    this.queueCapacity = queueCapacity;
    this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy");
  }

  /** 订阅一次，返回可撤销的句柄（close 幂等）；总线已关闭时拒绝订阅。 */
  public Subscription subscribe(ProcessEventListener listener) {
    Objects.requireNonNull(listener, "listener");
    if (closed.get()) {
      throw new IllegalStateException("EventBus 已关闭，不能再订阅");
    }
    Subscription subscription = new Subscription(this, listener);
    subscriptions.add(subscription);
    if (closed.get()) {
      // 与 close() 竞争：close 可能已取过快照，这里补一次自清，避免留下没人 drain 的孤儿分派线程。
      subscriptions.remove(subscription);
      subscription.beginShutdown();
      subscription.awaitTermination();
      throw new IllegalStateException("EventBus 已关闭，不能再订阅");
    }
    return subscription;
  }

  /**
   * 发布一条事件：按订阅顺序入队（不等待订阅方处理完）。总线关闭后静默丢弃——投递语义本身就是"尽力"。
   *
   * @throws NullPointerException event 为 null
   */
  public void publish(Event event) {
    Objects.requireNonNull(event, "event");
    if (closed.get()) {
      return;
    }
    for (Subscription subscription : subscriptions) {
      subscription.enqueue(event);
    }
  }

  /**
   * 关闭总线：幂等；停止接收新事件 → drain 已入队事件 → 等待各分派线程终止。全部订阅句柄随之失效。
   *
   * <p>先对全部订阅者做"停收"，再统一等待 drain 完成——避免一边停一边等的窗口里还有订阅者在收事件。
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    List<Subscription> draining = List.copyOf(subscriptions);
    for (Subscription subscription : draining) {
      subscription.beginShutdown();
    }
    for (Subscription subscription : draining) {
      subscription.awaitTermination();
    }
    subscriptions.clear();
  }

  /** 是否已关闭（测试/审计用）。 */
  public boolean isClosed() {
    return closed.get();
  }

  /** 每订阅者队列容量（测试/审计用）。 */
  public int queueCapacity() {
    return queueCapacity;
  }

  /** 溢出策略（测试/审计用）。 */
  public OverflowPolicy overflowPolicy() {
    return overflowPolicy;
  }

  /** 当前订阅数（测试用）；撤销或关闭后的句柄不再计数。 */
  public int subscriberCount() {
    return subscriptions.size();
  }

  /** 从订阅表摘除（幂等）；由 {@link Subscription#close()} 使用。 */
  private void unsubscribe(Subscription subscription) {
    subscriptions.remove(subscription);
  }

  /** 事件监听。 */
  @FunctionalInterface
  public interface ProcessEventListener {
    void onEvent(Event event);
  }

  /** 队列满时的行为。 */
  public enum OverflowPolicy {
    /** 阻塞 publish 直到队列有空位（默认；背压，不丢事件）。 */
    BLOCK,
    /** 丢弃队首最旧事件，腾位给最新事件（实时唤醒等"要最新不要全量"的消费方）。 */
    DROP_OLDEST
  }

  /** 订阅句柄；close 幂等，语义同总线关闭（停收 → drain → 分派线程退出）。 */
  public static final class Subscription implements AutoCloseable {

    /** 分派终止标记；只在分派线程内用引用相等判断，不参与业务语义。 */
    private static final Event POISON =
        new Event(Long.MIN_VALUE, Instant.EPOCH, "__eventbus_poison__", "", "", "");

    private final EventBus bus;
    private final ProcessEventListener listener;
    private final BlockingQueue<Event> queue;
    private final OverflowPolicy overflowPolicy;
    private final ReentrantLock enqueueLock = new ReentrantLock();
    private final Thread dispatcher;

    /** 是否已"停收"；写入持 {@link #enqueueLock}，读取面向分派线程故声明 volatile。 */
    private volatile boolean stopping;

    Subscription(EventBus bus, ProcessEventListener listener) {
      this.bus = bus;
      this.listener = listener;
      this.queue = new ArrayBlockingQueue<>(bus.queueCapacity);
      this.overflowPolicy = bus.overflowPolicy;
      this.dispatcher =
          Thread.ofVirtual().name("eventbus-subscriber").unstarted(this::dispatchLoop);
      this.dispatcher.start();
    }

    public ProcessEventListener listener() {
      return listener;
    }

    @Override
    public void close() {
      bus.unsubscribe(this);
      beginShutdown();
      awaitTermination();
    }

    /** 入队一条事件；已停收则丢弃（close 与 publish 竞争时的"尽力投递"边界）。 */
    private void enqueue(Event event) {
      enqueueLock.lock();
      try {
        if (stopping) {
          return;
        }
        if (overflowPolicy == OverflowPolicy.DROP_OLDEST) {
          // offer/poll 都在同一把锁内：并发 publish 也不会把"丢最旧"放大成丢多条、或打乱入队顺序。
          while (!queue.offer(event)) {
            queue.poll();
          }
          return;
        }
        if (Thread.currentThread() == dispatcher) {
          // 订阅方在自己的分派线程里 publish 给同一个订阅者：队列满时阻塞 put 会自我死锁（唯一消费者是自己），
          // 故这里退化为非阻塞 offer——满了就丢这条自产事件，保证分派线程不被自己卡死。
          if (!queue.offer(event)) {
            LOG.warn("EventBus 分派线程自产事件被丢弃（队列已满） type={} seq={}", event.type(), event.seq());
          }
          return;
        }
        queue.put(event);
      } catch (InterruptedException e) {
        // publish 线程被中断：丢弃这条事件并保留中断标记，不能把中断吞掉。
        Thread.currentThread().interrupt();
      } finally {
        enqueueLock.unlock();
      }
    }

    /**
     * 停止接收新事件，并在队列尾部压入终止标记（其前已入队的事件仍会被消费）。
     *
     * <p>用带超时的 offer 轮询而非无界阻塞 put：分派线程若因订阅方抛出非 RuntimeException 而提前死亡， 这里不会把 close()
     * 永久挂住。订阅方在自己的分派线程里自撤时不能入队终止标记（队列满时会自我死锁）， 改为依赖分派循环的"已停收且队列为空"退出条件。
     */
    private void beginShutdown() {
      enqueueLock.lock();
      try {
        if (stopping) {
          return;
        }
        stopping = true;
      } finally {
        enqueueLock.unlock();
      }
      // 终止标记在锁外入队：置位后已无生产者能再入队（enqueue 持同一把锁检查 stopping），
      // 故标记一定排在所有已入队事件之后；不在锁内等待空位，避免与"订阅方自己 publish"互锁。
      if (Thread.currentThread() == dispatcher || !dispatcher.isAlive()) {
        return;
      }
      if (overflowPolicy == OverflowPolicy.DROP_OLDEST) {
        while (!queue.offer(POISON)) {
          queue.poll();
        }
        return;
      }
      try {
        while (dispatcher.isAlive() && !queue.offer(POISON, 10, TimeUnit.MILLISECONDS)) {
          // 队列满：分派线程仍在消费，等它腾出空位；它若退出则结束等待。
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    /** 等待分派线程把已入队事件处理完并终止；在分派线程内调用（订阅方自撤）时不自我 join。 */
    private void awaitTermination() {
      if (Thread.currentThread() == dispatcher) {
        return;
      }
      try {
        dispatcher.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    /** 单订阅者的唯一分派循环：串行消费队列，订阅方异常只记日志、继续下一条。 */
    private void dispatchLoop() {
      try {
        while (true) {
          Event event = queue.poll();
          if (event == null) {
            // 自撤场景没有终止标记：已停收且队列已空即 drain 完毕。
            if (stopping && queue.isEmpty()) {
              return;
            }
            event = queue.take();
          }
          if (event == POISON) {
            return;
          }
          try {
            listener.onEvent(event);
          } catch (RuntimeException e) {
            LOG.warn("EventBus 订阅方处理事件失败 type={} seq={}", event.type(), event.seq(), e);
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
