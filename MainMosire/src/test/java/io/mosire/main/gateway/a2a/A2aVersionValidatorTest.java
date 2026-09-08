package io.mosire.main.gateway.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.VersionNotSupportedError;
import org.junit.jupiter.api.Test;

/** 版本协商：镜像 server-common A2AVersionValidator（缺省 0.3、主号兼容、任一解析失败即不兼容）。 */
class A2aVersionValidatorTest {

  private static final AgentCard CARD = A2aTestSupport.card("http://127.0.0.1:1234");

  @Test
  void matchesSameMajorVersion() {
    A2aVersionValidator.validate(CARD, "1.0");
    // minor 任意兼容
    A2aVersionValidator.validate(CARD, "1.7");
  }

  @Test
  void missingHeaderDefaultsTo03AndFailsAgainstV1() {
    assertThatThrownBy(() -> A2aVersionValidator.validate(CARD, null))
        .isInstanceOf(VersionNotSupportedError.class)
        .hasMessageContaining("Protocol version '0.3' is not supported. Supported versions: [1.0]");
    assertThatThrownBy(() -> A2aVersionValidator.validate(CARD, "  "))
        .isInstanceOf(VersionNotSupportedError.class);
  }

  @Test
  void majorMismatchRejected() {
    assertThatThrownBy(() -> A2aVersionValidator.validate(CARD, "2.0"))
        .isInstanceOf(VersionNotSupportedError.class)
        .hasMessageContaining("Supported versions: [1.0]");
  }

  @Test
  void malformedRequestedVersionIsIncompatible() {
    assertThat(A2aVersionValidator.isCompatible(List.of("1.0"), "x.y")).isFalse();
    assertThat(A2aVersionValidator.isCompatible(List.of("1.0"), "1")).isFalse();
    // 镜像解析只要求 ≥2 段："1.0.0"（major.minor.patch）照常解析、首段相同 → 兼容（多余段忽略）
    assertThat(A2aVersionValidator.isCompatible(List.of("1.0"), "1.0.0")).isTrue();
    assertThat(A2aVersionValidator.isCompatible(List.of("1.0"), "2.1")).isFalse();
  }

  @Test
  void malformedSupportedVersionShortCircuitsToIncompatible() {
    // 镜像短路：首个解析失败即整体不兼容（即使后续版本本可匹配）
    assertThat(A2aVersionValidator.isCompatible(List.of("bad", "1.0"), "1.0")).isFalse();
  }
}
