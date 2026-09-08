package io.mosire.agentlib.mcp;

import io.mosire.agentlib.tool.ToolResult;
import java.util.Map;

/** MCP 工具调用的执行点——把 {@link McpToolAdapter} 与 SDK 客户端解耦（便于离线测试）。 */
@FunctionalInterface
public interface McpCaller {

  /**
   * 调用远程工具。
   *
   * @param name 工具名
   * @param arguments JSON 对象参数（来自 LLM 填写的 tool call arguments）
   * @return 映射后的 {@link ToolResult}
   */
  ToolResult call(String name, Map<String, Object> arguments);
}
