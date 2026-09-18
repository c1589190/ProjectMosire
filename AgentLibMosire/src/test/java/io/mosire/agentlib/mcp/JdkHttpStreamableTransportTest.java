package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link JdkHttpStreamableServerTransportProvider}（P1-B 传输核）的判别性用例。
 *
 * <p>两条证据线，缺一不可：
 *
 * <ol>
 *   <li><b>真线协议</b>：SDK <b>官方</b> client（{@code HttpClientStreamableHttpTransport} + {@code
 *       McpClient.sync}） 经真实 HTTP 完成 {@code initialize → tools/list → tools/call}；
 *   <li><b>裸 HTTP 断言</b>：脱离 SDK client 直接发 HTTP——两端同源 SDK 会互相容忍对称缺陷（例如双方都不写/不读 {@code
 *       Mcp-Session-Id} 仍能握手），故必须独立钉住响应头、拒答与 SSE 帧形状。
 * </ol>
 *
 * <p><b>端口取 0</b>（系统分配）后从 {@code HttpServer.getAddress().getPort()} 读回——绝不硬编码 5715，避免并行/本机端口冲突。
 *
 * <p><b>executor 必须是虚拟线程池</b>：{@code HttpServer} 默认 executor 是单线程，将来 GET 监听流一旦停驻就会与后续 POST 互锁；本
 * todo 虽只返回 405，仍按生产形态装配，把风险钉在装配线上。
 *
 * <p>本类不实现 GET 监听流（todo 7）：官方 client 在 initialize 后会自动尝试建 GET 流，本 provider 回 405 ⇒ client 退化为
 * request-response 模式（SDK 明确分支），POST 三连照常跑通。
 */
@Timeout(60)
class JdkHttpStreamableTransportTest {

  private static final String PATH = "/mcp";
  private static final String SERVER_NAME = "jdk-http-test";

