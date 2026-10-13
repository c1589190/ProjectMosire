package io.mosire.agentlib.tool;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>宿主注入的“沙箱遮蔽面”</b>：一份“在 OS 沙箱（L3）里必须不可见或只读可见”的宿主路径清单，以及它相对调用者资源可达面的判定。
 *
 * <p><b>为什么需要它</b>：资源面的可见/可编辑收窄只挡得住声明了该资源的工具自己——普通 shell/文件工具（bash 及其 L3 沙箱）如果不看这份面， 一个对某命名空间不可见的
 * Agent 仍能 {@code cat <遮蔽根>/…} 把边界绕过去（技能树是第一个实例）。工具面是硬边界，但边界要成立还需要文件工具一侧的配合；
 * 本类是这份配合的<b>唯一数据入口</b>（判定规则只有一处实现，见 {@link #fence}）。
 *
 * <p><b>数据从哪来</b>：宿主（装配层）把“必须遮蔽的宿主路径”经 {@link ToolContext#config()} 下发——与 {@code
 * read_agent_context} 的三键同一条运行时缝（工具只读、不能反向索取）。清单<b>缺省为空</b>：没接线遮蔽面的部署（既有装配、离线单测、外部 MCP 面）
 * 逐字保持本能力引入前的行为，不产生任何遮蔽。
 *
 * <p><b>条目必须带命名空间（本类命名空间无关）</b>：清单元素形如 {@code {namespace: String, path: Path}}——命名空间由
 * <b>写清单的装配层</b>声明，判定只做 {@code declaredScope(条目.namespace)}。收编前本类硬编码读 {@code skills}（业务命名空间住在通用层， 见
 * {@code 设计-工作区标准与Docs系统.md} §2.1），那正是“第二处业务语义”；现在<b>不给缺省、不猜</b>：旧的无命名空间条目（裸字符串 / 裸 {@link Path}）一律
 * {@link IllegalArgumentException} 响亮拒绝——猜一个缺省命名空间等于把刚删掉的硬编码换个地方写回来。
 *
 * <p><b>判定表（逐格写死）</b>：
 *
 * <table>
 *   <caption>遮蔽判定</caption>
 *   <tr><th>条目声明的命名空间在调用者权限集里</th><th>结果</th></tr>
 *   <tr><td>未表态（本层没写过这一项）</td><td>{@code masked}——声明该资源的工具缺省是 DENY，未表态 = 什么都看不见，整棵树必须遮蔽</td></tr>
 *   <tr><td>显式 {@code none()} / 前缀盖不住整条路径</td><td>{@code masked}——可达面不覆盖这条路径，命令不得看见它</td></tr>
 *   <tr><td>{@code unlimited()} 或前缀覆盖整条路径</td><td>{@code readOnly}——可见者只读：可读，<b>不得</b>借文件工具改写（写入必须走资源工具自己的校验）</td></tr>
 * </table>
 *
 * <p><b>诚实边界</b>：本类只<b>算</b>这份面，落不落地由执行侧（{@code io.mosire.bash.BashSandbox}）负责；没有 L3 沙箱的执行路径 （无围栏的
 * bash、其它文件工具）拿不到这份保证——那是装配约束，不是本类能单方面补上的（见实现说明的边界一节）。
 */
public final class SandboxMask {

  /**
   * {@link ToolContext#config()} 里的键：宿主声明的遮蔽面（{@code List<Map<String,Object>>}，每个条目见 {@link
   * #NAMESPACE_KEY} / {@link #PATH_KEY}）。
   *
   * <p>缺失 / {@code null} / 空表 = 没有遮蔽面（既有行为逐字不变）。条目非法（不是条目 Map、缺命名空间、空白、含 NUL 等无法成为路径）⇒ {@link
   * IllegalArgumentException}：这是宿主装配错误，静默跳过会让“配了遮蔽”与“没配”长得一样。
   */
  public static final String CONFIG_KEY = "sandbox.maskedPaths";

  /** 遮蔽面条目的命名空间键：值必须是<b>非空字符串</b>，即调用者权限集里 {@code resourceScopes} 的键。 */
  public static final String NAMESPACE_KEY = "namespace";

  /** 遮蔽面条目的路径键：值为 {@link Path} 或路径文本（非空白），归一后必须是宿主绝对路径。 */
  public static final String PATH_KEY = "path";

  private SandboxMask() {}

  /**
   * 一条遮蔽面条目（读清单时解析出的形状）：命名空间 + 归一化后的宿主绝对路径。
   *
   * <p>解析与判定分开：{@link #entriesOf} 负责“形状对不对、是不是绝对路径”，{@link #fence} 只负责“可见面覆盖不覆盖”。
   */
  public record Entry(String namespace, Path path) {

    public Entry {
      Objects.requireNonNull(namespace, "namespace");
      Objects.requireNonNull(path, "path");
    }
  }

  /**
   * 一次调用的围栏：要遮蔽的路径、要只读暴露的路径、以及“遮蔽需要 OS 沙箱”这件事。
   *
   * @param masked 必须对命令不可见的宿主路径（沙箱内 bind 一个空目录盖住它）
   * @param readOnly 调用者可达面覆盖、因而可读但<b>不得写</b>的宿主路径（沙箱内按只读 bind）
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
   * 读宿主下发的遮蔽面清单（命名空间无关；条目形状见类 javadoc）。
   *
   * @param config 一次工具调用的配置缝（{@link ToolContext#config()}，恒非 null）
   * @throws IllegalArgumentException 条目不是 {@code {namespace, path}} 形状（裸字符串/裸路径、缺键、未知键、空白、无法成为路径）——
   *     宿主装配错误，响亮失败。**旧裸路径条目不再兼容**：不猜缺省命名空间。
   */
  public static List<Entry> entriesOf(Map<String, Object> config) {
    if (config == null) {
      return List.of();
    }
    Object raw = config.get(CONFIG_KEY);
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> items)) {
      throw new IllegalArgumentException(
          "配置 " + CONFIG_KEY + " 必须是条目数组（实际: " + raw.getClass().getSimpleName() + "）");
    }
    List<Entry> entries = new ArrayList<>(items.size());
    for (Object item : items) {
      entries.add(entryOf(item));
    }
    return List.copyOf(entries);
  }

  /**
   * 按“调用者权限集 × 宿主遮蔽面”算本次调用的围栏（判定表见类 javadoc）。
   *
   * <p>同一物理路径被多条条目声明（多个命名空间）时取<b>逐条目求与</b>：只要有一条判成不可见就遮蔽。这不是新语义——执行侧本来就先只读
   * bind、再用空目录遮蔽，遮蔽压过只读；在这里显式求与，只是把那份真实结果写进 {@link Fence}，不让桶的顺序替判定说话。
   *
   * @param permissions 调用者权限集（{@code null} ⇒ 按“什么都看不见”处置：全部遮蔽，fail-closed）
   * @param config 一次工具调用的配置缝
   */
  public static Fence fence(AgentPermissionSet permissions, Map<String, Object> config) {
    List<Entry> entries = entriesOf(config);
    if (entries.isEmpty()) {
      return Fence.none();
    }
    Map<Path, Boolean> verdicts = new LinkedHashMap<>();
    for (Entry entry : entries) {
      verdicts.merge(
          entry.path(), visible(permissions, entry.namespace(), entry.path()), (a, b) -> a && b);
    }
    List<Path> masked = new ArrayList<>();
    List<Path> readOnly = new ArrayList<>();
    for (Map.Entry<Path, Boolean> verdict : verdicts.entrySet()) {
      if (verdict.getValue()) {
        readOnly.add(verdict.getKey());
      } else {
        masked.add(verdict.getKey());
      }
    }
    return new Fence(masked, readOnly, !masked.isEmpty());
  }

  /**
   * 调用者在 {@code namespace} 上的可达面是否<b>覆盖整条路径</b>（{@link ResourceScope#covers} 的“涵盖”语义，不是“命中其中某处”）。
   *
   * <p>为什么必须是“覆盖整条”而不是“与它有交集”：可达面前缀若只是这条路径的一个子目录，那么这条路径对其余部分仍然是不可见的—— 只读暴露整棵树会把不可见的那部分一起漏出去。
   *
   * <p><b>未表态 ⇒ {@code false}</b>：命名空间的声明工具缺省是 DENY（“没配资源”读作“够不着”），不是“不限”。
   *
   * @param permissions 调用者权限集（{@code null} ⇒ {@code false}）
   * @param namespace 条目声明的命名空间（{@code resourceScopes} 的键）
   * @param path 宿主绝对路径
   */
  public static boolean visible(AgentPermissionSet permissions, String namespace, Path path) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(path, "path");
    if (permissions == null) {
      return false;
    }
    ResourceScope scope = permissions.resourceScopes().declaredScope(namespace);
    if (scope == null) {
      // 未表态：声明该资源的工具缺省 DENY ⇒ 看不见（不是“不限”）
      return false;
    }
    return scope.covers(ResourceScope.ofDir(path));
  }

  /** 解析一条清单条目（形状校验 + 归一化），见 {@link #entriesOf}。 */
  private static Entry entryOf(Object item) {
    if (!(item instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(
          "配置 "
              + CONFIG_KEY
              + " 的条目必须带命名空间（形如 {"
              + NAMESPACE_KEY
              + ": String, "
              + PATH_KEY
              + ": Path}），旧的无命名空间条目不再兼容（实际: "
              + item
              + "）");
    }
    for (Object key : map.keySet()) {
      if (!NAMESPACE_KEY.equals(key) && !PATH_KEY.equals(key)) {
        throw new IllegalArgumentException(
            "配置 " + CONFIG_KEY + " 的条目含未知键 " + key + "（只认 " + NAMESPACE_KEY + "/" + PATH_KEY + "）");
      }
    }
    Object namespaceRaw = map.get(NAMESPACE_KEY);
    if (!(namespaceRaw instanceof String namespace) || namespace.isBlank()) {
      throw new IllegalArgumentException(
          "配置 " + CONFIG_KEY + " 的条目缺少非空 " + NAMESPACE_KEY + "（命名空间不许缺省，缺省就是猜）: " + map);
    }
    Object pathRaw = map.get(PATH_KEY);
    if (pathRaw == null) {
      throw new IllegalArgumentException("配置 " + CONFIG_KEY + " 的条目缺少 " + PATH_KEY + ": " + map);
    }
    String text;
    if (pathRaw instanceof Path path) {
      text = path.toString();
    } else if (pathRaw instanceof String string) {
      text = string;
    } else {
      throw new IllegalArgumentException(
          "配置 " + CONFIG_KEY + " 的 " + PATH_KEY + " 必须是路径文本（实际: " + pathRaw + "）");
    }
    if (text.isBlank()) {
      throw new IllegalArgumentException("配置 " + CONFIG_KEY + " 的 " + PATH_KEY + " 不能为空白");
    }
    try {
      return new Entry(namespace.strip(), Path.of(text).toAbsolutePath().normalize());
    } catch (InvalidPathException e) {
      throw new IllegalArgumentException(
          "配置 " + CONFIG_KEY + " 的 " + PATH_KEY + " 不是合法路径: " + text, e);
    }
  }
}
