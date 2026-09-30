package io.mosire.agentlib.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 假 OpenAI 兼容端点（测试夹具，非测试类本身）：与 {@link OpenAICompatibleLlmClientTest} 里那套逐字同源——绑 {@code
 * 127.0.0.1:0}（临时端口）、虚拟线程 executor、按脚本分块 flush 吐 SSE 字节、并<b>捕获真实请求体</b>，全离线。
 *
 * <p><b>为什么抽出来</b>：本轮 A1/A2/B1/B3 验收用例按主题分散在多个测试类里，各自再抄一份端点会把"假端点行为"变成 N 份真相（改一处漏三处）；{@link
 * OpenAICompatibleLlmClientTest} 内的私有实现保持原样不动，避免与并行改动打架。
 *
 * <p><b>为什么分块 + 块间 flush</b>：只发完整帧的假端点测不出"跨读边界累积"这类真 bug——思维链、工具参数都是逐块增量到达的， 一次 {@code read}
 * 拿不到完整帧才是真实形态。
 */
final class FakeChatEndpoint implements AutoCloseable {

  /** SSE 终止符帧。 */
  static final byte[] DONE_FRAME = bytes("data: [DONE]\n\n");

  private static final ObjectMapper JSON = new ObjectMapper();

  private final List<byte[]> script = new CopyOnWriteArrayList<>();
  private final List<String> requestBodies = new CopyOnWriteArrayList<>();
  private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
  private final AtomicInteger writes = new AtomicInteger();
  private volatile int status = 200;
  private volatile String errorBody = "";

  private final HttpServer server;
  private final ExecutorService executor;

  private FakeChatEndpoint(HttpServer server, ExecutorService executor) {
    this.server = server;
    this.executor = executor;
  }

