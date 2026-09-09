package io.mosire.main.gateway.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.main.app.App;
import io.mosire.main.app.BootConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W4 App 装配级 E2E（离线）：App 装载 AG-UI 网关（与 A2A 共享单线程 chat 执行器）→ POST /sessions → GET
 * /sessions/{id}/events（jdk HttpClient 手工解析 {@code data: <json>}）断言完整 AG-UI 序列；协议错误面（重复 id 409、未知会话
 * 404、缺 user 文本 400）。
 */
class AppAgUiE2eTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @TempDir Path tempDir;

  private BootConfig bootConfig() {
    return new BootConfig(0, tempDir.resolve("data"), false, "", null, false, "127.0.0.1", 0);
  }

  private static String aguiBase(App app) {
    return "http://127.0.0.1:" + app.aguiPort();
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

  /** 门控 + 固定回复 LLM（保证"会话已在途"再订阅流）。 */
  private static final class GatedScriptLlm implements LlmClient {
    private final CountDownLatch release = new CountDownLatch(1);
    private boolean gated = true;

    void release() {
      release.countDown();
    }

    @Override
    public LlmResponse chat(LlmRequest request) throws LlmException {
      if (gated) {
        gated = false;
        try {
          release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new LlmException("门控被中断");
        }
      }
      return LlmResponse.text("App 级一回合回答");
    }
  }

  @Test
  void fullAppTurnProducesAgUiSessionWithCompleteStream() throws Exception {
    GatedScriptLlm llm = new GatedScriptLlm();
    App app = App.start(bootConfig(), llm);
    try {
      HttpResponse<String> created =
          post(
              aguiBase(app),
              "{\"threadId\":\"s-1\",\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}");
      assertThat(created.statusCode()).isEqualTo(200);
      JsonNode body = JSON.readTree(created.body());
      assertThat(body.get("id").asText()).isEqualTo("s-1");
      assertThat(body.get("threadId").asText()).isEqualTo("s-1");
      assertThat(body.get("runId").asText()).isEqualTo("s-1");
      assertThat(body.get("status").asText()).isEqualTo("running");

      Thread.sleep(100); // 运行已入场（门控阻塞在首回合）
      CompletableFuture<HttpResponse<String>> stream =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return events(aguiBase(app), "s-1");
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      Thread.sleep(100); // SSE 已订阅
      llm.release();
      HttpResponse<String> resp = stream.get(10, TimeUnit.SECONDS);

      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.headers().firstValue("Content-Type").orElseThrow())
          .contains("text/event-stream");
      assertThat(resp.body()).contains("data: ");
      List<JsonNode> frames = dataFrames(resp.body());
      assertThat(frameTypes(frames))
          .containsExactly(
              "RUN_STARTED",
              "TEXT_MESSAGE_START",
              "TEXT_MESSAGE_CONTENT",
              "TEXT_MESSAGE_END",
              "RUN_FINISHED");
      assertThat(frames.get(2).get("delta").asText()).isEqualTo("App 级一回合回答");
    } finally {
      app.close();
    }
  }

  @Test
  void duplicateSessionIdIsRejectedWith409() throws Exception {
    App app = App.start(bootConfig(), FakeLlmClient.with(LlmResponse.text("x")));
    try {
      String body = "{\"threadId\":\"dup\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
      assertThat(post(aguiBase(app), body).statusCode()).isEqualTo(200);
      assertThat(post(aguiBase(app), body).statusCode()).isEqualTo(409);
    } finally {
      app.close();
    }
  }

  @Test
  void unknownSessionAndInvalidBodyAreRejected() throws Exception {
    App app = App.start(bootConfig(), FakeLlmClient.with(LlmResponse.text("x")));
    try {
      assertThat(events(aguiBase(app), "ghost").statusCode()).isEqualTo(404);
      assertThat(post(aguiBase(app), "{\"threadId\":\"no-messages\"}").statusCode()).isEqualTo(400);
      assertThat(
              post(aguiBase(app), "{\"messages\":[{\"role\":\"assistant\",\"content\":\"hi\"}]}")
                  .statusCode())
          .isEqualTo(400);
    } finally {
      app.close();
    }
  }
}
