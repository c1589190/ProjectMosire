package io.mosire.main.gateway.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.SqliteEventStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R9 基准：1 万 a2a 快照事件下 {@link EventStoreA2aTaskStore#list} 依赖 SQL 侧 {@code GROUP BY correlation_id +
 * MAX(seq)}（配 {@code (type, correlation_id, seq)} 索引），不再全量拉取并逐条反序列化。
 *
 * <p>主断言（验收/回归）：(a) 索引存在于 {@code sqlite_master}；(b) 查询行数 == {@code count(DISTINCT
 * correlation_id)}；(c) 每个 correlationId 返回的是该 id 的 {@code MAX(seq)} 行； (d) 耗时"明显劣化才红"——超 {@code
 * SOFT_LIMIT_MS} 才 fail，否则仅日志（实验机 1.6GB 有换页，100ms 级属噪声）。
 */
class EventStoreA2aTaskStoreListScaleTest {

  /** 与 SqliteEventStore DDL 中的索引同名（断言两处保持一致，改索引名必须同步）。 */
  private static final String INDEX_NAME = "idx_events_type_correlation_id_seq";

  private static final int TASK_COUNT = 2_000;
  private static final int SNAPSHOTS_PER_TASK = 5; // 合计 10_000 条快照事件
  private static final int CONTEXT_VARIANT = 7; // contextId = "ctx-" + (i % 7)

  /**
   * 每任务 5 版快照的状态标记（第 5 版为最新）——fixture 刻意紧凑：Task 仅 id/contextId/status（spec Builder
   * 只对这三者断言非空），控制每个事件 JSON 体积，使基准聚焦"分组取最新"而非 Gson 序列化吞吐。
   */
  private static final TaskState[] VERSION_STATES = {
    TaskState.TASK_STATE_SUBMITTED,
    TaskState.TASK_STATE_WORKING,
    TaskState.TASK_STATE_COMPLETED,
    TaskState.TASK_STATE_CANCELED,
    TaskState.TASK_STATE_FAILED,
  };

  /** 软阈值：1000ms（R9 裁决放宽自 100ms；阈值以上视为明显劣化）。 */
  private static final long SOFT_LIMIT_MS = 1_000L;

  private static final Logger LOG =
      LoggerFactory.getLogger(EventStoreA2aTaskStoreListScaleTest.class);

  @TempDir Path dir;

  @Test
  void listAtTenThousandSnapshotsReturnsLatestPerTaskWithIndex() throws Exception {
    Path db = dir.resolve("scale.db");
    try (SqliteEventStore events = SqliteEventStore.open(db)) {
      bulkInsertSnapshots(db);

      measureFullList(new EventStoreA2aTaskStore(events));
      long elapsedMs = measureQueryLatest(events);

      assertIndexExists(db);
      assertQueryShape(events, db);
      assertSoftLimit(elapsedMs, "queryLatestByCorrelation 全量耗时");
    }
  }

  /** 端到端 store.list：每任务恰好一条、最新快照、创建序倒序；contextId 过滤在最新集上仍正确。 */
  private static void measureFullList(EventStoreA2aTaskStore store) {
    long start = System.nanoTime();
    List<Task> tasks = store.list(null, Integer.MAX_VALUE, 0);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    LOG.info(
        "R9 基准：{} 任务 × {} 快照 = {} 条事件，store.list 全量耗时 {}ms",
        TASK_COUNT,
        SNAPSHOTS_PER_TASK,
        TASK_COUNT * SNAPSHOTS_PER_TASK,
        elapsedMs);
    assertSoftLimit(elapsedMs, "store.list 全量耗时");

    assertThat(tasks).hasSize(TASK_COUNT);
    for (int i = 0; i < TASK_COUNT; i++) {
      Task task = tasks.get(i);
      assertThat(task.id()).isEqualTo("t-" + (TASK_COUNT - 1 - i));
      // 最新快照 = 第 5 版（FAILED）；若 list 误取旧版则该断言失败
      assertThat(task.status().state()).isEqualTo(VERSION_STATES[SNAPSHOTS_PER_TASK - 1]);
    }

    List<Task> ctx0 = store.list("ctx-0", Integer.MAX_VALUE, 0);
    assertThat(ctx0).hasSize(expectedContextSize(0)).allMatch(t -> "ctx-0".equals(t.contextId()));
  }

