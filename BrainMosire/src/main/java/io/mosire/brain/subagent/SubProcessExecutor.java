package io.mosire.brain.subagent;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.mcp.AgentToMcpServer;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.proc.ManagedProcess;
import io.mosire.agentlib.proc.SpawnSpec;
import io.mosire.agentlib.proc.SubprocessException;
import io.mosire.agentlib.proc.SubprocessManager;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认执行体：同 jar {@code agent} 子命令的真实子进程（计划 §4.4 SubProcessExecutor）。
 *
 * <p><strong>两种形态</strong>：
 *
 * <ul>
 *   <li>W3a 基础形态（{@link #SubProcessExecutor(SubprocessManager, AgentCommand)}）：只起进程，无协议层；
 *   <li>W3b 链接形态（{@link #SubProcessExecutor(SubprocessManager, AgentCommand, ToolRegistry, String,
 *       String)}）：子进程 stdin/stdout 被父侧接管为 MCP stdio 链接（{@code protocolStdout=true}，stdout
 *       泵让位），父侧为每个子 Agent 起一个 {@link AgentToMcpServer}，以<b>子 Agent 自身的权限集</b>调用 父级工具（红线 R7：不经此默认
 *       GUEST/unrestricted；调用者身份=子体身份，父级 SYSTEM 权限不外借）；<b>链接上下文的 {@code ToolContext.config} 只补
 *       {@code read_agent_context} 需要的三键</b>（见 {@link #linkToolConfig}——不补则子体读谁都是 {@code
 *       CONTEXT_UNAVAILABLE}，"读侧同判"（V4）根本走不到判定之后那一步）。
 * </ul>
 *
 * <p>进程级生命周期全委托 {@link SubprocessManager}（红线 5：进程句柄与管道归进程装配层持有， 本类只是委托入口，不外泄任何流/句柄给工具层）；启动命令按 Rul C
 * 由装配层注入 {@link AgentCommand}（Brain 不做文件系统假设）。三层关停（关 stdin → SIGTERM → 宽限等待 → 树级 SIGKILL）由 {@link
 * ManagedProcess#stopGracefully} 承担，本类经 {@link LaunchedSubagent} 窄缝转发；链接形态先关父侧 MCP
 * 服务再关进程（协议先停摆，子进程经 EOF 感知关停信号自然收尾）。
 */
public final class SubProcessExecutor implements AgentExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(SubProcessExecutor.class);

  private final SubprocessManager processes;
  private final AgentCommand command;

  /** 非 null = W3b 链接形态（父侧暴露的工具注册表 + server 名/版本）。 */
  private final ToolRegistry parentTools;

  private final String serverName;
  private final String serverVersion;

  /** 父侧 MCP server 的工具调用入口（S4-B2：装配层注入，缺省 {@link ToolCallAuthorizer#standard()}）。 */
  private final ToolCallAuthorizer authorizer;

  public SubProcessExecutor(SubprocessManager processes, AgentCommand command) {
    this(processes, command, null, null, null);
  }

  /**
   * W3b 链接形态：每个子 Agent 启动时在父侧挂一个 MCP server（stdio，读子 stdout / 写子 stdin）， 暴露父级工具、以子体权限调用。
   *
   * <p>前置：{@code command.parentLink()} 必须为 true（子体 argv 需带 {@code --parent-link} 才会走 MCP client
   * 形态；二者不同步会静默产生无协议客户端的空链接——这里宁可启动前就失败）。
   *
   * <p>EI_EXPOSE_REP2 抑制：{@code parentTools} 为装配层按实例共享的<b>实时</b>工具注册表（R7 语义——子体调用的父级工具面，
   * 工具随后随时可能注册进来，必须持有引用而非快照）；本类只读使用、不对外暴露——与 {@code AgentRuntime.registry()} 同类设计先例。
   * 抑制落在<b>真正持有引用</b>的那个构造器（下面这个 6 参终态构造）上——标注挂在只做委托的重载上会被 SpotBugs 判为无用抑制 （{@code
   * US_USELESS_SUPPRESSION_ON_METHOD}），而真正的暴露点反而漏报。
   */
  public SubProcessExecutor(
      SubprocessManager processes,
      AgentCommand command,
      ToolRegistry parentTools,
      String serverName,
      String serverVersion) {
    this(processes, command, parentTools, serverName, serverVersion, ToolCallAuthorizer.standard());
  }

  /**
   * W3b 链接形态 + <b>自定义工具调用入口</b>（S4-B2）：除最后一个参数外与上一构造逐字节相同。
   *
   * <p>为什么父侧每个子 Agent 的 MCP server 也要接审批入口：子体经 MCP 调<b>父级工具</b>时，判定必须与父级管线走同一条路（S4 的
   * "判定点统一"）——否则"需人审批"的父级工具在子体这条路上可以绕过审批直接执行。缺省 {@link ToolCallAuthorizer#standard()} （=
   * 无编排器，{@code Ask} fail-closed 拒）⇒ 既有行为逐字不变。
   *
   * <p>{@code authorizer} 由装配层注入（{@code App} 传装了审批编排器的那个）；本类不认识审批/登记表，只透传。
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public SubProcessExecutor(
      SubprocessManager processes,
      AgentCommand command,
      ToolRegistry parentTools,
      String serverName,
      String serverVersion,
      ToolCallAuthorizer authorizer) {
    this.processes = Objects.requireNonNull(processes, "processes");
    this.command = Objects.requireNonNull(command, "command");
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    if (parentTools != null) {
      if (!command.parentLink()) {
        throw new IllegalArgumentException(
            "链接形态的 AgentCommand 必须 parentLink=true——子体重定向走 --parent-link");
      }
      this.parentTools = parentTools;
      this.serverName = Objects.requireNonNull(serverName, "serverName");
      this.serverVersion = Objects.requireNonNull(serverVersion, "serverVersion");
    } else {
      this.parentTools = null;
      this.serverName = null;
      this.serverVersion = null;
    }
  }

  /**
   * <b>L2 起点固定</b>（S5-D）：子 JVM 的出生目录 = 它的 {@code fs} 可达面首根（<b>不改 argv</b>，只改 {@code
   * SpawnSpec.workingDir}）。
   *
   * <p><b>这不是围栏</b>（设计 §2.7 的措辞）：它只让"相对路径"默认落在允许区里，子 JVM 自身的代码不受任何限制。围栏在 L1（参数面硬拒）与 L3（{@link
   * io.mosire.brain.tools.BashSandbox}）。
   *
   * <p>根不存在/不可用 ⇒ 回落到"继承父进程 CWD"并记日志：起点不是安全边界，不许因为一个没建好的目录把子体启动拦死。可达面为"不限"时 没有根列表（{@code dirList()}
   * 为空）⇒ 同样是继承。
   */
  private static Path startDirectory(SubagentInstance instance) {
    List<Path> roots = instance.permissions().fsScope().dirList();
    for (Path root : roots) {
      if (Files.isDirectory(root)) {
        return root;
      }
    }
    if (!roots.isEmpty()) {
      LOG.warn("子 Agent({}) 的可达面根都不可用，出生目录回落到父进程 CWD: {}", instance.instanceId(), roots);
    }
    return null;
  }

  /**
   * 链接上下文的工具配置（D27 的读侧接线）：<b>只补 {@code read_agent_context} 需要的三键</b>（ {@link
   * AgentContextReader#CONFIG_SUBAGENTS_ROOT}/{@link
   * AgentContextReader#CONFIG_SELF_EVENTS_DB}/{@link AgentContextReader#CONFIG_SELF_AGENT_ID}）。
   *
   * <p><b>为什么与 {@link AgentCommand#argv} 同源</b>：子体的事件库在哪，由父侧交给它的 {@code --data-dir} 决定——{@code
   * argv} 写的是 {@code <childDataDir>/<实例 id>}（装配层侧 = {@code
   * <数据根>/subagents}），读侧就按同一处取，<b>不给第二份真相</b>： 自身库 = {@code <childDataDir>/<实例
   * id>/events.db}；{@code read_agent_context} 的子库根 = {@code
   * childDataDir}（实例的数据目录一律由<b>本进程</b>这个根派生——子体的 {@code spawn_sub_agent} 经父侧编排执行，孙代也在同一个根下落库， 与
   * {@code SubagentOrchestrationTools.childDbPath} 的 {@code <root>/<id>/events.db} 口径逐字对齐）。
   *
   * <p><b>边界（不许含糊）</b>：这里<b>只</b>有这三键，补的是"子体读得到"。bash 三件套等其它工具的配置<b>不</b>在此注入——它们在这条缝上仍拿到空 config ⇒
   * 各自的缺省口径（{@code BashToolConfig.fromToolConfig} 对空表有安全缺省；<b>但</b>部署侧配的 {@code tools.bash.*}
   * 附加项对子体不生效——见本轮报告，属未修的独立缝）。
   *
   * <p>{@code childDataDir == null}（装配层没注入数据根）⇒ 返回空表，逐字回到本缝接线前的行为：<b>不编路径</b>，读侧拿到的是 {@code
   * CONTEXT_UNAVAILABLE}（响亮），不是一份猜出来的库。
   */
  Map<String, Object> linkToolConfig(SubagentInstance instance) {
    if (command.childDataDir() == null) {
      return Map.of();
    }
    Path root = Path.of(command.childDataDir());
    String instanceId = instance.instanceId();
    return Map.of(
        AgentContextReader.CONFIG_SUBAGENTS_ROOT,
        root.toString(),
        AgentContextReader.CONFIG_SELF_EVENTS_DB,
        root.resolve(instanceId).resolve("events.db").toString(),
        AgentContextReader.CONFIG_SELF_AGENT_ID,
        instanceId);
  }

  @Override
  public LaunchedSubagent launch(SubagentInstance instance) {
    List<String> argv = command.argv(instance);
    SpawnSpec spec =
        new SpawnSpec(
            argv.get(0),
            argv.subList(1, argv.size()),
            Map.of(),
            startDirectory(instance),
            SpawnSpec.DEFAULT_MAX_OUTPUT_BYTES,
            parentTools != null);
    ManagedProcess managed;
    try {
      managed = processes.spawn(spec);
    } catch (SubprocessException e) {
      throw new SubagentLaunchException("子 Agent 子进程启动失败: " + e.getMessage(), e);
    }
    if (parentTools == null) {
      return new ManagedHandle(managed);
    }
    AgentToMcpServer server =
        AgentToMcpServer.start(
            parentTools,
            serverName,
            serverVersion,
            new ToolContext(
                instance.permissions().grantedToken(),
                // 红线 R7：父侧调用上下文 = 子体自身权限集（非父级 SYSTEM/unrestricted）
                instance.permissions(),
                // D27 读侧接线：read_agent_context 的三键（与 argv 同源，见 linkToolConfig）；
                // 其它工具配置不在此注入——各自缺省口径。
                linkToolConfig(instance),
                Map.of(),
                // S6：子体的<b>实例身份 + 命令档位 + 目标</b>绑在这条链上（绑一次，链上每次调用都取它）。
                // 这是子体身份的唯一来源：模型给不出、子进程自己报不了——它决定"命令闸工具按哪一档分流"，
                // 以及审批请求里的 requesterId/goal（上级判定据此回答"谁在问、被派去干什么"）。
                AgentIdentity.subagent(
                    instance.instanceId(), instance.mode(), instance.goal(), instance.depth())),
            authorizer,
            managed.stdout().orElseThrow(() -> new SubagentLaunchException("子 Agent stdout 不可用")),
            managed.stdin().orElseThrow(() -> new SubagentLaunchException("子 Agent stdin 不可用")));
    return new LinkedHandle(managed, server);
  }

  /** 关闭自身资源：无（SubprocessManager 归装配层持有并统一关停——红线 5；本类不拥有子进程）。 幂等。 */
  @Override
  public void close() {}

  /** 诊断文本上限（字符）：一条崩栈能到几十 KB，截尾保留——"最后一次说什么"比"从头说"有用。 */
  static final int EXIT_DIAGNOSTICS_MAX_CHARS = 600;

  /**
   * 异常退出的诊断文本（S5-E）：<b>正常退出 / 仍在运行 / 拿不到退出码 ⇒ 空</b>。
   *
   * <p>两个句柄共用这一段（生产走 {@link LinkedHandle}）：如果只在基础形态的句柄上实现，真进程链接形态就会又一次 "崩了没声音"——那正是本方法要消除的形态。
   *
   * <p>非零才是诊断对象：子体完成任务后退出 0 是正常终局，不该刷日志；把它当异常会让"安静"变成噪声，噪声里再出问题就没人看了。
   */
  static Optional<String> exitDiagnosticsOf(ManagedProcess managed) {
    if (managed.isAlive()) {
      return Optional.empty();
    }
    OptionalInt code = managed.exitValue();
    if (code.isEmpty() || code.getAsInt() == 0) {
      return Optional.empty();
    }
    String tail = String.join(" | ", managed.outputTail()).strip();
    if (tail.length() > EXIT_DIAGNOSTICS_MAX_CHARS) {
      tail = "…" + tail.substring(tail.length() - EXIT_DIAGNOSTICS_MAX_CHARS);
    }
    return Optional.of(
        "exitCode=" + code.getAsInt() + (tail.isEmpty() ? "（子进程没有输出）" : "；末段输出: " + tail));
  }

  /** 真进程句柄的窄缝适配：存亡/终止全数委托 {@link ManagedProcess}。 */
  private static final class ManagedHandle implements LaunchedSubagent {

    private final ManagedProcess managed;

    ManagedHandle(ManagedProcess managed) {
      this.managed = managed;
    }

    @Override
    public boolean isAlive() {
      return managed.isAlive();
    }

    @Override
    public Optional<String> exitDiagnostics() {
      return exitDiagnosticsOf(managed);
    }

    @Override
    public void close() {
      // 三层关停标准入口（宽限 10s——SubprocessManager.DEFAULT_GRACE，与 SubprocessManager.close 同源）
      managed.stopGracefully(SubprocessManager.DEFAULT_GRACE);
    }
  }

  /** 链接形态句柄：先关父侧 MCP server（协议停摆），再走标准三层关停；幂等（子进程已死时 server 关闭无副作用）。 */
  private static final class LinkedHandle implements LaunchedSubagent {

    private final ManagedProcess managed;
    private final AgentToMcpServer server;
    private volatile boolean closed;

    LinkedHandle(ManagedProcess managed, AgentToMcpServer server) {
      this.managed = managed;
      this.server = server;
    }

    @Override
    public boolean isAlive() {
      return managed.isAlive();
    }

    @Override
    public Optional<String> exitDiagnostics() {
      return exitDiagnosticsOf(managed);
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        server.close();
      } catch (RuntimeException e) {
        LOG.warn("父侧子 Agent MCP 服务关闭异常 pid={}", managed.pid(), e);
      }
      managed.stopGracefully(SubprocessManager.DEFAULT_GRACE);
    }
  }
}
