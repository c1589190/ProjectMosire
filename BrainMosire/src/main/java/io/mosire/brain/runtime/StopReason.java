package io.mosire.brain.runtime;

/** 回合结束原因（硬顶/正常收尾），供事件与日志审计。 */
public enum StopReason {
  /** 正常完成：模型返回纯文本、无工具调用。 */
  FINISHED,
  /** 到达 maxTurns 硬顶。 */
  TURN_LIMIT,
  /** 单个模型响应内工具调用数超过 maxToolCallsPerTurn。 */
  TOOL_CALL_LIMIT,
  /** 超出 timeBudget 硬顶。 */
  TIME_BUDGET,
  /** 超出 token 配额（计划 D10）。 */
  QUOTA,
  /** LLM 调用失败（网络/协议/供应商错误），管线优雅终止而非异常穿透。 */
  LLM_ERROR
}
