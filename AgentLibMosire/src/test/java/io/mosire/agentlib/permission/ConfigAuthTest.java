package io.mosire.agentlib.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link ConfigAuth} 的写授权判定（deny-by-default）：本类钉住 <b>llm.* 分支</b>（配置引导扩容，2026-09-14）—— 仅 SYSTEM
 * 可写、键段校验、其余前缀仍拒。keys./runtime./agents.* 的既有行为抽一条作回归对照。
 */
class ConfigAuthTest {

  @Test
  void llmKeysWritableBySystemOnly() {
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, null, "llm.routes")).isEmpty();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, null, "llm.routes.default.baseUrl"))
        .isEmpty();
    // 变异钉住：把 llm.* 从 SYSTEM 分支挪进 deny-by-default / 放宽到 DEFAULT ⇒ 本断言转红
    assertThat(
            ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "main", "llm.routes.default.baseUrl"))
        .asString()
        .contains("llm.* 仅 SYSTEM 可写");
    assertThat(ConfigAuth.writeDenialReason(AccessToken.GUEST, null, "llm.route"))
        .asString()
        .contains("llm.* 仅 SYSTEM 可写");
  }

  @Test
  void llmKeysSegmentValidationStillApplies() {
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, null, "llm.routes..bad"))
        .asString()
        .contains("配置键段不合法");
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, null, "llm."))
        .asString()
        .contains("配置键段不合法");
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, null, ""))
        .asString()
        .contains("配置键为空");
  }

  @Test
  void otherPrefixesStayDenied() {
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, null, "approval.timeoutSeconds"))
        .asString()
        .contains("键前缀不在可写范围");
  }

  @Test
  void keysFamilyStillSystemOnly() {
    assertThat(ConfigAuth.writeDenialReason(AccessToken.SYSTEM, null, "keys.glm")).isEmpty();
    assertThat(ConfigAuth.writeDenialReason(AccessToken.DEFAULT, "glm", "keys.glm"))
        .asString()
        .contains("keys.* 仅 SYSTEM 可写");
  }

  @Test
  void nullTokenIsDenied() {
    Optional<String> reason = ConfigAuth.writeDenialReason(null, null, "llm.routes");
    assertThat(reason).asString().contains("无身份令牌（权限集为空）");
  }
}
