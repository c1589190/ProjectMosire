package io.mosire.agentlib.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.permission.ToolSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * <b>工具目录查看器</b>（只读）：把一个调用者<b>自己有权调用</b>的工具及其来源元数据整理成 JSON。
 *
 * <p><b>为什么需要它</b>：A5 的 {@code title/group/tags} 与 A6 的 source displayName/version
 * 都属于“管理/展示”信息，不应该塞进每个 MCP tool 描述里（会把 {@code tools/list}
 * 撑大，也属于主动暴露更多）。本工具是唯一的查看入口：模型需要分组、标题、来源版本时按需调用。
 *
 * <p><b>可见面口径</b>：只列 {@link PermissionChecker#allows} 允许的工具——与 {@code
 * AgentPipeline.visibleTools()}、{@code ToolExecutionGuard} 同一个 oracle（A-6：可见面 ⊆
 * 判定面）。本工具自己不越过判定，也不展示调用者原本看不见的工具。
 *
 * <p><b>参数</b>：{@code sourceId?} / {@code group?} / {@code tag?} / {@code view=summary|full} /
 * {@code limit?}。默认 {@code view=summary}、{@code limit=50}（上限 200）。{@code full} 才返回逐工具明细；summary
 * 只返回来源与分组计数。
 */
public final class ToolCatalogTool implements AgentTool {

  /** 工具名：`list_*` 留给业务工具；这里明确是“目录”。 */
  public static final String NAME = "tool_catalog";

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ToolRegistry registry;

  public ToolCatalogTool(ToolRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "查看当前可调用工具与来源元数据（只读）。支持按 sourceId/group/tag 过滤；view=summary 只给计数，view=full 给逐工具明细。";
  }

  @Override
  public String title() {
    return "工具目录";
  }

  @Override
  public String group() {
    return "agentlib";
  }

