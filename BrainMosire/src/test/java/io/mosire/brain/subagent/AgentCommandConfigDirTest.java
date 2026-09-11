package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.config.FileConfigStore;
import io.mosire.agentlib.llm.ConfigApiKeySource;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.brain.runtime.AgentConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 三期 S1-A2 的参数面判据：子进程命令里的 {@code --config-dir <父的配置根>}。
 *
 * <p>判别三件事（专抓三类错法）：<b>传不传</b>（带非 null configDir 必须出现该参数、既有构造器必须不出现——抓"忘传"与"无条件传"）、 <b>放哪</b>（位置钉在
 * {@code --data-dir} 与 {@code --parent-link} 之间）、<b>传什么</b>（只传路径；密钥值绝不进参数面，D23）。
 */
class AgentCommandConfigDirTest {

  /** 哨兵密钥值：只活在 {@code @TempDir} 的假配置与本文件的断言里（真密钥从不进测试）。 */
  private static final String SENTINEL_KEY = "sk-sentinel-not-a-real-key";

  @TempDir Path tempDir;

  /**
   * ①：带 configDir ⇒ argv 含 {@code --config-dir <路径>}，且位置在 {@code --data-dir} 之后、{@code
   * --parent-link} 之前。
   */
  @Test
  void configDirFlagSitsBetweenDataDirAndParentLink() {
    assertThat(command("/opt/mosire/config").argv(instance("r-1", "reader", "目标是")))
        .containsExactly(
            "/usr/bin/java",
            "-Xmx128m",
            "-jar",
            "/opt/mosire/mosire.jar",
            "agent",
            "--id",
            "r-1",
            "--template",
            "reader",
            "--goal",
            "目标是",
            "--templates-dir",
            "/opt/mosire/templates",
            "--data-dir",
            "/opt/mosire/data/subagents/r-1",
            "--config-dir",
            "/opt/mosire/config",
            "--parent-link");
  }

  /**
   * ①（反面）：configDir == null ⇒ argv 里根本不出现该参数——既有五个构造器（3 参兼容、javaJar 两个、javaClasspath 两个） 与 7
   * 参位置构造一律传 null（R-A2-5），既有 AgentCommand 用例零影响。
   */
  @Test
  void existingConstructorsNeverEmitTheFlag() {
    List<AgentCommand> commands =
        List.of(
            new AgentCommand("java", List.of("-jar", "mosire.jar", "agent"), "--id"),
            AgentCommand.javaJar("java", "mosire.jar"),
            AgentCommand.javaJar("java", List.of("-Xmx128m"), "mosire.jar"),
            AgentCommand.javaClasspath("java", "io.mosire.Main"),
            AgentCommand.javaClasspath("java", List.of("-Xmx128m"), "io.mosire.Main"),
            new AgentCommand(
                "/usr/bin/java",
                List.of("-Xmx128m"),
                List.of("-jar", "/opt/mosire/mosire.jar", "agent"),
                "--id",
                "/opt/mosire/templates",
                "/opt/mosire/data/subagents",
                true));

    for (AgentCommand command : commands) {
      assertThat(command.configDir()).as("既有构造器一律传 null（R-A2-5）").isNull();
      assertThat(command.argv(instance("r-1", "reader", "目标是")))
          .as("configDir == null ⇒ argv 不含 --config-dir（有无条件传的实现会在这里变红）")
          .noneMatch(argument -> argument.contains("--config-dir"));
    }
  }

  /**
   * ③（硬性）：argv 只带路径，绝不带密钥值——用 {@code @TempDir} 造的哨兵配置跑一遍拼接。
   *
   * <p>夹具是<b>可咬的</b>：先用与子进程同一条链路（{@link FileConfigStore} + {@link
   * ConfigApiKeySource}）从<b>同一个</b>配置根 读出哨兵值，证明"这份配置里确实有一个密钥值可取"；再断言 argv
   * 的任何元素都不含它。把密钥塞进参数面图省事的实现会在这里变红。
   */
  @Test
  void argvCarriesTheConfigPathButNeverTheCredentialValue() throws Exception {
    Path configRoot = tempDir.resolve("config-root");
    Files.createDirectories(configRoot);
    Files.writeString(configRoot.resolve("config.json"), configJson(), StandardCharsets.UTF_8);

    ConfigApiKeySource keys =
        new ConfigApiKeySource(new FileConfigStore(configRoot), "keys.local", AccessToken.SYSTEM);
    assertThat(keys.apiKey()).as("夹具前置：同一配置根确实能取出密钥值（否则下面的断言恒真）").contains(SENTINEL_KEY);

    List<String> argv = command(configRoot.toString()).argv(instance("r-1", "reader", "目标是"));

    assertThat(argv).contains("--config-dir", configRoot.toString());
    assertThat(argv).noneMatch(argument -> argument.contains(SENTINEL_KEY));
  }

  // ---------- 工具 ----------

  private static AgentCommand command(String configDir) {
    return new AgentCommand(
        "/usr/bin/java",
        List.of("-Xmx128m"),
        List.of("-jar", "/opt/mosire/mosire.jar", "agent"),
        "--id",
        "/opt/mosire/templates",
        "/opt/mosire/data/subagents",
        true,
        configDir);
  }

  /** 假配置：{@code llm.*} 指向本机不可达端口（不会被拨号）+ {@code keys.local} = 哨兵值。 */
  private static String configJson() {
    return "{\"keys\":{\"local\":\""
        + SENTINEL_KEY
        + "\"},\"llm\":{\"baseUrl\":\"http://127.0.0.1:1/v1\",\"model\":\"model-one\","
        + "\"credentialsRef\":\"keys.local\"}}";
  }

  /** 测试用实例快照（同 {@code SubProcessExecutorTest}：只读数据模型，直接供给 argv 拼装契约）。 */
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
