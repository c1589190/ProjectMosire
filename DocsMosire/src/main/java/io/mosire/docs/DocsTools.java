package io.mosire.docs;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.workspace.Kind;
import io.mosire.workspace.Mount;
import io.mosire.workspace.WorkspaceRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Docs 工具面（设计 §5.6）：{@code doc_list} / {@code doc_read} / {@code doc_query} / {@code doc_append} /
 * {@code doc_void} / {@code doc_create} / {@code doc_edit}——七个工具，两种纪律。
 *
 * <p><b>工具族与工作区解耦</b>（A8）：本类的工具<b>不认识工作区</b>——它们只按 {@link ResourceManifest} 判 {@code docs} / {@code
 * docs-edit}，逐路径的 {@code require} 由 {@link DocsEngine} 在真正读写前完成。"这个 Agent 能不能用 docs 能力"是装配时挂载/授予的
 * <b>落格结果</b>，工具侧不再判一次，也不允许自行放宽。
 *
 * <p><b>资源声明</b>（设计 §5.6）：读工具 {@code ResourceManifest.of("docs", DENY)}；写工具 {@code
 * ResourceManifest.of("docs-edit", DENY)} 且 {@link ToolSpec} 标 {@code destructive =
 * true}（追加/作废/创建/编辑都改变盘上数据）。
 *
 * <p><b>面向模型的输出只含虚拟路径</b>（G4/A7）：物理落点只出现在本类与 {@link DiskDocFiles}/{@link DocLogs} 的日志里。
 *
 * <p><b>七个工具都要给 {@code mount}</b>：虚拟路径的根就是挂载名——把它显式化让"在哪个文档根里做事"成为调用的一部分，而不是从路径拼法里
 * 反推（反推会让"名字里带斜杠的挂载"这类边界变成猜谜）。
 */
public final class DocsTools {

  /** 列文档（{@code LOG} 列地址摘要；{@code FILE} 列文件）。 */
  public static final String DOC_LIST = "doc_list";

  /** 读文档（{@code LOG} 按 id/地址；{@code FILE} 按路径，可带行区间）。 */
  public static final String DOC_READ = "doc_read";

  /** 按时刻查询（{@code LOG}）。 */
  public static final String DOC_QUERY = "doc_query";

  /** 追加一条（{@code LOG}，破坏性）。 */
  public static final String DOC_APPEND = "doc_append";

  /** 作废一条（{@code LOG}，追加 {@code VOIDED}，破坏性）。 */
  public static final String DOC_VOID = "doc_void";

  /** 原子创建（{@code FILE}，撞名拒，破坏性）。 */
  public static final String DOC_CREATE = "doc_create";

  /** 行寻址编辑（{@code FILE}，多项全成或全不成，破坏性）。 */
  public static final String DOC_EDIT = "doc_edit";

  /** 本工具族注册的工具名集合（装配与"子进程不要把同名工具重复桥进来"的判断都用它）。 */
  public static final Set<String> NAMES =
      Set.of(DOC_LIST, DOC_READ, DOC_QUERY, DOC_APPEND, DOC_VOID, DOC_CREATE, DOC_EDIT);

  private static final ResourceManifest READ_MANIFEST = DocsCapability.Manifests.READ;

  private static final ResourceManifest WRITE_MANIFEST = DocsCapability.Manifests.WRITE;

  private DocsTools() {
    throw new AssertionError("No instances");
  }

  /**
   * 造出全套 Docs 工具（七个工具共享同一个引擎/访问层，因此共享同一份挂载视图）。
   *
   * @param registry 工作区注册表（唯一解析点；由装配方注入）
   */
  public static List<AgentTool> all(WorkspaceRegistry registry) {
    Objects.requireNonNull(registry, "registry");
    DocsAccess access = new DocsAccess(registry);
    return List.of(
        list(access),
        read(access),
        query(access),
        append(access),
        voidEntry(access),
        create(access),
        edit(access));
  }

  // ── 读工具 ──────────────────────────────────────────────────────────────────────────────────

