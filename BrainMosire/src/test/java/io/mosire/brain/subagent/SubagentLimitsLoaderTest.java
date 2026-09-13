package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.FileConfigStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SubagentLimitsLoader} 的离线闭环（S5-A）：{@code subagents.*} → 一份 {@link SubagentLimits}。
 *
 * <p>判别性约定（与 {@code CommandModeLoaderTest} 同取法）：
 *
 * <ul>
 *   <li><b>整项缺失/显式 null ⇒ 缺省</b>：没写过这一段的部署照常起来；
 *   <li><b>值真的来自配置</b>（改配置 → 结果随之改变，抓"常量返回 defaults"的实现——那种实现让配置形同虚设， 且不会有任何用例变红）；
 *   <li><b>存在但非法 ⇒ 响亮失败</b>且点名键与坏值：三个键决定"能派多少"，静默回退缺省会得到一个"看起来配了、其实没生效"的部署。
 * </ul>
 *
 * <p>全程离线：只用 {@code @TempDir} 上的 {@link FileConfigStore}，不起进程、不读真配置目录。
 */
class SubagentLimitsLoaderTest {

  @TempDir Path tempDir;

  @Test
  void missingSectionFallsBackToDefaults() throws IOException {
    writeConfig("{}");
    assertThat(SubagentLimitsLoader.load(store())).isEqualTo(SubagentLimits.defaults());
  }

  @Test
  void explicitNullsAreTreatedAsMissing() throws IOException {
    writeConfig(
        "{\"subagents\":{\"maxDepth\":null,\"maxChildrenPerInstance\":null,\"maxInstances\":null}}");
    assertThat(SubagentLimitsLoader.load(store())).isEqualTo(SubagentLimits.defaults());
  }

  /** 判别性：三个数都真的来自配置（否则"配置项存在"这件事在实现里根本没接线）。 */
  @Test
  void valuesComeFromTheConfigSection() throws IOException {
    writeConfig(
        "{\"subagents\":{\"maxDepth\":5,\"maxChildrenPerInstance\":2,\"maxInstances\":20}}");
    SubagentLimits limits = SubagentLimitsLoader.load(store());
    assertThat(limits.maxDepth()).isEqualTo(5);
    assertThat(limits.maxChildrenPerInstance()).isEqualTo(2);
    assertThat(limits.maxInstances()).isEqualTo(20);
    // 逐项独立：maxDepth 是总层数 ⇒ 子深度上限跟着它走（口径见 SubagentLimits 类注释）
    assertThat(limits.maxChildDepth()).isEqualTo(4);
    // 部分给：只写一个键时其余取缺省（不是"有一个键就整段覆盖"）
    writeConfig("{\"subagents\":{\"maxInstances\":20}}");
    SubagentLimits partial = SubagentLimitsLoader.load(store());
    assertThat(partial.maxDepth()).isEqualTo(SubagentLimits.defaults().maxDepth());
    assertThat(partial.maxInstances()).isEqualTo(20);
  }

  @Test
  void nonIntegerOrNonPositiveValuesFailLoudly() throws IOException {
    record Case(String json, String key, String expectedValueText) {}

    java.util.List<Case> cases =
        java.util.List.of(
            new Case("{\"subagents\":{\"maxDepth\":\"3\"}}", "subagents.maxDepth", "\"3\""),
            new Case("{\"subagents\":{\"maxDepth\":0}}", "subagents.maxDepth", "0"),
            new Case("{\"subagents\":{\"maxDepth\":-1}}", "subagents.maxDepth", "-1"),
            new Case("{\"subagents\":{\"maxDepth\":1.5}}", "subagents.maxDepth", "1.5"),
            new Case("{\"subagents\":{\"maxDepth\":true}}", "subagents.maxDepth", "true"),
            new Case(
                "{\"subagents\":{\"maxChildrenPerInstance\":0}}",
                "subagents.maxChildrenPerInstance",
                "0"),
            new Case("{\"subagents\":{\"maxInstances\":0}}", "subagents.maxInstances", "0"));

    for (Case testCase : cases) {
      writeConfig(testCase.json());
      ConfigException failure =
          catchThrowableOfType(ConfigException.class, () -> SubagentLimitsLoader.load(store()));
      assertThat(failure).as("config=%s", testCase.json()).isNotNull();
      assertThat(failure.code())
          .as("config=%s 的消息=%s", testCase.json(), failure.getMessage())
          .isEqualTo(SubagentLimitsLoader.E_SUBAGENT_LIMITS);
      // 点名"哪个键 + 原值"：三个键三套后果，通用文案指不出该改哪一行
      assertThat(failure.getMessage())
          .as("config=%s", testCase.json())
          .contains(testCase.key())
          .contains(testCase.expectedValueText());
    }
  }

  /**
   * 越界值同样响亮（{@code SubagentLimits} 的构造期守卫是取值范围唯一真值，本类只负责补上"哪个键"）。
   *
   * <p>{@code maxDepth = 1} 是<b>合法</b>的（= 只有主 Agent 那一层 ⇒ 子深度上限 0 ⇒ 谁都派不出来）：收严方向用不着报错， 配置里写 1
   * 就是"关掉派生面"的正经表达。
   */
  @Test
  void depthOfOneIsLegalAndMeansNoSpawning() throws IOException {
    writeConfig("{\"subagents\":{\"maxDepth\":1}}");
    SubagentLimits limits = SubagentLimitsLoader.load(store());
    assertThat(limits.maxDepth()).isEqualTo(1);
    assertThat(limits.maxChildDepth()).isZero();
  }

  private FileConfigStore store() {
    return new FileConfigStore(tempDir);
  }

  private void writeConfig(String json) throws IOException {
    Files.write(tempDir.resolve("config.json"), json.getBytes(StandardCharsets.UTF_8));
  }
}
