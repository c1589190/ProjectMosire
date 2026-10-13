package io.mosire.brain.skills;

/**
 * 技能面的两个权限命名空间（第 7 维 {@code resourceScopes} 的键）：{@link #SKILLS} 可见面、{@link #SKILLS_EDIT} 可编辑面。
 *
 * <p><b>为什么住在 Brain 而不是 AgentLib</b>：{@code ResourceScopeMap} 是<b>通用</b>权限接口——命名空间在那里是自由字符串，
 * 它不该认识“技能”这个本仓业务面。这两个键原先硬编码在 {@code ResourceScopeMap}（通用层沾业务语义：一处是常量本身、一处是 {@code SandboxMask}
 * 读它），收编后由技能面自带（见 {@code 设计-工作区标准与Docs系统.md} §2.1 / §6.1）；AgentLib 只保留通用命名空间 {@code
 * fs}。收编是<b>常量搬家</b>，值逐字不变。
 *
 * <p><b>{@link #SKILLS}：技能可见面</b>（能列出/检索/加载哪些技能的正文）。前缀语义即 {@code ResourceScope} 的通用前缀语义，
 * 落格的是<b>技能文件系统路径</b>：一个前缀既可以是一个<b>技能文件夹</b>（该子树里的技能都可见/可编辑），也可以是一个<b>单个 {@code SKILL.md}
 * 文件</b>（只有这一个技能文档）。之所以两种粒度能共用一套判定：{@code ResourceScope.contains} 是“段边界包含”， 文件路径恰好等于前缀时命中、在其下时也命中。
 *
 * <p><b>未表态 ≠ 可见</b>：本命名空间的使用方（技能工具）把工具缺省策略声明为 {@code DENY}，于是“没写过这一项”读作“看不见任何技能” （fail-closed），这与
 * {@code ResourceScopeMap} 的“未声明 = 本层不表态，回落下一层”一致——回落的那一层（工具缺省）就是 DENY。要“全可见”必须显式写 {@code
 * ResourceScope.unlimited()}。
 *
 * <p><b>{@link #SKILLS_EDIT}：技能可编辑面</b>（能在哪些范围内创建/修改 {@code SKILL.md}）。与 {@link #SKILLS}
 * 分开两个命名空间而不是一个“读写合一”的作用域，理由有二：
 *
 * <ul>
 *   <li>{@code ResourceScope} 是“区域”而不是“区域 × 操作”，一个命名空间只能表一个面；“可见但不能改”是必须能表达的取值；
 *   <li>两个命名空间各自独立逐跳求交，且<b>生效值恒满足 {@code skills-edit ⊆ skills}</b>（由物化侧求交保证）——编辑一个自己看不见的技能没有语义，
 *       这个包含关系把它变成结构性的。
 * </ul>
 *
 * <p>用法与 {@code ResourceScopeMap.FS} 相同：作为权限集里 {@code resourceScopes} 的键，也是 {@code SandboxMask}
 * 遮蔽面条目必须声明的命名空间（{@code SandboxMask} 自身不再认识任何业务命名空间）。
 */
public final class SkillNamespaces {

  /** 技能可见面命名空间：{@code ResourceScope} 的前缀落格技能文件系统路径（技能文件夹或单个 {@code SKILL.md}）。 */
  public static final String SKILLS = "skills";

  /** 技能可编辑面命名空间：能在哪些范围内创建/修改 {@code SKILL.md}；生效值恒 ⊆ {@link #SKILLS}。 */
  public static final String SKILLS_EDIT = "skills-edit";

  private SkillNamespaces() {}
}
