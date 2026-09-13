package io.mosire.agentlib.permission;

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
 * {@link CommandModeLoader} 的离线闭环（S6）：{@code commands.mode} → 主 Agent 的初始档位。
 *
 * <p>判别性约定（与 {@code BashToolConfigLoaderTest} 同取法）：
 *
 * <ul>
 *   <li><b>整项缺失/显式 null ⇒ {@code FULL}</b>：没写过这一项的部署照常起来，行为等于本功能引入前；
 *   <li><b>值真的来自配置</b>（改配置 → 结果随之改变，抓"常量返回 FULL"的实现——那种实现让配置形同虚设， 且不会有任何用例变红）；
 *   <li><b>存在但非法 ⇒ 响亮失败</b>且点名键与坏值：这一项直接决定命令闸的宽严，静默读成某一档的两种后果 （无声放开 / 每条命令都送审）都不是"猜错的代价"该付的。
 * </ul>
 *
 * <p>全程离线：只用 {@code @TempDir} 上的 {@link FileConfigStore}，不起进程、不读真配置目录。
 */
class CommandModeLoaderTest {

  @TempDir Path tempDir;

  @Test
  void missingSectionFallsBackToFull() throws IOException {
    writeConfig("{}");
    assertThat(CommandModeLoader.load(store())).isEqualTo(CommandMode.FULL);
  }

  @Test
  void explicitNullIsTreatedAsMissing() throws IOException {
    writeConfig("{\"commands\":{\"mode\":null}}");
    assertThat(CommandModeLoader.load(store())).isEqualTo(CommandMode.FULL);
  }

  /** 判别性：档位真的来自配置（既有取值也真的有第二个档）。 */
  @Test
  void valuesComeFromTheConfigSection() throws IOException {
    writeConfig("{\"commands\":{\"mode\":\"limited\"}}");
    assertThat(CommandModeLoader.load(store())).isEqualTo(CommandMode.LIMITED);
    writeConfig("{\"commands\":{\"mode\":\"full\"}}");
    assertThat(CommandModeLoader.load(store())).isEqualTo(CommandMode.FULL);
  }

  @Test
  void unknownOrNonTextualValuesFailLoudly() throws IOException {
    record Case(String json, String expectedValueText) {}
    java.util.List<Case> cases =
        java.util.List.of(
            new Case("{\"commands\":{\"mode\":\"partial\"}}", "partial"),
            new Case("{\"commands\":{\"mode\":\"LIMITED-ish\"}}", "LIMITED-ish"),
            new Case("{\"commands\":{\"mode\":1}}", "1"),
            new Case("{\"commands\":{\"mode\":true}}", "true"));

    for (Case testCase : cases) {
      writeConfig(testCase.json());
      ConfigException failure =
          catchThrowableOfType(ConfigException.class, () -> CommandModeLoader.load(store()));
      assertThat(failure).as("config=%s", testCase.json()).isNotNull();
      assertThat(failure.code())
          .as("config=%s 的消息=%s", testCase.json(), failure.getMessage())
          .isEqualTo(CommandModeLoader.E_COMMAND_MODE);
      assertThat(failure.getMessage())
          .as("消息要点名出错的键与坏值（抓通用文案）config=%s", testCase.json())
          .contains("commands.mode")
          .contains(testCase.expectedValueText());
    }
  }

  private FileConfigStore store() {
    return new FileConfigStore(tempDir);
  }

  private void writeConfig(String json) throws IOException {
    Files.write(tempDir.resolve("config.json"), json.getBytes(StandardCharsets.UTF_8));
  }
}
