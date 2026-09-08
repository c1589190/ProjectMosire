package io.mosire.brain.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 子 Agent 子进程的启动命令模板（Rul C：Brain 不做文件系统假设——jar 路径/入口类由装配层按当前 发行形态注入；本记录只是"命令 + 参数前缀 + id
 * 参数名"的最小形状，不含任何路径探测）。
 *
 * <p>形状即 D4 的 stdio 子进程形态：{@code <command> <prefix...> --id <instanceId>}；默认模板 {@code java -jar
 * <jar> agent --id <id>}（{@link #javaJar}）或 {@code java -cp <java.class.path> <Main> agent --id
 * <id>}（{@link #javaClasspath}）。
 *
 * @param command 可执行文件名（如 {@code java}）
 * @param prefix 子命令前缀参数（如 {@code -jar <jar> agent}）
 * @param idFlag 实例 id 参数名（{@code --id}）
 */
public record AgentCommand(String command, List<String> prefix, String idFlag) {

  public AgentCommand {
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("命令不能为空");
    }
    prefix = List.copyOf(prefix == null ? List.of() : prefix);
    if (idFlag == null || idFlag.isBlank()) {
      throw new IllegalArgumentException("idFlag 不能为空");
    }
  }

  /** 同 jar 形态：{@code <java> -jar <jarPath> agent --id <实例id>}（D4；jar 路径由装配层注入）。 */
  public static AgentCommand javaJar(String javaBinary, String jarPath) {
    if (jarPath == null || jarPath.isBlank()) {
      throw new IllegalArgumentException("jarPath 不能为空");
    }
    return new AgentCommand(javaBinary, List.of("-jar", jarPath, "agent"), "--id");
  }

  /**
   * classpath 形态：{@code <java> -cp <java.class.path> <mainClass> agent --id <实例id>}。
   *
   * <p>classpath 取 {@code System.getProperty("java.class.path")}——Rul C 允许的"当前进程自带 信息"，Brain
   * 侧仍不探测任何文件路径；其余参数由装配层给定。
   */
  public static AgentCommand javaClasspath(String javaBinary, String mainClass) {
    if (mainClass == null || mainClass.isBlank()) {
      throw new IllegalArgumentException("mainClass 不能为空");
    }
    return new AgentCommand(
        javaBinary,
        List.of("-cp", System.getProperty("java.class.path", ""), mainClass, "agent"),
        "--id");
  }

  /** 拼出完整命令行（命令 + 前缀 + id 参数对）。 */
  List<String> argv(String instanceId) {
    List<String> argv = new ArrayList<>();
    argv.add(command);
    argv.addAll(prefix);
    argv.add(idFlag);
    argv.add(Objects.requireNonNull(instanceId, "instanceId"));
    return argv;
  }
}
