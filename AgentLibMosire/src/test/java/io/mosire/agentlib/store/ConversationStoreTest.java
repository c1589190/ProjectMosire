package io.mosire.agentlib.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SqliteConversationStore} 契约：append-only 历史 + 显式 compact + 重启续聊。
 *
 * <p>行标识（{@code messages.id}）断言直接走 JDBC 读同一库文件：这是本任务唯一<b>能区分机制</b>的判据——只断言"内容相等" 的话，"每回合 DELETE +
 * 全量 INSERT"的实现照样全绿（行 id 全变、插入时序全变，而内容确实相等）。先例：{@code SqliteEventStoreTest} 的 {@code
 * sqlite_master}/{@code PRAGMA index_info} 断言同样直连库文件。
 */
class ConversationStoreTest {

  private static final String CONV = "conv-1";

  @TempDir Path tempDir;

  private Path db() {
    return tempDir.resolve("agent.db");
  }

  // ---------- 往返 ----------

  /** 四种角色 + 三种内容分片（含工具调用的嵌套参数、工具结果的成功/失败两态）逐条原样回读。 */
  @Test
  void appendThenLoadRoundTripsEveryContentKind() {
    List<LlmMessage> appended =
        List.of(
            LlmMessage.user("在吗"),
            LlmMessage.assistant(
                List.of(
                    new ContentPart.Text("先回显"),
                    new ContentPart.ToolCall(
                        "c-1", "echo", Map.of("text", "hello", "opts", Map.of("upper", "no"))))),
            LlmMessage.tool(new ContentPart.ToolResult("c-1", "echo", "echo: hello", null)),
            LlmMessage.assistant(List.of(new ContentPart.ToolCall("c-2", "bash", Map.of()))),
            LlmMessage.tool(new ContentPart.ToolResult("c-2", "bash", null, "权限被拒")),
            LlmMessage.assistant(List.of(new ContentPart.Text("完事"))));

    try (SqliteConversationStore store = SqliteConversationStore.open(db())) {
      appended.forEach(message -> store.append(CONV, message));

      assertThat(store.load(CONV)).containsExactlyElementsOf(appended);
    }
  }

  /**
   * 图片分片按<b>引用</b>往返：读回来的 {@link ContentPart.Image} 与写进去的逐字段相同（媒体类型 + 资产 id）。
   *
   * <p>★ "字节不落库"由<b>类型</b>结构保证（{@code Image} 根本没有字节字段，见其类注），所以本用例只钉"引用往返"——
   * 但这一步必须有：编解码里任何一个字段写丢/写错，模型下一轮就再也拿不到那张图。
   */
  @Test
  void imagePartsRoundTripByReference() {
    LlmMessage withImage =
        new LlmMessage(
            LlmMessage.ROLE_USER,
            List.of(new ContentPart.Text("看这张图"), new ContentPart.Image("image/png", "asset-1")));

    try (SqliteConversationStore store = SqliteConversationStore.open(db())) {
      store.append(CONV, withImage);

      assertThat(store.load(CONV)).containsExactly(withImage);
    }
  }

  @Test
  void loadUnknownConversationIsEmptyAndConversationsAreIsolated() {
    try (SqliteConversationStore store = SqliteConversationStore.open(db())) {
      assertThat(store.load("没有这个会话")).isEmpty();

      store.append(CONV, LlmMessage.user("会话一"));
      store.append("conv-2", LlmMessage.user("会话二"));

      assertThat(store.load(CONV)).containsExactly(LlmMessage.user("会话一"));
      assertThat(store.load("conv-2")).containsExactly(LlmMessage.user("会话二"));
    }
  }

  // ---------- 重启续聊（Store 级） ----------

  @Test
  void historySurvivesReopenOnTheSameFile() {
    Path db = db();
    try (SqliteConversationStore store = SqliteConversationStore.open(db)) {
      store.append(CONV, LlmMessage.user("问题一"));
      store.append(CONV, LlmMessage.assistant(List.of(new ContentPart.Text("第一轮回答"))));
    }

    try (SqliteConversationStore reopened = SqliteConversationStore.open(db)) {
      assertThat(reopened.load(CONV))
          .containsExactly(
              LlmMessage.user("问题一"), LlmMessage.assistant(List.of(new ContentPart.Text("第一轮回答"))));
      assertThat(reopened.dbFile()).isEqualTo(db);
    }
  }

  // ---------- 会话指针（D26：重置跨进程有效） ----------

