package io.mosire.agentlib.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.FileConfigStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ApprovalConfigLoader} 的闭环测试（三期 S4-B2 §一.3）：{@code approval.*} → {@link ApprovalConfig}。
 *
 * <p>判别性约定（照 {@code LlmRouteLoaderTest} 的取法）：
 *
 * <ul>
 *   <li><b>整项缺失 ⇒ 默认值</b>（不是错误，抓"把可选当必填"的实现）；JSON {@code null} 与缺失同口径；
 *   <li><b>值真的来自配置</b>（改写配置 → 结果随之改变，抓硬编码 300/0 的实现）；
 *   <li><b>存在但非法 ⇒ 响亮</b>，且消息点名各自的键（抓"同一条通用文案"的实现）；
 *   <li>{@code timeoutSeconds = 0} 合法（= 立刻拒，抓"把 0 当非法"的实现）。
 * </ul>
 *
 * <p>全程离线：只用 {@code @TempDir} 上的 {@link FileConfigStore}。
 */
class ApprovalConfigLoaderTest {

  @TempDir Path tempDir;

  /** 缺省口径：整项缺失 = 默认开 + 300 s + 端口 0（用户裁决，见 {@link ApprovalConfig}）。 */
  @Test
  void missingSectionFallsBackToDefaults() throws IOException {
    writeConfig("{}");
    ApprovalConfig config = ApprovalConfigLoader.load(store());
    assertThat(config).isEqualTo(ApprovalConfig.defaults());
    assertThat(config.http()).isTrue();
    assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(300));
    assertThat(config.httpPort()).isZero();
  }

  /** JSON {@code null} 与缺失同口径（先例：{@code LlmRouteLoader} 的 {@code credentialsRef}），同样走默认值。 */
  @Test
  void explicitNullsAreTreatedAsMissing() throws IOException {
    writeConfig("{\"approval\":{\"http\":null,\"timeoutSeconds\":null,\"httpPort\":null}}");
    assertThat(ApprovalConfigLoader.load(store())).isEqualTo(ApprovalConfig.defaults());
  }

  /** 判别性：三项都真的来自配置（抓硬编码默认值的实现）。 */
  @Test
  void valuesComeFromTheConfigSection() throws IOException {
    writeConfig("{\"approval\":{\"http\":false,\"timeoutSeconds\":45,\"httpPort\":18080}}");
    ApprovalConfig config = ApprovalConfigLoader.load(store());
    assertThat(config.http()).isFalse();
    assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(45));
    assertThat(config.httpPort()).isEqualTo(18080);
  }

  /** {@code timeoutSeconds = 0} 合法：零预算 ⇒ 编排器立刻拒（<b>不是</b>"关掉审批"，更不是放行）。 */
  @Test
  void zeroTimeoutIsLegalAndMeansZeroBudget() throws IOException {
    writeConfig("{\"approval\":{\"timeoutSeconds\":0}}");
    assertThat(ApprovalConfigLoader.load(store()).timeout()).isEqualTo(Duration.ZERO);
  }

  /** 存在但非法 ⇒ 响亮失败；消息点名各自的键（可分辨），且共用同一错误码。 */
  @Test
  void invalidValuesAreLoudAndNameTheirKey() throws IOException {
    record Case(String json, String expectedKey) {}
    List<Case> cases =
        List.of(
            new Case("{\"approval\":{\"http\":\"yes\"}}", "approval.http"),
            new Case("{\"approval\":{\"http\":1}}", "approval.http"),
            new Case("{\"approval\":{\"timeoutSeconds\":-1}}", "approval.timeoutSeconds"),
            new Case("{\"approval\":{\"timeoutSeconds\":\"300\"}}", "approval.timeoutSeconds"),
            new Case("{\"approval\":{\"timeoutSeconds\":1.5}}", "approval.timeoutSeconds"),
            new Case("{\"approval\":{\"timeoutSeconds\":true}}", "approval.timeoutSeconds"),
            new Case("{\"approval\":{\"httpPort\":\"8080\"}}", "approval.httpPort"),
            new Case("{\"approval\":{\"httpPort\":-1}}", "approval.httpPort"),
            new Case("{\"approval\":{\"httpPort\":70000}}", "approval.httpPort"));

    for (Case testCase : cases) {
      writeConfig(testCase.json());
      ConfigException failure =
          catchThrowableOfType(ConfigException.class, () -> ApprovalConfigLoader.load(store()));
      assertThat(failure).as("config=%s", testCase.json()).isNotNull();
      assertThat(failure.code())
          .as("config=%s", testCase.json())
          .isEqualTo(ApprovalConfigLoader.E_APPROVAL_CONFIG);
      assertThat(failure.getMessage())
          .as("消息必须点名出错的键（抓通用文案）config=%s", testCase.json())
          .contains(testCase.expectedKey());
    }
  }

  /** 一处配错时其余项<b>不得</b>被当成默认值悄悄生效：整个 load 抛错，调用方拿不到半成品配置。 */
  @Test
  void oneBadKeyFailsTheWholeLoad() throws IOException {
    writeConfig("{\"approval\":{\"http\":false,\"timeoutSeconds\":-5,\"httpPort\":1234}}");
    assertThat(
            catchThrowableOfType(ConfigException.class, () -> ApprovalConfigLoader.load(store())))
        .isNotNull();
  }

  private FileConfigStore store() {
    return new FileConfigStore(tempDir);
  }

  private void writeConfig(String json) throws IOException {
    Files.write(tempDir.resolve("config.json"), json.getBytes(StandardCharsets.UTF_8));
  }
}
