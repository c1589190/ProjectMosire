package io.mosire.main.gateway.agui;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.brain.runtime.StopReason;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AG-UI 会话（一次 POST /sessions = 一次运行；sessionId = threadId = runId——W4 一回合模型）。
 *
 * <p>事件隔离模型：本会话的事件只来自主 Agent（agent="main"），且 seq 落在半开区间（{@link #floor()}， {@link
 * #ceiling()}］内（floor=run 入场前末 seq、ceiling=run 退场后末 seq——seq = count 的存储模型下 floor
 * 是**上一个运行的末事件**，必须排他）——主 Agent 运行经共享单线程 chat 执行器串行化（见 {@link AgUiSessionRegistry}），任何其它运行的 seq
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

  /**
   * 订阅 SSE 流（同一时刻一个订阅者）；返回本订阅者的唤醒队列（{@code null} = 已被订阅 → 服务器回 409）。
   *
   * <p>返回的队列由订阅者持有并轮询；只用于"自己这一支流"——重连既不共享队列也不被他人队列干扰。
   */
  BlockingQueue<Object> attachStream() {
    BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    return stream.compareAndSet(null, queue) ? queue : null;
  }

  /**
   * 退订：仅当当前注册队列仍是本订阅者的队列才清除（CAS）——防止"本流迟到的 detach 清掉重连者新队列" （重连竞态：旧流 finally 先于新流 attach
   * 落地时，清的是新流的注册→新流只能自旋轮询）。
   */
  void detachStream(BlockingQueue<Object> queue) {
    stream.compareAndSet(queue, null);
  }

  /** 当前活动订阅者队列（驱逐/诊断用；{@code null} = 无人订阅）。 */
  BlockingQueue<Object> streamOrNull() {
    return stream.get();
  }

  /**
   * 队列信号（offer 不等待——R8 教训：put 在关闭窗可用中断标志、迟到不再唤醒；信号丢失由超时回读兜底）。
   *
   * <p>RV_RETURN_VALUE_IGNORED_BAD_PRACTICE 抑制：无界 {@link LinkedBlockingQueue} 上 {@code offer}
   * 必成功；且该处的语义是"尽力唤醒" （信号是 best-effort、丢失/迟到无害——流循环 300ms 超时回读兜底，见 AgUiHttpServer.streamLoop）——若改用
   * {@code put} 等待 会被中断标志卡在关闭窗（R8 实测教训）。
   */
  @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
  void signal(Object marker) {
    BlockingQueue<Object> queue = stream.get();
    if (queue != null) {
      queue.offer(marker);
    }
  }
}
