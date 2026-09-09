package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code run --fake-script} 的脚本解析/播放测试：步骤序（text/tool 交替）、{}参数可省略、 {@code $spawnedId}
 * 占位符解析为请求消息中最近一次 spawn_sub_agent 工具结果的 instanceId、耗尽即抛、坏格式即拒。
 */
class FakeLlmScriptTest {

  private static LlmRequest requestWithSpawn(String instanceId) {
    return LlmRequest.ofMessages(
        List.of(
            LlmMessage.user("产出环节"),
            LlmMessage.tool(
                new ContentPart.ToolResult(
                    "fake-1",
                    "spawn_sub_agent",
                    "{\"instanceId\":\"" + instanceId + "\"}",
                    null))));
  }

  @Test
  void textAndToolStepsPlayInOrderAndExhaustionThrows() throws Exception {
    LlmClient client =
        FakeLlmScript.parse(
            "text:第一轮回答;tool:spawn_sub_agent:{\"templateId\":\"reader\",\"goal\":\"问候\"};text:已派遣");
    LlmResponse first = client.chat(LlmRequest.ofMessages(List.of(LlmMessage.user("你好"))));
    assertThat(first.textPart()).contains("第一轮回答");

    LlmResponse second = client.chat(requestWithSpawn("i-1"));
    ContentPart.ToolCall call = (ContentPart.ToolCall) second.assistantMessage().content().get(0);
    assertThat(call.name()).isEqualTo("spawn_sub_agent");
    assertThat(call.arguments()).isEqualTo(Map.of("templateId", "reader", "goal", "问候"));

    LlmResponse third = client.chat(requestWithSpawn("i-1"));
    assertThat(third.textPart()).contains("已派遣");
    assertThatThrownBy(() -> client.chat(requestWithSpawn("i-1")))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("已耗空");
  }

  /** tool 步骤的参数可省略（默认 {}）；tool arg 缺省 OK。 */
  @Test
  void toolStepWithoutArgsDefaultsToEmpty() throws Exception {
    LlmClient client = FakeLlmScript.parse("tool:list_sub_agents");
    LlmResponse response = client.chat(requestWithSpawn("i-1"));
    ContentPart.ToolCall call = (ContentPart.ToolCall) response.assistantMessage().content().get(0);
    assertThat(call.name()).isEqualTo("list_sub_agents");
    assertThat(call.arguments()).isEmpty();
  }

  /** $spawnedId 占位符：命中请求里最近的 spawn_sub_agent 结果实例 id。 */
  @Test
  void spawnedIdPlaceholderResolvesFromLastSpawnResult() throws Exception {
    LlmClient client = FakeLlmScript.parse("tool:kill_sub_agent:{\"instanceId\":\"$spawnedId\"}");
    LlmResponse response =
        client.chat(
            LlmRequest.ofMessages(
                List.of(
                    LlmMessage.tool(
                        new ContentPart.ToolResult(
                            "fake-1", "spawn_sub_agent", "{\"instanceId\":\"child-1\"}", null)),
                    LlmMessage.tool(
                        new ContentPart.ToolResult(
                            "fake-2", "spawn_sub_agent", "{\"instanceId\":\"child-2\"}", null)))));
    ContentPart.ToolCall call = (ContentPart.ToolCall) response.assistantMessage().content().get(0);
    assertThat(call.arguments()).isEqualTo(Map.of("instanceId", "child-2"));
  }

  /** 占位符不可解析（请求中无 spawn 结果）→ LlmException 透出明确原因（进程面脚本排障用）。 */
  @Test
  void unresolvedPlaceholderFailsLoudly() {
    LlmClient client = FakeLlmScript.parse("tool:kill_sub_agent:{\"instanceId\":\"$spawnedId\"}");
    assertThatThrownBy(() -> client.chat(LlmRequest.ofMessages(List.of(LlmMessage.user("请终止")))))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("$spawnedId");
  }

  /** 坏格式（非 text:/tool: 前缀、空文本）在解析期即拒（与 BootConfig 解析一致的失败面）。 */
  @Test
  void malformedStepsAreRejectedAtParse() {
    assertThatThrownBy(() -> FakeLlmScript.parse("foo"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FakeLlmScript.parse("text:"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FakeLlmScript.parse("tool::{\"a\":1}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FakeLlmScript.parse("tool:echo:not-json"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FakeLlmScript.parse("")).isInstanceOf(IllegalArgumentException.class);
  }
}
