package io.mosire.brain.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.brain.runtime.EventTypes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 子 Agent 上下文读取器（D30）：按 instanceId 直读子体的事件库，投影成<b>五个视图</b>——补 U6 抓到的 "子体真干活、父拿不到结果"缺口。
 *
 * <p><b>视图</b>（{@code view} 参数）：
 *
 * <ul>
 *   <li>{@code summary}（缺省）：状态/模板/深度/目标 + 回合数 + token 汇总 + 首末时间 + <b>最后一条 output</b>—— 父的"一眼看结论"；
 *   <li>{@code turns}：逐条 {@code conversation.turn} 的 seq/时间/输入/输出（分条目回看全过程）；
 *   <li>{@code results}：<b>只看结果</b>——逐条 {@code conversation.turn} 的 output 文本；
 *   <li>{@code search}：按 {@code q} 关键词（substring——CJK 不指望分词）命中的条目，可用 {@code type} 限定；
 *   <li>{@code events}：按 {@code type} 取原始事件（排查：工具调用/记账/生命周期）。
 * </ul>
 *
 * <p><b>只读</b>：库经 {@link SqliteEventStore#openReadOnly(Path)} 打开——不建表、不迁移、不写；子体可能正在写同一个
 * 文件（WAL），读侧不该打断它。库不存在 ⇒ {@link IllegalStateException}（响亮），<b>不</b>降级成空结果
 * （"读不到"与"读到空"是两件事）。视图为空则是成功的空列表——不是错误。
 *
 * <p><b>分页口径（翻页不重不漏）</b>：{@code EventQuery} <b>没有正向游标</b>（只有 {@code beforeSeq}，且按 seq
 * 倒序返回），故本类在<b>读侧</b>做：分块倒序读全量 → 反转成正序 → 按视图投影与筛选 → 再按 {@code seq > sinceSeq} 取下一页；返回 {@code
 * nextSinceSeq}（= 本页最后一条的 seq，已到末尾则 null）与 {@code hasMore}。调用方逐页传入 上一页的 {@code nextSinceSeq}
 * 即可拼出全集，不重不漏。
 *
 * <p><b>超长文本</b>：按 UTF-8 字节截断且不切断多字节字符，截断处追加可见标记（{@code …[已截断：完整 N 字节 / 显示前 M
 * 字节]}）——模型看得见"这里少了一截"，不会把截断误当全文。
 *
 * <p><b>口径对齐</b>：{@code summary} 的 token 汇总与二期 {@code ContextReport} <b>同口径</b>——payload 里的 {@code
 * -1} 表示"该档未知"（如无缓存档），<b>不计入求和</b>而是单独计数（{@code unknown*Tokens}），绝不直接求和 （直接加会得出负数）。
 *
 * <p><b>不做的事</b>：不读父进程的配置/密钥（D23/D24）；返回的都是子体已落盘的事件（不含任何"在线请求体"）。
 */
public final class AgentContextReader {

  private static final ObjectMapper JSON = new ObjectMapper();

  public static final String VIEW_SUMMARY = "summary";
  public static final String VIEW_TURNS = "turns";
  public static final String VIEW_RESULTS = "results";
  public static final String VIEW_SEARCH = "search";
  public static final String VIEW_EVENTS = "events";

  /** 全部视图名（schema 的 enum 与参数校验同源）。 */
  public static final List<String> VIEWS =
      List.of(VIEW_SUMMARY, VIEW_TURNS, VIEW_RESULTS, VIEW_SEARCH, VIEW_EVENTS);

  /**
   * 子库根目录的注入键（{@code <dataDir>/subagents}，与 {@code App} 交给子进程的 {@code --data-dir} 同源）。
   *
   * <p>取值经 {@code ToolContext.config} 注入（运行时给工具注入自身配置的既定缝——Brain 不做文件系统假设/不持有全局 路径）。缺键 ⇒
   * 读工具响亮报错，不退化成猜路径。
   */
  public static final String CONFIG_SUBAGENTS_ROOT = "read_agent_context.subagents_root";

  /** 自身事件库路径的注入键（{@code target=self} 用；主 Agent 即 {@code <dataDir>/events.db}）。 */
  public static final String CONFIG_SELF_EVENTS_DB = "read_agent_context.self_events_db";

  /** 自身 Agent id 的注入键（{@code target=self} 时按它过滤事件的 {@code agent} 列）。 */
  public static final String CONFIG_SELF_AGENT_ID = "read_agent_context.self_agent_id";

  /** 分页缺省页大小。 */
  public static final int DEFAULT_LIMIT = 20;

  /** 分页页大小上限（防一次拉爆上下文；超过即夹到上限并在返回体如实回显）。 */
  public static final int MAX_LIMIT = 200;

  /** 单条文本的字节上限（超出即截断 + 标注）。 */
  public static final int MAX_TEXT_BYTES = 4096;

  /** 倒序分块扫描的块大小（与二期 ContextReport 的翻页粒度同量级）。 */
  private static final int SCAN_CHUNK = 500;

  private AgentContextReader() {
    throw new AssertionError("No instances");
  }

  /**
   * 视图请求（缺省值在构造器内归一：view=summary、limit=DEFAULT_LIMIT 且夹到 MAX_LIMIT、sinceSeq<0 视作 0）。
   *
   * @param view 视图名（见 {@link #VIEWS}；null/空白 = {@code summary}）
   * @param q 关键词（substring；仅 {@code search} 视图使用）
   * @param type 事件类型过滤（仅 {@code events}/{@code search} 视图使用）
   * @param limit 页大小
   * @param sinceSeq 游标：只取 seq 严格大于它的条目（0 = 从头开始）
   */
  public record Request(String view, String q, String type, int limit, long sinceSeq) {

    public Request {
      view = view == null || view.isBlank() ? VIEW_SUMMARY : view;
      if (!VIEWS.contains(view)) {
        throw new IllegalArgumentException("未知视图: " + view + "（可选 " + VIEWS + "）");
      }
      q = q == null ? "" : q;
      type = type == null ? "" : type;
      limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
      sinceSeq = Math.max(sinceSeq, 0L);
    }
  }

  /**
   * 读一个 Agent 的上下文并投影成视图。
   *
   * @param dbPath 事件库路径（<b>只读</b>打开）
   * @param agentId 事件 {@code agent} 列的过滤值（子体 = 其实例 id；自身 = 主 Agent id）——只读"这个 Agent 自己"
   *     的事件，不含它派生出来的下级
   * @param request 视图 + 分页 + 筛选
   * @param instance 子体实例快照（{@code summary} 的状态/模板/深度/目标来源；{@code target=self} 时可为 null——此时 summary
   *     只报可从事件推出的事实，不编造状态）
   * @return 结构化返回体（可直接 JSON 序列化）
   * @throws IllegalStateException 库不存在或打不开（响亮；调用方应转成显式错误而不是空结果）
   */
  public static Map<String, Object> read(
      Path dbPath, String agentId, Request request, SubagentInstance instance) {
    Objects.requireNonNull(dbPath, "dbPath");
    Objects.requireNonNull(agentId, "agentId");
    Objects.requireNonNull(request, "request");

    // 全程只读：openReadOnly 不建表/不迁移；try-with-resources 的 close 也不会做 WAL checkpoint（见 SqliteEventStore）
    List<Event> ascending;
    try (SqliteEventStore store = SqliteEventStore.openReadOnly(dbPath)) {
      ascending = readAllAscending(store, agentId);
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("view", request.view());
    body.put("agent", agentId);
    body.put("db", dbPath.toString());
    if (VIEW_SUMMARY.equals(request.view())) {
      body.put("summary", summary(ascending, agentId, instance));
      body.put("itemCount", 0);
      return body;
    }

    List<Map<String, Object>> rows =
        switch (request.view()) {
          case VIEW_TURNS -> turns(ascending);
          case VIEW_RESULTS -> results(ascending);
          case VIEW_SEARCH -> search(ascending, request.type(), request.q());
          case VIEW_EVENTS -> events(ascending, request.type());
          default -> List.of(); // 不可达：Request 已校验 view ∈ VIEWS，且 summary 在上面分支返回
        };

    // 分页（在投影/筛选之后做——这样"逐页拼接 == 过滤后全集"恒成立；seq 正序游标）
    List<Map<String, Object>> remaining =
        rows.stream().filter(row -> seqOf(row) > request.sinceSeq()).toList();
    List<Map<String, Object>> page =
        remaining.size() > request.limit() ? remaining.subList(0, request.limit()) : remaining;
    boolean hasMore = remaining.size() > page.size();
    Long nextSinceSeq = hasMore ? seqOf(page.get(page.size() - 1)) : null;
    body.put("items", List.copyOf(page));
    body.put("itemCount", page.size());
    body.put("matchedCount", remaining.size());
    body.put("limit", request.limit());
    body.put("hasMore", hasMore);
    body.put("nextSinceSeq", nextSinceSeq);
    return body;
  }

  /**
   * 倒序分块读全量再反转成正序（{@link EventQuery} 只有 {@code beforeSeq}、只按 seq 倒序返回）。
   *
   * <p>为什么不做成"正向游标"：{@link EventQuery} 是 33 个构造点的既有记录，加字段要么裂既有构造点、要么给每条 SQL 都加分支；读侧全量反转的代价是
   * O(该库事件数)，而一个子体库是"每步一次"级别的小库，换取的是零接口面改动。
   */
  private static List<Event> readAllAscending(SqliteEventStore store, String agentId) {
    List<Event> descending = new ArrayList<>();
    long beforeSeq = -1;
    while (true) {
      List<Event> chunk = store.query(new EventQuery(agentId, "", "", beforeSeq, SCAN_CHUNK));
      descending.addAll(chunk);
      if (chunk.size() < SCAN_CHUNK) {
        break;
      }
      beforeSeq = chunk.get(chunk.size() - 1).seq(); // 本块最老一条：下一块严格更老
    }
    Collections.reverse(descending);
    return descending;
  }

  // ---- 五个视图的投影 ----

  /** summary：状态/深度/模板/目标 + 回合数 + token 汇总 + 首末时间 + 最后一条 output。 */
  private static Map<String, Object> summary(
      List<Event> ascending, String agentId, SubagentInstance instance) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("agent", agentId);
    if (instance == null) {
      // 不编造：没有实例快照（如 target=self）就明说"无快照"，只报能从事件推出的事实
      summary.put("instanceKnown", false);
    } else {
      summary.put("instanceKnown", true);
      summary.put("instanceId", instance.instanceId());
      summary.put("templateId", instance.templateId());
      summary.put("status", instance.status().name());
      summary.put("depth", instance.depth());
      summary.put("goal", instance.goal());
    }
    List<Event> turns = filterByType(ascending, EventTypes.CONVERSATION_TURN);
    summary.put("turns", turns.size());
    summary.put("lastOutput", lastOutput(turns));
    summary.put("tokenUsage", tokenUsage(ascending));
    summary.put("firstTs", ascending.isEmpty() ? "" : ascending.get(0).ts().toString());
    summary.put(
        "lastTs", ascending.isEmpty() ? "" : ascending.get(ascending.size() - 1).ts().toString());
    summary.put("eventCount", ascending.size());
    return summary;
  }

  /** turns：逐条 conversation.turn 的输入与输出。 */
  private static List<Map<String, Object>> turns(List<Event> ascending) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Event event : filterByType(ascending, EventTypes.CONVERSATION_TURN)) {
      JsonNode payload = parsePayload(event.payload());
      Map<String, Object> row = base(event);
      putText(row, "input", payload.path("input").asText(""));
      putText(row, "output", payload.path("output").asText(""));
      rows.add(row);
    }
    return rows;
  }

  /** results：<b>只看结果</b>（只要答案）。 */
  private static List<Map<String, Object>> results(List<Event> ascending) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Event event : filterByType(ascending, EventTypes.CONVERSATION_TURN)) {
      JsonNode payload = parsePayload(event.payload());
      Map<String, Object> row = base(event);
      putText(row, "output", payload.path("output").asText(""));
      rows.add(row);
    }
    return rows;
  }

  /** search：关键词 substring 命中（CJK 不分词），可先按 type 限定。 */
  private static List<Map<String, Object>> search(
      List<Event> ascending, String type, String query) {
    if (query.isEmpty()) {
      throw new IllegalArgumentException("search 视图必须给 q（关键词，substring 匹配）");
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Event event : filterByType(ascending, type)) {
      String text = renderText(event);
      if (!text.contains(query)) {
        continue;
      }
      Map<String, Object> row = base(event);
      putText(row, "text", text);
      rows.add(row);
    }
    return rows;
  }

  /** events：原始事件（按类型可选）。 */
  private static List<Map<String, Object>> events(List<Event> ascending, String type) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Event event : filterByType(ascending, type)) {
      Map<String, Object> row = base(event);
      row.put("type", event.type());
      row.put("agent", event.agent());
      row.put("correlationId", event.correlationId());
      putText(row, "payload", event.payload());
      rows.add(row);
    }
    return rows;
  }

  // ---- 细节 ----

  /** 行骨架：seq + ts（排序与翻页游标都靠 seq）。 */
  private static Map<String, Object> base(Event event) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("seq", event.seq());
    row.put("ts", event.ts().toString());
    return row;
  }

  private static long seqOf(Map<String, Object> row) {
    return ((Number) row.get("seq")).longValue();
  }

  private static List<Event> filterByType(List<Event> events, String type) {
    if (type == null || type.isEmpty()) {
      return events;
    }
    List<Event> filtered = new ArrayList<>();
    for (Event event : events) {
      if (type.equals(event.type())) {
        filtered.add(event);
      }
    }
    return filtered;
  }

  /** 最后一条回合的 output（没有回合时为空串——不编造）。 */
  private static String lastOutput(List<Event> turns) {
    if (turns.isEmpty()) {
      return "";
    }
    JsonNode payload = parsePayload(turns.get(turns.size() - 1).payload());
    return truncate(payload.path("output").asText(""));
  }

  /** token 汇总（口径同二期 {@code ContextReport}）：{@code -1}=未知（如"无此缓存档"）不计入求和、单独计数。 */
  private static Map<String, Object> tokenUsage(List<Event> ascending) {
    long calls = 0;
    long input = 0;
    long output = 0;
    long cacheRead = 0;
    long cacheWrite = 0;
    long unknownInput = 0;
    long unknownOutput = 0;
    long unknownCacheRead = 0;
    long unknownCacheWrite = 0;
    for (Event event : filterByType(ascending, EventTypes.LLM_CALL)) {
      JsonNode payload = parsePayload(event.payload());
      calls++;
      long i = tokenOf(payload, "inputTokens");
      long o = tokenOf(payload, "outputTokens");
      long cr = tokenOf(payload, "cacheReadTokens");
      long cw = tokenOf(payload, "cacheWriteTokens");
      input += Math.max(0L, i);
      output += Math.max(0L, o);
      cacheRead += Math.max(0L, cr);
      cacheWrite += Math.max(0L, cw);
      unknownInput += i < 0 ? 1 : 0;
      unknownOutput += o < 0 ? 1 : 0;
      unknownCacheRead += cr < 0 ? 1 : 0;
      unknownCacheWrite += cw < 0 ? 1 : 0;
    }
    Map<String, Object> usage = new LinkedHashMap<>();
    usage.put("callCount", calls);
    usage.put("totalInputTokens", input);
    usage.put("totalOutputTokens", output);
    usage.put("totalCacheReadTokens", cacheRead);
    usage.put("totalCacheWriteTokens", cacheWrite);
    usage.put("unknownInputTokens", unknownInput);
    usage.put("unknownOutputTokens", unknownOutput);
    usage.put("unknownCacheReadTokens", unknownCacheRead);
    usage.put("unknownCacheWriteTokens", unknownCacheWrite);
    usage.put("note", "未知(-1)不计入求和、单独计数（口径同 ContextReport；不要把 -1 直接相加）");
    return usage;
  }

  /** 单条事件的可读文本（search 的匹配面与回显面同源，避免"匹配到的和看到的不一致"）。 */
  private static String renderText(Event event) {
    JsonNode payload = parsePayload(event.payload());
    return switch (event.type()) {
      case EventTypes.CONVERSATION_TURN ->
          "input="
              + payload.path("input").asText("")
              + "\noutput="
              + payload.path("output").asText("");
      case EventTypes.TOOL_CALL ->
          "tool=" + payload.path("tool").asText("") + " args=" + payload.path("args").toString();
      case EventTypes.TOOL_RESULT ->
          "tool="
              + payload.path("tool").asText("")
              + " ok="
              + payload.path("ok").asText("")
              + " message="
              + payload.path("message").asText("");
      case EventTypes.LLM_CALL ->
          "model="
              + payload.path("model").asText("")
              + " inputTokens="
              + payload.path("inputTokens").asText("")
              + " outputTokens="
              + payload.path("outputTokens").asText("");
      default -> event.payload();
    };
  }

  /** 文本字段：按字节截断 + 截断标记 + 行级 {@code truncated} 标志。 */
  private static void putText(Map<String, Object> row, String key, String value) {
    String truncated = truncate(value);
    row.put(key, truncated);
    if (!truncated.equals(value)) {
      row.put("truncated", true);
    }
  }

  /** 按 UTF-8 字节截断（不切断多字节字符——CJK 场景下按 char 截断会切出半个字），并追加可见标记。 */
  static String truncate(String text) {
    if (text == null || text.isEmpty()) {
      return text == null ? "" : text;
    }
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= MAX_TEXT_BYTES) {
      return text;
    }
    int end = 0;
    int used = 0;
    int index = 0;
    while (index < text.length()) {
      int codePoint = text.codePointAt(index);
      int utf8Length =
          new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
      if (used + utf8Length > MAX_TEXT_BYTES) {
        break;
      }
      used += utf8Length;
      index += Character.charCount(codePoint);
      end = index;
    }
    return text.substring(0, end) + "…[已截断：完整 " + bytes.length + " 字节 / 显示前 " + used + " 字节]";
  }

  private static long tokenOf(JsonNode payload, String key) {
    JsonNode value = payload.get(key);
    return value != null && value.isNumber() ? value.asLong() : -1L;
  }

  /** payload 解析：坏 payload 退回空对象（展示不应因单条脏数据崩——与二期 ContextReport 同口径）。 */
  private static JsonNode parsePayload(String payload) {
    try {
      JsonNode node = JSON.readTree(payload);
      return node == null || node.isMissingNode() ? JSON.createObjectNode() : node;
    } catch (IOException e) {
      return JSON.createObjectNode();
    }
  }
}
