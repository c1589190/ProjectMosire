package io.mosire.agentlib.permission;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一个命名空间内**可达区域**的显式取值（S5-B）：{@code unrestricted} 位 + 一组前缀。
 *
 * <p><b>为什么必须有 {@code unrestricted} 位</b>：白名单的"空集 = 全拒"与目录集的"空集 = ?"会直接打架—— 空集到底
 * 是"哪里都不许"还是"没写就随便"？不显式区分就会得到一个**看创建者心情**的语义。所以：{@code unrestricted=true} ⇒
 * "不限"（前缀必须为空，构造期守卫）；{@code unrestricted=false} 且前缀为空 ⇒
 * "哪里都不许"（deny-all，与工具白名单同口径）。二者**不同**，谁也不能退化成对方。
 *
 * <p><b>区域包含是"段边界"不是字符串前缀</b>：{@code /srv/work} <b>不</b>含 {@code /srv/work-evil}，也不含 {@code
 * /srv/work2}——字符串 {@code startsWith} 会把这两个放进围栏，而它们是**另一个目录**。判定规则：{@code c == p} 或 {@code
 * c.startsWith(p + "/")}（根 {@code /} 特殊处理）。
 *
 * <p><b>两道集运算的语义</b>（逐条写死，S5-B/C 都靠它）：
 *
 * <ul>
 *   <li>{@link #narrowTo}（交集）：不限 ∩ X = X；两个受限集求交 = 每对嵌套前缀里留**窄**的那个，互不相交的对不产生条目 ⇒ 结果可能是"空集 =
 *       哪里都不许"（这正是"两边都要满足"的正确答案）；
 *   <li>{@link #covers}（单调性）：不限涵盖一切；受限方必须逐前缀被涵盖——**受限 ≠ 涵盖**：一组前缀涵盖另一组， 当且仅当后者每个前缀都落在前者某个前缀之内。
 * </ul>
 *
 * <p><b>规范化</b>：前缀一律去掉重复 {@code /}、去掉尾部 {@code /}（根 {@code /} 除外）、拒绝空串与 {@code .}/{@code ..}
 * 段（前缀是"容器"，容器里出现相对段说明写的人把两个概念搞混了）。{@link #ofDirs} 在此之上把根按 {@code /} 的语义处理： 绝对化 + 词法归一，**已存在**的根再取
 * {@code toRealPath()}（化解"根本身是软链"的假安全，见 {@code 设计-Bash工具与工作目录围栏.md} §1.5）。
 *
 * <p><b>诚实分级</b>：本类型判的是"**命名**"（这个词法上属于允许区吗），不是"**可达性**"——软链、绑定挂载、{@code ..}
 * 之后的重解析都不在它的承诺范围内。真正的结构性围栏是 L3 沙箱（没 bind 的路径根本不存在）。
 */
public record ResourceScope(boolean unrestricted, Set<String> prefixes) {

  /** 前缀里的段分隔符（命名空间内的资源路径一律用它切段，Windows 反斜杠不参与）。 */
  public static final String SEPARATOR = "/";

  public ResourceScope {
    prefixes = prefixes == null ? Set.of() : new LinkedHashSet<>(prefixes);
    if (unrestricted) {
      if (!prefixes.isEmpty()) {
        throw new IllegalArgumentException(
            "unrestricted 的作用域不能带前缀（'不限'与'列出哪些地方'是两种取值）: " + prefixes);
      }
      prefixes = Set.of();
    } else {
      Set<String> normalized = new LinkedHashSet<>();
      for (String prefix : prefixes) {
        normalized.add(normalizePrefix(prefix));
      }
      prefixes = Set.copyOf(normalized);
    }
  }

  /** "不限"（= 本功能引入前的行为）。与 {@link #none()} 是两种取值，不是"没写"。 */
  public static ResourceScope unlimited() {
    return new ResourceScope(true, Set.of());
  }

  /** "哪里都不许"（显式空集，**不是**"没写"）。 */
  public static ResourceScope none() {
    return new ResourceScope(false, Set.of());
  }

  /** 按前缀建一个受限作用域（至少一个；要"哪里都不许"用 {@link #none()}）。 */
  public static ResourceScope of(String... prefixes) {
    if (prefixes == null || prefixes.length == 0) {
      throw new IllegalArgumentException("必须给至少一个前缀；空集请用 ResourceScope.none()");
    }
    return new ResourceScope(false, Set.of(prefixes));
  }

  /**
   * 目录作用域（{@code fs} 命名空间）：绝对化 + 词法归一，**已存在**的根再取 realpath。
   *
   * <p><b>不存在的根照样收</b>（"以后会建的工作目录"是正当需求）：只是它拿不到 realpath 那一层保证，所以 本次比对一律走词法。
   */
  public static ResourceScope ofDirs(Collection<Path> roots) {
    Objects.requireNonNull(roots, "roots");
    Set<String> prefixes = new LinkedHashSet<>();
    for (Path root : roots) {
      prefixes.add(dirPrefix(root));
    }
    return new ResourceScope(false, prefixes);
  }

  /** 单个目录作用域（语法糖）。 */
  public static ResourceScope ofDir(Path root) {
    return ofDirs(List.of(root));
  }

  /**
   * 候选资源是否落在本作用域内。
   *
   * <p><b>候选先按词法归一</b>（折叠重复分隔符、去尾分隔符、解析 {@code .}/{@code ..} 段，见 {@link #normalizeCandidate}）—— 与
   * {@link #allowsDir(Path)} 同口径：同一块资源经两个 API 问必须得到同一个答案（S5-C 补：字符串路径过去不解析 {@code ..}， 于是 {@code
   * /srv/work/../etc/shadow} 会被判成"在 /srv/work 之内"）。
   *
   * @param candidate 命名空间内的资源路径（{@code fs} 用绝对路径字符串）
   */
  public boolean allows(String candidate) {
    if (unrestricted) {
      return true;
    }
    String normalized = normalizeCandidate(candidate);
    for (String prefix : prefixes) {
      if (contains(prefix, normalized)) {
        return true;
      }
    }
    return false;
  }

  /** 目录是否落在本作用域内（{@code fs} 命名空间的取法：候选按词法归一，不做 realpath）。 */
  public boolean allowsDir(Path candidate) {
    Objects.requireNonNull(candidate, "candidate");
    return allows(candidate.toAbsolutePath().normalize().toString());
  }

  /**
   * 交集（containment 求交）：结果同时满足两边。任何一方"不限" ⇒ 另一方说了算。
   *
   * <p>前缀都是"容器"，两个容器要么互不相交、要么一个含另一个 ⇒ 交集就是留下嵌套对里窄的那个， 不需要区间代数。
   */
  public ResourceScope narrowTo(ResourceScope other) {
    Objects.requireNonNull(other, "other");
    if (unrestricted) {
      return other;
    }
    if (other.unrestricted) {
      return this;
    }
    Set<String> intersection = new LinkedHashSet<>();
    for (String mine : prefixes) {
      for (String theirs : other.prefixes) {
        if (contains(mine, theirs)) {
          intersection.add(theirs); // 对方更窄
        } else if (contains(theirs, mine)) {
          intersection.add(mine); // 本侧更窄
        }
      }
    }
    return new ResourceScope(false, intersection);
  }

  /** 单调性（{@code this ⊇ child}）：不限涵盖一切；受限方要求 child 的每个前缀都落在本侧某个前缀之内。 */
  public boolean covers(ResourceScope child) {
    Objects.requireNonNull(child, "child");
    if (unrestricted) {
      return true;
    }
    if (child.unrestricted) {
      return false;
    }
    for (String theirs : child.prefixes) {
      boolean covered = false;
      for (String mine : prefixes) {
        if (contains(mine, theirs)) {
          covered = true;
          break;
        }
      }
      if (!covered) {
        return false;
      }
    }
    return true;
  }

  /** 前缀列表（人读/落事件用；不可变）。 */
  public List<String> prefixList() {
    return List.copyOf(prefixes);
  }

  /** 目录列表（{@code fs} 命名空间用；不限时为空表——"不限"没有"根列表"可列）。 */
  public List<Path> dirList() {
    List<Path> dirs = new ArrayList<>();
    for (String prefix : prefixes) {
      dirs.add(Path.of(prefix));
    }
    return List.copyOf(dirs);
  }

  /** 摘要（落事件面用）：不限写成 {@code *}，受限写前缀列表——这里落的是<b>允许根</b>（审计要辨"谁在哪个 scope 下跑的"），不是被访问的具体文件。 */
  public String summary() {
    return unrestricted ? "*" : String.join(",", prefixes);
  }

  /** 段边界包含：{@code c == p} 或 {@code c} 在 {@code p + "/"} 之下；根 {@code /} 特判。 */
  static boolean contains(String prefix, String candidate) {
    if (SEPARATOR.equals(prefix)) {
      return candidate.startsWith(SEPARATOR);
    }
    return candidate.equals(prefix) || candidate.startsWith(prefix + SEPARATOR);
  }

  /** 前缀归一：折叠重复分隔符、去尾分隔符、拒绝空/相对段。 */
  private static String normalizePrefix(String raw) {
    String trimmed = raw == null ? "" : raw.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("作用域前缀不能为空");
    }
    String collapsed = collapse(trimmed);
    if (collapsed.length() > 1 && collapsed.endsWith(SEPARATOR)) {
      collapsed = collapsed.substring(0, collapsed.length() - 1);
    }
    for (String segment : collapsed.split(SEPARATOR, -1)) {
      if (".".equals(segment) || "..".equals(segment)) {
        throw new IllegalArgumentException("作用域前缀不能含 '.' 或 '..' 段（前缀是容器，相对段说明写的人搞混了两个概念）: " + raw);
      }
    }
    return collapsed;
  }

  /**
   * 候选归一：与前缀同口径（重复分隔符折叠、去尾分隔符），<b>再</b>按 {@link Path} 的语义词法解析 {@code .}/{@code ..} 段； 但**不**做
   * realpath（软链不在承诺范围内）。
   *
   * <p><b>包级可见</b>：{@link ResourceId}（S5-C）复用同一段归一口径——资源标识与作用域若各写一套归一，两边对"同一个路径"的 判断会分叉。
   *
   * <p><b>为什么候选解析 {@code ..} 而前缀拒绝 {@code ..}</b>：前缀是<b>容器</b>（"我允许哪些地方"，出现相对段说明写的人把两个概念 搞混了 ⇒
   * 装载期响亮失败）；候选是<b>指向</b>（"我要碰哪个地方"，相对段的含义明确 ⇒ 按 {@code Path.normalize()} 解析， 于是 {@code
   * /srv/work/../etc} 判成 {@code /srv/etc} 而不是"在 /srv/work 之内"）。
   */
  static String normalizeCandidate(String raw) {
    String trimmed = raw == null ? "" : raw.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    String collapsed = collapse(trimmed);
    if (collapsed.length() > 1 && collapsed.endsWith(SEPARATOR)) {
      collapsed = collapsed.substring(0, collapsed.length() - 1);
    }
    return resolveRelativeSegments(collapsed);
  }

  /**
   * 解析 {@code .}/{@code ..} 段（{@link Path#normalize()} 的语义词法，纯字符串实现——候选里含 NUL
   * 之类的怪字符不该抛异常，只该"不匹配任何前缀"）。
   */
  private static String resolveRelativeSegments(String value) {
    boolean absolute = value.startsWith(SEPARATOR);
    List<String> stack = new ArrayList<>();
    for (String segment : value.split(SEPARATOR, -1)) {
      if (segment.isEmpty() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        if (!stack.isEmpty() && !"..".equals(stack.get(stack.size() - 1))) {
          stack.remove(stack.size() - 1); // 退一层
        } else if (!absolute) {
          stack.add(".."); // 相对路径顶到头的 '..' 留在原处（与 Path.normalize 一致）
        }
        // 绝对路径上退到根再退：原地消失（/.. = /）
        continue;
      }
      stack.add(segment);
    }
    return (absolute ? SEPARATOR : "") + String.join(SEPARATOR, stack);
  }

  private static String collapse(String value) {
    StringBuilder sb = new StringBuilder(value.length());
    boolean lastWasSeparator = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '/') {
        if (!lastWasSeparator) {
          sb.append(c);
        }
        lastWasSeparator = true;
      } else {
        sb.append(c);
        lastWasSeparator = false;
      }
    }
    return sb.toString();
  }

  /** 目录 → 前缀：绝对化 + 词法归一；已存在的目录再取 realpath（拿不到就响亮失败——那是配置/环境问题）。 */
  private static String dirPrefix(Path root) {
    Objects.requireNonNull(root, "root");
    Path lexical = root.toAbsolutePath().normalize();
    Path effective = lexical;
    if (Files.exists(lexical)) {
      try {
        effective = lexical.toRealPath();
      } catch (IOException e) {
        throw new UncheckedIOException("工作目录根的 realpath 取不到: " + lexical, e);
      }
    }
    return normalizePrefix(effective.toString());
  }
}
