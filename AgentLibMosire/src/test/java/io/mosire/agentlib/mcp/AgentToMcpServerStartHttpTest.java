package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalEventTypes;
import io.mosire.agentlib.approval.ApprovalHttpEndpoint;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.HttpApprovalChannel;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.llm.ToolAssetResolver;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * P1-B {@code AgentToMcpServer.startHttp} 两重载的<b>全套验收</b>：生命周期所有权、host 可配、真 authorizer、端到端审批。
 *
 * <p>证据线是<b>真线</b>：SDK 官方 client（{@code HttpClientStreamableHttpTransport} + {@code
 * McpClient.sync}）经真实 socket 完成 {@code initialize → tools/list → tools/call}；审批侧用真 {@link
 * PendingApprovals} + 真 {@link ApprovalCoordinator} + 真 {@link ApprovalHttpEndpoint} + 真 {@link
 * HttpApprovalChannel}，只有"人怎么点"这一步由用例 扮演（经真 HTTP 端点）。
 *
 * <p>端口取 {@code 0}（系统分配）后从 {@link AgentToMcpServer#boundPort()} 读回，绝不硬编码，避免并行/本机端口冲突。
 */
@Timeout(60)
class AgentToMcpServerStartHttpTest {

  private static final String PATH = "/mcp";
  private static final String SERVER_NAME = "start-http-test";
  private static final String VERSION = "0.1.0";
  private static final String CLASS_KEY = "bash:ask:apt";
  private static final String APPROVALS_PATH = "/api/approvals";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient RAW_HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  // ---------------------------------------------------------------- ① 三连

  /** owned 形态：官方 client {@code initialize → tools/list → tools/call} 三连跑通。 */
  @Test
  void ownedServerCompletesInitializeListAndCall() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    try (AgentToMcpServer server = owned(registry, guest(), plainAuthorizer());
        McpSyncClient client = newClient(server.boundPort())) {
      McpSchema.InitializeResult init = client.initialize();
      assertThat(init.serverInfo().name()).isEqualTo(SERVER_NAME);
      assertThat(server.boundPort()).as("owned 形态暴露实际绑定端口").isPositive();
      assertThat(client.listTools().tools())
          .extracting(McpSchema.Tool::name)
          .containsExactly("echo");
      McpSchema.CallToolResult result =
          client.callTool(new McpSchema.CallToolRequest("echo", Map.of("text", "hi")));
      assertThat(result.isError()).isFalse();
      assertThat(wireText(result)).isEqualTo("echo:hi");
    }
  }

  // ---------------------------------------------------------------- ② noExport 不外发

  /** 带 {@link ToolSpec#noExport()} 的工具不得出现在 {@code tools/list}，且调用它失败（B4）。 */
  @Test
  void noExportToolNeverAppearsAndCallingItFails() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    registry.register(noExportTool());
    try (AgentToMcpServer server = owned(registry, guest(), plainAuthorizer());
        McpSyncClient client = newClient(server.boundPort())) {
      client.initialize();
      assertThat(client.listTools().tools())
          .extracting(McpSchema.Tool::name)
          .containsExactly("echo")
          .doesNotContain("hidden");
      assertThatThrownBy(() -> client.callTool(new McpSchema.CallToolRequest("hidden", Map.of())))
          .as("noExport 工具对客户端不存在，调用必须失败")
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Unknown tool");
    }
  }

  // ---------------------------------------------------------------- ③ wire code 口径

  /**
   * 工具<b>结果</b>错误仍走 {@code [mosire:code=…]}（B3 的承诺面）；未知工具错误维持既有<b>裸消息</b>形态（无标记）。
   *
   * <p>未知工具分支是 {@code handleCall} 的防御路径——SDK 在协议层先按 server 工具表拒未知名，故这里经反射直调该分支，
   * 把"裸消息形态不得被顺手改成带码"钉住（设计 §六 C4）。
   */
  @Test
  void toolResultErrorCarriesWireCodeButUnknownToolKeepsBareMessage() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(errorTool());
    try (AgentToMcpServer server = owned(registry, guest(), plainAuthorizer());
        McpSyncClient client = newClient(server.boundPort())) {
      client.initialize();
      McpSchema.CallToolResult result =
          client.callTool(new McpSchema.CallToolRequest("boom", Map.of()));
      assertThat(result.isError()).isTrue();
      assertThat(wireText(result)).startsWith("[mosire:code=BOOM]");

      Method handleCall =
          AgentToMcpServer.class.getDeclaredMethod(
              "handleCall",
              ToolRegistry.class,
              ToolContext.class,
              ToolCallAuthorizer.class,
              ToolAssetResolver.class,
              McpSchema.CallToolRequest.class);
      handleCall.setAccessible(true);
      McpSchema.CallToolResult bare =
          (McpSchema.CallToolResult)
              handleCall.invoke(
                  null,
                  registry,
                  guest(),
                  plainAuthorizer(),
                  ToolAssetResolver.none(),
                  new McpSchema.CallToolRequest("ghost", Map.of()));
      assertThat(bare.isError()).isTrue();
      assertThat(wireText(bare))
          .as("未知工具错误维持既有裸消息形态（不经 wire code）")
          .isEqualTo("工具不存在: ghost")
          .doesNotContain("[mosire:code=");
    }
  }

  // ---------------------------------------------------------------- ④ 真 authorizer

  /**
   * 真 authorizer + {@code Ask} 工具 + <b>无可用渠道</b> ⇒ fail-closed {@code APPROVAL_DENIED}，工具体不跑（B2）。
   */
  @Test
  void askToolWithNoAvailableChannelIsApprovalDeniedAndBodyNeverRuns() throws Exception {
    AtomicBoolean bodyRan = new AtomicBoolean(false);
    ToolRegistry registry = new ToolRegistry();
    registry.register(askTool(bodyRan));
    PendingApprovals pending = new PendingApprovals();
    HttpApprovalChannel channel = new HttpApprovalChannel(pending); // 不 markUp：面未就绪
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(), List.of((ApprovalChannel) channel), pending, Duration.ofSeconds(5), null);

    try (AgentToMcpServer server =
            owned(
                registry,
                systemCaller(),
                ToolCallAuthorizer.of(new ToolExecutionGuard(), coordinator));
        McpSyncClient client = newClient(server.boundPort())) {
      client.initialize();
      McpSchema.CallToolResult result =
          client.callTool(new McpSchema.CallToolRequest("apt_install", Map.of("pkg", "curl")));
      assertThat(result.isError()).isTrue();
      assertThat(wireText(result))
          .startsWith("[mosire:code=" + ToolCallAuthorizer.APPROVAL_DENIED + "]");
      assertThat(bodyRan).as("无可用渠道 = fail-closed，工具体绝不执行").isFalse();
    }
  }

  /** 真 authorizer + {@code Ask} 工具 + <b>有批准渠道</b> ⇒ 人的答复让调用真的执行（断言副作用，不只看 isError）。 */
  @Test
  void askToolWithAnApprovedChannelReallyExecutes() throws Exception {
    AtomicBoolean bodyRan = new AtomicBoolean(false);
    ToolRegistry registry = new ToolRegistry();
    registry.register(askTool(bodyRan));
    PendingApprovals pending = new PendingApprovals();
    HttpApprovalChannel channel = new HttpApprovalChannel(pending);
    channel.markUp();
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(), List.of((ApprovalChannel) channel), pending, Duration.ofSeconds(10), null);

    ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    try (AgentToMcpServer server =
            owned(
                registry,
                systemCaller(),
                ToolCallAuthorizer.of(new ToolExecutionGuard(), coordinator));
        McpSyncClient client = newClient(server.boundPort())) {
      client.initialize();
      Future<McpSchema.CallToolResult> call =
          calls.submit(
              () ->
                  client.callTool(
                      new McpSchema.CallToolRequest("apt_install", Map.of("pkg", "curl"))));

      ApprovalRequest served = awaitPending(pending, Duration.ofSeconds(10));
      assertThat(served.tool()).isEqualTo("apt_install");
      assertThat(pending.decide(served.id(), ApprovalDecision.APPROVE_ONCE, "http")).isTrue();

      McpSchema.CallToolResult result = call.get(10, TimeUnit.SECONDS);
      assertThat(result.isError()).isFalse();
      assertThat(wireText(result)).isEqualTo("installed:curl");
      assertThat(bodyRan).as("审批放行之后工具体真的执行（断言副作用）").isTrue();
    } finally {
      calls.shutdownNow();
    }
  }

  // ---------------------------------------------------------------- ⑤ 端到端审批

  /**
   * 端到端：client {@code tools/call} → 真 HTTP 审批面列出待裁决 → {@code POST} 决议 → 执行 + {@code
   * approval.decided} 审计。
   */
  @Test
  void endToEndApprovalOverHttpApproveRunsToolAndEmitsAuditEvent() throws Exception {
    AtomicBoolean bodyRan = new AtomicBoolean(false);
    ToolRegistry registry = new ToolRegistry();
    registry.register(askTool(bodyRan));
    EventBus bus = new EventBus();
    EventCollector events = new EventCollector();
    EventBus.Subscription subscription = bus.subscribe(events);
    ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    try (ApprovalRig rig = new ApprovalRig(bus);
        AgentToMcpServer server =
            owned(
                registry,
                systemCaller(),
                ToolCallAuthorizer.of(new ToolExecutionGuard(), rig.coordinator));
        McpSyncClient client = newClient(server.boundPort())) {
      client.initialize();
      Future<McpSchema.CallToolResult> call =
          calls.submit(
              () ->
                  client.callTool(
                      new McpSchema.CallToolRequest("apt_install", Map.of("pkg", "curl"))));

      JsonNode listed = awaitApprovalListed(rig.base(), Duration.ofSeconds(10));
      String id = listed.get("id").asText();
      assertThat(listed.get("tool").asText()).isEqualTo("apt_install");

      HttpResponse<String> decided =
          rawPost(
              rig.base() + APPROVALS_PATH + "/" + id,
              "{\"decision\":\"approve\",\"scope\":\"once\",\"by\":\"值班人\"}");
      assertThat(decided.statusCode()).isEqualTo(200);

      McpSchema.CallToolResult result = call.get(10, TimeUnit.SECONDS);
      assertThat(result.isError()).isFalse();
      assertThat(wireText(result)).isEqualTo("installed:curl");
      assertThat(bodyRan).isTrue();

      Event audit =
          events.awaitType(ApprovalEventTypes.DECIDED, Duration.ofSeconds(5)).orElseThrow();
      JsonNode payload = JSON.readTree(audit.payload());
      assertThat(payload.get("id").asText()).isEqualTo(id);
      assertThat(payload.get("decision").asText()).isEqualTo("APPROVE_ONCE");
      assertThat(payload.get("scope").asText()).isEqualTo("once");
    } finally {
      subscription.close();
      calls.shutdownNow();
      bus.close();
    }
  }

  /** 端到端（反向）：决议为 DENY ⇒ 调用失败且工具体不跑，审计事件照发。 */
  @Test
  void endToEndApprovalOverHttpDenyKeepsTheToolBodyUnrun() throws Exception {
    AtomicBoolean bodyRan = new AtomicBoolean(false);
    ToolRegistry registry = new ToolRegistry();
    registry.register(askTool(bodyRan));
    EventBus bus = new EventBus();
    EventCollector events = new EventCollector();
    EventBus.Subscription subscription = bus.subscribe(events);
    ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
    try (ApprovalRig rig = new ApprovalRig(bus);
        AgentToMcpServer server =
            owned(
                registry,
                systemCaller(),
                ToolCallAuthorizer.of(new ToolExecutionGuard(), rig.coordinator));
        McpSyncClient client = newClient(server.boundPort())) {
      client.initialize();
      Future<McpSchema.CallToolResult> call =
          calls.submit(
              () ->
                  client.callTool(
                      new McpSchema.CallToolRequest("apt_install", Map.of("pkg", "curl"))));

      JsonNode listed = awaitApprovalListed(rig.base(), Duration.ofSeconds(10));
      String id = listed.get("id").asText();
      assertThat(
              rawPost(rig.base() + APPROVALS_PATH + "/" + id, "{\"decision\":\"deny\"}")
                  .statusCode())
          .isEqualTo(200);

      McpSchema.CallToolResult result = call.get(10, TimeUnit.SECONDS);
      assertThat(result.isError()).isTrue();
      assertThat(wireText(result))
          .startsWith("[mosire:code=" + ToolCallAuthorizer.APPROVAL_DENIED + "]");
      assertThat(bodyRan).isFalse();

      Event audit =
          events.awaitType(ApprovalEventTypes.DECIDED, Duration.ofSeconds(5)).orElseThrow();
      assertThat(JSON.readTree(audit.payload()).get("decision").asText()).isEqualTo("DENY");
    } finally {
      subscription.close();
      calls.shutdownNow();
      bus.close();
    }
  }

  // ---------------------------------------------------------------- ⑥ 生命周期

  /** caller-owned：{@code close()} 后调用方 server 仍服务、调用方 executor 未被 shutdown、MCP context 已摘除（A4）。 */
  @Test
  void callerOwnedCloseLeavesTheCallersServerAndExecutorAlive() throws Exception {
    ExecutorService callerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    HttpServer callerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    callerServer.setExecutor(callerExecutor);
    callerServer.createContext("/probe", exchange -> respond(exchange, 200, "pong"));
    callerServer.start();
    int port = callerServer.getAddress().getPort();

    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    AgentToMcpServer server =
        AgentToMcpServer.startHttp(
            callerServer, PATH, registry, SERVER_NAME, VERSION, guest(), plainAuthorizer());
    try {
      assertThat(server.boundPort()).isEqualTo(port);
      try (McpSyncClient client = newClient(port)) {
        client.initialize();
        assertThat(
                wireText(
                    client.callTool(new McpSchema.CallToolRequest("echo", Map.of("text", "x")))))
            .isEqualTo("echo:x");
      }

      server.close();

      assertThat(rawGet("http://127.0.0.1:" + port + "/probe").statusCode())
          .as("caller-owned close 不得停调用方的 server")
          .isEqualTo(200);
      assertThat(callerExecutor.submit(() -> "alive").get(2, TimeUnit.SECONDS))
          .as("caller-owned close 不得 shutdown 调用方的 executor")
          .isEqualTo("alive");
      assertThat(rawGet("http://127.0.0.1:" + port + PATH).statusCode())
          .as("close 只摘自己挂的 context")
          .isNotEqualTo(200);
    } finally {
      callerServer.stop(0);
      callerExecutor.shutdownNow();
    }
  }

  /** owned：{@code close()} 后端口释放（可重绑）且自建 executor 已关。 */
  @Test
  void ownedCloseReleasesThePortAndShutsDownItsExecutor() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    AgentToMcpServer server = owned(registry, guest(), plainAuthorizer());
    int port = server.boundPort();
    try (McpSyncClient client = newClient(port)) {
      client.initialize();
    }
    ExecutorService ownedExecutor = ownedExecutorOf(server);
    assertThat(ownedExecutor.isShutdown()).as("close 前 executor 仍在跑").isFalse();

    server.close();

    assertThat(ownedExecutor.isShutdown()).as("owned close 必须 shutdown 自建 executor").isTrue();
    HttpServer rebound = rebindEventually(port);
    assertThat(rebound.getAddress().getPort()).as("端口已释放，可重绑").isEqualTo(port);
    rebound.stop(0);
  }

  // ---------------------------------------------------------------- ⑦ 公开面形状

  /** 反射断言 {@code startHttp} 两重载（+ 便捷重载）为 public；provider 类保持包私有、不出现在任何公开签名里。 */
  @Test
  void startHttpOverloadsArePublicAndProviderStaysPackagePrivate() throws Exception {
    Method callerOwned =
        AgentToMcpServer.class.getDeclaredMethod(
            "startHttp",
            HttpServer.class,
            String.class,
            ToolRegistry.class,
            String.class,
            String.class,
            ToolContext.class,
            ToolCallAuthorizer.class);
    Method owned =
        AgentToMcpServer.class.getDeclaredMethod(
            "startHttp",
            String.class,
            int.class,
            String.class,
            ToolRegistry.class,
            String.class,
            String.class,
            ToolContext.class,
            ToolCallAuthorizer.class);
    Method ownedConvenience =
        AgentToMcpServer.class.getDeclaredMethod(
            "startHttp",
            int.class,
            String.class,
            ToolRegistry.class,
            String.class,
            String.class,
            ToolContext.class,
            ToolCallAuthorizer.class);

    for (Method method : List.of(callerOwned, owned, ownedConvenience)) {
      assertThat(Modifier.isPublic(method.getModifiers())).as("%s 必须 public", method).isTrue();
      assertThat(Modifier.isStatic(method.getModifiers())).as("%s 必须 static", method).isTrue();
      assertThat(method.getReturnType()).isEqualTo(AgentToMcpServer.class);
      assertThat(method.getParameterTypes())
          .as("公开签名不得暴露 provider 类")
          .doesNotContain(JdkHttpStreamableServerTransportProvider.class);
    }

    assertThat(Modifier.isPublic(JdkHttpStreamableServerTransportProvider.class.getModifiers()))
        .as("provider 类必须保持包私有（公开面只有 startHttp）")
        .isFalse();

    for (Method method : AgentToMcpServer.class.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers())) {
        assertThat(method.getParameterTypes())
            .as("任何公开方法都不得以 provider 类为形参: %s", method)
            .doesNotContain(JdkHttpStreamableServerTransportProvider.class);
      }
    }
  }

  // ---------------------------------------------------------------- 夹具

  private static AgentToMcpServer owned(
      ToolRegistry registry, ToolContext caller, ToolCallAuthorizer authorizer) {
    return AgentToMcpServer.startHttp(0, PATH, registry, SERVER_NAME, VERSION, caller, authorizer);
  }

  private static McpSyncClient newClient(int port) {
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
            .endpoint(PATH)
            .resumableStreams(false)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    return McpClient.sync(transport).build();
  }

  private static ToolCallAuthorizer plainAuthorizer() {
    return ToolCallAuthorizer.of(new ToolExecutionGuard());
  }

  private static ToolContext guest() {
    return ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST));
  }

  private static ToolContext systemCaller() {
    return ToolContext.of(AccessToken.SYSTEM, AgentPermissionSet.system());
  }

  private static String wireText(McpSchema.CallToolResult result) {
    List<McpSchema.Content> content = result.content();
    return content.get(0) instanceof McpSchema.TextContent text ? text.text() : content.toString();
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

  private static AgentTool errorTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "boom";
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.error("BOOM", "炸了");
      }
    };
  }

  private static AgentTool askTool(AtomicBoolean bodyRan) {
    return new AgentTool() {
      @Override
      public String name() {
        return "apt_install";
      }

      @Override
      public String description() {
        return "安装一个系统包（测试用；真实形态 = C 包的 bash）";
      }

      @Override
      public ToolResult execute(ToolContext context) {
        bodyRan.set(true);
        return ToolResult.ok("installed:" + context.arguments().getOrDefault("pkg", ""));
      }

      @Override
      public ToolGate gate(ToolContext context) {
        return new ToolGate.Ask(CLASS_KEY, "安装系统包 " + context.arguments().getOrDefault("pkg", ""));
      }
    };
  }

  /** 真 HTTP 审批面装配（端口 0，绑 127.0.0.1）：登记表 + HTTP 通道 + 编排器 + 端点。 */
  private static final class ApprovalRig implements AutoCloseable {

    private final PendingApprovals pending = new PendingApprovals();
    private final HttpApprovalChannel channel = new HttpApprovalChannel(pending);
    private final ApprovalCoordinator coordinator;
    private final ApprovalHttpEndpoint endpoint;

    ApprovalRig(EventBus bus) {
      this.coordinator =
          new ApprovalCoordinator(
              List.of(), List.of((ApprovalChannel) channel), pending, Duration.ofSeconds(10), bus);
      this.endpoint = ApprovalHttpEndpoint.start(0, pending, coordinator);
      channel.markUp();
    }

    String base() {
      return "http://127.0.0.1:" + endpoint.boundPort();
    }

    @Override
    public void close() {
      endpoint.close();
      channel.close();
    }
  }

  /** 事件收集器：等某类型事件出现（EventBus 异步分发）。 */
  private static final class EventCollector implements EventBus.ProcessEventListener {

    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onEvent(Event event) {
      events.add(event);
    }

    Optional<Event> awaitType(String type, Duration limit) {
      long deadlineNanos = System.nanoTime() + limit.toNanos();
      while (System.nanoTime() < deadlineNanos) {
        synchronized (events) {
          for (Event event : events) {
            if (type.equals(event.type())) {
              return Optional.of(event);
            }
          }
        }
        sleep(5L);
      }
      return Optional.empty();
    }
  }

  // ---------------------------------------------------------------- 等待/HTTP/反射辅助

  private static ApprovalRequest awaitPending(PendingApprovals pending, Duration limit) {
    long deadlineNanos = System.nanoTime() + limit.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      List<ApprovalRequest> snapshot = pending.pending();
      if (!snapshot.isEmpty()) {
        return snapshot.get(0);
      }
      sleep(5L);
    }
    throw new AssertionError("审批请求未出现在登记表（等了 " + limit + "）");
  }

  private static JsonNode awaitApprovalListed(String base, Duration limit) throws Exception {
    long deadlineNanos = System.nanoTime() + limit.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      HttpResponse<String> response = rawGet(base + APPROVALS_PATH);
      JsonNode pending = JSON.readTree(response.body()).get("pending");
      if (pending != null && pending.size() > 0) {
        return pending.get(0);
      }
      sleep(10L);
    }
    throw new AssertionError("GET " + APPROVALS_PATH + " 未列出待裁决项（等了 " + limit + "）");
  }

  private static HttpServer rebindEventually(int port) throws Exception {
    IOException last = null;
    long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadlineNanos) {
      try {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
      } catch (IOException e) {
        last = e;
        sleep(50L);
      }
    }
    throw new AssertionError("owned close 后端口 " + port + " 未能释放/重绑", last);
  }

  private static ExecutorService ownedExecutorOf(AgentToMcpServer server) throws Exception {
    Field field = AgentToMcpServer.class.getDeclaredField("ownedExecutor");
    field.setAccessible(true);
    return (ExecutorService) field.get(server);
  }

  private static HttpResponse<String> rawGet(String url) throws Exception {
    return RAW_HTTP.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> rawPost(String url, String body) throws Exception {
    return RAW_HTTP.send(
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
