package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 三个单会话/stdio 族 {@code AgentToMcpServer.startWith} 重载的<b>公开 API 形状</b>与运行时契约。
 *
 * <p>为什么单独立一个用例类：把这三个注入口从"供测试"升为正式 API 是一次<b>可见性变更</b>——可见性是形状属性，任何行为测试都恒绿
 * （包私有方法在包内测试里照样调得通），只有反射才看得见。故本类的主断言走 {@link Class#getDeclaredMethod}，锁住"public + 参数类型逐一精确 + 返回类型
 * + javadoc {@code @since}"；行为与负向用例只作契约的运行时补证。
 *
 * <p>本类<b>不</b>触碰 streamable HTTP 注入口：它们仍为包私有，公开入口是后续的 {@code startHttp}（本类负向用例只证明 streamable
 * provider 误入 stdio 重载会被可读拒绝）。
 */
class AgentToMcpServerPublicTransportTest {

  private static final String MAIN_SOURCE =
      "src/main/java/io/mosire/agentlib/mcp/AgentToMcpServer.java";

  // ---------------------------------------------------------------- 反射：形状

  /**
   * 三个 stdio 族 {@code startWith} 必须 public、参数类型逐一精确匹配、返回 {@link AgentToMcpServer}。
   *
   * <p>用 {@code getDeclaredMethod} 而非 {@code getMethod}：后者只找 public 方法，包私有方法会直接 {@code
   * NoSuchMethodException}——那样即使可见性回退成包私有，测试也只是"找不到方法"而非"断言 public 失败"，判别信息更弱。这里显式取声明、显式断 {@link
   * Modifier#isPublic}。
   */
  @Test
  void stdioStartWithOverloadsArePublicWithExactSignature() throws Exception {
    Class<?>[] base = {
      ToolRegistry.class,
      String.class,
      String.class,
      ToolContext.class,
      McpServerTransportProvider.class
    };
    Method five = AgentToMcpServer.class.getDeclaredMethod("startWith", base);
    Method six =
        AgentToMcpServer.class.getDeclaredMethod(
            "startWith",
            ToolRegistry.class,
            String.class,
            String.class,
            ToolContext.class,
            McpServerTransportProvider.class,
            Predicate.class);
    Method seven =
        AgentToMcpServer.class.getDeclaredMethod(
            "startWith",
            ToolRegistry.class,
            String.class,
            String.class,
            ToolContext.class,
            McpServerTransportProvider.class,
            Predicate.class,
            ToolCallAuthorizer.class);

    for (Method m : List.of(five, six, seven)) {
      assertThat(Modifier.isPublic(m.getModifiers())).as("%s 必须是 public", m).isTrue();
      assertThat(Modifier.isStatic(m.getModifiers())).as("%s 必须是 static", m).isTrue();
      assertThat(m.getReturnType()).as("%s 返回类型", m).isEqualTo(AgentToMcpServer.class);
    }
    assertThat(five.getParameterTypes()).containsExactly(base);
    assertThat(six.getParameterTypes())
        .containsExactly(
            ToolRegistry.class,
            String.class,
            String.class,
            ToolContext.class,
            McpServerTransportProvider.class,
            Predicate.class);
    assertThat(seven.getParameterTypes())
        .containsExactly(
            ToolRegistry.class,
            String.class,
            String.class,
            ToolContext.class,
            McpServerTransportProvider.class,
            Predicate.class,
            ToolCallAuthorizer.class);

    // streamable 家族仍是包私有（公开入口留给后续 startHttp）：这里逐个数一下，防止误把兄弟重载一起公开。
    for (Method m : AgentToMcpServer.class.getDeclaredMethods()) {
      if ("startWith".equals(m.getName())
          && List.of(m.getParameterTypes()).contains(McpStreamableServerTransportProvider.class)) {
        assertThat(Modifier.isPublic(m.getModifiers()))
            .as("streamable startWith %s 应保持包私有（公开入口是 startHttp）", m)
            .isFalse();
      }
    }
  }

  /**
   * 三个公开重载的 javadoc 各自带 {@code @since}。
   *
   * <p><b>为什么读源文件而不是运行时反射</b>：javadoc 在编译期被 {@code javac} 丢弃，运行时反射拿不到（{@code @since} 不是 {@code
   * java.lang.annotation}，{@link java.lang.reflect.Method} 上没有任何 API 可读）。唯一可断言的事实来源是 {@code .java}
   * 源文件本身。此处按 {@code public static AgentToMcpServer startWith(} 逐次定位，回溯到其正上方最近的 {@code /**}
   * 文档块，断言块内含 {@code @since}；并断言这样的公开重载恰好 3 个。
   */
  @Test
  void stdioStartWithOverloadsEachCarrySinceJavadocTag() throws Exception {
    String source = Files.readString(mainSourcePath());
    String signature = "public static AgentToMcpServer startWith(";
    int cursor = 0;
    int publicOverloads = 0;
    while ((cursor = source.indexOf(signature, cursor)) >= 0) {
      int docStart = source.lastIndexOf("/**", cursor);
      assertThat(docStart).as("公开 startWith 前必须有 javadoc 块").isGreaterThan(-1);
      String javadoc = source.substring(docStart, cursor);
      assertThat(javadoc).as("公开 startWith 的 javadoc 必须含 @since").contains("@since");
      publicOverloads++;
      cursor += signature.length();
    }
    assertThat(publicOverloads).as("stdio 族公开 startWith 应恰好 3 个").isEqualTo(3);
    // 顺带钉住体例：@since 版本号与本仓 pom 版本一致。
    assertThat(source).contains("@since 0.1.0");
  }

  // ---------------------------------------------------------------- 行为：握手 + 动态同步

  /**
   * 以公开 5 参重载（transport 静态类型为 {@link McpServerTransportProvider}）起服务，走真实 MCP 握手：{@code initialize →
   * tools/list → tools/call}；随后断言 Registry 增删增量同步、{@link ToolSpec#noExport()} 工具永不外发。
   */
  @Test
  void publicStdioEntryPointServesHandshakeAndTracksRegistryChanges() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool("echo"));
    // noExport 工具（合成夹具）：无论初始注册还是增量同步都不得进入暴露面。
    registry.register(tool("hidden", ToolSpec.level(AccessToken.SYSTEM, true, false, true)));

    PipedInputStream serverReads = new PipedInputStream();
    PipedOutputStream clientWrites = new PipedOutputStream(serverReads);
    PipedInputStream clientReads = new PipedInputStream();
    PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
    McpServerTransportProvider transport =
        new StdioServerTransportProvider(McpJsonDefaults.getMapper(), serverReads, serverWrites);

    try (AgentToMcpServer server =
        AgentToMcpServer.startWith(registry, "public-entry", "0.1.0", guest(), transport)) {
      // 初始注册：noExport 被挡在暴露面外
      assertThat(server.toolNames()).containsExactly("echo");

      PipeMcpClientTransport clientTransport =
          new PipeMcpClientTransport(McpJsonDefaults.getMapper(), clientReads, clientWrites);
      McpSyncClient client = McpClient.sync(clientTransport).build();
      try {
        client.initialize();
        assertThat(client.listTools().tools())
            .extracting(McpSchema.Tool::name)
            .containsExactly("echo");
        McpSchema.CallToolResult result =
            client.callTool(new McpSchema.CallToolRequest("echo", Map.of("text", "你好")));
        assertThat(result.isError()).isFalse();
        assertThat(wireText(result)).isEqualTo("echo:你好");

        // 动态同步：新增出现、移除消失，与 stdio 语义一致
        registry.register(echoTool("pong"));
        assertThat(server.toolNames()).containsExactlyInAnyOrder("echo", "pong");
        registry.unregister("pong");
        assertThat(server.toolNames()).containsExactly("echo");

        // 增量新增的 noExport 工具仍永不外发
        registry.register(tool("hidden2", ToolSpec.level(AccessToken.SYSTEM, true, false, true)));
        assertThat(server.toolNames()).containsExactly("echo").doesNotContain("hidden", "hidden2");
      } finally {
        client.closeGracefully();
      }
    }
  }

  // ---------------------------------------------------------------- 负向：兄弟接口误传

  /**
   * 把同时实现两个兄弟接口的 provider 以 {@link McpServerTransportProvider} 静态类型传入公开重载 ⇒ 抛可读 {@link
   * IllegalArgumentException}，<b>不是</b> {@link ClassCastException}（守卫见方法体首部）。
   */
  @Test
  void streamableProviderIntoPublicStdioOverloadIsRejectedAsIllegalArgument() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool("echo"));
    McpServerTransportProvider disguised = new DualModeProvider();

    assertThatThrownBy(
            () ->
                AgentToMcpServer.startWith(
                    registry,
                    "public-guard",
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

  // ---------------------------------------------------------------- 夹具

  private static ToolContext guest() {
    return ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST));
  }

  private static String wireText(McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = result.content();
    return content.get(0) instanceof McpSchema.TextContent t ? t.text() : content.toString();
  }

  private static AgentTool echoTool(String name) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
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
        return ToolResult.ok(name + ":" + context.arguments().getOrDefault("text", ""));
      }
    };
  }

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

  /** 定位主源文件：Maven Surefire 的工作目录默认是模块根，但为防从仓库根直接跑 IDE/命令行，退化为仓库根相对路径。 */
  private static Path mainSourcePath() {
    Path direct = Paths.get(MAIN_SOURCE);
    if (Files.exists(direct)) {
      return direct;
    }
    return Paths.get("AgentLibMosire", MAIN_SOURCE);
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
