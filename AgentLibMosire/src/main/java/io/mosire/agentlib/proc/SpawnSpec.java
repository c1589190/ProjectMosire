package io.mosire.agentlib.proc;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 子进程启动规格：命令 + 参数 + 环境 + 工作目录 + 输出上限。
 *
 * <p>可空约定：{@code args}/{@code env} 为空、{@code workingDir} 为 null 时，分别表示"不传参数"、
 * "继承父进程环境"、"继承父进程工作目录"。
 *
 * <p><strong>有意不含 startupTimeout</strong>：普通 spawn 没有协议握手，无法判定"启动完成"这一时刻， 该字段只会成为永远不生效的死配置（同 R11
 * 先例：不给不存在的语义留参数）。握手式的启动判定属于 M2-W3 的 MCP stdio 层，不属于进程地基。
 *
 * <p>{@code maxOutputBytes} 为 0 或负数表示不限；正数时超出即判 overflowed 并立即强杀（防日志爆炸）。
 */
public record SpawnSpec(
    String command,
    List<String> args,
    Map<String, String> env,
    Path workingDir,
    long maxOutputBytes) {

  /** 默认输出上限：1 MiB。 */
  public static final long DEFAULT_MAX_OUTPUT_BYTES = 1_048_576L;

  /**
   * 规范化构造：args/env 归一为不可变集合（null 视为空），command 必须非空。
   *
   * @param command 可执行文件名或绝对路径，非空
   * @param args 参数列表，null/空 = 不传
   * @param env 额外环境变量，null/空 = 继承父进程
   * @param workingDir 工作目录，null = 继承父进程
   * @param maxOutputBytes 累计输出上限（字节），0 或负 = 不限
   */
  public SpawnSpec {
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("command 不能为空");
    }
    args = List.copyOf(args == null ? List.of() : args);
    env = Map.copyOf(env == null ? Map.of() : env);
  }

  /** 最简规格工厂：默认 1 MiB 输出上限、继承父进程环境与工作目录。 */
  public static SpawnSpec of(String command, String... args) {
    return new SpawnSpec(command, List.of(args), null, null, DEFAULT_MAX_OUTPUT_BYTES);
  }
}
