package io.mosire.docs;

/**
 * {@code FILE} 纪律的编辑动作（设计 §5.5 的写契约）。
 *
 * <p><b>为什么只有四种</b>：{@code FILE} 在 v1 <b>没有删除整个文件</b>（设计 §5.9 的负向判据：{@code rm} 在工具面里不存在）；{@link
 * #DELETE} 删的是<b>行</b>，它落在"行寻址编辑"这一族里（Linux 侧对应 {@code sed -i '10,20d'}），与"删文件"是两件事，别混。
 *
 * <p><b>行区间与锚点的关系</b>：{@link #INSERT_AFTER}/{@link #INSERT_BEFORE}
 * 的插入点是<b>一行</b>——用区间寻址时取区间的首行（after 用<b>末行</b>）；用锚点时必须唯一命中。
 */
public enum DocOpKind {
  /** 替换寻址到的行（区间 ⇒ 整段换成 {@code text}，可含换行）。 */
  REPLACE,

  /** 在寻址行<b>之后</b>插入 {@code text}（可含换行 ⇒ 一次插入多行）。 */
  INSERT_AFTER,

  /** 在寻址行<b>之前</b>插入 {@code text}。 */
  INSERT_BEFORE,

  /** 删除寻址到的行（不产出新行）。 */
  DELETE
}
