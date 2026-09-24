package io.mosire.agentlib.mcp;

import com.sun.net.httpserver.HttpServer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.agentlib.llm.ToolAssetResolver;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 *   <li>带 {@link ToolSpec#noExport()} 的工具<b>永不进入暴露面</b>（D30：读权力不外包给子体；判定见 {@code
 *       exportable}，初始注册与增量同步同源）；
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

  /**
   * 工具调用唯一入口（S4 判定点统一）：{@code tools/call} 的执行都经它，与 Brain 管线同一条路（理由见 {@link ToolCallAuthorizer}
   * 类注释）。默认 = {@link ToolCallAuthorizer#standard()}（仅三要素判定）。
   */
  private final ToolCallAuthorizer authorizer;

  /**
   * 资产解析 SPI：{@link ToolResult#assetDocIds()} 指向的<b>图片</b>资产会在结果里以 {@link McpSchema.ImageContent}
   * 出站（MCP 客户端的模型据此"看图"）。{@link ToolAssetResolver#none()} = 不认任何资产（图片引用被跳过，文本结果照旧）。
   */
  private final ToolAssetResolver assetResolver;

  /** 空实现兜底：在 startWith 的赋值前任何 close() 都是安全的（UwF 告警解除）。 */
  private AutoCloseable registrySubscription = () -> {};

  /**
   * 本实例挂载的 {@link HttpServer}（HTTP 形态才有；stdio 形态保持 {@code null}）。caller-owned 与 owned 共用：{@link
   * #boundPort()} 与 {@link #close()} 都读它区分"有没有 HTTP 面"。
   */
  private HttpServer httpServer;

  /** 本实例注册的 HTTP context 路径（{@link #close()} 时 {@code removeContext}）；stdio 形态为 {@code null}。 */
  private String httpPath;

  /**
   * owned 形态自建的 executor（caller-owned 形态保持 {@code null}）。
   *
   * <p><b>为什么用"非 null"而不是独立布尔表示 owned</b>：owned 的两个资源（server 与 executor）总是成对出现，少一个字段就少一处
   * "忘了同步"的机会——{@link #close()} 只看它决定要不要 {@code stop}/{@code shutdown}。
   *
   * <p><b>硬约束</b>：caller-owned 形态下这个字段恒为 null，故 {@code close()} 绝不 {@code shutdown} 调用方的线程池 （见设计文档
   * §二：{@code HttpServer.stop} 不关用户传进来的 executor，顺手关它会静默拆掉调用方共享的池）。
   */
  private ExecutorService ownedExecutor;

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
    return start(registry, serverName, serverVersion, caller, ToolCallAuthorizer.standard());
  }

  /**
   * 启动（自定义<b>工具调用入口</b>）：除 {@code authorizer} 外语义与 4 参重载相同。
   *
   * <p>经 MCP 到达的 {@code tools/call} 一律经该 authorizer 执行（{@link #handleCall}）——这是 S4 的"判定点统一"： MCP
   * 路径过去<b>直接调 {@code tool.execute}、连 guard 都不经</b>，与管线路径各判各的。
   *
   * @param authorizer 工具调用唯一入口（审批闸等横切判定都在其中）
   */
  public static AgentToMcpServer start(
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      ToolCallAuthorizer authorizer) {
    return startWith(
        registry,
        serverName,
        serverVersion,
        caller,
        new StdioServerTransportProvider(McpJsonDefaults.getMapper()),
        name -> true,
        authorizer);
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
    return start(
        registry, serverName, serverVersion, caller, ToolCallAuthorizer.standard(), in, out);
  }

  /**
   * 以给定的流对启动 + 自定义工具调用入口（W3b 父侧桥接的生产形态）。
   *
   * @param authorizer 工具调用唯一入口（见 {@link #start(ToolRegistry, String, String, ToolContext,
   *     ToolCallAuthorizer)}）
   */
  public static AgentToMcpServer start(
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      ToolCallAuthorizer authorizer,
      java.io.InputStream in,
      java.io.OutputStream out) {
    return startWith(
        registry,
        serverName,
        serverVersion,
        caller,
        new StdioServerTransportProvider(McpJsonDefaults.getMapper(), in, out),
        name -> true,
        authorizer);
  }

  /**
   * 启动（注入单会话/stdio 族 transport 的最小形态）：语义与 {@link #start(ToolRegistry, String, String, ToolContext)}
   * 相同，只是底层 transport 由调用方提供（如进程内管道流对）。
   *
   * <p><b>正式 API 契约</b>（三个 stdio 族 {@code startWith} 重载共用；以下逐条为准）：
   *
   * <ol>
   *   <li><b>调用者身份在建链时绑定</b>：{@code caller} 逐字决定 {@code tools/call} 的执行身份——MCP 客户端只能决定 {@code
   *       arguments}，<b>给不出也改不了</b>"这是哪个 Agent、按哪一档办"（身份由建链方注入，见 {@code handleCall} 的上下文拼装）；
   *   <li><b>暴露过滤器</b>：本重载默认全量（{@code name -> true}）。带 {@code include} 的重载只暴露命中者，初始注册与 Registry
   *       变更同步（{@code sync}）<b>同源</b>使用该谓词；谓词在 server 生命周期内应保持<b>纯函数</b>（其判定会被初始注册与每次同步反复取用）；
   *   <li><b>{@code tools/call} 的唯一入口</b>：一切调用经 {@link ToolCallAuthorizer}（审批闸、资源判定等横切判定都在其中，S4
   *       判定点统一），本重载默认 {@link ToolCallAuthorizer#standard()}；
   *   <li><b>动态同步承诺</b>：订阅 {@link ToolRegistry#onChange}，Registry 的增删会增量同步到暴露面；带 {@link
   *       ToolSpec#noExport()} 的工具<b>永不外发</b>（初始注册与增量同步同一暴露判定）；
   *   <li><b>参数类型限于单会话/stdio 族</b>：形参为 {@link McpServerTransportProvider}。streamable HTTP
   *       provider（{@link McpStreamableServerTransportProvider}）是它的<b>兄弟</b>接口，请走 streamable 注入口（后续
   *       {@code startHttp}）；若某实现类同时实现两者而 误传到这里，抛可读 {@link IllegalArgumentException}（守卫见方法体首部），绝不泄漏
   *       {@link ClassCastException}。
   * </ol>
   *
   * @param registry 待暴露的工具注册表（持有引用，后续变更实时同步）
   * @param serverName MCP server 自报名称（{@code serverInfo}）
   * @param serverVersion MCP server 自报版本
   * @param caller 经 MCP 到达的 {@code tools/call} 所使用的执行身份（建链时绑定，客户端改不了）
   * @param transport 单会话/stdio 族 transport（如 {@code StdioServerTransportProvider}）
   * @since 0.1.0
   */
  public static AgentToMcpServer startWith(
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      McpServerTransportProvider transport) {
    return startWith(registry, serverName, serverVersion, caller, transport, name -> true);
  }

  /**
   * 启动（注入单会话/stdio 族 transport + 暴露过滤器）：语义与 {@link #startWith(ToolRegistry, String, String,
   * ToolContext, McpServerTransportProvider)} 相同，仅初始注册与后续同步均以 {@code include} 收窄暴露面。
   *
   * <p>契约要点（完整版见 5 参重载）：①{@code caller} 身份在建链时绑定，MCP 客户端给不出也改不了；②{@code include}
   * 命中才暴露，谓词应为纯函数；③{@code tools/call} 一律经默认 {@link ToolCallAuthorizer#standard()}（唯一入口）； ④订阅 {@link
   * ToolRegistry#onChange}，增删增量同步，{@link ToolSpec#noExport()} 的工具永不外发；⑤形参限单会话/stdio 族
   * transport，streamable HTTP provider 误传抛 {@link IllegalArgumentException}。
   *
   * @param include 工具名过滤器（命中才暴露；谓词在 server 生命周期内应保持纯函数）
   * @since 0.1.0
   */
  public static AgentToMcpServer startWith(
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      McpServerTransportProvider transport,
      Predicate<String> include) {
    return startWith(
        registry,
        serverName,
        serverVersion,
        caller,
        transport,
        include,
        ToolCallAuthorizer.standard());
  }

  /**
   * 启动（注入单会话/stdio 族 transport + 暴露过滤器 + 自定义工具调用入口）：全参形态，前两者的语义分别同上两个重载；{@code authorizer} 决定
   * {@code tools/call} 的执行语义。
   *
   * <p>契约要点（完整版见 5 参重载）：①{@code caller} 身份在建链时绑定，MCP 客户端给不出也改不了；②{@code include}
   * 命中才暴露，谓词应为纯函数；③{@code authorizer} 是 {@code tools/call} 的<b>唯一入口</b>（审批闸等横切判定都在其中）； ④订阅 {@link
   * ToolRegistry#onChange}，增删增量同步，{@link ToolSpec#noExport()} 的工具永不外发；⑤形参限单会话/stdio 族
   * transport，streamable HTTP provider 误传抛 {@link IllegalArgumentException}。
   *
   * @param authorizer 工具调用唯一入口（审批闸等横切判定都在其中）
   * @since 0.1.0
   */
  public static AgentToMcpServer startWith(
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      McpServerTransportProvider transport,
      Predicate<String> include,
      ToolCallAuthorizer authorizer) {
    // 兄弟接口误传守卫：抛可读中文 IllegalArgumentException，绝不让 ClassCastException 从 SDK 内部泄漏（改造前强转的隐患）。
    if (transport instanceof McpStreamableServerTransportProvider) {
      throw new IllegalArgumentException(
          "该重载只接受单会话/stdio 族 transport（McpServerTransportProvider）；streamable HTTP provider 请用 streamable 注入口");
    }
    return assemble(
        McpServer.sync(transport),
        "stdio",
        registry,
        serverName,
        serverVersion,
        caller,
        include,
        authorizer,
        ToolAssetResolver.none());
  }

  /** streamable HTTP 注入口（供后续 HTTP 传输接线）；语义与 stdio 家族相同，仅底层 transport 不同。 */
  static AgentToMcpServer startWith(
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      McpStreamableServerTransportProvider transport) {
    return startWith(registry, serverName, serverVersion, caller, transport, name -> true);
  }

  /** streamable 注入口（带暴露过滤器）；语义与 stdio 家族的 6 参重载相同，仅底层 transport 不同。 */
  static AgentToMcpServer startWith(
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      McpStreamableServerTransportProvider transport,
      Predicate<String> include) {
    return startWith(
        registry,
        serverName,
        serverVersion,
        caller,
        transport,
        include,
        ToolCallAuthorizer.standard());
  }

  /**
   * streamable HTTP 全参注入口：注入 transport、暴露过滤器与工具调用入口；注册循环 / Registry 订阅 / 外发面日志与 stdio
   * 家族<b>共用同一段</b> 实现（见 {@link #assemble}）。
   */
  static AgentToMcpServer startWith(
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      McpStreamableServerTransportProvider transport,
      Predicate<String> include,
      ToolCallAuthorizer authorizer) {
    return startWith(
        registry,
        serverName,
        serverVersion,
        caller,
        transport,
        include,
        authorizer,
        ToolAssetResolver.none());
  }

  /**
   * streamable HTTP 全参注入口 + 资产解析 SPI（图片资产以 {@code ImageContent} 出站；语义见 {@link ToolAssetResolver}）。
   */
  static AgentToMcpServer startWith(
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      McpStreamableServerTransportProvider transport,
      Predicate<String> include,
      ToolCallAuthorizer authorizer,
      ToolAssetResolver assetResolver) {
    return assemble(
        McpServer.sync(transport),
        "streamable",
        registry,
        serverName,
        serverVersion,
        caller,
        include,
        authorizer,
        assetResolver);
  }

  /**
   * 启动（HTTP，<b>caller-owned</b>）：把本 server 挂到<b>调用方持有</b>的 {@link HttpServer} 上（需求书点名的形态）。
   *
   * <p><b>生命周期所有权</b>：本方法<b>不</b> {@code start()}、<b>不</b> {@code stop()} 传入的 server，也<b>不</b> 关它的
   * executor——{@link #close()} <b>只</b>做两件事：取消 Registry 订阅、{@code removeContext(path)}。调用方在 {@code
   * close()} 之后仍可继续用同一台 server 服务它自己注册的其它 context（判别性用例见 {@code AgentToMcpServerStartHttpTest} 的
   * caller-owned 一组）。<b>为什么这条必须写死</b>：{@code HttpServer.stop(int)} 只停服务、不会关用户传进来的 {@code
   * Executor}；实现里若顺手 {@code shutdown()} 会静默拆掉调用方共享的线程池（表现是调用方其它模块的请求突然全部挂起），
   * 且这种失败没有任何用例会红——除非像本类这样把所有权显式分开。
   *
   * <p><b>executor 告警</b>：若该 server 未设置自定义 executor（{@link HttpServer#getExecutor()} 为 {@code
   * null}）， 记 {@code WARN}——默认 executor 是<b>单线程</b>的，将来 GET 监听流停驻（parked）时会与后续 POST 互锁。调用方应在传入前
   * {@code setExecutor(Executors.newVirtualThreadPerTaskExecutor())}。
   *
   * <p><b>装配顺序硬约束</b>：先 {@code McpServer.sync(provider).build()}（SDK 在 build 时注入 {@code
   * sessionFactory}），再 {@code createContext(path, provider::handle)}——顺序反了的话首个 {@code initialize}
   * 会撞 null factory（provider 回 500）。本方法内部已按此顺序，调用方只需保证 server 在客户端连接前已 {@code start()}；server 已
   * start 后再 {@code createContext} 同样可用。
   *
   * <p><b>authorizer 形参必填</b>：本入口<b>不</b>提供缺省，<b>不得</b>静默退化为 {@link ToolCallAuthorizer#standard()}。
   * 理由：{@code standard()} 不带 {@link io.mosire.agentlib.approval.ApprovalCoordinator}，遇到工具自报的 {@link
   * io.mosire.agentlib.approval.ToolGate.Ask} 会 fail-closed 直接 {@code APPROVAL_DENIED}——这是对的
   * fail-closed， 但把它当成"网络暴露面"的隐式默认，等于让审批路径在外部面上静默失效（外部调用者只会看到"审批被拒"，而不是"你根本没装配审批"）。 要审批就显式装配带
   * coordinator 的 authorizer；不审批也要显式选一个（哪怕就是 {@code standard()}）。
   *
   * @param server 调用方持有并已（或即将）{@code start()} 的 HTTP server；本类不接管其生命周期
   * @param path MCP 端点路径（如 {@code /mcp}）；{@code createContext} 是前缀匹配，provider 内部做精确守卫
   * @param registry 待暴露的工具注册表（持有引用，后续变更实时同步）
   * @param serverName MCP server 自报名称
   * @param serverVersion MCP server 自报版本
   * @param caller 经 MCP 到达的 {@code tools/call} 所使用的执行身份（建链时绑定，客户端改不了）
   * @param authorizer 工具调用唯一入口（<b>必填</b>，见上文；审批闸等横切判定都在其中）
   * @since 0.1.0
   */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "createContext 抛错时先把已 build 的 SDK server 与 Registry 订阅收干净，再原样重抛调用方本会看到的异常（"
              + "不改变调用方既有 catch 语义）；显式重抛 RuntimeException 是此处设计意图，非缺陷。")
  public static AgentToMcpServer startHttp(
      HttpServer server,
      String path,
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      ToolCallAuthorizer authorizer) {
    Objects.requireNonNull(server, "server");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(authorizer, "authorizer");
    if (server.getExecutor() == null) {
      LOG.warn(
          "caller-owned HttpServer 未设置自定义 executor：默认单线程 executor 会与 parked GET 监听流互锁，"
              + "请在传入前 setExecutor(Executors.newVirtualThreadPerTaskExecutor())。path={}",
          path);
    }
    JdkHttpStreamableServerTransportProvider provider =
        new JdkHttpStreamableServerTransportProvider(path);
    // 顺序硬约束：先 build（SDK 在 build 时注入 sessionFactory），再 createContext。
    AgentToMcpServer self =
        startWith(registry, serverName, serverVersion, caller, provider, name -> true, authorizer);
    try {
      server.createContext(path, provider::handle);
    } catch (RuntimeException e) {
      // createContext 失败：已 build 的 SDK server 与 Registry 订阅会泄漏，先收干净再抛。
      self.close();
      throw e;
    }
    self.httpServer = server;
    self.httpPath = path;
    return self;
  }

  /**
   * 启动（HTTP，<b>owned</b>）：自建 {@link HttpServer} 与虚拟线程 executor 并绑定 {@code host:port}，生命周期归本对象。
   *
   * <p><b>生命周期所有权</b>：{@link #close()} 依序 ①取消 Registry 订阅 → ②{@code server.close()}（级联 provider
   * {@code closeGracefully()}，逐个关会话）→ ③{@code HttpServer.stop(0)} → ④{@code
   * executor.shutdown()}。②→③ 顺序不能颠倒：先让 provider 把每个会话的 listening stream 有序收掉，再停 server；反了会把停驻线程强断在
   * {@code latch.await()} 上，SSE 客户端看到裸断连。
   *
   * <p><b>host 非回环 = 显式 opt-in + 响亮告警</b>：默认由便捷重载绑 {@code 127.0.0.1}。显式传入非回环 host 本身<b>就是</b>
   * opt-in（调用方必须点名），但本面<b>无鉴权、无 TLS</b>——任何能连到该地址的人都能列举并调用工具、读取并替人答复审批。故非回环时记 {@code
   * WARN}，把风险说在启动日志里，而不是让人以为"默认就是安全的"。要对外提供服务必须先有鉴权，那是另一个包的事。
   *
   * <p><b>authorizer 形参必填</b>：同 {@link #startHttp(HttpServer, String, ToolRegistry, String, String,
   * ToolContext, ToolCallAuthorizer)}，不得静默退化为 {@link ToolCallAuthorizer#standard()}。
   *
   * @param host 绑定地址（非回环须显式点名并接受无鉴权风险）
   * @param port 端口；{@code 0} = 由系统分配，实际端口用 {@link #boundPort()} 读回
   * @param path MCP 端点路径（如 {@code /mcp}）
   * @param registry 待暴露的工具注册表（持有引用，后续变更实时同步）
   * @param serverName MCP server 自报名称
   * @param serverVersion MCP server 自报版本
   * @param caller 经 MCP 到达的 {@code tools/call} 所使用的执行身份（建链时绑定，客户端改不了）
   * @param authorizer 工具调用唯一入口（<b>必填</b>）
   * @since 0.1.0
   */
  public static AgentToMcpServer startHttp(
      String host,
      int port,
      String path,
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      ToolCallAuthorizer authorizer) {
    return startHttp(
        host,
        port,
        path,
        registry,
        serverName,
        serverVersion,
        caller,
        authorizer,
        ToolAssetResolver.none());
  }

  /**
   * 同 {@link #startHttp(String, int, String, ToolRegistry, String, String, ToolContext,
   * ToolCallAuthorizer)}，另注入<b>资产解析 SPI</b>：工具结果里 {@link ToolResult#assetDocIds()} 指向的图片资产 会在
   * {@code tools/call} 结果里以 {@code ImageContent} 出站（外部 agent 的模型据此"看图"）。
   *
   * @param assetResolver 资产解析 SPI（{@link ToolAssetResolver#none()} = 不认任何资产，只出文本）
   * @since 0.1.0
   */
  public static AgentToMcpServer startHttp(
      String host,
      int port,
      String path,
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      ToolCallAuthorizer authorizer,
      ToolAssetResolver assetResolver) {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(authorizer, "authorizer");
    Objects.requireNonNull(assetResolver, "assetResolver");
    if (!isLoopback(host)) {
      LOG.warn(
          "MCP HTTP 面绑定非回环地址 {}（无鉴权、无 TLS）：任何能连到该地址的人都能列举并调用工具。" + "仅在你已在本机/网络层另有隔离时这样做。path={}",
          host,
          path);
    }
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress(host, port), 0);
    } catch (IOException e) {
      executor.shutdownNow();
      throw new IllegalStateException("MCP HTTP 面启动失败 host=" + host + " port=" + port, e);
    }
    server.setExecutor(executor);
    try {
      JdkHttpStreamableServerTransportProvider provider =
          new JdkHttpStreamableServerTransportProvider(path);
      AgentToMcpServer self =
          startWith(
              registry,
              serverName,
              serverVersion,
              caller,
              provider,
              name -> true,
              authorizer,
              assetResolver);
      server.createContext(path, provider::handle);
      server.start();
      self.httpServer = server;
      self.httpPath = path;
      self.ownedExecutor = executor;
      return self;
    } catch (RuntimeException e) {
      // 装配半途失败：把自建资源收干净，不留半死的监听端口/线程池
      server.stop(0);
      executor.shutdownNow();
      throw new IllegalStateException("MCP HTTP 面装配失败 host=" + host + " port=" + port, e);
    }
  }

  /**
   * 启动（HTTP，<b>owned</b>，便捷重载）：host 缺省 {@code 127.0.0.1}（回环，无鉴权基线下的安全缺省）；其余语义见 {@link
   * #startHttp(String, int, String, ToolRegistry, String, String, ToolContext,
   * ToolCallAuthorizer)}。
   *
   * @param port 端口；{@code 0} = 由系统分配
   * @param path MCP 端点路径
   * @since 0.1.0
   */
  public static AgentToMcpServer startHttp(
      int port,
      String path,
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      ToolCallAuthorizer authorizer) {
    return startHttp(
        "127.0.0.1", port, path, registry, serverName, serverVersion, caller, authorizer);
  }

  /** 绑定地址是否回环：{@code localhost} 与解析出的回环地址为真；解析不了按非回环处理（宁多告警不漏）。 */
  private static boolean isLoopback(String host) {
    if ("localhost".equalsIgnoreCase(host)) {
      return true;
    }
    try {
      return InetAddress.getByName(host).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }

  /**
   * 两条 transport 路径<b>共用</b>的装配段：{@code serverInfo} → 逐工具 {@code toolCall} → {@code build}，再挂
   * Registry 订阅、按实际外发面记日志。
   *
   * <p>为什么通配 {@code SyncSpecification<?>} 就够：{@code serverInfo}/{@code toolCall} 都返回基类 {@code
   * SyncSpecification<S>}（不是自型 {@code S}），{@code build()} 签名也不含 {@code S}，故无需自型泛型参数；这也让 streamable
   * 与单会话 两条链能收进同一个 helper，避免复制粘贴。
   */
  private static AgentToMcpServer assemble(
      McpServer.SyncSpecification<?> spec,
      String transportLabel,
      ToolRegistry registry,
      String serverName,
      String serverVersion,
      ToolContext caller,
      Predicate<String> include,
      ToolCallAuthorizer authorizer,
      ToolAssetResolver assetResolver) {
    Objects.requireNonNull(include, "include");
    Objects.requireNonNull(authorizer, "authorizer");
    Objects.requireNonNull(assetResolver, "assetResolver");
    // 显式宣告 tools.listChanged=true（本计划唯一被批准的行为变更）：不设则走 SDK 自动派生路径，
    // ToolCapabilities.listChanged 为 null，addTool/removeTool 拆箱 NPE 被 ToolRegistry 的 catch 吞掉，
    // notifications/tools/list_changed 永不触达。只设 tools 位，logging 由 McpAsyncServer 构造补。
    spec.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());
    spec.serverInfo(serverName, serverVersion);
    for (AgentTool tool : registry.list()) {
      if (!exportable(tool, include)) {
        continue;
      }
      spec.toolCall(
          toMcpTool(tool),
          (exchange, request) -> handleCall(registry, caller, authorizer, assetResolver, request));
    }
    McpSyncServer server = spec.build();
    AgentToMcpServer self =
        new AgentToMcpServer(registry, caller, server, include, authorizer, assetResolver);
    // 先建对象再订阅（lambda 捕获 self）；订阅前错过的变化由 sync 的幂等 diff 兜底
    self.registrySubscription = registry.onChange(self::sync);
    // 日志口径 = **实际外发面**（{@code server.listTools()}，与客户端 tools/list 同源），不是 {@code registry.size()}：
    // 后者把被 include 挡下与标了 noExport 的工具也算进去，读日志的人会以为它们外发了（2026-09-12 使用模式实测踩到）。
    List<String> exported = server.listTools().stream().map(McpSchema.Tool::name).toList();
    List<String> withheld =
        registry.list().stream().map(AgentTool::name).filter(n -> !exported.contains(n)).toList();
    LOG.info(
        "MCP {} server 已启动: name={} version={} 外发工具 {} 个 {}；Registry 共 {} 个，未外发 {} 个 {}",
        transportLabel,
        serverName,
        serverVersion,
        exported.size(),
        exported,
        registry.size(),
        withheld.size(),
        withheld);
    return self;
  }

  private AgentToMcpServer(
      ToolRegistry registry,
      ToolContext caller,
      McpSyncServer server,
      Predicate<String> include,
      ToolCallAuthorizer authorizer,
      ToolAssetResolver assetResolver) {
    this.registry = registry;
    this.caller = caller;
    this.server = server;
    this.include = include;
    this.authorizer = authorizer;
    this.assetResolver = assetResolver;
  }

  /** 增量同步：以 Registry 为准，增删 diff（幂等，供 onChange 与手动调用）；可外发且命中的才在暴露面内。 */
  private void sync() {
    List<AgentTool> tools = registry.list();
    Set<String> desired =
        tools.stream()
            .filter(tool -> exportable(tool, include))
            .map(AgentTool::name)
            .collect(Collectors.toSet());
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
      if (exportable(tool, include) && !stillThere.contains(tool.name())) {
        server.addTool(new McpServerFeatures.SyncToolSpecification(toMcpTool(tool), this::handle));
        LOG.debug("MCP 工具注册: {}", tool.name());
      }
    }
  }

  /**
   * 暴露判定（初始注册与增量同步<b>唯一</b>的取法）= 名过滤器命中 <b>且</b> 工具未标"禁外发"。
   *
   * <p>{@link ToolSpec#noExport()} 是 D30 的安全必需：桥接把整个 Registry 交给子体，靠调用点逐个记得传 {@code include}
   * 过滤器会漏（新增工具时没人提醒），标在工具自己身上则是结构性的——子体拿不到句柄 = 读权力不外包。 判定与 {@link ToolRegistry}
   * 无关，纯函数，故初始注册与增量同步不会分叉。
   */
  private static boolean exportable(AgentTool tool, Predicate<String> include) {
    ToolSpec spec = tool.spec() == null ? ToolSpec.DEFAULT : tool.spec();
    return include.test(tool.name()) && !spec.noExport();
  }

  private McpSchema.CallToolResult handle(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
    return handleCall(registry, caller, authorizer, assetResolver, request);
  }

  /**
   * {@code tools/call} 的执行路径：<b>一切经 {@link ToolCallAuthorizer}</b>（S4 判定点统一）。
   *
   * <p>此前这里是裸的 {@code tool.execute(context)}——权限判定完全缺席（工具名级白名单/三要素位在这条路上从不生效）， 各系统级工具只能在自己的 execute
   * 里补一道"第二道闸"。改为经入口后，{@link ToolSpec} 的四个字段（身份级别 / 敏感 / 破坏 / 禁外发中的前三个）在这条路上<b>第一次真正参与判定</b>，且与
   * Brain 管线同一套规则。
   */
  private static McpSchema.CallToolResult handleCall(
      ToolRegistry registry,
      ToolContext caller,
      ToolCallAuthorizer authorizer,
      ToolAssetResolver assetResolver,
      McpSchema.CallToolRequest request) {
    if (registry.find(request.name()).isEmpty()) {
      return error("工具不存在: " + request.name());
    }
    // 参数与上下文拼装：MCP 客户端可传任意参数，ToolContext.arguments 为调用白名单来源（M3 细化）
    // 身份逐字来自链接上的 caller 模板（S6）：**MCP 客户端给不出也改不了**——它只能决定 arguments，
    // 而"这是哪个 Agent、按哪一档办"由建链方绑定（子 Agent 链接 = 子实例身份 + 子档位；外部面 = external-mcp）。
    ToolContext context =
        new ToolContext(
            caller.caller(),
            caller.permissions(),
            caller.config(),
            request.arguments() == null ? Map.of() : request.arguments(),
            caller.identity());
    return toCallToolResult(authorizer.execute(registry, request.name(), context), assetResolver);
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
   *
   * <p>缺省不带资产解析器：等价于 {@link #toCallToolResult(ToolResult, ToolAssetResolver)} 传 {@link
   * ToolAssetResolver#none()}——图片资产被跳过（文本结果照旧），供只需文本面的调用方/测试用。
   */
  static McpSchema.CallToolResult toCallToolResult(ToolResult result) {
    return toCallToolResult(result, ToolAssetResolver.none());
  }

  /**
   * ToolResult → CallToolResult（<b>文本 + 可选图片</b>）：文本内容永远在（{@link McpWireCode} 带内码）， 另把 {@link
   * ToolResult#assetDocIds()} 里能解析成 {@code image/*} 的资产各追加一个 {@link McpSchema.ImageContent} ——MCP
   * 客户端的模型据此"看图"（这是"世界渲染成图喂给 LLM"在 MCP 面的出口）。
   *
   * <p>★ 三条边界说清：① 非图片资产（如溢出的文本 docId）<b>不</b>出图片块，它们由别的面消费；② 解析不到的 id 记 {@code WARN}
   * 并跳过——**文本结果仍然有效**，若在这里硬失败会让"图片暂不可用"升级成"工具调用失败"（工件过期是常态，见 {@link ToolAssetResolver}）；③
   * 失败结果（{@code isError}）只出文本——错误面不该夹带图片。
   */
  static McpSchema.CallToolResult toCallToolResult(
      ToolResult result, ToolAssetResolver assetResolver) {
    List<McpSchema.Content> contents = new ArrayList<>();
    contents.add(new McpSchema.TextContent(McpWireCode.encode(result)));
    if (result.success()) {
      for (String assetId : result.assetDocIds()) {
        ToolAsset asset = assetResolver.resolve(assetId).orElse(null);
        if (asset == null) {
          LOG.warn("工具结果的资产引用解析不到，图片块已跳过: {}", assetId);
          continue;
        }
        if (!asset.mediaType().startsWith("image/")) {
          continue;
        }
        contents.add(
            new McpSchema.ImageContent(
                null, Base64.getEncoder().encodeToString(asset.bytes()), asset.mediaType()));
      }
    }
    return McpSchema.CallToolResult.builder(contents).isError(!result.success()).build();
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

  /**
   * 本实例挂载的 HTTP server 实际绑定端口（{@code port=0} 时由系统分配，装配层取它记日志/回传）；stdio 形态没有端口。
   *
   * @throws IllegalStateException 本实例不是 HTTP 形态
   */
  public int boundPort() {
    HttpServer server = httpServer;
    if (server == null) {
      throw new IllegalStateException("本实例不是 HTTP 形态，没有端口（stdio）");
    }
    return server.getAddress().getPort();
  }

  /**
   * 关闭本 server 并按其<b>所有权形态</b>收尾（次序见设计文档 §二）。
   *
   * <p>两条形态共有的头两步：①取消 Registry 订阅 → ②{@code server.close()}（级联 provider {@code closeGracefully()}，让
   * parked GET 监听流有序收尾）。之后按形态分岔：
   *
   * <ul>
   *   <li><b>caller-owned</b>（{@code ownedExecutor == null}）：只 {@code
   *       removeContext(path)}，<b>永不</b> {@code stop()} 调用方的 server、<b>永不</b> {@code shutdown()}
   *       调用方的 executor；
   *   <li><b>owned</b>：{@code HttpServer.stop(0)} + {@code executor.shutdown()}。②必须在 {@code
   *       stop(0)} 之前——先 stop 会把停驻线程强断在 latch 上，SSE 客户端看到裸断连。
   * </ul>
   */
  @Override
  public void close() {
    try {
      registrySubscription.close();
    } catch (Exception e) {
      LOG.warn("取消 Registry 订阅失败", e);
    }
    HttpServer http = httpServer;
    ExecutorService executor = ownedExecutor;
    try {
      server.close();
    } finally {
      // server.close() 抛错也不能跳过收尾，否则端口/线程池泄漏；次序仍是 removeContext → stop → shutdown。
      if (http != null) {
        try {
          http.removeContext(httpPath);
        } catch (RuntimeException e) {
          LOG.warn("移除 MCP context 失败: path={}", httpPath, e);
        }
        if (executor != null) {
          http.stop(0);
          executor.shutdown();
        }
      }
    }
  }
}
