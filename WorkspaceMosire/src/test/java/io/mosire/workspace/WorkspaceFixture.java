package io.mosire.workspace;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工作区测试夹具（R4 统一测试责任区）：把"能力目录 + 权限面 + 挂载"这套装配收成一处，让用例只写它要验的那一件事。
 *
 * <p><b>为什么不复用生产的能力定义</b>：{@code docs} 能力由 docs-mosire 注册、{@code bash} 由后续命令面模块注册，
 * 而本模块（以及它的测试）<b>不得依赖下游</b>（依赖方向 {@code agentlib ← workspace ← docs}）。因此夹具在这里按设计 §4.8 的
 * 能力表<b>重述</b>三个能力定义：
 *
 * <ul>
 *   <li>{@code docs}：落格 {@code docs: READ} + {@code docs-edit: WRITE}，shell 不可见（v1 唯一注册的能力）；
 *   <li>{@code bash}（后续模块，本夹具只为 M11/M12 的"只能更窄"提供第二个能力 id）：落格 {@code fs: READ+WRITE}， shell
 *       可见、白名单非空；
 *   <li>{@code skills-read}：落格 {@code skills: READ}，shell 不可见（R3b 保住技能根遮蔽语义的那一个）。
 * </ul>
 *
 * <p>命名空间字符串是<b>调用方（消费方）的契约</b>，不是本模块的常量——夹具里写字面量正是这条的体现。
 */
final class WorkspaceFixture {

  /** 文档能力 id（v1 唯一注册；定义见设计 §4.8 表）。 */
  static final String DOCS = "docs";

  /** 文档可编辑面命名空间。 */
  static final String DOCS_EDIT = "docs-edit";

  /** 命令能力 id（后续命令面模块；本夹具只为"能力只能更窄"提供第二个 id）。 */
  static final String BASH = "bash";

  /** 技能可读能力 id（R3b 装配期那一个）。 */
  static final String SKILLS_READ = "skills-read";

  /** 技能命名空间（消费方契约里的字面量）。 */
  static final String SKILLS = "skills";

  private WorkspaceFixture() {
    throw new AssertionError("No instances");
  }

  /** {@code docs} 能力（§4.8：{@code docs: READ} + {@code docs-edit: WRITE}，shell 不可见）。 */
  static Capability docsCapability() {
    return Capability.of(
        DOCS,
        Map.of(DOCS, Set.of(Operation.READ), DOCS_EDIT, Set.of(Operation.WRITE)),
        false,
        Set.of());
  }

  /** {@code bash} 能力（§4.8：{@code fs: READ+WRITE}，shell 可见，带非空白名单）。 */
  static Capability bashCapability() {
    return Capability.of(
        BASH, Map.of("fs", Set.of(Operation.READ, Operation.WRITE)), true, Set.of("ls", "cat"));
  }

  /** {@code skills-read} 能力（只含 {@code skills: READ}）。 */
  static Capability skillsReadCapability() {
    return Capability.of(SKILLS_READ, Map.of(SKILLS, Set.of(Operation.READ)), false, Set.of());
  }

  /** 能力目录：三个能力都注册（生产 v1 只注册 docs + skills-read；bash 见设计 §8 ⑤）。 */
  static CapabilityCatalog catalog() {
    CapabilityCatalog catalog = CapabilityCatalogs.inMemory();
    catalog.register(docsCapability());
    catalog.register(bashCapability());
    catalog.register(skillsReadCapability());
    return catalog;
  }

  /** 带血缘映射的注册表：{@code pathOf} 给不出的键按自身处理（缺省映射 = 只有"自己"能匹配，fail-closed）。 */
  static DefaultWorkspaceRegistry registry(CapabilityCatalog catalog, Map<String, String> pathOf) {
    return DefaultWorkspaceRegistry.create(catalog, null, pathOf::get);
  }

  /** 调用者权限集：{@code allowAll} + 给定命名空间面（未给的命名空间 = 未表态 ⇒ fail-closed）。 */
  static AgentPermissionSet permissions(Map<String, ResourceScope> scopes) {
    return AgentPermissionSet.builder(AccessToken.DEFAULT)
        .allowAll()
        .resourceScopes(ResourceScopeMap.of(scopes))
        .build();
  }

