package io.mosire.docs;

import java.util.List;

/**
 * {@code FILE} 纪律的存储契约（设计 §5.3/§5.5）：普通文件（虚拟路径 → 物理文件，一对一），<b>自实现 IO</b>（N13）。
 *
 * <p><b>职责边界</b>：纪律判定（{@code LOG} 挂载上不许 {@code doc_create}/{@code doc_edit}）与逐路径资源 {@code require}
 * 都在唯一入口 （{@link DocsEngine} / {@link DocsAccess}）；本接口只管"在给定虚拟路径上做原子 IO"。
 *
 * <p><b>原子性写死在这里</b>（B8）：
 *
 * <ul>
 *   <li>{@link #create}：同目录临时文件 → {@code ATOMIC_MOVE}；<b>撞名拒</b>（{@code DOC_EXISTS}）——不静默覆盖；
 *   <li>{@link #edit}：多项操作<b>全成或全不成</b>；失败时<b>盘上不动一个字</b>。
 * </ul>
 *
 * <p><b>没有删除</b>（v1）：{@code rm} 在工具面里不存在（设计 §5.9 的负向判据），本接口也不提供。
 */
public interface DocFileStore {

  /**
   * 读一个文件（{@code lines} 缺省 = 全文；{@code "10,40"} = 只回该闭区间）。
   *
   * @param lines 行区间（可空 = 全文）
   * @throws DocException {@code INVALID_ARGUMENTS}（路径/行区间形态非法）/ {@code DOC_NOT_FOUND}（面内不存在）/
   *     {@code DOC_READ_FAILED}（IO 失败）
   */
  DocReadResult read(String virtualPath, String lines);

  /**
   * 原子创建：{@code text} 一次落定，绝不出现"写一半的文档"。
   *
   * @return 落定后的描述（物理落点只进日志——面向模型的那一侧只出虚拟路径）
   * @throws DocException {@code DOC_EXISTS}（撞名）/ {@code DOC_WRITE_FAILED}（IO 失败，盘上不新增任何东西）
   */
  DocFile create(String virtualPath, String text);

  /**
   * 行寻址编辑：{@link EditOp} 的 {@code addr} 是<b>行区间</b>或<b>唯一精确锚点</b>。
   *
   * <p><b>全成或全不成</b>：多条操作在内存里逐条施加于同一份行视图，任何一条失败（锚点不命中/不唯一、{@code expect} 不符、行号越界）
   * 都整体拒绝，<b>盘上不动一个字</b>。
   *
   * @return 编辑后的描述
   * @throws DocException {@code DOC_NOT_FOUND} / {@code INVALID_ARGUMENTS}（锚点不唯一/越界/区间写反）/ {@code
   *     DOC_WRITE_FAILED}（原子替换失败）
   */
  DocFile edit(String virtualPath, List<EditOp> ops);
}
