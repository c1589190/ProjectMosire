package io.mosire.brain.subagent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 子 Agent 子进程的启动命令模板（Rul C：Brain 不做文件系统假设——jar 路径/入口类/目录由装配层按当前 发行形态注入；本记录只是 "命令 + 参数前缀 + id
 * 参数名"的最小形状，不含任何路径探测）。
 *
 * <p><strong>W3b 扩展</strong>：{@code jvmArgs}（内存 cap 等 JVM 级参数插在命令与子命令前缀之间）、 {@code
 * templatesDir}/{@code childDataDir}（模板目录与子进程数据根目录注入）、{@code parentLink} （父侧已建 stdio 链接——子体以 MCP
 * client 身份运行并阻塞等待父侧关停信号）。 数据来自实例快照： {@code argv(SubagentInstance)} = 命令 + JVM 参数 + 前缀 + {@code
 * --id <实例id>} + {@code --template <模板id>} + {@code --goal <目标>} +（可选 {@code --templates-dir
 * <目录>}）+（可选 {@code --data-dir <根>/<实例id>}）+（可选 {@code --config-dir <目录>}）+（可选 {@code
 * --parent-link}）。
 *
 * <p><strong>三期 S1-A2 扩展（{@code configDir}）</strong>：子 Agent 走真模型时它自己的 {@code --data-dir}
 * 是隔离目录，底下没有 {@code config.json}（配置在父的数据目录） ⇒ 装配层把父的配置根经本组件注入子进程。它是与 {@code templatesDir} 同性质的"注入
 * 值"（Rul C：Brain 不探测任何路径）；<b>只传路径、不传密钥值</b>——密钥由子进程自己从 {@code <configDir>/config.json} 读（D23）。
 *
 * @param command 可执行文件名（如 {@code java}）
 * @param jvmArgs JVM 级参数（如内存 cap；插在命令之后、子命令前缀之前；null/空 = 不传）
 * @param prefix 子命令前缀参数（如 {@code -jar <jar> agent}）
 * @param idFlag 实例 id 参数名（{@code --id}）
 * @param templatesDir 模板目录（装配层注入；null = 不传，子体按自身默认）
 * @param childDataDir 子 Agent 数据根目录（每实例取其 {@code <root>/<实例id>}；null = 不传）
 * @param parentLink 是否带 {@code --parent-link}（父侧已建 stdio 链接，子体走 MCP client 形态）
 * @param configDir 父的配置根目录（装配层注入；null = 不传——离线/测试形态，子体按自身默认）。<b>只放路径</b>：密钥值绝不经参数面 （R-A2-3/D23）
 */
public record AgentCommand(
    String command,
    List<String> jvmArgs,
    List<String> prefix,
    String idFlag,
    String templatesDir,
    String childDataDir,
    boolean parentLink,
    String configDir) {

  public AgentCommand {
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("命令不能为空");
    }
    jvmArgs = List.copyOf(jvmArgs == null ? List.of() : jvmArgs);
    prefix = List.copyOf(prefix == null ? List.of() : prefix);
    if (idFlag == null || idFlag.isBlank()) {
      throw new IllegalArgumentException("idFlag 不能为空");
    }
  }

  /** W3a 兼容构造：无 JVM 参数、无目录注入、非链接模式。 */
  public AgentCommand(String command, List<String> prefix, String idFlag) {
    this(command, List.of(), prefix, idFlag, null, null, false);
  }

  /**
   * S1-A2 之前的 7 参形态的兼容构造（{@code configDir = null}）：既有调用点与既有 {@code AgentCommand} 用例零改动
   * （R-A2-5——"既有构造器一律传 null"）。
   */
  public AgentCommand(
      String command,
      List<String> jvmArgs,
      List<String> prefix,
      String idFlag,
      String templatesDir,
      String childDataDir,
      boolean parentLink) {
    this(command, jvmArgs, prefix, idFlag, templatesDir, childDataDir, parentLink, null);
  }

  /** 同 jar 形态：{@code <java> <jvmArgs> -jar <jarPath> agent --id <实例id>}（D4；jar 路径由装配层注入）。 */
  public static AgentCommand javaJar(String javaBinary, String jarPath) {
    return javaJar(javaBinary, List.of(), jarPath);
  }

  /** 同 jar 形态 + JVM 参数（W3b：内存 cap 等）。 */
  public static AgentCommand javaJar(String javaBinary, List<String> jvmArgs, String jarPath) {
    if (jarPath == null || jarPath.isBlank()) {
      throw new IllegalArgumentException("jarPath 不能为空");
    }
    return new AgentCommand(
        javaBinary, jvmArgs, List.of("-jar", jarPath, "agent"), "--id", null, null, false, null);
  }

  /**
   * classpath 形态：{@code <java> <jvmArgs> -cp <java.class.path> <mainClass> agent --id <实例id>}。
   *
   * <p>classpath 取 {@code System.getProperty("java.class.path")}——Rul C 允许的"当前进程自带 信息"，Brain
   * 侧仍不探测任何文件路径；其余参数由装配层给定。
   */
  public static AgentCommand javaClasspath(String javaBinary, String mainClass) {
    return javaClasspath(javaBinary, List.of(), mainClass);
  }

  /** classpath 形态 + JVM 参数。 */
  public static AgentCommand javaClasspath(
      String javaBinary, List<String> jvmArgs, String mainClass) {
    if (mainClass == null || mainClass.isBlank()) {
      throw new IllegalArgumentException("mainClass 不能为空");
    }
    return new AgentCommand(
        javaBinary,
        jvmArgs,
        List.of("-cp", System.getProperty("java.class.path", ""), mainClass, "agent"),
        "--id",
        null,
        null,
        false,
        null);
  }

  /** 以实例数据拼出完整命令行（命令 + JVM 参数 + 前缀 + 实例参数对 + 可选注入项）。 */
  List<String> argv(SubagentInstance instance) {
    Objects.requireNonNull(instance, "instance");
    List<String> argv = new ArrayList<>();
    argv.add(command);
    argv.addAll(jvmArgs);
    argv.addAll(prefix);
    argv.add(idFlag);
    argv.add(instance.instanceId());
    argv.add("--template");
    argv.add(instance.templateId());
    argv.add("--goal");
    argv.add(instance.goal());
    if (templatesDir != null) {
      argv.add("--templates-dir");
      argv.add(templatesDir);
    }
    if (childDataDir != null) {
      argv.add("--data-dir");
      argv.add(Path.of(childDataDir).resolve(instance.instanceId()).toString());
    }
    if (configDir != null) {
      // S1-A2：只追加路径——密钥值不在本方法可达的任何字段里（D23/R-A2-3）
      argv.add("--config-dir");
      argv.add(configDir);
    }
    if (parentLink) {
      argv.add("--parent-link");
    }
    return argv;
  }
}
