package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link JdkHttpStreamableServerTransportProvider} GET 监听流的判别性用例（todo 7）。
 *
 * <p>核心命题：官方 client 在 initialize 后自动开 GET 监听流（{@code Accept: text/event-stream} + {@code
 * Mcp-Session-Id}），服务端必须把 handler 线程<b>停驻</b>在 latch 上、把 {@code notifications/tools/list_changed}
 * 经该流送出，并在关闭时唤醒停驻线程。四条用例：
 *
 * <ol>
 *   <li><b>B5 核心</b>：监听流就绪后 Registry 变更 ⇒ client 收到 {@code list_changed}（先就绪再变更，杜绝竞态）；
 *   <li><b>无泄漏</b>：服务端 {@code close()} 唤醒 parked handler（活跃监听流计数归零）；
 *   <li><b>半开服务端守卫</b>：{@code connectTimeout} 不保护"收下 GET 却不发响应头"，故本类全程 {@code @Timeout(30)} 兜底（评审
 *       MINOR 8）；
 *   <li><b>缺流不冒泡</b>：未建 GET 的会话 {@code sendNotification} 抛 {@code IllegalStateException}，被 {@code
 *       notifyClients} 捕获，其余会话照常收到通知、服务端不崩。
 * </ol>
 *
 * <p>端口取 0；{@code HttpServer} 装虚拟线程 executor（默认单线程 executor 会与 parked GET 互锁）。装配走包私有 {@code
 * AgentToMcpServer.startWith(..., McpStreamableServerTransportProvider, ...)}。
 */
@Timeout(30)
class JdkHttpStreamableListeningStreamTest {

  private static final String PATH = "/mcp";
  private static final String SERVER_NAME = "jdk-http-listen-test";

  /** 就绪/送达轮询上限：避开"initialize 返回不代表 GET 就绪"的竞态，不用固定 sleep。 */
  private static final Duration AWAIT = Duration.ofSeconds(5);

  /** 裸 HTTP 客户端（不依赖 SDK），用于建"无 GET 会话"与断言服务端存活。 */
  private static final HttpClient RAW_HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private HttpServer httpServer;
  private ExecutorService executor;
  private AgentToMcpServer server;
  private JdkHttpStreamableServerTransportProvider provider;
  private McpSyncClient client;
  private McpSyncClient secondClient;

  @AfterEach
  void tearDown() {
    closeQuietly(client);
    closeQuietly(secondClient);
    if (server != null) {
      try {
        server.close();
      } catch (Exception ignored) {
        // 关停清理失败不影响用例判定（httpServer 仍会被 stop）
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
   * 用例①（B5 核心）：监听流就绪后 Registry 增删 ⇒ client 经 GET 监听流收到 {@code notifications/tools/list_changed}。
   *
   * <p>评审 MINOR 10 修正：<b>先确认监听流已建立再变更</b>。{@code initialize} 返回只代表 POST 握手完成，client 的 GET 是随后异步
   * reconnect 打开的；若变更早于 GET 就绪，通知会因 {@code MissingMcpTransportSession} 被丢弃（服务端捕获后无流可送）。故用 自写轮询等
   * {@code activeListeningStreamCount()==1}（不引 Awaitility）。
   */
  @Test
  void registryChangeDeliversListChangedOverGetListeningStream() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("ping"));
    int port = startRig(registry);

    AtomicReference<List<String>> seenTools = new AtomicReference<>(List.of());
    client =
        newClient(port, tools -> seenTools.set(tools.stream().map(McpSchema.Tool::name).toList()));

    McpSchema.InitializeResult init = client.initialize();
    assertThat(init.serverInfo().name()).isEqualTo(SERVER_NAME);

    awaitUntil(
        () -> provider.activeListeningStreamCount() == 1, "GET 监听流建立（initialize 后异步 reconnect）");

    registry.register(tool("pong"));

    awaitUntil(() -> seenTools.get().contains("pong"), "client 经 GET 监听流收到 tools/list_changed");
    assertThat(seenTools.get()).contains("ping", "pong");
  }

  /** 用例②：服务端 {@code close()} 后 parked GET handler 全部退出（活跃监听流计数归零，无线程泄漏）。 */
  @Test
  void closeWakesParkedGetHandlerWithoutLeak() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("ping"));
    int port = startRig(registry);

    client = newClient(port, tools -> {});
    client.initialize();
    awaitUntil(() -> provider.activeListeningStreamCount() == 1, "GET 监听流建立");

    // close() 级联 provider.closeGracefully() → 关会话 → transport.close() → 倒数 latch 唤醒 parked handler。
    server.close();

