package io.mosire.main.gateway.agui;

import io.mosire.brain.runtime.StopReason;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AG-UI 会话（一次 POST /sessions = 一次运行；sessionId = threadId = runId——W4 一回合模型）。
 *
 * <p>事件隔离模型：本会话的事件只来自主 Agent（agent="main"），且 seq 落在 {@link #floor()}（运行入场时捕获）到 {@link
 * #ceiling()}（运行退场时捕获）之间——主 Agent 运行经共享单线程 chat 执行器串行化（见 {@link AgUiSessionRegistry}），任何其它运行的 seq
 * 必然落在本区间之外，因此"agent 过滤 + seq 区段"双保险即会话隔离。
 *
 * <p>流订阅模型：同一时刻最多一个 SSE 订阅者（{@link #attachStream()} CAS）；{@link #signal(Object)} 只 offer
 * 不等待——队列为"唤醒信号"（{@link #WAKEUP} 或 {@link #TERMINAL}），有序数据来自 EventStore（见 AgUiHttpServer 的 catch-up
 * 循环）。
 */
public final class AgUiSession {

  /** 运行状态（终态决定 SSE 流尾——RUN_FINISHED/RUN_ERROR）。 */
  public enum Status {
    RUNNING,
    COMPLETED,
    ERROR;

    public boolean isTerminal() {
      return this != RUNNING;
    }
  }

  /** 唤醒信号：有新事件落库（EventBus 通知 → 服务端回读 EventStore）。 */
  public static final Object WAKEUP = new Object();

  /** 唤醒信号：运行已终态（桥综合成 RUN_FINISHED/RUN_ERROR 后流尾）。 */
  public static final Object TERMINAL = new Object();

  /** 未开始/未终态时的事件窗哨兵（seq 从 1 开始，-1 安全）。 */
  private static final long UNSET = -1L;

  private final String id;
  private final String userText;
  private final Map<String, Object> input;

  private volatile long floor = UNSET;
  private volatile long ceiling = UNSET;
  private volatile Status status = Status.RUNNING;
  private volatile StopReason stopReason;
  private volatile String errorCode;
  private volatile String errorMessage;
  private volatile String finalText = "";

  private final AtomicReference<BlockingQueue<Object>> stream = new AtomicReference<>();

  AgUiSession(String id, String userText, Map<String, Object> input) {
    this.id = id;
    this.userText = userText;
    this.input = Map.copyOf(input);
  }

  public String id() {
    return id;
  }

  public String userText() {
    return userText;
  }

  /** POST 请求体规范化回显（RUN_STARTED.input 的数据源；只含最小字段）。 */
  public Map<String, Object> input() {
    return input;
  }

  public long floor() {
    return floor;
  }

  void markFloor(long floor) {
    this.floor = floor;
  }

  public long ceiling() {
    return ceiling;
  }

  void markCeiling(long ceiling) {
    this.ceiling = ceiling;
  }

  public Status status() {
    return status;
  }

  public StopReason stopReason() {
    return stopReason;
  }

  public String errorCode() {
    return errorCode;
  }

  public String errorMessage() {
    return errorMessage;
  }

  /** RUN_FINISHED.result 的数据源（终态由运行桥填充；FakeLlm/轮询兜底路径可为空串）。 */
  public String finalText() {
    return finalText;
  }

  void finish(
      Status terminal,
      StopReason stopReason,
      String errorCode,
      String errorMessage,
      String finalText) {
    this.status = terminal;
    this.stopReason = stopReason;
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
    this.finalText = finalText == null ? "" : finalText;
  }

  /** 订阅 SSE 流（同一时刻一个订阅者；false = 已被订阅 → 服务器回 409）。 */
  boolean attachStream() {
    return stream.compareAndSet(null, new LinkedBlockingQueue<>());
  }

  void detachStream() {
    stream.set(null);
  }

  BlockingQueue<Object> streamOrNull() {
    return stream.get();
  }

  /** 队列信号（offer 不等待——R8 教训：put 在关闭窗可用中断标志、迟到不再唤醒；信号丢失由超时回读兜底）。 */
  void signal(Object marker) {
    BlockingQueue<Object> queue = stream.get();
    if (queue != null) {
      queue.offer(marker);
    }
  }
}
