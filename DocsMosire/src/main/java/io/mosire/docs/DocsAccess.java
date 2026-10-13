package io.mosire.docs;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.workspace.Kind;
import io.mosire.workspace.Mount;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceRegistry;
import java.util.List;
import java.util.Map;

/**
 * <b>工具面的唯一入口</b>（设计 §5.1 的第二个门）：把 {@link DocsEngine} 的能力翻译成 {@code doc_*} 工具需要的形状——
 * 调用者身份、观看者、资源面、错误码、以及"面向模型只出虚拟路径"的渲染。
 *
 * <p><b>为什么不把这段逻辑写在工具里</b>：七个工具共用同一套纪律与准入语义。写在工具里意味着七处各判一遍（迟早有一处漏）， 也意味着"工具面包"与"库
 * API"开始分叉。这里只<b>翻译</b>，判据仍在 {@link DocVisibility}（B2）、纪律仍在 {@link DocsEngine}（B9）。
 *
 * <p><b>两件必须写死在这里的事</b>：
 *
 * <ul>
 *   <li><b>观看者</b>：身份取 {@code ToolContext.identity().instanceId()}（宿主绑定，模型给不出），GM 短路位取 {@code
 *       SYSTEM} 桶 ——不是模型自报的（见 {@link DocViewer#from}）；
 *   <li><b>"不可见 ≡ 不存在"</b>：取不到条目时抛的是 {@link DocException#notFound}，与"面内不存在"同一句话（设计 §5.5）——不给模型任何
 *       存在性提示。
 * </ul>
 */
public final class DocsAccess {

  private final DocsEngine engine;

  public DocsAccess(WorkspaceRegistry registry) {
    this.engine = new DocsEngine(registry);
  }

  /** 由工具上下文解析调用者（身份不由模型给出）。 */
  public static WorkspaceCaller callerOf(ToolContext context) {
    return new WorkspaceCaller(context.identity().instanceId(), context.permissions());
  }

  /** 由工具上下文解析观看者（身份 + GM 短路位，见 {@link DocViewer#from}）。 */
  public static DocViewer viewerOf(ToolContext context) {
    return DocViewer.from(context);
  }

  // ── 读面 ────────────────────────────────────────────────────────────────────────────────────

  /** 我有哪几个文档根（只有虚拟名 + 纪律 + 能力，不含物理落点）。 */
  public List<Mount> mounts(ToolContext context) {
    return engine.mounts(callerOf(context));
  }

  /**
   * {@code doc_list}：{@code LOG} 挂载 ⇒ 地址摘要（条数按可见性统计）；{@code FILE} 挂载 ⇒ 挂载内直接子项（虚拟路径）。
   *
   * <p><b>只列直接子项、不递归</b>：{@code Resolver.list} 的 SPI 没有深度参数（"能由现有参数确定语义的形态"是唯一选择），
   * 要更深就逐层下钻——这也让输出天然与 token 预算友好。
   */
  public List<String> list(ToolContext context, String virtualPath) {
    WorkspaceCaller caller = callerOf(context);
    DocsEngine.DocTarget target = engine.mountOf(virtualPath, caller);
    return switch (target.kind()) {
      case LOG -> {
        List<DocsEngine.DocAddressSummary> summaries =
            engine.list(virtualPath, caller, viewerOf(context));
        List<String> lines = new java.util.ArrayList<>(summaries.size());
        for (DocsEngine.DocAddressSummary summary : summaries) {
          lines.add(summary.address() + "（可见条目 " + summary.visibleEntries() + " 条）");
        }
        yield List.copyOf(lines);
      }
      case FILE ->
          // 注册表的 list 已经投影成虚拟路径（G4/A7），这里<b>不得</b>再拼一次挂载名——否则得到
          // "docs-ws/docs-ws/notes" 这种不存在的路径（R5 实测），照着再调用必然失败。
          engine.registry().list(virtualPath.strip(), caller);
      default ->
          throw DocException.discipline(
              "纪律不符（B9）：list 需要 LOG（列地址）或 FILE（列文件）纪律的挂载，而 '"
                  + target.virtualRoot()
                  + "' 是 "
                  + target.kind());
    };
  }

  /** {@code doc_read} 的两种应答：{@code LOG} 的条目 / {@code FILE} 的文本片段。 */
  public sealed interface ReadOutcome permits ReadOutcome.Entries, ReadOutcome.Text {

    /** {@code LOG}：读到的条目（可见性已收口）。 */
    record Entries(List<DocEntry> entries) implements ReadOutcome {