    awaitUntil(() -> provider.activeListeningStreamCount() == 0, "parked GET handler 退出、计数归零");
    assertThat(provider.activeListeningStreamCount()).isZero();
  }

  /**
   * 用例③（评审 MINOR 8）：{@code connectTimeout} 只保护连接建立——服务端收下 GET 却不发响应头时，客户端会无限挂起。 本用例用"半开
   * server"实证该风险（连接成功，但只有请求级 {@code timeout} 能解），从而钉住"本类必须全程
   * {@code @Timeout(30)}"这一约束：否则任一用例遇到半开服务端就会永久挂住而非失败。
   */
  @Test
  void acceptedGetWithNoResponseHeadersNeedsRequestLevelTimeout() throws Exception {
    HttpServer halfOpen = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService rogueExecutor = Executors.newVirtualThreadPerTaskExecutor();
    halfOpen.setExecutor(rogueExecutor);
    // 故意收下 GET 却永不 sendResponseHeaders / close：模拟"连接建立成功但响应头不来"的半开服务端。
    halfOpen.createContext(
        PATH,
        exchange -> {
          try {
            Thread.sleep(Duration.ofSeconds(10).toMillis());
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
    halfOpen.start();
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("http://127.0.0.1:" + halfOpen.getAddress().getPort() + PATH))
              .header("Accept", "text/event-stream")
              .header("Mcp-Session-Id", "s")
              .timeout(Duration.ofSeconds(2))
              .GET()
              .build();

      assertThatThrownBy(() -> RAW_HTTP.send(request, HttpResponse.BodyHandlers.ofString()))
          .as("连接已建立（connectTimeout 不报错），但服务端不发响应头 ⇒ 只有请求级 timeout 能解")
          .isInstanceOf(HttpTimeoutException.class);
    } finally {
      halfOpen.stop(0);
      rogueExecutor.shutdownNow();
    }
  }

  /**
   * 用例④：未建 GET 监听流的会话（{@code listeningStreamRef == MissingMcpTransportSession}）在广播时抛 {@code
   * IllegalStateException}，必须被 {@code notifyClients} 单会话捕获、<b>不冒泡</b>；同一轮广播里的其余会话照常收到 {@code
   * list_changed}，服务端不崩。
   */
  @Test
  void notifyClientsSwallowsMissingStreamAndOtherSessionsStillReceive() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool("ping"));
    int port = startRig(registry);

    // 会话 A：裸 HTTP initialize 拿到会话头，但**从不发 GET** ⇒ listeningStreamRef 保持 Missing。
    HttpResponse<String> init = rawPost(port, initializeBody(1), null);
    assertThat(init.statusCode()).isEqualTo(200);
    String sessionA = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
    assertThat(sessionA).isNotBlank();

    // 会话 B：官方 client，建立 GET 监听流。
    AtomicReference<List<String>> seenTools = new AtomicReference<>(List.of());
    secondClient =
        newClient(port, tools -> seenTools.set(tools.stream().map(McpSchema.Tool::name).toList()));
    secondClient.initialize();
    awaitUntil(() -> provider.activeListeningStreamCount() == 1, "会话 B 的 GET 监听流建立");

    // 广播：会话 A 的 sendNotification 抛 IllegalStateException，必须被捕获、不冒泡；会话 B 照常收到。
    assertThatCode(() -> registry.register(tool("pong"))).doesNotThrowAnyException();
    awaitUntil(() -> seenTools.get().contains("pong"), "其余会话照常收到 list_changed");

    // 服务端仍存活：会话 A 的普通 POST 仍正常应答（200 + SSE），且新工具可见。
    HttpResponse<String> list = rawPost(port, toolsListBody(2), sessionA);
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(list.body()).contains("pong");
  }

  // ---- 装配 ----

  /**
   * 起一套 rig：先 build MCP server（SDK 在此时注入 session factory），再 createContext + start。返回系统分配端口；
   * provider 存字段供就绪轮询。
   */
  private int startRig(ToolRegistry registry) {
    try {
      provider = new JdkHttpStreamableServerTransportProvider(PATH);
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
   * exchange <b>不</b>在返回时自动关（见设计文档 §一/证据探针），故必须显式建模，才能让"GET 不停驻"的回归被确定性钉住。
   */
  private static void handleThenFinalize(
      JdkHttpStreamableServerTransportProvider provider, HttpExchange exchange) throws IOException {
    try {
      provider.handle(exchange);
    } finally {
      exchange.close();
    }
  }

  /** 官方 client：{@code HttpClientStreamableHttpTransport} + {@code McpClient.sync} + tools 变更消费者。 */
  private static McpSyncClient newClient(
      int port, java.util.function.Consumer<List<McpSchema.Tool>> toolsChangeConsumer) {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
            .endpoint(PATH)
            .resumableStreams(false)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    return McpClient.sync(transport).toolsChangeConsumer(toolsChangeConsumer).build();
  }

  /** 自写轮询（不引 Awaitility）：到点仍不满足即带语义信息失败；配合类级 {@code @Timeout(30)} 兜底防真挂死。 */
  private static void awaitUntil(BooleanSupplier condition, String what) {
    long deadline = System.nanoTime() + AWAIT.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(20L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("等待被中断: " + what, interrupted);
      }
    }
    throw new AssertionError("等待超时（" + AWAIT.toSeconds() + "s）: " + what);
  }

  private static void closeQuietly(McpSyncClient toClose) {
    if (toClose != null) {
      try {
        toClose.closeGracefully();
      } catch (Exception ignored) {
        // 关停清理失败不影响用例判定
      }
    }
  }

  private static ToolContext guest() {
    return ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST));
  }

  /** 合成工具：名字即行为，仅用于观测暴露面与触发 list_changed。 */
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
        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"raw-listen-test\",\"version\":\"0.1.0\"}}}";
  }

  private static String toolsListBody(int id) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/list\",\"params\":{}}";
  }
}
