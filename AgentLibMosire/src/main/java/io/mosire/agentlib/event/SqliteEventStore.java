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
import java.util.Objects;
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

  /**
   * R9：{@code queryLatestByCorrelation} 的分组索引——(type, correlation_id, seq) 恰好覆盖"按类型分组取 MAX(seq)"。
   */
  private static final String LATEST_BY_CORRELATION_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_events_type_correlation_id_seq "
          + "ON events (type, correlation_id, seq)";

  private static final String WAL_INSERT =
      "INSERT INTO events (ts, type, agent, payload, correlation_id) VALUES (?, ?, ?, ?, ?)";
  private static final String SELECT_BY_SEQ =
      "SELECT seq, ts, type, agent, payload, correlation_id FROM events WHERE seq = ?";
  private static final String COUNT = "SELECT COUNT(*) FROM events";
  private static final String LATEST_BY_CORRELATION =
      """
      SELECT e.seq, e.ts, e.type, e.agent, e.payload, e.correlation_id
      FROM events e
        JOIN (
          SELECT correlation_id, MAX(seq) AS max_seq
          FROM events
          WHERE type = ?
          GROUP BY correlation_id
        ) latest
          ON latest.correlation_id = e.correlation_id
         AND latest.max_seq = e.seq
      WHERE e.type = ?
      ORDER BY e.seq DESC
      LIMIT ?
      OFFSET ?
      """;

  private final Connection connection;
  private final Path dbFile;
  private final Object lock = new Object();

  /** 文件路径：打开（建目录、设 PRAGMA、建表）。 */
  public static SqliteEventStore open(Path dbFile) {
    // 声明在 try 之外：initialize 失败时 catch 里要回收它（任务 #32）。getConnection 自身失败时仍为 null。
    Connection connection = null;
    try {
      Path parent = dbFile.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
      SqliteEventStore store = new SqliteEventStore(connection, dbFile);
      store.initialize();
      return store;
    } catch (IOException e) {
      // 建目录失败必然在 getConnection 之前：此路径上没有连接可回收
      throw new UncheckedIOException("无法创建事件存储目录: " + dbFile, e);
    } catch (SQLException e) {
      // initialize（PRAGMA/DDL）失败时连接已建立且处于半初始化态：直接关 Connection，而不是调本类 close()——
      // close() 先 wal_checkpoint 再 connection.close()（两者同一个 try），checkpoint 一抛错就跳过 close（泄漏照旧），
      // 且会用"关闭事件存储失败"顶掉这里的原异常
      closeSilently(connection);
      throw new IllegalStateException("打开 SQLite 事件存储失败: " + dbFile, e);
    }
  }

  /** 失败路径回收：清理自身失败不得顶掉调用方正上抛的异常（原异常原样上抛，类型/文案/cause 链不变）。 */
  private static void closeSilently(Connection connection) {
    if (connection == null) {
      return; // getConnection 自身就失败（如目标路径不可写）：没有连接可回收
    }
    try {
      connection.close();
    } catch (SQLException ignored) {
      // 刻意静默：半初始化的连接已无补救手段，此处再抛只会替换掉"打开…失败"这个根因
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
      // 幂等：IF NOT EXISTS——旧库打开时自动补建（新库建表后同语句即可）
      statement.execute(LATEST_BY_CORRELATION_INDEX);
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
  public List<Event> queryLatestByCorrelation(String type, int limit, int offset) {
    Objects.requireNonNull(type, "type");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit 必须为正: " + limit);
    }
    if (offset < 0) {
      throw new IllegalArgumentException("offset 不能为负: " + offset);
    }
    synchronized (lock) {
      try (var ps = connection.prepareStatement(LATEST_BY_CORRELATION)) {
        ps.setString(1, type);
        ps.setString(2, type);
        ps.setInt(3, limit);
        ps.setInt(4, offset);
        try (ResultSet rs = ps.executeQuery()) {
          List<Event> events = new ArrayList<>();
          while (rs.next()) {
            events.add(map(rs));
          }
          return List.copyOf(events);
        }
      } catch (SQLException e) {
        throw new IllegalStateException("查询最新分组事件失败 type=" + type, e);
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
