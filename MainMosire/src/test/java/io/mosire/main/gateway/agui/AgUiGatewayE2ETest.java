package io.mosire.main.gateway.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventWrite;
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
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W4 AG-UI 网关 E2E（离线全链路）：{@link AgUiSessionRegistry}（会话 + runner 桥）→ {@link AgUiHttpServer}（POST
 * /sessions + GET /sessions/{id}/events SSE）→ 主 {@code AgentRuntime.chat} （FakeLlm 脚本）→ jdk
 * HttpClient 手工解析 {@code data: <json>} SSE 帧——断言 §5.2.2 序列 RUN_STARTED…TEXT_MESSAGE_*…RUN_FINISHED
 * 与 RUN_ERROR 路径。
 *
 * <p>覆盖：① 一回合（含工具调用）流式收齐完整序列；② 已完成会话的 EventStore 重放（兜底）；③ StopReason≠FINISHED → RUN_ERROR；④ 子 Agent
 * 事件族（agent=子 id / 主 agent 上的 spawn 拒绝）不泄漏进主会话流。
 */
class AgUiGatewayE2ETest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @TempDir Path tempDir;

  /** 一套在线装配（可选门控 LLM）。 */
  private static final class Fixture implements AutoCloseable {
    final SqliteEventStore store;
    final EventBus bus;
    final AgentRuntime runtime;
    final AgUiSessionRegistry registry;
    final AgUiHttpServer server;
    final ExecutorService chatExecutor;
    final ToolRegistry tools;

    Fixture(LlmClient llm, ToolRegistry tools, Path dataDir) {
      this.store = SqliteEventStore.open(dataDir.resolve("events.db"));
      this.bus = new EventBus();
      this.tools = tools;
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
          Executors.newSingleThreadExecutor(Thread.ofVirtual().name("agui-test-chat-").factory());
      this.registry = new AgUiSessionRegistry(runtime, store, bus, chatExecutor);
      this.server =
          AgUiHttpServer.start(new InetSocketAddress("127.0.0.1", 0), registry, store, bus);
    }

    String baseUrl() {
      return "http://127.0.0.1:" + server.port();
    }

    @Override
    public void close() {
      server.close();
      registry.close();
      runtime.close();
      bus.close();
      store.close();
      chatExecutor.shutdownNow();
    }
  }

  private static ToolRegistry echoTool(String replyPrefix) {
    ToolRegistry tools = new ToolRegistry();
    tools.register(
        new AgentTool() {
          @Override
          public String name() {
            return "echo";
          }

          @Override
          public ToolResult execute(ToolContext context) {
            return ToolResult.ok(replyPrefix + context.arguments());
          }
        });
    return tools;
  }

  /** 门控 + 脚本 LLM：首次 chat 阻塞直到 release（保证流订阅时运行未终态 / 注入窗口）。 */
  private static final class GatedScriptLlm implements LlmClient {
    private final ArrayDeque<LlmResponse> script;
    private final CountDownLatch release = new CountDownLatch(1);
    private boolean gated = true;

    GatedScriptLlm(List<LlmResponse> responses) {
      this.script = new ArrayDeque<>(responses);
    }

    void release() {
      release.countDown();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      if (gated) {
        gated = false;
        try {
          release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new LlmException("门控被中断");
        }
      }
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new LlmException("GatedScriptLlm 脚本已耗空");
      }
      return response;
    }
  }

  private static HttpResponse<String> post(String url, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url + "/sessions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> events(String url, String sessionId) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url + "/sessions/" + sessionId + "/events"))
            .GET()
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static List<JsonNode> dataFrames(String body) {
    return java.util.Arrays.stream(body.split("\r?\n"))
        .filter(line -> line.startsWith("data: "))
        .map(line -> line.substring("data: ".length()))
        .map(
            line -> {
              try {
                return JSON.readTree(line);
              } catch (Exception e) {
                throw new AssertionError("SSE data 不是合法 JSON: " + line, e);
              }
            })
        .collect(Collectors.toList());
  }

  private static List<String> frameTypes(List<JsonNode> frames) {
    return frames.stream().map(node -> node.get("type").asText()).collect(Collectors.toList());
  }

  @Test
  void oneTurnWithToolCallStreamsFullSubsetSequence() throws Exception {
    // 脚本：回合1 = 工具调用（无文本），回合2 = 文本 → 工具回合的对话事件（output=""）被翻译层跳过
    GatedScriptLlm llm =
        new GatedScriptLlm(
            List.of(
                LlmResponse.toolCall("c-1", "echo", Map.of("x", 1)), LlmResponse.text("完成，这是回应")));
    try (Fixture f = new Fixture(llm, echoTool("ECHO "), tempDir)) {
      HttpResponse<String> created =
          post(
              f.baseUrl(),
              "{\"threadId\":\"s1\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}");
      assertThat(created.statusCode()).isEqualTo(200);
      JsonNode createdBody = JSON.readTree(created.body());
      assertThat(createdBody.get("id").asText()).isEqualTo("s1");
      assertThat(createdBody.get("status").asText()).isEqualTo("running");

      Thread.sleep(150); // 运行已入场（门控阻塞在首回合）
      CompletableFuture<HttpResponse<String>> stream =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return events(f.baseUrl(), "s1");
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      Thread.sleep(150); // 流已订阅
      llm.release();
      HttpResponse<String> resp = stream.get(10, TimeUnit.SECONDS);

      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.headers().firstValue("Content-Type").orElseThrow())
          .contains("text/event-stream");
      List<JsonNode> frames = dataFrames(resp.body());
      assertThat(frameTypes(frames))
          .containsExactly(
              "RUN_STARTED",
              "TOOL_CALL_START",
              "TOOL_CALL_ARGS",
              "TOOL_CALL_END",
              "TOOL_CALL_RESULT",
              "TEXT_MESSAGE_START",
              "TEXT_MESSAGE_CONTENT",
              "TEXT_MESSAGE_END",
              "RUN_FINISHED");
      assertThat(frames.get(0).get("threadId").asText()).isEqualTo("s1");
      assertThat(frames.get(0).get("runId").asText()).isEqualTo("s1");
      assertThat(frames.get(0).get("protocolVersion").asText()).isEqualTo("1.0");
      // RUN_STARTED.input = RunAgentInput 最小回显（threadId+runId+messages——schema runId 必填，评审
      // Important 2b）
      assertThat(frames.get(0).get("input").get("threadId").asText()).isEqualTo("s1");
      assertThat(frames.get(0).get("input").get("runId").asText()).isEqualTo("s1");
      assertThat(frames.get(0).get("input").has("messages")).isTrue();
      assertThat(frames.get(1).get("toolCallName").asText()).isEqualTo("echo");
      assertThat(frames.get(2).get("delta").asText()).isEqualTo("{\"x\":1}");
      assertThat(frames.get(4).get("content").asText()).contains("ECHO");
      assertThat(frames.get(6).get("delta").asText()).isEqualTo("完成，这是回应");
      assertThat(frames.get(8).has("result")).isTrue();
    }
  }

  @Test
  void completedSessionIsReplayedFromEventStore() throws Exception {
    try (Fixture f =
        new Fixture(FakeLlmClient.with(LlmResponse.text("重放答案")), new ToolRegistry(), tempDir)) {
      post(
          f.baseUrl(),
          "{\"threadId\":\"s2\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
      awaitStatus(f, "s2", AgUiSession.Status.COMPLETED);

      HttpResponse<String> resp = events(f.baseUrl(), "s2");
      assertThat(resp.statusCode()).isEqualTo(200);
      List<JsonNode> frames = dataFrames(resp.body());
      assertThat(frameTypes(frames))
          .containsExactly(
              "RUN_STARTED",
              "TEXT_MESSAGE_START",
              "TEXT_MESSAGE_CONTENT",
              "TEXT_MESSAGE_END",
              "RUN_FINISHED");
      assertThat(frames.get(2).get("delta").asText()).isEqualTo("重放答案");
      // 关停后事件重放语义：连 README 的兜底重连都是"重放 + 终态收尾"——终态由会话记录给出
      assertThat(frames.get(frames.size() - 1).get("type").asText()).isEqualTo("RUN_FINISHED");
    }
  }

  @Test
  void nonFinishedStopReasonProducesRunError() throws Exception {
    LlmClient broken =
        new LlmClient() {
          @Override
          public LlmResponse chat(LlmRequest request) throws LlmException {
            throw new LlmException("模拟网络失败");
          }
        };
    try (Fixture f = new Fixture(broken, new ToolRegistry(), tempDir)) {
      post(
          f.baseUrl(),
          "{\"threadId\":\"s3\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
      awaitStatus(f, "s3", AgUiSession.Status.ERROR);

      HttpResponse<String> resp = events(f.baseUrl(), "s3");
      List<JsonNode> frames = dataFrames(resp.body());
      assertThat(frameTypes(frames)).containsExactly("RUN_STARTED", "RUN_ERROR");
      assertThat(frames.get(1).get("code").asText()).isEqualTo("LLM_ERROR");
      assertThat(frames.get(1).get("message").asText()).contains("LLM_ERROR");
    }
  }

  @Test
  void rejectedRunKeepsNotScheduledErrorCodeInRunError() throws Exception {
    try (Fixture f =
        new Fixture(FakeLlmClient.with(LlmResponse.text("x")), new ToolRegistry(), tempDir)) {
      // 关停窗：共享执行器已 shutdownNow → 新 POST 被拒 → 会话终态 NOT_SCHEDULED（评审 Minor 4：
      // 该 code 不得被默认 RUNTIME_EXCEPTION 吞掉）
      f.chatExecutor.shutdownNow();
      post(
          f.baseUrl(),
          "{\"threadId\":\"s6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
      awaitStatus(f, "s6", AgUiSession.Status.ERROR);

      HttpResponse<String> resp = events(f.baseUrl(), "s6");
      List<JsonNode> frames = dataFrames(resp.body());
      assertThat(frameTypes(frames)).containsExactly("RUN_STARTED", "RUN_ERROR");
      assertThat(frames.get(1).get("code").asText()).isEqualTo("NOT_SCHEDULED");
      assertThat(frames.get(1).get("message").asText()).contains("chat 执行器已关停");
    }
  }

  @Test
  void previousRunOnSameStoreDoesNotLeakIntoNewSessionStream() throws Exception {
    try (Fixture f =
        new Fixture(
            FakeLlmClient.with(LlmResponse.text("先序回答"), LlmResponse.text("会话回答")),
            new ToolRegistry(),
            tempDir)) {
      // 同 store 先跑"先前运行"（模拟先前 demo/A2A 回合——共享执行器串行化前的末事件
      // 即 floor=count=先前末 seq 的 off-by-one 场景：seq == floor 属上一运行，不得入窗）
      f.runtime.chat("第一回合");
      long priorFloor = f.store.count();
      assertThat(priorFloor).isGreaterThanOrEqualTo(1); // 前置：确有先前事件

      post(
          f.baseUrl(),
          "{\"threadId\":\"s5\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
      awaitStatus(f, "s5", AgUiSession.Status.COMPLETED);

      HttpResponse<String> resp = events(f.baseUrl(), "s5");
      List<JsonNode> frames = dataFrames(resp.body());
      // 首帧 RUN_STARTED + 精确 5 帧——先前运行的 turn（seq==floor）帧零泄漏
      assertThat(frames.get(0).get("type").asText()).isEqualTo("RUN_STARTED");
      assertThat(frameTypes(frames))
          .containsExactly(
              "RUN_STARTED",
              "TEXT_MESSAGE_START",
              "TEXT_MESSAGE_CONTENT",
              "TEXT_MESSAGE_END",
              "RUN_FINISHED");
      assertThat(frames.get(2).get("delta").asText()).isEqualTo("会话回答");
    }
  }

  @Test
  void subagentEventsDoNotLeakIntoMainSessionStream() throws Exception {
    GatedScriptLlm llm = new GatedScriptLlm(List.of(LlmResponse.text("主回答")));
    try (Fixture f = new Fixture(llm, new ToolRegistry(), tempDir)) {
      post(
          f.baseUrl(),
          "{\"threadId\":\"s4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
      Thread.sleep(150); // 运行已入场（门控）

      // 注入三种子 Agent/主 Agent 上的外围事件：①子生命周期（agent=子id）②子回合（agent=子id）③spawn 拒绝
      // （agent=main、correlationId=子id——是"主会话流的隐含过滤"里唯一可能泄漏进 agent 过滤的形态）
      io.mosire.agentlib.event.Event life =
          f.store.append(
              EventWrite.of(
                  "agent.lifecycle",
                  "sub-1",
                  "{\"action\":\"running\",\"template\":\"t1\",\"depth\":1}",
                  "sub-1"));
      f.bus.publish(life);
      io.mosire.agentlib.event.Event turn =
          f.store.append(
              EventWrite.of(
                  "conversation.turn",
                  "sub-1",
                  "{\"turns\":1,\"input\":\"x\",\"output\":\"子输出\",\"model\":\"fake\"}"));
      f.bus.publish(turn);
      io.mosire.agentlib.event.Event denied =
          f.store.append(
              EventWrite.of(
                  "permission.denied",
                  "main",
                  "{\"template\":\"t1\",\"childId\":\"sub-1\",\"reason\":\"not subset\"}",
                  "sub-1"));
      f.bus.publish(denied);

      // 流必须先进（运行仍门控），才能注入后走"兜底重放 + 实时订阅"两条路径
      CompletableFuture<HttpResponse<String>> stream =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return events(f.baseUrl(), "s4");
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      Thread.sleep(150); // 流已订阅
      llm.release();
      HttpResponse<String> resp = stream.get(10, TimeUnit.SECONDS);

      List<JsonNode> frames = dataFrames(resp.body());
      List<String> types = frameTypes(frames);
      assertThat(types)
          .containsExactly(
              "RUN_STARTED",
              "TEXT_MESSAGE_START",
              "TEXT_MESSAGE_CONTENT",
              "TEXT_MESSAGE_END",
              "RUN_FINISHED");
      assertThat(frames.get(2).get("delta").asText()).isEqualTo("主回答");
      // 三种外围事件无一泄漏（无 SUBAGENT_*/额外 TEXT_MESSAGE/额外 RESULT）
      assertThat(types).doesNotContain("SUBAGENT_STARTED", "SUBAGENT_FINISHED", "SUBAGENT_ERROR");
    }
  }

  private static void awaitStatus(Fixture f, String sessionId, AgUiSession.Status status)
      throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      if (f.registry.statusOf(sessionId).orElse(null) == status) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("会话 " + sessionId + " 未达到 " + status);
  }
}
