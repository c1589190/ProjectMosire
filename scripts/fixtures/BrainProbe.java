import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.runtime.StopReason;
import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.AgentSpec;
import java.nio.file.Path;

public final class BrainProbe {
  public static void main(String[] args) {
    var spec = new AgentSpec(AgentConfig.builder("probe").build());
    var directory = Path.of("runtime-data");
    var firstInput = "Persist this fixture message";
    try (var runtime = AgentRuntime.builder(spec, request -> LlmResponse.text("sdk-brain-saved"))
        .dataDirectory(directory).build()) {
      var turn = runtime.chat(firstInput);
      if (turn.stopReason() != StopReason.FINISHED || !"sdk-brain-saved".equals(turn.text())) {
        throw new IllegalStateException("Brain could not complete the first persisted turn");
      }
    }
    try (var runtime = AgentRuntime.builder(spec, request -> {
      if (!request.messages().contains(LlmMessage.user(firstInput))) {
        throw new IllegalStateException("Persisted user message is missing after reopen");
      }
      return LlmResponse.text("sdk-brain-resumed");
    }).dataDirectory(directory).build()) {
      var turn = runtime.chat("Resume the fixture session");
      if (turn.stopReason() != StopReason.FINISHED || !"sdk-brain-resumed".equals(turn.text())) {
        throw new IllegalStateException("Brain could not resume the persisted turn");
      }
    }
    System.out.println("BRAIN SESSION REOPEN PASSED");
  }
}
