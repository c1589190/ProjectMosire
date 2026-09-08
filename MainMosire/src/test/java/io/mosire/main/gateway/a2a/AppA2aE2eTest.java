package io.mosire.main.gateway.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.EventTypes;
import io.mosire.main.app.App;
import io.mosire.main.app.BootConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.http.A2AHttpClientFactory;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W2b E2E（离线全链路）：App 装配的 A2A 网关 → 官方 client → JSON-RPC → {@link A2aTaskService} → {@link
 * A2aAgentRunner} → 主 {@code AgentRuntime.chat}（FakeLlm 脚本）→ 任务快照 WORKING→COMPLETED。
 *
 * <p>覆盖三件事：① 一回合任务经 SSE 收齐快照与终态（产物=finalText）；② 跨任务续接——同一 AgentRuntime 实例 （Rul B 串行执行器）经 R3 history
 * 回灌延续多轮会话（"新任务 + 共享实例"之路；同任务在途续接的执行语义另见 {@link A2aTaskServiceTest}）；③ App.close 触发 R8 的 close 合成
 * FAILED——在订阅的 A2A 客户端收到 FAILED 终态。
 */
class AppA2aE2eTest {

  private static final String FIRST_ANSWER = "第一轮回答（A2A）";
  private static final String SECOND_ANSWER = "第二轮回答（A2A）";

  @TempDir Path tempDir;

  private BootConfig bootConfig() {
    // A2A 127.0.0.1 + 端口 0（空闲端口）；demo 关闭（主 E2E 用注入 LLM 自己编排脚本）
    return new BootConfig(0, tempDir.resolve("data"), false, "", null, false, "127.0.0.1", 0);
  }

  private static Client client(App app) throws Exception {
    String url = A2aTestSupport.baseUrl(app.a2aPort());
    AgentCard card = A2A.getAgentCard(url);
    return Client.builder(card)
        .withTransport(
            JSONRPCTransport.class, new JSONRPCTransportConfig(A2AHttpClientFactory.create()))
        .build();
  }

