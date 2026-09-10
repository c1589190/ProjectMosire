package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.store.ConversationStore;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.context.BasicContextAssembler;
import io.mosire.brain.context.ContextAssembler;
import io.mosire.brain.context.ContextComposition;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 会话持久化接线（T16）：{@link AgentPipeline} + {@link SqliteConversationStore} 的联调——重启续聊（R4）、
 * 只追加尾部（R1/R9）、{@code lastHistory()} 契约（R5）、非 FINISHED 终止路径也落库。
 *
 * <p>既有 9 参构造与 {@link AgentPipelineTest} 的行为锁不动（R2）；本类只用新增的 {@code (store, conversationId)}
 * 重载。事件库与会话库是<b>同一个 SQLite 文件</b>（R7），行标识断言直连该文件读取。
 */
class AgentPipelineConversationStoreTest {

  private static final String CONV = "conv-1";

  @TempDir Path tempDir;

  private Path db;

  @BeforeEach
  void setUp() {
    db = tempDir.resolve("agent.db");
  }

  /** R4 + 计划验收句"进程重启续聊"：新实例构造即 hydrate，首回合请求必须带上上一个实例落库的消息。 */
  @Test
  void restartedInstanceReplaysPersistedHistoryInItsFirstRequest() {
    RecordingLlmClient firstRun = new RecordingLlmClient();
    firstRun.enqueue(LlmResponse.text("第一轮回答"));
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      AgentPipeline pipeline = pipeline(firstRun, events, bus, store);

      assertThat(pipeline.run("问题一").stopReason()).isEqualTo(StopReason.FINISHED);
    }

    // "重启"：同一库文件上全新 Store + 全新管线（内存工作集为空）
    RecordingLlmClient secondRun = new RecordingLlmClient();
    secondRun.enqueue(LlmResponse.text("第二轮回答"));
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      AgentPipeline restarted = pipeline(secondRun, events, bus, store);

      assertThat(restarted.lastHistory()).hasSize(2); // 构造即灌入既有会话

      TurnResult second = restarted.run("问题二");

