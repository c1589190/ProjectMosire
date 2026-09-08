package io.mosire.agentlib.proc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 受管子进程句柄：统一治理 spawn 与 adopt 两种来源的生命周期（存活监测、优雅关停、输出上限）。
 *
 * <p>两种模式的生命周期一致（{@link #stopGracefully} / {@link #onExit}），管道分工不同： <em>spawn 模式</em>管道归本类，可写
 * stdin、由虚拟线程捕获 stdout/stderr 做尾部诊断缓冲与输出上限；<em>adopt 模式</em>（{@link SubprocessManager#adopt}）的
 * stdin/stdout 恒为 empty——M2-W3 中 MCP SDK 的 StdioClientTransport 会自己 spawn 子 Agent 进程，管道归 SDK
 * 所有并由它读写，本类若再碰流必然打架； 因此 adopt 只按 PID 做生命周期治理（存活监测、超时强杀、进程树清理），不读流、不计数。
 *
 * <p>spawn 模式的输出捕获仅服务诊断与防日志爆炸（超 {@link SpawnSpec#maxOutputBytes} 立即强杀）， 不作为结构化输出通道——那是 W3 接 MCP
 * 之后的协议层职责。
 *
 * <p>锁用私有对象而非 {@code synchronized} 方法修饰符：外部可拿到本实例，用类自带锁会让外部持锁者 干扰内部互斥（同 SqliteEventStore 的 USO 理由）。
 */
public final class ManagedProcess implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ManagedProcess.class);

  /** 尾部缓冲最多保留的行数：诊断足够，又不至于在大输出下吃内存。 */
  private static final int MAX_TAIL_LINES = 100;

  private final ProcessHandle handle;

  /** spawn 模式非 null；adopt 模式恒为 null（管道归外部所有者，见类 Javadoc）。 */
  private final Process process;

  private final Optional<OutputStream> stdin;

  /** stdout 是否归协议层（true = 不启动诊断泵，{@link #stdout()} 暴露原始流）。 */
  private final boolean protocolStdout;

  private final long maxOutputBytes;
  private final Consumer<ManagedProcess> onStopped;
  private final Object tailLock = new Object();
  private final Object stopLock = new Object();
  private final ArrayDeque<String> tail = new ArrayDeque<>();
  private final AtomicLong totalBytes = new AtomicLong();
  private final AtomicBoolean overflowed = new AtomicBoolean();
  private volatile boolean stopped;

  ManagedProcess(
      ProcessHandle handle,
      Process process,
      long maxOutputBytes,
      Consumer<ManagedProcess> onStopped) {
    this(handle, process, maxOutputBytes, false, onStopped);
  }

  ManagedProcess(
      ProcessHandle handle,
      Process process,
      long maxOutputBytes,
      boolean protocolStdout,
      Consumer<ManagedProcess> onStopped) {
    this.handle = handle;
    this.process = process;
    this.maxOutputBytes = maxOutputBytes;
    this.protocolStdout = protocolStdout;
    this.onStopped = onStopped;
    this.stdin = Optional.ofNullable(process == null ? null : process.getOutputStream());
    // 自然退出的实例也要从 manager 集合移除，避免 managed() 积累僵尸条目；与 stopGracefully
    // 的移除重复调用是安全的（Set 语义）。
    handle.onExit().thenRun(this::notifyStopped);
  }

  /** 进程号。 */
  public long pid() {
    return handle.pid();
  }

  /** 进程当前是否存活。 */
  public boolean isAlive() {
    return handle.isAlive();
  }

  /** 底层进程句柄（不可变视图）：调用方可自行监听 {@code onExit} 或查树。 */
  public ProcessHandle handle() {
    return handle;
  }

  /** stdin（spawn 模式有；adopt 模式恒 empty——管道归外部所有者）。 */
  public Optional<OutputStream> stdin() {
    return stdin;
  }

  /**
   * stdout 原始流（仅 {@code protocolStdout=true} 时有；adopt 模式与默认模式恒 empty——协议层 是 spawn 模式下 stdout
   * 的唯一读者，诊断泵已按协议接管约定跳过）。
   */
  public Optional<InputStream> stdout() {
    if (!protocolStdout || process == null) {
      return Optional.empty();
    }
    return Optional.of(process.getInputStream());
  }

  /** 是否因输出超过上限而被强制终止。 */
  public boolean overflowed() {
    return overflowed.get();
  }

  /** 最近输出（spawn 模式，至多 {@value #MAX_TAIL_LINES} 行，时间正序快照）；adopt 模式恒为空列表。 */
  public List<String> outputTail() {
    synchronized (tailLock) {
      return List.copyOf(tail);
    }
  }

  /**
   * 退出监听：委托给 {@link ProcessHandle#onExit()}，完成后拿到的是句柄本身。
   *
   * <p>局限：adopt 模式下进程不是本 JVM spawn 的，无法拿到 exit code——需要 exit code 请用 spawn 模式，
   * 或由协议层（MCP/A2A）显式回报结果。
   */
  public CompletableFuture<ProcessHandle> onExit() {
    return handle.onExit();
  }

  /**
   * 三层优雅关停，顺序固定：① 关 stdin（半关闭常足以让协作式进程自行退出）→ ② SIGTERM → ③ 宽限等待 {@code grace} → ④ 仍未退则 SIGKILL
   * 清整棵进程树（先杀子孙再杀自身，防孤儿）。幂等；{@code grace} 为零或负跳过 ③（立即强杀，输出上限路径用）。完成后自身从 manager 集合移除。
   */
  public void stopGracefully(Duration grace) {
    if (stopped) {
      return;
    }
    synchronized (stopLock) {
      if (stopped) {
        return;
      }
      stopped = true;
    }
    // ① 关 stdin
    stdin.ifPresent(ManagedProcess::closeQuietly);
    // ② SIGTERM
    handle.destroy();
    // ③ 宽限等待（零/负 grace 跳过）
    if (grace != null && !grace.isZero() && !grace.isNegative()) {
      try {
        handle.onExit().get(grace.toNanos(), TimeUnit.NANOSECONDS);
      } catch (TimeoutException expected) {
        // 宽限期内未退：走 ④
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException impossible) {
        // onExit 的 future 只以句柄正常完成，不会异常完成
      }
    }
    // ④ SIGKILL 进程树
    if (handle.isAlive()) {
      handle.descendants().forEach(ProcessHandle::destroyForcibly);
      handle.destroyForcibly();
    }
    notifyStopped();
  }

  /** 等价于 {@code stopGracefully(SubprocessManager.DEFAULT_GRACE)}。 */
  @Override
  public void close() {
    stopGracefully(SubprocessManager.DEFAULT_GRACE);
  }

  /**
   * 由 manager 在注册后调用：启动 stdout/stderr 捕获虚拟线程（adopt 模式无流可读，空操作；协议接管模式的 stdout 不启泵——流归协议层，见类
   * Javadoc）。
   */
  void startOutputPumps() {
    if (process == null) {
      return;
    }
    if (!protocolStdout) {
      Thread.ofVirtual()
          .name("mosire-proc-" + pid() + "-stdout")
          .start(() -> pump(process.getInputStream()));
    }
    Thread.ofVirtual()
        .name("mosire-proc-" + pid() + "-stderr")
        .start(() -> pump(process.getErrorStream()));
  }

  /** 逐行读一条流：累计字节数（按 UTF-8 行字节 + 换行估算）、写尾部缓冲；超上限时置 overflowed 并立即强杀。 强杀导致的流关闭是正常路径，不算错误。 */
  private void pump(InputStream raw) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        long total = totalBytes.addAndGet(line.getBytes(StandardCharsets.UTF_8).length + 1L);
        synchronized (tailLock) {
          tail.addLast(line);
          while (tail.size() > MAX_TAIL_LINES) {
            tail.removeFirst();
          }
        }
        if (maxOutputBytes > 0 && total > maxOutputBytes && overflowed.compareAndSet(false, true)) {
          LOG.warn("子进程输出超过上限 {} 字节，立即强杀 pid={}", maxOutputBytes, pid());
          stopGracefully(Duration.ZERO);
          return;
        }
      }
    } catch (IOException e) {
      if (!stopped) {
        LOG.warn("读取子进程输出流失败 pid={}", pid(), e);
      }
    }
  }

  private void notifyStopped() {
    try {
      onStopped.accept(this);
    } catch (RuntimeException e) {
      LOG.warn("ManagedProcess 停止回调异常 pid={}", pid(), e);
    }
  }

  private static void closeQuietly(OutputStream stream) {
    try {
      stream.close();
    } catch (IOException e) {
      LOG.debug("关闭子进程 stdin 失败（忽略）", e);
    }
  }
}
