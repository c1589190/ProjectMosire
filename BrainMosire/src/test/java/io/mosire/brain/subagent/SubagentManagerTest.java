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
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.EventTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    // 子体正常跑完 ⇒ 按 §2.1 先把自己的终局落库（夹具模拟子进程的义务），父侧据此判 FINISHED + 停因
    InProcessExecutor executor =
        new InProcessExecutor(
            (childId, config) -> writeChildTerminalRecord(childId, "FINISHED", 3, 2));
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
    // §2.4：终局字段进父库事件（有才写）——这是"正常完成"的账
    assertThat(payloadOf(chain.get(3)))
        .containsEntry("action", "finished")
        .containsEntry("stopReason", "FINISHED")
        .containsEntry("turns", 3)
        .containsEntry("toolCalls", 2);
    // 内存面与事件面同一份事实（§2.4）
    assertThat(manager.terminalOutcome(id))
        .hasValueSatisfying(
            outcome -> {
              assertThat(outcome.stopReason()).isEqualTo("FINISHED");
              assertThat(outcome.turns()).isEqualTo(3);
              assertThat(outcome.toolCalls()).isEqualTo(2);
            });
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
    // 父深度 = 子深度上限（缺省口径：总层数 3 ⇒ 子深度 ≤ 2）⇒ 再派一层就是 3 > 2
    SubagentManager manager =
        manager(
            templateStore,
            new InProcessExecutor(),
            parentConfig(),
            AgentPermissionSet.system(),
            SubagentLimits.defaults().maxChildDepth());

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
        .containsEntry("depth", SubagentLimits.defaults().maxChildDepth() + 1)
        // 事件里报的是**总层数**（配置原值），消息里报的是子深度上限——两个口径都留着，读者不必换算
        .containsEntry("maxDepth", SubagentLimits.defaults().maxDepth());
  }

  @Test
  void depthAtDefaultLimitIsAllowed() {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    AgentTemplateStore templateStore = store();
    SubagentManager manager =
        manager(
            templateStore,
            new InProcessExecutor(),
            parentConfig(),
            AgentPermissionSet.system(),
            SubagentLimits.defaults().maxChildDepth() - 1);

    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("reader", "边界层", null, null, null, null));

    assertThat(spawned.depth()).isEqualTo(SubagentLimits.defaults().maxChildDepth());
    assertThat(manager.get(spawned.instanceId()))
        .hasValueSatisfying(
            i -> assertThat(i.depth()).isEqualTo(SubagentLimits.defaults().maxChildDepth()));
  }

  /**
   * S5-A 锚点修正（权限维）：单调性对照的是<b>调用者</b>的权限集，不是 manager 的构造期常量。
   *
   * <p><b>判别性</b>：本 manager 的 {@code parentPermissions} 是 {@code system()}（无白名单限制、全放行）。旧实现（对照
   * 构造期常量）会把这次派生物<b>放行</b>——模板要 {@code write}、调用者只有 {@code read}；把对照物换回 manager 常量，本用例转红。 拒绝类事件的
   * {@code agent} 也必须是调用者（"谁被拒了"），不是 manager 的主人。
   */
  @Test
  void monotonicityIsAnchoredToTheCallerNotTheManager() {
    write(template("writer", Set.of("read", "write"), Set.of(), false, false, false));
    SubagentManager manager =
        manager(store(), new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);

    AgentPermissionSet callerPermissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("read").build();
    AgentIdentity caller = AgentIdentity.subagent("caller-1", CommandMode.FULL, "上级派的任务", 1);

    assertThatThrownBy(
            () ->
                manager.spawn(
                    new SubagentLaunchRequest("writer", "写文件", null, null, null, null),
                    caller,
                    callerPermissions))
        .isInstanceOf(SubagentRejectedException.class)
        .hasMessageContaining("单调性");
    assertThat(manager.list()).as("拒绝发生在起实例之前").isEmpty();

    List<Event> denied = query(EventTypes.PERMISSION_DENIED);
    assertThat(denied).hasSize(1);
    assertThat(denied.get(0).agent()).isEqualTo("caller-1");
  }

  /**
   * S5-A 锚点修正（深度维）：深度对照的是<b>调用者身份的深度 + 1</b>，不是 manager 的 {@code parentDepth}。
   *
   * <p><b>判别性</b>：本 manager 的 {@code parentDepth} 是 0（旧实现会算出 depth=1 并放行），而调用者已在上限那一层——
   * 这正是"两跳以上把孙代拿去和主 Agent 比"的那个缺陷。反向对照（浅一层同 manager 放行）保证本用例不是"怎么都拒"。
   */
  @Test
  void depthIsAnchoredToTheCallerIdentityNotTheManagerConstant() {
    write(template("reader", Set.of("read"), Set.of(), true, false, false));
    SubagentManager manager =
        manager(store(), new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);
    int maxChildDepth = SubagentLimits.defaults().maxChildDepth();

    AgentIdentity deepCaller =
        AgentIdentity.subagent("deep-1", CommandMode.FULL, "已经在上限层", maxChildDepth);
    assertThatThrownBy(
            () ->
                manager.spawn(
                    new SubagentLaunchRequest("reader", "再派一层", null, null, null, null),
                    deepCaller,
                    AgentPermissionSet.system()))
        .isInstanceOf(SubagentRejectedException.class)
        .hasMessageContaining("深度");

    AgentIdentity shallowCaller = AgentIdentity.subagent("shallow-1", CommandMode.FULL, "第一层", 0);
    SubagentInstance spawned =
        manager.spawn(
            new SubagentLaunchRequest("reader", "正常一层", null, null, null, null),
            shallowCaller,
            AgentPermissionSet.system());
    assertThat(spawned.depth()).isEqualTo(1);
    assertThat(spawned.parentInstanceId()).isEqualTo("shallow-1");
  }

  /**
   * S5-A 繁殖预算（直系维）：额度按<b>调用者</b>分账、只数<b>在管</b>实例，消息带需求/剩余/上限三个数。
   *
   * <p>判别性：把"数在管"写成"数全部"，本用例末尾那次重启（第一个子体已 FINISHED）会转红；把额度按全局算， "别家调用者照常派"那步会转红。
   */
  @Test
  void childBudgetLimitsLiveChildrenPerCallerAndFreesSlotsWhenTheyFinish() throws Exception {
    write(template("reader", Set.of("read"), Set.of(), true, false, false));
    CountDownLatch release = new CountDownLatch(1);
    SubagentManager manager =
        manager(
            store(),
            new InProcessExecutor(
                (id, cfg) -> {
                  release.await();
                  // 放行后按 §2.1 落终局再"退栈"——否则父侧看到的是"未留下终局记录"的异常终局
                  writeChildTerminalRecord(id, "FINISHED", 1, 0);
                }),
            parentConfig(),
            AgentPermissionSet.system(),
            0,
            new SubagentLimits(3, 1, 8));
    AgentIdentity caller = AgentIdentity.subagent("parent-1", CommandMode.FULL, "派活", 1);

    SubagentInstance first =
        manager.spawn(
            new SubagentLaunchRequest("reader", "第一个", null, null, null, null),
            caller,
            AgentPermissionSet.system());

    assertThatThrownBy(
            () ->
                manager.spawn(
                    new SubagentLaunchRequest("reader", "第二个", null, null, null, null),
                    caller,
                    AgentPermissionSet.system()))
        .isInstanceOf(SubagentBudgetExceededException.class)
        .hasMessageContaining("BUDGET_EXHAUSTED")
        .hasMessageContaining("需求=1")
        .hasMessageContaining("剩余=0")
        .hasMessageContaining("上限=1");
    assertThat(manager.list()).as("被拒的那次不留半个实例").hasSize(1);

    List<Event> decisions = query(EventTypes.DECISION);
    assertThat(decisions).hasSize(1);
    assertThat(payloadOf(decisions.get(0)))
        .containsEntry("decision", "BUDGET_EXHAUSTED")
        .containsEntry("need", 1)
        .containsEntry("remaining", 0)
        .containsEntry("limit", 1);

    // 额度按调用者分账：别家调用者不受这家额度影响
    SubagentInstance other =
        manager.spawn(
            new SubagentLaunchRequest("reader", "别家", null, null, null, null),
            AgentIdentity.subagent("parent-2", CommandMode.FULL, "另一家", 1),
            AgentPermissionSet.system());
    assertThat(other.status()).isEqualTo(SubagentStatus.RUNNING);

    // 终态不再占额：第一个跑完 ⇒ 同一调用者又能派
    release.countDown();
    awaitStatus(manager, first.instanceId(), SubagentStatus.FINISHED);
    SubagentInstance third =
        manager.spawn(
            new SubagentLaunchRequest("reader", "第三个", null, null, null, null),
            caller,
            AgentPermissionSet.system());
    assertThat(third.instanceId()).isNotEqualTo(first.instanceId());
    assertThat(third.parentInstanceId()).isEqualTo("parent-1");
  }

  /** S5-A 繁殖预算（全局维）：跨调用者共享总额度，越界同样带三个数。 */
  @Test
  void globalInstanceBudgetRejectsAcrossDifferentCallers() {
    write(template("reader", Set.of("read"), Set.of(), true, false, false));
    CountDownLatch release = new CountDownLatch(1);
    SubagentManager manager =
        manager(
            store(),
            new InProcessExecutor((id, cfg) -> release.await()),
            parentConfig(),
            AgentPermissionSet.system(),
            0,
            new SubagentLimits(3, 8, 1));

    manager.spawn(
        new SubagentLaunchRequest("reader", "占满全局额度", null, null, null, null),
        AgentIdentity.subagent("parent-a", CommandMode.FULL, "甲", 1),
        AgentPermissionSet.system());

    assertThatThrownBy(
            () ->
                manager.spawn(
                    new SubagentLaunchRequest("reader", "别家也派不出", null, null, null, null),
                    AgentIdentity.subagent("parent-b", CommandMode.FULL, "乙", 1),
                    AgentPermissionSet.system()))
        .isInstanceOf(SubagentBudgetExceededException.class)
        .hasMessageContaining("全局在管子实例")
        .hasMessageContaining("上限=1");
    release.countDown();
  }

  // ---- S5-B：资源作用域（fs = 工作目录围栏） ----

  /**
   * S5-B 锚点 + 物化：子体的 {@code fs} 面取自<b>调用者</b>的权限集，并在派生那一刻落成具体取值。
   *
   * <p><b>判别性</b>：本 manager 的构造期权限是 {@code system()}（不限工作目录）。若实现锚在它身上（S5-A 修掉的那类"对照物错对象"），
   * 子体会拿到"不限" ⇒ 第一条断言转红。若实现只把"继承"留给运行期去问父级（子体这一维留空），第三条断言转红——留空会让 {@code
   * PermissionChecker.isSubset} 拿到"没有对象的比较"，静默放行一个真正生效的面更宽的子体。
   */
  @Test
  void childFsScopeIsAnchoredToTheCallerAndMaterialized() throws Exception {
    Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
    Path outside = Files.createDirectories(tempDir.resolve("outside"));
    write(template("reader", Set.of("read"), Set.of(), true, false, false));
    SubagentManager manager =
        manager(store(), new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);
    AgentPermissionSet callerPermissions = withFs(ResourceScope.ofDir(allowed));

    SubagentInstance spawned =
        manager.spawn(
            new SubagentLaunchRequest("reader", "调查", null, null, null, null),
            AgentIdentity.subagent("parent-1", CommandMode.FULL, "派活", 1),
            callerPermissions);

    ResourceScope childFs = spawned.permissions().fsScope();
    assertThat(childFs.unrestricted()).as("子体必须继承调用者的围栏，而不是 manager 的构造期权限").isFalse();
    assertThat(childFs.allowsDir(allowed.resolve("a/b"))).isTrue();
    assertThat(childFs.allowsDir(outside.resolve("a"))).isFalse();
    assertThat(spawned.permissions().resourceScopes().declaredScope(ResourceScopeMap.FS))
        .as("继承要物化：子体自己表了态，不留空")
        .isNotNull();
    assertThat(PermissionChecker.isSubset(spawned.permissions(), callerPermissions)).isTrue();
  }

  /** 模板的 {@code allowedWorkingDirs} 是<b>建议</b>：与调用者求交，这里更窄 ⇒ 取更窄的。 */
  @Test
  void templateWorkingDirSuggestionNarrowsTheChild() throws Exception {
    Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
    Path narrow = Files.createDirectories(allowed.resolve("narrow"));
    write(templateWithDirs(List.of(narrow.toString())));
    SubagentManager manager =
        manager(store(), new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(
            new SubagentLaunchRequest("reader", "调查", null, null, null, null),
            AgentIdentity.subagent("parent-1", CommandMode.FULL, "派活", 1),
            withFs(ResourceScope.ofDir(allowed)));

    ResourceScope childFs = spawned.permissions().fsScope();
    assertThat(childFs.allowsDir(narrow.resolve("x"))).isTrue();
    assertThat(childFs.allowsDir(allowed.resolve("sibling"))).as("模板更窄 ⇒ 按更窄的来（求交，不是取并）").isFalse();
  }

  /**
   * 模板建议<b>超出</b>调用者可达面 ⇒ <b>静默求交</b>（不报错、也不放大）：通用模板配置了上级之外的目录是常态。
   *
   * <p><b>判别性</b>：把求交写成"模板优先/取并"，第二条断言（子体拿不到别处）转红；写成"模板越界就抛"，这次 spawn 直接失败 ⇒ 也红。 反向对照见 {@link
   * #requestedDirsBeyondTheCallerAreRejectedLoudly}：同一份越界，<b>请求</b>那一面必须响亮。
   */
  @Test
  void templateSuggestionBeyondTheCallerIsIntersectedSilently() throws Exception {
    Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
    Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
    write(templateWithDirs(List.of(elsewhere.toString())));
    SubagentManager manager =
        manager(store(), new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(
            new SubagentLaunchRequest("reader", "调查", null, null, null, null),
            AgentIdentity.subagent("parent-1", CommandMode.FULL, "派活", 1),
            withFs(ResourceScope.ofDir(allowed)));

    ResourceScope childFs = spawned.permissions().fsScope();
    assertThat(childFs.unrestricted()).isFalse();
    assertThat(childFs.prefixList()).as("互不相交 ⇒ 显式空集（不是退化成'不限'）").isEmpty();
    assertThat(childFs.allowsDir(elsewhere.resolve("x"))).isFalse();
    assertThat(childFs.allowsDir(allowed.resolve("x"))).isFalse();
    assertThat(query(EventTypes.PERMISSION_DENIED)).as("建议面越界不是错误——只有请求面才响亮").isEmpty();
  }

  /**
   * 请求面是<b>要求</b>：超出调用者可达面 ⇒ {@code DIR_NOT_ALLOWED} + {@code permission.denied}，子体不落地。
   *
   * <p><b>判别性</b>：静默截断（把请求削到调用者可达面然后照常起实例）会让 {@code manager.list()} 非空、生命周期事件出现 ⇒ 两条断言转红。 拒绝事件里的
   * {@code agent} 必须是<b>被拒的调用者</b>（与 S5-A 同一口径）。
   */
  @Test
  void requestedDirsBeyondTheCallerAreRejectedLoudly() throws Exception {
    Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
    Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
    write(template("reader", Set.of("read"), Set.of(), true, false, false));
    SubagentManager manager =
        manager(store(), new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);

    assertThatThrownBy(
            () ->
                manager.spawn(
                    new SubagentLaunchRequest(
                        "reader",
                        "越界调查",
                        null,
                        null,
                        null,
                        null,
                        CommandMode.FULL,
                        List.of(elsewhere.toString())),
                    AgentIdentity.subagent("parent-1", CommandMode.FULL, "派活", 1),
                    withFs(ResourceScope.ofDir(allowed))))
        .isInstanceOf(WorkingDirDeniedException.class)
        .hasMessageContaining("DIR_NOT_ALLOWED");

    assertThat(manager.list()).as("拒绝发生在起实例之前").isEmpty();
    assertThat(query(EventTypes.AGENT_LIFECYCLE)).as("一个生命周期事件都不该有").isEmpty();

    List<Event> denied = query(EventTypes.PERMISSION_DENIED);
    assertThat(denied).hasSize(1);
    assertThat(denied.get(0).agent()).isEqualTo("parent-1");
    assertThat(payloadOf(denied.get(0)))
        .containsEntry("template", "reader")
        .containsEntry("requestedDirs", List.of(elsewhere.toString()))
        .containsEntry("callerScope", ResourceScope.ofDir(allowed).summary())
        .satisfies(p -> assertThat(p.get("reason").toString()).contains("DIR_NOT_ALLOWED"));
  }

  /** 请求面落在调用者之内 ⇒ 逐跳再收窄（这里窄一层）。 */
  @Test
  void requestedDirsWithinTheCallerNarrowTheChildFurther() throws Exception {
    Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
    Path narrow = Files.createDirectories(allowed.resolve("narrow"));
    write(template("reader", Set.of("read"), Set.of(), true, false, false));
    SubagentManager manager =
        manager(store(), new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(
            new SubagentLaunchRequest(
                "reader", "窄调查", null, null, null, null, null, List.of(narrow.toString())),
            AgentIdentity.subagent("parent-1", CommandMode.FULL, "派活", 1),
            withFs(ResourceScope.ofDir(allowed)));

    ResourceScope childFs = spawned.permissions().fsScope();
    assertThat(childFs.allowsDir(narrow.resolve("x"))).isTrue();
    assertThat(childFs.allowsDir(allowed.resolve("sibling"))).isFalse();
  }

  /**
   * 请求给<b>空表</b> = 显式"哪里都不许"：子体照常落地，但 {@code fs} 面是空集。
   *
   * <p><b>判别性</b>：把"显式空集"当成"没提这一嘴"（退化成继承调用者）⇒ 后两条断言转红。 与 {@code allowedTools: []}
   * 同形同义——显式写的空集是"不给"，不是"没写"。
   */
  @Test
  void requestedEmptyDirsGiveTheChildNothing() throws Exception {
    Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
    write(template("reader", Set.of("read"), Set.of(), true, false, false));
    SubagentManager manager =
        manager(store(), new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(
            new SubagentLaunchRequest("reader", "无目录任务", null, null, null, null, null, List.of()),
            AgentIdentity.subagent("parent-1", CommandMode.FULL, "派活", 1),
            withFs(ResourceScope.ofDir(allowed)));

    ResourceScope childFs = spawned.permissions().fsScope();
    assertThat(childFs.unrestricted()).as("空集不是'不限'").isFalse();
    assertThat(childFs.prefixList()).isEmpty();
    assertThat(childFs.allowsDir(allowed.resolve("x"))).isFalse();
  }

  /** 坏路径（含 NUL，{@code Path.of} 抛 {@code InvalidPathException}）与"越界"同码同事件：模型送来的参数不成立，不是崩溃。 */
  @Test
  void unparsableRequestedDirIsDeniedWithTheSameCode() throws Exception {
    Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
    write(template("reader", Set.of("read"), Set.of(), true, false, false));
    SubagentManager manager =
        manager(store(), new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);
    AgentIdentity caller = AgentIdentity.subagent("parent-1", CommandMode.FULL, "派活", 1);

    assertThatThrownBy(
            () ->
                manager.spawn(
                    new SubagentLaunchRequest(
                        "reader",
                        "坏路径",
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of("bad" + (char) 0 + "path")),
                    caller,
                    withFs(ResourceScope.ofDir(allowed))))
        .isInstanceOf(WorkingDirDeniedException.class)
        .hasMessageContaining("DIR_NOT_ALLOWED");

    assertThat(manager.list()).isEmpty();
    assertThat(query(EventTypes.PERMISSION_DENIED)).hasSize(1);
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

  /**
   * §2.3 规则 2（§四.3 的"异常终局可分"）：确认存活之后退出、但子库<b>没有</b>终局记录 ⇒ {@code FAILED} + 理由 {@code "未留下终局记录"} +
   * 退出码。
   *
   * <p><b>这正是本块推翻的旧口径</b>：原文担心"非零一律翻 FAILED 制造状态谎言"，那是<b>没有终局记录</b>的时代。今天"子体先落库后退栈"是协议义务，
   * <b>缺失</b>才是异常 ⇒ 谎言风险由协议消除。判别性：把判定回退成"RUNNING 退出即 FINISHED"（旧口径）⇒ 状态与事件链断言转红； 把 reason 换成任意文本 ⇒
   * payload 断言转红。
   */
  @Test
  void exitWithoutTerminalRecordIsFailedWithDesignReason() throws Exception {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    CrashingLauncher launcher =
        new CrashingLauncher("exitCode=1；末段输出: 模板目录不存在", false, java.util.OptionalInt.of(1));
    SubagentManager manager =
        manager(store(), launcher, parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("reader", "目标", null, null, null, null));
    String id = spawned.instanceId();
    assertThat(spawned.status()).isEqualTo(SubagentStatus.RUNNING);

    awaitStatus(manager, id, SubagentStatus.FAILED);

    assertThat(lifecycleActions(id)).containsExactly("configured", "spawning", "running", "failed");
    List<Event> chain = lifecycleEvents(id);
    assertThat(payloadOf(chain.get(3)))
        .containsEntry("action", "failed")
        .containsEntry("reason", "未留下终局记录")
        .containsEntry("exitCode", 1); // 退出码是诊断面：有值才写（§四.6）
    assertThat(manager.terminalOutcome(id))
        .hasValueSatisfying(
            outcome -> {
              assertThat(outcome.hasStopReason()).as("异常终局：没有停因，也不编一个").isFalse();
              assertThat(outcome.exitCode()).isEqualTo(1);
            });
    assertThat(launcher.diagnosticsCalls()).as("异常退出必须真去取原因（缝加了不用 = 没加）").isPositive();
  }

  /**
   * §四.6：句柄给不出退出码（{@code OptionalInt.empty()}）⇒ <b>事件里不写这个键</b>，绝不编 0（"拿不到"与"码是 0"是两件事）。
   *
   * <p>判别性：把 {@code exitCodeOf} 写成 {@code orElse(0)}（看着无害的"补个缺省"）⇒ {@code doesNotContainKey} 断言转红。
   */
  @Test
  void missingExitCodeIsNotFabricatedAsZero() throws Exception {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    CrashingLauncher launcher =
        new CrashingLauncher("（无诊断文本，只有死）", false, java.util.OptionalInt.empty());
    SubagentManager manager =
        manager(store(), launcher, parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("reader", "目标", null, null, null, null));
    String id = spawned.instanceId();

    awaitStatus(manager, id, SubagentStatus.FAILED);
    List<Event> chain = lifecycleEvents(id);
    assertThat(payloadOf(chain.get(3)))
        .as("拿不到码 ⇒ 不写键（写 0 = 把未知伪装成正常退出）")
        .doesNotContainKey("exitCode")
        .doesNotContainKey("stopReason");
    assertThat(manager.terminalOutcome(id))
        .hasValueSatisfying(outcome -> assertThat(outcome.exitCode()).isNull());
  }

  /**
   * §2.3 的竞态裁决：状态停在 {@code TERMINATING}、而子库<b>已有</b>终局记录 ⇒ 以事件为准记 {@code FINISHED} + {@code
   * stopReason} （"子体确实跑完了这一轮，谎报 KILLED 比 kill 请求落空更糟"）。
   *
   * <p>判别性：把这一支删掉（TERMINATING 一律 KILLED）⇒ 状态/事件链断言转红；去掉"已是终态即返回"的幂等 ⇒ 两条收束路径各发一条终局事件 ⇒ {@code
   * containsExactly} 转红。
   */
  @Test
  void terminatingWithChildTerminalRecordFinishesHonestly() throws Exception {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    RecordingLauncher launcher =
        new RecordingLauncher(); // 句柄活着；close() 才死（kill 的 awaitDead 因此快速收束）
    SubagentManager manager =
        manager(store(), launcher, parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("reader", "目标", null, null, null, null));
    String id = spawned.instanceId();

    // 子体在被关停之前跑完并落了库（真实形态：kill 与"自己跑完"撞在同一刻）
    writeChildTerminalRecord(id, "TURN_LIMIT", 7, 4);
    assertThat(manager.kill(id)).isTrue();

    awaitStatus(manager, id, SubagentStatus.FINISHED);
    assertThat(lifecycleActions(id))
        .containsExactly("configured", "spawning", "running", "terminating", "finished");
    assertThat(payloadOf(lifecycleEvents(id).get(4)))
        .containsEntry("action", "finished")
        .containsEntry("stopReason", "TURN_LIMIT")
        .containsEntry("turns", 7)
        .containsEntry("toolCalls", 4);
    assertThat(manager.terminalOutcome(id))
        .hasValueSatisfying(outcome -> assertThat(outcome.stopReason()).isEqualTo("TURN_LIMIT"));

    // 幂等：终态后重复 kill 是空操作，且不重复迁移/不重复发事件（两条收束路径只落一条终局）
    assertThat(manager.kill(id)).isFalse();
    assertThat(lifecycleActions(id)).hasSize(5);
  }

  /**
   * 装配层没注入子库根（{@code null}）⇒ 读不到任何终局记录 ⇒ 一律按"未留下终局记录"处理（fail-closed：宁可报异常终局，也不假装子体交代过）。
   *
   * <p>判别性：把 {@code null} 实现成"猜一个缺省路径"（{@code dataDir/subagents} 之类）⇒ 本用例转红。
   */
  @Test
  void missingChildDataRootFailsClosed() throws Exception {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    // 子体照常落库（库在 tempDir/subagents 下），但父侧<b>没被告知</b>这个根 ⇒ 不得去猜
    InProcessExecutor executor =
        new InProcessExecutor(
            (childId, config) -> writeChildTerminalRecord(childId, "FINISHED", 1, 1));
    SubagentManager manager =
        manager(
            store(),
            executor,
            parentConfig(),
            AgentPermissionSet.system(),
            0,
            SubagentLimits.defaults(),
            null);

    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("reader", "目标", null, null, null, null));
    awaitStatus(manager, spawned.instanceId(), SubagentStatus.FAILED);
    assertThat(lifecycleActions(spawned.instanceId()))
        .containsExactly("configured", "spawning", "running", "failed");
    assertThat(manager.terminalOutcome(spawned.instanceId()))
        .hasValueSatisfying(outcome -> assertThat(outcome.hasStopReason()).isFalse());
  }

  /**
   * §2.1 兼容口径（⑦ 微分支）：老库的 {@code action=finished} 事件<b>没有</b> {@code stopReason} 键 ⇒
   * <b>整条记录不认</b>（按"未留下终局记录" 处理），而不是"认得一半"（拿它去填 turns/toolCalls）。
   *
   * <p>判别性：把"必须带 {@code stopReason}"这条判据删掉 ⇒ 状态变 FINISHED、reason 消失 ⇒ 断言转红；只把判据挪到"turns 非空"上 ⇒
   * {@code doesNotContainKey("turns")} 转红。
   */
  @Test
  void legacyFinishedEventWithoutStopReasonIsNotATerminalRecord() throws Exception {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    InProcessExecutor executor =
        new InProcessExecutor(
            (childId, config) ->
                writeChildTerminalRecordPayload(
                    childId, "{\"action\":\"finished\",\"turns\":4,\"toolCalls\":2}"));
    SubagentManager manager =
        manager(store(), executor, parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("reader", "目标", null, null, null, null));
    String id = spawned.instanceId();

    awaitStatus(manager, id, SubagentStatus.FAILED);
    assertThat(lifecycleActions(id)).containsExactly("configured", "spawning", "running", "failed");
    assertThat(payloadOf(lifecycleEvents(id).get(3)))
        .containsEntry("reason", "未留下终局记录")
        .doesNotContainKey("stopReason")
        .as("老事件的计数器也不许漏进来：整条不认，不是'认得一半'")
        .doesNotContainKey("turns");
    assertThat(manager.terminalOutcome(id))
        .hasValueSatisfying(outcome -> assertThat(outcome.hasStopReason()).isFalse());
  }

  /**
   * §2.4 / §四.6 的"不编 0"落在<b>读数侧</b>（⑦ 微分支）：终局记录里 {@code turns} 是非数字（坏 payload / 外部写入）⇒ {@code null}
   * 且事件里不写这个键；同一记录里的 {@code toolCalls} 照常取到——一个字段坏不牵连整条记录（坏的是键，不是记录）。
   *
   * <p>判别性：把 {@code intField} 换成 {@code payload.path(key).asInt()}（Jackson 对非数字返回 0）⇒ {@code turns}
   * 断言转红。
   */
  @Test
  void nonNumericCountersAreNullNotZero() throws Exception {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    InProcessExecutor executor =
        new InProcessExecutor(
            (childId, config) ->
                writeChildTerminalRecordPayload(
                    childId,
                    "{\"action\":\"finished\",\"stopReason\":\"TURN_LIMIT\",\"turns\":\"四个\",\"toolCalls\":3}"));
    SubagentManager manager =
        manager(store(), executor, parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("reader", "目标", null, null, null, null));
    String id = spawned.instanceId();

    awaitStatus(manager, id, SubagentStatus.FINISHED);
    assertThat(payloadOf(lifecycleEvents(id).get(3)))
        .containsEntry("stopReason", "TURN_LIMIT")
        .containsEntry("toolCalls", 3)
        .as("非数字的 turns ⇒ 不写键（写 0 = 把'读不出'伪装成'没跑过'）")
        .doesNotContainKey("turns");
    assertThat(manager.terminalOutcome(id))
        .hasValueSatisfying(
            outcome -> {
              assertThat(outcome.stopReason()).isEqualTo("TURN_LIMIT");
              assertThat(outcome.turns()).as("非数字 ⇒ null").isNull();
              assertThat(outcome.toolCalls()).isEqualTo(3);
            });
  }

  /**
   * 诊断实现自身抛异常（最恶劣形态）⇒ 观测线程照样收束状态：诊断是日志面，不许把状态机带停。
   *
   * <p>判别性：去掉 {@code diagnosticsOf} 的 try/catch，观测线程在投递退出事实时死掉 ⇒ 实例永远停在 RUNNING ⇒ 本用例超时变红。
   */
  @Test
  void throwingDiagnosticsImplementationDoesNotStallTheExitWatch() throws Exception {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    CrashingLauncher launcher = new CrashingLauncher("（不会被取到）", true);
    SubagentManager manager =
        manager(store(), launcher, parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("reader", "目标", null, null, null, null));

    awaitStatus(manager, spawned.instanceId(), SubagentStatus.FAILED);
    assertThat(launcher.diagnosticsCalls()).as("确认诊断面真被调过（否则本用例是空转）").isPositive();
  }

  /**
   * §2.7 / §四.5：{@code close()} <b>由深到浅</b>——父 + 孙都在管时，孙先收到关停（别让"祖辈先死 ⇒ 子孙的父侧链路断在半路"）。
   *
   * <p><b>判别性（如实标注，2026-09-14）</b>：本用例走的是完整 close() 链（装配层 + 真实并发），断言"关停序列按深度降序"。它对"排序被去掉"的判别是
   * <b>概率性</b>的：{@code instances} 是 {@code HashMap}，把排序去掉后 3 个深层实例恰好被哈希序排到前面的概率 ≈ 1/C(7,3) ≈
   * 2.9%（评审实测：连跑 9 次未复现）⇒ <b>确定性判据在</b> {@link
   * #shutdownOrderIsDeepestFirstAndLegacyLast}（同一输入必得同一输出）。 本用例保留的价值是把"close()
   * 真的按那个序关、且一个不漏"钉在装配面上。
   */
  @Test
  void closeTerminatesDeepestFirst() throws Exception {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    OrderRecordingLauncher launcher = new OrderRecordingLauncher();
    SubagentManager manager =
        manager(
            store(),
            launcher,
            parentConfig(),
            AgentPermissionSet.system(),
            0,
            new SubagentLimits(3, 8, 8));

    List<String> allIds = new ArrayList<>();
    List<String> shallowIds = new ArrayList<>();
    for (int i = 0; i < 4; i++) { // 主 Agent 的直系（path 段数 2）
      String id =
          manager
              .spawn(new SubagentLaunchRequest("reader", "浅 " + i, null, null, null, null))
              .instanceId();
      shallowIds.add(id);
      allIds.add(id);
    }
    List<String> deepIds = new ArrayList<>();
    for (int i = 0; i < 3; i++) { // 孙代（path 段数 3）：调用者身份 = 父实例本身 ⇒ path = main/<父>/<孙>
      String id =
          manager
              .spawn(
                  new SubagentLaunchRequest("reader", "深 " + i, null, null, null, null),
                  AgentIdentity.subagent(shallowIds.get(i), CommandMode.FULL, "孙代任务", 1),
                  AgentPermissionSet.system())
              .instanceId();
      deepIds.add(id);
      allIds.add(id);
    }
    assertThat(manager.get(deepIds.get(0)).orElseThrow().lineagePath())
        .as("夹具前提：孙代的 path 真的比父深一段")
        .isEqualTo("main/" + shallowIds.get(0) + "/" + deepIds.get(0));

    manager.close();

    assertThat(launcher.closeOrder())
        .as("全部在管实例都被关停过（防空列表恒真）")
        .containsExactlyInAnyOrderElementsOf(allIds);
    List<Integer> depths =
        launcher.closeOrder().stream()
            .map(id -> deepIds.contains(id) ? 3 : 2) // 血缘段数：main/<父> = 2，main/<父>/<孙> = 3
            .toList();
    assertThat(depths)
        .as("关停顺序按血缘段数降序（深的先关）: " + launcher.closeOrder())
        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
  }

  /**
   * §2.7 的<b>确定性判据</b>（2026-09-14，替换"概率性判别"）：{@link SubagentManager#shutdownOrder}
   * 是静态纯函数，输入同一组实例必得同一序—— 深层先、浅层后、{@code lineagePath} 为空的老记录（深度
   * -1）<b>最后</b>、终态实例不入序、同深度保持登记序（稳定排序）。
   *
   * <p>判别性：去掉 {@code .reversed()} ⇒ 序反转，转红；把空 path 的深度写成 0（而非 -1）⇒ 老记录插到浅层之前，转红；把 {@code
   * lineagePath} 用 {@code split("/").length} 之类的"顺手实现"改掉 ⇒ 空串会算成 1 段，同样转红；把终态过滤去掉 ⇒ 转红。
   */
  @Test
  void shutdownOrderIsDeepestFirstAndLegacyLast() {
    Map<String, SubagentInstance> instances = new LinkedHashMap<>();
    instances.put("shallow-1", instance("shallow-1", "main/shallow-1", SubagentStatus.RUNNING));
    instances.put("deep-a", instance("deep-a", "main/a1/deep-a", SubagentStatus.RUNNING));
    instances.put("legacy", instance("legacy", "", SubagentStatus.RUNNING)); // 老记录：段数未知
    instances.put("deep-b", instance("deep-b", "main/a1/deep-b", SubagentStatus.RUNNING));
    instances.put(
        "already-done", instance("already-done", "main/a1/done", SubagentStatus.FINISHED));
    instances.put("null-path", instance("null-path", null, SubagentStatus.TERMINATING));

    assertThat(SubagentManager.shutdownOrder(instances))
        .as("深层先、浅层后、空 path 最后、终态不进序、同深度保持登记序")
        .containsExactly("deep-a", "deep-b", "shallow-1", "legacy", "null-path");

    assertThat(SubagentManager.shutdownOrder(Map.of())).as("空表 ⇒ 空序（防空列表恒真的假通过）").isEmpty();
  }

  @Test
  void killDuringSpawningReturnsFailedSnapshotNotRawIllegalState() throws Exception {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    AgentTemplateStore templateStore = store();
    GatedLauncher launcher = new GatedLauncher();
    SubagentManager manager =
        manager(templateStore, launcher, parentConfig(), AgentPermissionSet.system(), 0);

    // 覆盖范围说明：本用例确定性命中的是 SPAWNING 窗口（launch 卡住期）——kill 推 FAILED 后 spawn 的
    // RUNNING 推进撞终态，断言 spawn 收敛为 FAILED 快照（而非裸 ISE）、孤儿句柄仅关闭一次、事件链为
    // [configured, spawning, failed]。CONFIGURED 窗口已被"两转换同锁"结构性关闭（见 spawn 注释），现有缝
    // 无法确定性落窗——判别性回归需生产级 spawn-stage 钩子，按 YAGNI 不加（见修复轮 2 报告）。
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
    // kill 契约尾：终态后重复 kill 为幂等空操作（false），且全程不抛裸 IllegalStateException 类异常
    assertThat(manager.kill(id)).isFalse();
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
    // 传给 executor 的正是这份收紧后的配置（实例快照携带同一对象引用）
    assertThat(launcher.lastInstance()).isNotNull();
    assertThat(launcher.lastInstance().config()).isSameAs(child);
    assertThat(launcher.lastInstance().instanceId()).isEqualTo(spawned.instanceId());
    assertThat(launcher.lastInstance().templateId()).isEqualTo("sleuth");
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
    // 模板/请求/父级均不限时仍是 0（不限）——有别于被 0 静默收紧；launcher 收到的配置同源
    assertThat(launcher.lastInstance().config()).isSameAs(spawned.config());
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

  // ---- D27：A1（派发时点名工具白名单） ----

  /**
   * {@code A1} 越界（I1）：请求面点名调用者没有的工具 ⇒ <b>派发即拒</b>（{@link SubagentRejectedException} + 点名工具的事件），
   * <b>不建实例、不建链、不花预算</b>。
   *
   * <p>判别性：把这条检查删掉（或挪到"运行期调用时才失败"），本次派发会成功落地 ⇒ 前三条断言转红；把额度算在被拒那一次头上， 末尾那次合法派发会撞 {@code
   * BUDGET_EXHAUSTED} ⇒ 转红（每父额度只有 1）。
   */
  @Test
  void requestedToolsBeyondTheCallerAreRejectedAtDispatchWithoutSpendingBudget() {
    write(template("writer", Set.of("read", "write"), Set.of(), false, false, false));
    SubagentManager manager =
        manager(
            store(),
            new InProcessExecutor(),
            parentConfig(),
            AgentPermissionSet.system(),
            0,
            new SubagentLimits(3, 1, 8));
    AgentIdentity caller = AgentIdentity.subagent("parent-1", CommandMode.FULL, "派活", 1);
    AgentPermissionSet callerPermissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("read", "write").build();

    assertThatThrownBy(
            () ->
                manager.spawn(
                    new SubagentLaunchRequest(
                        "writer",
                        "提权尝试",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of("read", "admin-do")),
                    caller,
                    callerPermissions))
        .isInstanceOf(SubagentRejectedException.class)
        .hasMessageContaining("admin-do")
        .hasMessageContaining("I1");

    assertThat(manager.list()).as("不建实例").isEmpty();
    assertThat(query(EventTypes.AGENT_LIFECYCLE)).as("不建链（一个生命周期事件都没有）").isEmpty();

    List<Event> denied = query(EventTypes.PERMISSION_DENIED);
    assertThat(denied).hasSize(1);
    assertThat(denied.get(0).agent()).as("谁被拒了").isEqualTo("parent-1");
    assertThat(payloadOf(denied.get(0)))
        .containsEntry("template", "writer")
        .containsEntry("requestedTools", List.of("read", "admin-do"))
        .containsEntry("beyondCaller", List.of("admin-do"));

    // 不花预算：每父额度 = 1 —— 被拒的那一次若不占额，这次合法派发才过得去
    SubagentInstance ok =
        manager.spawn(
            new SubagentLaunchRequest(
                "writer", "合法收窄", null, null, null, null, null, null, List.of("read")),
            caller,
            callerPermissions);
    assertThat(ok.status()).isEqualTo(SubagentStatus.RUNNING);
  }

  /**
   * {@code A1} 收窄生效：生效白名单 = <b>模板 ∩ A1</b>（请求只收窄、不放大），并逐维落到子体的权限集与运行配置；{@code null} 逐字退回模板那一份。
   *
   * <p>判别性：把 A1 写成"直接覆盖模板"⇒"模板之外的点名工具进不来"与"调用者通配也不例外"转红；写成"忽略 A1"⇒ "被收窄掉的模板工具"与"空表什么都不给"转红。
   */
  @Test
  void requestedToolsNarrowToTheIntersectionWithTheTemplate() {
    write(template("reader", Set.of("read", "echo"), Set.of(), false, false, false));
    SubagentManager manager =
        manager(store(), new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);
    AgentIdentity main = AgentIdentity.main(CommandMode.FULL);
    AgentPermissionSet mainPermissions = AgentPermissionSet.system();

    // 调用者是通配（* ⇒ I1 全放行），但模板只有 read/echo ⇒ admin-do 静默求交掉（请求面只收窄，不放大）
    SubagentInstance child =
        manager.spawn(
            new SubagentLaunchRequest(
                "reader", "收窄", null, null, null, null, null, null, List.of("read", "admin-do")),
            main,
            mainPermissions);
    assertThat(child.permissions().isToolAllowed("read")).isTrue();
    assertThat(child.permissions().isToolAllowed("admin-do")).as("模板没有 ⇒ 求交掉").isFalse();
    assertThat(child.permissions().isToolAllowed("echo")).as("请求没点名 ⇒ 从模板基线里被收窄掉").isFalse();
    assertThat(child.config().allowedTools()).containsExactly("read");
    assertThat(child.lineagePath()).isEqualTo("main/" + child.instanceId());

    // 空表 = 显式"一个工具都不给"（与 allowedDirs: [] 同形同义）
    SubagentInstance none =
        manager.spawn(
            new SubagentLaunchRequest(
                "reader", "零工具", null, null, null, null, null, null, List.of()),
            main,
            mainPermissions);
    assertThat(none.permissions().allowedTools()).isEmpty();
    assertThat(none.permissions().isToolAllowed("read")).isFalse();

    // null = 没提这一嘴 ⇒ 逐字退回模板白名单（本字段引入前的行为）
    SubagentInstance byTemplate =
        manager.spawn(
            new SubagentLaunchRequest("reader", "模板说了算", null, null, null, null, null, null, null),
            main,
            mainPermissions);
    assertThat(byTemplate.permissions().isToolAllowed("read")).isTrue();
    assertThat(byTemplate.permissions().isToolAllowed("echo")).isTrue();
  }

  /**
   * {@code A1} 含 {@code "*"} = <b>不过滤</b>（{@link AgentPermissionSet#ALL_TOOLS} 是过滤器元素、不是工具名）：模板有限 ⇒
   * 子体权限集 逐字是模板那一份——既不放大成全域，也不是"求交求没了"的空集。
   *
   * <p>判别性（本轮变异靶）：把 {@code "*"} 当普通工具名走求交 ⇒ 模板有限这一支得到空集（①的三条断言整片转红）；把 {@code "*"} 当"放行一切"⇒
   * 模板没有的工具也会进白名单（①的第三条断言转红）。
   */
  @Test
  void wildcardAllowedToolsMeansNoFilterAgainstTheTemplate() {
    write(template("reader", Set.of("read", "echo"), Set.of(), false, false, false));
    write(template("wild", Set.of("*"), Set.of(), false, false, false));
    SubagentManager manager =
        manager(store(), new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);
    AgentIdentity main = AgentIdentity.main(CommandMode.FULL);
    AgentPermissionSet mainPermissions = AgentPermissionSet.system();

    // ① 模板有限 + A1=["*"] ⇒ 结果 = 模板那一份（逐名断言）
    SubagentInstance noFilter =
        manager.spawn(
            new SubagentLaunchRequest(
                "reader", "通配不过滤", null, null, null, null, null, null, List.of("*")),
            main,
            mainPermissions);
    assertThat(noFilter.permissions().isToolAllowed("read")).isTrue();
    assertThat(noFilter.permissions().isToolAllowed("echo")).isTrue();
    assertThat(noFilter.permissions().isToolAllowed("admin-do")).as("不是全域：模板没有的就是没有").isFalse();
    assertThat(noFilter.permissions().allowedTools()).containsExactlyInAnyOrder("read", "echo");

    // ② 模板通配 + A1 有限 ⇒ 结果 = A1（既有行为，别改坏）
    SubagentInstance narrowed =
        manager.spawn(
            new SubagentLaunchRequest(
                "wild", "模板通配收窄", null, null, null, null, null, null, List.of("read")),
            main,
            mainPermissions);
    assertThat(narrowed.permissions().allowedTools()).containsExactly("read");
    assertThat(narrowed.permissions().isToolAllowed("echo")).isFalse();

    // ③ 两侧通配 ⇒ 仍通配（"不过滤"落在通配模板上就是模板自己）
    SubagentInstance stillWild =
        manager.spawn(
            new SubagentLaunchRequest(
                "wild", "两侧通配", null, null, null, null, null, null, List.of("*")),
            main,
            mainPermissions);
    assertThat(stillWild.permissions().isToolAllowed("whatever-tool")).isTrue();
  }

  /**
   * 受限调用者点 {@code A1=["*"]} ⇒ <b>派发即拒</b>（I1）：{@code callerPermissions.isToolAllowed("*")}
   * 只在<b>调用者自己的白名单通配</b>时为真 （{@code "*"} 不做跨集合比较）——"受限者不得借通配把自己放大成全域"。<b>这是有意行为，别放宽成"I1 见到 *
   * 就放行"</b>。
   *
   * <p>判别性：把 {@code "*"} 特判成"I1 一律放行"⇒ 第一条断言转红（派发会成功落地、事件不出现）。
   */
  @Test
  void wildcardRequestFromRestrictedCallerIsRejectedAtDispatch() {
    write(template("reader", Set.of("read", "echo"), Set.of(), false, false, false));
    SubagentManager manager =
        manager(store(), new InProcessExecutor(), parentConfig(), AgentPermissionSet.system(), 0);
    AgentIdentity caller = AgentIdentity.subagent("parent-1", CommandMode.FULL, "派活", 1);
    AgentPermissionSet callerPermissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("read", "echo").build();

    assertThatThrownBy(
            () ->
                manager.spawn(
                    new SubagentLaunchRequest(
                        "reader", "通配提权", null, null, null, null, null, null, List.of("*")),
                    caller,
                    callerPermissions))
        .isInstanceOf(SubagentRejectedException.class)
        .hasMessageContaining("*")
        .hasMessageContaining("I1");

    assertThat(manager.list()).as("不建实例").isEmpty();
    List<Event> denied = query(EventTypes.PERMISSION_DENIED);
    assertThat(denied).hasSize(1);
    assertThat(denied.get(0).agent()).isEqualTo("parent-1");
    assertThat(payloadOf(denied.get(0)))
        .containsEntry("requestedTools", List.of("*"))
        .containsEntry("beyondCaller", List.of("*"));
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
    return manager(
        templateStore,
        launcher,
        parentConfig,
        parentPermissions,
        parentDepth,
        SubagentLimits.defaults());
  }

  private SubagentManager manager(
      AgentTemplateStore templateStore,
      SubagentLauncher launcher,
      AgentConfig parentConfig,
      AgentPermissionSet parentPermissions,
      int parentDepth,
      SubagentLimits limits) {
    return manager(
        templateStore,
        launcher,
        parentConfig,
        parentPermissions,
        parentDepth,
        limits,
        childDataRoot());
  }

  /** 全参重载：{@code childDataRoot} 显式给（{@code null} = 复现"装配层没注入子库根"的 fail-closed 形态）。 */
  private SubagentManager manager(
      AgentTemplateStore templateStore,
      SubagentLauncher launcher,
      AgentConfig parentConfig,
      AgentPermissionSet parentPermissions,
      int parentDepth,
      SubagentLimits limits,
      Path childDataRoot) {
    return new SubagentManager(
        templateStore,
        launcher,
        store,
        bus,
        parentConfig,
        parentPermissions,
        parentDepth,
        () -> CommandMode.FULL,
        limits,
        childDataRoot);
  }

  /** 子库根（§2.3）：与生产装配同形（{@code <dataDir>/subagents}），测试里挂在 {@link #tempDir} 下。 */
  private Path childDataRoot() {
    return tempDir.resolve("subagents");
  }

  /**
   * 模拟子体的协议义务（§2.1）：把终局记录写进<b>自己的</b>库 {@code <root>/<id>/events.db}。
   *
   * <p>为什么夹具要写它：父侧判终局<b>只认这条记录</b>（子体正常路径一定先落库后退栈）。夹具里"跑完"的子体不写 ⇒ 被如实记成异常终局 —— 这正是判别性的一半（另一半见
   * {@link #exitWithoutTerminalRecordIsFailedWithDesignReason}）。
   */
  private void writeChildTerminalRecord(
      String instanceId, String stopReason, int turns, int toolCalls) {
    Path dir = childDataRoot().resolve(instanceId);
    try {
      Files.createDirectories(dir);
      try (SqliteEventStore child = SqliteEventStore.open(dir.resolve("events.db"))) {
        child.append(
            io.mosire.agentlib.event.EventWrite.of(
                EventTypes.AGENT_LIFECYCLE,
                instanceId,
                JSON.writeValueAsString(
                    Map.of(
                        "action", "finished",
                        "stopReason", stopReason,
                        "turns", turns,
                        "toolCalls", toolCalls)),
                instanceId));
      }
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException("子库终局记录写入失败: " + instanceId, e);
    }
  }

  /** 原始终局记录写入（⑦ 老库/坏字段用例要写"不全"的 payload——{@code writeChildTerminalRecord} 写不出缺键形态）。 */
  private void writeChildTerminalRecordPayload(String instanceId, String payloadJson) {
    Path dir = childDataRoot().resolve(instanceId);
    try {
      Files.createDirectories(dir);
      try (SqliteEventStore child = SqliteEventStore.open(dir.resolve("events.db"))) {
        child.append(
            io.mosire.agentlib.event.EventWrite.of(
                EventTypes.AGENT_LIFECYCLE, instanceId, payloadJson, instanceId));
      }
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException("子库终局记录写入失败: " + instanceId, e);
    }
  }

  /** 实例快照（只给关停排序用例造输入：血缘路径/状态是唯二被 {@link SubagentManager#shutdownOrder} 读到的字段）。 */
  private static SubagentInstance instance(String id, String lineagePath, SubagentStatus status) {
    return new SubagentInstance(
        id,
        "reader",
        "目标",
        AgentConfig.builder(id).build(),
        AgentPermissionSet.builder(AccessToken.DEFAULT).build(),
        CommandMode.LIMITED,
        1,
        status,
        "",
        lineagePath);
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
    return template(
        id,
        allowedTools,
        deniedTools,
        destructiveAllowed,
        sensitiveAllowed,
        readOnly,
        maxTurns,
        maxToolCallsPerTurn,
        timeBudgetSeconds,
        quotaMaxTokens,
        null);
  }

  /** 带 {@code allowedWorkingDirs}（S5-B）的重载：null = 不限，空表 = 哪里都不许。 */
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
      long quotaMaxTokens,
      List<String> allowedWorkingDirs) {
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
        quotaMaxTokens,
        List.of(),
        allowedWorkingDirs);
  }

  /** S5-B 用的模板：只差一组 {@code allowedWorkingDirs}（null = 不限，空表 = 哪里都不许），其余取缺省。 */
  private static AgentTemplate templateWithDirs(List<String> allowedWorkingDirs) {
    return template(
        "reader",
        Set.of("read"),
        Set.of(),
        true,
        false,
        false,
        2000,
        30,
        600,
        0,
        allowedWorkingDirs);
  }

  /** 一份"只把 {@code fs} 面收紧到给定目录"的权限集（其余维照 {@code system()}）。 */
  private static AgentPermissionSet withFs(ResourceScope fs) {
    return AgentPermissionSet.system()
        .withResourceScopes(ResourceScopeMap.of(ResourceScopeMap.FS, fs));
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
    public LaunchedSubagent launch(SubagentInstance instance) {
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

  /**
   * 崩退 launcher（S5-E 诊断面）：句柄"启动即死"（观测线程下一轮轮询就投递退出事实），并带（或故意抛）诊断文本。
   *
   * <p>为什么句柄要能"抛"：{@link LaunchedSubagent#exitDiagnostics()} 是在退出观测线程里被调的，任何实现都可能在那一瞬抛
   * （子进程刚死、流已关）——最恶劣形态必须被夹具覆盖，否则"诊断不许带崩状态机"这条约束没有判别性。
   */
  private static final class CrashingLauncher implements SubagentLauncher {

    private final String diagnostics;
    private final boolean throwOnDiagnostics;
    private final java.util.OptionalInt exitCode;
    private final AtomicInteger diagnosticsCalls = new AtomicInteger();

    CrashingLauncher(String diagnostics, boolean throwOnDiagnostics) {
      this(diagnostics, throwOnDiagnostics, java.util.OptionalInt.empty());
    }

    /**
     * @param exitCode 句柄能否给出退出码（§四.6：拿不到就 {@code empty} ⇒ 事件里不写键，绝不编 0）
     */
    CrashingLauncher(
        String diagnostics, boolean throwOnDiagnostics, java.util.OptionalInt exitCode) {
      this.diagnostics = diagnostics;
      this.throwOnDiagnostics = throwOnDiagnostics;
      this.exitCode = exitCode;
    }

    int diagnosticsCalls() {
      return diagnosticsCalls.get();
    }

    @Override
    public LaunchedSubagent launch(SubagentInstance instance) {
      return new LaunchedSubagent() {
        @Override
        public boolean isAlive() {
          return false;
        }

        @Override
        public Optional<String> exitDiagnostics() {
          diagnosticsCalls.incrementAndGet();
          if (throwOnDiagnostics) {
            throw new IllegalStateException("诊断实现自身崩了");
          }
          return Optional.of(diagnostics);
        }

        @Override
        public java.util.OptionalInt exitCode() {
          return exitCode;
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public void close() {}
  }

  /**
   * 关停顺序 launcher（§2.7 的观测面）：每个句柄的 {@code close()} 把自己记进<b>一个全局序列</b>——"谁先收到关停"由此可判。
   *
   * <p>句柄一直活着，直到被关停（close 置死）：这既是 close() 路径的前提，也让 {@code terminate} 的 {@code awaitDead} 快速收束。
   */
  private static final class OrderRecordingLauncher implements SubagentLauncher {

    private final List<String> closeOrder =
        java.util.Collections.synchronizedList(new ArrayList<>());
    private final Map<String, AtomicBoolean> alive = new java.util.concurrent.ConcurrentHashMap<>();

    List<String> closeOrder() {
      return List.copyOf(closeOrder);
    }

    @Override
    public LaunchedSubagent launch(SubagentInstance instance) {
      String id = instance.instanceId();
      alive.put(id, new AtomicBoolean(true));
      AtomicBoolean closed = new AtomicBoolean(false); // close 幂等：序列里一个实例只出现一次
      return new LaunchedSubagent() {
        @Override
        public boolean isAlive() {
          return alive.get(id).get();
        }

        @Override
        public void close() {
          if (closed.compareAndSet(false, true)) {
            alive.get(id).set(false);
            closeOrder.add(id);
          }
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
    private volatile SubagentInstance lastInstance;

    int closeCalls() {
      return closeCalls.get();
    }

    int selfCloseCalls() {
      return selfCloseCalls.get();
    }

    SubagentInstance lastInstance() {
      return lastInstance;
    }

    @Override
    public LaunchedSubagent launch(SubagentInstance instance) {
      lastInstance = instance;
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
