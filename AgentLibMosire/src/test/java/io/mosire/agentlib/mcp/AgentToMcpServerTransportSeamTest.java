package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * {@link AgentToMcpServer} transport 接缝：stdio 家族与 streamable HTTP 注入口的兄弟接口分派。
 *
 * <p>SDK 2.0.1 里 {@code McpServer.sync(McpServerTransportProvider)} 与 {@code
 * McpServer.sync(McpStreamableServerTransportProvider)} 是两个重载，两个 provider
 * 接口互不继承。正常路径靠重载静态分派；本测试锁住两条 易错边界：
 *
 * <ol>
 *   <li><b>误传守卫</b>：实现类同时实现两者而被传进单会话重载时，必须抛可读 {@link IllegalArgumentException}，绝不让 {@link
 *       ClassCastException} 从 SDK 内部泄漏（改造前强转的隐患）；
 *   <li><b>streamable 注入口可装配</b>：最小假 provider 能让 {@code serverInfo}/{@code toolCall}/{@code build}
 *       与 Registry 订阅走通（完整握手属后续 todo）。
 * </ol>
 */
class AgentToMcpServerTransportSeamTest {

  /** 合成工具：名字即行为，仅用于观测暴露面。 */
  private static AgentTool tool(String name) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok(name);
      }
    };
  }

  private static ToolContext guest() {
    return ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST));
  }

  /**
   * 兄弟接口误传的判别性用例：假 provider 同时实现两个接口，以 {@link McpServerTransportProvider} 静态类型传入单会话全参重载 ⇒ 守卫命中。断言的是
   * {@code IllegalArgumentException} 且消息可读，<b>不是</b> {@code ClassCastException}。
   */
  @Test
  void streamableProviderIntoStdioOverloadIsRejectedWithReadableMessage() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("ping"));
    McpServerTransportProvider disguised = new DualModeProvider();

    assertThatThrownBy(
            () ->
                AgentToMcpServer.startWith(
                    registry,
                    "seam-guard",
                    "0.1.0",
                    guest(),
                    disguised,
                    name -> true,
                    ToolCallAuthorizer.standard()))
        .isInstanceOf(IllegalArgumentException.class)
        .isNotInstanceOf(ClassCastException.class)
        .hasMessageContaining("单会话")
        .hasMessageContaining("McpServerTransportProvider")
        .hasMessageContaining("streamable");
  }

  /** streamable 注入口能完成装配，且初始注册与 Registry 变更同步（onChange → sync）都生效。 */
  @Test
  void streamableEntryPointAssemblesAndTracksRegistryChanges() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("ping"));
    FakeStreamableProvider transport = new FakeStreamableProvider();

    try (AgentToMcpServer server =
        AgentToMcpServer.startWith(registry, "seam-streamable", "0.1.0", guest(), transport)) {
      assertThat(transport.sessionFactory).as("SDK 必须在 build 时注入 session factory").isNotNull();
      assertThat(server.toolNames()).containsExactly("ping");
      registry.register(tool("pong"));
      assertThat(server.toolNames()).containsExactlyInAnyOrder("ping", "pong");
    }
  }

  /**
   * 最小 streamable provider：只需 setSessionFactory 可调用、notifyClients/closeGracefully 返回 Mono.empty()。
   */
  private static final class FakeStreamableProvider
      implements McpStreamableServerTransportProvider {

    private McpStreamableServerSession.Factory sessionFactory;

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
      this.sessionFactory = sessionFactory;
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
      return Mono.empty();
    }

    @Override
    public Mono<Void> closeGracefully() {
      return Mono.empty();
    }
  }

  /** 同时实现两个兄弟接口的假 provider：触发"实现类同时实现两者"的误传路径。 */
  private static final class DualModeProvider
      implements McpServerTransportProvider, McpStreamableServerTransportProvider {

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {}

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {}

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
      return Mono.empty();
    }

    @Override
    public Mono<Void> closeGracefully() {
      return Mono.empty();
    }
  }
}
