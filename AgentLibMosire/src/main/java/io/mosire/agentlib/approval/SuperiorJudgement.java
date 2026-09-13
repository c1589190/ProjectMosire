package io.mosire.agentlib.approval;

/**
 * <b>上级 Agent 的判定</b>：把一条"不完全权限档提升上来的"审批请求交出去，收回"放行 / 拒"与一行理由。
 *
 * <p>独立成接口（而不是直接塞进 {@link SuperiorJudgeGate}）的理由：真实实现要走 LLM——网络调用、有超时、会失败， 而闸的逻辑（什么时候该判、判完怎么落到
 * {@link ApprovalDecision}）是纯的。分开后两者各自可测，也让"换一个判定实现"（脚本、规则、另一个模型）不动闸。
 *
 * <p><b>判定不是授权</b>：这里只回答"这一次调用放不放行"，写不回任何权限集、也不被任何子体继承（同 {@link ApprovalDecision} 的口径）。
 */
@FunctionalInterface
public interface SuperiorJudgement {

  /**
   * 判定一条请求。
   *
   * <p>实现<b>不该抛异常</b>：闸对异常按"拒"办（fail-closed），但异常路径的日志只有"失败了"这一层， 不如实现自己把失败原因写清楚。
   *
   * @return 判定结果（不得为 {@code null}）
   */
  Decision judge(ApprovalRequest request);

  /**
   * 一次判定的结果。
   *
   * @param approved 放行 = {@code true}
   * @param reason 一行理由——<b>只进日志</b>，不进事件面、不进 {@code ToolResult}：判定提示里带着命令原文，
   *     理由复述它并不奇怪，而日志是本仓既定的调试面（D23/D24 的持久面禁令针对事件库与模型消息）
   */
  record Decision(boolean approved, String reason) {

    public Decision {
      reason = reason == null ? "" : reason;
    }

    public static Decision approve(String reason) {
      return new Decision(true, reason);
    }

    public static Decision deny(String reason) {
      return new Decision(false, reason);
    }
  }
}
