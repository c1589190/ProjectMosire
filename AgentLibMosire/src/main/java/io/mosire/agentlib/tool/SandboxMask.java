package io.mosire.agentlib.tool;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>宿主注入的“沙箱遮蔽面”</b>：一份“在 OS 沙箱（L3）里必须不可见或只读可见”的宿主路径清单，以及它相对调用者技能可见面的判定。
 *
 * <p><b>为什么需要它</b>：技能工具面上的可见/可编辑收窄只挡得住技能工具自己——普通 shell/文件工具（bash 及其 L3 沙箱）如果不看这份面， 一个技能不可见的 Agent
 * 仍能 {@code cat <技能根>/…/SKILL.md} 把边界绕过去。技能工具是硬边界，但边界要成立还需要文件工具一侧的配合；
 * 本类是这份配合的<b>唯一数据入口</b>（判定规则只有一处实现，见 {@link #fence}）。
 *
 * <p><b>数据从哪来</b>：宿主（装配层）把“技能根”这类宿主路径经 {@link ToolContext#config()} 下发——与 {@code
 * read_agent_context} 的三键同一条运行时缝（工具只读、不能反向索取）。清单<b>缺省为空</b>：没接线技能面的部署（既有装配、离线单测、外部 MCP 面）
 * 逐字保持本能力引入前的行为，不产生任何遮蔽。
 *
 * <p><b>判定表（逐格写死）</b>：
 *
 * <table>
 *   <caption>遮蔽判定</caption>
 *   <tr><th>调用者 {@code skills} 命名空间</th><th>结果</th></tr>
 *   <tr><td>未表态（本层没写过这一项）</td><td>{@code masked}——工具缺省是 DENY，未表态 = 一个技能都看不见，整棵树必须遮蔽</td></tr>
 *   <tr><td>显式 {@code none()} / 前缀盖不住整个根</td><td>{@code masked}——可见面不覆盖这条路径，命令不得看见它</td></tr>
 *   <tr><td>{@code unlimited()} 或前缀覆盖整条路径</td><td>{@code readOnly}——可见者对技能树只读：可读正文/资源，<b>不得</b>借文件工具改写（写入必须走技能工具的 frontmatter 校验）</td></tr>
 * </table>
 *
 * <p><b>诚实边界</b>：本类只<b>算</b>这份面，落不落地由执行侧（{@code io.mosire.bash.BashSandbox}）负责；没有 L3 沙箱的执行路径 （无围栏的
 * bash、其它文件工具）拿不到这份保证——那是装配约束，不是本类能单方面补上的（见实现说明的边界一节）。
 */
public final class SandboxMask {

  /**
   * {@link ToolContext#config()} 里的键：宿主声明的遮蔽面（{@code List<String>} 或 {@code List<Path>}，宿主绝对路径）。
   *
   * <p>缺失 / {@code null} / 空表 = 没有遮蔽面（既有行为逐字不变）。元素非法（空白、含 NUL 等无法成为路径）⇒ {@link
   * IllegalArgumentException}：这是宿主装配错误，静默跳过会让“配了遮蔽”与“没配”长得一样。
   */
  public static final String CONFIG_KEY = "sandbox.maskedPaths";

  private SandboxMask() {}

  /**
   * 一次调用的技能树围栏：要遮蔽的路径、要只读暴露的路径、以及“遮蔽需要 OS 沙箱”这件事。
   *
   * @param masked 必须对命令不可见的宿主路径（沙箱内 bind 一个空目录盖住它）
   * @param readOnly 调用者可见面覆盖、因而可读但<b>不得写</b>的宿主路径（沙箱内按只读 bind）
   * @param sandboxRequired {@code masked} 非空 ⇒ 只有沙箱执行才谈得上“遮蔽”；调用方在拿不到沙箱（无围栏执行）时必须
   *     <b>响亮拒绝</b>而不是静默放行（否则这条边界在那一刻不存在）
   */
  public record Fence(List<Path> masked, List<Path> readOnly, boolean sandboxRequired) {

    public Fence {
      // 内联 List.copyOf：让 SpotBugs 看得见不可变来源（经 helper 间接赋值会被判 EI_EXPOSE_REP——本仓既有写法）
      masked = List.copyOf(normalizePaths(masked));
      readOnly = List.copyOf(normalizePaths(readOnly));
    }

    /** 没有遮蔽面（清单为空）：本能力未接线时调用方走的就是这一档。 */
    public static Fence none() {
      return new Fence(List.of(), List.of(), false);
    }

    /** 本围栏是否什么都不做（不遮蔽、不只读、也不要求沙箱）。 */
    public boolean isEmpty() {
      return masked.isEmpty() && readOnly.isEmpty();
    }

    /** 归一（绝对化 + 词法）：返回可变表，由调用点内联 {@code List.copyOf} 落成不可变快照。 */
    private static List<Path> normalizePaths(List<Path> paths) {
      if (paths == null || paths.isEmpty()) {
        return List.of();
      }
      List<Path> copy = new ArrayList<>(paths.size());
      for (Path path : paths) {
        copy.add(Objects.requireNonNull(path, "遮蔽面路径").toAbsolutePath().normalize());
      }
      return copy;
    }
  }

  /**
   * 读宿主下发的遮蔽面清单。
   *
   * @param config 一次工具调用的配置缝（{@link ToolContext#config()}，恒非 null）
   * @throws IllegalArgumentException 元素不是字符串/路径、空白、或无法成为路径（宿主装配错误，响亮失败）
   */
  public static List<Path> pathsOf(Map<String, Object> config) {
    if (config == null) {
      return List.of();
    }
    Object raw = config.get(CONFIG_KEY);
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> items)) {
      throw new IllegalArgumentException(
          "配置 " + CONFIG_KEY + " 必须是路径数组（实际: " + raw.getClass().getSimpleName() + "）");
    }
    List<Path> paths = new ArrayList<>(items.size());
    for (Object item : items) {
      String text;
      if (item instanceof Path path) {
        text = path.toString();
      } else if (item instanceof String string) {
        text = string;
      } else {
        throw new IllegalArgumentException("配置 " + CONFIG_KEY + " 的元素必须是路径文本（实际: " + item + "）");
      }
      if (text.isBlank()) {
        throw new IllegalArgumentException("配置 " + CONFIG_KEY + " 的元素不能为空白");
      }
      try {
        paths.add(Path.of(text).toAbsolutePath().normalize());
      } catch (InvalidPathException e) {
        throw new IllegalArgumentException("配置 " + CONFIG_KEY + " 的元素不是合法路径: " + text, e);
      }
    }
    return List.copyOf(paths);
  }

  /**
   * 按“调用者权限集 × 宿主遮蔽面”算本次调用的技能树围栏（判定表见类 javadoc）。
   *
   * @param permissions 调用者权限集（{@code null} ⇒ 按“什么都看不见”处置：全部遮蔽，fail-closed）
   * @param config 一次工具调用的配置缝
   */
  public static Fence fence(AgentPermissionSet permissions, Map<String, Object> config) {
    List<Path> paths = pathsOf(config);
    if (paths.isEmpty()) {
      return Fence.none();
    }
    List<Path> masked = new ArrayList<>();
    List<Path> readOnly = new ArrayList<>();
    for (Path path : paths) {
      if (skillsVisible(permissions, path)) {
        readOnly.add(path);
      } else {
        masked.add(path);
      }
    }
    return new Fence(masked, readOnly, !masked.isEmpty());
  }

  /**
   * 调用者的技能可见面是否<b>覆盖整条路径</b>（{@link ResourceScope#covers} 的“涵盖”语义，不是“命中其中某处”）。
   *
   * <p>为什么必须是“覆盖整条”而不是“与它有交集”：可见面前缀若只是技能根的一个子目录，那么根本身对其余部分仍然是不可见的—— 只读暴露整棵树会把不可见的那部分一起漏出去。
   */
  public static boolean skillsVisible(AgentPermissionSet permissions, Path path) {
    if (permissions == null) {
      return false;
    }
    ResourceScope visible = permissions.resourceScopes().declaredScope(ResourceScopeMap.SKILLS);
    if (visible == null) {
      // 未表态：工具缺省 DENY ⇒ 一个技能都看不见（不是“不限”）
      return false;
    }
    return visible.covers(ResourceScope.ofDir(Objects.requireNonNull(path, "path")));
  }
}
