package io.mosire.main.gateway.agui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.EventStore;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AG-UI HTTP 入口（W4 最小子集，D6 自写绑定：jdk.httpserver + 虚拟线程，与 A2aHttpServer 同构）。
 *
 * <ul>
 *   <li>{@code POST /sessions}：接收 RunAgentInput 最小字段（{@code threadId}/{@code sessionId} + {@code
 *       messages}）→ 建会话（sessionId=threadId=runId 的一回合模型）并调度主 Agent 回合；响应 {@code
 *       {"id","threadId","runId","status"}}；错误面：400（坏 JSON/缺 user 文本）、409（id 重复）、503（已关停）；
 *   <li>{@code GET /sessions/{id}/events}：{@code text/event-stream}——RUN_STARTED →（翻译层事件序列）→
 *       RUN_FINISHED | RUN_ERROR 后结束流；同一时刻仅一个订阅者（并发订阅回 409）；未订阅的终态会话仍可从 EventStore 重放
 *       （断连/关停后的重连兜底——参见 AgUiHttpServer.streamLoop 的 catch-up 结构）；
 *   <li>事件顺序契约（计划 §5.2.2）：连续序 = RUN_STARTED → TEXT_MESSAGE_* → TOOL_CALL_* →
 *       RUN_FINISHED|RUN_ERROR。 每帧 {@code data: <json>}（{"type": ...} 判别键），帧与帧直接换行分隔。
 * </ul>
 *
 * <p>网络面默认 127.0.0.1（红线：对外打开必须显式配置 {@code --agui-address}）。
 */
