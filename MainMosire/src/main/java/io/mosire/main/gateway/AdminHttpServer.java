package io.mosire.main.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.brain.subagent.SubagentInstance;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AdminREST（jdk.httpserver + 虚拟线程）——默认仅绑 127.0.0.1（安全基线，计划 §六）。
 *
 * <p>端点（W5 扩展）：{@code GET /health}、{@code GET /api/status}（M1）；{@code GET /api/agents}（子 Agent
 * 列表，{@link #start(int, Supplier, Supplier, Supplier, Function)} 的 agentsSource 快照）、{@code GET
 * /api/tools}（registry 工具名单——名字数组）、{@code GET /api/events}（guest 只读、offset/limit 分页 +
 * agent/type/correlationId 过滤）。三个 W5 端点只接受 GET（其余方法 405——只读面）；"guest" = 无凭据调用即为
 * 最低权限形态（不要求任何认证头——只读数据面，计划 §5.2.1 记法）。
 *
 * <p><b>分页约定</b>：{@code /api/events} 参数 {@code limit}（默认 50，≥1）/ {@code offset}（默认 0，≥0）——底层 {@link
 * EventStore#query} 只有 beforeSeq 游标形态，因此 handler 以 {@code EventQuery(limit = offset + limit)}
 * 拉取后在内存侧跳过 offset 条（事件列表本地库规模小，避免为只读面新增 offset 查询 API——中期计划 W5 "复用现有查询"）。 结果按 seq 倒序（Store
 * 契约），响应形如 {@code
 * {"events":[{"seq":..,"ts":"..","type":"..","agent":"..","payload":…,"correlationId":".."}]}}；
 * payload 为合法 JSON 时按对象返回（否则原样字符串）。
 *
 * <p>其余路径 404。{@code /health}/{@code /api/status} 保持 M1 语义（只读快照）。
 */
public final class AdminHttpServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(AdminHttpServer.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** /api/events 默认分页大小（只读面，足够本地审计；max 由 offset+limit 上限兜底）。 */
  private static final int DEFAULT_EVENTS_LIMIT = 50;

  private final HttpServer server;
  private final ExecutorService executor;
  private final Supplier<StatusSnapshot> statusSource;
  private final Supplier<List<SubagentInstance>> agentsSource;
  private final Supplier<List<String>> toolsSource;
  private final Function<EventQuery, List<Event>> eventsSource;
  private final long start = System.currentTimeMillis();

  /**
   * 启动并绑回环地址；port=0 时由系统分配（测试用），实际端口见 {@link #boundPort()}。
   *
   * <p>仅健康/状态面（M1 测试兼容形态；agents/tools 恒空、events 恒空表——W5 三端点请用完全构造）。
   */
  public static AdminHttpServer start(int port, Supplier<StatusSnapshot> statusSource) {
    return start(port, statusSource, List::of, List::of, query -> List.of());
  }

  /**
   * 启动（W5 完全构造）：三端点数据面外置注入——{@code agentsSource}（{@code SubagentManager.list} 快照）、 {@code
   * toolsSource}（registry 名单）、{@code eventsSource}（{@code EventStore.query} 只读入口，handler 不持有 Store
   * 本体）。
   */
  public static AdminHttpServer start(
      int port,
      Supplier<StatusSnapshot> statusSource,
      Supplier<List<SubagentInstance>> agentsSource,
      Supplier<List<String>> toolsSource,
      Function<EventQuery, List<Event>> eventsSource) {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
      AdminHttpServer app =
          new AdminHttpServer(server, statusSource, agentsSource, toolsSource, eventsSource);
      server.createContext("/health", app::handleHealth);
      server.createContext("/api/status", app::handleStatus);
      server.createContext("/api/agents", app::handleAgents);
      server.createContext("/api/tools", app::handleTools);
      server.createContext("/api/events", app::handleEvents);
      server.setExecutor(app.executor);
      server.start();
      return app;
    } catch (IOException e) {
      throw new IllegalStateException("AdminREST 启动失败 port=" + port, e);
    }
  }

  private AdminHttpServer(
      HttpServer server,
      Supplier<StatusSnapshot> statusSource,
      Supplier<List<SubagentInstance>> agentsSource,
      Supplier<List<String>> toolsSource,
      Function<EventQuery, List<Event>> eventsSource) {
    this.server = server;
    this.statusSource = statusSource;
    this.agentsSource = agentsSource;
    this.toolsSource = toolsSource;
    this.eventsSource = eventsSource;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  private void handleHealth(HttpExchange exchange) throws IOException {
    StatusSnapshot snapshot = statusSource.get();
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    send(
        exchange,
        200,
        Map.of(
            "status", snapshot.status(),
            "artifact", snapshot.artifact(),
            "version", snapshot.version(),
            "pid", snapshot.pid(),
            "uptimeMillis", System.currentTimeMillis() - start));
  }

  private void handleStatus(HttpExchange exchange) throws IOException {
    StatusSnapshot snapshot = statusSource.get();
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    send(
        exchange,
        200,
        Map.of(
            "status", snapshot.status(),
            "agentId", snapshot.agentId(),
            "eventCount", snapshot.eventCount(),
            "uptimeMillis", System.currentTimeMillis() - start));
  }

  /** 子 Agent 列表（agent 面只读：instanceId/templateId/goal/depth/status——不含权限集/运行配置）。 */
  private void handleAgents(HttpExchange exchange) throws IOException {
    if (!requireGet(exchange)) {
      return;
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (SubagentInstance instance : agentsSource.get()) {
      rows.add(
          Map.of(
              "instanceId", instance.instanceId(),
              "templateId", instance.templateId(),
              "goal", instance.goal(),
              "depth", instance.depth(),
              "status", instance.status().name()));
    }
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    send(exchange, 200, Map.of("agents", rows));
  }

  /** registry 工具名单（名字数组；按 registry 快照排序——ToolRegistry.list 语义）。 */
  private void handleTools(HttpExchange exchange) throws IOException {
    if (!requireGet(exchange)) {
      return;
    }
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    send(exchange, 200, Map.of("tools", toolsSource.get()));
  }

  /** 事件只读查询（分页 offset/limit + agent/type/correlationId 过滤；过滤条件是空字符串=不过滤）。 */
  private void handleEvents(HttpExchange exchange) throws IOException {
    if (!requireGet(exchange)) {
      return;
    }
    Map<String, String> params = queryParams(exchange.getRequestURI());
    int limit;
    int offset;
    try {
      limit = Integer.parseInt(params.getOrDefault("limit", String.valueOf(DEFAULT_EVENTS_LIMIT)));
      offset = Integer.parseInt(params.getOrDefault("offset", "0"));
    } catch (NumberFormatException e) {
      send(exchange, 400, Map.of("error", "limit/offset 必须为整数"));
      return;
    }
    if (limit < 1 || offset < 0) {
      send(exchange, 400, Map.of("error", "limit 必须 ≥1 且 offset 必须 ≥0"));
      return;
    }
    String agent = params.getOrDefault("agent", "");
    String type = params.getOrDefault("type", "");
    String correlationId = params.getOrDefault("correlationId", "");
    // 底层只有 beforeSeq 游标：以 limit=offset+limit 一次拉取，再跳过前 offset 条（分页约定见类注释）
    int fetch = (int) Math.min((long) limit + offset, Integer.MAX_VALUE);
    List<Event> all = eventsSource.apply(new EventQuery(agent, type, correlationId, -1, fetch));
    int from = Math.min(offset, all.size());
    int to = Math.min(offset + limit, all.size());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Event event : all.subList(from, to)) {
      rows.add(
          Map.of(
              "seq", event.seq(),
              "ts", event.ts().toString(),
              "type", event.type(),
              "agent", event.agent(),
              "payload", payloadNode(event.payload()),
              "correlationId", event.correlationId()));
    }
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    send(exchange, 200, Map.of("events", rows));
  }

  /** W5 只读端点方法门禁（health/status 维持 M1 布局不变）。 */
  private static boolean requireGet(HttpExchange exchange) throws IOException {
    if ("GET".equals(exchange.getRequestMethod())) {
      return true;
    }
    exchange.sendResponseHeaders(405, -1);
    exchange.close();
    return false;
  }

  /** payload 字段：合法 JSON → 结构化对象；坏 JSON/空 → 原样字符串（如实传递，不做猜测性重写）。 */
  private static Object payloadNode(String payload) {
    if (payload == null || payload.isBlank()) {
      return payload == null ? "" : payload;
    }
    try {
      return JSON.readTree(payload);
    } catch (JsonProcessingException e) {
      return payload;
    }
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
    byte[] bytes = JSON.writeValueAsBytes(body);
    exchange.sendResponseHeaders(code, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** 实际绑定端口。 */
  public int boundPort() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdown();
    LOG.info("AdminREST 已停止");
  }
}
