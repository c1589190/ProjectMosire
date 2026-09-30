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
 * API.}——第一轮没有历史所以不撞，<b>只有多轮才暴露</b>。 A1 已经把"读到"做全了（{@link LlmResponse#reasoning()}），但第一版 {@link
 * LlmMessage} 把"没有该字段"与"字段存在但为空"合并成一个空串：模型某次收口没产思维链时，历史 assistant 消息在线上缺键，第二轮再 400。修复后由路由能力位 +
 * {@link LlmMessage.ReasoningState} 三态共同把两个方向分开，本类逐条钉住它。
 *
 * <p><b>两个可分辨方向</b>：
 *
 * <ul>
 *   <li><b>思考路由</b>（{@link LlmTransport#echoReasoningContent()} = true）：assistant
 *       消息<b>恒发</b>该键；没有思维链内容时发 {@code ""}，且解析层用 {@link LlmMessage.ReasoningState#EMPTY}
 *       把"供应商明确发过空串"这个事实带回历史。
 *   <li><b>非思考路由</b>（false）：维持"有思维链正文才发"；没有任何内容时该键<b>完全不出现</b>（不是空串——给不认该字段的供应商塞空字段是自找 400）。
 * </ul>
 *
 * <p><b>三个可观测面</b>（都不靠读代码确认）：
 *
 * <ol>
 *   <li><b>发送侧</b>：{@code jdk.httpserver} 假端点捕获的<b>真实请求体</b>里，assistant 消息有没有 {@code
 *       reasoning_content}，以及它是否按路由能力位分成"恒发（思考）"与"有才发（非思考）"两种形态。
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
   * <b>非思考路由的反面对照</b>（与上一条同等重要）：没有思维链的消息，{@code reasoning_content} 这个键<b>根本不出现</b>——不是空串、也不是 {@code
   * null}。给不支持该字段的供应商塞一个空值，是另一种自找 400；两个方向都要有判据，否则"实现成恒发"照样全绿。
   *
   * <p>本用例的夹具默认路由（{@link FakeChatEndpoint#client(String)}）就是非思考路由；思考路由的恒发行为由下一条钉住。两者必须同时存在。
   */
  @Test
  void nonThinkingRouteOmitsReasoningContentFieldEntirelyWhenMessageHasNoReasoning()
      throws IOException {
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

  /**
   * <b>思考路由的正面判据</b>：assistant 消息没有思维链正文时，请求体里也必须出现 {@code reasoning_content}，且值为空串。
   *
   * <p>只钉"有才发"会让非思考供应商安全，却让思考模式多轮在第二次请求缺键 400；本用例与上一条一起把"发/不发"两侧都钉死在路由能力位上。
   */
  @Test
  void thinkingRouteSendsEmptyReasoningContentForAssistantWithoutReasoningContent()
      throws IOException {
    endpoint.sayJson(contentFrame("在的")).done();

    endpoint
        .client(ROUTE_MODEL, true)
        .chat(
            LlmRequest.ofMessages(
                List.of(
                    LlmMessage.user("在吗"),
                    LlmMessage.assistant(List.of(new ContentPart.Text("在的"))))));

    JsonNode messages = endpoint.requestJson(0).path("messages");
    assertThat(messages.path(0).has("reasoning_content"))
        .as("user 消息不是模型产出，思考路由也不得给它塞 reasoning_content")
        .isFalse();
    JsonNode assistant = messages.path(1);
    assertThat(assistant.path("reasoning_content").isTextual())
        .as("思考路由的 assistant 消息必须带该键（内容为空就发空串）")
        .isTrue();
    assertThat(assistant.path("reasoning_content").asText()).isEmpty();
  }

  /**
   * 消息本身显式带 {@code ReasoningState.EMPTY} 时，即使路由不是思考模式，也要把"键存在但为空"这个事实发出去。
   *
   * <p>它是存储回放/手工构造路径的守卫：调用方既然明确说了"这条消息有该字段"，发送侧就不能按默认的"空 = 没有"把它吞掉。
   */
  @Test
  void explicitEmptyReasoningStateIsSentEvenOnNonThinkingRoute() throws IOException {
    endpoint.sayJson(contentFrame("继续")).done();

    LlmMessage explicitEmpty =
        LlmMessage.assistantEchoingEmptyReasoning(List.of(new ContentPart.Text("收口")));
    endpoint
        .client(ROUTE_MODEL)
        .chat(LlmRequest.ofMessages(List.of(LlmMessage.user("继续"), explicitEmpty)));

    JsonNode assistant = endpoint.requestJson(0).path("messages").path(1);
    assertThat(assistant.path("reasoning_content").isTextual()).isTrue();
    assertThat(assistant.path("reasoning_content").asText()).isEmpty();
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

  /**
   * <b>本缺陷的最直接回归 pin</b>：第一轮响应<b>完全没有</b> {@code reasoning_content}（模型收口时没产思维链），消费方仍只是把
   * assistantMessage 原样追加；在思考路由上，第二轮请求的 assistant 消息必须带该键且值为空串。
   *
   * <p>这正是 2026-09-30 真 provider 报 400 的形态：第一版 A6 只在有思维链正文时写键，模型某次收口无思维链时历史缺键，第二次 LLM 调用即挂。
   * 这里用工具调用响应覆盖"assistant 消息有 tool_calls 但没有 reasoning_content"的形态。
   */
  @Test
  void thinkingRouteEchoesEmptyReasoningContentOnSecondTurnWhenFirstResponseHadNone()
      throws IOException {
    OpenAICompatibleLlmClient client = endpoint.client(ROUTE_MODEL, true);
    endpoint.sayJson(toolCallFrame("call_9", "echo", "{}")).done();

    LlmResponse first = client.chat(LlmRequest.ofMessages(List.of(LlmMessage.user("调一下"))));
    assertThat(first.reasoningState()).isEqualTo(LlmMessage.ReasoningState.ABSENT);
    assertThat(first.assistantMessage().hasReasoningField()).isFalse();

    List<LlmMessage> history = new ArrayList<>();
    history.add(LlmMessage.user("调一下"));
    history.add(first.assistantMessage());
    history.add(LlmMessage.tool(new ContentPart.ToolResult("call_9", "echo", "echo: ok", null)));

    endpoint.sayJson(contentFrame("处理完了")).done();
    client.chat(LlmRequest.ofMessages(List.copyOf(history)));

    JsonNode echoed = endpoint.requestJson(1).path("messages").path(1);
    assertThat(echoed.path("role").asText()).isEqualTo(LlmMessage.ROLE_ASSISTANT);
    assertThat(echoed.path("reasoning_content").isTextual())
        .as("思考路由下历史 assistant 消息缺该键就是复现 400 的缺口")
        .isTrue();
    assertThat(echoed.path("reasoning_content").asText()).isEmpty();
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

  /** 供应商没下发思维链 ⇒ assistant 消息的 reasoning 是<b>空串</b>（不是 null：调用方不该在这里吃 NPE），三态是 {@code ABSENT}。 */
  @Test
  void responseAssistantMessageHasEmptyReasoningWhenVendorSentNone() {
    endpoint.sayJson(contentFrame("普通模型的普通回答")).done();

    LlmResponse response = endpoint.client(ROUTE_MODEL).chat(oneUserTurn());

    assertThat(response.assistantMessage().reasoning()).isNotNull().isEmpty();
    assertThat(response.reasoningState()).isEqualTo(LlmMessage.ReasoningState.ABSENT);
    assertThat(response.assistantMessage().hasReasoningField()).isFalse();
    assertThat(response.reasoningDisposition()).isEqualTo(LlmResponse.ReasoningDisposition.ABSENT);
  }

  /**
   * 供应商<b>明确下发了空 {@code reasoning_content}</b> ⇒ 三态必须是 {@code EMPTY}，不能与"压根没下发"合并。
   *
   * <p>这是 A6 修复版的数据模型底线：没有这个区分，存储往返与下一次请求都补不回"键曾经存在"的事实。
   */
  @Test
  void responseAssistantMessageHasExplicitEmptyReasoningStateWhenVendorSentEmptyField() {
    endpoint.sayJson(reasoningContentFrame("")).sayJson(contentFrame("普通回答")).done();

    LlmResponse response = endpoint.client(ROUTE_MODEL).chat(oneUserTurn());

    assertThat(response.assistantMessage().reasoning()).isEmpty();
    assertThat(response.reasoningState()).isEqualTo(LlmMessage.ReasoningState.EMPTY);
    assertThat(response.assistantMessage().hasReasoningField()).isTrue();
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
    assertThat(fromTwoArg.reasoningState()).isEqualTo(LlmMessage.ReasoningState.ABSENT);
    assertThat(LlmMessage.system("s").reasoning()).isEmpty();
    assertThat(LlmMessage.user("u").reasoning()).isEmpty();
    assertThat(LlmMessage.assistant(List.of(new ContentPart.Text("a"))).reasoning()).isEmpty();
    assertThat(LlmMessage.assistant(List.of(new ContentPart.Text("a"))).reasoningState())
        .isEqualTo(LlmMessage.ReasoningState.ABSENT);
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
    assertThat(withReasoning.reasoningState()).isEqualTo(LlmMessage.ReasoningState.TEXT);
    assertThat(withReasoning.role()).isEqualTo(base.role());
    assertThat(withReasoning.content()).isEqualTo(base.content());
    assertThat(base.reasoning()).isEmpty();
  }

  /** {@code null} 思维链按"没有思维链"收口成空串（与 {@link LlmResponse} 同一口径）。 */
  @Test
  void normalizesNullReasoningToEmptyString() {
    assertThat(new LlmMessage("assistant", List.of(new ContentPart.Text("t")), null).reasoning())
        .isEmpty();
    assertThat(
            new LlmMessage("assistant", List.of(new ContentPart.Text("t")), null).reasoningState())
        .isEqualTo(LlmMessage.ReasoningState.ABSENT);
    assertThat(LlmMessage.assistant(List.of(new ContentPart.Text("t")), null).reasoning())
        .isEmpty();
    assertThat(LlmMessage.assistant(List.of(new ContentPart.Text("t")), null).reasoningState())
        .isEqualTo(LlmMessage.ReasoningState.ABSENT);
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
    LlmMessage explicitEmpty =
        LlmMessage.assistantEchoingEmptyReasoning(List.of(new ContentPart.Text("空思维链但键在")));
    LlmMessage withoutReasoning = LlmMessage.assistant(List.of(new ContentPart.Text("完事")));

    try (SqliteConversationStore store = SqliteConversationStore.open(db())) {
      store.append(CONV, withReasoning);
      store.append(CONV, explicitEmpty);
      store.append(CONV, withoutReasoning);

      List<LlmMessage> loaded = store.load(CONV);
      assertThat(loaded).containsExactly(withReasoning, explicitEmpty, withoutReasoning);
      assertThat(loaded.get(1).reasoningState()).isEqualTo(LlmMessage.ReasoningState.EMPTY);
      assertThat(loaded.get(1).hasReasoningField()).isTrue();
      assertThat(loaded.get(2).reasoningState()).isEqualTo(LlmMessage.ReasoningState.ABSENT);
      assertThat(loaded.get(2).hasReasoningField()).isFalse();
    }
  }

  /**
   * 落盘形态（直接读 {@code messages.content} 列的字节）：<b>字段存在</b>就写 {@code reasoning} 键（有正文写原文、显式空串写 {@code
   * ""}），字段缺席才<b>一个字节都不动</b>。
   *
   * <p>为什么"缺席不写"值得单独钉：本改动动的是已经落盘的格式，若实现成"恒写空串"，则所有既有消息的落盘字节都会变——而它们在语义上
   * 什么都没变。让"无该字段"这条最普遍的路径逐字不变，是兼容性里最便宜、也最容易悄悄丢掉的一条；同时"显式空串"又必须有独立可分辨的落盘形态。
   */
  @Test
  void persistedJsonCarriesReasoningKeyExactlyWhenTheFieldExists() throws Exception {
    Path db = db();
    try (SqliteConversationStore store = SqliteConversationStore.open(db)) {
      store.append(CONV, LlmMessage.assistant(List.of(new ContentPart.Text("有思维链")), "先想：想过了"));
      store.append(
          CONV, LlmMessage.assistantEchoingEmptyReasoning(List.of(new ContentPart.Text("显式空思维链"))));
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

    assertThat(encoded).hasSize(3);
    assertThat(encoded.get(0)).contains("\"reasoning\":\"先想：想过了\"");
    assertThat(encoded.get(1))
        .as("显式空串必须落成 reasoning 键（存在但空），不能与缺席合并")
        .contains("\"reasoning\":\"\"");
    assertThat(encoded.get(2)).doesNotContain("reasoning");
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
      assertThat(loaded.get(1).reasoningState()).isEqualTo(LlmMessage.ReasoningState.ABSENT);
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
