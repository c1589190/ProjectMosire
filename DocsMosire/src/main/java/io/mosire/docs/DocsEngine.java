package io.mosire.docs;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.workspace.Kind;
import io.mosire.workspace.Mount;
import io.mosire.workspace.MountName;
import io.mosire.workspace.WorkspaceCaller;
import io.mosire.workspace.WorkspaceException;
import io.mosire.workspace.WorkspaceRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Docs 引擎：把"虚拟路径 → 挂载（纪律）→ 物理落点"这一条链收在<b>一处</b>，并在这里落三件事—— <b>纪律判定</b>（B9）、<b>逐路径准入</b>（{@code
 * docs}/{@code docs-edit} 前缀）、<b>可见性收口</b>（B2）。
 *
 * <p><b>为什么需要它</b>：工具面（{@link DocsTools}）与库 API 必须走同一套语义。若工具各自去调 {@code registry.resolve}、
 * 各自判纪律，就会出现"工具 A 忘了判纪律"的漏洞；引擎是这四段（解析 → 纪律 → 准入 → 可见性）的唯一归属地。
 *
 * <p><b>解析（设计 §4.5 的五步）不在本模块重写</b>：虚拟↔物理的映射归 {@code workspace-mosire} 的 {@link
 * WorkspaceRegistry#resolve}（它是唯一解析点，含准入与 containment）。本模块只做两件它不做的事：
 *
 * <ol>
 *   <li>把解析结果<b>归位到挂载</b>（取该挂载的 {@link Kind}——纪律是"声明式"的，B9）；
 *   <li>在真正读写前按 {@code docs} / {@code docs-edit} 面做<b>逐路径</b>要求（{@link Requirement}）。
 * </ol>
 *
 * <p><b>为什么不实现 {@code WorkspaceRegistry}</b>：那会让 Docs 变成"第二个工作区实现"。本类只<b>持有一个已装配的
 * registry</b>（构造期注入），不新建、不改写它——解析点仍然只有一个。
 *
 * <p><b>资源面按整段路径判定</b>：{@code require} 的参数用<b>挂载的物理根</b>（{@code docs:<挂载根>}）。前缀的语义是"容器"——
 * "能碰这个挂载"就该能碰它里面的任意文件；逐文件判定会把 {@link ResourceScope} 的"容器"语义用成"白名单枚举"，而工作区模块已经 保证了"这个挂载的物理范围 ⊆
 * 调用者可达面"（M1/M3）。因此路径级越权（{@code ../..}）由解析点的 containment 挡住，权限级越权 由本处的面判定挡住。
 */
public final class DocsEngine {

  private static final Logger LOG = LoggerFactory.getLogger(DocsEngine.class);

  /** 部分路径段的形态守卫（与 {@link MountName#PATTERN} 同口径；挂载名的形态由 {@link MountName} 自己判）。 */
  private static final String SEGMENT_PATTERN = "[A-Za-z0-9._-]{1,128}";

  private final WorkspaceRegistry registry;

  public DocsEngine(WorkspaceRegistry registry) {
    this.registry = java.util.Objects.requireNonNull(registry, "registry");
  }

  /**
   * 装配时注入的工作区注册表（<b>唯一解析点</b>）。
   *
   * <p>暴露它是为了两件事：① {@code doc_list} 在 {@code FILE} 挂载上要列直接子项（那是 {@link WorkspaceRegistry#list} 的职责，
   * Docs 不重写投影）；② 诊断/装配点需要问"挂载表是什么"。<b>不是</b>给调用点绕开引擎直接解析用的——那样就绕过了纪律与准入判定。
   */
  public WorkspaceRegistry registry() {
    return registry;
  }

  // ── LOG 纪律 ────────────────────────────────────────────────────────────────────────────────

  /**
   * 追加一条（{@code LOG} 纪律）。
   *
   * @throws DocException {@code DOC_INVALID}（纪律不符）/ {@code RESOURCE_DENIED}（范围外或权限面盖不住）/ {@code
   *     INVALID_ARGUMENTS}（地址非法）/ {@code DOC_EXISTS}（撞 id）/ {@code DOC_WRITE_FAILED}
   */
  public DocEntry append(String virtualPath, WorkspaceCaller caller, AppendRequest request) {
    DocTarget target = mountOf(virtualPath, caller);
    target.requireKind(Kind.LOG, "append");
    require(target, caller, RequestKind.APPEND);
    return logStore(target).append(request);
  }

  /**
   * 作废一条（{@code LOG} 纪律）：追加一条同载荷、状态 {@code VOIDED} 的记录，原记录不动。
   *
   * @throws DocException {@code DOC_INVALID}（纪律不符）/ {@code DOC_NOT_FOUND}（面内不存在）
   */
  public DocEntry voidEntry(String virtualPath, WorkspaceCaller caller, String id) {
    DocTarget target = mountOf(virtualPath, caller);
    target.requireKind(Kind.LOG, "void");
    require(target, caller, RequestKind.VOID);
    return logStore(target).voidEntry(DocEntryId.parse(id));
  }

  /**
   * 按 id 取一条：<b>不可见 ≡ 不存在</b>——两者都返回空（设计 §5.5；判据见 {@link DocVisibility}）。
   *
   * @throws DocException {@code DOC_INVALID}（纪律不符——{@code FILE} 挂载上没有条目级 id）
   */
  public Optional<DocEntry> find(
      String virtualPath, WorkspaceCaller caller, DocViewer viewer, String id) {
    DocTarget target = mountOf(virtualPath, caller);
    target.requireKind(Kind.LOG, "find");
    require(target, caller, RequestKind.READ);
    Optional<DocEntry> found = logStore(target).find(DocEntryId.parse(id));
    if (found.isEmpty()) {
      LOG.debug("docs.find 未命中（与不可见同回答）虚拟路径={} 调用者={}", virtualPath, caller.holder());
      return Optional.empty();
    }
    return DocVisibility.visible(viewer, found.get());
  }

  /**
   * 读一个地址的全部条目：<b>先按可见性过滤</b>（不可见的条目在结果里根本不存在），再按序号升序返回。
   *
   * @throws DocException {@code DOC_INVALID}（纪律不符）
   */
  public List<DocEntry> read(
      String virtualPath, WorkspaceCaller caller, DocViewer viewer, String address) {
    DocTarget target = mountOf(virtualPath, caller);
    target.requireKind(Kind.LOG, "read");
    require(target, caller, RequestKind.READ);
    List<DocEntry> entries = logStore(target).read(address);
    List<DocEntry> visible = new ArrayList<>(entries.size());
    for (DocEntry entry : entries) {
      DocVisibility.visible(viewer, entry).ifPresent(visible::add);
    }
    return List.copyOf(visible);
  }

  /** 查询（{@code LOG} 纪律）：先按可见性过滤、再按 tick/区间过滤、再排序、最后截断——"先排后截"在存储实现里写死（设计 §5.5）。 */
  public List<DocEntry> query(
      String virtualPath, WorkspaceCaller caller, QuerySpec spec, DocViewer viewer) {
    DocTarget target = mountOf(virtualPath, caller);
    target.requireKind(Kind.LOG, "query");
    require(target, caller, RequestKind.READ);
    return logStore(target).query(spec, viewer);
  }

  /**
   * 列出 {@code LOG} 挂载里全部地址的摘要（{@code doc_list} 用）。
   *
   * <p><b>为什么只有摘要、没有条目</b>：{@code LOG} 的地址由调用方定义且不透明——"列出地址 + 条数"回答"这里有什么文档"， 而"哪几条我能看"是 {@link
   * #query} 的事（它带 limit，也带可见性判据）。
   *
   * @param viewer 观看者（<b>条数按可见性统计</b>：数出不可见条目等于泄露它们的存在）
   */
  public List<DocAddressSummary> list(
      String virtualPath, WorkspaceCaller caller, DocViewer viewer) {
    DocTarget target = mountOf(virtualPath, caller);
    target.requireKind(Kind.LOG, "list");
    require(target, caller, RequestKind.READ);
    DocStore store = logStore(target);
    List<DocAddressSummary> summaries = new ArrayList<>();
    for (String address : store.addresses()) {
      List<DocEntry> entries = store.read(address);
      long visible = entries.stream().filter(entry -> DocVisibility.canSee(viewer, entry)).count();
      if (visible > 0) {
        summaries.add(new DocAddressSummary(address, visible));
      }
    }
    return List.copyOf(summaries);
  }

  // ── FILE 纪律 ───────────────────────────────────────────────────────────────────────────────

  /**
   * 读一个文件（{@code FILE} 纪律；{@code lines} 缺省 = 全文）。
   *
   * @throws DocException {@code DOC_INVALID}（纪律不符）/ {@code DOC_NOT_FOUND}（面内不存在）
   */
  public DocReadResult readFile(String virtualPath, WorkspaceCaller caller, String lines) {
    DocTarget target = mountOf(virtualPath, caller);
    target.requireKind(Kind.FILE, "read");
    require(target, caller, RequestKind.READ);
    return files(target).read(target.relativePath(), lines);
  }

  /**
   * 原子创建（{@code FILE} 纪律）：撞名拒、内容一次落定（设计 §5.5）。
   *
   * @throws DocException {@code DOC_INVALID}（纪律不符）/ {@code DOC_EXISTS}（撞名）/ {@code
   *     DOC_WRITE_FAILED}
   */
  public DocFile createFile(String virtualPath, WorkspaceCaller caller, String text) {
    DocTarget target = mountOf(virtualPath, caller);
    target.requireKind(Kind.FILE, "create");
    require(target, caller, RequestKind.CREATE);
    return files(target).create(target.relativePath(), text);
  }

  /**
   * 行寻址编辑（{@code FILE} 纪律）：多项操作<b>全成或全不成</b>，失败时盘上不动一个字。
   *
   * @throws DocException {@code DOC_INVALID}（纪律不符）/ {@code INVALID_ARGUMENTS}（锚点不唯一/越界）/ {@code
   *     DOC_NOT_FOUND} / {@code DOC_WRITE_FAILED}
   */
  public DocFile editFile(String virtualPath, WorkspaceCaller caller, List<EditOp> ops) {
    DocTarget target = mountOf(virtualPath, caller);
    target.requireKind(Kind.FILE, "edit");
    require(target, caller, RequestKind.EDIT);
    return files(target).edit(target.relativePath(), ops);
  }

  // ── 挂载与诊断 ──────────────────────────────────────────────────────────────────────────────

  /**
   * 某身份可使用（{@code user == holder} 切片）的挂载，按挂载名排序——工具面据此知道"我有哪几个文档根"。
   *
   * <p><b>不判准入</b>：解析点会在 {@link #mountOf} 时判（这正是"不重复判"的落法：本方法只回答"你的挂载表里有什么"）。
   */
  public List<Mount> mounts(WorkspaceCaller caller) {
    java.util.Objects.requireNonNull(caller, "caller");
    List<Mount> mounted = new ArrayList<>(registry.mounts(caller.holder()));
    mounted.sort(java.util.Comparator.comparing(mount -> mount.name().value()));
    return List.copyOf(mounted);
  }

  /** 已观察到的坏数据（坏行/坏文件），按挂载归集——披露不静默（设计 §5.3）。 */
  public List<BrokenDoc> broken(WorkspaceCaller caller) {
    List<BrokenDoc> all = new ArrayList<>();
    for (Mount mount : mounts(caller)) {
      try {
        Path base = baseDirOf(mount, caller);
        if (mount.kind() == Kind.LOG) {
          all.addAll(DocLogs.onDisk(base).broken());
        } else if (mount.kind() == Kind.FILE) {
          all.addAll(new DiskDocFiles(base, relative -> render(mount, relative)).broken());
        }
      } catch (WorkspaceException e) {
        LOG.debug("docs.broken 跳过盖不住的挂载 名字={} 调用者={}", mount.name().value(), caller.holder());
      }
    }
    return List.copyOf(all);
  }

  // ── 内部：解析归位 ───────────────────────────────────────────────────────────────────────────

  /**
   * 虚拟路径 → (挂载, 纪律, 挂载相对路径)。
   *
   * <p>顺序写死：<b>先过唯一解析点</b>（{@link WorkspaceRegistry#resolve}，它判"名字存不存在 + 准入 + containment"），
   * <b>再把落点归位到某个挂载</b>（取它的 {@code Kind}）。反过来（先按名字找挂载、再解析）会让"挂载不存在"与"无权"分成两条路， 而设计 §4.5
   * 要求它们<b>同码</b>——同码这件事由解析点保证，本方法不替它分叉。
   *
   * @throws DocException {@code INVALID_ARGUMENTS}（虚拟路径拼法非法）/ {@code RESOURCE_DENIED}（范围外或名字不存在）
   */
  DocTarget mountOf(String virtualPath, WorkspaceCaller caller) {
    java.util.Objects.requireNonNull(caller, "caller");
    String relative = relativeOf(virtualPath);
    Path physical;
    try {
      physical = registry.resolve(virtualPath.strip(), caller);
    } catch (WorkspaceException e) {
      throw translate(e);
    }
    List<Mount> hits = new ArrayList<>();
    for (Mount mount : registry.mounts(caller.holder())) {
      Path base;
      try {
        base = baseDirOf(mount, caller);
      } catch (WorkspaceException e) {
        continue;
      }
      if (physical.startsWith(base)) {
        hits.add(mount);
      }
    }
    if (hits.isEmpty()) {
      // 解析成功却归不到任何挂载：只可能是挂载表在解析与归位之间被改过。fail-closed，不猜（也不泄露落点）
      throw DocException.denied("这个虚拟路径归不到任何挂载（解析与归位之间挂载表变了）: " + virtualPath);
    }
    if (hits.size() > 1) {
      // 嵌套挂载（§4.2 的推迟项）会让"纪律是哪一个"变得不确定：宁拒不猜（否则"该拒的写"可能落进可写的那一个）
      throw DocException.invalid(
          "这个虚拟路径同时落在 " + hits.size() + " 个挂载的范围内（嵌套挂载在 v1 是推迟项，无法确定纪律）: " + virtualPath);
    }
    Mount mount = hits.get(0);
    return new DocTarget(mount, relative, physical);
  }

  /** 解析挂载的物理根（一次解析，不创建任何东西）。 */
  private Path baseDirOf(Mount mount, WorkspaceCaller caller) {
    String name = mount.name().value();
    try {
      return registry.resolve(name, caller);
    } catch (WorkspaceException e) {
      throw translate(e);
    }
  }

  /**
   * 虚拟路径 → 挂载内相对路径（拼法守卫与设计 §4.2/G3 同口径）。
   *
   * <p>本方法<b>只做形态解析</b>：名字是否存在、能不能用，归唯一解析点（见 {@link #mountOf}）。因此它与 {@code WorkspaceRegistry}
   * 的拼法知识有一个共同风险——两处拼法若不一致，会出现"解析器认为是 A、Docs 认为是 B"。这里逐条照抄拼法规则，
   * 并且只用于"取出相对路径"（不用于鉴权），最坏后果是一次具名拒，而不是越权。
   */
  static String relativeOf(String virtualPath) {
    String raw = virtualPath == null ? "" : virtualPath.strip();
    if (raw.isEmpty()) {
      throw DocException.invalid("虚拟路径不能为空（形态：<挂载名>/<相对路径>）");
    }
    if (raw.startsWith("/")) {
      throw DocException.invalid("虚拟路径不得以 '/' 开头（挂载名就是根）: " + raw);
    }
    String[] segments = raw.split("/", -1);
    if (!MountName.isValid(segments[0])) {
      throw DocException.invalid(
          "虚拟路径的第一段不是合法挂载名（" + MountName.PATTERN + "）: '" + segments[0] + "'");
    }
    StringBuilder relative = new StringBuilder();
    for (int i = 1; i < segments.length; i++) {
      String segment = segments[i];
      if (segment.isEmpty()) {
        throw DocException.invalid("虚拟路径不得含空段（重复 '/' 或尾随 '/'）: " + raw);
      }
      if (".".equals(segment) || "..".equals(segment)) {
        throw DocException.invalid("虚拟路径不得含 '.' 或 '..' 段: " + raw);
      }
      if (relative.length() > 0) {
        relative.append('/');
      }
      relative.append(segment);
    }
    return relative.toString();
  }

  /** 相对路径 → 虚拟路径（<b>面向模型的唯一去向</b>：A7/G4）。 */
  static String render(Mount mount, String relativePath) {
    String relative = relativePath == null ? "" : relativePath.strip();
    return relative.isEmpty() ? mount.name().value() : mount.name().value() + "/" + relative;
  }

  // ── 内部：准入（docs / docs-edit 前缀） ───────────────────────────────────────────────────────

  /**
   * 一次访问要求的<b>面</b>（读面 {@code docs} / 写面 {@code docs-edit}）。
   *
   * <p><b>为什么不是一个"读写"布尔</b>：两个面是<b>两个命名空间</b>，可以独立表态（"看得见但不能改"是主要用法）。把读写成 {@code WRITE}、或把 {@code
   * docs-edit} 读成 {@code docs}，都会让"只读参考文档"这种装配失去意义。
   */
  private enum RequestKind {
    READ(Operation.READ, false),
    APPEND(Operation.WRITE, true),
    VOID(Operation.WRITE, true),
    CREATE(Operation.WRITE, true),
    EDIT(Operation.WRITE, true);

    private final Operation operation;
    private final boolean writes;

    RequestKind(Operation operation, boolean writes) {
      this.operation = operation;
      this.writes = writes;
    }
  }

  /**
   * 逐路径准入：读面看 {@code docs}，写面看 {@code docs-edit}；未表态即拒（fail-closed，设计 §5.6）。
   *
   * <p><b>两段都判</b>：① {@link ResourceScope#allows} 看"这个具体落点"在不在可达面内；② {@link ResourceScope#covers}
   * 看前缀集合<b>整体</b>盖住挂载根——嵌套/多根挂载下"(a) 允许但 (b) 只覆盖一半"是可能的，只判 ① 会让半个挂载溜进来。两道都过才算够得着。
   */
  private void require(DocTarget target, WorkspaceCaller caller, RequestKind kind) {
    String namespace = kind.writes ? DocNamespaces.DOCS_EDIT : DocNamespaces.DOCS;
    ResourceScope scope = caller.scope(namespace);
    ResourceScope root = ResourceScope.ofDir(target.baseDir());
    if (!scope.allows(target.physical().toString()) || !scope.covers(root)) {
      throw DocException.denied(
          "范围外：调用者在 "
              + namespace
              + " 面上盖不住这个挂载的落点（未表态或超出前缀；这是显式表态，不是\"未表态\"）: "
              + target.virtualRoot());
    }
  }

  // ── 内部：存储装配（每次操作现建，保证"写完立刻对后续读可见"） ───────────────────────────────

  /** {@code LOG} 存储（读、查、列、追加、作废都经它）。 */
  private DocStore logStore(DocTarget target) {
    return DocLogs.onDisk(target.baseDir());
  }

  /** {@code FILE} 存储（注入相对路径 → 虚拟路径的渲染，保证面向模型的那一侧只出虚拟路径）。 */
  private DocFileStore files(DocTarget target) {
    return new DiskDocFiles(target.baseDir(), relative -> render(target.mount(), relative));
  }

  /**
   * 工作区侧失败 → Docs 侧的失败（工具面共用同一张转码表，避免"同一个异常在两个入口得到两个码"）。
   *
   * <p>{@code WORKSPACE_NOT_FOUND} 与 {@code RESOURCE_DENIED} 在 Docs 面上都归"范围外"：挂载不存在、不是你的挂载、
   * 名字你无权解析——三者在设计 §4.5 里就是同码，工具面不该把它们分开（否则又成了存在性探测器）。
   */
  static DocException translate(WorkspaceException e) {
    // 工作区侧的失败码与 Docs 侧的自有码保持各自口径：RESOURCE_DENIED 直接透传（同一字面），
    // 其余（名字不存在、形态非法）在 Docs 面上归 INVALID_ARGUMENTS/RESOURCE_DENIED 两类——不新增第五个码。
    if (WorkspaceException.RESOURCE_DENIED.equals(e.code())) {
      return DocException.denied(e.getMessage());
    }
    if (WorkspaceException.WORKSPACE_NOT_FOUND.equals(e.code())) {
      // "挂载不存在"与"无权"同码（设计 §4.5）：在 Docs 面上也同回答（范围外）
      return DocException.denied(e.getMessage());
    }
    return DocException.invalid(e.getMessage());
  }

  /** 挂载内地址摘要（{@code doc_list} 的 {@code LOG} 输出元素）。 */
  public record DocAddressSummary(String address, long visibleEntries) {}

  /**
   * 解析归位的结果：<b>挂载 + 纪律 + 挂载内相对路径</b>。
   *
   * <p>{@link #baseDir()} 是物理落点——它<b>只在本模块内部流转</b>（喂存储与准入判定），绝不进面向模型的文案（A7）。
   */
  record DocTarget(Mount mount, String relativePath, Path physical) {

    Kind kind() {
      return mount.kind();
    }

    /** 挂载的物理根（准入判定与存储装配用它；<b>不进面向模型的文案</b>）。 */
    Path baseDir() {
      return mount.dir();
    }

    /** 虚拟路径的根（{@code <挂载名>}）——错误文案里用它，不用物理落点。 */
    String virtualRoot() {
      return mount.name().value();
    }

    /** 虚拟路径（{@code <挂载名>/<相对路径>}；面向模型的唯一形态）。 */
    String virtualPath() {
      return render(mount, relativePath);
    }

    /**
     * 纪律守卫（B9）：越纪律<b>具名拒</b>，不静默降级。
     *
     * <p>{@link Kind#SCRATCH}/{@link Kind#REFERENCE} 上两类操作都不许：{@code SCRATCH} 是通用暂存区、{@code
     * REFERENCE} 是只读引用——两者都不是"文档"，给它们任何文档写口都会让"纪律是声明式的"这句话变成"几乎声明式"。
     */
    void requireKind(Kind required, String action) {
      Kind actual = mount.kind();
      if (actual == required) {
        return;
      }
      throw DocException.discipline(
          "纪律不符（B9）："
              + action
              + " 需要 "
              + required
              + " 纪律的挂载，而 '"
              + virtualRoot()
              + "' 是 "
              + actual
              + disciplineHint(required));
    }

    private static String disciplineHint(Kind required) {
      if (required == Kind.LOG) {
        return "（追加型文档：只能追加/作废，不能就地改文件）";
      }
      if (required == Kind.FILE) {
        return "（文件型文档：用 doc_create/doc_edit/doc_read；\"追加一条\"是 LOG 纪律的动作）";
      }
      return "";
    }
  }
}
