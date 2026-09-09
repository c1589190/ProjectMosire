package io.mosire.agentlib.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * MCP 工具源（单向：拉起一个外部 stdio server，把其工具映射进本地 {@code ToolRegistry}）。
 *
 * <p>两种连接形态：
 *
 * <ul>
 *   <li>{@link #McpToolSource(McpServerLinkConfig)} + {@link #connect()}——标准形态：按配置自己 spawn 外部 stdio
 *       server（M2）；
 *   <li>{@link #McpToolSource(String)} + {@link #connect(McpClientTransport)}——接管形态（W3b 子 Agent
 *       进程侧）：进程的 stdin/stdout 已由父进程建好（MCP stdio 链接），复用该传输握手，不再 spawn。
 * </ul>
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
 * McpClient.SyncSpec#toolsChangeConsumer} 捕获后刷新缓存快照，并触发 {@link #onChange} 的全部订阅者（消费方重读
 * {@link #listTools()} 获取新快照，在 Registry 上做增删增量）。
 */
public final class McpToolSource implements ToolSource, AutoCloseable {

  /** 建连超时默认值——与 {@link McpServerLinkConfig} 的缺省一致（接管形态无 config 时的兜底）。 */
  private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(15);

  /** 请求超时默认值——与 {@link McpServerLinkConfig} 的缺省一致。 */
  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

  private final String name;
  private final McpServerLinkConfig config;
  private McpSyncClient client;
  private volatile List<AgentTool> cached = List.of();
  private final CopyOnWriteArrayList<Runnable> changeListeners = new CopyOnWriteArrayList<>();
  private volatile boolean disconnected;

  /**
   * 远程侧是否已不可用。
   *
   * <p>触发点：{@link #callRemote} 捕获到传输层失败或请求超时（同步客户端对远程进程死亡的直接表象 即超时，无法区分“进程死亡”与“呼叫挂起”，故一并标记）。
   * 复位时机：{@link #connect()} 成功建立新连接时。是否重连以及如何重连由接线层决定（本类只标记）。
   */
  public boolean isDisconnected() {
    return disconnected;
  }

  public McpToolSource(McpServerLinkConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.name = config.name();
  }

  /** 接管形态：只声明源名，连接经 {@link #connect(McpClientTransport)} 注入已建好的传输。 */
  public McpToolSource(String name) {
    this.config = null;
    this.name = Objects.requireNonNull(name, "name");
  }

  /** 源名（日志与审计用）。 */
  public String name() {
    return name;
  }

  /** 拉起子进程并完成 MCP 握手；幂等（已连接则直接返回）。 */
  public synchronized void connect() {
    if (client != null) {
      return;
    }
    if (config == null) {
      throw new IllegalStateException("接管形态的 MCP 工具源只能经 connect(McpClientTransport) 连接");
    }
    ServerParameters parameters =
        ServerParameters.builder(config.command()).args(config.args()).env(config.env()).build();
    StdioClientTransport transport =
        new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
    connect(
        transport, config.startupTimeout(), config.requestTimeout(), () -> disconnected = false);
  }

  /**
   * 以注入的传输握手（接管形态入口；W3b 子 Agent 进程用父进程建好的管道流传输）。
   *
   * <p>幂等：已连接则直接返回；成功后复位断连标记（初始基线目录立即回放给订阅者）。
   */
  public synchronized void connect(McpClientTransport transport) {
    if (client != null) {
      return;
    }
    if (transport == null) {
      throw new IllegalArgumentException("传输不能为空");
    }
    connect(
        transport, DEFAULT_STARTUP_TIMEOUT, DEFAULT_REQUEST_TIMEOUT, () -> disconnected = false);
  }

  private void connect(
      McpClientTransport transport,
      Duration startupTimeout,
      Duration requestTimeout,
      Runnable onConnected) {
    McpSyncClient newClient =
        McpClient.sync(transport)
            .requestTimeout(requestTimeout)
            .initializationTimeout(startupTimeout)
            .toolsChangeConsumer(this::onServerToolsChanged)
            .build();
    newClient.initialize();
    this.client = newClient;
    // 初始基线：tools/list_changed 之外的首次快照（若有订阅者立即回放）
    cacheAndNotify(newClient.listTools().tools());
    // 重连成功（connect 是唯一建立连接的入口）：复位断连标记
    onConnected.run();
  }

  /** {@inheritDoc}：ToolSource 唯一标识 = 源名（与 {@link #name()} 同值）。 */
  @Override
  public String id() {
    return name;
  }

  /**
   * 订阅工具目录变化。回调无参数：消费方经 {@link #listTools()} 重读缓存快照（连接前订阅 → connect 尾基线触发；连接后订阅
   * → 不回放当前快照，初始状态由消费方主动读取）。线程：可能在 SDK 通知线程回调，实现须快速返回、勿阻塞。
   */
  @Override
  public AutoCloseable onChange(Runnable listener) {
    Objects.requireNonNull(listener, "listener");
    changeListeners.add(listener);
    return () -> changeListeners.remove(listener);
  }

  /**
   * 当前 MCP server 的工具目录快照（映射为 AgentTool 列表，name 去重保序）。
   *
   * <p>{@inheritDoc}：快照语义——返回缓存而非活查询。缓存于 {@link #connect()} 成功时建立基线，之后由 {@code
   * tools/list_changed} 刷新；未连接时返回空列表。
   */
  @Override
  public synchronized List<AgentTool> listTools() {
    return cached;
  }

  private void onServerToolsChanged(List<McpSchema.Tool> tools) {
    cacheAndNotify(tools);
  }

  private void cacheAndNotify(List<McpSchema.Tool> tools) {
    List<AgentTool> mapped = mapAll(tools);
    this.cached = mapped;
    for (Runnable listener : changeListeners) {
      listener.run();
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
    McpSchema.CallToolResult result;
    try {
      result =
          connected.callTool(new McpSchema.CallToolRequest(name, Map.copyOf(arguments), Map.of()));
    } catch (RuntimeException e) {
      // 契约：IO/远程错误映射为 ToolResult，不得穿透 AgentTool.execute——仅远程侧失败额外标记断连
      Throwable remoteFailure = findRemoteFailure(e);
      if (remoteFailure != null) {
        this.disconnected = true;
      }
      return ToolResult.error(McpWireCode.MCP_TOOL_ERROR, remoteFailureMessage(remoteFailure, e));
    }
    return map(result);
  }

  /**
   * 沿 cause 链识别“远程侧不可用”类失败：传输层异常或 {@link TimeoutException}（同步客户端把请求超时经 reactor
   * 包装后抛出，故须沿链识别）。本地代码错误（如参数 NPE）不在此列：映射错误结果但不标记断连。
   */
  private static Throwable findRemoteFailure(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof McpTransportException
          || t instanceof McpTransportSessionClosedException
          || t instanceof McpTransportSessionNotFoundException
          || t instanceof TimeoutException) {
        return t;
      }
    }
    return null;
  }

  /** 失败描述：超时用中文短语；异常消息为空时以类名兜底。 */
  private static String remoteFailureMessage(Throwable remoteFailure, Throwable e) {
    Throwable source = remoteFailure != null ? remoteFailure : e;
    if (source instanceof TimeoutException) {
      return "MCP 远程调用超时";
    }
    String detail = source.getMessage() != null ? source.getMessage() : source.toString();
    return "MCP 远程调用失败: " + detail;
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
