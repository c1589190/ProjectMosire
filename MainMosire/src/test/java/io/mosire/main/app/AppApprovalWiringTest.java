package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.mcp.PipeMcpClientTransport;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.StopReason;
import io.mosire.brain.runtime.TurnResult;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 审批面在<b>生产装配路径</b>上的接线判据（S4-B2）：H8（同一份 {@code PendingApprovals}）+ H7 的装配线版本（MCP 面） + fail-closed
 * 的反面对照。
 *
 * <p><b>为什么必须在 App 这一层验</b>：管线层/通道层各自的用例证明不了"装配时把同一份登记表交给了两条路"——那正是本包最可能犯的错 （H8
 * 的判别性就是"两处各建一个登记表"）。这里起真 {@code App}（真 HTTP 面、真编排器、真工具面），人由用例经 {@code POST /api/approvals/{id}}
 * 扮演。
 *
 * <p>两条来路各一条：① 主 Agent 管线（{@code chat} 里模型要求调用工具）；② 外部 MCP 客户端的 {@code tools/call}（App 的 MCP
 * 暴露面）。两条都必须落到<b>同一个</b>审批面与同一份登记表上。
 */
class AppApprovalWiringTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CLASS_KEY = "bash:ask:probe";
  private static final String SUMMARY = "探测命令（工具自报的一行说明）";

  /** 参数里的"密钥样"明文：新面的任何响应里都不许出现它（红线 4：只带 digest/summary/classKey）。 */
  private static final String SECRET = "sk-live-SECRET123";

  @TempDir Path tempDir;

  /**
   * H8：经 HTTP 面决议的那一条，能让阻塞在<b>管线侧</b>的等待返回，工具体随后真的执行。
   *
   * <p>判别性：装配时若 HTTP 面与编排器各用一份 {@code PendingApprovals}（本包最可能犯的错），列表里就有不这条请求 ⇒ {@code
   * awaitPendingItem} 到点抛错；把 {@code askForApproval} 的协调器摘掉 ⇒ 列表里也不会出现它。
   */
  @Test
  void httpDecisionReleasesThePipelineWaitingOnTheSameRegistry() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    Path dataDir = tempDir.resolve("data");
    writeApprovalConfig(dataDir, 25);
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(LlmResponse.toolCall("call-1", "danger_probe", Map.of("token", SECRET)));
    llm.enqueue(LlmResponse.text("已按人的裁决处理"));
    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);

    try (App app = App.start(config, llm, List.of(probeTool(runs)))) {
      assertThat(app.approvalPort()).as("审批 HTTP 面默认开（approval.http 缺省 true）").isGreaterThan(0);
      ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<TurnResult> turn = turns.submit(() -> app.runtime().chat("帮我装个包"));

        JsonNode item = awaitPendingItem(app.approvalPort(), Duration.ofSeconds(20));
        assertThat(item.get("tool").asText()).isEqualTo("danger_probe");
        assertThat(item.get("classKey").asText()).isEqualTo(CLASS_KEY);
        assertThat(item.get("summary").asText()).as("摘要由工具自报（本包不拿参数拼摘要）").isEqualTo(SUMMARY);
        assertThat(item.toString())
            .as("待裁决列表只带 digest/summary/classKey：参数明文一个字节都不许出现")
            .doesNotContain(SECRET);
        assertThat(runs).as("人还没答，工具体绝不能先跑").hasValue(0);

        HttpResponse<String> receipt =
            postDecision(app.approvalPort(), item.get("id").asText(), "{\"decision\":\"approve\"}");
        assertThat(receipt.statusCode()).isEqualTo(200);
        JsonNode receiptBody = JSON.readTree(receipt.body());
        assertThat(receiptBody.get("decision").asText()).isEqualTo("approve");
        assertThat(receiptBody.get("scope").asText())
            .as("主 Agent 单实例（SYSTEM）批一次就是一次")
            .isEqualTo("once");

        assertThat(turn.get(20, TimeUnit.SECONDS).stopReason()).isEqualTo(StopReason.FINISHED);
        assertThat(runs).as("批准之后工具体真的执行了").hasValue(1);
      } finally {
        turns.shutdownNow();
      }
    }
  }

  /**
   * fail-closed 的反面对照（红线 1：本包不得新增放行分支）：人答 {@code deny} ⇒ 工具体一次都不跑，且拒绝的结论回到模型面。
   *
   * <p>判别性：把 POST 的裁决结果丢掉（如只回 200 不写登记表），管线会一直等到超时——工具体虽然仍不跑，但"拒"会以 {@code APPROVAL_DENIED}
   * 之外的形态（超时）出现，第三条断言（工具结果里的文案）转红。
   */
  @Test
  void deniedApprovalFailsClosedAndKeepsTheToolBodyUnfired() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    Path dataDir = tempDir.resolve("data");
    writeApprovalConfig(dataDir, 25);
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(LlmResponse.toolCall("call-1", "danger_probe", Map.of("token", SECRET)));
    llm.enqueue(LlmResponse.text("好的，我不装了"));
    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);

    try (App app = App.start(config, llm, List.of(probeTool(runs)))) {
      ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<TurnResult> turn = turns.submit(() -> app.runtime().chat("帮我装个包"));
        JsonNode item = awaitPendingItem(app.approvalPort(), Duration.ofSeconds(20));

        HttpResponse<String> receipt =
            postDecision(app.approvalPort(), item.get("id").asText(), "{\"decision\":\"deny\"}");
        assertThat(receipt.statusCode()).isEqualTo(200);
        JsonNode receiptBody = JSON.readTree(receipt.body());
        assertThat(receiptBody.get("decision").asText()).isEqualTo("deny");
        assertThat(receiptBody.get("scope").asText()).as("拒没有作用域").isEqualTo("none");

        assertThat(turn.get(20, TimeUnit.SECONDS).stopReason()).isEqualTo(StopReason.FINISHED);
        assertThat(runs).as("被拒的调用绝不执行工具体").hasValue(0);
        assertThat(toolErrors(llm.requests.get(1)))
            .as("拒绝的结论回到了模型面（fail-closed 的可见形态）")
            .singleElement()
            .satisfies(error -> assertThat(error).contains("审批未放行"));
      } finally {
        turns.shutdownNow();
      }
    }
  }

  /**
   * H7 的装配线版本：外部 MCP 客户端经 <b>App 的 stdio MCP 面</b>调一个 {@code Ask} 工具时，判定同样落到审批面（V8 的结构前提）， 且人从 HTTP
   * 面能答上——两处看到同一个 id。
   *
   * <p>MCP 侧的身份是 5 参重载的缺省调用者 {@code GUEST}（= 全体外部 MCP 客户端，桶粒度分不出实例）：人对它批"本会话"会被收窄为一次 ——"下次还问"是设计（S5
   * 身份穿透前不许泛化），回执必须如实回报 {@code once}。
   *
   * <p>判别性：把 {@code App.java} 里的 authorizer 换回不含协调器的 {@code ToolCallAuthorizer.standard()}（或把 5
   * 参重载换成 3 参），本用例的 {@code awaitPendingItem} 到点抛错 ⇒ 转红。
   *
   * <p>装配细节：MCP stdio 传输在构造期捕获 {@code System.in}/{@code System.out}，而 App 起的 MCP server 走的是默认 stdio
   * 传输 ⇒ 起 App 之前把这两个流换成管道、起完立刻还原（其余组件不写 stdout：日志走 stderr）。
   */
  @Test
  void mcpExposedToolCallGoesThroughTheSameApprovalFace() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    Path dataDir = tempDir.resolve("data");
    writeApprovalConfig(dataDir, 25);
    RecordingLlmClient llm = new RecordingLlmClient();
    BootConfig config = new BootConfig(0, dataDir, false, "", null, true, "127.0.0.1", 0);

    PrintStream originalOut = System.out;
    InputStream originalIn = System.in;
    PipedInputStream serverReads = new PipedInputStream();
    PipedOutputStream clientWrites = new PipedOutputStream(serverReads);
    PipedInputStream clientReads = new PipedInputStream();
    PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
    PrintStream protocolOut = new PrintStream(serverWrites, true, StandardCharsets.UTF_8);
    App app;
    System.setIn(serverReads);
    System.setOut(protocolOut);
    try {
      app = App.start(config, llm, List.of(probeTool(runs)));
    } finally {
      System.setIn(originalIn);
      System.setOut(originalOut);
    }

    try (app;
        McpSyncClient client =
            McpClient.sync(
                    new PipeMcpClientTransport(
                        McpJsonDefaults.getMapper(), clientReads, clientWrites))
                .build()) {
      client.initialize();
      ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<McpSchema.CallToolResult> call =
            calls.submit(
                () ->
                    client.callTool(
                        new McpSchema.CallToolRequest("danger_probe", Map.of("token", SECRET))));

        JsonNode item = awaitPendingItem(app.approvalPort(), Duration.ofSeconds(20));
        assertThat(item.get("tool").asText()).isEqualTo("danger_probe");
        assertThat(item.get("classKey").asText()).isEqualTo(CLASS_KEY);
        assertThat(item.toString()).doesNotContain(SECRET);
        assertThat(runs).as("人还没答，MCP 这条路上的工具体也不能先跑").hasValue(0);

        HttpResponse<String> receipt =
            postDecision(
                app.approvalPort(),
                item.get("id").asText(),
                "{\"decision\":\"approve\",\"scope\":\"session\"}");
        assertThat(receipt.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(receipt.body()).get("scope").asText())
            .as("GUEST 桶 = 全体外部 MCP 客户端（身份非唯一）⇒ 人给的「本会话」降级为一次")
            .isEqualTo("once");

        McpSchema.CallToolResult result = call.get(20, TimeUnit.SECONDS);
        assertThat(result.isError()).isFalse();
        assertThat(runs).hasValue(1);
      } finally {
        calls.shutdownNow();
      }
    }
  }

  // ---------- 工具与助手 ----------

  /** 自报 {@link ToolGate.Ask} 的桩工具（真实形态 = C 包的 bash）：体一旦执行就计数。 */
  private static AgentTool probeTool(AtomicInteger runs) {
    return new AgentTool() {
      @Override
      public String name() {
        return "danger_probe";
      }

      @Override
      public String description() {
        return "测试用的待审批工具";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of("token", Map.of("type", "string")));
      }

      @Override
      public ToolGate gate(ToolContext context) {
        return new ToolGate.Ask(CLASS_KEY, SUMMARY);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        runs.incrementAndGet();
        return ToolResult.ok("probe:ran");
      }
    };
  }

  /** 审批装配参数：只写超时（HTTP 面缺省开、端口缺省 0）——超时取小值让"没人答"的失败路径有界。 */
  private static void writeApprovalConfig(Path dataDir, int timeoutSeconds) throws Exception {
    Files.createDirectories(dataDir);
    Files.writeString(
        dataDir.resolve("config.json"),
        "{\"approval\":{\"timeoutSeconds\":" + timeoutSeconds + "}}",
        StandardCharsets.UTF_8);
  }

  /** 轮询待裁决列表直到出现第一项；到点仍为空即判失败（把"没装配上"与"装配成两份登记表"一并抓出来）。 */
  private static JsonNode awaitPendingItem(int port, Duration limit) throws Exception {
    long deadline = System.currentTimeMillis() + limit.toMillis();
    JsonNode rows = null;
    while (System.currentTimeMillis() < deadline) {
      rows = JSON.readTree(get(port).body()).get("pending");
      if (rows.size() > 0) {
        return rows.get(0);
      }
      Thread.sleep(20L);
    }
    throw new AssertionError("审批 HTTP 面上一直没有待裁决项（等了 " + limit + "），最后拿到: " + rows);
  }

  private static HttpResponse<String> get(int port) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(base(port) + "/api/approvals")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> postDecision(int port, String id, String body)
      throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(base(port) + "/api/approvals/" + id))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static String base(int port) {
    return "http://127.0.0.1:" + port;
  }

  /** 请求里的工具结果（失败信息在 {@code error} 字段，见 {@code ContentPart.ToolResult} 契约）。 */
  private static List<String> toolErrors(LlmRequest request) {
    List<String> errors = new ArrayList<>();
    for (var message : request.messages()) {
      for (ContentPart part : message.content()) {
        if (part instanceof ContentPart.ToolResult result && result.error() != null) {
          errors.add(result.error());
        }
      }
    }
    return errors;
  }

  /** 记录请求的脚本桩（本类的判据要读"模型第二次看到的是什么"）。 */
  private static final class RecordingLlmClient implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    void enqueue(LlmResponse response) {
      script.addLast(response);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new LlmException("RecordingLlmClient 脚本已耗空");
      }
      return response;
    }
  }
}
