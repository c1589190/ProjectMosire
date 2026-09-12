package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.brain.context.BasicContextAssembler;
import io.mosire.brain.context.CompactSummarySlot;
import io.mosire.brain.context.Compactor;
import io.mosire.brain.context.ContextLayer;
import io.mosire.brain.context.ContextPolicy;
import io.mosire.brain.context.ContextSources;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 会话重置（P3-3 / D26：{@code 清空内存工作集 + 切到一个全新 conversationId}）：{@link
 * AgentPipeline#resetConversation(String)} 的四件事逐一落定——换 id、清工作集（落库/回灌都改按新 id 寻址）、旧会话在库里原样留档（不删）、
 * 摘要槽归空。
 *
 * <p><b>判别性</b>：每条断言都对着"漏做某一步"的失败模式——①不换 id：新回合落在旧会话上（{@code load(旧)} 多出内容、{@code load(新)}
 * 为空）；②不清工作集：下一回合的请求仍带上一段的问答（请求断言必红）；③清工作集但<b>同时</b>删了旧会话：{@code load(旧)}
 * 为空；④漏清摘要槽：新会话带着一段描述旧对话的摘要开场（槽断言必红）。
 *
 * <p>9 参构造（不落库）路径同样可重置——它没有库可寻址，"换 id + 清工作集"照旧成立（见最后一条用例）。
 */
class AgentPipelineResetConversationTest {

  private static final String CONV_OLD = "conv-old";

  /** 档 2 的摘要正文（形状同 {@code AgentPipelineCompactionTest}：Compactor 按这八个字段解析）。 */
  private static final String SUMMARY_JSON =
      """
      {"objective":"旧会话目标","completed":["旧会话完成项"],"pending":[],"decisions":[],
       "changedFiles":[],"errors":[],"activeSkills":[],"unresolved":[]}
      """;

  @TempDir Path tempDir;

  private Path db;

  @BeforeEach
  void setUp() {
    db = tempDir.resolve("agent.db");
  }

  /**
   * 验收③（Brain 半）：重置后下一回合从零开始（请求里<b>没有</b>重置前的内容），且旧会话仍能从库里读回（D26：不删库）。
   *
   * <p>同时钉住"重置不是重建管线"的落点：同一个管线实例在重置前后都在用同一个 store，只是寻址的 id 变了。
   */
  @Test
  void resetStartsTheNextTurnFromScratchAndKeepsTheOldConversationInTheStore() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      RecordingLlmClient llm = new RecordingLlmClient();
      llm.enqueue(LlmResponse.text("回答一"));
      llm.enqueue(LlmResponse.text("回答二"));
      AgentPipeline pipeline = persisting(llm, events, bus, store, CONV_OLD);

      assertThat(pipeline.conversationId()).isEqualTo(CONV_OLD);
      assertThat(pipeline.run("问题一").stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(pipeline.lastHistory()).hasSize(2);
      assertThat(store.load(CONV_OLD))
          .containsExactly(
              LlmMessage.user("问题一"), LlmMessage.assistant(List.of(new ContentPart.Text("回答一"))));

      pipeline.resetConversation("conv-new");

      // 重置当刻：id 已换、工作集已空、旧会话一条不少、新会话还什么都没有
      assertThat(pipeline.conversationId()).isEqualTo("conv-new");
      assertThat(pipeline.lastHistory()).isEmpty();
      assertThat(store.load(CONV_OLD)).hasSize(2);
      assertThat(store.load("conv-new")).isEmpty();

      assertThat(pipeline.run("问题二").stopReason()).isEqualTo(StopReason.FINISHED);

      // 验收③的正面判据：新回合的请求恰为 [system, 本轮 user] 两条——重置前的一问一答一个字都没带上
      List<LlmMessage> request = llm.requests.get(1).messages();
      assertThat(request).hasSize(2);
      assertThat(request.get(0).role()).isEqualTo(LlmMessage.ROLE_SYSTEM);
      assertThat(request).doesNotContain(LlmMessage.user("问题一"));
      assertThat(request.get(request.size() - 1)).isEqualTo(LlmMessage.user("问题二"));

      // 新会话只装了重置后的内容；旧会话是只追加的——重置既没改写它、也没清空它
      assertThat(store.load("conv-new"))
          .containsExactly(
              LlmMessage.user("问题二"), LlmMessage.assistant(List.of(new ContentPart.Text("回答二"))));
      assertThat(store.load(CONV_OLD))
          .containsExactly(
              LlmMessage.user("问题一"), LlmMessage.assistant(List.of(new ContentPart.Text("回答一"))));
      assertThat(pipeline.conversationId()).isEqualTo("conv-new");
    }
  }

  /**
   * 重置的第三件事（摘要槽）：槽里挂着上一个会话的摘要时，重置必须清掉它——否则新会话会带着一段描述旧对话的"记忆"开场。
   *
   * <p><b>摘要由真实压缩产生</b>（不是测试直接 {@code set()} 造出来的）：18 条历史灌进库 → 管线 hydrate → 预算 300 触发档 2 →
   * 摘要写进<b>装配器与管线共用的那个槽</b>（取槽来源是装配器自己，同 {@code AgentPipelineCompactionTest} 的口径）。第一条断言先证明"槽里
   * 真的有东西"，否则"重置后为空"可能只是因为它自始至终就是空的（假绿）。
   */
  @Test
  void resetClearsTheCompactSummaryProducedByARealCompaction() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      seedLongHistory(store, CONV_OLD);
      CompactSummarySlot slot = CompactSummarySlot.empty();
      BasicContextAssembler assembler =
          new BasicContextAssembler(ContextPolicy.defaults(), ContextSources.none(), slot);
      RecordingLlmClient llm = new RecordingLlmClient();
      llm.enqueue(LlmResponse.text("回答一"));
      RecordingLlmClient summarizer = new RecordingLlmClient();
      summarizer.enqueue(LlmResponse.text(SUMMARY_JSON));
      AgentPipeline pipeline = compacting(llm, events, bus, store, assembler, summarizer, slot);

      assertThat(pipeline.run("问题一").stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(summarizer.requests).as("档 2 确实取过摘要（槽里应当有东西）").hasSize(1);
      assertThat(slot.current()).isNotNull();

      pipeline.resetConversation("conv-new");

      assertThat(slot.current()).as("重置必须清掉上一个会话的摘要").isNull();
      assertThat(pipeline.lastHistory()).isEmpty();
      assertThat(pipeline.conversationId()).isEqualTo("conv-new");
    }
  }

  /**
   * 9 参构造（不落库）路径的重置：没有库可寻址，"换 id + 清工作集"照旧成立——行为与持久化路径的差异只在于"旧会话无处留档"。
   *
   * <p>初始 id 取 {@code config.id()}（既有取法，{@code conversationIdOf}）：本用例顺带把"9 参构造的会话 id 语义没被重置能力改动"钉住。
   */
  @Test
  void resetWorksOnTheNonPersistingPipelineToo() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        EventBus bus = new EventBus()) {
      RecordingLlmClient llm = new RecordingLlmClient();
      llm.enqueue(LlmResponse.text("回答一"));
      llm.enqueue(LlmResponse.text("回答二"));
      AgentConfig config = AgentConfig.builder("main").build();
      AgentPipeline pipeline =
          new AgentPipeline(
              config,
              llm,
              new ToolRegistry(),
              new ToolExecutionGuard(),
              new BasicContextAssembler(),
              events,
              bus,
              AgentPermissionSet.system().grantedToken(),
              AgentPermissionSet.system());

      assertThat(pipeline.run("问题一").stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(pipeline.conversationId()).isEqualTo("main");

      pipeline.resetConversation("s-2");

      assertThat(pipeline.conversationId()).isEqualTo("s-2");
      assertThat(pipeline.lastHistory()).isEmpty();

      pipeline.run("问题二");

      assertThat(llm.requests.get(1).messages()).doesNotContain(LlmMessage.user("问题一"));
      assertThat(llm.requests.get(1).messages()).hasSize(2);
    }
  }

  // ---------- 装配 ----------

  /** 落库路径（11 参构造）：不接压缩器。 */
  private AgentPipeline persisting(
      LlmClient llm, EventStore events, EventBus bus, ConversationStore store, String conv) {
    AgentConfig config = AgentConfig.builder("main").build();
    return new AgentPipeline(
        config,
        llm,
        new ToolRegistry(),
        new ToolExecutionGuard(),
        new BasicContextAssembler(),
        events,
        bus,
        AgentPermissionSet.system().grantedToken(),
        AgentPermissionSet.system(),
        store,
        conv);
  }

  /** 13 参构造：接压缩器 + 与装配器共用的摘要槽（档 2 的摘要必须能在装配器渲染的层里看见）。 */
  private AgentPipeline compacting(
      LlmClient llm,
      EventStore events,
      EventBus bus,
      ConversationStore store,
      BasicContextAssembler assembler,
      LlmClient summarizer,
      CompactSummarySlot slot) {
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
        CONV_OLD,
        new Compactor(
            ContextPolicy.defaults().withBudget(ContextLayer.CONVERSATION, 300), summarizer),
        slot);
  }

  /**
   * 18 条历史直接落库（管线 hydrate 时灌进工作集）：头一对各 4000 字符（档 1 视图放不下）、尾 6 条各 ~110 字符（尾部放得下）—— 预算 300 的字符账落在档
   * 2（同 {@code AgentPipelineCompactionTest#historyForTierTwo} 的形状）。
   */
  private static void seedLongHistory(SqliteConversationStore store, String conversationId) {
    List<LlmMessage> seeded = new ArrayList<>();
    seeded.add(LlmMessage.user("HIST#0 " + "头".repeat(4000)));
    seeded.add(LlmMessage.assistant(List.of(new ContentPart.Text("HIST#1 " + "头".repeat(4000)))));
    for (int i = 1; i <= 8; i++) {
      seeded.add(LlmMessage.user("HIST#" + (i * 2) + " " + "尾".repeat(100)));
      seeded.add(
          LlmMessage.assistant(
              List.of(new ContentPart.Text("HIST#" + (i * 2 + 1) + " " + "尾".repeat(100)))));
    }
    for (LlmMessage message : seeded) {
      store.append(conversationId, message);
    }
  }

  /** 记录每次收到的 {@link LlmRequest} 的脚本桩（请求断言必须看请求内容，不能只看返回文本）。 */
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
