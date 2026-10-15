package io.mosire.agentlib.permission;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 装订期角色目录审计（设计 §4.6「装订期：派生 + 审计」的机制面；不变量 A-4 / A-5）：装配期把工具注册表扫一遍， 在进程开始对外服务之前把"角色维的漂移"变成一条可见的结论。
 *
 * <p><b>为什么需要它</b>：角色名对 AgentLib 是<b>不透明标签</b>（设计 §4.3.1），判定侧只做"调用者角色 ∩ 工具声明角色 ≠ ∅"。
 * 这留下两类<b>静默</b>失败：
 *
 * <ul>
 *   <li>工具声明了一个<b>拼错 / 未登记</b>的角色名 ⇒ 该工具<b>永远不可达</b>，但进程照常起、日志照常干净——正是 §4.6 说的"漂移无症状"（A-4）；
 *   <li>宿主另有一份<b>手抄</b>的角色→工具目录（"哪些工具属于哪一格"），它与从注册表<b>派生</b>出来的目录各写一遍 ⇒ 两份真相， 改了一处另一处不跟着动（A-5，也就是
 *       §4.6 点名的"隔壁 #3"形状）。
 * </ul>
 *
 * <p>本类把这两类都拦在<b>装订期</b>：未登记角色名 ⇒ 失败；派生清单 ⇔ 手抄清单不符 ⇒ 失败。第三类结论
 * ——"声明了角色但没有任何调用者配置能覆盖"——只<b>报告</b>：它可能是"这个角色本部署还没配"的正常部署态，不是 bug。 <b>失败如何响亮由调用方决定</b>（{@code
 * Main} 的装配层读 {@link Report#hasFailures()}：本仓口径 = 启动期原样上抛 RuntimeException），本类只产出结论，不抛错、不退出、不写事件。
 *
 * <p><b>为什么机制在 AgentLib，而派生函数由宿主注入</b>：通用层不认识 {@code GM}/{@code DM}，也不认识 {@code GM_ONLY}/{@code
 * DM_APPROVAL}/{@code DM_FREE} 这类分格（P1 / §4.3）——"哪些角色存在""哪些工具算哪一格"全是宿主的知识。 AgentLib 只提供"枚举注册表 +
 * 套用宿主给的派生函数 + 与宿主给的清单对账"这套机制：派生函数以 {@link RoleDerivation} 注入，注册表里 {@link AgentTool#roles()}
 * 的<b>原始声明</b>对通用层始终只是字符串集合。
 *
 * <p><b>v1 范围（如实记）</b>：本版做 ① 未登记角色名 ⇒ 失败、② 无调用者能覆盖的角色 / 工具 ⇒ 报告、③ 派生 ⇔ 手抄对账 （宿主<b>两样都</b>给了才做）⇒
 * 失败。"报告项在严格模式下升级为失败"<b>登记为后续</b>——本类刻意<b>不</b>提供 strict 开关：一个今天永远读不到的参数就是死参数，还会让调用方误以为严格模式已经存在。
 *
 * <p><b>今天的行为（零行为变化）</b>：全仓生产代码没有任何工具覆写 {@link AgentTool#roles()}（只有缺省空集 = 本维不表态）， 所以对真实注册表，本审计的
 * <b>失败恒为空</b>（不改变任何执行结果）；<b>报告</b>则只在宿主给了非空 {@code knownRoles}（例如配了 {@code agent.roles}
 * 却没有任何工具声明它）时才非空——那正是检查 ② 想让人看见的"这一维今天不起作用"，不是失败，也不拦启动。
 *
 * <p><b>线程与生命周期</b>：只读 {@link ToolRegistry#list()} 的当刻快照（该快照是排序副本），不注册、不改变更、无静态状态； 本类实例不可变，{@link
 * #run()} 可重复调用（每次都重新取注册表快照）。
 */
public final class RoleCatalogAudit {

  private final ToolRegistry registry;
  private final Set<String> knownRoles;
  private final RoleDerivation derivation;
  private final Map<String, Set<String>> handwrittenCatalog;

  private RoleCatalogAudit(
      ToolRegistry registry,
      Set<String> knownRoles,
      RoleDerivation derivation,
      Map<String, Set<String>> handwrittenCatalog) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.knownRoles = knownRoles;
    this.derivation = derivation;
    this.handwrittenCatalog = handwrittenCatalog;
  }

  /**
   * 建一次审计：注册表 + 宿主给出的"已知角色全集"。
   *
   * <p>派生函数与手抄清单都不给是合法形态：前者缺省 ⇒ 检查 ② 用"注册表自身派生的目录"（角色 → 声明了它的工具）， 后者缺省 ⇒ 检查 ③ 跳过。
   *
   * @param registry 工具注册表（{@link #run()} 取它的当刻快照）
   * @param knownRoles 已知角色全集（宿主注入：主 Agent 角色 ∪ 在场模板声明的角色 ∪ 外部 MCP 面角色）
   * @throws IllegalArgumentException {@code knownRoles} 含空白名——那是宿主 bug，静默收下会让"未登记角色"的判定失真
   */
  public static RoleCatalogAudit of(ToolRegistry registry, Collection<String> knownRoles) {
    Objects.requireNonNull(knownRoles, "knownRoles");
    Set<String> normalized = new TreeSet<>();
    for (String role : knownRoles) {
      if (role == null || role.isBlank()) {
        throw new IllegalArgumentException("knownRoles 含空白角色名: " + role);
      }
      normalized.add(role.trim());
    }
    return new RoleCatalogAudit(registry, Set.copyOf(normalized), null, null);
  }

  /**
   * 注入派生函数（宿主把注册表派生成 角色 → 工具名集合，例如隔壁的三格分类）。
   *
   * <p>不注入 ⇒ 检查 ② 用注册表里 {@link AgentTool#roles()} 的原始声明做缺省目录；检查 ③ 因缺派生清单而跳过。
   */
  public RoleCatalogAudit withDerivation(RoleDerivation derivation) {
    return new RoleCatalogAudit(
        registry, knownRoles, Objects.requireNonNull(derivation, "derivation"), handwrittenCatalog);
  }

  /**
   * 注入手抄清单（宿主自己维护的那份 角色 → 工具名集合）。
   *
   * <p>与派生函数<b>同时</b>提供才会做检查 ③（对账）；只给一份 ⇒ 跳过——不是"空跑通过"，而是"本部署没提供这一层"。
   */
  public RoleCatalogAudit withHandwrittenCatalog(Map<String, Set<String>> handwrittenCatalog) {
    return new RoleCatalogAudit(
        registry, knownRoles, derivation, copyCatalog(handwrittenCatalog, "handwrittenCatalog"));
  }

  /**
   * 跑一次审计（纯读）。
   *
   * <p>三条检查的输入与结论：① 每个工具 {@link AgentTool#roles()} 里的名字 ∉ {@code knownRoles} ⇒ 失败明细（含工具名 + 角色名）； ②
   * 已知角色（或派生目录里的角色）底下没有任何工具、或某工具声明的角色<b>全都</b>不在 {@code knownRoles} 内 ⇒ 报告明细； ③ 派生目录与手抄清单不一致 ⇒
   * 失败明细（多 / 少哪些条目）。
   *
   * @return 不可变报告：计数 + 三类明细 + {@link Report#hasFailures()} + {@link Report#summary()}
   */
  public Report run() {
    List<AgentTool> tools = registry.list();
    List<String> unknownRoleFindings = new ArrayList<>();
    List<String> uncoveredRoleFindings = new ArrayList<>();
    int roleDeclaringToolCount = 0;
    for (AgentTool tool : tools) {
      // null 按"不表态"读（AgentTool#roles 的防呆口径）；空集 = 本维不表态 ⇒ 不参与本审计（A-2：既有工具行为不变）
      Set<String> declared = tool.roles();
      if (declared == null || declared.isEmpty()) {
        continue;
      }
      roleDeclaringToolCount++;
      Set<String> normalized = new TreeSet<>();
      Set<String> unknown = new TreeSet<>();
      for (String candidate : declared) {
        String name = candidate == null ? "" : candidate.trim();
        if (name.isEmpty()) {
          // 空白名匹配不了任何调用者（RoleSet#intersects 的口径）⇒ 与"拼错的角色"同类：永远不可达
          unknown.add("");
          continue;
        }
        normalized.add(name);
        if (!knownRoles.contains(name)) {
          unknown.add(name);
        }
      }
      if (!unknown.isEmpty()) {
        unknownRoleFindings.add(
            "工具 "
                + tool.name()
                + " 声明了未登记角色: "
                + renderNames(unknown)
                + "（拼错的角色 = 永远不可达的工具；设计 §4.6 不变量 A-4）");
      }
      if (!normalized.isEmpty() && normalized.stream().noneMatch(knownRoles::contains)) {
        uncoveredRoleFindings.add(
            "工具 "
                + tool.name()
                + " 声明的角色 "
                + renderNames(normalized)
                + " 全都不在已知角色集内——没有任何调用者配置能覆盖它（今天不可达）");
      }
    }
    Map<String, Set<String>> derived =
        derivation == null
            ? deriveFromRegistry(tools)
            : copyCatalog(derivation.derive(registry), "derivation");
    // ② 的另一半：角色底下没有工具（含"派生目录里有这个角色但没有工具"这种宿主自相矛盾）
    Set<String> rolesToCheck = new TreeSet<>(knownRoles);
    rolesToCheck.addAll(derived.keySet());
    for (String role : rolesToCheck) {
      if (derived.getOrDefault(role, Set.of()).isEmpty()) {
        uncoveredRoleFindings.add("角色 '" + role + "' 底下没有任何工具——没有工具声明它（这一维今天不起作用）");
      }
    }
    List<String> catalogDiffFindings = new ArrayList<>();
    boolean catalogChecked = derivation != null && handwrittenCatalog != null;
    if (catalogChecked) {
      Set<String> roles = new TreeSet<>(derived.keySet());
      roles.addAll(handwrittenCatalog.keySet());
      for (String role : roles) {
        Set<String> derivedTools = derived.get(role);
        Set<String> handwrittenTools = handwrittenCatalog.get(role);
        if (derivedTools == null) {
          catalogDiffFindings.add(
              "手抄清单多出角色 '" + role + "'（派生清单里没有这个角色）：" + describeTools(handwrittenTools));
        } else if (handwrittenTools == null) {
          catalogDiffFindings.add(
              "手抄清单缺角色 '" + role + "'（派生清单里有这个角色）：" + describeTools(derivedTools));
        } else if (!derivedTools.equals(handwrittenTools)) {
          Set<String> missing = new TreeSet<>(derivedTools);
          missing.removeAll(handwrittenTools);
          Set<String> extra = new TreeSet<>(handwrittenTools);
          extra.removeAll(derivedTools);
          List<String> parts = new ArrayList<>();
          if (!missing.isEmpty()) {
            parts.add("手抄清单缺少 " + renderNames(missing));
          }
          if (!extra.isEmpty()) {
            parts.add("手抄清单多出 " + renderNames(extra));
          }
          catalogDiffFindings.add("角色 '" + role + "' 的工具集不一致：" + String.join("；", parts));
        }
      }
    }
    return new Report(
        tools.size(),
        roleDeclaringToolCount,
        knownRoles.size(),
        catalogChecked,
        unknownRoleFindings,
        uncoveredRoleFindings,
        catalogDiffFindings);
  }

  /**
   * 宿主注入的派生函数：把注册表派生成 角色 → 工具名集合（例如隔壁的 {@code GM_ONLY / DM_APPROVAL / DM_FREE} 三格分类）。
   *
   * <p><b>为什么是注入而不是本类内建</b>：分格是宿主的知识，AgentLib 不认识具体角色名（设计 §4.3 / §4.6）。 缺省（不注入）时本类只用注册表里 {@link
   * AgentTool#roles()} 的原始声明做一张"角色 → 声明了它的工具"的目录。
   */
  @FunctionalInterface
  public interface RoleDerivation {

    /**
     * @param registry 工具注册表（宿主通常取 {@code list()} 的当刻快照来算）
     * @return 角色 → 工具名集合（键为角色名，值为工具名；允许为空表）
     */
    Map<String, Set<String>> derive(ToolRegistry registry);
  }

  /**
   * 一次审计的报告：计数 + 三类明细。<b>不可变</b>；失败由调用方（装配层）决定怎么响亮。
   *
   * @param toolCount 注册表里的工具总数
   * @param roleDeclaringToolCount 声明了非空角色集的工具数（空集 / null = 本维不表态，不计入）
   * @param knownRoleCount 宿主注入的已知角色数
   * @param catalogChecked 是否做了派生 ⇔ 手抄对账（宿主两样都给了才做；{@code false} = 本部署没提供这一层， <b>不是</b>"对账通过"）
   * @param unknownRoleFindings ① 未登记角色名的明细（含工具名 + 角色名）⇒ 计入 {@link #hasFailures()}
   * @param uncoveredRoleFindings ② 声明了角色但没有任何调用者配置能覆盖的明细 ⇒ v1 只报告、不失败
   * @param catalogDiffFindings ③ 派生 ⇔ 手抄不一致的明细（多 / 少哪些条目）⇒ 计入 {@link #hasFailures()}
   */
  public record Report(
      int toolCount,
      int roleDeclaringToolCount,
      int knownRoleCount,
      boolean catalogChecked,
      List<String> unknownRoleFindings,
      List<String> uncoveredRoleFindings,
      List<String> catalogDiffFindings) {

    public Report {
      unknownRoleFindings = List.copyOf(unknownRoleFindings);
      uncoveredRoleFindings = List.copyOf(uncoveredRoleFindings);
      catalogDiffFindings = List.copyOf(catalogDiffFindings);
    }

    /** 有失败吗（① 未登记角色名 / ③ 目录对账不符）。空报告（今天的真实注册表）恒 {@code false}。 */
    public boolean hasFailures() {
      return !unknownRoleFindings.isEmpty() || !catalogDiffFindings.isEmpty();
    }

    /** 全部失败明细（调用方拼错误消息用）：① 在前、③ 在后，顺序稳定。 */
    public List<String> failures() {
      List<String> all = new ArrayList<>(unknownRoleFindings);
      all.addAll(catalogDiffFindings);
      return List.copyOf(all);
    }

    /** 一行摘要（日志用）：计数 + 失败 / 报告条数 + 对账是否做过；明细走三个访问器。 */
    public String summary() {
      return "装订期角色审计: 工具="
          + toolCount
          + " 声明了角色的工具="
          + roleDeclaringToolCount
          + " 已知角色="
          + knownRoleCount
          + " 失败="
          + (unknownRoleFindings.size() + catalogDiffFindings.size())
          + " 报告="
          + uncoveredRoleFindings.size()
          + " 目录对账="
          + (catalogChecked ? "已做" : "未提供");
    }
  }

  /** 缺省派生：角色 → 声明了它的工具名（空白名留给出错的那条检查点名，不进目录）。 */
  private static Map<String, Set<String>> deriveFromRegistry(List<AgentTool> tools) {
    Map<String, Set<String>> derived = new TreeMap<>();
    for (AgentTool tool : tools) {
      Set<String> declared = tool.roles();
      if (declared == null) {
        continue;
      }
      for (String candidate : declared) {
        String name = candidate == null ? "" : candidate.trim();
        if (name.isEmpty()) {
          continue;
        }
        derived.computeIfAbsent(name, key -> new TreeSet<>()).add(tool.name());
      }
    }
    return freeze(derived);
  }

  /** 宿主目录的防御拷贝：角色名去首尾空白（与 {@link RoleSet} 同口径），工具名原样保留；坏名响亮失败。 */
  private static Map<String, Set<String>> copyCatalog(
      Map<String, Set<String>> catalog, String what) {
    Objects.requireNonNull(catalog, what);
    Map<String, Set<String>> copy = new TreeMap<>();
    for (Map.Entry<String, Set<String>> entry : catalog.entrySet()) {
      String role = entry.getKey();
      if (role == null || role.isBlank()) {
        throw new IllegalArgumentException(what + " 含空白角色名: " + role);
      }
      Set<String> tools =
          new TreeSet<>(
              Objects.requireNonNull(entry.getValue(), what + " 的角色 '" + role + "' 没有工具集"));
      copy.put(role.trim(), Set.copyOf(tools));
    }
    return Map.copyOf(copy);
  }

  /** 目录定形：内联 {@code Set.copyOf}（本仓既有写法，让 SpotBugs 看得见不可变来源）。 */
  private static Map<String, Set<String>> freeze(Map<String, Set<String>> catalog) {
    Map<String, Set<String>> frozen = new TreeMap<>();
    for (Map.Entry<String, Set<String>> entry : catalog.entrySet()) {
      frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
    }
    return Map.copyOf(frozen);
  }

  /** 工具集的人读渲染（明细用）：空集显式写出来，避免"工具 "后面跟一段空白。 */
  private static String describeTools(Set<String> tools) {
    return tools.isEmpty() ? "工具集为空" : "工具 " + renderNames(tools);
  }

  /** 名字列表的人读渲染（明细用）：升序由调用方的 {@code TreeSet} 保证；空白名显式点名。 */
  private static String renderNames(Collection<String> names) {
    List<String> rendered = new ArrayList<>();
    for (String name : names) {
      rendered.add(name == null || name.isEmpty() ? "<空白>" : "'" + name + "'");
    }
    return String.join(", ", rendered);
  }
}
