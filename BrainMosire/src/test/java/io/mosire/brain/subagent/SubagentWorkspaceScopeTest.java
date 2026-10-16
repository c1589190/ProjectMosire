package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.docs.DocNamespaces;
import io.mosire.docs.DocsCapability;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.CapabilityCatalogs;
import io.mosire.workspace.DefaultWorkspaceRegistry;
import io.mosire.workspace.Kind;
import io.mosire.workspace.Mount;
import io.mosire.workspace.MountName;
import io.mosire.workspace.Origin;
import io.mosire.workspace.Resolvers;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceException;
import io.mosire.workspace.WorkspacePermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 子工作区在 spawn 链上的判据（设计 §4.7 子工作区表 W1–W6、§4.10 M9–M12、§5.7），全离线。
 *
 * <ul>
 *   <li>{@link #childWorkspaceIsATrueSubdirectoryAndFacesAreNarrowedToIt()}（W1/W5/M9：真子目录 +
 *       子体文档面被夹到它）；
 *   <li>{@link #childCannotMountOutsideItsOwnWorkspace()}（W2：在自己的工作区之外划 ⇒ 拒）；
 *   <li>{@link #depthLimitRejectsBeforeAnyMountIsRegistered()}（W3：深度闸复用 subagents.maxDepth，且在挂载之前）。
 * </ul>
 *
 * <p>W3 的闸门住在派生链而不是工作区模块（设计 §8.3 R2→R3b 交接项 3），因此这条只能在 spawn 面上验。
 */
class SubagentWorkspaceScopeTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private SqliteEventStore store;
  private EventBus bus;
  private Path agentsDir;
  private Path mainWorkspace;
  private CapabilityCatalog catalog;
  private Map<String, String> lineage;

  @BeforeEach
  void setUp() throws IOException {
    agentsDir = Files.createDirectories(tempDir.resolve("agents"));
    mainWorkspace = Files.createDirectories(tempDir.resolve("workspaces").resolve("main"));
    store = SqliteEventStore.open(tempDir.resolve("events.db"));
    bus = new EventBus();
    catalog = CapabilityCatalogs.inMemory();
    DocsCapability.register(catalog);
    lineage = new ConcurrentHashMap<>();
    lineage.put("main", "main");
  }

  @AfterEach
  void tearDown() {
    bus.close();
    store.close();
  }

  /** W1/W5/M9：子工作区落在自己的工作区内的真子目录；物化后的文档/挂载面被夹到它。 */
  @Test
  void childWorkspaceIsATrueSubdirectoryAndFacesAreNarrowedToIt() throws IOException {
    write(template("wsp"));
    DefaultWorkspaceRegistry registry = registryWithMainWorkspace();
    SubagentManager manager = manager(registry, SubagentLimits.defaults(), 0);

    SubagentInstance child =
        manager.spawn(
            new SubagentLaunchRequest("wsp", "划一块自己的地", Set.of(), null, null, null),
            AgentIdentity.main(CommandMode.FULL),
            parentPermissions());

    Path childDir = mainWorkspace.resolve(child.instanceId());
    assertThat(childDir.startsWith(mainWorkspace))
        .as("落点 = 自己的工作区目录下的真子目录（W1 严格更小：登记只落映射，目录由写文档的动作建）")
        .isTrue();
    assertThat(childDir).isNotEqualTo(mainWorkspace);

    assertThat(registry.mounts(child.instanceId()))
        .singleElement()
        .satisfies(
            mount -> {
              assertThat(mount.dir()).isEqualTo(childDir);
              assertThat(mount.name().value())
                  .as("W5：子体看到的仍是自己的 /workspace")
                  .isEqualTo("workspace");
              assertThat(mount.holder()).as("M2：holder 恒为创建者（父）").isEqualTo("main");
              assertThat(mount.user()).isEqualTo(child.instanceId());
              assertThat(mount.kind()).isEqualTo(Kind.FILE);
              assertThat(mount.origin()).as("M6：运行期挂载不进遮蔽面").isEqualTo(Origin.RUNTIME);
            });

    ResourceScope docs = declared(child, DocNamespaces.DOCS);
    assertThat(docs.covers(ResourceScope.ofDir(childDir))).as("能力在挂载时落格（A8）").isTrue();
    assertThat(docs.covers(ResourceScope.ofDir(mainWorkspace)))
        .as("W5：父的其它内容对子体不可见（面被夹到它自己的落点）")
        .isFalse();
    ResourceScope workspaces = declared(child, WorkspacePermissions.WORKSPACES);
    assertThat(workspaces.covers(ResourceScope.ofDir(childDir)))
        .as("W2 的边界：子体只能在自己的工作区目录内划")
        .isTrue();
    assertThat(workspaces.covers(ResourceScope.ofDir(mainWorkspace.resolve("sibling")))).isFalse();
  }

  /** W2：拿着物化后的面（= 只能在自己落点内划）去外面建挂载 ⇒ 拒。 */
  @Test
  void childCannotMountOutsideItsOwnWorkspace() throws IOException {
    write(template("wsp"));
    DefaultWorkspaceRegistry registry = registryWithMainWorkspace();
    SubagentManager manager = manager(registry, SubagentLimits.defaults(), 0);
    SubagentInstance child =
        manager.spawn(
            new SubagentLaunchRequest("wsp", "子体", Set.of(), null, null, null),
            AgentIdentity.main(CommandMode.FULL),
            parentPermissions());
    Path sibling = Files.createDirectories(mainWorkspace.resolve("sibling"));
    WorkspaceCaller childCaller = new WorkspaceCaller(child.instanceId(), child.permissions());

    assertThatThrownBy(
            () ->
                registry.mount(
                    new Mount(
                        new MountName("scratch"),
                        child.instanceId(),
                        child.instanceId(),
                        sibling,
                        Resolvers.direct(),
                        Kind.FILE,
                        Set.of(DocsCapability.ID),
                        Origin.RUNTIME),
                    childCaller))
        .isInstanceOf(WorkspaceException.class)
        .extracting(e -> ((WorkspaceException) e).code())
        .isEqualTo(WorkspaceException.RESOURCE_DENIED);

    // 反面：在自己的落点内划一块更小的 —— 允许（W1 的"严格更小"是这一段）
    Path grand = Files.createDirectories(mainWorkspace.resolve(child.instanceId()).resolve("sub"));
    Mount mounted =
        registry.mount(
            new Mount(
                new MountName("scratch"),
                child.instanceId(),
                child.instanceId(),
                grand,
                Resolvers.direct(),
                Kind.FILE,
                Set.of(DocsCapability.ID),
                Origin.RUNTIME),
            childCaller);
    assertThat(mounted.dir()).isEqualTo(grand);
  }

  /**
   * W3：深度超 {@code subagents.maxDepth} ⇒ 拒，且<b>不留下任何挂载</b>（闸在挂载之前）。
   *
   * <p>深度的对照物是<b>调用者身份的深度 + 1</b>（S5-A 锚点修正，见 {@code SubagentManagerTest} 的同名判据），
   * 因此这里把调用者本身放在上限那一层（manager 的 {@code parentDepth} 常量不参与判定）。
   */
  @Test
  void depthLimitRejectsBeforeAnyMountIsRegistered() throws IOException {
    write(template("wsp"));
    DefaultWorkspaceRegistry registry = registryWithMainWorkspace();
    int maxChildDepth = SubagentLimits.defaults().maxChildDepth();
    SubagentManager manager = manager(registry, SubagentLimits.defaults(), maxChildDepth);
    int before = registry.mounts("main").size();
    AgentIdentity atLimit =
        AgentIdentity.subagent("deep-1", CommandMode.FULL, "已经在上限层", maxChildDepth);

    assertThatThrownBy(
            () ->
                manager.spawn(
                    new SubagentLaunchRequest("wsp", "深链", Set.of(), null, null, null),
                    atLimit,
                    parentPermissions()))
        .isInstanceOf(SubagentRejectedException.class)
        .hasMessageContaining("深度");

    assertThat(registry.mounts("main")).as("超深度的 spawn 不许污染挂载表").hasSize(before);
    assertThat(registry.events()).isNotEmpty();
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 装配期挂载：父自己的 {@code /workspace}（FILE + docs 能力，§5.7 的 N9 主场景）。 */
  private DefaultWorkspaceRegistry registryWithMainWorkspace() {
    DefaultWorkspaceRegistry registry =
        DefaultWorkspaceRegistry.create(catalog, null, lineage::get);
    registry.mount(
        new Mount(
            new MountName("workspace"),
            "main",
            "main",
            mainWorkspace,
            Resolvers.direct(),
            Kind.FILE,
            Set.of(DocsCapability.ID),
            Origin.ASSEMBLY),
        new WorkspaceCaller("main", parentPermissions()));
    return registry;
  }

  private SubagentManager manager(
      DefaultWorkspaceRegistry registry, SubagentLimits limits, int parentDepth) {
    AgentTemplateStore templateStore = new AgentTemplateStore(agentsDir);
    templateStore.load();
    return new SubagentManager(
        templateStore,
        new InProcessExecutor(),
        store,
        bus,
        AgentConfig.builder("main").build(),
        parentPermissions(),
        parentDepth,
        () -> CommandMode.FULL,
        limits,
        tempDir.resolve("subagents"),
        registry,
        lineage::put);
  }

  /** 父级权限集：三个面都表态覆盖自己的工作区（M1/M3 要求"先表态再挂载"；§8.3 R2→R3b 交接项 1/1b）。 */
  private AgentPermissionSet parentPermissions() {
    ResourceScope scope = ResourceScope.ofDir(mainWorkspace);
    return AgentPermissionSet.system()
        .withResourceScopes(
            ResourceScopeMap.of(
                Map.of(
                    WorkspacePermissions.WORKSPACES, scope,
                    DocNamespaces.DOCS, scope,
                    DocNamespaces.DOCS_EDIT, scope)));
  }

  private static ResourceScope declared(SubagentInstance instance, String namespace) {
    ResourceScope scope = instance.permissions().resourceScopes().declaredScope(namespace);
    return scope == null ? ResourceScope.none() : scope;
  }

  private static AgentTemplate template(String id) {
    return new AgentTemplate(
        id,
        "工作区范围测试模板",
        "你是测试子 Agent。",
        "fake",
        AccessToken.DEFAULT,
        Set.of("skill_list"),
        Set.of(),
        true,
        false,
        false,
        2000,
        30,
        600,
        0,
        java.util.List.of(),
        null,
        null,
        null);
  }

  private void write(AgentTemplate template) throws IOException {
    JSON.writeValue(agentsDir.resolve(template.id() + ".json").toFile(), template);
  }
}
