package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.agent.AgentConfig;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 窗口下沉后宿主侧的三条判据（{@code 设计-上下文窗口下沉.md} §五）：
 *
 * <ul>
 *   <li><b>判据 10（本批唯一新增的判别力档）</b>：装配器返回<b>等长但被改写 / 被重排</b>的序列时，位置契约守卫必须抛 {@link
 *       IllegalStateException}，且必须抛在<b>发信与任何写入之前</b>。旧守卫（{@code size >= replayedSize + 2} 且 {@code
 *       messages[replayedSize + 1]} 的 role 是 user）自认看不见这一档——长度对得上、本轮 user 的绝对下标也对得上，只有内容不同；新守卫逐条
 *       {@code equals} 才拦得住。同文件里的 {@code NONE} 档是对照（接收判据）：同一套夹具换个老实装配器必须正常跑完，证明上面两条红不是"守卫见谁都抛"。
 *   <li><b>判据 12（§5.1 的 QUOTA 行为变更）</b>：配额超限终止时，模型答复<b>留在窗口且已落库</b>。
 *   <li><b>判据 13（§5.2 的 LLM_ERROR 接线要求）</b>：{@code llm.chat} 抛 {@link LlmException} 时，窗口与库里<b>含本轮
 *       user、不含 assistant</b>——{@code LlmRoundTrip} 不替宿主补写回，这一条由管线自己的失败分支保证。
 * </ul>
 *
 * <p>夹具风格沿用 {@code AgentPipelineConversationStoreTest}（同一套事件库 + 会话库 + 脚本 LLM 桩）与 {@code
 * AgentPipelineTest} 的 {@code RecordingLlmClient}，但本类自带副本：那两处的桩是既有断言的一部分，本批不动它们。
 */
class AgentPipelineContextWindowTest {

  private static final String CONV = "conv-window";

  @TempDir Path tempDir;

  private Path db;

  @BeforeEach
  void setUp() {
    db = tempDir.resolve("agent.db");
  }

  /**
   * 判据 10 档一（改写）：装配器把窗口尾条换成<b>同 role 的另一条</b>——序列长度、{@code messages[replayedSize + 1]} 的 role
   * 全都对得上，只有内容被改写。旧守卫对这一档完全失明，新守卫必须当场拦下。
   */
  @Test
  void equalLengthRewrittenWindowIsRejectedLoudlyBeforeAnySendOrWrite() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      RecordingLlmClient first = new RecordingLlmClient();
      first.enqueue(LlmResponse.text("第一轮回答"));
      pipeline(first, events, bus, store, new BreachingAssembler(Breach.NONE)).run("问题一");

      RecordingLlmClient second = new RecordingLlmClient();
      second.enqueue(LlmResponse.text("第二轮回答"));
      AgentPipeline pipeline =
          pipeline(second, events, bus, store, new BreachingAssembler(Breach.REWRITE_WINDOW_TAIL));

      assertThatThrownBy(() -> pipeline.run("问题二"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("assembler 违反契约")
          .hasMessageContaining("replayedSize=2")
          .hasMessageContaining("size=4")
          .hasMessageContaining("窗口内容未被原样回灌");

      // 守卫在发信与任何写入之前：模型一次都没被调用，内存与库都还停在上一轮的 2 条
      assertThat(second.requests).isEmpty();
      assertThat(pipeline.lastHistory()).hasSize(2);
      assertThat(store.load(CONV)).hasSize(2);
    }
  }

  /** 判据 10 档二（重排）：窗口两段顺序互换——长度不变、本轮 user 的绝对下标不变，只有顺序不同。 */
  @Test
  void equalLengthReorderedWindowIsRejectedLoudlyBeforeAnySendOrWrite() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      RecordingLlmClient first = new RecordingLlmClient();
      first.enqueue(LlmResponse.text("第一轮回答"));
      pipeline(first, events, bus, store, new BreachingAssembler(Breach.NONE)).run("问题一");

      RecordingLlmClient second = new RecordingLlmClient();
      second.enqueue(LlmResponse.text("第二轮回答"));
      AgentPipeline pipeline =
          pipeline(second, events, bus, store, new BreachingAssembler(Breach.REORDER_WINDOW));

      assertThatThrownBy(() -> pipeline.run("问题二"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("assembler 违反契约")
          .hasMessageContaining("replayedSize=2")
          .hasMessageContaining("size=4")
          .hasMessageContaining("窗口内容未被原样回灌");

      assertThat(second.requests).isEmpty();
      assertThat(pipeline.lastHistory()).hasSize(2);
      assertThat(store.load(CONV)).hasSize(2);
    }
  }

  /** 判据 10 的对照（接收判据）：同一套夹具改回老实装配器 ⇒ 守卫放行，第二轮正常跑完且请求形状恰为 {@code [头部] + 窗口 + 本轮 user}。 */
  @Test
  void honestEqualLengthAssemblerPassesTheGuardAndFinishesTheSecondTurn() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      RecordingLlmClient llm = new RecordingLlmClient();
      llm.enqueue(LlmResponse.text("第一轮回答"));
      llm.enqueue(LlmResponse.text("第二轮回答"));
      AgentPipeline pipeline =
          pipeline(llm, events, bus, store, new BreachingAssembler(Breach.NONE));

