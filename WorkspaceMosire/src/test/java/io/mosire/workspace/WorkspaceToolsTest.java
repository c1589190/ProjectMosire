package io.mosire.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 工作区工具面的判据表（设计 §4.7 / §4.11 正向"运行期 {@code mount_workspace} 成功并落事件"与工具面相关负向），全离线。
 *
 * <ul>
 *   <li>{@link #threeToolsShareTheWorkspacesManifest()}（§4.7：三个工具共用 {@code workspaces} 面、缺省 DENY）；
 *   <li>{@link #mountWorkspaceSucceedsAndStaysRuntimeOnly()}（正向往返 + M6：只建运行期挂载）；
 *   <li>{@link #mountWithoutWorkspacesFaceIsDeniedBeforeExecution()}（§4.11 负向：越界创建；工具声明的面缺省 DENY）；
 *   <li>{@link #unmountAndListRoundTripWithoutPhysicalPaths()}（正向 + A7/G4）；
 *   <li>{@link #badArgumentsAreRejectedWithNamedCodes()}（A6/§4.2：相对路径、未归一、未知 kind、未注册能力）。
 * </ul>
 */
class WorkspaceToolsTest {

  @TempDir Path tempDir;

  private Path root;
  private CapabilityCatalog catalog;
  private DefaultWorkspaceRegistry registry;

  @BeforeEach
  void setUp() throws IOException {
    root = Files.createDirectories(tempDir.resolve("data"));
    catalog = WorkspaceFixture.catalog();
    registry = DefaultWorkspaceRegistry.create(catalog);
  }

  /** §4.7：三个工具名与共用资源面（{@code workspaces}、缺省 {@link ResourcePolicy#DENY}）。 */
  @Test
  void threeToolsShareTheWorkspacesManifest() {
    List<AgentTool> tools = WorkspaceTools.all(registry);

    assertThat(tools)
        .extracting(AgentTool::name)
        .containsExactlyInAnyOrderElementsOf(WorkspaceTools.NAMES);
    for (AgentTool tool : tools) {
      assertThat(tool.resources().namespaces())
          .as("%s 的资源面", tool.name())
          .containsExactly(WorkspacePermissions.WORKSPACES);
      assertThat(tool.resources().policyFor(WorkspacePermissions.WORKSPACES))
          .as("未表态 = 一个挂载都建不了")
          .isEqualTo(ResourcePolicy.DENY);
      assertThat(tool.spec().destructive()).as("登记不改文件，不是破坏性工具").isFalse();
    }
  }

  /** §4.11 正向：运行期 {@code mount_workspace} 成功、落事件、且只产出 {@code Origin.RUNTIME}（M6）。 */
  @Test
  void mountWorkspaceSucceedsAndStaysRuntimeOnly() throws IOException {
    Path dir = Files.createDirectories(root.resolve("scratch"));
    AgentPermissionSet permissions = WorkspaceFixture.fullFaces(root);
    AgentTool mount = tool(WorkspaceTools.MOUNT_WORKSPACE);

    ToolResult result =
        mount.execute(
            context(
                permissions,
                mount,
                Map.of(
                    "name",
                    "scratch",
                    "dir",
                    dir.toString(),
                    "kind",
                    "SCRATCH",
                    "capabilities",
                    List.of("docs"))));

    assertThat(result.success()).as(result.message()).isTrue();
    assertThat(result.message()).doesNotContain(dir.toString());
    assertThat(registry.mounts("main"))
        .singleElement()
        .extracting(Mount::origin)
        .isEqualTo(Origin.RUNTIME);
    assertThat(registry.maskEntries()).as("M6：运行期挂载不进遮蔽面").isEmpty();
    assertThat(registry.events()).extracting(WorkspaceEvent::type).contains(WorkspaceEvent.MOUNTED);
  }

  /**
   * §4.11 负向（越界创建）：调用者未在 {@code workspaces} 面表态 ⇒ 执行前就被既有唯一入口拒。
   *
   * <p>走真实工具边界（{@link ToolCallAuthorizer}）：工具自己只调 {@code require}，把 {@code ResourceDeniedException}
   * 转成工具错误码是唯一入口的职责——直接调 {@code execute} 会把它抛出来（那是正确行为， 不是"没拒"）。
   */
  @Test
  void mountWithoutWorkspacesFaceIsDeniedBeforeExecution() throws IOException {
    Path dir = Files.createDirectories(root.resolve("scratch"));
    AgentTool mount = tool(WorkspaceTools.MOUNT_WORKSPACE);
    AgentPermissionSet silent =
        WorkspaceFixture.permissions(Map.of(WorkspaceFixture.DOCS, ResourceScope.ofDir(root)));
    io.mosire.agentlib.tool.ToolRegistry tools = new io.mosire.agentlib.tool.ToolRegistry();
    tools.register(mount);

    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(
                tools,
                WorkspaceTools.MOUNT_WORKSPACE,
                context(
                    silent,
                    mount,
                    Map.of("name", "scratch", "dir", dir.toString(), "kind", "SCRATCH")));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(registry.mounts("main")).isEmpty();
  }

  /** §4.11 正向 + A7/G4：{@code list_workspaces} 只出虚拟路径，{@code unmount_workspace} 只注销不删目录。 */
  @Test
  void unmountAndListRoundTripWithoutPhysicalPaths() throws IOException {
    Path dir = Files.createDirectories(root.resolve("workspace"));
    AgentPermissionSet permissions = WorkspaceFixture.fullFaces(root);
    registry.mount(
        WorkspaceFixture.runtime("workspace", "main", "main", dir, Kind.FILE), caller(permissions));

    ToolResult listed =
        tool(WorkspaceTools.LIST_WORKSPACES)
            .execute(context(permissions, tool(WorkspaceTools.LIST_WORKSPACES), Map.of()));
    assertThat(listed.success()).isTrue();
    assertThat(listed.message()).contains("workspace").doesNotContain(dir.toString());

    ToolResult unmounted =
        tool(WorkspaceTools.UNMOUNT_WORKSPACE)
            .execute(
                context(
                    permissions,
                    tool(WorkspaceTools.UNMOUNT_WORKSPACE),
                    Map.of("name", "workspace")));
    assertThat(unmounted.success()).as(unmounted.message()).isTrue();
    assertThat(registry.mounts("main")).isEmpty();
    assertThat(Files.isDirectory(dir)).as("卸载只注销挂载，保留物理目录").isTrue();
  }

  /** §4.11 负向（A6/§4.2/M3）：入参与能力 id 的每一格都必须响亮拒，不猜。 */
  @Test
  void badArgumentsAreRejectedWithNamedCodes() throws IOException {
    Path dir = Files.createDirectories(root.resolve("scratch"));
    AgentPermissionSet permissions = WorkspaceFixture.fullFaces(root);
    AgentTool mount = tool(WorkspaceTools.MOUNT_WORKSPACE);

    assertThat(
            mount
                .execute(
                    context(
                        permissions,
                        mount,
                        Map.of("name", "scratch", "dir", "relative/dir", "kind", "SCRATCH")))
                .code())
        .as("物理落点必须绝对")
        .isEqualTo(WorkspaceException.INVALID_ARGUMENTS);
    assertThat(
            mount
                .execute(
                    context(
                        permissions,
                        mount,
                        Map.of(
                            "name",
                            "scratch",
                            "dir",
                            root.resolve("a").resolve("..").toString(),
                            "kind",
                            "SCRATCH")))
                .code())
        .as("物理落点必须已归一")
        .isEqualTo(WorkspaceException.INVALID_ARGUMENTS);
    assertThat(
            mount
                .execute(
                    context(
                        permissions,
                        mount,
                        Map.of("name", "scratch", "dir", dir.toString(), "kind", "NOPE")))
                .code())
        .isEqualTo(WorkspaceException.INVALID_ARGUMENTS);
    assertThat(
            mount
                .execute(
                    context(
                        permissions,
                        mount,
                        Map.of(
                            "name",
                            "scratch",
                            "dir",
                            dir.toString(),
                            "kind",
                            "SCRATCH",
                            "capabilities",
                            List.of("no-such-capability"))))
                .code())
        .as("未注册的能力 id ⇒ 具名拒（M3）")
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
    assertThat(registry.mounts("main")).as("四次被拒都不留挂载壳").isEmpty();
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private AgentTool tool(String name) {
    for (AgentTool tool : WorkspaceTools.all(registry)) {
      if (tool.name().equals(name)) {
        return tool;
      }
    }
    throw new AssertionError("WorkspaceTools 里没有这个工具名: " + name);
  }

  private static WorkspaceCaller caller(AgentPermissionSet permissions) {
    return new WorkspaceCaller("main", permissions);
  }

  private ToolContext context(
      AgentPermissionSet permissions, AgentTool tool, Map<String, Object> arguments) {
    return new ToolContext(
        AccessToken.DEFAULT,
        permissions,
        Map.of(),
        arguments,
        new AgentIdentity("main", CommandMode.FULL, "", 0),
        ResourceAuthorizer.of(permissions, tool.resources()));
  }
}
