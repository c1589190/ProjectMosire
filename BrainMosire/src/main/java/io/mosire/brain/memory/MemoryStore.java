package io.mosire.brain.memory;

import io.mosire.agentlib.permission.AccessToken;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 记忆存储：计划 §六 数据模型中 {@code memory} + {@code memory_fts} 两张表的 SQLite 实现。
 *
 * <p>与 {@code SqliteEventStore} <b>同库不同连接</b>：计划 §六 把记忆表与事件表列在同一份 DDL 里（同一个库文件），
 * 但两类表的读写节奏与生命周期不同，因此本类自持连接、自建表——两连接靠 WAL + {@code busy_timeout=5000} 协同（SQLite
 * 同库多连接的标准做法，非本类引入的新约定）。
 *
 * <p>并发模型照事件存储：单连接 + 私有锁串行化。P0 规模（每次 remember 一条、检索走索引）下足够； 锁用私有对象而非 {@code synchronized} 方法修饰符的理由同
 * {@code SqliteEventStore}（避免外部持锁者干扰内部互斥）。
 *
 * <p>本类只做存储与匹配；"该不该给这个调用者看"的过滤条件由 {@link MemoryRetriever} 换算成身份集合传入， 但过滤执行位置在本类的 SQL 内（必须在 LIMIT
 * 之前生效，否则越权行会白占 K 槽位）。
 */
public final class MemoryStore implements AutoCloseable {

  /**
   * 记忆主表：正文 + 两条元数据维度。
   *
   * <p>{@code required_token} 存 {@code AccessToken} 名（权限模型的身份维度，写入者等级即该行的最低可读等级）； {@code scope} 存
   * {@code AgentSpec.memoryScope} 词表（user/project/local）。两者都用文本存语义名而非序号： 枚举序号会在插入新常量的瞬间失效，且库内容人工可读。
   */
  private static final String DDL =
      """
      CREATE TABLE IF NOT EXISTS memory (
        id             INTEGER PRIMARY KEY AUTOINCREMENT,
        ts             TEXT NOT NULL,
        topic          TEXT NOT NULL,
        text           TEXT NOT NULL,
        scope          TEXT NOT NULL,
        required_token TEXT NOT NULL
      )
      """;

  /**
   * FTS5 索引表：{@code rowid} 与 {@code memory.id} 一一对应，写入与主表同事务双写（见 {@link #remember}）。
   * 本类当前没有删除路径；将来若加删除，必须在同一事务里同时删两表，否则索引会指向已不存在的记忆。
   *
   * <p><b>CJK 红线——必须 {@code tokenize='trigram'}，不能用 FTS5 默认的 unicode61</b>：unicode61 按空白/标点
   * 切词，一段无空格中文整段算 <b>一个 token</b>，句内词因此永远匹配不上。本机实测对照（sqlite3 3.45，两表同插 {@code "用户喜欢用 Kafka"}）：
   *
   * <pre>
   *   fts5(tokenize='unicode61')  MATCH '"喜欢用"'  → 0 行（token 是"用户喜欢用"整段，切不出"喜欢用"）
   *   fts5(tokenize='trigram')    MATCH '"喜欢用"'  → 1 行（三字滑窗，句内子串命中）
   * </pre>
   *
   * 代价：trigram 需 ≥3 字符才能成形，1–2 字查询在本表上必然 0 行 → 由 {@link MemoryRetriever} 回退 LIKE 子串匹配。
   */
  private static final String FTS_DDL =
      """
      CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
        topic,
        text,
        tokenize='trigram'
      )
      """;

  private static final String INSERT_MEMORY =
      "INSERT INTO memory (ts, topic, text, scope, required_token) VALUES (?, ?, ?, ?, ?)";
  private static final String INSERT_FTS =
      "INSERT INTO memory_fts (rowid, topic, text) VALUES (?, ?, ?)";

  /** {@code %s} 处填身份占位符列表；{@code bm25()} 越小越相关，故升序取前 K。 */
  private static final String SELECT_BY_FTS =
      """
      SELECT m.id, m.ts, m.topic, m.text, m.scope
      FROM memory_fts
        JOIN memory m ON m.id = memory_fts.rowid
      WHERE memory_fts MATCH ?
        AND m.required_token IN (%s)
      ORDER BY bm25(memory_fts)
      LIMIT ?
      """;

  /** 短查询回退路径：无索引可用，按写入倒序（越新越可能相关）取前 K。 */
  private static final String SELECT_BY_LIKE =
      """
      SELECT m.id, m.ts, m.topic, m.text, m.scope
      FROM memory m
      WHERE m.required_token IN (%s)
        AND (m.topic LIKE ? ESCAPE '\\' OR m.text LIKE ? ESCAPE '\\')
      ORDER BY m.id DESC
      LIMIT ?
      """;

  private final Connection connection;
  private final Path dbFile;
  private final Object lock = new Object();

  /** 文件路径：打开（建目录、设 PRAGMA、建表）。同一路径应传给事件存储与记忆存储（计划 §六 同库）。 */
  public static MemoryStore open(Path dbFile) {
    try {
      Path parent = dbFile.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
      MemoryStore store = new MemoryStore(connection, dbFile);
      store.initialize();
      return store;
    } catch (IOException e) {
      throw new UncheckedIOException("无法创建记忆存储目录: " + dbFile, e);
    } catch (SQLException e) {
      throw new IllegalStateException("打开 SQLite 记忆存储失败: " + dbFile, e);
    }
  }

  private MemoryStore(Connection connection, Path dbFile) {
    this.connection = connection;
    this.dbFile = dbFile;
  }

