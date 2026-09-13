package io.mosire.agentlib.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.Digest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 把 SDK 的 {@link McpSchema.Tool} 适配为 {@link AgentTool}（一次转换，双向复用）。
 *
 * <p>外部 MCP 工具一律按"常规工具"（{@link ToolSpec#DEFAULT}）暴露：其是否敏感/破坏无法先知， 凭 name/description 由三层（brain
 * 载入表）细化——P0 先保持最小口径（计划 §3.2）。
 *
 * <p><b>落账视图（F3）：一律安全摘要，绝不落明文</b>——见 {@link #ledgerArgs(ToolContext)}。
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

  /**
   * 落 {@code tool.call} 事件时的参数视图：<b>桥的这侧没有策略可言</b> ⇒ 一律只落"指纹 + 体量 + 参数名"，<b>绝不落明文</b>。
   *
   * <p><b>为什么不能照抄真工具的视图</b>："{@code AgentTool#ledgerArgs} 自报落账视图"这套 SPI 的前提是<b>知识在工具那一侧</b> （只有
   * bash 自己知道 {@code command}/{@code env} 是模型自造的任意字符串）。本类只拿得到名字与 schema——哪个字段可能承载
   * 密钥（D23/D24）它<b>猜不出</b>；而猜错的代价是把明文写进事件库（子体的 {@code events.db} 没有 messages 表，这条落账是它
   * 命令的唯一持久痕迹），猜对的收益只是几个对账字段 ⇒ 缺省必须 fail-closed。
   *
   * <p><b>两个作用点都是本类，这是有意的</b>：① <b>子 Agent 侧</b>——子进程的 registry 为空，工具全部经 {@link McpSourceBridge}
   * 从父级桥来，子体拿到的就是本类（F3 的形态：桥接工具没有策略 ⇒ 命令明文落进子体自己的 {@code tool.call}）； ② 父侧<b>外部</b> MCP
   * 工具——也是本类。前者是必须修的缺陷，后者顺带收进同一条红线：外部工具的字符串参数与 bash 命令同性质 （模型自造、可能复述密钥），系统对它同样一无所知。
   *
   * <p><b>形状</b>：{@code {"digest": "sha256:<32 hex>", "len": <参数值字符总长>, "keys": [参数名（字典序）]}}。
   * {@code digest} 与审批摘要（{@code ToolCallAuthorizer.defaultDigest}，也是 {@code approval.*} 事件的 {@code
   * digest}） <b>同一段代码、同一输入面（全部参数）</b> ⇒ 参数经 JSON 往返不变时两者逐字相同，可由摘要互证"子体这次调用就是被批过的那次"。 {@code keys}
   * 只暴露参数名（工具 schema 对模型本就是公开面），{@code len} 给一个粗粒度体量。
   *
   * <p><b>诚实的边界</b>：① 这是<b>减少暴露面，不是保密</b>——低熵参数（短命令）仍可被字典攻击反推（同 {@link Digest} 的边界）； ②
   * 真需要"逐字回放子体命令"就得走"真工具自报落账策略过桥"那条路（登记在案，未实现）；③ 外部 MCP 工具据此<b>也</b>只落摘要， 是这条缺省有意的连带面，不是副作用。
   */
  @Override
  public Map<String, Object> ledgerArgs(ToolContext context) {
    Objects.requireNonNull(context, "context");
    Map<String, Object> arguments = context.arguments();
    long chars = 0;
    for (Object value : arguments.values()) {
      if (value != null) {
        chars += String.valueOf(value).length();
      }
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("digest", Digest.ofArguments(arguments));
    view.put("len", chars);
    // 参数名排序：同参同视图（对账与去重都靠这一条）
    view.put("keys", new ArrayList<>(new TreeSet<>(arguments.keySet())));
    return Map.copyOf(view);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    // 身份/权限已在 guard 边界校验；此处把调用参数交给远程
    return caller.call(name, context.arguments());
  }
}
