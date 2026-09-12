package io.mosire.agentlib.tool;

import io.mosire.agentlib.permission.PermissionChecker;
import java.util.Optional;

/**
 * 工具执行最终边界：权限判定 → 执行，一条路标。
 *
 * <p>设计（计划 §3.1）：拒绝即终局（无审批流、无静默降级），返回 {@code ToolResult.error("PERMISSION_DENIED", ...)}；可插拔 {@code
 * ApprovalGate}（AG-UI HITL） 留在**本类之上**（{@link ToolCallAuthorizer}）叠加——它需要"先判定、后审批、再执行"的**三段**顺序，
 * 而本类的 {@link #execute} 是"判定 + 执行"两段，无法在中间插桩，故拆出 {@link #denial} 供上层先用。
 *
 * <p>审计（通过/拒绝写事件）由调用方（Brain 管线）负责， 本类不持有存储依赖。
 */
public final class ToolExecutionGuard {

  /** 拒绝的统一错误码。 */
  public static final String DENIED = "PERMISSION_DENIED";

  /**
   * 仅判定、<b>不执行</b>：权限不足（或工具不存在）⇒ 返回拒绝结果，否则返回空。
   *
   * <p>这是 {@link ToolCallAuthorizer} 需要的"判定"那一段：硬拒必须**先于**审批闸终局（被硬拒的调用不进审批、更不进工具体），
   * 否则一次审批放行就可能把一个本就无权执行的调用洗白。判定规则与 {@link #execute} 逐条同源（同一个 {@link
   * PermissionChecker}），此处不新增规则、不吞异常。
   *
   * @param registry 注册表（工具不存在 ⇒ {@code TOOL_NOT_FOUND}，与 {@link #execute} 同码同文案）
   */
  public Optional<ToolResult> denial(ToolRegistry registry, String toolName, ToolContext context) {
    AgentTool tool = registry.find(toolName).orElse(null);
    if (tool == null) {
      return Optional.of(ToolResult.error("TOOL_NOT_FOUND", "工具不存在: " + toolName));
    }
    return denial(tool, context);
  }

  /**
   * 仅判定（工具实例已在手，省一次查找）：语义与 {@link #denial(ToolRegistry, String, ToolContext)} 相同。
   *
   * @param tool 非 null（调用方已解析）
   */
  public Optional<ToolResult> denial(AgentTool tool, ToolContext context) {
    return PermissionChecker.denialReason(context.permissions(), tool.name(), tool.spec())
        .map(reason -> ToolResult.error(DENIED, reason));
  }

  /** 判定 + 执行（两段合一；需要在中间插桩的场景用 {@link #denial(AgentTool, ToolContext)}）。 */
  public ToolResult execute(ToolRegistry registry, String toolName, ToolContext context) {
    AgentTool tool = registry.find(toolName).orElse(null);
    if (tool == null) {
      return ToolResult.error("TOOL_NOT_FOUND", "工具不存在: " + toolName);
    }
    Optional<ToolResult> denied = denial(tool, context);
    if (denied.isPresent()) {
      return denied.get();
    }
    return tool.execute(context);
  }
}