  /** "完整的部署面"权限集：{@code workspaces}/{@code docs}/{@code docs-edit} 都覆盖给定根——装配期挂载与落格的最小充分面。 */
  static AgentPermissionSet fullFaces(Path... roots) {
    ResourceScope scope = ResourceScope.of(prefixes(roots));
    Map<String, ResourceScope> scopes = new LinkedHashMap<>();
    scopes.put(WorkspacePermissions.WORKSPACES, scope);
    scopes.put(DOCS, scope);
    scopes.put(DOCS_EDIT, scope);
    return permissions(scopes);
  }

  /** 只声明 {@code workspaces} 面的权限集（"能在哪里建挂载"够，但能力落格盖不住）。 */
  static AgentPermissionSet mountFaceOnly(Path... roots) {
    return permissions(Map.of(WorkspacePermissions.WORKSPACES, ResourceScope.of(prefixes(roots))));
  }

  /** 再加上 {@code skills} 面的完整部署面（挂技能根那类 {@code skills-read} 挂载时必需：M1/M3 要求能力落格的命名空间 也覆盖落点）。 */
  static AgentPermissionSet fullFacesWithSkills(Path... roots) {
    ResourceScope scope = ResourceScope.of(prefixes(roots));
    Map<String, ResourceScope> scopes = new LinkedHashMap<>();
    scopes.put(WorkspacePermissions.WORKSPACES, scope);
    scopes.put(DOCS, scope);
    scopes.put(DOCS_EDIT, scope);
    scopes.put(SKILLS, scope);
    return permissions(scopes);
  }

  /** 装配期挂载（{@code docs} 能力、{@link Origin#ASSEMBLY}）。 */
  static Mount assembly(String name, String holder, String user, Path dir, Kind kind) {
    return mount(name, holder, user, dir, kind, Set.of(DOCS), Origin.ASSEMBLY);
  }

  /** 运行期挂载（M6：不进遮蔽面）。 */
  static Mount runtime(String name, String holder, String user, Path dir, Kind kind) {
    return mount(name, holder, user, dir, kind, Set.of(DOCS), Origin.RUNTIME);
  }

  /** 完整形态（能力集与来源可指定）。 */
  static Mount mount(
      String name,
      String holder,
      String user,
      Path dir,
      Kind kind,
      Set<String> capabilities,
      Origin origin) {
    return new Mount(
        new MountName(name), holder, user, dir, Resolvers.direct(), kind, capabilities, origin);
  }

  /**
   * 派生挂载（M10/W4，R5b 后新增的口径）：由 {@code derivedFrom} 那块派生的子工作区挂载。
   *
   * <p>为什么夹具需要它：级联失效**只沿派生链**（不按物理包含），所以"父卸载 ⇒ 子失效"的夹具必须真的写成派生关系， 否则测的是"两块独立挂载恰好物理嵌套"（那正是 R5
   * 实测里被误伤的形态）。
   */
  static Mount derived(
      String name, String holder, String user, Path dir, Kind kind, String derivedFrom) {
    return new Mount(
        new MountName(name),
        holder,
        user,
        dir,
        Resolvers.direct(),
        kind,
        Set.of(DOCS),
        Origin.RUNTIME,
        Optional.of(new MountName(derivedFrom)));
  }

  /** 挂载并返回注册表（装配一步到位）。 */
  static DefaultWorkspaceRegistry mounted(
      CapabilityCatalog catalog,
      Map<String, String> pathOf,
      Mount spec,
      WorkspaceCaller mountingCaller) {
    DefaultWorkspaceRegistry registry = registry(catalog, pathOf);
    registry.mount(spec, mountingCaller);
    return registry;
  }

  static String[] prefixes(Path... roots) {
    String[] prefixes = new String[roots.length];
    for (int i = 0; i < roots.length; i++) {
      prefixes[i] = roots[i].toAbsolutePath().normalize().toString();
    }
    if (prefixes.length == 0) {
      throw new IllegalArgumentException("夹具至少要一个物理根（没有根就没有可表态的面）");
    }
    return prefixes;
  }
}
