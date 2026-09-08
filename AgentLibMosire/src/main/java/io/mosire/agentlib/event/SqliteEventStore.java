package io.mosire.agentlib.event;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 事件存储实现（WAL + busy_timeout；单连接同步串行化写）。
 *
 * <p>并发模型：一个连接 + 全部访问走同一把锁——P0 规模（单进程、事件写入频率为"每步一次" 级别）下单连接足够且语义最简单；未来若写成为瓶颈再升级连接池/queue（记录于
 * 开发计划.md §九）。
 *
 * <p>锁用私有对象而非 {@code synchronized} 方法修饰符：实例经由静态工厂 {@link #open} 暴露， 用类自带锁会让外部持锁者干扰内部互斥（SpotBugs
 * USO_UNSAFE_METHOD_SYNCHRONIZATION）。
 */
public final class SqliteEventStore implements EventStore {

  /** 计划 D8：WAL + busy_timeout=5000。 */
  private static final String DDL =
      """
      CREATE TABLE IF NOT EXISTS events (
        seq            INTEGER PRIMARY KEY AUTOINCREMENT,
        ts             TEXT    NOT NULL,
        type           TEXT    NOT NULL,
        agent          TEXT    NOT NULL,
        payload        TEXT    NOT NULL DEFAULT '',
        correlation_id TEXT    NOT NULL DEFAULT ''
      )
      """;

  private static final String WAL_INSERT =
      "INSERT INTO events (ts, type, agent, payload, correlation_id) VALUES (?, ?, ?, ?, ?)";
  private static final String SELECT_BY_SEQ =
      "SELECT seq, ts, type, agent, payload, correlation_id FROM events WHERE seq = ?";
  private static final String COUNT = "SELECT COUNT(*) FROM events";

  private final Connection connection;
  private final Path dbFile;
  private final Object lock = new Object();

  /** 文件路径：打开（建目录、设 PRAGMA、建表）。 */
  public static SqliteEventStore open(Path dbFile) {
    try {
      Path parent = dbFile.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
      SqliteEventStore store = new SqliteEventStore(connection, dbFile);
      store.initialize();
      return store;
    } catch (IOException e) {
      throw new UncheckedIOException("无法创建事件存储目录: " + dbFile, e);
    } catch (SQLException e) {
      throw new IllegalStateException("打开 SQLite 事件存储失败: " + dbFile, e);
    }
  }

  private SqliteEventStore(Connection connection, Path dbFile) {
    this.connection = connection;
    this.dbFile = dbFile;
  }

  private void initialize() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA journal_mode = WAL");
      statement.execute("PRAGMA busy_timeout = 5000");
      statement.execute(DDL);
    }
  }

  @Override
  public Event append(EventWrite write) {
    synchronized (lock) {
      String ts = Instant.now().toString();
      try (var ps = connection.prepareStatement(WAL_INSERT, Statement.RETURN_GENERATED_KEYS)) {
        ps.setString(1, ts);
        ps.setString(2, write.type());
        ps.setString(3, write.agent());
        ps.setString(4, write.payload());
        ps.setString(5, write.correlationId());
        ps.executeUpdate();
        long seq;
        try (ResultSet keys = ps.getGeneratedKeys()) {
          if (!keys.next()) {
            throw new IllegalStateException("写入未返回生成键: " + write.type());
          }
          seq = keys.getLong(1);
        }
        return new Event(
            seq,
            Instant.parse(ts),
            write.type(),
            write.agent(),
            write.payload(),
            write.correlationId());
      } catch (SQLException e) {
        throw new IllegalStateException("追加事件失败: " + write.type(), e);
      }
    }
  }

  @Override
  public List<Event> query(EventQuery query) {
    synchronized (lock) {
      StringBuilder sql =
          new StringBuilder(
              "SELECT seq, ts, type, agent, payload, correlation_id FROM events WHERE 1=1");
      List<String> params = new ArrayList<>();
      if (!query.agent().isEmpty()) {
        sql.append(" AND agent = ?");
        params.add(query.agent());
      }
      if (!query.type().isEmpty()) {
        sql.append(" AND type = ?");
        params.add(query.type());
      }
      if (!query.correlationId().isEmpty()) {
        sql.append(" AND correlation_id = ?");
        params.add(query.correlationId());
      }
      if (query.beforeSeq() >= 0) {
        sql.append(" AND seq < ?");
        params.add(String.valueOf(query.beforeSeq()));
      }
      sql.append(" ORDER BY seq DESC LIMIT ?");
      params.add(String.valueOf(query.limit()));

      try (var ps = connection.prepareStatement(sql.toString())) {
        for (int i = 0; i < params.size(); i++) {
          ps.setString(i + 1, params.get(i));
        }
        try (ResultSet rs = ps.executeQuery()) {
          List<Event> events = new ArrayList<>();
          while (rs.next()) {
            events.add(map(rs));
          }
          return List.copyOf(events);
        }
      } catch (SQLException e) {
        throw new IllegalStateException("查询事件失败", e);
      }
    }
  }

  @Override
  public Optional<Event> bySeq(long seq) {
    synchronized (lock) {
      try (var ps = connection.prepareStatement(SELECT_BY_SEQ)) {
        ps.setLong(1, seq);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        }
      } catch (SQLException e) {
        throw new IllegalStateException("查询事件失败 seq=" + seq, e);
      }
    }
  }

  @Override
  public long count() {
    synchronized (lock) {
      try (var ps = connection.prepareStatement(COUNT);
          ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      } catch (SQLException e) {
        throw new IllegalStateException("统计事件失败", e);
      }
    }
  }

  private static Event map(ResultSet rs) throws SQLException {
    return new Event(
        rs.getLong("seq"),
        Instant.parse(rs.getString("ts")),
        rs.getString("type"),
        rs.getString("agent"),
        rs.getString("payload"),
        rs.getString("correlation_id"));
  }

  @Override
  public void close() {
    synchronized (lock) {
      try {
        // 收尾 checkpoint：把 WAL 内容刷回主库文件，进程退出后不留 wal 文件
        try (Statement statement = connection.createStatement()) {
          statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        connection.close();
      } catch (SQLException e) {
        throw new IllegalStateException("关闭事件存储失败: " + dbFile, e);
      }
    }
  }

  public Path dbFile() {
    return dbFile;
  }
}
