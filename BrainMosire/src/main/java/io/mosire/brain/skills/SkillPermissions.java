package io.mosire.brain.skills;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * 技能可见面 / 可编辑面在权限集上的落格与读取（{@link ResourceScopeMap#SKILLS} / {@link ResourceScopeMap#SKILLS_EDIT}）。
 *
 * <p><b>为什么复用第 7 维（{@code resourceScopes}）而不是新加权限字段</b>：技能范围必须与工具、目录、档位、预算同走 “逐跳只减不增”的单调性守卫（红线 1）。第
 * 7 维已经带着 {@link ResourceScope#narrowTo}（求交）与 {@link ResourceScope#covers}（涵盖）两段现成语义，{@code
 * PermissionChecker.isSubset} 也已逐命名空间比对——把它落成两个命名空间， 单调性、求交、事件诊断三件事都不用第二套实现。
 *
 * <p><b>三档语义（写死，不许有第四种读法）</b>：
 *
 * <ul>
 *   <li><b>未表态</b>（权限集里没有这个命名空间）⇒ 读作“<b>一个技能都看不见/改不了</b>”。这不是本类自造的规则：技能工具声明的缺省策略是 {@code DENY}（见
 *       {@code SkillTools}），{@code ResourceScopeMap} 的“未声明 = 本层不表态 ⇒ 回落下一层”落到的那一层正是
 *       DENY。于是“没配技能”与“技能全禁”在行为上一致，不存在“缺省静默放大”的中间态；
 *   <li><b>显式 {@code none()}</b> ⇒ 同上（看不见/改不了），但它是一个<b>表态</b>：会被单调性守卫看见，子级不能借“没表态”把它洗掉；
 *   <li><b>{@code unlimited()} 或前缀集</b> ⇒ 前缀即技能文件系统路径：一个<b>技能文件夹</b>（子树）或一个<b>单个 {@code
 *       SKILL.md}</b>。<b>要“全可见”必须在配置里显式写</b>（主 Agent 的装配就是这么做的）。
 * </ul>
 *
 * <p><b>不变式：可编辑面 ⊆ 可见面</b>。{@link #withScopes} 在写入权限集时把可编辑面与可见面求交，{@code SubagentManager}
 * 物化子体时也走这里——于是“改得动但看不见”这种自相矛盾的取值在结构上造不出来。
 */
public final class SkillPermissions {

  /** 技能名的形态约束（≡ 目录名；与 {@code AgentTemplate} 的 id 同形，可直接当目录名用，且不含路径分隔符）。 */
  public static final String NAME_PATTERN = "[a-z0-9][a-z0-9-]{0,63}";

  private SkillPermissions() {}

  /** 可见面（未表态 ⇒ {@link ResourceScope#none()}，fail-closed）。 */
  public static ResourceScope visible(AgentPermissionSet permissions) {
    return declared(permissions, ResourceScopeMap.SKILLS);
  }

  /** 可编辑面（未表态 ⇒ {@link ResourceScope#none()}，fail-closed）。 */
  public static ResourceScope editable(AgentPermissionSet permissions) {
    return declared(permissions, ResourceScopeMap.SKILLS_EDIT);
  }

  /** 某个技能文件（{@code SKILL.md}）是否可见。 */
  public static boolean isVisible(AgentPermissionSet permissions, Path skillFile) {
    Objects.requireNonNull(skillFile, "skillFile");
    return visible(permissions).allowsDir(skillFile);
  }

  /** 某个技能文件是否可编辑。 */
  public static boolean isEditable(AgentPermissionSet permissions, Path skillFile) {
    Objects.requireNonNull(skillFile, "skillFile");
    return editable(permissions).allowsDir(skillFile);
  }

  /** 目录扫描结果的可见性过滤器（供 {@link SkillCatalog#listing(Predicate)} 与预载使用）。 */
  public static Predicate<Skill> visibleFilter(AgentPermissionSet permissions) {
    ResourceScope scope = visible(permissions);
    return skill -> scope.allowsDir(skill.skillFile());
  }

  /**
   * 换掉两个技能面（其余命名空间逐字保留）；<b>可编辑面自动与可见面求交</b>（不变式见类 javadoc）。
   *
   * @param permissions 原权限集（非 null）
   * @param visible 新的可见面（非 null）
   * @param editable 新的可编辑面（非 null；超出可见面的部分被求交掉，不放大）
   */
  public static AgentPermissionSet withScopes(
      AgentPermissionSet permissions, ResourceScope visible, ResourceScope editable) {
    Objects.requireNonNull(permissions, "permissions");
    Objects.requireNonNull(visible, "visible");
    Objects.requireNonNull(editable, "editable");
    Map<String, ResourceScope> scopes = new TreeMap<>(permissions.resourceScopes().byNamespace());
    scopes.put(ResourceScopeMap.SKILLS, visible);
    scopes.put(ResourceScopeMap.SKILLS_EDIT, editable.narrowTo(visible));
    return permissions.withResourceScopes(new ResourceScopeMap(scopes));
  }

  /** 取一个命名空间的表态（未表态 ⇒ {@code none()}，见类 javadoc 的三档语义）。 */
  private static ResourceScope declared(AgentPermissionSet permissions, String namespace) {
    if (permissions == null) {
      return ResourceScope.none();
    }
    ResourceScope scope = permissions.resourceScopes().declaredScope(namespace);
    return scope == null ? ResourceScope.none() : scope;
  }

  /**
   * 把一个技能作用域编码成可经 argv 传递的文本表（子 Agent 进程恢复自己的面用）。
   *
   * <p>编码：{@code unlimited ⇒ ["*"]}；{@code none ⇒ ["none"]}；受限 ⇒ 逐个前缀一条（前缀本身禁止 {@code .}/{@code ..}
   * 段， 见 {@link ResourceScope}，故这里只需原样导出）。{@code "*"}/{@code "none"} 与路径项混用是非法取值——那种表读起来有歧义
   * （到底是不限还是这几个路径？），{@link #decode} 会响亮拒绝而不是替写的人挑一个。
   */
  public static List<String> encode(ResourceScope scope) {
    Objects.requireNonNull(scope, "scope");
    if (scope.unrestricted()) {
      return List.of("*");
    }
    if (scope.prefixes().isEmpty()) {
      return List.of("none");
    }
    return List.copyOf(scope.prefixList());
  }

  /**
   * {@link #encode} 的逆运算；<b>空表读作 {@code none}</b>（不是“不限”）——缺省方向必须朝收紧的一侧。
   *
   * @throws IllegalArgumentException 取值自相矛盾（{@code *}/{@code none} 与路径混用、重复的特殊值）
   */
  public static ResourceScope decode(List<String> encoded) {
    if (encoded == null || encoded.isEmpty()) {
      return ResourceScope.none();
    }
    boolean unlimited = false;
    boolean none = false;
    List<String> prefixes = new ArrayList<>();
    for (String item : encoded) {
      String text = item == null ? "" : item.strip();
      if (text.isEmpty()) {
        throw new IllegalArgumentException("技能作用域取值不能为空白: " + encoded);
      }
      switch (text) {
        case "*" -> unlimited = true;
        case "none" -> none = true;
        default -> prefixes.add(text);
      }
    }
    if ((unlimited && none) || ((unlimited || none) && !prefixes.isEmpty())) {
      throw new IllegalArgumentException("技能作用域取值不能把 '*'/'none' 与路径混用（语义有歧义）: " + encoded);
    }
    if (unlimited) {
      return ResourceScope.unlimited();
    }
    if (none) {
      return ResourceScope.none();
    }
    return ResourceScope.of(prefixes.toArray(String[]::new));
  }
}
