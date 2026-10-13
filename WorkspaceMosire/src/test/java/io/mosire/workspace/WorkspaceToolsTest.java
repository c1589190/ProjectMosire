package io.mosire.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 工作区工具面的判据表（设计 §4.7「工具面的落点形态」行 ★ R6 修订 / §4.10 M4-M11 / §4.11 / §4.5），全离线。
 *
 * <p><b>落点形态是派生形态</b>（★ R6）：{@code mount_workspace} 只接受 {@code name} / {@code parent} / {@code
 * subdir} / {@code kind} / {@code capabilities}，落点 = 解析 {@code parent} 得到的物理目录 + {@code subdir}；绝对
 * {@code dir} 形态只留在注册表 API（装配期宿主代码）。因此 {@code parent} 必须是"你自己切片里的挂载名"，subdir 必须是 parent
 * 下的真相对子目录，能力不得超出 parent，且输出里不出现任何物理路径（A7/G4）。
 *
 * <p><b>逐条对号</b>：
 *
 * <ul>
 *   <li>{@link #threeToolsShareTheWorkspacesManifest()}（§4.7：三个工具共用 {@code workspaces} 面、缺省 DENY）；
 *   <li>{@link #mountSchemaIsDerivedFormOnly()}（§4.7：schema 只有派生形态五个入参，{@code dir}/{@code
 *       user}/{@code derivedFrom} 已删）；
 *   <li>{@link #mountWorkspaceSucceedsAndStaysRuntimeOnly()}（正向 + M6/M10：落点、归属、血缘、只建运行期挂载）；
 *   <li>{@link #listWorkspacesAnnouncesParentCandidatesWithoutPhysicalPaths()}（§4.7：list 点名可用
 *       parent 名 + A7/G4）；
 *   <li>{@link #unmountAndListRoundTripWithoutPhysicalPaths()}（正向 + A7/G4：只注销不删目录）；
 *   <li>{@link #badArgumentsAreRejectedWithNamedCodes()}（A6/W1/W2/M3：物理路径、非法 subdir、未知 kind、未注册能力）；
 *   <li>{@link #unknownParentAndForeignMountShareTheNotFoundCode()}（§4.5：不存在与"别人的挂载"同码，不泄露存在性）；
 *   <li>{@link #capabilitiesCannotExceedParent()}（M11：子能力不得超出 parent，含"能力够时放行"的对照）；
 *   <li>{@link #duplicateNameIsRejectedWithNameConflict()}（M4：重名具名拒、不静默替换）；
 *   <li>{@link #unmountingParentCascadesOnlyToDerivedMounts()}（M10：级联只沿派生链、不按物理包含、失效后不回落）；
 *   <li>{@link #mountWithoutWorkspacesFaceIsDeniedBeforeExecution()}（§4.11 负向：工具声明的面缺省 DENY ⇒
 *       执行前拒）。
 * </ul>
 */
class WorkspaceToolsTest {

  private static final String MAIN = "main";

  @TempDir Path tempDir;

  private Path root;
  private CapabilityCatalog catalog;
  private DefaultWorkspaceRegistry registry;
  private AgentPermissionSet permissions;

  @BeforeEach
  void setUp() throws IOException {
    root = Files.createDirectories(tempDir.resolve("data"));
    catalog = WorkspaceFixture.catalog();
    registry = DefaultWorkspaceRegistry.create(catalog);
    permissions = WorkspaceFixture.fullFaces(root);
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

  /** §4.7（★ R6）：入参只有派生形态那一组；{@code dir}/{@code user}/{@code derivedFrom} 不再存在。 */
  @Test
  void mountSchemaIsDerivedFormOnly() {
    Map<String, Object> schema = mountTool().jsonSchema();

    assertThat(schemaProperties(schema))
        .as("工具面不接受绝对物理路径，也不接受代他人挂载/自选派生源")
        .containsOnlyKeys("name", "parent", "subdir", "kind", "capabilities");
    assertThat(schemaRequired(schema))
        .containsExactlyInAnyOrder("name", "parent", "subdir", "kind");
  }

  /**
   * §4.7 正向 + M6/M10：只给 {@code parent + subdir} 就能挂上；落点 = parent 根 + subdir；{@code holder = user =
   * 调用者}；{@code derivedFrom = parent}；只产出 {@link Origin#RUNTIME}。
   */
  @Test
  void mountWorkspaceSucceedsAndStaysRuntimeOnly() throws IOException {
    Path parentDir = Files.createDirectories(root.resolve("workspace"));
    mountAssembly("workspace", MAIN, parentDir, Set.of(WorkspaceFixture.DOCS));

    ToolResult result =
        mount("scratch", "workspace", "notes/deep", "SCRATCH", List.of(WorkspaceFixture.DOCS));

    assertThat(result.success()).as(result.message()).isTrue();
    assertNoPhysical(result.message());
    assertThat(result.message()).contains("scratch").contains("派生自=workspace");

    Mount mounted = mountNamed("scratch");
    assertThat(mounted.dir())
        .as("落点 = parent 落点 + subdir")
        .isEqualTo(parentDir.resolve("notes/deep"));
    assertThat(registry.resolve("scratch", callerOfMain()))
        .as("映射可达（落点是严格更深的真子目录，W1）")
        .isEqualTo(parentDir.resolve("notes/deep"));
    assertThat(mounted.holder()).as("holder = 调用者").isEqualTo(MAIN);
    assertThat(mounted.user()).as("user = 调用者本人（工具面只服务「给自己划」）").isEqualTo(MAIN);
    assertThat(mounted.derivedFrom())
        .as("M10：derivedFrom = parent（卸载 parent 时本挂载级联失效）")
        .contains(new MountName("workspace"));
    assertThat(mounted.origin()).as("M6：工具面只建运行期挂载").isEqualTo(Origin.RUNTIME);
    assertThat(mounted.kind()).isEqualTo(Kind.SCRATCH);
    assertThat(mounted.capabilities()).containsExactly(WorkspaceFixture.DOCS);
    assertThat(registry.maskEntries())
        .extracting(MaskEntry::path)
        .as("M6：遮蔽面里只有装配期那块 parent，运行期挂载（mounted.dir()）不进")
        .containsOnly(parentDir);
    assertThat(registry.events()).extracting(WorkspaceEvent::type).contains(WorkspaceEvent.MOUNTED);
  }

  /** §4.7（★ R6）：{@code list_workspaces} 必须告诉模型哪些名字能当 {@code parent}；输出仍只含虚拟路径（A7/G4）。 */
  @Test
  void listWorkspacesAnnouncesParentCandidatesWithoutPhysicalPaths() throws IOException {
    ToolResult nothing = listOn(DefaultWorkspaceRegistry.create(catalog));

    assertThat(nothing.success()).as(nothing.message()).isTrue();
    assertThat(nothing.message())
        .as("一个 parent 都没有时要说清楚「先请装配方挂一块」，而不是让模型无从下手")
        .contains("一个 parent 也没有");

    Path parentDir = Files.createDirectories(root.resolve("workspace"));
    mountAssembly("workspace", MAIN, parentDir, Set.of(WorkspaceFixture.DOCS));

    ToolResult listed = listOn(registry);

    assertThat(listed.success()).as(listed.message()).isTrue();
    assertThat(listed.message())
        .as("模型看不到物理布局，不点名可用 parent 名就仍然无从下手")
        .contains("可用作 parent 的挂载名")
        .contains("workspace");
    assertNoPhysical(listed.message());
  }

  /** §4.11 正向 + A7/G4：{@code unmount_workspace} 只注销不删目录。 */
  @Test
  void unmountAndListRoundTripWithoutPhysicalPaths() throws IOException {
    Path dir = Files.createDirectories(root.resolve("workspace"));
    registry.mount(
        WorkspaceFixture.runtime("workspace", MAIN, MAIN, dir, Kind.FILE), callerOfMain());

    ToolResult listed = listOn(registry);
    assertThat(listed.success()).as(listed.message()).isTrue();
    assertThat(listed.message()).contains("workspace");
    assertNoPhysical(listed.message());

    ToolResult unmounted = unmount("workspace");

    assertThat(unmounted.success()).as(unmounted.message()).isTrue();
    assertNoPhysical(unmounted.message());
    assertThat(registry.mounts(MAIN)).isEmpty();
    assertThat(Files.isDirectory(dir)).as("卸载只注销挂载，保留物理目录").isTrue();
  }

  /** §4.7 负向（A6/W1/W2/M3）：物理路径与非法 {@code subdir} 一律具名拒，不猜、不留空壳。 */
  @Test
  void badArgumentsAreRejectedWithNamedCodes() throws IOException {
    Path parentDir = Files.createDirectories(root.resolve("workspace"));
    mountAssembly("workspace", MAIN, parentDir, Set.of(WorkspaceFixture.DOCS));

    assertRejected(
        WorkspaceException.INVALID_ARGUMENTS,
        "parent 只接受挂载名（绝对物理路径不是挂载名）",
        args("name", "abs-parent", "parent", "/etc", "subdir", "x", "kind", "FILE"));

    List<String> badSubdirs = List.of("..", ".", "../x", "a/../b", "/abs", "a//b", "a/", "");
    for (int i = 0; i < badSubdirs.size(); i++) {
      assertRejected(
          WorkspaceException.INVALID_ARGUMENTS,
          "subdir 必须是 parent 下的真相对子目录（不许逃逸/绝对/空段/尾随 '/'）: '" + badSubdirs.get(i) + "'",
          args(
              "name",
              "bad-sub-" + i,
              "parent",
              "workspace",
              "subdir",
              badSubdirs.get(i),
              "kind",
              "FILE",
              "capabilities",
              List.of(WorkspaceFixture.DOCS)));
    }

    assertRejected(
        WorkspaceException.INVALID_ARGUMENTS,
        "kind 必须是已注册纪律",
        args("name", "bad-kind", "parent", "workspace", "subdir", "x", "kind", "NOPE"));
    assertRejected(
        WorkspaceException.RESOURCE_DENIED,
        "未注册的能力 id ⇒ M3 具名拒（不落一个假承诺）",
        args(
            "name",
            "bad-cap",
            "parent",
            "workspace",
            "subdir",
            "x",
            "kind",
            "FILE",
            "capabilities",
            List.of("no-such-capability")));

    assertThat(registry.mounts(MAIN)).as("被拒的挂载不留空壳（只剩装配期那块 parent）").hasSize(1);
  }

  /** §4.5 负向：{@code parent} 不存在与"别人的挂载"必须<b>同码</b>——否则工具面变成存在性探测器。 */
  @Test
  void unknownParentAndForeignMountShareTheNotFoundCode() throws IOException {
    Path parentDir = Files.createDirectories(root.resolve("workspace"));
    mountAssembly("workspace", MAIN, parentDir, Set.of(WorkspaceFixture.DOCS));
    Path foreignDir = Files.createDirectories(root.resolve("outsider-ws"));
    mountAssembly("bobws", "outsider", foreignDir, Set.of(WorkspaceFixture.DOCS));
    assertThat(registry.mounts("outsider")).as("对照前置：别人的挂载真的存在").hasSize(1);

    ToolResult missing =
        mount(permissions, "p-nope", "nope", "x", "FILE", List.of(WorkspaceFixture.DOCS));
    ToolResult foreign =
        mount(permissions, "p-foreign", "bobws", "x", "FILE", List.of(WorkspaceFixture.DOCS));

    assertThat(missing.code()).isEqualTo(WorkspaceException.WORKSPACE_NOT_FOUND);
    assertThat(foreign.code())
        .as("别人的挂载与不存在给同一条回答：不泄露存在性")
        .isEqualTo(WorkspaceException.WORKSPACE_NOT_FOUND)
        .isEqualTo(missing.code());
    assertNoPhysical(missing.message());
    assertNoPhysical(foreign.message());
  }

  /**
   * §4.10 M11：子工作区的能力只能更窄——能力 id 不在 parent 的能力集里 ⇒ {@code RESOURCE_DENIED}。
   *
   * <p><b>为什么带"能力够时放行"的对照</b>：同一份权限面既能表达 M11 的红、也能表达"能挂上"，才能证明红在 M11 上，而不是红在 M1/M3
   * 的能力落格判定上（那会是一条恒红的假判据）。
   */
  @Test
  void capabilitiesCannotExceedParent() throws IOException {
    AgentPermissionSet withFsFace = permissionsWithFsFace();
    Path parentDir = Files.createDirectories(root.resolve("workspace"));
    Path wideDir = Files.createDirectories(root.resolve("wide"));
    registry.mount(
        WorkspaceFixture.mount(
            "workspace",
            MAIN,
            MAIN,
            parentDir,
            Kind.FILE,
            Set.of(WorkspaceFixture.DOCS),
            Origin.ASSEMBLY),
        new WorkspaceCaller(MAIN, withFsFace));
    registry.mount(
        WorkspaceFixture.mount(
            "wide",
            MAIN,
            MAIN,
            wideDir,
            Kind.FILE,
            Set.of(WorkspaceFixture.DOCS, WorkspaceFixture.BASH),
            Origin.ASSEMBLY),
        new WorkspaceCaller(MAIN, withFsFace));

    ToolResult beyond =
        mount(withFsFace, "wide-bash", "workspace", "x", "FILE", List.of(WorkspaceFixture.BASH));

    assertThat(beyond.code())
        .as("M11：bash 不在 parent（workspace）的能力集里")
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
    assertNoPhysical(beyond.message());

    ToolResult allowed =
        mount(withFsFace, "wide-bash-ok", "wide", "x", "FILE", List.of(WorkspaceFixture.BASH));

    assertThat(allowed.success())
        .as("对照：同一份面 + 能力足够的 parent ⇒ 放行（M11 不是恒红）: " + allowed.message())
        .isTrue();
  }

  /** §4.10 M4：同一使用者下重名 ⇒ 具名 {@code WORKSPACE_NAME_CONFLICT}，不静默替换、不覆盖。 */
  @Test
  void duplicateNameIsRejectedWithNameConflict() throws IOException {
    Path parentDir = Files.createDirectories(root.resolve("workspace"));
    mountAssembly("workspace", MAIN, parentDir, Set.of(WorkspaceFixture.DOCS));
    ToolResult first = mount("child", "workspace", "a", "FILE", List.of(WorkspaceFixture.DOCS));
    assertThat(first.success()).as(first.message()).isTrue();

    ToolResult second = mount("child", "workspace", "b", "FILE", List.of(WorkspaceFixture.DOCS));

    assertThat(second.code()).isEqualTo(WorkspaceException.WORKSPACE_NAME_CONFLICT);
    assertNoPhysical(second.message());
    assertThat(registry.mounts(MAIN)).hasSize(2);
    assertThat(mountNamed("child").dir())
        .as("撞名不静默替换：原挂载的落点一动不动")
        .isEqualTo(parentDir.resolve("a"));
  }

  /**
   * §4.10 M10（R5 后修订）：卸载 parent ⇒ 由它<b>派生</b>的挂载一并失效、解析 {@code WORKSPACE_NOT_FOUND}（不回落）；
   * 级联<b>只沿派生链</b>，不按物理包含。
   */
  @Test
  void unmountingParentCascadesOnlyToDerivedMounts() throws IOException {
    // outer 物理包含整棵树：若级联按"物理包含"判（R5 实测的旧错法），outer 会被一起带走
    mountAssembly("outer", MAIN, root, Set.of(WorkspaceFixture.DOCS));
    Path parentDir = Files.createDirectories(root.resolve("workspace"));
    mountAssembly("workspace", MAIN, parentDir, Set.of(WorkspaceFixture.DOCS));
    ToolResult child = mount("child", "workspace", "sub", "FILE", List.of(WorkspaceFixture.DOCS));
    assertThat(child.success()).as(child.message()).isTrue();
    assertThat(registry.resolve("child", callerOfMain())).isEqualTo(parentDir.resolve("sub"));

    ToolResult unmounted = unmount("workspace");

    assertThat(unmounted.success()).as(unmounted.message()).isTrue();
    assertThat(registry.mounts(MAIN))
        .extracting(mount -> mount.name().value())
        .as("独立挂载不因物理嵌套被级联带走")
        .containsExactly("outer");

    WorkspaceException gone =
        catchThrowableOfType(
            () -> registry.resolve("child", callerOfMain()), WorkspaceException.class);
    assertThat(gone).as("派生的挂载必须真的不在表里").isNotNull();
    assertThat(gone.code()).as("失效后不回落").isEqualTo(WorkspaceException.WORKSPACE_NOT_FOUND);
    WorkspaceException deeper =
        catchThrowableOfType(
            () -> registry.resolve("child/sub", callerOfMain()), WorkspaceException.class);
    assertThat(deeper).isNotNull();
    assertThat(deeper.code()).isEqualTo(WorkspaceException.WORKSPACE_NOT_FOUND);
    assertThat(registry.resolve("outer", callerOfMain())).isEqualTo(root);
    assertThat(registry.events())
        .filteredOn(event -> WorkspaceEvent.UNMOUNTED.equals(event.type()))
        .as("两条卸载事件：parent 一条 + 级联失效一条")
        .hasSize(2)
        .anySatisfy(event -> assertThat(event.detail()).contains("级联失效"));
  }

  /**
   * §4.11 负向（越界创建）：调用者未在 {@code workspaces} 面表态 ⇒ 执行前就被既有唯一入口拒。
   *
   * <p>走真实工具边界（{@link ToolCallAuthorizer}）：工具自己只调 {@code require}，把 {@code ResourceDeniedException}
   * 转成工具错误码是唯一入口的职责——直接调 {@code execute} 会把它抛出来（那是正确行为，不是"没拒"）。
   *
   * <p><b>不断言"这条文案不含物理路径"</b>：执行前拒的文案由 AgentLib 的资源前置闸生成，它的摘要带物理路径（设计 §9.20 已登记的 AgentLib
   * 收口项，不归本模块）。把已知缺口写进断言只会制造一条恒红的假判据。
   */
  @Test
  void mountWithoutWorkspacesFaceIsDeniedBeforeExecution() throws IOException {
    Path parentDir = Files.createDirectories(root.resolve("workspace"));
    mountAssembly("workspace", MAIN, parentDir, Set.of(WorkspaceFixture.DOCS));
    AgentTool mount = mountTool();
    AgentPermissionSet silent =
        WorkspaceFixture.permissions(Map.of(WorkspaceFixture.DOCS, ResourceScope.ofDir(root)));
    ToolRegistry tools = new ToolRegistry();
    tools.register(mount);

    ToolResult result =
        ToolCallAuthorizer.standard()
            .execute(
                tools,
                WorkspaceTools.MOUNT_WORKSPACE,
                context(
                    silent,
                    mount,
                    args("name", "scratch", "parent", "workspace", "subdir", "x", "kind", "FILE")));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
    assertThat(registry.mounts(MAIN)).as("执行前就被拒：表里只剩装配期那块").hasSize(1);
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 装配期挂载（宿主代码走注册表 API 的绝对 {@code dir} 形态）：工具面的 {@code parent} 只能从这些名字里挑。 */
  private void mountAssembly(String name, String user, Path dir, Set<String> capabilities) {
    registry.mount(
        new Mount(
            new MountName(name),
            user,
            user,
            dir,
            Resolvers.direct(),
            Kind.FILE,
            capabilities,
            Origin.ASSEMBLY,
            Optional.empty()),
        new WorkspaceCaller(user, permissions));
  }

  private AgentTool mountTool() {
    return tool(registry, WorkspaceTools.MOUNT_WORKSPACE);
  }

  private static AgentTool tool(WorkspaceRegistry registry, String name) {
    for (AgentTool tool : WorkspaceTools.all(registry)) {
      if (tool.name().equals(name)) {
        return tool;
      }
    }
    throw new AssertionError("WorkspaceTools 里没有这个工具名: " + name);
  }

  private WorkspaceCaller callerOfMain() {
    return new WorkspaceCaller(MAIN, permissions);
  }

  private Mount mountNamed(String name) {
    return registry.mounts(MAIN).stream()
        .filter(mount -> mount.name().value().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有这个挂载: " + name));
  }

  private ToolResult mount(
      String name, String parent, String subdir, String kind, List<String> capabilities) {
    return mount(permissions, name, parent, subdir, kind, capabilities);
  }

  private ToolResult mount(
      AgentPermissionSet perms,
      String name,
      String parent,
      String subdir,
      String kind,
      List<String> capabilities) {
    AgentTool mount = mountTool();
    return mount.execute(
        context(
            perms,
            mount,
            args(
                "name", name,
                "parent", parent,
                "subdir", subdir,
                "kind", kind,
                "capabilities", capabilities)));
  }

  private ToolResult unmount(String name) {
    AgentTool unmount = tool(registry, WorkspaceTools.UNMOUNT_WORKSPACE);
    return unmount.execute(context(permissions, unmount, Map.of("name", name)));
  }

  private ToolResult listOn(DefaultWorkspaceRegistry target) {
    AgentTool list = tool(target, WorkspaceTools.LIST_WORKSPACES);
    return list.execute(context(permissions, list, Map.of()));
  }

  private void assertRejected(String expectedCode, String what, Map<String, Object> arguments) {
    AgentTool mount = mountTool();
    ToolResult result = mount.execute(context(permissions, mount, arguments));

    assertThat(result.success()).as("%s（期望被拒）", what).isFalse();
    assertThat(result.code()).as("%s ⇒ 错误码", what).isEqualTo(expectedCode);
    assertNoPhysical(result.message());
  }

  /** A7/G4：面向模型的输出里不许出现任何物理路径（含本用例自己的临时根）。 */
  private void assertNoPhysical(String text) {
    assertThat(text)
        .as("A7/G4：面向模型的输出不含物理路径")
        .doesNotContain(root.toString())
        .doesNotContain(tempDir.toString());
  }

  /**
   * 权限面：{@code workspaces}/{@code docs}/{@code docs-edit} 之外再加上 {@code bash} 落格的 {@code fs}（M11
   * 对照用）。
   */
  private AgentPermissionSet permissionsWithFsFace() {
    ResourceScope scope = ResourceScope.of(WorkspaceFixture.prefixes(root));
    Map<String, ResourceScope> scopes = new LinkedHashMap<>();
    scopes.put(WorkspacePermissions.WORKSPACES, scope);
    scopes.put(WorkspaceFixture.DOCS, scope);
    scopes.put(WorkspaceFixture.DOCS_EDIT, scope);
    scopes.put("fs", scope);
    return WorkspaceFixture.permissions(scopes);
  }

  private ToolContext context(
      AgentPermissionSet perms, AgentTool tool, Map<String, Object> arguments) {
    return new ToolContext(
        AccessToken.DEFAULT,
        perms,
        Map.of(),
        arguments,
        new AgentIdentity(MAIN, CommandMode.FULL, "", 0),
        ResourceAuthorizer.of(perms, tool.resources()));
  }

  private static Map<String, Object> args(Object... keyValues) {
    Map<String, Object> arguments = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      arguments.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return arguments;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> schemaProperties(Map<String, Object> schema) {
    return (Map<String, Object>) schema.get("properties");
  }

  @SuppressWarnings("unchecked")
  private static List<String> schemaRequired(Map<String, Object> schema) {
    return (List<String>) schema.get("required");
  }
}
