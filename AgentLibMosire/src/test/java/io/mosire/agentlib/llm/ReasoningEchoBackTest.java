package io.mosire.agentlib.llm;

import static io.mosire.agentlib.llm.FakeChatEndpoint.contentFrame;
import static io.mosire.agentlib.llm.FakeChatEndpoint.reasoningContentFrame;
import static io.mosire.agentlib.llm.FakeChatEndpoint.toolCallFrame;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.agentlib.store.SqliteConversationStore;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>A6 验收</b>：思维链的<b>回传</b>——{@code reasoning_content} 不只是"读得到"，还要"发得回去"。
 *
 * <p><b>为什么要有这一层</b>（simos 侧真 provider 实测，非推断）：决策人多轮对话跑第二轮时，思考模式的供应商回 {@code HTTP 400: The
 * "reasoning_content" in the thinking mode must be passed back to the
 * API.}——第一轮没有历史所以不撞，<b>只有多轮才暴露</b>。 A1 已经把"读到"做全了（{@link LlmResponse#reasoning()}），但当时 {@link
 * LlmMessage} 里<b>没有承载它的位置</b>，发送侧自然也发不出去 ⇒ 缺口在"回传"这一环，本类逐条钉住它。
 *
 * <p><b>三个可观测面</b>（都不靠读代码确认）：
 *
 * <ol>
 *   <li><b>发送侧</b>：{@code jdk.httpserver} 假端点捕获的<b>真实请求体</b>里，assistant 消息有没有 {@code
 *       reasoning_content}，以及 <b>没有思维链时那个键必须完全不出现</b>（不是空串——给别的供应商塞空字段是自找 400）。
 *   <li><b>响应侧</b>：{@link LlmResponse#assistantMessage()} 自己就带着思维链，于是消费方那句"把上一条 assistant 原样追加进历史"
 *       <b>零改动</b>即可回传。
 *   <li><b>存储侧</b>：历史是要落盘、下一 tick 再读回来的（simos 的 {@code conversations.load} 正是这条路）⇒ 存储不往返，
 *       修复就只在单进程内成立。
 * </ol>
 */
class ReasoningEchoBackTest {

  private static final String ROUTE_MODEL = "reasoning-echoback-route-fake";
  private static final String CONV = "conv-echo";

  @TempDir Path tempDir;

  private FakeChatEndpoint endpoint;

  @BeforeEach
  void startFakeEndpoint() throws IOException {
    endpoint = FakeChatEndpoint.start();
  }

  @AfterEach
  void stopFakeEndpoint() {
    endpoint.close();
  }

  // ---------- ① 发送侧：有思维链 ⇒ 发 reasoning_content ----------

  /**
   * assistant 消息带着思维链 ⇒ 请求体里出现 {@code reasoning_content}，<b>与原值逐字相同</b>，且不影响同一条消息的 {@code
   * tool_calls}（工具调用与思维链是并列的两件事，回传思维链不能把它们挤掉）。
   */
  @Test
  void sendsReasoningContentBackForAssistantMessageCarryingReasoning() throws IOException {
    endpoint.sayJson(contentFrame("继续")).done();

    LlmMessage assistant =
        LlmMessage.assistant(
            List.of(new ContentPart.ToolCall("call_1", "echo", Map.of("text", "hi"))),
            "先想：该调 echo 了");
    LlmRequest request =
        LlmRequest.ofMessages(
            List.of(
                LlmMessage.user("看看"),
                assistant,
                LlmMessage.tool(new ContentPart.ToolResult("call_1", "echo", "echo: hi", null))));

    endpoint.client(ROUTE_MODEL).chat(request);

    JsonNode sent = endpoint.requestJson(0).path("messages").path(1);
    assertThat(sent.path("role").asText()).isEqualTo(LlmMessage.ROLE_ASSISTANT);
    assertThat(sent.path("reasoning_content").asText()).isEqualTo("先想：该调 echo 了");
    // 工具调用仍在（回传思维链不得以牺牲工具调用为代价）
    assertThat(sent.path("tool_calls")).hasSize(1);
    assertThat(sent.path("tool_calls").path(0).path("function").path("name").asText())
        .isEqualTo("echo");
  }

  /**
   * <b>反面对照</b>（与上一条同等重要）：没有思维链的消息，{@code reasoning_content} 这个键<b>根本不出现</b>——不是空串、也不是 {@code
   * null}。给不支持该字段的供应商塞一个空值，是另一种自找 400；两个方向都要有判据，否则"实现成恒发"照样全绿。
   */
  @Test
  void omitsReasoningContentFieldEntirelyWhenMessageHasNoReasoning() throws IOException {
    endpoint.sayJson(contentFrame("在的")).done();

    endpoint
        .client(ROUTE_MODEL)
        .chat(
            LlmRequest.ofMessages(
                List.of(
                    LlmMessage.user("在吗"),
                    LlmMessage.assistant(List.of(new ContentPart.Text("在的"))))));

    JsonNode messages = endpoint.requestJson(0).path("messages");
    assertThat(messages.path(0).has("reasoning_content")).isFalse();
    assertThat(messages.path(1).has("reasoning_content")).isFalse();
    // 反面对照的对照：这条消息本身确实发出去了（否则"键不存在"可能只是因为压根没这条消息）
    assertThat(messages.path(1).path("content").asText()).isEqualTo("在的");
  }

  // ---------- ② 回归 pin：真实体感的那一轮（第二轮 400） ----------

  /**
   * <b>端到端回声</b>：第一轮供应商回了思维链 + 工具调用；消费方<b>零改动</b>（就是把 {@link LlmResponse#assistantMessage()}
   * 原样追加进历史）；第二轮请求的 assistant 消息上带着第一轮的 {@code reasoning_content}。
   *
   * <p>这正是 simos 侧真 provider 报 400 的那条路径。它比上面两条更强：断言的是"消费方照现有写法走一遍"的结果，而不是"我们手工造一条带 reasoning 的消息"。
   */
  @Test
  void secondTurnEchoesTheReasoningOfTheFirstTurnWithoutConsumerChanges() throws IOException {
    OpenAICompatibleLlmClient client = endpoint.client(ROUTE_MODEL);
    endpoint
        .sayJson(reasoningContentFrame("先想：查一下地图"))
        .sayJson(toolCallFrame("call_7", "map_overview", "{}"))
        .done();

    LlmResponse first = client.chat(LlmRequest.ofMessages(List.of(LlmMessage.user("看看地图"))));

    // 消费方的现有写法（simos DecisionAgentRunner / brain AgentPipeline 都是这一行）
    List<LlmMessage> history = new ArrayList<>();
    history.add(LlmMessage.user("看看地图"));
    history.add(first.assistantMessage());

    endpoint.sayJson(contentFrame("地图是这样的")).done();
    client.chat(LlmRequest.ofMessages(List.copyOf(history)));

    JsonNode echoed = endpoint.requestJson(1).path("messages").path(1);
    assertThat(echoed.path("role").asText()).isEqualTo(LlmMessage.ROLE_ASSISTANT);
    assertThat(echoed.path("reasoning_content").asText()).isEqualTo("先想：查一下地图");
    assertThat(echoed.path("tool_calls")).hasSize(1);
  }

  // ---------- ③ 响应侧：assistantMessage 自带思维链 ----------

  /** {@code SEPARATE}：正文与思维链并存时，assistant 消息也带着思维链（与 {@link LlmResponse#reasoning()} 同一份原文）。 */
  @Test
  void responseAssistantMessageCarriesReasoningWhenSeparate() {
    endpoint.sayJson(reasoningContentFrame("先想：用户问在吗")).sayJson(contentFrame("在的")).done();

    LlmResponse response = endpoint.client(ROUTE_MODEL).chat(oneUserTurn());

    assertThat(response.assistantMessage().reasoning()).isEqualTo("先想：用户问在吗");
    assertThat(response.assistantMessage().reasoning()).isEqualTo(response.reasoning());
    // 正文里仍不得混进思维链（A1 的判据不被本次改动放松）
    assertThat(response.assistantMessage().content()).containsExactly(new ContentPart.Text("在的"));
  }

  /** {@code FOLDED}（正文空、思维链被折成正文）：assistant 消息同样带上思维链——它与正文是两件事。 */
  @Test
  void responseAssistantMessageCarriesReasoningEvenWhenFolded() {
    endpoint.sayJson(reasoningContentFrame("额度全花在思考上了")).done();

    LlmResponse response = endpoint.client(ROUTE_MODEL).chat(oneUserTurn());

    assertThat(response.reasoningDisposition()).isEqualTo(LlmResponse.ReasoningDisposition.FOLDED);
    assertThat(response.assistantMessage().reasoning()).isEqualTo("额度全花在思考上了");
    assertThat(response.textPart()).contains("额度全花在思考上了");
  }

  /** 供应商没下发思维链 ⇒ assistant 消息的 reasoning 是<b>空串</b>（不是 null：调用方不该在这里吃 NPE）。 */
  @Test
  void responseAssistantMessageHasEmptyReasoningWhenVendorSentNone() {
    endpoint.sayJson(contentFrame("普通模型的普通回答")).done();

    LlmResponse response = endpoint.client(ROUTE_MODEL).chat(oneUserTurn());

    assertThat(response.assistantMessage().reasoning()).isNotNull().isEmpty();
    assertThat(response.reasoningDisposition()).isEqualTo(LlmResponse.ReasoningDisposition.ABSENT);
  }

  // ---------- ④ 兼容性：既有构造点逐字不动 ----------

  /**
   * 兼容性硬要求（{@code BrainMosire} 与 simos 都在用本库）：<b>两参构造器与四个既有工厂全部保留</b>，产出的消息 reasoning 为空串。
   * 本用例的价值不在断言本身，而在于它是"这些调用点仍然编译得过 + 语义仍是老样子"的锚。
   */
  @Test
  void legacyConstructorsAndFactoriesDefaultToEmptyReasoning() {
    LlmMessage fromTwoArg = new LlmMessage("assistant", List.of(new ContentPart.Text("老调用点")));
    LlmMessage toolResult = LlmMessage.tool(new ContentPart.ToolResult("c", "n", "ok", null));

    assertThat(fromTwoArg.reasoning()).isEmpty();
    assertThat(LlmMessage.system("s").reasoning()).isEmpty();
    assertThat(LlmMessage.user("u").reasoning()).isEmpty();
    assertThat(LlmMessage.assistant(List.of(new ContentPart.Text("a"))).reasoning()).isEmpty();
    assertThat(toolResult.reasoning()).isEmpty();
    // 老调用点的其余语义不变
    assertThat(fromTwoArg.role()).isEqualTo("assistant");
    assertThat(fromTwoArg.content()).containsExactly(new ContentPart.Text("老调用点"));
  }

  /** {@code withReasoning} 是<b>换一份</b>（不可变），且换出来的消息只有思维链变了——其余组件逐字相同。 */
  @Test
  void withReasoningReturnsACopyAndLeavesTheOriginalAlone() {
    LlmMessage base = LlmMessage.assistant(List.of(new ContentPart.Text("正文")));

    LlmMessage withReasoning = base.withReasoning("想了一下");

    assertThat(withReasoning.reasoning()).isEqualTo("想了一下");
    assertThat(withReasoning.role()).isEqualTo(base.role());
    assertThat(withReasoning.content()).isEqualTo(base.content());
    assertThat(base.reasoning()).isEmpty();
  }

  /** {@code null} 思维链按"没有思维链"收口成空串（与 {@link LlmResponse} 同一口径）。 */
  @Test
  void normalizesNullReasoningToEmptyString() {
    assertThat(new LlmMessage("assistant", List.of(new ContentPart.Text("t")), null).reasoning())
        .isEmpty();
    assertThat(LlmMessage.assistant(List.of(new ContentPart.Text("t")), null).reasoning())
        .isEmpty();
  }

  // ---------- ⑤ 存储侧：历史落盘再读回，思维链还在 ----------

  /**
   * 往返：带思维链的 assistant 消息落盘后原样读回（simos 每轮的 {@code conversations.load} 就是这条路——存储不往返，修复就只在 单进程内成立，下一
   * tick 又 400）。
   */
  @Test
  void conversationStoreRoundTripsReasoningAttachedToAssistantMessage() {
    LlmMessage withReasoning =
        LlmMessage.assistant(
            List.of(new ContentPart.ToolCall("c-1", "echo", Map.of("text", "hi"))), "先想：调 echo");
    LlmMessage withoutReasoning = LlmMessage.assistant(List.of(new ContentPart.Text("完事")));

    try (SqliteConversationStore store = SqliteConversationStore.open(db())) {
      store.append(CONV, withReasoning);
      store.append(CONV, withoutReasoning);

      assertThat(store.load(CONV)).containsExactly(withReasoning, withoutReasoning);
    }
  }

  /**
   * 落盘形态（直接读 {@code messages.content} 列的字节）：<b>有</b>思维链才写 {@code reasoning} 键，没有就<b>一个字节都不动</b>。
   *
   * <p>为什么"没有就不写"值得单独钉：本改动动的是已经落盘的格式，若实现成"恒写空串"，则所有既有消息的落盘字节都会变——而它们在语义上
   * 什么都没变。让"无思维链"这条最普遍的路径逐字不变，是兼容性里最便宜、也最容易悄悄丢掉的一条。
   */
  @Test
  void persistedJsonCarriesReasoningKeyOnlyWhenThereIsOne() throws Exception {
    Path db = db();
    try (SqliteConversationStore store = SqliteConversationStore.open(db)) {
      store.append(CONV, LlmMessage.assistant(List.of(new ContentPart.Text("有思维链")), "先想：想过了"));
      store.append(CONV, LlmMessage.assistant(List.of(new ContentPart.Text("没有思维链"))));
    }

    List<String> encoded = new ArrayList<>();
    try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + db);
        PreparedStatement ps =
            raw.prepareStatement(
                "SELECT content FROM messages WHERE conversation_id = ? ORDER BY id")) {
      ps.setString(1, CONV);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          encoded.add(rs.getString(1));
        }
      }
    }

    assertThat(encoded).hasSize(2);
    assertThat(encoded.get(0)).contains("\"reasoning\":\"先想：想过了\"");
    assertThat(encoded.get(1)).doesNotContain("reasoning");
  }

  /**
   * <b>老档兼容</b>（直接往库里写改版前那种 JSON 行）：没有 {@code reasoning} 键的历史消息照常解码，思维链按空串补齐。
   *
   * <p>为什么必须有一条这样的用例：本改动动的是<b>已经落盘过</b>的格式。若解码写成"缺该键就抛"或"缺该键给 null"，症状是升级后所有
   * 老会话一审就炸——而那正是"跑了一天的档忽然读不开"的形态。
   */
  @Test
  void decodesLegacyRowsWrittenBeforeReasoningExisted() throws Exception {
    Path db = db();
    try (SqliteConversationStore store = SqliteConversationStore.open(db)) {
      store.append(CONV, LlmMessage.user("老档的一轮"));
    }
    try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + db);
        PreparedStatement ps =
            raw.prepareStatement(
                "INSERT INTO messages (conversation_id, role, content, ts)"
                    + " VALUES (?, ?, ?, ?)")) {
      // 改版前 encode 出来的字节：只有 role + parts，没有 reasoning 键
      ps.setString(1, CONV);
      ps.setString(2, LlmMessage.ROLE_ASSISTANT);
      ps.setString(
          3, "{\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"text\":\"老档的回答\"}]}");
      ps.setString(4, "2026-09-21T00:00:00Z");
      ps.executeUpdate();
    }

    try (SqliteConversationStore store = SqliteConversationStore.open(db)) {
      List<LlmMessage> loaded = store.load(CONV);

      assertThat(loaded).hasSize(2);
      assertThat(loaded.get(1).role()).isEqualTo(LlmMessage.ROLE_ASSISTANT);
      assertThat(loaded.get(1).content()).containsExactly(new ContentPart.Text("老档的回答"));
      assertThat(loaded.get(1).reasoning()).isEmpty();
    }
  }

  // ---------- 辅助 ----------

  private Path db() {
    return tempDir.resolve("echo.db");
  }

  private static LlmRequest oneUserTurn() {
    return LlmRequest.ofMessages(List.of(LlmMessage.user("在吗")));
  }
}
