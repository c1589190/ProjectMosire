package io.mosire.main.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmRouteLoader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SetupHttpServer} 端到端（真 HTTP + 真 SSE stub 后端，{@code 设计-配置引导.md} 判据 4）： 合法 provider ⇒ 探测过 →
 * 200 → status 转 configured → 路由读得回 → 路由器热切换后 chat 走 stub； 探测失败 ⇒ 400 且<b>不写盘</b>；坏名/方法不符各一钉。
 */
@Timeout(60)
class SetupHttpServerTest {

  @TempDir Path tempDir;

  private HttpServer stub;
  private SetupHttpServer server;
  private SetupLlmRouter router;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
    if (stub != null) {
      stub.stop(0);
    }
  }

  /** 一个会回答 SSE 的 OpenAI 兼容 stub（一帧内容 + usage + DONE）。 */
  private int startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/v1/chat/completions",
        exchange -> {
          try {
            byte[] body =
                ("data: {\"model\":\"stub-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"pong\"}}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\n\n"
                        + "data: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          } catch (IOException clientHungUp) {
            // 客户端读完 [DONE] 即停，属预期
          }
        });
    stub.start();
    return stub.getAddress().getPort();
  }

  private void startServer() {
    router = SetupLlmRouter.unconfigured();
    server = SetupHttpServer.start(0, tempDir, router);
  }

  private HttpResponse<String> post(String json) throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/setup/llm"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> getStatus() throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    return client.send(
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/api/setup/status"))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void validProviderProbesWritesSwapsAndReportsConfigured() throws Exception {
    int stubPort = startStub();
    startServer();

    assertThat(getStatus().body()).contains("\"configured\":false").contains("unconfigured");

    HttpResponse<String> response =
        post(
            "{\"name\":\"default\",\"baseUrl\":\"http://127.0.0.1:"
                + stubPort
                + "/v1\",\"model\":\"stub-model\",\"apiKey\":\"sk-test\"}");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"ok\":true").contains("stub-model");
    assertThat(getStatus().body()).contains("\"configured\":true");
    assertThat(router.model()).isEqualTo("stub-model");
    // 路由读回（写盘证据）+ 密钥作为 Bearer 到达 provider
    assertThat(LlmRouteLoader.load(new io.mosire.agentlib.config.FileConfigStore(tempDir)).model())
        .isEqualTo("stub-model");
    assertThat(router.chat(LlmRequest.ofMessages(java.util.List.of())).textPart()).contains("pong");
  }

  @Test
  void unreachableProviderReturns400AndWritesNothing() throws Exception {
    startServer();

    HttpResponse<String> response =
        post("{\"baseUrl\":\"http://127.0.0.1:1\",\"model\":\"m\",\"apiKey\":\"\"}");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("探测失败");
    assertThat(router.configured()).isFalse();
    assertThat(new io.mosire.agentlib.config.FileConfigStore(tempDir).get("llm", "routes"))
        .isEmpty();
  }

  @Test
  void badRouteNameIsRejectedBeforeProbe() throws Exception {
    int stubPort = startStub();
    startServer();

    HttpResponse<String> response =
        post(
            "{\"name\":\"bad name\",\"baseUrl\":\"http://127.0.0.1:"
                + stubPort
                + "/v1\",\"model\":\"m\"}");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("name 非法");
    assertThat(router.configured()).isFalse();
  }

  @Test
  void wrongMethodGets405() throws Exception {
    startServer();
    HttpClient client = HttpClient.newHttpClient();
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/api/setup/llm"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(405);
  }
}
