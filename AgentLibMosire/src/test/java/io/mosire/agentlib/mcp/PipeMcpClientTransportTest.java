package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link PipeMcpClientTransport} 契约：同一对双向管道上，以「客户端 = 管道传输、服务端 = {@link
 * StdioServerTransportProvider}（流构造）」完成真实 MCP 握手、tools/list 与 tools/call。
 *
 * <p>这是 W3b 父子进程 stdio 链接的传输层单元证明：SDK 官方客户端 StdioClientTransport 只能自己 spawn 子进程（无流构造），而子 Agent（MCP
 * client 角色）必须复用父进程已建好的管道——本测试证明 自定义管道传输与官方服务端完全互通。
 */
class PipeMcpClientTransportTest {

  @Test
  void speaksMcpOverPipedStreamsAndCompletesOnClose() throws Exception {
    // 服务端 ←（读） serverReads /（写） serverWrites；客户端反向
    PipedInputStream serverReads = new PipedInputStream();
    PipedOutputStream clientWrites = new PipedOutputStream(serverReads);
    PipedInputStream clientReads = new PipedInputStream();
    PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());

    try (AgentToMcpServer server =
        AgentToMcpServer.startWith(
            registry,
            "pipe-server",
            "0.1.0",
            ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST)),
            new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), serverReads, serverWrites))) {
      PipeMcpClientTransport transport =
          new PipeMcpClientTransport(McpJsonDefaults.getMapper(), clientReads, clientWrites);
      McpSyncClient client = McpClient.sync(transport).build();
      try {
        client.initialize();
        List<McpSchema.Tool> tools = client.listTools().tools();
        assertThat(tools).extracting(McpSchema.Tool::name).containsExactly("echo");

        McpSchema.CallToolResult result =
            client.callTool(new McpSchema.CallToolRequest("echo", Map.of("text", "你好")));
        // SDK 2.0.1：成功响应 isError 为 Boolean false（非 null）
        assertThat(result.isError()).isFalse();
        assertThat(result.content())
            .extracting(c -> c instanceof McpSchema.TextContent t ? t.text() : c.toString())
            .containsExactly("echo:你好");
      } finally {
        client.closeGracefully();
      }
      // 客户端优雅关闭 → 传输停摆 → whenClosed 完成（父侧靠它感知链接断开）
      assertThat(transport.whenClosed().get(5, TimeUnit.SECONDS)).isNull();
    }
  }

  private static AgentTool echoTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "echo";
      }

      @Override
      public String description() {
        return "原样返回输入文本";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")));
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("echo:" + context.arguments().getOrDefault("text", ""));
      }
    };
  }
}
