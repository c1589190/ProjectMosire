package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpToolSourceTest {

  @Test
  void adaptsToolMetadataAndDelegatesCall() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder("echo", Map.of("type", "object"))
            .title("回显")
            .description("原样返回输入")
            .build();
    McpCaller caller = (name, arguments) -> ToolResult.ok(name + ":" + arguments.get("text"));

    AgentTool adapted = McpToolAdapter.of(tool, caller);
    assertThat(adapted.name()).isEqualTo("echo");
    assertThat(adapted.description()).isEqualTo("原样返回输入");
    assertThat(adapted.jsonSchema()).containsEntry("type", "object");

    ToolContext context =
        new ToolContext(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build(),
            Map.of(),
            Map.of("text", "hi"));
    ToolResult result = adapted.execute(context);
    assertThat(result.success()).isTrue();
    assertThat(result.message()).isEqualTo("echo:hi"); // 参数经 ToolContext 传入调用方
  }

  @Test
  void mapsMcpCallToolResultToToolResult() {
    McpSchema.CallToolResult ok =
        new McpSchema.CallToolResult(
            List.of(
                McpSchema.TextContent.builder("结果一").build(),
                McpSchema.TextContent.builder("结果二").build()),
            false,
            null,
            Map.of());
    ToolResult mapped = McpToolSource.map(ok);
    assertThat(mapped.success()).isTrue();
    assertThat(mapped.message()).isEqualTo("结果一\n结果二");

    McpSchema.CallToolResult error =
        new McpSchema.CallToolResult(
            List.of(McpSchema.TextContent.builder("炸了").build()), true, null, Map.of());
    ToolResult mappedError = McpToolSource.map(error);
    assertThat(mappedError.success()).isFalse();
    assertThat(mappedError.code()).isEqualTo("MCP_TOOL_ERROR");
    assertThat(mappedError.message()).isEqualTo("炸了");
  }
}
