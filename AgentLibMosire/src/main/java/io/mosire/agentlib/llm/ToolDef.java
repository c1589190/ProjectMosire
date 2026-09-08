package io.mosire.agentlib.llm;

import java.util.Map;
import java.util.Objects;

/**
 * 传给 LLM 的工具定义（JSON Schema 形式）。
 *
 * <p>刻意独立于 {@code io.mosire.agentlib.tool} 包：llm 层只关心"模型该知道哪些函数"， 不依赖调用方 Agent 工具系统的任何假设——其他程序复用
 * llm 时也不需要引入工具栈。
 */
public record ToolDef(String name, String description, Map<String, Object> jsonSchema) {

  public ToolDef {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(jsonSchema, "jsonSchema");
    // 立即不可变快照；Schema Map 中不允许 null 值（与 ToolCall 一致）
    jsonSchema = Map.copyOf(jsonSchema);
  }

  public static ToolDef of(String name, String description, Map<String, Object> jsonSchema) {
    return new ToolDef(name, description, jsonSchema);
  }
}
