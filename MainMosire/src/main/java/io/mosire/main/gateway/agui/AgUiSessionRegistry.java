package io.mosire.main.gateway.agui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.EventStore;
import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.StopReason;
import io.mosire.brain.runtime.TurnResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AG-UI 会话注册表：POST /sessions → 建会话 + 调度主 Agent 一回合（一次运行）。
 *
 * <p><b>运行桥（R8 镜像的合成点）</b>：Brain 没有"回合结束"事件（FINISHED 无事件、其余停止原因只有 decision）—— 会话的终态由运行桥从 {@link
 * TurnResult}/{@link StopReason} 合成：FINISHED → RUN_FINISHED（result= finalText），否则 →
 * RUN_ERROR（code=StopReason 名；message 尽量带 decision.detail 的 reason）。
 *
 * <p><b>会话隔离</b>：主 Agent 事件在 EventStore 里没有 correlationId，因此按"agent=main + seq 区段 [floor,
 * ceiling)"过滤：floor 在运行入场时捕获、ceiling 在退场时捕获；主 Agent 运行经<b>共享单线程 chat 执行器</b>（与 A2A 同一实例——{@code
 * AgentPipeline.history} 实例级共享且非线程安全，串行化即不变量） 串行化，其它运行的 seq 必然落在区间之外。子 Agent 事件族（agent=子 id）与主
 * Agent 上的 spawn 拒绝形态 （agent=main、correlationId=子 id）由翻译层显式过滤（{@link AgUiEventTranslator#translate}
 * 空表）。
 *
 * <p><b>事件窗与移交</b>：POST 即调度、GET 即订阅；终态后未订阅的会话仍可经 EventStore 兜底重放 （{@code floor/ceiling}
 * 同存于会话记录），因此断连/关停后客户端可重连收齐（与 R8 的"已终态可查询"一致）。
 *
 * <p><b>容量</b>：会话上限 {@value #MAX_SESSIONS}——超出时逐出最旧的已终态且无订阅者会话（仅驱逐已完成记录， 不驱逐在途——避免泄漏在途运行）。
 */
public final class AgUiSessionRegistry implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(AgUiSessionRegistry.class);

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 会话上限（防遗忘的 API 冲垮内存；验证子集里 64 足够）。 */
  static final int MAX_SESSIONS = 64;

  /** 事件回读的 decision 详情深度（决策事件最新 32 条足够——回合有限）。 */
  private static final int REASON_LOOKBACK = 32;

  private final AgentRuntime runtime;
  private final EventStore events;
  private final EventBus bus;
  private final ExecutorService chatExecutor;
  private final Map<String, AgUiSession> sessions = new ConcurrentHashMap<>();
  private volatile boolean closed;

  public AgUiSessionRegistry(
      AgentRuntime runtime, EventStore events, EventBus bus, ExecutorService chatExecutor) {
    this.runtime = runtime;
    this.events = events;
    this.bus = bus;
    this.chatExecutor = chatExecutor;
  }

  /**
   * 建会话并调度运行：{@code threadId}（{@code sessionId} 为别名）缺失时自动分配 "s-&lt;uuid&gt;"；messages 须含至少一条带文本的
   * user 消息（text = 运行输入）。
   *
   * @throws DuplicateSessionException id 已存在（409）
   * @throws IllegalArgumentException 输入不合法（400）
   * @throws IllegalStateException 注册表已关停（503）
   */
  public AgUiSession createSession(Map<String, Object> request) {
    requireOpen();
    String id = firstNonBlank(text(request.get("threadId")), text(request.get("sessionId")));
    if (id == null) {
      id = "s-" + UUID.randomUUID();
    }
    String userText = extractUserText(request.get("messages"));
    if (userText.isEmpty()) {
      throw new IllegalArgumentException("messages 中缺少带文本的 user 消息");
    }
    if (sessions.containsKey(id)) {
      throw new DuplicateSessionException(id);
    }
    List<Object> rawMessages = rawMessages(request.get("messages"));
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("threadId", id);
    input.put("messages", rawMessages);
    AgUiSession session = new AgUiSession(id, userText, input);
    AgUiSession raced = sessions.putIfAbsent(id, session);
    if (raced != null) {
      throw new DuplicateSessionException(id);
    }
    evictCompleted();
    try {
      chatExecutor.execute(() -> runSession(session));
    } catch (RejectedExecutionException e) {
      // 关停窗：执行器已拒绝（A2A 任务服务先关停共享执行器——关停语义见 App.close 说明）
      session.finish(AgUiSession.Status.ERROR, null, "NOT_SCHEDULED", "会话未调度：chat 执行器已关停", "");
      LOG.warn("AG-UI 会话未调度（执行器已关停）: {}", id);
    }
    return session;
  }

  /** 运行桥：floor/ceiling 捕获 → 一回合 → 终态合成（含 TERMINAL 信号；无订阅者时仅落记录，供重放）。 */
  private void runSession(AgUiSession session) {
    long floor = events.count();
    session.markFloor(floor);
    TurnResult result;
    String exceptionCode = null;
    String exceptionMessage = null;
    try {
      result = runtime.chat(session.userText());
    } catch (RuntimeException e) {
      result = null;
      exceptionCode = "RUNTIME_EXCEPTION";
      exceptionMessage = "回合异常: " + e.getMessage();
      LOG.warn("AG-UI 会话 {} 回合异常: {}", session.id(), e.getMessage(), e);
    }
    session.markCeiling(events.count());
    if (result != null && result.stopReason() == StopReason.FINISHED) {
      session.finish(
          AgUiSession.Status.COMPLETED,
          StopReason.FINISHED,
          null,
          null,
          result.finalText().orElse(""));
    } else if (result != null) {
      String reasonDetail = reasonDetail(session, result.stopReason());
      session.finish(
          AgUiSession.Status.ERROR,
          result.stopReason(),
          result.stopReason().name(),
          unreasonMessage(result.stopReason(), reasonDetail),
          "");
    } else {
      session.finish(AgUiSession.Status.ERROR, null, exceptionCode, exceptionMessage, "");
    }
    session.signal(AgUiSession.TERMINAL);
  }

  /** 从 [floor, ceiling) 的 decision 事件里捞停止原因的 detail（尽力而为——没有也不阻塞 RUN_ERROR）。 */
  private String reasonDetail(AgUiSession session, StopReason stopReason) {
    if (stopReason == null) {
      return null;
    }
    for (Event event :
        events.query(new EventQuery("main", "decision", "", session.ceiling(), REASON_LOOKBACK))) {
      if (event.seq() < session.floor()) {
        break;
      }
      try {
        JsonNode parsed = JSON.readTree(event.payload());
        if (!(parsed instanceof ObjectNode node)) {
          continue;
        }
        JsonNode decision = node.get("decision");
        if (decision != null && stopReason.name().equals(decision.asText())) {
          JsonNode detail = node.get("detail");
          if (detail != null && detail.isObject()) {
            JsonNode reason = detail.get("reason");
            return reason == null || !reason.isTextual() ? null : reason.asText();
          }
          return null;
        }
      } catch (Exception e) {
        // 坏事件跳过——reasonDetail 是尽力而为
      }
    }
    return null;
  }

  private static String unreasonMessage(StopReason stopReason, String reasonDetail) {
    String message = "Agent 回合未完成: " + stopReason.name();
    return reasonDetail == null || reasonDetail.isEmpty()
        ? message
        : message + " (" + reasonDetail + ")";
  }

  public Optional<AgUiSession.Status> statusOf(String id) {
    return session(id).map(AgUiSession::status);
  }

  public Optional<AgUiSession> session(String id) {
    return Optional.ofNullable(sessions.get(id));
  }

  /** 会话数（AdminREST 后续 /api/sessions 的数据源；测试用）。 */
  public int size() {
    return sessions.size();
  }

  @Override
  public void close() {
    closed = true;
    // 注：共享执行器归 A2aTaskService/App 持有（串行化不变量），这里只封创建口
  }

  /** 驱逐最旧的已终态会话（无订阅者；在途会话永不驱逐）。 */
  private void evictCompleted() {
    if (sessions.size() <= MAX_SESSIONS) {
      return;
    }
    sessions.values().stream()
        .filter(s -> s.status().isTerminal() && s.streamOrNull() == null)
        .sorted((a, b) -> Long.compare(b.ceiling(), a.ceiling()))
        .limit(sessions.size() - MAX_SESSIONS)
        .forEach(
            s -> {
              sessions.remove(s.id(), s);
              LOG.info("驱逐已完成 AG-UI 会话 {}", s.id());
            });
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("AG-UI 网关已关停");
    }
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static String text(Object value) {
    return value instanceof String s ? s : null;
  }

  /** 取最后一条带文本的 user 消息（RunAgentInput 模型："history" 由 Brain 经 Pipeline 管理——会话只见最终输入）。 */
  @SuppressWarnings("unchecked")
  private static String extractUserText(Object messagesRaw) {
    if (!(messagesRaw instanceof List<?>)) {
      return "";
    }
    String userText = "";
    for (Object messageRaw : (List<Object>) messagesRaw) {
      if (!(messageRaw instanceof Map<?, ?> message)) {
        continue;
      }
      Object roleValue = message.get("role");
      if (!(roleValue instanceof String role) || !"user".equalsIgnoreCase(role)) {
        continue;
      }
      String contentText = messageText(message.get("content"));
      if (!contentText.isEmpty()) {
        userText = contentText;
      }
    }
    return userText;
  }

  /** 消息文本提取：String 内容直接用；多段内容取第一个 text 段（AG-UI 消息模型的最小子集）。 */
  @SuppressWarnings("unchecked")
  private static String messageText(Object content) {
    if (content instanceof String text) {
      return text;
    }
    if (!(content instanceof List<?> parts)) {
      return "";
    }
    for (Object partRaw : (List<Object>) parts) {
      if (partRaw instanceof Map<?, ?> part) {
        if ("text".equals(part.get("type"))) {
          Object text = part.get("text");
          if (text instanceof String s) {
            return s;
          }
        }
      }
    }
    return "";
  }

  @SuppressWarnings("unchecked")
  private static List<Object> rawMessages(Object messagesRaw) {
    if (messagesRaw instanceof List<?> list
        && list.stream().allMatch(m -> m instanceof Map<?, ?>)) {
      return (List<Object>) list;
    }
    return List.of();
  }

  /** sessionId 重复（服务器映射 409）。 */
  public static final class DuplicateSessionException extends IllegalArgumentException {

    private final String sessionId;

    DuplicateSessionException(String sessionId) {
      super("会话已存在: " + sessionId);
      this.sessionId = sessionId;
    }

    public String sessionId() {
      return sessionId;
    }
  }
}
