package io.mosire.brain.subagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 包内共享的 Jackson 序列化入口（事件 payload 与工具结果 JSON 复用同一 Mapper，避免每类一个实例）。 */
final class SubagentJson {

  private static final Logger LOG = LoggerFactory.getLogger(SubagentJson.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SubagentJson() {
    throw new AssertionError("No instances");
  }

  /**
   * 序列化为 JSON；失败返回 {@code "{}"}（与 AgentPipeline 同策略——payload 是旁路数据， 序列化失败不应打断业务流，但必须留痕）。
   *
   * @param payload 可序列化对象（Map/record/集合）
   * @return JSON 字符串
   */
  static String toJson(Object payload) {
    try {
      return MAPPER.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      LOG.error("payload 序列化失败 type={}", payload.getClass().getName(), e);
      return "{}";
    }
  }
}
