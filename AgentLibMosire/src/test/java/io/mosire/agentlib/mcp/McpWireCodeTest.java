package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.tool.ToolResult;
import org.junit.jupiter.api.Test;

/** 私有带内错误码约定的编解码（纯函数，无 I/O）。 */
class McpWireCodeTest {

  @Test
  void errorCodeSurvivesRoundTrip() {
    ToolResult original = ToolResult.error("PERMISSION_DENIED", "没有权限访问 keys.*");
    String encoded = McpWireCode.encode(original);
    assertThat(encoded).startsWith("[mosire:code=PERMISSION_DENIED]");
    String[] decoded = McpWireCode.decode(encoded, "fallback");
    assertThat(decoded[0]).isEqualTo("PERMISSION_DENIED");
    assertThat(decoded[1]).isEqualTo("没有权限访问 keys.*");
  }

  @Test
  void successIsNotWrapped() {
    assertThat(McpWireCode.encode(ToolResult.ok("fine"))).isEqualTo("fine");
  }

  @Test
  void foreignServerTextFallsBackToGenericCode() {
    String[] decoded = McpWireCode.decode("BOOM", "MCP 工具调用失败");
    assertThat(decoded[0]).isEqualTo("MCP_TOOL_ERROR");
    assertThat(decoded[1]).isEqualTo("BOOM");
    // 空文本 → 兜底消息
    assertThat(McpWireCode.decode("", "MCP 工具调用失败")[1]).isEqualTo("MCP 工具调用失败");
  }
}
