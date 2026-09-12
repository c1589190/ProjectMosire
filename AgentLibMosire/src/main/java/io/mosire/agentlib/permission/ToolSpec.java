package io.mosire.agentlib.permission;

import java.util.Objects;

/**
 * 工具三要素——工具本身的权限元数据（计划 D9 的"工具"维度）：
 *
 * <ul>
 *   <li>{@code requiredLevel}：最低身份级别；
 *   <li>{@code sensitive}：敏感动作（向用户发送消息/写文件），默认拒绝、需显式放行；
 *   <li>{@code destructive}：破坏性动作（删除/覆盖/杀进程），默认拒绝；
 *   <li>{@code noExport}：<b>禁外发</b>（D30）——该工具不得进入 {@code AgentToMcpServer} 的桥接工具表， 因此子 Agent（经父侧
 *       MCP 桥拿工具目录）永远看不见它。默认 {@code false}，既有工具行为逐字不变。
 * </ul>
 *
 * <p><b>为什么需要 {@code noExport}</b>：桥接把父进程<b>整个</b> {@code ToolRegistry} 交给子体当 MCP 工具面 （{@code App}
 * 的 {@code --mcp-expose}）。凡"只准主 Agent 用、一旦外发就是越权"的工具（如 D30 的 {@code read_agent_context}：外发给子体 =
 * 每个子体都能读别家子体的上下文，正是 D27 读侧明令禁止的事），必须有一个 <b>结构性</b>的标注位把"不可外包"写在工具自己身上——而不是靠调用点逐个记得传过滤器（会漏）。判定落点是
 * {@code AgentToMcpServer} 的暴露判定，见 {@code exportable}。
 */
public record ToolSpec(
    AccessToken requiredLevel, boolean sensitive, boolean destructive, boolean noExport) {

  /** 常规工具（guest 一级即可用、非敏感非破坏、可外发）。 */
  public static final ToolSpec DEFAULT = new ToolSpec(AccessToken.GUEST, false, false, false);

  public ToolSpec {
    Objects.requireNonNull(requiredLevel, "requiredLevel");
  }

  /** D30 之前的 3 参形态：{@code noExport = false}——既有调用点（含子类/测试）零改动，行为逐字不变。 */
  public ToolSpec(AccessToken requiredLevel, boolean sensitive, boolean destructive) {
    this(requiredLevel, sensitive, destructive, false);
  }

  public static ToolSpec level(AccessToken requiredLevel) {
    return new ToolSpec(requiredLevel, false, false, false);
  }

  public static ToolSpec level(AccessToken requiredLevel, boolean sensitive, boolean destructive) {
    return new ToolSpec(requiredLevel, sensitive, destructive, false);
  }

  /** 带"禁外发"位的形态（D30）：{@code noExport = true} 的工具不进 MCP 桥接工具表。 */
  public static ToolSpec level(
      AccessToken requiredLevel, boolean sensitive, boolean destructive, boolean noExport) {
    return new ToolSpec(requiredLevel, sensitive, destructive, noExport);
  }
}
