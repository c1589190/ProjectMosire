package io.mosire.docs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code doc_edit} 的 {@code ops} 参数 → {@link EditOp} 列表的翻译。
 *
 * <p><b>寻址三选一，写死不许含糊</b>：一项编辑只能给 {@code line}（单行）、{@code from}/{@code to}（闭区间）或 {@code anchor}
 * （唯一精确锚点）三者之一。给了两种以上一律具名拒——"两处都给了，用哪个"如果由实现来猜，工具面就会出现"同一份 ops 在两次调用里改动不同位置"。
 *
 * <p><b>动作名的宽容度</b>：{@code replace}/{@code insertAfter}/{@code insertBefore}/{@code delete} 是本仓的口径，
 * {@code insert_after}/{@code insert-after} 也接受（模型会把 snake_case 与 kebab-case 混着写，而这三者语义无歧义）。
 * 超过这个宽容度的拼法一律拒，不做"最接近匹配"。
 */
final class DocEditOps {

  private DocEditOps() {
    throw new AssertionError("No instances");
  }

  /**
   * 解析 {@code ops}（数组；每项是一个对象）。
   *
   * @throws DocException {@code INVALID_ARGUMENTS}（形态/寻址/动作名非法）
   */
  static List<EditOp> parse(Object raw) {
    if (raw == null) {
      throw DocException.invalid("doc_edit 需要 ops（至少一项编辑）");
    }
    if (!(raw instanceof List<?> list)) {
      throw DocException.invalid("ops 必须是数组（每一项是一条编辑）: " + raw);
    }
    if (list.isEmpty()) {
      throw DocException.invalid("ops 不能为空数组（什么都不改的调用是笔误，不是\"不变\"）");
    }
    List<EditOp> ops = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      ops.add(parseOne(i, list.get(i)));
    }
    return List.copyOf(ops);
  }

  private static EditOp parseOne(int index, Object raw) {
    String where = "ops[" + index + "]";
    if (!(raw instanceof Map<?, ?> map)) {
      throw DocException.invalid(where + " 必须是对象: " + raw);
    }
    DocOpKind kind = kind(where, map.get("op"));
    DocAddr addr = addr(where, map);
    String text = map.get("text") == null ? "" : String.valueOf(map.get("text"));
    Optional<String> expect =
        map.get("expect") == null
            ? Optional.empty()
            : Optional.of(String.valueOf(map.get("expect")));
    return new EditOp(addr, kind, text, expect);
  }

  private static DocOpKind kind(String where, Object raw) {
    String value = raw == null ? "" : String.valueOf(raw).strip();
    if (value.isEmpty()) {
      throw DocException.invalid(where + " 缺 op（replace / insertAfter / insertBefore / delete 之一）");
    }
    String normalized = value.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "replace", "substitute" -> DocOpKind.REPLACE;
      case "insertafter", "after" -> DocOpKind.INSERT_AFTER;
      case "insertbefore", "before" -> DocOpKind.INSERT_BEFORE;
      case "delete", "remove" -> DocOpKind.DELETE;
      default ->
          throw DocException.invalid(
              where + " 的 op 不认识（replace / insertAfter / insertBefore / delete）: '" + value + "'");
    };
  }

  private static DocAddr addr(String where, Map<?, ?> map) {
    boolean hasAnchor = map.get("anchor") != null;
    boolean hasLine = map.get("line") != null;
    boolean hasFrom = map.get("from") != null;
    boolean hasTo = map.get("to") != null;
    if (map.get("from") != null ^ map.get("to") != null) {
      throw DocException.invalid(where + " 的行区间必须同时给 from 与 to（闭区间，从 1 起）");
    }
    int modes = (hasAnchor ? 1 : 0) + (hasLine ? 1 : 0) + (hasFrom ? 1 : 0);
    if (modes == 0) {
      throw DocException.invalid(where + " 缺寻址（anchor / line / from+to 三选一）");
    }
    if (modes > 1) {
      throw DocException.invalid(where + " 给了多种寻址（anchor / line / from+to 只能给一种，不替你猜）");
    }
    if (hasAnchor) {
      return DocAddr.anchor(String.valueOf(map.get("anchor")));
    }
    if (hasLine) {
      return DocAddr.line(positive(where, "line", map.get("line")));
    }
    int from = positive(where, "from", map.get("from"));
    int to = positive(where, "to", map.get("to"));
    return DocAddr.range(from, to);
  }

  private static int positive(String where, String name, Object raw) {
    String text = String.valueOf(raw).strip();
    int value;
    try {
      value = Integer.parseInt(text);
    } catch (NumberFormatException e) {
      throw DocException.invalid(where + " 的 " + name + " 不是整数: '" + text + "'");
    }
    if (value < 1) {
      throw DocException.invalid(where + " 的 " + name + " 从 1 起: " + value);
    }
    return value;
  }
}
