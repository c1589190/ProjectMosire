package io.mosire.bash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.FileConfigStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BashToolConfigLoader} 的闭环测试（S4-C §一.4）：{@code tools.bash.*} → {@link BashToolConfig}。
 *
 * <p>判别性约定（照 {@code ApprovalConfigLoaderTest} 的取法）：
 *
 * <ul>
 *   <li><b>整项缺失 ⇒ 缺省</b>（没写过 {@code tools.bash.*} 的部署照常起来：无追加条目 + {@code digest}）；
 *   <li><b>值真的来自配置</b>（改写配置 → 结果随之改变，抓硬编码缺省的实现）；
 *   <li><b>存在但非法 ⇒ 响亮</b>且点名各自的键（这两个键都是安全相关的：一个决定哪些命令被硬拒、一个决定命令是否落明文）；
 *   <li><b>只有"追加"没有"删除"</b>：配置能加严、不能减严（抓"加个键就能把内置清单清空"的实现）。
 * </ul>
 *
 * <p>全程离线：只用 {@code @TempDir} 上的 {@link FileConfigStore}，不起进程、不碰真文件系统。
 */
class BashToolConfigLoaderTest {

  @TempDir Path tempDir;

  @Test
  void missingSectionFallsBackToDefaults() throws IOException {
    writeConfig("{}");
    assertThat(BashToolConfigLoader.load(store())).isEqualTo(BashToolConfig.defaults());
  }

  @Test
  void explicitNullsAreTreatedAsMissing() throws IOException {
    writeConfig(
        "{\"tools\":{\"bash\":{\"blockedExtra\":null,\"askExtra\":null,\"commandLog\":null}}}");
    assertThat(BashToolConfigLoader.load(store())).isEqualTo(BashToolConfig.defaults());
  }

  /** 判别性：三项都真的来自配置（抓硬编码缺省的实现）。 */
  @Test
  void valuesComeFromTheConfigSection() throws IOException {
    writeConfig(
        "{\"tools\":{\"bash\":{\"blockedExtra\":[\"nc\"],\"askExtra\":[\"telnet\"],\"commandLog\":\"full\"}}}");
    BashToolConfig config = BashToolConfigLoader.load(store());
    assertThat(config.blockedExtra()).containsExactly("nc");
    assertThat(config.askExtra()).containsExactly("telnet");
    assertThat(config.commandLog()).isEqualTo(BashToolConfig.CommandLog.FULL);
    assertThat(config.toToolConfig())
        .containsEntry(BashToolConfig.KEY_COMMAND_LOG, "full")
        .containsEntry(BashToolConfig.KEY_BLOCKED_EXTRA, List.of("nc"));
  }

  @Test
  void invalidValuesAreLoudAndNameTheirKey() throws IOException {
    record Case(String json, String expectedKey) {}
    List<Case> cases =
        List.of(
            new Case("{\"tools\":{\"bash\":{\"commandLog\":\"false\"}}}", "tools.bash.commandLog"),
            new Case("{\"tools\":{\"bash\":{\"commandLog\":\"FULL\"}}}", "tools.bash.commandLog"),
            new Case("{\"tools\":{\"bash\":{\"commandLog\":1}}}", "tools.bash.commandLog"),
            new Case("{\"tools\":{\"bash\":{\"blockedExtra\":\"nc\"}}}", "tools.bash.blockedExtra"),
            new Case("{\"tools\":{\"bash\":{\"blockedExtra\":[1]}}}", "tools.bash.blockedExtra"),
            new Case(
                "{\"tools\":{\"bash\":{\"blockedExtra\":[\"  \"]}}}", "tools.bash.blockedExtra"),
            new Case("{\"tools\":{\"bash\":{\"askExtra\":\"telnet\"}}}", "tools.bash.askExtra"));

    for (Case testCase : cases) {
      writeConfig(testCase.json());
      ConfigException failure =
          catchThrowableOfType(ConfigException.class, () -> BashToolConfigLoader.load(store()));
      assertThat(failure).as("config=%s", testCase.json()).isNotNull();
      assertThat(failure.code())
          .as("config=%s 的消息=%s", testCase.json(), failure.getMessage())
          .isEqualTo(BashToolConfigLoader.E_BASH_CONFIG);
      assertThat(failure.getMessage())
          .as("消息必须点名出错的键（抓通用文案）config=%s", testCase.json())
          .contains(testCase.expectedKey());
    }
  }

  /**
   * 红线 1 的<b>结构性</b>证据：配置只有"追加"入口 ⇒ 内置档位一件都降不下来。
   *
   * <p>反过来说：把追加表配成任何值（含空表），{@code apt-get install} 仍是 Ask、{@code rm -rf /} 仍是 Block——
   * "关掉安全阀"不是一行配置能做到的事，要删条目必须改分类器源码（代码评审的事，不是配置的事）。
   */
  @Test
  void extraEntriesCanOnlyAddNeverRemove() throws IOException {
    writeConfig(
        "{\"tools\":{\"bash\":{\"blockedExtra\":[],\"askExtra\":[],\"commandLog\":\"digest\"}}}");
    BashToolConfig config = BashToolConfigLoader.load(store());
    assertThat(
            BashCommandClassifier.classify(
                "apt-get install x", config.blockedExtra(), config.askExtra()))
        .isInstanceOf(ToolGate.Ask.class);
    assertThat(BashCommandClassifier.classify("rm -rf /", config.blockedExtra(), config.askExtra()))
        .isInstanceOf(ToolGate.Block.class);
    // 没有"删除内置条目"的键：写一个自造键进去，读出来的仍是缺省（既不生效也不静默改档）
    writeConfig("{\"tools\":{\"bash\":{\"builtinBlocked\":[]}}}");
    assertThat(BashToolConfigLoader.load(store())).isEqualTo(BashToolConfig.defaults());
  }

  private FileConfigStore store() {
    return new FileConfigStore(tempDir);
  }

  private void writeConfig(String json) throws IOException {
    Files.write(tempDir.resolve("config.json"), json.getBytes(StandardCharsets.UTF_8));
  }
}
