package io.mosire.agentlib.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D30 只读打开（{@link SqliteEventStore#openReadOnly}）的判别性用例：<b>绝不写</b>（不建表/不迁移/不 INSERT）、 <b>能读正被别的连接写的
 * WAL 库</b>、<b>库不存在响亮报错</b>。
 *
 * <p>本类不断言"只读实现内部长什么样"，只断言可观测事实：库里多没多出对象、读不读得到、写不写得进去、走的哪条路 （{@link
 * SqliteEventStore#readOnlyMode()}）。
 */
class SqliteEventStoreReadOnlyTest {

  @TempDir Path tempDir;

  /** 手工造一个"只有 events 表、没有索引"的库——用来判别只读路径有没有偷偷跑 DDL/迁移。 */
  private Path minimalDb() throws SQLException {
    Path db = tempDir.resolve("minimal.db");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE events (
            seq            INTEGER PRIMARY KEY AUTOINCREMENT,
            ts             TEXT    NOT NULL,
            type           TEXT    NOT NULL,
            agent          TEXT    NOT NULL,
            payload        TEXT    NOT NULL DEFAULT '',
            correlation_id TEXT    NOT NULL DEFAULT ''
          )
          """);
      statement.execute(
          "INSERT INTO events (ts, type, agent, payload, correlation_id) "
              + "VALUES ('2026-09-12T00:00:00Z', 'conversation.turn', 'child-1', '{\"output\":\"原文\"}', 'child-1')");
    }
    return db;
  }

  /** sqlite_master 的全部对象名（schema 是否被动过的唯一事实来源）。 */
  private static List<String> schemaObjects(Path db) throws SQLException {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT type || ':' || name FROM sqlite_master")) {
      List<String> objects = new java.util.ArrayList<>();
      while (rs.next()) {
        objects.add(rs.getString(1));
      }
      return objects;
    }
  }

  @Test
  void readsLiveWalWriterDataWithoutWritingAnything() throws Exception {
    Path db = tempDir.resolve("events.db");
    SqliteEventStore writer = SqliteEventStore.open(db);
    try {
      writer.append(
          EventWrite.of("conversation.turn", "child-1", "{\"output\":\"第一段\"}", "child-1"));
      List<String> schemaBefore = schemaObjects(db);

      try (SqliteEventStore reader = SqliteEventStore.openReadOnly(db)) {
        // 走的哪条路：不是配置项，是事实——用例把它固定下来，报告才有据可依
        assertThat(reader.readOnlyMode())
            .isIn(SqliteEventStore.ReadOnlyMode.URI_RO, SqliteEventStore.ReadOnlyMode.QUERY_ONLY);
        assertThat(reader.query(new EventQuery("child-1", "", "", -1, 10)))
            .hasSize(1)
            .allSatisfy(event -> assertThat(event.payload()).contains("第一段"));

        // 写侧活着、继续写：读侧不阻塞它（同一时刻 writer 未被读事务卡死）
        writer.append(
            EventWrite.of("conversation.turn", "child-1", "{\"output\":\"第二段\"}", "child-1"));
        // 读侧能读到新提交的数据（WAL 下读快照随每次查询刷新）
        assertThat(reader.query(new EventQuery("child-1", "", "", -1, 10))).hasSize(2);

        // 读侧写：直接拒绝（不是"让 SQLite 报错"）
        assertThatThrownBy(() -> reader.append(EventWrite.of("t", "child-1")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("只读");
      }

      // 只读路径没写任何东西：schema 逐字未变（若它跑了 initialize()，会多出 idx_events_type_correlation_id_seq）
      assertThat(schemaObjects(db)).isEqualTo(schemaBefore);
    } finally {
      writer.close();
    }
  }

  @Test
  void readonlyOpenDoesNotRunSchemaInitializationOnBareDb() throws Exception {
    Path db = minimalDb();
    List<String> before = schemaObjects(db);

    try (SqliteEventStore reader = SqliteEventStore.openReadOnly(db)) {
      assertThat(reader.query(new EventQuery("child-1", "", "", -1, 10))).hasSize(1);
    }

    // 判别性核心：普通连接（退化路径）本来有写权限，若只读路径误跑了 DDL，这里就会多出索引
    assertThat(schemaObjects(db))
        .isEqualTo(before)
        .doesNotContain("index:idx_events_type_correlation_id_seq");
  }

  @Test
  void fallbackPathReadsAndStaysReadOnly() throws Exception {
    Path db = minimalDb();
    List<String> before = schemaObjects(db);

    // preferUriMode=false：判别性地验证退化路径（普通连接 + PRAGMA query_only=1）本身可用且同样只读
    try (SqliteEventStore reader = SqliteEventStore.openReadOnly(db, false)) {
      assertThat(reader.readOnlyMode()).isEqualTo(SqliteEventStore.ReadOnlyMode.QUERY_ONLY);
      assertThat(reader.query(new EventQuery("child-1", "", "", -1, 10))).hasSize(1);
      assertThatThrownBy(() -> reader.append(EventWrite.of("t", "child-1")))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThat(schemaObjects(db)).isEqualTo(before);
  }

  @Test
  void missingDbFailsLoudly() {
    Path absent = tempDir.resolve("subagents").resolve("ghost-1").resolve("events.db");
    assertThatThrownBy(() -> SqliteEventStore.openReadOnly(absent))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不存在");
    // 响亮 = 真的什么都没建（不把"读不到"悄悄变成"空库"）
    assertThat(Files.exists(absent.getParent())).isFalse();
  }

  @Test
  void directoryInsteadOfFileAlsoFailsLoudly() throws Exception {
    Path dir = Files.createDirectories(tempDir.resolve("not-a-db"));
    assertThatThrownBy(() -> SqliteEventStore.openReadOnly(dir))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不存在");
  }
}
