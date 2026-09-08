package io.mosire.agentlib.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个外部 MCP server 的连接配置（stdio transports）。
 *
 * <p>注意（计划 §3.2 实测结论）：MCP SDK 的 {@code ServerParameters} 无工作目录字段——本配置 也不承诺 cwd；未来需要 cwd 时由 AgentLib
 * 的 SubprocessManager（M2）先设置好再启动，而不是 传给 SDK。
 */
public record McpServerLinkConfig(
    String name,
    String command,
    List<String> args,
    Map<String, String> env,
    Duration startupTimeout,
    Duration requestTimeout) {

  public McpServerLinkConfig {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(command, "command");
    args = args == null ? List.of() : List.copyOf(args);
    env = env == null ? Map.of() : Map.copyOf(env);
    startupTimeout = startupTimeout == null ? Duration.ofSeconds(15) : startupTimeout;
    requestTimeout = requestTimeout == null ? Duration.ofSeconds(60) : requestTimeout;
    if (command.isBlank()) {
      throw new IllegalArgumentException("command 不能为空");
    }
  }

  public static McpServerLinkConfig of(String name, String command) {
    return new McpServerLinkConfig(name, command, List.of(), Map.of(), null, null);
  }
}
