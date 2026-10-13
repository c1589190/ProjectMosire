package io.mosire.docs;

import java.util.List;
import java.util.Optional;

/**
 * {@code LOG} 纪律的存储契约（设计 §5.3/§5.5）：<b>追加型</b>——没有覆盖、没有删除，作废 = 追加一条 {@code VOIDED}。
 *
 * <p><b>身份与序号归实现维护</b>（B1）：{@code ordinal} = 该地址的<b>日志行序</b>（从 0 起），{@code id = <地址>#<序号>}。调用方（
 * {@link DocsEngine}）只给地址与载荷。
 *
 * <p><b>参数里没有 {@link DocViewer} 的三个方法</b>（{@code append}/{@code voidEntry}/{@code find}/{@code
 * read}）是<b>库 API</b> 的形态：调用方（进程内代码／唯一入口）自己负责在结果上套 {@link DocVisibility#canSee}——工具层与库 API
 * 都必须走那<b>唯一</b>判据 （B2）。{@link #query(QuerySpec, DocViewer)}
 * 是例外：它的契约本身就包含"先按可见性过滤，再排序、再截断"，所以观看者必须进来。
 *
 * <p><b>坏数据不静默</b>（设计 §5.3）：读不出来的行进 {@link #broken()} 并<b>继续</b>读——它是一份诊断快照，不是异常。
 *
 * <p><b>实现必须线程安全</b>：同一地址的并发追加不撕裂、不同地址互不阻塞（文件锁只锁该地址的日志文件）。
 */
public interface DocStore {

  /**
   * 追加一条（{@code status = EFFECTIVE}）。
   *
   * <p><b>可重放</b>：同地址 + 同载荷已在日志里 ⇒ 返回<b>已存在</b>的那条，不重复落盘（设计 §5.2 的"同 base + 同载荷 ⇒ 同 id"）； 同 id
   * 被<b>不同</b>载荷占用 ⇒ 具名拒（{@link DocException#DOC_EXISTS}，B6）。
   *
   * @throws DocException {@code INVALID_ARGUMENTS}（地址非法）/ {@code DOC_EXISTS}（撞 id）/ {@code
   *     DOC_WRITE_FAILED}（IO 失败，盘上不动）
   */
  DocEntry append(AppendRequest req);

  /**
   * 作废一条：<b>追加</b>一条与目标同地址、同载荷、{@code status = VOIDED} 的新记录（留痕，原记录不动）。
   *
   * <p>已作废的条目再作废 ⇒ 幂等返回原条目（重复点击不该造出第二堆作废痕迹）。
   *
   * @throws DocException {@code DOC_NOT_FOUND}（该 id 在面内不存在）
   */
  DocEntry voidEntry(DocEntryId id);

  /**
   * 按 id 取（<b>不做可见性判断</b>——调用方须经 {@link DocVisibility#visible} 收口，B2）。
   *
   * <p>面内不存在 ⇒ {@link Optional#empty()}；"不可见"与"不存在"在工具层返回同一个回答（设计 §5.5）。
   */
  Optional<DocEntry> find(DocEntryId id);

  /** 某地址的全部条目（按序号升序）；地址没有日志 ⇒ 空表（不抛）。 */
  List<DocEntry> read(String address);

  /**
   * 全部已登记的地址（排序稳定）。
   *
   * <p><b>为什么不返回条目</b>：地址是"这里有哪些文档"的答案，条目是"这个文档有什么内容"的答案——后者要过可见性判据与 limit （见 {@link
   * #query}）。把两者合成一个方法会让某个调用点顺手把不可见条目的条数也数出来（等于泄露它们的存在）。
   */
  List<String> addresses();

  /**
   * 查询：先按 {@link DocVisibility#canSee} 过滤（不可见的条目在结果里<b>不存在</b>），再按（{@code tick} 降序，同 tick 按 {@code
   * (地址, 序号数值)} 升序）排序，最后按 {@code spec.limit()} 截断（设计 §5.5 的"先排后截"）。
   *
   * <p>空结果不抛，返回空表。
   */
  List<DocEntry> query(QuerySpec spec, DocViewer viewer);

  /**
   * 已观察到的坏数据（坏行/不可解析的记录/读失败的文件），按发现顺序；<b>只披露不抛</b>。
   *
   * <p>这是一份<b>诊断快照</b>（累计值），不是"上次读操作的结果"——要看某次读的坏行请比对 {@link #read(String)} 的返回与 {@code broken}
   * 的增量。之所以做成累计：一次读操作只覆盖一个地址，而"哪些数据是坏的"是关于<b>存储</b>的事实。
   */
  List<BrokenDoc> broken();
}
