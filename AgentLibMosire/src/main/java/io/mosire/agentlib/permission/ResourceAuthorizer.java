package io.mosire.agentlib.permission;

import java.util.Objects;
import java.util.Optional;

/**
 * 资源判定的唯一入口（S5-C）：<b>命令式 SPI</b>（{@link #require}/{@link #allows}）+ <b>声明式前置闸</b>（{@link
 * #denial()}）。
 *
 * <p><b>它由谁建、怎么到工具手里</b>：宿主（{@code ToolCallAuthorizer}）在工具调用唯一入口的第 3 段用"调用者权限集 × 工具声明的 {@link
 * ResourceManifest}"建它，注入 {@link io.mosire.agentlib.tool.ToolContext#resources()}，工具在真正读写前调 {@link
 * #require}。工具<b>只调不判</b>——自行放行等于跳过唯一入口。
 *
 * <p><b>判定表（逐格写死；"未表态"与"表成空"是两种取值，同 {@link ResourceScopeMap} 的口径）</b>：
 *
 * <table>
 *   <caption>判定表</caption>
 *   <tr><th>情形</th><th>结果</th></tr>
 *   <tr><td>没有判定者（{@link #denying()}）</td><td>拒——fail-closed：没经唯一入口就没有资源判定</td></tr>
 *   <tr><td>工具未声明该命名空间</td><td>拒——"没声明"≠"随便用"（{@link ResourceManifest#NONE} 的工具用不了本 SPI）</td></tr>
 *   <tr><td>调用者显式表态该命名空间</td><td>区域 = 调用者的 {@link ResourceScope}（越界拒）；操作用显式表态说了算（工具缺省策略退场）</td></tr>
 *   <tr><td>调用者未表态该命名空间</td><td>区域 = 整个命名空间；操作取工具缺省策略（{@code UNRESTRICTED} 全放 / {@code READ_ONLY} 只读 /
 *       {@code DENY} 全拒）</td></tr>
 * </table>
 *
 * <p><b>"缺省不是封顶"是本类的核心语义</b>（设计 §2.5.3 的"缺省值 = 工具的 ResourceManifest 默认"）：调用者的显式表态取代工具缺省 策略，理由与代价写在
 * {@link ResourcePolicy} 的类注释里——那里是这条语义的唯一说明点。
 *
 * <p><b>不持有的东西</b>：不写事件、不打日志（审计与拒因落账归调用点，与 {@code ToolExecutionGuard} 同口径）；不判工具名级权限 （那是 {@code
 * ToolExecutionGuard} 的活，入口的第 2 段已经判过）；不做"可达性"判定（本类判的是"命名"——软链、绑定挂载、{@code ..}
 * 之后的重解析不在承诺范围内，真正的结构性围栏是 L3 沙箱）。
 *
 * <p><b>不可变 + 值相等</b>：两个判定者字段相同即相等（{@link io.mosire.agentlib.tool.ToolContext} 是 record，等值语义要一致）。
 */
public final class ResourceAuthorizer {

  /** 无判定者（{@link #denying()}）：两个字段都为 null——这是唯一允许 null 分量的形态。 */
  private static final ResourceAuthorizer DENYING = new ResourceAuthorizer(null, null);

  private final AgentPermissionSet permissions;
  private final ResourceManifest manifest;

  private ResourceAuthorizer(AgentPermissionSet permissions, ResourceManifest manifest) {
    this.permissions = permissions;
    this.manifest = manifest;
  }

  /**
   * 没有判定者：{@link #require} 一律拒、{@link #denial()} 空（= 本闸无事可做，工具自己那一侧仍然全拒）。
   *
   * <p>这是 {@link io.mosire.agentlib.tool.ToolContext#resources()} 的缺省值：手工拼出来的上下文里工具够不着任何资源，
   * 直到宿主在唯一入口注入真正的判定者——<b>缺省 fail-closed，不是"缺省放行"</b>。
   */
  public static ResourceAuthorizer denying() {
    return DENYING;
  }

  /** 按"调用者权限集 × 工具声明的资源面"建判定者（宿主侧用；两个参数都不得为 null）。 */
  public static ResourceAuthorizer of(AgentPermissionSet permissions, ResourceManifest manifest) {
    return new ResourceAuthorizer(
        Objects.requireNonNull(permissions, "permissions"),
        Objects.requireNonNull(manifest, "manifest"));
  }

  /**
   * 这次访问会不会被放行（只读问法，供"先列我能碰的"这类用法）。
   *
   * @param operation 读还是写
   * @param id 资源标识（命名空间 + 路径）
   */
  public boolean allows(Operation operation, ResourceId id) {
    return denialReason(operation, id) == null;
  }