      assertThat(second.stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(secondRun.requests).hasSize(1);
      List<LlmMessage> request = secondRun.requests.get(0).messages();
      assertThat(request).hasSize(4);
      // assembler 契约：第一条必为 system（其正文由装配器生成，这里只锁它在位）
      assertThat(request.get(0).role()).isEqualTo(LlmMessage.ROLE_SYSTEM);
      assertThat(request.subList(1, request.size()))
          .containsExactly(
              LlmMessage.user("问题一"),
              LlmMessage.assistant(List.of(new ContentPart.Text("第一轮回答"))),
              LlmMessage.user("问题二"));
    }
  }

  /**
   * R9 判别性 pin：第二回合只追加尾部——第一回合落库行的<b>行标识</b>与内容逐个未变。
   *
   * <p>"每回合 DELETE + 全量 INSERT"（把 {@code saveHistory} 的清空重写机制直译到 SQLite）在这里必红：行 id
   * 全变、插入时序全变，而"内容相等"的断言抓不到它；"每回合重写全量历史"（重复前缀）则在条数 4 上立刻暴露。
   */
  @Test
  void secondTurnAppendsOnlyTheNewTailAndKeepsRowIdentities() throws Exception {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      RecordingLlmClient llm = new RecordingLlmClient();
      llm.enqueue(LlmResponse.text("第一轮回答"));
      llm.enqueue(LlmResponse.text("第二轮回答"));
      AgentPipeline pipeline = pipeline(llm, events, bus, store);

      assertThat(pipeline.run("问题一").stopReason()).isEqualTo(StopReason.FINISHED);
      List<StoredRow> afterFirstTurn = rows(CONV);
      assertThat(afterFirstTurn).hasSize(2);

      assertThat(pipeline.run("问题二").stopReason()).isEqualTo(StopReason.FINISHED);
      List<StoredRow> afterSecondTurn = rows(CONV);

      assertThat(afterSecondTurn).hasSize(4);
      // 判别性断言：第一回合的行（id + role + 内容）在第二回合一个都没被改写
      assertThat(afterSecondTurn.subList(0, afterFirstTurn.size()))
          .containsExactlyElementsOf(afterFirstTurn);
      // 不重复前缀：会话内容恰好 4 条，无重复
      assertThat(store.load(CONV))
          .containsExactly(
              LlmMessage.user("问题一"),
              LlmMessage.assistant(List.of(new ContentPart.Text("第一轮回答"))),
              LlmMessage.user("问题二"),
              LlmMessage.assistant(List.of(new ContentPart.Text("第二轮回答"))));
      assertThat(pipeline.lastHistory()).hasSize(4);
    }
  }

  /** R5：{@code lastHistory()} 仍是"不含 system 头"的<b>快照</b>（M2 的 A2A/AG-UI 契约，切 Store 后语义冻结）。 */
  @Test
  void lastHistoryStaysImmutableSnapshotWithoutSystemHead() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      RecordingLlmClient llm = new RecordingLlmClient();
      llm.enqueue(LlmResponse.text("回答一"));
      llm.enqueue(LlmResponse.text("回答二"));
      AgentPipeline pipeline = pipeline(llm, events, bus, store);

      pipeline.run("问题一");
      List<LlmMessage> snapshot = pipeline.lastHistory();

      assertThat(snapshot)
          .extracting(LlmMessage::role)
          .containsExactly(LlmMessage.ROLE_USER, LlmMessage.ROLE_ASSISTANT);
      assertThat(snapshot).noneMatch(message -> LlmMessage.ROLE_SYSTEM.equals(message.role()));
      assertThatThrownBy(() -> snapshot.add(LlmMessage.user("注入")))
          .isInstanceOf(UnsupportedOperationException.class);

      pipeline.run("问题二");

      assertThat(snapshot).hasSize(2); // 快照不被后续回合改写
      assertThat(pipeline.lastHistory())
          .extracting(LlmMessage::role)
          .containsExactly(
              LlmMessage.ROLE_USER,
              LlmMessage.ROLE_ASSISTANT,
              LlmMessage.ROLE_USER,
              LlmMessage.ROLE_ASSISTANT);
    }
  }

  /** R1 佐证：非 FINISHED 的终止路径（LLM_ERROR）同样经过 saveHistory——落库、且能被下一个实例回灌。 */
  @Test
  void failedTurnStillPersistsItsTailForTheNextInstance() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      // 空脚本 → 第一次 chat 即抛 LlmException → LLM_ERROR 终止
      AgentPipeline failing = pipeline(new RecordingLlmClient(), events, bus, store);

      assertThat(failing.run("问一句").stopReason()).isEqualTo(StopReason.LLM_ERROR);
      assertThat(store.load(CONV)).containsExactly(LlmMessage.user("问一句"));
    }

    RecordingLlmClient next = new RecordingLlmClient();
    next.enqueue(LlmResponse.text("答"));
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      AgentPipeline restarted = pipeline(next, events, bus, store);

      restarted.run("再问");

      assertThat(next.requests.get(0).messages()).contains(LlmMessage.user("问一句"));
    }
  }

  /**
   * M3 判别性用例 1：assembler 少回灌一条历史 → 守卫响亮失败，而不是静默丢本回合尾部。
   *
   * <p>去掉守卫后本用例必红，且红的方式<b>不是</b>"换一种异常抛"：本回合无 LLM 响应（空脚本 → LLM_ERROR 终止，assistant 消息未入列），{@code
   * messages} 恰为 {@code [system] + 少一条的历史 + [本轮 user]} 共 3 条，而 {@code subList(replayedSize + 1,
   * messages.size())} = {@code subList(3, 3)} 是空区间（fromIndex == toIndex，不越界）—— 本回合的 user
   * 被静默丢光、内存与库都不写，{@code run()} 正常返回一个 LLM_ERROR 的 TurnResult，没有任何异常可循。
   */
  @Test
  void assemblerDroppingHistoryIsRejectedLoudlyInsteadOfSilentlyDroppingTheTail() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      RecordingLlmClient first = new RecordingLlmClient();
      first.enqueue(LlmResponse.text("第一轮回答"));
      pipeline(first, events, bus, store).run("问题一"); // 正常回合：历史成为 2 条

      RecordingLlmClient second = new RecordingLlmClient(); // 空脚本
      AgentPipeline pipeline =
          pipeline(
              second,
              events,
              bus,
              store,
              new ContractViolatingAssembler(ContractBreach.DROP_ONE_HISTORY));

      assertThatThrownBy(() -> pipeline.run("问题二"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("assembler 违反契约")
          .hasMessageContaining("replayedSize=2")
          .hasMessageContaining("size=3");

      // 守卫在任何内存/落库写入之前：违规回合没有留下半截痕迹
      assertThat(pipeline.lastHistory()).hasSize(2);
      assertThat(store.load(CONV)).hasSize(2);
    }
  }

  /**
   * M3 判别性用例 2：assembler 保留全量历史、但在 system 之后多插一条摘要 → 守卫响亮失败，而不是把已落库的历史当新增重复追加。
   *
   * <p>去掉守卫后本用例必红：本轮的 assistant 已入列，{@code messages} = {@code [system, 摘要, 历史×2, user, assistant]}
   * 共 6 条，切片下标整体前移一位——{@code subList(replayedSize + 1, size)} = {@code subList(3, 6)}
   * 会把<b>已落库的历史尾条</b>连同本轮 user 与 assistant 再 append 一次，append-only 库里出现重复前缀， 且不抛任何异常。
   */
  @Test
  void assemblerPrependingSummaryOverFullHistoryIsRejectedLoudlyInsteadOfDuplicatingPrefix() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      RecordingLlmClient first = new RecordingLlmClient();
      first.enqueue(LlmResponse.text("第一轮回答"));
      pipeline(first, events, bus, store).run("问题一"); // 正常回合：历史成为 2 条

      RecordingLlmClient second = new RecordingLlmClient();
      second.enqueue(LlmResponse.text("第二轮回答"));
      AgentPipeline pipeline =
          pipeline(
              second,
              events,
              bus,
              store,
              new ContractViolatingAssembler(ContractBreach.PREPEND_SUMMARY));

      // [system, 摘要, 历史×2, user, assistant] = 6 条：长度够，但 messages[replayedSize + 1] 是回灌历史的尾条
      // （assistant）而非本轮 user
      assertThatThrownBy(() -> pipeline.run("问题二"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("assembler 违反契约")
          .hasMessageContaining("replayedSize=2")
          .hasMessageContaining("size=6");

      assertThat(pipeline.lastHistory()).hasSize(2);
      assertThat(store.load(CONV)).hasSize(2);
    }
  }

  /**
   * M4 pin：落库失败不吞——{@code append} 第 2 条抛异常时，异常穿透 {@code run()}（不被吞成正常 TurnResult），库里只剩已落库的<b>前缀</b>
   * （第 1 条），而内存 {@code lastHistory()} 已含完整尾部。
   *
   * <p>"库里有前缀" + "内存有全量"共同定义重启语义：新实例 hydrate 到的是前缀，Agent 不会失忆到最后一条落库消息之前，也不会凭空
   * 看到一条从未落库的消息。库与内存<b>不一致</b>是这条契约的既定形态，不是缺陷。
   */
  @Test
  void persistFailurePropagatesOutOfRunAndLeavesOnlyThePersistedPrefix() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      RecordingLlmClient llm = new RecordingLlmClient();
      llm.enqueue(LlmResponse.text("第一轮回答"));
      AgentPipeline pipeline =
          pipeline(llm, events, bus, new FailingOnSecondAppendConversationStore(store));

      assertThatThrownBy(() -> pipeline.run("问题一"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("注入的落库失败");

      // 库里只剩前缀：第 1 条（本轮 user）已落，第 2 条（本轮 assistant）起没有
      assertThat(store.load(CONV)).containsExactly(LlmMessage.user("问题一"));
      // 内存已含完整尾部：本回合的 assistant 回复没有因落库失败而丢（memory 与库的不一致是既定语义）
      assertThat(pipeline.lastHistory())
          .containsExactly(
              LlmMessage.user("问题一"), LlmMessage.assistant(List.of(new ContentPart.Text("第一轮回答"))));
    }
  }

  /** 新增重载：9 参构造之外的 {@code (store, conversationId)}（R2/R3——conversationId 是显式参数）。 */
  private static AgentPipeline pipeline(
      LlmClient llm, EventStore events, EventBus bus, ConversationStore store) {
    return pipeline(llm, events, bus, store, new BasicContextAssembler());
  }

  /** 同上，但装配器由调用方给定（守卫用例需要违反契约的桩）。 */
  private static AgentPipeline pipeline(
      LlmClient llm,
      EventStore events,
      EventBus bus,
      ConversationStore store,
      ContextAssembler assembler) {
    AgentConfig config = AgentConfig.builder("main").build();
    return new AgentPipeline(
        config,
        llm,
        new ToolRegistry(),
        new ToolExecutionGuard(),
        assembler,
        events,
        bus,
        AgentPermissionSet.system().grantedToken(),
        AgentPermissionSet.system(),
        store,
        CONV);
  }

  /** 一条落库消息的行真值：行标识 + 角色 + 内容 JSON（独立连接读同一库文件）。 */
  private record StoredRow(long id, String role, String content) {}

  private List<StoredRow> rows(String conversationId) throws Exception {
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

  /** 记录每次收到的 LlmRequest 的脚本桩（回灌断言必须看请求内容，不能只看返回文本）。 */
  private static final class RecordingLlmClient implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    void enqueue(LlmResponse response) {
      script.addLast(response);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new LlmException("RecordingLlmClient 脚本已耗空");
      }
      return response;
    }
  }

  /** 契约破坏的两种档位（守卫用例的判别性输入）。 */
  private enum ContractBreach {
    /** 少回灌一条历史：回灌长度不再是 history.size()，本轮 user 也不再落在 {@code replayedSize + 1}。 */
    DROP_ONE_HISTORY,
    /** 保留全量历史、但在 system 之后多插一条摘要：本轮 user 的绝对下标整体前移一位。 */
    PREPEND_SUMMARY
  }

  /**
   * 违反 {@link ContextAssembler} 契约的桩：正常装配器恒返回 {@code [system] + history（原样）+ [user]}，本桩刻意破坏其中一处，
   * 用来证明管线守卫把"静默错写"变成"响亮失败"。
   */
  private static final class ContractViolatingAssembler implements ContextAssembler {

    private final ContractBreach breach;

    ContractViolatingAssembler(ContractBreach breach) {
      this.breach = breach;
    }

    @Override
    public LlmRequest buildRequest(
        AgentConfig config, String userMessage, List<LlmMessage> history, List<AgentTool> tools) {
      List<LlmMessage> replayed = history;
      if (breach == ContractBreach.DROP_ONE_HISTORY && !history.isEmpty()) {
        replayed = history.subList(1, history.size());
      }
      List<LlmMessage> messages = new ArrayList<>(replayed.size() + 3);
      messages.add(LlmMessage.system("桩 system"));
      if (breach == ContractBreach.PREPEND_SUMMARY) {
        messages.add(LlmMessage.assistant(List.of(new ContentPart.Text("压缩摘要"))));
      }
      messages.addAll(replayed);
      messages.add(LlmMessage.user(userMessage));
      return new LlmRequest(messages, List.of());
    }

    @Override
    public ContextComposition composition(
        AgentConfig config, String userMessage, List<LlmMessage> history, List<AgentTool> tools) {
      return new ContextComposition(Map.of());
    }
  }

  /** 落库失败桩：{@code append} 第 2 次调用抛（第 1 条已委托落库），且不吞、不包装——验证异常穿透 {@code run()}。 */
  private static final class FailingOnSecondAppendConversationStore implements ConversationStore {

    private final ConversationStore delegate;
    private int appends;

    FailingOnSecondAppendConversationStore(ConversationStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public void append(String conversationId, LlmMessage message) {
      appends++;
      if (appends == 2) {
        throw new IllegalStateException("注入的落库失败：第 2 条消息写盘失败");
      }
      delegate.append(conversationId, message);
    }

    @Override
    public List<LlmMessage> load(String conversationId) {
      return delegate.load(conversationId);
    }

    @Override
    public void compact(String conversationId, String summary) {
      delegate.compact(conversationId, summary);
    }
  }
}