  /** {@code doc_list}：列出某个文档根里的东西（{@code LOG}：地址 + 可见条数；{@code FILE}：路径）。 */
  static AgentTool list(DocsAccess access) {
    Map<String, Object> schema =
        objectSchema(
            Map.of(
                "mount",
                Map.of(
                    "type",
                    "string",
                    "description",
                    "文档根（虚拟挂载名，如 workspace / docs）；缺省 = 列出你名下的全部文档根")));
    return new DocTool(
        DOC_LIST,
        "列出文档根里的内容：追加型（LOG）根列出地址与你可看见的条数；文件型（FILE）根列出路径（只列直接子项）。",
        schema,
        READ_MANIFEST,
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        context -> {
          String mount = DocArguments.text(context.arguments(), "mount");
          List<Mount> mounts = access.mounts(context);
          if (mount.isEmpty()) {
            if (mounts.isEmpty()) {
              return ToolResult.ok("（你名下没有文档根；文档能力由工作区挂载授予）");
            }
            StringBuilder text = new StringBuilder();
            for (Mount item : mounts) {
              text.append("- ")
                  .append(item.name().value())
                  .append(" 纪律=")
                  .append(item.kind())
                  .append(" 能力=")
                  .append(new java.util.TreeSet<>(item.capabilities()))
                  .append('\n');
            }
            return ToolResult.ok("文档根 " + mounts.size() + " 个：\n" + text);
          }
          List<String> entries = access.list(context, mount);
          // 坏数据现扫一次（见 DocsEngine.broken 的代价说明）——一次调用只扫这一遍，空结果分支也复用它
          List<BrokenDoc> broken = access.broken(context);
          if (entries.isEmpty()) {
            return ToolResult.ok(emptyListing(access, context, mount, broken));
          }
          StringBuilder text = new StringBuilder("共 " + entries.size() + " 项：\n");
          for (String entry : entries) {
            text.append("- ").append(entry).append('\n');
          }
          return ToolResult.ok(withBroken(text.toString(), broken));
        });
  }

  /** {@code doc_read}：读文档（{@code LOG} 按 id 或地址；{@code FILE} 按路径 + 可选行区间）。 */
  static AgentTool read(DocsAccess access) {
    Map<String, Object> schema =
        objectSchema(
            Map.of(
                "mount", requiredText("虚拟路径或文档根（如 workspace 或 docs/notes.md）"),
                "id", Map.of("type", "string", "description", "LOG 条目 id（<地址>#<序号>）"),
                "address", Map.of("type", "string", "description", "LOG 地址（读该地址下你可看见的条目）"),
                "lines", Map.of("type", "string", "description", "FILE 行区间，如 \"10,40\"；缺省 = 全文")));
    return new DocTool(
        DOC_READ,
        "读一个文档：LOG 根按 id 或 address 读条目；FILE 根按路径读文本（可用 lines 只取一段，省 token）。"
            + "\"取不到\"不区分\"不存在\"与\"不可见\"。",
        schema,
        READ_MANIFEST,
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        context -> {
          String path = DocArguments.required(context.arguments(), "mount");
          DocsAccess.ReadOutcome outcome =
              access.read(
                  context,
                  path,
                  DocArguments.text(context.arguments(), "id"),
                  DocArguments.text(context.arguments(), "address"),
                  DocArguments.text(context.arguments(), "lines"));
          return switch (outcome) {
            case DocsAccess.ReadOutcome.Text text ->
                ToolResult.ok(
                    withBroken(
                        "虚拟路径="
                            + text.result().path()
                            + " 行="
                            + (text.result().rangeLabel().orElse("1-" + text.result().totalLines()))
                            + " 共 "
                            + text.result().totalLines()
                            + " 行\n"
                            + text.result().content(),
                        text.result().broken()));
            case DocsAccess.ReadOutcome.Entries entries -> {
              StringBuilder rendered = new StringBuilder();
              for (DocEntry entry : entries.entries()) {
                rendered.append(renderEntry(entry)).append('\n');
              }
              yield ToolResult.ok(
                  withBroken(rendered.toString().stripTrailing(), access.broken(context)));
            }
          };
        });
  }