  private void initialize() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA journal_mode = WAL");
      statement.execute("PRAGMA busy_timeout = 5000");
      statement.execute(DDL);
      statement.execute(FTS_DDL);
    }
  }

  /**
   * 写入一条记忆（主表行 + FTS 索引行，同事务——只落一半会让记忆"存在但检索不到"）。
   *
   * @param topic 主题短标签（与正文一同参与检索）
   * @param text 正文
   * @param scope user/project/local（词表同 {@code AgentSpec.memoryScope}）
   * @param requiredToken 写入者身份等级：检索时只有"至少该等级"的调用者可见
   * @return 新记忆的行 id
   */
  public long remember(String topic, String text, String scope, AccessToken requiredToken) {
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(requiredToken, "requiredToken");
    String ts = Instant.now().toString();
    synchronized (lock) {
      try {
        connection.setAutoCommit(false);
        long id;
        try {
          id = insertMemory(ts, topic, text, scope, requiredToken);
          insertFts(id, topic, text);
          connection.commit();
        } catch (SQLException | RuntimeException e) {
          // 运行期异常也要回滚：否则 finally 的 setAutoCommit(true) 会按 JDBC 语义
          // 隐式提交这半截事务（如主表已插、FTS 未插），双写原子性就破了
          rollbackAfterFailure(e);
          throw e;
        } finally {
          connection.setAutoCommit(true);
        }
        return id;
      } catch (SQLException e) {
        throw new IllegalStateException("写入记忆失败: " + topic, e);
      }
    }
  }

  /**
   * FTS5 子串匹配（trigram）：查询串整体作为一个 phrase，内部双引号翻倍转义——避免用户输入里的 {@code *} {@code (} {@code -} 等被当成 FTS5
   * 查询语法。
   *
   * @param visibleTokens 可见身份集合（{@code required_token} 命中其一即可见）
   */
  List<MemoryRecord> searchByFts(String query, List<AccessToken> visibleTokens, int k) {
    String phrase = "\"" + query.replace("\"", "\"\"") + "\"";
    String sql = SELECT_BY_FTS.formatted(placeholders(visibleTokens.size()));
    synchronized (lock) {
      try (PreparedStatement ps = connection.prepareStatement(sql)) {
        int index = 1;
        ps.setString(index++, phrase);
        for (AccessToken token : visibleTokens) {
          ps.setString(index++, token.name());
        }
        ps.setInt(index, k);
        return collect(ps);
      } catch (SQLException e) {
        throw new IllegalStateException("FTS 检索记忆失败: " + query, e);
      }
    }
  }

  /** 短查询回退：{@code LIKE '%term%'} 子串匹配（trigram 无法处理 <3 字符查询），语义与 trigram 一致。 */
  List<MemoryRecord> searchByLike(String query, List<AccessToken> visibleTokens, int k) {
    String pattern = "%" + escapeLike(query) + "%";
    String sql = SELECT_BY_LIKE.formatted(placeholders(visibleTokens.size()));
    synchronized (lock) {
      try (PreparedStatement ps = connection.prepareStatement(sql)) {
        int index = 1;
        for (AccessToken token : visibleTokens) {
          ps.setString(index++, token.name());
        }
        ps.setString(index++, pattern);
        ps.setString(index++, pattern);
        ps.setInt(index, k);
        return collect(ps);
      } catch (SQLException e) {
        throw new IllegalStateException("LIKE 检索记忆失败: " + query, e);
      }
    }
  }

  private long insertMemory(
      String ts, String topic, String text, String scope, AccessToken requiredToken)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(INSERT_MEMORY, Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, ts);
      ps.setString(2, topic);
      ps.setString(3, text);
      ps.setString(4, scope);
      ps.setString(5, requiredToken.name());
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        if (!keys.next()) {
          throw new IllegalStateException("写入记忆未返回生成键: " + topic);
        }
        return keys.getLong(1);
      }
    }
  }

  private void insertFts(long rowId, String topic, String text) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(INSERT_FTS)) {
      ps.setLong(1, rowId);
      ps.setString(2, topic);
      ps.setString(3, text);
      ps.executeUpdate();
    }
  }

  /** 失败回滚。回滚自身再失败时挂到原异常（suppressed）而不替换它：首个失败才是根因， 第二处失败是后果——丢掉首因会让排查反向。 */
  private void rollbackAfterFailure(Throwable cause) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      cause.addSuppressed(rollbackFailure);
    }
  }

  private static List<MemoryRecord> collect(PreparedStatement ps) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      List<MemoryRecord> records = new ArrayList<>();
      while (rs.next()) {
        records.add(
            new MemoryRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("text"),
                rs.getString("scope"),
                Instant.parse(rs.getString("ts"))));
      }
      return List.copyOf(records);
    }
  }

  /** IN 列表的占位符串（数量随可见身份数变化，故 SQL 模板留 {@code %s}）。 */
  private static String placeholders(int count) {
    return String.join(", ", Collections.nCopies(count, "?"));
  }

  /** LIKE 元字符转义（配 SQL 的 {@code ESCAPE '\'}）：{@code \ % _} 按字面匹配，用户输入不致扩大匹配面。 */
  private static String escapeLike(String raw) {
    StringBuilder escaped = new StringBuilder(raw.length() + 8);
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '\\' || c == '%' || c == '_') {
        escaped.append('\\');
      }
      escaped.append(c);
    }
    return escaped.toString();
  }

  @Override
  public void close() {
    synchronized (lock) {
      try {
        // 收尾 checkpoint：把 WAL 内容刷回主库文件，进程退出后不留 wal 文件（同事件存储）
        try (Statement statement = connection.createStatement()) {
          statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        connection.close();
      } catch (SQLException e) {
        throw new IllegalStateException("关闭记忆存储失败: " + dbFile, e);
      }
    }
  }

  public Path dbFile() {
    return dbFile;
  }
}
