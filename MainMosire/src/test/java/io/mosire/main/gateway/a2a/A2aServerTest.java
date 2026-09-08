package io.mosire.main.gateway.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.a2aproject.sdk.common.A2AHeaders;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;

/** 黑盒原始 wire 测试（官方 client 之外的手写 body：错误映射 / SSE 帧 / 版本协商 / 条件请求）。 */
class A2aServerTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  /** 直通 runner（完成状态推进）。 */
  private static A2aTaskService.A2aMessageRunner happyPath() {
    return (taskId, message, editor) -> {
      editor.transition(TaskState.TASK_STATE_WORKING);
      editor.transition(TaskState.TASK_STATE_COMPLETED);
    };
  }

  /** runner 收窄为 {@code throws A2AError} 后，受检的 InterruptedException 在测试 runner 内就地包装。 */
  private static void awaitRelease(CountDownLatch release) {
    try {
      release.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private static A2aHttpServer start(A2aTaskService service) {
    return A2aHttpServer.start(
        new InetSocketAddress("127.0.0.1", 0),
        port -> A2aTestSupport.card(A2aTestSupport.baseUrl(port)),
        new A2aJsonRpcHandler(A2aTestSupport.card("http://127.0.0.1:0"), service));
  }

  private static A2aHttpServer start(A2aTaskService.A2aMessageRunner runner) {
    return start(
        new A2aTaskService(
            new MemoryA2aTaskStore(), runner, Executors.newVirtualThreadPerTaskExecutor()));
  }

  private static HttpResponse<String> post(String url, String body, String version)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(url + "/a2a"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (version != null) {
      builder.header(A2AHeaders.A2A_VERSION, version);
    }
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String rpc(String method, String paramsJson) {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
        + method
        + "\",\"params\":{"
        + paramsJson
        + "}}";
  }

  private static final String USER_MESSAGE_PARAMS =
      "\"message\":{\"messageId\":\"m-1\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"ping\"}]}";

  private static List<String> sseData(String body) {
    return java.util.Arrays.stream(body.split("\r?\n"))
        .filter(line -> line.startsWith("data: "))
        .map(line -> line.substring("data: ".length()))
        .collect(Collectors.toList());
  }

  @Test
  void servesAgentCardWithEtagAnd304() throws Exception {
    try (A2aHttpServer server =
        start(
            new A2aTaskService(
                new MemoryA2aTaskStore(),
                happyPath(),
                Executors.newVirtualThreadPerTaskExecutor()))) {
      String url = A2aTestSupport.baseUrl(server);
      HttpResponse<String> card =
          HTTP.send(
              HttpRequest.newBuilder(URI.create(url + A2aHttpServer.CARD_PATH)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(card.statusCode()).isEqualTo(200);
      assertThat(card.body()).contains("\"name\":\"mosire-test\"");
      assertThat(card.body()).contains("\"url\":\"" + url + "\"");
      String etag = card.headers().firstValue("ETag").orElseThrow();
      assertThat(etag).startsWith("\"");

      HttpResponse<String> conditional =
          HTTP.send(
              HttpRequest.newBuilder(URI.create(url + A2aHttpServer.CARD_PATH))
                  .header("If-None-Match", etag)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(conditional.statusCode()).isEqualTo(304);
    }
  }

  @Test
  void sendMessageReturnsSubmittedTaskSnapshot() throws Exception {
    try (A2aHttpServer server = start(happyPath())) {
      HttpResponse<String> resp =
          post(A2aTestSupport.baseUrl(server), rpc("SendMessage", USER_MESSAGE_PARAMS), "1.0");
      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.headers().firstValue(A2AHeaders.A2A_VERSION)).contains("1.0");
      // SendMessage 返回提交瞬间的快照（异步推进），而非最终态
      assertThat(resp.body()).contains("\"result\"");
      assertThat(resp.body()).contains("\"state\":\"TASK_STATE_SUBMITTED\"");
      assertThat(resp.body()).contains("\"jsonrpc\":\"2.0\"");
    }
  }

  @Test
  void getTaskReflectsRunnerProgress() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (A2aHttpServer server =
        start(
            (taskId, message, editor) -> {
              started.countDown();
              awaitRelease(release);
              editor.transition(TaskState.TASK_STATE_WORKING);
              editor.transition(TaskState.TASK_STATE_COMPLETED);
            })) {
      String url = A2aTestSupport.baseUrl(server);
      HttpResponse<String> send = post(url, rpc("SendMessage", USER_MESSAGE_PARAMS), "1.0");
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      String taskId = extractTaskId(send.body());
      assertThat(taskId).isNotBlank();

      // 运行中：runner 尚未推进 → 仍是 SUBMITTED 快照
      HttpResponse<String> running = post(url, rpc("GetTask", "\"id\":\"" + taskId + "\""), "1.0");
      assertThat(running.body()).contains("\"state\":\"TASK_STATE_SUBMITTED\"");

      release.countDown();
      HttpResponse<String> done = awaitFinal(url, taskId);
      assertThat(done.body()).contains("\"state\":\"TASK_STATE_COMPLETED\"");
    }
  }

  @Test
  void cancelTaskThenRejectSecondCancel() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (A2aHttpServer server =
        start(
            (taskId, message, editor) -> {
              started.countDown();
              awaitRelease(release);
            })) {
      String url = A2aTestSupport.baseUrl(server);
      HttpResponse<String> send = post(url, rpc("SendMessage", USER_MESSAGE_PARAMS), "1.0");
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      String taskId = extractTaskId(send.body());

      HttpResponse<String> cancel =
          post(url, rpc("CancelTask", "\"id\":\"" + taskId + "\""), "1.0");
      assertThat(cancel.body()).contains("\"state\":\"TASK_STATE_CANCELED\"");

      HttpResponse<String> again = post(url, rpc("CancelTask", "\"id\":\"" + taskId + "\""), "1.0");
      assertThat(again.body()).contains("\"code\":-32002");
      assertThat(again.body()).contains("\"reason\":\"TASK_NOT_CANCELABLE\"");
      release.countDown();
    }
  }

  @Test
  void getTaskMissingReturnsTaskNotFound() throws Exception {
    try (A2aHttpServer server = start(happyPath())) {
      HttpResponse<String> resp =
          post(A2aTestSupport.baseUrl(server), rpc("GetTask", "\"id\":\"ghost\""), "1.0");
      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.body()).contains("\"code\":-32001");
      assertThat(resp.body()).contains("\"reason\":\"TASK_NOT_FOUND\"");
    }
  }

  @Test
  void listTasksFiltersByContext() throws Exception {
    try (A2aHttpServer server = start(happyPath())) {
      String url = A2aTestSupport.baseUrl(server);
      post(url, rpc("SendMessage", USER_MESSAGE_PARAMS), "1.0");
      HttpResponse<String> ctx =
          post(
              url,
              rpc(
                  "SendMessage",
                  "\"message\":{\"messageId\":\"m-2\",\"contextId\":\"ctx-1\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"hi\"}]}"),
              "1.0");
      assertThat(extractTaskId(ctx.body())).isNotBlank();

      HttpResponse<String> list = post(url, rpc("ListTasks", "\"contextId\":\"ctx-1\""), "1.0");
      assertThat(list.body()).contains("\"contextId\":\"ctx-1\"");
      assertThat(list.body()).contains("\"totalSize\":1");
      assertThat(list.body()).doesNotContain("\"m-1\"");
    }
  }

  @Test
  void versionNegotiationRejectsMismatch() throws Exception {
    try (A2aHttpServer server = start(happyPath())) {
      String url = A2aTestSupport.baseUrl(server);
      // 缺失头 → 缺省 0.3 → 支持 1.0 → -32009
      HttpResponse<String> missing = post(url, rpc("GetTask", "\"id\":\"x\""), null);
      assertThat(missing.body()).contains("\"code\":-32009");
      assertThat(missing.body()).contains("\"reason\":\"VERSION_NOT_SUPPORTED\"");
      // 主号不符
      HttpResponse<String> v2 = post(url, rpc("GetTask", "\"id\":\"x\""), "2.0");
      assertThat(v2.body()).contains("\"code\":-32009");
      // 兼容版本 → 走业务流程（任务不存在 → -32001）
      HttpResponse<String> ok = post(url, rpc("GetTask", "\"id\":\"x\""), "1.0");
      assertThat(ok.body()).contains("\"code\":-32001");
    }
  }

  @Test
  void unknownMethodReturnsMethodNotFound() throws Exception {
    try (A2aHttpServer server = start(happyPath())) {
      HttpResponse<String> resp = post(A2aTestSupport.baseUrl(server), rpc("Foo", ""), "1.0");
      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.body()).contains("\"code\":-32601");
      assertThat(resp.body()).contains("\"id\":1");
    }
  }

  @Test
  void malformedBodyReturnsJSONParseError() throws Exception {
    try (A2aHttpServer server = start(happyPath())) {
      HttpResponse<String> resp = post(A2aTestSupport.baseUrl(server), "this is not json", "1.0");
      assertThat(resp.body()).contains("\"code\":-32700");
      assertThat(resp.body()).contains("\"reason\":\"JSON_PARSE\"");
      // released JSONRPCUtils.toJsonRPCErrorResponse(null, e) 直接省略 id 字段（JSON-RPC 2.0 允许 null id 的
      // Parse error 信封）
      assertThat(resp.body()).doesNotContain("\"id\"");
    }
  }

  @Test
  void subscribeToTaskStreamsSseFramesUntilFinal() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (A2aHttpServer server =
        start(
            (taskId, message, editor) -> {
              started.countDown();
              awaitRelease(release);
              editor.transition(TaskState.TASK_STATE_WORKING);
              editor.transition(TaskState.TASK_STATE_COMPLETED);
            })) {
      String url = A2aTestSupport.baseUrl(server);
      HttpResponse<String> send = post(url, rpc("SendMessage", USER_MESSAGE_PARAMS), "1.0");
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      String taskId = extractTaskId(send.body());

      CompletableFuture<HttpResponse<String>> stream =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return post(url, rpc("SubscribeToTask", "\"id\":\"" + taskId + "\""), "1.0");
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      Thread.sleep(200);
      release.countDown();
      HttpResponse<String> resp = stream.get(10, TimeUnit.SECONDS);

      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.headers().firstValue("Content-Type").orElseThrow())
          .contains("text/event-stream");
      List<String> frames = sseData(resp.body());
      assertThat(frames).isNotEmpty();
      assertThat(frames)
          .allSatisfy(
              frame -> assertThat(frame).contains("\"jsonrpc\":\"2.0\"").contains("\"result\""));
      // 客户端契约（§3.1.6）：首个事件 = Task 快照；其后 = statusUpdate/artifactUpdate 增量，末帧 = 终态 statusUpdate
      assertThat(frames.get(0))
          .contains("\"task\":")
          .contains("\"state\":\"TASK_STATE_SUBMITTED\"");
      assertThat(frames.get(frames.size() - 1))
          .contains("\"statusUpdate\":")
          .contains("\"state\":\"TASK_STATE_COMPLETED\"");
      assertThat(resp.body()).contains("\nid: ");
    }
  }

  @Test
  void sendStreamingMessageStreamsSseFrames() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (A2aHttpServer server =
        start(
            (taskId, message, editor) -> {
              started.countDown();
              awaitRelease(release);
              editor.transition(TaskState.TASK_STATE_COMPLETED);
            })) {
      String url = A2aTestSupport.baseUrl(server);
      CompletableFuture<HttpResponse<String>> stream =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return post(url, rpc("SendStreamingMessage", USER_MESSAGE_PARAMS), "1.0");
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(200);
      release.countDown();
      HttpResponse<String> resp = stream.get(10, TimeUnit.SECONDS);

      List<String> frames = sseData(resp.body());
      assertThat(frames).isNotEmpty();
      assertThat(frames.get(frames.size() - 1)).contains("\"state\":\"TASK_STATE_COMPLETED\"");
    }
  }

  private static String extractTaskId(String responseBody) {
    Matcher matcher = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(responseBody);
    return matcher.find() ? matcher.group(1) : "";
  }

  private static HttpResponse<String> awaitFinal(String url, String taskId) throws Exception {
    for (int i = 0; i < 100; i++) {
      HttpResponse<String> resp = post(url, rpc("GetTask", "\"id\":\"" + taskId + "\""), "1.0");
      if (resp.body().contains("TASK_STATE_COMPLETED")) {
        return resp;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("task " + taskId + " did not complete in time");
  }
}
