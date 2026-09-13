package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.brain.runtime.AgentConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S5-E 实测缺陷的判据：<b>注入路径一律绝对化</b>（{@link AgentCommand} 构造期，基准 = 父进程 CWD）。
 *
 * <p>缺陷形态（2026-09-13 真进程实测）：父 Agent 配了 {@code agents.workingDirs} ⇒ 子体的出生目录 = 它的 {@code fs}
 * 可达面首根，<b>不是父的 CWD</b>（{@code SubProcessExecutor.startDirectory}，S5-D 的 L2）；装配层注入的
 * <b>相对</b>模板目录于是在子体那侧解析成另一个位置（{@code <沙箱根>/.work/…}，不存在）⇒ 子体启动即退，父侧只有一个不带原因的 {@code
 * finished}。默认模板目录就是相对的 {@code configs/agents}，所以这不是边角：<b>任何配了工作目录围栏的部署，所有 spawn 都会静默死</b>。
 *
 * <p>判别性：把构造期的 {@code absoluteOrNull} 拿掉（或改成只对存在的路径绝对化），下面每个用例都会红——断言的是"解析结果与父进程同源"， 不是"字符串等于某个常量"。
 */
class AgentCommandInjectedPathTest {

  /** 父侧的相对注入形态（实测里就是 {@code .work/usage-test/s5-templates} 这一族）。 */
  private static final String REL_TEMPLATES = ".work/usage-test/s5-templates";

  private static final String REL_CHILD_DATA = ".work/usage-test/s5-data";
  private static final String REL_CONFIG = ".work/mosire/config";

  /** ①：三个注入路径在<b>记录字段</b>上就已绝对化（argv 只是下游）。 */
  @Test
  void relativeInjectedPathsBecomeAbsoluteInTheRecordItself() {
    AgentCommand command = relativeCommand();

    assertThat(command.templatesDir()).isEqualTo(absolute(REL_TEMPLATES));
    assertThat(command.childDataDir()).isEqualTo(absolute(REL_CHILD_DATA));
    assertThat(command.configDir()).isEqualTo(absolute(REL_CONFIG));
  }

  /**
   * ②：argv 里出现的是<b>绝对路径</b>——子体拿到的参数面与父同源。
   *
   * <p>这一条是缺陷的直接复现面：预修形态下 argv 里是 {@code .work/usage-test/s5-templates}，子体在沙箱出生目录里解析成 {@code
   * <沙箱根>/.work/…}。
   */
  @Test
  void argvCarriesTheAbsoluteFormNotTheRawRelativeOne() {
    List<String> argv = relativeCommand().argv(instance("r-1", "reader", "目标是"));

    assertThat(argv)
        .contains("--templates-dir", absolute(REL_TEMPLATES))
        .contains("--config-dir", absolute(REL_CONFIG));
    assertThat(argv).as("原样相对路径就是缺陷形态").doesNotContain(REL_TEMPLATES, REL_CONFIG);
    // --data-dir 是根目录与实例 id 的拼接：绝对根 + id 仍是绝对
    assertThat(argv).contains("--data-dir", Path.of(absolute(REL_CHILD_DATA), "r-1").toString());
    assertThat(argv)
        .filteredOn(argument -> argument.startsWith(".work/"))
        .as("argv 里不许再有任何相对注入项")
        .isEmpty();
  }

  /** ③：绝对化 = {@code toAbsolutePath().normalize()}——{@code ..}/{@code .} 在<b>父侧</b>就被消掉。 */
  @Test
  void normalizationHappensOnTheParentSide() {
    AgentCommand command =
        new AgentCommand(
            "/usr/bin/java",
            List.of(),
            List.of("-jar", "/opt/mosire/mosire.jar", "agent"),
            "--id",
            "./a/../" + REL_TEMPLATES,
            null,
            false,
            null);

    assertThat(command.templatesDir())
        .isEqualTo(absolute(REL_TEMPLATES))
        .doesNotContain("./", "/../");
  }

  /** ④：null 仍是 null（"没注入"与"注入了个空"是两件事——既有构造器一字不变）。 */
  @Test
  void nullInjectionStaysNull() {
    AgentCommand command = AgentCommand.javaJar("/usr/bin/java", "/opt/mosire/mosire.jar");

    assertThat(command.templatesDir()).isNull();
    assertThat(command.childDataDir()).isNull();
    assertThat(command.configDir()).isNull();
    assertThat(command.argv(instance("r-1", "reader", "目标是")))
        .doesNotContain("--templates-dir", "--data-dir", "--config-dir");
  }

  /**
   * ⑤：绝对化<b>不探测存在性</b>（Rul C：路径由装配层给定，Brain 不做文件系统假设）。
   *
   * <p>本用例的路径保证不存在：实现里若"顺手"加了 {@code isDirectory()} 判断再决定绝对化/丢弃，这里会红。存在性判断属启动前 诊断，不属参数面构建。
   */
  @Test
  void absoluteizationDoesNotProbeExistence() {
    String missing = ".work/no-such-dir-" + Long.toHexString(System.nanoTime());
    AgentCommand command =
        new AgentCommand(
            "/usr/bin/java", List.of(), List.of("agent"), "--id", missing, missing, false, missing);

    assertThat(command.templatesDir()).isEqualTo(absolute(missing));
    assertThat(command.childDataDir()).isEqualTo(absolute(missing));
    assertThat(command.configDir()).isEqualTo(absolute(missing));
  }

  // ---------- 工具 ----------

  private static AgentCommand relativeCommand() {
    return new AgentCommand(
        "/usr/bin/java",
        List.of("-Xmx128m"),
        List.of("-jar", "mosire.jar", "agent"),
        "--id",
        REL_TEMPLATES,
        REL_CHILD_DATA,
        true,
        REL_CONFIG);
  }

  /** 期望值 = 与实现同源的解析规则（父进程 CWD 基准 + normalize）。 */
  private static String absolute(String path) {
    return Path.of(path).toAbsolutePath().normalize().toString();
  }

  private static SubagentInstance instance(String id, String templateId, String goal) {
    return new SubagentInstance(
        id,
        templateId,
        goal,
        AgentConfig.builder(id).build(),
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("echo").build(),
        1,
        SubagentStatus.RUNNING);
  }
}
