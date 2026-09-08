package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.mosire.agentlib.proc.ManagedProcess;
import io.mosire.agentlib.proc.SpawnSpec;
import io.mosire.agentlib.proc.SubprocessException;
import io.mosire.agentlib.proc.SubprocessManager;
import io.mosire.brain.runtime.AgentConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link SubProcessExecutor} 的命令组装与生命周期委托（mock SubprocessManager/ManagedProcess—— Brain
 * 单元测试不启动真实子进程，Rul C）。
 */
class SubProcessExecutorTest {

  @Test
  void buildsSameJarAgentCommandWithInstanceId() {
    SubprocessManager processes = mock(SubprocessManager.class);
    ManagedProcess managed = mock(ManagedProcess.class);
    when(processes.spawn(any(SpawnSpec.class))).thenReturn(managed);
    AgentCommand command = AgentCommand.javaJar("/usr/bin/java", "/opt/mosire/mosire.jar");
    SubProcessExecutor executor = new SubProcessExecutor(processes, command);

    LaunchedSubagent handle =
        executor.launch("reader-1a2b3c4d", AgentConfig.builder("reader-1a2b3c4d").build());

    ArgumentCaptor<SpawnSpec> captor = ArgumentCaptor.forClass(SpawnSpec.class);
    verify(processes).spawn(captor.capture());
    SpawnSpec spec = captor.getValue();
    assertThat(spec.command()).isEqualTo("/usr/bin/java");
    assertThat(spec.args())
        .containsExactly("-jar", "/opt/mosire/mosire.jar", "agent", "--id", "reader-1a2b3c4d");
    assertThat(spec.maxOutputBytes()).isEqualTo(SpawnSpec.DEFAULT_MAX_OUTPUT_BYTES);
    assertThat(handle).isNotNull();
  }

  @Test
  void classpathCommandUsesSystemClasspathAndMainClass() {
    SubprocessManager processes = mock(SubprocessManager.class);
    ManagedProcess managed = mock(ManagedProcess.class);
    when(processes.spawn(any(SpawnSpec.class))).thenReturn(managed);
    AgentCommand command = AgentCommand.javaClasspath("/usr/bin/java", "io.mosire.main.Main");
    SubProcessExecutor executor = new SubProcessExecutor(processes, command);

    executor.launch("agent-9090", AgentConfig.builder("agent-9090").build());

    ArgumentCaptor<SpawnSpec> captor = ArgumentCaptor.forClass(SpawnSpec.class);
    verify(processes).spawn(captor.capture());
    SpawnSpec spec = captor.getValue();
    assertThat(spec.command()).isEqualTo("/usr/bin/java");
    assertThat(spec.args())
        .containsExactly(
            "-cp",
            System.getProperty("java.class.path"),
            "io.mosire.main.Main",
            "agent",
            "--id",
            "agent-9090");
  }

  @Test
  void handleDelegatesLivenessAndThreeTierShutdownToManagedProcess() {
    SubprocessManager processes = mock(SubprocessManager.class);
    ManagedProcess managed = mock(ManagedProcess.class);
    when(processes.spawn(any(SpawnSpec.class))).thenReturn(managed);
    when(managed.isAlive()).thenReturn(true, false);
    SubProcessExecutor executor =
        new SubProcessExecutor(processes, AgentCommand.javaJar("java", "mosire.jar"));

    LaunchedSubagent handle = executor.launch("a-1", AgentConfig.builder("a-1").build());
    assertThat(handle.isAlive()).isTrue();
    assertThat(handle.isAlive()).isFalse();

    handle.close();
    // 三层关停经由 SubprocessManager/ManagedProcess 的标准入口（优雅停+宽限+树级强杀在内）
    verify(managed).stopGracefully(SubprocessManager.DEFAULT_GRACE);
  }

  @Test
  void launchFailureMapsSubprocessExceptionToSubagentLaunchException() {
    SubprocessManager processes = mock(SubprocessManager.class);
    when(processes.spawn(any(SpawnSpec.class))).thenThrow(new SubprocessException("命令不存在"));
    SubProcessExecutor executor =
        new SubProcessExecutor(processes, AgentCommand.javaJar("java", "mosire.jar"));

    assertThatThrownBy(() -> executor.launch("a-2", AgentConfig.builder("a-2").build()))
        .isInstanceOf(SubagentLaunchException.class)
        .hasMessageContaining("命令不存在");
  }
}
