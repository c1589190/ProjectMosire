package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.mosire.agentlib.mcp.McpToolSource;
import io.mosire.agentlib.mcp.PipeMcpClientTransport;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.RoleSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.proc.ManagedProcess;
import io.mosire.agentlib.proc.SpawnSpec;
import io.mosire.agentlib.proc.SubprocessManager;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.AgentConfig;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * ★ A-6 的<b>父侧桥落点</b>：子体经 MCP 看得见的工具表（{@code tools/list} 的外发面）= 该子体的判定面。
 *
 * <p><b>为什么必须在 {@link SubProcessExecutor#launch} 这一层验</b>：子侧（{@code
 * SubagentProcessMain.filterFor}）只看得到 {@code McpToolAdapter}——它的 {@code spec()} 恒 {@code
 * ToolSpec.DEFAULT}、{@code roles()} 恒空集，所以敏感/破坏/角色 这几维在子侧 <b>判不了</b>；只有父侧起桥处同时握着真工具（真 {@code
 * spec}/{@code roles}）与子体权限集。把谓词换成 {@code name -> true} 就是"子体看见一调就吃 {@code PERMISSION_DENIED}
 * 的工具"——本类用真的 MCP 客户端从管道另一头读回那份目录， 断言它<b>不</b>出现。
 *
 * <p><b>怎么做到"真的走完 launch"</b>：用 {@link SubprocessManager} 的 mock 把 {@code spawn} 换成一对面管 （{@code
 * managed.stdout()/stdin()}），再在同一进程的另一端接 {@link McpToolSource} + {@link PipeMcpClientTransport}
 * 扮演子进程。于是被测代码是<b>生产的那条 lambda</b>（include 谓词），不是测试里另写的一份判定式。
 *
 * <p><b>判别性（变异自证）</b>：把父侧 include 谓词换成 {@code name -> true} ⇒ {@link
 * #bridgeExportsOnlyTheToolsThatPassTheChildDecisionFace} 转红（gm_tool / danger_tool 重新出现在外发面里）。
 */
class SubProcessExecutorBridgeRolesTest {

  /**
   * ★ 负向主判据：{@code gm_tool}（角色不相交）与 {@code danger_tool}（破坏性未放行）都<b>不在</b>子体的工具目录里； {@code
   * plain_tool} 在。
   */
  @Test
  void bridgeExportsOnlyTheToolsThatPassTheChildDecisionFace() throws Exception {
    ToolRegistry parentTools = new ToolRegistry();
    parentTools.register(synthetic("plain_tool", Set.of(), ToolSpec.DEFAULT, new AtomicInteger()));
    parentTools.register(synthetic("gm_tool", Set.of("gm"), ToolSpec.DEFAULT, new AtomicInteger()));
    parentTools.register(
        synthetic(
            "danger_tool",
            Set.of(),
            new ToolSpec(AccessToken.GUEST, false, true),
            new AtomicInteger()));

    // 子体权限集：白名单显式放行这三个（"白名单放行但判定不过"的形状），但角色是 dm、破坏性未放行
    AgentPermissionSet childPermissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow("plain_tool", "gm_tool", "danger_tool")
            .roles(RoleSet.of("dm"))
            .build();
    assertThat(childPermissions.isToolAllowed("gm_tool")).isTrue();
    assertThat(childPermissions.isToolAllowed("danger_tool")).isTrue();

    try (BridgeRig rig = new BridgeRig(parentTools, childPermissions)) {
      List<String> exposed = rig.exposedToolNames();

      assertThat(exposed).as("只有过了该子体判定面的工具才许外发（A-6：可见面 ⊆ 判定面）").containsExactly("plain_tool");
      assertThat(exposed)
          .as("白名单放行 ≠ 可见：角色与破坏性这两维只在父侧判得了")
          .doesNotContain("gm_tool", "danger_tool");
    }
  }

  /** 正向对照：把子体角色改成 {@code gm}、破坏性放行 ⇒ 两个工具都出现在外发面（上一条的"不可见"来自判定面，不是恒不可见）。 */
  @Test
  void aChildThatPassesEveryDimensionSeesThemAll() throws Exception {
    ToolRegistry parentTools = new ToolRegistry();
    parentTools.register(synthetic("gm_tool", Set.of("gm"), ToolSpec.DEFAULT, new AtomicInteger()));
    parentTools.register(
        synthetic(
            "danger_tool",
            Set.of(),
            new ToolSpec(AccessToken.GUEST, false, true),
            new AtomicInteger()));
    parentTools.register(synthetic("plain_tool", Set.of(), ToolSpec.DEFAULT, new AtomicInteger()));
    AgentPermissionSet childPermissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow("plain_tool", "gm_tool", "danger_tool")
            .destructiveAllowed(true)
            .roles(RoleSet.of("gm"))
            .build();

    try (BridgeRig rig = new BridgeRig(parentTools, childPermissions)) {
      assertThat(rig.exposedToolNames())
          .containsExactlyInAnyOrder("plain_tool", "gm_tool", "danger_tool");
    }
  }

  /** A-2 回归：<b>没声明角色</b>的工具对任何子体都照常外发（角色维引入不改变既有外发面）。 */
  @Test
  void toolsWithoutRoleDeclarationsAreExportedToEveryChild() throws Exception {
    ToolRegistry parentTools = new ToolRegistry();
    parentTools.register(synthetic("plain_tool", Set.of(), ToolSpec.DEFAULT, new AtomicInteger()));
    AgentPermissionSet bottomChild =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("plain_tool").build();
    assertThat(bottomChild.roles()).isEqualTo(RoleSet.none());

    try (BridgeRig rig = new BridgeRig(parentTools, bottomChild)) {
      assertThat(rig.exposedToolNames()).containsExactly("plain_tool");
    }
  }

  /** 桶维同样在父侧外发面生效：子体是 DEFAULT，父侧注册表里的 SYSTEM 级工具不许外发。 */
  @Test
  void toolsAboveTheChildTokenLevelAreNotExported() throws Exception {
    ToolRegistry parentTools = new ToolRegistry();
    parentTools.register(
        synthetic("sys_tool", Set.of(), ToolSpec.level(AccessToken.SYSTEM), new AtomicInteger()));
    parentTools.register(synthetic("plain_tool", Set.of(), ToolSpec.DEFAULT, new AtomicInteger()));
    AgentPermissionSet childPermissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("sys_tool", "plain_tool").build();

    try (BridgeRig rig = new BridgeRig(parentTools, childPermissions)) {
      assertThat(rig.exposedToolNames()).containsExactly("plain_tool");
    }
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /**
   * 起一次真正的 {@link SubProcessExecutor#launch}（链接形态），再用真 MCP 客户端从管道另一端读 {@code tools/list}。
   *
   * <p>子进程侧由本进程扮演：{@code managed.stdout()} 给出"父侧可读的输入"、{@code managed.stdin()} 给出"父侧可写的输出"，
   * 客户端的收发端就是这两条管的另一端。
   */
  private static final class BridgeRig implements AutoCloseable {

    private final LaunchedSubagent handle;
    private final McpToolSource clientSource;
    private final PipeMcpClientTransport clientTransport;

    BridgeRig(ToolRegistry parentTools, AgentPermissionSet childPermissions) throws Exception {
      SubprocessManager processes = mock(SubprocessManager.class);
      ManagedProcess managed = mock(ManagedProcess.class);
      when(processes.spawn(any(SpawnSpec.class))).thenReturn(managed);

      // 管道缓冲区放大：tools/list 的响应在本进程内两端线程间传递，默认 1 KiB 会让大目录冒死锁风险（与生产无关的测试装置细节）
      PipedInputStream parentReads = new PipedInputStream(1 << 20);
      PipedOutputStream childWrites = new PipedOutputStream(parentReads);
      PipedInputStream childReads = new PipedInputStream(1 << 20);
      PipedOutputStream parentWrites = new PipedOutputStream(childReads);
      InputStream serverIn = parentReads;
      OutputStream serverOut = parentWrites;
      when(managed.stdout()).thenReturn(Optional.of(serverIn));
      when(managed.stdin()).thenReturn(Optional.of(serverOut));

      AgentCommand command =
          new AgentCommand(
              "/usr/bin/java",
              List.of(),
              List.of("-jar", "/opt/mosire/mosire.jar", "agent"),
              "--id",
              null,
              null,
              true);
      SubProcessExecutor executor =
          new SubProcessExecutor(processes, command, parentTools, "mosire-parent", "0.1.0");

      handle = executor.launch(instance(childPermissions));

      clientSource = new McpToolSource("child-side");
      clientTransport =
          new PipeMcpClientTransport(McpJsonDefaults.getMapper(), childReads, childWrites);
      clientSource.connect(clientTransport);
    }

    List<String> exposedToolNames() {
      return clientSource.listTools().stream().map(AgentTool::name).toList();
    }

    @Override
    public void close() {
      try {
        clientSource.close();
      } catch (RuntimeException ignored) {
        // 关停面不是本类判据；真实生产里子进程自行退栈
      }
      try {
        clientTransport.closeGracefully().block();
      } catch (RuntimeException ignored) {
        // 同上
      }
      handle.close();
    }
  }

  private static SubagentInstance instance(AgentPermissionSet permissions) {
    return new SubagentInstance(
        "reader-1a2b3c4d",
        "reader",
        "目标",
        AgentConfig.builder("reader-1a2b3c4d").build(),
        permissions,
        1,
        SubagentStatus.RUNNING);
  }

  private static AgentTool synthetic(
      String name, Set<String> roles, ToolSpec spec, AtomicInteger runs) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return "合成工具 " + name;
      }

      @Override
      public Set<String> roles() {
        return roles;
      }

      @Override
      public ToolSpec spec() {
        return spec;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        runs.incrementAndGet();
        return ToolResult.ok(name + ":ok");
      }
    };
  }
}
