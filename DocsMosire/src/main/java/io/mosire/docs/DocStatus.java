package io.mosire.docs;

/**
 * 条目的状态（设计 §5.4：{@code EFFECTIVE}/{@code VOIDED}，核心字段）。
 *
 * <p><b>为什么作废不是删除</b>：{@code LOG} 纪律的价值就是"永不删除 + 留痕 + 可重放"（隔壁 R-W1/W2/R-S6）。作废 = <b>再追加一条</b>同为
 * {@code VOIDED} 的记录，原条目一个字都不动——于是"这条被谁在什么时候作废"本身也成为留痕。任何"原地改状态"的实现都会同时毁掉不可变语义与可重放性。
 */
public enum DocStatus {
  /** 生效。 */
  EFFECTIVE,

  /** 已作废（由 {@link DocStore#voidEntry} 追加产生；原条目保持 {@link #EFFECTIVE} 不动）。 */
  VOIDED
}
