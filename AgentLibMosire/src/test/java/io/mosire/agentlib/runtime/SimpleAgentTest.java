package io.mosire.agentlib.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionTurnScope;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.llm.ToolDef;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SimpleAgentTest {

  private static final Map<String, Object> TEXT_SCHEMA =
      Map.of(
          "type", "object",
          "properties", Map.of("text", Map.of("type", "string")),
          "required", List.of("text"));

  @Test
  void callsAuthorizedFunctionAndReturnsDeniedResultsFromTheSameToolSnapshot() {
    AtomicInteger echoRuns = new AtomicInteger();
    AtomicInteger restrictedRuns = new AtomicInteger();
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll(
        List.of(
            AgentTool.of(
                "echo",
                "返回输入文本",
                TEXT_SCHEMA,
                ToolSpec.DEFAULT,
                call -> {
                  echoRuns.incrementAndGet();
                  return ToolResult.ok((String) call.arguments().get("text"));
                }),
            trackedTool("config_deny", ToolGate.ALLOW, restrictedRuns),
            trackedTool("host_deny", ToolGate.ALLOW, restrictedRuns),
            trackedTool("outside_config", ToolGate.ALLOW, restrictedRuns),
            trackedTool("outside_host", ToolGate.ALLOW, restrictedRuns),
            trackedTool("approval", new ToolGate.Ask("fixture", "确认"), restrictedRuns)));
    AgentConfig config =
        AgentConfig.builder("simple")
            .allowedTools(Set.of("echo", "config_deny", "host_deny", "outside_host", "approval"))
            .deniedTools(Set.of("config_deny"))
            .build();
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow("echo", "config_deny", "host_deny", "outside_config", "approval")
            .deny("host_deny")
            .build();
    AtomicInteger rounds = new AtomicInteger();
    SimpleAgent agent =
        SimpleAgent.create(
            config,
            request -> {
              assertThat(request.tools())
                  .extracting(ToolDef::name)
                  .containsExactlyInAnyOrder("echo", "approval");
              if (rounds.getAndIncrement() == 0) {
                registry.unregister("echo");
                registry.register(trackedTool("echo", ToolGate.ALLOW, restrictedRuns));
                return replyWithCalls(
                    new ContentPart.ToolCall("ok", "echo", Map.of("text", "hello")),
                    call("config", "config_deny"),
                    call("host", "host_deny"),
                    call("outside-config", "outside_config"),
                    call("outside-host", "outside_host"),
                    call("approval", "approval"));
              }
              List<ContentPart.ToolResult> results = toolResults(request);
              assertThat(results)
                  .extracting(ContentPart.ToolResult::toolCallId)
                  .containsExactly(
                      "ok", "config", "host", "outside-config", "outside-host", "approval");
              assertThat(results.getFirst().content()).isEqualTo("hello");
              assertThat(results.subList(1, 5))
                  .allSatisfy(result -> assertThat(result.error()).contains("工具不存在"));
              assertThat(results.getLast().error()).contains("未装配审批编排器");
              return LlmResponse.text("done");
            },
            registry,
            permissions);

    PendingDecisionQueue outerQueue =
        DecisionTurnScope.bind(new PendingDecisionQueue("outer", AccessToken.DEFAULT));
    try {
      TurnResult result = agent.chat("执行回显");
      assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(result.finalText()).contains("done");
      assertThat(rounds.get()).isEqualTo(2);
      assertThat(echoRuns.get()).isEqualTo(1);
      assertThat(restrictedRuns.get()).isZero();
      assertThat(outerQueue.isEmpty()).isTrue();
      assertThat(DecisionTurnScope.current()).containsSame(outerQueue);
    } finally {
      DecisionTurnScope.clear(outerQueue);
    }
  }

  @Test
  void anOverLimitBatchLeavesAResultForEverySkippedCallInTheNextTurn() {
    AtomicInteger bodyRuns = new AtomicInteger();
    ToolRegistry registry = new ToolRegistry();
    registry.register(trackedTool("echo", ToolGate.ALLOW, bodyRuns));
    AgentConfig config = AgentConfig.builder("simple").maxToolCallsPerTurn(1).build();
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("echo").build();
    AtomicInteger requests = new AtomicInteger();
    SimpleAgent agent =
        SimpleAgent.create(
            config,
            request -> {
              if (requests.getAndIncrement() == 0) {
                return replyWithCalls(call("first", "echo"), call("second", "echo"));
              }
              assertThat(request.messages())
                  .containsSubsequence(LlmMessage.user("发出两个调用"), LlmMessage.user("继续"));
              List<ContentPart.ToolResult> results = toolResults(request);
              assertThat(results)
                  .extracting(ContentPart.ToolResult::toolCallId)
                  .containsExactly("first", "second");
              assertThat(results)
                  .allSatisfy(result -> assertThat(result.error()).contains("整批未执行"));
              return LlmResponse.text("continued");
            },
            registry,
            permissions);

    TurnResult stopped = agent.chat("发出两个调用");
    assertThat(stopped.stopReason()).isEqualTo(StopReason.TOOL_CALL_LIMIT);
    assertThat(stopped.toolCalls()).isZero();
    assertThat(agent.chat("继续").finalText()).contains("continued");
    assertThat(requests.get()).isEqualTo(2);
    assertThat(bodyRuns.get()).isZero();
  }

  private static ContentPart.ToolCall call(String id, String name) {
    return new ContentPart.ToolCall(id, name, Map.of());
  }

  private static LlmResponse replyWithCalls(ContentPart.ToolCall... calls) {
    return new LlmResponse(LlmMessage.assistant(List.of(calls)), "fixture", 1, 1);
  }

  private static List<ContentPart.ToolResult> toolResults(LlmRequest request) {
    return request.messages().stream()
        .flatMap(message -> message.content().stream())
        .filter(ContentPart.ToolResult.class::isInstance)
        .map(ContentPart.ToolResult.class::cast)
        .toList();
  }

  private static AgentTool trackedTool(
      String name, ToolGate classification, AtomicInteger bodyRuns) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public ToolGate gate(ToolContext context) {
        return classification;
      }

      @Override
      public ToolResult execute(ToolContext context) {
        bodyRuns.incrementAndGet();
        return ToolResult.ok("fixture");
      }
    };
  }
}
