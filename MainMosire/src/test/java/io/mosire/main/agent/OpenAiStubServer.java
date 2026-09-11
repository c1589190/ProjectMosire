package io.mosire.main.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用假 OpenAI 兼容端点（{@code POST /chat/completions}，SSE 流式）：绑回环 {@code 127.0.0.1} + 临时端口，
 * <b>全离线</b>——不出网、不碰任何真实供应商（真连通性归 S1-B，D22；同 {@code OpenAICompatibleLlmClientPipelineTest} 的先例）。
 *
 * <p>用途（三期 S1-A2）：给"真模型形态的子 Agent 子进程"一个确定性的对端，从而在不打网络的前提下钉住三件事——
 *
 * <ol>
 *   <li><b>确实走真模型</b>：{@link #requestBodies()} 里出现配置里的模型名、不出现模板里的哨兵 {@code "fake"}（R-A2-7）；
 *   <li><b>硬顶仍拦得住</b>：{@link #served()} 是请求计数，而本端点<b>永不主动收尾</b>（{@link #withToolCalls}）时，唯一能让
 *       子进程停下的就是模板硬顶——被绕过就是无限跑（R-A2-8 的实测判据）；
 *   <li><b>失败形态可分辨</b>：文本 / N 个工具调用 / usage / 响应前延迟四个旋钮覆盖 4 个硬顶维度。
 * </ol>
 */
final class OpenAiStubServer implements AutoCloseable {

  /** 工具调用用的工具名（子进程模板白名单里有它；无父链接时子体 registry 为空 → 调用只拿到失败结果，不影响轮次推进）。 */
  static final String TOOL_NAME = "echo";

  /** 默认回复文本（子 Agent 一回合内收尾）——用例可以把它当作"真实模型产出"的标记串断言。 */
  static final String DEFAULT_TEXT = "真模型自检回复";

  private final String model;
  private final HttpServer server;
  private final ExecutorService executor;
  private final AtomicInteger served = new AtomicInteger();
  private final List<String> requestBodies = new CopyOnWriteArrayList<>();

  /** 当前响应脚本（默认：一句文本 = 一回合收尾）。 */
  private volatile Reply reply = new Reply(DEFAULT_TEXT, 0, -1, -1, Duration.ZERO);

  OpenAiStubServer(String model) throws IOException {
    this.model = model;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // 并发服务（同 R6 先例）：缺省 executor 在调用线程内串行执行，与本用例"客户端读到一半、服务端继续写"的形态互锁
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/chat/completions", this::handle);
    server.start();
  }

  /** 每次响应回 {@code count} 个工具调用（{@code count > 0} ⇒ LLM 永不主动收尾，轮次只能被硬顶终止）。 */
  OpenAiStubServer withToolCalls(int count) {
    reply = reply.with(reply.text(), count, reply.usageIn(), reply.usageOut(), reply.delay());
    return this;
  }

  /** 附带 {@code usage} 帧（{@code quotaMaxTokens} 硬顶的刺激）。 */
  OpenAiStubServer withUsage(long inputTokens, long outputTokens) {
    reply = reply.with(reply.text(), reply.toolCalls(), inputTokens, outputTokens, reply.delay());
    return this;
  }

  /** 每响应在任何帧之前延迟（{@code timeBudgetSeconds} 硬顶的刺激）。 */
  OpenAiStubServer withDelay(Duration delay) {
    reply = reply.with(reply.text(), reply.toolCalls(), reply.usageIn(), reply.usageOut(), delay);
    return this;
  }

  /** 端点根地址（{@code http://127.0.0.1:<临时端口>}，拼进配置的 {@code llm.baseUrl}）。 */
  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** 已服务的请求数（硬顶判据的核心计数）。 */
  int served() {
    return served.get();
  }

  /** 已收到的请求体原文（模型名/协议面断言用；里面只有假配置的模型名，没有任何真实凭据）。 */
  List<String> requestBodies() {
    return List.copyOf(requestBodies);
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  // ---------- 假端点实现 ----------

  private void handle(HttpExchange exchange) throws IOException {
    try {
      requestBodies.add(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      served.incrementAndGet();
      Reply current = reply;
      exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0); // 0 = chunked：流式分块到达（客户端必须跨读边界缓冲）
      OutputStream out = exchange.getResponseBody();
      if (!current.delay().isZero()) {
        sleepQuietly(current.delay().toMillis());
      }
      for (byte[] frame : frames(current)) {
        out.write(frame);
        out.flush();
      }
      exchange.close();
    } catch (IOException clientHungUp) {
      // 客户端收完 [DONE] 即停读、或读超时后取消连接，都是预期路径
    }
  }

  private List<byte[]> frames(Reply current) {
    List<byte[]> frames = new ArrayList<>();
    for (int index = 0; index < current.toolCalls(); index++) {
      frames.add(sse(toolCallFrame(index)));
    }
    if (current.toolCalls() == 0) {
      frames.add(sse(textFrame(current.text())));
    }
    if (current.usageIn() >= 0) {
      frames.add(sse(usageFrame(current.usageIn(), current.usageOut())));
    }
    frames.add(DONE_FRAME);
    return frames;
  }

  private String textFrame(String content) {
    return "{\"model\":\""
        + model
        + "\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
        + content
        + "\"}}]}";
  }

  private String toolCallFrame(int index) {
    return "{\"model\":\""
        + model
        + "\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":"
        + index
        + ",\"id\":\"call_"
        + (index + 1)
        + "\",\"type\":\"function\",\"function\":{\"name\":\""
        + TOOL_NAME
        + "\",\"arguments\":\"{\\\"text\\\":\\\"ping\\\"}\"}}]}}]}";
  }

  private static String usageFrame(long inputTokens, long outputTokens) {
    return "{\"choices\":[],\"usage\":{\"prompt_tokens\":"
        + inputTokens
        + ",\"completion_tokens\":"
        + outputTokens
        + "}}";
  }

  private static final byte[] DONE_FRAME = bytes("data: [DONE]\n\n");

  private static byte[] sse(String json) {
    return bytes("data: " + json + "\n\n");
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

  /** 一次响应的脚本（工具调用数 + 文本 + usage + 响应前延迟）。 */
  private record Reply(String text, int toolCalls, long usageIn, long usageOut, Duration delay) {

    Reply with(String text, int toolCalls, long usageIn, long usageOut, Duration delay) {
      return new Reply(text, toolCalls, usageIn, usageOut, delay);
    }
  }
}
