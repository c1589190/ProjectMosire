package io.mosire.main.setup;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.llm.LlmException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LlmSetupWizard}（注入流，不碰真网——{@link LlmSetupWizard.Probe} 假探测）：
 * 菜单→预设回车采纳→密钥→探测→写盘的主链、探测失败重试链、取消链； 密钥值不出现在输出里（D3 密钥纪律的向导侧钉子）。
 */
class LlmSetupWizardTest {

  @TempDir Path tempDir;

  /** 逐行脚本输入；耗尽即抛（跑飞立刻暴露）。 */
  private static BufferedReader lines(String... values) {
    return new BufferedReader(new StringReader(String.join("\n", values) + "\n"));
  }

  private static LlmSetupWizard.LineReader reader(BufferedReader in) {
    return prompt -> {
      String line = in.readLine();
      if (line == null) {
        throw new IOException("输入脚本耗尽");
      }
      return line;
    };
  }

  private static LlmSetupWizard.SecretReader secret(String value) {
    return prompt -> value.toCharArray();
  }

  @Test
  void presetChannelWithDefaultsProbesWritesAndReturnsTrue() throws IOException {
    List<String> probed = new ArrayList<>();
    LlmSetupWizard wizard =
        new LlmSetupWizard(
            (baseUrl, model, apiKey) -> probed.add(baseUrl + "|" + model + "|" + apiKey));
    var out = new java.io.ByteArrayOutputStream();

    boolean configured =
        wizard.run(
            tempDir,
            reader(lines("1", "", "", "sk-test")),
            secret("sk-test"),
            new java.io.PrintStream(out, true, StandardCharsets.UTF_8));

    assertThat(configured).isTrue();
    assertThat(probed)
        .containsExactly("https://open.bigmodel.cn/api/coding/paas/v4|glm-5.3-flash|sk-test");
    // 读侧原路回读（判据同 LlmConfigWriterTest，这里只钉"向导确实写了"）
    var store = new io.mosire.agentlib.config.FileConfigStore(tempDir);
    assertThat(io.mosire.agentlib.llm.LlmRouteLoader.load(store).model())
        .isEqualTo("glm-5.3-flash");
    // 密钥纪律：向导输出不含密钥值
    assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("sk-test");
  }

  @Test
  void probeFailureLoopsBackAndSecondAttemptSucceeds() throws IOException {
    int[] attempts = {0};
    LlmSetupWizard wizard =
        new LlmSetupWizard(
            (baseUrl, model, apiKey) -> {
              if (attempts[0]++ == 0) {
                throw new LlmException("连接被拒");
              }
            });
    var out = new java.io.ByteArrayOutputStream();

    boolean configured =
        wizard.run(
            tempDir,
            reader(lines("1", "", "", "sk-1", "1", "", "", "sk-1")),
            secret("sk-1"),
            new java.io.PrintStream(out, true, StandardCharsets.UTF_8));

    assertThat(configured).isTrue();
    assertThat(attempts[0]).isEqualTo(2);
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("探测失败")
        .contains("连接被拒")
        .contains("配置未写入");
  }

  @Test
  void cancelWritesNothingAndReturnsFalse() throws IOException {
    LlmSetupWizard wizard = new LlmSetupWizard((b, m, k) -> {});
    var out = new java.io.ByteArrayOutputStream();

    boolean configured =
        wizard.run(
            tempDir,
            reader(lines("4")),
            secret(""),
            new java.io.PrintStream(out, true, StandardCharsets.UTF_8));

    assertThat(configured).isFalse();
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("取消");
    var store = new io.mosire.agentlib.config.FileConfigStore(tempDir);
    assertThat(store.get("llm", "routes")).isEmpty();
  }
}
