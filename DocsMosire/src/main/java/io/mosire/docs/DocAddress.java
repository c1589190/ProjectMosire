package io.mosire.docs;

/**
 * {@code LOG} 纪律的<b>地址</b>合法性判据（设计 §5.2/B1/B6）。
 *
 * <p><b>为什么地址归调用方、合法性归 Docs</b>：地址是不透明字符串（Docs 不解析它的含义——隔壁的 {@code sd:...} 与本文的 {@code world/...}
 * 都只是字符串）。但"什么算一条合法地址"必须是<b>一处</b>知识：写入侧不校验，非法地址就会在读侧表现为"文档静默消失"（正是设计 §5.2 要消灭的失败形态）。
 *
 * <p><b>规则逐条写死</b>（违反即 {@link DocException#INVALID_ARGUMENTS}）：
 *
 * <ul>
 *   <li>非空白、去首尾空白后非空；
 *   <li>不得含<b>空白字符</b>（地址会进 {@code id} 文本、日志文件名与工具参数，含空白会让人看不出两个地址的差别）；
 *   <li>不得含分隔符 {@code #}——它是 {@code <地址>#<序号>} 的分隔符，含它会让 id 不可回解；
 *   <li>不得含 {@code //}（空段会与"层级路径"的直觉打架，也难在投影里对账）；
 *   <li>长度 ≤ {@link #MAX_LENGTH}：地址会变成日志文件名（编码后），过长会在文件系统层以 {@code ENAMETOOLONG} 现身——
 *       那属于"写入侧拒"的范畴，不是读的时候才发现。
 * </ul>
 *
 * <p><b>文件名不是身份</b>（B5）：编码后的地址只作索引，读回时以记录内的 {@code address} 为准（见 {@link DocLogs}）。
 */
public final class DocAddress {

  /** 地址长度上限（编码后作为文件名，须给前缀/后缀留余量）。 */
  public static final int MAX_LENGTH = 200;

  /** id 的分隔符（{@code <地址>#<序号>}）。 */
  public static final char ID_SEPARATOR = '#';

  private DocAddress() {
    throw new AssertionError("No instances");
  }

  /**
   * 校验并归一一条地址（去首尾空白）。
   *
   * @return 归一后的地址
   * @throws DocException {@code INVALID_ARGUMENTS}（形态违反上述任一条）
   */
  public static String require(String address) {
    String value = address == null ? "" : address.strip();
    if (value.isEmpty()) {
      throw DocException.invalid("文档地址不能为空（地址由调用方定义，但必须是可辨别的非空字符串）");
    }
    if (value.chars().anyMatch(Character::isWhitespace)) {
      throw DocException.invalid("文档地址不得含空白字符: '" + value + "'");
    }
    if (value.indexOf(ID_SEPARATOR) >= 0) {
      throw DocException.invalid(
          "文档地址不得含 '" + ID_SEPARATOR + "'（它是 <地址>#<序号> 的分隔符）: '" + value + "'");
    }
    if (value.contains("//")) {
      throw DocException.invalid("文档地址不得含 '//'（空段）: '" + value + "'");
    }
    if (value.length() > MAX_LENGTH) {
      throw DocException.invalid("文档地址过长（上限 " + MAX_LENGTH + " 字符）: " + value.length());
    }
    return value;
  }
}
