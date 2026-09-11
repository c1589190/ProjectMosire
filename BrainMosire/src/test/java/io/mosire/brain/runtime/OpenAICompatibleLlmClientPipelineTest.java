package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ModelRoute;
import io.mosire.agentlib.llm.OpenAICompatibleLlmClient;
import io.mosire.agentlib.llm.QuotaExceededException;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.context.BasicContextAssembler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T18 跨层窄集成用例（R3）：<b>真实</b> {@link OpenAICompatibleLlmClient} + {@code jdk.httpserver} 假 OpenAI 端点
 * + {@link AgentPipeline} + 小额度 {@link io.mosire.agentlib.llm.LlmQuota}。
 *
 * <p>为什么必须放在 Brain：验收句要断言"走到 {@link StopReason#QUOTA}"，而 QUOTA 的触发点在 {@link AgentPipeline} （它捕获
 * {@link QuotaExceededException} 并终止为 QUOTA），AgentLib 不能依赖 Brain（D2 单向边界）——该 E2E 不可能只放在 AgentLib 里。
 *
 * <p>全离线：假端点绑 127.0.0.1 + 临时端口 0，无任何真实供应商/网络。
 */
class OpenAICompatibleLlmClientPipelineTest {

  @TempDir Path tempDir;

  /** 每次请求依次取一份脚本（脚本耗尽后复用最后一份）。 */
  private final List<List<byte[]>> callScripts = new CopyOnWriteArrayList<>();

  private final AtomicInteger callsServed = new AtomicInteger();
  private final List<String> requestBodies = new CopyOnWriteArrayList<>();
  private final AtomicReference<Map<String, Object>> toolArguments = new AtomicReference<>();
  private volatile int errorStatus;

  private HttpServer server;
  private ExecutorService executor;
  private SqliteEventStore store;
  private EventBus bus;

  @BeforeEach
  void setUp() throws IOException {
    store = SqliteEventStore.open(tempDir.resolve("events.db"));
    bus = new EventBus();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // 并发服务（R6）：HttpServer 缺省 executor 在调用线程内串行执行，而本用例是"客户端读到一半、服务端继续写"的形态
    // ——单线程服务端会把自己和客户端一起卡住。虚拟线程每任务一个：量小、天然守护（不会拖住构建 JVM）。
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/chat/completions", this::handle);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    executor.shutdownNow();
    bus.close();
    store.close();
  }

  /**
   * 真实 client + 管线 + 小额度配额：第一轮流里的 {@code tool_calls}（arguments 分三块到达）必须被真实解析并执行， 第二轮记账越界后由管线终止为
   * {@link StopReason#QUOTA}。
   *
   * <p>断言刻意压在"账目数值"（已用 110 / 上限 100）与"工具实际收到的参数"上——只断言"有 toolCalls"或"停了"都是空转。
   */
  @Test
  void executesStreamedToolCallThenStopsWithQuota() {
    // 第一轮：arguments 分三块（每块单独都不是合法 JSON）；usage 60+40 = 100（不越界，配额上限正是 100）
    callScripts.add(
        List.of(
            sse(toolCallFrame("{\"te", true)),
            sse(toolCallFrame("xt\":\"hello-", false)),
            sse(toolCallFrame("from-chunks\"}", false)),
            sse(usageFrame(60, 40)),
            DONE_FRAME));
    // 第二轮：普通文本 + usage 5+5 → 账目 110 > 100 → QUOTA
    callScripts.add(List.of(sse(textFrame("第二次回答")), sse(usageFrame(5, 5)), DONE_FRAME));

    AgentPipeline pipeline = pipeline(100);

    TurnResult result = pipeline.run("跑一下 echo");

    assertThat(result.stopReason()).isEqualTo(StopReason.QUOTA);
    assertThat(result.turns()).isEqualTo(2);
    assertThat(result.toolCalls()).isEqualTo(1);
    // 流式 tool_calls 被真实解析（参数跨 chunk 拼接完整）并真实执行
    assertThat(toolArguments.get())
        .containsExactlyInAnyOrderEntriesOf(Map.of("text", "hello-from-chunks"));
    // 工具结果确实回灌进第二次请求（协议下行方向也通）
    assertThat(requestBodies).hasSize(2);
    assertThat(requestBodies.get(1))
        .contains(
            "\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"echo: hello-from-chunks\"");
    // 账目数值来自流里的 usage（拿不到 usage 就只会在 100 处停下或永不停下）：110 = 100 + 10
    assertThat(decisionPayloads())
        .anySatisfy(
            payload ->
                assertThat(payload)
                    .contains("\"decision\":\"QUOTA\"")
                    .contains("token 配额超限")
                    .contains("已用 110")
                    .contains("上限 100"));
    // 两次成功调用各落一条 llm.call（D18）；EventQuery 按 seq 倒序返回，故不依赖顺序、按内容配对
    List<String> llmCallPayloads = payloads(EventTypes.LLM_CALL);
    assertThat(llmCallPayloads).hasSize(2);
    assertThat(llmCallPayloads)
        .anySatisfy(
            payload ->
                assertThat(payload).contains("\"inputTokens\":60").contains("\"outputTokens\":40"))
        .anySatisfy(
            payload ->
                assertThat(payload).contains("\"inputTokens\":5").contains("\"outputTokens\":5"));
  }

