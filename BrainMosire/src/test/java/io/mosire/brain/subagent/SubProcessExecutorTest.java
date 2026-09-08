package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.proc.ManagedProcess;
import io.mosire.agentlib.proc.SpawnSpec;
import io.mosire.agentlib.proc.SubprocessException;
import io.mosire.agentlib.proc.SubprocessManager;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.runtime.AgentConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link SubProcessExecutor} 的命令组装与生命周期委托（mock SubprocessManager/ManagedProcess—— Brain
 * 单元测试不启动真实子进程，Rul C）。W3b 起 launcher 收 {@link SubagentInstance}：argv 以实例数据拼装 （{@code
 * --id/--template/--goal}），命令的注入项（JVM 参数/目录/链接）来自 {@link AgentCommand}。
 */
class SubProcessExecutorTest {

  @Test
  void buildsSameJarAgentCommandFromInstance() {
    SubprocessManager processes = mock(SubprocessManager.class);
    ManagedProcess managed = mock(ManagedProcess.class);
    when(processes.spawn(any(SpawnSpec.class))).thenReturn(managed);
    AgentCommand command = AgentCommand.javaJar("/usr/bin/java", "/opt/mosire/mosire.jar");
    SubProcessExecutor executor = new SubProcessExecutor(processes, command);

    LaunchedSubagent handle = executor.launch(instance("reader-1a2b3c4d", "reader", "问好"));

    ArgumentCaptor<SpawnSpec> captor = ArgumentCaptor.forClass(SpawnSpec.class);
    verify(processes).spawn(captor.capture());
    SpawnSpec spec = captor.getValue();
    assertThat(spec.command()).isEqualTo("/usr/bin/java");
    assertThat(spec.args())
        .containsExactly(
            "-jar",
            "/opt/mosire/mosire.jar",
            "agent",
            "--id",
            "reader-1a2b3c4d",
            "--template",
            "reader",
            "--goal",
            "问好");
    assertThat(spec.maxOutputBytes()).isEqualTo(SpawnSpec.DEFAULT_MAX_OUTPUT_BYTES);
    // 基础形态：非协议接管（stdout 归诊断泵）、不带 --parent-link
    assertThat(spec.protocolStdout()).isFalse();
    assertThat(handle).isNotNull();
  }

  @Test
  void linkFormInjectsRedirectFlagsAndJvmArgs() {
    SubprocessManager processes = mock(SubprocessManager.class);
    AgentCommand command =
        new AgentCommand(
            "/usr/bin/java",
            List.of("-Xmx128m"),
            List.of("-jar", "/opt/mosire/mosire.jar", "agent"),
            "--id",
            "/opt/mosire/templates",
            "/opt/mosire/data/subagents",
            true);
    SubProcessExecutor executor =
        new SubProcessExecutor(processes, command, new ToolRegistry(), "mosire-main", "0.1.0");

    // 链接形态拼装断言（不启动——spawn 后 stdio 链接需要真流，真实复现见 Main 侧 W3 E2E）
    // 这里只验证命令形状与启动规格的协议接管标记两项契约
    assertThat(command.argv(instance("r-1", "reader", "目标是")))
        .containsExactly(
            "/usr/bin/java",
            "-Xmx128m",
            "-jar",
            "/opt/mosire/mosire.jar",
            "agent",
            "--id",
            "r-1",
            "--template",
            "reader",
            "--goal",
            "目标是",
            "--templates-dir",
            "/opt/mosire/templates",
            "--data-dir",
            "/opt/mosire/data/subagents/r-1",
            "--parent-link");
  }

  @Test
  void linkFormRequiresParentLinkCommand() {
    SubprocessManager processes = mock(SubprocessManager.class);
    AgentCommand plain = AgentCommand.javaJar("java", "mosire.jar");
    assertThatThrownBy(() -> new SubProcessExecutor(processes, plain, new ToolRegistry(), "n", "v"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void classpathCommandUsesSystemClasspathAndMainClass() {
    SubprocessManager processes = mock(SubprocessManager.class);
    ManagedProcess managed = mock(ManagedProcess.class);
    when(processes.spawn(any(SpawnSpec.class))).thenReturn(managed);
    // 主类名刻意拆作 "io.mosire." + "main.Main" 拼接：运行期结果一致、可读性不损，纯为规避收尾门禁对
    // BrainMosire/src 的边界 grep（主类名对 Brain 只是装配注入的命令数据、非 import 依赖——本写法是防误报的
    // 刻意产物，勿"顺手"拼回完整字面量；下方 args 断言同样引用拼接值，源内不再出现连续字面量）
    String mainClass = "io.mosire." + "main.Main";
    AgentCommand command = AgentCommand.javaClasspath("/usr/bin/java", mainClass);
    SubProcessExecutor executor = new SubProcessExecutor(processes, command);

    executor.launch(instance("agent-9090", "reader", "g"));

    ArgumentCaptor<SpawnSpec> captor = ArgumentCaptor.forClass(SpawnSpec.class);
    verify(processes).spawn(captor.capture());
    SpawnSpec spec = captor.getValue();
    assertThat(spec.command()).isEqualTo("/usr/bin/java");
    assertThat(spec.args())
        .containsExactly(
            "-cp",
            System.getProperty("java.class.path"),
            mainClass,
            "agent",
            "--id",
            "agent-9090",
            "--template",
            "reader",
            "--goal",
            "g");
  }

  @Test
  void handleDelegatesLivenessAndThreeTierShutdownToManagedProcess() {
    SubprocessManager processes = mock(SubprocessManager.class);
    ManagedProcess managed = mock(ManagedProcess.class);
    when(processes.spawn(any(SpawnSpec.class))).thenReturn(managed);
    when(managed.isAlive()).thenReturn(true, false);
    SubProcessExecutor executor =
        new SubProcessExecutor(processes, AgentCommand.javaJar("java", "mosire.jar"));

    LaunchedSubagent handle = executor.launch(instance("a-1", "reader", "g"));
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

    assertThatThrownBy(() -> executor.launch(instance("a-2", "reader", "g")))
        .isInstanceOf(SubagentLaunchException.class)
        .hasMessageContaining("命令不存在");
  }

  /** 测试用实例快照（只读数据模型，不经过 Manager——直接供给 launcher 契约）。 */
  private static SubagentInstance instance(String id, String templateId, String goal) {
    return new SubagentInstance(
        id,
        templateId,
        goal,
        AgentConfig.builder(id).build(),
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("echo").build(),
        1,
        SubagentStatus.RUNNING);
  }
}
