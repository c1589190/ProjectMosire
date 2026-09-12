package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.EventTypes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D30 {@link AgentContextReader} 的判别性用例：五视图各自取对行、<b>空结果 ≠ 报错</b>与<b>库不存在 = 报错</b>分开测、 翻页不重不漏（≥3
 * 页逐页拼接 == 全集）、超长文本按字节截断且不切断多字节字符。
 *
 * <p>全部离线：直接造一个子体形状的事件库（{@code events} 表 + 子体自己那些事件），经只读路径读。
 */
class AgentContextReaderTest {

  private static final String CHILD = "reader-d8231100fd948a4d";

  @TempDir Path tempDir;

  // ---- 造库 ----

  /** 造一个子体库（含回合/记账/工具/生命周期），返回 events.db 路径。 */
  private Path childDb(int turns) throws Exception {
    Path db = tempDir.resolve("subagents").resolve(CHILD).resolve("events.db");
    Files.createDirectories(db.getParent());
    try (SqliteEventStore store = SqliteEventStore.open(db)) {
      store.append(
          EventWrite.of(EventTypes.AGENT_LIFECYCLE, CHILD, "{\"action\":\"running\"}", CHILD));
      for (int i = 1; i <= turns; i++) {
        store.append(
            EventWrite.of(
                EventTypes.CONVERSATION_TURN,
                CHILD,
                "{\"turns\":" + i + ",\"input\":\"问" + i + "\",\"output\":\"答" + i + "\"}",
                CHILD));
        store.append(
            EventWrite.of(
                EventTypes.LLM_CALL,
                CHILD,
                "{\"inputTokens\":10,\"outputTokens\":2,\"cacheReadTokens\":0,"
                    + "\"cacheWriteTokens\":-1,\"model\":\"fake\"}",
                CHILD));
      }
      store.append(
          EventWrite.of(
              EventTypes.TOOL_CALL,
              CHILD,
              "{\"tool\":\"echo\",\"args\":{\"text\":\"芝麻开门\"}}",
              CHILD));
      store.append(
          EventWrite.of(
              EventTypes.TOOL_RESULT,
              CHILD,
              "{\"tool\":\"echo\",\"ok\":true,\"message\":\"开了\"}",
              CHILD));
    }
    return db;
  }

  private static Map<String, Object> read(
      Path db, String view, String q, String type, int limit, long sinceSeq) {
    return AgentContextReader.read(
        db, CHILD, new AgentContextReader.Request(view, q, type, limit, sinceSeq), instance());
  }