  /** {@code doc_query}：按时刻查（{@code LOG}；{@code limit} 必填、先排后截）。 */
  static AgentTool query(DocsAccess access) {
    Map<String, Object> schema =
        objectSchema(
            Map.of(
                "mount", requiredText("文档根（LOG 纪律的虚拟挂载名）"),
                "tick", Map.of("type", "integer", "description", "精确时刻（与 from/to 互斥）"),
                "from", Map.of("type", "integer", "description", "闭区间下界 tick"),
                "to", Map.of("type", "integer", "description", "闭区间上界 tick"),
                "limit", Map.of("type", "integer", "description", "返回条数上限（必填，> 0；先排后截）")),
            List.of("mount", "limit"));
    return new DocTool(
        DOC_QUERY,
        "按时刻查询追加型（LOG）文档：tick 精确 与 [from,to] 闭区间互斥；limit 必填。"
            + "结果按 tick 降序、同 tick 按（地址, 序号数值）升序，先排序后截断。",
        schema,
        READ_MANIFEST,
        ToolSpec.level(AccessToken.DEFAULT, false, false),
        context -> {
          String mount = DocArguments.required(context.arguments(), "mount");
          QuerySpec spec = querySpec(context);
          List<DocEntry> entries = access.query(context, mount, spec);
          if (entries.isEmpty()) {
            return ToolResult.ok("（没有命中的条目）" + brokenSuffix(access.broken(context)));
          }
          StringBuilder text = new StringBuilder("命中 " + entries.size() + " 条：\n");
          for (DocEntry entry : entries) {
            text.append(renderEntry(entry)).append('\n');
          }
          return ToolResult.ok(withBroken(text.toString().stripTrailing(), access.broken(context)));
        });
  }

  // ── 写工具（destructive） ─────────────────────────────────────────────────────────────────────

  /** {@code doc_append}：追加一条（{@code LOG}）。 */
  static AgentTool append(DocsAccess access) {
    // 12 个属性：Map.of 的上限是 10 对，这里用 LinkedHashMap 逐项 put（顺序稳定，也便于日后增删字段）
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("mount", requiredText("文档根（LOG 纪律的虚拟挂载名）"));
    properties.put("address", requiredText("文档地址（由你定义的不透明字符串：非空、无空白、无 '#'）"));
    properties.put("tick", Map.of("type", "integer", "description", "时刻（查询与排序的主键）"));
    properties.put("key", Map.of("type", "string", "description", "正文的键（如事件类型）"));
    properties.put("value", Map.of("description", "正文的值（原样进出，不解析）"));
    properties.put(
        "tags",
        Map.of("type", "array", "items", textItem(), "description", "可见性：显式指派给谁（\"self\" 表示你自己）"));
    properties.put(
        "affiliations",
        Map.of("type", "array", "items", textItem(), "description", "可见性：按归属可见（与观看者归属求交）"));
    properties.put("owner", Map.of("type", "string", "description", "可见性：单决策人私有（\"self\" 表示你自己）"));
    properties.put("note", Map.of("type", "string", "description", "备注"));
    properties.put("fields", Map.of("type", "object", "description", "自定义字段区（不参与身份/可见性/排序）"));
    properties.put("at", Map.of("type", "string", "description", "世界坐标等注入值（读回原样）"));
    Map<String, Object> schema = objectSchema(properties, List.of("mount", "address", "tick"));
    return new DocTool(
        DOC_APPEND,
        "往追加型（LOG）文档里追加一条（永不覆盖、永不删除；同地址同载荷重复写会命中同一条 id）。" + "这是改变盘上数据的操作。",
        schema,
        WRITE_MANIFEST,
        ToolSpec.level(AccessToken.DEFAULT, false, true),
        context -> {
          String mount = DocArguments.required(context.arguments(), "mount");
          DocEntry entry = access.append(context, mount, selfExpanded(context));
          return ToolResult.ok(
              "已追加 虚拟路径="
                  + mount
                  + " id="
                  + entry.id()
                  + " 状态="
                  + entry.status()
                  + " tick="
                  + entry.tick()
                  + brokenSuffix(access.broken(context)));
        });
  }

