package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.EventTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W3 组件 4 契约：三个内置编排工具（{@code spawn_sub_agent}/{@code kill_sub_agent}/{@code
 * list_sub_agents}）的注册签名、权限三要素（{@code ToolSpec.level(SYSTEM, sensitive=false,
 * destructive=true)}——GUEST/DEFAULT 经 guard 天然拒绝）、参数约定与错误映射。 全离线（InProcessExecutor + COUNT_DOWN
 * 门闩；无真实子进程——真实子进程见 Main 侧 W3 E2E）。
 */
class SubagentOrchestrationToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  /**
   * 工具面 spec（S5-A 起 spawn 与另两个<b>分档</b>）：{@code spawn_sub_agent} = DEFAULT 级（子 Agent 也能派孙代），{@code
   * kill}/{@code list} 仍是 SYSTEM（子体能派、不能收别人的）。
   *
   * <p>判别性：把 spawn 的 {@code requiredLevel} 改回 SYSTEM，本用例第一处断言即转红——这正是"降级真的生效"的判别面 （S4-A 之前 spec
   * 是装饰，MCP 路径裸调 {@code tool.execute}）。
   */
  @Test
  void spawnIsDefaultLevelWhileKillAndListStaySystem() {
    Fixture f = fixture(systemParent(), null);
    List<AgentTool> tools = SubagentOrchestrationTools.of(f.manager());
    // D30 起是四个（多 read_agent_context）；注册序 = 名字字典序
    assertThat(tools)
        .extracting(AgentTool::name)
        .containsExactly(
            "kill_sub_agent", "list_sub_agents", "read_agent_context", "spawn_sub_agent");

    Map<String, ToolSpec> specs = new LinkedHashMap<>();
    for (AgentTool tool : tools) {
      specs.put(tool.name(), tool.spec());
    }
    assertThat(specs.get(SubagentOrchestrationTools.SPAWN_SUB_AGENT))
        .isEqualTo(ToolSpec.level(AccessToken.DEFAULT, false, true));
    assertThat(specs.get(SubagentOrchestrationTools.KILL_SUB_AGENT))
        .isEqualTo(ToolSpec.level(AccessToken.SYSTEM, false, true));
    assertThat(specs.get(SubagentOrchestrationTools.LIST_SUB_AGENTS))
        .isEqualTo(ToolSpec.level(AccessToken.SYSTEM, false, true));
    // 读工具的 spec 另有一维（noExport=true）+ sensitive=true，判别在 ReadAgentContextToolTest
    assertThat(specs.get(SubagentOrchestrationTools.READ_AGENT_CONTEXT))
        .isEqualTo(ToolSpec.level(AccessToken.SYSTEM, true, false, true));

    for (AgentTool tool : tools) {
      if (tool.name().equals(SubagentOrchestrationTools.READ_AGENT_CONTEXT)) {
        continue;
      }
      assertThat(tool.spec().noExport()).isFalse(); // 三个非读工具仍可外发（子体拿得到 spawn/kill/list）
    }
    f.close();
  }

  @Test
  void spawnToolRunsChildToRunningWithLifecycleChain() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch blocked = new CountDownLatch(1);
    Fixture f =
        fixture(
            systemParent(),
            new InProcessExecutor(
                (instanceId, config) -> {
                  started.countDown();
                  blocked.await();
                }));
    ToolRegistry registry = registryOf(f.manager());

    ToolResult result =
        new ToolExecutionGuard()
            .execute(
                registry,
                "spawn_sub_agent",
                new ToolContext(
                    AccessToken.SYSTEM,
                    AgentPermissionSet.system(),
                    Map.of(),
                    Map.of("templateId", "reader", "goal", "向主 Agent 问好")));
    assertThat(result.success()).isTrue();
    Map<String, Object> snapshot = JSON.readValue(result.message(), Map.class);
    String childId = (String) snapshot.get("instanceId");
    assertThat(childId).startsWith("reader-");
    assertThat(snapshot.get("status")).isEqualTo("RUNNING");
    assertThat(snapshot.get("depth")).isEqualTo(1);

    // 生命周期事件链已落库（correlationId = 子实例 id）
    assertThat(f.lifecycleActions(childId)).containsExactly("configured", "spawning", "running");
    assertThat(f.manager().get(childId).orElseThrow().permissions().isToolAllowed("echo")).isTrue();
    assertThat(
            f.manager().get(childId).orElseThrow().permissions().isToolAllowed("spawn_sub_agent"))
        .isFalse();

    started.await(2, TimeUnit.SECONDS);
    blocked.countDown();
    f.close();
  }

  @Test
  void killToolTerminatesRunningChildAndEmitsKilled() throws Exception {
    CountDownLatch blocked = new CountDownLatch(1);
    Fixture f =
        fixture(systemParent(), new InProcessExecutor((instanceId, config) -> blocked.await()));
    ToolExecutionGuard guard = new ToolExecutionGuard();
    ToolRegistry registry = registryOf(f.manager());

    ToolResult spawn =
        guard.execute(
            registry,
            "spawn_sub_agent",
            new ToolContext(
                AccessToken.SYSTEM,
                AgentPermissionSet.system(),
                Map.of(),
                Map.of("templateId", "reader", "goal", "g")));
    String childId = (String) JSON.<Map>readValue(spawn.message(), Map.class).get("instanceId");
    assertThat(childId).isNotNull();

    // kill 时子体仍阻塞（RUNNING）：kill → TERMINATING →（句柄关停）→ KILLED 事件链
    ToolResult kill =
        guard.execute(registry, "kill_sub_agent", context(Map.of("instanceId", childId)));
    assertThat(kill.success()).isTrue();
    assertThat(kill.message()).contains(childId);

    awaitStatus(f.manager(), childId, "KILLED");
    assertThat(f.lifecycleActions(childId))
        .containsExactly("configured", "spawning", "running", "terminating", "killed");

    // 终态幂等：重复 kill 仍是工具级成功（空操作语义），不抛
    ToolResult again =
        guard.execute(registry, "kill_sub_agent", context(Map.of("instanceId", childId)));
    assertThat(again.success()).isTrue();
    // 未知 id → 可读错误码，不向 LLM 抛裸异常
    ToolResult unknown =
        guard.execute(registry, "kill_sub_agent", context(Map.of("instanceId", "nope-1234-nope")));
    assertThat(unknown.success()).isFalse();
    assertThat(unknown.code()).isEqualTo("UNKNOWN_INSTANCE");
    blocked.countDown();
    f.close();
  }

  @Test
  void listToolSnapshotsInstancesIncludingNonFinalStatus() throws Exception {
    CountDownLatch blocked = new CountDownLatch(1);
    Fixture f =
        fixture(systemParent(), new InProcessExecutor((instanceId, config) -> blocked.await()));
    ToolExecutionGuard guard = new ToolExecutionGuard();
    ToolRegistry registry = registryOf(f.manager());

    guard.execute(
        registry,
        "spawn_sub_agent",
        new ToolContext(
            AccessToken.SYSTEM,
            AgentPermissionSet.system(),
            Map.of(),
            Map.of("templateId", "reader", "goal", "g")));

    ToolResult list = guard.execute(registry, "list_sub_agents", context(Map.of()));
    assertThat(list.success()).isTrue();
    List<Map<String, Object>> rows = JSON.readValue(list.message(), List.class);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsKeys("instanceId", "templateId", "depth", "status")
        .containsEntry("templateId", "reader");
    assertThat(rows.get(0).get("status")).isEqualTo("RUNNING");
    assertThat(rows.get(0).get("depth")).isEqualTo(1);
    blocked.countDown();
    f.close();
  }

  /**
   * 红线 1 单调性在<b>工具路径</b>上按调用者权限集判（S5-A 锚点修正）：调用者只有 {@code echo}，子模板想带 {@code admin-do} ⇒ 拒。
   *
   * <p><b>判别性</b>：本 fixture 的 manager 注入的是 {@code system()}（全放行）——旧实现拿构造期常量对照，这次派生物会被
   * <b>放行</b>；把对照物换回 manager 常量，本用例转红。拒绝类事件的 {@code agent} 必须是调用者实例 id（"谁被拒了"）。
   */
  @Test
  void escalatedSpawnRejectedWithPermissionDeniedEventAndNoInstance() {
    writeTemplate("grand", Set.of("echo", "admin-do"), "想提权的子 Agent");
    Fixture f = fixture(systemParent(), null);
    ToolExecutionGuard guard = new ToolExecutionGuard();
    ToolRegistry registry = registryOf(f.manager());

    // 调用者自己的白名单：能调 spawn 且放行破坏性操作（否则连工具都进不来，见 guard 层两个身份闸用例），
    // 但<b>没有 admin-do</b>——这正是要被单调性拦下的那一项
    AgentPermissionSet callerPermissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow("echo")
            .allow("spawn_sub_agent")
            .destructiveAllowed(true)
            .build();
    AgentIdentity caller = AgentIdentity.subagent("limited-parent-1", CommandMode.FULL, "上级派的任务");

    ToolResult result =
        guard.execute(
            registry,
            "spawn_sub_agent",
            new ToolContext(
                AccessToken.DEFAULT,
                callerPermissions,
                Map.of(),
                Map.of("templateId", "grand", "goal", "提权尝试"),
                caller));
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("PERMISSION_DENIED");
    assertThat(result.message()).contains("父级许可");

    // 拒绝类事件 agent=调用者 id、correlationId=拟生成的子 id，且没有实例落地
    List<Event> denied =
        f.events()
            .query(new EventQuery("limited-parent-1", EventTypes.PERMISSION_DENIED, "", -1, 100));
    assertThat(denied).hasSize(1);
    assertThat(denied.get(0).correlationId()).startsWith("grand-");
    assertThat(f.manager().list()).isEmpty();
    f.close();
  }

  /**
   * guard 层身份闸（S5-A 起<b>分档</b>）：GUEST 三个工具全拒；DEFAULT（= 子 Agent 的默认级别）只进得去 {@code
   * spawn_sub_agent}，{@code kill}/{@code list} 仍被 SYSTEM 级挡在门外。
   *
   * <p>判别性：spawn 若还是 SYSTEM 级，"DEFAULT 派得出孙代"那步会被 guard 拦下（转红）；kill/list 若跟着降到 DEFAULT， 第二段断言转红。
   */
  @Test
  void guardDeniesEverythingForGuestAndOnlySpawnForDefault() {
    Fixture f = fixture(systemParent(), new InProcessExecutor((instanceId, config) -> {}));
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    for (String name : List.of("spawn_sub_agent", "kill_sub_agent", "list_sub_agents")) {
      ToolResult denied =
          guard.execute(
              registry,
              name,
              new ToolContext(
                  AccessToken.GUEST,
                  AgentPermissionSet.builder(AccessToken.GUEST).allowAll().build(),
                  Map.of(),
                  Map.of("templateId", "reader", "goal", "提权尝试")));
      assertThat(denied.success()).as(name).isFalse();
      assertThat(denied.code()).as(name).isEqualTo(ToolExecutionGuard.DENIED);
      assertThat(denied.message()).contains("身份级别不足");
    }

    // 身份级别闸读的是**权限集的 grantedToken**（PermissionChecker 第 3 条），不是 ToolContext.caller——
    // 所以"DEFAULT 调用者"必须连权限集一起是 DEFAULT，否则这个用例会因为权限集恰好是 system() 而假通过
    ToolContext defaultCaller =
        new ToolContext(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT)
                .allowAll()
                .destructiveAllowed(true)
                .build(),
            Map.of(),
            Map.of("templateId", "reader", "goal", "派孙代"));
    ToolResult spawned = guard.execute(registry, "spawn_sub_agent", defaultCaller);
    assertThat(spawned.success()).isTrue();
    assertThat(f.manager().list()).hasSize(1);

    for (String name : List.of("kill_sub_agent", "list_sub_agents")) {
      ToolResult denied = guard.execute(registry, name, defaultCaller);
      assertThat(denied.success()).as(name).isFalse();
      assertThat(denied.code()).as(name).isEqualTo(ToolExecutionGuard.DENIED);
      assertThat(denied.message()).contains("身份级别不足");
    }
    f.close();
  }

  /**
   * 预算耗尽在工具面的映射：{@code BUDGET_EXHAUSTED} 与越权 {@code DENIED} <b>分码</b>——前者"现在不行"（等/收敛）， 后者"你不该要"。
   *
   * <p>判别性：{@link SubagentBudgetExceededException} 是 {@link SubagentRejectedException} 的子类，catch
   * 顺序若被 调换（预算落到 {@code SubagentRejectedException} 那支），码会变成 {@code DENIED}，本用例转红。
   */
  @Test
  void exhaustedBudgetMapsToBudgetExhaustedCodeNotDenied() throws Exception {
    CountDownLatch blocked = new CountDownLatch(1);
    Fixture f =
        fixture(
            systemParent(),
            new InProcessExecutor((instanceId, config) -> blocked.await()),
            new SubagentLimits(3, 1, 8));
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    ToolResult first =
        guard.execute(
            registry, "spawn_sub_agent", context(Map.of("templateId", "reader", "goal", "占住额度")));
    assertThat(first.success()).isTrue();

    ToolResult second =
        guard.execute(
            registry, "spawn_sub_agent", context(Map.of("templateId", "reader", "goal", "再要一个")));
    assertThat(second.success()).isFalse();
    assertThat(second.code()).isEqualTo("BUDGET_EXHAUSTED");
    assertThat(second.message()).contains("需求=1").contains("剩余=0").contains("上限=1");
    assertThat(f.manager().list()).as("被拒的那次不留半个实例").hasSize(1);
    blocked.countDown();
    f.close();
  }

  /**
   * S6 档位单调性（<b>调用者</b>侧）：{@code mode} 不得超过调用者<b>自己</b>的档位。身份由宿主绑定（本用例显式给 {@link
   * AgentIdentity#subagent}），模型给不出也改不了。
   *
   * <p>判别性构造：本 fixture 的 {@link SubagentManager} 走 7 参构造 ⇒ 父档位缺省 {@code FULL}，所以"请求被拒"
   * 只可能来自调用者这一维——把这道检查删掉，{@code mode=full} 会一路走到 manager 并被接受（父档位覆盖得动），
   * 本用例转红。两个维度都必要：父级闸管"本级给不给"，调用者闸管"谁在要"——链上每一跳都受后者约束， 否则一个 LIMITED 的下级能借本级要出 FULL 的下级。
   *
   * <p>本用例只断言"拒/放"与实例数，不掺子进程：executor 用 {@link InProcessExecutor} 且任务是空实现。
   */
  @Test
  void spawnModeCannotExceedTheCallerMode() throws Exception {
    Fixture f = fixture(systemParent(), new InProcessExecutor((instanceId, config) -> {}));
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    ToolResult denied =
        guard.execute(
            registry,
            "spawn_sub_agent",
            new ToolContext(
                AccessToken.SYSTEM,
                AgentPermissionSet.system(),
                Map.of(),
                Map.of("templateId", "reader", "goal", "越档尝试", "mode", "full"),
                AgentIdentity.subagent("limited-child-1", CommandMode.LIMITED, "上级派的任务")));

    assertThat(denied.success()).isFalse();
    assertThat(denied.code()).isEqualTo(ToolExecutionGuard.DENIED);
    assertThat(denied.message()).contains("LIMITED").contains("FULL");
    assertThat(f.manager().list()).as("拒绝必须发生在起实例之前（不留半个实例）").isEmpty();

    // 反向对照：同档要得到——否则本用例退化成"怎么都拒"，判别力为零
    ToolResult accepted =
        guard.execute(
            registry,
            "spawn_sub_agent",
            new ToolContext(
                AccessToken.SYSTEM,
                AgentPermissionSet.system(),
                Map.of(),
                Map.of("templateId", "reader", "goal", "同档尝试", "mode", "limited"),
                AgentIdentity.subagent("limited-child-2", CommandMode.LIMITED, "上级派的任务")));
    assertThat(accepted.success()).isTrue();
    assertThat(f.manager().list()).hasSize(1);
    assertThat(f.manager().list().get(0).mode()).isEqualTo(CommandMode.LIMITED);
    f.close();
  }

  @Test
  void missingRequiredArgumentsYieldReadableError() {
    Fixture f = fixture(systemParent(), null);
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    ToolResult noGoal =
        guard.execute(registry, "spawn_sub_agent", context(Map.of("templateId", "reader")));
    assertThat(noGoal.success()).isFalse();
    assertThat(noGoal.code()).isEqualTo("INVALID_ARGUMENTS");
    ToolResult noId = guard.execute(registry, "kill_sub_agent", context(Map.of()));
    assertThat(noId.code()).isEqualTo("INVALID_ARGUMENTS");
    f.close();
  }

  @Test
  void nonNumericCapArgumentsYieldInvalidArgumentsInsteadOfRelaxing() {
    Fixture f = fixture(systemParent(), null);
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    // "1e4"（科学计数写法，Integer/Long.parse 不认）：字段存在但非数字 → INVALID_ARGUMENTS
    // （不得静默退化为"未收紧"——那会是无抱怨地放宽上限，反转 fail-safe 方向）
    ToolResult badTurns =
        guard.execute(
            registry,
            "spawn_sub_agent",
            context(Map.of("templateId", "reader", "goal", "g", "maxTurnsCap", "1e4")));
    assertThat(badTurns.success()).isFalse();
    assertThat(badTurns.code()).isEqualTo("INVALID_ARGUMENTS");
    assertThat(badTurns.message()).contains("maxTurnsCap").contains("必须是整数");

    ToolResult badQuota =
        guard.execute(
            registry,
            "spawn_sub_agent",
            context(Map.of("templateId", "reader", "goal", "g", "quotaMaxTokensCap", "1e4")));
    assertThat(badQuota.success()).isFalse();
    assertThat(badQuota.code()).isEqualTo("INVALID_ARGUMENTS");
    assertThat(badQuota.message()).contains("quotaMaxTokensCap");

    // 解析期拒绝：无实例落地（fail-safe）；字段缺失仍为"不收紧"（null 合法），合法数字照常放行
    assertThat(f.manager().list()).isEmpty();
    ToolResult ok =
        guard.execute(
            registry,
            "spawn_sub_agent",
            context(Map.of("templateId", "reader", "goal", "g", "maxTurnsCap", 10)));
    assertThat(ok.success()).isTrue();
    f.close();
  }

  // ---- S5-B：allowedDirs 参数面 ----

  /**
   * {@code allowedDirs} 一路落到子体的 {@code fs} 面（父级之内再收窄），且子体这一维是<b>物化</b>的。
   *
   * <p><b>判别性</b>：参数面若不透传（漏传/吞掉），子体会拿到调用者的整面 ⇒ 第二条断言转红。这里刻意让请求比调用者窄一层， 好把"透传了"和"透传后被忽略"分开。
   */
  @Test
  void allowedDirsArgumentReachesTheChildsFsScope() throws Exception {
    Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
    Path narrow = Files.createDirectories(allowed.resolve("narrow"));
    Fixture f = fixture(systemParent(), null);
    ToolRegistry registry = registryOf(f.manager());

    ToolResult result =
        new ToolExecutionGuard()
            .execute(
                registry,
                "spawn_sub_agent",
                contextWith(
                    withFs(ResourceScope.ofDir(allowed)),
                    Map.of(
                        "templateId",
                        "reader",
                        "goal",
                        "窄调查",
                        "allowedDirs",
                        List.of(narrow.toString()))));
    assertThat(result.success()).as(result.message()).isTrue();
    String childId = (String) JSON.<Map>readValue(result.message(), Map.class).get("instanceId");

    ResourceScope childFs = f.manager().get(childId).orElseThrow().permissions().fsScope();
    assertThat(childFs.allowsDir(narrow.resolve("x"))).isTrue();
    assertThat(childFs.allowsDir(allowed.resolve("sibling"))).as("收窄真的生效过").isFalse();
    assertThat(
            f.manager()
                .get(childId)
                .orElseThrow()
                .permissions()
                .resourceScopes()
                .declaredScope("fs"))
        .isNotNull();
    f.close();
  }

  /**
   * 请求面越界 ⇒ <b>工具码 {@code DIR_NOT_ALLOWED}</b>（不是笼统的 DENIED），子体不落地。
   *
   * <p><b>判别性</b>：把异常映射退回 {@code SubagentRejectedException} 那一条（去掉独立 catch），码会变成 DENIED ⇒ 第一条断言转红——
   * 而这两个码对模型说的是两件事（"要个够得着的目录" vs "你不该用这个工具"）。
   */
  @Test
  void allowedDirsBeyondTheCallerYieldDirNotAllowed() throws Exception {
    Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
    Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));
    Fixture f = fixture(systemParent(), null);
    ToolRegistry registry = registryOf(f.manager());

    ToolResult result =
        new ToolExecutionGuard()
            .execute(
                registry,
                "spawn_sub_agent",
                contextWith(
                    withFs(ResourceScope.ofDir(allowed)),
                    Map.of(
                        "templateId",
                        "reader",
                        "goal",
                        "越界调查",
                        "allowedDirs",
                        List.of(elsewhere.toString()))));

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DIR_NOT_ALLOWED");
    assertThat(result.message()).contains("DIR_NOT_ALLOWED");
    assertThat(f.manager().list()).as("拒绝发生在起实例之前").isEmpty();
    assertThat(f.events().query(new EventQuery("", EventTypes.AGENT_LIFECYCLE, "", -1, 100)))
        .as("一个生命周期事件都不该有")
        .isEmpty();
    f.close();
  }

  /**
   * {@code allowedDirs} 的元素是空白（或 JSON {@code null}）⇒ {@code INVALID_ARGUMENTS}（模型参数问题），
   * 与"越界"（{@code DIR_NOT_ALLOWED}）分码；两者都不得静默当成"没提这一嘴"。
   *
   * <p>非字符串元素（如 {@code 42}）<b>不</b>在这里拒绝：本工具的文本参数一律走 {@code String.valueOf} 强转（{@code
   * strArg}/{@code strSetArg} 同惯例），"42" 会当成相对路径按进程 cwd 解析——它是"一个真的目录要求"，成不成立交给可达面判。
   */
  @Test
  void blankAllowedDirsElementsYieldInvalidArguments() throws Exception {
    Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
    Fixture f = fixture(systemParent(), null);
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    List<Object> withNull = new ArrayList<>();
    withNull.add(null);
    List<Object> cases = new ArrayList<>();
    cases.add("");
    cases.add("   ");
    cases.add(withNull);

    for (Object bad : cases) {
      ToolResult result =
          guard.execute(
              registry,
              "spawn_sub_agent",
              contextWith(
                  withFs(ResourceScope.ofDir(allowed)),
                  Map.of("templateId", "reader", "goal", "g", "allowedDirs", bad)));
      assertThat(result.success()).as("allowedDirs=%s", bad).isFalse();
      assertThat(result.code()).as("allowedDirs=%s", bad).isEqualTo("INVALID_ARGUMENTS");
      assertThat(result.message()).as("allowedDirs=%s", bad).contains("allowedDirs");
    }
    assertThat(f.manager().list()).as("一个实例都不该落地").isEmpty();
    f.close();
  }

  /**
   * 调用者<b>不限</b>时 {@code allowedDirs} 照常是"要求"（收窄生效），不是"没接上"：给面接上了，只是没人收窄过。
   *
   * <p>判别性：把"调用者不限"实现成"跳过请求面"（例如 {@code if (callerFs.unrestricted()) return callerFs;}），
   * 第二条断言转红——不限不等于"请求面可以随便被忽略"。
   */
  @Test
  void allowedDirsStillBindsWhenTheCallerIsUnrestricted() throws Exception {
    Path narrow = Files.createDirectories(tempDir.resolve("narrow"));
    Fixture f = fixture(systemParent(), null);
    ToolRegistry registry = registryOf(f.manager());

    ToolResult result =
        new ToolExecutionGuard()
            .execute(
                registry,
                "spawn_sub_agent",
                context(
                    Map.of(
                        "templateId",
                        "reader",
                        "goal",
                        "g",
                        "allowedDirs",
                        List.of(narrow.toString()))));
    assertThat(result.success()).as(result.message()).isTrue();
    String childId = (String) JSON.<Map>readValue(result.message(), Map.class).get("instanceId");

    ResourceScope childFs = f.manager().get(childId).orElseThrow().permissions().fsScope();
    assertThat(childFs.unrestricted()).isFalse();
    assertThat(childFs.allowsDir(narrow.resolve("x"))).isTrue();
    assertThat(childFs.allowsDir(tempDir.resolve("outside"))).isFalse();
    f.close();
  }

  // ---- fixtures ----

  /** 管理器 + 事件库 + 总线（测试自建并注入，便于直接查事件）。 */
  private static final class Fixture implements AutoCloseable {
    private final SqliteEventStore store;
    private final EventBus bus;
    private final SubagentManager manager;

    Fixture(SqliteEventStore store, EventBus bus, SubagentManager manager) {
      this.store = store;
      this.bus = bus;
      this.manager = manager;
    }

    SqliteEventStore events() {
      return store;
    }

    SubagentManager manager() {
      return manager;
    }

    /** 子 Agent 生命周期动作序列（seq 升序）。 */
    List<String> lifecycleActions(String childId) {
      List<Event> events =
          store.query(new EventQuery(childId, EventTypes.AGENT_LIFECYCLE, childId, -1, 100));
      List<String> actions = new ArrayList<>();
      for (int i = events.size() - 1; i >= 0; i--) {
        try {
          actions.add(JSON.readValue(events.get(i).payload(), Map.class).get("action").toString());
        } catch (JsonProcessingException e) {
          throw new AssertionError("生命周期事件 payload 无法解析", e);
        }
      }
      return actions;
    }

    @Override
    public void close() {
      try {
        manager.close();
      } finally {
        bus.close();
        store.close();
      }
    }
  }

  private Fixture fixture(AgentPermissionSet parentPermissions, InProcessExecutor executor) {
    return fixture(parentPermissions, executor, SubagentLimits.defaults());
  }

  private Fixture fixture(
      AgentPermissionSet parentPermissions, InProcessExecutor executor, SubagentLimits limits) {
    writeTemplate("reader", Set.of("echo"), "只读问候子 Agent");
    AgentTemplateStore store = new AgentTemplateStore(templateDir());
    store.load();
    SqliteEventStore events;
    EventBus bus;
    try {
      Files.createDirectories(tempDir.resolve("data"));
      events = SqliteEventStore.open(tempDir.resolve("data").resolve("events.db"));
      bus = new EventBus();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    SubagentManager manager =
        new SubagentManager(
            store,
            executor == null ? new InProcessExecutor() : executor,
            events,
            bus,
            AgentConfig.builder("main").build(),
            parentPermissions,
            0,
            () -> CommandMode.FULL,
            limits);
    return new Fixture(events, bus, manager);
  }

  private ToolRegistry registryOf(SubagentManager manager) {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll(SubagentOrchestrationTools.of(manager));
    return registry;
  }

  private Path templateDir() {
    return tempDir.resolve("templates");
  }

  private void writeTemplate(String id, Set<String> allowedTools, String systemPrompt) {
    Path dir = templateDir();
    try {
      Files.createDirectories(dir);
      Files.writeString(
          dir.resolve(id + ".json"),
          """
          {
            "id": "%s",
            "description": "只读子 Agent",
            "systemPrompt": "%s",
            "model": "fake",
            "token": "DEFAULT",
            "allowedTools": %s,
            "deniedTools": [],
            "destructiveAllowed": false,
            "sensitiveAllowed": false,
            "readOnly": false,
            "maxTurns": 5,
            "maxToolCallsPerTurn": 5,
            "timeBudgetSeconds": 60,
            "quotaMaxTokens": 0
          }
          """
              .formatted(id, systemPrompt, JSON.writeValueAsString(allowedTools)));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static ToolContext context(Map<String, Object> args) {
    return new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), args);
  }

  /** 换一份<b>调用者权限集</b>的上下文（S5-B：fs 面受限的调用者）。 */
  private static ToolContext contextWith(AgentPermissionSet permissions, Map<String, Object> args) {
    return new ToolContext(AccessToken.SYSTEM, permissions, Map.of(), args);
  }

  /** 一份"只把 {@code fs} 面收紧到给定目录"的权限集（其余维照 {@code system()}）。 */
  private static AgentPermissionSet withFs(ResourceScope fs) {
    return AgentPermissionSet.system()
        .withResourceScopes(ResourceScopeMap.of(ResourceScopeMap.FS, fs));
  }

  private static AgentPermissionSet systemParent() {
    return AgentPermissionSet.system();
  }

  private static void awaitStatus(SubagentManager manager, String childId, String expected)
      throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      SubagentInstance current = manager.get(childId).orElse(null);
      if (current != null && current.status().name().equals(expected)) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("子 Agent 未在超时内到达 " + expected + "：" + childId);
  }
}
