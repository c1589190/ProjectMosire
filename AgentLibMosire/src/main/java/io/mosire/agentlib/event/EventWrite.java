package io.mosire.agentlib.event;

import java.util.Objects;

/** 写事件请求：seq/ts 由存储分配，写入侧只给语义内容。 */
public record EventWrite(String type, String agent, String payload, String correlationId) {

  public EventWrite {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(agent, "agent");
    payload = payload == null ? "" : payload;
    correlationId = correlationId == null ? "" : correlationId;
  }

  public static EventWrite of(String type, String agent) {
    return new EventWrite(type, agent, "", "");
  }

  public static EventWrite of(String type, String agent, String payload) {
    return new EventWrite(type, agent, payload, "");
  }

  public static EventWrite of(String type, String agent, String payload, String correlationId) {
    return new EventWrite(type, agent, payload, correlationId);
  }
}
