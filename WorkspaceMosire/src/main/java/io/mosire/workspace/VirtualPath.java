package io.mosire.workspace;

import java.util.Objects;

/**
 * 虚拟路径的解析结果 {@code (挂载名, 相对路径)}（设计 §4.2、§4.5 第①步）。
 *
 * <p><b>拼法由标准定死（G3）</b>：{@code <挂载名>/<相对路径>}。规则逐条写死，违反即 {@code INVALID_ARGUMENTS}：
 *
 * <ul>
 *   <li>不得为空、不得以 {@code /} 开头（挂载名就是根；带前导斜杠会被误读成物理路径）；
 *   <li>第一段必须是合法挂载名（{@link MountName#PATTERN}）——拼错的名字是<b>入参非法</b>，不是"挂载不存在"；
 *   <li>其余段不得为空（重复 {@code /}、尾随 {@code /} 都算出界写法）、不得是 {@code .} 或 {@code ..}；
 *   <li>只有挂载名、没有相对部分 ⇒ 指向挂载根（合法）。
 * </ul>
 *
 * <p><b>为什么尾随 {@code /} 也拒</b>："唯一拼法"是 G3 的要点：{@code a/b} 与 {@code a/b/} 若都合法，投影输出、事件留痕
 * 与用例断言就会各写一种，而"同一个位置"看起来像两个。首尾空白会被剥掉（模型常带），这不会造成歧义。
 */
record VirtualPath(MountName mount, String relative) {

  private static final String SEPARATOR = "/";

  VirtualPath {
    Objects.requireNonNull(mount, "mount");
    relative = relative == null ? "" : relative;
  }

  /**
   * 解析一条虚拟路径（{@code resolve} 与 {@code list} 共用同一拼法）。
   *
   * @throws WorkspaceException {@code INVALID_ARGUMENTS}（形态违反上述任一条）
   */
  static VirtualPath parse(String virtualPath) {
    String raw = virtualPath == null ? "" : virtualPath.strip();
    if (raw.isEmpty()) {
      throw WorkspaceException.invalid("虚拟路径不能为空（形态：<挂载名>/<相对路径>）");
    }
    if (raw.startsWith(SEPARATOR)) {
      throw WorkspaceException.invalid("虚拟路径不得以 '/' 开头（挂载名就是根，它不是物理路径）: " + raw);
    }
    String[] segments = raw.split(SEPARATOR, -1);
    MountName mount;
    try {
      mount = new MountName(segments[0]);
    } catch (IllegalArgumentException e) {
      throw WorkspaceException.invalid(
          "虚拟路径的第一段不是合法挂载名（" + MountName.PATTERN + "）: '" + segments[0] + "'");
    }
    StringBuilder relative = new StringBuilder();
    for (int i = 1; i < segments.length; i++) {
      String segment = segments[i];
      if (segment.isEmpty()) {
        throw WorkspaceException.invalid("虚拟路径不得含空段（重复 '/' 或尾随 '/'）: " + raw);
      }
      if (".".equals(segment) || "..".equals(segment)) {
        throw WorkspaceException.invalid("虚拟路径不得含 '.' 或 '..' 段: " + raw);
      }
      if (relative.length() > 0) {
        relative.append(SEPARATOR);
      }
      relative.append(segment);
    }
    return new VirtualPath(mount, relative.toString());
  }

  /**
   * 渲染回虚拟路径文本（面向模型/事件用；<b>只含虚拟路径</b>，G4/A7）：挂载根就是挂载名本身，不带尾随分隔符。
   *
   * <p>渲染结果与"唯一拼法"一致：解析后回渲染得到同形字符串，因此消息、事件与断言三处写的是同一个东西。
   */
  String label() {
    return relative.isEmpty() ? mount.value() : mount.value() + SEPARATOR + relative;
  }

  /** 校验 resolver 投影出的条目是不是"挂载内的相对路径"（执行侧扩展点的输出也要过拼法守卫，G1/G3）。 */
  static String requireRelativeEntry(String entry) {
    String value = entry == null ? "" : entry.strip();
    if (value.isEmpty()) {
      throw WorkspaceException.denied("映射策略投影出了空条目（fail-closed）");
    }
    if (value.startsWith(SEPARATOR)) {
      throw WorkspaceException.denied("映射策略投影出了绝对路径条目（fail-closed）");
    }
    for (String segment : value.split(SEPARATOR, -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        throw WorkspaceException.denied("映射策略投影出的条目不是挂载内的相对路径（fail-closed）");
      }
    }
    return value;
  }
}
