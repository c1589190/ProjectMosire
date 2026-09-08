package io.mosire.brain.subagent;

import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.proc.SubprocessManager;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.EventTypes;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子 Agent 编排根（计划 §4.4 / 中期计划 W3 组件 2）：spawn / 查询 / kill 的单一入口，状态机、权限 单调性（红线 1）、深度限制（默认 4）、防跑飞 cap
 * 派生与事件链全部在本类闭环。
 *
 * <p><strong>状态机</strong>：{@code
 * CONFIGURED→SPAWNING→RUNNING→(TERMINATING)→(FINISHED|FAILED|KILLED)}， 每次合法推进发一条 {@code
 * agent.lifecycle} 事件（correlationId = 子 Agent 实例 id）；非法推进直接抛 {@link
 * IllegalStateException}（"非法推进直接抛错而非静默漂移"——SubagentStatus 设计约定）。崩溃恢复 （M3）= 事件重放，本期只落事件、不写恢复逻辑。
 *
 * <p><strong>spawn 检查顺序</strong>（中期计划 W3）：模板存在 → 权限单调性 （{@link PermissionChecker#isSubset}，拒绝即
 * {@link SubagentRejectedException} + {@code permission.denied} 事件）→ 深度（{@code parentDepth+1 >
 * DEFAULT_MAX_DEPTH} 拒绝，{@code decision:DEPTH_LIMIT} 事件）→ launcher 启动。cap 派生：{@code min(模板, 请求收紧,
 * 父级)}——子级只能更小（D10 硬顶的" 单调性第四维度"）。
 *
 * <p><strong>kill</strong>（红线 5：kill 是 Manager 的编排入口，不是"工具关进程"）：RUNNING 一步转为 TERMINATING（事件）→ 经
 * {@link SubagentLauncher} 窄缝关闭（真实实现即三层关停：关 stdin → SIGTERM → 宽限 → 树级 SIGKILL）→ 终态确认 →
 * KILLED（事件）；kill-before-running 按 {@link SubagentStatus#FAILED} 语义收束。终结后幂等（重复 kill 是空操作）。Manager
 * 不持有 {@link SubprocessManager}——它归装配层（Main） 持有。
 *
 * <p><strong>退出观测</strong>:WIP {@link LaunchedSubagent} 只暴露 {@code isAlive()/close()}（窄缝不泄漏
 * onExit/退出码——决策回溯见其 Javadoc），本类以一个每实例虚拟线程做轮询桥接 ({@value #EXIT_POLL_MILLIS}ms)，自然退出 →
 * FINISHED、关停中退出 → KILLED；W3b 接协议层后（MCP stdio 终止/退出码回报）可换为事件驱动，桥接点收敛在 {@link #onChildExited}
 * 一处。管理核心仍单线程语义： 所有状态转移在同一把私有锁下原子完成，观测线程只投递"已退出"事实。
 *
 * <p><strong>事件面</strong>：生命周期事件 {@code agent=childId, correlationId=childId}；拒绝类事件 {@code agent=父级
 * id, correlationId=拟生成的子实例 id}（查询可串起"尝试+转移"全程）。
 */
public final class SubagentManager implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(SubagentManager.class);

  /** 子 Agent 深度上限默认值（计划 §4.4 / 中期计划 W3：默认 4；根即主 Agent = 0）。 */
  public static final int DEFAULT_MAX_DEPTH = 4;

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
  private final int parentDepth;

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
    this.templateStore = Objects.requireNonNull(templateStore, "templateStore");
    this.launcher = Objects.requireNonNull(launcher, "launcher");
    this.events = Objects.requireNonNull(events, "events");
    this.bus = Objects.requireNonNull(bus, "bus");
    this.parentConfig = Objects.requireNonNull(parentConfig, "parentConfig");
    this.parentPermissions = Objects.requireNonNull(parentPermissions, "parentPermissions");
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
   * @return RUNNING 时的实例快照（此后记录以 manager 内 map 为准）
   * @throws SubagentRejectedException 模板不存在 / 权限越权（+{@code permission.denied} 事件）/ 深度超限 （+{@code
   *     decision:DEPTH_LIMIT} 事件）
   * @throws SubagentLaunchException launcher 无法拉起子进程（+lifecycle FAILED 事件）
   */
  public SubagentInstance spawn(SubagentLaunchRequest request) {
    ensureOpen();
    AgentTemplate template =
        templateStore
            .get(request.templateId())
            .orElseThrow(
                () -> new SubagentRejectedException("子 Agent 模板不存在: " + request.templateId()));
    String instanceId = instanceIdOf(template.id());
    AgentPermissionSet permissions = tightenPermissions(template, request);
    checkMonotonicity(template, instanceId, permissions);
    int depth = parentDepth + 1;
    checkDepth(template, instanceId, depth);
    AgentConfig childConfig = deriveConfig(template, request, instanceId, permissions);

    SubagentInstance configured =
        new SubagentInstance(
            instanceId,
            template.id(),
            request.goal(),
            childConfig,
            permissions,
            depth,
            SubagentStatus.CONFIGURED);
    SubagentInstance running;
    LaunchedSubagent handle = null;
    try {
      // 两转换同锁封窗：CONFIGURED 记录建立（+configured 事件）与 SPAWNING 推进置于同一临界区——CONFIGURED
      // 状态对其它线程不可观测（锁上只能看到 SPAWNING 及之后），kill 的 terminate 分支结构上不可能读到
      // CONFIGURED，从而消除"kill 落在 CONFIGURED 窗口"竞态（历史上 terminate 对 CONFIGURED 推 FAILED
      // 会因 canTransition 不允许 CONFIGURED→FAILED 而抛裸 ISE）。
      synchronized (lock) {
        ensureOpen();
        instances.put(instanceId, configured);
        emitLifecycle(instanceId, template.id(), "configured", depth, request.goal(), null);
        running =
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

  /** 权限收紧：模板权限集 + 请求 extraDenied（只增不减，白名单永不放大——SubagentLaunchRequest 语义）。 */
  private static AgentPermissionSet tightenPermissions(
      AgentTemplate template, SubagentLaunchRequest request) {
    AgentPermissionSet base = template.toPermissionSet();
    if (request.extraDenied().isEmpty()) {
      return base;
    }
    Set<String> denied = new HashSet<>(base.deniedTools());
    denied.addAll(request.extraDenied());
    return new AgentPermissionSet(
        base.grantedToken(),
        base.allowedTools(),
        Set.copyOf(denied),
        base.destructiveAllowed(),
        base.sensitiveAllowed(),
        base.readOnly());
  }

  /**
   * 红线 1：权限单调性（{@link PermissionChecker#isSubset} 是判定真值，本方法只负责"拒绝 + 事件"的 编排；具体维度诊断仅供消息可读性，判定不分叉）。
   */
  private void checkMonotonicity(
      AgentTemplate template, String instanceId, AgentPermissionSet permissions) {
    if (PermissionChecker.isSubset(permissions, parentPermissions)) {
      return;
    }
    String message =
        "子 Agent 权限超出父级许可（红线 1 单调性）: " + nonSubsetReason(permissions, parentPermissions);
    emit(
        EventTypes.PERMISSION_DENIED,
        parentConfig.id(),
        instanceId,
        Map.of("template", template.id(), "childId", instanceId, "reason", message));
    throw new SubagentRejectedException(message);
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
    return "权限集不在父级范围内";
  }

  /** 深度限制（默认 4，超限拒绝 + decision:DEPTH_LIMIT 事件）。 */
  private void checkDepth(AgentTemplate template, String instanceId, int depth) {
    if (depth <= DEFAULT_MAX_DEPTH) {
      return;
    }
    String message = "子 Agent 深度超限: depth=" + depth + " > max=" + DEFAULT_MAX_DEPTH;
    emit(
        EventTypes.DECISION,
        parentConfig.id(),
        instanceId,
        Map.of(
            "decision", "DEPTH_LIMIT",
            "template", template.id(),
            "childId", instanceId,
            "depth", depth,
            "maxDepth", DEFAULT_MAX_DEPTH));
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
          onChildExited(instanceId);
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

  /** 已退出事实投递（观测线程）：RUNNING→FINISHED（自然完成）；TERMINATING→KILLED；余为防御性 FAILED。 */
  private void onChildExited(String instanceId) {
    synchronized (lock) {
      SubagentInstance current = instances.get(instanceId);
      if (current == null || current.status().isFinal()) {
        return;
      }
      switch (current.status()) {
        case RUNNING ->
            transition(
                instanceId,
                SubagentStatus.FINISHED,
                lifecyclePayload("finished", current.templateId(), current.depth(), null, null));
        case TERMINATING ->
            transition(
                instanceId,
                SubagentStatus.KILLED,
                lifecyclePayload("killed", current.templateId(), current.depth(), null, null));
        default ->
            transition(
                instanceId,
                SubagentStatus.FAILED,
                lifecyclePayload(
                    "failed", current.templateId(), current.depth(), null, "subagent 退出于确认存活之前"));
      }
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
