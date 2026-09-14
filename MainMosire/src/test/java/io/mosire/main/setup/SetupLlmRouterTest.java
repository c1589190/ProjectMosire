package io.mosire.main.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import org.junit.jupiter.api.Test;

/**
 * {@link SetupLlmRouter}：未配置态诚实（model=unconfigured、chat 响亮导航）、swap 热切换后逐字委托 （配置引导 D6；变异 = 删 swap ⇒
 * configured 恒 false，本类转红）。
 */
class SetupLlmRouterTest {

  @Test
  void unconfiguredStateIsHonest() {
    SetupLlmRouter router = SetupLlmRouter.unconfigured();

    assertThat(router.configured()).isFalse();
    assertThat(router.model()).isEqualTo("unconfigured");
    assertThatThrownBy(() -> router.chat(LlmRequest.ofMessages(java.util.List.of())))
        .isInstanceOf(LlmException.class)
        .hasMessageContaining("/api/setup/llm")
        .hasMessageContaining("--setup");
  }

  @Test
  void swapDelegatesChatAndModelWithoutReassembly() {
    SetupLlmRouter router = SetupLlmRouter.unconfigured();
    FakeLlmClient real = FakeLlmClient.with(LlmResponse.text("pong"));

    router.swap(real);

    assertThat(router.configured()).isTrue();
    assertThat(router.model()).isEqualTo("fake");
    assertThat(router.chat(LlmRequest.ofMessages(java.util.List.of())).textPart()).contains("pong");
  }

  @Test
  void swapRejectsNull() {
    SetupLlmRouter router = SetupLlmRouter.unconfigured();
    assertThatThrownBy(() -> router.swap(null)).isInstanceOf(NullPointerException.class);
  }
}
