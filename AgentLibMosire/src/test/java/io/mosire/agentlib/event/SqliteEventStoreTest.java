package io.mosire.agentlib.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
}
