package io.mosire.brain.subagent;

import io.mosire.agentlib.proc.ManagedProcess;
import io.mosire.agentlib.proc.SpawnSpec;
import io.mosire.agentlib.proc.SubprocessException;
import io.mosire.agentlib.proc.SubprocessManager;
import io.mosire.brain.runtime.AgentConfig;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 默认执行体：同 jar {@code agent} 子命令的真实子进程（计划 §4.4 SubProcessExecutor）。
 *
 * <p>进程级生命周期全委托 {@link SubprocessManager}（红线 5：进程句柄与管道归进程装配层持有， 本类只是委托入口，不外泄任何流/句柄给工具层）；启动命令按 Rul C
 * 由装配层注入 {@link AgentCommand}（Brain 不做文件系统假设）。三层关停（关 stdin → SIGTERM → 宽限等待 → 树级 SIGKILL）由 {@link
 * ManagedProcess#stopGracefully} 承担，本类经 {@link LaunchedSubagent} 窄缝转发。
 */
public final class SubProcessExecutor implements AgentExecutor {

  private final SubprocessManager processes;
  private final AgentCommand command;

  public SubProcessExecutor(SubprocessManager processes, AgentCommand command) {
    this.processes = Objects.requireNonNull(processes, "processes");
    this.command = Objects.requireNonNull(command, "command");
  }

  @Override
  public LaunchedSubagent launch(String instanceId, AgentConfig childConfig) {
    List<String> argv = command.argv(instanceId);
    SpawnSpec spec =
        new SpawnSpec(
            argv.get(0),
            argv.subList(1, argv.size()),
            Map.of(),
            null,
            SpawnSpec.DEFAULT_MAX_OUTPUT_BYTES);
    ManagedProcess managed;
    try {
      managed = processes.spawn(spec);
    } catch (SubprocessException e) {
      throw new SubagentLaunchException("子 Agent 子进程启动失败: " + e.getMessage(), e);
    }
    return new ManagedHandle(managed);
  }

  /** 关闭自身资源：无（SubprocessManager 归装配层持有并统一关停——红线 5；本类不拥有子进程）。 幂等。 */
  @Override
  public void close() {}

  /** 真进程句柄的窄缝适配：存亡/终止全数委托 {@link ManagedProcess}。 */
  private static final class ManagedHandle implements LaunchedSubagent {

    private final ManagedProcess managed;

    ManagedHandle(ManagedProcess managed) {
      this.managed = managed;
    }

    @Override
    public boolean isAlive() {
      return managed.isAlive();
    }

    @Override
    public void close() {
      // 三层关停标准入口（宽限 10s——SubprocessManager.DEFAULT_GRACE，与 SubprocessManager.close 同源）
      managed.stopGracefully(SubprocessManager.DEFAULT_GRACE);
    }
  }
}
