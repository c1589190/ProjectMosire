package io.mosire.main.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.EventStore;
import io.mosire.brain.runtime.EventTypes;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * LLM 调用 token 记账汇总（D18：token 经济的可观测走 EventStore，不走 OTel）。
 *
 * <p>从事件库拉取全部 {@code llm.call} 事件（{@link EventTypes#LLM_CALL}），聚合调用次数、输入/输出/缓存读/缓存写 token
 * 总量与按模型分解。payload 由生产端固化（见 brain AgentPipeline）：键为 {@code inputTokens / outputTokens /
 * cacheReadTokens / cacheWriteTokens / model / latencyMs / turns}。
 *
 * <p>未知 token（payload 记 {@code -1}）不折算成 0 参与求和，而是排除在总和之外并单独计数如实报告——把 "不知道"伪装成"为零"会污染成本统计，违背 Token
 * Economy 的记账诚实性。
 */
public final class ContextReport {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 分页游标每页大小：EventQuery 以 beforeSeq 翻页，避免对大库一次性取全量。 */
  private static final int PAGE_SIZE = 500;

  /** payload 缺失/非数值时按"未知"处理，与生产端 -1 约定同一语义。 */
  private static final long UNKNOWN = -1L;

  private ContextReport() {
    throw new AssertionError("No instances");
  }

  /** 单模型聚合：调用次数 + 输入/输出 token 总量（未知值不计入求和，见类注释）。 */
  public record ModelUsage(long callCount, long inputTokens, long outputTokens) {}

  /**
   * 汇总结果。
   *
   * <p>{@code unknown*Tokens} 字段是该维度为未知（-1）的事件条数——只计数、不参与对应总和。
   *
   * @param callCount 调用总次数
   * @param totalInputTokens 输入 token 总量（不含未知）
   * @param totalOutputTokens 输出 token 总量（不含未知）
   * @param totalCacheReadTokens 缓存读 token 总量（不含未知）
   * @param totalCacheWriteTokens 缓存写 token 总量（不含未知）
   * @param unknownInputTokens 输入 token 未知的调用数
   * @param unknownOutputTokens 输出 token 未知的调用数
   * @param unknownCacheReadTokens 缓存读 token 未知的调用数
   * @param unknownCacheWriteTokens 缓存写 token 未知的调用数
   * @param perModel 按模型分解（键为模型名，缺失时记"未知"）
   */
  public record Summary(
      long callCount,
      long totalInputTokens,
      long totalOutputTokens,
      long totalCacheReadTokens,
      long totalCacheWriteTokens,
      long unknownInputTokens,
      long unknownOutputTokens,
      long unknownCacheReadTokens,
      long unknownCacheWriteTokens,
      Map<String, ModelUsage> perModel) {

    public Summary {
      // 不可变拷贝（SpotBugs EI_EXPOSE_REP）+ 确定性顺序：按用量（输入+输出）降序，同量按名称升序
      List<Map.Entry<String, ModelUsage>> entries = new ArrayList<>(perModel.entrySet());
      entries.sort(
          Comparator.<Map.Entry<String, ModelUsage>>comparingLong(
                  entry -> entry.getValue().inputTokens() + entry.getValue().outputTokens())
              .reversed()
              .thenComparing(Map.Entry::getKey));
      Map<String, ModelUsage> ordered = new LinkedHashMap<>();
      entries.forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
      perModel = Collections.unmodifiableMap(ordered);
    }
  }

  /** 从事件库聚合全部 llm.call 记账事件。 */
  public static Summary summarize(EventStore store) {
    List<Event> events = queryAllLlmCalls(store);
    long callCount = 0;
    long totalInput = 0;
    long totalOutput = 0;
    long totalCacheRead = 0;
    long totalCacheWrite = 0;
    long unknownInput = 0;
    long unknownOutput = 0;
    long unknownCacheRead = 0;
    long unknownCacheWrite = 0;
    // 模型 -> {调用次数, 输入, 输出}；TreeMap 保证聚合顺序确定性，渲染前再按用量排序
    Map<String, long[]> perModel = new TreeMap<>();

    for (Event event : events) {
      JsonNode node = parsePayload(event);
      callCount++;
      totalInput += accumulate(node, "inputTokens");
      if (tokenOf(node, "inputTokens") < 0) {
        unknownInput++;
      }
      totalOutput += accumulate(node, "outputTokens");
      if (tokenOf(node, "outputTokens") < 0) {
        unknownOutput++;
      }
      totalCacheRead += accumulate(node, "cacheReadTokens");
      if (tokenOf(node, "cacheReadTokens") < 0) {
        unknownCacheRead++;
      }
      totalCacheWrite += accumulate(node, "cacheWriteTokens");
      if (tokenOf(node, "cacheWriteTokens") < 0) {
        unknownCacheWrite++;
      }
      String model = node.path("model").asText("未知");
      long[] usage = perModel.computeIfAbsent(model, key -> new long[3]);
      usage[0]++;
      usage[1] += Math.max(0L, tokenOf(node, "inputTokens"));
      usage[2] += Math.max(0L, tokenOf(node, "outputTokens"));
    }

    Map<String, ModelUsage> byModel = new LinkedHashMap<>();
    perModel.forEach(
        (model, usage) -> byModel.put(model, new ModelUsage(usage[0], usage[1], usage[2])));

    return new Summary(
        callCount,
        totalInput,
        totalOutput,
        totalCacheRead,
        totalCacheWrite,
        unknownInput,
        unknownOutput,
        unknownCacheRead,
        unknownCacheWrite,
        byModel);
  }

  /** 查询并渲染为多行中文文本（可直接打印到 stdout）。 */
  public static String render(EventStore store) {
    return render(summarize(store));
  }

  /** 渲染已算好的汇总（便于复用与测试）。 */
  public static String render(Summary summary) {
    StringBuilder sb = new StringBuilder();
    sb.append("LLM 调用 token 统计（llm.call 事件汇总）").append('\n');
    sb.append("调用次数: ").append(summary.callCount()).append('\n');
    sb.append("输入 tokens: ")
        .append(summary.totalInputTokens())
        .append("（未知 ")
        .append(summary.unknownInputTokens())
        .append(" 次）")
        .append('\n');
    sb.append("输出 tokens: ")
        .append(summary.totalOutputTokens())
        .append("（未知 ")
        .append(summary.unknownOutputTokens())
        .append(" 次）")
        .append('\n');
    sb.append("缓存读 tokens: ")
        .append(summary.totalCacheReadTokens())
        .append("（未知 ")
        .append(summary.unknownCacheReadTokens())
        .append(" 次）")
        .append('\n');
    sb.append("缓存写 tokens: ")
        .append(summary.totalCacheWriteTokens())
        .append("（未知 ")
        .append(summary.unknownCacheWriteTokens())
        .append(" 次）")
        .append('\n');
    sb.append('\n');
    sb.append("按模型:").append('\n');
    if (summary.perModel().isEmpty()) {
      sb.append("  （无 llm.call 事件）").append('\n');
    } else {
      sb.append(String.format("  %-16s %8s %12s %12s%n", "模型", "次数", "输入", "输出"));
      for (Map.Entry<String, ModelUsage> entry : summary.perModel().entrySet()) {
        sb.append(
            String.format(
                "  %-16s %8d %12d %12d%n",
                entry.getKey(),
                entry.getValue().callCount(),
                entry.getValue().inputTokens(),
                entry.getValue().outputTokens()));
      }
    }
    return sb.toString();
  }

  /** beforeSeq 游标翻页取全量 llm.call（EventQuery 按 seq 倒序、limit 正数约束）。 */
  private static List<Event> queryAllLlmCalls(EventStore store) {
    List<Event> all = new ArrayList<>();
    long beforeSeq = -1;
    while (true) {
      List<Event> page =
          store.query(new EventQuery("", EventTypes.LLM_CALL, "", beforeSeq, PAGE_SIZE));
      all.addAll(page);
      if (page.size() < PAGE_SIZE) {
        return all;
      }
      beforeSeq = page.get(page.size() - 1).seq();
    }
  }

  /** payload 解析：坏 payload 视为全未知并继续（记账展示不应因单条脏数据崩溃）。 */
  private static JsonNode parsePayload(Event event) {
    try {
      JsonNode node = JSON.readTree(event.payload());
      return node == null || node.isMissingNode() ? JSON.createObjectNode() : node;
    } catch (IOException e) {
      return JSON.createObjectNode();
    }
  }

  /** 取 token 数值；缺失/非数值按未知（-1）。 */
  private static long tokenOf(JsonNode node, String key) {
    JsonNode value = node.get(key);
    return value != null && value.isNumber() ? value.asLong() : UNKNOWN;
  }

  /** 未知（-1 及以下）不计入求和，返回 0。 */
  private static long accumulate(JsonNode node, String key) {
    long value = tokenOf(node, key);
    return value < 0 ? 0L : value;
  }
}
