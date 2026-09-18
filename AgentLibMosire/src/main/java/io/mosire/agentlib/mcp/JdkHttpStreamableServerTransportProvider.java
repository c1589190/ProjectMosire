package io.mosire.agentlib.mcp;

import com.sun.net.httpserver.HttpExchange;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * JDK {@code com.sun.net.httpserver.HttpServer} 版<b>有状态</b> Streamable HTTP 服务端传输（P1-B 传输核）。
 *
 * <p><b>为什么需要它</b>：MCP SDK 2.0.1 的服务端 HTTP transport 全是 servlet 系（要拖 servlet-api + 容器），与两仓"JDK
 * {@code HttpServer} + 零框架"的既有形态不符。本类只把 SDK 的 {@link McpStreamableServerSession} 会话机缝到 JDK 的 HTTP
 * 交换上：路由与生命周期由 SDK 管，HTTP 外壳（verb→session→{@code Mcp-Session-Id}）由本类负责。
 *
 * <p><b>本类只做 POST 面 + SSE 帧 + 会话 + DELETE</b>（todo 5 的范围）：GET 监听流（长连 + 停驻 + 探活）属 todo 7， 本类对 GET 一律
 * 405——官方 client 收到 405 会退化为 request-response 模式（不建长连），故 POST 三连可独立跑通。 对 {@code startHttp}
 * 工厂与公开注入口属 todo 8，本类保持包私有。
 *
 * <p><b>verb→session 路由（写死，见设计文档 §一）</b>：
 *
 * <ul>
 *   <li>精确路径守卫 → {@code closing} ⇒ 503 → 方法分派；
 *   <li>POST {@code initialize}（唯一豁免会话头的 POST）：{@code sessionFactory.startSession(...)} → 会话入表 →
 *       200 + {@code Mcp-Session-Id} + {@code application/json}；
 *   <li>POST 普通 request（会话命中）：{@code session.responseStream(req, transport)} → 200 + {@code
 *       text/event-stream}（chunked）；<b>handler 必须等该 Mono 完成再返回</b>——{@code handle()} 返回即关连接，
 *       订阅即返回会让客户端读到截断的 SSE；
 *   <li>POST notification / response（会话命中）：{@code session.accept(...)} ⇒ 202；
 *   <li>DELETE（会话命中）：{@code session.delete()} + 出表 ⇒ 200；
 *   <li>无会话头 ⇒ 400；未知会话 ⇒ 404；方法不在 GET/POST/DELETE ⇒ 405 + {@code Allow}。
 * </ul>
 *
 * <p><b>明确不在实现面</b>：{@code McpTransportStream}/{@code consumeSseStream} 是 client 侧接口，服务端只需把 SSE
 * 帧写出去；{@code MCP-Protocol-Version} 不校验（照抄 SDK servlet 参考实现）；{@code Last-Event-ID} 重放不承诺（SDK 2.0.1
 * 的 {@code replay} 返回 {@code Flux.empty()}）。
 *
 * <p><b>线程与编码</b>：写侧用 {@link ReentrantLock}（非 {@code synchronized}——Java 21 虚拟线程下 {@code
 * synchronized} 会 pin 住载体线程）；所有字节显式 {@link StandardCharsets#UTF_8}。调用方必须给 {@code HttpServer} 装虚拟线程
 * executor（默认单线程 executor 会与将来的 parked GET 互锁，见设计文档 §四）。
 *
 * <p>本类不拥有 {@code HttpServer}：{@code createContext(path, this::handle)} 由调用方装配，{@link
 * #closeGracefully()} 只关会话，不 {@code stop()} 调用方的 server（生命周期所有权在 todo 8 的 {@code startHttp} 层定）。
 */
final class JdkHttpStreamableServerTransportProvider
    implements McpStreamableServerTransportProvider {

  private static final Logger LOG =
      LoggerFactory.getLogger(JdkHttpStreamableServerTransportProvider.class);

  /** 默认端点路径（与 SDK servlet 参考实现一致）。 */
  static final String DEFAULT_PATH = "/mcp";

  /** 请求体上限：16 MiB（与 SDK 参考实现的 {@code DEFAULT_REQUEST_MAX_SIZE} 同量级）。 */
  private static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;

  private static final String ACCEPT = "Accept";
  private static final String APPLICATION_JSON = "application/json";
  private static final String TEXT_EVENT_STREAM = "text/event-stream";

  /** SSE 事件类型：JSON-RPC 消息（SDK 常量 {@code MESSAGE_EVENT_TYPE}）。 */
  private static final String MESSAGE_EVENT_TYPE = "message";

  private final McpJsonMapper jsonMapper;
  private final String path;

  /** SDK 在 {@code McpServer.sync(provider).build()} 时注入；注入前 initialize 会得到 500。 */
  private volatile McpStreamableServerSession.Factory sessionFactory;

  /** 活动会话表（键 = {@code Mcp-Session-Id}）。 */
  private final ConcurrentHashMap<String, McpStreamableServerSession> sessions =
      new ConcurrentHashMap<>();

  /** 关闭标志：置真后所有请求 503（会话先被优雅关闭，见 {@link #closeGracefully()}）。 */
  private volatile boolean closing;

  JdkHttpStreamableServerTransportProvider(String path) {
    this(McpJsonDefaults.getMapper(), path);
  }

  JdkHttpStreamableServerTransportProvider(McpJsonMapper jsonMapper, String path) {
    if (jsonMapper == null || path == null || path.isBlank()) {
      throw new IllegalArgumentException("jsonMapper/path 不能为空");
    }
    this.jsonMapper = jsonMapper;
    this.path = path;
  }

  @Override
  public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
    this.sessionFactory = sessionFactory;
  }

  /**
   * 向所有活动会话广播通知。每个会话单独 try/catch：单会话失败只记日志、不阻断其余（未建 GET 监听流时 {@code sendNotification} 会抛 {@code
   * IllegalStateException}，属标准行为）。
   *
   * <p>本 todo 为最小实现；通知送达的完整语义（逐会话捕获 + 与 todo 7 的 GET 流配合）在 todo 7 收口。
   */
  @Override
  public Mono<Void> notifyClients(String method, Object params) {
    if (sessions.isEmpty()) {
      return Mono.empty();
    }
    return Mono.fromRunnable(
        () ->
            sessions
                .values()
                .forEach(
                    session -> {
                      try {
                        session.sendNotification(method, params).block();
                      } catch (Exception e) {
                        LOG.info(
                            "MCP 通知发送失败: session={} method={} err={}",
                            session.getId(),
                            method,
                            e.getMessage());
                      }
                    }));
  }

  /** 向单个会话发通知；会话不存在返回 {@code Mono.empty()}（不是错误）。 */
  @Override
  public Mono<Void> notifyClient(String sessionId, String method, Object params) {
    return Mono.defer(
        () -> {
          McpStreamableServerSession session = sessions.get(sessionId);
          if (session == null) {
            return Mono.empty();
          }
          return session.sendNotification(method, params);
        });
  }

  /** 优雅关闭：置 {@code closing}，逐个关会话（单个失败只记日志），清空会话表。 */
  @Override
  public Mono<Void> closeGracefully() {
    return Mono.fromRunnable(
        () -> {
          closing = true;
          sessions
              .values()
              .forEach(
                  session -> {
                    try {
                      session.closeGracefully().block();
                    } catch (Exception e) {
                      LOG.warn("MCP 会话优雅关闭失败: session={} err={}", session.getId(), e.getMessage());
                    }
                  });
          sessions.clear();
        });
  }

  /**
   * HTTP 分发入口（供调用方 {@code createContext(path, this::handle)}）。
   *
   * <p>校验顺序写死（见设计文档 §1.1）：<b>路径精确</b> → {@code closing} → 方法分派。路径必须逐字等于 {@code path}——{@code
   * createContext} 是<b>前缀</b>匹配，不显式守卫会让 {@code /mcp/extra} 也命中。
   */
  void handle(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestURI().getPath().equals(path)) {
      sendError(exchange, 404, "未知路径: " + exchange.getRequestURI().getPath());
      return;
    }
    if (closing) {
      sendError(exchange, 503, "服务正在关闭");
      return;
    }
    switch (exchange.getRequestMethod()) {
      case "POST" -> handlePost(exchange);
      case "DELETE" -> handleDelete(exchange);
      case "GET" -> handleGet(exchange);
      default -> {
        exchange.getResponseHeaders().set("Allow", "GET, POST, DELETE");
        exchange.sendResponseHeaders(405, -1);
        exchange.close();
      }
    }
  }

  /**
   * GET 占位：todo 5 不实现监听流（todo 7）。返回 405 让官方 client 退化为 request-response 模式——这是 SDK client 明确定义的
   * 分支（{@code HttpClientStreamableHttpTransport} 见 405 即 {@code Flux.empty()}），不会污染会话。
   */
  private void handleGet(HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().set("Allow", "POST, DELETE");
    exchange.sendResponseHeaders(405, -1);
    exchange.close();
  }

  /** POST：body 上限 → Accept 合格性 → 反序列化 → initialize / 会话路由。 */
  private void handlePost(HttpExchange exchange) throws IOException {
    byte[] body;
    try {
      body = readBody(exchange.getRequestBody(), MAX_REQUEST_BYTES);
    } catch (PayloadTooLargeException e) {
      sendError(exchange, 413, "请求体过大（上限 " + MAX_REQUEST_BYTES + " 字节）");
      return;
    }

    String accept = exchange.getRequestHeaders().getFirst(ACCEPT);
    if (accept == null
        || !accept.contains(APPLICATION_JSON)
        || !accept.contains(TEXT_EVENT_STREAM)) {
      sendError(exchange, 400, "Accept 必须同时包含 application/json 与 text/event-stream");
      return;
    }

    McpSchema.JSONRPCMessage message;
    try {
      message =
          McpSchema.deserializeJsonRpcMessage(jsonMapper, new String(body, StandardCharsets.UTF_8));
    } catch (IOException | IllegalArgumentException e) {
      sendError(exchange, 400, "无法解析 JSON-RPC 消息: " + e.getMessage());
      return;
    }

    // initialize 是唯一的会话创建点，也是唯一豁免会话头的 POST（它在应答里发放会话头）。
    if (message instanceof McpSchema.JSONRPCRequest request
        && McpSchema.METHOD_INITIALIZE.equals(request.method())) {
      handleInitialize(exchange, request);
      return;
    }

    String sessionId = exchange.getRequestHeaders().getFirst(HttpHeaders.MCP_SESSION_ID);
    if (sessionId == null || sessionId.isBlank()) {
      sendError(exchange, 400, "缺少 Mcp-Session-Id 会话头");
      return;
    }
    McpStreamableServerSession session = sessions.get(sessionId);
    if (session == null) {
      sendError(exchange, 404, "未知会话: " + sessionId);
      return;
    }

    if (message instanceof McpSchema.JSONRPCNotification notification) {
      acceptOrError(exchange, () -> session.accept(notification).block(), sessionId);
      return;
    }
    if (message instanceof McpSchema.JSONRPCResponse response) {
      acceptOrError(exchange, () -> session.accept(response).block(), sessionId);
      return;
    }
    if (message instanceof McpSchema.JSONRPCRequest request) {
      handleRequestStream(exchange, session, request);
      return;
    }
    sendError(exchange, 500, "未知消息类型");
  }

  /** notification / response 的公共处理：成功 ⇒ 202；处理抛错 ⇒ 500（SDK 对未知 response id 会抛）。 */
  private void acceptOrError(HttpExchange exchange, Runnable accept, String sessionId)
      throws IOException {
    try {
      accept.run();
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
    } catch (RuntimeException e) {
      LOG.warn("MCP 消息接收失败: session={} err={}", sessionId, e.getMessage());
      sendError(exchange, 500, "消息处理失败: " + e.getMessage());
    }
  }

  /** initialize：起会话 → 入表 → 200 + {@code Mcp-Session-Id} + JSON 体（体 = JSONRPCResponse.result）。 */
  private void handleInitialize(HttpExchange exchange, McpSchema.JSONRPCRequest request)
      throws IOException {
    McpStreamableServerSession.Factory factory = sessionFactory;
    if (factory == null) {
      sendError(exchange, 500, "会话工厂尚未注入（McpServer.sync(provider) 未 build）");
      return;
    }

    String sessionId;
    byte[] responseBytes;
    try {
      McpSchema.InitializeRequest initializeRequest =
          jsonMapper.convertValue(request.params(), new TypeRef<McpSchema.InitializeRequest>() {});
      McpStreamableServerSession.McpStreamableServerSessionInit init =
          factory.startSession(initializeRequest);
      sessionId = init.session().getId();
      sessions.put(sessionId, init.session());
      McpSchema.InitializeResult initResult = init.initResult().block();
      String json =
          jsonMapper.writeValueAsString(McpSchema.JSONRPCResponse.result(request.id(), initResult));
      responseBytes = json.getBytes(StandardCharsets.UTF_8);
    } catch (RuntimeException | IOException e) {
      LOG.error("MCP 会话初始化失败", e);
      sendError(exchange, 500, "会话初始化失败: " + e.getMessage());
      return;
    }

    exchange.getResponseHeaders().set("Content-Type", APPLICATION_JSON + "; charset=utf-8");
    exchange.getResponseHeaders().set(HttpHeaders.MCP_SESSION_ID, sessionId);
    exchange.sendResponseHeaders(200, responseBytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(responseBytes);
    }
  }

  /**
   * 普通 request：200 + {@code text/event-stream}（<b>chunked</b>：{@code sendResponseHeaders(200,
   * 0)}，绝不能用 body 长度——那会缓冲并立即关流）。handler <b>必须等</b> {@code responseStream} 的 Mono 完成再返回，否则 {@code
   * handle()} 返回即关连接、客户端读到截断的 SSE。
   */
  private void handleRequestStream(
      HttpExchange exchange, McpStreamableServerSession session, McpSchema.JSONRPCRequest request)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", TEXT_EVENT_STREAM + "; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
    exchange.sendResponseHeaders(200, 0);

    ExchangeTransport transport = new ExchangeTransport(exchange, session.getId());
    try {
      session.responseStream(request, transport).block();
    } catch (RuntimeException e) {
      LOG.warn(
          "MCP 请求流处理失败: session={} method={} err={}",
          session.getId(),
          request.method(),
          e.getMessage());
      transport.close();
    }
  }

  /** DELETE：会话命中则 {@code session.delete()} + 出表 ⇒ 200。 */
  private void handleDelete(HttpExchange exchange) throws IOException {
    String sessionId = exchange.getRequestHeaders().getFirst(HttpHeaders.MCP_SESSION_ID);
    if (sessionId == null || sessionId.isBlank()) {
      sendError(exchange, 400, "缺少 Mcp-Session-Id 会话头");
      return;
    }
    McpStreamableServerSession session = sessions.get(sessionId);
    if (session == null) {
      sendError(exchange, 404, "未知会话: " + sessionId);
      return;
    }
    try {
      session.delete().block();
      sessions.remove(sessionId);
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    } catch (RuntimeException e) {
      LOG.warn("MCP 会话删除失败: session={} err={}", sessionId, e.getMessage());
      sendError(exchange, 500, "会话删除失败: " + e.getMessage());
    }
  }

  /** 读请求体；超过上限抛 {@link PayloadTooLargeException}（调用方据此回 413）。 */
  private static byte[] readBody(InputStream in, int max) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int total = 0;
    int read;
    while ((read = in.read(chunk)) != -1) {
      total += read;
      if (total > max) {
        throw new PayloadTooLargeException();
      }
      buffer.write(chunk, 0, read);
    }
    return buffer.toByteArray();
  }

  /** JSON 错误响应（HTTP 层拒绝；官方 client 只看状态码，不看体）。 */
  private void sendError(HttpExchange exchange, int status, String message) throws IOException {
    byte[] bytes =
        jsonMapper.writeValueAsString(Map.of("error", message)).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", APPLICATION_JSON + "; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** 请求体超限的内部信号（同 SDK {@code MaxSizeExceededException} 语义）。 */
  private static final class PayloadTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;
  }

  /**
   * 单个 SSE 流（POST 请求的应答流）的传输实现：<b>只实现 5 个方法</b>（{@code sendMessage}×2 / {@code unmarshalFrom} /
   * {@code close} / {@code closeGracefully}）。
   *
   * <p>写侧用 {@link ReentrantLock} 串行化：SDK 的会话机可能从多个线程触发写（响应、通知、服务端请求），SSE 帧必须原子。 每次写完 {@code
   * flush()} 后 {@code checkError()}——{@link PrintWriter} 吞 IO 异常，不查它会把"客户端已断开"当成发送成功。
   */
  private final class ExchangeTransport implements McpStreamableServerTransport {

    private final HttpExchange exchange;
    private final String sessionId;
    private final PrintWriter writer;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed;

    ExchangeTransport(HttpExchange exchange, String sessionId) {
      this.exchange = exchange;
      this.sessionId = sessionId;
      this.writer =
          new PrintWriter(
              new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
      return sendMessage(message, null);
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
      return Mono.fromRunnable(
          () -> {
            if (closed) {
              return;
            }
            lock.lock();
            try {
              if (closed) {
                return;
              }
              String json = jsonMapper.writeValueAsString(message);
              writeSseEvent(messageId, json);
            } catch (IOException e) {
              LOG.warn("MCP SSE 发送失败（客户端可能已断开）: session={} err={}", sessionId, e.getMessage());
              sessions.remove(sessionId);
              close();
            } finally {
              lock.unlock();
            }
          });
    }

    /**
     * SSE 帧：{@code id: <id>\n} + {@code event: message\n} + {@code data: <紧凑 JSON>\n\n} + flush。
     *
     * <p>末尾空行是帧分隔符，缺它客户端不会 dispatch 该事件（变异自证①）。{@code id} 缺省用会话 id（SDK 对 {@code responseStream}
     * 的应答不传 messageId，见 servlet 参考实现）。
     */
    private void writeSseEvent(String messageId, String data) throws IOException {
      writer.write("id: " + (messageId != null ? messageId : sessionId) + "\n");
      writer.write("event: " + MESSAGE_EVENT_TYPE + "\n");
      writer.write("data: " + data + "\n\n");
      writer.flush();
      if (writer.checkError()) {
        throw new IOException("Client disconnected");
      }
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
      return jsonMapper.convertValue(data, typeRef);
    }

    @Override
    public Mono<Void> closeGracefully() {
      return Mono.fromRunnable(this::close);
    }

    @Override
    public void close() {
      lock.lock();
      try {
        if (closed) {
          return;
        }
        closed = true;
        writer.flush();
        exchange.close();
      } catch (RuntimeException e) {
        LOG.debug("关闭 SSE 交换失败: session={} err={}", sessionId, e.getMessage());
      } finally {
        lock.unlock();
      }
    }
  }
}
