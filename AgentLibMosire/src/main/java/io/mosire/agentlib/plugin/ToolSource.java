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

  /**
   * 装载时注入宿主服务（{@link HostServices}）：<b>一次</b>，早于 {@link #listTools()}，由装载方（{@link
   * PluginToolSource}）在把本源的 {@code id()} 与工具登记进全局目录<b>之前</b>调用。
   *
   * <p><b>为什么在这里校验配置</b>：这是唯一的"装载期"钩子。配置项存在但值非法 ⇒ 在<b>本方法里抛</b>，装载随之失败（回滚 + {@code FAILED} +
   * 启动响亮失败，见 {@link PluginToolSource} 的失败语义）——若拖到第一次工具调用才解析，坏配置就变成"跑到那一步才炸"的静默降级。
   *
   * <p><b>实现约束</b>：
   *
   * <ul>
   *   <li><b>不得</b>在此（或任何别的地方）向全局状态注册工具——工具进入全局目录的唯一路径是 {@link #listTools()} 返回的快照；
   *   <li>本方法在<b>每次装载</b>时被调一次；{@code disable → enable} 是<b>重新装载</b>（新建类加载器、重新取扩展实例），
   *       因此实现不必把本方法当成"进程内只调一次"，但也不该在装载之外被调；
   *   <li>{@link HostServices#config()} <b>可能为 null</b>（未装配的宿主，如 3 参构造的 {@link
   *       PluginToolSource}）——实现要么响亮失败， 要么退化到缺省并记日志，不得假设非空。
   * </ul>
   *
   * <p>缺省实现为 no-op：不需要宿主服务的插件（纯静态工具集）什么都不用做，且<b>旧宿主</b>（不认识本方法的宿主 API 版本）与本接口的 缺省实现天然二进制兼容——这正是把它做成
   * default 方法的原因。
   *
   * @param services 宿主服务面（非 null；未装配时是 {@link HostServices#none()}，其 {@code config()} 为 null）
   * @throws RuntimeException 装载期校验失败（该插件装载失败并回滚，绝不静默跳过）
   */
  default void init(HostServices services) {}

  /** 供给源唯一标识（审计/按源卸载用）；同一运行时内不可重复，生命周期内不应变化。 */
  String id();

  /** 该源当前提供的全局可用工具快照（进入 L3 白名单前的全集；全局可用 ≠ 每个 Agent 可用）。 */
  List<AgentTool> listTools();

  /** 订阅本源工具集变更（增/删/替换），变更后同步回调、勿阻塞；返回句柄 close 即撤销订阅。 */
  AutoCloseable onChange(Runnable listener);

  /** 释放源自身持有的资源（无资源则空实现）。 */
  default void close() {}
}
