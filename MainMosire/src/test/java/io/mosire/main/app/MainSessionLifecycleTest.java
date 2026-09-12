package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.brain.runtime.StopReason;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P3-3 会话生命周期的<b>生产装配层</b>判据（App → AgentRuntime → AgentPipeline → SqliteConversationStore）：
 *
 * <ul>
 *   <li>验收① 两轮对话的第二轮确实带上第一轮（断言请求里含历史）；
 *   <li>验收② <b>重启进程后历史仍在</b>——两段 {@code App}（各自全新建运行时/存储连接）共用一个 data-dir，第二段的首个请求必须含第一段
 *       的问答；这是"落盘真的生效"的判据，管线层同样的断言证明不了装配接线（store 可能压根没被接上）；
 *   <li>验收③ 重置后下一轮从零开始，且旧会话仍能从库里读回（D26：不删库）；
 *   <li>反向用例：{@code --demo} 行为零变化——demo 分叉下会话存储<b>根本没被打开</b>（连表都不存在），更谈不上落库。
 * </ul>
 *
 * <p><b>为什么用 HTTP 之外的口</b>：{@code app.runtime().chat(..)} 是同一个运行时实例的同步口（A2A/AG-UI/调试网关都经它），
 * 与"重启"判据互不干扰——进程重启的语义由<b>新建 App</b> 表达，不需要真的 fork 进程（{@code MainRealLlmTest} 已覆盖进程边界的启动 语义）。重置的
 * HTTP 面在 {@code DebugChatSessionHttpTest}。
 */
class MainSessionLifecycleTest {

  @TempDir Path tempDir;

