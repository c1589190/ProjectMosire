package io.mosire.main.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AdminREST（jdk.httpserver + 虚拟线程）——默认仅绑 127.0.0.1（安全基线，计划 §六）。
 *
 * <p>M1 端点：{@code GET /health}、{@code GET /api/status}；其余路径 404。后续端点
 * （agents/tools/events，权限：guest/只读）在 M2/M3 扩展。
 */
public final class AdminHttpServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(AdminHttpServer.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer server;
  private final ExecutorService executor;
  private final Supplier<StatusSnapshot> statusSource;
  private final long start = System.currentTimeMillis();

  /** 启动并绑回环地址；port=0 时由系统分配（测试用），实际端口见 {@link #boundPort()}。 */
  public static AdminHttpServer start(int port, Supplier<StatusSnapshot> statusSource) {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
      AdminHttpServer app = new AdminHttpServer(server, statusSource);
      server.createContext("/health", app::handleHealth);
      server.createContext("/api/status", app::handleStatus);
      server.setExecutor(app.executor);
      server.start();
      return app;
    } catch (IOException e) {
      throw new IllegalStateException("AdminREST 启动失败 port=" + port, e);
    }
  }

  private AdminHttpServer(HttpServer server, Supplier<StatusSnapshot> statusSource) {
    this.server = server;
    this.statusSource = statusSource;
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

  private static void send(HttpExchange exchange, int code, Map<String, Object> body)
      throws IOException {
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