  /** {@code doc_void}：作废一条（追加 {@code VOIDED}，原记录不动）。 */
  static AgentTool voidEntry(DocsAccess access) {
    Map<String, Object> schema =
        objectSchema(
            Map.of(
                "mount", requiredText("文档根（LOG 纪律的虚拟挂载名）"),
                "id", requiredText("要作废的条目 id（<地址>#<序号>）")),
            List.of("mount", "id"));
    return new DocTool(
        DOC_VOID,
        "作废一条追加型（LOG）文档条目：不改原记录，而是<b>再追加</b>一条 VOIDED 记录（留痕、可重放）。" + "这是改变盘上数据的操作。",
        schema,
        WRITE_MANIFEST,
        ToolSpec.level(AccessToken.DEFAULT, false, true),
        context -> {
          String mount = DocArguments.required(context.arguments(), "mount");
          String id = DocArguments.required(context.arguments(), "id");
          DocEntry voided = access.voidEntry(context, mount, id);
          return ToolResult.ok(
              "已作废 原条目="
                  + id
                  + " 作废记录 id="
                  + voided.id()
                  + "（原记录保留在盘上）"
                  + brokenSuffix(access.broken(context)));
        });
  }

  /** {@code doc_create}：原子创建（{@code FILE}，撞名拒）。 */
  static AgentTool create(DocsAccess access) {
    Map<String, Object> schema =
        objectSchema(
            Map.of(
                "mount", requiredText("虚拟路径（<挂载名>/<相对路径>，如 workspace/notes/2026-10-13.md）"),
                "text", Map.of("type", "string", "description", "文件内容（一次落定）")),
            List.of("mount", "text"));
    return new DocTool(
        DOC_CREATE,
        "在文件型（FILE）文档根里原子创建一个文件：撞名即拒（不覆盖），内容一次落定——盘上不会出现写一半的文档。" + "这是改变盘上数据的操作。",
        schema,
        WRITE_MANIFEST,
        ToolSpec.level(AccessToken.DEFAULT, false, true),
        context -> {
          String path = DocArguments.required(context.arguments(), "mount");
          String text = DocArguments.text(context.arguments(), "text");
          DocFile created = access.create(context, path, text);
          return ToolResult.ok(
              "已创建 虚拟路径="
                  + created.virtualPath()
                  + " 行数="
                  + created.lines()
                  + " 字节="
                  + created.bytes());
        });
  }