  private static SubagentInstance instance() {
    return new SubagentInstance(
        CHILD,
        "reader",
        "把话说清楚",
        AgentConfig.builder(CHILD).build(),
        AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
        1,
        SubagentStatus.FINISHED);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(Map<String, Object> body) {
    return (List<Map<String, Object>>) body.get("items");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> summaryOf(Map<String, Object> body) {
    return (Map<String, Object>) body.get("summary");
  }

  // ---- 判别性用例 ----

  @Test
  void fiveViewsEachPickTheirOwnRows() throws Exception {
    Path db = childDb(2);

    Map<String, Object> turns = read(db, AgentContextReader.VIEW_TURNS, "", "", 20, 0);
    assertThat(items(turns))
        .hasSize(2)
        .allSatisfy(
            row -> {
              assertThat(row).containsKeys("seq", "ts", "input", "output");
              assertThat(String.valueOf(row.get("input"))).startsWith("问");
              assertThat(String.valueOf(row.get("output"))).startsWith("答");
            });
    assertThat(String.valueOf(items(turns).get(1).get("input"))).isEqualTo("问2");

    // results：只出 output，不夹 input
    Map<String, Object> results = read(db, AgentContextReader.VIEW_RESULTS, "", "", 20, 0);
    assertThat(items(results))
        .hasSize(2)
        .allSatisfy(row -> assertThat(row).doesNotContainKey("input"));
    assertThat(items(results)).extracting(row -> row.get("output")).containsExactly("答1", "答2");

    // events：按类型取原始事件（含 correlationId 与 payload）
    Map<String, Object> toolCalls =
        read(db, AgentContextReader.VIEW_EVENTS, "", EventTypes.TOOL_CALL, 20, 0);
    assertThat(items(toolCalls))
        .hasSize(1)
        .allSatisfy(
            row -> {
              assertThat(row.get("type")).isEqualTo(EventTypes.TOOL_CALL);
              assertThat(row.get("correlationId")).isEqualTo(CHILD);
              assertThat(String.valueOf(row.get("payload"))).contains("echo");
            });
    Map<String, Object> allEvents = read(db, AgentContextReader.VIEW_EVENTS, "", "", 200, 0);
    assertThat(items(allEvents))
        .hasSize(2 * 2 + 1 + 2); // 2 回合 × (turn+llm.call) + lifecycle + tool×2

    // search：只出命中项（substring，CJK 不分词）
    Map<String, Object> hit = read(db, AgentContextReader.VIEW_SEARCH, "芝麻开门", "", 20, 0);
    assertThat(items(hit))
        .hasSize(1)
        .allSatisfy(row -> assertThat(String.valueOf(row.get("text"))).contains("芝麻开门"));
    assertThat(
            read(db, AgentContextReader.VIEW_SEARCH, "芝麻开门", EventTypes.CONVERSATION_TURN, 20, 0)
                .get("itemCount"))
        .isEqualTo(0); // 命中项在 tool.call 上：type 限定后应当为 0（空 ≠ 错）

    // summary：状态/模板/深度/目标 + 回合数 + 最后一条 output + token 汇总
    Map<String, Object> summary =
        summaryOf(read(db, AgentContextReader.VIEW_SUMMARY, "", "", 20, 0));
    assertThat(summary.get("status")).isEqualTo("FINISHED");
    assertThat(summary.get("templateId")).isEqualTo("reader");
    assertThat(summary.get("depth")).isEqualTo(1);
    assertThat(summary.get("goal")).isEqualTo("把话说清楚");
    assertThat(summary.get("turns")).isEqualTo(2);
    assertThat(summary.get("lastOutput")).isEqualTo("答2");
    @SuppressWarnings("unchecked")
    Map<String, Object> usage = (Map<String, Object>) summary.get("tokenUsage");
    assertThat(usage.get("callCount")).isEqualTo(2L);
    assertThat(usage.get("totalInputTokens")).isEqualTo(20L);
    assertThat(usage.get("totalOutputTokens")).isEqualTo(4L);
    // -1（未知）不计入求和、单独计数——口径同二期 ContextReport（直接相加会得负数）
    assertThat(usage.get("totalCacheWriteTokens")).isEqualTo(0L);
    assertThat(usage.get("unknownCacheWriteTokens")).isEqualTo(2L);
  }

  @Test
  void emptyResultIsNotAnErrorButMissingDbIs() throws Exception {
    Path db = childDb(1);
    Map<String, Object> empty = read(db, AgentContextReader.VIEW_EVENTS, "", "不存在的类型", 20, 0);
    assertThat(items(empty)).isEmpty();
    assertThat(empty.get("itemCount")).isEqualTo(0);
    assertThat(empty.get("hasMore")).isEqualTo(false);

    Path absent = tempDir.resolve("subagents").resolve("ghost-1").resolve("events.db");
    assertThatThrownBy(() -> read(absent, AgentContextReader.VIEW_SUMMARY, "", "", 20, 0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不存在");
  }

  @Test
  void pagingIsLosslessAndDuplicationFree() throws Exception {
    Path db = childDb(7);
    int limit = 3;
    List<Long> collected = new ArrayList<>();
    Set<Long> seen = new LinkedHashSet<>();
    long cursor = 0;
    int pages = 0;
    while (true) {
      Map<String, Object> page = read(db, AgentContextReader.VIEW_TURNS, "", "", limit, cursor);
      pages++;
      for (Map<String, Object> row : items(page)) {
        long seq = ((Number) row.get("seq")).longValue();
        assertThat(seen.add(seq)).as("seq 不得重复出现: %s", seq).isTrue();
        collected.add(seq);
      }
      if (!Boolean.TRUE.equals(page.get("hasMore"))) {
        assertThat(page.get("nextSinceSeq")).isNull();
        break;
      }
      cursor = ((Number) page.get("nextSinceSeq")).longValue();
    }
    // ≥3 页
    assertThat(pages).isEqualTo(3); // 7 条 / 每页 3 = 3、3、1
    // 逐页拼接 == 全集且严格升序（不重不漏）
    List<Long> all = new ArrayList<>();
    for (Map<String, Object> row : items(read(db, AgentContextReader.VIEW_TURNS, "", "", 200, 0))) {
      all.add(((Number) row.get("seq")).longValue());
    }
    assertThat(collected).containsExactlyElementsOf(all);
    assertThat(collected).isSorted();
  }

  @Test
  void longTextIsTruncatedOnByteBoundaryAndMarked() throws Exception {
    Path db = tempDir.resolve("subagents").resolve(CHILD).resolve("events.db");
    Files.createDirectories(db.getParent());
    String longCjk = "上下文".repeat(AgentContextReader.MAX_TEXT_BYTES); // 远超字节上限的多字节文本
    try (SqliteEventStore store = SqliteEventStore.open(db)) {
      store.append(
          EventWrite.of(
              EventTypes.CONVERSATION_TURN,
              CHILD,
              "{\"input\":\"短\",\"output\":\"" + longCjk + "\"}",
              CHILD));
    }
    Map<String, Object> results = read(db, AgentContextReader.VIEW_RESULTS, "", "", 20, 0);
    Map<String, Object> row = items(results).get(0);
    String output = String.valueOf(row.get("output"));
    assertThat(row.get("truncated")).isEqualTo(true);
    assertThat(output).contains("已截断");
    assertThat(output.getBytes(StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(AgentContextReader.MAX_TEXT_BYTES + 64); // 上限 + 标记
    // 没切断多字节字符：完整文本重新编码后不含 UTF-8 替换符（截断点落在字符边界）
    assertThat(output).doesNotContain("�");
  }

  @Test
  void searchWithoutKeywordIsInvalidNotSilentlyEmpty() throws Exception {
    Path db = childDb(1);
    assertThatThrownBy(() -> read(db, AgentContextReader.VIEW_SEARCH, "", "", 20, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("q");
    assertThatThrownBy(() -> new AgentContextReader.Request("不存在的视图", "", "", 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知视图");
  }
}