  @Override
  public Set<String> tags() {
    return Set.of("catalog", "read-only");
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of(
            "sourceId",
            Map.of("type", "string", "description", "只看某个供给源（省略 = 全部）"),
            "group",
            Map.of("type", "string", "description", "只看某个工具组（省略 = 全部）"),
            "tag",
            Map.of("type", "string", "description", "只看带某个标签的工具（省略 = 全部）"),
            "view",
            Map.of(
                "type", "string",
                "enum", List.of("summary", "full"),
                "description", "summary = 来源/分组计数；full = 逐工具明细"),
            "limit",
            Map.of(
                "type",
                "integer",
                "minimum",
                1,
                "maximum",
                MAX_LIMIT,
                "description",
                "full 视图最多返回多少条工具（默认 " + DEFAULT_LIMIT + "）")),
        "additionalProperties",
        false);
  }

  @Override
  public ToolSpec spec() {
    // 只读查询；guest 级即可。它只返回调用者自己有权调用的工具，不构成越权读取。
    return ToolSpec.DEFAULT;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    Objects.requireNonNull(context, "context");
    String sourceId;
    String group;
    String tag;
    String view;
    int limit;
    try {
      ArgumentReader args = new ArgumentReader(context.arguments());
      sourceId = args.optionalString("sourceId");
      group = args.optionalString("group");
      tag = args.optionalString("tag");
      view = args.stringOrDefault("view", "summary");
      limit = args.intOrDefault("limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
    } catch (IllegalArgumentException e) {
      return ArgumentReader.invalid(e.getMessage());
    }
    if (!"summary".equals(view) && !"full".equals(view)) {
      return ArgumentReader.invalid("view 只能是 summary 或 full: " + view);
    }

    List<AgentTool> candidates =
        sourceId == null ? registry.list() : registry.listBySource(sourceId);
    List<AgentTool> visible = new ArrayList<>();
    for (AgentTool tool : candidates) {
      ToolSpec spec = tool.spec() == null ? ToolSpec.DEFAULT : tool.spec();
      if (!PermissionChecker.allows(context.permissions(), tool.name(), spec, tool.roles())) {
        continue;
      }
      if (group != null && !group.equals(tool.group())) {
        continue;
      }
      if (tag != null && !tool.tags().contains(tag)) {
        continue;
      }
      visible.add(tool);
    }
    visible.sort(Comparator.comparing(AgentTool::name));

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("view", view);
    root.put("toolCount", visible.size());
    root.put("sources", sources(visible));
    root.put("groups", groups(visible));
    if ("full".equals(view)) {
      List<Map<String, Object>> tools = new ArrayList<>();
      for (AgentTool tool : visible) {
        if (tools.size() >= limit) {
          break;
        }
        tools.add(toolJson(tool, registry));
      }
      root.put("tools", tools);
      root.put("truncated", visible.size() > tools.size());
      root.put("limit", limit);
    }

    try {
      return ToolResult.ok(MAPPER.writeValueAsString(root));
    } catch (JsonProcessingException e) {
      return ToolResult.error("TOOL_CRASH", "工具目录序列化失败");
    }
  }

  private List<Map<String, Object>> sources(List<AgentTool> visible) {
    Map<String, Integer> counts = new TreeMap<>();
    for (AgentTool tool : visible) {
      String sourceId = registry.sourceOf(tool.name()).orElse("");
      counts.merge(sourceId, 1, Integer::sum);
    }
    List<Map<String, Object>> sources = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      String sourceId = entry.getKey();
      Map<String, Object> source = new LinkedHashMap<>();
      source.put("sourceId", sourceId);
      if (sourceId.isEmpty()) {
        source.put("displayName", "builtin");
        source.put("version", "");
      } else {
        ToolSourceInfo info =
            registry
                .sourceInfo(sourceId)
                .orElse(new ToolSourceInfo(sourceId, sourceId, "", "", Map.of()));
        source.put("displayName", info.displayName());
        source.put("version", info.version());
      }
      source.put("toolCount", entry.getValue());
      sources.add(source);
    }
    return sources;
  }

  private static List<Map<String, Object>> groups(List<AgentTool> visible) {
    Map<String, Integer> counts = new TreeMap<>();
    for (AgentTool tool : visible) {
      counts.merge(tool.group(), 1, Integer::sum);
    }
    List<Map<String, Object>> groups = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      Map<String, Object> group = new LinkedHashMap<>();
      group.put("group", entry.getKey());
      group.put("toolCount", entry.getValue());
      groups.add(group);
    }
    return groups;
  }

  private static Map<String, Object> toolJson(AgentTool tool, ToolRegistry registry) {
    ToolSpec spec = tool.spec() == null ? ToolSpec.DEFAULT : tool.spec();
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("name", tool.name());
    json.put("title", tool.title());
    json.put("group", tool.group());
    json.put("tags", new ArrayList<>(new TreeSet<>(tool.tags())));
    json.put("sourceId", registry.sourceOf(tool.name()).orElse(""));
    json.put("requiredLevel", spec.requiredLevel().name());
    json.put("sensitive", spec.sensitive());
    json.put("destructive", spec.destructive());
    return json;
  }

  /** 参数读取：集中校验，错误消息只含参数名/约束，不回显调用者传入的任意长文本。 */
  static final class ArgumentReader {

    private final Map<String, Object> arguments;

    ArgumentReader(Map<String, Object> arguments) {
      this.arguments = arguments == null ? Map.of() : arguments;
    }

    String optionalString(String name) {
      Object value = arguments.get(name);
      if (value == null) {
        return null;
      }
      if (!(value instanceof String text)) {
        throw new IllegalArgumentException(name + " 必须是字符串");
      }
      return text.isBlank() ? null : text.strip();
    }

    String stringOrDefault(String name, String fallback) {
      String value = optionalString(name);
      return value == null ? fallback : value;
    }

    int intOrDefault(String name, int fallback, int min, int max) {
      Object value = arguments.get(name);
      if (value == null) {
        return fallback;
      }
      if (!(value instanceof Number number)) {
        throw new IllegalArgumentException(name + " 必须是整数");
      }
      long asLong = number.longValue();
      if (asLong < min || asLong > max) {
        throw new IllegalArgumentException(name + " 必须在 " + min + ".." + max + " 之间");
      }
      return (int) asLong;
    }

    static ToolResult invalid(String message) {
      return ToolResult.error("INVALID_ARGUMENTS", "参数不合法: " + message);
    }
  }
}
