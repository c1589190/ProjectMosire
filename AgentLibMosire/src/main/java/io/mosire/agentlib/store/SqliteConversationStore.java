package io.mosire.agentlib.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 会话存储的 SQLite 实现：{@code conversations} + {@code messages} 两表，与事件库<b>同一文件</b>（计划 §六 一份 DDL）、
 * <b>自建连接</b>（不改 {@link io.mosire.agentlib.event.SqliteEventStore}）。
 *
 * <p>连接写法照 {@code SqliteEventStore}/{@code MemoryStore}：{@code PRAGMA journal_mode = WAL} + {@code
 * busy_timeout = 5000} + {@code CREATE TABLE IF NOT EXISTS}（旧库打开即自动补建，重复打开幂等）； 并发模型 = 单连接 +
 * 私有锁串行化（锁用私有对象而非 {@code synchronized} 方法修饰符，理由同 {@code SqliteEventStore}）。
 *
 * <p><b>append-only 落在两个层次</b>：
 *
 * <ul>
 *   <li>{@code messages.id} 为 {@code INTEGER PRIMARY KEY AUTOINCREMENT}——插入即定序，且 SQLite 不复用已删
 *       id：行标识就是"这条消息是何时被追加的"的不可伪造证据，也是 prompt-cache 前缀稳定的抓手；
 *   <li>{@link #append} 只写一条 INSERT，不做任何 UPDATE/DELETE——已落库的行永不改写。
 * </ul>
 *
 * <p><b>压缩</b>：{@code conversations.compacted_thru_id} 是压缩点（压缩当时会话的最大行 id），{@code summary}
 * 是摘要正文；两者只在 {@link #compact} 里被写。压缩点之前的 {@code messages} 行<b>不删除</b>（留档可审计），只是不再被 {@link #load}
 * 返回。摘要以 {@code role = assistant} 的单文本消息形态回灌，且置于 {@link #load} 结果的首位（它本就是模型自己对压缩前对话的记忆替身）。
 *
 * <p><b>本类不承诺"首条非 system 消息为 user"，也不承诺相邻 role 交替</b>：摘要的 role 恒为 {@code assistant}，于是它一旦存在就是
 * {@link #load} 的首条——首条非 system 消息即 assistant；压缩点又可能落在回合中段，紧随摘要的也可能是 assistant/tool。 Anthropic
 * 类"首个非 system 消息必须是 user"的严格供应商因此会拒收这种形状。这是刻意的取舍（改成 {@code user} 只会把风险从"首条非 user"换成"可能连续两条
 * user"，更差）；摘要在请求里的最终形态（例如合并进 system 段）属于后续压缩接线的设计空间，本类只负责按上述形态存取。
 *
 * <p><b>消息内容编码</b>（内部格式，随本类演进）：一条消息 = 一行，{@code role} 单列 + {@code content} 列存 {@code {role,
 * parts[], reasoning?}} 的 JSON（{@code parts} 里每个分片带 {@code type} 判别符：{@code text}/{@code
 * tool_call}/{@code tool_result}/{@code image}）。手写这层判别符而不用 Jackson 的类型元信息，是为了让格式显式、可读、不依赖实体类上的注解。
 * {@code reasoning}（A6 的思维链）<b>在“字段存在”时写</b>：有正文写原文，显式空串也写空串，字段压根缺席才不写；缺键按 {@code ABSENT}
 * 读——改版前的行因此照常可读（见 {@link #encode}/{@link #decodeReasoning}）。
 *
 * <p>表在会话 id 上无外键约束：会话行由首次 {@code append}/{@code compact} 自动建（{@code INSERT OR IGNORE}），
 * 因此本类不需要调用方先"创建会话"。
 */
public final class SqliteConversationStore implements ConversationStore, AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** {@code tool_call} 的参数容器类型（JSON 对象 → {@code Map<String, Object>}）。 */
  private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {};

  private static final String FIELD_ROLE = "role";
  private static final String FIELD_PARTS = "parts";

  /** 思维链（A6 修复版）：字段存在就写出（含显式空串），缺席才省略，见 {@link #encode}。 */
  private static final String FIELD_REASONING = "reasoning";

  private static final String FIELD_TYPE = "type";
  private static final String FIELD_TEXT = "text";
  private static final String FIELD_ID = "id";
  private static final String FIELD_NAME = "name";
  private static final String FIELD_ARGUMENTS = "arguments";
  private static final String FIELD_TOOL_CALL_ID = "toolCallId";
  private static final String FIELD_CONTENT = "content";
  private static final String FIELD_ERROR = "error";

  /** 图片分片（{@link ContentPart.Image}）：媒体类型 + 资产引用——**字节不落库**（见 Image 的类注）。 */
  private static final String FIELD_MEDIA_TYPE = "mediaType";

  private static final String FIELD_ASSET_ID = "assetId";

  private static final String TYPE_TEXT = "text";
  private static final String TYPE_TOOL_CALL = "tool_call";
  private static final String TYPE_TOOL_RESULT = "tool_result";
  private static final String TYPE_IMAGE = "image";

  private static final String CONVERSATIONS_DDL =
      """
      CREATE TABLE IF NOT EXISTS conversations (
        id                TEXT    PRIMARY KEY,
        created_ts        TEXT    NOT NULL,
        updated_ts        TEXT    NOT NULL,
        summary           TEXT    NOT NULL DEFAULT '',
        compacted_thru_id INTEGER NOT NULL DEFAULT 0
      )
      """;

  private static final String MESSAGES_DDL =
      """
      CREATE TABLE IF NOT EXISTS messages (
        id              INTEGER PRIMARY KEY AUTOINCREMENT,
        conversation_id TEXT    NOT NULL,
        role            TEXT    NOT NULL,
        content         TEXT    NOT NULL,
        ts              TEXT    NOT NULL
      )
      """;

  /** {@link #load} 的访问路径：(conversation_id, id) 恰好覆盖"按会话过滤 + 按追加序取压缩点之后"。 */
  private static final String MESSAGES_INDEX =
      "CREATE INDEX IF NOT EXISTS idx_messages_conversation_id_id ON messages (conversation_id, id)";

  /**
   * 会话指针（{@link #currentConversationId}）：key-value 单表单行，与内容同库——"重置跨进程有效"要求指针与消息在同一个原子可见面上，
   * 独立小文件会多出"两份状态谁先落地"的问题。
   */
  private static final String SESSION_STATE_DDL =
      """
      CREATE TABLE IF NOT EXISTS session_state (
        key   TEXT PRIMARY KEY,
        value TEXT NOT NULL
      )
      """;

  /** 指针的 key（单行表；将来若有别的会话级标量，同一张表另起 key）。 */
  private static final String CURRENT_CONVERSATION_KEY = "current_conversation_id";

  private static final String SELECT_CURRENT_CONVERSATION =
      "SELECT value FROM session_state WHERE key = '" + CURRENT_CONVERSATION_KEY + "'";

  private static final String UPSERT_CURRENT_CONVERSATION =
      "INSERT INTO session_state (key, value) VALUES ('"
          + CURRENT_CONVERSATION_KEY
          + "', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value";

  private static final String INSERT_CONVERSATION =
      "INSERT OR IGNORE INTO conversations (id, created_ts, updated_ts) VALUES (?, ?, ?)";
  private static final String TOUCH_CONVERSATION =
      "UPDATE conversations SET updated_ts = ? WHERE id = ?";
  private static final String INSERT_MESSAGE =
      "INSERT INTO messages (conversation_id, role, content, ts) VALUES (?, ?, ?, ?)";

  /**
   * 压缩：摘要 + 压缩点（= 当前会话最大行 id；空会话记 0）。{@code COALESCE} 兜住"一条消息都没有"的会话—— 此时压缩点是 0，{@code load} 的
   * {@code id > 0} 恒真，shape 与"尚未压缩"一致（空历史）。
   */
  private static final String COMPACT_CONVERSATION =
      """
      UPDATE conversations
      SET summary           = ?,
          compacted_thru_id = COALESCE((SELECT MAX(id) FROM messages WHERE conversation_id = ?), 0),
          updated_ts        = ?
      WHERE id = ?
      """;

  private static final String SELECT_CONVERSATION =
      "SELECT summary, compacted_thru_id FROM conversations WHERE id = ?";
  private static final String SELECT_MESSAGES =
      "SELECT role, content FROM messages WHERE conversation_id = ? AND id > ? ORDER BY id";

  private final Connection connection;
  private final Path dbFile;
  private final Object lock = new Object();

  /** 文件路径：打开（建目录、设 PRAGMA、建表）。同一路径应传给事件存储（计划 §六 同库）。 */
  public static SqliteConversationStore open(Path dbFile) {
    // 声明在 try 之外：initialize 失败时 catch 里要回收它（任务 #32）。getConnection 自身失败时仍为 null。
    Connection connection = null;
    try {
      Path parent = dbFile.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
      SqliteConversationStore store = new SqliteConversationStore(connection, dbFile);
      store.initialize();
      return store;
    } catch (IOException e) {
      // 建目录失败必然在 getConnection 之前：此路径上没有连接可回收
      throw new UncheckedIOException("无法创建会话存储目录: " + dbFile, e);
    } catch (SQLException e) {
      // initialize（PRAGMA/DDL）失败时连接已建立且处于半初始化态：直接关 Connection，而不是调本类 close()——
      // close() 先 wal_checkpoint 再 connection.close()（两者同一个 try），checkpoint 一抛错就跳过 close（泄漏照旧），
      // 且会用"关闭会话存储失败"顶掉这里的原异常
      closeSilently(connection);
      throw new IllegalStateException("打开 SQLite 会话存储失败: " + dbFile, e);
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

  private SqliteConversationStore(Connection connection, Path dbFile) {
    this.connection = connection;
    this.dbFile = dbFile;
  }

  private void initialize() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA journal_mode = WAL");
      statement.execute("PRAGMA busy_timeout = 5000");
      statement.execute(CONVERSATIONS_DDL);
      statement.execute(MESSAGES_DDL);
      statement.execute(MESSAGES_INDEX);
      statement.execute(SESSION_STATE_DDL);
    }
  }

  @Override
  public Optional<String> currentConversationId() {
    synchronized (lock) {
      try (PreparedStatement ps = connection.prepareStatement(SELECT_CURRENT_CONVERSATION);
          ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
      } catch (SQLException e) {
        throw new IllegalStateException("读取会话指针失败: " + dbFile, e);
      }
    }
  }

  @Override
  public void setCurrentConversationId(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    synchronized (lock) {
      try (PreparedStatement ps = connection.prepareStatement(UPSERT_CURRENT_CONVERSATION)) {
        ps.setString(1, conversationId);
        ps.executeUpdate();
      } catch (SQLException e) {
        throw new IllegalStateException("写入会话指针失败: " + conversationId, e);
      }
    }
  }

  @Override
  public void append(String conversationId, LlmMessage message) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(message, "message");
    String ts = Instant.now().toString();
    // 编码是纯函数且在锁外完成：缩短临界区，编码失败也不触及连接
    String content = encode(message);
    synchronized (lock) {
      try {
        connection.setAutoCommit(false);
        try {
          ensureConversation(conversationId, ts);
          insertMessage(conversationId, message.role(), content, ts);
          touchConversation(conversationId, ts);
          connection.commit();
        } catch (SQLException | RuntimeException e) {
          // 运行期异常也要回滚：否则 finally 的 setAutoCommit(true) 会按 JDBC 语义隐式提交半截事务
          rollbackAfterFailure(e);
          throw e;
        } finally {
          connection.setAutoCommit(true);
        }
      } catch (SQLException e) {
        throw new IllegalStateException("追加会话消息失败: " + conversationId, e);
      }
    }
  }

  @Override
  public List<LlmMessage> load(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    synchronized (lock) {
      try {
        String summary = "";
        long compactedThruId = 0L;
        try (PreparedStatement ps = connection.prepareStatement(SELECT_CONVERSATION)) {
          ps.setString(1, conversationId);
          try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
              summary = rs.getString(1);
              compactedThruId = rs.getLong(2);
            }
          }
        }
        List<LlmMessage> messages = new ArrayList<>();
        if (!summary.isEmpty()) {
          messages.add(summaryMessage(summary));
        }
        try (PreparedStatement ps = connection.prepareStatement(SELECT_MESSAGES)) {
          ps.setString(1, conversationId);
          ps.setLong(2, compactedThruId);
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              messages.add(decode(rs.getString(1), rs.getString(2)));
            }
          }
        }
        return List.copyOf(messages);
      } catch (SQLException e) {
        throw new IllegalStateException("读取会话失败: " + conversationId, e);
      }
    }
  }

  @Override
  public void compact(String conversationId, String summary) {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(summary, "summary");
    String ts = Instant.now().toString();
    synchronized (lock) {
      try {
        connection.setAutoCommit(false);
        try {
          ensureConversation(conversationId, ts);
          try (PreparedStatement ps = connection.prepareStatement(COMPACT_CONVERSATION)) {
            ps.setString(1, summary);
            ps.setString(2, conversationId);
            ps.setString(3, ts);
            ps.setString(4, conversationId);
            ps.executeUpdate();
          }
          connection.commit();
        } catch (SQLException | RuntimeException e) {
          rollbackAfterFailure(e);
          throw e;
        } finally {
          connection.setAutoCommit(true);
        }
      } catch (SQLException e) {
        throw new IllegalStateException("压缩会话失败: " + conversationId, e);
      }
    }
  }

  private void ensureConversation(String conversationId, String ts) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(INSERT_CONVERSATION)) {
      ps.setString(1, conversationId);
      ps.setString(2, ts);
      ps.setString(3, ts);
      ps.executeUpdate();
    }
  }

  private void touchConversation(String conversationId, String ts) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(TOUCH_CONVERSATION)) {
      ps.setString(1, ts);
      ps.setString(2, conversationId);
      ps.executeUpdate();
    }
  }

  private void insertMessage(String conversationId, String role, String content, String ts)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(INSERT_MESSAGE)) {
      ps.setString(1, conversationId);
      ps.setString(2, role);
      ps.setString(3, content);
      ps.setString(4, ts);
      ps.executeUpdate();
    }
  }

  /** 失败回滚。回滚自身再失败时挂到原异常（suppressed）而不替换它：首个失败才是根因（同 {@code MemoryStore}）。 */
  private void rollbackAfterFailure(Throwable cause) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      cause.addSuppressed(rollbackFailure);
    }
  }

  /** 摘要消息的形态（{@link ConversationStore} 契约的一部分）：{@code role = assistant} 的单文本消息。 */
  private static LlmMessage summaryMessage(String summary) {
    return LlmMessage.assistant(List.of(new ContentPart.Text(summary)));
  }

  /**
   * 把一条消息编成落盘 JSON：{@code {role, parts[], reasoning?}}。
   *
   * <p><b>思维链（A6 修复版）按“字段是否存在”写</b>：{@code ABSENT} 不写任何字节；{@code EMPTY} 写 {@code
   * "reasoning":""}；{@code TEXT} 写原文。第一版只在非空时写，无法在存储往返里保住“键存在但为空”这一事实——而 DeepSeek thinking 模式要求历史
   * assistant 消息把空 {@code reasoning_content} 原样带回，丢掉的正是下一轮 400 的根因。
   *
   * <p>“没有该字段”仍是最普遍的路径：让老消息的落盘字节<b>逐字节保持原样</b>（而不是给它们补一个空串键），既省无谓库体积，也让 “本改动对既有消息零影响”成为可断言的字节级事实（见
   * {@code ReasoningEchoBackTest.persistedJsonCarriesReasoningKeyExactlyWhenTheFieldExists}）。
   *
   * <p><b>不写进 {@code parts}</b>：思维链不是"内容分片"（它不参与正文，也不该被拼进正文），且在供应商线格式里它是 {@code content}
   * 的兄弟键。放独立字段与 {@link LlmMessage#reasoning()} 的形状一一对应。
   */
  private static String encode(LlmMessage message) {
    ObjectNode root = JSON.createObjectNode();
    root.put(FIELD_ROLE, message.role());
    if (message.hasReasoningField()) {
      root.put(FIELD_REASONING, message.reasoning());
    }
    ArrayNode parts = root.putArray(FIELD_PARTS);
    for (ContentPart part : message.content()) {
      ObjectNode node = parts.addObject();
      // ContentPart 是密封接口：模式匹配 switch 无需 default，将来新增分片类型会编译失败（而不是静默丢内容）
      switch (part) {
        case ContentPart.Text text -> {
          node.put(FIELD_TYPE, TYPE_TEXT);
          node.put(FIELD_TEXT, text.text());
        }
        case ContentPart.ToolCall call -> {
          node.put(FIELD_TYPE, TYPE_TOOL_CALL);
          node.put(FIELD_ID, call.id());
          node.put(FIELD_NAME, call.name());
          node.set(FIELD_ARGUMENTS, JSON.valueToTree(call.arguments()));
        }
        case ContentPart.ToolResult result -> {
          node.put(FIELD_TYPE, TYPE_TOOL_RESULT);
          node.put(FIELD_TOOL_CALL_ID, result.toolCallId());
          node.put(FIELD_NAME, result.name());
          // content/error 恰好一个非空：两个字段都写全，空的那个写 JSON null
          node.put(FIELD_CONTENT, result.content());
          node.put(FIELD_ERROR, result.error());
        }
        case ContentPart.Image image -> {
          // ★ 只落"媒体类型 + 资产引用"，**字节不落库**：图片动辄几百 KB，内联进会话行会让库体积与回放成本双双失控
          //   （字节由宿主的 ToolAssetResolver 在发送那一刻解析；解析不到时发送侧响亮报错，不静默丢图）。
          node.put(FIELD_TYPE, TYPE_IMAGE);
          node.put(FIELD_MEDIA_TYPE, image.mediaType());
          node.put(FIELD_ASSET_ID, image.assetId());
        }
      }
    }
    return write(root);
  }

  private static LlmMessage decode(String role, String contentJson) {
    JsonNode root = read(contentJson);
    JsonNode parts = root.path(FIELD_PARTS);
    if (!parts.isArray()) {
      throw new IllegalStateException("会话消息内容缺少 " + FIELD_PARTS + " 数组: " + contentJson);
    }
    List<ContentPart> content = new ArrayList<>(parts.size());
    for (JsonNode node : parts) {
      String type = requiredText(node, FIELD_TYPE);
      switch (type) {
        case TYPE_TEXT -> content.add(new ContentPart.Text(requiredText(node, FIELD_TEXT)));
        case TYPE_TOOL_CALL ->
            content.add(
                new ContentPart.ToolCall(
                    requiredText(node, FIELD_ID),
                    requiredText(node, FIELD_NAME),
                    JSON.convertValue(node.path(FIELD_ARGUMENTS), ARGUMENTS_TYPE)));
        case TYPE_TOOL_RESULT ->
            content.add(
                new ContentPart.ToolResult(
                    requiredText(node, FIELD_TOOL_CALL_ID),
                    requiredText(node, FIELD_NAME),
                    nullableText(node, FIELD_CONTENT),
                    nullableText(node, FIELD_ERROR)));
        case TYPE_IMAGE ->
            content.add(
                new ContentPart.Image(
                    requiredText(node, FIELD_MEDIA_TYPE), requiredText(node, FIELD_ASSET_ID)));
        default -> throw new IllegalStateException("未知会话消息分片类型: " + type);
      }
    }
    DecodedReasoning reasoning = decodeReasoning(root);
    return new LlmMessage(role, content, reasoning.text(), reasoning.state());
  }

  /** 存储层解码出的思维链：原文 + 三态（两者必须互证，构造 {@link LlmMessage} 时由它兜底校验）。 */
  private record DecodedReasoning(String text, LlmMessage.ReasoningState state) {}

  /**
   * 取出思维链三态：<b>缺键、{@code null}、非文本一律按“字段缺席”（{@code ABSENT}）收口</b>；文本则为 {@code TEXT}/{@code EMPTY}。
   *
   * <p>为什么缺键必须是合法输入：改版前的行里根本没有这个键，而它们是要继续读的（升级后第一次打开老库就走这里）。判 {@code isTextual()} 而不是直接 {@code
   * asText()} 的理由同前——老档/手改库里出现 {@code "reasoning": null} 不该让整条会话读不开。
   *
   * <p>文本空串与缺键刻意分开：前者是“曾经发生过、要原样回传”的事实，后者是“从未有过”。存储往返若把两者合并，思考模式的多轮修复就只在单进程内成立， 下一 tick
   * 从库里读回历史时又会缺键。
   */
  private static DecodedReasoning decodeReasoning(JsonNode root) {
    JsonNode value = root.get(FIELD_REASONING);
    if (value == null || value.isNull() || !value.isTextual()) {
      return new DecodedReasoning("", LlmMessage.ReasoningState.ABSENT);
    }
    String text = value.textValue();
    return new DecodedReasoning(
        text, text.isEmpty() ? LlmMessage.ReasoningState.EMPTY : LlmMessage.ReasoningState.TEXT);
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalStateException("会话消息分片缺少字段 " + field + ": " + node);
    }
    return value.textValue();
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static String write(JsonNode node) {
    try {
      return JSON.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("序列化会话消息失败", e);
    }
  }

  private static JsonNode read(String json) {
    try {
      return JSON.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("反序列化会话消息失败: " + json, e);
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      try {
        // 收尾 checkpoint：把 WAL 内容刷回主库文件，进程退出后不留 wal 文件（同事件/记忆存储）
        try (Statement statement = connection.createStatement()) {
          statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        connection.close();
      } catch (SQLException e) {
        throw new IllegalStateException("关闭会话存储失败: " + dbFile, e);
      }
    }
  }

  public Path dbFile() {
    return dbFile;
  }
}
