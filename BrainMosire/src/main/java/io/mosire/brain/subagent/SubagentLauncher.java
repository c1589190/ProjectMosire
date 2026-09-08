package io.mosire.brain.subagent;

/**
 * 子进程启动缝（W3a 只定义契约，W3b 接真实 {@code agent --id} stdio 子进程）。
 *
 * <p>为什么留缝而不是直接起进程：本包的状态机/权限/事件链必须能离线验证（测试给假实现）， 真实进程管理（agentlib.proc 的进程组、超时、stdio 泵）是独立关注点，W3b
 * 以独立实现接入， 编排核心不因接线而改动。
 *
 * <p>实现约定：{@link #close()} 关闭 launcher 自身持有的资源（不是某个子实例——子实例句柄见 {@link LaunchedSubagent}），且必须幂等。
 */
public interface SubagentLauncher extends AutoCloseable {

  /**
   * 启动一个子 Agent 进程/执行体（W3b 签名：完整实例快照而非"id + 配置"——真实执行体需要模板 id、 目标与权限集来拼装 {@code agent --id} 命令与父侧
   * MCP 服务，数据都来自快照，不留缺省假设）。
   *
   * @param instance 已通过守卫的实例快照（只读数据；launcher 不改动，状态推进归 Manager）
   * @return 存活句柄
   * @throws SubagentLaunchException 启动失败（含原因）
   */
  LaunchedSubagent launch(SubagentInstance instance) throws SubagentLaunchException;

  /** 幂等关闭 launcher 自身资源。 */
  @Override
  void close();
}
