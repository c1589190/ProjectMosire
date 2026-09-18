package io.mosire.main.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.mosire.agentlib.approval.ApprovalHttpEndpoint;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.CommandModeHolder;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S6 命令档位端点的 Main 侧<b>薄包装</b>：{@code GET /api/commands/mode} 读、{@code POST} 改主 Agent 的命令档位。
 *
 * <p><b>为什么在 Main 而不在 AgentLib</b>（设计 §五 A3 裁决）：档位是主 Agent 的命令策略，属 Brain/Main 面；AgentLib
 * 的通用审批件只做"审批传输 + 登记表接入"，不该认识"档位"这个概念。本类因此只把端点逻辑挂在<b>同一台</b> {@link ApprovalHttpEndpoint} 的同一端口上（经
 * {@link ApprovalHttpEndpoint#createContext(String, HttpHandler)}），<b>不</b>另起第二台 server。
 *
 * <p><b>不含任何审批判定</b>：本类不读也不写 {@code PendingApprovals}，不参与 {@code ApprovalCoordinator} 的放行/拒绝；它只是
 * {@link CommandModeHolder} 的一层 HTTP 读写口。
 *
 * <p><b>改档立即对下一次调用生效</b>（读方现读持有者，不经重启）。<b>坏值一律 400、绝不静默当某一档</b>：把 {@code "partial"} 读成 {@code FULL}
 * 等于无声放开命令闸，是本功能最不该有的失败形态。
 *
 * <p><b>留痕只到日志</b>：档位变更没有对应事件类型（事件词汇表在 Brain，加一类要动那份契约），故本层只 {@code LOG.info} 记录 "旧值 → 新值 +
 * by"。要持久审计得先加事件类型，别在响应里假装。
 */
public final class CommandModeHttpHandler implements HttpHandler {

  private static final Logger LOG = LoggerFactory.getLogger(CommandModeHttpHandler.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 档位端点路径（S6）：{@code GET} 读、{@code POST} 改。 */
  public static final String PATH = "/api/commands/mode";

  /** 请求体上限（byte）：档位 body 只有几十字节，给足余量后超限直接 400（不做流式解析）。 */
  private static final int MAX_BODY_BYTES = 8192;

  /** {@code by} 缺省值（审计注记，不参与判定）。 */
  private static final String DEFAULT_BY = "http";

  /** 主 Agent 档位持有者（S6）。 */
  private final CommandModeHolder commandMode;

  public CommandModeHttpHandler(CommandModeHolder commandMode) {
    this.commandMode = Objects.requireNonNull(commandMode, "commandMode");
  }

  /**
   * 把档位端点挂到<b>同一台</b>审批 server 上（不另起第二台 server/端口）。
   *
   * <p>{@code commandMode == null} = 不提供档位端点（该路径一律 404，与迁移前 Main 侧审批面的 4 参 重载同口径）。
   */
  public static void register(ApprovalHttpEndpoint endpoint, CommandModeHolder commandMode) {
    Objects.requireNonNull(endpoint, "endpoint");
    if (commandMode != null) {
      endpoint.createContext(PATH, new CommandModeHttpHandler(commandMode));
    }
  }

  /** {@code GET} 读当前档位；{@code POST} 改；其余方法 405（带 {@code Allow: GET, POST}）。 */
  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("GET".equals(method)) {
      send(exchange, 200, Map.of("mode", commandMode.get().wireName()));
      return;
    }
    if (!"POST".equals(method)) {
      exchange.getResponseHeaders().set("Allow", "GET, POST");
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
    if (raw.length > MAX_BODY_BYTES) {
      send(exchange, 400, Map.of("error", "请求体过大（上限 " + MAX_BODY_BYTES + " 字节）"));
      return;
    }
    JsonNode node;
    try {
      node = raw.length == 0 ? null : JSON.readTree(raw);
    } catch (JsonProcessingException e) {
      send(exchange, 400, Map.of("error", "请求体不是合法 JSON"));
      return;
    }
    if (node == null || !node.isObject()) {
      send(exchange, 400, Map.of("error", "请求体必须是 JSON 对象"));
      return;
    }
    JsonNode modeNode = node.get("mode");
    if (modeNode == null || !modeNode.isTextual()) {
      send(exchange, 400, Map.of("error", "mode 必填且必须是文本（full|limited）"));
      return;
    }
    CommandMode parsed = CommandMode.parse(modeNode.asText());
    if (parsed == null) {
      send(exchange, 400, Map.of("error", "mode 只接受 full|limited"));
      return;
    }
    JsonNode byNode = node.get("by");
    String by =
        byNode != null && byNode.isTextual() && !byNode.asText().isBlank()
            ? byNode.asText().strip()
            : DEFAULT_BY;
    CommandMode previous = commandMode.set(parsed);
    LOG.info("命令档位已改: {} → {}（by={}）", previous.wireName(), parsed.wireName(), by);
    Map<String, Object> receipt = new LinkedHashMap<>();
    receipt.put("mode", parsed.wireName());
    receipt.put("previous", previous.wireName());
    receipt.put("by", by);
    send(exchange, 200, receipt);
  }

  private static void send(HttpExchange exchange, int code, Object body) throws IOException {
    byte[] bytes;
    try {
      bytes = JSON.writeValueAsBytes(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("档位端点响应序列化失败", e);
    }
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(code, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