public final class AgUiHttpServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(AgUiHttpServer.class);

  private static final ObjectMapper JSON = new ObjectMapper();

  public static final String SESSIONS_PATH = "/sessions";
  private static final String EVENTS_SUFFIX = "/events";

  /** 兜底回读页大小（一次 SEQ 区间翻页；512 条足够一回合的事件体量）。 */
  private static final int CATCHUP_PAGE = 512;

  /** 空闲轮询窗口（消息信号丢失时的保底回读——catch-up 幂等，多读无害）。 */
  private static final long POLL_TIMEOUT_MS = 300L;

  private final HttpServer server;
  private final AgUiSessionRegistry registry;
  private final EventStore events;
  private final EventBus bus;
  private final AgUiEventTranslator translator = new AgUiEventTranslator();

  private AgUiHttpServer(
      HttpServer server, AgUiSessionRegistry registry, EventStore events, EventBus bus) {
    this.server = server;
    this.registry = registry;
    this.events = events;
    this.bus = bus;
  }

  public static AgUiHttpServer start(
      InetSocketAddress address, AgUiSessionRegistry registry, EventStore events, EventBus bus) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(events, "events");
    Objects.requireNonNull(bus, "bus");
    try {
      HttpServer server = HttpServer.create(address, 0);
      AgUiHttpServer self = new AgUiHttpServer(server, registry, events, bus);
      server.createContext(SESSIONS_PATH, self::dispatch);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.start();
      LOG.info("AG-UI 网关已启动: {} 端口 {}", address.getHostString(), self.port());
      return self;
    } catch (IOException e) {
      throw new IllegalStateException("启动 AG-UI HTTP server 失败: " + address, e);
    }
  }

  /** 实际监听端口（测试取随机端口用）。 */
  public int port() {
    return server.getAddress().getPort();
  }

  /**
   * 关闭（优雅，同 A2aHttpServer 推论）：{@code HttpServer.stop(1)}——先停接入，再给在途 SSE 写帧循环 1 秒排空。
   *
   * <p>（实际关停次序，与 App.close 一致）共享 chat 执行器先被 A2A 任务服务 {@code shutdownNow}——在途 AG-UI 回合被中断、 运行桥 catch
   * 后立即置 RUN_ERROR 终态并送 TERMINAL 信号；随后本网关 {@code stop(1)} 给在途流排空窗口，窗口内流收终态帧写完。 窗口过后未送达的客户端经重连从
   * EventStore 重放 + 会话记录终态收齐（见 App.close Javadoc）。
   */
  @Override
  public void close() {
    server.stop(1);
  }

  private void dispatch(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    if (SESSIONS_PATH.equals(path)) {
      if ("POST".equals(method)) {
        serveCreate(exchange);
        return;
      }
      exchange.getResponseHeaders().set("Allow", "POST");
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    if (path.startsWith(SESSIONS_PATH + "/") && path.endsWith(EVENTS_SUFFIX)) {
      if ("GET".equals(method)) {
        serveEvents(exchange, sessionIdOf(path));
        return;
      }
      exchange.getResponseHeaders().set("Allow", "GET");
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(404, -1);
    exchange.close();
  }

  /** POST /sessions：解析 RunAgentInput 最小字段 → {@link AgUiSessionRegistry#createSession}。 */
  private void serveCreate(HttpExchange exchange) throws IOException {
    String body;
    try (var in = exchange.getRequestBody()) {
      body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Map<String, Object> request;
    try {
      JsonNode parsed = JSON.readTree(body);
      if (!(parsed instanceof ObjectNode)) {
        throw new IllegalArgumentException("请求体必须是 JSON 对象");
      }
      request = JSON.convertValue(parsed, new TypeReference<Map<String, Object>>() {});
    } catch (IOException | IllegalArgumentException e) {
      // JsonProcessingException（语法坏 JSON）同属 IOException——两者都必须是 400（契约：坏 JSON 见 §4）
      respondError(exchange, 400, "请求体不是合法 JSON 对象");
      return;
    }
    try {
      AgUiSession session = registry.createSession(request);
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      respondJson(
          exchange,
          200,
          Map.of(
              "id", session.id(),
              "threadId", session.id(),
              "runId", session.id(),
              "status", statusName(session.status())));
    } catch (AgUiSessionRegistry.DuplicateSessionException e) {
      respondError(exchange, 409, "会话已存在: " + e.sessionId());
    } catch (IllegalArgumentException e) {
      respondError(exchange, 400, e.getMessage());
    } catch (IllegalStateException e) {
      respondError(exchange, 503, e.getMessage());
    }
  }

  /** GET /sessions/{id}/events：SSE 流（唯一订阅者；有界回读 + 终态收尾）。 */
  private void serveEvents(HttpExchange exchange, String sessionId) throws IOException {
    if (sessionId.isEmpty()) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    AgUiSession session = registry.session(sessionId).orElse(null);
    if (session == null) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    BlockingQueue<Object> streamQueue = session.attachStream();
    if (streamQueue == null) {
      respondError(exchange, 409, "该会话的 /events 已有一个订阅者（同一时刻仅一个）");
      return;
    }
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(200, 0);
    streamLoop(exchange, session, streamQueue);
  }

  /**
   * SSE 写帧循环：EventBus 订阅仅作"唤醒信号"，有序数据从 EventStore 回读（订阅丢失/信号迟到都无害——回读 catch-up
   * 幂等）。终态（RUN_FINISHED/RUN_ERROR）写出后即断流（计划 §5.2.2："终态收尾"；客户端以流结束为 一回合结束，重连从 EventStore
   * 重放）。循环结构（每迭代）：catch-up（floor/ceiling 最近值）→ 终态检查 → 空闲轮询。
   */
  private void streamLoop(
      HttpExchange exchange, AgUiSession session, BlockingQueue<Object> streamQueue) {
    String id = session.id();
    EventBus.Subscription subscription = bus.subscribe(event -> session.signal(AgUiSession.WAKEUP));
    long lastSent = -1L;
    try (OutputStream out = exchange.getResponseBody()) {
      // RUN_STARTED 是会话级合成事件（协议开口帧——与存储事件无关，重连时也先发它）
      writeFrame(out, translator.runStarted(id, id, session.input()));
      while (true) {
        if (session.floor() >= 0) {
          lastSent = writeCatchup(out, session, lastSent);
        }
        if (session.status().isTerminal()) {
          // 终态补一轮 catch-up：桥先置 ceiling 再置终态（runSession），终态判定晚于任何落库——
          // 覆盖"上一轮 catch-up 之后、终态判定之前"的迟到窗口；随后写终态帧并断流
          if (session.ceiling() >= 0) {
            lastSent = writeCatchup(out, session, lastSent);
          }
          writeFrame(out, terminalEvent(session));
          out.flush();
          break;
        }
        // 空轮询只在本订阅者队列上（自始捕获——重连者另建队列，互不干扰；不存在"队列为 null 自旋"）
        try {
          streamQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
        // 超时/唤醒都回到 catch-up（幂等；信号丢失安全）
      }
    } catch (IOException e) {
      // 客户端断连/服务端关停（stop(1) 掐连接）：正常退场路径
      LOG.info("AG-UI SSE 流断开: {} ({})", id, e.getMessage());
    } finally {
      subscription.close();
      // 仅当注册队列仍是自己这支流的队列才清（CAS）——重连竞态：晚到的 detach 不得清掉新订阅者的队列
      session.detachStream(streamQueue);
    }
  }

  /** 回写（floor, ceiling］区间内新事件（DESC 分页回读 → 升序写出；seq 去重由 minSeq 游标保证）。 */
  private long writeCatchup(OutputStream out, AgUiSession session, long lastSent)
      throws IOException {
    for (Event event : readCatchup(session)) {
      if (event.seq() <= lastSent) {
        continue;
      }
      for (AgUiEvent mapped : translator.translate(event)) {
        writeFrame(out, mapped);
      }
      lastSent = event.seq();
    }
    return lastSent;
  }

  /**
   * 按代理序分页回读会话窗口内（agent=main、seq∈(floor, ceiling］）的事件并升序归整。
   *
   * <p>（评审 Minor 9 记录项，不修）每迭代全窗回读 = O(N×window) 的信号爆发路径（N=窗口事件数、window=迭代次数）； W4 一回合窗口极小（≤数十条）且迭代受
   * 300ms 轮询/终态收敛约束，量级无害；留作后续"增量游标 + seq 订阅"优化。
   */
  private List<Event> readCatchup(AgUiSession session) {
    long floor = session.floor();
    long ceiling = session.ceiling();
    long before = ceiling >= 0 ? ceiling + 1 : -1L;
    List<Event> collected = new ArrayList<>();
    while (true) {
      List<Event> page = events.query(new EventQuery("main", "", "", before, CATCHUP_PAGE));
      if (page.isEmpty()) {
        break;
      }
      long minSeq = page.get(page.size() - 1).seq();
      for (Event event : page) {
        // floor 排他（seq == floor 属上一运行的末事件——AUTOINCREMENT 且无删除时 count == 末 seq）
        if (event.seq() > floor) {
          collected.add(event);
        }
      }
      if (minSeq <= floor) {
        break;
      }
      before = minSeq;
    }
    collected.sort(Comparator.comparingLong(Event::seq));
    return collected;
  }

  /**
   * 终态事件（会话记录合成：COMPLETED → RUN_FINISHED；ERROR → RUN_ERROR）。
   *
   * <p>RUN_ERROR 的 code 取用顺序：{@code stopReason} 名 → {@code errorCode}（StopReason 缺失时由运行桥/注册表指定
   * ——如关停窗的 NOT_SCHEDULED，避免被吞成默认 RUNTIME_EXCEPTION）→ 兜底 RUNTIME_EXCEPTION。
   */
  private AgUiEvent terminalEvent(AgUiSession session) {
    String id = session.id();
    if (session.status() == AgUiSession.Status.COMPLETED) {
      return translator.runFinished(id, id, session.finalText());
    }
    String code = session.stopReason() != null ? session.stopReason().name() : session.errorCode();
    return translator.runError(code == null ? "RUNTIME_EXCEPTION" : code, session.errorMessage());
  }

  private static String sessionIdOf(String path) {
    return path.substring(SESSIONS_PATH.length() + 1, path.length() - EVENTS_SUFFIX.length());
  }

  private static String statusName(AgUiSession.Status status) {
    return status.name().toLowerCase(java.util.Locale.ROOT);
  }

  private void writeFrame(OutputStream out, AgUiEvent event) throws IOException {
    // 每帧 data: <json>\n\n（字段 order: type 先）
    out.write(("data: " + translator.toJson(event) + "\n\n").getBytes(StandardCharsets.UTF_8));
  }

  private static void respondJson(HttpExchange exchange, int status, Map<String, Object> body)
      throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void respondError(HttpExchange exchange, int status, String message)
      throws IOException {
    respondJson(exchange, status, Map.of("error", message == null ? "" : message));
  }
}
