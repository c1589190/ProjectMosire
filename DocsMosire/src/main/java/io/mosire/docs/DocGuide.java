package io.mosire.docs;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.workspace.Kind;
import io.mosire.workspace.Mount;
import io.mosire.workspace.WorkspaceException;
import io.mosire.workspace.WorkspaceRegistry;
import io.mosire.workspace.WorkspaceTools;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code doc_guide} 的内容渲染（设计 §5.11）：{@code doc_*} 工具清单与用法 + 两种纪律 + 在调用者自己的哪些挂载上可读/可写 + 路径形态 + 常用错误码
 * + 指路"不知道挂载名就先调 {@code workspace_info}"。
 *
 * <p><b>为什么文案单独一个文件</b>：工具骨架（schema / spec / manifest / 异常转码）是判定相关代码，面向模型的文案会随用户反馈反复润色。
 * 分开之后"改措辞"碰不到骨架。
 *
 * <p><b>不漂移（本设计写死的硬性质）</b>：① 工具清单<b>从真实工具对象现读</b>（{@link DocsTools#all} 的 {@code name()} 与 {@code
 * description()}，必需参数从各自的 schema 的 {@code required} 现取），家族新增一个 {@code doc_*} 工具，这里自动跟着变——
 * 所以清单里<b>包含 {@code doc_guide} 自己</b>（它就是这个家族的一个工具），"家族工具名集合 == guide 列出的名字集合"因此是<b>全等</b>关系，
 * 不需要任何减法；② 纪律与工具的对应也<b>从实际注册的工具求交</b>得到，不是抄一段可能过期的句子——将来新加的工具若没被归入两种纪律，输出会如实 单列一行（而不是假装它不存在）。
 *
 * <p><b>只输出调用者自己的东西</b>：挂载清单取 {@link DocsAccess#mounts}（按 {@code user} 索引的切片，与唯一解析点同一索引），
 * 可读/可写按调用者自己的 {@code docs} / {@code docs-edit} 表态算——"换一个身份调用 ⇒ 看不到别人的挂载"因此是结构性的。
 *
 * <p><b>绝不输出物理落点</b>（A7/G4）：{@link Mount#dir()} 只喂给权限面判定，从不进文案——本类输出里出现的路径一律是虚拟路径。
 *
 * <p><b>不做第二套判定</b>：可读/可写<b>调的就是引擎那一份实现</b>（{@link
 * DocsEngine#faceCovers}），本类只做参数绑定——它只<b>读</b>调用者的表态， 既不放行也不拒绝任何一次真实访问：放行/拒绝仍然只发生在 {@link
 * DocsEngine} 与工具调用唯一入口。
 */
final class DocGuide {

  /** {@code FILE} 纪律（整文件）的工具（引生产常量，不抄字面量）。 */
  private static final Set<String> FILE_TOOLS =
      Set.of(DocsTools.DOC_CREATE, DocsTools.DOC_READ, DocsTools.DOC_EDIT);

  /** {@code LOG} 纪律（追加型）的工具。 */
  private static final Set<String> LOG_TOOLS =
      Set.of(DocsTools.DOC_APPEND, DocsTools.DOC_VOID, DocsTools.DOC_QUERY, DocsTools.DOC_LIST);

  /** 两种纪律都能用的读类工具（{@code FILE} 按路径、{@code LOG} 按地址或条目 id）。 */
  private static final Set<String> BOTH_DISCIPLINES =
      Set.of(DocsTools.DOC_READ, DocsTools.DOC_LIST);

  private DocGuide() {
    throw new AssertionError("No instances");
  }

  /** 渲染整段回答（六段，见类注释）。 */
  static String render(WorkspaceRegistry registry, DocsAccess access, ToolContext context) {
    List<AgentTool> family = DocsTools.all(registry);
    Set<String> registered = new TreeSet<>();
    StringBuilder listing = new StringBuilder();
    for (AgentTool tool : family) {
      registered.add(tool.name());
      listing
          .append("- ")
          .append(tool.name())
          .append("：")
          .append(flat(tool.description()))
          .append("（schema 必需参数：")
          .append(requiredArguments(tool))
          .append("）\n");
    }
    List<Mount> mounts = access.mounts(context);
    return new StringBuilder()
        .append("【工具】")
        .append(family.size())
        .append(" 个（从当前注册的工具现读；名字 / 一句话 / schema 必需参数）：\n")
        .append(listing)
        .append(disciplineSection(registered))
        .append(mountSection(context, mounts))
        .append("【路径形态】<挂载名>/<相对路径>，例：")
        .append(exampleOf(mounts))
        .append("（第一段必须是你的挂载名；不写物理目录，不写开头的 '/' 与 '..' 段）\n")
        .append(answerSection())
        .append("【不知道挂载名？】先调 ")
        .append(WorkspaceTools.WORKSPACE_INFO)
        .append("：它列出你自己的挂载名（虚拟名），不用拿工具名去猜。\n")
        .toString();
  }

  /** 两种纪律与工具的对应：<b>按实际注册的工具求交</b>，家族新增工具不会被漏在外。 */
  private static String disciplineSection(Set<String> registered) {
    Set<String> fileTools = retain(registered, FILE_TOOLS);
    Set<String> logTools = retain(registered, LOG_TOOLS);
    StringBuilder text = new StringBuilder("【两种纪律】纪律写在挂载上（见下一段），决定这块地能怎么动：\n");
    text.append("- ")
        .append(Kind.FILE)
        .append("（整文件：按虚拟路径读写整个文件）：")
        .append(join(fileTools))
        .append('\n');
    text.append("- ")
        .append(Kind.LOG)
        .append("（追加型：只能追加与作废，条目不可变、永不就地改）：")
        .append(join(logTools))
        .append('\n');
    text.append("- 两种纪律都能用：")
        .append(join(retain(registered, BOTH_DISCIPLINES)))
        .append("（FILE 按路径，LOG 按地址或条目 id）\n");
    Set<String> unclassified = new TreeSet<>(registered);
    unclassified.removeAll(FILE_TOOLS);
    unclassified.removeAll(LOG_TOOLS);
    if (!unclassified.isEmpty()) {
      // 将来新加的家族工具没被归入上面两行时如实单列：既不假设它存在，也不因为"我不认识它"就把它从清单里抹掉
      text.append("- 还没归入上面两行的工具（以它自己的说明为准）：").append(join(unclassified)).append('\n');
    }
    text.append("- 纪律不符的调用被具名拒：").append(DocException.DOC_INVALID).append("（不静默降级、不替你换一种写法）\n");
    return text.toString();
  }

  /** 在调用者自己的哪些挂载上可读/可写（按他的 {@code docs} / {@code docs-edit} 表态算）。 */
  private static String mountSection(ToolContext context, List<Mount> mounts) {
    if (mounts.isEmpty()) {
      return "【在你的挂载上】0 个：你名下还没有挂载——先让上级给你挂一块（见 "
          + WorkspaceTools.WORKSPACE_INFO
          + "），否则 doc_* 会用 "
          + DocException.RESOURCE_DENIED
          + " 回答你。\n";
    }
    StringBuilder text =
        new StringBuilder("【在你的挂载上】").append(mounts.size()).append(" 个（只有你自己的；换一个身份看不到别人的）：\n");
    for (Mount mount : mounts) {
      boolean readable = mountCovers(context, DocNamespaces.DOCS, mount);
      boolean writable = mountCovers(context, DocNamespaces.DOCS_EDIT, mount);
      text.append("- ")
          .append(mount.name().value())
          .append("：可读 ")
          .append(flag(readable))
          .append(" 可写 ")
          .append(flag(writable))
          .append("（")
          .append(reason(readable, writable))
          .append("） 纪律=")
          .append(mount.kind());
      if (mount.kind() != Kind.FILE && mount.kind() != Kind.LOG) {
        // 这种纪律不在 docs 工具的处理范围内（引擎会以 DOC_INVALID 具名拒）——说清楚，别让模型以为是权限问题
        text.append("；这种纪律上 doc_* 用不了（会被 ").append(DocException.DOC_INVALID).append(" 具名拒）");
      }
      text.append('\n');
    }
    return text.toString();
  }

  /** 常用错误码对照（全部引生产常量：码改了，这里跟着改）。 */
  private static String answerSection() {
    return "【常见回答】\n"
        + "- "
        + DocException.RESOURCE_DENIED
        + "：够不着——这个挂载不是你的，或你在 "
        + DocNamespaces.DOCS
        + " / "
        + DocNamespaces.DOCS_EDIT
        + " 面上的表态没覆盖它（未表态 = 什么都看不见、也改不了）。\n"
        + "- "
        + DocException.DOC_NOT_FOUND
        + "：面内不存在，或存在但不在你的可见面内——两者是同一个回答（不泄露存在性），别当成\"再试一次就好\"。\n"
        + "- "
        + WorkspaceException.WORKSPACE_NOT_FOUND
        + "：挂载名不对，或这个挂载不是你的（工作区侧的原生码；在 doc_* 面上按转码表回答成 "
        + DocException.RESOURCE_DENIED
        + "）。\n"
        + "- "
        + DocException.DOC_INVALID
        + "：纪律不符（B9）——在 LOG 挂载上创建/编辑文件、在 FILE 挂载上追加/作废，都具名拒。\n"
        + "- "
        + DocException.DOC_EXISTS
        + "：撞名/撞 id（创建时路径已存在、追加时 id 已被别的载荷占用）。\n"
        + "- "
        + DocException.DOC_WRITE_FAILED
        + "：写失败——盘上不动一个字。\n";
  }

  /**
   * 这个挂载整体够不够得着。guide 回答的粒度就是"<b>整块挂载</b>"，所以落点取挂载根（两个参数同一个值）——判据本体不在这里，见 {@link
   * DocsEngine#faceCovers}。
   *
   * <p><b>本方法里没有判定式</b>：{@code allows} / {@code covers} / {@code declared} 一个字都不在本类出现（结构上不可能与引擎分叉），
   * 这里只做参数绑定。
   */
  private static boolean mountCovers(ToolContext context, String namespace, Mount mount) {
    return DocsEngine.faceCovers(context.permissions(), namespace, mount.dir(), mount.dir());
  }

  /** 工具 schema 里的必需参数（现读，不猜）：没有就写"无"。 */
  private static String requiredArguments(AgentTool tool) {
    Object required = tool.jsonSchema().get("required");
    if (!(required instanceof List<?> list) || list.isEmpty()) {
      return "无";
    }
    StringBuilder text = new StringBuilder();
    for (Object item : list) {
      if (text.length() > 0) {
        text.append(", ");
      }
      text.append(item);
    }
    return text.toString();
  }

  /**
   * 路径形态示例：优先用调用者自己的一个 {@code FILE} 挂载名——"第一段就是挂载名"这件事必须用真名字示范，编一个出来正好会重演"拿工具名当挂载名猜" 的事故。零挂载时用占位符。
   */
  private static String exampleOf(List<Mount> mounts) {
    for (Mount mount : mounts) {
      if (mount.kind() == Kind.FILE) {
        return mount.name().value() + "/notes/hello.md";
      }
    }
    return mounts.isEmpty()
        ? "<挂载名>/notes/hello.md"
        : mounts.get(0).name().value() + "/notes/hello.md";
  }

  /** 两个布尔值的可读渲染（✅ / ❌）。 */
  private static String flag(boolean value) {
    return value ? "✅" : "❌";
  }

  /** "为什么是这个答案"的一句话（只说事实，不替调用者猜意图）。 */
  private static String reason(boolean readable, boolean writable) {
    if (readable && writable) {
      return "你的 " + DocNamespaces.DOCS + " / " + DocNamespaces.DOCS_EDIT + " 面都覆盖它";
    }
    if (readable) {
      return "你的 " + DocNamespaces.DOCS + " 面覆盖它，" + DocNamespaces.DOCS_EDIT + " 面没覆盖：只读";
    }
    if (writable) {
      return "你的 " + DocNamespaces.DOCS_EDIT + " 面覆盖它，" + DocNamespaces.DOCS + " 面没覆盖";
    }
    return DocNamespaces.DOCS + " / " + DocNamespaces.DOCS_EDIT + " 面都没覆盖它（未表态 = 什么都看不见、也改不了）";
  }

  /** 求交（保持稳定次序）。 */
  private static Set<String> retain(Set<String> registered, Set<String> candidates) {
    Set<String> kept = new TreeSet<>(registered);
    kept.retainAll(candidates);
    return kept;
  }

  private static String join(Set<String> names) {
    return names.isEmpty() ? "（无）" : String.join(" / ", names);
  }

  /** 工具描述是单行契约；渲染成一行（换行会破坏"一条一行"的清单形状）。 */
  private static String flat(String description) {
    return description == null ? "" : description.replace('\n', ' ').replace('\r', ' ').strip();
  }
}
