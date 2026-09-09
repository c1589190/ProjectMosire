package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.brain.runtime.EventTypes;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ContextReport} 对 llm.call 记账事件的聚合行为（总量、按模型、未知 -1 的处理）。 */
class ContextReportTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private static String payload(Map<String, Object> body) throws Exception {
    return JSON.writeValueAsString(body);
  }

  @Test
  void aggregatesCallCountTotalsAndPerModelBreakdown() throws Exception {
    try (SqliteEventStore store = SqliteEventStore.open(tempDir.resolve("events.db"))) {
      store.append(
          EventWrite.of(
              EventTypes.LLM_CALL,
              "main",
              payload(
                  Map.of(
                      "inputTokens",
                      100,
                      "outputTokens",
                      40,
                      "cacheReadTokens",
                      5,
                      "cacheWriteTokens",
                      1,
                      "model",
                      "glm-5",
                      "latencyMs",
                      120,
                      "turns",
                      1))));
      store.append(
          EventWrite.of(
              EventTypes.LLM_CALL,
              "main",
              payload(
                  Map.of(
                      "inputTokens",
                      30,
                      "outputTokens",
                      10,
                      "cacheReadTokens",
                      0,
                      "cacheWriteTokens",
                      0,
                      "model",
                      "glm-5",
                      "latencyMs",
                      80,
                      "turns",
                      2))));
      store.append(
          EventWrite.of(
              EventTypes.LLM_CALL,
              "sub",
              payload(
                  Map.of(
                      "inputTokens",
                      7,
                      "outputTokens",
                      3,
                      "cacheReadTokens",
                      2,
                      "cacheWriteTokens",
                      4,
                      "model",
                      "qwen",
                      "latencyMs",
                      50,
                      "turns",
                      1))));
      // 非 llm.call 事件不参与统计
      store.append(EventWrite.of(EventTypes.CONVERSATION_TURN, "main", "{}"));

      ContextReport.Summary summary = ContextReport.summarize(store);

      assertThat(summary.callCount()).isEqualTo(3);
      assertThat(summary.totalInputTokens()).isEqualTo(137);
      assertThat(summary.totalOutputTokens()).isEqualTo(53);
      assertThat(summary.totalCacheReadTokens()).isEqualTo(7);
      assertThat(summary.totalCacheWriteTokens()).isEqualTo(5);
      assertThat(summary.unknownInputTokens()).isZero();

      assertThat(summary.perModel()).containsOnlyKeys("glm-5", "qwen");
      assertThat(summary.perModel().get("glm-5").callCount()).isEqualTo(2);
      assertThat(summary.perModel().get("glm-5").inputTokens()).isEqualTo(130);
      assertThat(summary.perModel().get("glm-5").outputTokens()).isEqualTo(50);
      assertThat(summary.perModel().get("qwen").callCount()).isEqualTo(1);
      assertThat(summary.perModel().get("qwen").inputTokens()).isEqualTo(7);
      assertThat(summary.perModel().get("qwen").outputTokens()).isEqualTo(3);
    }
  }

  @Test
  void unknownMinusOneTokensExcludedFromTotalsAndCounted() throws Exception {
    try (SqliteEventStore store = SqliteEventStore.open(tempDir.resolve("events.db"))) {
      store.append(
          EventWrite.of(
              EventTypes.LLM_CALL,
              "main",
              payload(
                  Map.of(
                      "inputTokens",
                      -1,
                      "outputTokens",
                      12,
                      "cacheReadTokens",
                      0,
                      "cacheWriteTokens",
                      -1,
                      "model",
                      "glm-5",
                      "latencyMs",
                      30,
                      "turns",
                      1))));
      store.append(
          EventWrite.of(
              EventTypes.LLM_CALL,
              "main",
              payload(
                  Map.of(
                      "inputTokens",
                      20,
                      "outputTokens",
                      -1,
                      "cacheReadTokens",
                      1,
                      "cacheWriteTokens",
                      2,
                      "model",
                      "qwen",
                      "latencyMs",
                      20,
                      "turns",
                      1))));

      ContextReport.Summary summary = ContextReport.summarize(store);

      assertThat(summary.callCount()).isEqualTo(2);
      // -1 不按 0 计入总和
      assertThat(summary.totalInputTokens()).isEqualTo(20);
      assertThat(summary.unknownInputTokens()).isEqualTo(1);
      assertThat(summary.totalOutputTokens()).isEqualTo(12);
      assertThat(summary.unknownOutputTokens()).isEqualTo(1);
      assertThat(summary.totalCacheWriteTokens()).isEqualTo(2);
      assertThat(summary.unknownCacheWriteTokens()).isEqualTo(1);
      assertThat(summary.unknownCacheReadTokens()).isZero();
      // 未知值同样不计入按模型聚合
      assertThat(summary.perModel().get("glm-5").inputTokens()).isZero();
      assertThat(summary.perModel().get("glm-5").outputTokens()).isEqualTo(12);
    }
  }

  @Test
  void renderShowsTotalsPerModelAndUnknownNotes() throws Exception {
    try (SqliteEventStore store = SqliteEventStore.open(tempDir.resolve("events.db"))) {
      store.append(
          EventWrite.of(
              EventTypes.LLM_CALL,
              "main",
              payload(
                  Map.of(
                      "inputTokens",
                      -1,
                      "outputTokens",
                      12,
                      "cacheReadTokens",
                      0,
                      "cacheWriteTokens",
                      -1,
                      "model",
                      "glm-5",
                      "latencyMs",
                      30,
                      "turns",
                      1))));
      store.append(
          EventWrite.of(
              EventTypes.LLM_CALL,
              "main",
              payload(
                  Map.of(
                      "inputTokens",
                      20,
                      "outputTokens",
                      -1,
                      "cacheReadTokens",
                      1,
                      "cacheWriteTokens",
                      2,
                      "model",
                      "qwen",
                      "latencyMs",
                      20,
                      "turns",
                      1))));

      String text = ContextReport.render(store);

      assertThat(text).contains("调用次数: 2");
      assertThat(text).contains("输入 tokens: 20（未知 1 次）");
      assertThat(text).contains("输出 tokens: 12（未知 1 次）");
      assertThat(text).contains("glm-5");
      assertThat(text).contains("qwen");
    }
  }

  @Test
  void emptyStoreRendersZeroCalls() {
    try (SqliteEventStore store = SqliteEventStore.open(tempDir.resolve("events.db"))) {
      ContextReport.Summary summary = ContextReport.summarize(store);
      assertThat(summary.callCount()).isZero();
      assertThat(summary.perModel()).isEmpty();
      assertThat(ContextReport.render(summary)).contains("调用次数: 0");
    }
  }
}