  /** 验收①：同一个 App 实例的连续两轮——第二轮的请求里必须带上一轮的问答。 */
  @Test
  void secondTurnReplaysTheFirstTurnFromThePersistedConversation() throws Exception {
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(LlmResponse.text("回答一"));
    llm.enqueue(LlmResponse.text("回答二"));
    try (App app = App.start(bootConfig(tempDir.resolve("data")), llm)) {
      assertThat(app.runtime().conversationId()).isEqualTo(App.MAIN_CONVERSATION_ID);
      assertThat(app.runtime().chat("问题一").stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(app.runtime().chat("问题二").stopReason()).isEqualTo(StopReason.FINISHED);
    }

    assertThat(llm.requests).hasSize(2);
    List<LlmMessage> second = llm.requests.get(1).messages();
    assertThat(second.get(0).role()).isEqualTo(LlmMessage.ROLE_SYSTEM);
    assertThat(second.subList(1, second.size()))
        .containsExactly(
            LlmMessage.user("问题一"),
            LlmMessage.assistant(List.of(new ContentPart.Text("回答一"))),
            LlmMessage.user("问题二"));
  }

  /**
   * 验收②：<b>重启进程后历史仍在</b>。第二段 App 是全新的装配（新运行时、新存储连接、空内存工作集），它的首个请求含上一段的问答—— 唯一可能的来源就是 data-dir 里的库。
   */
  @Test
  void restartedAppReplaysHistoryPersistedByThePreviousApp() throws Exception {
    Path dataDir = tempDir.resolve("data");

    RecordingLlmClient firstProcess = new RecordingLlmClient();
    firstProcess.enqueue(LlmResponse.text("重启前的回答"));
    try (App app = App.start(bootConfig(dataDir), firstProcess)) {
      assertThat(app.runtime().chat("重启前的提问").stopReason()).isEqualTo(StopReason.FINISHED);
    }

    RecordingLlmClient secondProcess = new RecordingLlmClient();
    secondProcess.enqueue(LlmResponse.text("重启后的回答"));
    try (App app = App.start(bootConfig(dataDir), secondProcess)) {
      assertThat(app.runtime().chat("重启后的提问").stopReason()).isEqualTo(StopReason.FINISHED);
    }

    assertThat(secondProcess.requests).hasSize(1);
    List<LlmMessage> firstRequestAfterRestart = secondProcess.requests.get(0).messages();
    assertThat(firstRequestAfterRestart.get(0).role()).isEqualTo(LlmMessage.ROLE_SYSTEM);
    assertThat(firstRequestAfterRestart.subList(1, firstRequestAfterRestart.size()))
        .containsExactly(
            LlmMessage.user("重启前的提问"),
            LlmMessage.assistant(List.of(new ContentPart.Text("重启前的回答"))),
            LlmMessage.user("重启后的提问"));
  }

  /** 验收③：重置后下一轮从零开始（请求不含重置前的内容），旧会话仍能从库里读回——且是<b>关停之后</b>从盘上读回（重置不是"清内存"， 更不是"删库"）。 */
  @Test
  void resetStartsFromScratchAndKeepsTheOldConversationOnDisk() throws Exception {
    Path dataDir = tempDir.resolve("data");
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(LlmResponse.text("回答一"));
    llm.enqueue(LlmResponse.text("回答二"));
    try (App app = App.start(bootConfig(dataDir), llm)) {
      app.runtime().chat("重置前的提问");

      app.runtime().resetSession("s-reset-1");

      assertThat(app.runtime().conversationId()).isEqualTo("s-reset-1");
      app.runtime().chat("重置后的提问");

      List<LlmMessage> afterReset = llm.requests.get(1).messages();
      assertThat(afterReset).doesNotContain(LlmMessage.user("重置前的提问"));
      assertThat(afterReset).hasSize(2); // [system, 本轮 user]——重置前的一问一答一条都没带上
    }

    try (SqliteConversationStore store =
        SqliteConversationStore.open(dataDir.resolve("events.db"))) {
      assertThat(store.load(App.MAIN_CONVERSATION_ID))
          .as("旧会话留档（D26：不删库）")
          .containsExactly(
              LlmMessage.user("重置前的提问"),
              LlmMessage.assistant(List.of(new ContentPart.Text("回答一"))));
      assertThat(store.load("s-reset-1"))
          .containsExactly(
              LlmMessage.user("重置后的提问"),
              LlmMessage.assistant(List.of(new ContentPart.Text("回答二"))));
    }
  }

  /**
   * 验收③的<b>跨进程</b>半边：重置后重启，接的是重置出的新会话，而不是把用户已经清掉的那个会话重新灌回来（D26 拒绝"只清内存"的理由正是后者）。
   *
   * <p>判别性：这条用例是 2026-09-12 真模型冒烟暴露的缺陷的回填——当时的实现里"当前会话"没有任何持久载体，重启必退回 {@code
   * MAIN_CONVERSATION_ID}（断言① {@code conversationId()} 会红），且重启后的首个请求把重置前的问答原样带上（断言② 会红）。
   */
  @Test
  void restartedAppResumesTheResetSessionInsteadOfResurrectingTheClearedHistory() throws Exception {
    Path dataDir = tempDir.resolve("data");

    RecordingLlmClient firstProcess = new RecordingLlmClient();
    firstProcess.enqueue(LlmResponse.text("重置前回答"));
    firstProcess.enqueue(LlmResponse.text("重置后回答"));
    try (App app = App.start(bootConfig(dataDir), firstProcess)) {
      assertThat(app.runtime().chat("重置前的提问").stopReason()).isEqualTo(StopReason.FINISHED);

      app.runtime().resetSession("s-after-reset");
      assertThat(app.runtime().chat("重置后的提问").stopReason()).isEqualTo(StopReason.FINISHED);
    }

    RecordingLlmClient secondProcess = new RecordingLlmClient();
    secondProcess.enqueue(LlmResponse.text("重启后的回答"));
    try (App app = App.start(bootConfig(dataDir), secondProcess)) {
      assertThat(app.runtime().conversationId())
          .as("重启接的是重置出的会话，不是固定初始会话")
          .isEqualTo("s-after-reset");

      assertThat(app.runtime().chat("重启后的提问").stopReason()).isEqualTo(StopReason.FINISHED);
    }

    assertThat(secondProcess.requests).hasSize(1);
    List<LlmMessage> firstRequestAfterRestart = secondProcess.requests.get(0).messages();
    assertThat(firstRequestAfterRestart).doesNotContain(LlmMessage.user("重置前的提问"));
    assertThat(firstRequestAfterRestart.subList(1, firstRequestAfterRestart.size()))
        .containsExactly(
            LlmMessage.user("重置后的提问"),
            LlmMessage.assistant(List.of(new ContentPart.Text("重置后回答"))),
            LlmMessage.user("重启后的提问"));
  }

  /**
   * 反向用例：{@code --demo} 行为零变化——demo 分叉下<b>根本不打开</b>会话存储。
   *
   * <p>判据是"库里连 {@code conversations}/{@code messages} 表都不存在"（DDL 由 {@code
   * SqliteConversationStore.open} 建）：一旦有人在 demo 路径上接了 store，这两个表必然出现（demo 回合还会往里写）。比"表存在但空"更强——
   * 后者只能证明没写，证明不了没接。
   */
  @Test
  void demoBootNeverOpensTheConversationStore() throws Exception {
    Path dataDir = tempDir.resolve("demo-data");
    BootConfig config =
        new BootConfig(0, dataDir, true, App.DEMO_USER_MESSAGE, null, false, "127.0.0.1", 0);
    try (App app = App.start(config)) {
      // demo 回合确实跑了（否则"没落库"可能只是因为它压根没动）
      assertThat(app.eventCount()).isGreaterThanOrEqualTo(2);
    }

    try (Connection connection =
            DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("events.db"));
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
                    + " AND name IN ('conversations', 'messages')");
        ResultSet rows = statement.executeQuery()) {
      assertThat(rows.next()).as("demo 分叉不得打开会话存储（表在 = 被打开过）").isFalse();
    }
  }

  /** 非 demo 的生产形态（不做子 Agent 编排、不暴露 MCP：与本类判据无关的装配面一律取最小）。 */
  private static BootConfig bootConfig(Path dataDir) {
    return new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);
  }

  /** 记录每次收到的 {@link LlmRequest} 的脚本桩（回灌断言必须看请求内容，不能只看返回文本）。 */
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
}
