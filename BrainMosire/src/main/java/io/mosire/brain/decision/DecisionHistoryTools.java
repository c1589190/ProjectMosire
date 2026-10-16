package io.mosire.brain.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.decision.DecisionEventTypes;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.subagent.AgentContextReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>查自己的决策历史</b>（{@code decision_history} 工具；设计 §5 裁定 5 的底线「下一轮要看结果直接调工具看」+ 裁定 3 的归属口径）。
 *
 * <p><b>它读什么</b>：<b>提出者自己的 {@code events.db}</b>——不新建持久表、不读父进程的表、不问内存态登记表。数据是 {@code
 * decision.package.*} 那五类事件（由装配层的事件镜像按前缀白名单落库，见 {@code App#mirrorInfrastructureEvent}）：
 * 包里每条工具的调用明细本来就在事件里（{@code toolNames}/{@code digests}/{@code outcomes}），读侧只做投影。
 *
 * <p><b>可见面（本类的安全边界）</b>：只列 {@code proposerKey} 等于<b>本调用者实例身份</b>的包（{@link ToolContext#identity()}
 * 的 {@code instanceId}，与管线建待裁决队列时用的那个键<b>同源</b>）。
 * 别人提的包、以及没有归属键的旧事件，一律<b>不出现</b>——"查别人的"与"查不到"返回同一个空结果，<b>没有存在性 oracle</b> （与本仓 {@code
 * ResourceDeniedException} 那条口径一致：不可达的名字与不存在的名字长得一样）。
 *
 * <p><b>出参</b>：包 id / 状态 / 逐条 {@code {index, tool, outcome, reason}} / 裁决者 / 时间 / 轨迹（逐级预裁定的"谁改了谁"）。
 * <b>不含参数明文</b>：事件面本来就只有摘要，本类一行明文都不读（与事件面口径一致）。
 *
 * <p><b>去向下标的四种取值</b>（读侧词汇，与 {@link io.mosire.agentlib.decision.CommitReceipt.ItemOutcome} 的关系写在
 * {@link #itemRows} 里）：{@code COMMITTED} / {@code NOT_COMMITTED} / {@code REJECTED} 来自回执或包层口径，
 * {@code PENDING}（还没裁）与 {@code NOT_SETTLED}（裁决已作出但没看到提交回执）是读侧对"包还没走完"的两种如实标注。
 *
 * <p><b>只读</b>：库经 {@link SqliteEventStore#openReadOnly(Path)} 打开——不建表、不迁移、不写。库不存在 ⇒ {@link
 * ToolResult} 错误（{@code HISTORY_UNAVAILABLE}）：读不到与读到空是两件事。
 *
 * <p><b>★ 本单的缺口（如实记）</b>：子 Agent 跨进程那条路没接（它的包由父进程结算），而这条工具读的是"本进程自己的库"—— 所以在子体面（父侧 MCP
 * 桥）上它<b>外发被关</b>（{@code noExport}）：不是"子体查不到自己"，而是"子体那条路还没有自己的库可查"。 缺口登记在 P2b 报告里，不假装它已经work。
 */
public final class DecisionHistoryTools {

  private static final Logger LOG = LoggerFactory.getLogger(DecisionHistoryTools.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 工具名（模型面唯一入口）。 */
  public static final String NAME = "decision_history";

  /** 自身事件库路径的注入键（复用 {@code read_agent_context} 的那一条：同一个库、同一份装配来源）。 */
  public static final String CONFIG_SELF_EVENTS_DB = AgentContextReader.CONFIG_SELF_EVENTS_DB;

  /** 缺省列多少个包。 */
  public static final int DEFAULT_LIMIT = 5;

  /** 单次最多列多少个包（防一次拉爆上下文）。 */
  public static final int MAX_LIMIT = 50;

  /** 每类事件各取多少条做并集（包数 × 每包 1~2 条，取大一点不会漏掉同包的早期事件）。 */
  private static final int PER_TYPE_SCAN = 200;

  /** 读侧去向：还没裁（包还在等人）。 */
  static final String OUTCOME_PENDING = "PENDING";

  /** 读侧去向：裁决已作出、但事件里看不到提交回执（未落定）。 */
  static final String OUTCOME_NOT_SETTLED = "NOT_SETTLED";

  private DecisionHistoryTools() {
    throw new AssertionError("No instances");
  }

  /**
   * 工具（宿主注册进 registry）：入参 {@code {packageId?: string, limit?: int}}；无参 = 列最近若干条自己的包。
   *
   * <p>三要素：{@link AccessToken#DEFAULT} 级、非敏感非破坏、{@code noExport = true}——"本进程自己的历史"不外发给别的 Agent
   * （子体那条路还没有自己的库，见类注释的缺口）。
   */
  public static AgentTool tool() {
    return new AgentTool() {
      @Override
      public String name() {
        return NAME;
      }

      @Override
      public String description() {
        return "查自己的决策历史（决策包）：列最近若干个包的逐条去向（COMMITTED/NOT_COMMITTED/REJECTED/PENDING/NOT_SETTLED）、"
            + "可空理由、裁决者与逐级预裁定轨迹。只能查自己提的包；不含参数明文。无参=最近若干条，给 packageId=看某一个包。";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of(
            "type",
            "object",
            "properties",
            Map.of(
                "packageId",
                Map.of("type", "string", "description", "只看这个包（省略 = 列最近若干条）"),
                "limit",
                Map.of(
                    "type",
                    "integer",
                    "description",
                    "最多列几个包（缺省 " + DEFAULT_LIMIT + "，上限 " + MAX_LIMIT + "）")));
      }

      @Override
      public ToolSpec spec() {
        return ToolSpec.level(AccessToken.DEFAULT, false, false, true);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        try {
          if (context.identity().isUnknown()) {
            // 身份没穿透 = 答不出"你是谁" ⇒ 不猜、不读（fail-closed；可见面靠这个键，键都没有就无从过滤）
            return ToolResult.error(
                "NO_IDENTITY", "调用者身份未穿透（instanceId=unknown）：查不了「自己的」历史，不做无归属的读取");
          }
          Path dbPath = requiredPath(context.config(), CONFIG_SELF_EVENTS_DB);
          if (!Files.isRegularFile(dbPath)) {
            throw new IllegalStateException("事件库不存在（该 Agent 可能尚未落任何事件，或其数据目录已被清理）: " + dbPath);
          }
          Map<String, Object> body =
              read(
                  dbPath,
                  context.identity().instanceId(),
                  strArg(context, "packageId"),
                  intArg(context, "limit", DEFAULT_LIMIT));
          return ToolResult.ok(JSON.writeValueAsString(body));
        } catch (IllegalArgumentException bad) {
          return ToolResult.error("INVALID_ARGUMENTS", bad.getMessage());
        } catch (IllegalStateException | UncheckedIOException | JsonProcessingException failure) {
          LOG.warn("decision_history 读取失败", failure);
          return ToolResult.error("HISTORY_UNAVAILABLE", String.valueOf(failure.getMessage()));
        }
      }
    };
  }

  /**
   * 读并投影（纯函数，供工具与测试直接调用）。
   *
   * @param dbPath 事件库路径（<b>只读</b>打开）
   * @param selfId 本调用者的实例 id（= 包内 {@code proposerKey}；不匹配的包一律不出现）
   * @param packageId 只看这个包（{@code null}/空白 = 列最近若干条）
   * @param limit 最多列几个包（夹到 {@code [1, MAX_LIMIT]}；给了 packageId 时无意义）
   * @return 结构化返回体（可直接 JSON 序列化）；查不到（不存在 / 不是自己的）⇒ 空列表（无存在性 oracle）
   * @throws IllegalStateException 库打不开
   */
  static Map<String, Object> read(Path dbPath, String selfId, String packageId, int limit) {
    Objects.requireNonNull(dbPath, "dbPath");
    Objects.requireNonNull(selfId, "selfId");
    int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
    List<Event> events = new ArrayList<>();
    try (SqliteEventStore store = SqliteEventStore.openReadOnly(dbPath)) {
      // 每类各取"按 correlationId 分组的最新一条"，再在内存里按包并起来：
      // 一个包一个 correlationId（桥的事件 correlationId = 包 id），所以这就是"每个包每类一条"
      for (String type : DECISION_EVENT_TYPES) {
        events.addAll(store.queryLatestByCorrelation(type, PER_TYPE_SCAN, 0));
      }
    }
    Map<String, List<Event>> byPackage = new LinkedHashMap<>();
    for (Event event : events) {
      String id = event.correlationId();
      if (id == null || id.isBlank()) {
        continue;
      }
      byPackage.computeIfAbsent(id, key -> new ArrayList<>()).add(event);
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    List<String> packageIds = new ArrayList<>(byPackage.keySet());
    // 最近的包在前：按包内最新事件的 seq 倒序（seq 由存储单调分配，是唯一可靠的时序）
    packageIds.sort(Comparator.comparingLong((String id) -> maxSeq(byPackage.get(id))).reversed());
    for (String id : packageIds) {
      boolean single = packageId != null && !packageId.isBlank();
      if (single && !packageId.equals(id)) {
        // 点了名的包：只找它（别人的 / 不存在的 ⇒ 一行都不产出，与"查不到"逐字同形）
        continue;
      }
      Map<String, Object> row = rowOf(id, byPackage.get(id), selfId).orElse(null);
      if (row == null) {
        // 不是自己的包 / payload 认不出归属：不出现（"查别人的"与"查不到"返回同一个结果）
        continue;
      }
      rows.add(row);
      if (single || rows.size() >= capped) {
        break;
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("self", selfId);
    body.put("packages", rows);
    body.put("count", rows.size());
    return body;
  }

  /** 决策包的五类事件（顺序即状态推进顺序；与 {@link DecisionEventTypes} 一一对应，不另立一份词表）。 */
  private static final List<String> DECISION_EVENT_TYPES =
      List.of(
          DecisionEventTypes.PROPOSED,
          DecisionEventTypes.DECIDED,
          DecisionEventTypes.COMMITTED,
          DecisionEventTypes.CONFLICTED,
          DecisionEventTypes.EXPIRED);

  /**
   * 一个包 → 一行（不匹配 {@code selfId} / payload 读不出归属 ⇒ 空）。
   *
   * <p>状态取自事件（<b>不</b>猜）：{@code expired} &gt; {@code conflicted} &gt; {@code committed} &gt;
   * （{@code decided} 且一条都没批 ⇒ {@code REJECTED}；有批 ⇒ {@code DECIDED} 等提交）&gt; {@code proposed}。
   */
  private static Optional<Map<String, Object>> rowOf(
      String packageId, List<Event> events, String selfId) {
    Map<String, Event> latest = new LinkedHashMap<>();
    for (Event event : events) {
      latest.put(event.type(), event);
    }
    Event proposed = latest.get(DecisionEventTypes.PROPOSED);
    Event first = proposed == null ? newest(events) : proposed;
    JsonNode base = first == null ? null : parse(first.payload());
    if (base == null) {
      return Optional.empty();
    }
    if (!selfId.equals(text(base, "proposerKey"))) {
      // 归属不符（含"旧事件没有这个键"）：不出现。宁可查不到，也不猜归属（裁定 3）
      return Optional.empty();
    }
    Event committed = latest.get(DecisionEventTypes.COMMITTED);
    Event conflicted = latest.get(DecisionEventTypes.CONFLICTED);
    Event expired = latest.get(DecisionEventTypes.EXPIRED);
    Event decided = latest.get(DecisionEventTypes.DECIDED);
    JsonNode trail = null;
    if (decided != null) {
      JsonNode decidedPayload = parse(decided.payload());
      trail = decidedPayload == null ? null : decidedPayload.get("trail");
    }
    int itemCount = intOf(base, "itemCount");
    int approvedCount = intOf(base, "approvedCount");

    String state;
    if (expired != null) {
      state = "EXPIRED";
    } else if (conflicted != null) {
      state = "CONFLICTED";
    } else if (committed != null) {
      state = "COMMITTED";
    } else if (decided != null) {
      state = approvedCount == 0 ? "REJECTED" : "DECIDED";
    } else {
      state = "PROPOSED";
    }

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("packageId", packageId);
    row.put("state", state);
    row.put("itemCount", itemCount);
    row.put("approvedCount", approvedCount);
    row.put("rejectedCount", intOf(base, "rejectedCount"));
    row.put("toolNames", strings(base.get("toolNames")));
    row.put("finalizedBy", trail == null ? null : text(trail, "finalizedBy"));
    row.put("escalatedToHuman", trail == null ? List.of() : ints(trail.get("escalatedToHuman")));
    row.put("trail", trailRow(trail));
    Event last = newest(events);
    row.put("ts", last == null ? "" : last.ts().toString());
    row.put("items", itemRows(base, committed != null ? committed : conflicted, expired, decided));
    return Optional.of(row);
  }

  /** 轨迹行（逐级"谁批/驳了哪些条、可空理由"+ 上报集合 + 终局）；载荷里没有 {@code trail} ⇒ null。 */
  private static Map<String, Object> trailRow(JsonNode trail) {
    if (trail == null || !trail.isObject()) {
      return null;
    }
    List<Map<String, Object>> levels = new ArrayList<>();
    JsonNode levelNodes = trail.get("levels");
    if (levelNodes != null && levelNodes.isArray()) {
      for (JsonNode level : levelNodes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("decider", text(level, "decider"));
        row.put("mode", text(level, "mode"));
        row.put("approved", ints(level.get("approved")));
        row.put("rejected", ints(level.get("rejected")));
        row.put("reason", nullableText(level, "reason"));
        levels.add(row);
      }
    }
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("levels", levels);
    row.put("escalatedToHuman", ints(trail.get("escalatedToHuman")));
    row.put("finalApproved", ints(trail.get("finalApproved")));
    row.put("finalizedBy", nullableText(trail, "finalizedBy"));
    return row;
  }

  /**
   * 逐条去向 + 可空理由（读侧投影）。
   *
   * <p>优先级：<b>有提交回执</b>（{@code committed}/{@code conflicted} 事件里的 {@code outcomes}）⇒ 用它（提交口填的理由，
   * 未说明时 {@code reason = null}）；<b>只有做废</b> ⇒ 整包 {@code NOT_COMMITTED} + 作废原因；<b>只有裁决</b> ⇒ 被驳的
   * {@code REJECTED}（理由用包层那条口径 {@link DecisionPackages#REJECTED_ITEM_REASON}）、被批的 {@code
   * NOT_SETTLED}（裁决已作出、没看到回执）；<b>只有提议</b> ⇒ {@code PENDING}。
   *
   * <p>条目身份（index/toolName）来自承载事件的 {@code indexes}/{@code toolNames}（顺序 = 包内顺序）；提交回执那一档用 {@code
   * outcomes} 自带的 index/toolName。
   */
  private static List<Map<String, Object>> itemRows(
      JsonNode base, Event receiptEvent, Event expired, Event decided) {
    if (receiptEvent != null) {
      JsonNode payload = parse(receiptEvent.payload());
      JsonNode outcomes = payload == null ? null : payload.get("outcomes");
      if (outcomes != null && outcomes.isArray()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode row : outcomes) {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("index", intOf(row, "index"));
          item.put("tool", text(row, "toolName"));
          item.put("outcome", text(row, "outcome"));
          item.put("reason", nullableText(row, "reason"));
          rows.add(item);
        }
        return rows;
      }
    }
    List<Integer> indexes = ints(base.get("indexes"));
    List<String> tools = strings(base.get("toolNames"));
    Set<Integer> approved = new LinkedHashSet<>();
    if (decided != null) {
      JsonNode payload = parse(decided.payload());
      JsonNode trail = payload == null ? null : payload.get("trail");
      approved.addAll(ints(trail == null ? null : trail.get("finalApproved")));
    }
    String expiredReason = null;
    if (expired != null) {
      JsonNode payload = parse(expired.payload());
      expiredReason = payload == null ? null : nullableText(payload, "reason");
    }
    List<Map<String, Object>> rows = new ArrayList<>(indexes.size());
    for (int i = 0; i < indexes.size(); i++) {
      int index = indexes.get(i);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("index", index);
      item.put("tool", i < tools.size() ? tools.get(i) : "");
      if (expired != null) {
        item.put("outcome", "NOT_COMMITTED");
        item.put("reason", "决策包作废（零落盘）：" + (expiredReason == null ? "未说明原因" : expiredReason));
      } else if (decided != null && !approved.contains(index)) {
        item.put("outcome", "REJECTED");
        item.put("reason", DecisionPackages.REJECTED_ITEM_REASON);
      } else if (decided != null) {
        item.put("outcome", OUTCOME_NOT_SETTLED);
        item.put("reason", "已裁决批准，但事件里看不到提交回执（未落定）");
      } else {
        item.put("outcome", OUTCOME_PENDING);
        item.put("reason", null);
      }
      rows.add(item);
    }
    return rows;
  }

  /** 包内最新一条事件（决定这一行的 {@code ts}）。 */
  private static Event newest(List<Event> events) {
    Event newest = null;
    for (Event event : events) {
      if (newest == null || event.seq() > newest.seq()) {
        newest = event;
      }
    }
    return newest;
  }

  private static long maxSeq(List<Event> events) {
    long max = 0L;
    for (Event event : events) {
      max = Math.max(max, event.seq());
    }
    return max;
  }

  private static JsonNode parse(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      return JSON.readTree(payload);
    } catch (IOException notJson) {
      // 事件载荷坏了：这一行认不出归属/内容 ⇒ 当作"读不到"（不猜、不抛——一条坏事件不该让整次查询失败）
      LOG.warn("决策包事件载荷不是合法 JSON（该包按读不到处理）", notJson);
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? "" : value.asText("");
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static int intOf(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || !value.isNumber() ? 0 : value.asInt();
  }

  private static List<String> strings(JsonNode node) {
    List<String> out = new ArrayList<>();
    if (node != null && node.isArray()) {
      for (JsonNode item : node) {
        out.add(item.asText(""));
      }
    }
    return List.copyOf(out);
  }

  private static List<Integer> ints(JsonNode node) {
    List<Integer> out = new ArrayList<>();
    if (node != null && node.isArray()) {
      for (JsonNode item : node) {
        if (item.isNumber()) {
          out.add(item.asInt());
        }
      }
    }
    return List.copyOf(out);
  }

  private static Path requiredPath(Map<String, Object> config, String key) {
    Object value = config.get(key);
    if (value == null || String.valueOf(value).isBlank()) {
      throw new IllegalStateException("工具配置缺失 " + key + "（装配层未注入，不做路径猜测）");
    }
    return Path.of(String.valueOf(value));
  }

  private static String strArg(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof String text) {
      return text.isBlank() ? null : text.strip();
    }
    return String.valueOf(value);
  }

  private static int intArg(ToolContext context, String name, int fallback) {
    Object value = context.arguments().get(name);
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value).strip());
    } catch (NumberFormatException bad) {
      throw new IllegalArgumentException(name + " 必须是整数，收到: " + value);
    }
  }
}
