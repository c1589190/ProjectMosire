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
            new InProcessExecutor((id, cfg) -> release.await()),
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
   * S5-E：子体在确认存活之后异常退出（非零码）⇒ 状态<b>仍是 FINISHED</b>（生命周期走到终局），原因经诊断面取回。
   *
   * <p>判别性：把"非零退出"顺手翻成 {@link SubagentStatus#FAILED}（一种看着更"对"的改法）会让事件链断言变红——状态是生命周期
   * 事实（确认存活后退出就是终态），退出码是诊断事实，二者混用会让需求/预算/事件账目跟着撒谎。
   */
  @Test
  void abnormalExitStaysFinishedAndTakesReasonFromTheHandle() throws Exception {
    AgentTemplate template = template("reader", Set.of("read"), Set.of(), true, false, false);
    write(template);
    CrashingLauncher launcher = new CrashingLauncher("exitCode=1；末段输出: 模板目录不存在", false);
    SubagentManager manager =
        manager(store(), launcher, parentConfig(), AgentPermissionSet.system(), 0);

    SubagentInstance spawned =
        manager.spawn(new SubagentLaunchRequest("reader", "目标", null, null, null, null));
    String id = spawned.instanceId();
    assertThat(spawned.status()).isEqualTo(SubagentStatus.RUNNING);

    awaitStatus(manager, id, SubagentStatus.FINISHED);

    assertThat(lifecycleActions(id))
        .containsExactly("configured", "spawning", "running", "finished");
    assertThat(launcher.diagnosticsCalls()).as("异常退出必须真去取原因（缝加了不用 = 没加）").isPositive();
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

    awaitStatus(manager, spawned.instanceId(), SubagentStatus.FINISHED);
    assertThat(launcher.diagnosticsCalls()).as("确认诊断面真被调过（否则本用例是空转）").isPositive();
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
    return new SubagentManager(
        templateStore,
        launcher,
        store,
        bus,
        parentConfig,
        parentPermissions,
        parentDepth,
        () -> CommandMode.FULL,
        limits);
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
    private final AtomicInteger diagnosticsCalls = new AtomicInteger();

    CrashingLauncher(String diagnostics, boolean throwOnDiagnostics) {
      this.diagnostics = diagnostics;
      this.throwOnDiagnostics = throwOnDiagnostics;
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
        public void close() {}
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
