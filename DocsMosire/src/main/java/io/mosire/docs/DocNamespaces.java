package io.mosire.docs;

/**
 * Docs 面的两个权限命名空间（第 7 维 {@code resourceScopes} 的键）：{@link #DOCS} 可见/可读面、{@link #DOCS_EDIT} 可编辑面。
 *
 * <p><b>为什么常量住在本模块而不是 {@code agentlib-mosire}</b>（N10/N13）：{@code ResourceScopeMap}
 * 是<b>通用</b>权限接口—— 命名空间在那里是自由字符串，它不该认识"文档"这个本仓业务面（收编前的 {@code SKILLS}/{@code SKILLS_EDIT}
 * 正是被这样搬走的）。 Docs 自带自己的两个键，AgentLib 只保留通用命名空间 {@code fs}。
 *
 * <p><b>为什么是两个命名空间而不是一个"读写合一"</b>：{@code ResourceScope} 是"区域"而不是"区域 × 操作"，一个命名空间只能表一个面；
 * "看得见但不能改"必须是能表达的取值（只读参考文档是文档系统的主要用法之一）。两个命名空间各自独立求交，且<b>生效值恒满足 {@code docs-edit ⊆
 * docs}</b>——写一份自己看不见的文档没有语义，这个包含关系把它变成结构性的。
 *
 * <p><b>落格的是什么</b>：前缀 = 该挂载的<b>物理落点</b>（工作区模块的 {@code grant} 写进去，见设计 §4.8）。Docs 工具<b>不认工作区</b>
 * （A8）：它只按"调用者在 {@code docs} / {@code docs-edit} 上的表态"逐路径判，因此"这个 Agent 能不能用 docs 能力"是挂载/授予时的落格结果，
 * 工具侧不再判一次、也不允许自行放宽。
 *
 * <p><b>三档 fail-closed</b>：未表态 = 什么也看不见/改不了（工具的资源声明是 {@code DENY}）；显式 {@code none()} = 表成空； 显式前缀集 /
 * {@code unlimited()} = 可见。不存在"缺省静默放大"。
 */
public final class DocNamespaces {

  /** 文档可见（可读）面：前缀 = 挂载的物理落点。 */
  public static final String DOCS = "docs";

  /** 文档可编辑面（创建 / 追加 / 修改 / 作废）：生效值恒 ⊆ {@link #DOCS}。 */
  public static final String DOCS_EDIT = "docs-edit";

  private DocNamespaces() {
    throw new AssertionError("No instances");
  }
}
