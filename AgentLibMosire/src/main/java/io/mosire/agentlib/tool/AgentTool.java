package io.mosire.agentlib.tool;

import io.mosire.agentlib.permission.ToolSpec;
import java.util.Map;

/**
 * 工具唯一接口：一个 Agent 可执行动作的最小描述 + 执行器。
 *
 * <p>实现约定：
 *
 * <ul>
 *   <li>{@link #name()} 必须全局唯一（注册进 {@link ToolRegistry} 时重复即抛错）；
 *   <li>{@link #jsonSchema()} 为 JSON Schema（output 到 LLM 工具定义）；
 *   <li>{@link #spec()} 声明三要素（默认 {@link ToolSpec#DEFAULT}：guest 级、非敏感非破坏）——
 *       不声明不报错，但敏感工具必须显式声明，否则永远不会被放行；
 *   <li>实现自身不做权限判断（那是 {@link ToolExecutionGuard} 的边界职责），只按参数执行。
 * </ul>
 */
public interface AgentTool {

  String name();

  /** 面向模型的自然语言描述；缺省空串（模型可读性差，工具应提供）。 */
  default String description() {
    return "";
  }

  /** 工具入参的 JSON Schema；缺省为无参数。 */
  default Map<String, Object> jsonSchema() {
    return Map.of("type", "object", "properties", Map.of());
  }

  default ToolSpec spec() {
    return ToolSpec.DEFAULT;
  }

  /**
   * 本工具对这次调用的自报闸位；缺省 {@link ToolGate#ALLOW}（既有工具行为逐字不变）。
   *
   * <p>分类器是人的检查点的<b>分流器，不是安全边界</b>（见设计 §2.1）——实现须诚实，不得自称"完备"： 报 {@code ALLOW}
   * 的含义是"我判断这次调用不需要人看"，不是"我证明了它安全"。
   *
   * <p>本 SPI 是 S4 的<b>唯一</b>新增判定入口：工具报"要不要人看"，而"放不放行"仍归 {@link ToolCallAuthorizer}（硬拒 →（审批闸）→
   * 执行）。工具<b>不得</b>据此自行放行——{@code Block} 由入口转成 {@code COMMAND_BLOCKED} 且不进审批。
   */
  default io.mosire.agentlib.approval.ToolGate gate(ToolContext context) {
    return io.mosire.agentlib.approval.ToolGate.ALLOW;
  }

  /**
   * 执行一次调用。实现必须把参数、IO 错误映射为 {@link ToolResult}（成功或带 code 的失败）， 不抛异常吞掉边界错误（意外异常可抛，由审计记录后转为工具层错误）。
   */
  ToolResult execute(ToolContext context);
}
