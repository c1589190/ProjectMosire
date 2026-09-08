package io.mosire.agentlib.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteEventStoreTest {

  @TempDir Path tempDir;

  @Test
  void appendsAndAssignsMonotonicSeq() {
    try (SqliteEventStore store = SqliteEventStore.open(tempDir.resolve("events.db"))) {
      Event first = store.append(EventWrite.of("conversation.turn", "main", "{\"n\":1}"));
      Event second = store.append(EventWrite.of("tool.call", "main", "{}", "corr-1"));
      assertThat(first.seq()).isEqualTo(1);
      assertThat(second.seq()).isEqualTo(2);
      assertThat(first.agent()).isEqualTo("main");
      assertThat(second.correlationId()).isEqualTo("corr-1");
      assertThat(store.count()).isEqualTo(2);
    }
  }

  @Test
  void queriesByAgentTypeAndCorrelation() {
    try (SqliteEventStore store = SqliteEventStore.open(tempDir.resolve("events.db"))) {
      store.append(EventWrite.of("a.type", "main", "{}"));
      store.append(EventWrite.of("b.type", "main", "{}"));
      store.append(EventWrite.of("a.type", "sub", "{}", "corr"));
      assertThat(store.query(EventQuery.recent(10))).hasSize(3);
      List<Event> byAgent = store.query(new EventQuery("sub", "", "", -1, 10));
      assertThat(byAgent).hasSize(1);
      assertThat(byAgent.get(0).agent()).isEqualTo("sub");
      List<Event> byType = store.query(new EventQuery("", "a.type", "", -1, 10));
      assertThat(byType).hasSize(2);
      List<Event> byCorr = store.query(new EventQuery("", "", "corr", -1, 10));
      assertThat(byCorr).hasSize(1);
      assertThat(byCorr.get(0).correlationId()).isEqualTo("corr");
    }
  }

  @Test
  void queryReturnsNewestFirstWithLimitAndCursor() {
    try (SqliteEventStore store = SqliteEventStore.open(tempDir.resolve("events.db"))) {
      for (int i = 0; i < 5; i++) {
        store.append(EventWrite.of("t", "main", String.valueOf(i)));
      }
      List<Event> recent = store.query(EventQuery.recent(2));
      assertThat(recent).extracting(Event::seq).containsExactly(5L, 4L);
      // 游标翻页：取 seq<5 的两条
      List<Event> page = store.query(new EventQuery("", "", "", 5, 2));
      assertThat(page).extracting(Event::seq).containsExactly(4L, 3L);
    }
  }

  @Test
  void persistsAcrossReopen() {
    Path db = tempDir.resolve("events.db");
    try (SqliteEventStore store = SqliteEventStore.open(db)) {
      store.append(EventWrite.of("persist.me", "main", "{\"x\":1}"));
    }
    try (SqliteEventStore store = SqliteEventStore.open(db)) {
      assertThat(store.count()).isEqualTo(1);
      assertThat(store.bySeq(1)).isPresent();
      assertThat(store.bySeq(1).get().payload()).isEqualTo("{\"x\":1}");
    }
  }

  @Test
  void validatesWrite() {
    try (SqliteEventStore store = SqliteEventStore.open(tempDir.resolve("events.db"))) {
      org.junit.jupiter.api.Assertions.assertThrows(
          NullPointerException.class, () -> store.append(new EventWrite(null, "main", "", "")));
    }
  }

  /** R9：每组 correlationId 只返回 seq 最大的一条，且仅在 type 内分组（异类型同 id 互不干扰，空 corr 也是独立组）。 */
  @Test
  void queryLatestByCorrelationReturnsMaxSeqRowPerCorrelation() {
    try (SqliteEventStore store = SqliteEventStore.open(tempDir.resolve("events.db"))) {
      store.append(EventWrite.of("a.type", "main", "{v:1}")); // seq 1, 未带 corr（空组）
      store.append(EventWrite.of("b.type", "main", "{v:1}", "c1")); // seq 2, 异类型不干扰分组
      store.append(EventWrite.of("a.type", "main", "{v:1}", "c1")); // seq 3
      store.append(EventWrite.of("a.type", "main", "{v:2}", "c2")); // seq 4
      store.append(EventWrite.of("a.type", "main", "{v:2}", "c1")); // seq 5, c1 最新
      store.append(EventWrite.of("a.type", "main", "{v:3}", "c2")); // seq 6, c2 最新
      store.append(EventWrite.of("a.type", "main", "{v:9}")); // seq 7, 空组最新

      List<Event> latest = store.queryLatestByCorrelation("a.type", 10, 0);
      assertThat(latest).extracting(Event::seq).containsExactly(7L, 6L, 5L);
      assertThat(latest).extracting(Event::correlationId).containsExactly("", "c2", "c1");
      assertThat(latest).extracting(Event::payload).containsExactly("{v:9}", "{v:3}", "{v:2}");
    }
  }

  /** R9：分组后的最新集合按 seq 倒序，且支持 limit/offset 分页（offset 越界返回空）。 */
  @Test
  void queryLatestByCorrelationPaginatesOverDistinctCorrelations() {
    try (SqliteEventStore store = SqliteEventStore.open(tempDir.resolve("events.db"))) {
      for (int i = 0; i < 6; i++) {
        store.append(EventWrite.of("t", "main", "v" + i, "corr-" + i)); // seq 1..6 各一组
      }
      store.append(EventWrite.of("t", "main", "v1b", "corr-1")); // seq 7: corr-1 的最新一条

      // 最新 seq 集合（倒序）：7(c1), 6(c5), 5(c4), 4(c3), 3(c2), 1(c0)
      assertThat(store.queryLatestByCorrelation("t", 10, 0))
          .extracting(Event::seq)
          .containsExactly(7L, 6L, 5L, 4L, 3L, 1L);
      assertThat(store.queryLatestByCorrelation("t", 2, 1))
          .extracting(Event::seq)
          .containsExactly(6L, 5L);
      assertThat(store.queryLatestByCorrelation("t", 2, 2))
          .extracting(Event::seq)
          .containsExactly(5L, 4L);
      assertThat(store.queryLatestByCorrelation("t", 2, 100)).isEmpty();
    }
  }

  /** R9：DDL 幂等建 {@code (type, correlation_id, seq)} 索引（sqlite_master 存在 + 列序正确）。 */
  @Test
  void queryLatestByCorrelationCreatesSupportingIndex() throws Exception {
    Path db = tempDir.resolve("events.db");
    try (SqliteEventStore store = SqliteEventStore.open(db)) {
      store.append(EventWrite.of("t", "main", "{}", "c"));
    }
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var ps =
            c.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?")) {
      ps.setString(1, "idx_events_type_correlation_id_seq");
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
      }
    }
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var stmt = c.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA index_info(idx_events_type_correlation_id_seq)")) {
      List<String> columns = new ArrayList<>();
      while (rs.next()) {
        columns.add(rs.getString("name"));
      }
      assertThat(columns).containsExactly("type", "correlation_id", "seq");
    }
  }

  /** R9：limit 必须为正、offset 不能为负、type 不能为 null（与 EventQuery 构造器同样严格）。 */
  @Test
  void queryLatestByCorrelationValidatesArgs() {
    try (SqliteEventStore store = SqliteEventStore.open(tempDir.resolve("events.db"))) {
      assertThatThrownBy(() -> store.queryLatestByCorrelation("t", 0, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("limit");
      assertThatThrownBy(() -> store.queryLatestByCorrelation("t", 1, -1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("offset");
      org.junit.jupiter.api.Assertions.assertThrows(
          NullPointerException.class, () -> store.queryLatestByCorrelation(null, 1, 0));
    }
  }
}
