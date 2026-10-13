package io.mosire.workspace;

import io.mosire.agentlib.permission.Operation;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 工作区能力：<b>在这个工作区里</b>允许做的一类操作（设计 §4.1、§4.8，N9）。
 *
 * <p><b>能力是"在工作区里能干什么"的唯一决定点</b>（A8）：能力只在<b>挂载 / 授予时</b>落成"命名空间 + 前缀"与 shell 可见位；
 * 运行期不再有第二处判定——工具族不得自行放宽准入，也不得绕过工作区另开一套"能不能用"。
 *
 * <table>
 *   <caption>v1 的能力表（设计 §4.8）</caption>
 *   <tr><th>id</th><th>落格（授予时写进权限集）</th><th>shell 可见</th><th>适用纪律</th></tr>
 *   <tr><td>{@code docs}</td><td>{@code docs} ← dir（READ）；{@code docs-edit} ← dir（WRITE）</td>
 *       <td>否（遮蔽 / 只读）</td><td>{@link Kind#LOG} / {@link Kind#FILE}</td></tr>
 *   <tr><td>{@code bash}（后续模块）</td><td>{@code fs} ← dir（READ + WRITE）</td><td>是（不遮蔽）</td>
 *       <td>仅 {@link Kind#FILE} / {@link Kind#SCRATCH}</td></tr>
 * </table>
 *
 * <p><b>本模块不注册任何能力</b>：{@code docs} 由 docs-mosire 注册、{@code bash} 由后续命令面模块注册——能力目录的注册
 * 是各工具族的装配动作，工作区模块只提供目录与落格机制（否则"谁定义了能力"就又成了一个散在各处的知识）。
 *
 * @param id 能力 id（{@code docs} / {@code bash} / …）——工具族用它向工作区模块申请准入
 * @param namespaces 授予时落格的命名空间与操作（如 docs 能力 = {@code {docs: {READ}, docs-edit: {WRITE}}}）；
 *     <b>不得为空</b>：一个什么都不落格的能力等于什么都没授权，注册它只会让人误以为"给了权限"
 * @param shellVisible 是否允许 shell 看见该挂载（docs = false；bash = true）。这是<b>声明期的表态</b>，运行期不重判
 * @param allowedCommands 仅 {@code bash} 能力用：命令白名单（N12；<b>空集 = 一条命令都不许</b>，fail-closed）
 */
public record Capability(
    String id,
    Map<String, Set<Operation>> namespaces,
    boolean shellVisible,
    Set<String> allowedCommands) {

  public Capability {
    id = id == null ? "" : id.strip();
    if (id.isEmpty()) {
      throw new IllegalArgumentException("能力 id 不能为空");
    }
    if (id.chars().anyMatch(Character::isWhitespace)) {
      // 带空白/分隔符的 id 会在落格时变成两个命名空间或一个错键，装配期拒比运行期"看起来有权限"便宜
      throw new IllegalArgumentException("能力 id 不得含空白字符: '" + id + "'");
    }
    Map<String, Set<Operation>> given = namespaces == null ? Map.of() : namespaces;
    if (given.isEmpty()) {
      throw new IllegalArgumentException("能力必须至少落格一个命名空间（否则它什么也不放行）: " + id);
    }
    Map<String, Set<Operation>> normalized = new TreeMap<>();
    for (Map.Entry<String, Set<Operation>> entry : given.entrySet()) {
      String namespace = entry.getKey() == null ? "" : entry.getKey().strip();
      if (namespace.isEmpty()) {
        throw new IllegalArgumentException("能力的命名空间不能为空: " + id);
      }
      Set<Operation> operations = entry.getValue();
      if (operations == null || operations.isEmpty()) {
        throw new IllegalArgumentException("能力在命名空间 " + namespace + " 上的操作集不能为空: " + id);
      }
      normalized.put(namespace, Set.copyOf(operations));
    }
    // 内联 Map.copyOf：让 SpotBugs 看得见不可变来源（本仓既有写法，见 AgentPermissionSet）
    namespaces = Map.copyOf(normalized);
    allowedCommands = allowedCommands == null ? Set.of() : Set.copyOf(allowedCommands);
  }

  /** 常用形态的语法糖（读起来更接近"注册一个能力"）。 */
  public static Capability of(
      String id,
      Map<String, Set<Operation>> namespaces,
      boolean shellVisible,
      Set<String> allowedCommands) {
    return new Capability(id, namespaces, shellVisible, allowedCommands);
  }

  /** 落格的命名空间集合（{@link #namespaces()} 是"命名空间 → 操作"的表，这里只要键）。 */
  public Set<String> namespaceNames() {
    return namespaces.keySet();
  }
}
