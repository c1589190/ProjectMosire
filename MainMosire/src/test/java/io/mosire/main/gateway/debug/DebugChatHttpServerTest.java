package io.mosire.main.gateway.debug;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.AgentRuntime;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P2-1 Task 3：{@link DebugChatHttpServer} 四端点离线 E2E（HTTP → {@link DebugChatService} → 假 LLM 全链路）：
 * 提交/轮询终态结果、stop 取消、倒序分页、未知 runId 404 与方法门禁 405。
 */
class DebugChatHttpServerTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  /** 在线装配：SqliteEventStore + FakeLlm/门控 LLM + 单线程虚拟线程 chat 执行器 + 调试对话 HTTP 服务（端口自动分配）。 */
  private static final class Fixture implements AutoCloseable {
    final SqliteEventStore store;
    final EventBus bus;
    final AgentRuntime runtime;
    final ExecutorService chatExecutor;
    final DebugChatService service;
    final DebugChatHttpServer server;
    final HttpClient client = HttpClient.newHttpClient();

    Fixture(LlmClient llm, Path dataDir) {
      this.store = SqliteEventStore.open(dataDir.resolve("events.db"));
      this.bus = new EventBus();
      ToolRegistry tools = new ToolRegistry();
      tools.register(
          new AgentTool() {
            @Override
            public String name() {
              return "echo";
            }

            @Override
            public ToolResult execute(ToolContext context) {
              return ToolResult.ok("echo: " + context.arguments());
            }
          });
      this.runtime =
          new AgentRuntime(
              AgentConfig.builder("main")
                  .systemPrompt("你是 Mosire 主 Agent。")
                  .description("测试主 Agent")
                  .build(),
              llm,
              tools,
              store,
              bus,
              AgentPermissionSet.system());
      this.chatExecutor =
          Executors.newSingleThreadExecutor(Thread.ofVirtual().name("debug-http-test-").factory());
      this.service = new DebugChatService(runtime, chatExecutor);
      this.server = DebugChatHttpServer.start(0, service);
    }

    String base() {
      return "http://127.0.0.1:" + server.port();
    }

    HttpResponse<String> send(HttpRequest request) throws Exception {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String jsonBody) throws Exception {
      return send(
          HttpRequest.newBuilder(URI.create(base() + path))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
              .build());
    }

    HttpResponse<String> get(String path) throws Exception {
      return send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build());
    }

    @Override
    public void close() {
      server.close();
      service.close();
      runtime.close();
      bus.close();
      store.close();
      chatExecutor.shutdownNow();
    }
  }

  /** 第 2 次 chat 调用挂起、等测试线程放行的门控脚本桩（运行中取消需要确定的同步点）。 */
  private static final class GateLlmClient implements LlmClient {

    final CountDownLatch enteredSecondCall = new CountDownLatch(1);
    final CountDownLatch releaseSecondCall = new CountDownLatch(1);

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private int calls;

    void enqueue(LlmResponse response) {
      script.addLast(response);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      calls++;
      if (calls == 2) {
        enteredSecondCall.countDown();
        try {
          releaseSecondCall.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new LlmException("GateLlmClient 脚本已耗空");
      }
      return response;
    }
  }

  @Test
  void submitThenPollRunUntilTerminalResultProjection() throws Exception {
    try (Fixture fixture = new Fixture(FakeLlmClient.with(LlmResponse.text("你好，我在。")), tempDir)) {
      HttpResponse<String> submitted = fixture.post("/api/chat", "{\"message\":\"第一条调试消息\"}");
      assertThat(submitted.statusCode()).isEqualTo(200);
      JsonNode body = JSON.readTree(submitted.body());
      long runId = body.get("runId").asLong();
      assertThat(runId).isPositive();
      assertThat(body.get("status").asText()).isEqualTo("running");

      JsonNode terminal = awaitTerminalOverHttp(fixture, runId);
      assertThat(terminal.get("status").asText()).isEqualTo("finished");
      assertThat(terminal.get("message").asText()).isEqualTo("第一条调试消息");
      assertThat(terminal.get("createdAt").asText()).isNotBlank();

      JsonNode result = terminal.get("result");
      assertThat(result).isNotNull();
      assertThat(result.get("stopReason").asText()).isEqualTo("FINISHED");
      assertThat(result.get("turns").asInt()).isGreaterThanOrEqualTo(1);
      assertThat(result.get("toolCalls").asInt()).isEqualTo(0);
      assertThat(result.get("text").asText()).isEqualTo("你好，我在。");
    }
  }

  @Test
  void submitRejectsMissingOrBlankMessage() throws Exception {
    try (Fixture fixture = new Fixture(FakeLlmClient.with(LlmResponse.text("无所谓")), tempDir)) {
      assertThat(fixture.post("/api/chat", "{}").statusCode()).isEqualTo(400);
      assertThat(fixture.post("/api/chat", "{\"message\":\"   \"}").statusCode()).isEqualTo(400);
      assertThat(fixture.post("/api/chat", "not-json").statusCode()).isEqualTo(400);
    }
  }

  @Test
  void stopRunningRunReturnsStoppedTrueThenFalse() throws Exception {
    GateLlmClient llm = new GateLlmClient();
    llm.enqueue(LlmResponse.toolCall("c-1", "echo", Map.of("text", "hi")));
    llm.enqueue(LlmResponse.toolCall("c-2", "echo", Map.of("text", "again")));
    llm.enqueue(LlmResponse.text("恢复后回答"));
    try (Fixture fixture = new Fixture(llm, tempDir)) {
      HttpResponse<String> submitted = fixture.post("/api/chat", "{\"message\":\"取消我\"}");
      long runId = JSON.readTree(submitted.body()).get("runId").asLong();

      assertThat(llm.enteredSecondCall.await(5, TimeUnit.SECONDS)).isTrue();
      HttpResponse<String> stopped = fixture.post("/api/chat/" + runId + "/stop", "");
      assertThat(stopped.statusCode()).isEqualTo(200);
      assertThat(JSON.readTree(stopped.body()).get("stopped").asBoolean()).isTrue();

      llm.releaseSecondCall.countDown();
      JsonNode terminal = awaitTerminalOverHttp(fixture, runId);
      assertThat(terminal.get("status").asText()).isEqualTo("cancelled");
      assertThat(terminal.get("result").get("stopReason").asText()).isEqualTo("CANCELLED");

      // 终态后再 stop 是 no-op：仍 200，但 stopped=false
      HttpResponse<String> again = fixture.post("/api/chat/" + runId + "/stop", "");
      assertThat(again.statusCode()).isEqualTo(200);
      assertThat(JSON.readTree(again.body()).get("stopped").asBoolean()).isFalse();
    }
  }

  @Test
  void resultsPaginationNewestFirstAndBadParams() throws Exception {
    try (Fixture fixture =
        new Fixture(
            FakeLlmClient.with(LlmResponse.text("一"), LlmResponse.text("二"), LlmResponse.text("三")),
            tempDir)) {
      long first =
          JSON.readTree(fixture.post("/api/chat", "{\"message\":\"1\"}").body())
              .get("runId")
              .asLong();
      JSON.readTree(fixture.post("/api/chat", "{\"message\":\"2\"}").body()).get("runId");
      long last =
          JSON.readTree(fixture.post("/api/chat", "{\"message\":\"3\"}").body())
              .get("runId")
              .asLong();
      awaitTerminalOverHttp(fixture, last);

      HttpResponse<String> page0 = fixture.get("/api/chat/results?page=0&size=2");
      assertThat(page0.statusCode()).isEqualTo(200);
      JsonNode rows0 = JSON.readTree(page0.body()).get("results");
      assertThat(rows0).hasSize(2);
      assertThat(rows0.get(0).get("runId").asLong()).isEqualTo(last);
      assertThat(rows0.get(1).get("runId").asLong()).isEqualTo(last - 1);

      HttpResponse<String> page1 = fixture.get("/api/chat/results?page=1&size=2");
      JsonNode rows1 = JSON.readTree(page1.body()).get("results");
      assertThat(rows1).hasSize(1);
      assertThat(rows1.get(0).get("runId").asLong()).isEqualTo(first);
      assertThat(rows1.get(0).get("createdAt").asText()).isNotBlank();

      // 缺省参数：page=0、size=10 → 全量倒序
      HttpResponse<String> defaults = fixture.get("/api/chat/results");
      assertThat(JSON.readTree(defaults.body()).get("results")).hasSize(3);

      assertThat(fixture.get("/api/chat/results?page=-1").statusCode()).isEqualTo(400);
      assertThat(fixture.get("/api/chat/results?page=0&size=0").statusCode()).isEqualTo(400);
      assertThat(fixture.get("/api/chat/results?page=abc").statusCode()).isEqualTo(400);
    }
  }

  @Test
  void unknownRunIdReturns404OnGetAndStop() throws Exception {
    try (Fixture fixture = new Fixture(FakeLlmClient.with(LlmResponse.text("你好")), tempDir)) {
      assertThat(fixture.get("/api/chat/999").statusCode()).isEqualTo(404);
      assertThat(fixture.post("/api/chat/999/stop", "").statusCode()).isEqualTo(404);
      assertThat(fixture.get("/api/chat/not-a-number").statusCode()).isEqualTo(404);
    }
  }

  @Test
  void methodGatingReturns405ForUndeclaredMethods() throws Exception {
    try (Fixture fixture = new Fixture(FakeLlmClient.with(LlmResponse.text("你好")), tempDir)) {
      assertThat(fixture.get("/api/chat").statusCode()).isEqualTo(405);
      assertThat(fixture.post("/api/chat/results", "").statusCode()).isEqualTo(405);
      assertThat(
              fixture
                  .send(
                      HttpRequest.newBuilder(URI.create(fixture.base() + "/api/chat/1"))
                          .DELETE()
                          .build())
                  .statusCode())
          .isEqualTo(405);
    }
  }

  /** 轮询 HTTP 直到运行离开 RUNNING（服务异步推进，轮询即最简同步点）。 */
  private static JsonNode awaitTerminalOverHttp(Fixture fixture, long runId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      HttpResponse<String> response = fixture.get("/api/chat/" + runId);
      if (response.statusCode() == 200) {
        JsonNode node = JSON.readTree(response.body());
        if (!"running".equals(node.get("status").asText())) {
          return node;
        }
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
    throw new AssertionError("运行未在超时内到达终态: " + runId);
  }
}
