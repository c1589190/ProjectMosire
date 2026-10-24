package io.mosire.agentlib.compact;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.ToolDef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 保守字符估算：空文本为 0、ASCII 向上取整、非 ASCII 至少 1 token、完整请求计入 tools。 */
class TokenEstimatorTest {

  private final TokenEstimator estimator = new TokenEstimator();

  @Test
  void emptyTextIsZeroAndAsciiUsesConservativeCeiling() {
    assertThat(estimator.estimateText("")).isZero();
    assertThat(estimator.estimateText("abcd")).isEqualTo(1);
    assertThat(estimator.estimateText("abcde")).isEqualTo(2);
  }

  @Test
  void nonAsciiCodePointsAreNeverCheaperThanAsciiCharacters() {
    assertThat(estimator.estimateText("中文")).isEqualTo(2);
    assertThat(estimator.estimateText("中文")).isGreaterThan(estimator.estimateText("aa"));
    assertThat(estimator.estimateText("a中")).isEqualTo(2);
    assertThat(estimator.estimateText("🙂")).isEqualTo(1);
  }

  @Test
  void estimateRequestIncludesEveryToolDefinition() {
    ToolDef tool =
        ToolDef.of(
            "large_tool",
            "description-that-costs-tokens",
            Map.of("type", "object", "description", "schema-description"));
    LlmRequest withoutTools = LlmRequest.ofMessages(List.of(LlmMessage.user("hello")));
    LlmRequest withTools = new LlmRequest(withoutTools.messages(), List.of(tool));

    assertThat(estimator.estimateRequest(withTools))
        .isGreaterThan(estimator.estimateRequest(withoutTools));
    assertThat(estimator.estimateRequest(new LlmRequest(List.of(), List.of()))).isZero();
  }

  @Test
  void messageEstimateIncludesRoleAndTextParts() {
    assertThat(estimator.estimateMessage(LlmMessage.user("abcd"))).isEqualTo(2);
    assertThat(estimator.estimateMessages(List.of(LlmMessage.user("abcd")))).isEqualTo(2);
  }
}
