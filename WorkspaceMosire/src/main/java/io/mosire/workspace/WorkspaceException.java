package io.mosire.workspace;

import io.mosire.agentlib.tool.ToolCallAuthorizer;

/**
 * 工作区操作的失败（稳定错误码 + 面向模型的文案）。
 *
 * <p><b>为什么用异常而不是返回值</b>：解析点、挂载守卫、resolver 三处都可能拒，而"拒"必须<b>立刻</b>截断流程——返回 {@code
 * null}/空值会让某个调用点顺手"当没挂载"继续跑，那正是 M5 明令禁止的"回落"。异常让"忘记处理"变成编译/运行期 显式失败，而不是静默放行。
 *
 * <p><b>错误码只有这几个，且有两个语义写死</b>：
 *
 * <ul>
 *   <li>{@link #WORKSPACE_NOT_FOUND}：<b>"不存在"与"不可见"同码</b>（设计 §4.5）——判据必须先于存在性，否则工具面会退化成
 *       "挂载存在性探测器"。挂载不存在、挂载被卸载、调用者不是该挂载的使用者，都走这个码；
 *   <li>{@link #RESOURCE_DENIED}：能力不匹配、解析越界、守卫（M/G 组）拒——值与 {@link
 *       ToolCallAuthorizer#RESOURCE_DENIED} 同字面（工具边界把它转成同一个工具错误码，模型看到的仍是既有口径）。
 * </ul>
 *
 * <p><b>文案纪律（G4/A7）</b>：本异常的 {@link #getMessage()} 可能直接进模型可见的工具结果，因此<b>不得含物理路径</b>—— 物理落点只进 SLF4J
 * 日志与审计事件（{@link WorkspaceEvent#detail()}）。
 */
public final class WorkspaceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 入参/拼法非法（虚拟路径形态、挂载名形态、物理落点非绝对/未归一、能力 id 未注册等）。 */
  public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";

  /** 挂载不存在、已被卸载、或调用者不是其使用者（<b>与"无权"同码</b>，见类 javadoc）。 */
  public static final String WORKSPACE_NOT_FOUND = "WORKSPACE_NOT_FOUND";

  /** 权限/守卫拒绝（能力不匹配、解析越界、M/G 组守卫）。 */
  public static final String RESOURCE_DENIED = ToolCallAuthorizer.RESOURCE_DENIED;

  /** 虚拟挂载名冲突（M4：<b>具名拒</b>，不静默替换、不覆盖他人挂载）。 */
  public static final String WORKSPACE_NAME_CONFLICT = "WORKSPACE_NAME_CONFLICT";

  private final String code;

  public WorkspaceException(String code, String message) {
    super(message);
    this.code = requireCode(code);
  }

  public WorkspaceException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = requireCode(code);
  }

  /** 稳定错误码（工具边界拿它构造 {@code ToolResult.error}）。 */
  public String code() {
    return code;
  }

  public static WorkspaceException invalid(String message) {
    return new WorkspaceException(INVALID_ARGUMENTS, message);
  }

  public static WorkspaceException notFound(String message) {
    return new WorkspaceException(WORKSPACE_NOT_FOUND, message);
  }

  public static WorkspaceException denied(String message) {
    return new WorkspaceException(RESOURCE_DENIED, message);
  }

  public static WorkspaceException conflict(String message) {
    return new WorkspaceException(WORKSPACE_NAME_CONFLICT, message);
  }

  private static String requireCode(String code) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("工作区异常的 code 不能为空");
    }
    return code;
  }
}
