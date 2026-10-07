package io.mosire.brain.skills;

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
 * {@link SkillSettings} 的离线闭环（{@code skills.*} → 装配参数），口径照 {@code WorkingDirsLoaderTest} / {@code
 * SubagentLimitsLoaderTest}：
 *
 * <ul>
 *   <li>整段缺失 ⇒ 缺省根 + 缺省预算 + 不预载（没配技能的部署照常起来）；
 *   <li>值真的来自配置（改配置 ⇒ 结果随之改变——抓“常量返回缺省”的实现）；
 *   <li>存在但非法 ⇒ 响亮失败并点名键与坏值（静默回落缺省会得到“看起来配了、其实按别的办”的部署）。
 * </ul>
 */
class SkillSettingsTest {

  @TempDir Path tempDir;

  @Test
  void missingSectionUsesDefaultRootBudgetAndNoPreload() throws IOException {
    writeConfig("{}");
    Path defaultRoot = tempDir.resolve("default-skills");
    SkillSettings settings = SkillSettings.load(store(), defaultRoot);
    assertThat(settings.root()).isEqualTo(defaultRoot.toAbsolutePath().normalize());
    assertThat(settings.listingBudget()).isEqualTo(SkillCatalog.DEFAULT_LISTING_BUDGET);
    assertThat(settings.preloaded()).isEmpty();
  }

  @Test
  void valuesComeFromTheConfigSection() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("my-skills"));
    writeConfig(
        "{\"skills\":{\"dir\":\""
            + root.toString().replace("\\", "\\\\")
            + "\",\"listingBudget\":0,\"preload\":[\"alpha\",\"beta\"]}}");
    SkillSettings settings = SkillSettings.load(store(), tempDir.resolve("ignored"));
    assertThat(settings.root()).isEqualTo(root.toRealPath());
    assertThat(settings.listingBudget()).as("0 是合法取值（不披露目录档）").isZero();
    assertThat(settings.preloaded()).containsExactlyInAnyOrder("alpha", "beta");
  }

  @Test
  void nullsAreTreatedAsMissing() throws IOException {
    writeConfig("{\"skills\":{\"dir\":null,\"listingBudget\":null,\"preload\":null}}");
    SkillSettings settings = SkillSettings.load(store(), tempDir.resolve("default-skills"));
    assertThat(settings.root())
        .isEqualTo(tempDir.resolve("default-skills").toAbsolutePath().normalize());
    assertThat(settings.preloaded()).isEmpty();
  }

  @Test
  void invalidValuesFailLoudlyWithKeyAndRawValue() throws IOException {
    writeConfig("{\"skills\":{\"dir\":42}}");
    ConfigException badDir =
        catchThrowableOfType(() -> SkillSettings.load(store(), tempDir), ConfigException.class);
    assertThat(badDir.code()).isEqualTo(SkillSettings.E_SKILLS);
    assertThat(badDir.getMessage()).contains("skills.*").contains("42");

    writeConfig("{\"skills\":{\"listingBudget\":-1}}");
    assertThat(
            catchThrowableOfType(() -> SkillSettings.load(store(), tempDir), ConfigException.class))
        .isNotNull();

    writeConfig("{\"skills\":{\"listingBudget\":\"big\"}}");
    assertThat(
            catchThrowableOfType(() -> SkillSettings.load(store(), tempDir), ConfigException.class))
        .isNotNull();

    writeConfig("{\"skills\":{\"preload\":[\"alpha\",7]}}");
    ConfigException badPreload =
        catchThrowableOfType(() -> SkillSettings.load(store(), tempDir), ConfigException.class);
    assertThat(badPreload.getMessage()).contains("skills.*");
  }

  // ---------- 夹具 ----------

  private FileConfigStore store() {
    return new FileConfigStore(tempDir);
  }

  private void writeConfig(String json) throws IOException {
    Files.writeString(tempDir.resolve("config.json"), json, StandardCharsets.UTF_8);
  }
}
