package io.mosire.workspace;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * 挂载（设计 §4.1、§4.3）：一个虚拟根名 + 一个物理目录（v1 一对一）+ 一组能力 id + {@code holder}（创建者）+ {@code user}（使用者）。
 *
 * <p><b>本体的核心是"映射 + 鉴权"，不是"一块具名目录"</b>：物理目录只是映射的一种落点；换 resolver 不改调用方，物理布局 （{@code
 * <dataDir>/workspaces/...}、分片、store 覆盖目录）对模型不可见。
 *
 * <p><b>holder 与 user 的区别（N11）</b>：
 *
 * <ul>
 *   <li>{@link #holder()}：<b>创建者</b>——归属 + 生命周期权限（M5：只有创建者或其祖先能卸载）；恒为创建者本人（M2）， 不许代他人署名；
 *   <li>{@link #user()}：<b>使用者</b>——<b>解析按它索引</b>（"谁的挂载表"），只能是自己或自己的后代（M2）。
 * </ul>
 *
 * <p>两者在"自己给自己挂"时相同，在"父为子挂"时不同（父 = holder，子 = user）。虚拟挂载名的唯一性按<b>使用者</b>分域 （A4/M4）：不同的使用者可以各自有一个
 * {@code workspace}（设计 §4.7 的递归结构就是靠这一条成立的）。
 *
 * @param name 虚拟根名（形态见 {@link MountName}）
 * @param holder 创建者身份键（恒为创建者本人，M2）
 * @param user 使用者身份键（解析索引键，M2：自己或后代）
 * @param dir 物理落点（绝对路径、已归一；{@code ..} 一律拒，A6）
 * @param resolver 映射策略（v1 只有 {@link Resolvers#direct()}；null ⇒ 取一对一）
 * @param kind 纪律（决定 shell 待遇，{@link Kind}）
 * @param capabilities 能力 id 集合（空集 = 谁都不放行，fail-closed；未注册的 id 在挂载时被拒）
 * @param origin 挂载来源（决定进不进 bash 遮蔽面，M6）
 */
public record Mount(
    MountName name,
    String holder,
    String user,
    Path dir,
    Resolver resolver,
    Kind kind,
    Set<String> capabilities,
    Origin origin) {

  public Mount {
    Objects.requireNonNull(name, "name");
    holder = requireIdentity(holder, "holder");
    user = requireIdentity(user, "user");
    dir = requireDir(dir);
    // 缺省 = 一对一（v1 唯一实现）。给 null 不是"没有映射策略"，而是"用标准的那一个"——direct() 是fail-closed 的一侧
    resolver = resolver == null ? Resolvers.direct() : resolver;
    Objects.requireNonNull(kind, "kind");
    // 内联 Set.copyOf：让 SpotBugs 看得见不可变来源（本仓既有写法）
    capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    Objects.requireNonNull(origin, "origin");
  }

  /** 常用形态的语法糖（resolver 取 {@link Resolvers#direct()}）。 */
  public static Mount of(
      MountName name,
      String holder,
      String user,
      Path dir,
      Kind kind,
      Set<String> capabilities,
      Origin origin) {
    return new Mount(name, holder, user, dir, Resolvers.direct(), kind, capabilities, origin);
  }

  /** 物理落点是否落在本挂载的授权范围内（段边界，A6/G1；同一实现服务 containment 校验与级联判定）。 */
  boolean contains(Path candidate) {
    Objects.requireNonNull(candidate, "candidate");
    Path normalized =
        candidate.isAbsolute() ? candidate.normalize() : candidate.toAbsolutePath().normalize();
    // Path.startsWith 是按段比较的（/a/b 不以 /a/bc 开头），不需要再手写 "+ /" 的字符串版本
    return normalized.equals(dir) || normalized.startsWith(dir);
  }

  /** 严格落在本挂载之内（不含等于）——子工作区/级联失效的判据（W1/W2、M10）。 */
  boolean strictlyContains(Path candidate) {
    Objects.requireNonNull(candidate, "candidate");
    Path normalized =
        candidate.isAbsolute() ? candidate.normalize() : candidate.toAbsolutePath().normalize();
    return normalized.startsWith(dir) && !normalized.equals(dir);
  }

  private static String requireIdentity(String value, String field) {
    String key = value == null ? "" : value.strip();
    if (key.isEmpty()) {
      throw new IllegalArgumentException("挂载的 " + field + " 不能为空");
    }
    if (key.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("挂载的 " + field + " 不得含空白字符: '" + key + "'");
    }
    return key;
  }

  /**
   * 物理落点：绝对 + 已归一（A6）。<b>不解析软链</b>：本模块判的是"命名"而不是"可达性"（同 {@code ResourceScope} 的分级）——真正的结构性围栏是执行侧的
   * L3 沙箱。
   */
  private static Path requireDir(Path dir) {
    Objects.requireNonNull(dir, "dir");
    if (!dir.isAbsolute()) {
      throw new IllegalArgumentException("挂载的物理落点必须是绝对路径: " + dir);
    }
    Path normalized = dir.normalize();
    if (!normalized.equals(dir)) {
      throw new IllegalArgumentException("挂载的物理落点必须已归一（不接受 '.' / '..' 段或重复分隔符）: " + dir);
    }
    return normalized;
  }
}
