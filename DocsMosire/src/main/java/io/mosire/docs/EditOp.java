package io.mosire.docs;

import java.util.Objects;
import java.util.Optional;

/**
 * 一次行寻址编辑（设计 §5.5）：{@code (addr, kind, text)} + 可选前置断言 {@link #expect()}。
 *
 * <p><b>多项操作全成或全不成</b>：{@link DocFileStore#edit} 收到的是一个 {@code
 * List<EditOp>}——它在<b>内存里</b>把全部操作逐条施加到 同一份行视图上，任何一条失败（锚点不命中/不唯一、{@code expect}
 * 不符、行号越界）都<b>整体拒绝且盘上不动一个字</b>（B8/B10）。先做一半再回滚 那种写法在这里不可能出现：写盘只发生在所有操作都成功之后的一次原子替换。
 *
 * <p><b>{@link #expect()} 是什么</b>：操作前的<b>前置断言</b>——寻址到的原文（{@link DocOpKind#REPLACE}/{@link
 * DocOpKind#DELETE} 取 被替换/被删除的那段文本，含换行；{@link DocOpKind#INSERT_AFTER}/{@link
 * DocOpKind#INSERT_BEFORE} 取锚点行本身）必须与它<b>逐字相等</b>，
 * 否则拒。它把"我以为这一行还是老样子"变成可检验的前置条件——并发编辑与过期上下文最常见的事故由此变成一次具名拒，而不是一次静默改错。
 *
 * @param addr 寻址（行号 / 行区间 / 唯一精确锚点）
 * @param kind 动作
 * @param text 新文本（{@link DocOpKind#DELETE} 时无意义，必须为空）
 * @param expect 前置断言（空 = 不断言）
 */
public record EditOp(DocAddr addr, DocOpKind kind, String text, Optional<String> expect) {

  public EditOp {
    Objects.requireNonNull(addr, "addr");
    Objects.requireNonNull(kind, "kind");
    text = text == null ? "" : text;
    expect = expect == null ? Optional.empty() : expect;
    if (kind == DocOpKind.DELETE && !text.isEmpty()) {
      throw DocException.invalid("DELETE 操作不接受 text（删除没有新文本）: " + text);
    }
  }

  /** 无前置断言的编辑（最常见的形态）。 */
  public static EditOp of(DocAddr addr, DocOpKind kind, String text) {
    return new EditOp(addr, kind, text, Optional.empty());
  }

  /** 带前置断言的编辑。 */
  public static EditOp of(DocAddr addr, DocOpKind kind, String text, String expect) {
    return new EditOp(addr, kind, text, Optional.ofNullable(expect));
  }
}
