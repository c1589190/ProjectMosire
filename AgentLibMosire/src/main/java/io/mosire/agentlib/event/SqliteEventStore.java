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

  /** 只读打开时实际走通的那条路（审计/测试用；写路径恒为 {@code null}）。 */
  private final ReadOnlyMode readOnlyMode;

  /**
   * 只读连接实际使用的只读手段——<b>不是配置项，是事实记录</b>（D30 要求注释/审计写明"实际用的是哪一种"）。
   *
   * <ul>
   *   <li>{@link #URI_RO}：{@code jdbc:sqlite:file:<绝对路径>?mode=ro}——SQLite 自己把库按只读打开， 写入/DDL
   *       在任何层面都被拒；
   *   <li>{@link #QUERY_ONLY}：退化路径——普通连接 + {@code PRAGMA query_only = 1}（SQL 层拒写； 但连接本身仍可让 SQLite 做
   *       WAL 的 -shm/-wal 文件级簿记，见 {@link #openReadOnly}）。
   * </ul>
   */
  public enum ReadOnlyMode {
    URI_RO,
    QUERY_ONLY
  }

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
      SqliteEventStore store = new SqliteEventStore(connection, dbFile, null);
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

  /**
   * <b>只读打开</b>（D30 读侧：父进程按 {@code <dataDir>/subagents/<instanceId>/events.db} 读子体的上下文）。
   *
   * <p>三条硬约束与各自的手段：
   *
   * <ol>
   *   <li><b>绝不写</b>：不建表、不迁移、不 INSERT/UPDATE——本路径<b>不调用</b> {@link #initialize()}（它含 {@code PRAGMA
   *       journal_mode = WAL} 与两条 DDL），也不用 {@link #append}；写入会被 SQLite 直接拒绝；
   *   <li><b>必须能读"正被别的进程写"的库</b>（子 Agent 还活着、WAL 在写）：先用 {@code jdbc:sqlite:file:<绝对路径>?mode=ro}；WAL
   *       库用只读连接打开时 SQLite 可能需要读写 {@code -shm} 才能建立读快照，若该形态打不开（{@code SQLITE_CANTOPEN} / {@code
   *       SQLITE_READONLY_CANTINIT} 等）， 退化到普通连接 + {@code PRAGMA query_only = 1}；
   *   <li><b>库不存在 → 响亮报错</b>：抛 {@link IllegalStateException}，绝不静默返回空结果（"读不到"与"读到空"是
   *       两件事，混起来会把"库没落成"伪装成"子体没干活"）。
   * </ol>
   *
   * <p><b>实际用的是哪一条</b>：见 {@link #readOnlyMode()}（{@link ReadOnlyMode}）——本方法只负责"能只读就用
   * 只读"，事实记在实例上供审计与判别性用例读取，不做猜测。
   *
   * <p><b>退化路径的诚实口径</b>：{@code PRAGMA query_only = 1} 拦的是 <b>SQL 层</b>的写（INSERT/UPDATE/DDL 直接 抛
   * {@code SQLITE_READONLY}），但普通连接打开 WAL 库时 SQLite 自身仍可能创建/更新 {@code -shm}、 或在无 WAL
   * 的库上做恢复性簿记——这是文件级副作用，不改变 {@code events} 表的任何一行。要求"连文件都不碰"就得让 {@code mode=ro} 必然可用（即子体侧保证 -shm
   * 可读），那是另一件事；本类把差异如实记在 {@link ReadOnlyMode} 上。
   */
  public static SqliteEventStore openReadOnly(Path dbFile) {
    return openReadOnly(dbFile, true);
  }

  /**
   * 只读打开（{@code preferUriMode = false} 时跳过 {@code mode=ro} 直接走退化路径）。
   *
   * <p>包内可见的<b>测试缝</b>：退化路径的现实触发条件是"WAL 的 {@code -shm} 打不开"，靠文件权限构造不稳定（本仓以 root
   * 跑测试，权限位对它无效），故用本参数<b>判别性地</b>验证退化路径本身可用且同样只读。
   */
  static SqliteEventStore openReadOnly(Path dbFile, boolean preferUriMode) {
    Objects.requireNonNull(dbFile, "dbFile");
    if (!Files.isRegularFile(dbFile)) {
      throw new IllegalStateException("只读打开事件库失败：库文件不存在（只读路径不创建库）: " + dbFile.toAbsolutePath());
    }
    if (!preferUriMode) {
      return queryOnlyStore(dbFile, null);
    }
    try {
      return uriReadOnlyStore(dbFile);
    } catch (SQLException uriFailure) {
      return queryOnlyStore(dbFile, uriFailure);
    }
  }

  /** 只读手段之一：URI 形态 {@code mode=ro}（库文件由 SQLite 按只读打开）。 */
  private static SqliteEventStore uriReadOnlyStore(Path dbFile) throws SQLException {
    // 用原始绝对路径拼接（不能用 Path.toUri()：它会带出 "file:///" 前缀，与 jdbc:sqlite: 的 "file:" 叠加成
    // "file:file:///…"，SQLite 直接 SQLITE_CANTOPEN——2026-09-12 实测）
    Connection readOnly =
        DriverManager.getConnection("jdbc:sqlite:file:" + dbFile.toAbsolutePath() + "?mode=ro");
    return new SqliteEventStore(readOnly, dbFile, ReadOnlyMode.URI_RO);
  }

  /**
   * 只读手段之二（退化）：普通连接 + {@code PRAGMA query_only = 1}。打开或设 PRAGMA 失败则响亮度错——原 {@code uriFailure} 挂在
   * suppressed 上，两条失败线索都不丢。
   */
  private static SqliteEventStore queryOnlyStore(Path dbFile, SQLException uriFailure) {
    Connection connection = null;
    try {
      connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA query_only = 1");
      }
      return new SqliteEventStore(connection, dbFile, ReadOnlyMode.QUERY_ONLY);
    } catch (SQLException e) {
      if (uriFailure != null) {
        e.addSuppressed(uriFailure);
      }
      closeSilently(connection);
      throw new IllegalStateException(
          "只读打开 SQLite 事件库失败（mode=ro 与 query_only 两条路均不可用）: " + dbFile, e);
    }
  }

  private SqliteEventStore(Connection connection, Path dbFile, ReadOnlyMode readOnlyMode) {
    this.connection = connection;
    this.dbFile = dbFile;
    this.readOnlyMode = readOnlyMode;
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
      if (readOnlyMode != null) {
        // 显式拒绝而非"让 SQLite 报错"：只读 store 被拿去写是调用方接错线（读错对象/拿错句柄），
        // 消息要指出这一点，别让上层从 SQLITE_READONLY 里猜
        throw new IllegalStateException("只读事件存储不允许追加事件（" + readOnlyMode + "）: " + write.type());
      }
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
        if (readOnlyMode == null) {
          // 收尾 checkpoint：把 WAL 内容刷回主库文件，进程退出后不留 wal 文件
          // （只读路径绝不做——TRUNCATE checkpoint 会写主库文件，正是"只读"要禁的事）
          try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
          }
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

  /** 本次打开实际使用的只读手段；写路径（{@link #open}）恒为 {@code null}。审计与判别性用例据此断言"走的是哪条路"， 而不是从代码里猜。 */
  public ReadOnlyMode readOnlyMode() {
    return readOnlyMode;
  }
}
