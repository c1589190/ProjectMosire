package io.mosire.agentlib.permission;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 工具声明的资源面（S5-C 声明式 SPI）：命名空间 → 缺省策略。
 *
 * <p><b>为什么工具必须显式声明</b>：{@link ResourceAuthorizer#require} 对"未声明的命名空间"一律拒——"没声明"读作"我不知道这类资源
 * 该怎么判"，而不是"随便用"。这份声明是工具作者对自己数据面的唯一表态点，也是宿主在执行前能问的唯一凭据。
 *
 * <p>{@link #NONE}（缺省）= 一个命名空间都不声明：不用资源 SPI 的工具的形态，行为与本功能引入前逐字相同（它从不调 {@code
 * require}，"未声明即拒"不会被触发）。
 *
 * <p><b>与调用者那一层的关系</b>：本声明是<b>缺省值</b>，调用者在 {@link ResourceScopeMap} 里显式表态过的命名空间以调用者为准 （理由见 {@link
 * ResourcePolicy}）。
 */
public record ResourceManifest(Map<String, ResourcePolicy> byNamespace) {

  /** 不声明任何命名空间（{@code AgentTool.resources()} 的缺省值）。 */
  public static final ResourceManifest NONE = new ResourceManifest(Map.of());

  public ResourceManifest {
    Map<String, ResourcePolicy> given = byNamespace == null ? Map.of() : byNamespace;
    Map<String, ResourcePolicy> normalized = new TreeMap<>();
    for (Map.Entry<String, ResourcePolicy> entry : given.entrySet()) {
      // 命名空间去空白后必须非空（与 ResourceScopeMap / ResourceId 同一口径，三处口径必须一致）
      String namespace = entry.getKey() == null ? "" : entry.getKey().trim();
      if (namespace.isEmpty()) {
        throw new IllegalArgumentException("资源命名空间不能为空");
      }
      normalized.put(namespace, Objects.requireNonNull(entry.getValue(), "policy of " + namespace));
    }
    // 内联 Map.copyOf：让 SpotBugs 看得见不可变来源（本仓既有写法，见 AgentPermissionSet）
    byNamespace = Map.copyOf(normalized);
  }

  /** 单个命名空间的声明（最常见形态）。 */
  public static ResourceManifest of(String namespace, ResourcePolicy policy) {
    return new ResourceManifest(Map.of(namespace, policy));
  }

  /** 多个命名空间的声明。 */
  public static ResourceManifest of(Map<String, ResourcePolicy> byNamespace) {
    return new ResourceManifest(byNamespace);
  }

  /** 声明过的命名空间（"未声明"的可见面）。 */
  public Set<String> namespaces() {
    return byNamespace.keySet();
  }

  /** 一个命名空间都没声明（= {@link #NONE}）。 */
  public boolean isEmpty() {
    return byNamespace.isEmpty();
  }

  /** 某命名空间的缺省策略；<b>未声明返回 {@code null}</b>（判定点据此走"未声明即拒"，不替工具猜）。 */
  public ResourcePolicy policyFor(String namespace) {
    return byNamespace.get(namespace);
  }

  /** 摘要（落日志/写拒因用）：空表写 {@code *}，否则写 {@code 命名空间=策略}（不含任何路径，无秘密）。 */
  public String summary() {
    if (byNamespace.isEmpty()) {
      return "*";
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, ResourcePolicy> entry : byNamespace.entrySet()) {
      if (sb.length() > 0) {
        sb.append(';');
      }
      sb.append(entry.getKey()).append('=').append(entry.getValue());
    }
    return sb.toString();
  }
}
