package io.mosire.docs;

import java.util.Objects;

/**
 * {@code LOG} 纪律的条目身份：{@code <地址>#<序号>}（设计 §5.2/B1）。
 *
 * <p><b>为什么身份由 Docs 派生而不是调用方给</b>：调用方给地址（不透明），Docs 给序号（= 该地址的日志行序）。若让调用方连序号一起给， "同一地址的第 3
 * 条"就有了两个可能不一致的来源；派生则让"重放同一份记录 ⇒ 落在同一个 id"成为结构性结论。
 *
 * <p><b>序号按数值比较，不按字符串</b>（设计 §5.2 显式登记的差异）：字符串序在 {@code #10} 与 {@code #2} 处会错。
 *
 * <p><b>解析</b>：{@link #parse(String)} 只接受本类 {@link #toString()} 的产物（唯一的回解写法），拼错的名字是<b>入参非法</b>，
 * 不是"文档不存在"——两者混起来会让工具面变成"id 拼写探测器"。
 *
 * @param address 调用方定义的地址（非空、无空白、无 {@code #}，见 {@link DocAddress}）
 * @param ordinal 该地址内的序号（从 0 起，由存储按行序维护）
 */
public record DocEntryId(String address, long ordinal) implements Comparable<DocEntryId> {

  public DocEntryId {
    address = DocAddress.require(address);
    if (ordinal < 0) {
      throw DocException.invalid("条目序号不能为负: " + ordinal);
    }
  }

  /** 语法糖：{@code of(地址, 序号)}。 */
  public static DocEntryId of(String address, long ordinal) {
    return new DocEntryId(address, ordinal);
  }

  /**
   * 解析 {@code <地址>#<序号>}。
   *
   * @throws DocException {@code INVALID_ARGUMENTS}（缺分隔符、序号非数字/为负、地址形态非法）
   */
  public static DocEntryId parse(String text) {
    String value = text == null ? "" : text.strip();
    if (value.isEmpty()) {
      throw DocException.invalid("条目 id 不能为空（形态：<地址>#<序号>）");
    }
    int index = value.lastIndexOf(DocAddress.ID_SEPARATOR);
    if (index <= 0 || index == value.length() - 1) {
      throw DocException.invalid("条目 id 形态非法（应为 <地址>#<序号>）: '" + value + "'");
    }
    String address = value.substring(0, index);
    String ordinalText = value.substring(index + 1);
    long ordinal;
    try {
      ordinal = Long.parseLong(ordinalText);
    } catch (NumberFormatException e) {
      throw DocException.invalid("条目 id 的序号不是数字: '" + value + "'");
    }
    return new DocEntryId(address, ordinal);
  }

  /** 有序列：先按地址（字典序），再按序号<b>数值</b>——同 tick 的稳定排序键（设计 §5.5）。 */
  @Override
  public int compareTo(DocEntryId other) {
    Objects.requireNonNull(other, "other");
    int byAddress = address.compareTo(other.address);
    return byAddress != 0 ? byAddress : Long.compare(ordinal, other.ordinal);
  }

  @Override
  public String toString() {
    return address + DocAddress.ID_SEPARATOR + ordinal;
  }
}
