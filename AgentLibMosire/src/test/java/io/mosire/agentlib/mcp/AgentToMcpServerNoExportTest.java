package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import org.junit.jupiter.api.Test;

/**
 * D30 <b>禁外发</b>（{@link ToolSpec#noExport()}）的判别性用例：带该位的工具<b>不进桥接工具表</b>——初始注册与增量同步 都进不去。
 *
 * <p>为什么这是安全必需：桥（{@code AgentToMcpServer}）把父进程<b>整个</b> {@code ToolRegistry} 交给子体当 MCP 工具面 （{@code
 * App} 的 {@code --mcp-expose}）。若 {@code read_agent_context} 被外发，每个子体都能读别家子体的上下文—— 正是 D27 读侧明令禁止的事。
 *
 * <p><b>判别性</b>：把 {@code read_agent_context} 桩的 spec 从 {@code ToolSpec.level(SYSTEM, true, false,
 * true)} 改成 3 参形态（{@code noExport} 缺省 false）⇒ 下面第一条断言立刻转红（工具出现在工具表里）。
 */
class AgentToMcpServerNoExportTest {

  /**
   * 名字与真实 D30 工具同名（本测试验证的是"带禁外发位的工具不进桥表"这一机制，不是 Brain 侧那只工具）。
   *
   * <p>此处的同名工具是<b>合成夹具</b>：真实 {@code read_agent_context} 自 D27 起已<b>不带</b> {@code noExport}（读侧改为外发
   * + subtree 判定守，见 {@code 设计-身份与血缘.md} §四）⇒ 本用例的判别力全部来自这个桩上的位，与那只工具现况无关。
   */
  private static AgentTool tool(String name, ToolSpec spec) {
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
        return ToolResult.ok(name);
      }
    };
  }

  private static AgentToMcpServer serverOver(ToolRegistry registry) throws Exception {
    PipedInputStream serverReads = new PipedInputStream();
    PipedOutputStream clientWrites = new PipedOutputStream(serverReads);
    PipedInputStream clientReads = new PipedInputStream();
    PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
    return AgentToMcpServer.startWith(
        registry,
        "no-export-test",
        "0.1.0",
        ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST)),
        new StdioServerTransportProvider(McpJsonDefaults.getMapper(), serverReads, serverWrites));
  }

  @Test
  void noExportToolNeverAppearsInBridgeTable() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    // 判别位在上：把第 4 个参数拿掉（走 3 参形态 ⇒ noExport=false）⇒ 本用例转红
    registry.register(
        tool("read_agent_context", ToolSpec.level(AccessToken.SYSTEM, true, false, true)));
    registry.register(tool("list_sub_agents", ToolSpec.level(AccessToken.SYSTEM, false, true)));
    try (AgentToMcpServer server = serverOver(registry)) {
      // 初始注册即不暴露
      assertThat(server.toolNames())
          .containsExactly("list_sub_agents")
          .doesNotContain("read_agent_context");
      // 增量同步（Registry 变更 → onChange → sync）同样不暴露
      registry.register(tool("spawn_sub_agent", ToolSpec.level(AccessToken.SYSTEM, false, true)));
      registry.unregister("list_sub_agents");
      assertThat(server.toolNames())
          .containsExactly("spawn_sub_agent")
          .doesNotContain("read_agent_context");
    }
  }

  @Test
  void legacyThreeArgSpecKeepsToolExportable() throws Exception {
    // 既有工具的 spec 走 3 参形态：noExport 必须仍是 false（"逐字不变"的可断言形态）
    assertThat(ToolSpec.level(AccessToken.SYSTEM, false, true).noExport()).isFalse();
    assertThat(ToolSpec.DEFAULT.noExport()).isFalse();
    assertThat(new ToolSpec(AccessToken.GUEST, false, false).noExport()).isFalse();

    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("kill_sub_agent", ToolSpec.level(AccessToken.SYSTEM, false, true)));
    try (AgentToMcpServer server = serverOver(registry)) {
      assertThat(server.toolNames()).containsExactly("kill_sub_agent");
    }
  }

  @Test
  void nameFilterAndNoExportComposeWithoutLeaking() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(
        tool("read_agent_context", ToolSpec.level(AccessToken.SYSTEM, true, false, true)));
    registry.register(tool("secret_read_agent_context", ToolSpec.DEFAULT));
    PipedInputStream serverReads = new PipedInputStream();
    PipedOutputStream clientWrites = new PipedOutputStream(serverReads);
    PipedInputStream clientReads = new PipedInputStream();
    PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
    try (AgentToMcpServer server =
        AgentToMcpServer.startWith(
            registry,
            "no-export-filter-test",
            "0.1.0",
            ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST)),
            new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), serverReads, serverWrites),
            name -> !name.startsWith("secret"))) {
      // 两条闸各管一边：名过滤器挡 secret*，noExport 位挡 read_agent_context
      assertThat(server.toolNames()).isEmpty();
      // 两工具都还在 Registry 里（"不暴露" ≠ "被删掉"——本地调用面不受影响）
      assertThat(registry.list()).extracting(AgentTool::name).hasSize(2);
    }
  }
}
