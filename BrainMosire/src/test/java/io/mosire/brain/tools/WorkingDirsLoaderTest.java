package io.mosire.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.config.ConfigException;
import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.permission.ResourceScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkingDirsLoader} 的离线闭环（S5-B）：{@code agents.workingDirs} → 主 Agent 的 {@code fs} 作用域。
 *
 * <p>判别性约定（与 {@code SubagentLimitsLoaderTest} / {@code CommandModeLoaderTest} 同取法）：
 *
 * <ul>
 *   <li><b>整项缺失/显式 null ⇒ 不限</b>（裁决 ①：主 Agent 缺省不限）；
 *   <li><b>"不限"与"哪里都不许"必须能被分开</b>：缺项 = 不限，{@code []} = 哪里都不许——两者取相反值；
 *   <li><b>值真的来自配置</b>（改配置 → 结果随之改变，抓"常量返回不限"的实现）；
 *   <li><b>存在但非法 ⇒ 响亮失败</b>且点名键与原值：本项是目录围栏的唯一来源，静默回退成"不限"会得到一个看起来围住了、 其实敞着的部署。
 * </ul>
 */
class WorkingDirsLoaderTest {

  @TempDir Path tempDir;

  @Test
  void missingKeyMeansUnrestricted() throws IOException {
    writeConfig("{}");
    assertThat(WorkingDirsLoader.load(store()).unrestricted()).isTrue();
    writeConfig("{\"agents\":{\"workingDirs\":null}}");
    assertThat(WorkingDirsLoader.load(store()).unrestricted()).isTrue();
  }

  @Test
  void emptyArrayMeansNothingIsAllowed() throws IOException {
    writeConfig("{\"agents\":{\"workingDirs\":[]}}");
    ResourceScope scope = WorkingDirsLoader.load(store());
    // 显式空集 ≠ 缺项：前者"哪里都不许"，后者"不限"（判反了就是一次静默放大）
    assertThat(scope.unrestricted()).isFalse();
    assertThat(scope.allows("/srv/work")).isFalse();
    assertThat(scope.allows("/")).isFalse();
  }

  @Test
  void valuesComeFromTheConfigAndAreNormalized() throws IOException {
    Path work = Files.createDirectories(tempDir.resolve("work"));
    Path data = Files.createDirectories(tempDir.resolve("data"));
    writeConfig(
        "{\"agents\":{\"workingDirs\":[\""
            + work
            + "\",\""
            + tempDir.resolve("data").resolve(".")
            + "\"]}}");
    ResourceScope scope = WorkingDirsLoader.load(store());
    assertThat(scope.unrestricted()).isFalse();
    assertThat(scope.dirList()).containsExactlyInAnyOrder(work.toRealPath(), data.toRealPath());
    assertThat(scope.allowsDir(work.resolve("a/b"))).isTrue();
    assertThat(scope.allowsDir(tempDir.resolve("elsewhere"))).isFalse();
    // 相对路径按进程 cwd 绝对化（这里用绝对路径的等价写法：只断言"没有相对形态的根"）
    assertThat(scope.dirList()).allMatch(Path::isAbsolute);
  }

  @Test
  void realPathOfExistingRootsIsResolved() throws IOException {
    Path real = Files.createDirectories(tempDir.resolve("real"));
    Path link = tempDir.resolve("link");
    Files.createSymbolicLink(link, real);
    writeConfig("{\"agents\":{\"workingDirs\":[\"" + link + "\"]}}");
    assertThat(WorkingDirsLoader.load(store()).dirList()).containsExactly(real.toRealPath());
  }

  @Test
  void relativePathsResolveAgainstTheProcessCwdAndDotSegmentsAreLexical() throws IOException {
    Path cwd = Path.of("").toAbsolutePath();
    // 相对路径 ⇒ 按本进程 cwd 绝对化；"." / ".." 段由 Path 的<b>词法</b>归一解析（不是拒绝）——
    // 配置是运维自己写的，"路径字面指向哪就是哪"是它该有的读法
    writeConfig("{\"agents\":{\"workingDirs\":[\"relative/work\",\"./here/../here2\"]}}");
    ResourceScope scope = WorkingDirsLoader.load(store());
    assertThat(scope.dirList())
        .containsExactlyInAnyOrder(
            cwd.resolve("relative/work").normalize(), cwd.resolve("here2").normalize());
    assertThat(scope.dirList()).allMatch(Path::isAbsolute);
  }

  @Test
  void unparsablePathFailsLoudly() throws IOException {
    // 含 NUL 的路径根本不是合法路径（Path.of 抛 InvalidPathException）——本类包一层点名键名
    writeConfig("{\"agents\":{\"workingDirs\":[\"bad\\u0000path\"]}}");
    ConfigException failure =
        catchThrowableOfType(ConfigException.class, () -> WorkingDirsLoader.load(store()));
    assertThat(failure).isNotNull();
    assertThat(failure.code()).isEqualTo(WorkingDirsLoader.E_WORKING_DIRS);
    assertThat(failure.getMessage()).contains(WorkingDirsLoader.FULL_KEY);
  }

  @Test
  void malformedValuesFailLoudly() throws IOException {
    record Case(String json, String expectedValueText) {}

    java.util.List<Case> cases =
        java.util.List.of(
            new Case("{\"agents\":{\"workingDirs\":\"/srv/work\"}}", "\"/srv/work\""),
            new Case("{\"agents\":{\"workingDirs\":[42]}}", "42"),
            new Case("{\"agents\":{\"workingDirs\":[\"  \"]}}", "\"  \""),
            new Case("{\"agents\":{\"workingDirs\":[true]}}", "true"),
            new Case("{\"agents\":{\"workingDirs\":{}}}", "{}"));

    for (Case testCase : cases) {
      writeConfig(testCase.json());
      ConfigException failure =
          catchThrowableOfType(ConfigException.class, () -> WorkingDirsLoader.load(store()));
      assertThat(failure).as("config=%s", testCase.json()).isNotNull();
      assertThat(failure.code())
          .as("config=%s 的消息=%s", testCase.json(), failure.getMessage())
          .isEqualTo(WorkingDirsLoader.E_WORKING_DIRS);
      assertThat(failure.getMessage())
          .as("config=%s", testCase.json())
          .contains(WorkingDirsLoader.FULL_KEY)
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
