package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.context.BasicContextAssembler;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentPipelineTest {

  @TempDir Path tempDir;

  private SqliteEventStore store;
  private EventBus bus;

  @BeforeEach
  void setUp() {
    store = SqliteEventStore.open(tempDir.resolve("events.db"));
    bus = new EventBus();
  }

  @AfterEach
  void tearDown() {
    bus.close();
    store.close();
  }

  @Test
  void singleTextTurnFinishesWithTurnEventPersisted() {
    AgentConfig config = AgentConfig.builder("main").build();
    FakeLlmClient llm = FakeLlmClient.with(LlmResponse.text("你好，我在。"));
    AgentRuntime runtime =
        new AgentRuntime(config, llm, new ToolRegistry(), store, bus, AgentPermissionSet.system());

    TurnResult result = runtime.chat("在吗");

    assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(result.turns()).isEqualTo(1);
    assertThat(result.finalText()).hasValue("你好，我在。");
    // 事件链：lifecycle(started) + conversation.turn
    assertThat(store.count()).isEqualTo(2);
    assertThat(query(EventTypes.CONVERSATION_TURN)).hasSize(1);
    assertThat(query(EventTypes.AGENT_LIFECYCLE)).hasSize(1);

    runtime.close();
  }

  @Test
  void toolCallRoundTrip() {
    AgentConfig config = AgentConfig.builder("main").maxTurns(3).build();
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    FakeLlmClient llm =
        FakeLlmClient.with(
            LlmResponse.toolCall("c-1", "echo", Map.of("text", "hello")), LlmResponse.text("已回显"));
    AgentRuntime runtime =
        new AgentRuntime(
            config,
            llm,
            registry,
            new ToolExecutionGuard(),
            new BasicContextAssembler(),
            store,
            bus,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build());

    TurnResult result = runtime.chat("帮我回显");

    assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(result.turns()).isEqualTo(2);
    assertThat(result.toolCalls()).isEqualTo(1);
    assertThat(query(EventTypes.TOOL_CALL)).hasSize(1);
    assertThat(query(EventTypes.TOOL_RESULT)).hasSize(1);
    runtime.close();
  }

  @Test
  void turnLimitCapsRunawayLoop() {
    AgentConfig config = AgentConfig.builder("main").maxTurns(2).build();
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    // 模型停不下来：每回合都要执行工具（文本响应会立即 FINISHED，造不出飞驰循环）
    FakeLlmClient llm =
        FakeLlmClient.with(
            LlmResponse.toolCall("c-1", "echo", Map.of("text", "a")),
            LlmResponse.toolCall("c-2", "echo", Map.of("text", "b")),
            LlmResponse.toolCall("c-3", "echo", Map.of("text", "c")));
    AgentRuntime runtime =
        new AgentRuntime(
            config,
            llm,
            registry,
            new ToolExecutionGuard(),
            new BasicContextAssembler(),
            store,
            bus,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build());

    TurnResult result = runtime.chat("继续");

    assertThat(result.stopReason()).isEqualTo(StopReason.TURN_LIMIT);
    assertThat(result.turns()).isEqualTo(2);
    assertThat(result.toolCalls()).isEqualTo(2);
    assertThat(query(EventTypes.DECISION))
        .anySatisfy(e -> assertThat(e.payload()).contains("TURN_LIMIT"));
    runtime.close();
  }

  @Test
  void toolCallLimitCapsSingleResponseFlood() {
    AgentConfig config = AgentConfig.builder("main").maxToolCallsPerTurn(1).maxTurns(5).build();
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    // 单条响应里挤 2 个工具调用（> 每回合上限 1）→ 整回合就地终止，都不执行
    LlmResponse flood =
        new LlmResponse(
            LlmMessage.assistant(
                List.of(
                    new ContentPart.ToolCall("c-1", "echo", Map.of("text", "1")),
                    new ContentPart.ToolCall("c-2", "echo", Map.of("text", "2")))),
            "fake-model",
            LlmResponse.UNKNOWN_TOKENS,
            LlmResponse.UNKNOWN_TOKENS);
    FakeLlmClient llm = FakeLlmClient.with(flood, LlmResponse.text("END"));
    AgentRuntime runtime =
        new AgentRuntime(
            config,
            llm,
            registry,
            new ToolExecutionGuard(),
            new BasicContextAssembler(),
            store,
            bus,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build());

    TurnResult result = runtime.chat("调用两个");

    assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_CALL_LIMIT);
    assertThat(result.turns()).isEqualTo(1);
    assertThat(result.toolCalls()).isEqualTo(0); // 超限回合并未执行任何调用
    runtime.close();
  }

  @Test
  void permissionDeniedEmitsDedicatedEvent() {
    AgentConfig config = AgentConfig.builder("main").maxTurns(2).build();
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    FakeLlmClient llm =
        FakeLlmClient.with(LlmResponse.toolCall("c-1", "echo", Map.of()), LlmResponse.text("end"));
    // 白名单空（不给 echo）→ guard 拒绝
    AgentRuntime runtime =
        new AgentRuntime(
            config,
            llm,
            registry,
            new ToolExecutionGuard(),
            new BasicContextAssembler(),
            store,
            bus,
            AgentPermissionSet.builder(AccessToken.DEFAULT).build());

    TurnResult result = runtime.chat("执行");

    assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(query(EventTypes.PERMISSION_DENIED)).hasSize(1);
    runtime.close();
  }

  /** 多轮回灌：第二轮请求必须包含第一轮的全部消息（system 前缀 + user + assistant 回复），再接新 user—— 这是"Agent 每回合失忆"缺陷的回归锁。 */
  @Test
  void secondTurnReplaysFullHistoryFromFirstTurn() {
    AgentConfig config = AgentConfig.builder("main").build();
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(LlmResponse.text("第一轮回答"));
    llm.enqueue(LlmResponse.text("第二轮回答"));
    AgentRuntime runtime =
        new AgentRuntime(config, llm, new ToolRegistry(), store, bus, AgentPermissionSet.system());

    runtime.chat("问题一");
    TurnResult second = runtime.chat("问题二");

    assertThat(second.stopReason()).isEqualTo(StopReason.FINISHED);
    assertThat(llm.requests).hasSize(2);

    List<LlmMessage> firstRequest = llm.requests.get(0).messages();
    assertThat(firstRequest).hasSize(2);
    assertThat(firstRequest.get(0).role()).isEqualTo(LlmMessage.ROLE_SYSTEM);
    assertThat(firstRequest.get(1).role()).isEqualTo(LlmMessage.ROLE_USER);

    List<LlmMessage> secondRequest = llm.requests.get(1).messages();
    assertThat(secondRequest).hasSize(4);
    assertThat(secondRequest.get(0).role()).isEqualTo(LlmMessage.ROLE_SYSTEM);
    assertThat(secondRequest.get(1).role()).isEqualTo(LlmMessage.ROLE_USER);
    assertThat(textOf(secondRequest.get(1))).isEqualTo("问题一");
    assertThat(secondRequest.get(2).role()).isEqualTo(LlmMessage.ROLE_ASSISTANT);
    assertThat(textOf(secondRequest.get(2))).isEqualTo("第一轮回答");
    assertThat(secondRequest.get(3).role()).isEqualTo(LlmMessage.ROLE_USER);
    assertThat(textOf(secondRequest.get(3))).isEqualTo("问题二");
    runtime.close();
  }

  /** TOOL_CALL_LIMIT 悬空修复：超限回合一整个工具调用都没执行，历史里不能留下无应答的 tool_call——回灌后部分供应商会拒收。 */
  @Test
  void toolCallLimitBackfillsPlaceholderResultsForEveryDanglingCall() {
    AgentConfig config = AgentConfig.builder("main").maxToolCallsPerTurn(1).maxTurns(5).build();
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool());
    LlmResponse flood =
        new LlmResponse(
            LlmMessage.assistant(
                List.of(
                    new ContentPart.ToolCall("c-1", "echo", Map.of("text", "1")),
                    new ContentPart.ToolCall("c-2", "echo", Map.of("text", "2")))),
            "fake-model",
            LlmResponse.UNKNOWN_TOKENS,
            LlmResponse.UNKNOWN_TOKENS);
    FakeLlmClient llm = FakeLlmClient.with(flood, LlmResponse.text("END"));
    AgentPermissionSet permissionSet =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
    AgentPipeline pipeline =
        new AgentPipeline(
            config,
            llm,
            registry,
            new ToolExecutionGuard(),
            new BasicContextAssembler(),
            store,
            bus,
            permissionSet.grantedToken(),
            permissionSet);

    TurnResult result = pipeline.run("调用两个");

    assertThat(result.stopReason()).isEqualTo(StopReason.TOOL_CALL_LIMIT);
    assertThat(result.toolCalls()).isEqualTo(0);

    // 历史（不含 system 头）：user + assistant(2 calls) + 两条占位 tool 结果
    List<LlmMessage> history = pipeline.lastHistory();
    assertThat(history).hasSize(4);
    List<ContentPart.ToolCall> calls = toolCallsOf(history.get(1));
    assertThat(calls).hasSize(2);
    assertThat(toolResultsOf(history.get(2))).hasSize(1);
    assertThat(toolResultsOf(history.get(3))).hasSize(1);
    for (LlmMessage message : history.subList(2, history.size())) {
      for (ContentPart.ToolResult toolResult : toolResultsOf(message)) {
        assertThat(toolResult.toolCallId()).isIn("c-1", "c-2");
        assertThat(toolResult.isError()).isTrue();
        assertThat(toolResult.error()).contains("超限");
      }
    }
  }

  /** LLM 异常路径：不再穿透 run()，而是 LLM_ERROR 优雅终止 + decision 事件 + 历史保留。 */
  @Test
  void llmFailureStopsGracefullyWithDecisionEventAndHistory() {
    AgentConfig config = AgentConfig.builder("main").build();
    // 空脚本：FakeLlmClient 脚本耗尽即抛 LlmException
    FakeLlmClient llm = FakeLlmClient.with();
    AgentPipeline pipeline =
        new AgentPipeline(
            config,
            llm,
            new ToolRegistry(),
            new ToolExecutionGuard(),
            new BasicContextAssembler(),
            store,
            bus,
            AgentPermissionSet.system().grantedToken(),
            AgentPermissionSet.system());

    TurnResult result = pipeline.run("问一句");

    assertThat(result.stopReason()).isEqualTo(StopReason.LLM_ERROR);
    assertThat(query(EventTypes.DECISION))
        .anySatisfy(e -> assertThat(e.payload()).contains("LLM_ERROR"));
    assertThat(pipeline.lastHistory()).isNotEmpty();
  }

  private java.util.List<Event> query(String type) {
    return store.query(new EventQuery("", type, "", -1, 100));
  }

  private static String textOf(LlmMessage message) {
    return message.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(ContentPart.Text.class::cast)
        .map(ContentPart.Text::text)
        .findFirst()
        .orElse("");
  }

  private static List<ContentPart.ToolCall> toolCallsOf(LlmMessage message) {
    return message.content().stream()
        .filter(ContentPart.ToolCall.class::isInstance)
        .map(ContentPart.ToolCall.class::cast)
        .toList();
  }

  private static List<ContentPart.ToolResult> toolResultsOf(LlmMessage message) {
    return message.content().stream()
        .filter(ContentPart.ToolResult.class::isInstance)
        .map(ContentPart.ToolResult.class::cast)
        .toList();
  }

  /** 记录每次收到的 LlmRequest 的脚本桩（FakeLlmClient 不记录请求，多轮回灌断言需要）。 */
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

  private static AgentTool echoTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "echo";
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("echo: " + context.arguments().getOrDefault("text", ""));
      }
    };
  }
}
