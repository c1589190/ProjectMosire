package io.mosire.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** M0 冒烟测试：验证模块骨架、版本资源过滤与运行时版本读取。 */
class VersionTest {
  private static final String VERSION_RESOURCE = "/io/mosire/main/version.properties";

  @Test
  void artifactIdIsPresent() {
    assertThat(Version.ARTIFACT_ID).isEqualTo("main-mosire");
  }

  @Test
  void versionMatchesFilteredResource() throws IOException {
    Properties properties = new Properties();
    try (InputStream input = VersionTest.class.getResourceAsStream(VERSION_RESOURCE)) {
      assertThat(input).as("版本资源 %s 必须存在", VERSION_RESOURCE).isNotNull();
      properties.load(input);
    }

    assertThat(Version.VERSION)
        .isEqualTo(properties.getProperty("version"))
        .isNotBlank()
        .isNotEqualTo("0.1.0-SNAPSHOT")
        .doesNotContain("${");
  }
}
