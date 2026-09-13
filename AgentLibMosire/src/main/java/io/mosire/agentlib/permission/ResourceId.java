package io.mosire.agentlib.permission;

/**
 * 一个资源标识：{@code <namespace>:<path>}（设计 §2.5 的统一抽象，如 {@code fs:/srv/work}、 {@code
 * plugin:foo/data:tenant/9}）。
 *
 * <p><b>为什么命名空间与路径分开存而不是一个字符串</b>：判定要分两步走——先按命名空间找到"谁的表态管这一维"（工具缺省策略、调用者可达面），
 * 再按路径比可达面。合成一个字符串就得在每个判定点重新切开它，而切开的位置恰恰是命名空间自己可能含 {@code :} 的那种输入 （见 {@link #fullId()} 的警告）。
 *
 * <p><b>路径不做归一</b>：{@code //a//b} 与 {@code /a/b} 在这里是两个取值；归一（折叠分隔符、解析 {@code .}/{@code ..} 段）归
 * {@link ResourceScope#allows} 管——判定只有一处口径，不在数据类里再写一套。
 *
 * <p><b>诚实边界</b>：本类型判的是"名字"，不是"可达性"（软链、绑定挂载不在承诺范围内）——同 {@link ResourceScope} 的分级。
 */
public record ResourceId(String namespace, String path) {

  public ResourceId {
    // 命名空间与 ResourceScopeMap 同口径：去首尾空白后必须非空（两处口径必须一致，否则 " fs " 与 "fs" 会各自成立）
    namespace = namespace == null ? "" : namespace.trim();
    if (namespace.isEmpty()) {
      throw new IllegalArgumentException("资源命名空间不能为空（形如 fs / plugin:foo/data）");
    }
    path = path == null ? "" : path.trim();
    if (path.isEmpty()) {
      throw new IllegalArgumentException("资源路径不能为空: namespace=" + namespace);
    }
  }

  /** 按命名空间 + 路径建标识（如 {@code of("plugin:foo/data", "tenant/9")}）。 */
  public static ResourceId of(String namespace, String path) {
    return new ResourceId(namespace, path);
  }

  /**
   * 展示用全名 {@code <namespace>:<path>}（落日志、写拒因给人/模型看）。
   *
   * <p><b>不是可回解的编码</b>：命名空间本身可以含 {@code :}（设计 §2.5 的例子 {@code plugin:foo/data}）⇒ 靠 {@code split}
   * 反解析会得到错误的切分。要拆开请用 {@link #namespace()} / {@link #path()}；本方法只保证同一对 (namespace, path) 得到同一个串。
   */
  public String fullId() {
    return namespace + ":" + path;
  }
}
