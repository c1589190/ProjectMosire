package io.mosire.agentlib.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LlmQuotaTest {

  @Test
  void accumulatesTokens() {
    LlmQuota quota = new LlmQuota(100);
    quota.record(10, 20);
    quota.record(5, 5);
    assertThat(quota.usedTokens()).isEqualTo(40);
  }

  @Test
  void throwsWhenExceededAndKeepsCounting() {
    LlmQuota quota = new LlmQuota(10);
    quota.record(6, 3);
    assertThatThrownBy(() -> quota.record(2, 1)).isInstanceOf(QuotaExceededException.class);
    assertThat(quota.usedTokens()).isEqualTo(12);
  }

  @Test
  void unknownTokensCountAsZero() {
    LlmQuota quota = new LlmQuota(10);
    quota.record(LlmResponse.UNKNOWN_TOKENS, LlmResponse.UNKNOWN_TOKENS);
    assertThat(quota.usedTokens()).isZero();
  }

  @Test
  void rejectsNonPositiveMax() {
    assertThatThrownBy(() -> new LlmQuota(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
