package io.mosire.brain.subagent;

import io.mosire.brain.runtime.AgentConfig;

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
   * 启动一个子 Agent 进程/执行体。
   *
   * @param instanceId 实例 id（事件 correlationId 与进程标识；W3b 映射为 {@code agent --id}）
   * @param childConfig 子 Agent 配置（数据不是代码——launcher 只读取，不改动）
   * @return 存活句柄
   * @throws SubagentLaunchException 启动失败（含原因）
   */
  LaunchedSubagent launch(String instanceId, AgentConfig childConfig)
      throws SubagentLaunchException;

  /** 幂等关闭 launcher 自身资源。 */
  @Override
  void close();
}
