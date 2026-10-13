package io.mosire.docs;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.workspace.Capability;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.Kind;
import java.util.Map;
import java.util.Set;

/**
 * {@code docs} 能力的<b>定义</b>（设计 §4.8 的能力表；N9/N13）。
 *
 * <p><b>为什么定义住在本模块</b>：能力 id → 落格命名空间的对应表是"这个工具族要哪些权限面"的知识，谁的工具谁定义（工作区模块只提供目录与
 * 落格机制，`WorkspaceMosire/.../Capability.java` 的注释把这一点写死了）。因此 {@code docs} 由 Docs 注册、{@code bash}
 * 由后续命令面模块注册。
 *
 * <p><b>值逐条照抄设计 §4.8</b>：
 *
 * <pre>
 * docs 能力 = { "docs": {READ}, "docs-edit": {WRITE} }，shellVisible = false，allowedCommands = 空集
 * </pre>
 *
 * <p><b>{@code shellVisible = false} 是 v1 的 fail-closed 落点</b>（N13）：v1 只注册 {@code docs} 一个能力 ⇒
 * 所有挂载在 shell 侧一律遮蔽。 {@code FILE} 挂载的"可写"是<b>经 Docs 工具可写</b>，不是 shell 可写；命令面随 {@code bash}
 * 能力一起到后续模块。
 *
 * <p><b>{@code allowedCommands} 为空集</b>：本能力不携带任何命令白名单——"白名单为空 = 一条命令都不许"（M12 的 fail-closed 口径）。
 */
public final class DocsCapability {

  /** 能力 id（挂载声明与目录注册都用它）。 */
  public static final String ID = "docs";

  /** 能力定义（记录不可变，可安全共享/重复注册——目录对"同 id 同定义"是幂等的）。 */
  private static final Capability CAPABILITY =
      Capability.of(
          ID,
          Map.of(
              DocNamespaces.DOCS, Set.of(Operation.READ),
              DocNamespaces.DOCS_EDIT, Set.of(Operation.WRITE)),
          false,
          Set.of());

  private DocsCapability() {
    throw new AssertionError("No instances");
  }

  /**
   * {@code docs} 能力：落格 {@code docs ← dir（READ）} 与 {@code docs-edit ← dir（WRITE）}，shell 不可见、无命令白名单。
   *
   * <p>调用点（装配期）：{@code catalog.register(DocsCapability.docs())}，随后挂载声明 {@code capabilities =
   * {"docs"}}、 由工作区模块的 {@code grant} 把这个能力落成"命名空间 + 前缀"。<b>本方法只给定义，不做落格</b>——A8：能力是唯一决定点，
   * 运行期不得有第二处判定。
   */
  public static Capability docs() {
    return CAPABILITY;
  }

  /** 装配便利：把 {@code docs} 能力注册进给定目录（幂等：同 id 同定义重复注册不算错）。 */
  public static void register(CapabilityCatalog catalog) {
    java.util.Objects.requireNonNull(catalog, "catalog").register(docs());
  }

  /**
   * 两个工具面的资源声明（设计 §5.6）：读工具 {@code docs} / 写工具 {@code docs-edit}，缺省策略都是 {@link
   * ResourcePolicy#DENY}。
   *
   * <p><b>为什么缺省 DENY 而不是 READ_ONLY</b>：文档面与技能面同族——"未表态 = 什么都看不见"是这里的正确读法（三档 fail-closed）。
   * 想让某个调用者读，必须显式在 {@code docs} 上表态（工作区模块的 {@code grant} 会替挂载做这件事）。
   */
  public static final class Manifests {

    /** 读工具（{@code doc_list}/{@code doc_read}/{@code doc_query}）的资源面。 */
    public static final ResourceManifest READ =
        ResourceManifest.of(DocNamespaces.DOCS, ResourcePolicy.DENY);

    /** 写工具（{@code doc_append}/{@code doc_void}/{@code doc_create}/{@code doc_edit}）的资源面。 */
    public static final ResourceManifest WRITE =
        ResourceManifest.of(DocNamespaces.DOCS_EDIT, ResourcePolicy.DENY);

    private Manifests() {
      throw new AssertionError("No instances");
    }
  }

  /** 本能力适用的纪律（文档：两种都适用；{@code bash} 只适用 {@link Kind#FILE}/{@link Kind#SCRATCH}）。 */
  public static Set<Kind> disciplines() {
    return Set.of(Kind.LOG, Kind.FILE);
  }
}
