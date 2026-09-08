package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.EventTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SubagentManager} 状态机/事件链/守卫/关停编排的行为契约（全离线：InProcessExecutor 与 RecordingLauncher，无真实子进程）。
 */
class SubagentManagerTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  private SqliteEventStore store;
  private EventBus bus;
  private Path agentsDir;

  @BeforeEach
  void setUp() throws Exception {
    agentsDir = tempDir.resolve("agents");
    Files.createDirectories(agentsDir);
    store = SqliteEventStore.open(tempDir.resolve("events.db"));
    bus = new EventBus();
  }

  @AfterEach
  void tearDown() {
    bus.close();
    store.close();
  }

  @Test
  void spawnSuccessDrivesFullStateMachineAndPersistsEveryTransition() throws Exception {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    AgentTemplateStore templateStore = store();
    InProcessExecutor executor = new InProcessExecutor();
    SubagentManager manager =
        manager(templateStore, executor, parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(
            new SubagentLaunchRequest("reader", "通读 README 并汇报结构", null, null, null, null));
    String id = spawned.instanceId();

    assertThat(spawned.status()).isEqualTo(SubagentStatus.RUNNING);
    assertThat(spawned.templateId()).isEqualTo("reader");
    assertThat(spawned.depth()).isEqualTo(1);
    assertThat(spawned.config().id()).isEqualTo(id);
    assertThat(manager.list()).extracting(SubagentInstance::instanceId).containsExactly(id);

    awaitStatus(manager, id, SubagentStatus.FINISHED);

    // get(id) 以 manager 内最新记录为准（watcher 可能已推进到 FINISHED）
    assertThat(manager.get(id)).hasValueSatisfying(i -> assertThat(i.instanceId()).isEqualTo(id));
    assertThat(manager.get(id))
        .hasValueSatisfying(i -> assertThat(i.status()).isEqualTo(SubagentStatus.FINISHED));

    List<String> actions = lifecycleActions(id);
    assertThat(actions).containsExactly("configured", "spawning", "running", "finished");

    // 每步转移的事件都落了库（链路按 seq 单调递增，correlationId=子 Agent id）
    List<Event> chain = lifecycleEvents(id);
    assertThat(chain).hasSize(4);
    assertThat(chain.get(0).correlationId()).isEqualTo(id);
    assertThat(chain.get(0).agent()).isEqualTo(id);
    assertThat(payloadOf(chain.get(0)))
        .containsEntry("action", "configured")
        .containsEntry("template", "reader")
        .containsEntry("depth", 1)
        .containsEntry("goalLength", "通读 README 并汇报结构".length());
    assertThat(manager.get(id))
        .hasValueSatisfying(i -> assertThat(i.status()).isEqualTo(SubagentStatus.FINISHED));
  }

  @Test
  void privilegeEscalationIsRejectedAndEmitsPermissionDenied() {
    AgentTemplate template =
        template("writer", Set.of("read", "write"), Set.of(), false, false, false);
    write(template);
    AgentTemplateStore templateStore = store();
    // 父级（主 Agent）白名单只有 read——子级要 write 即越权
    AgentPermissionSet parentPermissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("read").build();
    SubagentManager manager =
        manager(templateStore, new InProcessExecutor(), parentConfig(), parentPermissions, 0);

    assertThatThrownBy(
            () -> manager.spawn(new SubagentLaunchRequest("writer", "写文件", null, null, null, null)))
        .isInstanceOf(SubagentRejectedException.class)
        .hasMessageContaining("单调性");

    List<Event> denied = query(EventTypes.PERMISSION_DENIED);
    assertThat(denied).hasSize(1);
    Event event = denied.get(0);
    assertThat(event.correlationId()).startsWith("writer");
    assertThat(event.agent()).isEqualTo("main");
    assertThat(payloadOf(event))
        .containsEntry("template", "writer")
        .containsEntry("childId", event.correlationId())
        .satisfies(p -> assertThat(p.get("reason").toString()).contains("单调性"));
  }

  @Test
  void depthBeyondDefaultIsRejectedWithDecisionEvent() {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    AgentTemplateStore templateStore = store();
    SubagentManager manager =
        manager(
            templateStore, new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 4);

    assertThatThrownBy(
            () ->
                manager.spawn(new SubagentLaunchRequest("reader", "深链调查", null, null, null, null)))
        .isInstanceOf(SubagentRejectedException.class)
        .hasMessageContaining("深度");

    List<Event> decisions = query(EventTypes.DECISION);
    assertThat(decisions).hasSize(1);
    Event event = decisions.get(0);
    assertThat(event.correlationId()).startsWith("reader");
    assertThat(payloadOf(event))
        .containsEntry("decision", "DEPTH_LIMIT")
        .containsEntry("template", "reader")
        .containsEntry("depth", 5)
        .containsEntry("maxDepth", SubagentManager.DEFAULT_MAX_DEPTH);
  }

  @Test
  void depthAtDefaultLimitIsAllowed() {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    AgentTemplateStore templateStore = store();
    SubagentManager manager =
        manager(
            templateStore, new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 3);

    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("reader", "边界层", null, null, null, null));

    assertThat(spawned.depth()).isEqualTo(4);
    assertThat(manager.get(spawned.instanceId()))
        .hasValueSatisfying(i -> assertThat(i.depth()).isEqualTo(4));
  }

  @Test
  void killOrchestratesLauncherShutdownAndEmitsKilled() throws Exception {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    AgentTemplateStore templateStore = store();
    RecordingLauncher launcher = new RecordingLauncher();
    SubagentManager manager =
        manager(templateStore, launcher, parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("reader", "跑一个长任务", null, null, null, null));
    String id = spawned.instanceId();
    assertThat(spawned.status()).isEqualTo(SubagentStatus.RUNNING);
    assertThat(launcher.closeCalls()).isZero();

    assertThat(manager.kill(id)).isTrue();
    assertThat(launcher.closeCalls()).isEqualTo(1);
    awaitStatus(manager, id, SubagentStatus.KILLED);

    assertThat(lifecycleActions(id))
        .containsExactly("configured", "spawning", "running", "terminating", "killed");
    // 终态幂等：重复 kill 不再触发任何关停动作
    assertThat(manager.kill(id)).isFalse();
    assertThat(launcher.closeCalls()).isEqualTo(1);
    assertThat(manager.get(id))
        .hasValueSatisfying(i -> assertThat(i.status()).isEqualTo(SubagentStatus.KILLED));
  }

  @Test
  void killDuringSpawningReturnsFailedSnapshotNotRawIllegalState() throws Exception {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    AgentTemplateStore templateStore = store();
    GatedLauncher launcher = new GatedLauncher();
    SubagentManager manager =
        manager(templateStore, launcher, parentConfig(), AgentPermissionSet.system(), 0);

    // spawn 卡在 launcher.launch（SPAWNING）期间被 kill → kill 推 FAILED；launch 返回后 spawn 的 RUNNING
    // 推进必然撞终态——必须收敛为 FAILED 快照、不得把裸 IllegalStateException 抛给调用方、孤儿句柄只关一次
    CompletableFuture<SubagentInstance> spawnFuture =
        CompletableFuture.supplyAsync(
            () -> manager.spawn(new SubagentLaunchRequest("reader", "任务", null, null, null, null)));

    launcher.awaitLaunchStarted(2, TimeUnit.SECONDS);
    String id;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (true) {
      List<SubagentInstance> all = manager.list();
      if (!all.isEmpty()) {
        id = all.get(0).instanceId();
        break;
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError("spawn 未建立实例记录");
      }
      Thread.sleep(20);
    }
    assertThat(manager.list().get(0).status()).isEqualTo(SubagentStatus.SPAWNING);
    assertThat(manager.kill(id)).isTrue();
    launcher.release();

    SubagentInstance result = spawnFuture.get(5, TimeUnit.SECONDS);
    assertThat(result.status()).isEqualTo(SubagentStatus.FAILED);
    assertThat(manager.get(id))
        .hasValueSatisfying(i -> assertThat(i.status()).isEqualTo(SubagentStatus.FAILED));
    assertThat(lifecycleActions(id)).containsExactly("configured", "spawning", "failed");
    // 句柄在 spawn 收尾路径关闭一次（kill 抢先时句柄尚未入表，未二次关停）
    assertThat(launcher.closeCalls()).isEqualTo(1);
  }

  @Test
  void childCapsAreDerivedFromTemplateRequestAndParentOnlySmaller() {
    AgentTemplate template =
        template("sleuth", Set.of("read"), Set.of(), true, false, true, 2000, 30, 600, 2000);
    write(template);
    AgentTemplateStore templateStore = store();
    AgentConfig parentConfig =
        AgentConfig.builder("main")
            .maxTurns(100)
            .maxToolCallsPerTurn(7)
            .timeBudget(Duration.ofSeconds(120))
            .quotaMaxTokens(800)
            .build();
    RecordingLauncher launcher = new RecordingLauncher();
    SubagentManager manager =
        manager(templateStore, launcher, parentConfig, AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("sleuth", "调查", Set.of("rm"), 50, 60L, 200L));

    AgentConfig child = spawned.config();
    assertThat(child.maxTurns()).isEqualTo(50); // min(模板 2000, 请求 50, 父 100)
    assertThat(child.maxToolCallsPerTurn()).isEqualTo(7); // min(模板 30, 父 7)
    assertThat(child.timeBudget()).isEqualTo(Duration.ofSeconds(60)); // min(600, 请求 60, 父 120)
    assertThat(child.quotaMaxTokens()).isEqualTo(200); // min(2000, 请求 200, 父 800)
    assertThat(child.id()).isEqualTo(spawned.instanceId());
    assertThat(child.allowedTools()).containsExactly("read");
    assertThat(child.deniedTools()).contains("rm");
    assertThat(child.systemPrompt()).isEqualTo(template.systemPrompt());
    // 传给 executor 的正是这份收紧后的配置
    assertThat(launcher.lastConfig()).isSameAs(child);
    // 权限集与配置同源收紧：extraDenied 并入 denied
    assertThat(spawned.permissions().deniedTools()).contains("rm");
    // 收紧后仍落在父级之内
    assertThat(
            io.mosire.agentlib.permission.PermissionChecker.isSubset(
                spawned.permissions(), AgentPermissionSet.system()))
        .isTrue();
  }

  @Test
  void parentUnlimitedQuotaDoesNotBindButParentTightCapStillWins() {
    AgentTemplate template =
        template("sleuth", Set.of("read"), Set.of(), true, false, true, 2000, 30, 600, 2000);
    write(template);
    AgentTemplateStore templateStore = store();
    // 父级 quota=0（不限）；时间预算父 60 秒
    AgentConfig parentConfig =
        AgentConfig.builder("main")
            .timeBudget(Duration.ofSeconds(60))
            .quotaMaxTokens(AgentConfig.NO_QUOTA)
            .build();
    SubagentManager manager =
        manager(
            templateStore, new InProcessExecutor(), parentConfig, AgentPermissionSet.system(), 0);

    // 请求的配额收紧比模板松（1500 < 2000 但 > 0=不限）仍生效；时间预算请求 90 > 父 60 → 取父
    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("sleuth", "调查", Set.of(), null, 90L, 1500L));

    assertThat(spawned.config().quotaMaxTokens()).isEqualTo(1500);
    assertThat(spawned.config().timeBudget()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void templateZeroQuotaIsUnconstrainedSoRequestTighteningStillApplies() {
    // 模板配额 0 = 不限（AgentTemplate 校验 ≥0）：请求收紧 1500 应保留，而非被 0 吞掉退回 "不限"
    AgentTemplate template =
        template("sleuth", Set.of("read"), Set.of(), true, false, true, 2000, 30, 600, 0);
    write(template);
    AgentTemplateStore templateStore = store();
    RecordingLauncher launcher = new RecordingLauncher();
    SubagentManager manager =
        manager(templateStore, launcher, parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("sleuth", "调查", Set.of(), null, null, 1500L));

    assertThat(spawned.config().quotaMaxTokens()).isEqualTo(1500);
    // 模板/请求/父级均不限时仍是 0（不限）——有别于被 0 静默收紧
    assertThat(launcher.lastConfig()).isSameAs(spawned.config());
  }

  @Test
  void unknownTemplateIsRejectedBeforeAnyLifecycleEvent() {
    AgentTemplateStore templateStore = store();
    SubagentManager manager =
        manager(
            templateStore, new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);

    assertThatThrownBy(
            () -> manager.spawn(new SubagentLaunchRequest("ghost", "目标", null, null, null, null)))
        .isInstanceOf(SubagentRejectedException.class)
        .hasMessageContaining("不存在");
    assertThat(store.count()).isZero();
  }

  @Test
  void killUnknownIdThrowsAndGetUnknownReturnsEmpty() {
    AgentTemplateStore templateStore = store();
    SubagentManager manager =
        manager(
            templateStore, new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);

    assertThatThrownBy(() -> manager.kill("ghost")).isInstanceOf(IllegalArgumentException.class);
    assertThat(manager.get("ghost")).isEmpty();
  }

  @Test
  void managerCloseTerminatesAllRunningSubAgents() {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    AgentTemplateStore templateStore = store();
    RecordingLauncher launcher = new RecordingLauncher();
    SubagentManager manager =
        manager(templateStore, launcher, parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance first =
        manager.spawn(new SubagentLaunchRequest("reader", "任务一", null, null, null, null));
    SubagentInstance second =
        manager.spawn(new SubagentLaunchRequest("reader", "任务二", null, null, null, null));

    manager.close();

    assertThat(launcher.closeCalls()).isEqualTo(2);
    assertThat(manager.get(first.instanceId()))
        .hasValueSatisfying(i -> assertThat(i.status()).isEqualTo(SubagentStatus.KILLED));
    assertThat(manager.get(second.instanceId()))
        .hasValueSatisfying(i -> assertThat(i.status()).isEqualTo(SubagentStatus.KILLED));
    assertThat(lifecycleActions(first.instanceId())).contains("terminating", "killed");
    assertThat(lifecycleActions(second.instanceId())).contains("terminating", "killed");
    // close 幂等：不会对已终态子 Agent 二次关停
    manager.close();
    assertThat(launcher.closeCalls()).isEqualTo(2);
  }

  @Test
  void closeIsIdempotentAndSpawnAfterCloseRejected() {
    AgentTemplateStore templateStore = store();
    RecordingLauncher launcher = new RecordingLauncher();
    SubagentManager manager =
        manager(templateStore, launcher, parentConfig(), AgentPermissionSet.system(), 0);
    manager.close();
    manager.close();
    assertThat(launcher.selfCloseCalls()).isEqualTo(1);

    assertThatThrownBy(
            () -> manager.spawn(new SubagentLaunchRequest("reader", "目标", null, null, null, null)))
        .isInstanceOf(IllegalStateException.class);
  }

  // ---- helpers ----

  private AgentTemplateStore store() {
    AgentTemplateStore templateStore = new AgentTemplateStore(agentsDir);
    templateStore.load();
    return templateStore;
  }

  private SubagentManager manager(
      AgentTemplateStore templateStore,
      SubagentLauncher launcher,
      AgentConfig parentConfig,
      AgentPermissionSet parentPermissions,
      int parentDepth) {
    return new SubagentManager(
        templateStore, launcher, store, bus, parentConfig, parentPermissions, parentDepth);
  }

  private static AgentConfig parentConfig() {
    return AgentConfig.builder("main").build();
  }

  private void write(AgentTemplate template) {
    try {
      JSON.writeValue(agentsDir.resolve(template.id() + ".json").toFile(), template);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException("模板写入失败: " + template.id(), e);
    }
  }

  /** 测试模板工厂：默认只读收缩视角，逐字段覆盖。 */
  private static AgentTemplate template(
      String id,
      Set<String> allowedTools,
      Set<String> deniedTools,
      boolean destructiveAllowed,
      boolean sensitiveAllowed,
      boolean readOnly) {
    return template(
        id,
        allowedTools,
        deniedTools,
        destructiveAllowed,
        sensitiveAllowed,
        readOnly,
        2000,
        30,
        600,
        0);
  }

  private static AgentTemplate template(
      String id,
      Set<String> allowedTools,
      Set<String> deniedTools,
      boolean destructiveAllowed,
      boolean sensitiveAllowed,
      boolean readOnly,
      int maxTurns,
      int maxToolCallsPerTurn,
      long timeBudgetSeconds,
      long quotaMaxTokens) {
    return new AgentTemplate(
        id,
        "测试模板 " + id,
        "你是只读子 Agent " + id + "，只做调查与汇报。",
        "fake",
        AccessToken.DEFAULT,
        allowedTools,
        deniedTools,
        destructiveAllowed,
        sensitiveAllowed,
        readOnly,
        maxTurns,
        maxToolCallsPerTurn,
        timeBudgetSeconds,
        quotaMaxTokens);
  }

  private void awaitStatus(SubagentManager manager, String id, SubagentStatus status)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (manager.get(id).isPresent() && manager.get(id).get().status() == status) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError(
        "等待状态超时: "
            + id
            + " 期望 "
            + status
            + "，实际 "
            + manager.get(id).map(SubagentInstance::status).orElse(null));
  }

  private List<Event> lifecycleEvents(String correlationId) {
    List<Event> events =
        new ArrayList<>(
            store.query(new EventQuery("", EventTypes.AGENT_LIFECYCLE, correlationId, -1, 200)));
    events.sort(java.util.Comparator.comparingLong(Event::seq));
    return events;
  }

  private List<String> lifecycleActions(String correlationId) {
    return lifecycleEvents(correlationId).stream().map(e -> actionOf(e)).toList();
  }

  private static String actionOf(Event e) {
    return payloadOf(e).get("action").toString();
  }

  private List<Event> query(String type) {
    return store.query(new EventQuery("", type, "", -1, 200));
  }

  private static Map<String, Object> payloadOf(Event e) {
    try {
      return JSON.readValue(e.payload(), new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception ex) {
      throw new AssertionError("事件 payload 解析失败: " + e.payload(), ex);
    }
  }

  /** 门闩 launcher：launch 进入后阻塞，直到 {@link #release()}——确定性复现 SPAWNING 窗口 kill 竞态。 */
  private static final class GatedLauncher implements SubagentLauncher {

    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger closeCalls = new AtomicInteger();

    void awaitLaunchStarted(long seconds, TimeUnit unit) throws InterruptedException {
      if (!entered.await(seconds, unit)) {
        throw new AssertionError("launcher.launch 未进入（spawn 未走到 SPAWNING 阶段）");
      }
    }

    void release() {
      release.countDown();
    }

    int closeCalls() {
      return closeCalls.get();
    }

    @Override
    public LaunchedSubagent launch(String instanceId, AgentConfig childConfig) {
      entered.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return new LaunchedSubagent() {
        @Override
        public boolean isAlive() {
          return alive.get();
        }

        @Override
        public void close() {
          closeCalls.incrementAndGet();
          alive.set(false);
        }
      };
    }

    @Override
    public void close() {}
  }

  /** 在管 launcher 记录每一次 launch/close（断言"close 只经 Manager 编排一次"）。 */
  private static final class RecordingLauncher implements SubagentLauncher {

    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicInteger closeCalls = new AtomicInteger();
    private final AtomicInteger selfCloseCalls = new AtomicInteger();
    private volatile AgentConfig lastConfig;

    int closeCalls() {
      return closeCalls.get();
    }

    int selfCloseCalls() {
      return selfCloseCalls.get();
    }

    AgentConfig lastConfig() {
      return lastConfig;
    }

    @Override
    public LaunchedSubagent launch(String instanceId, AgentConfig childConfig) {
      lastConfig = childConfig;
      return new LaunchedSubagent() {
        @Override
        public boolean isAlive() {
          return alive.get();
        }

        @Override
        public void close() {
          closeCalls.incrementAndGet();
          alive.set(false);
        }
      };
    }

    @Override
    public void close() {
      selfCloseCalls.incrementAndGet();
    }
  }
}
