package io.mosire.agentlib.proc;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 父进程看门狗（子进程侧自保）：子 Agent 定期检查父进程是否还活着，父死则执行回调后自停——防孤儿。
 *
 * <p>为什么放在子进程侧：信任边界 = 进程边界（AGENTS.md 红线），子 Agent 是独立 stdio 子进程，父进程 （Brain/Manager）崩溃时没人替它收尸；stdin
 * 半关闭不是所有运行时都可靠触发退出，轮询 PID 是最后一道 兜底。子 Agent 启动时用 {@link #startForCurrentProcess} 挂上即可。
 */
public final class ParentWatchdog implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ParentWatchdog.class);

  private final Thread watcher;
  private final AtomicBoolean stopped;

  private ParentWatchdog(Thread watcher, AtomicBoolean stopped) {
    this.watcher = watcher;
    this.stopped = stopped;
  }

  /**
   * 启动看门狗：虚拟线程按 {@code interval} 轮询 {@code parentSupplier}，supplier 返回 null 或 {@code !isAlive()}
   * 时执行 {@code onParentGone} 后自停。
   *
   * @param interval 轮询间隔（毫秒级精度，低于 1ms 按 1ms 处理）
   * @param parentSupplier 父进程句柄来源（每次轮询现取，不缓存——句柄可能在检查间隙失效）
   * @param onParentGone 父进程消失后的动作（自杀清理）；回调抛异常只记 LOG.warn，不中断自停流程
   */
  public static ParentWatchdog start(
      Duration interval, Supplier<ProcessHandle> parentSupplier, Runnable onParentGone) {
    Objects.requireNonNull(interval, "interval");
    Objects.requireNonNull(parentSupplier, "parentSupplier");
    Objects.requireNonNull(onParentGone, "onParentGone");
    // 停止标志由工厂与看门狗线程共享：close 置位中断轮询，自停路径置位后直接返回
    AtomicBoolean stopped = new AtomicBoolean();
    Thread thread =
        Thread.ofVirtual()
            .name("mosire-parent-watchdog")
            .start(() -> watch(stopped, interval, parentSupplier, onParentGone));
    return new ParentWatchdog(thread, stopped);
  }

  /** 便捷方法：监控"当前进程的父进程"（{@code ProcessHandle.current().parent()}）。子 Agent 进程用这个： 父死则自杀，防孤儿。 */
  public static ParentWatchdog startForCurrentProcess(Duration interval, Runnable onParentGone) {
    return start(interval, () -> ProcessHandle.current().parent().orElse(null), onParentGone);
  }

  /** 幂等停止：已停止（close 或已自停）直接返回，否则置位并中断轮询线程。 */
  @Override
  public void close() {
    if (stopped.getAndSet(true)) {
      return;
    }
    watcher.interrupt();
  }

  /** 看门狗是否已停止（close 或父进程消失自停）。 */
  public boolean isStopped() {
    return stopped.get();
  }

  /** 轮询线程句柄（包私有，测试验证线程退出用）。 */
  Thread watcher() {
    return watcher;
  }

  private static void watch(
      AtomicBoolean stopped,
      Duration interval,
      Supplier<ProcessHandle> parentSupplier,
      Runnable onParentGone) {
    long intervalMillis = Math.max(1L, interval.toMillis());
    while (true) {
      try {
        TimeUnit.MILLISECONDS.sleep(intervalMillis);
      } catch (InterruptedException e) {
        return; // close() 中断即退出
      }
      if (stopped.get()) {
        return;
      }
      ProcessHandle parent;
      try {
        parent = parentSupplier.get();
      } catch (RuntimeException e) {
        // supplier 暂时性失败（如 /proc 读取抖动）≠ 父进程死亡，记日志下轮再查
        LOG.warn("ParentWatchdog 轮询父进程失败（下一轮重试）", e);
        continue;
      }
      if (parent != null && parent.isAlive()) {
        continue;
      }
      stopped.set(true);
      try {
        onParentGone.run();
      } catch (RuntimeException e) {
        LOG.warn("ParentWatchdog onParentGone 回调异常（自停流程照常完成）", e);
      }
      return;
    }
  }
}
