package io.mosire.main.gateway.debug;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.brain.runtime.TurnResult;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调试对话网关（jdk.httpserver + 虚拟线程）——仅绑 127.0.0.1（安全基线同 AdminREST/A2A/AG-UI），把 {@link DebugChatService}
 * 的内存态运行记录以 JSON 形态暴露给开发期的 curl/脚本探查。
 *
 * <p>端点（单 context {@code /api/chat} 按路径后缀路由）：
 *
 * <ul>
 *   <li>{@code POST /api/chat}：body {@code {"message":".."}} → {@link DebugChatService#submit}，返回
 *       {@code {"runId":N,"status":"running"}}；message 缺失/空白/坏 JSON 一律 400。
 *   <li>{@code POST /api/chat/{runId}/stop}：{@link DebugChatService#stop}，返回 {@code
 *       {"runId":N,"stopped":true|false}}；runId 未知 404（与"存在但已终态"的 stopped=false 区分）。
 *   <li>{@code GET /api/chat/{runId}}：运行快照 {@code
 *       {"runId","status","message","createdAt","result"}}； result 仅终态有值（RUNNING 与硬失败时为 null）。
 *   <li>{@code GET /api/chat/results?page=0&size=10}：{@link DebugChatService#listResults} 倒序分页， 返回
 *       {@code {"results":[...]}}；page/size 非法（非整数/越界）一律 400。
 *   <li>{@code POST /api/chat/session}：{@link DebugChatService#resetSession()} 重置会话（P3-3 / D26）， 返回
 *       {@code {"conversationId":"<新>","previousConversationId":"<旧>"}}；服务已关停 503。
 * </ul>
 *
 * <p>方法门禁：各端点只接受声明的方法，其余 405（同 {@code AdminHttpServer.requireGet} 形态）。status 一律小写 （{@code
 * running}/{@code finished}/{@code cancelled}/{@code failed}），stopReason 用枚举 {@code name()}，
 * createdAt 用 {@code Instant.toString()}（ISO-8601）。JSON 响应全部手工构建 Map 经 {@link ObjectMapper} 序列化
 * （不注册任何 JavaTimeModule——Instant 已显式转字符串）。
 */
public final class DebugChatHttpServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(DebugChatHttpServer.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String CONTEXT_PATH = "/api/chat";
  private static final String STOP_SUFFIX = "/stop";
  private static final String SESSION_SUFFIX = "/session";

  /** /api/results 默认分页（page=0、size=10）。 */
  private static final int DEFAULT_PAGE = 0;

  private static final int DEFAULT_SIZE = 10;

  private final HttpServer server;
  private final ExecutorService executor;
  private final DebugChatService chat;

  /**
   * 启动并绑回环地址；port=0 时由系统分配（默认与测试用），实际端口见 {@link #port()}。
   *
   * @throws IllegalStateException 端口绑定失败（占用等）
   */
  public static DebugChatHttpServer start(int port, DebugChatService chat) {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
      DebugChatHttpServer app = new DebugChatHttpServer(server, chat);
      server.createContext(CONTEXT_PATH, app::handleChat);
      server.setExecutor(app.executor);
      server.start();
      return app;
    } catch (IOException e) {
      throw new IllegalStateException("调试对话网关启动失败 port=" + port, e);
    }
  }

  private DebugChatHttpServer(HttpServer server, DebugChatService chat) {
    this.server = server;
    this.chat = chat;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /** 单 context 路由：按路径后缀分发到四端点（未知形态一律 404）。 */
  private void handleChat(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String rest =
        path.length() > CONTEXT_PATH.length() ? path.substring(CONTEXT_PATH.length()) : "";
    if (rest.isEmpty()) {
      if (!requireMethod(exchange, "POST")) {
        return;
      }
      handleSubmit(exchange);
    } else if (rest.equals("/results")) {
      if (!requireMethod(exchange, "GET")) {
        return;
      }
      handleResults(exchange);
    } else if (rest.equals(SESSION_SUFFIX)) {
      if (!requireMethod(exchange, "POST")) {
        return;
      }
      handleSessionReset(exchange);
    } else if (rest.endsWith(STOP_SUFFIX)) {
      int cut = rest.length() - STOP_SUFFIX.length();
      Long runId = cut > 1 ? parseRunId(rest.substring(1, cut)) : null;
      if (runId == null) {
        send(exchange, 404, Map.of("error", "未知 runId"));
        return;
      }
      if (!requireMethod(exchange, "POST")) {
        return;
      }
      handleStop(exchange, runId);
    } else {
      Long runId = parseRunId(rest.substring(1));
      if (runId == null) {
        send(exchange, 404, Map.of("error", "未知 runId"));
        return;
      }
      if (!requireMethod(exchange, "GET")) {
        return;
      }
      handleGetRun(exchange, runId);
    }
  }

  /** POST /api/chat：解析 body.message → submit（坏 JSON/缺失/空白 message 一律 400）。 */
  private void handleSubmit(HttpExchange exchange) throws IOException {
    String body;
    try (var in = exchange.getRequestBody()) {
      body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String message = null;
    try {
      JsonNode node = JSON.readTree(body);
      if (node != null
          && node.isObject()
          && node.has("message")
          && node.get("message").isTextual()) {
        message = node.get("message").asText();
      }
    } catch (JsonProcessingException e) {
      // 坏 JSON = 缺失 message 同等处理（400），不猜测修复
    }
    if (message == null || message.isBlank()) {
      send(exchange, 400, Map.of("error", "message 必填且不能为空白"));
      return;
    }
    long runId;
    try {
      runId = chat.submit(message);
    } catch (IllegalStateException e) {
      send(exchange, 503, Map.of("error", "调试对话服务已关停"));
      return;
    }
    send(exchange, 200, Map.of("runId", runId, "status", "running"));
  }

  /**
   * POST /api/chat/session：重置会话（P3-3 / D26）——返回 {@code
   * {"conversationId":"<新>","previousConversationId":"<旧>"}}。
   *
   * <p>无请求体（重置不需要参数：新 id 由服务生成，调用方不指定）。响应语义是"<b>已受理</b>"而非"已生效"——切换动作排在共享 chat 执行器上
   * （R11）。这对调用方是够用的：同一执行器 FIFO ⇒ 拿到本响应之后提交的对话回合必然在切换<b>之后</b>执行，不会与在途回合交错。 服务已关停（含关停窗内 执行器已拒）一律
   * 503：绝不回报一个没发生的重置。
   */
  private void handleSessionReset(HttpExchange exchange) throws IOException {
    DebugSessionReset reset;
    try {
      reset = chat.resetSession();
    } catch (IllegalStateException e) {
      send(exchange, 503, Map.of("error", "调试对话服务已关停"));
      return;
    }
    send(
        exchange,
        200,
        Map.of(
            "conversationId", reset.conversationId(),
            "previousConversationId", reset.previousConversationId()));
  }

  /** POST /api/chat/{runId}/stop：未知 runId 404；已知则转发取消（stopped=false = 已终态 no-op）。 */
  private void handleStop(HttpExchange exchange, long runId) throws IOException {
    if (chat.get(runId).isEmpty()) {
      send(exchange, 404, Map.of("error", "未知 runId: " + runId));
      return;
    }
    send(exchange, 200, Map.of("runId", runId, "stopped", chat.stop(runId)));
  }

  /** GET /api/chat/{runId}：运行快照（result 仅终态有值——RUNNING/硬失败为 null）。 */
  private void handleGetRun(HttpExchange exchange, long runId) throws IOException {
    Optional<DebugChatRun> run = chat.get(runId);
    if (run.isEmpty()) {
      send(exchange, 404, Map.of("error", "未知 runId: " + runId));
      return;
    }
    send(exchange, 200, runRow(run.get()));
  }

  /** GET /api/chat/results：倒序分页（page 缺省 0、size 缺省 10；非法参数 400）。 */
  private void handleResults(HttpExchange exchange) throws IOException {
    Map<String, String> params = queryParams(exchange.getRequestURI());
    int page;
    int size;
    try {
      page = Integer.parseInt(params.getOrDefault("page", String.valueOf(DEFAULT_PAGE)));
      size = Integer.parseInt(params.getOrDefault("size", String.valueOf(DEFAULT_SIZE)));
    } catch (NumberFormatException e) {
      send(exchange, 400, Map.of("error", "page/size 必须为整数"));
      return;
    }
    List<DebugChatRun> runs;
    try {
      runs = chat.listResults(page, size);
    } catch (IllegalArgumentException e) {
      send(exchange, 400, Map.of("error", e.getMessage() == null ? "非法分页参数" : e.getMessage()));
      return;
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (DebugChatRun run : runs) {
      rows.add(runRow(run));
    }
    send(exchange, 200, Map.of("results", rows));
  }

  /**
   * 运行记录的 JSON 投影（单查与列表共用）：status 小写、createdAt 为 ISO-8601 字符串、result 仅终态有值。
   *
   * <p>用 LinkedHashMap 而非 Map.of：result 允许 null（Map.of 拒绝 null 值）。
   */
  private static Map<String, Object> runRow(DebugChatRun run) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("runId", run.runId());
    row.put("status", run.status().name().toLowerCase(Locale.ROOT));
    row.put("message", run.message());
    row.put("createdAt", run.createdAt().toString());
    row.put("result", run.result() == null ? null : resultRow(run.result()));
    return row;
  }

  /** TurnResult 投影：stopReason 用枚举 name()，text 为最终文本（可能为空串）。 */
  private static Map<String, Object> resultRow(TurnResult result) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("stopReason", result.stopReason().name());
    row.put("turns", result.turns());
    row.put("toolCalls", result.toolCalls());
    row.put("text", result.text());
    return row;
  }

  /** runId 路径段解析：非正整数一律视为未知（404），不做猜测性纠正。 */
  private static Long parseRunId(String raw) {
    try {
      long id = Long.parseLong(raw);
      return id > 0 ? id : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** 端点方法门禁：非声明方法 405（带 Allow 头），响应体缺省（同 AdminREST）。 */
  private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
    if (method.equals(exchange.getRequestMethod())) {
      return true;
    }
    exchange.getResponseHeaders().set("Allow", method);
    exchange.sendResponseHeaders(405, -1);
    exchange.close();
    return false;
  }

  private static Map<String, String> queryParams(URI uri) {
    Map<String, String> params = new HashMap<>();
    String query = uri.getRawQuery();
    if (query == null || query.isEmpty()) {
      return params;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        params.put(
            URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return params;
  }

  private static void send(HttpExchange exchange, int code, Object body) throws IOException {
    byte[] bytes;
    try {
      bytes = JSON.writeValueAsBytes(body);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException("调试对话响应序列化失败", e);
    }
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(code, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** 实际绑定端口（port=0 自动分配场景下即真实端口）。 */
  public int port() {
    return server.getAddress().getPort();
  }

  /** 停止 HTTP 接入并关停自身 executor；不触碰注入的 {@link DebugChatService} 与共享 chat 执行器（归 App）。 */
  @Override
  public void close() {
    server.stop(0);
    executor.shutdown();
    LOG.info("调试对话网关已停止");
  }
}
