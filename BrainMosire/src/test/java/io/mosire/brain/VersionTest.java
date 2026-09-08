package io.mosire.brain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** M0 冒烟测试：验证模块骨架可编译、可测试、可打包。 */
class VersionTest {

  @Test
  void artifactIdIsPresent() {
    assertThat(Version.ARTIFACT_ID).isEqualTo("brain-mosire");
  }

  @Test
  void versionIsPresent() {
    assertThat(Version.VERSION).isNotBlank();
  }
}
