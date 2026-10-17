package io.mosire.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.ResourceScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspaceRegistry#resolveForIo} 的安全判据：软链文件、软链父目录、软链挂载根、CREATE 路由。
 *
 * <p>这些用例的目标不是“能解析”，而是“不能经软链逃出挂载真实根”；失败断言看稳定错误码，且异常消息不得出现物理路径。
 */
class WorkspacePathSecurityTest {

  @TempDir Path tempDir;

  private Path root;
  private CapabilityCatalog catalog;
  private Map<String, String> lineage;

  @BeforeEach
  void setUp() throws IOException {
    root = Files.createDirectories(tempDir.resolve("data"));
    catalog = WorkspaceFixture.catalog();
    lineage = Map.of("main", "main");
  }

  @Test
  void readRejectsSymlinkFileOutsideMount() throws IOException {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path outside = Files.createDirectories(tempDir.resolve("outside"));
    Path secret = Files.writeString(outside.resolve("secret.md"), "SECRET");
    symlink(workspace.resolve("link.md"), secret);

    DefaultWorkspaceRegistry registry = mountedWorkspace(workspace);
    WorkspaceCaller caller = caller();

    assertThatThrownBy(
            () ->
                registry.resolveForIo(
                    "workspace/link.md", caller, IoMode.READ, SymlinkPolicy.REJECT))
        .isInstanceOf(WorkspaceException.class)
        .satisfies(
            error -> {
              WorkspaceException workspaceError = (WorkspaceException) error;
              assertThat(workspaceError.code()).isEqualTo(WorkspaceException.RESOURCE_DENIED);
              assertThat(workspaceError.getMessage()).doesNotContain(tempDir.toString());
            });
  }

  @Test
  void readAllowsSymlinkOnlyWhenExplicitlyResolvedWithinRoot() throws IOException {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path real = Files.writeString(workspace.resolve("real.md"), "ok");
    symlink(workspace.resolve("link.md"), real);

    DefaultWorkspaceRegistry registry = mountedWorkspace(workspace);
    WorkspaceCaller caller = caller();

    assertThatThrownBy(
            () ->
                registry.resolveForIo(
                    "workspace/link.md", caller, IoMode.READ, SymlinkPolicy.REJECT))
        .isInstanceOf(WorkspaceException.class);

    ResolvedPath resolved =
        registry.resolveForIo(
            "workspace/link.md", caller, IoMode.READ, SymlinkPolicy.RESOLVE_WITHIN_ROOT);
    assertThat(resolved.exists()).isTrue();
    assertThat(resolved.realPath()).isEqualTo(real.toRealPath());
  }

