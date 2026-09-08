package io.mosire.agentlib.event;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条已落库的事件（持久化后的完整形态：seq 为存储分配、ts 为写入时的时间戳）。
 *
 * <p>{@code payload} 为 JSON 字符串（不预设具体 schema，消费方按其语义解释；词汇表第一批见 brain 的 {@code EventTypes}）。{@code
 * correlationId} 用于串起一次会话/任务的事件链 （AG-UI 会话 id、A2A task id、子 Agent 生命周期 id）。
 */
public record Event(
    long seq, Instant ts, String type, String agent, String payload, String correlationId) {

  public Event {
    Objects.requireNonNull(ts, "ts");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(agent, "agent");
    payload = payload == null ? "" : payload;
    correlationId = correlationId == null ? "" : correlationId;
  }
}
