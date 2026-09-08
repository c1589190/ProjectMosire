package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.brain.runtime.AgentConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentTemplate} 的脚本字段与派生件（W3b 新增）：{@code script} 是子 Agent 离线假 LLM 的引导脚本 （模型名默认
 * fake；脚本为模板要素，数据不是代码），并提供以实例 id 重造的 {@link #toAgentConfig(String)}。
 */
class AgentTemplateScriptTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void parsesScriptStepsFromJsonAndNormalizes() throws Exception {
    AgentTemplate template =
        JSON.readValue(
            """
            {
              "id": "reader",
              "systemPrompt": "只读子 Agent",
              "maxTurns": 5,
              "maxToolCallsPerTurn": 5,
              "timeBudgetSeconds": 60,
              "quotaMaxTokens": 0,
              "script": [
                {"toolCall": "echo", "args": {"text": "问好"}},
                {"text": "子 Agent 完成"}
              ]
            }
            """,
            AgentTemplate.class);
    assertThat(template.script()).hasSize(2);
    AgentTemplate.ScriptStep first = template.script().get(0);
    assertThat(first.toolCall()).isEqualTo("echo");
    assertThat(first.args()).containsEntry("text", "问好");
    assertThat(first.text()).isNull();
    AgentTemplate.ScriptStep second = template.script().get(1);
    assertThat(second.text()).isEqualTo("子 Agent 完成");
    // 无 script 字段 → 空脚本（规范构造归一），不算非法模板
    assertThat(
            JSON.readValue(
                    "{"
                        + "\"id\":\"plain\",\"systemPrompt\":\"x\",\"maxTurns\":2,"
                        + "\"maxToolCallsPerTurn\":2,\"timeBudgetSeconds\":10,\"quotaMaxTokens\":0}",
                    AgentTemplate.class)
                .script())
        .isEmpty();
  }

  @Test
  void rejectsAmbiguousScriptSteps() {
    assertThatThrownBy(() -> new AgentTemplate.ScriptStep("echo", Map.of(), "text"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AgentTemplate.ScriptStep(null, Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AgentTemplate.ScriptStep("", Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void scriptedFakeLlmPlaysStepsInOrderThenExhausts() {
    AgentTemplate template = sampleWithScript("reader");
    LlmClient llm = template.scriptedFakeLlm();
    LlmResponse first = llm.chat(LlmRequest.ofMessages(List.of()));
    assertThat(first.assistantMessage().content())
        .extracting(Object::toString)
        .anyMatch(s -> s.contains("echo"));
    LlmResponse second = llm.chat(LlmRequest.ofMessages(List.of()));
    assertThat(second.textPart()).contains("子 Agent 完成");
    assertThatThrownBy(() -> llm.chat(LlmRequest.ofMessages(List.of())))
        .isInstanceOf(io.mosire.agentlib.llm.LlmException.class);
    // 空脚本：一次温和的文本回复（子 Agent 无引导时仍能完成一回合）
    AgentTemplate plain = sampleNoScript("plain");
    assertThat(plain.scriptedFakeLlm().chat(LlmRequest.ofMessages(List.of())).textPart())
        .isNotEmpty();
  }

  @Test
  void toAgentConfigReplacesIdWithInstanceId() {
    AgentTemplate template = sampleWithScript("reader");
    AgentConfig config = template.toAgentConfig("reader-1a2b3c");
    assertThat(config.id()).isEqualTo("reader-1a2b3c");
    assertThat(config.systemPrompt()).isEqualTo(template.systemPrompt());
    assertThat(config.allowedTools()).isEqualTo(template.allowedTools());
  }

  private static AgentTemplate sampleWithScript(String id) {
    return new AgentTemplate(
        id,
        "",
        "只读子 Agent",
        "fake",
        io.mosire.agentlib.permission.AccessToken.DEFAULT,
        java.util.Set.of("echo"),
        java.util.Set.of(),
        false,
        false,
        false,
        5,
        5,
        60,
        0,
        List.of(
            new AgentTemplate.ScriptStep("echo", Map.of("text", "问好"), null),
            new AgentTemplate.ScriptStep(null, Map.of(), "子 Agent 完成")));
  }

  private static AgentTemplate sampleNoScript(String id) {
    return new AgentTemplate(
        id,
        "",
        "普通子 Agent",
        "fake",
        io.mosire.agentlib.permission.AccessToken.DEFAULT,
        java.util.Set.of(),
        java.util.Set.of(),
        false,
        false,
        false,
        2,
        2,
        60,
        0,
        List.of());
  }
}
