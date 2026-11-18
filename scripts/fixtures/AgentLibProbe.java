import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.runtime.SimpleAgent;
import io.mosire.agentlib.runtime.StopReason;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Map;

public final class AgentLibProbe {
  public static void main(String[] args) {
    var registry = new ToolRegistry();
    registry.register(AgentTool.of(
        "echo", "Return the fixture value", Map.of("type", "object"), ToolSpec.DEFAULT,
        context -> ToolResult.ok("sdk-tool-result")));
    var permissions = AgentPermissionSet.builder(AccessToken.GUEST).allow("echo").build();
    var agent = SimpleAgent.create(AgentConfig.builder("probe").build(), request -> {
      var last = request.messages().getLast();
      if (last.role().equals(LlmMessage.ROLE_TOOL)) {
        if (!(last.content().getFirst() instanceof ContentPart.ToolResult result)
            || !result.toolCallId().equals("probe-call") || !"sdk-tool-result".equals(result.content())) {
          throw new IllegalStateException("Tool result missing from model request");
        }
        return LlmResponse.text("sdk-agentlib-ready");
      }
      if (request.tools().size() != 1 || !request.tools().getFirst().name().equals("echo")) {
        throw new IllegalStateException("Expected exactly one authorized tool");
      }
      return LlmResponse.toolCall("probe-call", "echo", Map.of());
    }, registry, permissions);
    var turn = agent.chat("Run the fixture tool");
    if (turn.stopReason() != StopReason.FINISHED || turn.turns() != 2 || turn.toolCalls() != 1
        || !"sdk-agentlib-ready".equals(turn.text())) {
      throw new IllegalStateException("AgentLib tool round trip did not complete");
    }
    System.out.println("AGENTLIB TOOL ROUND TRIP PASSED");
  }
}
