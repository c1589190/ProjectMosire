package io.mosire.agentlib.tool;

import io.mosire.agentlib.permission.PermissionChecker;

/**
 * 工具执行最终边界：权限判定 → 执行，一条路标。
 *
 * <p>设计（计划 §3.1）：拒绝即终局（无审批流、无静默降级），返回 {@code ToolResult.error("PERMISSION_DENIED", ...)}；可插拔 {@code
 * ApprovalGate}（AG-UI HITL） 留待 M2 在 guard 之上叠加，不进入本类。审计（通过/拒绝写事件）由调用方（Brain 管线）负责， 本类不持有存储依赖。
 */
public final class ToolExecutionGuard {

  /** 拒绝的统一错误码。 */
  public static final String DENIED = "PERMISSION_DENIED";

  public ToolResult execute(ToolRegistry registry, String toolName, ToolContext context) {
    AgentTool tool = registry.find(toolName).orElse(null);
    if (tool == null) {
      return ToolResult.error("TOOL_NOT_FOUND", "工具不存在: " + toolName);
    }
    var denial = PermissionChecker.denialReason(context.permissions(), toolName, tool.spec());
    if (denial.isPresent()) {
      return ToolResult.error(DENIED, denial.get());
    }
    return tool.execute(context);
  }
}