  @Test
  void agentTurnOverA2aStreamCompletesWithFinalTextArtifactAndTurnEvent() throws Exception {
    // 门控 LLM：首回合在 chat 入场处阻塞——保证订阅时任务尚未终态（收齐"快照→终态"递增序列）
    GatedScriptLlm llm = new GatedScriptLlm(FIRST_ANSWER);
    App app = App.start(bootConfig(), llm);
    try (Client client = client(app)) {
      List<Object> events = new CopyOnWriteArrayList<>();
      List<TaskState> seen = new ArrayList<>();
      CountDownLatch finalEvent = new CountDownLatch(1);
      client.sendMessage(
          A2A.toUserMessage("你好，A2A 一回合"),
          List.of(
              (event, card) -> {
                events.add(event);
                if (event instanceof TaskEvent e) {
                  seen.add(e.getTask().status().state());
                } else if (event instanceof TaskUpdateEvent e) {
                  seen.add(e.getTask().status().state());
                  if (e.getTask().status().state().isFinal()) {
                    finalEvent.countDown();
                  }
                }
              }),
          t -> {},
          new ClientCallContext(Map.of(), Map.of()));

      // 首帧契约：第一个事件 = 任务快照（SUBMITTED/WORKING 存在竞窗——runner 的 WORKING 推进与订阅先后不定）
      assertEventual("首快照未送达", () -> !events.isEmpty());
      assertThat(events.get(0)).isInstanceOf(TaskEvent.class);

      llm.release();
      assertThat(finalEvent.await(10, TimeUnit.SECONDS)).isTrue();

      // 终态：COMPLETED + 产物 = finalText；快照序列单调递增到终态
      TaskUpdateEvent last = (TaskUpdateEvent) events.get(events.size() - 1);
      assertThat(last.getTask().status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
      assertThat(last.getTask().artifacts()).extracting(Artifact::name).containsExactly("final");
      assertThat(((TextPart) last.getTask().artifacts().get(0).parts().get(0)).text())
          .isEqualTo(FIRST_ANSWER);
      assertThat(seen.get(0)).isIn(TaskState.TASK_STATE_SUBMITTED, TaskState.TASK_STATE_WORKING);
      assertThat(seen).contains(TaskState.TASK_STATE_WORKING);
      assertThat(seen.get(seen.size() - 1)).isEqualTo(TaskState.TASK_STATE_COMPLETED);

      // tasks/get 幂等：终态读取返回同一状态+产物（官方 client 同步路径）
      Task got =
          client.getTask(
              new TaskQueryParams(last.getTask().id(), null, null),
              new ClientCallContext(Map.of(), Map.of()));
      assertThat(got.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
      assertThat(got.artifacts()).extracting(Artifact::name).containsExactly("final");

      // AG-UI 事件面顺带断言：conversation.turn 已流出（Task 7 从 EventBus 订阅即用）
      var turns = app.queryEvents(new EventQuery("", EventTypes.CONVERSATION_TURN, "", -1, 10));
      assertThat(turns).hasSize(1);
      assertThat(new ObjectMapper().readTree(turns.get(0).payload()).get("output").asText())
          .isEqualTo(FIRST_ANSWER);
    } finally {
      app.close();
    }
  }

  @Test
  void nextTaskResumesSameAgentConversationWithHistory() throws Exception {
    GatedScriptLlm llm = new GatedScriptLlm(FIRST_ANSWER, SECOND_ANSWER);
    App app = App.start(bootConfig(), llm);
    try {
      // 任务一（无 taskId）：首回合 chat 门控在 LLM 入场处，release 后正常 COMPLETED。
      // 注意：本测试不再演示"同任务续接"——Rul B 串行执行器下，续接 runMessage 必然排在第一回合之后，
      // 彼时任务已 COMPLETED（终态），续接 runner 的 WORKING 推进被状态机拒绝（final→different）；
      // 这是"每任务一回合"桥+串行化的内在语义（服务层已单独测过在途续接，见 A2aTaskServiceTest）。
      // 因此多轮对话经"新任务 + 同一 AgentRuntime 实例"延续：R3 的 history 回灌跨任务生效。
      HttpResponse<String> firstResp =
          post(app, rpc("SendMessage", messageParams("m-1", "第一轮问题", null)), "1.0");
      assertThat(firstResp.body()).contains("\"state\":\"TASK_STATE_SUBMITTED\"");
      String taskId = extractTaskId(firstResp.body());
      awaitState(app, taskId, TaskState.TASK_STATE_WORKING);
      llm.release();
      awaitState(app, taskId, TaskState.TASK_STATE_COMPLETED);
      assertThat(llm.requests).hasSize(1);

      // 任务二（新 taskId，同一 runtime 实例）：第二回合的 LLM 请求必须携带任务一的完整回合
      // （system+user1+assistant1+user2）——R3 history 回灌生效（同一 Agent 实例的连续对话）
      HttpResponse<String> secondResp =
          post(app, rpc("SendMessage", messageParams("m-2", "第二轮问题", null)), "1.0");
      String secondTaskId = extractTaskId(secondResp.body());
      awaitState(app, secondTaskId, TaskState.TASK_STATE_COMPLETED);

      assertThat(llm.requests).hasSize(2);
      assertThat(llm.requests.get(1).messages()).hasSize(4);
      assertThat(llm.requests.get(1).messages().get(0).role()).isEqualTo(LlmMessage.ROLE_SYSTEM);
      assertThat(textOf(llm.requests.get(1).messages().get(1))).isEqualTo("第一轮问题");
      assertThat(textOf(llm.requests.get(1).messages().get(2))).isEqualTo(FIRST_ANSWER);
      assertThat(textOf(llm.requests.get(1).messages().get(3))).isEqualTo("第二轮问题");

      // 任务二终态：final 产物 = 第二轮回答；两任务各一条 conversation.turn
      Task done = getTask(app, secondTaskId);
      assertThat(done.artifacts()).extracting(Artifact::name).containsExactly("final");
      assertThat(((TextPart) done.artifacts().get(0).parts().get(0)).text())
          .isEqualTo(SECOND_ANSWER);
      assertThat(app.queryEvents(new EventQuery("", EventTypes.CONVERSATION_TURN, "", -1, 10)))
          .hasSize(2);
    } finally {
      app.close();
    }
  }

  @Test
  void appCloseSynthesizesFailedAndInFlightSubscriberSeesTerminalFailure() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    // 脚本：一回合只调工具 → 管线阻塞在工具执行上（任务停在 WORKING，等待 close 的合成 FAILED）
    FakeLlmClient llm = FakeLlmClient.with(LlmResponse.toolCall("c-1", "wait", Map.of()));
    App app = App.start(bootConfig(), llm);
    try (Client client = client(app)) {
      app.runtime()
          .registry()
          .register(
              new AgentTool() {
                @Override
                public String name() {
                  return "wait";
                }

                @Override
                public ToolResult execute(ToolContext context) {
                  try {
                    release.await(30, TimeUnit.SECONDS);
                    return ToolResult.ok("释放（未关闭）");
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("INTERRUPTED", "App 关闭，任务线程被中断");
                  }
                }
              });

      // 发送 → 任务进入 WORKING（runner 在工具中阻塞）→ 流式订阅先就位
      HttpResponse<String> sendResp =
          post(app, rpc("SendMessage", messageParams("m-1", "停在等待工具", null)), "1.0");
      String taskId = extractTaskId(sendResp.body());
      awaitState(app, taskId, TaskState.TASK_STATE_WORKING);

      List<Object> events = new CopyOnWriteArrayList<>();
      CountDownLatch firstEvent = new CountDownLatch(1);
      CountDownLatch finalEvent = new CountDownLatch(1);
      client.subscribeToTask(
          new TaskIdParams(taskId, null),
          List.of(
              (event, card) -> {
                // 先 add 后 countDown：唤醒等待方后立刻读列表必须看到已入列的元素
                events.add(event);
                if (event instanceof TaskEvent e) {
                  firstEvent.countDown();
                } else if (event instanceof TaskUpdateEvent e) {
                  firstEvent.countDown();
                  if (e.getTask().status().state().isFinal()) {
                    finalEvent.countDown();
                  }
                }
              }),
          t -> {},
          new ClientCallContext(Map.of(), Map.of()));
      assertThat(firstEvent.await(10, TimeUnit.SECONDS)).isTrue();

      // close：任务服务先行合成 FAILED → 网关 stop(1) 排空断连 → 订阅流收终态
      app.close();
      assertThat(finalEvent.await(10, TimeUnit.SECONDS)).isTrue();

      TaskUpdateEvent last = (TaskUpdateEvent) events.get(events.size() - 1);
      assertThat(last.getTask().status().state()).isEqualTo(TaskState.TASK_STATE_FAILED);

      // 持久化检查：closed 后事件库重开仍可读（FAILED 快照 + TOOL_CALL 均已落库；TOOL_CALL 放这里断言——
      // awaitState(WORKING) 只保证已 WORKING，FakeLlm 毫秒级执行下可能尚未入库；close 之后必然已写）
      try (SqliteEventStore reopened =
          SqliteEventStore.open(tempDir.resolve("data").resolve("events.db"))) {
        List<io.mosire.agentlib.event.Event> snapshots =
            reopened.query(new EventQuery("", EventStoreA2aTaskStore.SNAPSHOT_TYPE, taskId, -1, 5));
        assertThat(snapshots).isNotEmpty();
        assertThat(snapshots.get(0).payload()).contains("TASK_STATE_FAILED");
        assertThat(reopened.query(new EventQuery("", EventTypes.TOOL_CALL, "", -1, 10))).hasSize(1);
      }
    } finally {
      release.countDown();
      app.close();
    }
  }

  // ---- 工具 ----

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static String rpc(String method, String paramsJson) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
        + method
        + "\",\"params\":{"
        + paramsJson
        + "}}";
  }

