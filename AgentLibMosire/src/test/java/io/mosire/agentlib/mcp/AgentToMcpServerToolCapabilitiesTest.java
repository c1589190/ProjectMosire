package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * {@link AgentToMcpServer} 的 tool capabilities 宣告与 {@code notifications/tools/list_changed} 送达。
 *
 * <p>缺陷背景（两路复核 + 实测确认）：装配时从不调 {@code spec.capabilities(...)}，SDK 自动派生出 {@code
 * ToolCapabilities(null)}；{@code McpAsyncServer.addTool/removeTool} 对 {@code
 * serverCapabilities.tools().listChanged()} 拆箱 null 抛 NPE，被 {@link ToolRegistry} 的变更广播 catch 吞成
 * WARN，净效果是 {@code notifications/tools/list_changed} 永不触达。本测试用<b>捕获型假 transport</b>直接观测 {@code
 * notifyClients}，不依赖官方 client 的 notification 订阅（避免脆弱时序）。
 *
 * <p>为什么不断言"无 WARN 日志"：AgentLibMosire 测试类路径只有 slf4j-api 无日志实现，无法装 appender；而"通知到达"是
 * "监听器未被异常吞掉"的<b>等价更强</b>命题——若 NPE 仍发生，通知根本不会发出。
 */
class AgentToMcpServerToolCapabilitiesTest {

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
   * 核心用例：Registry 增删必须各触发一次 {@code notifications/tools/list_changed}（假 transport 逐字收到 SDK 常量 {@link
   * McpSchema#METHOD_NOTIFICATION_TOOLS_LIST_CHANGED}）。
   */
  @Test
  void registryRegisterAndUnregisterEmitToolsListChangedNotification() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("ping"));
    CapturingStreamableProvider transport = new CapturingStreamableProvider();

    try (AgentToMcpServer server =
        AgentToMcpServer.startWith(registry, "caps-notify", "0.1.0", guest(), transport)) {
      assertThat(transport.notifiedMethods())
          .as("装配阶段只注册初始工具，不应产生 list_changed 通知")
          .doesNotContain(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED);

      registry.register(tool("pong"));
      assertThat(transport.notifiedMethods())
          .as("Registry 新增工具必须外发 list_changed")
          .containsExactly(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED);

      registry.unregister("pong");
      assertThat(transport.notifiedMethods())
          .as("Registry 移除工具必须再外发一次 list_changed")
          .containsExactly(
              McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED,
              McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED);
    }
  }

  /**
   * 等价更强断言：增删两个方向各自都必须送达通知，证明 {@code ToolRegistry.notifyChanged} 未再吞掉 {@code addTool/removeTool}
   * 的异常（异常被吞 ⇒ 通知缺失）。分别隔离 add-only 与 remove-only 路径，避免互相掩盖。
   */
  @Test
  void bothMutationDirectionsDeliverNotificationWithoutSwallowedListenerException() {
    ToolRegistry registry = new ToolRegistry();
    CapturingStreamableProvider transport = new CapturingStreamableProvider();

    try (AgentToMcpServer server =
        AgentToMcpServer.startWith(registry, "caps-directions", "0.1.0", guest(), transport)) {
      registry.register(tool("alpha"));
      assertThat(transport.notifiedMethods())
          .as("仅新增路径：应恰好一次 list_changed")
          .containsExactly(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED);

      transport.clear();
      registry.unregister("alpha");
      assertThat(transport.notifiedMethods())
          .as("仅移除路径：应恰好一次 list_changed")
          .containsExactly(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED);
    }
  }

  /** 行为回归钉：修 capabilities 不得改坏注册循环——{@code listTools()} 仍与 Registry 增删同步。 */
  @Test
  void listToolsStillTracksRegistryChanges() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("ping"));
    CapturingStreamableProvider transport = new CapturingStreamableProvider();

    try (AgentToMcpServer server =
        AgentToMcpServer.startWith(registry, "caps-list", "0.1.0", guest(), transport)) {
      assertThat(server.toolNames()).containsExactly("ping");

      registry.register(tool("pong"));
      assertThat(server.toolNames()).containsExactlyInAnyOrder("ping", "pong");

      registry.unregister("ping");
      assertThat(server.toolNames()).containsExactly("pong");
    }
  }

  /** 记录 {@code notifyClients(method, params)} 调用的假 streamable provider：只观测方法名。 */
  private static final class CapturingStreamableProvider
      implements McpStreamableServerTransportProvider {

    private final List<String> notifiedMethods = new CopyOnWriteArrayList<>();

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {}

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
      notifiedMethods.add(method);
      return Mono.empty();
    }

    @Override
    public Mono<Void> closeGracefully() {
      return Mono.empty();
    }

    List<String> notifiedMethods() {
      return List.copyOf(notifiedMethods);
    }

    void clear() {
      notifiedMethods.clear();
    }
  }
}
