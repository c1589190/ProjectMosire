package io.mosire.brain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AgentConfigTest {

  @Test
  void defaultsMatchHardCaps() {
    AgentConfig config = AgentConfig.builder("main").build();
    assertThat(config.id()).isEqualTo("main");
    assertThat(config.maxTurns()).isEqualTo(AgentConfig.DEFAULT_MAX_TURNS);
    assertThat(config.maxToolCallsPerTurn()).isEqualTo(AgentConfig.DEFAULT_MAX_TOOL_CALLS_PER_TURN);
    assertThat(config.timeBudget()).isEqualTo(AgentConfig.DEFAULT_TIME_BUDGET);
    assertThat(config.quotaMaxTokens()).isEqualTo(AgentConfig.NO_QUOTA);
    assertThat(config.allowedTools()).containsExactly("*");
  }

  @Test
  void rejectsInvalidLimits() {
    assertThatThrownBy(() -> AgentConfig.builder("main").maxTurns(0).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AgentConfig.builder("main").maxToolCallsPerTurn(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AgentConfig.builder("main").quotaMaxTokens(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void customValuesPersist() {
    AgentConfig config = AgentConfig.builder("sub").maxTurns(3).description("测试子 Agent").build();
    assertThat(config.maxTurns()).isEqualTo(3);
    assertThat(config.description()).isEqualTo("测试子 Agent");
  }
}