  /** 新查询自身：行数 == DISTINCT 数（b），每行 == 该 correlationId 的 MAX(seq)（c）。 */
  private static long measureQueryLatest(SqliteEventStore events) {
    long start = System.nanoTime();
    List<Event> latest =
        events.queryLatestByCorrelation(EventStoreA2aTaskStore.SNAPSHOT_TYPE, Integer.MAX_VALUE, 0);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    LOG.info("R9 基准：queryLatestByCorrelation 全量耗时 {}ms（返回 {} 行）", elapsedMs, latest.size());
    return elapsedMs;
  }

  private static void assertQueryShape(SqliteEventStore events, Path db) throws Exception {
    List<Event> latest =
        events.queryLatestByCorrelation(EventStoreA2aTaskStore.SNAPSHOT_TYPE, Integer.MAX_VALUE, 0);
    assertThat(latest).hasSize(distinctCorrelationCount(db));
    Map<String, Long> maxSeqByCorrelation = maxSeqByCorrelation(db);
    for (Event event : latest) {
      assertThat(maxSeqByCorrelation)
          .as("correlationId %s 应返回其 MAX(seq) 行", event.correlationId())
          .containsEntry(event.correlationId(), event.seq());
    }
  }

  /** (a) 索引存在于 sqlite_master 且列序为 (type, correlation_id, seq)。 */
  private static void assertIndexExists(Path db) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var ps =
            c.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?")) {
      ps.setString(1, INDEX_NAME);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("索引 %s 应已创建", INDEX_NAME).isTrue();
      }
    }
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var stmt = c.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA index_info(" + INDEX_NAME + ")")) {
      List<String> columns = new ArrayList<>();
      while (rs.next()) {
        columns.add(rs.getString("name"));
      }
      assertThat(columns).containsExactly("type", "correlation_id", "seq");
    }
  }

  /** 单事务批量插入 1 万条快照事件（走新连接绕过 store 逐条 commit，秒级内完成）。 */
  private static void bulkInsertSnapshots(Path db) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
      c.setAutoCommit(false);
      try (var ps =
          c.prepareStatement(
              "INSERT INTO events (ts, type, agent, payload, correlation_id) VALUES (?, ?, ?, ?, ?)")) {
        String ts = Instant.now().toString();
        for (int i = 0; i < TASK_COUNT; i++) {
          String taskId = "t-" + i;
          String contextId = "ctx-" + (i % CONTEXT_VARIANT);
          for (int v = 0; v < SNAPSHOTS_PER_TASK; v++) {
            Task task =
                Task.builder()
                    .id(taskId)
                    .contextId(contextId)
                    .status(new TaskStatus(VERSION_STATES[v]))
                    .build();
            ps.setString(1, ts);
            ps.setString(2, EventStoreA2aTaskStore.SNAPSHOT_TYPE);
            ps.setString(3, "a2a");
            ps.setString(4, JsonUtil.toJson(task));
            ps.setString(5, taskId);
            ps.addBatch();
          }
        }
        ps.executeBatch();
        c.commit();
      }
    }
  }

  private static int expectedContextSize(int variant) {
    int n = 0;
    for (int i = 0; i < TASK_COUNT; i++) {
      if (i % CONTEXT_VARIANT == variant) {
        n++;
      }
    }
    return n;
  }

  private static int distinctCorrelationCount(Path db) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var ps =
            c.prepareStatement(
                "SELECT COUNT(*) FROM (SELECT DISTINCT correlation_id FROM events WHERE type = ?)")) {
      ps.setString(1, EventStoreA2aTaskStore.SNAPSHOT_TYPE);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
  }

  private static Map<String, Long> maxSeqByCorrelation(Path db) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var ps =
            c.prepareStatement(
                "SELECT correlation_id, MAX(seq) FROM events WHERE type = ? GROUP BY correlation_id")) {
      ps.setString(1, EventStoreA2aTaskStore.SNAPSHOT_TYPE);
      Map<String, Long> map = new HashMap<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          map.put(rs.getString(1), rs.getLong(2));
        }
      }
      return map;
    }
  }

  private static void assertSoftLimit(long elapsedMs, String what) {
    assertThat(elapsedMs)
        .as("%s 超 %dms —— 明显劣化才算回归（R9 裁决放宽阈值）", what, SOFT_LIMIT_MS)
        .isLessThan(SOFT_LIMIT_MS);
  }
}
