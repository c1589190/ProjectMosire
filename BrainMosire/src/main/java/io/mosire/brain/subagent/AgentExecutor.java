package io.mosire.brain.subagent;

/**
 * 子 Agent 执行体抽象（计划 §4.4）。
 *
 * <p>与 WIP {@link SubagentLauncher} 同合同——launcher 是"启动缝"的本包命名（W3a 只定义契约、W3b 接 真实 {@code agent --id}
 * stdio 子进程），AgentExecutor 是计划 §4.4 的执行体抽象命名；本接口即 两个命名面的映射点，实现类按 4.4 清单：{@link
 * SubProcessExecutor}（默认，同 jar {@code agent} 子命令 + MCP stdio 连接）/ {@link InProcessExecutor}（测试）/（预留
 * {@code ContainerExecutor}）。
 *
 * <p>为什么 Manager 只依赖窄缝（{@link SubagentLauncher}）：编排核心只做生命周期编排，执行体内部
 * （进程管道/命令组装/隔离）不侵入状态机与事件链；假实现与真实现可以在同缝下互换（ {@link SubagentManagerTest} 给假 launcher，Main 装配给
 * SubProcessExecutor）。
 */
public interface AgentExecutor extends SubagentLauncher {}
