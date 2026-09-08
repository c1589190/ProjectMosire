package io.mosire.main.gateway.a2a;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.main.gateway.a2a.A2aJsonRpcHandler.Outcome;
import io.mosire.main.gateway.a2a.A2aJsonRpcHandler.Outcome.Single;
import io.mosire.main.gateway.a2a.A2aJsonRpcHandler.Outcome.Stream;
import io.mosire.main.gateway.a2a.A2aJsonRpcHandler.Outcome.StreamSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import org.a2aproject.sdk.common.A2AHeaders;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A2A HTTP 入口（计划 5.2.3；D6 自写绑定：jdk.httpserver + 虚拟线程）。
 *
 * <ul>
 *   <li>{@code GET /.well-known/agent-card.json}：Agent Card（Cache-Control + ETag/304 条件请求——TCK
 *       级就绪）；
 *   <li>{@code POST /a2a}（及根路径 "/"——官方客户端按界面 URL 直接 POST 根路径）：JSON-RPC 入站（版本协商在 handler 内；响应回带
 *       {@code A2A-Version} 头）；
 *   <li>流式方法（SendStreamingMessage/SubscribeToTask）响应 = {@code text/event-stream}，每事件 {@code data:
 *       <jsonrpc 信封>} + {@code id: <n>}，终态/出错后流关闭（官方客户端以连接关闭为流结束）；
 *   <li>网络面默认 127.0.0.1（红线/计划：对外打开必须显式配置）。
 * </ul>
 *
 * <p>SSE 流式写帧在 handler 线程：事件源（runner 线程）只向无界队列 push（廉价、线程安全），顺序由队列保证。
 */
