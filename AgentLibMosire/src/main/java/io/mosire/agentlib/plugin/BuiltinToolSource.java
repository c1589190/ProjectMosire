package io.mosire.agentlib.plugin;

import io.mosire.agentlib.tool.AgentTool;
import java.util.List;
import java.util.Objects;

/**
 * 内置工具供给源：main classpath 上的系统扩展工具（决策 D13 的"焊死"形态）。
 *
 * <p>与 {@link io.mosire.agentlib.mcp.McpToolSource}（外部进程）、将来的插件源（plugins/ 目录，P2-7）并列的第三类供给源。
 * 工具列表在构造时确定并防御性拷贝，此后永不变化——内置工具随主 jar 发布，增删即重新发行， 因此 {@link #onChange(Runnable)} 是
 * no-op（订阅者永远不会收到回调，返回的句柄 close 亦为空操作）。
 *
 * <p>归属语义：实例只声明"全局目录里有哪些工具"（{@link #listTools()} 快照）；某 Agent 能否调用由其 L3 白名单决定， 本类不做任何按 Agent 的放行判断（见
 * {@link ToolSource} 接口约定）。
 */
public final class BuiltinToolSource implements ToolSource {

  private final String id;
  private final List<AgentTool> tools;

  /**
   * @param id 供给源唯一标识（审计与按源卸载用，如 "builtin"）
   * @param tools 内置工具全集（防御性拷贝，构造后与调用方脱钩）
   */
  public BuiltinToolSource(String id, List<AgentTool> tools) {
    this.id = Objects.requireNonNull(id, "id");
    this.tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public List<AgentTool> listTools() {
    return List.copyOf(tools);
  }

  /** 静态列表永不变化：订阅是 no-op，仅校验监听器非空并返回可关闭的空句柄。 */
  @Override
  public AutoCloseable onChange(Runnable listener) {
    Objects.requireNonNull(listener, "listener");
    return () -> {};
  }
}