      public Entries {
        // 防御拷贝（EI_EXPOSE_REP + EI_EXPOSE_REP2）：构造与访问器在同一处收口，
        // 调用方拿不回一个能反向改本值的可变表。目标为 null 时 List.copyOf 直接 NPE（fail-closed）
        entries = List.copyOf(entries);
      }
    }

    /** {@code FILE}：读到的文本片段。 */
    record Text(DocReadResult result) implements ReadOutcome {}
  }

  /**
   * {@code doc_read}：{@code LOG} 挂载按 {@code id}/{@code address} 读；{@code FILE} 挂载按虚拟路径读（可带 {@code
   * lines}）。
   *
   * @throws DocException {@code DOC_NOT_FOUND}（面内不存在<b>或</b>不可见——同一个回答）
   */
  public ReadOutcome read(
      ToolContext context, String virtualPath, String id, String address, String lines) {
    WorkspaceCaller caller = callerOf(context);
    DocViewer viewer = viewerOf(context);
    DocsEngine.DocTarget target = engine.mountOf(virtualPath, caller);
    if (target.kind() == Kind.FILE) {
      return new ReadOutcome.Text(engine.readFile(virtualPath, caller, lines));
    }
    target.requireKind(Kind.LOG, "read");
    if (!id.isEmpty()) {
      DocEntry entry =
          engine.find(virtualPath, caller, viewer, id).orElseThrow(() -> notVisibleOrMissing(id));
      return new ReadOutcome.Entries(List.of(entry));
    }
    if (address.isEmpty()) {
      throw DocException.invalid("LOG 纪律的 doc_read 需要 id（精确取一条）或 address（读该地址全部条目）之一");
    }
    List<DocEntry> entries = engine.read(virtualPath, caller, viewer, address);
    if (entries.isEmpty()) {
      // 面内没有可见条目：与"不可见"同回答（不泄露该地址是否存在、也不泄露它有多少条）
      throw notVisibleOrMissing(address);
    }
    return new ReadOutcome.Entries(entries);
  }

  /** {@code doc_query}（{@code LOG} 纪律）。 */
  public List<DocEntry> query(ToolContext context, String virtualPath, QuerySpec spec) {
    WorkspaceCaller caller = callerOf(context);
    return engine.query(virtualPath, caller, spec, viewerOf(context));
  }

  // ── 写面 ────────────────────────────────────────────────────────────────────────────────────

  /** {@code doc_append}（{@code LOG} 纪律）：返回新条目（或重放命中的已存在条目）。 */
  public DocEntry append(ToolContext context, String virtualPath, Map<String, Object> arguments) {
    WorkspaceCaller caller = callerOf(context);
    AppendRequest request = DocArguments.appendRequest(virtualPath, arguments);
    return engine.append(virtualPath, caller, request);
  }

  /** {@code doc_void}（{@code LOG} 纪律）：追加一条 {@code VOIDED}。 */
  public DocEntry voidEntry(ToolContext context, String virtualPath, String id) {
    WorkspaceCaller caller = callerOf(context);
    return engine.voidEntry(virtualPath, caller, id);
  }

  /** {@code doc_create}（{@code FILE} 纪律）：原子创建。 */
  public DocFile create(ToolContext context, String virtualPath, String text) {
    WorkspaceCaller caller = callerOf(context);
    return engine.createFile(virtualPath, caller, text);
  }

  /** {@code doc_edit}（{@code FILE} 纪律）：行寻址编辑，多项全成或全不成。 */
  public DocFile edit(ToolContext context, String virtualPath, List<EditOp> ops) {
    WorkspaceCaller caller = callerOf(context);
    return engine.editFile(virtualPath, caller, ops);
  }

  // ── 诊断 ────────────────────────────────────────────────────────────────────────────────────

  /** 与某次操作相关的坏数据披露（无 ⇒ 空表）。 */
  public List<BrokenDoc> broken(ToolContext context) {
    return engine.broken(callerOf(context));
  }

  /** 引擎（工具面自己也要用它做纪律与准入判定，见 {@link DocsTools}）。 */
  DocsEngine engine() {
    return engine;
  }

  /** 工作区侧失败 → Docs 侧失败（与 {@link DocsEngine} 同一张表）。 */
  static DocException translate(io.mosire.workspace.WorkspaceException e) {
    return DocsEngine.translate(e);
  }

  /** "取不到"的统一文案：<b>不区分</b>"不存在"与"不可见"（设计 §5.5）。 */
  private static DocException notVisibleOrMissing(String what) {
    return DocException.notFound("取不到这个文档（面内不存在；或存在但不在你的可见面内——这两者在这里是同一个回答）: " + what);
  }
}
