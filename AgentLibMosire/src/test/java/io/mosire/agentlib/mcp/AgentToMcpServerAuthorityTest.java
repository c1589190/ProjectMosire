package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * MCP 路径的<b>判定面</b>（S4 判定点统一）：{@code tools/call} 一律经 {@code ToolCallAuthorizer}。
 *
 * <p><b>为什么单独立一个用例类</b>：改这一行之前，{@code handleCall} 是裸的 {@code tool.execute(context)}——权限判定完全缺席。
 * 于是"工具名级白名单 / 身份级别 / 敏感·破坏需显式放行"这一整套规则在 MCP 这条路上<b>从不生效</b>，各系统级工具只能在自己的 execute 里补一道"第二道闸"（{@code
 * SubagentOrchestrationTools.systemOnly} 的注释自陈如此）。本用例把该洞钉住： 把它还原成裸 execute，三条断言全部转红。
 *
 * <p>走的是真实 MCP 握手（管道传输 + SDK 官方客户端），不是直接调私有方法——避免"测了个假入口"。
 */
class AgentToMcpServerAuthorityTest {

  private static AgentTool tool(String name, ToolSpec spec, AtomicBoolean bodyRan) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolSpec spec() {
        return spec;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        bodyRan.set(true);
        return ToolResult.ok(name + ":executed");
      }
    };
  }

  /** 调用者权限集：白名单通配 ⇒ "被挡住"只能来自身份级别/放行位，不与白名单混淆。 */
  private static AgentPermissionSet unrestricted(
      AccessToken token, boolean sensitive, boolean destructive) {
    return AgentPermissionSet.builder(token)
        .allowAll()
        .sensitiveAllowed(sensitive)
        .destructiveAllowed(destructive)
        .build();
  }

  /** 起一个 server（给定 caller 权限集）+ 管道客户端，调一次工具并返回响应文本。 */
  private static McpSchema.CallToolResult callAs(
      ToolRegistry registry, AgentPermissionSet permissions, String toolName) throws Exception {
    PipedInputStream serverReads = new PipedInputStream();
    PipedOutputStream clientWrites = new PipedOutputStream(serverReads);
    PipedInputStream clientReads = new PipedInputStream();
    PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
    try (AgentToMcpServer server =
        AgentToMcpServer.startWith(
            registry,
            "authority-test",
            "0.1.0",
            ToolContext.of(permissions.grantedToken(), permissions),
            new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), serverReads, serverWrites))) {
      PipeMcpClientTransport transport =
          new PipeMcpClientTransport(McpJsonDefaults.getMapper(), clientReads, clientWrites);
      McpSyncClient client = McpClient.sync(transport).build();
      try {
        client.initialize();
        return client.callTool(new McpSchema.CallToolRequest(toolName, Map.of()));
      } finally {
        client.closeGracefully();
      }
    }
  }

  private static String wireText(McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = result.content();
    return content.get(0) instanceof McpSchema.TextContent t ? t.text() : content.toString();
  }

  @Test
  void mcpPathJudgesWithTheSameRulesAsThePipeline() throws Exception {
    AtomicBoolean systemBodyRan = new AtomicBoolean(false);
    AtomicBoolean sensitiveBodyRan = new AtomicBoolean(false);
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("orch", ToolSpec.level(AccessToken.SYSTEM, false, true), systemBodyRan));
    registry.register(
        tool("write", ToolSpec.level(AccessToken.DEFAULT, true, false), sensitiveBodyRan));

    // 桥接的生产形态（SubProcessExecutor）：caller = 子 Agent 自身的权限集（DEFAULT 身份）
    AgentPermissionSet bridgeCaller = unrestricted(AccessToken.DEFAULT, false, false);

    // ① 身份级别不足
    McpSchema.CallToolResult systemCall = callAs(registry, bridgeCaller, "orch");
    assertThat(systemCall.isError()).isTrue();
    assertThat(wireText(systemCall)).startsWith("[mosire:code=" + ToolExecutionGuard.DENIED + "]");
    assertThat(wireText(systemCall)).contains("SYSTEM");

    // ② 身份够、敏感放行位不够
    McpSchema.CallToolResult sensitiveCall = callAs(registry, bridgeCaller, "write");
    assertThat(sensitiveCall.isError()).isTrue();
    assertThat(wireText(sensitiveCall))
        .startsWith("[mosire:code=" + ToolExecutionGuard.DENIED + "]");

    // ★ 判别性：被拒的调用没碰工具体（裸 execute 的旧行为会返 ok 且副作用已发生）
    assertThat(systemBodyRan).isFalse();
    assertThat(sensitiveBodyRan).isFalse();

    // ★ 反面对照：同一注册表、同一工具、同一通道，只把 caller 换成够格者 ⇒ 执行成功。
    //   没有这一条，"被拒"无法与"通道坏了/工具没注册/参数不对"区分开。
    assertThat(callAs(registry, AgentPermissionSet.system(), "orch").isError()).isFalse();
    assertThat(systemBodyRan).isTrue();
  }

  /**
   * 未注册的工具名<b>到不了</b> {@code handleCall}：MCP SDK 按 server 已注册的工具表先行校验并抛错。故 {@code handleCall} 里的
   * {@code TOOL_NOT_FOUND} 分支在这条路上是<b>防御路径</b>（保留，但无法由本层用例触达）——这条钉子防的是后人把它当"未测的洞"去补。
   */
  @Test
  void unknownToolNameIsRejectedByTheProtocolLayerBeforeOurHandler() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("orch", ToolSpec.DEFAULT, new AtomicBoolean(false)));
    assertThatThrownBy(() -> callAs(registry, AgentPermissionSet.system(), "nonexistent"))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Unknown tool");
  }
}