  /** {@code doc_edit}：行寻址编辑（{@code FILE}，多项全成或全不成）。 */
  static AgentTool edit(DocsAccess access) {
    Map<String, Object> schema =
        objectSchema(
            Map.of(
                "mount",
                requiredText("虚拟路径（<挂载名>/<相对路径>）"),
                "ops",
                Map.of(
                    "type",
                    "array",
                    "description",
                    "编辑操作（一次多个，全成或全不成）。每项：{op, anchor|line|from+to, text?, expect?}",
                    "items",
                    Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                            "op",
                                Map.of(
                                    "type",
                                    "string",
                                    "enum",
                                    List.of("replace", "insertAfter", "insertBefore", "delete")),
                            "anchor",
                                Map.of("type", "string", "description", "唯一精确锚点（整行逐字相等；不命中/不唯一即拒）"),
                            "line", Map.of("type", "integer", "description", "单行行号（从 1 起）"),
                            "from", Map.of("type", "integer", "description", "行区间起点（从 1 起，闭区间）"),
                            "to", Map.of("type", "integer", "description", "行区间终点（从 1 起，闭区间）"),
                            "text", Map.of("type", "string", "description", "新文本（delete 不给）"),
                            "expect",
                                Map.of("type", "string", "description", "前置断言：寻址到的原文必须与它逐字相等"))))),
            List.of("mount", "ops"));
    return new DocTool(
        DOC_EDIT,
        "在文件型（FILE）文档根里就地编辑：按行号/行区间/唯一精确锚点寻址，一次可给多个操作（全成或全不成）。"
            + "锚点不命中或不唯一、expect 不符、行号越界都会整体拒绝且<b>盘上不动一个字</b>。这是改变盘上数据的操作。",
        schema,
        WRITE_MANIFEST,
        ToolSpec.level(AccessToken.DEFAULT, false, true),
        context -> {
          String path = DocArguments.required(context.arguments(), "mount");
          List<EditOp> ops = DocEditOps.parse(context.arguments().get("ops"));
          DocFile edited = access.edit(context, path, ops);
          return ToolResult.ok(
              "已编辑 虚拟路径="
                  + edited.virtualPath()
                  + " 操作数="
                  + ops.size()
                  + " 行数="
                  + edited.lines()
                  + " 字节="
                  + edited.bytes());
        });
  }

  // ── 内部：参数与渲染 ─────────────────────────────────────────────────────────────────────────

  /**
   * 把 {@code tags}/{@code owner} 里的 {@code self}/{@code @self} 占位符换成宿主绑定的实例 id。
   *
   * <p><b>逐项精确匹配，不做子串替换</b>：{@code "selfish"} 不是占位符——把 {@code self} 当子串替换会把它改成 {@code
   * "<实例id>ish"}，那是一次静默的错误可见性指派（正好是文档面最不该出错的地方）。
   */
  private static Map<String, Object> selfExpanded(ToolContext context) {
    Map<String, Object> arguments = new LinkedHashMap<>(context.arguments());
    String self = context.identity().instanceId();
    Object tags = arguments.get("tags");
    if (tags instanceof List<?> list) {
      List<Object> expanded = new ArrayList<>(list.size());
      for (Object item : list) {
        expanded.add(isSelf(item) ? self : item);
      }
      arguments.put("tags", expanded);
    } else if (tags instanceof String text) {
      List<String> expanded = new ArrayList<>();
      for (String piece : text.split(",")) {
        String token = piece.strip();
        if (!token.isEmpty()) {
          expanded.add(isSelf(token) ? self : token);
        }
      }
      arguments.put("tags", expanded);
    }
    Object owner = arguments.get("owner");
    if (isSelf(owner)) {
      arguments.put("owner", self);
    }
    return arguments;
  }

  private static boolean isSelf(Object value) {
    return value != null
        && DocArguments.SELF_PLACEHOLDERS.contains(
            String.valueOf(value).strip().toLowerCase(java.util.Locale.ROOT));
  }

  private static QuerySpec querySpec(ToolContext context) {
    Map<String, Object> arguments = context.arguments();
    int limit =
        DocArguments.intValue(arguments, "limit")
            .orElseThrow(() -> DocException.invalid("limit 为必填参数且必须大于 0（本契约没有\"不限量\"）"));
    java.util.Optional<Long> tick = DocArguments.longValue(arguments, "tick");
    java.util.Optional<Long> from = DocArguments.longValue(arguments, "from");
    java.util.Optional<Long> to = DocArguments.longValue(arguments, "to");
    return new QuerySpec(tick, from, to, limit);
  }

  /** 条目的一行渲染（只含虚拟地址与用户可见字段，不含物理落点）。 */
  private static String renderEntry(DocEntry entry) {
    StringBuilder text = new StringBuilder();
    text.append("- id=").append(entry.id()).append(" tick=").append(entry.tick());
    text.append(" 状态=").append(entry.status());
    if (!entry.key().isEmpty()) {
      text.append(" key=").append(oneLine(entry.key()));
    }
    text.append(" 可见轴=")
        .append("owner=")
        .append(entry.owner().orElse("-"))
        .append(" tags=")
        .append(new java.util.TreeSet<>(entry.tags()))
        .append(" affiliations=")
        .append(new java.util.TreeSet<>(entry.affiliations()));
    if (!entry.fields().isEmpty()) {
      text.append(" fields=").append(new java.util.TreeMap<>(entry.fields()));
    }
    entry.note().ifPresent(note -> text.append(" note=").append(oneLine(note)));
    entry.at().ifPresent(at -> text.append(" at=").append(oneLine(at)));
    text.append("\n  正文=").append(String.valueOf(entry.value()));
    return text.toString();
  }

  private static String oneLine(String text) {
    return text.replace("\n", "\\n").replace("\r", "\\r");
  }

  private static String emptyListing(
      DocsAccess access, ToolContext context, String mount, List<BrokenDoc> broken) {
    List<Mount> mounts = access.mounts(context);
    for (Mount item : mounts) {
      if (item.name().value().equals(mount.strip())) {
        if (item.kind() == Kind.LOG) {
          return "（这个文档根里没有你能看见的文档地址）" + brokenSuffix(broken);
        }
        return "（这个文档根里没有条目）" + brokenSuffix(broken);
      }
    }
    return "（没有可列出的内容）" + brokenSuffix(broken);
  }

  /** 坏数据披露（设计 §5.3：不静默；<b>不抛</b>——一条坏行不该让整个调用失败）。 */
  private static String withBroken(String text, List<BrokenDoc> broken) {
    return text + brokenSuffix(broken);
  }

  private static String brokenSuffix(List<BrokenDoc> broken) {
    if (broken.isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder("\n⚠ 有 " + broken.size() + " 处坏数据（已跳过并继续，未静默）：\n");
    for (BrokenDoc item : broken) {
      text.append("- ").append(item.label()).append('\n');
    }
    return text.toString().stripTrailing();
  }

  private static Map<String, Object> requiredText(String description) {
    return Map.of("type", "string", "description", description);
  }

  private static Map<String, Object> textItem() {
    return Map.of("type", "string");
  }

  private static Map<String, Object> objectSchema(Map<String, Object> properties) {
    return objectSchema(properties, List.of());
  }

  private static Map<String, Object> objectSchema(
      Map<String, Object> properties, List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }
    return schema;
  }

  /**
   * 共同骨架：把 {@link DocException} 转成带稳定错误码的 {@link ToolResult}。
   *
   * <p><b>权限拒绝不经这里转</b>：{@code require} 抛的 {@code ResourceDeniedException} 由唯一入口 {@code
   * ToolCallAuthorizer} 接住并转码（工具不得自裁）——本类只处理文档语义自己的失败。
   */
  private static final class DocTool implements AgentTool {

    private final String name;
    private final String description;
    private final Map<String, Object> schema;
    private final ResourceManifest manifest;
    private final ToolSpec spec;
    private final Function<ToolContext, ToolResult> body;

    DocTool(
        String name,
        String description,
        Map<String, Object> schema,
        ResourceManifest manifest,
        ToolSpec spec,
        Function<ToolContext, ToolResult> body) {
      this.name = name;
      this.description = description;
      this.schema = schema;
      this.manifest = manifest;
      this.spec = spec;
      this.body = body;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String description() {
      return description;
    }

    /**
     * EI_EXPOSE_REP 抑制：{@code schema} 是 {@code Map.of}/{@code List.of}/字符串组成的<b>深不可变</b>树（与 {@code
     * SkillTools}/{@code WorkspaceTools} 同款理由），装配期定型后不再改写。
     */
    @SuppressFBWarnings("EI_EXPOSE_REP")
    @Override
    public Map<String, Object> jsonSchema() {
      return schema;
    }

    @Override
    public ToolSpec spec() {
      return spec;
    }

    @Override
    public ResourceManifest resources() {
      return manifest;
    }

    @Override
    public ToolResult execute(ToolContext context) {
      try {
        return body.apply(context);
      } catch (DocException e) {
        return ToolResult.error(e.code(), e.getMessage());
      } catch (io.mosire.workspace.WorkspaceException e) {
        // 工具边界也要接住工作区侧的失败：否则它会以未检查异常穿出工具面（MCP 面会把它变成一次协议错误，
        // 模型看到的东西与"被拒"完全不同）。转码表与引擎共用一处，两个入口不会说出两个码。
        DocException translated = DocsAccess.translate(e);
        return ToolResult.error(translated.code(), translated.getMessage());
      }
    }
  }
}
