package io.mosire.agentlib.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FakeLlmClientTest {

  @Test
  void replaysScriptInOrder() {
    FakeLlmClient client = FakeLlmClient.with(LlmResponse.text("第一"), LlmResponse.text("第二"));
    assertThat(client.chat(LlmRequest.ofMessages(List.of())).textPart()).contains("第一");
    assertThat(client.chat(LlmRequest.ofMessages(List.of())).textPart()).contains("第二");
    assertThat(client.calls()).isEqualTo(2);
    assertThat(client.queued()).isZero();
  }

  @Test
  void throwsWhenScriptExhausted() {
    FakeLlmClient client = FakeLlmClient.with(LlmResponse.text("只有一条"));
    client.chat(LlmRequest.ofMessages(List.of()));
    assertThatThrownBy(() -> client.chat(LlmRequest.ofMessages(List.of())))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("耗空");
  }

  @Test
  void toolCallResponseCarriesArgumentsThrough() {
    LlmResponse response = LlmResponse.toolCall("c-1", "echo", Map.of("text", "hi"));
    LlmMessage message = response.assistantMessage();
    assertThat(message.role()).isEqualTo("assistant");
    ContentPart.ToolCall call = (ContentPart.ToolCall) message.content().get(0);
    assertThat(call.id()).isEqualTo("c-1");
    assertThat(call.name()).isEqualTo("echo");
    assertThat(call.arguments()).containsEntry("text", "hi");
  }

  /** 观测名如实报 {@code fake}——与"没声明模型"的 {@code unknown} 分开：两者混同会让假模型探针两个方向同时失效。 */
  @Test
  void modelIsReportedAsFake() {
    assertThat(new FakeLlmClient().model()).isEqualTo("fake");
  }
}
