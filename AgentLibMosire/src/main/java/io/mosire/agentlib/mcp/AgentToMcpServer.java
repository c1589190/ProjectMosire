package io.mosire.agentlib.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把本地 {@link ToolRegistry} 暴露为 MCP stdio server（计划 §3.2 的 AgentToMcpServer；决策 D4：子 Agent 进程即此形态）。
 *
 * <p>调用方（主 Agent / 外部客户端）以标准 MCP 客户端连接本进程 stdin/stdout：握手 → {@code tools/list} → {@code
 * tools/call}。 与 Registry 的映射规则：
 *
 * <ul>
 *   <li>启动时枚举 Registry 现有工具（在 transport 启动前注册，避免客户端首二次 {@code listTools} 时缺工具）；
 *   <li>Registry 变更（{@link ToolRegistry#onChange}）增量 addTool/removeTool——SDK 自动通知 {@code
 *       notifications/tools/list_changed}；
 *   <li>工具校验失败（执行中）按 {@code isError} 返回，不抛异常。
 * </ul>
 *
 * <p>调用者身份：M2 最小口径——未携带身份信息前一律按 {@link AccessToken#GUEST} 构造 {@link ToolContext}（不超权）；
 * 工具自身的三要素级别校验由运行侧 {@code ToolExecutionGuard} 负责（管道内），本类只做 MCP 语义映射，不重复判断； 带身份的调用（子 Agent 配 MCP
 * 凭据）在 M3 权限接线细化。
 */
public final class AgentToMcpServer implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(AgentToMcpServer.class);

  private final ToolRegistry registry;
  private final ToolContext caller;
  private final McpSyncServer server;

  /** 暴露过滤器：命中才暴露（默认全量）。注册后修改无效——以 server 启动时的实例为准。 */
  private final Predicate<String> include;

  /** 空实现兜底：在 startWith 的赋值前任何 close() 都是安全的（UwF 告警解除）。 */
  private AutoCloseable registrySubscription = () -> {};

  /**
   * 以默认 GUEST 调用身份启动（标准 stdio）。
   *
   * @param registry 待暴露的工具注册表（持有引用，后续变更实时同步）
   * @param serverName MCP server 自报名称（{@code serverInfo}，如 "mosire-main"）
   * @param serverVersion MCP server 自报版本
   */
  public static AgentToMcpServer start(
      ToolRegistry registry, String serverName, String serverVersion) {
    return start(
        registry,
        serverName,
        serverVersion,
        ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST)));
  }

  /**
   * 启动（可定制调用者上下文，测试/未来带身份调用用）。
   *
   * <p>实现：先枚举 Registry 快照注册初始工具，再 build 启动 transport——两阶段之间 Registry 变化的增量由 onChange 订阅兜底（sync 以
   * server 实况为基准做 diff，天然幂等）。
   *
   * <p><b>权限风险</b>：{@code caller} 的权限集决定经 MCP 暴露工具的执行身份（{@code tools/call} 即以该身份构造 {@link
   * ToolContext} 过运行侧校验）。M3 接线时子 Agent 进程必须传入自身实际权限集；对外暴露面必须用收窄的 guest 只读集， 禁止照抄三参重载的默认 GUEST +
   * unrestricted 组合。
   */
  public static AgentToMcpServer start(
      ToolRegistry registry, String serverName, String serverVersion, ToolContext caller) {
    return startWith(
        registry,
        serverName,
        serverVersion,
        caller,
        new StdioServerTransportProvider(McpJsonDefaults.getMapper()));
  }

  /**
   * 启动（带暴露过滤器）：只把 {@code include} 命中的 Registry 工具暴露给 MCP 客户端， 初始注册与后续 Registry 变更同步（{@code
   * sync}）均按此过滤； 未命中的工具既不新增，也已在暴露面内则被移除。
   *
   * <p>不传本重载的既有 {@code start} 系列保持全量暴露（默认 {@code name -> true}），行为不变。
   *
   * @param include 工具名过滤器（命中才暴露；谓词在 server 生命周期内应保持纯函数）
   */
  public static AgentToMcpServer start(
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      Predicate<String> include) {
    return startWith(
        registry,
        serverName,
        serverVersion,
        caller,
        new StdioServerTransportProvider(McpJsonDefaults.getMapper()),
        include);
  }

  /**
   * 以给定的流对启动（W3b 父子 stdio 链接的父侧：子进程的 stdIn/stdout 已被父进程接管，本 server 直接 在注入的流上服务，不复用 3 参重载的独立 spawn
   * 流程）。语义与 {@link #start(ToolRegistry, String, String, ToolContext)} 相同。
   *
   * @param caller 经 MCP 暴露的工具在 {@code tools/call} 时使用的执行身份（W3b 子侧必须传子 Agent 自身的 权限集，红线 R7：子体不能沿父级
   *     SYSTEM 权限执行）
   */
  public static AgentToMcpServer start(
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      java.io.InputStream in,
      java.io.OutputStream out) {
    return startWith(
        registry,
        serverName,
        serverVersion,
        caller,
        new StdioServerTransportProvider(McpJsonDefaults.getMapper(), in, out));
  }

  /** 供测试注入自定义 transport（如管道流）；语义与默认 stdio 相同。 */
  static AgentToMcpServer startWith(
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      McpServerTransportProvider transport) {
    return startWith(registry, serverName, serverVersion, caller, transport, name -> true);
  }

  /**
   * 供测试注入自定义 transport 并带暴露过滤器；语义与 {@link #startWith(ToolRegistry, String, String, ToolContext,
   * McpServerTransportProvider)} 相同，仅初始注册与后续同步均以 {@code include} 收窄暴露面。
   */
  static AgentToMcpServer startWith(
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      McpServerTransportProvider transport,
      Predicate<String> include) {
    Objects.requireNonNull(include, "include");
    // sync() 声明返回 Self 型链（serverInfo/toolCall 声明为 SyncSpecification<S>），末端缩窄为 S 实例
    McpServer.SingleSessionSyncSpecification spec =
        (McpServer.SingleSessionSyncSpecification)
            McpServer.sync(transport).serverInfo(serverName, serverVersion);
    for (AgentTool tool : registry.list()) {
      if (!include.test(tool.name())) {
        continue;
      }
      spec.toolCall(toMcpTool(tool), (exchange, request) -> handleCall(registry, caller, request));
    }
    McpSyncServer server = spec.build();
    AgentToMcpServer self = new AgentToMcpServer(registry, caller, server, include);
    // 先建对象再订阅（lambda 捕获 self）；订阅前错过的变化由 sync 的幂等 diff 兜底
    self.registrySubscription = registry.onChange(self::sync);
    LOG.info(
        "MCP stdio server 已启动: name={} version={} 初始工具 {} 个",
        serverName,
        serverVersion,
        registry.size());
    return self;
  }

  private AgentToMcpServer(
      ToolRegistry registry, ToolContext caller, McpSyncServer server, Predicate<String> include) {
    this.registry = registry;
    this.caller = caller;
    this.server = server;
    this.include = include;
  }

  /** 增量同步：以 Registry 为准，增删 diff（幂等，供 onChange 与手动调用）；过滤器命中的才在暴露面内。 */
  private void sync() {
    List<AgentTool> tools = registry.list();
    Set<String> desired =
        tools.stream().map(AgentTool::name).filter(include).collect(Collectors.toSet());
    List<String> existing = server.listTools().stream().map(McpSchema.Tool::name).toList();
    for (String name : existing) {
      if (!desired.contains(name)) {
        server.removeTool(name);
        LOG.debug("MCP 工具移除: {}", name);
      }
    }
    Set<String> stillThere =
        server.listTools().stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
    for (AgentTool tool : tools) {
      if (include.test(tool.name()) && !stillThere.contains(tool.name())) {
        server.addTool(new McpServerFeatures.SyncToolSpecification(toMcpTool(tool), this::handle));
        LOG.debug("MCP 工具注册: {}", tool.name());
      }
    }
  }

  private McpSchema.CallToolResult handle(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    return handleCall(registry, caller, request);
  }

  private static McpSchema.CallToolResult handleCall(
      ToolRegistry registry, ToolContext caller, McpSchema.CallToolRequest request) {
    AgentTool tool = registry.find(request.name()).orElse(null);
    if (tool == null) {
      return error("工具不存在: " + request.name());
    }
    // 参数与上下文拼装：MCP 客户端可传任意参数，ToolContext.arguments 为调用白名单来源（M3 细化）
    ToolContext context =
        new ToolContext(
            caller.caller(),
            caller.permissions(),
            caller.config(),
            request.arguments() == null ? Map.of() : request.arguments());
    return toCallToolResult(tool.execute(context));
  }

  /** AgentTool → MCP Tool（schema 直传；null 值由 SDK 序列化容忍——参考 McpToolAdapter 的反向说明）。 */
  static McpSchema.Tool toMcpTool(AgentTool tool) {
    return McpSchema.Tool.builder(tool.name(), tool.jsonSchema())
        .description(tool.description())
        .build();
  }

  /**
   * ToolResult → CallToolResult（文本内容；isError 映射保持与 {@link McpToolSource#map} 一致）。 错误码经 {@link
   * McpWireCode} 带内透传（MCP 协议本身没有码字段，此约定仅 mosire 间生效）。
   */
  static McpSchema.CallToolResult toCallToolResult(ToolResult result) {
    McpSchema.TextContent content = new McpSchema.TextContent(McpWireCode.encode(result));
    return McpSchema.CallToolResult.builder(List.of(content)).isError(!result.success()).build();
  }

  private static McpSchema.CallToolResult error(String message) {
    return McpSchema.CallToolResult.builder(List.of(new McpSchema.TextContent(message)))
        .isError(true)
        .build();
  }

  /** 当前暴露的工具目录（测试与审计用）。 */
  public List<String> toolNames() {
    return server.listTools().stream().map(McpSchema.Tool::name).toList();
  }

  @Override
  public void close() {
    try {
      registrySubscription.close();
    } catch (Exception e) {
      LOG.warn("取消 Registry 订阅失败", e);
    }
    server.close();
  }
}
