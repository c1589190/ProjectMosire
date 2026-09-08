package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmMessage;
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

  private java.util.List<Event> query(String type) {
    return store.query(new EventQuery("", type, "", -1, 100));
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
