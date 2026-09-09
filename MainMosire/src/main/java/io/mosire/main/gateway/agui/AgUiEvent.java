package io.mosire.main.gateway.agui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * AG-UI 事件（调度面量：AG-UI 1.0 Draft 词汇表的子集事件）。
 *
 * <p>{@link #fields()} 即线格式 JSON 对象本体（含 {@code "type"} 判别键）；{@link #type()} 是它的便捷访问值。 构造时做两件事：把
 * {@code type} 写入 fields（保证序列化结构 {"type": ..., ...}）且值不可为空（翻译层明确 缺失字段→空串/回退 id，见 {@link
 * AgUiEventTranslator}）。
 */
public record AgUiEvent(String type, Map<String, Object> fields) {

  public AgUiEvent {
    Objects.requireNonNull(type, "type");
    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    copy.put("type", type);
    for (Map.Entry<String, Object> entry : Objects.requireNonNull(fields, "fields").entrySet()) {
      copy.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
    }
    fields = Collections.unmodifiableMap(copy);
  }

  /** 便捷构造：仅含 type 与字段（无值时省略字段本身）。 */
  public static AgUiEvent of(String type, Map<String, Object> fields) {
    return new AgUiEvent(type, fields);
  }
}
