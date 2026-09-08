package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import org.junit.jupiter.api.Test;

/**
 * {@link McpSourceBridge} 名字过滤装配（W3b：子 Agent 只把自己的白名单工具同步进 registry——父侧暴露面是 全量工具，白名单过滤必须在子侧落地，否则子
 * Agent 的目录会泄漏父级全部工具）。
 */
class McpSourceBridgeFilterTest {

  @Test
  void bindsOnlyNamesPassingFilterAndCleansUp() throws Exception {
    PipedInputStream serverReads = new PipedInputStream();
    PipedOutputStream clientWrites = new PipedOutputStream(serverReads);
    PipedInputStream clientReads = new PipedInputStream();
    PipedOutputStream serverWrites = new PipedOutputStream(clientReads);

    ToolRegistry exposed = new ToolRegistry();
    exposed.register(tool("echo", "echo:"));
    exposed.register(tool("spawn_sub_agent", "spawn:"));

    try (AgentToMcpServer server =
        AgentToMcpServer.startWith(
            exposed,
            "filter-server",
            "0.1.0",
            ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST)),
            new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), serverReads, serverWrites))) {
      McpToolSource source = new McpToolSource("filter-test");
      source.connect(
          new PipeMcpClientTransport(McpJsonDefaults.getMapper(), clientReads, clientWrites));
      ToolRegistry child = new ToolRegistry();
      try (McpSourceBridge bridge =
          McpSourceBridge.bind(source, child, name -> name.equals("echo"))) {
        // 过滤：只同步白名单命中的工具（父侧暴露面全量，子侧收窄）
        assertThat(child.list()).extracting(AgentTool::name).containsExactly("echo");
        assertThat(bridge.synchronizedNames()).containsExactly("echo");
      }
      // close → 子侧注册表恢复原状
      assertThat(child.list()).isEmpty();
    }
  }

  private static AgentTool tool(String name, String prefix) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok(prefix + context.arguments().getOrDefault("text", ""));
      }
    };
  }
}
