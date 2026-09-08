package io.mosire.agentlib.event;

import java.util.Objects;

/**
 * 事件查询条件。
 *
 * <p>空字段 = 不过滤；{@code beforeSeq} = 只取 seq 严格小于该值的区间（翻页游标，-1 表示不限制）； 结果按 seq 倒序返回（最新在前）。
 */
public record EventQuery(
    String agent, String type, String correlationId, long beforeSeq, int limit) {

  public EventQuery {
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(correlationId, "correlationId");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit 必须为正: " + limit);
    }
  }

  /** 最近 {@code limit} 条，不过滤。 */
  public static EventQuery recent(int limit) {
    return new EventQuery("", "", "", -1, limit);
  }
}
