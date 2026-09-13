package io.mosire.agentlib.permission;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本进程<b>主 Agent 的当前档位</b>：配置给初值，运行时经 HTTP 断点可改（用户裁决"也可以整个命令 / http 断点调"）。
 *
 * <p><b>为什么是可变的持有者</b>：档位不是启动期常量——读方（工具调用装配 {@code ToolContext.identity}、
 * 上级判定闸的"本级是不是完全权限"）每次调用<b>现读</b>，改档立即对下一次调用生效，不需要重启。持 {@link AtomicReference} 是为了"读-改"跨线程可见（MCP
 * 面跑在 Reactor 的 boundedElastic 上，聊天执行器是另一条线程）。
 *
 * <p><b>降档不回滚已批的会话放行</b>：{@link #set} 只换档，不去动审批表里已经记下的 {@code (调用者, classKey)}
 * 放行键——那些键是人（或上级）在<b>当时</b>那条命令上批的，清不清由装配层决定（{@link #set} 返回旧值， 让调用方拿得到"从哪一档换过来的"用于留痕与清理）。
 */
public final class CommandModeHolder {

  /**
   * 运行时缝的键名：装配层把持有者放进 {@code ToolContext.config}（同一个 Map 里还有工具自己的配置项）， 宿主侧的上下文装配处（{@code
   * AgentPipeline} 建调用上下文时）从同一个缝里现读它——无需为"档位"再穿一条构造器链。
   */
  public static final String CONFIG_KEY = "commands.modeHolder";

  private final AtomicReference<CommandMode> mode;

  public CommandModeHolder(CommandMode initial) {
    this.mode = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
  }

  /** 当前档位（读方每次现读，别把它缓存到字段里）。 */
  public CommandMode get() {
    return mode.get();
  }

  /**
   * 换档。
   *
   * @return <b>旧值</b>（调用方据此留痕；旧值 = 新值时说明这次是空操作）
   */
  public CommandMode set(CommandMode next) {
    return mode.getAndSet(Objects.requireNonNull(next, "next"));
  }
}
