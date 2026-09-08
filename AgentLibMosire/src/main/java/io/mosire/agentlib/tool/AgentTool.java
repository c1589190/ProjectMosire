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
   * 执行一次调用。实现必须把参数、IO 错误映射为 {@link ToolResult}（成功或带 code 的失败）， 不抛异常吞掉边界错误（意外异常可抛，由审计记录后转为工具层错误）。
   */
  ToolResult execute(ToolContext context);
}
