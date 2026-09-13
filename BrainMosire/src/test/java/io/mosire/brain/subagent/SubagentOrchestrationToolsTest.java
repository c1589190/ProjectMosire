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
import java.time.Duration;
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
 * W3 组件 4 契约：四个内置编排工具（{@code spawn_sub_agent}/{@code kill_sub_agent}/{@code list_sub_agents}/{@code
 * read_agent_context}）的注册签名、权限三要素、参数约定与错误映射。
 *
 * <p><b>D27 起的判定两处各管一边</b>：guard/spec 只管"这一档能不能进这个工具"（四个工具全 {@code DEFAULT} 级），"这一个你能不能动" 由 {@link
 * ContextAccessJudge} 判（能力位 + 血缘）——所以本类的 kill/list 用例要把<b>身份</b>绑成 main（缺省 UNKNOWN 会被判定 fail-closed
 * 拒掉，见 {@link #guardOnlyAdmitsDefaultLevelAndTheJudgmentDecides}）。 全离线（InProcessExecutor +
 * COUNT_DOWN 门闩；无真实子进程——真实子进程见 Main 侧 W3 E2E）。
 */
class SubagentOrchestrationToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  /**
   * 工具面 spec（D27 起<b>五个全 DEFAULT 级</b>）：级别只回答"这一档能不能进这个工具"，"这一个你能不能动"归血缘判定 （{@link
   * ContextAccessJudge}）。破坏位仍分开：{@code kill}/{@code spawn} = destructive（模板要显式放行），{@code
   * list}/{@code read}/{@code wait} 不是。
   *
   * <p>判别性：把任一工具的 {@code requiredLevel} 改回 SYSTEM，本用例对应断言即转红——这正是"降级真的生效"的判别面 （S4-A 之前 spec 是装饰，MCP
   * 路径裸调 {@code tool.execute}）。
   */
  @Test
  void allFiveOrchestrationToolsAreDefaultLevelWithJudgmentInCharge() {
    Fixture f = fixture(systemParent(), null);
    List<AgentTool> tools = SubagentOrchestrationTools.of(f.manager());
    // D30 起四个（多 read_agent_context）、§2.5 起五个（多 wait_sub_agent）；注册序 = 名字字典序
    assertThat(tools)
        .extracting(AgentTool::name)
        .containsExactly(
            "kill_sub_agent",
            "list_sub_agents",
            "read_agent_context",
            "spawn_sub_agent",
            "wait_sub_agent");

    Map<String, ToolSpec> specs = new LinkedHashMap<>();
    for (AgentTool tool : tools) {
      specs.put(tool.name(), tool.spec());
    }
    assertThat(specs.get(SubagentOrchestrationTools.SPAWN_SUB_AGENT))
        .isEqualTo(ToolSpec.level(AccessToken.DEFAULT, false, true));
    assertThat(specs.get(SubagentOrchestrationTools.KILL_SUB_AGENT))
        .isEqualTo(ToolSpec.level(AccessToken.DEFAULT, false, true));
    assertThat(specs.get(SubagentOrchestrationTools.LIST_SUB_AGENTS))
        .isEqualTo(ToolSpec.level(AccessToken.DEFAULT, false, false));
    assertThat(specs.get(SubagentOrchestrationTools.READ_AGENT_CONTEXT))
        .isEqualTo(ToolSpec.level(AccessToken.DEFAULT, false, false));
    // §2.5：只读等待 ⇒ DEFAULT + 非敏感 + 非破坏（且不设 noExport——判定说了算）
    assertThat(specs.get(SubagentOrchestrationTools.WAIT_SUB_AGENT))
        .isEqualTo(ToolSpec.level(AccessToken.DEFAULT, false, false));

    for (AgentTool tool : tools) {
      assertThat(tool.spec().noExport())
          .as(tool.name())
          .isFalse(); // 五个都可外发（D27：读工具也去掉 noExport，改由判定守）
      assertThat(tool.spec().sensitive()).as(tool.name()).isFalse();
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
                    Map.of("templateId", "reader", "goal", "向主 Agent 问好"),
                    AgentIdentity.main(CommandMode.FULL)));
    assertThat(result.success()).isTrue();
    Map<String, Object> snapshot = JSON.readValue(result.message(), Map.class);
    String childId = (String) snapshot.get("instanceId");
    assertThat(childId).startsWith("reader-");
    assertThat(snapshot.get("status")).isEqualTo("RUNNING");
    assertThat(snapshot.get("depth")).isEqualTo(1);

    // D27 血缘：派发那一刻由 manager 写上（主 Agent 派的就是 main 段），子体不参与、也给不出
    assertThat(f.manager().get(childId).orElseThrow().lineagePath()).isEqualTo("main/" + childId);
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

    // 派发时绑 main 身份：子体的血缘 path（main/<id>）由身份解析得来，之后 kill 才落得进它的 subtree
    ToolResult spawn =
        guard.execute(
            registry,
            "spawn_sub_agent",
            new ToolContext(
                AccessToken.SYSTEM,
                AgentPermissionSet.system(),
                Map.of(),
                Map.of("templateId", "reader", "goal", "g"),
                AgentIdentity.main(CommandMode.FULL)));
    String childId = (String) JSON.<Map>readValue(spawn.message(), Map.class).get("instanceId");
    assertThat(childId).isNotNull();

    // kill 时子体仍阻塞（RUNNING）：kill → TERMINATING →（句柄关停）→ KILLED 事件链
    ToolResult kill =
        guard.execute(registry, "kill_sub_agent", context(Map.of("instanceId", childId)));
    assertThat(kill.success()).isTrue();
    assertThat(kill.message()).contains(childId);

    // §2.6：kill 返回<b>终态</b>而不是"已请求"——KILLED 明说，且<b>不带</b> stopReason（被杀的子体没交代过停因）
    Map<String, Object> killBody = okBody(kill);
    assertThat(killBody)
        .containsEntry("instanceId", childId)
        .containsEntry("status", "KILLED")
        .doesNotContainKey("stopReason");
    assertThat(killBody.get("message").toString()).contains("已终止").contains(childId);

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
            Map.of("templateId", "reader", "goal", "g"),
            AgentIdentity.main(CommandMode.FULL)));

    ToolResult list = guard.execute(registry, "list_sub_agents", context(Map.of()));
    assertThat(list.success()).isTrue();
    List<Map<String, Object>> rows = JSON.readValue(list.message(), List.class);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsKeys("instanceId", "templateId", "depth", "status")
        .containsEntry("templateId", "reader");
    assertThat(rows.get(0).get("status")).isEqualTo("RUNNING");
    assertThat(rows.get(0).get("depth")).isEqualTo(1);
    // §2.6：没终结就没有终局字段——"没有 stopReason"本身就是信息（不是填出来的假值）
    assertThat(rows.get(0)).doesNotContainKey("stopReason").doesNotContainKey("exitCode");
    blocked.countDown();
    f.close();
  }

  /**
   * §2.5 + §2.6（C 块落点）：{@code wait_sub_agent} 等到终态 ⇒ 带 {@code stopReason}/{@code turns}/{@code
   * toolCalls}； {@code list} 的同一份终局字段也带上——"正常完成"与"异常退出"在模型面上由此可分。
   *
   * <p>判别性：把终局字段从返回体里去掉（只留 status）⇒ 断言转红；把"等到终态"写成"立刻返回当前状态"⇒ status 断言转红。
   */
  @Test
  void waitToolReturnsTerminalOutcomeAndListCarriesTerminalFields() throws Exception {
    Fixture f =
        fixture(
            systemParent(),
            new InProcessExecutor(
                (instanceId, config) -> writeChildTerminalRecord(instanceId, "FINISHED", 4, 3)));
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    ToolResult spawn =
        guard.execute(
            registry, "spawn_sub_agent", context(Map.of("templateId", "reader", "goal", "跑完就退")));
    String childId = (String) okBody(spawn).get("instanceId");

    ToolResult waited =
        guard.execute(
            registry, "wait_sub_agent", context(Map.of("instanceId", childId, "timeoutMs", 5000)));
    Map<String, Object> body = okBody(waited);
    assertThat(body)
        .containsEntry("instanceId", childId)
        .containsEntry("status", "FINISHED")
        .containsEntry("stopReason", "FINISHED")
        .containsEntry("turns", 4)
        .containsEntry("toolCalls", 3);
    assertThat(((Number) body.get("waitedMillis")).longValue()).isBetween(0L, 5000L);

    ToolResult list = guard.execute(registry, "list_sub_agents", context(Map.of()));
    List<Map<String, Object>> rows = JSON.readValue(list.message(), List.class);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("status", "FINISHED")
        .containsEntry("stopReason", "FINISHED")
        .containsEntry("turns", 4);
    f.close();
  }

  /**
   * §2.5 / §四.4 的第二态：<b>超时不是错误</b>——到点成功返回当前 {@code status}（不抛、不报错误码），模型自己决定再等还是 kill。
   *
   * <p>判别性：把超时实现成 {@code ToolResult.error}（"等不到就报错"的直觉改法）⇒ {@link #okBody} 断言转红；把等待实现成"立即返回"⇒
   * {@code elapsedMillis} 断言转红。
   */
  @Test
  void waitToolTimesOutWithoutError() throws Exception {
    CountDownLatch blocked = new CountDownLatch(1);
    Fixture f =
        fixture(systemParent(), new InProcessExecutor((instanceId, config) -> blocked.await()));
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    ToolResult spawn =
        guard.execute(
            registry, "spawn_sub_agent", context(Map.of("templateId", "reader", "goal", "长任务")));
    String childId = (String) okBody(spawn).get("instanceId");

    long started = System.nanoTime();
    ToolResult waited =
        guard.execute(
            registry, "wait_sub_agent", context(Map.of("instanceId", childId, "timeoutMs", 300)));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    Map<String, Object> body = okBody(waited); // 成功结果：超时不是错误
    assertThat(body)
        .containsEntry("status", "RUNNING")
        .doesNotContainKey("stopReason")
        .doesNotContainKey("exitCode");
    assertThat(elapsedMillis).as("真把预算等完了（不是立刻返回）").isGreaterThanOrEqualTo(200L);
    blocked.countDown();
    f.close();
  }

  /**
   * §2.5 的单次等待上限（必改 2，2026-09-14 裁决）：{@code timeoutMs > MAX_WAIT_MILLIS} ⇒ {@code
   * INVALID_ARGUMENTS}， 消息里写明<b>上限值</b>与<b>替代做法</b>；是<b>拒</b>不是<b>静默截断</b>（截断 =
   * 悄悄改掉模型给的预算，模型会以为自己等了 120 s）。
   *
   * <p><b>"没等"本身就是判据的一半</b>：夹具的子体被门闩钉住、永不收尾——若这次调用真的等过，它只可能在预算（30001 ms）耗尽之后才返回 {@code
   * ok(RUNNING)}。所以"错误码 + 亚秒返回 + 状态未推进"三点合起来才是"没等"的判别面；只断言错误码说不出"等没等"。
   *
   * <p>判别性：删掉上限检查 ⇒ 本用例在 ~30 s 后拿到 {@code ok(RUNNING)} ⇒ 转红（变异自证形态见本轮报告）。
   */
  @Test
  void waitToolRejectsTimeoutBeyondCapWithoutWaiting() throws Exception {
    CountDownLatch blocked = new CountDownLatch(1);
    Fixture f =
        fixture(systemParent(), new InProcessExecutor((instanceId, config) -> blocked.await()));
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    ToolResult spawn =
        guard.execute(
            registry, "spawn_sub_agent", context(Map.of("templateId", "reader", "goal", "长任务")));
    String childId = (String) okBody(spawn).get("instanceId");

    long started = System.nanoTime();
    ToolResult rejected =
        guard.execute(
            registry,
            "wait_sub_agent",
            context(
                Map.of(
                    "instanceId",
                    childId,
                    "timeoutMs",
                    SubagentOrchestrationTools.MAX_WAIT_MILLIS + 1)));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(rejected.success()).isFalse();
    assertThat(rejected.code()).isEqualTo("INVALID_ARGUMENTS");
    assertThat(rejected.message())
        .contains(String.valueOf(SubagentOrchestrationTools.MAX_WAIT_MILLIS))
        .contains("分几次等")
        .contains("list_sub_agents");
    assertThat(elapsedMillis).as("没等：真等的话夹具要等满 30001 ms 才会返回").isLessThan(5_000L);
    assertThat(f.manager().get(childId).orElseThrow().status().name())
        .as("状态未被推进（没发生等待）")
        .isEqualTo("RUNNING");
    blocked.countDown();
    f.close();
  }

  /**
   * 上限的<b>边界</b>（必改 2）：{@code timeoutMs = MAX_WAIT_MILLIS}（30000）仍然合法，且"超时不是错误"的红线在边界上不破——
   * 非终态实例到点<b>正常返回</b>当前状态（ok + waitedMillis），不是错误码。本用例真等满 30 s：这正是判据要求的代价。
   *
   * <p>判别性：把上限判成 {@code >=}（边界值也被拒）⇒ 本用例转红；把"到时"实现成 error（"等不到就报错"的直觉改法）⇒ {@link #okBody} 转红。
   */
  @Test
  void waitToolCapBoundaryTimesOutAsNormalReturn() throws Exception {
    CountDownLatch blocked = new CountDownLatch(1);
    Fixture f =
        fixture(systemParent(), new InProcessExecutor((instanceId, config) -> blocked.await()));
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    ToolResult spawn =
        guard.execute(
            registry, "spawn_sub_agent", context(Map.of("templateId", "reader", "goal", "长任务")));
    String childId = (String) okBody(spawn).get("instanceId");

    ToolResult waited =
        guard.execute(
            registry,
            "wait_sub_agent",
            context(
                Map.of(
                    "instanceId",
                    childId,
                    "timeoutMs",
                    SubagentOrchestrationTools.MAX_WAIT_MILLIS)));

    Map<String, Object> body = okBody(waited); // 成功结果：边界值合法、超时不是错误
    assertThat(body)
        .containsEntry("status", "RUNNING")
        .doesNotContainKey("stopReason")
        .doesNotContainKey("exitCode");
    assertThat(((Number) body.get("waitedMillis")).longValue())
        .as("真把边界预算等完了（不是立刻返回）")
        .isGreaterThanOrEqualTo(SubagentOrchestrationTools.MAX_WAIT_MILLIS - 1_000L);
    blocked.countDown();
    f.close();
  }

  /**
   * 上限必须留在 MCP 请求超时<b>内侧</b>（必改 2 的理由本身要有判据）：子体路径上这个工具经父侧 MCP 服务器调用，客户端单次请求超时默认 60 s （{@code
   * McpToolSource.DEFAULT_REQUEST_TIMEOUT} 与 {@link
   * io.mosire.agentlib.mcp.McpServerLinkConfig#requestTimeout()} 缺省同值）； 上限一旦 ≥ 它，"等待到时"就会先撞传输超时 ⇒
   * 设计承诺的"超时不是错误"在子体路径上失效。
   *
   * <p>判别性：把 {@code MAX_WAIT_MILLIS} 抬到 60 s 以上 ⇒ 转红。
   */
  @Test
  void maxWaitStaysInsideMcpRequestTimeout() {
    Duration requestTimeout =
        new io.mosire.agentlib.mcp.McpServerLinkConfig("guard", "cmd", null, null, null, null)
            .requestTimeout();
    assertThat(Duration.ofMillis(SubagentOrchestrationTools.MAX_WAIT_MILLIS))
        .as("单次等待上限必须在 MCP 请求超时内侧（否则'等待到时'变成传输错误）")
        .isLessThan(requestTimeout);
  }

  /**
   * §四.4 的第三态 + 能力位：{@code wait} 的判据是 A 块的血缘判定（能力位 + subtree）—— <b>兄弟分支等不了</b>（两边 path 都能解析出，只是不在同一
   * subtree），<b>父等得了自己的子</b>；能力位缺则 {@code DENIED}。
   *
   * <p>判别性：把判据换成"能进工具就能等"⇒ 兄弟分支那条断言转红；把能力位与"能调这个工具"混作一谈 ⇒ {@code DENIED} 断言转红。
   */
  @Test
  void waitJudgementIsLineageBased() throws Exception {
    Fixture f =
        fixture(
            systemParent(),
            new InProcessExecutor(
                (instanceId, config) -> writeChildTerminalRecord(instanceId, "FINISHED", 1, 0)));
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();
    AgentPermissionSet defaultPermissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().destructiveAllowed(true).build();

    ToolResult spawnedA =
        guard.execute(
            registry, "spawn_sub_agent", context(Map.of("templateId", "reader", "goal", "A")));
    String childA = (String) okBody(spawnedA).get("instanceId");
    ToolResult spawnedB =
        guard.execute(
            registry, "spawn_sub_agent", context(Map.of("templateId", "reader", "goal", "B")));
    String childB = (String) okBody(spawnedB).get("instanceId");
    // 孙代（A 自己派的）：父等自己的子 = 允许
    ToolResult spawnedG =
        guard.execute(
            registry,
            "spawn_sub_agent",
            new ToolContext(
                AccessToken.DEFAULT,
                defaultPermissions,
                Map.of(),
                Map.of("templateId", "reader", "goal", "G"),
                AgentIdentity.subagent(childA, CommandMode.FULL, "上级派的任务", 1)));
    String childG = (String) okBody(spawnedG).get("instanceId");
    assertThat(f.manager().get(childG).orElseThrow().lineagePath())
        .isEqualTo("main/" + childA + "/" + childG);

    // 兄弟分支（B 等 A 的子）：两边 path 都能解析（main/B 与 main/A/G），但不同血脉 ⇒ SUBTREE_DENIED
    ToolResult denied =
        guard.execute(
            registry,
            "wait_sub_agent",
            new ToolContext(
                AccessToken.DEFAULT,
                defaultPermissions,
                Map.of(),
                Map.of("instanceId", childG, "timeoutMs", 5000),
                AgentIdentity.subagent(childB, CommandMode.FULL, "别家的活", 1)));
    assertThat(denied.success()).isFalse();
    assertThat(denied.code()).isEqualTo(ContextAccessJudge.SUBTREE_DENIED);
    assertThat(denied.message())
        .as("模型面只说这一类被拒（细节进日志，f4060c1 口径）")
        .isEqualTo(ContextAccessJudge.OUT_OF_SCOPE_REASON);

    // 反向对照：父等自己的子 ⇒ 放行（否则本用例退化成"怎么都拒"）
    ToolResult allowed =
        guard.execute(
            registry,
            "wait_sub_agent",
            new ToolContext(
                AccessToken.DEFAULT,
                defaultPermissions,
                Map.of(),
                Map.of("instanceId", childG, "timeoutMs", 5000),
                AgentIdentity.subagent(childA, CommandMode.FULL, "上级派的任务", 1)));
    assertThat(okBody(allowed)).containsEntry("status", "FINISHED");

    // 能力位独立于"能不能进这个工具"：白名单里没有 wait_sub_agent ⇒ 判定第⑥条 DENIED。
    // 注意必须<b>直呼工具</b>（不经 guard）：guard 的入口白名单会在更前面就拒掉，那样验到的是 guard 那一层，
    // 判定里的能力位反而没被走到——"探针没走到判定点"正是要避免的假通过。
    AgentPermissionSet noWait =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("list_sub_agents").build();
    AgentTool waitTool = registry.find(SubagentOrchestrationTools.WAIT_SUB_AGENT).orElseThrow();
    ToolResult deniedByBit =
        waitTool.execute(
            new ToolContext(
                AccessToken.DEFAULT,
                noWait,
                Map.of(),
                Map.of("instanceId", childG, "timeoutMs", 5000),
                AgentIdentity.main(CommandMode.FULL)));
    assertThat(deniedByBit.success()).isFalse();
    assertThat(deniedByBit.code()).isEqualTo(ToolExecutionGuard.DENIED);
    assertThat(deniedByBit.message()).contains("能力位");
    f.close();
  }

  /** §2.5：{@code timeoutMs} 必填且非负——参数问题是 {@code INVALID_ARGUMENTS}，不落到判定/等待里。 */
  @Test
  void waitToolRequiresNonNegativeTimeout() {
    Fixture f = fixture(systemParent(), null);
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    ToolResult missing =
        guard.execute(registry, "wait_sub_agent", context(Map.of("instanceId", "reader-nope")));
    assertThat(missing.success()).isFalse();
    assertThat(missing.code()).isEqualTo("INVALID_ARGUMENTS");
    assertThat(missing.message()).contains("timeoutMs");

    ToolResult negative =
        guard.execute(
            registry,
            "wait_sub_agent",
            context(Map.of("instanceId", "reader-nope", "timeoutMs", -1)));
    assertThat(negative.success()).isFalse();
    assertThat(negative.code()).isEqualTo("INVALID_ARGUMENTS");

    ToolResult noId = guard.execute(registry, "wait_sub_agent", context(Map.of("timeoutMs", 10)));
    assertThat(noId.code()).isEqualTo("INVALID_ARGUMENTS");
    f.close();
  }

  /**
   * §2.6 的诚实口径：{@code kill} 等一小段确认后返回终态——在管子体 ⇒ {@code KILLED}；已经跑完的实例 ⇒ 照实报 {@code FINISHED} +
   * {@code stopReason}（<b>不谎报"已终止"</b>）。
   *
   * <p>判别性：把返回体换回固定的"已发出终止请求"文本（旧口径）⇒ 两条 {@code message} 断言转红。
   */
  @Test
  void killReportsTerminalStateHonestly() throws Exception {
    CountDownLatch blocked = new CountDownLatch(1);
    Fixture f =
        fixture(systemParent(), new InProcessExecutor((instanceId, config) -> blocked.await()));
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    ToolResult spawn =
        guard.execute(
            registry, "spawn_sub_agent", context(Map.of("templateId", "reader", "goal", "长任务")));
    String childId = (String) okBody(spawn).get("instanceId");
    Map<String, Object> killed =
        okBody(guard.execute(registry, "kill_sub_agent", context(Map.of("instanceId", childId))));
    assertThat(killed).containsEntry("status", "KILLED").doesNotContainKey("stopReason");
    assertThat(killed.get("message").toString()).contains("已终止").contains(childId);
    blocked.countDown();

    // 已经自己跑完的实例：kill 不改状态、不假装"刚被终止"——照实报 FINISHED + stopReason
    Fixture finished =
        fixture(
            systemParent(),
            new InProcessExecutor(
                (instanceId, config) -> writeChildTerminalRecord(instanceId, "TURN_LIMIT", 9, 5)));
    ToolRegistry finishedRegistry = registryOf(finished.manager());
    ToolResult spawn2 =
        guard.execute(
            finishedRegistry,
            "spawn_sub_agent",
            context(Map.of("templateId", "reader", "goal", "跑完")));
    String child2 = (String) okBody(spawn2).get("instanceId");
    awaitStatus(finished.manager(), child2, "FINISHED");

    Map<String, Object> lateKill =
        okBody(
            guard.execute(
                finishedRegistry, "kill_sub_agent", context(Map.of("instanceId", child2))));
    assertThat(lateKill)
        .containsEntry("status", "FINISHED")
        .containsEntry("stopReason", "TURN_LIMIT");
    assertThat(lateKill.get("message").toString()).contains("已是终态").contains(child2);

    f.close();
    finished.close();
  }

  /**
   * §2.6 的第三条口径（必改 1，2026-09-14）：<b>确认窗口结束、句柄仍活</b> ⇒ 状态停在 {@code TERMINATING}，kill 必须照实说"已发出终止请求，
   * 尚未确认"，<b>不许谎报"已终止"</b>——三条口径里只有它没被判据钉住，而它恰是"不谎报"红线的所在。
   *
   * <p>夹具 = {@link IgnoreCloseLauncher}：句柄 {@code close()} 只记次数、不置死（真实形态：三层关停发出后子进程还活着、宽限内不死），于是
   * {@code awaitDead(KILL_CONFIRM_MILLIS)} 走满 10 s 也等不到死亡 ⇒ 终止观测<b>不</b>收束成
   * KILLED。同一条路径也是"父侧不谎报"的结构保证： 状态机宁可停在 TERMINATING，也不把"已请求"说成"已完成"。
   *
   * <p>判别性：把 {@code case TERMINATING} 的文案换成 {@code case KILLED} 那句（"已终止…"）⇒ 文案断言转红；把"窗口到点"直接记
   * KILLED ⇒ status/stopReason 断言转红。
   */
  @Test
  void killReportsStillTerminatingWhenConfirmWindowEndsAlive() throws Exception {
    IgnoreCloseLauncher launcher = new IgnoreCloseLauncher();
    Fixture f = fixture(systemParent(), launcher);
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    ToolResult spawn =
        guard.execute(
            registry,
            "spawn_sub_agent",
            context(Map.of("templateId", "reader", "goal", "关不掉的长任务")));
    String childId = (String) okBody(spawn).get("instanceId");

    long started = System.nanoTime();
    ToolResult killed =
        guard.execute(registry, "kill_sub_agent", context(Map.of("instanceId", childId)));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    Map<String, Object> body = okBody(killed);
    assertThat(body)
        .as("窗口到点仍活 ⇒ 停在 TERMINATING（不许推进成 KILLED）")
        .containsEntry("status", "TERMINATING")
        .doesNotContainKey("stopReason");
    assertThat(body.get("message").toString())
        .contains("尚未确认")
        .contains("TERMINATING")
        .contains(childId)
        .as("红线：不谎报'已终止'")
        .doesNotContain("已终止");
    assertThat(elapsedMillis)
        .as("真等完了确认窗口（不是立刻返回当前状态）")
        .isGreaterThanOrEqualTo(SubagentManager.KILL_CONFIRM_MILLIS - 1_000L);
    assertThat(launcher.closeCalls()).as("三层关停入口被调到（句柄 close 恰好一次）").isEqualTo(1);
    assertThat(f.manager().get(childId).orElseThrow().status().name())
        .as("Manager 侧状态与工具面回显一致")
        .isEqualTo("TERMINATING");
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
   * guard 层身份闸（S5-A 起<b>分档</b>）+ 判定的分工（D27）：GUEST 四个工具全拒（身份级别不足）；DEFAULT（= 子 Agent 的默认级别）
   * <b>进得去</b>四个工具，但"能不能动到东西"由血缘判定说了算——身份解析不出（缺省 {@code UNKNOWN}）一律 fail-closed， 而同一份级别、只把身份换成 main
   * 就放行：<b>级别不是判据，身份/血缘才是</b>。
   *
   * <p>判别性：把 kill/list 的 spec 改回 SYSTEM，"进得去工具"那步会被 guard 拦下（码是 {@code DENIED} 而不是 {@code
   * SUBTREE_DENIED}）⇒ 转红；把判定换回 {@code caller == SYSTEM} 自查，第三段（DEFAULT 权限 + main 身份）会被拒 ⇒ 转红。
   */
  @Test
  void guardOnlyAdmitsDefaultLevelAndTheJudgmentDecides() throws Exception {
    CountDownLatch blocked = new CountDownLatch(1);
    Fixture f =
        fixture(systemParent(), new InProcessExecutor((instanceId, config) -> blocked.await()));
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    for (String name :
        List.of(
            "spawn_sub_agent",
            "kill_sub_agent",
            "list_sub_agents",
            "read_agent_context",
            "wait_sub_agent")) {
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
    AgentPermissionSet defaultPermissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().destructiveAllowed(true).build();
    // 派发绑 main 身份（子体才有可解析的血缘 path），级别仍是 DEFAULT——"谁能进工具"与"这个能不能动"是两回事
    ToolResult spawned =
        guard.execute(
            registry,
            "spawn_sub_agent",
            new ToolContext(
                AccessToken.DEFAULT,
                defaultPermissions,
                Map.of(),
                Map.of("templateId", "reader", "goal", "派孙代"),
                AgentIdentity.main(CommandMode.FULL)));
    assertThat(spawned.success()).as(spawned.message()).isTrue();
    assertThat(f.manager().list()).hasSize(1);
    String childId = f.manager().list().get(0).instanceId();
    assertThat(f.manager().get(childId).orElseThrow().lineagePath())
        .as("主 Agent 派的子体：path 有 main 段")
        .isEqualTo("main/" + childId);

    // 同一份 DEFAULT 权限 + 身份解析不出：工具进得去，判定拒（fail-closed）
    for (String name :
        List.of("kill_sub_agent", "list_sub_agents", "read_agent_context", "wait_sub_agent")) {
      Map<String, Object> args =
          name.equals("kill_sub_agent")
              ? Map.of("instanceId", childId)
              : name.equals("wait_sub_agent")
                  ? Map.of("instanceId", childId, "timeoutMs", 0)
                  : name.equals("read_agent_context") ? Map.of("target", childId) : Map.of();
      ToolResult denied =
          guard.execute(
              registry,
              name,
              new ToolContext(AccessToken.DEFAULT, defaultPermissions, Map.of(), args));
      assertThat(denied.success()).as(name).isFalse();
      assertThat(denied.code()).as(name).isEqualTo(ContextAccessJudge.SUBTREE_DENIED);
      assertThat(denied.message()).as(name).isEqualTo(ContextAccessJudge.OUT_OF_SCOPE_REASON);
    }

    // 同一份级别、只换身份（main）⇒ 判定放行：DEFAULT 级不再是"能不能动"的判据
    ToolResult killed =
        guard.execute(
            registry,
            "kill_sub_agent",
            new ToolContext(
                AccessToken.DEFAULT,
                defaultPermissions,
                Map.of(),
                Map.of("instanceId", childId),
                AgentIdentity.main(CommandMode.FULL)));
    assertThat(killed.success()).as(killed.message()).isTrue();
    ToolResult listed =
        guard.execute(
            registry,
            "list_sub_agents",
            new ToolContext(
                AccessToken.DEFAULT,
                defaultPermissions,
                Map.of(),
                Map.of(),
                AgentIdentity.main(CommandMode.FULL)));
    assertThat(listed.success()).as(listed.message()).isTrue();
    assertThat(listed.message()).contains(childId);
    blocked.countDown();
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

  // ---- D27：A1（派发时点名工具白名单） ----

  /**
   * {@code allowedTools} 参数面（D27 的 {@code A1}）：透传到派发（生效值 = <b>模板 ∩ A1</b>），点名调用者没有的工具 ⇒ {@code
   * PERMISSION_DENIED}（消息点名那个工具）且<b>不留实例</b>。
   *
   * <p>判别性：参数面漏接（schema 有、请求构造没接）⇒ 子体仍拿模板全量 ⇒ 收窄断言转红；把越界做成"静默求交"⇒ 本该失败的派发 成功落地、manager 里多一个实例 ⇒
   * 后两条断言转红。
   */
  @Test
  void allowedToolsArgumentNarrowsTheChildAndRejectsBeyondCaller() throws Exception {
    writeTemplate("pair", Set.of("echo", SubagentOrchestrationTools.READ_AGENT_CONTEXT), "双工具模板");
    Fixture f = fixture(systemParent(), new InProcessExecutor((instanceId, config) -> {}));
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    // 调用者自己有 echo + read_agent_context + spawn（I1 的两个基准都在），但**没有** admin-do
    AgentPermissionSet callerPermissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow("echo", SubagentOrchestrationTools.READ_AGENT_CONTEXT, "spawn_sub_agent")
            .destructiveAllowed(true)
            .build();
    AgentIdentity caller = AgentIdentity.subagent("a1-instance", CommandMode.FULL, "派活", 1);

    ToolResult narrowed =
        guard.execute(
            registry,
            "spawn_sub_agent",
            new ToolContext(
                AccessToken.DEFAULT,
                callerPermissions,
                Map.of(),
                Map.of("templateId", "pair", "goal", "收窄一个工具", "allowedTools", List.of("echo")),
                caller));
    assertThat(narrowed.success()).as(narrowed.message()).isTrue();
    String childId = (String) JSON.<Map>readValue(narrowed.message(), Map.class).get("instanceId");
    assertThat(f.manager().get(childId).orElseThrow().permissions().isToolAllowed("echo")).isTrue();
    assertThat(
            f.manager()
                .get(childId)
                .orElseThrow()
                .permissions()
                .isToolAllowed(SubagentOrchestrationTools.READ_AGENT_CONTEXT))
        .as("模板里有、请求没点名 ⇒ 被 A1 收窄掉")
        .isFalse();

    ToolResult rejected =
        guard.execute(
            registry,
            "spawn_sub_agent",
            new ToolContext(
                AccessToken.DEFAULT,
                callerPermissions,
                Map.of(),
                Map.of(
                    "templateId",
                    "pair",
                    "goal",
                    "点名越界工具",
                    "allowedTools",
                    List.of("echo", "admin-do")),
                caller));
    assertThat(rejected.success()).isFalse();
    assertThat(rejected.code()).isEqualTo(ToolExecutionGuard.DENIED);
    assertThat(rejected.message()).contains("admin-do").contains("I1");
    assertThat(f.manager().list()).as("被拒那次不留实例").hasSize(1);
    f.close();
  }

  // ---- fixtures ----

  /**
   * "句柄 {@code close()} 不死" launcher（必改 1 的夹具，2026-09-14）：关闭只记次数、存活恒为真——用来把 kill 的 <b>TERMINATING
   * 确认窗口</b>钉在确定性构造上（真实形态：三层关停发完、子进程仍未死）。
   *
   * <p>反面：任何"窗口到点就报 KILLED"的实现都会在这个夹具下暴露（状态推进没有事实依据）。{@code closeCalls} 同时证明三层关停入口真被调到，
   * 而不是"压根没试过关"。
   */
  private static final class IgnoreCloseLauncher implements SubagentLauncher {

    private final java.util.concurrent.atomic.AtomicInteger closeCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    int closeCalls() {
      return closeCalls.get();
    }

    @Override
    public LaunchedSubagent launch(SubagentInstance instance) {
      return new LaunchedSubagent() {
        @Override
        public boolean isAlive() {
          return true; // 永远活着：确认窗口内不会有"已退出"事实
        }

        @Override
        public void close() {
          closeCalls.incrementAndGet();
        }
      };
    }

    @Override
    public void close() {}
  }

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

  /** 执行体缝是 {@link SubagentLauncher}（InProcessExecutor 只是其一）："句柄 close() 不死"这类夹具经它注入。 */
  private Fixture fixture(AgentPermissionSet parentPermissions, SubagentLauncher launcher) {
    return fixture(parentPermissions, launcher, SubagentLimits.defaults());
  }

  private Fixture fixture(
      AgentPermissionSet parentPermissions, SubagentLauncher launcher, SubagentLimits limits) {
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
            launcher == null ? new InProcessExecutor() : launcher,
            events,
            bus,
            AgentConfig.builder("main").build(),
            parentPermissions,
            0,
            () -> CommandMode.FULL,
            limits,
            childDataRoot());
    return new Fixture(events, bus, manager);
  }

  /** 子库根（§2.3）：生产装配是 {@code <dataDir>/subagents}，测试里跟父库同挂 {@code tempDir/data} 下。 */
  private Path childDataRoot() {
    return tempDir.resolve("data").resolve("subagents");
  }

  /** 模拟子体的协议义务（§2.1）：终局记录写进<b>自己的</b>库 {@code <root>/<id>/events.db}。 */
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

  /** 工具返回体（JSON）→ Map；顺带断言"是成功结果"。 */
  private static Map<String, Object> okBody(ToolResult result) throws Exception {
    assertThat(result.success()).as(result.message()).isTrue();
    return JSON.readValue(result.message(), Map.class);
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

  /**
   * 缺省调用上下文 = <b>主 Agent 在问</b>（SYSTEM 权限 + {@code main} 身份）。D27 起身份是判定的输入之一：不绑身份会被血缘判定 fail-closed
   * 拒掉（这正是 {@link #guardOnlyAdmitsDefaultLevelAndTheJudgmentDecides} 的反向面）。
   */
  private static ToolContext context(Map<String, Object> args) {
    return new ToolContext(
        AccessToken.SYSTEM,
        AgentPermissionSet.system(),
        Map.of(),
        args,
        AgentIdentity.main(CommandMode.FULL));
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
