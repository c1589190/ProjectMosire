package io.mosire.main.gateway.debug;

import io.mosire.brain.runtime.TurnResult;
import java.time.Instant;

/**
 * 一次调试对话运行的不可变快照（内存态运行记录）。
 *
 * <p>运行状态推进时以<b>整体替换</b>的方式更新（服务内部用新 record 覆盖同 runId 的旧值）——因此本类型无需同步即可安全共享。
 *
 * @param runId 运行 id（服务内 AtomicLong 自增，单调递增）
 * @param status 运行状态
 * @param message 提交时的用户消息原文
 * @param result 回合结果投影（仅终态有值；FAILED by-exception 时为 null）
 * @param createdAt 提交时刻
 */
public record DebugChatRun(
    long runId, Status status, String message, TurnResult result, Instant createdAt) {

  /** 运行状态：RUNNING 在途；FINISHED 正常收尾；CANCELLED 被取消；FAILED 其余停止原因或回合异常。 */
  public enum Status {
    RUNNING,
    FINISHED,
    CANCELLED,
    FAILED
  }
}