  @Test
  void createRejectsSymlinkParentOutsideMountAndWritesNothing() throws IOException {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path outside = Files.createDirectories(tempDir.resolve("outside"));
    symlink(workspace.resolve("linkdir"), outside);

    DefaultWorkspaceRegistry registry = mountedWorkspace(workspace);
    WorkspaceCaller caller = caller();

    assertThatThrownBy(
            () ->
                registry.resolveForIo(
                    "workspace/linkdir/escaped.txt", caller, IoMode.CREATE, SymlinkPolicy.REJECT))
        .isInstanceOf(WorkspaceException.class)
        .extracting(error -> ((WorkspaceException) error).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
    assertThat(outside.resolve("escaped.txt")).doesNotExist();
  }

  @Test
  void writeExistingRejectsSymlinkEvenWhenTargetIsInsideRoot() throws IOException {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path real = Files.writeString(workspace.resolve("real.md"), "ok");
    symlink(workspace.resolve("link.md"), real);

    DefaultWorkspaceRegistry registry = mountedWorkspace(workspace);
    WorkspaceCaller caller = caller();

    assertThatThrownBy(
            () ->
                registry.resolveForIo(
                    "workspace/link.md", caller, IoMode.WRITE_EXISTING, SymlinkPolicy.REJECT))
        .isInstanceOf(WorkspaceException.class);
  }

  @Test
  void mountRootSymlinkCannotEscapeCallerFace() throws IOException {
    Path outside = Files.createDirectories(tempDir.resolve("outside"));
    Path link = root.resolve("workspace-link");
    symlink(link, outside);

    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    assertThatThrownBy(
            () ->
                registry.mount(
                    WorkspaceFixture.assembly("workspace", "main", "main", link, Kind.FILE),
                    caller()))
        .isInstanceOf(WorkspaceException.class)
        .extracting(error -> ((WorkspaceException) error).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
    assertThat(registry.mounts("main")).isEmpty();
  }

  @Test
  void createResolvesNonExistingTargetUnderRealMountRoot() throws IOException {
    Path workspace = Files.createDirectories(root.resolve("workspace"));

    DefaultWorkspaceRegistry registry = mountedWorkspace(workspace);
    WorkspaceCaller caller = caller();

    ResolvedPath resolved =
        registry.resolveForIo(
            "workspace/future/notes.md", caller, IoMode.CREATE, SymlinkPolicy.REJECT);

    assertThat(resolved.exists()).isFalse();
    assertThat(resolved.realPath().startsWith(workspace.toRealPath())).isTrue();
    assertThat(resolved.physicalPath()).isEqualTo(workspace.resolve("future/notes.md"));
  }

  /** 契约 §4.1/§9.5 后半：{@code RESOLVE_WITHIN_ROOT} 只是“允许软链”，真实目标仍必须在挂载真实根内。 */
  @Test
  void resolveWithinRootStillRejectsSymlinkTargetOutsideMount() throws IOException {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path outside = Files.createDirectories(tempDir.resolve("outside"));
    Path secret = Files.writeString(outside.resolve("secret.md"), "SECRET");
    symlink(workspace.resolve("link.md"), secret);

    DefaultWorkspaceRegistry registry = mountedWorkspace(workspace);
    WorkspaceCaller caller = caller();

    assertThatThrownBy(
            () ->
                registry.resolveForIo(
                    "workspace/link.md", caller, IoMode.READ, SymlinkPolicy.RESOLVE_WITHIN_ROOT))
        .isInstanceOf(WorkspaceException.class)
        .satisfies(
            error -> {
              WorkspaceException workspaceError = (WorkspaceException) error;
              assertThat(workspaceError.code()).isEqualTo(WorkspaceException.RESOURCE_DENIED);
              assertThat(workspaceError.getMessage()).doesNotContain(tempDir.toString());
            });
  }

  /** 契约 §4.2/§9.2 后半：{@code WRITE_EXISTING} 经软链父目录同样拒，外部文件不被触碰。 */
  @Test
  void writeExistingRejectsSymlinkParentOutsideMountAndLeavesFileUntouched() throws IOException {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path outside = Files.createDirectories(tempDir.resolve("outside"));
    Path target = Files.writeString(outside.resolve("escaped.txt"), "original");
    symlink(workspace.resolve("linkdir"), outside);

    DefaultWorkspaceRegistry registry = mountedWorkspace(workspace);
    WorkspaceCaller caller = caller();

    assertThatThrownBy(
            () ->
                registry.resolveForIo(
                    "workspace/linkdir/escaped.txt",
                    caller,
                    IoMode.WRITE_EXISTING,
                    SymlinkPolicy.REJECT))
        .isInstanceOf(WorkspaceException.class)
        .extracting(error -> ((WorkspaceException) error).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
    assertThat(Files.readString(target)).isEqualTo("original");
  }

  /** 契约 §9.4：挂载根是软链、真实目标在权限内 ⇒ 按真实根工作，投影不泄露物理路径。 */
  @Test
  void mountRootSymlinkInsideFaceWorksOnRealRoot() throws IOException {
    Path real = Files.createDirectories(root.resolve("real-workspace"));
    Files.writeString(real.resolve("a.md"), "ok");
    Path link = root.resolve("workspace-link");
    symlink(link, real);

    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    registry.mount(
        WorkspaceFixture.assembly("workspace", "main", "main", link, Kind.FILE), caller());

    ResolvedPath resolved =
        registry.resolveForIo("workspace/a.md", caller(), IoMode.READ, SymlinkPolicy.REJECT);
    assertThat(resolved.exists()).isTrue();
    assertThat(resolved.realPath()).isEqualTo(real.toRealPath().resolve("a.md"));
    assertThat(
            registry
                .resolveForIo("workspace", caller(), IoMode.READ, SymlinkPolicy.REJECT)
                .realPath())
        .as("挂载根本身也可以经真实根解析")
        .isEqualTo(real.toRealPath());
    assertThat(registry.list("workspace", caller()))
        .as("投影只出虚拟路径，不泄露物理落点")
        .containsExactly("workspace/a.md");
  }

  /** 目录列举也是读取：软链目录不能把挂载外的条目投影出来（契约 §1“唯一解析点”）。 */
  @Test
  void listRejectsSymlinkDirectoryOutsideMount() throws IOException {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path outside = Files.createDirectories(tempDir.resolve("outside"));
    Files.writeString(outside.resolve("secret.md"), "SECRET");
    symlink(workspace.resolve("linkdir"), outside);

    DefaultWorkspaceRegistry registry = mountedWorkspace(workspace);

    assertThatThrownBy(() -> registry.list("workspace/linkdir", caller()))
        .isInstanceOf(WorkspaceException.class)
        .satisfies(
            error -> {
              WorkspaceException workspaceError = (WorkspaceException) error;
              assertThat(workspaceError.code()).isEqualTo(WorkspaceException.RESOURCE_DENIED);
              assertThat(workspaceError.getMessage()).doesNotContain(tempDir.toString());
            });
  }

  /** 空能力挂载保持既有语义：可登记，但不落格、不进遮蔽面，安全 IO 解析仍按“没有能力”具名拒。 */
  @Test
  void emptyCapabilityMountStaysRegisteredButSecureIoIsDenied() throws IOException {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    registry.mount(
        WorkspaceFixture.mount(
            "workspace", "main", "main", workspace, Kind.FILE, Set.of(), Origin.ASSEMBLY),
        caller());

    assertThat(registry.mounts("main")).hasSize(1);
    assertThat(registry.maskEntries()).isEmpty();
    assertThatThrownBy(
            () ->
                registry.resolveForIo(
                    "workspace/notes.md", caller(), IoMode.CREATE, SymlinkPolicy.REJECT))
        .isInstanceOf(WorkspaceException.class)
        .extracting(error -> ((WorkspaceException) error).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
  }

  /**
   * follow-up（task-2）：{@code workspaces} 是“创建挂载”的面，不是“读取已有挂载”的面——只有 docs/docs-edit 面、没有 workspaces
   * 面的调用者，对别人已挂载的目录做 READ / CREATE 必须成功。
   */
  @Test
  void capabilityOnlyCallerReadsAndCreatesWithoutWorkspacesFace() throws IOException {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Files.writeString(workspace.resolve("a.md"), "ok");
    DefaultWorkspaceRegistry registry = mountedWorkspace(workspace);
    WorkspaceCaller capabilityOnly = capabilityOnlyCaller(root);

    ResolvedPath read =
        registry.resolveForIo("workspace/a.md", capabilityOnly, IoMode.READ, SymlinkPolicy.REJECT);
    assertThat(read.exists()).isTrue();
    assertThat(read.realPath()).isEqualTo(workspace.toRealPath().resolve("a.md"));

    ResolvedPath create =
        registry.resolveForIo(
            "workspace/new/notes.md", capabilityOnly, IoMode.CREATE, SymlinkPolicy.REJECT);
    assertThat(create.exists()).isFalse();
    assertThat(create.realPath())
        .isEqualTo(workspace.toRealPath().resolve("new").resolve("notes.md"));
    assertThat(registry.list("workspace", capabilityOnly))
        .as("list 的安全前置同样只依赖 capability 面")
        .containsExactly("workspace/a.md");
  }

  /** 去掉 workspaces 要求不等于去掉 capability 判据：capability 面覆盖不到挂载落点时仍具名拒。 */
  @Test
  void capabilityOnlyCallerOutsideCapabilityFaceIsStillDenied() throws IOException {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
    DefaultWorkspaceRegistry registry = mountedWorkspace(workspace);
    WorkspaceCaller noDocsFace = capabilityOnlyCaller(elsewhere);

    assertThatThrownBy(
            () ->
                registry.resolveForIo(
                    "workspace/a.md", noDocsFace, IoMode.READ, SymlinkPolicy.REJECT))
        .isInstanceOf(WorkspaceException.class)
        .extracting(error -> ((WorkspaceException) error).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
  }

  /**
   * 去掉 workspaces 要求后，capability 的<b>真实根</b>检查还在：调用者的 capability 面覆盖词法挂载点、但真实根经软链跑到面外 ⇒ 拒。
   *
   * <p>挂载由宽面调用者完成（docs + workspaces 都覆盖 {@code tempDir}）；读取者只有 docs 面且只覆盖 {@code root}。挂载根选“尚不存在的
   * 目录”，这样 {@code ResourceScope.ofDir} 只能做词法比较、而 {@code realMountRoot} 会经软链父目录算出真实根——两条路径分叉，拒只能来自
   * {@code admitRealRoot}。
   */
  @Test
  void capabilityOnlyCallerCannotResolveRealRootOutsideCapabilityFace() throws IOException {
    Files.createDirectories(tempDir.resolve("outside").resolve("sub"));
    Path link = root.resolve("escaped-root");
    symlink(link, tempDir.resolve("outside"));
    Path mountDir = link.resolve("sub").resolve("missing-workspace");
    assertThat(mountDir).doesNotExist();

    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    WorkspaceCaller wideCaller = new WorkspaceCaller("main", WorkspaceFixture.fullFaces(tempDir));
    registry.mount(
        WorkspaceFixture.assembly("workspace", "main", "main", mountDir, Kind.FILE), wideCaller);

    assertThatThrownBy(
            () ->
                registry.resolveForIo(
                    "workspace/x.md",
                    capabilityOnlyCaller(root),
                    IoMode.CREATE,
                    SymlinkPolicy.REJECT))
        .isInstanceOf(WorkspaceException.class)
        .extracting(error -> ((WorkspaceException) error).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
  }

  /** M1 不放宽：只有 capability 面、没有 workspaces 面的调用者依然不能创建挂载。 */
  @Test
  void mountStillRequiresWorkspacesFaceForCapabilityOnlyCaller() throws IOException {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    WorkspaceCaller capabilityOnly = capabilityOnlyCaller(root);

    assertThatThrownBy(
            () ->
                registry.mount(
                    WorkspaceFixture.assembly("workspace", "main", "main", workspace, Kind.FILE),
                    capabilityOnly))
        .isInstanceOf(WorkspaceException.class)
        .extracting(error -> ((WorkspaceException) error).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
    assertThat(registry.mounts("main")).isEmpty();
  }

  /**
   * M1 的真实根检查也仍在挂载期生效：词法落点在 workspaces 面内、真实落点经软链跑到面外 ⇒ 拒。
   *
   * <p>docs 面故意放到整个 {@code tempDir}（覆盖真实落点），这样拒只能来自 M1 的 workspaces 真实根检查，而不是 capability 面的 {@code
   * admitRealRoot}——用例才能判别“M1 没有随 follow-up 一起放宽”。
   */
  @Test
  void mountStillRejectsRealRootOutsideWorkspacesFaceWhenLexicalPathLooksInside()
      throws IOException {
    Files.createDirectories(tempDir.resolve("outside").resolve("sub"));
    Path link = root.resolve("escaped-root");
    symlink(link, tempDir.resolve("outside"));
    Path declared = link.resolve("sub").resolve("child");
    assertThat(declared).doesNotExist();

    DefaultWorkspaceRegistry registry = WorkspaceFixture.registry(catalog, lineage);
    assertThatThrownBy(
            () ->
                registry.mount(
                    WorkspaceFixture.assembly("workspace", "main", "main", declared, Kind.FILE),
                    mountFaceRootDocsWideCaller()))
        .isInstanceOf(WorkspaceException.class)
        .extracting(error -> ((WorkspaceException) error).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);
    assertThat(registry.mounts("main")).isEmpty();
  }

  private DefaultWorkspaceRegistry mountedWorkspace(Path workspace) {
    return WorkspaceFixture.mounted(
        catalog,
        lineage,
        WorkspaceFixture.assembly("workspace", "main", "main", workspace, Kind.FILE),
        caller());
  }

  /** 只有 docs / docs-edit 两个 capability 面（显式不表态 workspaces ⇒ 没有创建挂载的面）。 */
  private WorkspaceCaller capabilityOnlyCaller(Path... roots) {
    ResourceScope scope = ResourceScope.of(WorkspaceFixture.prefixes(roots));
    Map<String, ResourceScope> scopes = new LinkedHashMap<>();
    scopes.put(WorkspaceFixture.DOCS, scope);
    scopes.put(WorkspaceFixture.DOCS_EDIT, scope);
    return new WorkspaceCaller("main", WorkspaceFixture.permissions(scopes));
  }

  /** workspaces 面只覆盖 root，docs 面覆盖整个 tempDir：用于把 M1 真实根检查与 capability 面检查隔离开。 */
  private WorkspaceCaller mountFaceRootDocsWideCaller() {
    Map<String, ResourceScope> scopes = new LinkedHashMap<>();
    scopes.put(WorkspacePermissions.WORKSPACES, ResourceScope.ofDir(root));
    scopes.put(WorkspaceFixture.DOCS, ResourceScope.ofDir(tempDir));
    scopes.put(WorkspaceFixture.DOCS_EDIT, ResourceScope.ofDir(tempDir));
    return new WorkspaceCaller("main", WorkspaceFixture.permissions(scopes));
  }

  private WorkspaceCaller caller() {
    return new WorkspaceCaller("main", WorkspaceFixture.fullFaces(root));
  }

  private static void symlink(Path link, Path target) throws IOException {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException unsupported) {
      Assumptions.assumeTrue(false, "当前文件系统不支持软链，跳过该安全探针");
    }
  }
}
