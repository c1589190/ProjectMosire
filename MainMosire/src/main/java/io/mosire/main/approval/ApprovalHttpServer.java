package io.mosire.main.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.PendingApprovals;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审批 HTTP 面（三期 S4-B2，形态照抄 {@code AdminHttpServer}：{@code com.sun.net.httpserver} + 虚拟线程）： 待人裁决列表 +
 * 人的答复入口。
 *
 * <p><b>恒绑 {@code 127.0.0.1}，无鉴权——这是有意的</b>（与既有 AdminREST 同基线）：本面既没有认证也没有 TLS，任何能连到该端口的
 * 本机进程都能读待裁决项并替人作答。因此 <b>{@code HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)}
 * 是硬写死的</b>： 本类<b>不提供任何改绑地址的入口</b>（没有 host 形参、没有可配 host 的口）。要对外提供服务必须先有鉴权，那是另一个包的事。
 *
 * <p>端点：
 *
 * <ul>
 *   <li>{@code GET /api/approvals} → {@code
 *       {"pending":[{id,tool,classKey,summary,digest,createdAtEpochMs,deadlineEpochMs}]}}
 *       ——待裁决<b>快照</b>（直接从登记表读，不持有副本：副本会有"人在副本上作答"的分叉）；
 *   <li>{@code POST /api/approvals/{id}} → body {@code
 *       {"decision":"approve|deny","scope":"once|session","by":"<可选文本>"}}： 成功 {@code 200} +
 *       决议回执；未知/已过期/已被摘除的 id {@code 404}；重复决议 {@code 409}；坏 body {@code 400}； 方法不符 {@code 405}（带
 *       {@code Allow} 头，同既有惯例）。
 * </ul>
 *
 * <p><b>回执里的 scope 是"实际生效"的那个</b>（本类最容易写错的一条）：会话级放行对<b>非唯一身份</b>的调用者（{@code DEFAULT} 桶 = 全部子
 * Agent、{@code GUEST} 桶 = 全部外部 MCP 客户端）会被编排器降级为一次——人点了"本会话"而实际只批了一次，这个差异必须在回执里 看得见。取值只能用 {@link
 * ApprovalCoordinator#effectiveDecision(String, ApprovalDecision)}（收窄的<b>唯一</b>实现）， <b>不得</b>在本层复制
 * {@code "SYSTEM"} 字面量或任何降级规则：那样收窄一改，响应就会对人说谎，而没有任何用例会红。
 *
 * <p><b>判定顺序（先查后决，别只靠 decide 的布尔）</b>：{@code get(id)} 为空 ⇒ 404（从未存在 / 已过期 / 已被编排器 fail-closed
 * 摘除，三者外部不可区分）；有值且 {@code decide} 返回真 ⇒ 200；有值但 {@code decide} 返回假 ⇒ 409（已被人决议过，幂等保护）。
 *
 * <p><b>{@code by} 只是审计注记</b>：body 里的 {@code by} 原样进登记表与事件，<b>不参与</b>任何判定——不存在"body 里自称 root
 * 所以放行"这类路径。缺省填 {@code "http"}（本通道名）。
 *
 * <p><b>不落任何明文</b>：响应与日志只带 {@code id}/{@code tool}/{@code classKey}/{@code summary}/{@code digest}
 * 与决议——{@code summary} 由工具自报（{@code ToolGate.Ask} 的第二字段），本层不拼参数、不读 args。
 *
 * <p><b>已知边界（记账，不是已解决）</b>：人卡在超时窗口的最后一刻答复、而编排器仍按超时 DENY 时，该项会被摘除 ⇒ 人先前拿到的 {@code 200} 与随后的 {@code
 * 404} 并存。这是 fail-closed 的窄窗口（那次工具调用已被拒，没有任何放行发生），S4-B2 不处理。
 */
public final class ApprovalHttpServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(ApprovalHttpServer.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 审批面根路径（{@code GET} 用本路径，{@code POST} 用本路径 + {@code /{id}}）。 */
  static final String PATH = "/api/approvals";

  /** 请求体上限（byte）：审批 body 只有几十字节，给足余量后超限直接 400（不做流式解析）。 */
  private static final int MAX_BODY_BYTES = 8192;

  /** {@code by} 缺省值 = 本通道名（审计注记，不参与判定）。 */
  private static final String DEFAULT_BY = "http";

  private final HttpServer server;
  private final ExecutorService executor;
  private final PendingApprovals pending;
  private final ApprovalCoordinator coordinator;

  /**
   * 启动并绑回环地址（端口 {@code 0} = 由系统分配，实际端口见 {@link #boundPort()}）。
   *
   * <p>只收 {@code port}：<b>没有 host 形参</b>——绑定地址是编译期常量（见类 javadoc 的安全基线）。
   *
   * @param pending 进程内<b>唯一</b>的那份登记表（与编排器同一个实例：两通道看到的必须是同一 id）
   * @param coordinator 审批编排器——本面靠它算"实际生效的 scope"（{@link
   *     ApprovalCoordinator#effectiveDecision(String, ApprovalDecision)}）
   */
  public static ApprovalHttpServer start(
      int port, PendingApprovals pending, ApprovalCoordinator coordinator) {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
      ApprovalHttpServer app = new ApprovalHttpServer(server, pending, coordinator);
      server.createContext(PATH, app::handleApprovals);
      server.setExecutor(app.executor);
      server.start();
      return app;
    } catch (IOException e) {
      throw new IllegalStateException("审批 HTTP 面启动失败（恒绑 127.0.0.1） port=" + port, e);
    }
  }

  private ApprovalHttpServer(
      HttpServer server, PendingApprovals pending, ApprovalCoordinator coordinator) {
    this.server = server;
    this.pending = pending;
    this.coordinator = coordinator;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /** 单一上下文 + 路径后缀分发（同既有网关惯例）：{@code ""} = 列表（GET），{@code /{id}} = 决议（POST）。 */
  private void handleApprovals(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (path == null || !path.startsWith(PATH)) {
      send(exchange, 404, Map.of("error", "未知路径"));
      return;
    }
    String suffix = path.substring(PATH.length());
    if (suffix.isEmpty()) {
      if (!requireMethod(exchange, "GET")) {
        return;
      }
      handleList(exchange);
      return;
    }
    // 只认 "/{id}"：多余的路径段（/a/b）与裸斜杠（/）都不猜，一律 404
    if (suffix.length() < 2 || suffix.charAt(0) != '/' || suffix.indexOf('/', 1) >= 0) {
      send(exchange, 404, Map.of("error", "未知路径"));
      return;
    }
    if (!requireMethod(exchange, "POST")) {
      return;
    }
    handleDecide(exchange, suffix.substring(1));
  }

  /** 待裁决快照：源就是登记表（不读副本——副本会与人的答复分叉）。 */
  private void handleList(HttpExchange exchange) throws IOException {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (ApprovalRequest request : pending.pending()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", request.id());
      row.put("tool", request.tool());
      row.put("classKey", request.classKey());
      row.put("summary", request.summary());
      row.put("digest", request.digest());
      row.put("createdAtEpochMs", request.createdAtEpochMs());
      row.put("deadlineEpochMs", request.deadlineEpochMs());
      rows.add(row);
    }
    send(exchange, 200, Map.of("pending", rows));
  }

  /** 人的答复：先查后决（404 / 200 / 409 三态可分辨，见类 javadoc 的判定顺序）。 */
  private void handleDecide(HttpExchange exchange, String id) throws IOException {
    Optional<ApprovalRequest> registered = pending.get(id);
    if (registered.isEmpty()) {
      // 从未存在 / 已过期 / 已被编排器摘除——三者外部不可区分，一律 404（B1 已把语义定死）
      send(exchange, 404, Map.of("error", "未知或已失效的审批 id"));
      return;
    }
    Body body = readBody(exchange);
    if (body == null) {
      return; // 400 已发
    }
    ApprovalDecision requested = body.decision();
    String by = body.by();
    if (!pending.decide(id, requested, by)) {
      // 有值却决不动 = 已经被人决议过（幂等保护）
      send(exchange, 409, Map.of("error", "该审批已决议（幂等保护：重复决议不覆盖首次决定）"));
      return;
    }
    // ★ 实际生效的 scope：收窄只在编排器里实现一处，本层只调它（不得复制 "SYSTEM" 字面量或降级规则）
    ApprovalDecision effective =
        coordinator.effectiveDecision(registered.get().callerKey(), requested);
    Map<String, Object> receipt = new LinkedHashMap<>();
    receipt.put("id", id);
    receipt.put("decision", effective == ApprovalDecision.DENY ? "deny" : "approve");
    receipt.put("scope", scopeOf(effective));
    receipt.put("by", by);
    LOG.info(
        "审批面收到人的答复 id={} decision={} scope={}", id, receipt.get("decision"), receipt.get("scope"));
    send(exchange, 200, receipt);
  }

  /** 决议作用域口径（与编排器事件面同口径：拒没有作用域，记 {@code none}）。 */
  private static String scopeOf(ApprovalDecision decision) {
    return switch (decision) {
      case APPROVE_ONCE -> "once";
      case APPROVE_SESSION -> "session";
      case DENY -> "none";
    };
  }

  /**
   * 解析并校验请求体；非法时已发 400 并返回 {@code null}。
   *
   * <p>校验口径：body 必须是 JSON 对象；{@code decision} 必须是 {@code approve}/{@code deny}；{@code scope} 只允许出现在
   * {@code approve} 上且为 {@code once}/{@code session}（缺省 {@code once}——最小授权，不是"不给 scope 就按会话批"）；
   * {@code deny} 带 {@code scope} 是非法组合（它没有作用域可给）。{@code by} 可选、必须是文本（只作审计注记）。
   */
  private Body readBody(HttpExchange exchange) throws IOException {
    byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
    if (raw.length > MAX_BODY_BYTES) {
      send(exchange, 400, Map.of("error", "请求体过大（上限 " + MAX_BODY_BYTES + " 字节）"));
      return null;
    }
    JsonNode node;
    try {
      node = raw.length == 0 ? null : JSON.readTree(raw);
    } catch (JsonProcessingException e) {
      send(exchange, 400, Map.of("error", "请求体不是合法 JSON"));
      return null;
    }
    if (node == null || !node.isObject()) {
      send(exchange, 400, Map.of("error", "请求体必须是 JSON 对象"));
      return null;
    }
    JsonNode decisionNode = node.get("decision");
    if (decisionNode == null || !decisionNode.isTextual()) {
      send(exchange, 400, Map.of("error", "decision 必填且必须是文本（approve|deny）"));
      return null;
    }
    JsonNode scopeNode = node.get("scope");
    String scope = null;
    if (scopeNode != null && !scopeNode.isNull()) {
      if (!scopeNode.isTextual()) {
        send(exchange, 400, Map.of("error", "scope 必须是文本（once|session）"));
        return null;
      }
      scope = scopeNode.asText().strip();
    }
    JsonNode byNode = node.get("by");
    String by = DEFAULT_BY;
    if (byNode != null && !byNode.isNull()) {
      if (!byNode.isTextual()) {
        send(exchange, 400, Map.of("error", "by 必须是文本（它只是审计注记，不参与判定）"));
        return null;
      }
      String rawBy = byNode.asText().strip();
      by = rawBy.isEmpty() ? DEFAULT_BY : rawBy;
    }
    ApprovalDecision decision;
    switch (decisionNode.asText().strip()) {
      case "approve" -> {
        if (scope == null || scope.equals("once")) {
          decision = ApprovalDecision.APPROVE_ONCE;
        } else if (scope.equals("session")) {
          decision = ApprovalDecision.APPROVE_SESSION;
        } else {
          send(exchange, 400, Map.of("error", "scope 只接受 once|session"));
          return null;
        }
      }
      case "deny" -> {
        if (scope != null) {
          // deny 没有作用域可给：带 scope 的 deny 是非法组合（不猜人的意思）
          send(exchange, 400, Map.of("error", "deny 不接受 scope（拒绝没有作用域）"));
          return null;
        }
        decision = ApprovalDecision.DENY;
      }
      default -> {
        send(exchange, 400, Map.of("error", "decision 只接受 approve|deny"));
        return null;
      }
    }
    return new Body(decision, by);
  }

  /** 端点方法门禁：非声明方法 405（带 Allow 头），响应体缺省（同既有网关惯例）。 */
  private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
    if (method.equals(exchange.getRequestMethod())) {
      return true;
    }
    exchange.getResponseHeaders().set("Allow", method);
    exchange.sendResponseHeaders(405, -1);
    exchange.close();
    return false;
  }

  private static void send(HttpExchange exchange, int code, Object body) throws IOException {
    byte[] bytes;
    try {
      bytes = JSON.writeValueAsBytes(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("审批 HTTP 面响应序列化失败", e);
    }
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(code, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** 实际绑定端口（恒 loopback；{@code 0} = 自动分配场景下由装配层取此值并 LOG.info）。 */
  public int boundPort() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    int port = boundPort();
    server.stop(0);
    executor.shutdown();
    LOG.info("审批 HTTP 面已停止（端口 {}）", port);
  }

  /** 已校验的请求体（决议 + 审计注记）。 */
  private record Body(ApprovalDecision decision, String by) {}
}
