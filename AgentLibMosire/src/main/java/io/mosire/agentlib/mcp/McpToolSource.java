package io.mosire.agentlib.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * MCP 工具源（单向：拉起一个外部 stdio server，把其工具映射进本地 {@code ToolRegistry}）。
 *
 * <p>用法：
 *
 * <pre>{@code
 * try (McpToolSource source = new McpToolSource(config)) {
 *   source.connect();
 *   var tools = source.listTools();   // 转 AgentTool，可 registerAll 进 ToolRegistry
 * }
 * }</pre>
 *
 * <p>生命周期：{@link #connect()} 幂等；{@code tools/list_changed} 变化经 {@link
 * McpClient.SyncSpec#toolsChangeConsumer} 捕获，映射后经 {@link #onToolsChanged} 回放全量新列表（消费方在 Registry
 * 上做增删增量）。
 */
public final class McpToolSource implements AutoCloseable {

  private final McpServerLinkConfig config;
  private McpSyncClient client;
  private volatile List<AgentTool> cached = List.of();
  private volatile Consumer<List<AgentTool>> changeListener;

  public McpToolSource(McpServerLinkConfig config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  /** 配置里声明的源名（日志与审计用）。 */
  public String name() {
    return config.name();
  }

  /** 拉起子进程并完成 MCP 握手；幂等（已连接则直接返回）。 */
  public synchronized void connect() {
    if (client != null) {
      return;
    }
    ServerParameters parameters =
        ServerParameters.builder(config.command()).args(config.args()).env(config.env()).build();
    StdioClientTransport transport =
        new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
    McpSyncClient newClient =
        McpClient.sync(transport)
            .requestTimeout(config.requestTimeout())
            .initializationTimeout(config.startupTimeout())
            .toolsChangeConsumer(this::onServerToolsChanged)
            .build();
    newClient.initialize();
    this.client = newClient;
    // 初始基线：tools/list_changed 之外的首次快照（若有订阅者立即回放）
    cacheAndNotify(newClient.listTools().tools());
  }

  /**
   * 订阅工具目录变化。回调参数 = 全量映射后的新列表（保证不会漏掉变化：连接前订阅 → connect 尾回放； 连接后订阅 → 回放缓存）。 线程：可能在 SDK 通知线程回调，可重入契约与
   * listTools 相同。
   */
  public synchronized void onToolsChanged(Consumer<List<AgentTool>> listener) {
    this.changeListener = Objects.requireNonNull(listener, "listener");
    if (client != null) {
      listener.accept(cached);
    }
  }

  /** 当前 MCP server 的工具目录（映射为 AgentTool 列表，name 去重保序）。 */
  public synchronized List<AgentTool> listTools() {
    requireConnected();
    McpSchema.ListToolsResult result = client.listTools();
    return mapAll(result.tools());
  }

  private void onServerToolsChanged(List<McpSchema.Tool> tools) {
    cacheAndNotify(tools);
  }

  private void cacheAndNotify(List<McpSchema.Tool> tools) {
    List<AgentTool> mapped = mapAll(tools);
    this.cached = mapped;
    Consumer<List<AgentTool>> listener = this.changeListener;
    if (listener != null) {
      listener.accept(mapped);
    }
  }

  private List<AgentTool> mapAll(List<McpSchema.Tool> tools) {
    return tools.stream()
        .map(tool -> McpToolAdapter.of(tool, this::callRemote))
        .collect(Collectors.toUnmodifiableList());
  }

  private ToolResult callRemote(String name, Map<String, Object> arguments) {
    // 经同步访问器取 client：唯一不在 synchronized 方法里的字段访问也走同一把锁（SpotBugs IS2 一致性）
    McpSyncClient connected = requireConnected();
    McpSchema.CallToolResult result =
        connected.callTool(new McpSchema.CallToolRequest(name, Map.copyOf(arguments), Map.of()));
    return map(result);
  }

  /** CallToolResult → ToolResult（文本内容拼接；isError 映射为错误码）。 */
  static ToolResult map(McpSchema.CallToolResult result) {
    String text =
        result.content() == null
            ? ""
            : result.content().stream()
                .map(
                    content ->
                        content instanceof McpSchema.TextContent t ? t.text() : content.toString())
                .collect(Collectors.joining("\n"));
    if (Boolean.TRUE.equals(result.isError())) {
      // 带内错误码（mosire 间约定）：无标记 → 通用码，见 McpWireCode
      String[] codeAndMessage = McpWireCode.decode(text, "MCP 工具调用失败");
      return ToolResult.error(codeAndMessage[0], codeAndMessage[1]);
    }
    return ToolResult.ok(text);
  }

  private synchronized McpSyncClient requireConnected() {
    if (client == null) {
      throw new IllegalStateException("MCP 工具源未连接: " + config.name());
    }
    return client;
  }

  @Override
  public synchronized void close() {
    if (client != null) {
      client.close();
      client = null;
    }
  }
}
