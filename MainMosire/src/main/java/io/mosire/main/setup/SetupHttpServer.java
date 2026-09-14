package io.mosire.main.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.llm.ConfigApiKeySource;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmRouteLoader;
import io.mosire.agentlib.llm.ModelRoute;
import io.mosire.agentlib.llm.OpenAICompatibleLlmClient;
import io.mosire.agentlib.permission.AccessToken;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置引导 HTTP 面（配置引导 D4/D5；仅 {@code --setup} 时装配）：接收完整 OpenAI 格式 provider， 探测通过后写盘并热切换——进程不重启即以新
 * provider 工作。
 *
 * <p>安全基线与审批/Admin 面同款：<b>恒绑 127.0.0.1、无鉴权</b>（本面只在引导期使用，且 {@code llm.*}/{@code keys.*} 的写授权在
 * {@code ConfigAuth} 收敛为仅 SYSTEM——本服务以运行时自身身份落盘，不代理任何 Agent 身份）。 探测失败一律 {@code 400}
 * 且<b>不写盘</b>；错误消息来自客户端净化后的文案，不含密钥。
 *
 * <p>端点：
 *
 * <ul>
 *   <li>{@code GET /api/setup/status} → {@code {"configured":bool,"model":"unconfigured|<model>"}}；
 *   <li>{@code POST /api/setup/llm} → body {@code
 *       {"name":"default","baseUrl":"…","model":"…","apiKey":"…"}} （name 缺省 {@code default}
 *       且须过路由名关；apiKey 可空 = 匿名）→ 探测 → 写盘 → 热切换 → {@code 200 {"ok":true,"name","model"}}；参数坏/探测失败
 *       {@code 400}；方法不符 {@code 405}。
 * </ul>
 */
public final class SetupHttpServer {

  private static final Logger LOG = LoggerFactory.getLogger(SetupHttpServer.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  static final String PATH_STATUS = "/api/setup/status";
  static final String PATH_LLM = "/api/setup/llm";

  private final HttpServer server;
  private final ExecutorService executor;
  private final FileConfigStore store;
  private final SetupLlmRouter router;

  private SetupHttpServer(HttpServer server, FileConfigStore store, SetupLlmRouter router) {
    this.server = server;
    this.store = store;
    this.router = router;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /** 启动并绑回环地址（端口 {@code 0} = 系统分配，实际端口见 {@link #port()}）。 */
  public static SetupHttpServer start(int port, Path configRoot, SetupLlmRouter router) {
    Objects.requireNonNull(configRoot, "configRoot");
    Objects.requireNonNull(router, "router");
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
      SetupHttpServer app = new SetupHttpServer(server, new FileConfigStore(configRoot), router);
      server.createContext(PATH_STATUS, app::handleStatus);
      server.createContext(PATH_LLM, app::handleLlm);
      server.setExecutor(app.executor);
      server.start();
      return app;
    } catch (IOException e) {
      throw new IllegalStateException("配置引导面启动失败（恒绑 127.0.0.1） port=" + port, e);
    }
  }

  /** 实际绑定端口。 */
  public int port() {
    return server.getAddress().getPort();
  }

  /** 停止接收新请求（引导是一次性动作；进程关停链调用）。 */
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  private void handleStatus(HttpExchange exchange) throws IOException {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        send(exchange, 405, error("仅支持 GET"));
        return;
      }
      ObjectNode body = JSON.createObjectNode();
      body.put("configured", router.configured());
      body.put("model", router.model());
      send(exchange, 200, body);
    } finally {
      exchange.close();
    }
  }

  private void handleLlm(HttpExchange exchange) throws IOException {
    try {
      if (!"POST".equals(exchange.getRequestMethod())) {
        send(exchange, 405, error("仅支持 POST"));
        return;
      }
      JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
      if (body == null || !body.isObject()) {
        send(exchange, 400, error("body 必须是 JSON 对象"));
        return;
      }
      String name = textOr(body, "name", "default");
      String baseUrl = textOr(body, "baseUrl", "");
      String model = textOr(body, "model", "");
      String apiKey = textOr(body, "apiKey", "");
      if (!LlmRouteLoader.isValidRouteName(name)) {
        send(exchange, 400, error("name 非法（字母/数字开头，仅含字母数字_-）"));
        return;
      }
      if (baseUrl.isBlank() || model.isBlank()) {
        send(exchange, 400, error("baseUrl 与 model 必填"));
        return;
      }
      try {
        LlmProviderProbe.ping(baseUrl, model, apiKey);
      } catch (LlmException probeFailure) {
        LOG.info("setup 探测失败（未写盘） model={} : {}", model, probeFailure.getMessage());
        send(exchange, 400, error("探测失败：" + probeFailure.getMessage()));
        return;
      }
      LlmConfigWriter.write(store, name, baseUrl, model, apiKey);
      String credentialsRef = apiKey.isBlank() ? "" : "keys." + name;
      router.swap(
          new OpenAICompatibleLlmClient(
              new ModelRoute(name, baseUrl, model, credentialsRef),
              new ConfigApiKeySource(store, credentialsRef, AccessToken.SYSTEM)));
      LOG.info("setup 完成：路由 {} 模型 {}（热切换生效，配置已落盘）", name, model);
      ObjectNode ok = JSON.createObjectNode();
      ok.put("ok", true);
      ok.put("name", name);
      ok.put("model", model);
      send(exchange, 200, ok);
    } catch (IOException parseFailure) {
      send(exchange, 400, error("body 不是合法 JSON"));
    } finally {
      exchange.close();
    }
  }

  private static String textOr(JsonNode body, String field, String dflt) {
    JsonNode value = body.get(field);
    return value == null || !value.isTextual() ? dflt : value.asText();
  }

  private static ObjectNode error(String message) {
    ObjectNode node = JSON.createObjectNode();
    node.put("error", message);
    return node;
  }

  private static void send(HttpExchange exchange, int status, ObjectNode body) throws IOException {
    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
