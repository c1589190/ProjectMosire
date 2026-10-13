package io.mosire.workspace;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 工作区在 AgentLib 通用权限接口上的落格与读取（设计 §4.8、§4.10 A1）。
 *
 * <p><b>不新造权限机制（A1）</b>：权限落格一律是"某命名空间的一条前缀"——{@code workspaces} 命名空间的前缀 = "允许在这个
 * 物理范围内新建挂载"；能力落格的命名空间（如 {@code docs} / {@code docs-edit} / {@code fs}）的前缀 = 该挂载的物理落点。
 * 单调性、求交、诊断都走既有 {@link ResourceScope} / {@link ResourceScopeMap}，本类只提供"读写这几条前缀"的窄工具，形状 照 {@code
 * SkillPermissions}。
 *
 * <p><b>三档语义（写死，不许有第四种读法）</b>：
 *
 * <ul>
 *   <li><b>未表态</b>（权限集里没有这个命名空间）⇒ {@link #declared} 读作 {@code none()}（<b>一个挂载都建不了 /
 *       够不着</b>）。这不是本类自造的规则：工具声明的缺省策略是 {@code DENY}（见 {@link WorkspaceTools} 的 manifest）， {@code
 *       ResourceScopeMap} 的"未声明 = 本层不表态 ⇒ 回落下一层"落到的那一层正是 DENY；
 *   <li><b>显式 {@code none()}</b> ⇒ 同上（够不着），但它是一个<b>表态</b>：单调性守卫看得见它，子级不能借"没表态"把它洗掉。 <b>落格（{@link
 *       WorkspaceRegistry#grant}）照样会往它上面并进挂载前缀</b>——"能力落格"是授予动作，
 *       挡住能力要靠"不在挂载上声明它"，不是靠目标自己先前的表态（A8：能力是唯一决定点）；
 *   <li><b>{@code unlimited()} 或前缀集</b> ⇒ 前缀即物理落点。
 * </ul>
 */
public final class WorkspacePermissions {

  /** 工作区命名空间：前缀 = 允许在该物理范围内新建挂载（设计 §4.7；缺省 DENY）。 */
  public static final String WORKSPACES = "workspaces";

  private WorkspacePermissions() {
    throw new AssertionError("No instances");
  }

  /** 某命名空间上的表态；{@code permissions == null} 或未表态 ⇒ {@link ResourceScope#none()}（fail-closed）。 */
  public static ResourceScope declared(AgentPermissionSet permissions, String namespace) {
    if (permissions == null || namespace == null) {
      return ResourceScope.none();
    }
    ResourceScope scope = permissions.resourceScopes().declaredScope(namespace);
    return scope == null ? ResourceScope.none() : scope;
  }

  /** 挂载面（{@code workspaces} 命名空间）：能在这个物理范围内新建挂载吗。 */
  public static ResourceScope mountFace(AgentPermissionSet permissions) {
    return declared(permissions, WORKSPACES);
  }

  /** 物理落点是否落在调用者的挂载面内（M1 的"workspaces 面"那一半）。 */
  public static boolean canMountWithin(AgentPermissionSet permissions, Path dir) {
    return covered(mountFace(permissions), dir);
  }

  /** 调用者是否持有某能力（该能力任一落格命名空间覆盖该物理落点）——设计 §4.5 第③步的判据。 */
  public static boolean admits(AgentPermissionSet permissions, Capability capability, Path dir) {
    Objects.requireNonNull(capability, "capability");
    for (String namespace : capability.namespaceNames()) {
      ResourceScope declared =
          permissions == null ? null : permissions.resourceScopes().declaredScope(namespace);
      if (declared != null && covered(declared, dir)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 可达面是否<b>覆盖整条路径</b>（{@link ResourceScope#covers} 的"涵盖"语义，不是"命中其中某处"）——与 {@code
   * SandboxMask.visible} 同一口径（设计 §4.8："覆盖整条路径"取 covers 语义）。
   */
  public static boolean covered(ResourceScope scope, Path dir) {
    if (scope == null || dir == null) {
      return false;
    }
    return scope.covers(ResourceScope.ofDir(dir));
  }

  /** 换掉（或补上）一个命名空间的面，其余命名空间逐字保留。 */
  public static AgentPermissionSet withNamespace(
      AgentPermissionSet permissions, String namespace, ResourceScope scope) {
    Objects.requireNonNull(permissions, "permissions");
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(scope, "scope");
    Map<String, ResourceScope> scopes = new TreeMap<>(permissions.resourceScopes().byNamespace());
    scopes.put(namespace, scope);
    return permissions.withResourceScopes(new ResourceScopeMap(scopes));
  }

  /**
   * 把挂载的物理落点<b>并进</b>一个命名空间的面（{@link WorkspaceRegistry#grant} 的落格动作）。
   *
   * <p><b>只加一条前缀，不动其它任何维度</b>：它不改工具白名单、拒绝名单、破坏性/敏感位、{@code readOnly}——"绝不放大"
   * 在这些维度上是逐字成立；在可达面这一维，并进的前缀只可能是<b>挂载时已被 M1/M3 校验过 ⊆ 挂载者面</b>的那些落点 （写入端唯一），因此也不会引入超范围的前缀。
   *
   * <p><b>显式 {@code none()} 也会被并进</b>：并进是"能力落格"的定义动作（A8 的能力是唯一决定点），不是对目标先前表态的
   * 重判——要挡住某个能力，装配方应当不声明它。
   *
   * @param existing 该命名空间当前的面（{@code null} 视作未表态 = 空）
   * @param dir 挂载的物理落点
   */
  public static ResourceScope land(ResourceScope existing, Path dir) {
    Objects.requireNonNull(dir, "dir");
    if (existing != null && existing.unrestricted()) {
      // "不限"已经含一切，再塞一条前缀只会让面变得"看起来有边界"（summary 更难读），语义不变
      return existing;
    }
    Set<String> prefixes = new LinkedHashSet<>();
    if (existing != null) {
      prefixes.addAll(existing.prefixes());
    }
    prefixes.add(directoryPrefix(dir));
    return ResourceScope.of(prefixes.toArray(String[]::new));
  }

  /** 目录 → 一条作用域前缀（与 {@code ResourceScope.ofDirs} 同口径：绝对化 + 归一，已存在则取 realpath）。 */
  public static String directoryPrefix(Path dir) {
    Objects.requireNonNull(dir, "dir");
    // ofDir 已经处理了"绝对化/归一/realpath"三件事，这里只是取回那一条前缀——口径只有一处
    return ResourceScope.ofDir(dir).prefixList().get(0);
  }

  /** 命名空间 → 面的摘要（落日志/事件用；不含模型自造串）。 */
  public static String summary(AgentPermissionSet permissions) {
    return permissions == null ? "*" : permissions.resourceScopes().summary();
  }
}
