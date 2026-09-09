package io.mosire.agentlib.plugin;

import io.mosire.agentlib.tool.AgentTool;
import java.util.List;
import org.pf4j.ExtensionPoint;

/**
 * 工具供给源扩展点（PF4J ExtensionPoint）：工具供应链的 L2 层。
 *
 * <p>工具按三层供应链组织：
 *
 * <ol>
 *   <li><b>L1 — {@link AgentTool}</b>：单个工具的定义与执行器；
 *   <li><b>L2 — ToolSource（本接口）</b>：一个供给源，把一批工具注册进全局工具目录（{@link
 *       io.mosire.agentlib.tool.ToolRegistry}）。系统内建源与 MCP 导入/导出源以主 classpath 扩展形式存在，插件源以 JAR 形式放入
 *       plugins/ 目录由 PF4J 加载（决策 D13）；
 *   <li><b>L3 — 每个 Agent 的工具白名单</b>：各 Agent 只能实际调用白名单内的工具。
 * </ol>
 *
 * <p>关键语义：<b>全局可用 ≠ 每个 Agent 可用</b>——{@link #listTools()} 声明的只是"进入全局目录"的工具；某 Agent 能否实际调用由其 L3
 * 白名单决定，供给源不做也不得做任何按 Agent 的放行判断（那是权限层的职责）。
 *
 * <p>实现约定：{@link #id()} 在同一运行时内唯一（审计与按源卸载依赖它）；工具集可能随运行变化（如上游增删）， 变更时经 {@link #onChange(Runnable)}
 * 通知注册方做增量同步，实现不得自行向 {@link io.mosire.agentlib.tool.ToolRegistry} 之外的全局状态注册工具。
 */
public interface ToolSource extends ExtensionPoint {

  /** 供给源唯一标识（审计/按源卸载用）；同一运行时内不可重复，生命周期内不应变化。 */
  String id();

  /** 该源当前提供的全局可用工具快照（进入 L3 白名单前的全集；全局可用 ≠ 每个 Agent 可用）。 */
  List<AgentTool> listTools();

  /** 订阅本源工具集变更（增/删/替换），变更后同步回调、勿阻塞；返回句柄 close 即撤销订阅。 */
  AutoCloseable onChange(Runnable listener);

  /** 释放源自身持有的资源（无资源则空实现）。 */
  default void close() {}
}
