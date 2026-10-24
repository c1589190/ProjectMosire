package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.compact.CompactionOutcome;
import io.mosire.agentlib.compact.CompactionPolicy;
import io.mosire.agentlib.compact.ContextCompactor;
import io.mosire.agentlib.compact.DefaultContextCompactor;
import io.mosire.agentlib.context.ContextComposer;
import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.runtime.StopReason;
import io.mosire.agentlib.runtime.TurnResult;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.context.BasicContextAssembler;
import io.mosire.brain.context.CompactSummarySlot;
import io.mosire.brain.context.Compactor;
import io.mosire.brain.context.ContextPolicy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Brain 管线的新 ContextCompactor 接线、事件、失败与持久化重启。 */
class AgentPipelineDefaultCompactionTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CONV = "conv-default-compact";
  private static final String SYSTEM = "SYSTEM-PROMPT";
  private static final String OLD_TEXT = "OLD-HISTORY-";

  @TempDir Path tempDir;

  @Test
  void everyLlmRoundTripInvokesTheNewCompactorIncludingInsideAToolLoop() throws Exception {
    Path db = tempDir.resolve("every-round.db");
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      List<String> order = new ArrayList<>();
      ScriptedClient llm = new ScriptedClient(order);
      llm.enqueue(LlmResponse.toolCall("call-1", "echo", Map.of("text", "hi")));
      llm.enqueue(LlmResponse.text("finished"));
      ToolRegistry registry = new ToolRegistry();
      registry.register(new EchoTool());
      AgentPermissionSet permissions =
          AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
      AgentPipeline pipeline =
          new AgentPipeline(
              AgentConfig.builder("main").systemPrompt(SYSTEM).maxTurns(5).build(),
              llm,
              registry,
              new ToolExecutionGuard(),
              new BasicContextAssembler(),
              events,
              bus,
              permissions.grantedToken(),
              permissions,
              store,
              CONV,
              new AlwaysCompactingCompactor(order));

      TurnResult result = pipeline.run("CURRENT-USER");

      assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(order).containsExactly("compact", "llm", "compact", "llm");
      assertThat(llm.requests).hasSize(2);
      assertThat(llm.requests.get(0).messages())
          .filteredOn(m -> "user".equals(m.role()))
          .hasSize(1);
      assertThat(llm.requests.get(1).messages())
          .filteredOn(m -> "user".equals(m.role()))
          .hasSize(1);
      assertThat(llm.requests)
          .allSatisfy(
              request ->
                  assertThat(request.messages())
                      .noneMatch(message -> ContextWindow.ROLE_COMPACT.equals(message.role())));
    }
  }

  @Test
  void successfulCheckpointWritesCompactAndCallEventsWithoutSummaryBody() throws Exception {
    Path db = tempDir.resolve("success.db");
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      seed(store);
      ScriptedClient llm = new ScriptedClient(new ArrayList<>());
      llm.enqueue(summarizerResponse("SECRET-SUMMARY-BODY"));
      llm.enqueue(LlmResponse.text("main answer"));
      AgentPipeline pipeline = pipelineWithCompactor(llm, events, bus, store, policy());

      TurnResult result = pipeline.run("new question");

      assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(llm.requests).hasSize(2);
      assertThat(llm.requests.get(0).tools()).as("摘要请求无工具").isEmpty();
      assertThat(llm.requests.get(0).messages())
          .noneMatch(
              message ->
                  message.content().stream().anyMatch(ContentPart.Text.class::isInstance)
                      && textOf(message).contains("new question"));

      List<Event> compactEvents = eventsOf(events, EventTypes.CONVERSATION_COMPACT);
      assertThat(compactEvents).hasSize(1);
      JsonNode payload = JSON.readTree(compactEvents.get(0).payload());
      assertThat(payload.get("tier").asText()).isEqualTo("checkpoint");
      assertThat(payload.get("reason").asText()).isEqualTo("threshold");
      assertThat(payload.get("beforeTokens").asInt())
          .isGreaterThanOrEqualTo(payload.get("triggerTokens").asInt());
      assertThat(payload.get("afterTokens").asInt())
          .isLessThan(payload.get("triggerTokens").asInt());
      assertThat(payload.get("droppedMessages").asInt()).isPositive();
      assertThat(payload.get("summaryTokens").asInt()).isPositive();
      assertThat(compactEvents.get(0).payload()).doesNotContain("SECRET-SUMMARY-BODY");

      List<Event> compactCalls =
          eventsOf(events, EventTypes.LLM_CALL).stream()
              .filter(event -> event.payload().contains("\"phase\":\"compact\""))
              .toList();
      assertThat(compactCalls).hasSize(1);
      JsonNode call = JSON.readTree(compactCalls.get(0).payload());
      assertThat(call.get("phase").asText()).isEqualTo("compact");
      assertThat(call.get("model").asText()).isEqualTo("summary-model");
      assertThat(call.get("inputTokens").asLong()).isEqualTo(321);
      assertThat(call.get("outputTokens").asLong()).isEqualTo(123);
      assertThat(compactCalls.get(0).payload()).doesNotContain("SECRET-SUMMARY-BODY");

      assertThat(pipeline.lastHistory()).hasSize(2);
      assertThat(textOf(pipeline.lastHistory().get(0))).contains("new question");
      assertThat(llm.requests.get(1).messages())
          .noneMatch(message -> ContextWindow.ROLE_COMPACT.equals(message.role()));
    }
  }

  @Test
  void blankSummaryFailsStrictlyKeepsTheUserAndNeverSendsAnUncompactedMainRequest()
      throws Exception {
    Path db = tempDir.resolve("failure.db");
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      seed(store);
      ScriptedClient llm = new ScriptedClient(new ArrayList<>());
      llm.enqueue(LlmResponse.text("   "));
      AgentPipeline pipeline = pipelineWithCompactor(llm, events, bus, store, policy());

      TurnResult result = pipeline.run("new question");

      assertThat(result.stopReason()).isEqualTo(StopReason.LLM_ERROR);
      assertThat(llm.requests).hasSize(1);
      assertThat(llm.requests.get(0).tools()).as("唯一调用是摘要调用，不是主请求").isEmpty();
      assertThat(llm.requests.get(0).messages())
          .noneMatch(message -> textOf(message).contains("new question"));

      List<Event> decisions = eventsOf(events, EventTypes.DECISION);
      assertThat(decisions)
          .anySatisfy(event -> assertThat(event.payload()).contains("COMPACT_FAILED"));
      assertThat(eventsOf(events, EventTypes.CONVERSATION_COMPACT)).isEmpty();
      List<Event> compactCalls =
          eventsOf(events, EventTypes.LLM_CALL).stream()
              .filter(event -> event.payload().contains("\"phase\":\"compact\""))
              .toList();
      assertThat(compactCalls).hasSize(1);

      assertThat(pipeline.lastHistory())
          .anySatisfy(message -> assertThat(textOf(message)).isEqualTo("new question"));
      assertThat(store.load(CONV))
          .anySatisfy(message -> assertThat(textOf(message)).isEqualTo("new question"));
      assertThat(
              store.load(CONV).stream()
                  .filter(message -> textOf(message).equals("new question"))
                  .count())
          .isEqualTo(1);
    }
  }

  @Test
  void requestCompactionIsForwardedAndUnwiredPipelinesThrow() throws Exception {
    Path db = tempDir.resolve("manual.db");
    try (SqliteEventStore events = SqliteEventStore.open(db);
        EventBus bus = new EventBus()) {
      CountingCompactor counting = new CountingCompactor();
      AgentConfig config = AgentConfig.builder("main").systemPrompt(SYSTEM).build();
      AgentPermissionSet permissions = AgentPermissionSet.system();
      AgentPipeline wired =
          new AgentPipeline(
              config,
              request -> LlmResponse.text("unused"),
              new ToolRegistry(),
              new ToolExecutionGuard(),
              new BasicContextAssembler(),
              events,
              bus,
              permissions.grantedToken(),
              permissions,
              Map.of(),
              counting);
      wired.requestCompaction();
      assertThat(counting.requests).isEqualTo(1);

      AgentPipeline unwired =
          new AgentPipeline(
              config,
              request -> LlmResponse.text("unused"),
              new ToolRegistry(),
              new ToolExecutionGuard(),
              new BasicContextAssembler(),
              events,
              bus,
              permissions.grantedToken(),
              permissions);
      assertThatThrownBy(unwired::requestCompaction)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("未接 ContextCompactor");
    }
  }

  @Test
  void runtimeRequestCompactionIsForwardedAndUnwiredRuntimeThrows() throws Exception {
    Path db = tempDir.resolve("runtime-manual.db");
    try (SqliteEventStore events = SqliteEventStore.open(db);
        EventBus bus = new EventBus()) {
      AgentConfig config = AgentConfig.builder("main").systemPrompt(SYSTEM).build();
      AgentPermissionSet permissions = AgentPermissionSet.system();
      CountingCompactor counting = new CountingCompactor();
      AgentRuntime wired =
          new AgentRuntime(
              config,
              request -> LlmResponse.text("unused"),
              new ToolRegistry(),
              events,
              bus,
              permissions,
              (io.mosire.brain.decision.CrossProcessDecisionSubmitter) null,
              counting);
      wired.requestCompaction();
      assertThat(counting.requests).isEqualTo(1);
      wired.close();

      AgentRuntime unwired =
          new AgentRuntime(
              config,
              request -> LlmResponse.text("unused"),
              new ToolRegistry(),
              events,
              bus,
              permissions);
      assertThatThrownBy(unwired::requestCompaction)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("未接 ContextCompactor");
      unwired.close();
    }
  }

  @Test
  void oldAndNewCompactorsCannotBeInjectedIntoTheSamePipeline() throws Exception {
    Path db = tempDir.resolve("double-compaction.db");
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      AgentConfig config = AgentConfig.builder("main").systemPrompt(SYSTEM).build();
      AgentPermissionSet permissions = AgentPermissionSet.system();
      Compactor oldCompactor =
          new Compactor(ContextPolicy.defaults(), request -> LlmResponse.text("unused"));

      assertThatThrownBy(
              () ->
                  new AgentPipeline(
                      config,
                      request -> LlmResponse.text("unused"),
                      new ToolRegistry(),
                      new ToolExecutionGuard(),
                      new BasicContextAssembler(),
                      events,
                      bus,
                      permissions.grantedToken(),
                      permissions,
                      store,
                      CONV,
                      oldCompactor,
                      CompactSummarySlot.empty(),
                      new CountingCompactor()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("不能同时注入");
    }
  }

  @Test
  void persistedCheckpointReloadsToTheSameVisibleHistoryAndOldRowsRemain() throws Exception {
    Path db = tempDir.resolve("restart.db");
    try (SqliteEventStore events = SqliteEventStore.open(db);
        SqliteConversationStore store = SqliteConversationStore.open(db);
        EventBus bus = new EventBus()) {
      List<LlmMessage> seeded = seed(store);
      ScriptedClient firstLlm = new ScriptedClient(new ArrayList<>());
      firstLlm.enqueue(summarizerResponse("PERSISTED-SUMMARY"));
      firstLlm.enqueue(LlmResponse.text("first answer"));
      AgentPipeline first = pipelineWithCompactor(firstLlm, events, bus, store, policy());

      assertThat(first.run("checkpoint user").stopReason()).isEqualTo(StopReason.FINISHED);
      List<LlmMessage> rawAfter = store.load(CONV);
      List<LlmMessage> visibleBeforeRestart = first.lastHistory();
      ContextWindow reloaded = new ContextWindow(CONV, rawAfter);
      assertThat(reloaded.messages()).isEqualTo(visibleBeforeRestart);
      assertThat(rawAfter.subList(0, seeded.size())).isEqualTo(seeded);
      assertThat(rawAfter).hasSize(seeded.size() + 3);

      ScriptedClient secondLlm = new ScriptedClient(new ArrayList<>());
      secondLlm.enqueue(LlmResponse.text("second answer"));
      AgentPipeline restarted =
          new AgentPipeline(
              AgentConfig.builder("main").systemPrompt(SYSTEM).build(),
              secondLlm,
              new ToolRegistry(),
              new ToolExecutionGuard(),
              new BasicContextAssembler(),
              events,
              bus,
              AgentPermissionSet.system().grantedToken(),
              AgentPermissionSet.system(),
              store,
              CONV,
              (ContextCompactor) null);

      assertThat(restarted.run("after restart").stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(secondLlm.requests).hasSize(1);
      String sent = allTextOf(secondLlm.requests.get(0).messages());
      assertThat(sent)
          .contains("PERSISTED-SUMMARY")
          .contains("checkpoint user")
          .contains("after restart");
      assertThat(sent).doesNotContain(OLD_TEXT);
      assertThat(secondLlm.requests.get(0).messages())
          .noneMatch(message -> ContextWindow.ROLE_COMPACT.equals(message.role()));
    }
  }

  private static List<LlmMessage> seed(SqliteConversationStore store) {
    List<LlmMessage> seeded =
        List.of(
            LlmMessage.user(OLD_TEXT + "u" + "x".repeat(2400)),
            LlmMessage.assistant(List.of(new ContentPart.Text(OLD_TEXT + "a"))));
    for (LlmMessage message : seeded) {
      store.append(CONV, message);
    }
    return seeded;
  }

  private static AgentPipeline pipelineWithCompactor(
      LlmClient llm,
      EventStore events,
      EventBus bus,
      SqliteConversationStore store,
      CompactionPolicy policy) {
    ContextCompactor compactor = new DefaultContextCompactor(llm, policy);
    AgentPermissionSet permissions = AgentPermissionSet.system();
    return new AgentPipeline(
        AgentConfig.builder("main").systemPrompt(SYSTEM).maxTurns(5).build(),
        llm,
        new ToolRegistry(),
        new ToolExecutionGuard(),
        new BasicContextAssembler(),
        events,
        bus,
        permissions.grantedToken(),
        permissions,
        store,
        CONV,
        compactor);
  }

  private static CompactionPolicy policy() {
    return new CompactionPolicy(1000, 500, 100, 0, "P");
  }

  private static LlmResponse summarizerResponse(String text) {
    return new LlmResponse(
        LlmMessage.assistant(List.of(new ContentPart.Text(text))),
        "summary-model",
        321,
        123,
        45,
        6);
  }

  private static List<Event> eventsOf(EventStore events, String type) {
    return events.query(new EventQuery("", type, "", -1, 100));
  }

  private static String allTextOf(List<LlmMessage> messages) {
    StringBuilder text = new StringBuilder();
    for (LlmMessage message : messages) {
      text.append(textOf(message)).append('\n');
    }
    return text.toString();
  }

  private static String textOf(LlmMessage message) {
    return message.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(ContentPart.Text.class::cast)
        .map(ContentPart.Text::text)
        .reduce("", String::concat);
  }

  private static final class ScriptedClient implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<String> order;
    private final List<LlmRequest> requests = new ArrayList<>();

    private ScriptedClient(List<String> order) {
      this.order = order;
    }

    void enqueue(LlmResponse response) {
      script.addLast(response);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      order.add("llm");
      requests.add(request);
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new AssertionError("LLM 脚本耗空");
      }
      return response;
    }
  }

  private static final class AlwaysCompactingCompactor implements ContextCompactor {

    private final List<String> order;
    private int calls;

    private AlwaysCompactingCompactor(List<String> order) {
      this.order = order;
    }

    @Override
    public CompactionOutcome compactIfNeeded(
        ContextWindow window,
        List<LlmMessage> turnInput,
        List<AgentTool> tools,
        ContextComposer composer) {
      order.add("compact");
      calls++;
      composer.compose(window.messages(), turnInput, tools);
      int dropped = window.size();
      window.compact("ROUND-" + calls + "-SUMMARY");
      if (turnInput.isEmpty()) {
        window.append(LlmMessage.user("任务尚未完成。请继续。"));
      } else {
        window.appendAll(turnInput);
      }
      return new CompactionOutcome(List.of(), true, "threshold", 0, 0, 0, dropped, 0, null, 0L);
    }
  }

  private static final class CountingCompactor implements ContextCompactor {

    private int requests;

    @Override
    public CompactionOutcome compactIfNeeded(
        ContextWindow window,
        List<LlmMessage> turnInput,
        List<AgentTool> tools,
        ContextComposer composer) {
      return new CompactionOutcome(List.copyOf(turnInput), false, "", 0, 0, 0, 0, 0, null, 0L);
    }

    @Override
    public void requestCompaction() {
      requests++;
    }
  }

  private record EchoTool() implements AgentTool {

    @Override
    public String name() {
      return "echo";
    }

    @Override
    public String description() {
      return "echo text";
    }

    @Override
    public ToolResult execute(ToolContext context) {
      return ToolResult.ok("echo:" + context.arguments().getOrDefault("text", ""));
    }
  }
}
