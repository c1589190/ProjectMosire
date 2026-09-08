package io.mosire.agentlib.proc;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子进程管理器：spawn / adopt 的统一入口，维护在管实例集合，close 时批量收割。
 *
 * <p><strong>spawn 与 adopt 的分工</strong>：spawn 由本类启动进程并持有全部管道（stdin 可写、输出可捕获）； adopt 只按 PID
 * 收养一个<b>已存在</b>的进程做生命周期治理。为什么需要 adopt——M2-W3 中 MCP SDK 的 StdioClientTransport 会自己 spawn 子 Agent
 * 进程（管道归 SDK），Manager 拿不到 Process 对象，只能按 PID 收养，负责存活监测、超时强杀与进程树清理；因此被收养实例的 stdin/stdout 恒为
 * empty，不读流。
 *
 * <p>锁用私有对象而非 {@code synchronized} 方法修饰符：实例被外部持有，用类自带锁会让外部持锁者 干扰内部互斥（同 SqliteEventStore 的 USO 理由）。
 */
public final class SubprocessManager implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(SubprocessManager.class);

  /** 默认优雅关停宽限：close() 与 ManagedProcess.close() 共用。 */
  public static final Duration DEFAULT_GRACE = Duration.ofSeconds(10);

  private final Object lock = new Object();
  private final Set<ManagedProcess> processes = new HashSet<>();

  /**
   * 启动子进程并纳入管理。环境变量合并进继承的父环境；工作目录 null = 继承父进程。
   *
   * @throws SubprocessException 进程启动失败（命令不存在、目录非法等）
   */
  public ManagedProcess spawn(SpawnSpec spec) throws SubprocessException {
    List<String> commandLine = new ArrayList<>();
    commandLine.add(spec.command());
    commandLine.addAll(spec.args());
    ProcessBuilder builder = new ProcessBuilder(commandLine);
    builder.redirectErrorStream(false);
    if (!spec.env().isEmpty()) {
      builder.environment().putAll(spec.env());
    }
    if (spec.workingDir() != null) {
      builder.directory(spec.workingDir().toFile());
    }
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      throw new SubprocessException("启动子进程失败: " + spec.command(), e);
    }
    ManagedProcess managed =
        new ManagedProcess(
            process.toHandle(),
            process,
            spec.maxOutputBytes(),
            spec.protocolStdout(),
            this::removeStopped);
    // 先注册后启动捕获线程：若溢出强杀抢在注册前发生，实例会以"已停止"状态被重新注册而泄漏在集合里
    synchronized (lock) {
      processes.add(managed);
    }
    managed.startOutputPumps();
    LOG.debug("spawn 子进程 pid={} command={}", managed.pid(), spec.command());
    return managed;
  }

  /**
   * 按 PID 收养一个已存在的进程（stdin/stdout 恒 empty，只管生命周期，见类 Javadoc）。
   *
   * @throws SubprocessException 该 pid 对应的进程不存在
   */
  public ManagedProcess adopt(long pid) throws SubprocessException {
    ProcessHandle handle =
        ProcessHandle.of(pid).orElseThrow(() -> new SubprocessException("收养失败，pid 不存在: " + pid));
    ManagedProcess managed = new ManagedProcess(handle, null, 0, this::removeStopped);
    synchronized (lock) {
      processes.add(managed);
    }
    LOG.debug("adopt 进程 pid={}", pid);
    return managed;
  }

  /** 当前在管实例的快照（含已退出但尚未被 close 收割的实例）。 */
  public List<ManagedProcess> managed() {
    synchronized (lock) {
      return List.copyOf(processes);
    }
  }

  /**
   * 对全部存活实例执行默认宽限的优雅关停（幂等：重复调用是空操作）。 注意：关闭的是"子进程"，不是本进程——进程自身生命周期归 Main/SubprocessManager 的持有者管。
   */
  @Override
  public void close() {
    List<ManagedProcess> snapshot;
    synchronized (lock) {
      snapshot = List.copyOf(processes);
    }
    for (ManagedProcess managed : snapshot) {
      if (managed.isAlive()) {
        managed.stopGracefully(DEFAULT_GRACE);
      }
    }
  }

  private void removeStopped(ManagedProcess managed) {
    synchronized (lock) {
      processes.remove(managed);
    }
  }
}
