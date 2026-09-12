package io.mosire.agentlib.tool;

import java.util.Objects;
import java.util.Optional;

/**
 * <b>工具调用的唯一入口</b>：权限判定 → （审批闸）→ 执行。
 *
 * <p><b>为什么需要它</b>（2026-09-12 S4 调查实测）：本仓有<b>两个</b>工具调用入口——Brain 管线（{@code
 * AgentPipeline.executeToolCall}）与 MCP 桥（{@code AgentToMcpServer.handleCall}）。前者经 {@link
 * ToolExecutionGuard} 判定，后者<b>直接 {@code tool.execute(context)}、连 {@link ToolExecutionGuard}
 * 都不经</b>（这正是各系统级工具不得不在工具体里 自造 "第二道闸" 的原因）。两条路各判各的，语义必然分叉：任何新判定（审批、资源作用域、未来的额度）只落一条路，另一条就没有它。
 * 本类把"解析工具 → 判定 → 执行"收成一条，两个入口都只调这里。
 *
 * <p><b>三段顺序不可换</b>：
 *
 * <ol>
 *   <li><b>硬拒</b>（工具不存在 / 权限不足）——终局，<b>不进审批</b>。否则"审批放行"会把一个本就无权执行的调用洗白；
 *   <li><b>审批闸</b>（工具自报需人确认 ⇒ 问人或按策略拒）——落点见 S4 B 块；
 *   <li><b>执行</b>。
 * </ol>
 *
 * <p><b>不持有的东西</b>：不写事件、不持有存储（审计仍归调用方，与 guard 同口径）；不做工具名级判断之外的任何策略。
 */
public final class ToolCallAuthorizer {

  private final ToolExecutionGuard guard;

  public ToolCallAuthorizer(ToolExecutionGuard guard) {
    this.guard = Objects.requireNonNull(guard, "guard");
  }

  /** 以给定 guard 装配（等价于构造器；供调用点读起来像配置而非 new）。 */
  public static ToolCallAuthorizer of(ToolExecutionGuard guard) {
    return new ToolCallAuthorizer(guard);
  }

  /** 默认装配（默认三要素判定规则）。 */
  public static ToolCallAuthorizer standard() {
    return new ToolCallAuthorizer(new ToolExecutionGuard());
  }

  /**
   * 执行一次工具调用。
   *
   * @param registry 工具注册表
   * @param toolName 工具名（模型给出；不受信）
   * @param context 调用上下文（调用者身份 + 权限集 + 工具配置 + 参数）
   */
  public ToolResult execute(ToolRegistry registry, String toolName, ToolContext context) {
    Optional<ToolResult> denied = guard.denial(registry, toolName, context);
    if (denied.isPresent()) {
      return denied.get();
    }
    AgentTool tool = registry.find(toolName).orElseThrow();
    // 审批闸的落点：S4 B 块在此判定（工具自报的 BLOCKED/ASK/ALLOW 三档）。在那之前，本行只做"能进来的都已通过硬拒"这一件事。
    return tool.execute(context);
  }
}