  /** 起一个绑临时端口的假端点；用完必须 {@link #close()}。 */
  static FakeChatEndpoint start() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // 并发服务：HttpServer 缺省 executor 是"调用线程内串行执行"，本套用例有"客户端正在读、服务端还没写完"的形态，
    // 单线程服务端会把两边一起卡死（同 OpenAICompatibleLlmClientTest 的理由）。虚拟线程天然守护，不拖住构建 JVM。
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    FakeChatEndpoint endpoint = new FakeChatEndpoint(server, executor);
    server.createContext("/chat/completions", endpoint::handle);
    server.start();
    return endpoint;
  }

  /** 端点根地址（{@code http://127.0.0.1:<临时端口>}）。 */
  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** 追加一个 SSE data 帧（一个 JSON 对象）。 */
  FakeChatEndpoint sayJson(String json) {
    script.add(sseFrame(json));
    return this;
  }

  /** 追加一块原始字节（刻意造"帧跨读边界"的形态）。 */
  FakeChatEndpoint sayRaw(String raw) {
    script.add(bytes(raw));
    return this;
  }

  /** 追加终止符 {@code data: [DONE]}。 */
  FakeChatEndpoint done() {
    script.add(DONE_FRAME);
    return this;
  }

  /** 让端点以非 2xx 应答（供应商侧错误：限流/鉴权/5xx 等）。 */
  FakeChatEndpoint failWith(int httpStatus, String body) {
    this.status = httpStatus;
    this.errorBody = body == null ? "" : body;
    return this;
  }

  /** 第 {@code index} 次请求的<b>原始请求体</b>（"供应商侧可观测"的证据面）。 */
  String requestBody(int index) {
    return requestBodies.get(index);
  }

  /** 第 {@code index} 次请求的原始请求体，已解析成 JSON 树。 */
  JsonNode requestJson(int index) throws IOException {
    return JSON.readTree(requestBodies.get(index));
  }

  /** 收到的请求条数。 */
  int requestCount() {
    return requestBodies.size();
  }

  /** 最后一次请求的 {@code Authorization} 头（未设置时为 {@code null}）。 */
  String lastAuthorization() {
    return lastAuthorization.get();
  }

  /** 已写出的响应块数（{@code > 1} = 响应确实分了多块）。 */
  int writes() {
    return writes.get();
  }

  /** 造一个指向本端点的真实客户端（连接超时 2s）。 */
  OpenAICompatibleLlmClient client(
      String routeModel, OpenAICompatibleLlmClient.ApiKeySource keys, Duration readTimeout) {
    return new OpenAICompatibleLlmClient(
        ModelRoute.of("test", baseUrl(), routeModel, "keys.fake"),
        keys,
        Duration.ofSeconds(2),
        readTimeout);
  }

  /** 匿名客户端（不带 {@code Authorization}）、读超时 5s、非思考路由（不回传空思维链键）。 */
  OpenAICompatibleLlmClient client(String routeModel) {
    return client(routeModel, false);
  }

  /**
   * 匿名客户端，可显式声明思考模式的 {@code echoReasoningContent} 能力位（A6 修复版）。
   *
   * <p>思考路由的线级形态与普通路由不同：前者对每条 assistant 消息恒发 {@code reasoning_content}（可为空串），后者只在有正文时才发。
   * 假端点捕获的请求体是这两种形态唯一的可观测面。
   */
  OpenAICompatibleLlmClient client(String routeModel, boolean echoReasoningContent) {
    ModelRoute route =
        ModelRoute.of(
            "test",
            baseUrl(),
            routeModel,
            "keys.fake",
            new LlmTransport(
                LlmProtocol.OPENAI_COMPATIBLE,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                echoReasoningContent));
    return new OpenAICompatibleLlmClient(
        route, OpenAICompatibleLlmClient.ApiKeySource.none(), ToolAssetResolver.none());
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      requestBodies.add(rawBody);
      if (status != 200) {
        byte[] body = bytes(errorBody);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        return;
      }
      if (script.isEmpty()) {
        // 空脚本 = 200 但不是 SSE（模拟代理/网关返回普通 JSON）
        byte[] body = bytes("{\"object\":\"chat.completion\"}");
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        return;
      }
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0); // 0 = chunked：分块写、逐块 flush
      OutputStream out = exchange.getResponseBody();
      for (byte[] chunk : script) {
        out.write(chunk);
        out.flush();
        writes.incrementAndGet();
        sleepQuietly(20); // 块间停顿：逼出"一次 read 拿不到完整帧"的真实形态
      }
      exchange.close();
    } catch (IOException clientHungUp) {
      // 客户端见到 [DONE] 提前停读、或读超时后取消连接，都是预期路径（服务端写失败而已）
    }
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  // ---------- SSE 造帧工具 ----------

  /** 一个 {@code data:} 帧的字节。 */
  static byte[] sseFrame(String json) {
    return bytes("data: " + json + "\n\n");
  }

  static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** 只含 {@code choices[0].delta} 的应答帧。 */
  static String deltaFrame(String deltaJson) {
    return "{\"choices\":[{\"index\":0,\"delta\":" + deltaJson + "}]}";
  }

  /** 正文分片帧（文本自动转义）。 */
  static String contentFrame(String text) {
    return deltaFrame("{\"content\":\"" + escapeJsonString(text) + "\"}");
  }

  /** {@code reasoning_content} 分片帧（A1 的供应商侧形态；文本自动转义）。 */
  static String reasoningContentFrame(String text) {
    return deltaFrame("{\"reasoning_content\":\"" + escapeJsonString(text) + "\"}");
  }

  /** 一个含完整工具调用的帧（{@code arguments} 以 JSON 文本嵌入，内部引号自动转义）。 */
  static String toolCallFrame(String toolCallId, String toolName, String argumentsJson) {
    return "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\""
        + toolCallId
        + "\",\"type\":\"function\",\"function\":{\"name\":\""
        + toolName
        + "\",\"arguments\":\""
        + escapeJsonString(argumentsJson)
        + "\"}}]}}]}";
  }

  /** 把原始文本转义成 JSON 字符串内容（片段自身含 {@code "}，直接嵌入会造出非法帧）。 */
  static String escapeJsonString(String raw) {
    return raw.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
