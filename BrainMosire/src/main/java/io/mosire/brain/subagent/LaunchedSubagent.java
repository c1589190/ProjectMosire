package io.mosire.brain.subagent;

/**
 * 一个已启动子 Agent 的存活句柄（进程/执行体的最小控制面）。
 *
 * <p>为什么只有 {@code isAlive()} 和 {@code close()}：编排核心（Manager）只做生命周期编排， 不关心子进程的 stdio/退出码细节——那是 W3b
 * 真实实现与后续监控的事；控制面越窄，假实现越可信。
 *
 * <p>实现约定：{@link #close()} 必须幂等（kill 流程可能对同一句柄触发多次关闭）。
 */
public interface LaunchedSubagent extends AutoCloseable {

  /** 执行体是否仍存活（终态判定不依赖它，供观测/诊断）。 */
  boolean isAlive();

  /** 请求终止执行体；幂等。 */
  @Override
  void close();
}
