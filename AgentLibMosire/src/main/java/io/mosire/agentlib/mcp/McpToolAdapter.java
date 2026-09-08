package io.mosire.agentlib.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 把 SDK 的 {@link McpSchema.Tool} 适配为 {@link AgentTool}（一次转换，双向复用）。
 *
 * <p>外部 MCP 工具一律按"常规工具"（{@link ToolSpec#DEFAULT}）暴露：其是否敏感/破坏无法先知， 凭 name/description 由三层（brain
 * 载入表）细化——P0 先保持最小口径（计划 §3.2）。
 */
public final class McpToolAdapter implements AgentTool {

  private final String name;
  private final String description;
  private final Map<String, Object> jsonSchema;
  private final McpCaller caller;

  /** 将一个 MCP 工具描述包装为 AgentTool；{@code caller} 为实际执行回调。 */
  public static McpToolAdapter of(McpSchema.Tool tool, McpCaller caller) {
    Objects.requireNonNull(tool, "tool");
    Objects.requireNonNull(caller, "caller");
    String description = tool.description();
    if (description == null || description.isBlank()) {
      description = tool.title() == null || tool.title().isBlank() ? tool.name() : tool.title();
    }
    Map<String, Object> schema = tool.inputSchema() == null ? Map.of() : tool.inputSchema();
    return new McpToolAdapter(tool.name(), description, schema, caller);
  }

  private McpToolAdapter(
      String name, String description, Map<String, Object> jsonSchema, McpCaller caller) {
    this.name = name;
    this.description = description;
    this.jsonSchema = jsonSchema;
    this.caller = caller;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String description() {
    return description;
  }

  @Override
  public Map<String, Object> jsonSchema() {
    // 对外每次给新快照（Schema 可能含 null 值，不能 Map.copyOf）
    return Collections.unmodifiableMap(new LinkedHashMap<>(jsonSchema));
  }

  @Override
  public ToolResult execute(ToolContext context) {
    // 身份/权限已在 guard 边界校验；此处把调用参数交给远程
    return caller.call(name, context.arguments());
  }
}
