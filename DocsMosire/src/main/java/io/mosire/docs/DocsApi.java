package io.mosire.docs;

import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceRegistry;
import java.util.List;
import java.util.Optional;

/**
 * <b>库 API 的唯一出口</b>（设计 §5.1 的第一个门）：把 {@link DocsEngine} 的能力收成"一次调用 = 一次具名结果"， 失败一律是 {@link
 * DocException}（稳定错误码），成功一律是值对象。<b>不涉及权限判定之外的东西</b>——没有 {@code ToolContext}、 没有工具错误码、没有资源声明：那是
 * {@link DocsAccess}（第二个门）的事。
 *
 * <p><b>为什么库 API 也要经引擎而不是直接 {@code new DocLogs(dir)}</b>：纪律判定（B9）与逐路径准入是文档语义的一部分，不是工具面的装饰。
 * 进程内的调用者（装配面、管理面、将来的 HTTP/GUI）若绕开引擎直连存储，就得到一套"没纪律、没准入"的语义——两个门必须同语义（B2 的同类教训）。
 *
 * <p><b>可见性由调用方给 {@link DocViewer}</b>：库 API 的调用者已经在进程内（它自己就是可信代码），但仍然必须显式说"以谁的眼睛看"—— 判据只有一处（{@link
 * DocVisibility}），而"以谁的眼睛"是调用语义的一部分，不该有缺省值（缺省必是"全看得见"，那是最危险的一侧）。
 */
public final class DocsApi {

  private final DocsEngine engine;

  public DocsApi(WorkspaceRegistry registry) {
    this.engine = new DocsEngine(registry);
  }

  /** 我有哪几个文档根（{@code user == holder} 切片；含纪律与能力，不含物理落点）。 */
  public List<io.mosire.workspace.Mount> mounts(WorkspaceCaller caller) {
    return engine.mounts(caller);
  }

  // ── LOG 纪律 ────────────────────────────────────────────────────────────────────────────────

  /**
   * 追加一条（{@code LOG}）。
   *
   * @throws DocException 见 {@link DocsEngine#append}
   */
  public DocEntry append(String virtualPath, WorkspaceCaller caller, AppendRequest request) {
    return engine.append(virtualPath, caller, request);
  }

  /** 作废一条（追加 {@code VOIDED}，留痕）。 */
  public DocEntry voidEntry(String virtualPath, WorkspaceCaller caller, DocEntryId id) {
    return engine.voidEntry(virtualPath, caller, id.toString());
  }

  /**
   * 按 id 取一条：<b>不可见 ≡ 不存在</b>（同一个空返回；设计 §5.5）。要给"面内不存在"一个具名错误码的调用点（工具面）用 {@code orElseThrow(...)}
   * 自行决定文案——库 API 不替它选文案。
   */
  public Optional<DocEntry> find(
      String virtualPath, WorkspaceCaller caller, DocViewer viewer, DocEntryId id) {
    return engine.find(virtualPath, caller, viewer, id.toString());
  }

  /** 读一个地址的全部条目（可见性已收口：不可见的条目不在结果里）。 */
  public List<DocEntry> read(
      String virtualPath, WorkspaceCaller caller, DocViewer viewer, String address) {
    return engine.read(virtualPath, caller, viewer, address);
  }

  /** 查询（先排后截在存储层写死）。 */
  public List<DocEntry> query(
      String virtualPath, WorkspaceCaller caller, QuerySpec spec, DocViewer viewer) {
    return engine.query(virtualPath, caller, spec, viewer);
  }

  /** 列出 {@code LOG} 挂载里的地址摘要（条数按可见性统计）。 */
  public List<DocsEngine.DocAddressSummary> list(
      String virtualPath, WorkspaceCaller caller, DocViewer viewer) {
    return engine.list(virtualPath, caller, viewer);
  }

  // ── FILE 纪律 ───────────────────────────────────────────────────────────────────────────────

  /** 读一个文件（{@code lines} 缺省 = 全文；{@code "10,40"} = 只回该闭区间）。 */
  public DocReadResult readFile(String virtualPath, WorkspaceCaller caller, String lines) {
    return engine.readFile(virtualPath, caller, lines);
  }

  /** 原子创建（撞名拒、内容一次落定）。 */
  public DocFile createFile(String virtualPath, WorkspaceCaller caller, String text) {
    return engine.createFile(virtualPath, caller, text);
  }

  /** 行寻址编辑（多项全成或全不成；失败时盘上不动一个字）。 */
  public DocFile editFile(String virtualPath, WorkspaceCaller caller, List<EditOp> ops) {
    return engine.editFile(virtualPath, caller, ops);
  }

  // ── 诊断 ────────────────────────────────────────────────────────────────────────────────────

  /** 已观察到的坏数据（坏行/坏文件）：披露不静默（设计 §5.3）。 */
  public List<BrokenDoc> broken(WorkspaceCaller caller) {
    return engine.broken(caller);
  }

  /** 底层引擎（给"要解析挂载/纪律"的进程内调用点用；工具面经 {@link DocsAccess}）。 */
  public DocsEngine engine() {
    return engine;
  }
}
