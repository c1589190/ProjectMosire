package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
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

  @Test
  void toolsUseSystemLevelSensitiveFalseDestructiveTrue() {
    Fixture f = fixture(systemParent(), null);
    List<AgentTool> tools = SubagentOrchestrationTools.of(f.manager());
    // D30 起是四个（多 read_agent_context）；注册序 = 名字字典序
    assertThat(tools)
        .extracting(AgentTool::name)
        .containsExactly(
            "kill_sub_agent", "list_sub_agents", "read_agent_context", "spawn_sub_agent");
    for (AgentTool tool : tools) {
      if (tool.name().equals(SubagentOrchestrationTools.READ_AGENT_CONTEXT)) {
        continue; // 读工具的 spec 另有一维（noExport）+ sensitive=true，判别在 ReadAgentContextToolTest
      }
      assertThat(tool.spec()).isEqualTo(ToolSpec.level(AccessToken.SYSTEM, false, true));
      assertThat(tool.spec().noExport()).isFalse(); // 既有三工具逐字不变：仍可外发（子体拿得到 spawn/kill/list）
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

  @Test
  void escalatedSpawnRejectedWithPermissionDeniedEventAndNoInstance() {
    // 父级（manager 注入的父权限）只有白名单 echo；子模板想带 admin-do → 红线 1 单调性拒绝。
    // 调用方是主 Agent（SYSTEM，system() 权限集）——guard 放行，拒绝发生在 manager 单调性检查处
    writeTemplate("grand", Set.of("echo", "admin-do"), "想提权的子 Agent");
    AgentPermissionSet parentPermissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("echo").build();
    Fixture f = fixture(parentPermissions, null);
    ToolExecutionGuard guard = new ToolExecutionGuard();
    ToolRegistry registry = registryOf(f.manager());

    ToolResult result =
        guard.execute(
            registry,
            "spawn_sub_agent",
            new ToolContext(
                AccessToken.SYSTEM,
                AgentPermissionSet.system(),
                Map.of(),
                Map.of("templateId", "grand", "goal", "提权尝试")));
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("PERMISSION_DENIED");
    assertThat(result.message()).contains("父级许可");

    // 拒绝类事件 agent=父级 id、correlationId=拟生成的子 id，且没有实例落地
    List<Event> denied =
        f.events().query(new EventQuery("main", EventTypes.PERMISSION_DENIED, "", -1, 100));
    assertThat(denied).hasSize(1);
    assertThat(denied.get(0).correlationId()).startsWith("grand-");
    assertThat(f.manager().list()).isEmpty();
    f.close();
  }

  @Test
  void guardDeniesSystemToolForGuestAndDefaultCallers() {
    Fixture f = fixture(systemParent(), null);
    ToolRegistry registry = registryOf(f.manager());
    ToolExecutionGuard guard = new ToolExecutionGuard();

    for (AccessToken token : List.of(AccessToken.GUEST, AccessToken.DEFAULT)) {
      ToolResult denied =
          guard.execute(
              registry,
              "spawn_sub_agent",
              new ToolContext(
                  token,
                  AgentPermissionSet.builder(token).allowAll().build(),
                  Map.of(),
                  Map.of("templateId", "reader", "goal", "提权尝试")));
      assertThat(denied.success()).isFalse();
      assertThat(denied.code()).isEqualTo(ToolExecutionGuard.DENIED);
      assertThat(denied.message()).contains("身份级别不足");
    }
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
            0);
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
