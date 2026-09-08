package io.mosire.brain.subagent;

/**
 * spawn 请求被拒绝（模板不存在、权限单调性不通过、深度超限）。
 *
 * <p>为什么是 checked 语义的独立 unchecked 类型：拒绝是编排的<b>正常业务分支</b>（LLM 经常试探 越权 spawn），不是异常故障——工具层捕获它转成 {@code
 * SPAWN_REJECTED} 工具错误回灌模型； 而 launch 失败（{@link SubagentLaunchException}）是资源故障，二者不可混为一谈。
 */
public class SubagentRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SubagentRejectedException(String message) {
    super(message);
  }
}
