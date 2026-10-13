package io.mosire.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.tool.SandboxMask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 工作区标准的判据表（设计 §4.11 全量 + §4.4 G 组 + §4.10 A/M 组），全离线。
 *
 * <p><b>逐条对号</b>（用例名后的编号即设计条目）：
 *
 * <ul>
 *   <li>正向：{@link #resolveAndListProjectVirtualPathsOnly()}、{@link
 *       #grantLandsPrefixesAndKeepsOtherDimensions()}、 {@link
 *       #grandchildWorkspaceInsideChildWorkspaceSucceeds()}、{@link
 *       #unmountKeepsDirectoryAndKillsResolution()}、 {@link #runtimeMountLandsAuditEvent()}；
 *   <li>负向：{@link #mountOutsideWorkspacesFaceIsRejected()}（A2/M1）、{@link
 *       #mountForNonDescendantIsRejected()}（M2）、 {@link #holderMustBeTheCallerHimself()}（M2）、{@link
 *       #unregisteredCapabilityIsRejected()}（M3）、 {@link
 *       #capabilityLandingBeyondCallerFaceIsRejected()}（M1/M3）、 {@link
 *       #childWorkspaceEqualToOwnRootIsRejected()}（W1/M9）、 {@link
 *       #childWorkspaceOutsideOwnWorkspaceIsRejected()}（W2/M1）、 {@link
 *       #childCapabilityWiderThanParentIsRejected()}（M11）、 {@link
 *       #mountNameConflictIsNamedRejection()}（M4/A4）、 {@link
 *       #unmountByNonHolderIsRejected()}（M5）、{@link #unmountUnknownNameIsNotFound()}
 *       （M5/§4.5：与"不可见"同码）、{@link #parentUnmountCascadesToChildren()}（M10/W4）、 {@link
 *       #resolverEscapeIsRejected()}（G1）、{@link #errorsNeverCarryPhysicalPaths()}（A7/G4）、 {@link
 *       #virtualPathShapesAreRejected()}（§4.2/G3）、{@link #emptyCapabilityMountLandsNothing()}（A8）、
 *       {@link #runtimeMountNeverEntersShellMask()}（M6）、 {@link
 *       #docsMountsAreNeverWritableInShellFace()}（A3/A8/N13）、 {@link
 *       #grantOnExplicitlyDeclaredTargetWithoutLandingIsRejected()}（A5）。
 * </ul>
 */
class WorkspaceRegistryTest {

  @TempDir Path tempDir;

  private Path root;
  private CapabilityCatalog catalog;
  private Map<String, String> lineage;

  @BeforeEach
  void setUp() throws IOException {
    root = Files.createDirectories(tempDir.resolve("data"));
    catalog = WorkspaceFixture.catalog();
    lineage = new LinkedHashMap<>();
    lineage.put("main", "main");
    lineage.put("a1", "main/a1");
    lineage.put("b1", "main/a1/b1");
    lineage.put("other", "other");
  }

  // ────────────────────────────── 正向 ──────────────────────────────

  /** §4.11 正向：装配期挂载后 {@code resolve} 能取到物理落点；{@code list} 投影出虚拟条目。 */
  @Test
  void resolveAndListProjectVirtualPathsOnly() throws IOException {
    Path dir = Files.createDirectories(root.resolve("docs"));
    Files.createDirectories(dir.resolve("notes"));
    Files.writeString(dir.resolve("a.md"), "x");
    WorkspaceCaller caller = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry =
        WorkspaceFixture.mounted(
            catalog,
            lineage,
            WorkspaceFixture.assembly("docs", "main", "main", dir, Kind.FILE),
            caller);

    assertThat(registry.resolve("docs/notes/a.md", caller)).isEqualTo(dir.resolve("notes/a.md"));
    assertThat(registry.resolve("docs", caller)).isEqualTo(dir);
    List<String> listed = registry.list("docs", caller);
    assertThat(listed).containsExactly("docs/a.md", "docs/notes");
    assertThat(String.join("\n", listed)).doesNotContain(root.toAbsolutePath().toString());
  }

  /** §4.11 正向：{@code grant} 后子体权限集里确有该前缀，且不动其它维度。 */
  @Test
  void grantLandsPrefixesAndKeepsOtherDimensions() throws IOException {
    Path dir = Files.createDirectories(root.resolve("workspaces").resolve("a1"));
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry =
        WorkspaceFixture.mounted(
            catalog,
            lineage,
            WorkspaceFixture.assembly("workspace", "main", "a1", dir, Kind.LOG),
            main);

    AgentPermissionSet target =
        WorkspaceFixture.permissions(Map.of("fs", ResourceScope.ofDir(root)));
    AgentPermissionSet landed = registry.grant("a1", target);

    assertThat(
            landed
                .resourceScopes()
                .declaredScope(WorkspaceFixture.DOCS)
                .covers(ResourceScope.ofDir(dir)))
        .isTrue();
    assertThat(
            landed
                .resourceScopes()
                .declaredScope(WorkspaceFixture.DOCS_EDIT)
                .covers(ResourceScope.ofDir(dir)))
        .isTrue();
    assertThat(landed.resourceScopes().declaredScope("fs"))
        .as("其它命名空间逐字保留")
        .isEqualTo(target.resourceScopes().declaredScope("fs"));
  }

  /** §4.11 正向：子体在自己的工作区目录内为孙代划更小工作区成功，孙代再划一层成功（W1 递归）。 */
  @Test
  void grandchildWorkspaceInsideChildWorkspaceSucceeds() throws IOException {
    Path mainDir = Files.createDirectories(root.resolve("workspaces").resolve("main"));
    Path childDir = Files.createDirectories(mainDir.resolve("a1"));
    Path grandDir = Files.createDirectories(childDir.resolve("b1"));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    registry.mount(
        WorkspaceFixture.assembly("workspace", "main", "main", mainDir, Kind.FILE), main);

    WorkspaceCaller a1 = new WorkspaceCaller("a1", WorkspaceFixture.fullFaces(root));
    Mount child =
        registry.mount(WorkspaceFixture.assembly("workspace", "a1", "a1", childDir, Kind.FILE), a1);
    assertThat(child.user()).isEqualTo("a1");
    assertThat(child.holder()).as("holder 恒为创建者本人").isEqualTo("a1");

    WorkspaceCaller b1 = new WorkspaceCaller("b1", WorkspaceFixture.fullFaces(root));
    Mount grand =
        registry.mount(WorkspaceFixture.assembly("workspace", "b1", "b1", grandDir, Kind.FILE), b1);
    assertThat(grand.dir()).isEqualTo(grandDir);
    assertThat(registry.resolve("workspace", b1)).isEqualTo(grandDir);
  }

  /** §4.11 正向：{@code unmount} 后名字彻底不可解析，而目录仍在（文档是数据）。 */
  @Test
  void unmountKeepsDirectoryAndKillsResolution() throws IOException {
    Path dir = Files.createDirectories(root.resolve("workspaces").resolve("main"));
    Files.writeString(dir.resolve("keep.md"), "数据仍在");
    WorkspaceCaller caller = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry =
        WorkspaceFixture.mounted(
            catalog,
            lineage,
            WorkspaceFixture.assembly("workspace", "main", "main", dir, Kind.FILE),
            caller);

    registry.unmount(new MountName("workspace"), caller);

    assertThatThrownBy(() -> registry.resolve("workspace/keep.md", caller))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.WORKSPACE_NOT_FOUND);
    assertThat(Files.readString(dir.resolve("keep.md"))).isEqualTo("数据仍在");
    assertThat(registry.mounts("main")).isEmpty();
  }

  /** §4.11 正向：运行期 {@code mount_workspace} 成功并落事件（M8）。 */
  @Test
  void runtimeMountLandsAuditEvent() throws IOException {
    Path dir = Files.createDirectories(root.resolve("runtime"));
    WorkspaceCaller caller = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    List<WorkspaceEvent> sunk = new ArrayList<>();
    DefaultWorkspaceRegistry registry =
        DefaultWorkspaceRegistry.create(catalog, sunk::add, lineage::get);

    registry.mount(WorkspaceFixture.runtime("scratch", "main", "main", dir, Kind.SCRATCH), caller);

    assertThat(registry.events())
        .extracting(WorkspaceEvent::type)
        .containsExactly(WorkspaceEvent.MOUNTED);
    assertThat(sunk).extracting(WorkspaceEvent::type).containsExactly(WorkspaceEvent.MOUNTED);
    assertThat(sunk.get(0).detail()).as("事件里留物理落点（G4：只进日志与审计）").contains(dir.toString());
  }

  /** §4.11 负向（A2/M1）：越界创建被拒——物理范围不在 {@code workspaces} 面内。 */
  @Test
  void mountOutsideWorkspacesFaceIsRejected() throws IOException {
    Path allowed = Files.createDirectories(root.resolve("allowed"));
    Path outside = Files.createDirectories(tempDir.resolve("outside"));
    WorkspaceCaller caller =
        new WorkspaceCaller(
            "main",
            WorkspaceFixture.permissions(
                Map.of(
                    WorkspacePermissions.WORKSPACES,
                    ResourceScope.ofDir(allowed),
                    WorkspaceFixture.DOCS,
                    ResourceScope.ofDir(outside),
                    WorkspaceFixture.DOCS_EDIT,
                    ResourceScope.ofDir(outside))));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);

    assertThatThrownBy(
            () ->
                registry.mount(
                    WorkspaceFixture.assembly("docs", "main", "main", outside, Kind.FILE), caller))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
    assertThat(registry.mounts("main")).as("被拒的挂载不留空壳").isEmpty();
    assertThat(registry.events()).extracting(WorkspaceEvent::type).contains(WorkspaceEvent.DENIED);
  }

  /** §4.11 负向（M2）：给非后代挂载被拒（{@code user} 必须落在自己的血缘子树内）。 */
  @Test
  void mountForNonDescendantIsRejected() throws IOException {
    Path dir = Files.createDirectories(root.resolve("elsewhere"));
    WorkspaceCaller caller = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);

    assertThatThrownBy(
            () ->
                registry.mount(
                    WorkspaceFixture.assembly("workspace", "main", "other", dir, Kind.FILE),
                    caller))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
  }

  /** §4.11 负向（M2）：{@code holder} 恒为创建者本人——不许代他人署名。 */
  @Test
  void holderMustBeTheCallerHimself() throws IOException {
    Path dir = Files.createDirectories(root.resolve("signed"));
    WorkspaceCaller caller = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);

    assertThatThrownBy(
            () ->
                registry.mount(
                    WorkspaceFixture.assembly("workspace", "a1", "a1", dir, Kind.FILE), caller))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
  }

  /** §4.11 负向（M3）：未注册的能力 id 被拒——不落格的能力不许声明。 */
  @Test
  void unregisteredCapabilityIsRejected() throws IOException {
    Path dir = Files.createDirectories(root.resolve("docs"));
    WorkspaceCaller caller = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);

    assertThatThrownBy(
            () ->
                registry.mount(
                    WorkspaceFixture.mount(
                        "docs",
                        "main",
                        "main",
                        dir,
                        Kind.LOG,
                        Set.of("no-such-capability"),
                        Origin.ASSEMBLY),
                    caller))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
  }

  /** §4.11 负向（M1/M3 第二半）：能力落格后必须仍 ⊆ 调用者面——只有 {@code workspaces} 面不够。 */
  @Test
  void capabilityLandingBeyondCallerFaceIsRejected() throws IOException {
    Path dir = Files.createDirectories(root.resolve("docs"));
    WorkspaceCaller caller = new WorkspaceCaller("main", WorkspaceFixture.mountFaceOnly(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);

    assertThatThrownBy(
            () ->
                registry.mount(
                    WorkspaceFixture.assembly("docs", "main", "main", dir, Kind.LOG), caller))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
  }

  /** §4.11 负向（W1/M9）：子工作区 = 自己的工作区根 ⇒ 拒（"严格更小"）。 */
  @Test
  void childWorkspaceEqualToOwnRootIsRejected() throws IOException {
    Path mainDir = Files.createDirectories(root.resolve("workspaces").resolve("main"));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    registry.mount(
        WorkspaceFixture.assembly("workspace", "main", "main", mainDir, Kind.FILE), main);

    assertThatThrownBy(
            () ->
                registry.mount(
                    WorkspaceFixture.assembly("workspace", "main", "main", mainDir, Kind.FILE),
                    main))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
    assertThat(registry.mounts("main")).hasSize(1);
  }

  /**
   * §4.11 负向（W2："只能在自己的工作区目录内划"）：子体的 {@code workspaces} 面在 spawn 时被收窄到自己的工作区落点 （{@code
   * SubagentManager.effectiveScopes}），因此"在外面划"命中 M1 ⇒ 拒。
   */
  @Test
  void childWorkspaceOutsideOwnWorkspaceIsRejected() throws IOException {
    Path mainDir = Files.createDirectories(root.resolve("workspaces").resolve("main"));
    Path childDir = Files.createDirectories(mainDir.resolve("a1"));
    Path sibling = Files.createDirectories(mainDir.resolve("sibling"));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    registry.mount(
        WorkspaceFixture.assembly("workspace", "main", "main", mainDir, Kind.FILE), main);
    registry.mount(WorkspaceFixture.assembly("workspace", "main", "a1", childDir, Kind.FILE), main);

    // 子体：workspaces 面 = 自己的工作区落点（spawn 侧 narrowTo 的结果）
    WorkspaceCaller a1 =
        new WorkspaceCaller(
            "a1",
            WorkspaceFixture.permissions(
                Map.of(
                    WorkspacePermissions.WORKSPACES,
                    ResourceScope.ofDir(childDir),
                    WorkspaceFixture.DOCS,
                    ResourceScope.ofDir(childDir),
                    WorkspaceFixture.DOCS_EDIT,
                    ResourceScope.ofDir(childDir))));

    assertThat(registry.resolve("workspace", a1)).as("自己的工作区可解析").isEqualTo(childDir);
    assertThatThrownBy(
            () ->
                registry.mount(
                    WorkspaceFixture.assembly("scratch", "a1", "a1", sibling, Kind.SCRATCH), a1))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
  }

  /** §4.11 负向（M11/W6）：子工作区声明的能力超出父在自己工作区上的能力 ⇒ 拒。 */
  @Test
  void childCapabilityWiderThanParentIsRejected() throws IOException {
    Path mainDir = Files.createDirectories(root.resolve("workspaces").resolve("main"));
    Path childDir = Files.createDirectories(mainDir.resolve("a1"));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    registry.mount(
        WorkspaceFixture.assembly("workspace", "main", "main", mainDir, Kind.FILE), main);

    // 父的 /workspace 只声明 docs；子声明 {docs, bash} ⇒ 超出父
    WorkspaceCaller a1 = new WorkspaceCaller("a1", WorkspaceFixture.fullFaces(root));
    assertThatThrownBy(
            () ->
                registry.mount(
                    WorkspaceFixture.mount(
                        "workspace",
                        "a1",
                        "a1",
                        childDir,
                        Kind.FILE,
                        Set.of(WorkspaceFixture.DOCS, WorkspaceFixture.BASH),
                        Origin.ASSEMBLY),
                    a1))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
  }

  /** §4.11 负向（M4/A4）：名字冲突 ⇒ 具名拒（不静默替换、不覆盖他人挂载）。 */
  @Test
  void mountNameConflictIsNamedRejection() throws IOException {
    Path first = Files.createDirectories(root.resolve("one"));
    Path second = Files.createDirectories(root.resolve("two"));
    WorkspaceCaller caller = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    registry.mount(WorkspaceFixture.assembly("docs", "main", "main", first, Kind.FILE), caller);

    assertThatThrownBy(
            () ->
                registry.mount(
                    WorkspaceFixture.assembly("docs", "main", "main", second, Kind.FILE), caller))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.WORKSPACE_NAME_CONFLICT);
    assertThat(registry.mounts("main")).singleElement().extracting(Mount::dir).isEqualTo(first);
  }

  /** §4.11 负向（M5）：使用者（非创建者）卸不掉父为自己挂的工作区。 */
  @Test
  void unmountByNonHolderIsRejected() throws IOException {
    Path childDir =
        Files.createDirectories(root.resolve("workspaces").resolve("main").resolve("a1"));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    registry.mount(WorkspaceFixture.assembly("workspace", "main", "a1", childDir, Kind.FILE), main);

    WorkspaceCaller a1 = new WorkspaceCaller("a1", WorkspaceFixture.fullFaces(root));
    assertThatThrownBy(() -> registry.unmount(new MountName("workspace"), a1))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
  }

  /** §4.11 负向（M5/§4.5）：卸一个不存在的名字与"无权卸"同码——不泄露存在性。 */
  @Test
  void unmountUnknownNameIsNotFound() {
    WorkspaceCaller caller = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);

    assertThatThrownBy(() -> registry.unmount(new MountName("nothing"), caller))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.WORKSPACE_NOT_FOUND);
  }

  /** §4.11 负向（M10/W4）：父挂载卸载 ⇒ **由它派生的**子工作区挂载一并失效，不回落、不静默。 */
  @Test
  void parentUnmountCascadesToChildren() throws IOException {
    Path mainDir = Files.createDirectories(root.resolve("workspaces").resolve("main"));
    Path childDir = Files.createDirectories(mainDir.resolve("a1"));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    registry.mount(
        WorkspaceFixture.assembly("workspace", "main", "main", mainDir, Kind.FILE), main);
    // 子体那块由 main（创建者）在 mainDir 内的真子目录上**派生**挂载：holder=main、user=a1、
    // derivedFrom=父自己那块。M10 修订后级联只沿派生链——按物理包含判会误伤独立挂载（R5 实测）。
    registry.mount(
        WorkspaceFixture.derived("workspace", "main", "a1", childDir, Kind.FILE, "workspace"),
        main);
    WorkspaceCaller a1 = new WorkspaceCaller("a1", WorkspaceFixture.fullFaces(root));
    assertThat(registry.resolve("workspace", a1)).isEqualTo(childDir);

    registry.unmount(new MountName("workspace"), main);

    assertThatThrownBy(() -> registry.resolve("workspace", a1))
        .as("级联失效后解析 fail-closed（不回落成物理路径）")
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.WORKSPACE_NOT_FOUND);
    assertThat(registry.mounts("a1")).isEmpty();
    assertThat(registry.events())
        .extracting(WorkspaceEvent::type)
        .contains(WorkspaceEvent.UNMOUNTED);
    assertThat(Files.isDirectory(childDir)).as("物理目录保留").isTrue();
  }

  /** §4.11 负向（G1）：resolver 指向授权范围之外 ⇒ 拒（resolver 不是越权后门）。 */
  @Test
  void resolverEscapeIsRejected() throws IOException {
    Path dir = Files.createDirectories(root.resolve("docs"));
    Resolver escaping =
        new Resolver() {
          @Override
          public Path resolve(Path base, String relative) {
            return tempDir.resolve("outside").resolve("steal.txt");
          }

          @Override
          public List<String> list(Path base, String relativePrefix) {
            return List.of();
          }
        };
    Mount spec =
        new Mount(
            new MountName("docs"),
            "main",
            "main",
            dir,
            escaping,
            Kind.FILE,
            Set.of(WorkspaceFixture.DOCS),
            Origin.ASSEMBLY);
    WorkspaceCaller caller = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    registry.mount(spec, caller);

    assertThatThrownBy(() -> registry.resolve("docs/steal.txt", caller))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
  }

  /** §4.11 负向（A7/G4）：面向模型的错误/投影里不出现物理路径。 */
  @Test
  void errorsNeverCarryPhysicalPaths() throws IOException {
    Path dir = Files.createDirectories(root.resolve("docs"));
    WorkspaceCaller caller = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);

    Throwable notFound = assertThatThrownBy(() -> registry.resolve("docs/x", caller)).actual();
    Throwable denied =
        assertThatThrownBy(
                () ->
                    registry.mount(
                        WorkspaceFixture.assembly("docs", "main", "main", dir, Kind.FILE),
                        new WorkspaceCaller("main", WorkspaceFixture.mountFaceOnly(root))))
            .actual();

    assertThat(notFound.getMessage()).doesNotContain(tempDir.toAbsolutePath().toString());
    assertThat(denied.getMessage()).doesNotContain(tempDir.toAbsolutePath().toString());
  }

  /** §4.11 负向（§4.2/G3）：虚拟路径拼法非法 ⇒ {@code INVALID_ARGUMENTS}（不是"挂载不存在"）。 */
  @Test
  void virtualPathShapesAreRejected() {
    WorkspaceCaller caller = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    List<String> bad =
        List.of("", " ", "/docs", "Docs/a", "docs//a", "docs/", "docs/./a", "docs/../a", "-docs");

    for (String path : bad) {
      assertThatThrownBy(() -> registry.resolve(path, caller))
          .as("虚拟路径 '%s' 应被判为入参非法", path)
          .isInstanceOf(WorkspaceException.class)
          .extracting(e -> ((WorkspaceException) e).code())
          .isEqualTo(WorkspaceException.INVALID_ARGUMENTS);
    }
  }

  /** §4.11 负向（M6）：运行期新建的挂载不进 bash 遮蔽面（shell 看不见它）。 */
  @Test
  void runtimeMountNeverEntersShellMask() throws IOException {
    Path assemblyDir = Files.createDirectories(root.resolve("assembly"));
    Path runtimeDir = Files.createDirectories(root.resolve("runtime"));
    WorkspaceCaller caller = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    registry.mount(
        WorkspaceFixture.assembly("docs", "main", "main", assemblyDir, Kind.FILE), caller);
    registry.mount(
        WorkspaceFixture.runtime("scratch", "main", "main", runtimeDir, Kind.SCRATCH), caller);

    List<MaskEntry> entries = registry.maskEntries();
    assertThat(entries)
        .extracting(MaskEntry::path)
        .contains(assemblyDir)
        .doesNotContain(runtimeDir);
    assertThat(entries).extracting(MaskEntry::namespace).contains("docs", "docs-edit");
  }

  /**
   * §4.11 负向（A3/A8/N13）：<b>v1 所有挂载在 shell 侧都不是"可写"</b>——没有 {@code bash} 能力（落 {@code fs} 面）⇒
   * 遮蔽面里只可能出"遮蔽"或"只读 bind"两档，且对未表态的调用者一律遮蔽。
   *
   * <p>判别性：若某条能力把落格命名空间写成 {@code fs}（= 让 shell 照旧可写），"遮蔽面里不得有 fs 面"这条断言会红。
   */
  @Test
  void docsMountsAreNeverWritableInShellFace() throws IOException {
    Path dir = Files.createDirectories(root.resolve("workspace"));
    Path project = Files.createDirectories(root.resolve("project"));
    // 装配方自己声明了技能面（否则技能根的挂载会被 M1/M3 拒）；遮蔽面是**逐调用者算**的：
    // 下面那个"能经 bash 写这片区域"的调用者并没有技能面 ⇒ 技能根对它依然遮蔽。
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFacesWithSkills(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    registry.mount(WorkspaceFixture.assembly("workspace", "main", "main", dir, Kind.FILE), main);
    registry.mount(
        WorkspaceFixture.mount(
            "project",
            "main",
            "main",
            project,
            Kind.REFERENCE,
            Set.of(WorkspaceFixture.SKILLS_READ),
            Origin.ASSEMBLY),
        main);

    assertThat(registry.maskEntries())
        .as("v1 的能力表里没有任何一条落到 fs 面（落 fs = shell 可写）")
        .extracting(MaskEntry::namespace)
        .doesNotContain("fs");

    Map<String, Object> maskConfig = maskConfigOf(registry.maskEntries());
    // 连"能经 bash 写这片区域"的调用者（fs 面覆盖 root）也必须落在围栏里：docs 面可见 ⇒ 只读 bind
    AgentPermissionSet bashCapable =
        AgentPermissionSet.system()
            .withResourceScopes(
                new io.mosire.agentlib.permission.ResourceScopeMap(
                    Map.of(
                        WorkspaceFixture.DOCS,
                        ResourceScope.ofDir(root),
                        WorkspaceFixture.DOCS_EDIT,
                        ResourceScope.ofDir(root),
                        "fs",
                        ResourceScope.ofDir(root))));
    SandboxMask.Fence fence = SandboxMask.fence(bashCapable, maskConfig);
    assertThat(fence.readOnly()).containsExactly(dir);
    assertThat(fence.masked()).as("skills 面未表态 ⇒ 技能根遮蔽").containsExactly(project);
    assertThat(fence.sandboxRequired()).isTrue();

    SandboxMask.Fence undeclared = SandboxMask.fence(AgentPermissionSet.system(), maskConfig);
    assertThat(undeclared.masked()).as("未表态 ⇒ 整棵遮蔽").contains(dir, project);
    assertThat(undeclared.readOnly()).isEmpty();
  }

  /** §4.11 负向（A5）：目标权限集在该命名空间**已显式表态且不含落点** ⇒ 具名拒（不静默放大）。 */
  @Test
  void grantOnExplicitlyDeclaredTargetWithoutLandingIsRejected() throws IOException {
    Path dir = Files.createDirectories(root.resolve("workspaces").resolve("a1"));
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    registry.mount(WorkspaceFixture.assembly("workspace", "main", "a1", dir, Kind.FILE), main);

    AgentPermissionSet explicitNone =
        WorkspaceFixture.permissions(
            Map.of(
                WorkspaceFixture.DOCS, ResourceScope.none(),
                WorkspaceFixture.DOCS_EDIT, ResourceScope.none()));
    assertThatThrownBy(() -> registry.grant("a1", explicitNone))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);

    AgentPermissionSet otherLanding =
        WorkspaceFixture.permissions(
            Map.of(
                WorkspaceFixture.DOCS, ResourceScope.ofDir(tempDir.resolve("elsewhere")),
                WorkspaceFixture.DOCS_EDIT, ResourceScope.ofDir(tempDir.resolve("elsewhere"))));
    assertThatThrownBy(() -> registry.grant("a1", otherLanding))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);

    // 未表态 ⇒ 可以补进（grant 的正向那一半）
    AgentPermissionSet undeclared =
        WorkspaceFixture.permissions(Map.of("fs", ResourceScope.ofDir(root)));
    assertThat(
            registry.grant("a1", undeclared).resourceScopes().declaredScope(WorkspaceFixture.DOCS))
        .isNotNull();
  }

  /** §4.11 负向：不在自己切片里的挂载名 ⇒ {@code WORKSPACE_NOT_FOUND}（与"不可见"同码）。 */
  @Test
  void unknownMountIsNotFoundNotDenied() throws IOException {
    Path dir = Files.createDirectories(root.resolve("docs"));
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    registry.mount(WorkspaceFixture.assembly("docs", "main", "main", dir, Kind.FILE), main);

    WorkspaceCaller stranger = new WorkspaceCaller("stranger", WorkspaceFixture.fullFaces(root));
    assertThatThrownBy(() -> registry.resolve("docs/x", stranger))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.WORKSPACE_NOT_FOUND);
  }

  /** §4.11 负向（R2 交接项 4）：{@code list} 对缺失/非目录返回 {@code WORKSPACE_NOT_FOUND}。 */
  @Test
  void listOnMissingDirectoryIsNotFound() throws IOException {
    Path dir = Files.createDirectories(root.resolve("docs"));
    Files.writeString(dir.resolve("a.md"), "x");
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    registry.mount(WorkspaceFixture.assembly("docs", "main", "main", dir, Kind.FILE), main);

    assertThatThrownBy(() -> registry.list("docs/nope", main))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.WORKSPACE_NOT_FOUND);
    assertThatThrownBy(() -> registry.list("docs/a.md", main))
        .as("不是目录 ⇒ 同码")
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.WORKSPACE_NOT_FOUND);
  }

  /** A8 的另一半：能力集为空 ⇒ {@code grant} 不落格、遮蔽面无条目、解析拒（谁都不持有该能力）。 */
  @Test
  void emptyCapabilityMountLandsNothing() throws IOException {
    Path dir = Files.createDirectories(root.resolve("docs"));
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    registry.mount(
        WorkspaceFixture.mount("docs", "main", "main", dir, Kind.FILE, Set.of(), Origin.ASSEMBLY),
        main);

    AgentPermissionSet landed =
        registry.grant(
            "main", WorkspaceFixture.permissions(Map.of("fs", ResourceScope.ofDir(root))));

    assertThat(landed.resourceScopes().declaredScope(WorkspaceFixture.DOCS)).isNull();
    assertThat(registry.maskEntries()).isEmpty();
    assertThatThrownBy(() -> registry.resolve("docs/x", main))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
  }

  /** 遮蔽面清单稳定排序（同一路径的不同命名空间/纪律必须都留着——G7 的逐条目求与正是靠它们）。 */
  @Test
  void maskEntriesAreStableAndSorted() throws IOException {
    Path dir = Files.createDirectories(root.resolve("docs"));
    Path nested = Files.createDirectories(dir.resolve("sub"));
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    registry.mount(WorkspaceFixture.assembly("docs", "main", "main", dir, Kind.FILE), main);
    registry.mount(
        WorkspaceFixture.assembly("skills", "main", "main", nested, Kind.REFERENCE), main);

    assertThat(registry.maskEntries())
        .extracting(entry -> entry.namespace() + "@" + entry.kind())
        .containsExactly("docs@FILE", "docs@REFERENCE", "docs-edit@FILE", "docs-edit@REFERENCE");
  }

  /**
   * G7 的另一半：同一物理路径被<b>两个命名空间</b>的条目声明 ⇒ 交给 {@link SandboxMask} 的逐条目求与——任一命名空间不可见 即遮蔽（"遮蔽压过只读"）。
   *
   * <p>两条挂载分属两个使用者切片（同一路径在同一使用者下不可能有两条同名挂载）：一个 {@code docs} 能力、一个 {@code skills-read}
   * 能力，遮蔽面是全局的（{@code maskEntries()} 汇所有装配期切片）。
   */
  @Test
  void samePathDeclaredByTwoNamespacesIsMaskedWhenOneInvisible() throws IOException {
    Path dir = Files.createDirectories(root.resolve("shared"));
    WorkspaceCaller main = new WorkspaceCaller("main", WorkspaceFixture.fullFacesWithSkills(root));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    registry.mount(WorkspaceFixture.assembly("workspace", "main", "main", dir, Kind.FILE), main);
    WorkspaceCaller a1 = new WorkspaceCaller("a1", WorkspaceFixture.fullFacesWithSkills(root));
    registry.mount(
        WorkspaceFixture.mount(
            "skills",
            "a1",
            "a1",
            dir,
            Kind.REFERENCE,
            Set.of(WorkspaceFixture.SKILLS_READ),
            Origin.ASSEMBLY),
        a1);
    assertThat(registry.maskEntries()).extracting(MaskEntry::namespace).contains("docs", "skills");

    // ① 调用者只有 docs 面（没有 skills 面）⇒ 逐条目求与后仍是遮蔽
    AgentPermissionSet docsOnly =
        AgentPermissionSet.system()
            .withResourceScopes(
                new io.mosire.agentlib.permission.ResourceScopeMap(
                    Map.of(
                        WorkspaceFixture.DOCS, ResourceScope.ofDir(root),
                        WorkspaceFixture.DOCS_EDIT, ResourceScope.ofDir(root))));
    SandboxMask.Fence half = SandboxMask.fence(docsOnly, maskConfigOf(registry.maskEntries()));
    assertThat(half.masked()).contains(dir);
    assertThat(half.readOnly()).isEmpty();

    // ② 反过来（两条都可见）⇒ 只读（求与的另一端）
    SandboxMask.Fence both =
        SandboxMask.fence(
            AgentPermissionSet.system()
                .withResourceScopes(
                    new io.mosire.agentlib.permission.ResourceScopeMap(
                        Map.of(
                            WorkspaceFixture.DOCS, ResourceScope.ofDir(root),
                            WorkspaceFixture.DOCS_EDIT, ResourceScope.ofDir(root),
                            WorkspaceFixture.SKILLS, ResourceScope.ofDir(root)))),
            maskConfigOf(registry.maskEntries()));
    assertThat(both.readOnly()).containsExactly(dir);
    assertThat(both.masked()).isEmpty();
  }

  /** 遮蔽面条目 → {@link SandboxMask} 的配置形状（生产装配 {@code App.maskConfigOf} 同形）。 */
  private static Map<String, Object> maskConfigOf(List<MaskEntry> entries) {
    List<Map<String, Object>> config = new ArrayList<>(entries.size());
    for (MaskEntry entry : entries) {
      config.add(
          Map.of(
              SandboxMask.NAMESPACE_KEY,
              entry.namespace(),
              SandboxMask.PATH_KEY,
              entry.path().toString()));
    }
    return Map.of(SandboxMask.CONFIG_KEY, config);
  }
}