public final class A2aHttpServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(A2aHttpServer.class);

  /** 流结束哨兵（不会作为 JSON 帧出现——data 行以 {@code {"} 开头）。 */
  private static final String END = "[A2A_END]";

  public static final String CARD_PATH = "/.well-known/agent-card.json";
  public static final String RPC_PATH = "/a2a";

  private final HttpServer server;
  private final AgentCard card;

  private A2aHttpServer(HttpServer server, AgentCard card) {
    this.server = server;
    this.card = card;
  }

  /**
   * 启动。
   *
   * @param address 绑定地址（测试传 127.0.0.1:0 取随机端口）
   * @param card 本 Agent Card（供 CARD_PATH 服务）
   * @param handler JSON-RPC 绑定
   */
  public static A2aHttpServer start(
      InetSocketAddress address, AgentCard card, A2aJsonRpcHandler handler) {
    Objects.requireNonNull(card, "card");
    return start(address, port -> card, handler);
  }

  /**
   * 启动（卡片工厂版）：先绑定端口，再按**真实**监听端口构建 Agent Card——卡片 URL 必须携带实际地址（port 0 = 随机端口场景下由调用方取港口）。
   *
   * @param address 绑定地址（0 = 随机端口）
   * @param cardFactory 端口 → 卡片
   * @param handler JSON-RPC 绑定
   */
  public static A2aHttpServer start(
      InetSocketAddress address, IntFunction<AgentCard> cardFactory, A2aJsonRpcHandler handler) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(cardFactory, "cardFactory");
    Objects.requireNonNull(handler, "handler");
    try {
      HttpServer server = HttpServer.create(address, 0);
      AgentCard card = cardFactory.apply(server.getAddress().getPort());
      A2aHttpServer self = new A2aHttpServer(server, card);
      server.createContext("/", exchange -> self.dispatch(exchange, handler));
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.start();
      LOG.info("A2A 网关已启动: {} 端口 {}", address.getHostString(), self.port());
      return self;
    } catch (IOException e) {
      throw new IllegalStateException("启动 A2A HTTP server 失败: " + address, e);
    }
  }

  /** 实际监听端口（测试取随机端口用）。 */
  public int port() {
    return server.getAddress().getPort();
  }

  private void dispatch(HttpExchange exchange, A2aJsonRpcHandler handler) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (CARD_PATH.equals(path)) {
      if ("GET".equals(exchange.getRequestMethod())) {
        serveCard(exchange);
      } else {
        exchange.sendResponseHeaders(405, -1);
      }
      return;
    }
    // 也接受根路径 "/"：官方 client 直接把 POST 打到 interface.url()（即卡片 URL 本身，参照官方 reference 的
    // post("/") + 卡片 URL = http://host:port 约定；/a2a 是本项目计划的显式路径，二者等价）
    if ((RPC_PATH.equals(path) || "/".equals(path)) && "POST".equals(exchange.getRequestMethod())) {
      serveRpc(exchange, handler);
      return;
    }
    exchange.sendResponseHeaders(404, -1);
  }

  private void serveCard(HttpExchange exchange) throws IOException {
    String json;
    try {
      json = JsonUtil.toJson(card);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("序列化 AgentCard 失败", e);
    }
    String etag = etagOf(json);
    String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
    exchange.getResponseHeaders().set("ETag", etag);
    if (etag.equals(ifNoneMatch)) {
      exchange.sendResponseHeaders(304, -1);
      exchange.close();
      return;
    }
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private void serveRpc(HttpExchange exchange, A2aJsonRpcHandler handler) throws IOException {
    String body;
    try (var in = exchange.getRequestBody()) {
      body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String version = exchange.getRequestHeaders().getFirst(A2AHeaders.A2A_VERSION);
    Outcome outcome = handler.handle(body, version);
    // 响应回带本服务协议版本（官方 server 同款头；供客户端做协商确认）
    exchange.getResponseHeaders().set(A2AHeaders.A2A_VERSION, cardPrimaryVersion());
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    if (outcome instanceof Single single) {
      byte[] bytes = single.json().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
      return;
    }
    if (outcome instanceof Stream stream) {
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
      exchange.sendResponseHeaders(200, 0);
      writeSse(exchange, stream);
    }
  }

  /** SSE 写帧（handler 线程）：从无界队列逐帧写 {@code data:/id:}，END 哨兵后关闭；客户端断连则中断生产者。 */
  private static void writeSse(HttpExchange exchange, Stream stream) throws IOException {
    StreamSource source = stream.source();
    BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    Thread producer =
        Thread.ofVirtual()
            .name("a2a-sse")
            .start(
                () -> {
                  try {
                    source.run(feeder(queue));
                  } catch (Throwable t) {
                    LOG.error("SSE 事件源异常中断", t);
                  } finally {
                    // offer：无界队列必成功且不检查中断——事件源线程即使带着中断标记也要让走流句柄收到 END 收尾
                    queue.offer(END);
                  }
                });
    try (OutputStream out = exchange.getResponseBody()) {
      int seq = 0;
      String frame;
      while (!(frame = queue.take()).equals(END)) {
        out.write(("data: " + frame + "\nid: " + seq++ + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      producer.interrupt();
    }
  }

  /**
   * 终态帧入队（Consumer 包装）。
   *
   * <p>用 {@code offer} 而非 {@code put}：① 无界 {@link LinkedBlockingQueue} 必然成功（永不拒收）；② {@code put} 的
   * {@code lockInterruptibly()} 会把"已中断线程"直接判定为失败（R8 关停时在途 runner 线程带 {@code shutdownNow}
   * 的中断标记投递终态帧——实测 {@code put} 抛 {@link InterruptedException} 丢帧，订阅者只见连接切断不见 FAILED，正是 R8 要消灭的现象）。
   */
  private static Consumer<String> feeder(BlockingQueue<String> queue) {
    return queue::offer;
  }

  private String cardPrimaryVersion() {
    return card.supportedInterfaces().isEmpty()
        ? org.a2aproject.sdk.spec.AgentInterface.CURRENT_PROTOCOL_VERSION
        : card.supportedInterfaces().get(0).protocolVersion();
  }

  private static String etagOf(String json) {
    try {
      byte[] digest =
          MessageDigest.getInstance("MD5").digest(json.getBytes(StandardCharsets.UTF_8));
      return '"' + java.util.HexFormat.of().formatHex(digest) + '"';
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 关闭（优雅）：先停止接入新连接，再给在途 exchange（SSE 写帧循环）最多 1 秒排空时间，最后关闭全部连接。
   *
   * <p>{@code HttpServer.stop(delay)} 的契约是"关闭监听后等待当前 exchange handler 完成（或 delay 秒超时），
   * 然后关闭所有连接"——delay=0 会立即断连，在途 SSE 流（例如 R8 关停合成 FAILED 之后要推给订阅者的终态 {@code
   * statusUpdate}）可能来不及写出去（A2aServerTest 与 AppA2aE2eTest 曾实测：stop(0) 下订阅者收不到 终态帧，只能看到连接切断）。1
   * 秒窗口内事件源必然排空：{@code A2aTaskService.close()} 已先行合成终态 （App.close 的关停顺序保证），写帧循环在队列上阻塞直至 END
   * 哨兵，无锁态挂起。
   */
  @Override
  public void close() {
    server.stop(1);
  }
}
