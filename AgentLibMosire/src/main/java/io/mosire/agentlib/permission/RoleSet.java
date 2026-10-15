package io.mosire.agentlib.permission;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一组<b>角色标签</b>（设计 §4.3.1）：不可变值类型，⊥ = 空集 = "一个角色都不是"。
 *
 * <p><b>为什么要有独立类型</b>：AgentLib 将来不只服务一个项目，通用层不出现 {@code GM}/{@code DM} 这类名字， 只认"标签集合 +
 * 子集序"。这条序必须收成<b>唯一实现点</b>：否则 spawn 守卫、可见面派生、装订期审计会各写一遍 {@code containsAll}，"子集"的口径迟早分叉。角色名对
 * AgentLib 是<b>不透明</b>标签——语义（谁是 GM）只在宿主。
 *
 * <p><b>两侧空集读法相反</b>（本类型最容易写错的地方）：
 *
 * <ul>
 *   <li><b>调用者侧</b>（{@link AgentPermissionSet#roles()}）：空集 = ⊥ = "谁都不是"（fail-closed）——身份没有
 *       "回落"这一说，未表态就不能被任何"声明了角色的工具"调用；
 *   <li><b>声明侧</b>（{@code io.mosire.agentlib.tool.AgentTool#roles()}）：空集 = ⊤ = "不表态 = 任意角色可调"
 *       ——既有工具一个都没声明角色，若这里读作"谁都不能用"，本维一引入就把全部既有工具打死。
 * </ul>
 *
 * <p>⇒ ⊤ 只出现在<b>声明侧</b>，且由声明侧的调用方（判定点）负责把它翻译成"本维不参与判定"；本类型只表达 ⊥ 与 "一组具体角色"两种取值——<b>不提供</b> {@code
 * any()}：一个"谁都能过的 RoleSet"会让上面两种读法在类型内部合流， 正是口径分叉的入口。
 *
 * <p><b>规范化</b>：角色名一律去首尾空白、去重后做成<b>不可变快照</b>（内联 {@code Set.copyOf}，本仓既有写法：让 SpotBugs 看得见不可变来源）；★
 * <b>迭代序不保证</b>（{@code Set.copyOf} 的顺序是实现细节）——需要稳定顺序的<b>对外文本</b>由 {@link #summary()}
 * <b>显式升序</b>。空白名（含 {@code null}）<b>响亮失败</b>——拼错的角色 = 永远不可达的工具，静默收下就是"漂移无症状" （设计
 * §4.6）。角色名大小写敏感：它是不透明标签，不是枚举。
 *
 * <p><b>留的扩展缝</b>：宿主若要角色层级（{@code admin ⊇ operator}），只需换 {@link #covers}/{@link #narrowTo} 的实现（按给定的
 * subsumption 关系求包含与 meet），接口一字不动；v1 不做层级——层级表 + 角色枚举是两份声明，会漂。
 */
public record RoleSet(Set<String> roles) {

  public RoleSet {
    if (roles == null || roles.isEmpty()) {
      roles = Set.of();
    } else {
      Set<String> normalized = new TreeSet<>();
      for (String role : roles) {
        normalized.add(normalizeRole(role));
      }
      // 内联 Set.copyOf：本仓既有写法（同 ResourceScope / AgentPermissionSet），让 SpotBugs 看得见不可变来源
      // （Collections.unmodifiableSet 会被判 EI_EXPOSE_REP）。
      // ★ 代价如实记：迭代序不再是契约（Set.copyOf 的顺序是实现细节）——对外文本由 summary() 显式升序。
      roles = Set.copyOf(normalized);
    }
  }

  /** ⊥：一个角色都不是（调用者侧的 fail-closed 取值；声明侧的"不表态"不在这里表达，走空集 {@code Set.of()} 的读法）。 */
  public static RoleSet none() {
    return new RoleSet(Set.of());
  }

  /**
   * 明确的角色集合（至少一个角色名；要 ⊥ 用 {@link #none()}）。
   *
   * <p><b>空 / 全空白 ⇒ 响亮失败</b>：{@code roles=[]} 与"我忘了写角色名"在配置面上长得一模一样，静默收成 ⊥
   * 会让"漏配"变成一个看起来正常的取值。任一名为空白同样响亮失败——悄悄丢掉那一项等于把拼错的名字洗成别的角色。
   */
  public static RoleSet of(String... roles) {
    if (roles == null || roles.length == 0) {
      throw new IllegalArgumentException("必须给至少一个角色名；空集请用 RoleSet.none()");
    }
    Set<String> names = new LinkedHashSet<>();
    for (String role : roles) {
      names.add(normalizeRole(role));
    }
    return new RoleSet(names);
  }

  /**
   * meet = 交集（逐层求交用它）：结果同时满足两边。
   *
   * <p>任何一方是 ⊥ ⇒ 结果 ⊥（与 §4.5"父空集 ⇒ 子必须空集"同向）；交集与 {@code this} 内容相同则原样返回。
   */
  public RoleSet narrowTo(RoleSet other) {
    Objects.requireNonNull(other, "other");
    if (roles.isEmpty() || other.roles.isEmpty()) {
      return RoleSet.none();
    }
    Set<String> intersection = new LinkedHashSet<>(roles);
    intersection.retainAll(other.roles);
    if (intersection.equals(roles)) {
      return this;
    }
    return new RoleSet(intersection);
  }

  /**
   * 序：{@code this ⊇ child}（权限单调性判定用它）。
   *
   * <p>⊥ 只涵盖 ⊥：父级一个角色都没有 ⇒ 子级也必须一个都没有，"父不表态、子自称管理员"的放大天然被封住。 注意与 {@link #intersects}
   * 的方向相反——那是"有没有交集"（能不能调），这是"包不包含"（能不能授）。
   */
  public boolean covers(RoleSet child) {
    Objects.requireNonNull(child, "child");
    return roles.containsAll(child.roles);
  }

  /**
   * 判定：本集合 ∩ {@code declared} ≠ ∅（调用者角色 ∩ 工具声明角色）。
   *
   * <p>{@code declared} 是工具的<b>原始声明</b>（{@code String} 集合，不是本类型）：这里按与本类型相同的口径比对 （去首尾空白），所以声明侧写
   * {@code " gm "} 仍能匹配 {@code "gm"}；但<b>空白项不匹配任何东西</b>（⇒ 返回 false ⇒ 拒），不是"退回不表态"——一个声明了空白角色名的工具是宿主
   * bug，fail-closed 才是正确落点。
   *
   * <p>{@code declared} 为 {@code null}/空 ⇒ false；本侧为 ⊥ ⇒ 恒 false（A-3：无角色调用者调角色门工具必拒）。
   */
  public boolean intersects(Collection<String> declared) {
    if (declared == null || declared.isEmpty() || roles.isEmpty()) {
      return false;
    }
    for (String candidate : declared) {
      if (candidate == null) {
        continue;
      }
      String name = candidate.trim();
      if (!name.isEmpty() && roles.contains(name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 摘要（落事件/日志用）：按字典序逗号连接；⊥ 写空串。
   *
   * <p>角色名<b>不是密钥</b>（设计 §4.3.1 明确"角色名可落"），所以直接落名字——审计要能回答"谁是什么角色"。
   */
  public String summary() {
    // 显式升序：存储侧的迭代序不保证（见类注释"规范化"），对外文本必须稳定
    return String.join(",", new TreeSet<>(roles));
  }

  /** 角色名归一：去首尾空白；空白（含 {@code null}）响亮失败（见类注释的"规范化"）。 */
  private static String normalizeRole(String raw) {
    String name = raw == null ? "" : raw.trim();
    if (name.isEmpty()) {
      throw new IllegalArgumentException(
          "角色名不能为空白（null/空串/全空白）: '" + raw + "'；要\"一个角色都不是\"请用 RoleSet.none()");
    }
    return name;
  }
}
