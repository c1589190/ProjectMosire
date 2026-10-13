package io.mosire.docs;

import java.util.Objects;

/**
 * {@code FILE} 纪律的编辑寻址（设计 §5.5）：<b>行号 / 行区间 / 唯一精确锚点</b>——三种，不多不少。
 *
 * <p><b>为什么不做"正则锚点"</b>：正则的多义与转义是 token 与缺陷的双重来源（设计 §5.6 对 {@code sed} 的评价同源）；"精确锚点"要求整行<b>逐字</b>
 * 相等，命中 0 处或 &gt; 1 处一律拒——不唯一就是不确定，不确定就别动盘上的字（B8/B10）。
 *
 * <p><b>为什么行号从 1 起</b>：与 Linux 的行寻址习惯一致（{@code sed -n '10,40p'}、编辑器行号），也让工具面回显的 {@code
 * lines="10,40"} 与 {@link Addr#line(int)} 是同一套数法。
 *
 * <p><b>单行是区间的特例</b>：{@link #line(int)} 等价于 {@link #range(int, int)}，内部只保留一个 canonic 形态（{@code from
 * == to}）， 避免"单行"与"1..1"两条路径各自判一遍。
 *
 * @param anchor 精确锚点（整行逐字相等；空 = 用行号寻址）
 * @param from 起始行号（1 起，闭区间；用锚点时为 0）
 * @param to 结束行号（1 起，闭区间，&ge; {@code from}；用锚点时为 0）
 */
public record DocAddr(String anchor, int from, int to) {

  public DocAddr {
    anchor = anchor == null ? "" : anchor;
    if (!anchor.isEmpty()) {
      if (from != 0 || to != 0) {
        throw DocException.invalid("寻址方式互斥：精确锚点与行号区间只能给一个");
      }
    } else {
      if (from < 1 || to < 1) {
        throw DocException.invalid("行号从 1 起（单行也写成区间）: [" + from + ", " + to + "]");
      }
      if (from > to) {
        throw DocException.invalid("行区间下界大于上界（写反了）: [" + from + ", " + to + "]");
      }
    }
  }

  /** 单行寻址（行号从 1 起）。 */
  public static DocAddr line(int line) {
    return new DocAddr("", line, line);
  }

  /** 行区间寻址（闭区间，从 1 起）。 */
  public static DocAddr range(int from, int to) {
    return new DocAddr("", from, to);
  }

  /** 唯一精确锚点（整行逐字相等，且必须唯一命中）。 */
  public static DocAddr anchor(String text) {
    Objects.requireNonNull(text, "anchor");
    if (text.isEmpty()) {
      throw DocException.invalid("精确锚点不能为空（空锚点会与空行撞上，且没有可辨认的语义）");
    }
    if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
      throw DocException.invalid("精确锚点必须是<b>单行</b>文本（多行请用行区间寻址）");
    }
    return new DocAddr(text, 0, 0);
  }

  /** 是否用锚点寻址。 */
  public boolean byAnchor() {
    return !anchor.isEmpty();
  }

  /** 面向模型/日志的渲染（不含物理路径）。 */
  public String label() {
    return byAnchor() ? "锚点@" + anchor : (from == to ? "行 " + from : "行 " + from + "-" + to);
  }
}