  /** 裸 HTTP 客户端（不依赖 SDK，见类注释的 B6 理由）。 */
  private static final HttpClient RAW_HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private HttpServer httpServer;
  private ExecutorService executor;
  private AgentToMcpServer server;
  private McpSyncClient client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      try {
        client.closeGracefully();
      } catch (Exception ignored) {
        // 关停清理失败不影响用例判定（server 侧仍会被 stop）
      }
    }
    if (server != null) {
      try {
        server.close();
      } catch (Exception ignored) {
        // 同上
      }
    }
    if (httpServer != null) {
      httpServer.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  /**
   * 官方 client 三连：{@code initialize → tools/list → tools/call}。
   *
   * <p>判别性：把 POST request 分支改成"订阅即返回"（不等 {@code responseStream} 的 Mono）⇒ {@code handle()} 返回即关连接，
   * SSE 被截断，{@code callTool} 失败（变异自证②）。把 SSE 帧末尾空行去掉 ⇒ 客户端不 dispatch 事件，同样红（变异自证①）。
   */
  @Test
  void officialClientCompletesInitializeListAndCall() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    registry.register(noExportTool());
    int port = startRig(registry);

    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
            .endpoint(PATH)
            .resumableStreams(false)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    client = McpClient.sync(transport).build();

    McpSchema.InitializeResult init = client.initialize();
    assertThat(init.serverInfo().name()).isEqualTo(SERVER_NAME);

    McpSchema.ListToolsResult tools = client.listTools();
    assertThat(tools.tools())
        .as("noExport 工具不得出现在 tools/list")
        .extracting(McpSchema.Tool::name)
        .containsExactly("echo");

    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest("echo", Map.of("text", "hi")));
    assertThat(result.isError()).isFalse();
    assertThat(wireText(result)).isEqualTo("echo:hi");
  }

  /** 裸 HTTP 断言①：initialize 响应头<b>确有</b> {@code Mcp-Session-Id}（不靠 SDK client 的容忍）。 */
  @Test
  void rawHttpInitializeAdvertisesSessionId() throws Exception {
    int port = startRig(registryWithEcho());

    HttpResponse<String> response = rawPost(port, initializeBody(1), null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Mcp-Session-Id"))
        .as("initialize 应答必须发放会话头")
        .isPresent()
        .get()
        .asString()
        .isNotBlank();
  }

  /** 裸 HTTP 断言②：拿到会话后，后续不带 {@code Mcp-Session-Id} 的 POST 被拒（非 200，实为 400）。 */
  @Test
  void rawHttpPostWithoutSessionIdIsRejected() throws Exception {
    int port = startRig(registryWithEcho());
    rawPost(port, initializeBody(1), null);

    HttpResponse<String> response = rawPost(port, toolsListBody(2), null);

    assertThat(response.statusCode()).as("缺会话头的非 initialize POST 必须被拒").isNotEqualTo(200);
    assertThat(response.statusCode()).isEqualTo(400);
  }

  /** 裸 HTTP 断言③：普通 request 的响应体是 SSE 帧（{@code event: message} + {@code data: {...}}）。 */
  @Test
  void rawHttpRequestResponseStreamIsSseFramed() throws Exception {
    int port = startRig(registryWithEcho());
    HttpResponse<String> init = rawPost(port, initializeBody(1), null);
    String sessionId = init.headers().firstValue("Mcp-Session-Id").orElseThrow();

    HttpResponse<String> response = rawPost(port, toolsListBody(2), sessionId);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type"))
        .get()
        .asString()
        .contains("text/event-stream");
    assertThat(response.body())
        .as(
            "SSE 帧形如 id/event/data 且以空行结尾——缺空行时官方 client 会在 EOF 补 flush（同源容忍），"
                + "故帧终止符只能由裸 HTTP 断言钉住")
        .contains("id: ")
        .contains("event: message")
        .contains("data: {")
        .endsWith("\n\n");
  }

  // ---- 装配 ----

  /**
   * 起一套 rig：先 build MCP server（SDK 在此时注入 session factory——装配顺序硬约束），再 createContext + start。
   * 返回系统分配的实际端口。
   */
  private int startRig(ToolRegistry registry) {
    try {
      JdkHttpStreamableServerTransportProvider provider =
          new JdkHttpStreamableServerTransportProvider(PATH);
      server =
          AgentToMcpServer.startWith(
              registry,
              SERVER_NAME,
              "0.1.0",
              guest(),
              provider,
              name -> true,
              ToolCallAuthorizer.standard());
      httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      executor = Executors.newVirtualThreadPerTaskExecutor();
      httpServer.setExecutor(executor);
      httpServer.createContext(PATH, exchange -> handleThenFinalize(provider, exchange));
      httpServer.start();
      return httpServer.getAddress().getPort();
    } catch (IOException e) {
      throw new AssertionError("JDK HTTP 传输装配失败", e);
    }
  }

  /**
   * 包一层：handler 返回后强制关交换，模拟"框架在 handler 返回即收尾连接"的契约（servlet/startAsync 语义）。JDK 21 实测对 chunked
   * exchange <b>不</b>在返回时自动关（见证据探针），故必须显式建模——这样"POST request 分支订阅即返回"的回归才会被 {@code callTool}
   * 钉红（变异自证②）。
   */
  private static void handleThenFinalize(
      JdkHttpStreamableServerTransportProvider provider, HttpExchange exchange) throws IOException {
    try {
      provider.handle(exchange);
    } finally {
      exchange.close();
    }
  }

  private static ToolRegistry registryWithEcho() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    return registry;
  }

  private static ToolContext guest() {
    return ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST));
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
        // 刻意慢 120ms：SDK 对同步工具规格默认 subscribeOn(boundedElastic)（immediateExecution=false），
        // 响应在**另一线程**上产生。handler 若"订阅即返回"（不等 Mono），exchange 会先被关、帧丢失；
        // 有这段延迟，这个竞态才确定性地偏向后完成（变异自证②）。
        try {
          Thread.sleep(120L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        return ToolResult.ok("echo:" + context.arguments().getOrDefault("text", ""));
      }
    };
  }

  /** 带 {@link ToolSpec#noExport()} 的工具：不得进 {@code tools/list}（暴露面收窄的结构性写法）。 */
  private static AgentTool noExportTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "hidden";
      }

      @Override
      public ToolSpec spec() {
        return ToolSpec.level(AccessToken.GUEST, false, false, true);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("hidden");
      }
    };
  }

  private static String wireText(McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = result.content();
    return content.get(0) instanceof McpSchema.TextContent text ? text.text() : content.toString();
  }

  // ---- 裸 HTTP 辅助 ----

  private static HttpResponse<String> rawPost(int port, String jsonBody, String sessionId)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + PATH))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
    if (sessionId != null) {
      builder.header("Mcp-Session-Id", sessionId);
    }
    return RAW_HTTP.send(
        builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static String initializeBody(int id) {
    return "{\"jsonrpc\":\"2.0\",\"id\":"
        + id
        + ",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"raw-test\",\"version\":\"0.1.0\"}}}";
  }

  private static String toolsListBody(int id) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/list\",\"params\":{}}";
  }
}
