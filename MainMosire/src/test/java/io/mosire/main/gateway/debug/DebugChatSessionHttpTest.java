package io.mosire.main.gateway.debug;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.AgentSpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P3-3 会话重置的 HTTP 面（验收④⑤）：{@code POST /api/chat/session} 路由可达、响应带新旧两个会话 id、切换真的生效—— 且切换动作排在<b>共享单线程
 * chat 执行器</b>上（R11），与在途回合不交错。
 *
 * <p><b>夹具为什么带会话库</b>：本类要断言的正是"消息落在<b>哪个</b>会话名下"——没有 {@link SqliteConversationStore}
 * 就看不见"在途回合的尾部有没有被算进新会话"这个失败模式（它是重置被错误地放在 HTTP 线程上执行时唯一会红的地方）。 装配照生产：事件库与会话库<b>同一文件</b>，主 Agent 走带
 * store 的构造，初始会话 id 为 {@code "main"}。
 */
class DebugChatSessionHttpTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 生产装配里的初始会话 id（{@code App.MAIN_CONVERSATION_ID}——测试不复用 App 常量以免把断言绑到演示路径上）。 */
  private static final String MAIN_CONVERSATION_ID = "main";

  @TempDir Path tempDir;

  /** 在线装配：事件库 + 会话库（同一文件）+ 记录型 LLM + 单线程虚拟线程 chat 执行器 + 调试对话 HTTP 服务（端口自动分配）。 */
  private static final class Fixture implements AutoCloseable {

    final Path db;
    final SqliteEventStore events;
    final SqliteConversationStore conversations;
    final EventBus bus;
    final AgentRuntime runtime;
    final ExecutorService chatExecutor;
    final DebugChatService service;
    final DebugChatHttpServer server;
    final HttpClient client = HttpClient.newHttpClient();

    Fixture(LlmClient llm, Path dataDir) {
      this.db = dataDir.resolve("events.db");
      this.events = SqliteEventStore.open(db);
      this.conversations = SqliteConversationStore.open(db);
      this.bus = new EventBus();
      this.runtime =
          new AgentRuntime(
              new AgentSpec(
                  AgentConfig.builder("main")
                      .systemPrompt("你是 Mosire 主 Agent。")
                      .description("测试主 Agent")
                      .build()),
              llm,
              new ToolRegistry(),
              events,
              bus,
              AgentPermissionSet.system(),
              conversations,
              MAIN_CONVERSATION_ID);
      this.chatExecutor =
          Executors.newSingleThreadExecutor(
              Thread.ofVirtual().name("debug-session-test-").factory());
      this.service = new DebugChatService(runtime, chatExecutor);
      this.server = DebugChatHttpServer.start(0, service);
    }

    String base() {
      return "http://127.0.0.1:" + server.port();
    }

    HttpResponse<String> post(String path, String jsonBody) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create(base() + path))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }

    /** 提交一条消息并等它到终态（返回 HTTP 状态码与 body 文本，终态快照由调用方再查）。 */
    long submitAndAwaitTerminal(String message) throws Exception {
      long runId =
          JSON.readTree(post("/api/chat", "{\"message\":\"" + message + "\"}").body())
              .get("runId")
              .asLong();
      awaitTerminalOverHttp(runId);
      return runId;
    }

    JsonNode awaitTerminalOverHttp(long runId) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (System.nanoTime() < deadline) {
        HttpResponse<String> response = get("/api/chat/" + runId);
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

    @Override
    public void close() {
      server.close();
      service.close();
      runtime.close();
      conversations.close();
      bus.close();
      events.close();
      chatExecutor.shutdownNow();
    }
  }

  /**
   * 第一次 LLM 调用挂起、等测试线程放行；后续调用照常返回脚本响应（记录每一次请求）。
   *
   * <p>挂起<b>第 1 次</b>调用（而非第 2 次）是本章的关键：这样"重置请求到达时恰有一个在途回合"是确定的同步点，而不是碰运气。
   */
  private static final class FirstCallGateLlmClient implements LlmClient {

    final CountDownLatch enteredFirstCall = new CountDownLatch(1);
    final CountDownLatch releaseFirstCall = new CountDownLatch(1);
    final List<LlmRequest> requests = new CopyOnWriteArrayList<>();

    private final Deque<LlmResponse> script = new ArrayDeque<>();

    void enqueue(LlmResponse response) {
      script.addLast(response);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      if (requests.size() == 1) {
        enteredFirstCall.countDown();
        try {
          releaseFirstCall.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new LlmException("FirstCallGateLlmClient 脚本已耗空");
      }
      return response;
    }
  }

  /**
   * 验收⑤：{@code POST /api/chat/session} 可达，响应带新会话 id 与提交时刻的旧会话 id；切换<b>真的生效</b>——重置后的回合落在新会话名下，
   * 旧会话原样留档（D26：不删库）。
   */
  @Test
  void sessionRouteResetsToANewConversationAndKeepsTheOldOneReadable() throws Exception {
    FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text("回答一"), LlmResponse.text("回答二"));
    try (Fixture fixture = new Fixture(llm, tempDir)) {
      fixture.submitAndAwaitTerminal("重置前的提问");
      assertThat(fixture.conversations.load(MAIN_CONVERSATION_ID)).hasSize(2);

      // 方法门禁：只有 POST 是可声明的（GET 405，同其余端点口径）
      assertThat(fixture.get("/api/chat/session").statusCode()).isEqualTo(405);

      HttpResponse<String> reset = fixture.post("/api/chat/session", "");
      assertThat(reset.statusCode()).isEqualTo(200);
      JsonNode body = JSON.readTree(reset.body());
      String newId = body.get("conversationId").asText();
      assertThat(newId).isNotBlank().isNotEqualTo(MAIN_CONVERSATION_ID);
      assertThat(body.get("previousConversationId").asText()).isEqualTo(MAIN_CONVERSATION_ID);

      // 切换排在执行器上：HTTP 响应先回、动作随后生效——轮询等它落地（空闲执行器下即下一个任务）
      awaitConversationId(fixture, newId);

      fixture.submitAndAwaitTerminal("重置后的提问");

      assertThat(fixture.conversations.load(MAIN_CONVERSATION_ID))
          .as("旧会话留档：一问一答原样还在")
          .containsExactly(
              LlmMessage.user("重置前的提问"),
              LlmMessage.assistant(List.of(new ContentPart.Text("回答一"))));
      assertThat(fixture.conversations.load(newId))
          .as("新会话从零开始：只有重置后的那一对")
          .containsExactly(
              LlmMessage.user("重置后的提问"),
              LlmMessage.assistant(List.of(new ContentPart.Text("回答二"))));
    }
  }

  /**
   * 验收④：重置与在途回合<b>不交错</b>——重置请求是在一个回合正卡在 LLM 调用里时到的，它只能排在那个回合之后执行。
   *
   * <p><b>判据分两层，都不是时序碰运气</b>：
   *
   * <ol>
   *   <li>重置响应回来后、放行在途回合之前，{@code runtime.conversationId()} <b>仍是旧 id</b>：在途回合占着执行器（阻塞在 LLM
   *       调用上），排队中的重置任务不可能已经跑过——"在 HTTP 线程上直接改"的实现会在这一步直接翻车；
   *   <li>在途回合的尾部落在<b>旧会话</b>名下、新会话里一条不沾：若重置抢在回合收尾之前改了 id，回合的 {@code saveHistory} 就会把上一个会话
   *       的问答追加进新会话（这正是"交错"的物理后果，断言落在 {@code load(新)} 上）。
   * </ol>
   */
  @Test
  void resetWaitsBehindAnInFlightTurnInsteadOfInterleavingWithIt() throws Exception {
    FirstCallGateLlmClient llm = new FirstCallGateLlmClient();
    llm.enqueue(LlmResponse.text("回答一"));
    llm.enqueue(LlmResponse.text("回答二"));
    try (Fixture fixture = new Fixture(llm, tempDir)) {
      // 在途回合：提交即被门控卡在第一次 LLM 调用里
      long inFlightRun =
          JSON.readTree(fixture.post("/api/chat", "{\"message\":\"重置前的提问\"}").body())
              .get("runId")
              .asLong();
      assertThat(llm.enteredFirstCall.await(15, TimeUnit.SECONDS)).isTrue();

      HttpResponse<String> reset = fixture.post("/api/chat/session", "");
      assertThat(reset.statusCode()).isEqualTo(200);
      String newId = JSON.readTree(reset.body()).get("conversationId").asText();
      assertThat(JSON.readTree(reset.body()).get("previousConversationId").asText())
          .isEqualTo(MAIN_CONVERSATION_ID);

      // 判据①：在途回合还卡着，切换不得发生（排在同一串行化点 ⇒ 必须等它）
      assertThat(fixture.runtime.conversationId())
          .as("重置已受理但尚未执行：在途回合占着执行器")
          .isEqualTo(MAIN_CONVERSATION_ID);

      // 重置之后的回合排在重置之后（同一执行器 FIFO）
      long afterResetRun =
          JSON.readTree(fixture.post("/api/chat", "{\"message\":\"重置后的提问\"}").body())
              .get("runId")
              .asLong();
      llm.releaseFirstCall.countDown();

      awaitConversationId(fixture, newId);
      // 两个回合都跑到终态（切换都执行完了 ⇒ 在途回合早已收尾）
      assertThat(fixture.awaitTerminalOverHttp(inFlightRun).get("status").asText())
          .isEqualTo("finished");
      assertThat(fixture.awaitTerminalOverHttp(afterResetRun).get("status").asText())
          .isEqualTo("finished");

      // 判据②：在途回合整条落在旧会话；新会话只有重置后的那一对（交错的话这里会出现"重置前的提问"）
      assertThat(fixture.conversations.load(MAIN_CONVERSATION_ID))
          .containsExactly(
              LlmMessage.user("重置前的提问"),
              LlmMessage.assistant(List.of(new ContentPart.Text("回答一"))));
      assertThat(fixture.conversations.load(newId))
          .as("新会话不得沾上在途回合的任何一条")
          .containsExactly(
              LlmMessage.user("重置后的提问"),
              LlmMessage.assistant(List.of(new ContentPart.Text("回答二"))));

      // 重置后的回合请求不含重置前的内容（工作集真的清了，不是只换了 id）
      assertThat(llm.requests).hasSize(2);
      assertThat(llm.requests.get(1).messages()).doesNotContain(LlmMessage.user("重置前的提问"));
      assertThat(llm.requests.get(1).messages()).hasSize(2);
    }
  }

  /** 关停窗内的诚实失败：共享执行器已拒 ⇒ 503，绝不回报一个没发生的重置。 */
  @Test
  void resetIsRejectedLoudlyWhenTheSharedExecutorIsShutDown() throws Exception {
    try (Fixture fixture = new Fixture(FakeLlmClient.with(LlmResponse.text("你好")), tempDir)) {
      fixture.chatExecutor.shutdown();

      HttpResponse<String> reset = fixture.post("/api/chat/session", "");
      assertThat(reset.statusCode()).isEqualTo(503);
      assertThat(JSON.readTree(reset.body()).get("error").asText()).isNotBlank();
      assertThat(fixture.runtime.conversationId()).isEqualTo(MAIN_CONVERSATION_ID);
    }
  }

  /** 服务已关停（提交口封死）时同样是 503——重置不被吞成"看着像成功"。 */
  @Test
  void resetAfterServiceCloseReturns503() throws Exception {
    try (Fixture fixture = new Fixture(FakeLlmClient.with(LlmResponse.text("你好")), tempDir)) {
      fixture.service.close();

      assertThat(fixture.post("/api/chat/session", "").statusCode()).isEqualTo(503);
    }
  }

  /** 轮询等切换生效（切换排在执行器上，HTTP 响应不保证已生效——见 {@link DebugSessionReset} 的语义边界）。 */
  private static void awaitConversationId(Fixture fixture, String expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (expected.equals(fixture.runtime.conversationId())) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
    throw new AssertionError("会话切换未在超时内生效: " + expected);
  }
}
