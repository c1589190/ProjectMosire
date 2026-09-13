package io.mosire.brain.subagent;

import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.EventWrite;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
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
 * <p><strong>spawn 检查顺序</strong>（中期计划 W3）：模板存在 → <b>工作目录越界</b>（S5-B，请求面要了调用者够不着的地方 ⇒ {@link
 * WorkingDirDeniedException} + {@code permission.denied} 事件）→ 权限单调性 （{@link
 * PermissionChecker#isSubset}，拒绝即 {@link SubagentRejectedException} + {@code permission.denied}
 * 事件）→ 档位单调性（S6，{@code decision} 无事件、拒绝走 {@code permission.denied}）→ 深度（{@code 调用者depth+1 > 子深度上限}
 * 拒绝，{@code decision:DEPTH_LIMIT} 事件）→〔锁内〕繁殖预算（{@code decision:BUDGET_EXHAUSTED} 事件）→ launcher
 * 启动。cap 派生： {@code min(模板, 请求收紧, 父级)}——子级只能更小（D10 硬顶的"单调性第四维度"）。
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
 * <p><strong>退出观测</strong>：{@link LaunchedSubagent} 只暴露 {@code isAlive()/close()} 与诊断面 {@code
 * exitDiagnostics()}（窄缝不泄漏 onExit/退出码——决策回溯见其 Javadoc），本类以一个每实例虚拟线程做轮询桥接 ({@value
 * #EXIT_POLL_MILLIS}ms)，自然退出 → FINISHED、关停中退出 → KILLED；W3b 接协议层后（MCP stdio 终止/退出码回报）可换为事件驱动，桥接点收敛在
 * {@link #onChildExited} 一处。管理核心仍单线程语义： 所有状态转移在同一把私有锁下原子完成，观测线程只投递"已退出"事实。
 *
 * <p><strong>事件面</strong>：生命周期事件 {@code agent=childId, correlationId=childId}；拒绝类事件 {@code agent=父级
 * id, correlationId=拟生成的子实例 id}（查询可串起"尝试+转移"全程）。
 */
public final class SubagentManager implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(SubagentManager.class);

  /** 退出观测轮询间隔（毫秒）。 */
  private static final long EXIT_POLL_MILLIS = 100L;

  /** kill 终态确认宽限（进程三层关停内已等过一次，这里只做收尾确认）。 */
  private static final long KILL_CONFIRM_MILLIS = SubprocessManager.DEFAULT_GRACE.toMillis();

  private final AgentTemplateStore templateStore;
  private final SubagentLauncher launcher;
  private final EventStore events;
  private final EventBus bus;
  private final AgentConfig parentConfig;
  private final AgentPermissionSet parentPermissions;
  private final Supplier<CommandMode> parentMode;
  private final int parentDepth;
  private final SubagentLimits limits;

  /** 私有锁：状态记录/事件顺序/句柄表全部在锁内原子推进（同 SubprocessManager/USO 理由）。 */
  private final Object lock = new Object();

  private final Map<String, SubagentInstance> instances = new HashMap<>();
  private final Map<String, LaunchedSubagent> handles = new HashMap<>();
  private final Set<Thread> watchers = new HashSet<>();

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
   * <p>检查顺序（中期计划 W3）：模板存在 → 权限单调性 → 深度限制 → cap 派生 → 状态机 CONFIGURED→SPAWNING→RUNNING。启动失败（{@link
   * SubagentLaunchException}）把状态推进到 FAILED 并 原样传播（WIP 异常 Javadoc）。
   *
   * <p><b>S5-A 起"谁在要"进入判定</b>：本 3 参形态 = "主 Agent 在要"（口径逐字等于本段之前的行为——身份取 {@code
   * parentConfig.id()}、权限对照取构造期的 {@code parentPermissions}、深度取构造期的 {@code parentDepth}）。 经 MCP
   * 链接进来的下级调用走 {@link #spawn(SubagentLaunchRequest, AgentIdentity, AgentPermissionSet)}：那一支的对照物是
   * <b>调用者自己</b>，而不是这个 manager 属于谁。
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
   * @param caller 调用者身份（实例 id + 档位 + 深度；宿主绑定，模型给不出）
   * @param callerPermissions 调用者自己的权限集（单调性对照真值 + 资源可达面的来源）
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
    AgentPermissionSet permissions =
        tightenPermissions(template, request, callerPermissions, instanceId, caller.instanceId());
    checkMonotonicity(template, instanceId, permissions, callerPermissions, caller.instanceId());
    CommandMode mode = resolveMode(template, instanceId, request, caller);
    int depth = caller.depth() + 1;
    checkDepth(template, instanceId, depth, caller.instanceId());
    AgentConfig childConfig = deriveConfig(template, request, instanceId, permissions);

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
            caller.instanceId());
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
      live =
          instances.values().stream()
              .filter(i -> !i.status().isFinal())
              .map(SubagentInstance::instanceId)
              .toList();
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
   * 权限收紧：模板权限集 + 请求 extraDenied（只增不减，白名单永不放大——SubagentLaunchRequest 语义）+ <b>资源可达面物化</b> （S5-B：调用者 ∩
   * 模板建议 ∩ 请求，逐命名空间落成子体的具体取值）。
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
    AgentPermissionSet base = template.toPermissionSet();
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
      synchronized (lock) {
        SubagentInstance current = instances.get(instanceId);
        if (current != null && !current.status().isFinal()) {
          transition(
              instanceId,
              SubagentStatus.KILLED,
              lifecyclePayload("killed", current.templateId(), current.depth(), null, null));
        }
      }
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
   * 已退出事实投递（观测线程）：RUNNING→FINISHED（自然完成）；TERMINATING→KILLED；余为防御性 FAILED。
   *
   * <p><b>S5-E：异常退出要留一行原因</b>。子体在确认存活之后退出，过去在事件面与日志上同形——"干完活正常退"与"启动即崩"都只有 一个 {@code
   * finished}（实测：子体拿到解析不到的相对 {@code --templates-dir} 当场退出，父侧零行日志）。现在<b>非零退出</b>会在父侧日志 留一行 WARN，文本取自
   * {@link LaunchedSubagent#exitDiagnostics()}（子进程 stderr 尾部）。
   *
   * <p><b>只记日志，不动状态</b>：状态说的是"这个子体的生命周期走到哪了"（确认存活后退出 = 终态），退出码是<b>诊断面</b>。 把非零一律翻成 FAILED
   * 反而制造状态谎言（子体可以跑完目标再以非零码退栈），而且需求/预算/事件账目都挂在状态上。对应的口径是既有先例： 模型只见粗类，细节进日志。
   *
   * <p>TERMINATING（我们自己要求的关停）<b>不记</b>：那里的非零退出（SIGTERM 惯常 143）是预期形态，记了就是噪声——噪声里再出问题没人看。
   *
   * <p>诊断在<b>锁外</b>取：它读子进程输出尾部（自己一把锁），而输出泵在"输出超限"路径上会经 stopGracefully → 移除回调进本类的锁——
   * 持锁取诊断等于把两条锁序接起来。诊断是尽力而为的面，取不到照样走状态机。
   */
  private void onChildExited(String instanceId, LaunchedSubagent handle) {
    String diagnostics = diagnosticsOf(handle);
    boolean abnormal;
    synchronized (lock) {
      SubagentInstance current = instances.get(instanceId);
      if (current == null || current.status().isFinal()) {
        return;
      }
      switch (current.status()) {
        case RUNNING -> {
          abnormal = true;
          transition(
              instanceId,
              SubagentStatus.FINISHED,
              lifecyclePayload("finished", current.templateId(), current.depth(), null, null));
        }
        case TERMINATING -> {
          abnormal = false;
          transition(
              instanceId,
              SubagentStatus.KILLED,
              lifecyclePayload("killed", current.templateId(), current.depth(), null, null));
        }
        default -> {
          abnormal = true;
          transition(
              instanceId,
              SubagentStatus.FAILED,
              lifecyclePayload(
                  "failed", current.templateId(), current.depth(), null, "subagent 退出于确认存活之前"));
        }
      }
    }
    if (abnormal && diagnostics != null) {
      // 锁外记日志：诊断文本可能很长，别让它占着状态机的锁
      LOG.warn("子 Agent 异常退出（未留下正常终局）: id={} 原因: {}", instanceId, diagnostics);
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

  private static boolean awaitDead(LaunchedSubagent handle, long maxMillis) {
    long deadline = System.nanoTime() + maxMillis * 1_000_000L;
    try {
      while (System.nanoTime() < deadline) {
        if (!handle.isAlive()) {
          return true;
        }
        Thread.sleep(EXIT_POLL_MILLIS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return !handle.isAlive();
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