  /**
   * R4 pin（管线层，<b>只钉管线可观测面</b>）：供应商 429 在管线上表现为 {@link StopReason#LLM_ERROR}，decision 文案带出 HTTP 429
   * 且不含"配额超限"，且没有任何 {@code llm.call} 记账。
   *
   * <p><b>本用例钉不住什么（如实登记，勿高估）</b>：{@code QuotaExceededException} 是 {@link
   * io.mosire.agentlib.llm.LlmException} 的子类，若客户端在 429 时误抛它、但<b>消息文案保持不变</b>，本用例三条断言全绿 （评审用变异 M5
   * 实测：2/2 通过、退出码 0）——本用例只否证"stopReason 一条论断就够"这一半，不做异常<b>类型</b>判别。
   *
   * <p>异常类型的判别由 AgentLib 侧承担：{@code
   * OpenAICompatibleLlmClientTest.mapsProviderRateLimitToLlmExceptionNotQuotaExceeded} 的 {@code
   * isNotInstanceOf(QuotaExceededException.class)}（同一变异 M5 在那里<b>会红</b>）。此处不发明白盒断言去补类型判别
   * ——类型归协议层，管线层只负责"客户端抛什么，管线落什么"。
   */
  @Test
  void mapsProviderRateLimitToLlmErrorInsteadOfQuota() {
    errorStatus = 429;

    TurnResult result = pipeline(100).run("跑一下");

    assertThat(result.stopReason()).isEqualTo(StopReason.LLM_ERROR);
    List<String> decisions = payloads(EventTypes.DECISION);
    assertThat(decisions).isNotEmpty();
    assertThat(decisions).anySatisfy(payload -> assertThat(payload).contains("429"));
    assertThat(decisions).allSatisfy(payload -> assertThat(payload).doesNotContain("配额超限"));
    assertThat(query(EventTypes.LLM_CALL)).isEmpty();
  }

  // ---------- 装配 ----------

  private AgentPipeline pipeline(long quotaMaxTokens) {
    AgentConfig config = AgentConfig.builder("main").quotaMaxTokens(quotaMaxTokens).build();
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    ModelRoute route =
        ModelRoute.of(
            "test", "http://127.0.0.1:" + server.getAddress().getPort(), "fake-model", "keys.fake");
    OpenAICompatibleLlmClient llm =
        new OpenAICompatibleLlmClient(
            route,
            OpenAICompatibleLlmClient.ApiKeySource.none(),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5));
    return new AgentPipeline(
        config,
        llm,
        registry,
        new ToolExecutionGuard(),
        new BasicContextAssembler(),
        store,
        bus,
        AgentPermissionSet.system().grantedToken(),
        AgentPermissionSet.system());
  }

  private AgentTool echoTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "echo";
      }

      @Override
      public String description() {
        return "回显 text 参数";
      }

      @Override
      public ToolResult execute(ToolContext context) {
        toolArguments.set(context.arguments());
        return ToolResult.ok("echo: " + context.arguments().getOrDefault("text", ""));
      }
    };
  }

  private List<String> decisionPayloads() {
    return payloads(EventTypes.DECISION);
  }

  private List<String> payloads(String type) {
    return query(type).stream().map(Event::payload).toList();
  }

  private List<Event> query(String type) {
    return store.query(new EventQuery("", type, "", -1, 100));
  }

  // ---------- 假 OpenAI 端点 ----------

  private void handle(HttpExchange exchange) throws IOException {
    try {
      requestBodies.add(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      int index = callsServed.getAndIncrement();
      if (errorStatus != 0) {
        byte[] body =
            ("{\"error\":{\"message\":\"Rate limit reached for model\",\"type\":\"rate_limit_error\"}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(errorStatus, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        return;
      }
      List<byte[]> script = callScripts.get(Math.min(index, callScripts.size() - 1));
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0); // 0 = chunked：刻意分块 + flush（R5 判据的刺激）
      OutputStream out = exchange.getResponseBody();
      for (byte[] chunk : script) {
        out.write(chunk);
        out.flush();
        sleepQuietly(20);
      }
      exchange.close();
    } catch (IOException clientHungUp) {
      // 客户端收完 [DONE] 即停读、或读超时后取消连接，都是预期路径
    }
  }

  private static final byte[] DONE_FRAME = bytes("data: [DONE]\n\n");

  private static byte[] sse(String json) {
    return bytes("data: " + json + "\n\n");
  }

  private static String textFrame(String content) {
    return "{\"model\":\"fake-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
        + content
        + "\"}}]}";
  }

  private static String usageFrame(long inputTokens, long outputTokens) {
    return "{\"choices\":[],\"usage\":{\"prompt_tokens\":"
        + inputTokens
        + ",\"completion_tokens\":"
        + outputTokens
        + "}}";
  }

  /**
   * 工具调用帧：{@code id/name} 只在首帧给出（同 OpenAI 语义——重复给出会让"取首个非空"以外的心智模型出错）， arguments 由调用方切块传入（分块刻意不落在
   * JSON 边界上）。
   */
  private static String toolCallFrame(String argumentsFragment, boolean identityFrame) {
    String identity =
        identityFrame
            ? "\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"echo\","
            : "\"type\":\"function\",\"function\":{";
    return "{\"model\":\"fake-model\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
        + identity
        + "\"arguments\":\""
        + argumentsFragment.replace("\\", "\\\\").replace("\"", "\\\"")
        + "\"}}]}}]}";
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
