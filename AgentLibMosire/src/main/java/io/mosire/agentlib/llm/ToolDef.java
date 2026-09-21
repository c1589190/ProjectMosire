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
    // 立即不可变快照；顶层不允许 null 键/值（与 ToolCall 一致）。
    // 先手扫一遍再 Map.copyOf：Map.copyOf 撞上 null 只抛**无消息**的 NPE，而 schema 常来自外部（MCP 服务器的
    // inputSchema 是任意 JSON，顶层带 null 值并不罕见）——工具一多就无从知道坏的是哪一条。接受/拒绝的集合与
    // Map.copyOf 逐字相同，这里只是把"拒了"补成"拒了并点名"。
    // 注意约束是**浅层**的：properties 等嵌套层里的 null 值是合法 JSON，照常通过（Jackson 也容忍）。
    for (Map.Entry<String, Object> entry : jsonSchema.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "jsonSchema 含 null 键");
      Objects.requireNonNull(entry.getValue(), "jsonSchema." + entry.getKey() + " 的值为 null");
    }
    jsonSchema = Map.copyOf(jsonSchema);
  }

  public static ToolDef of(String name, String description, Map<String, Object> jsonSchema) {
    return new ToolDef(name, description, jsonSchema);
  }
}