  private static String messageParams(String messageId, String text, String taskId) {
    return "\"message\":{\"messageId\":\""
        + messageId
        + "\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\""
        + text
        + "\"}]"
        + (taskId == null ? "" : ",\"taskId\":\"" + taskId + "\"")
        + "}";
  }

  private static HttpResponse<String> post(App app, String body, String version) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(A2aTestSupport.baseUrl(app.a2aPort()) + "/a2a"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (version != null) {
      builder.header(org.a2aproject.sdk.common.A2AHeaders.A2A_VERSION, version);
    }
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String extractTaskId(String responseBody) {
    Matcher matcher = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(responseBody);
    return matcher.find() ? matcher.group(1) : "";
  }

  /** 轮询 GetTask 直至目标状态（E2E 通用等态。状态含字符串则 json 直接含枚举名）。 */
  private static void awaitState(App app, String taskId, TaskState state) throws Exception {
    String expected = state.name();
    for (int i = 0; i < 200; i++) {
      HttpResponse<String> resp = post(app, rpc("GetTask", "\"id\":\"" + taskId + "\""), "1.0");
      if (resp.body().contains(expected)) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("task " + taskId + " 未达到 " + expected);
  }

  private static Task getTask(App app, String taskId) throws Exception {
    HttpResponse<String> resp = post(app, rpc("GetTask", "\"id\":\"" + taskId + "\""), "1.0");
    try {
      ObjectMapper mapper = new ObjectMapper();
      // GetTask 响应的 result 即 Task 本体（无嵌套 task 包装——与官方 client 的解析一致，见测试①）
      String taskJson = mapper.readTree(resp.body()).path("result").toString();
      Task task = org.a2aproject.sdk.jsonrpc.common.json.JsonUtil.fromJson(taskJson, Task.class);
      if (task == null) {
        throw new AssertionError("GetTask result 解析为空: " + resp.body());
      }
      return task;
    } catch (AssertionError e) {
      throw e;
    } catch (Exception e) {
      throw new AssertionError("GetTask 响应解析失败: " + resp.body(), e);
    }
  }

  private static void assertEventual(String message, java.util.function.BooleanSupplier condition)
      throws Exception {
    for (int i = 0; i < 200; i++) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(message);
  }

  private static String textOf(LlmMessage message) {
    return message.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(ContentPart.Text.class::cast)
        .map(ContentPart.Text::text)
        .findFirst()
        .orElse("");
  }

  /** 门控 + 记录 LLM：首次 chat 入场阻塞直到 release（保证任务在订阅/续接时刻未终态），并记录每次请求（历史断言用）。 */
  private static final class GatedScriptLlm implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    // 线程安全：记录发生在 a2a-task 执行线程（chat 调用方），断言发生在测试线程
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private boolean gated = true;

    GatedScriptLlm(String... replyTexts) {
      for (String reply : replyTexts) {
        script.addLast(LlmResponse.text(reply));
      }
    }

    void release() {
      release.countDown();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      if (gated) {
        gated = false;
        try {
          release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new LlmException("GatedScriptLlm 脚本已耗空");
      }
      return response;
    }
  }
}