  /**
   * 关键访问点的一次断言：放行则返回，拒则抛 {@link ResourceDeniedException}（消息即拒因，面向模型可读）。
   *
   * <p><b>调用时机</b>：工具<b>真正读写之前</b>——放在读取之后等于"先碰了再问"。
   *
   * @throws ResourceDeniedException 判定为拒
   */
  public void require(Operation operation, ResourceId id) {
    String reason = denialReason(operation, id);
    if (reason != null) {
      throw new ResourceDeniedException(reason);
    }
  }

  /**
   * 声明式前置闸：工具声明的命名空间里<b>一个都够不着</b>时返回拒因（宿主据此在执行前拒掉整个调用，连审批都不问——审批解决不了 "够不着"，问了也是白问人）。
   *
   * <p><b>何时不说话</b>：工具没声明任何命名空间（{@link ResourceManifest#NONE}，含所有不用本 SPI 的工具）、或没有判定者 ⇒ 空。
   * 这两情形下"够不够得着"是每次访问各自的事（{@code require} 那一侧仍然全拒），本闸不替它们下结论。
   *
   * <p>返回空<b>不等于</b>这次调用就放行：声明面够得着 ≠ 具体那个资源够得着（后者由 {@code require} 逐次判）。
   */
  public Optional<String> denial() {
    if (permissions == null || manifest == null || manifest.isEmpty()) {
      return Optional.empty();
    }
    for (String namespace : manifest.namespaces()) {
      if (namespaceUsable(namespace)) {
        return Optional.empty();
      }
    }
    return Optional.of(
        "该调用者够不着这个工具声明的任何资源（执行前拒）: 工具声明="
            + manifest.summary()
            + " 调用者可达="
            + permissions.resourceScopes().summary()
            + "（要够得着，调用者须在自己的可达面上显式表态——"
            + "'未表态'与'哪里都不许'是两种取值）");
  }

  /** 命名空间这一维上，本次调用者是否<b>原则上</b>够得着（不看具体路径）。 */
  private boolean namespaceUsable(String namespace) {
    ResourceScope declared = permissions.resourceScopes().declaredScope(namespace);
    if (declared != null) {
      // 显式表态：unrestricted 或"有前缀"都算够得着；显式空集（none()）= 哪里都不许
      return declared.unrestricted() || !declared.prefixes().isEmpty();
    }
    return manifest.policyFor(namespace) != ResourcePolicy.DENY;
  }

  /** 逐格判定；允许返回 null（拒则返回面向模型的拒因）。 */
  private String denialReason(Operation operation, ResourceId id) {
    if (permissions == null || manifest == null) {
      return "没有资源判定者（fail-closed 拒）: "
          + operation
          + " "
          + id.fullId()
          + "（工具须经唯一入口执行，不得自造上下文绕开判定）";
    }
    ResourcePolicy policy = manifest.policyFor(id.namespace());
    if (policy == null) {
      return "工具未声明资源命名空间（未声明即拒，工具不得自行放行）: " + operation + " " + id.fullId();
    }
    ResourceScope declared = permissions.resourceScopes().declaredScope(id.namespace());
    if (declared == null) {
      // 调用者没提这一嘴 ⇒ 取工具作者定的缺省策略（设计 §2.5.3）
      if (policy == ResourcePolicy.DENY) {
        return "工具对该命名空间的缺省策略是 deny，且调用者未表态: " + operation + " " + id.fullId();
      }
      if (policy == ResourcePolicy.READ_ONLY && operation == Operation.WRITE) {
        return "工具对该命名空间的缺省策略是 read-only（调用者未表态），写操作被拒: " + id.fullId();
      }
      return null;
    }
    // 调用者显式表了态：这一维归它管（工具缺省策略退场），但可达面必须守住
    if (!declared.allows(id.path())) {
      return operation
          + " "
          + id.fullId()
          + " 不在调用者的可达面内: 调用者可达="
          + declared.summary()
          + "（这是显式表态，不是'未表态'）";
    }
    return null;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ResourceAuthorizer that)) {
      return false;
    }
    return Objects.equals(permissions, that.permissions) && Objects.equals(manifest, that.manifest);
  }

  @Override
  public int hashCode() {
    return Objects.hash(permissions, manifest);
  }

  @Override
  public String toString() {
    if (permissions == null || manifest == null) {
      return "ResourceAuthorizer[denying]";
    }
    return "ResourceAuthorizer[声明="
        + manifest.summary()
        + " 调用者可达="
        + permissions.resourceScopes().summary()
        + "]";
  }
}
