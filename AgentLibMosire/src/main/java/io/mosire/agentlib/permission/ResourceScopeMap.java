package io.mosire.agentlib.permission;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 命名空间 → {@link ResourceScope} 的<b>一层</b>取值（S5-B 的第 7 维：{@code resourceScopes}）。
 *
 * <p><b>"未声明"≠"全拒"，而是"本层不表态"</b>。这一层是<b>限制层</b>：它只回答"我允许多少"， 没表态的命名空间回落到下一层的取值——按 {@code
 * 设计-审批与工具内权限.md} §2.5 的口径，那个下一层是<b>工具自己的 {@code ResourceManifest} 默认</b>（工具作者定，缺省 read-only
 * 更安全）。空图 = 一层什么也没说 = 本功能引入前的行为（逐字不变）。
 *
 * <p><b>"哪里都不许"必须显式写</b>：想表达"这个 Agent 够不着某命名空间"，写成 {@code scopeFor(ns) =
 * ResourceScope.none()}，而不是"不写这一项"——后者是"没意见"，两者行为相反。这与 {@code AgentPermissionSet} 白名单同精神（{@code
 * allowedTools} 空集 = 一个工具都不给）：<b>空 = 不给</b>在<b>值</b>上成立， 在<b>层</b>上不成立（层空 = 不表态）。
 *
 * <p><b>继承必须落成数据</b>：子体的这一层不是"照抄父级引用"，而是<i>逐命名空间求交后的具体值</i>（{@link #withNamespace}）。
 * 若只在需要时"往上问父级"，{@code isSubset} 就得到一个<b>没有对象的比较</b>——子级"没表态"和子级"表态为不限"在运行期长得一模一样，
 * 单调性判定会静默放行（这正是本段要修的那类锚点缺陷）。
 */
public record ResourceScopeMap(Map<String, ResourceScope> byNamespace) {

  /** {@code fs} 命名空间（工作目录围栏在这一维上落格）。 */
  public static final String FS = "fs";

  public ResourceScopeMap {
    byNamespace = byNamespace == null ? Map.of() : new LinkedHashMap<>(byNamespace);
    Map<String, ResourceScope> copy = new TreeMap<>();
    for (Map.Entry<String, ResourceScope> entry : byNamespace.entrySet()) {
      String namespace = entry.getKey() == null ? "" : entry.getKey().trim();
      if (namespace.isEmpty()) {
        throw new IllegalArgumentException("命名空间名不能为空");
      }
      copy.put(namespace, Objects.requireNonNull(entry.getValue(), "scope of " + namespace));
    }
    byNamespace = Map.copyOf(copy);
  }

  /** 空图：本层不表态（= 本功能引入前的行为，也是 {@code resourceScopes} 的缺省值）。 */
  public static ResourceScopeMap empty() {
    return new ResourceScopeMap(Map.of());
  }

  /** 只要一个命名空间的一层（{@code fs} 围栏的常见形状）。 */
  public static ResourceScopeMap of(String namespace, ResourceScope scope) {
    return new ResourceScopeMap(Map.of(namespace, scope));
  }

  /** 受限图（按命名空间给值）。 */
  public static ResourceScopeMap of(Map<String, ResourceScope> byNamespace) {
    return new ResourceScopeMap(byNamespace);
  }

  /** 本层表态过的命名空间（"未声明 = 不表态"的可见面）。 */
  public Set<String> namespaces() {
    return byNamespace.keySet();
  }

  /** 本层对某命名空间的取值；<b>未表态返回 {@code null}</b>（回落下一层——不替调用方猜）。 */
  public ResourceScope declaredScope(String namespace) {
    return byNamespace.get(namespace);
  }

  /**
   * 本层对某命名空间的取值，未表态按 {@link ResourceScope#unlimited()} 读（= "本层不设限"）。
   *
   * <p>给<b>只关心"有没有被本层挡住"</b>的调用点用（如 bash 的 fs 判定）；要区分"没表态"与"表态为不限"的调用点用 {@link #declaredScope}。
   */
  public ResourceScope scopeOrUnrestricted(String namespace) {
    ResourceScope scope = byNamespace.get(namespace);
    return scope == null ? ResourceScope.unlimited() : scope;
  }

  /** 换掉（或补上）一个命名空间的取值——<b>继承落成数据</b>的入口（其余命名空间逐字保留）。 */
  public ResourceScopeMap withNamespace(String namespace, ResourceScope scope) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(scope, "scope");
    Map<String, ResourceScope> next = new TreeMap<>(byNamespace);
    next.put(namespace, scope);
    return new ResourceScopeMap(next);
  }

  /**
   * 逐命名空间求交：不限 ∩ X = X；本层没表态的命名空间按"不表态"处理（对方说了算）。
   *
   * <p>结果里保留所有表态过的命名空间——交集为空也要显式留成 {@link ResourceScope#none()}（"全拒"要看得见）。
   */
  public ResourceScopeMap narrowTo(ResourceScopeMap other) {
    Objects.requireNonNull(other, "other");
    Map<String, ResourceScope> intersection = new TreeMap<>();
    for (String namespace : union(namespaces(), other.namespaces())) {
      ResourceScope mine = declaredScope(namespace);
      ResourceScope theirs = other.declaredScope(namespace);
      if (mine == null) {
        intersection.put(namespace, theirs);
      } else if (theirs == null) {
        intersection.put(namespace, mine);
      } else {
        intersection.put(namespace, mine.narrowTo(theirs));
      }
    }
    return new ResourceScopeMap(intersection);
  }

  /**
   * 单调性（{@code this ⊇ child}）：本层表态过的每个命名空间，child 都必须不比它宽。
   *
   * <p>child 对某个命名空间<b>没表态</b>时按"最宽"处置（即有可能超出）——但只在<b>本层表过态</b>的那个命名空间上才触发拒绝： 本层没意见的命名空间，child
   * 怎么表态都不构成越权（表成更窄是收缩，表成不限是"与我无关"）。
   */
  public boolean covers(ResourceScopeMap child) {
    Objects.requireNonNull(child, "child");
    for (Map.Entry<String, ResourceScope> entry : byNamespace.entrySet()) {
      ResourceScope childScope = child.declaredScope(entry.getKey());
      if (childScope == null) {
        // child 没表态 ⇒ 运行期落到下一层默认，无从证明"不比本层宽" ⇒ 保守判否（fail-closed）
        if (!entry.getValue().unrestricted()) {
          return false;
        }
        continue;
      }
      if (!entry.getValue().covers(childScope)) {
        return false;
      }
    }
    return true;
  }

  /** 摘要（落事件/日志用）：空图写 {@code *}（本层不表态），否则写 {@code 命名空间=前缀列表}（允许根列表——审计要辨"谁在哪个 scope 下跑的"）。 */
  public String summary() {
    if (byNamespace.isEmpty()) {
      return "*";
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, ResourceScope> entry : byNamespace.entrySet()) {
      if (sb.length() > 0) {
        sb.append(';');
      }
      sb.append(entry.getKey()).append('=').append(entry.getValue().summary());
    }
    return sb.toString();
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> all = new TreeSet<>(a);
    all.addAll(b);
    return all;
  }
}
