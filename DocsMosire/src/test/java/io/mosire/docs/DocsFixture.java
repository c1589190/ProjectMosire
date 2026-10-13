package io.mosire.docs;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.CapabilityCatalogs;
import io.mosire.workspace.DefaultWorkspaceRegistry;
import io.mosire.workspace.Kind;
import io.mosire.workspace.Mount;
import io.mosire.workspace.MountName;
import io.mosire.workspace.Origin;
import io.mosire.workspace.Resolvers;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceRegistry;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Docs 测试夹具（R4 统一测试责任区）：把"能力目录 + 权限面 + 挂载表"这套装配讲清楚，让每个用例只写它要验的那一件事。
 *
 * <p><b>为什么需要它</b>：{@code DocsEngine} 的每条判据都要先穿过工作区解析点的准入（M1/M3 要求"能力落格后仍 ⊆ 调用者面"），
 * 装配写错会让用例红在装配上而不是被测点上。这里把装配收成一处，并保证它与生产装配（{@code App}）同形：
 *
 * <ul>
 *   <li>能力目录注册 {@link DocsCapability#docs()}（v1 唯一注册的能力）；
 *   <li>调用者权限集<b>显式表态</b>三个面（{@code workspaces} / {@code docs} / {@code docs-edit}）并覆盖用到的物理根——
 *       未表态会让挂载被 M1 拒（"未表态 = 一个挂载都建不了"是有意的 fail-closed）；
 *   <li>挂载声明 {@code capabilities = {"docs"}}、{@code Origin.ASSEMBLY}（进遮蔽面；运行期档见 {@link #runtime}）。
 * </ul>
 *
 * <p>夹具只提供"装配"，不提供断言——判据仍写在各自用例里。
 */
final class DocsFixture {

  private DocsFixture() {
    throw new AssertionError("No instances");
  }

  /** 能力目录：只注册 {@code docs}（生产装配同形）。 */
  static CapabilityCatalog catalog() {
    CapabilityCatalog catalog = CapabilityCatalogs.inMemory();
    DocsCapability.register(catalog);
    return catalog;
  }

  /** 调用者权限集：{@code allowAll} + 三个面都表态为"覆盖给定物理根"。 */
  static AgentPermissionSet permissions(Path... roots) {
    ResourceScope scope = ResourceScope.of(prefixes(roots));
    Map<String, ResourceScope> scopes = new LinkedHashMap<>();
    scopes.put("workspaces", scope);
    scopes.put(DocNamespaces.DOCS, scope);
    scopes.put(DocNamespaces.DOCS_EDIT, scope);
    return AgentPermissionSet.builder(AccessToken.DEFAULT)
        .allowAll()
        .resourceScopes(new ResourceScopeMap(scopes))
        .build();
  }

  /** 调用者：身份键 + 覆盖给定根的三个面。 */
  static WorkspaceCaller caller(String holder, Path... roots) {
    return new WorkspaceCaller(holder, permissions(roots));
  }

  /** 只在某命名空间上表态的调用者（缺省 = 该命名空间未表态）；其余面不表态。 */
  static WorkspaceCaller callerDeclaring(String holder, String namespace, ResourceScope scope) {
    return new WorkspaceCaller(
        holder,
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allowAll()
            .resourceScopes(ResourceScopeMap.of(namespace, scope))
            .build());
  }

  /** 缺省注册表：血缘路径 = 身份键本身（只有"自己"能匹配，fail-closed）。 */
  static DefaultWorkspaceRegistry registry(CapabilityCatalog catalog) {
    return DefaultWorkspaceRegistry.create(catalog);
  }

  /**
   * 带血缘映射的注册表：{@code pathOf} 给不出的键按自身处理——"父为子挂"（M2）需要父子血缘路径可解析。
   *
   * @param pathOf 身份键 → 血缘路径（如 {@code main} → {@code main}、{@code a1} → {@code main/a1}）
   */
  static DefaultWorkspaceRegistry registry(CapabilityCatalog catalog, Map<String, String> pathOf) {
    Function<String, String> lineage = key -> pathOf.getOrDefault(key, key);
    return DefaultWorkspaceRegistry.create(catalog, null, lineage);
  }

  /** 装配期挂载（{@code docs} 能力 ⇒ 进遮蔽面）：{@code LOG}/{@code FILE} 两种纪律都用它。 */
  static Mount mount(String name, String holder, String user, Path dir, Kind kind) {
    return mount(name, holder, user, dir, kind, Set.of(DocsCapability.ID), Origin.ASSEMBLY);
  }

  /** 完整形态的挂载（能力集与来源可指定；空能力集用于"未授予能力"的负向）。 */
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

  /** 运行期挂载（M6：不进遮蔽面）。 */
  static Mount runtime(String name, String holder, String user, Path dir, Kind kind) {
    return mount(name, holder, user, dir, kind, Set.of(DocsCapability.ID), Origin.RUNTIME);
  }

  /** 建注册表并挂一条挂载（一步到位，省掉每个用例的重复）。 */
  static DefaultWorkspaceRegistry registryWith(
      CapabilityCatalog catalog, Mount spec, WorkspaceCaller mountingCaller) {
    DefaultWorkspaceRegistry registry = registry(catalog);
    registry.mount(spec, mountingCaller);
    return registry;
  }

  /** 同上，但带血缘映射（"父为子挂"必须用它：缺省映射下 M2 只认"自己"）。 */
  static DefaultWorkspaceRegistry registryWith(
      CapabilityCatalog catalog,
      Map<String, String> pathOf,
      Mount spec,
      WorkspaceCaller mountingCaller) {
    DefaultWorkspaceRegistry registry = registry(catalog, pathOf);
    registry.mount(spec, mountingCaller);
    return registry;
  }

  /** 从工具面取一个工具（找不到即失败——名字写错不该静默跳过）。 */
  static AgentTool tool(WorkspaceRegistry registry, String name) {
    for (AgentTool tool : DocsTools.all(registry)) {
      if (tool.name().equals(name)) {
        return tool;
      }
    }
    throw new AssertionError("DocsTools 里没有这个工具名: " + name);
  }

  /**
   * 工具调用上下文：身份 = 宿主绑定的实例 id，资源判定者 = 真实 {@link ResourceAuthorizer}（"调用者面 × 工具声明的面"）。
   *
   * <p><b>为什么必须注入真实判定者</b>：缺省是 {@code denying()}（fail-closed）——用它测"放行"路径会红在夹具上。
   */
  static ToolContext context(
      String instanceId,
      AgentPermissionSet permissions,
      AgentTool tool,
      Map<String, Object> arguments) {
    return context(AccessToken.DEFAULT, instanceId, permissions, tool, arguments);
  }

  /** 同上，但令牌可指定：{@code SYSTEM} 桶 = GM 短路位（{@link DocViewer#from} 按它取 {@code seesAll}）。 */
  static ToolContext context(
      AccessToken token,
      String instanceId,
      AgentPermissionSet permissions,
      AgentTool tool,
      Map<String, Object> arguments) {
    return new ToolContext(
        token,
        permissions,
        Map.of(),
        arguments,
        new AgentIdentity(instanceId, CommandMode.FULL, "", 0),
        ResourceAuthorizer.of(permissions, tool.resources()));
  }

  /** 工具调用上下文的简写：权限集由根推出（身份键与用户面同源）。 */
  static ToolContext context(String instanceId, AgentTool tool, Path... roots) {
    return context(instanceId, permissions(roots), tool, Map.of());
  }

  /** 命令行式参数（工具面参数名与 schema 一致）。 */
  static Map<String, Object> args(Object... keyValues) {
    Map<String, Object> arguments = new LinkedHashMap<>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      arguments.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return arguments;
  }

  /** 最简追加请求。 */
  static AppendRequest appendOf(String address, long tick, String key, Object value) {
    return AppendRequest.of(address, tick, key, value);
  }

  private static String[] prefixes(Path... roots) {
    List<String> prefixes = new java.util.ArrayList<>(roots.length);
    for (Path root : roots) {
      prefixes.add(root.toAbsolutePath().normalize().toString());
    }
    if (prefixes.isEmpty()) {
      throw new IllegalArgumentException("夹具至少要一个物理根（没有根就没有可表态的面）");
    }
    return prefixes.toArray(String[]::new);
  }
}