      assertThat(pipeline.run("问题一").stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(pipeline.run("问题二").stopReason()).isEqualTo(StopReason.FINISHED);

      assertThat(llm.requests.get(1).messages())
          .containsExactly(
              LlmMessage.system("桩 system"),
              LlmMessage.user("问题一"),
              LlmMessage.assistant(List.of(new ContentPart.Text("第一轮回答"))),
              LlmMessage.user("问题二"));
      assertThat(pipeline.lastHistory()).hasSize(4);
    }
  }

  /** 判据 12（§5.1）：配额超限 ⇒ {@link StopReason#QUOTA}，且模型答复既在窗口里、也已落库（旧行为把它丢掉了）。 */
  @Test
  void quotaExceededKeepsTheModelAnswerInWindowAndStore() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      RecordingLlmClient llm = new RecordingLlmClient();
      LlmMessage answer = LlmMessage.assistant(List.of(new ContentPart.Text("超限前模型已经答完的话")));
      // 用量 6 + 5 = 11 > 上限 10：llm.call 记账先落，配额判定后置 ⇒ 走 QUOTA 出口
      llm.enqueue(new LlmResponse(answer, "fake-model", 6, 5));
      AgentPipeline pipeline =
          pipeline(llm, events, bus, store, AgentConfig.builder("main").quotaMaxTokens(10).build());

      TurnResult result = pipeline.run("问题一");

      assertThat(result.stopReason()).isEqualTo(StopReason.QUOTA);
      assertThat(result.turns()).isEqualTo(1);
      assertThat(pipeline.lastHistory())
          .as("QUOTA 出口保留模型答复（窗口）")
          .containsExactly(LlmMessage.user("问题一"), answer);
      assertThat(store.load(CONV))
          .as("QUOTA 出口保留模型答复（已落库）")
          .containsExactly(LlmMessage.user("问题一"), answer);
    }
  }

  /** 判据 13（§5.2）：{@code llm.chat} 抛 {@link LlmException} ⇒ 本轮 user 在窗口与库里，assistant 一条都没有。 */
  @Test
  void llmFailureKeepsTheTurnInputInWindowAndStoreWithoutAnyAssistant() {
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      // 空脚本 ⇒ 第一次 chat 即抛 LlmException
      RecordingLlmClient llm = new RecordingLlmClient();
      AgentPipeline pipeline = pipeline(llm, events, bus, store);

      TurnResult result = pipeline.run("问一句");

      assertThat(result.stopReason()).isEqualTo(StopReason.LLM_ERROR);
      assertThat(llm.requests).hasSize(1);
      assertThat(llm.requests.get(0).messages()).contains(LlmMessage.user("问一句"));
      assertThat(pipeline.lastHistory())
          .as("失败回合：窗口含本轮 user")
          .containsExactly(LlmMessage.user("问一句"));
      assertThat(pipeline.lastHistory())
          .as("失败回合：窗口不含 assistant")
          .noneMatch(message -> LlmMessage.ROLE_ASSISTANT.equals(message.role()));
      assertThat(store.load(CONV)).as("失败回合：本轮 user 已落库").containsExactly(LlmMessage.user("问一句"));
      assertThat(store.load(CONV))
          .as("失败回合：库里没有 assistant")
          .noneMatch(message -> LlmMessage.ROLE_ASSISTANT.equals(message.role()));
    }
  }

  private static AgentPipeline pipeline(
      LlmClient llm, EventStore events, EventBus bus, ConversationStore store) {
    return pipeline(llm, events, bus, store, AgentConfig.builder("main").build());
  }

  private static AgentPipeline pipeline(
      LlmClient llm,
      EventStore events,
      EventBus bus,
      ConversationStore store,
      ContextAssembler assembler) {
    return pipeline(llm, events, bus, store, AgentConfig.builder("main").build(), assembler);
  }

  private static AgentPipeline pipeline(
      LlmClient llm, EventStore events, EventBus bus, ConversationStore store, AgentConfig config) {
    return pipeline(llm, events, bus, store, config, new BasicContextAssembler());
  }

  private static AgentPipeline pipeline(
      LlmClient llm,
      EventStore events,
      EventBus bus,
      ConversationStore store,
      AgentConfig config,
      ContextAssembler assembler) {
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

  /** 装配器桩的三种档：老实装配（对照）、等长改写、等长重排。 */
  private enum Breach {
    /** 不破坏：{@code [system] + 窗口原样 + 本轮 user}。 */
    NONE,
    /** 等长改写：把窗口尾条换成同 role 的另一条 ⇒ 长度与 role 位形都不变，只有内容变了。 */
    REWRITE_WINDOW_TAIL,
    /** 等长重排：把窗口两段顺序互换 ⇒ 长度不变，本轮 user 的绝对下标也不变。 */
    REORDER_WINDOW
  }

  /** 与既有 {@code ContractViolatingAssembler} 同风格，但破坏的是"等长"那一档（旧守卫看不见的形状）。 */
  private static final class BreachingAssembler implements ContextAssembler {

    private final Breach breach;

    BreachingAssembler(Breach breach) {
      this.breach = breach;
    }

    @Override
    public LlmRequest buildRequest(
        AgentConfig config, String userMessage, List<LlmMessage> history, List<AgentTool> tools) {
      List<LlmMessage> replayed = new ArrayList<>(history);
      switch (breach) {
        case REWRITE_WINDOW_TAIL -> {
          if (!replayed.isEmpty()) {
            replayed.set(
                replayed.size() - 1, LlmMessage.assistant(List.of(new ContentPart.Text("被改写的历史"))));
          }
        }
        case REORDER_WINDOW -> Collections.reverse(replayed);
        case NONE -> {}
      }
      List<LlmMessage> messages = new ArrayList<>(replayed.size() + 2);
      messages.add(LlmMessage.system("桩 system"));
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

  /** 记录每次收到的 LlmRequest 的脚本桩（与既有夹具同源；脚本耗空即抛 LlmException）。 */
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
