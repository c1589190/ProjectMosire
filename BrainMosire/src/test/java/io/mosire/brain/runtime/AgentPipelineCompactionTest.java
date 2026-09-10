package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
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
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.context.BasicContextAssembler;
import io.mosire.brain.context.CompactSummary;
import io.mosire.brain.context.CompactSummarySlot;
import io.mosire.brain.context.Compactor;
import io.mosire.brain.context.ContextAssembler;
import io.mosire.brain.context.ContextLayer;
import io.mosire.brain.context.ContextPolicy;
import io.mosire.brain.context.ContextSources;
import io.mosire.brain.skills.Skill;
import io.mosire.brain.skills.SkillCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 会话压缩的管线接线（T17）：{@link AgentPipeline} + {@link Compactor} + {@link CompactSummarySlot} + {@link
 * SqliteConversationStore} 的联调。
 *
 * <p><b>夹具的字符账</b>：估算口径 = 字符数 / 4（同 {@code ContextComposition}）。{@link #historyForTierTwo()} 共 18
 * 条——头一对各 4000 字符（≈2010 token，档 1 会保留头部，故档 1 视图恒 ≥ 2010 token）、尾 6 条各 ~110 字符（≈170 token）、 全量 ≈
 * 2330 token。于是：预算 300 → 档 1 放不下、档 2 的尾部放得下（档 2）；预算 60 → 连尾部都放不下（档 3）；预算 1000 用 {@link
 * #historyForTierOne()}（中段 10 条各 ~2000 字符，头尾极小）→ 档 1 够用且不花一次 LLM 调用。
 *
 * <p><b>每条用例都断言"摘要器被调了几次"</b>：阶梯的验收句是"先试便宜的、禁止跳级花钱"——只看档位的话，"跳级调了 LLM 但档位报成 micro"这类实现能蒙混过去。
 *
 * <p>本类不改既有的 {@code AgentPipelineTest}（9 参构造的行为锁）与 {@code AgentPipelineConversationStoreTest}（T16 的
 * store 行为锁）：只写新增的 13 参构造 + 压缩路径。
 */
class AgentPipelineCompactionTest {

  private static final String CONV = "conv-1";

  /** 行为提示哨兵：R3 的判据要能断言"它没有进摘要器、也没被压缩弄丢"。 */
  private static final String SYSTEM_CANARY = "SYSTEM-PROMPT-CANARY-7b1e";

  private static final String SKILL_CANARY = "SKILL-CANARY-4f2a";

  private static final String COMPACT_SUMMARY_HEADER = "此前会话的压缩摘要（较早的消息已被压缩，以下为要点）：";

  private static final String SUMMARY_JSON =
      """
      {"objective":"目标哨兵","completed":["完成哨兵"],"pending":["待办哨兵"],"decisions":["决策哨兵"],
       "changedFiles":["文件哨兵"],"errors":["错误哨兵"],"activeSkills":["技能哨兵"],"unresolved":["未决哨兵"]}
      """;

  private static final CompactSummary EXPECTED =
      new CompactSummary(
          "目标哨兵",
          List.of("完成哨兵"),
          List.of("待办哨兵"),
          List.of("决策哨兵"),
          List.of("文件哨兵"),
          List.of("错误哨兵"),
          List.of("技能哨兵"),
          List.of("未决哨兵"));

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private Path db;

  @BeforeEach
  void setUp() {
    db = tempDir.resolve("agent.db");
  }

  /**
   * R9 的"未压缩时 byte-identical"：同一个回合里，<b>没接压缩器</b>与<b>接了压缩器但没触门</b>（默认策略只配
   * SYSTEM）产出的请求逐字节一致——两个回合都比（第一回合无历史、第二回合带历史回灌）。
   *
   * <p>判别性：任何"接线即改变请求"的实现（哪怕只是多一个空段落、多一条空摘要消息、多一次 llm 调用）在 {@code isEqualTo} 或事件断言上必红；{@link
   * UnscriptedSummarizer} 让"未触门也调一次摘要"的实现直接炸红。
   */
  @Test
  void unwiredAndUntriggeredPipelinesProduceByteIdenticalRequests() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      ScriptedClient plain = ScriptedClient.answering("回答一", "回答二");
      ScriptedClient wired = ScriptedClient.answering("回答一", "回答二");
      ContextAssembler assembler = assembler(CompactSummarySlot.empty());
      AgentPipeline withoutCompactor =
          pipeline(
              plain, events, bus, store, "conv-plain", null, CompactSummarySlot.empty(), assembler);
      AgentPipeline untriggered =
          pipeline(
              wired,
              events,
              bus,
              store,
              "conv-wired",
              new Compactor(ContextPolicy.defaults(), new UnscriptedSummarizer()),
              CompactSummarySlot.empty(),
              assembler);

      withoutCompactor.run("问题一");
      untriggered.run("问题一");
      withoutCompactor.run("问题二");
      untriggered.run("问题二");

      assertThat(wired.requests).hasSize(2);
      assertThat(wired.requests.get(0).messages())
          .isEqualTo(plain.requests.get(0).messages())
          .hasSize(2); // [system, user]——没有摘要段、没有占位
      assertThat(wired.requests.get(1).messages())
          .isEqualTo(plain.requests.get(1).messages())
          .hasSize(4); // [system, u1, a1, u2]——历史回灌逐字节一致
      // 未触发 = 事件流里没有任何压缩痕迹（R4 的否定侧）
      assertThat(eventsOf(events, EventTypes.CONVERSATION_COMPACT)).isEmpty();
      assertThat(eventsOf(events, EventTypes.LLM_CALL))
          .isNotEmpty()
          .noneMatch(event -> event.payload().contains("compact"));
    }
  }

  /**
   * R1 档 2 + R5 + R3：中段被摘要后，摘要进 {@code COMPACT_SUMMARY} 层（八字段全文）、会话只剩尾部、持久层一个字节不改，
   * 且<b>摘要器的输入里没有持久层的任何字节</b>。
   *
   * <p>判别性：每一条都有对应的错实现被抓——摘要只进了内存不进层（{@code systemText} 不含八字段）、工作集没换（中段内容还在请求里）、 摘要器拿到了 agent 的
   * system 提示/技能目录（R3 禁止"把持久层烤进摘要"）、持久层被压缩顶掉（{@code startsWith}/{@code contains} 技能行）、摘要器带了工具。
   */
  @Test
  void tierTwoSummaryLandsInTheCompactSummaryLayerAndTheTailStaysVerbatim() throws Exception {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      List<LlmMessage> seeded = historyForTierTwo();
      for (LlmMessage message : seeded) {
        store.append(CONV, message);
      }
      CompactSummarySlot slot = CompactSummarySlot.empty();
      ScriptedClient llm = ScriptedClient.answering("这一轮的回答");
      ScriptedSummarizer summarizer = ScriptedSummarizer.returning(SUMMARY_JSON);
      AgentPipeline pipeline =
          pipeline(
              llm,
              events,
              bus,
              store,
              CONV,
              new Compactor(policy(300), summarizer),
              slot,
              assembler(slot));

      assertThat(pipeline.run("新问题").stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(summarizer.calls()).isEqualTo(1);

      List<LlmMessage> request = llm.requests.get(0).messages();
      String systemText = systemTextOf(request);
      // 档 2 的落点：八字段摘要全文进 COMPACT_SUMMARY 层
      assertThat(systemText)
          .contains(COMPACT_SUMMARY_HEADER)
          .contains("目标哨兵")
          .contains("已完成：\n- 完成哨兵")
          .contains("未决问题：\n- 未决哨兵");
      // 会话 = 尾部 6 条 + 本轮 user；中段（含被丢弃的长头部）在请求里彻底不见
      assertThat(request.subList(1, request.size()))
          .containsExactlyElementsOf(
              concat(seeded.subList(12, seeded.size()), List.of(LlmMessage.user("新问题"))));
      assertThat(systemText).doesNotContain("HIST#0 ").doesNotContain("HIST#2 ");
      // R3：持久层照旧（system 前缀 + 技能目录都在），压缩只换了会话
      assertThat(systemText).startsWith(SYSTEM_CANARY).contains("- alpha: " + SKILL_CANARY);
      // R3 的另一半：摘要器的输入 = 恰好被丢弃的中段，且没有 SYSTEM 提示/技能目录的任何字节、没有工具
      LlmRequest summaryRequest = summarizer.requests().get(0);
      String summarizerInput = allTextOf(summaryRequest.messages());
      assertThat(summaryRequest.tools()).isEmpty();
      assertThat(summarizerInput).contains("HIST#0 ").contains("HIST#2 ");
      assertThat(summarizerInput).doesNotContain(SYSTEM_CANARY).doesNotContain(SKILL_CANARY);
      assertThat(summaryRequest.messages())
          .filteredOn(message -> LlmMessage.ROLE_SYSTEM.equals(message.role()))
          .hasSize(1);
      // 本回合尾部照常 append（档 2 不碰存储：load 回来的仍是完整历史）
      assertThat(store.load(CONV)).hasSize(seeded.size() + 2);
      assertThat(pipeline.lastHistory()).hasSize(8);
      // 事件：档位/丢弃条数/保留条数/摘要 token（R4）——keptMessages 与"摘要已进层"互为佐证
      JsonNode payload = onlyPayload(events, EventTypes.CONVERSATION_COMPACT);
      assertThat(payload.get("tier").asText()).isEqualTo("summary");
      assertThat(payload.get("droppedMessages").asInt()).isEqualTo(12);
      assertThat(payload.get("keptMessages").asInt()).isEqualTo(6);
      assertThat(payload.get("summaryTokens").asInt()).isEqualTo(EXPECTED.render().length() / 4);
    }
  }

  /**
   * R7：摘要调用是<b>额外</b>的 LLM 调用，它的 token 记账必须落 {@code llm.call}（带 {@code "phase":"compact"}、不带 {@code
   * turns}）——P2-3 的 token 汇总因此看得见压缩成本。
   *
   * <p>判别性：不落这笔账的实现（压缩成本在 D18 的账本上凭空消失）在这里必红；把它记成主循环回合（带 turns）的实现也会红。
   */
  @Test
  void summarizerCallIsAccountedAsAnExtraLlmCallEvent() throws Exception {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      for (LlmMessage message : historyForTierTwo()) {
        store.append(CONV, message);
      }
      CompactSummarySlot slot = CompactSummarySlot.empty();
      AgentPipeline pipeline =
          pipeline(
              ScriptedClient.answering("这一轮的回答"),
              events,
              bus,
              store,
              CONV,
              new Compactor(policy(300), ScriptedSummarizer.returning(SUMMARY_JSON)),
              slot,
              assembler(slot));

      pipeline.run("新问题");

      List<Event> calls = eventsOf(events, EventTypes.LLM_CALL);
      assertThat(calls).hasSize(2); // 主循环一次 + 摘要器一次
      JsonNode compactCall =
          JSON.readTree(
              calls.stream()
                  .filter(event -> event.payload().contains("\"phase\":\"compact\""))
                  .findFirst()
                  .orElseThrow(() -> new AssertionError("压缩成本没有落 llm.call：" + calls))
                  .payload());
      assertThat(compactCall.get("phase").asText()).isEqualTo("compact");
      assertThat(compactCall.get("inputTokens").asLong()).isEqualTo(321);
      assertThat(compactCall.get("outputTokens").asLong()).isEqualTo(123);
      assertThat(compactCall.get("cacheReadTokens").asLong()).isEqualTo(45);
      assertThat(compactCall.get("cacheWriteTokens").asLong()).isEqualTo(6);
      assertThat(compactCall.get("model").asText()).isEqualTo("fake-summarizer");
      assertThat(compactCall.has("latencyMs")).isTrue();
      assertThat(compactCall.has("turns")).isFalse(); // 系统级维护调用，不是主循环的回合
    }
  }

  /**
   * R1 档 3 + R9 的"重启后仍续接"：快照落库（压缩点 = 当前末尾）→ 工作集按 {@code load} 的权威形态重灌、槽清空；<b>重启</b>（同库文件 + 全新
   * store/管线）后新实例自"摘要 + 压缩点之后的消息"续起，第一回合的请求就带着摘要与上一轮的消息。
   *
   * <p>判别性：
   *
   * <ul>
   *   <li>档 3 不落库的实现：重启后的请求里没有摘要消息，{@code store.load} 断言红；
   *   <li>档 3 只落"中段摘要"的实现（压缩点之前的尾部内容静默消失）：{@code store.load} 与重启后的续接断言红；
   *   <li>清槽漏掉的实现：摘要同时出现在 COMPACT_SUMMARY 层与会话里（同一内容两遍）， {@code
   *       doesNotContain(COMPACT_SUMMARY_HEADER)} 红。
   * </ul>
   */
  @Test
  void tierThreeSnapshotSurvivesRestartAndTheNextInstanceContinuesFromTheSummary() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      for (LlmMessage message : historyForTierTwo()) {
        store.append(CONV, message);
      }
      CompactSummarySlot slot = CompactSummarySlot.empty();
      ScriptedClient llm = ScriptedClient.answering("第一轮的回答");
      AgentPipeline pipeline =
          pipeline(
              llm,
              events,
              bus,
              store,
              CONV,
              new Compactor(policy(60), ScriptedSummarizer.returning(SUMMARY_JSON)),
              slot,
              assembler(slot));

      assertThat(pipeline.run("第一轮").stopReason()).isEqualTo(StopReason.FINISHED);

      LlmMessage summaryMessage =
          LlmMessage.assistant(List.of(new ContentPart.Text(EXPECTED.render())));
      // 摘要进会话（首条 assistant——与 T16 的 load 形态同源）；本回合的问答在压缩点之后追加
      assertThat(store.load(CONV))
          .containsExactly(
              summaryMessage,
              LlmMessage.user("第一轮"),
              LlmMessage.assistant(List.of(new ContentPart.Text("第一轮的回答"))));
      assertThat(pipeline.lastHistory()).hasSize(3);
      // 槽清空：摘要已进入会话本身，不再在层里出现第二遍
      assertThat(slot.current()).isNull();
      assertThat(systemTextOf(llm.requests.get(0).messages()))
          .doesNotContain(COMPACT_SUMMARY_HEADER);
      // 压缩后的工作集恰是"摘要一条"（本回合的请求在它之后接 user）
      assertThat(llm.requests.get(0).messages().subList(1, 2)).containsExactly(summaryMessage);
    }

    // "重启"：同一库文件上全新 Store + 全新管线（内存工作集为空），不接压缩器——考验的是 hydrate + 续接本身
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      ScriptedClient restartedLlm = ScriptedClient.answering("第二轮的回答");
      AgentPipeline restarted =
          pipeline(
              restartedLlm,
              events,
              bus,
              store,
              CONV,
              null,
              CompactSummarySlot.empty(),
              new BasicContextAssembler());

      assertThat(restarted.lastHistory()).hasSize(3); // 摘要 + 第一轮的问答（压缩点之后）

      assertThat(restarted.run("第二轮").stopReason()).isEqualTo(StopReason.FINISHED);

      List<LlmMessage> request = restartedLlm.requests.get(0).messages();
      assertThat(request.get(0).role()).isEqualTo(LlmMessage.ROLE_SYSTEM);
      assertThat(request.subList(1, request.size()))
          .containsExactly(
              LlmMessage.assistant(List.of(new ContentPart.Text(EXPECTED.render()))),
              LlmMessage.user("第一轮"),
              LlmMessage.assistant(List.of(new ContentPart.Text("第一轮的回答"))),
              LlmMessage.user("第二轮"));
    }
  }

  /**
   * 档 2 → 档 3 的升级：同一实例上先压缩成摘要（摘要进层），随后会话再涨到连尾部都放不下 → 转档 3 落库，<b>层必须清空</b>（摘要
   * 已进入会话本身，再留一份就是同一内容出现两遍），且新版摘要必须<b>并入</b>上一版（{@code ConversationStore.compact} 把合并责任交给调用方）。
   *
   * <p>判别性（这条正是"档 3 落库后忘了清槽"的杀手）：忘了清槽的实现会让压缩后的请求里 <b>同时</b>出现 COMPACT_SUMMARY 层里的旧摘要与会话里的新摘要，{@code
   * doesNotContain(COMPACT_SUMMARY_HEADER)} 必红； 不把上一版摘要传给摘要器的实现会让第二段被压缩的历史从请求里消失，{@code
   * contains("上一版压缩摘要")} 必红。
   *
   * <p>夹具的账：{@link #historyForTierTwo()} 的尾部 6 条各 400 字符（≈620 token），预算 700——第一回合档 1 视图 ≈2650 token
   * 放不下、尾部 620 放得下 → 档 2；第一回合的回答刻意给 3000 字符（≈760 token），于是第二回合"最近 6 条" ≈950 token 已超预算 → 档 3。
   */
  @Test
  void escalatingFromSummaryToSnapshotClearsTheStaleLayerAndMergesThePreviousSummary() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      List<LlmMessage> seeded = historyForTierTwo(400);
      for (LlmMessage message : seeded) {
        store.append(CONV, message);
      }
      CompactSummarySlot slot = CompactSummarySlot.empty();
      String longAnswer = "第一轮的回答" + "长".repeat(3000);
      ScriptedClient llm = ScriptedClient.answering(longAnswer, "第二轮的回答");
      ScriptedSummarizer summarizer =
          ScriptedSummarizer.returning(SUMMARY_JSON).enqueue(summarizerResponse(SUMMARY_JSON));
      AgentPipeline pipeline =
          pipeline(
              llm,
              events,
              bus,
              store,
              CONV,
              new Compactor(policy(700), summarizer),
              slot,
              assembler(slot));

      // 第一回合：档 2——摘要进层，工作集只剩尾部
      assertThat(pipeline.run("第一轮").stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(slot.current()).isEqualTo(EXPECTED);
      assertThat(systemTextOf(llm.requests.get(0).messages()))
          .contains(COMPACT_SUMMARY_HEADER)
          .contains("目标哨兵");

      // 第二回合：会话超过预算 → 档 3 落库
      assertThat(pipeline.run("第二轮").stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(summarizer.calls()).isEqualTo(2);
      // 新版摘要落库为会话首条，且槽已清空——旧摘要不再出现在层里（同一内容不出现两遍）
      assertThat(store.load(CONV).get(0))
          .isEqualTo(LlmMessage.assistant(List.of(new ContentPart.Text(EXPECTED.render()))));
      assertThat(slot.current()).isNull();
      assertThat(systemTextOf(llm.requests.get(1).messages()))
          .doesNotContain(COMPACT_SUMMARY_HEADER);
      // 合并责任在调用方：第二次摘要的输入里带着上一版摘要全文（它在被丢弃的中段之前）
      List<LlmMessage> secondInput = summarizer.requests().get(1).messages();
      assertThat(textOf(secondInput.get(1))).contains("上一版压缩摘要").contains(EXPECTED.render());
      assertThat(secondInput.get(2)).isEqualTo(seeded.get(12)); // 被丢弃段的第一条 = 档 2 尾部之首
      // 两次压缩各落一条事件（档 2 一次、档 3 一次）
      assertThat(
              eventsOf(events, EventTypes.CONVERSATION_COMPACT).stream()
                  .map(Event::payload)
                  .toList())
          .hasSize(2)
          .anySatisfy(payload -> assertThat(payload).contains("\"tier\":\"summary\""))
          .anySatisfy(payload -> assertThat(payload).contains("\"tier\":\"snapshot\""));
    }
  }

  /**
   * R8-b（T16 两条判别性用例的<b>对偶</b>）：<b>hydrate 含摘要 → 跑一回合 → 守卫不响且行为正确</b>。
   *
   * <p>为什么必须补这条：{@code saveHistory} 的前置守卫要求 {@code messages} 恒为 {@code [system] + 回灌历史原样 + [本轮
   * user, …]}，而"合法压缩"（档 3 落库后重启、或经 {@code compact} 记录压缩过的会话）正是<b>回灌历史本身变短</b>的情形。T16
   * 的两条用例只证明了"违约会被拦下"，没有任何用例钉住"合法压缩不会被误拦"——守卫的 Javadoc 这么宣称，但宣称不是证据。
   *
   * <p>本用例的判别性：如果守卫被写成"回灌历史必须与库里的行数一致"或"回灌历史不得含 assistant 摘要"（都是结构上相似但错误的实现）， 这里会抛 {@link
   * IllegalStateException}，用例红；而正确实现下 {@code run()} 正常 FINISHED，且回灌顺序逐条可查。
   */
  @Test
  void hydratingAPersistedSummaryDoesNotTripTheSaveHistoryGuard() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      store.append(CONV, LlmMessage.user("旧问题一"));
      store.append(CONV, LlmMessage.assistant(List.of(new ContentPart.Text("旧回答一"))));
      store.append(CONV, LlmMessage.user("旧问题二"));
      store.append(CONV, LlmMessage.assistant(List.of(new ContentPart.Text("旧回答二"))));
      String persistedSummary = "已压缩的历史要点（摘要哨兵）";
      store.compact(CONV, persistedSummary); // 压缩点 = 当前末尾（4 条）
      store.append(CONV, LlmMessage.user("压缩点之后的问题"));
      store.append(CONV, LlmMessage.assistant(List.of(new ContentPart.Text("压缩点之后的回答"))));

      ScriptedClient llm = ScriptedClient.answering("本回合回答");
      AgentPipeline pipeline =
          pipeline(
              llm,
              events,
              bus,
              store,
              CONV,
              null,
              CompactSummarySlot.empty(),
              new BasicContextAssembler());

      // hydrate 得到的是"摘要 + 压缩点之后的消息"（T16 的 load 形态）
      assertThat(pipeline.lastHistory()).hasSize(3);

      // 守卫在此求值：合法压缩形态不得触发（触发即抛 IllegalStateException，用例红）
      assertThat(pipeline.run("新问题").stopReason()).isEqualTo(StopReason.FINISHED);

      List<LlmMessage> request = llm.requests.get(0).messages();
      assertThat(request.get(0).role()).isEqualTo(LlmMessage.ROLE_SYSTEM);
      assertThat(request.subList(1, request.size()))
          .containsExactly(
              LlmMessage.assistant(List.of(new ContentPart.Text(persistedSummary))),
              LlmMessage.user("压缩点之后的问题"),
              LlmMessage.assistant(List.of(new ContentPart.Text("压缩点之后的回答"))),
              LlmMessage.user("新问题"));
      // 本回合尾部照常落库（append-only 未被摘要记录打扰），且没有重复前缀
      assertThat(store.load(CONV)).hasSize(5);
      assertThat(pipeline.lastHistory()).hasSize(5);
    }
  }

  /**
   * R11 选项 ①（结构性回避撕裂读）：档 3 的 {@code compact}/{@code load} 走的是<b>注入给管线的那个 store 实例</b>——
   * 管线存活期间不存在第二个 store 实例/第二条连接，{@code load()} 的两条查询之间插不进一次 {@code compact()}。
   *
   * <p>判别性：一个"管线自己 new 一个 SqliteConversationStore（或另开连接）落库"的实现会让计数器看到 0 次 {@code compact}，且我在这里通过
   * spy 读到的库内容不会变——两条断言都会红。
   */
  @Test
  void snapshotPersistenceGoesThroughThePipelinesOwnStoreInstance() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      for (LlmMessage message : historyForTierTwo()) {
        store.append(CONV, message);
      }
      SpyConversationStore spy = new SpyConversationStore(store);
      CompactSummarySlot slot = CompactSummarySlot.empty();
      AgentPipeline pipeline =
          pipeline(
              ScriptedClient.answering("第一轮的回答"),
              events,
              bus,
              spy,
              CONV,
              new Compactor(policy(60), ScriptedSummarizer.returning(SUMMARY_JSON)),
              slot,
              assembler(slot));

      assertThat(pipeline.run("第一轮").stopReason()).isEqualTo(StopReason.FINISHED);

      // 档 3 的落库与重灌都经这个实例：compact 恰一次、load 至少两次（构造 hydrate + 落库后重灌）
      assertThat(spy.compacts()).isEqualTo(1);
      assertThat(spy.loads()).isGreaterThanOrEqualTo(2);
      assertThat(spy.appends()).isGreaterThan(0); // 本回合尾部也走同一实例
      // 摘要确实经该实例到达库（不是"管线另开一条连接写完了、这个实例看不到"）
      assertThat(spy.delegateLoad(CONV))
          .containsExactly(
              LlmMessage.assistant(List.of(new ContentPart.Text(EXPECTED.render()))),
              LlmMessage.user("第一轮"),
              LlmMessage.assistant(List.of(new ContentPart.Text("第一轮的回答"))));
    }
  }

  /**
   * 降级可观测（R7/R1 的"失败也要可见"）：摘要调用抛 {@link LlmException} 时——<b>不</b>落 llm.call（没有 response
   * 可记账，不编造）、退回档 1 的披露式丢弃、并落 {@code decision=COMPACT_DEGRADED} 带原因。
   *
   * <p>判别性：降级后"历史原样不动"的实现（占位缺失 → 压缩悄悄失效）在占位断言上红；把降级吞掉不披露的实现（用户与审计都看不到这次压缩 失败）在 decision 断言上红；编造
   * {@code llm.call} 的实现条数断言红。
   */
  @Test
  void degradedSummaryIsDisclosedThroughADecisionEvent() throws Exception {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      for (LlmMessage message : historyForTierTwo()) {
        store.append(CONV, message);
      }
      CompactSummarySlot slot = CompactSummarySlot.empty();
      ScriptedClient llm = ScriptedClient.answering("这一轮的回答");
      ScriptedSummarizer summarizer = ScriptedSummarizer.failing("摘要服务不可用");
      AgentPipeline pipeline =
          pipeline(
              llm,
              events,
              bus,
              store,
              CONV,
              new Compactor(policy(300), summarizer),
              slot,
              assembler(slot));

      assertThat(pipeline.run("新问题").stopReason()).isEqualTo(StopReason.FINISHED);

      // 退回档 1：请求里是披露占位（条数可见），不是"什么都没发生"——工作集 = 头 2 条 + 占位 + 尾 6 条
      List<LlmMessage> request = llm.requests.get(0).messages();
      assertThat(request.get(3).role()).isEqualTo(LlmMessage.ROLE_ASSISTANT);
      assertThat(textOf(request.get(3))).contains("已丢弃").contains("10").contains("条历史消息");
      // 槽没被写入空摘要
      assertThat(slot.current()).isNull();
      // 降级在事件流里可见，且原因可查
      JsonNode decision =
          JSON.readTree(
              eventsOf(events, EventTypes.DECISION).stream()
                  .filter(event -> event.payload().contains("COMPACT_DEGRADED"))
                  .findFirst()
                  .orElseThrow(() -> new AssertionError("降级没有落 decision 事件"))
                  .payload());
      assertThat(decision.get("decision").asText()).isEqualTo("COMPACT_DEGRADED");
      assertThat(decision.get("detail").get("reason").asText()).contains("摘要调用失败");
      // 档位照实记（micro）：这次压缩确实发生了，只是没拿到摘要
      JsonNode payload = onlyPayload(events, EventTypes.CONVERSATION_COMPACT);
      assertThat(payload.get("tier").asText()).isEqualTo("micro");
      assertThat(payload.get("droppedMessages").asInt()).isEqualTo(10);
      assertThat(payload.get("summaryTokens").asInt()).isEqualTo(-1);
      // 失败的调用没有 response：不编造 llm.call（只有主循环那一次）
      assertThat(eventsOf(events, EventTypes.LLM_CALL)).hasSize(1);
    }
  }

  /**
   * R7 的另一半：摘要<b>拿回来了但解析不出八字段</b>（降级为档 1）时，这笔 token 仍然要落 {@code llm.call}——花掉的钱不能因为
   * "摘要没用上"就从账上消失。降级与记账是两件事。
   *
   * <p>判别性：把"降级"实现成"整件事当没发生"（不记账、也不落 decision）的实现在两条断言上必红。
   */
  @Test
  void unparseableSummaryStillBooksItsTokenCost() throws Exception {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      for (LlmMessage message : historyForTierTwo()) {
        store.append(CONV, message);
      }
      CompactSummarySlot slot = CompactSummarySlot.empty();
      AgentPipeline pipeline =
          pipeline(
              ScriptedClient.answering("这一轮的回答"),
              events,
              bus,
              store,
              CONV,
              new Compactor(policy(300), ScriptedSummarizer.returning("模型说了些别的，不是 JSON")),
              slot,
              assembler(slot));

      assertThat(pipeline.run("新问题").stopReason()).isEqualTo(StopReason.FINISHED);

      List<Event> calls = eventsOf(events, EventTypes.LLM_CALL);
      assertThat(calls).hasSize(2); // 主循环一次 + 摘要器一次（尽管摘要不可用）
      JsonNode compactCall =
          JSON.readTree(
              calls.stream()
                  .filter(event -> event.payload().contains("\"phase\":\"compact\""))
                  .findFirst()
                  .orElseThrow(() -> new AssertionError("解析失败的摘要调用没有记账：" + calls))
                  .payload());
      assertThat(compactCall.get("inputTokens").asLong()).isEqualTo(321);
      // 档位照实记（降级成 micro），并有 decision 披露
      assertThat(onlyPayload(events, EventTypes.CONVERSATION_COMPACT).get("tier").asText())
          .isEqualTo("micro");
      assertThat(eventsOf(events, EventTypes.DECISION))
          .anyMatch(event -> event.payload().contains("COMPACT_DEGRADED"));
    }
  }

  /**
   * 档 1 全链路：中段被披露式丢弃、<b>零 LLM 调用</b>、事件里 {@code summaryTokens = -1}（本次没有摘要），会话尾部与持久层都不受影响。
   *
   * <p>判别性：跳过档 1 直接做摘要的实现（本来免费的一步却花钱）在 {@code summarizer.calls() == 0} 与事件 tier 上红；
   * 静默丢弃的实现（无占位）在占位断言上红。
   */
  @Test
  void tierOneCompactionCostsNothingAndDisclosesTheDrop() throws Exception {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      List<LlmMessage> seeded = historyForTierOne();
      for (LlmMessage message : seeded) {
        store.append(CONV, message);
      }
      CompactSummarySlot slot = CompactSummarySlot.empty();
      ScriptedClient llm = ScriptedClient.answering("这一轮的回答");
      ScriptedSummarizer summarizer = new ScriptedSummarizer();
      AgentPipeline pipeline =
          pipeline(
              llm,
              events,
              bus,
              store,
              CONV,
              new Compactor(policy(1000), summarizer),
              slot,
              assembler(slot));

      assertThat(pipeline.run("新问题").stopReason()).isEqualTo(StopReason.FINISHED);

      assertThat(summarizer.calls()).isZero();
      List<LlmMessage> request = llm.requests.get(0).messages();
      // [system, 头 2 条, 占位, 尾 6 条, 本轮 user]
      assertThat(request).hasSize(11);
      assertThat(request.get(1)).isEqualTo(seeded.get(0));
      assertThat(textOf(request.get(3))).contains("已丢弃").contains("10").contains("条历史消息");
      assertThat(request.subList(4, 10)).containsExactlyElementsOf(seeded.subList(12, 18));
      assertThat(summarizerInputs(summarizer)).isEmpty();
      assertThat(eventsOf(events, EventTypes.LLM_CALL)).hasSize(1); // 只有主循环那一次
      JsonNode payload = onlyPayload(events, EventTypes.CONVERSATION_COMPACT);
      assertThat(payload.get("tier").asText()).isEqualTo("micro");
      assertThat(payload.get("droppedMessages").asInt()).isEqualTo(10);
      assertThat(payload.get("summaryTokens").asInt()).isEqualTo(-1);
      // 档 1 不碰存储：完整历史仍在库里（重启回来的是全量，不是被 micro 砍过的视图）
      assertThat(store.load(CONV)).hasSize(seeded.size() + 2);
    }
  }

  // ---------- 装配 ----------

  private AgentPipeline pipeline(
      LlmClient llm,
      EventStore events,
      EventBus bus,
      ConversationStore store,
      String conversationId,
      Compactor compactor,
      CompactSummarySlot slot,
      ContextAssembler assembler) {
    AgentConfig config = AgentConfig.builder("main").systemPrompt(SYSTEM_CANARY).build();
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
        conversationId,
        compactor,
        slot);
  }

  /** 带技能目录的装配器（R3 的持久层哨兵）；{@code slot} 必须与管线共用一个实例。 */
  private ContextAssembler assembler(CompactSummarySlot slot) {
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(writeSkill());
    return new BasicContextAssembler(
        ContextPolicy.defaults(), ContextSources.skills(catalog, Set.of()), slot);
  }

  private static ContextPolicy policy(int conversationBudget) {
    return ContextPolicy.defaults().withBudget(ContextLayer.CONVERSATION, conversationBudget);
  }

  /** 18 条：头一对各 4000 字符（档 1 放不下）、尾 6 条各 ~110 字符。全量 ≈ 2330 token。 */
  private static List<LlmMessage> historyForTierTwo() {
    return historyForTierTwo(100);
  }

  /**
   * 同上，但尾部正文长度可调（升级用例要把尾部做到"预算 700 恰好放得下"，见该用例的字符账）。
   *
   * @param tailFiller 尾 6 条每条正文的字符数
   */
  private static List<LlmMessage> historyForTierTwo(int tailFiller) {
    List<LlmMessage> seeded = new ArrayList<>();
    seeded.add(LlmMessage.user("HIST#0 " + "头".repeat(4000)));
    seeded.add(LlmMessage.assistant(List.of(new ContentPart.Text("HIST#1 " + "头".repeat(4000)))));
    for (int i = 1; i <= 8; i++) {
      seeded.add(LlmMessage.user("HIST#" + (i * 2) + " " + "尾".repeat(tailFiller)));
      seeded.add(
          LlmMessage.assistant(
              List.of(new ContentPart.Text("HIST#" + (i * 2 + 1) + " " + "尾".repeat(tailFiller)))));
    }
    return seeded;
  }

  /** 18 条：头尾极小、中段 10 条各 ~2000 字符（全量 ≈ 5035 token，档 1 视图 ≈ 32 token）。 */
  private static List<LlmMessage> historyForTierOne() {
    List<LlmMessage> seeded = new ArrayList<>();
    seeded.add(LlmMessage.user("HIST#0 头"));
    seeded.add(LlmMessage.assistant(List.of(new ContentPart.Text("HIST#1 头"))));
    for (int i = 1; i <= 5; i++) {
      seeded.add(LlmMessage.user("HIST#" + (i * 2) + " " + "中".repeat(2000)));
      seeded.add(
          LlmMessage.assistant(
              List.of(new ContentPart.Text("HIST#" + (i * 2 + 1) + " " + "中".repeat(2000)))));
    }
    for (int i = 6; i <= 8; i++) {
      seeded.add(LlmMessage.user("HIST#" + (i * 2) + " 尾"));
      seeded.add(LlmMessage.assistant(List.of(new ContentPart.Text("HIST#" + (i * 2 + 1) + " 尾"))));
    }
    return seeded;
  }

  private Path writeSkill() {
    Path dir = tempDir.resolve("skills").resolve("alpha");
    Path file = dir.resolve(Skill.SKILL_FILE_NAME);
    if (!Files.exists(file)) {
      try {
        Files.createDirectories(dir);
        Files.writeString(
            file,
            "---\nname: alpha\ndescription: " + SKILL_CANARY + "\n---\n正文",
            StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return tempDir.resolve("skills");
  }

  // ---------- 断言辅助 ----------

  private static List<LlmMessage> concat(List<LlmMessage> head, List<LlmMessage> tail) {
    List<LlmMessage> all = new ArrayList<>(head);
    all.addAll(tail);
    return all;
  }

  private static String systemTextOf(List<LlmMessage> messages) {
    return textOf(messages.get(0));
  }

  /** 文本拼包（只取 Text 分片；本测试的夹具消息都只有文本）。 */
  private static String textOf(LlmMessage message) {
    return message.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(ContentPart.Text.class::cast)
        .map(ContentPart.Text::text)
        .reduce("", String::concat);
  }

  private static String allTextOf(List<LlmMessage> messages) {
    StringBuilder text = new StringBuilder();
    for (LlmMessage message : messages) {
      text.append(textOf(message)).append('\n');
    }
    return text.toString();
  }

  private static List<Event> eventsOf(EventStore events, String type) {
    return events.query(new EventQuery("", type, "", -1, 100));
  }

  /** 恰有一条该类型事件时取它的 payload（多于一条本身就是编排错误——静默取第一条会掩盖重复触发）。 */
  private static JsonNode onlyPayload(EventStore events, String type) throws Exception {
    List<Event> matched = eventsOf(events, type);
    assertThat(matched).hasSize(1);
    return JSON.readTree(matched.get(0).payload());
  }

  private static List<String> summarizerInputs(ScriptedSummarizer summarizer) {
    return summarizer.requests().stream()
        .map(LlmRequest::messages)
        .map(AgentPipelineCompactionTest::allTextOf)
        .toList();
  }

  /** 摘要器的脚本响应：token 记账字段是 R7 断言的口径（模型名刻意与主 LLM 不同，便于识别）。 */
  private static LlmResponse summarizerResponse(String replyText) {
    return new LlmResponse(
        LlmMessage.assistant(List.of(new ContentPart.Text(replyText))),
        "fake-summarizer",
        321,
        123,
        45,
        6);
  }

  /** 记录请求、按脚本回答的主 LLM。脚本耗尽是编排错误（AssertionError 不被主循环的 LlmException 处理吞掉）。 */
  private static final class ScriptedClient implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    static ScriptedClient answering(String... replies) {
      ScriptedClient client = new ScriptedClient();
      for (String reply : replies) {
        client.script.addLast(LlmResponse.text(reply));
      }
      return client;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new AssertionError("主 LLM 脚本耗空——疑似多出了计划外的调用");
      }
      return response;
    }
  }

  /** 任何调用都是编排错误（未触门的用例用它当探针）。 */
  private static final class UnscriptedSummarizer implements LlmClient {

    @Override
    public LlmResponse chat(LlmRequest request) {
      throw new AssertionError("本用例不该发生摘要调用");
    }
  }

  /** 脚本化摘要器：记录请求、计数调用，按脚本返回或抛 {@link LlmException}（token 记账字段是 R7 断言的口径）。 */
  private static final class ScriptedSummarizer implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();
    private LlmException failure;
    private int calls;

    static ScriptedSummarizer returning(String replyText) {
      return new ScriptedSummarizer().enqueue(summarizerResponse(replyText));
    }

    static ScriptedSummarizer failing(String message) {
      ScriptedSummarizer summarizer = new ScriptedSummarizer();
      summarizer.failure = new LlmException(message);
      return summarizer;
    }

    ScriptedSummarizer enqueue(LlmResponse response) {
      script.addLast(response);
      return this;
    }

    int calls() {
      return calls;
    }

    List<LlmRequest> requests() {
      return requests;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      calls++;
      requests.add(request);
      if (failure != null) {
        throw failure;
      }
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new AssertionError("摘要器脚本耗空");
      }
      return response;
    }
  }

  /** 委托 + 计数的 store 探针：证明档 3 的落库/重灌走的就是注入给管线的那个实例（R11 选项 ① 的判别性 pin）。 */
  private static final class SpyConversationStore implements ConversationStore {

    private final ConversationStore delegate;
    private int compacts;
    private int loads;
    private int appends;

    SpyConversationStore(ConversationStore delegate) {
      this.delegate = delegate;
    }

    int compacts() {
      return compacts;
    }

    int loads() {
      return loads;
    }

    int appends() {
      return appends;
    }

    /** 绕过计数器直读委托（断言"库里的确是这个实例写的"）。 */
    List<LlmMessage> delegateLoad(String conversationId) {
      return delegate.load(conversationId);
    }

    @Override
    public void append(String conversationId, LlmMessage message) {
      appends++;
      delegate.append(conversationId, message);
    }

    @Override
    public List<LlmMessage> load(String conversationId) {
      loads++;
      return delegate.load(conversationId);
    }

    @Override
    public void compact(String conversationId, String summary) {
      compacts++;
      delegate.compact(conversationId, summary);
    }
  }
}
