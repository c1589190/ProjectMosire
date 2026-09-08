package io.mosire.brain.subagent;

/**
 * 子 Agent 启动失败（launcher 无法把子进程拉起，或拉起后立刻失联）。
 *
 * <p>为什么独立成异常类型而不是复用 {@link IllegalStateException}：launch 失败是外部资源问题 （可执行文件缺失、stdio 打不开），调用方（{@link
 * SubagentManager}）要把状态推进到 FAILED 并 原样传播；一个明确的类型让"资源性失败"与"编程错误"在 catch 处可区分。
 */
public class SubagentLaunchException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SubagentLaunchException(String message) {
    super(message);
  }

  public SubagentLaunchException(String message, Throwable cause) {
    super(message, cause);
  }
}
