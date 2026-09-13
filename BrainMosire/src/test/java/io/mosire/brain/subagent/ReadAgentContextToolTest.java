package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventWrite;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D30 {@code read_agent_context} 工具面契约：<b>权限位为假 = 可辨的拒绝（不是空结果）</b>、{@code target=self}、 未知实例、越界
 * id、配置缺失/库缺失一律响亮报错、描述与 spec 的诚实口径。
 *
 * <p>全离线：{@link InProcessExecutor} 假执行体，子库由测试直接写文件；不起真进程、不花 token。
 */
class ReadAgentContextToolTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String MAIN = "main";

  @TempDir Path tempDir;

  // ---- 用例 ----

  @Test
  void permissionBitFalseDeniesDistinguishablyInsteadOfEmptyResult() throws Exception {
    Fixture f = fixture();
    SubagentInstance child = spawn(f);
    writeChildDb(f, child.instanceId());

    // 能力位为假：白名单只给 list_sub_agents（读是独立能力位，不随"能调工具"自动到手）
    AgentPermissionSet withoutRead =
        AgentPermissionSet.builder(AccessToken.SYSTEM).allow("list_sub_agents").build();
    ToolResult denied = f.callDirect(withoutRead, f.childConfig(), args(child.instanceId()));

    assertThat(denied.success()).isFalse();
    assertThat(denied.code()).isEqualTo(ToolExecutionGuard.DENIED);
    assertThat(denied.message()).contains("未授予").contains("read_agent_context");
    // "可辨的拒绝"≠"空结果"：不是一份能冒充成功的空视图体
    assertThat(denied.message()).doesNotContain("\"items\"").doesNotContain("\"summary\"");

    // 判别性：同一条码路、只把能力位补上 ⇒ 成功（拒绝确实来自该位，不是环境/参数不合格）
    ToolResult allowed =
        f.callDirect(AgentPermissionSet.system(), f.childConfig(), args(child.instanceId()));
    assertThat(allowed.success()).isTrue();
    assertThat(allowed.message()).contains("\"view\"");

    // 常规管线路径（guard）同结论：白名单挡在读之前，仍然是可辨拒绝
    ToolResult viaGuard = f.callViaGuard(withoutRead, f.childConfig(), args(child.instanceId()));
    assertThat(viaGuard.success()).isFalse();
    assertThat(viaGuard.code()).isEqualTo(ToolExecutionGuard.DENIED);
    f.close();
  }

  @Test
  void nonSystemCallerDeniedWithSecondGate() throws Exception {
    Fixture f = fixture();
    SubagentInstance child = spawn(f);

    // MCP 路径不走管线 guard（本用例不建库：闸在读之前就该拦住）：子体身份（DEFAULT）拿 allowAll 也读不了别家上下文（红线 R7 的第二道闸）
    ToolResult denied =
        f.callDirect(
            AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
            f.childConfig(),
            args(child.instanceId()));
    assertThat(denied.success()).isFalse();
    assertThat(denied.code()).isEqualTo(ToolExecutionGuard.DENIED);
    assertThat(denied.message()).contains("身份级别不足");
    f.close();
  }

  @Test
  void selfTargetReadsOwnEventsDb() throws Exception {
    Fixture f = fixture();
    // 主 Agent 自己的事件：agent 列 = main
    f.selfStore()
        .append(
            EventWrite.of(
                EventTypes.CONVERSATION_TURN,
                MAIN,
                "{\"input\":\"问题\",\"output\":\"我自己的答案\"}",
                MAIN));
    // 混入一条"别人的"事件：只读 target 自己的 agent 列，不越界读
    f.selfStore()
        .append(
            EventWrite.of(EventTypes.CONVERSATION_TURN, "别的agent", "{\"output\":\"不该被看到\"}", MAIN));

    ToolResult self =
        f.callDirect(
            AgentPermissionSet.system(),
            f.selfConfig(),
            Map.of("target", "self", "view", "turns", "limit", 1));

    assertThat(self.success()).isTrue();
    Map<String, Object> body = JSON.readValue(self.message(), Map.class);
    assertThat(body.get("agent")).isEqualTo(MAIN);
    assertThat(String.valueOf(self.message())).contains("我自己的答案").doesNotContain("不该被看到");
    // 分页游标如实回吐（父可据此续页）
    assertThat(body).containsKeys("hasMore", "nextSinceSeq", "itemCount", "limit");
    f.close();
  }

  @Test
  void unknownInstanceAndPathLikeTargetsDeniedNeverReadAnotherDb() throws Exception {
    Fixture f = fixture();

    for (String target : List.of("ghost-0000", "../main", "../../etc/passwd", "/etc/passwd")) {
      ToolResult denied = f.callDirect(AgentPermissionSet.system(), f.childConfig(), args(target));
      assertThat(denied.success()).as("target=%s 不应成功", target).isFalse();
      assertThat(denied.code()).as("target=%s", target).isEqualTo("UNKNOWN_INSTANCE");
      assertThat(denied.message()).contains("D27");
    }

    // 纵深防御：即便判定被绕过，路径拼接本身也挡越界 id（本用例直接对拼接函数判别）
    Path root = f.root();
    assertThat(SubagentOrchestrationTools.childDbPath(root, "reader-abc123"))
        .isEqualTo(root.resolve("reader-abc123").resolve("events.db"));
    for (String evil : List.of("..", "../main", "a/../../b", "reader-abc/../../..")) {
      assertThatThrownBy(() -> SubagentOrchestrationTools.childDbPath(root, evil))
          .as("id=%s 应被挡", evil)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("越出子库根目录");
    }
    f.close();
  }

  @Test
  void missingDbFailsLoudlyAndCreatesNothing() throws Exception {
    Fixture f = fixture();
    SubagentInstance child = spawn(f); // 已知实例，但磁盘上没有它的库

    ToolResult result =
        f.callDirect(AgentPermissionSet.system(), f.childConfig(), args(child.instanceId()));
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("CONTEXT_UNAVAILABLE");
    assertThat(result.message()).contains("事件库不存在");
    // 响亮 = 不把"读不到"悄悄变成"这个子体没干活（空结果）"，也不替它建目录
    assertThat(Files.exists(f.root().resolve(child.instanceId()))).isFalse();
    f.close();
  }

  @Test
  void missingInjectedConfigFailsLoudlyInsteadOfGuessingPaths() throws Exception {
    Fixture f = fixture();
    SubagentInstance child = spawn(f);

    ToolResult noRoot =
        f.callDirect(AgentPermissionSet.system(), Map.of(), args(child.instanceId()));
    assertThat(noRoot.success()).isFalse();
    assertThat(noRoot.code()).isEqualTo("CONTEXT_UNAVAILABLE");
    assertThat(noRoot.message()).contains(AgentContextReader.CONFIG_SUBAGENTS_ROOT).contains("缺失");

    ToolResult noSelf =
        f.callDirect(AgentPermissionSet.system(), Map.of(), Map.of("target", "self"));
    assertThat(noSelf.success()).isFalse();
    assertThat(noSelf.message()).contains(AgentContextReader.CONFIG_SELF_EVENTS_DB);
    f.close();
  }

  @Test
  void schemaSpecAndDescriptionAreHonestAboutD27Gap() {
    Fixture f = fixture();
    List<AgentTool> tools = SubagentOrchestrationTools.of(f.manager());
    assertThat(tools)
        .extracting(AgentTool::name)
        .containsExactly(
            "kill_sub_agent", "list_sub_agents", "read_agent_context", "spawn_sub_agent");

    AgentTool read =
        tools.stream()
            .filter(t -> t.name().equals(SubagentOrchestrationTools.READ_AGENT_CONTEXT))
            .findFirst()
            .orElseThrow();
    // 描述诚实：明写"暂不支持"的边界，不把它说成"已按权限模型保护"
    assertThat(read.description())
        .contains("D27")
        .contains("同进程已知子体")
        .contains("只读")
        .doesNotContain("已按权限模型保护");
    // 三要素 + 禁外发位
    assertThat(read.spec()).isEqualTo(ToolSpec.level(AccessToken.SYSTEM, true, false, true));
    assertThat(read.spec().noExport()).isTrue();
    assertThat(read.spec().destructive()).isFalse();
    assertThat(read.spec().sensitive()).isTrue();
    // 其余工具：kill/list 仍是 SYSTEM（noExport 缺省 false ⇒ 仍可外发）；spawn 从 S5-A 起降为 DEFAULT
    // （对子 Agent 开放派发——判别面在 SubagentOrchestrationToolsTest，这里只锁定"本工具的邻居没被顺手改坏"）
    for (AgentTool tool : tools) {
      if (tool.name().equals(SubagentOrchestrationTools.READ_AGENT_CONTEXT)) {
        continue;
      }
      AccessToken expected =
          tool.name().equals(SubagentOrchestrationTools.SPAWN_SUB_AGENT)
              ? AccessToken.DEFAULT
              : AccessToken.SYSTEM;
      assertThat(tool.spec()).as(tool.name()).isEqualTo(ToolSpec.level(expected, false, true));
      assertThat(tool.spec().noExport()).as(tool.name()).isFalse();
    }
    // schema：view 的 enum 与读取器 VIEWS 同源（不含口径外的视图名）
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) read.jsonSchema().get("properties");
    @SuppressWarnings("unchecked")
    Map<String, Object> view = (Map<String, Object>) properties.get("view");
    assertThat(view.get("enum")).isEqualTo(AgentContextReader.VIEWS);
    assertThat(String.valueOf(read.jsonSchema().get("required"))).contains("target");

    // 越界 view 名 → INVALID_ARGUMENTS（不是静默按 summary 处理）
    ToolResult badView =
        f.callDirect(
            AgentPermissionSet.system(), f.childConfig(), Map.of("target", "self", "view", "全都要"));
    assertThat(badView.success()).isFalse();
    assertThat(badView.code()).isEqualTo("INVALID_ARGUMENTS");
    assertThat(badView.message()).contains("未知视图");
    f.close();
  }

  // ---- fixtures ----

  private static Map<String, Object> args(String target) {
    return Map.of("target", target, "view", "results");
  }

  private final class Fixture implements AutoCloseable {
    private final SqliteEventStore store;
    private final EventBus bus;
    private final SubagentManager manager;
    private final ToolRegistry registry;

    Fixture(SqliteEventStore store, EventBus bus, SubagentManager manager) {
      this.store = store;
      this.bus = bus;
      this.manager = manager;
      this.registry = new ToolRegistry();
      this.registry.registerAll(SubagentOrchestrationTools.of(manager));
    }

    SqliteEventStore selfStore() {
      return store;
    }

    SubagentManager manager() {
      return manager;
    }

    Path root() {
      return tempDir.resolve("subagents");
    }

    Map<String, Object> childConfig() {
      return Map.of(
          AgentContextReader.CONFIG_SUBAGENTS_ROOT, root().toString(),
          AgentContextReader.CONFIG_SELF_EVENTS_DB, selfDb().toString(),
          AgentContextReader.CONFIG_SELF_AGENT_ID, MAIN);
    }

    Map<String, Object> selfConfig() {
      return childConfig();
    }

    /**
     * 直接调工具（绕过管线 guard——模拟 MCP 路径：判定必须自己在工具里做）。
     *
     * <p>身份取自权限集的 token（与管线一致：调用者是谁 = 权限集发给谁）——不写死 SYSTEM，否则"子体身份"用例 会假绿。
     */
    ToolResult callDirect(
        AgentPermissionSet permissions, Map<String, Object> config, Map<String, Object> args) {
      AgentTool tool = registry.find(SubagentOrchestrationTools.READ_AGENT_CONTEXT).orElseThrow();
      return tool.execute(new ToolContext(permissions.grantedToken(), permissions, config, args));
    }

    /** 常规管线路径（guard 在前）。 */
    ToolResult callViaGuard(
        AgentPermissionSet permissions, Map<String, Object> config, Map<String, Object> args) {
      return new ToolExecutionGuard()
          .execute(
              registry,
              SubagentOrchestrationTools.READ_AGENT_CONTEXT,
              new ToolContext(permissions.grantedToken(), permissions, config, args));
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

  private Path selfDb() {
    return tempDir.resolve("data").resolve("events.db");
  }

  private Fixture fixture() {
    try {
      Path templateDir = tempDir.resolve("templates");
      Files.createDirectories(templateDir);
      Files.writeString(
          templateDir.resolve("reader.json"),
          """
          {
            "id": "reader",
            "description": "只读子 Agent",
            "systemPrompt": "只读问候",
            "model": "fake",
            "token": "DEFAULT",
            "allowedTools": ["echo"],
            "deniedTools": [],
            "destructiveAllowed": false,
            "sensitiveAllowed": false,
            "readOnly": false,
            "maxTurns": 5,
            "maxToolCallsPerTurn": 5,
            "timeBudgetSeconds": 60,
            "quotaMaxTokens": 0
          }
          """);
      AgentTemplateStore templates = new AgentTemplateStore(templateDir);
      templates.load();
      Files.createDirectories(selfDb().getParent());
      SqliteEventStore store = SqliteEventStore.open(selfDb());
      EventBus bus = new EventBus();
      SubagentManager manager =
          new SubagentManager(
              templates,
              new InProcessExecutor(), // 假执行体：立即完成，不起真进程
              store,
              bus,
              AgentConfig.builder(MAIN).build(),
              AgentPermissionSet.system(),
              0);
      return new Fixture(store, bus, manager);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  /** 造一个"已知子体"（执行体瞬时完成，不起进程）；<b>不</b>建它的库——需要库的用例显式调 {@link #writeChildDb}。 */
  private SubagentInstance spawn(Fixture f) throws Exception {
    return f.manager()
        .spawn(new SubagentLaunchRequest("reader", "把自己的上下文交出来", Set.of(), null, null, null));
  }

  /** 在磁盘上建出该子体的库并写一条回合（模拟"子体真干过活"）。 */
  private void writeChildDb(Fixture f, String instanceId) throws Exception {
    Path dir = f.root().resolve(instanceId);
    Files.createDirectories(dir);
    try (SqliteEventStore child = SqliteEventStore.open(dir.resolve("events.db"))) {
      child.append(
          EventWrite.of(
              EventTypes.CONVERSATION_TURN,
              instanceId,
              "{\"input\":\"干活\",\"output\":\"干完了\"}",
              instanceId));
    }
  }
}
