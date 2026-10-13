package io.mosire.docs;

import io.mosire.agentlib.tool.ToolCallAuthorizer;

/**
 * Docs 操作的失败（稳定错误码 + 面向模型的文案），错误码口径见设计 §5.6。
 *
 * <p><b>为什么用异常而不是返回值</b>：一次文档操作要穿过"纪律判定 → 准入 require → 解析 → IO"四段，任何一段的"拒"都必须<b>立刻</b> 截断——返回
 * {@code null}/空表会让某个调用点顺手"当没有"继续跑，而"不可见"与"不存在"恰恰必须在工具层回答成<b>同一句话</b>（见 {@link
 * #DOC_NOT_FOUND}）。异常让"忘记处理"变成显式失败，而不是静默放行。
 *
 * <p><b>三个语义写死的码</b>：
 *
 * <ul>
 *   <li>{@link #RESOURCE_DENIED}：<b>范围外</b>（未表态 / 前缀盖不住 / 纪律外的写口）——值与 {@link
 *       ToolCallAuthorizer#RESOURCE_DENIED} 同字面。范围外是"我够不着这块"，不是"这块不存在"；
 *   <li>{@link #DOC_NOT_FOUND}：<b>面内</b>不存在（与"不可见"在工具层同回答，不泄露存在性）。★ 旧文档 {@code 设计-Skill运行时能力.md} §3
 *       把这条写反了（设计 §10.2 D1），本文按代码口径：范围外与面内不存在<b>分码</b>，而"不可见"归到 {@code DOC_NOT_FOUND} 那一侧；
 *   <li>{@link #DOC_INVALID}：纪律不符（B9）——{@code LOG} 挂载上跑文件编辑、{@code FILE}
 *       挂载上跑追加/作废，一律<b>具名拒</b>，不静默降级。
 * </ul>
 *
 * <p><b>文案纪律</b>：本异常的 {@link #getMessage()} 会直接进模型可见的工具结果（以及事件库＝持久面），因此<b>不得含物理路径</b>—— 物理落点只进
 * SLF4J 日志。
 */
public final class DocException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 入参/地址/路径/行区间非法（形态问题，跟权限与存在性无关）。 */
  public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";

  /** 范围外或未表态：由唯一入口转码，值同 {@link ToolCallAuthorizer#RESOURCE_DENIED}。 */
  public static final String RESOURCE_DENIED = ToolCallAuthorizer.RESOURCE_DENIED;

  /** <b>面内</b>不存在（与"不可见"同回答；见类 javadoc 的三条语义）。 */
  public static final String DOC_NOT_FOUND = "DOC_NOT_FOUND";

  /** 撞名/撞 id：{@code FILE} 的路径已存在、{@code LOG} 的 id 已被不同载荷占用（写入侧就拒，B6）。 */
  public static final String DOC_EXISTS = "DOC_EXISTS";

  /** 纪律不符（B9）：越纪律的操作具名拒。 */
  public static final String DOC_INVALID = "DOC_INVALID";

  /** 读失败（IO 层）：文件读不出来、编码不合法等。 */
  public static final String DOC_READ_FAILED = "DOC_READ_FAILED";

  /** 写失败（IO 层）：临时文件写不出、原子替换失败等——<b>失败时盘上不动一个字</b>（B8）。 */
  public static final String DOC_WRITE_FAILED = "DOC_WRITE_FAILED";

  /** 重扫失败：日志/文件列表刷不出来（坏行、坏文件之外的结构性问题）。 */
  public static final String DOC_RELOAD_FAILED = "DOC_RELOAD_FAILED";

  private final String code;

  public DocException(String code, String message) {
    super(message);
    this.code = requireCode(code);
  }

  public DocException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = requireCode(code);
  }

  /** 稳定错误码（工具边界拿它构造 {@code ToolResult.error}）。 */
  public String code() {
    return code;
  }

  /** 入参非法（形态问题）。 */
  public static DocException invalid(String message) {
    return new DocException(INVALID_ARGUMENTS, message);
  }

  /** 范围外/未表态（工具边界的统一转码）。 */
  public static DocException denied(String message) {
    return new DocException(RESOURCE_DENIED, message);
  }

  /** 面内不存在（工具层对"不可见"也用这个码，两者同回答）。 */
  public static DocException notFound(String message) {
    return new DocException(DOC_NOT_FOUND, message);
  }

  /** 撞名/撞 id。 */
  public static DocException exists(String message) {
    return new DocException(DOC_EXISTS, message);
  }

  /** 纪律不符（B9）。 */
  public static DocException discipline(String message) {
    return new DocException(DOC_INVALID, message);
  }

  /** 读失败。 */
  public static DocException readFailed(String message, Throwable cause) {
    return new DocException(DOC_READ_FAILED, message, cause);
  }

  /** 写失败（失败时盘上不动）。 */
  public static DocException writeFailed(String message, Throwable cause) {
    return new DocException(DOC_WRITE_FAILED, message, cause);
  }

  /** 重扫失败。 */
  public static DocException reloadFailed(String message, Throwable cause) {
    return new DocException(DOC_RELOAD_FAILED, message, cause);
  }

  private static String requireCode(String code) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("Docs 异常的 code 不能为空");
    }
    return code;
  }
}