  /**
   * 指针缺省为空、写下即跨 {@code open} 存活、可被覆盖（重置两次 = 指针跟到<b>最后一次</b>）。
   *
   * <p>判别性：只断言"指针读回来的值"抓不住"指针只活在内存里"的实现——中间那次重开（全新连接、全新对象）才是判据。
   */
  @Test
  void currentConversationIdIsAbsentThenSurvivesReopenAndIsOverwritten() {
    Path db = db();
    try (SqliteConversationStore store = SqliteConversationStore.open(db)) {
      assertThat(store.currentConversationId()).as("新库没有指针（首次启动才落到缺省会话）").isEmpty();

      store.setCurrentConversationId("s-1");
      assertThat(store.currentConversationId()).contains("s-1");
    }

    try (SqliteConversationStore reopened = SqliteConversationStore.open(db)) {
      assertThat(reopened.currentConversationId()).as("指针跨 open 存活").contains("s-1");

      reopened.setCurrentConversationId("s-2");
      assertThat(reopened.currentConversationId()).contains("s-2");
    }

    try (SqliteConversationStore third = SqliteConversationStore.open(db)) {
      assertThat(third.currentConversationId()).as("重开读到的是最后一次写下的").contains("s-2");
    }
  }

  /**
   * 指针指向的会话<b>不必</b>已有内容（重置出来的新会话在首条消息落库前就是"当前会话"）——且写指针不得顺手产出一条消息。
   *
   * <p>判别性：把指针实现成"往会话里塞一条标记消息"（可读、可跨 open，表面上都过）的实现会在这里红：{@code load} 不再是空， {@code messages} 行数也不再是
   * 0——而那条标记消息会被当成真实历史回灌给模型。
   */
  @Test
  void pointingAtAContentlessConversationAddsNoMessage() throws Exception {
    Path db = db();
    try (SqliteConversationStore store = SqliteConversationStore.open(db)) {
      store.setCurrentConversationId("s-empty");

      assertThat(store.load("s-empty")).as("指针不是内容：空会话读回来仍是空").isEmpty();
    }

    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM messages),"
                    + " (SELECT COUNT(*) FROM conversations WHERE id = 's-empty')");
        ResultSet rs = ps.executeQuery()) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).as("写指针不得产生消息行").isZero();
      assertThat(rs.getInt(2)).as("写指针不得趁便建会话行").isZero();
    }
  }

  // ---------- R7：同一 SQLite 文件 + 自建连接（WAL / DDL 幂等） ----------

  @Test
  void opensWithWalJournalModeAndBothTablesSurviveIdempotentOpen() throws Exception {
    Path db = db();
    try (SqliteConversationStore first = SqliteConversationStore.open(db)) {
      first.append(CONV, LlmMessage.user("一次"));
    }
    // 二次 open = 对既有库重跑 DDL（CREATE TABLE IF NOT EXISTS 幂等）
    try (SqliteConversationStore second = SqliteConversationStore.open(db)) {
      assertThat(second.load(CONV)).containsExactly(LlmMessage.user("一次"));
    }
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        var stmt = c.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
      }
      List<String> tables = new ArrayList<>();
      try (ResultSet rs =
          stmt.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
        while (rs.next()) {
          tables.add(rs.getString(1));
        }
      }
      assertThat(tables).contains("conversations", "messages");
    }
  }

  /** R7：与事件库同一文件——两个存储各自持连接，互不干扰（WAL + busy_timeout 协同）。 */
  @Test
  void livesInTheSameSqliteFileAsTheEventStore() {
    Path db = db();
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore conversations = SqliteConversationStore.open(db)) {
      events.append(EventWrite.of("conversation.turn", "main", "{\"n\":1}"));
      conversations.append(CONV, LlmMessage.user("同库"));

      assertThat(conversations.load(CONV)).containsExactly(LlmMessage.user("同库"));
    }

    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore conversations = SqliteConversationStore.open(db)) {
      assertThat(events.count()).isEqualTo(1);
      assertThat(conversations.load(CONV)).containsExactly(LlmMessage.user("同库"));
    }
  }

  // ---------- R9：append-only 的判别性判据 ----------

  /**
   * 判别性 pin：追加不改变既有行的行标识与内容——{@code DELETE + 全量 INSERT} 的实现会让旧行 id 全变（SQLite {@code AUTOINCREMENT}
   * 不复用已删 id），只断言"内容相等"抓不到它。
   */
  @Test
  void appendNeverRewritesExistingRows() throws Exception {
    Path db = db();
    try (SqliteConversationStore store = SqliteConversationStore.open(db)) {
      store.append(CONV, LlmMessage.user("一"));
      store.append(CONV, LlmMessage.assistant(List.of(new ContentPart.Text("答一"))));
      List<StoredRow> before = rows(db, CONV);

      store.append(CONV, LlmMessage.user("二"));
      store.append(CONV, LlmMessage.assistant(List.of(new ContentPart.Text("答二"))));
      List<StoredRow> after = rows(db, CONV);

      assertThat(before).hasSize(2);
      assertThat(after).hasSize(4);
      // 旧行（id + role + 内容）逐个未变：这才是"只追加尾部"
      assertThat(after.subList(0, before.size())).containsExactlyElementsOf(before);
      assertThat(after.subList(before.size(), after.size()))
          .extracting(StoredRow::id)
          .allSatisfy(id -> assertThat(id).isGreaterThan(before.get(before.size() - 1).id()));
      assertThat(store.load(CONV)).hasSize(4);
    }
  }

  // ---------- R8：compact 只能显式触发，且只改 load 视图 ----------

  @Test
  void explicitCompactPrefixesSummaryAndKeepsRawRowsForAudit() throws Exception {
    Path db = db();
    try (SqliteConversationStore store = SqliteConversationStore.open(db)) {
      store.append(CONV, LlmMessage.user("一"));
      store.append(CONV, LlmMessage.assistant(List.of(new ContentPart.Text("答一"))));
      store.append(CONV, LlmMessage.user("二"));
      store.append(CONV, LlmMessage.assistant(List.of(new ContentPart.Text("答二"))));
      List<StoredRow> rawBeforeCompact = rows(db, CONV);

      // 压缩前：load 就是原始消息本身，不含任何 summary（summary 只在显式 compact 之后出现）
      assertThat(store.load(CONV)).hasSize(4);
      assertThat(store.load(CONV).get(0).role()).isEqualTo(LlmMessage.ROLE_USER);

      store.compact(CONV, "前两轮的摘要");

      // 压缩后：load = summary + 压缩点之后的消息（此处压缩点即尾部，故只剩 summary）
      assertThat(store.load(CONV)).containsExactly(summary("前两轮的摘要"));
      // 原始行一个都没删：留档可审计
      assertThat(rows(db, CONV)).containsExactlyElementsOf(rawBeforeCompact);
      long point = rawBeforeCompact.get(rawBeforeCompact.size() - 1).id();
      assertThat(compactState(db, CONV)).isEqualTo(new StoredCompact("前两轮的摘要", point));

      // append 绝不自动压缩：压缩点与 summary 原样不动，新消息落在 summary 之后
      store.append(CONV, LlmMessage.user("三"));
      assertThat(store.load(CONV)).containsExactly(summary("前两轮的摘要"), LlmMessage.user("三"));
      assertThat(compactState(db, CONV)).isEqualTo(new StoredCompact("前两轮的摘要", point));
      assertThat(rows(db, CONV)).hasSize(rawBeforeCompact.size() + 1);
    }
  }

  @Test
  void appendWithoutCompactNeverProducesASummary() throws Exception {
    Path db = db();
    try (SqliteConversationStore store = SqliteConversationStore.open(db)) {
      store.append(CONV, LlmMessage.user("一"));
      store.append(CONV, LlmMessage.user("二"));

      assertThat(store.load(CONV)).containsExactly(LlmMessage.user("一"), LlmMessage.user("二"));
      assertThat(compactState(db, CONV).summary()).isEmpty();
      assertThat(compactState(db, CONV).compactedThruId()).isZero();
    }
  }

  /** 二次 compact（显式）替换 summary 并前移压缩点；旧 summary 不再出现在 load 里（替换只能经 compact 发生）。 */
  @Test
  void secondCompactReplacesSummaryAndMovesThePointForward() {
    Path db = db();
    try (SqliteConversationStore store = SqliteConversationStore.open(db)) {
      store.append(CONV, LlmMessage.user("一"));
      store.append(CONV, LlmMessage.assistant(List.of(new ContentPart.Text("答一"))));
      store.compact(CONV, "第一版摘要");

      store.append(CONV, LlmMessage.user("二"));
      store.append(CONV, LlmMessage.assistant(List.of(new ContentPart.Text("答二"))));
      assertThat(store.load(CONV))
          .containsExactly(
              summary("第一版摘要"),
              LlmMessage.user("二"),
              LlmMessage.assistant(List.of(new ContentPart.Text("答二"))));

      store.compact(CONV, "第二版摘要");

      assertThat(store.load(CONV)).containsExactly(summary("第二版摘要"));
      assertThat(store.load(CONV)).doesNotContain(summary("第一版摘要"));
    }
  }

  // ---------- R6：每次调用自身原子（并发只靠内部锁，不放宽上层契约） ----------

  @Test
  void concurrentAppendsLandOneByOneWithoutLoss() throws Exception {
    int threads = 4;
    int perThread = 25;
    CountDownLatch start = new CountDownLatch(1);
    try (SqliteConversationStore store = SqliteConversationStore.open(db())) {
      List<Thread> workers = new ArrayList<>();
      List<Throwable> failures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        int worker = t;
        Thread thread =
            new Thread(
                () -> {
                  try {
                    start.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < perThread; i++) {
                      store.append(CONV, LlmMessage.user("w" + worker + "-" + i));
                    }
                  } catch (Throwable e) {
                    synchronized (failures) {
                      failures.add(e);
                    }
                  }
                });
        workers.add(thread);
        thread.start();
      }
      start.countDown();
      for (Thread thread : workers) {
        thread.join(10_000);
        assertThat(thread.isAlive()).isFalse();
      }

      assertThat(failures).isEmpty();
      assertThat(store.load(CONV)).hasSize(threads * perThread);
      assertThat(rows(db(), CONV)).extracting(StoredRow::id).doesNotHaveDuplicates();
    }
  }

  // ---------- 参数校验 ----------

  @Test
  void validatesArguments() {
    try (SqliteConversationStore store = SqliteConversationStore.open(db())) {
      assertThatThrownBy(() -> store.append(null, LlmMessage.user("x")))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> store.append(CONV, null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> store.load(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> store.compact(null, "s")).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> store.compact(CONV, null)).isInstanceOf(NullPointerException.class);
    }
  }

  // ---------- 任务 #32：失败路径（initialize 抛错）上已取得连接的回收 ----------

  /**
   * 任务 #32（AgentLib 既有缺陷，与 {@code SqliteEventStore} 同形）：{@code initialize()} 抛错时必须回收已建立的连接，判据是
   * 本进程指向该库文件的 fd 归零。判别前提与取样纪律见 {@code
   * SqliteEventStoreTest#initializeFailureReleasesConnection}（同款输入： 内容非 SQLite 库 ⇒ {@code
   * getConnection} 成功、首条语句抛 SQLITE_NOTADB，失败点落在 {@code initialize()}）。
   */
  @Test
  void initializeFailureReleasesConnection() throws IOException {
    assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")), "需要 /proc/self/fd（Linux）观察连接释放");
    Path db = db();
    Files.write(db, "not a sqlite database".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> SqliteConversationStore.open(db))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("打开 SQLite 会话存储失败: " + db)
        .hasCauseInstanceOf(SQLException.class)
        .getCause()
        .hasMessageContaining("not a database");

    assertThat(openLibraryFds(db)).as("open() 失败后不得残留指向 agent.db 的打开连接").isZero();
  }

  /**
   * 本进程打开的、指向 {@code dbFile}（或其 {@code -wal}/{@code -shm} 伴生文件）的 fd 数。目录也一并比对：每个用例有自己的
   * tempDir，只看文件名会把同 fork 里其他用例的库连接一并数进来（判据必须只认本用例那一份）。跨模块照抄自 {@code AppMcpLinkTest}。
   */
  private static long openLibraryFds(Path dbFile) throws IOException {
    Path dir = dbFile.toAbsolutePath().getParent();
    String name = dbFile.getFileName().toString();
    try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
      return fds.filter(
              fd -> {
                try {
                  Path target = Files.readSymbolicLink(fd);
                  return target.startsWith(dir)
                      && target.getFileName() != null
                      && target.getFileName().toString().startsWith(name);
                } catch (IOException e) {
                  return false; // 列举与读取之间该 fd 已被关闭：与本判据无关
                }
              })
          .count();
    }
  }

  // ---------- 库内真值读取（同一文件、独立连接） ----------

  /** 一条落库消息的行真值：行标识 + 角色 + 内容 JSON。 */
  private record StoredRow(long id, String role, String content) {}

  /** conversations 行的压缩状态。 */
  private record StoredCompact(String summary, long compactedThruId) {}

  private static List<StoredRow> rows(Path db, String conversationId) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT id, role, content FROM messages WHERE conversation_id = ? ORDER BY id")) {
      ps.setString(1, conversationId);
      try (ResultSet rs = ps.executeQuery()) {
        List<StoredRow> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(new StoredRow(rs.getLong(1), rs.getString(2), rs.getString(3)));
        }
        return rows;
      }
    }
  }

  private static StoredCompact compactState(Path db, String conversationId) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT summary, compacted_thru_id FROM conversations WHERE id = ?")) {
      ps.setString(1, conversationId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return new StoredCompact("", 0L);
        }
        return new StoredCompact(rs.getString(1), rs.getLong(2));
      }
    }
  }

  /** load 里 summary 的位置与形状：独占首条，role=assistant（保证 user/assistant 交替，严格供应商也收）。 */
  private static LlmMessage summary(String text) {
    return LlmMessage.assistant(List.of(new ContentPart.Text(text)));
  }
}
