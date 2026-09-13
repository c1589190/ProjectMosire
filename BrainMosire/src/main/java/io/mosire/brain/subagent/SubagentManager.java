package io.mosire.brain.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.proc.SubprocessManager;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.EventTypes;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子 Agent 编排根（计划 §4.4 / 中期计划 W3 组件 2）：spawn / 查询 / kill 的单一入口，状态机、权限 单调性（红线 1）、档位单调性（S6）、
 * 深度与繁殖预算（{@link SubagentLimits}，S5-A）、防跑飞 cap 派生与事件链全部在本类闭环。
 *
 * <p><strong>状态机</strong>：{@code
 * CONFIGURED→SPAWNING→RUNNING→(TERMINATING)→(FINISHED|FAILED|KILLED)}， 每次合法推进发一条 {@code
 * agent.lifecycle} 事件（correlationId = 子 Agent 实例 id）；非法推进直接抛 {@link
 * IllegalStateException}（"非法推进直接抛错而非静默漂移"——SubagentStatus 设计约定）。崩溃恢复 （M3）= 事件重放，本期只落事件、不写恢复逻辑。
 *
 * <p><strong>spawn 检查顺序</strong>（中期计划 W3 + D27 的 I1 与血缘）：模板存在 → <b>{@code A1}
 * 工具越界</b>（D27：请求面点名了调用者没有的工具 ⇒ {@link SubagentRejectedException} + {@code permission.denied} 事件，见
 * {@link #checkRequestedToolsWithinCaller}）→ <b>工作目录越界</b>（S5-B，请求面要了调用者够不着的地方 ⇒ {@link
 * WorkingDirDeniedException} + {@code permission.denied} 事件）→ 权限单调性 （{@link
 * PermissionChecker#isSubset}，拒绝即 {@link SubagentRejectedException} + {@code permission.denied}
 * 事件）→ 档位单调性（S6，{@code decision} 无事件、拒绝走 {@code permission.denied}）→ 深度（{@code 调用者depth+1 > 子深度上限}
 * 拒绝，{@code decision:DEPTH_LIMIT} 事件）→〔锁内〕繁殖预算（{@code decision:BUDGET_EXHAUSTED} 事件）→ launcher
 * 启动。cap 派生： {@code min(模板, 请求收紧, 父级)}——子级只能更小（D10 硬顶的"单调性第四维度"）。
 *
 * <p><strong>血缘（D27）</strong>：每个实例在派发那一刻被写上 {@code lineagePath}（{@code main/a1/a1-1}，见 {@link
 * #lineagePathOf}），判定读它（{@link ContextAccessJudge}）。它是<b>事实记录</b>而不是守卫输入—— 父解析不出时记空串、照常派发，由判定面对空串
 * fail-closed。
 *
 * <p><strong>S5-A 起三个守卫的对照物是"调用者"</strong>（{@link #spawn(SubagentLaunchRequest, AgentIdentity,
 * AgentPermissionSet)}）：权限对调用者权限集、深度对调用者深度 + 1、档位对调用者档位。manager 的构造期 {@code
 * parentPermissions/parentDepth} 只服务于 3 参 {@code spawn}（= "主 Agent 在要"）。
 *
 * <p><strong>kill</strong>（红线 5：kill 是 Manager 的编排入口，不是"工具关进程"）：RUNNING 一步转为 TERMINATING（事件）→ 经
 * {@link SubagentLauncher} 窄缝关闭（真实实现即三层关停：关 stdin → SIGTERM → 宽限 → 树级 SIGKILL）→ 终态确认 →
 * KILLED（事件）；kill-before-running 按 {@link SubagentStatus#FAILED} 语义收束。终结后幂等（重复 kill 是空操作）。Manager
 * 不持有 {@link SubprocessManager}——它归装配层（Main） 持有。
 *
 * <p><strong>退出观测（§2.3 起重写）</strong>：{@link LaunchedSubagent} 只暴露 {@code isAlive()/close()}、诊断面
 * {@code exitDiagnostics()} 与退出码 {@code exitCode()}（窄缝不泄漏 onExit），本类以一个每实例虚拟线程做轮询桥接 ({@value
 * #EXIT_POLL_MILLIS}ms)，把"已退出"事实投递给唯一收束点 {@link #onChildExited}。<b>判据是子库终局事件的有无</b>（子体在 {@code
 * chat()} 返回后先落库再退栈，见 {@code SubagentProcessMain}）而不是退出码——被杀的子体照常 {@code return 0}，码答不出"为什么停"：
 * 有终局记录 ⇒ FINISHED + stopReason（哪怕此刻在 TERMINATING）；无记录 ⇒ RUNNING 记 FAILED（{@code 未留下终局记录} + 退出码）、
 * TERMINATING 记 KILLED、SPAWNING 记 FAILED（既有口径）。管理核心仍单线程语义： 所有状态转移在同一把私有锁下原子完成，观测线程只投递"已退出"事实。
 *
 * <p><strong>事件面</strong>：生命周期事件 {@code agent=childId, correlationId=childId}；拒绝类事件 {@code agent=父级
 * id, correlationId=拟生成的子实例 id}（查询可串起"尝试+转移"全程）。
 */
public final class SubagentManager implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(SubagentManager.class);

  /** 退出观测轮询间隔（毫秒）。 */
  private static final long EXIT_POLL_MILLIS = 100L;

  /**
   * kill 终态确认宽限（进程三层关停内已等过一次，这里只做收尾确认）。
   *
   * <p>§2.6 起也供 {@code kill_sub_agent} 工具复用：工具在 {@code kill()} 之外只补"这一段预算的剩余部分"（见 {@code
   * SubagentOrchestrationTools#kill}），所以总时延不叠加。
   */
  public static final long KILL_CONFIRM_MILLIS = SubprocessManager.DEFAULT_GRACE.toMillis();

  /** 子库终局记录的扫描上限（倒序取最近若干条 {@code agent.lifecycle}——终局记录必在尾部）。 */
  private static final int TERMINAL_SCAN_LIMIT = 50;

  /** 子库终局事件的动作名（设计 §2.1：{@code agent.lifecycle/action=finished}）——父侧判定的唯一判据。 */
  private static final String TERMINAL_ACTION = "finished";

  /** 子库里的终局记录缺失时给模型面的原因（规则 2，设计 §2.3 逐字）。 */
  static final String NO_TERMINAL_RECORD_REASON = "未留下终局记录";

  /** 子体在确认存活之前就退出时给模型面的原因（规则 4，既有口径逐字保留）。 */
  static final String EXIT_BEFORE_RUNNING_REASON = "subagent 退出于确认存活之前";

  private static final ObjectMapper JSON = new ObjectMapper();

  private final AgentTemplateStore templateStore;
  private final SubagentLauncher launcher;
  private final EventStore events;
  private final EventBus bus;
  private final AgentConfig parentConfig;
  private final AgentPermissionSet parentPermissions;
  private final Supplier<CommandMode> parentMode;
  private final int parentDepth;
  private final SubagentLimits limits;

  /**
   * 子 Agent 数据根（{@code <dataDir>/subagents}）——父侧读<b>子库终局记录</b>的路径源（设计 §2.3 的判据）。
   *
   * <p>与 {@link AgentCommand#childDataDir()} 同源（子体的 {@code --data-dir} 就是它底下的 {@code <实例 id>}），
   * 路径再由本类拼成 {@code <root>/<实例id>/events.db}——拼装规则只有一份（{@link #childDbPath(Path, String)}，工具面
   * {@code read_agent_context} 也调它），与 {@code AgentContextReader} 的口径逐字一致（不给第二份真相）。
   *
   * <p><b>{@code null} = 装配层没注入</b>（既有夹具/离线形态）⇒ 读不到任何终局记录 ⇒ 一律按"未留下终局记录"处理（fail-closed：宁可
   * 报异常终局，也不假装子体交代过）。生产装配（{@code App.wireSubagents}）必注入。
   */
  private final Path childDataRoot;

  /** 私有锁：状态记录/事件顺序/句柄表全部在锁内原子推进（同 SubprocessManager/USO 理由）。 */
  private final Object lock = new Object();

  private final Map<String, SubagentInstance> instances = new HashMap<>();
  private final Map<String, LaunchedSubagent> handles = new HashMap<>();
  private final Set<Thread> watchers = new HashSet<>();

  /** 父侧观测到的终局（设计 §2.4 的内存面；父库事件是同一份事实的持久面）。终态不受理新增，单调写入。 */
  private final Map<String, TerminalOutcome> outcomes = new HashMap<>();

  private volatile boolean closed;

  /**
   * @param templateStore 模板库（spawn 前须完成 {@link AgentTemplateStore#load()}）
   * @param launcher 启动缝（装配层注入：{@link SubProcessExecutor} / {@link InProcessExecutor}）
   * @param events 事件存储（每状态推进立即落库——崩溃恢复的重放来源）
   * @param bus 进程内事件总线（旁路通知，与 AgentRuntime 的 emit 同径）
   * @param parentConfig 父级（主 Agent）运行配置——cap 派生上限来源（D10）
   * @param parentPermissions 父级权限集——单调性对照（红线 1）
   * @param parentDepth 父级深度（主 Agent = 0；子 Agent 层级 +1）
   */
  public SubagentManager(
      AgentTemplateStore templateStore,
      SubagentLauncher launcher,
      EventStore events,
      EventBus bus,
      AgentConfig parentConfig,
      AgentPermissionSet parentPermissions,
      int parentDepth) {
    this(
        templateStore,
        launcher,
        events,
        bus,
        parentConfig,
        parentPermissions,
        parentDepth,
        () -> CommandMode.FULL);
  }

  /**
   * 全参装配 + <b>父级档位取数缝</b>（S6）。
   *
   * @param parentMode 父级当前档位的取数缝（现读：主 Agent 的档位经 HTTP 断点可改，派生守卫要按<b>当刻</b>档位判）。 缺省 {@code () ->
   *     CommandMode.FULL} = "没有档位这个概念"时的行为（子体拿不到比 LIMITED 更宽的档，见 {@link #spawn}）
   */
  public SubagentManager(
      AgentTemplateStore templateStore,
      SubagentLauncher launcher,
      EventStore events,
      EventBus bus,
      AgentConfig parentConfig,
      AgentPermissionSet parentPermissions,
      int parentDepth,
      Supplier<CommandMode> parentMode) {
    this(
        templateStore,
        launcher,
        events,
        bus,
        parentConfig,
        parentPermissions,
        parentDepth,
        parentMode,
        SubagentLimits.defaults());
  }

  /**
   * 全参装配 + <b>繁殖预算</b>（S5-A / D28）。
   *
   * @param limits 深度/直系/全局三个额度（见 {@link SubagentLimits}）；缺省 {@link SubagentLimits#defaults()}
   */
  public SubagentManager(
      AgentTemplateStore templateStore,
      SubagentLauncher launcher,
      EventStore events,
      EventBus bus,
      AgentConfig parentConfig,
      AgentPermissionSet parentPermissions,
      int parentDepth,
      Supplier<CommandMode> parentMode,
      SubagentLimits limits) {
    this(
        templateStore,
        launcher,
        events,
        bus,
        parentConfig,
        parentPermissions,
        parentDepth,
        parentMode,
        limits,
        null);
  }

  /**
   * 全参装配 + <b>子库根</b>（设计 §2.3 的终局判据）。
   *
   * @param childDataRoot 子 Agent 数据根（每实例取其 {@code <root>/<实例 id>/events.db}，与 {@link
   *     AgentCommand#childDataDir()} 同源）。{@code null} = 未注入 ⇒ 读不到终局记录 ⇒ 一律按"未留下终局记录"
   *     处理（fail-closed，不假装子体交代过）；生产装配必注入（{@code App.wireSubagents}）
   */
  public SubagentManager(
      AgentTemplateStore templateStore,
      SubagentLauncher launcher,
      EventStore events,
      EventBus bus,
      AgentConfig parentConfig,
      AgentPermissionSet parentPermissions,
      int parentDepth,
      Supplier<CommandMode> parentMode,
      SubagentLimits limits,
      Path childDataRoot) {
    this.childDataRoot = childDataRoot;
    this.limits = Objects.requireNonNull(limits, "limits");
    this.templateStore = Objects.requireNonNull(templateStore, "templateStore");
    this.launcher = Objects.requireNonNull(launcher, "launcher");
    this.events = Objects.requireNonNull(events, "events");
    this.bus = Objects.requireNonNull(bus, "bus");
    this.parentConfig = Objects.requireNonNull(parentConfig, "parentConfig");
    this.parentPermissions = Objects.requireNonNull(parentPermissions, "parentPermissions");
    this.parentMode = Objects.requireNonNull(parentMode, "parentMode");
    if (parentDepth < 0) {
      throw new IllegalArgumentException("parentDepth 不能为负: " + parentDepth);
    }
    this.parentDepth = parentDepth;
  }

  /**
   * 拉起一个子 Agent（同步到 RUNNING；执行体真正的运行时长由 launcher/退出观测接续推进）。
   *
   * <p>检查顺序（中期计划 W3 + D27 的 I1）：模板存在 → {@code A1} 工具越界（请求面点名调用者没有的工具 ⇒ 派发即拒） → 权限单调性 → 深度限制 → cap
   * 派生 → 状态机 CONFIGURED→SPAWNING→RUNNING。启动失败（{@link SubagentLaunchException}）把状态推进到 FAILED 并
   * 原样传播（WIP 异常 Javadoc）。
   *
   * <p><b>S5-A 起"谁在要"进入判定</b>：本 3 参形态 = "主 Agent 在要"（口径逐字等于本段之前的行为——身份取 {@code
   * parentConfig.id()}、权限对照取构造期的 {@code parentPermissions}、深度取构造期的 {@code parentDepth}）。 经 MCP
   * 链接进来的下级调用走 {@link #spawn(SubagentLaunchRequest, AgentIdentity, AgentPermissionSet)}：那一支的对照物是
   * <b>调用者自己</b>，而不是这个 manager 属于谁。
   *
   * <p><b>可达性前提（防呆）</b>：本重载给自己造的身份是 {@code parentConfig.id()}——只有它等于 {@link AgentIdentity#MAIN_ID}
   * 时，派出的子体才有可解析的父 path（{@code lineagePath = "main/<id>"}）。装配层若把别的 id 塞进 {@code parentConfig}（既不是
   * {@code main}，也不在本 manager 的实例表里），子体照常派发但 {@code lineagePath} 记空串 ⇒ 任何工具面都够不着它 （判定④/⑤
   * fail-closed，见 {@link #lineagePathOf}），只剩 {@link #kill(String)}/{@link #close()} 的关停面能收。生产此重载零调用
   * （主 Agent 经编排工具走 3 参那一支），保留它只为兼容既有夹具与"manager 自己就是主 Agent"的老装配。
   *
   * @return RUNNING 时的实例快照（此后记录以 manager 内 map 为准）
   * @throws SubagentRejectedException 模板不存在 / 权限越权（+{@code permission.denied} 事件）/ 深度超限 （+{@code
   *     decision:DEPTH_LIMIT} 事件）
   * @throws SubagentBudgetExceededException 繁殖预算耗尽（+{@code decision:BUDGET_EXHAUSTED}
   *     事件，消息含需求/剩余/上限）
   * @throws SubagentLaunchException launcher 无法拉起子进程（+lifecycle FAILED 事件）
   */
  public SubagentInstance spawn(SubagentLaunchRequest request) {
    CommandMode mode = parentMode.get();
    AgentIdentity self =
        new AgentIdentity(
            parentConfig.id(), mode == null ? CommandMode.FULL : mode, "", parentDepth);
    return spawn(request, self, parentPermissions);
  }

  /**
   * 以<b>调用者身份</b>派生一个子 Agent（S5-A 的锚点修正）。
   *
   * <p><b>三个守卫的对照物全部换成 {@code caller}</b>——权限单调性对 {@code callerPermissions}、深度上限对 {@code
   * caller.depth() + 1}、档位单调性对 {@code caller.mode()}。此前它们对照的是 manager 的构造期常量（{@code
   * parentPermissions}/{@code parentDepth}/父档位缝），只有"调用者恰好就是那个父"时才等价：两跳以上会把孙代拿去和 <b>主 Agent</b>
   * 比，于是"孙 ⊆ 子"的形状对、对象错（S4 登记的构造期常量缺陷）。
   *
   * <p><b>预算在锁内判</b>：额度数的是在管子实例，"先数后建"若不在同一临界区里，并发两次 spawn 可以双双通过。
   *
   * <p><b>资源可达面（S5-B）也锚在 {@code callerPermissions} 上</b>：子体的 {@code fs} 面 = 调用者 ∩ 模板建议 ∩ 请求，逐跳物化（见
   * {@link #effectiveScopes}）。请求要了调用者够不着的地方 ⇒ {@link WorkingDirDeniedException} （{@code
   * DIR_NOT_ALLOWED}），子体不落地。
   *
   * <p><b>授权面（D27 的 {@code A1}）同样锚在 {@code callerPermissions} 上</b>：请求点了调用者没有的工具 ⇒ {@link
   * #checkRequestedToolsWithinCaller} 派发即拒（{@code permission.denied} 事件点名越界工具）；给了白名单就与模板求交（只收窄，见
   * {@link #tightenPermissions}）。
   *
   * <p><b>血缘在派发那一刻落账</b>：{@code lineagePath} 由本方法拼给实例（{@link #lineagePathOf}），子体改不了也给不出；
   * 父身份解析不出时记空串、照常派发（判定面 fail-closed）。
   *
   * @param caller 调用者身份（实例 id + 档位 + 深度；宿主绑定，模型给不出）
   * @param callerPermissions 调用者自己的权限集（单调性对照真值 + 资源可达面的来源）
   * @throws SubagentRejectedException 模板不存在 / {@code A1} 点名了调用者没有的工具（D27 的 I1）/ 权限或档位越权 / 深度超限
   * @throws WorkingDirDeniedException 请求面的工作目录超出调用者可达面（{@code DIR_NOT_ALLOWED} 码，见该异常类注释）
   */
  public SubagentInstance spawn(
      SubagentLaunchRequest request, AgentIdentity caller, AgentPermissionSet callerPermissions) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(callerPermissions, "callerPermissions");
    ensureOpen();
    AgentTemplate template =
        templateStore
            .get(request.templateId())
            .orElseThrow(
                () -> new SubagentRejectedException("子 Agent 模板不存在: " + request.templateId()));
    String instanceId = instanceIdOf(template.id());
    // D27：授权面点名 ⊆ 调用者能力集（I1）——<b>派发即拒</b>，与目录越界同形（同"请求面是要求"口径）
    checkRequestedToolsWithinCaller(template, instanceId, request, callerPermissions, caller);
    AgentPermissionSet permissions =
        tightenPermissions(template, request, callerPermissions, instanceId, caller.instanceId());
    checkMonotonicity(template, instanceId, permissions, callerPermissions, caller.instanceId());
    CommandMode mode = resolveMode(template, instanceId, request, caller);
    int depth = caller.depth() + 1;
    checkDepth(template, instanceId, depth, caller.instanceId());
    AgentConfig childConfig = deriveConfig(template, request, instanceId, permissions);
    String lineagePath = lineagePathOf(caller.instanceId(), instanceId);

    SubagentInstance configured =
        new SubagentInstance(
            instanceId,
            template.id(),
            request.goal(),
            childConfig,
            permissions,
            mode,
            depth,
            SubagentStatus.CONFIGURED,
            caller.instanceId(),
            lineagePath);
    SubagentInstance running;
    LaunchedSubagent handle = null;
    try {
      // 两转换同锁封窗：CONFIGURED 记录建立（+configured 事件）与 SPAWNING 推进置于同一临界区——CONFIGURED
      // 状态对其它线程不可观测（锁上只能看到 SPAWNING 及之后），kill 的 terminate 分支结构上不可能读到
      // CONFIGURED，从而消除"kill 落在 CONFIGURED 窗口"竞态（历史上 terminate 对 CONFIGURED 推 FAILED
      // 会因 canTransition 不允许 CONFIGURED→FAILED 而抛裸 ISE）。
      synchronized (lock) {
        ensureOpen();
        checkBudget(template, instanceId, caller);
        instances.put(instanceId, configured);
        emitLifecycle(instanceId, template.id(), "configured", depth, request.goal(), null);
        transition(
            instanceId,
            SubagentStatus.SPAWNING,
            lifecyclePayload("spawning", template.id(), depth, request.goal(), null));
      }
      try {
        // W3b：launcher 拿完整实例快照（id/模板/目标/权限）拼 agent --id 命令与父侧 MCP 服务
        handle = launcher.launch(configured);
      } catch (SubagentLaunchException e) {
        transition(
            instanceId,
            SubagentStatus.FAILED,
            lifecyclePayload("failed", template.id(), depth, null, e.getMessage()));
        throw e;
      }
      synchronized (lock) {
        handles.put(instanceId, handle);
      }
      running =
          transition(
              instanceId,
              SubagentStatus.RUNNING,
              lifecyclePayload("running", template.id(), depth, null, null));
      armWatch(instanceId, handle);
    } catch (IllegalStateException e) {
      // 并发 kill-before-running（launch 卡住期，或句柄 put 后 RUNNING 前）：kill 已把状态推至 FAILED/KILLED。
      // 句柄可能被 kill 抢先摘除并关停，也可能刚 put 进来无人收尾——在同一个临界区内取终态快照并摘除孤儿句柄，
      // 锁外关闭（真实 executor 下 stopGracefully 宽限可达 10s，不得占用锁，同 terminate 的写法），
      // 返回终态快照（FAILED/KILLED），不让裸 IllegalStateException 泄漏给调用方。
      LOG.warn("spawn 与 kill 竞态: 子 Agent 在确认存活前已被终止 {}", instanceId);
      SubagentInstance snapshot;
      LaunchedSubagent orphaned;
      synchronized (lock) {
        snapshot = instances.get(instanceId);
        orphaned = handles.remove(instanceId);
      }
      if (snapshot == null) {
        // 实例记录从未建立（如并发 close 在临界区内拒绝的 ensureOpen 路径）：非 kill 竞态，原样传播
        throw e;
      }
      closeQuietly(orphaned);
      return snapshot;
    }
    return running;
  }

  /** 按 id 查询当前实例快照（未知 id 返回 empty）。 */
  public Optional<SubagentInstance> get(String instanceId) {
    synchronized (lock) {
      return Optional.ofNullable(instances.get(instanceId));
    }
  }

  /** 全部子 Agent 快照（按实例 id 升序；供 list_sub_agents 工具与 AdminREST /api/agents）。 */
  public List<SubagentInstance> list() {
    synchronized (lock) {
      return instances.values().stream()
          .sorted(Comparator.comparing(SubagentInstance::instanceId))
          .toList();
    }
  }

  /**
   * 父侧观测到的<b>终局</b>（设计 §2.4 的内存面）：未知/尚未终结的实例返回 {@link Optional#empty()}。
   *
   * <p>模型面（{@code list}/{@code wait}/{@code kill} 三个工具）据此带出 {@code stopReason} 等字段——这是 C 块"正常完成 vs
   * 异常退出"可见的<b>唯一数据来源</b>；{@code status} 仍是唯一状态来源，本表只管"为什么停"。
   */
  public Optional<TerminalOutcome> terminalOutcome(String instanceId) {
    synchronized (lock) {
      return Optional.ofNullable(outcomes.get(instanceId));
    }
  }

  /**
   * 等到<b>终态</b>或超时（设计 §2.5）：100 ms 轮询某个实例的状态机状态，<b>不占锁</b>（每次只短暂取锁读快照， 关停路径的宽限等待不在这里）。
   *
   * <p><b>超时不是错误</b>：到点返回<b>当前快照</b>（可能仍是非终态），由调用方决定再等还是收——工具层照实回显，不抛异常。 与 {@link
   * #awaitDead(LaunchedSubagent, long)} 的分工：那个等的是<b>句柄</b>（进程真的死了才算数，kill 收尾用，且必须在观测线程 已停的 {@link
   * #close()} 路径上也能工作），本方法等的是<b>状态机终态</b>（观测线程投递的结果）；两者共用同一个 100 ms 轮询骨架 {@link
   * #pollUntil(BooleanSupplier, long)}（设计 §2.5 的"`awaitDead` 是它的特例"——特例的是<b>条件</b>，骨架只有一份）。
   *
   * @param timeoutMillis 最长等待毫秒（≤0 ⇒ 立即返回当前快照）
   * @return 终态或（超时的）当前快照
   * @throws IllegalArgumentException 未知 id（工具层的判定已保证"已知实例"，走到这里说明判完到等待之间实例没了）
   */
  public SubagentInstance awaitTerminal(String instanceId, long timeoutMillis) {
    pollUntil(() -> snapshotOrThrow(instanceId).status().isFinal(), timeoutMillis);
    return snapshotOrThrow(instanceId);
  }

  /** 实例快照（未知 id 抛 {@link IllegalArgumentException}——调用方已完成判定，走到这里实例没了是程序错误）。 */
  private SubagentInstance snapshotOrThrow(String instanceId) {
    SubagentInstance current = get(instanceId).orElse(null);
    if (current == null) {
      throw new IllegalArgumentException("未知子 Agent: " + instanceId);
    }
    return current;
  }

  /**
   * kill：Manager 编排入口（红线 5，非"工具关进程"）。
   *
   * <p>RUNNING → TERMINATING（事件）→ launcher 句柄关闭（真实实现 = 三层关停）→ 终态确认 → KILLED （事件）；SPAWNING →
   * FAILED（kill-before-running，合法推进）。CONFIGURED 对外不可观测（spawn 两转换 同锁，见 spawn），terminate 的 CONFIGURED
   * 分支仅为防御守卫，返回 false。终态/TERMINATING 重复调用为空操作。
   *
   * @return true = 本次调用实际发起了终止（状态已推进）；false = 已是终态/已在终止（幂等空操作）
   * @throws IllegalArgumentException 未知 id
   */
  public boolean kill(String instanceId) {
    ensureOpen();
    return terminate(instanceId);
  }

  /**
   * 关停全部在管子 Agent（子进程树清理经 launcher 窄缝）+ 关闭 launcher；幂等。
   *
   * <p>语义：子进程的生命周期归 SubprocessManager/Main（红线 5），这里的"清理"是编排收尾——逐个 terminate +
   * 通知启动缝释放自身资源，进程级兜底（宽限强杀）由 SubprocessManager 侧承担。
   *
   * <p><b>顺序由深到浅</b>（设计 §2.7）：按 {@code lineagePath} 段数降序关停，{@code lineagePath} 为空的老记录排<b>最后</b>。 序由
   * {@link #shutdownOrder(Map)} 给出（静态纯函数，可确定性单测）；仍是同一条 {@link #terminate(String)} 路径，
   * 异常吞成日志（既有口径）——顺序只是"谁先谁后"，不改任何终态语义。
   */
  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      watchers.forEach(Thread::interrupt);
      watchers.clear();
    }
    List<String> live;
    synchronized (lock) {
      live = shutdownOrder(instances);
    }
    for (String id : live) {
      try {
        terminate(id);
      } catch (RuntimeException e) {
        LOG.warn("关停子 Agent 失败 id={}", id, e);
      }
    }
    try {
      launcher.close();
    } catch (RuntimeException e) {
      LOG.warn("launcher 关闭异常", e);
    }
  }

  /**
   * 关停次序（设计 §2.7）：<b>由深到浅</b>——先关子孙再关祖辈，别让"祖辈先死 ⇒ 子孙的父侧链路断在半路"；{@code lineagePath} 为空的老记录（深度记
   * {@code -1}）排<b>最后</b>，不与深层抢序（它们没有可信的深度可言）。
   *
   * <p><b>为什么抽成静态纯函数</b>：次序只由 {@code lineagePath} 决定，拿 {@code close()}
   * 里"真实并发谁先收到终止"去判序是<b>概率性</b>判别 （三个同深实例的正确序只占 C(7,3) 之一，评审实测理论逃逸 ≈2.9%）。本函数的判据是"给定这组实例，返回的 id
   * 序"——同一输入必得同一输出， 顺序错了必转红。
   *
   * <p>只过滤终态（终态实例不再关）与 {@link #close()} 逐字同口径；{@link java.util.stream.Stream#sorted} 是<b>稳定</b>排序，
   * 同深度保持<b>传入 Map 的迭代序</b>——生产侧的 {@code instances} 是 {@code HashMap} ⇒
   * 同深度之间<b>没有</b>定义序（本函数不额外定义它）； 测试用 {@code LinkedHashMap} 喂输入，把这个"同深度保持迭代序"的性质钉成确定性的。
   */
  static List<String> shutdownOrder(Map<String, SubagentInstance> instances) {
    return instances.values().stream()
        .filter(i -> !i.status().isFinal())
        .sorted(
            Comparator.comparingInt((SubagentInstance i) -> lineageDepth(i.lineagePath()))
                .reversed())
        .map(SubagentInstance::instanceId)
        .toList();
  }

  // ---- 内部：守卫与派生 ----

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("SubagentManager 已关闭");
    }
  }

  /** 实例 id = 模板 id + 随机后缀（进程标识素材；拒绝路径也先生成 id 以便事件可串查）。 */
  private static String instanceIdOf(String templateId) {
    return templateId + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong());
  }

  /**
   * 血缘路径（D27）：主 Agent 的 path = {@code main}（= {@link AgentIdentity#MAIN_ID}）；子实例 = {@code 父path +
   * "/" + 自己的 instanceId}（形态 {@code main/a1/a1-1}）。
   *
   * <p><b>谁写</b>：只有本方法（{@link #spawn} 内、实例 id 生成处），写进 {@link SubagentInstance#lineagePath()}
   * 后不可变。子体 <b>不参与、也给不出</b>（I4 不可自报）——判定读的是 manager 里的这一份，不是子体自报的任何东西。事件面不新增明文（path 只进实例记录）。
   *
   * <p><b>父解析不出 ⇒ 记空串，不拒派发</b>：把不确定挡在决策点（{@link ContextAccessJudge} 对空 path fail-closed），而不是挡在构造点——
   * 否则 S5-A/S6 的三参兼容路径与既有夹具会被"身份不完整"整片拒掉。
   *
   * <p><b>空串 path 的实例在工具面上谁都动不了</b>（本段 2026-09-13 修正：此前写的"它照样能被父 kill"不成立）：目标 path 空 + 调用者可解析 ⇒
   * 判定⑤拒；而空 path 只可能由解析不出的父派生（{@link #lineagePathOfCaller} 返回空串）⇒ 那个父自己的 callerPath 也空 ⇒
   * 判定④<b>先</b>拒；连 {@code target=self} 都过不去（②按"调用者能解析出"才放行）。两条路都 fail-closed，不存在"谁经工具面还能碰它"。
   *
   * <p><b>补偿手段 = 关停面，不是工具面</b>：这类实例仍进得出 {@link #kill(String)} 与 {@link #close()}——两者都直呼 {@link
   * #terminate(String)}（编排入口，不经 {@link ContextAccessJudge}、不看 path），所以"读写面够不着"不会变成实例/进程泄漏；要清掉它们走
   * manager 入口，别试图从工具面绕。
   */
  private String lineagePathOf(String callerId, String childId) {
    String parentPath = lineagePathOfCaller(callerId);
    return parentPath.isEmpty() ? "" : parentPath + "/" + childId;
  }

  /**
   * 调用者的血缘路径（{@code ""} = 解析不出：既不是 main，也不在本 manager 里）。<b>委托</b> {@link
   * ContextAccessJudge#pathOf(String, SubagentManager)}——判定面（范围）与写入面（拼子体 path）必须只有一份身份解析，
   * 两处各写一遍就会分叉 （同族缺陷温床）。
   */
  private String lineagePathOfCaller(String callerId) {
    return ContextAccessJudge.pathOf(callerId, this);
  }

  /**
   * 血缘段数（{@code main/a1/a1-1} ⇒ 3）：只服务 {@link #shutdownOrder(Map)}（= {@link #close()} 的"由深到浅"排序）。
   *
   * <p>空串（老记录/父身份解析不出）记 {@code -1}——它是"深度未知"，必须排在所有已知深度之后（设计 §2.7："不与深层抢序"）， 而不是被当成"第 0 层"（那会把它排到段数
   * 1 之前）。
   */
  private static int lineageDepth(String lineagePath) {
    if (lineagePath == null || lineagePath.isEmpty()) {
      return -1;
    }
    int segments = 1;
    for (int i = 0; i < lineagePath.length(); i++) {
      if (lineagePath.charAt(i) == '/') {
        segments++;
      }
    }
    return segments;
  }

  /**
   * D27 / {@code A1} 的<b>硬上限检查</b>（I1）：请求面点名的工具必须 ⊆ {@link #tightenPermissions} 之前的<b>调用者能力集</b>。
   *
   * <p><b>为什么在派发那刻、而不是"运行期调用时才失败"</b>：点名越界是<b>结构性问题</b>（请求面写错了一个工具名/想给子体自己没有的权力），
   * 派发即拒让调用者在启动前就知道"这次要的东西不成立"，而不是等子体跑起来、某个工具调用时才撞墙（那时责任面已模糊，模型只会看到"工具不可用"）。 与 {@code allowedDirs}
   * 的"请求面是要求"完全同形：<b>不静默截断</b>。
   *
   * <p>对照物是 {@code callerPermissions}——{@code isToolAllowed} 的既有口径（含 {@code "*"} 通配：调用者白名单通配 ⇒
   * 全放行）。 拒绝走既有 {@link SubagentRejectedException}（{@code PERMISSION_DENIED} 码）+ {@code
   * permission.denied} 事件（<b>点名</b> 越界工具），并<b>不建实例、不建链、不花预算</b>。
   *
   * <p>只有请求面给了白名单才检查（{@code null} = 不指定 ⇒ 模板说了算，逐字回到本字段引入前的行为）。
   */
  private void checkRequestedToolsWithinCaller(
      AgentTemplate template,
      String instanceId,
      SubagentLaunchRequest request,
      AgentPermissionSet callerPermissions,
      AgentIdentity caller) {
    if (request.allowedTools() == null) {
      return;
    }
    Set<String> beyond = new LinkedHashSet<>();
    for (String tool : request.allowedTools()) {
      if (!callerPermissions.isToolAllowed(tool)) {
        beyond.add(tool);
      }
    }
    if (beyond.isEmpty()) {
      return;
    }
    String message = "子 Agent 授权超出调用者能力（I1 单调性）: 以下工具不在调用者白名单内: " + beyond;
    emit(
        EventTypes.PERMISSION_DENIED,
        caller.instanceId(),
        instanceId,
        Map.of(
            "template", template.id(),
            "childId", instanceId,
            "reason", message,
            "requestedTools", request.allowedTools(),
            "beyondCaller", List.copyOf(beyond)));
    throw new SubagentRejectedException(message);
  }

  /**
   * 权限收紧：模板权限集 + 请求 {@code allowedTools}（{@code A1}，白名单只做<b>求交</b>——永不放大）+ 请求 extraDenied
   * （只增不减，白名单永不放大——SubagentLaunchRequest 语义）+ <b>资源可达面物化</b>（S5-B：调用者 ∩ 模板建议 ∩ 请求，逐命名空间落成子体的具体取值）。
   *
   * <p><b>{@code A1} 的三层语义</b>（见 {@link
   * SubagentLaunchRequest#allowedTools()}）：模板白名单是<b>基线</b>（部署者写的）， 请求是<b>收窄要求</b>（超出模板 ⇒
   * 静默求交），调用者能力集是<b>硬上限</b>（超出 ⇒ {@link #checkRequestedToolsWithinCaller}
   * 派发即拒，不在这里）。求交结果为空是<b>合法</b>取值（显式"什么都不给"）， 不是错误；{@code null}（没提这一嘴）逐字退回模板白名单。
   *
   * <p><b>为什么继承要物化而不是"运行时往上问父级"</b>：{@link PermissionChecker#isSubset} 比的是两份<b>权限集数据</b>。
   * 如果子体这一维留空（"我照父级的办"）， covers 只能看到"子级没表态"，无从证明它不比父级宽——判定会静默放行一个运行期真正生效的面比父级宽的子体（锚错对象的同族缺陷， 见 S5-A
   * 的构造期常量教训）。
   */
  private AgentPermissionSet tightenPermissions(
      AgentTemplate template,
      SubagentLaunchRequest request,
      AgentPermissionSet callerPermissions,
      String instanceId,
      String callerId) {
    ResourceScopeMap scopes =
        effectiveScopes(template, request, callerPermissions, instanceId, callerId);
    AgentPermissionSet base = narrowedTemplatePermissions(template, request);
    if (request.extraDenied().isEmpty()) {
      return base.withResourceScopes(scopes);
    }
    Set<String> denied = new HashSet<>(base.deniedTools());
    denied.addAll(request.extraDenied());
    return new AgentPermissionSet(
        base.grantedToken(),
        base.allowedTools(),
        Set.copyOf(denied),
        base.destructiveAllowed(),
        base.sensitiveAllowed(),
        base.readOnly(),
        scopes);
  }

  /**
   * 模板权限集 + {@code A1} 收窄后的白名单（{@code null} = 不指定 ⇒ 逐字退回模板那一份）。
   *
   * <p><b>{@code "*"} 是"不过滤"的过滤器元素，不是工具名</b>（{@link AgentPermissionSet#ALL_TOOLS}）——三种组合：
   *
   * <ul>
   *   <li>{@code A1} 含 {@code "*"} ⇒ <b>不过滤</b>：结果逐字是<b>模板那一份</b>（模板也通配 ⇒ 仍 {@code
   *       "*"}）。"全域"不是可点名的工具， 请求面只收窄、不放大；模板说了算的东西不因为一句"随便"多出来；
   *   <li>模板通配 + {@code A1} 有限 ⇒ 结果 = {@code A1}（两侧都放行才留——"模板通配"不把请求没点的工具塞回来）；
   *   <li>两侧都有限 ⇒ 求交（模板之外的点名静默掉，同 {@code allowedDirs} 的"模板是建议"口径）。
   * </ul>
   *
   * <p><b>I1 对 {@code "*"} 的行为（有意如此，别放宽）</b>：{@code callerPermissions.isToolAllowed("*")}
   * 只在<b>调用者自己的白名单本身通配</b>时为真 （{@link AgentPermissionSet#isToolAllowed(String)} 的既有口径，{@code "*"}
   * 不做跨集合比较）⇒ 受限调用者点 {@code A1=["*"]} 会在 {@link #checkRequestedToolsWithinCaller}
   * 被<b>派发即拒</b>（{@code beyondCaller=["*"]}）——"受限者不得借通配把自己放大成全域" 的直接结果，不是漏判。
   */
  private static AgentPermissionSet narrowedTemplatePermissions(
      AgentTemplate template, SubagentLaunchRequest request) {
    AgentPermissionSet base = template.toPermissionSet();
    if (request.allowedTools() == null) {
      return base;
    }
    // A1 通配 = 不过滤（见 javadoc）：逐字返回模板那一份，不是"求交得到空集"
    if (request.allowedTools().contains(AgentPermissionSet.ALL_TOOLS)) {
      return base;
    }
    Set<String> narrowed = new LinkedHashSet<>();
    for (String tool : request.allowedTools()) {
      if (template.allowedTools().contains(AgentPermissionSet.ALL_TOOLS)
          || template.allowedTools().contains(tool)) {
        narrowed.add(tool);
      }
    }
    // 空集合法（= 子体一个工具都没有）；A1 有限而模板通配 ⇒ narrowed = A1 本身（上面那条分支只挡"含 *"）
    return new AgentPermissionSet(
        base.grantedToken(),
        narrowed,
        base.deniedTools(),
        base.destructiveAllowed(),
        base.sensitiveAllowed(),
        base.readOnly(),
        base.resourceScopes());
  }

  /**
   * 子体资源可达面的生效值（设计 §2.4）：{@code 调用者 ∩ 模板建议 ∩ 请求}，逐命名空间求交后<b>物化</b>。
   *
   * <p>三个来源的分工（口径不同，别互相套用）：
   *
   * <ul>
   *   <li><b>调用者</b>：真值来源。它表过态的命名空间，子体一律接着（只减不增）；
   *   <li><b>模板</b>（{@code allowedWorkingDirs}）：<b>建议</b>——超出调用者可达面就求交（通用配置被上级收紧是常态）， 不静默放大也不报错；
   *   <li><b>请求</b>（{@code allowedDirs}）：<b>要求</b>——超出调用者可达面 ⇒ {@link
   *       WorkingDirDeniedException}（+{@code permission.denied} 事件），父子都不静默截断。
   * </ul>
   *
   * <p>其余命名空间（{@code fs} 以外的）逐条照抄调用者的取值——本段只给 {@code fs} 定义了"模板/请求怎么参与"， 别的命名空间没有对应字段，照抄即"继承父级"（裁决
   * ②）。
   */
  private ResourceScopeMap effectiveScopes(
      AgentTemplate template,
      SubagentLaunchRequest request,
      AgentPermissionSet callerPermissions,
      String instanceId,
      String callerId) {
    ResourceScope callerFs = callerPermissions.fsScope();
    ResourceScope requested = requestedDirScope(template, request, instanceId, callerId, callerFs);
    if (requested != null && !callerFs.covers(requested)) {
      throw dirDenied(template, instanceId, callerId, request, callerFs);
    }
    ResourceScope effectiveFs =
        callerFs.narrowTo(template.workingDirScope()).narrowTo(effective(requested));
    return callerPermissions.resourceScopes().withNamespace(ResourceScopeMap.FS, effectiveFs);
  }

  /**
   * 请求面的目录集（{@code null} = 没提这一嘴 ⇒ 不参与求交；空表 = 显式"哪里都不许"）。
   *
   * <p>路径归一失败（含 {@code ..} 段）也走 {@link WorkingDirDeniedException}：那是模型送来的坏参数，
   * 与"越界"同属"这次要的东西不成立"，工具层落同一个码回灌即可（坏路径重试一次也会被同样拒掉，不会变成无限重试的哑谜）。
   */
  private ResourceScope requestedDirScope(
      AgentTemplate template,
      SubagentLaunchRequest request,
      String instanceId,
      String callerId,
      ResourceScope callerFs) {
    if (request.allowedDirs() == null) {
      return null;
    }
    if (request.allowedDirs().isEmpty()) {
      return ResourceScope.none();
    }
    try {
      return ResourceScope.ofDirs(request.allowedDirs().stream().map(Path::of).toList());
    } catch (IllegalArgumentException | UncheckedIOException e) {
      String message =
          "子 Agent 工作目录参数无法归一（DIR_NOT_ALLOWED）: 请求="
              + request.allowedDirs()
              + " 原因="
              + e.getMessage();
      emit(
          EventTypes.PERMISSION_DENIED,
          callerId,
          instanceId,
          Map.of(
              "template", template.id(),
              "childId", instanceId,
              "reason", message,
              "requestedDirs", request.allowedDirs(),
              "callerScope", callerFs.summary()));
      throw new WorkingDirDeniedException(message);
    }
  }

  /** 越界拒绝：{@code permission.denied} 事件（agent = <b>被拒的调用者</b>）+ 带需求与可达面的异常。 */
  private WorkingDirDeniedException dirDenied(
      AgentTemplate template,
      String instanceId,
      String callerId,
      SubagentLaunchRequest request,
      ResourceScope callerFs) {
    String message =
        "子 Agent 工作目录越界（DIR_NOT_ALLOWED）: 请求="
            + request.allowedDirs()
            + " 调用者可达="
            + callerFs.summary()
            + "（请求面是'要求'不是'建议'：超出上级可达面一律拒，任一方都不静默截断）";
    emit(
        EventTypes.PERMISSION_DENIED,
        callerId,
        instanceId,
        Map.of(
            "template",
            template.id(),
            "childId",
            instanceId,
            "reason",
            message,
            "requestedDirs",
            request.allowedDirs(),
            "callerScope",
            callerFs.summary()));
    return new WorkingDirDeniedException(message);
  }

  /** {@code null}（不表态）在求交里等价于"不限"——它本来就不限制任何东西。 */
  private static ResourceScope effective(ResourceScope scope) {
    return scope == null ? ResourceScope.unlimited() : scope;
  }

  /**
   * 子体的<b>命令档位</b>：请求里没给就 {@link CommandMode#LIMITED}（子 Agent 的缺省档，用户裁决"必须是子 Agent 的默认"），
   * 给了就得过单调性—— 父级档位<b>够不着</b>的一律拒（{@link SubagentRejectedException} + {@code permission.denied}
   * 事件）， <b>不静默降级</b>：静默降级会让模型以为拿到的是它要的那一档，之后每条命令都"莫名"要审批，而真正的原因在启动那一刻。
   *
   * <p>与权限收紧的关系：档位不改变工具<b>可用性</b>（那是权限集的事），它只决定"命令闸工具按哪一档分流"—— 所以这里不做 {@code limit}
   * 也不回写权限集，只判够不够（{@code CommandMode.covers}）。
   */
  private CommandMode resolveMode(
      AgentTemplate template,
      String instanceId,
      SubagentLaunchRequest request,
      AgentIdentity caller) {
    CommandMode requested = request.mode() == null ? CommandMode.LIMITED : request.mode();
    CommandMode parent = caller.mode();
    if (parent == null || !parent.covers(requested)) {
      String message = "子 Agent 档位超出父级许可（单调性）: 父级=" + parent + " 子级=" + requested;
      emit(
          EventTypes.PERMISSION_DENIED,
          caller.instanceId(),
          instanceId,
          Map.of("template", template.id(), "childId", instanceId, "reason", message));
      throw new SubagentRejectedException(message);
    }
    return requested;
  }

  /**
   * 红线 1：权限单调性（{@link PermissionChecker#isSubset} 是判定真值，本方法只负责"拒绝 + 事件"的 编排；具体维度诊断仅供消息可读性，判定不分叉）。
   *
   * <p><b>对照物是调用者的权限集</b>（S5-A）：拒绝类事件的 {@code agent} 字段也记调用者实例 id——事件要能回答"谁被拒了"， 而不是"manager 属于谁"。
   */
  private void checkMonotonicity(
      AgentTemplate template,
      String instanceId,
      AgentPermissionSet permissions,
      AgentPermissionSet callerPermissions,
      String callerId) {
    if (PermissionChecker.isSubset(permissions, callerPermissions)) {
      return;
    }
    String message =
        "子 Agent 权限超出父级许可（红线 1 单调性）: " + nonSubsetReason(permissions, callerPermissions);
    emit(
        EventTypes.PERMISSION_DENIED,
        callerId,
        instanceId,
        Map.of("template", template.id(), "childId", instanceId, "reason", message));
    throw new SubagentRejectedException(message);
  }

  /**
   * 繁殖预算（D28 基础项）：单调用者直系 + 全局在管两个额度，<b>只数在管（非终态）实例</b>——跑完的不再占额。
   *
   * <p><b>必须在锁内调用</b>：只数不建会让并发 spawn 双双越过额度。消息带<b>需求/剩余/上限</b>三个数，模型据此才知道差距是 1 还是 100。
   *
   * <p>深度不在这里（它由 {@link #checkDepth} 判、码也不同）：深度是"链有多长"，本方法是"这一层有多少"——两者的终止 手段不同（改模板 vs 等/收）。
   */
  private void checkBudget(AgentTemplate template, String instanceId, AgentIdentity caller) {
    long liveTotal = instances.values().stream().filter(i -> !i.status().isFinal()).count();
    if (liveTotal + 1 > limits.maxInstances()) {
      throw budgetExceeded(
          template, instanceId, "全局在管子实例", liveTotal, limits.maxInstances(), caller);
    }
    long liveChildren =
        instances.values().stream()
            .filter(i -> !i.status().isFinal() && caller.instanceId().equals(i.parentInstanceId()))
            .count();
    if (liveChildren + 1 > limits.maxChildrenPerInstance()) {
      throw budgetExceeded(
          template,
          instanceId,
          "调用者 " + caller.instanceId() + " 的直系在管子实例",
          liveChildren,
          limits.maxChildrenPerInstance(),
          caller);
    }
  }

  /**
   * 预算拒绝：{@code decision:BUDGET_EXHAUSTED} 事件 + 带三个数的异常（口径见 {@link
   * SubagentBudgetExceededException}）。
   */
  private SubagentBudgetExceededException budgetExceeded(
      AgentTemplate template,
      String instanceId,
      String what,
      long current,
      int limit,
      AgentIdentity caller) {
    long remaining = Math.max(0, limit - current);
    String message =
        "子 Agent 繁殖预算耗尽（BUDGET_EXHAUSTED）: " + what + " 需求=1 剩余=" + remaining + " 上限=" + limit;
    emit(
        EventTypes.DECISION,
        caller.instanceId(),
        instanceId,
        Map.of(
            "decision", "BUDGET_EXHAUSTED",
            "template", template.id(),
            "childId", instanceId,
            "scope", what,
            "need", 1,
            "remaining", remaining,
            "limit", limit));
    return new SubagentBudgetExceededException(message);
  }

  /** 诊断用：返回第一个不满足的维度（与 isSubset 规则逐条镜像，仅供消息）。 */
  private static String nonSubsetReason(AgentPermissionSet granted, AgentPermissionSet parent) {
    if (!parent.grantedToken().atLeast(granted.grantedToken())) {
      return "身份级别 " + granted.grantedToken() + " 高于父级 " + parent.grantedToken();
    }
    if (!parent.allowedTools().contains(AgentPermissionSet.ALL_TOOLS)) {
      if (granted.allowedTools().contains(AgentPermissionSet.ALL_TOOLS)) {
        return "子级白名单通配（父级白名单未通配）";
      }
      if (!parent.allowedTools().containsAll(granted.allowedTools())) {
        Set<String> extra = new HashSet<>(granted.allowedTools());
        extra.removeAll(parent.allowedTools());
        return "以下工具不在父级白名单内: " + extra;
      }
    }
    if (!granted.deniedTools().containsAll(parent.deniedTools())) {
      return "子级未保留父级全部显式拒绝";
    }
    if (granted.destructiveAllowed() && !parent.destructiveAllowed()) {
      return "子级放行破坏性工具而父级未放行";
    }
    if (granted.sensitiveAllowed() && !parent.sensitiveAllowed()) {
      return "子级放行敏感工具而父级未放行";
    }
    if (parent.readOnly() && !granted.readOnly()) {
      return "父级只读而子级未只读";
    }
    for (String namespace : parent.resourceScopes().namespaces()) {
      ResourceScope parentScope = parent.resourceScopes().declaredScope(namespace);
      ResourceScope child = granted.resourceScopes().declaredScope(namespace);
      if (child == null) {
        return "子级未表态资源命名空间 " + namespace + "（父级已表态=" + parentScope.summary() + "）";
      }
      if (!parentScope.covers(child)) {
        return "资源命名空间 "
            + namespace
            + " 子级更宽: 父级="
            + parentScope.summary()
            + " 子级="
            + child.summary();
      }
    }
    return "权限集不在父级范围内";
  }

  /**
   * 深度限制（{@code subagents.maxDepth}，<b>总层数口径</b>，超限拒绝 + {@code decision:DEPTH_LIMIT} 事件）。
   *
   * <p>对照深度是<b>调用者的深度 + 1</b>（不再用构造期常量）：event 里的 {@code maxDepth} 报的是总层数（配置原值），
   * 消息里报的是子深度上限——两个口径都写清楚，免得读者按哪个判都对不上。
   */
  private void checkDepth(AgentTemplate template, String instanceId, int depth, String callerId) {
    int maxChildDepth = limits.maxChildDepth();
    if (depth <= maxChildDepth) {
      return;
    }
    String message = "子 Agent 深度超限: depth=" + depth + " > max=" + maxChildDepth;
    emit(
        EventTypes.DECISION,
        callerId,
        instanceId,
        Map.of(
            "decision",
            "DEPTH_LIMIT",
            "template",
            template.id(),
            "childId",
            instanceId,
            "depth",
            depth,
            "maxDepth",
            limits.maxDepth()));
    throw new SubagentRejectedException(message);
  }

  /**
   * cap 派生（防跑飞 D10 第四维度）：每个维度取 min(模板, 请求收紧, 父级)；请求 cap 非正视为 "不收紧"（SubagentLaunchRequest：更松的 cap
   * 被忽略而非报错），父级 quota 0（不限）不参与 min。
   */
  private AgentConfig deriveConfig(
      AgentTemplate template,
      SubagentLaunchRequest request,
      String instanceId,
      AgentPermissionSet permissions) {
    AgentConfig.Builder builder =
        AgentConfig.builder(instanceId)
            .description(template.description())
            .systemPrompt(template.systemPrompt())
            .model(template.model())
            .allowedTools(permissions.allowedTools())
            .deniedTools(permissions.deniedTools())
            .maxTurns(minCap(template.maxTurns(), request.maxTurnsCap(), parentConfig.maxTurns()))
            .maxToolCallsPerTurn(
                Math.min(template.maxToolCallsPerTurn(), parentConfig.maxToolCallsPerTurn()))
            .timeBudget(
                Duration.ofSeconds(
                    minCap(
                        template.timeBudgetSeconds(),
                        request.timeBudgetSecondsCap(),
                        parentConfig.timeBudget().toSeconds())))
            .quotaMaxTokens(
                minQuota(
                    template.quotaMaxTokens(),
                    request.quotaMaxTokensCap(),
                    parentConfig.quotaMaxTokens()));
    return builder.build();
  }

  private static int minCap(int templateValue, Integer requestCap, int parentValue) {
    int value = Math.min(templateValue, parentValue);
    return requestCap != null && requestCap > 0 ? Math.min(value, requestCap) : value;
  }

  private static long minCap(long templateValue, Long requestCap, long parentValue) {
    long value = Math.min(templateValue, parentValue);
    return requestCap != null && requestCap > 0 ? Math.min(value, requestCap) : value;
  }

  /** 配额 min 语义：0（不限）的参与者不约束（模板、请求、父级三者同规）。 */
  private static long minQuota(long templateValue, Long requestCap, long parentValue) {
    long value = templateValue > 0 ? templateValue : Long.MAX_VALUE; // 模板 0 = 不限，不参与 min
    if (requestCap != null && requestCap > 0) {
      value = Math.min(value, requestCap);
    }
    if (parentValue > 0) {
      value = Math.min(value, parentValue);
    }
    return value == Long.MAX_VALUE ? 0 : value; // 三者均不限 → 仍是不限（0）
  }

  // ---- 内部：状态机与事件 ----

  /** 推进到 {@code to} 并发 lifecycle 事件；非法推进抛错、终态→同态幂等返回（不重复发事件）。 */
  private SubagentInstance transition(
      String instanceId, SubagentStatus to, Map<String, Object> lifecyclePayload) {
    synchronized (lock) {
      SubagentInstance current = instances.get(instanceId);
      if (current == null) {
        throw new IllegalStateException("未知子 Agent 推进: " + instanceId);
      }
      if (current.status().isFinal()) {
        if (current.status() == to) {
          return current; // 终态→同态：幂等静默（并发 kill 后 launch 失败的 FAILED 重复推进即此路径）
        }
        throw new IllegalStateException(
            "非法状态推进 " + current.status() + "→" + to + "（" + instanceId + "）");
      }
      if (!SubagentStatus.canTransition(current.status(), to)) {
        throw new IllegalStateException(
            "非法状态推进 " + current.status() + "→" + to + "（" + instanceId + "）");
      }
      SubagentInstance next = current.withStatus(to);
      instances.put(instanceId, next);
      if (to.isFinal()) {
        handles.remove(instanceId); // 终态句柄不再被任何路径使用：防长时间运行下句柄表无界增长
      }
      emit(EventTypes.AGENT_LIFECYCLE, instanceId, instanceId, lifecyclePayload);
      return next;
    }
  }

  /** 导入期事件（配置装配/生命周期外）：CONFIGURED 记录建立。agent=子 id、correlation=子 id。 */
  private void emitLifecycle(
      String instanceId, String templateId, String action, int depth, String goal, String reason) {
    emit(
        EventTypes.AGENT_LIFECYCLE,
        instanceId,
        instanceId,
        lifecyclePayload(action, templateId, depth, goal, reason));
  }

  private static Map<String, Object> lifecyclePayload(
      String action, String templateId, int depth, String goal, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("action", action);
    payload.put("template", templateId);
    payload.put("depth", depth);
    if (goal != null) {
      payload.put("goalLength", goal.length());
    }
    if (reason != null) {
      payload.put("reason", reason);
    }
    return payload;
  }

  private Event emit(String type, String agent, String correlationId, Map<String, ?> payload) {
    Event event =
        events.append(EventWrite.of(type, agent, SubagentJson.toJson(payload), correlationId));
    bus.publish(event);
    return event;
  }

  // ---- 内部：kill 编排与退出观测 ----

  private boolean terminate(String instanceId) {
    LaunchedSubagent handle = null;
    String templateId;
    int depth;
    boolean killBeforeRunning = false;
    synchronized (lock) {
      SubagentInstance current = instances.get(instanceId);
      if (current == null) {
        throw new IllegalArgumentException("未知子 Agent: " + instanceId);
      }
      templateId = current.templateId();
      depth = current.depth();
      SubagentStatus status = current.status();
      if (status == SubagentStatus.TERMINATING || status.isFinal()) {
        return false; // 幂等空操作
      }
      if (status == SubagentStatus.CONFIGURED) {
        // 防御守卫（不可达）：spawn 把 CONFIGURED 记录建立与 SPAWNING 推进放在同一临界区（见 spawn），
        // 其它线程在锁上不可能观察到 CONFIGURED。若未来改动破坏该不变量而触达此分支，返回 false——
        // 不推进非法状态（canTransition 仅允许 CONFIGURED→SPAWNING；对 CONFIGURED 推 FAILED 会抛裸 ISE，
        // 即修复轮 2 重审发现的原始缺陷），也不把裸 IllegalStateException 泄漏给 kill 调用方。
        return false;
      }
      if (status == SubagentStatus.SPAWNING) {
        // kill-before-running：SPAWNING→FAILED 为合法推进；句柄从表摘除，统一在锁外关闭
        handle = handles.remove(instanceId);
        transition(
            instanceId,
            SubagentStatus.FAILED,
            lifecyclePayload("failed", templateId, depth, null, "kill-before-running"));
        killBeforeRunning = true;
      } else {
        // RUNNING：TERMINATING 先于句柄关闭（状态与事件的因果一致性）
        transition(
            instanceId,
            SubagentStatus.TERMINATING,
            lifecyclePayload("terminating", templateId, depth, null, null));
        handle = handles.get(instanceId);
      }
    }
    closeQuietly(handle); // 锁外三层关停入口（stopGracefully 宽限最长 10s，任何路径都不占锁）
    if (killBeforeRunning) {
      return true;
    }
    boolean dead = handle == null || awaitDead(handle, KILL_CONFIRM_MILLIS);
    if (dead) {
      // 与观测线程<b>同一个</b>收束函数（设计 §2.3：两条收束路径必须幂等、终态不重复迁移）：
      // TERMINATING + 无终局记录 ⇒ KILLED；罕见但正确的一支 = 子体在被关停前已把终局落了库 ⇒ 以事件为准记 FINISHED + stopReason。
      // 谁先到谁生效，后到的那条在锁内看到终态即返回（不重复迁移、不重复发事件）。
      onChildExited(instanceId, handle);
    } else {
      // 三层关停后仍未死：留 TERMINATING，由退出观测在真正死亡时落 KILLED——不谎报终态
      LOG.warn("子 Agent 关停后仍存活（等待退出观测收束）: {}", instanceId);
    }
    return true;
  }

  /** 启动退出观测：每实例一个虚拟线程轮询存亡（100ms），把"已退出"事实投递回状态机。 */
  private void armWatch(String instanceId, LaunchedSubagent handle) {
    Thread watcher =
        Thread.ofVirtual()
            .name("mosire-subagent-exit-" + instanceId)
            .start(() -> watchExit(instanceId, handle));
    synchronized (lock) {
      watchers.add(watcher);
    }
  }

  private void watchExit(String instanceId, LaunchedSubagent handle) {
    try {
      while (!closed) {
        if (!handle.isAlive()) {
          onChildExited(instanceId, handle);
          return;
        }
        Thread.sleep(EXIT_POLL_MILLIS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      synchronized (lock) {
        watchers.remove(Thread.currentThread());
      }
    }
  }

  /**
   * 已退出事实的<b>唯一收束点</b>（设计 §2.3 重写；观测线程与 {@link #terminate(String)} 的 {@code awaitDead} 两条路径都走这里）。
   *
   * <p><b>判据 = 子库终局事件的有无，不是退出码</b>（被杀的子体照常 {@code return 0}，码答不出"为什么停"）：
   *
   * <table>
   *   <caption>四条规则</caption>
   *   <tr><th>观测<th>终态<th>理由</tr>
   *   <tr><td>有终局记录（任何非终态）<td>{@code FINISHED} + {@code stopReason}/{@code turns}/{@code toolCalls}
   *       <td>子体自己交代了它为什么停；含"TERMINATING 而子库有终局事件"——子体确实跑完了这一轮，谎报 KILLED 比"kill 请求落空"更糟</tr>
   *   <tr><td>{@code RUNNING} 退出、无记录<td>{@code FAILED}（`未留下终局记录`）+ 退出码
   *       <td>正常路径<b>一定</b>先落库后退栈；没有 ⇒ 异常退栈（崩了/被外部杀了）</tr>
   *   <tr><td>{@code TERMINATING} 退出、无记录<td>{@code KILLED}（无 {@code stopReason}）
   *       <td>既有口径逐字保留：是我们要求的关停，不许假装子体交代过停因</td></tr>
   *   <tr><td>{@code SPAWNING} 退出<td>{@code FAILED}（`subagent 退出于确认存活之前`）
   *       <td>既有口径逐字保留</td></tr>
   * </table>
   *
   * <p><b>幂等（两条路径同刻到达）</b>：状态已是终态 ⇒ 直接返回（不重复迁移、不重复发事件）。靠"先读后判"在同一把锁内完成， 而不是靠 {@code canTransition}
   * 兜底——`KILLED→FAILED` 恰是非法迁移，靠它挡会把设计缺陷伪装成"恰好没炸"。
   *
   * <p><b>LLM 失败不算异常终局</b>：{@code AgentPipeline} 已把它收敛成 {@code StopReason#LLM_ERROR} 的正常回合 ⇒ 走规则 1，
   * 模型面如实看到"模型坏了"（这正是 C 块要的）。
   *
   * <p><b>S5-E 的异常退出日志保留</b>：无终局记录且拿到诊断文本（子进程 stderr 尾部）时记一行 WARN——"干完活正常退"与"启动即崩"
   * 在事件面上不能再同形。诊断与子库读取都在<b>锁外</b>做（前者读子进程输出尾部有自己一把锁，后者是文件 IO）， 持锁做等于把两条锁序接起来。
   */
  private void onChildExited(String instanceId, LaunchedSubagent handle) {
    String diagnostics = diagnosticsOf(handle);
    Integer exitCode = exitCodeOf(handle);
    // 子库读取是文件 IO，锁外做：读到什么就是什么，进锁后只做状态迁移
    TerminalOutcome record =
        readChildTerminalRecord(instanceId)
            .map(
                found ->
                    new TerminalOutcome(
                        found.stopReason(), found.turns(), found.toolCalls(), exitCode))
            .orElse(null);
    boolean abnormal;
    synchronized (lock) {
      SubagentInstance current = instances.get(instanceId);
      if (current == null || current.status().isFinal()) {
        return; // 幂等：另一条收束路径已落定（或实例从未建立），不重复迁移
      }
      TerminalOutcome outcome =
          record != null ? record : new TerminalOutcome(null, null, null, exitCode);
      outcomes.put(instanceId, outcome);
      if (record != null) {
        abnormal = false;
        transition(
            instanceId,
            SubagentStatus.FINISHED,
            terminalPayload(current, "finished", null, outcome));
      } else {
        switch (current.status()) {
          case RUNNING -> {
            abnormal = true;
            transition(
                instanceId,
                SubagentStatus.FAILED,
                terminalPayload(current, "failed", NO_TERMINAL_RECORD_REASON, outcome));
          }
          case TERMINATING -> {
            abnormal = false;
            transition(
                instanceId,
                SubagentStatus.KILLED,
                terminalPayload(current, "killed", null, outcome));
          }
          // 设计 §2.3 表第 4 行（SPAWNING 退出 ⇒ FAILED "subagent 退出于确认存活之前"）。
          // <b>本分支今天不可达，是防御分支，刻意保留</b>（2026-09-14 复核，非"删掉凑覆盖"）——退出事实只有两个
          // 投递口，各自都到不了早期状态：①armWatch/watchExit 只在 RUNNING 转换之后才挂（见 spawn:388，且 RUNNING
          // 转换撞终态时走 catch 分支、不挂观测）；②terminate 的 awaitDead 路径只在状态已到 TERMINATING 之后调它
          // （SPAWNING 那条走 kill-before-running，同锁内先转 FAILED 并 return，根本不投递退出事实）。
          // CONFIGURED 则被 spawn 的同锁两转换封窗、对外不可观测。
          // 保留的理由：万一将来出现新的退出事实来源（新执行器/新的状态路径），这里必须按规则 4 记 FAILED，
          // 而不是让退出事实落到别处被静默吞掉。它同时是 `EXIT_BEFORE_RUNNING_REASON` 的唯一引用点。
          default -> {
            abnormal = true;
            transition(
                instanceId,
                SubagentStatus.FAILED,
                terminalPayload(current, "failed", EXIT_BEFORE_RUNNING_REASON, outcome));
          }
        }
      }
    }
    if (abnormal && diagnostics != null) {
      // 锁外记日志：诊断文本可能很长，别让它占着状态机的锁
      LOG.warn("子 Agent 异常退出（未留下正常终局）: id={} 原因: {}", instanceId, diagnostics);
    }
  }

  /**
   * 子库终局记录（设计 §2.1 的 {@code agent.lifecycle/action=finished}）：<b>父侧终局判定的唯一判据</b>。
   *
   * <p>读取口径（只读、不建表、不迁移）：
   *
   * <ul>
   *   <li>路径 = {@code <childDataRoot>/<实例 id>/events.db}（与子进程的 {@code --data-dir} 同源），拼装统一走 {@link
   *       #childDbPath(Path, String)}——越根 id 在那里被挡，本方法按"没留下终局记录"收（fail-closed，不读根外文件）；
   *   <li>库不存在 / 未注入根 / 读失败 ⇒ {@code empty}（并按"未留下终局记录"处理，读失败只在父侧日志留痕，不进模型面）；
   *   <li><b>兼容老库</b>：事件必须<b>带</b> {@code stopReason} 键才认——老事件（无该键）⇒ 按"未留下终局记录"处理，<b>不编造</b>；
   *   <li>{@code turns}/{@code toolCalls} 缺失 ⇒ {@code null}（不填 0）。
   * </ul>
   */
  private Optional<TerminalOutcome> readChildTerminalRecord(String instanceId) {
    if (childDataRoot == null) {
      return Optional.empty(); // 装配层没注入根：读不到就等于没留下（fail-closed），不猜路径
    }
    Path db;
    try {
      // 路径拼装与越根防护<b>只此一份</b>（childDbPath）：本方法与 read_agent_context 是同一个口径的两个读侧，
      // 两处各拼一遍路径正是"同族缺陷温床"（2026-09-14 收敛）。越根 ⇒ 当"没留下终局记录"（fail-closed，不读根外文件）。
      db = childDbPath(childDataRoot, instanceId);
    } catch (RuntimeException e) {
      LOG.warn("子 Agent 终局记录路径越出子库根（按未留下终局记录处理）: id={}", instanceId, e);
      return Optional.empty();
    }
    if (!Files.isRegularFile(db)) {
      return Optional.empty();
    }
    try (SqliteEventStore store = SqliteEventStore.openReadOnly(db)) {
      // 倒序取最近若干条 lifecycle（终局记录必在尾部），命中第一条带 stopReason 的 finished
      for (Event event :
          store.query(
              new EventQuery(
                  instanceId, EventTypes.AGENT_LIFECYCLE, instanceId, -1, TERMINAL_SCAN_LIMIT))) {
        JsonNode payload;
        try {
          payload = JSON.readTree(event.payload());
        } catch (java.io.IOException e) {
          continue; // 单条坏 payload 不该让整个判定崩（同 AgentContextReader 口径）
        }
        if (payload == null
            || !TERMINAL_ACTION.equals(payload.path("action").asText())
            || payload.path("stopReason").asText().isBlank()) {
          continue;
        }
        return Optional.of(
            new TerminalOutcome(
                payload.path("stopReason").asText(),
                intField(payload, "turns"),
                intField(payload, "toolCalls"),
                null)); // 退出码不在子库里：由调用方从句柄补（拿不到就是 null）
      }
      return Optional.empty();
    } catch (RuntimeException e) {
      LOG.warn("子 Agent 终局记录读取失败（按未留下终局记录处理）: id={} db={}", instanceId, db, e);
      return Optional.empty();
    }
  }

  /**
   * 子库路径：{@code <root>/<instanceId>/events.db}（与装配层交给子进程的 {@code --data-dir} 同源），并挡掉越出根目录的 id。
   *
   * <p><b>为什么放在这里、且只有这一份</b>（2026-09-14 收敛）：拼路径的规则是"子库布局"这一件事，之前两处实现（本类的 {@code
   * readChildTerminalRecord} 与 {@code
   * SubagentOrchestrationTools.read_agent_context}）已经分叉——后者有越根防护、前者没有。
   * 同一口径两处实现是<b>同族缺陷温床</b>（改动只落一处、另一处静默过期），所以收敛成一个包内静态函数，工具侧直接调它。 模板 id 由模板文件给出，不排除被人为写成 {@code
   * ../..}；判定层只认"已知实例"，这里是<b>纵深防御</b>。
   *
   * @throws IllegalStateException id 归一化后不在根目录之下（调用方按各自的 fail-closed 口径处理：工具面 {@code
   *     CONTEXT_UNAVAILABLE}，终局判定按"未留下终局记录"）
   */
  static Path childDbPath(Path root, String instanceId) {
    Path normalizedRoot = root.normalize();
    Path dir = normalizedRoot.resolve(instanceId).normalize();
    if (!dir.startsWith(normalizedRoot)) {
      throw new IllegalStateException("实例 id 越出子库根目录: " + instanceId);
    }
    return dir.resolve("events.db");
  }

  /** 终局事件 payload：终局字段<b>有才写</b>（{@link TerminalOutcome#fields()}）。 */
  private static Map<String, Object> terminalPayload(
      SubagentInstance instance, String action, String reason, TerminalOutcome outcome) {
    Map<String, Object> payload =
        lifecyclePayload(action, instance.templateId(), instance.depth(), null, reason);
    payload.putAll(outcome.fields());
    return payload;
  }

  /** 数字字段（缺失/非数字 ⇒ {@code null}，不编 0）。 */
  private static Integer intField(JsonNode payload, String key) {
    JsonNode value = payload.get(key);
    return value != null && value.isNumber() ? value.asInt() : null;
  }

  /** 退出码（{@code null} = 拿不到）：<b>不编 0</b>——拿不到就当没有，事件里不写这个键（§四.6）。 */
  private static Integer exitCodeOf(LaunchedSubagent handle) {
    if (handle == null) {
      return null;
    }
    try {
      java.util.OptionalInt code = handle.exitCode();
      return code.isPresent() ? code.getAsInt() : null;
    } catch (RuntimeException e) {
      LOG.warn("子 Agent 退出码获取失败（按拿不到处理，不编 0）", e);
      return null;
    }
  }

  /** 退出诊断文本（null = 拿不到）：观测线程专用，本方法与实现都不得抛——诊断不许把状态机观测带崩。 */
  private static String diagnosticsOf(LaunchedSubagent handle) {
    if (handle == null) {
      return null;
    }
    try {
      return handle.exitDiagnostics().filter(text -> !text.isBlank()).orElse(null);
    } catch (RuntimeException e) {
      LOG.warn("子 Agent 退出诊断获取失败（忽略，继续收束状态）", e);
      return null;
    }
  }

  /** {@link #awaitTerminal(String, long)} 的<b>句柄级特例</b>（设计 §2.5）：同一个骨架，条件换成"句柄死亡"。 */
  private static boolean awaitDead(LaunchedSubagent handle, long maxMillis) {
    return pollUntil(() -> !handle.isAlive(), maxMillis);
  }

  /**
   * 100 ms 轮询骨架（设计 §2.5）：{@code awaitTerminal} 与 {@code awaitDead} 共用——<b>不占锁</b>（每次只短暂取锁读快照， 关停路径的
   * 10s 宽限等待不在这里）。只做"等/不等得到"这一件事，超时与中断都不抛异常。
   *
   * <p>条件先判、后判超时：预算为 0 时先看当刻事实（"已经满足"不该被 0 预算吞掉），再原地返回。
   *
   * @return true = 条件在预算内成立；false = 超时或中断（此时条件<b>不</b>成立——中断前再判一次）
   */
  private static boolean pollUntil(BooleanSupplier condition, long timeoutMillis) {
    long budgetNanos =
        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
    long started = System.nanoTime();
    while (true) {
      if (condition.getAsBoolean()) {
        return true;
      }
      if (System.nanoTime() - started >= budgetNanos) {
        return false;
      }
      try {
        Thread.sleep(EXIT_POLL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return condition.getAsBoolean();
      }
    }
  }

  private static void closeQuietly(LaunchedSubagent handle) {
    if (handle == null) {
      return;
    }
    try {
      handle.close();
    } catch (RuntimeException e) {
      LOG.warn("子 Agent 句柄关闭异常", e);
    }
  }
}
