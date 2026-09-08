package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.runtime.EventTypes;
import io.mosire.brain.subagent.SubagentInstance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W3b 真实子进程 E2E（本任务唯一的真实子进程 E2E——Task 6 验收口径）。
 *
 * <p>场景（离线、仅回环管道）：主 Agent（FakeLlm 编排脚本）经 {@code spawn_sub_agent} 派生两个<b>真实</b>子 Agent 子进程 （{@code
 * Main agent --id ... --parent-link}，父侧接管其 stdin/stdout 为 MCP stdio 链接）：
 *
 * <ol>
 *   <li>reader（只读模板 {@code [echo]}）：子体经 MCP 回环调用父级 echo 工具（tool.call/tool.result 落在子体事件库）；
 *   <li>grand（模板 {@code [echo, spawn_sub_agent]}）：子体尝试提权派生——父侧编排工具的内联身份校验拒绝 （{@code
 *       permission.denied} 事件落在子体事件库、父侧零实例落地）；
 *   <li>两个子体跑完脚本后阻塞等待父侧关停信号内（{@code whenClosed}），父级 kill → TERMINATING→KILLED 事件链。
 * </ol>
 *
 * <p>断言面：父级事件库 = 主 Agent 的 spawn 工具调用/结果 + 两个子 Agent 的完整生命周期链（configured→…→killed）、 子级事件库（{@code
 * <dataDir>/subagents/<instanceId>/events.db}）= 各自的 tool.call/tool.result（grand 另含
 * permission.denied）。
 */
class W3SubagentE2ETest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void realChildSubprocessCallsParentToolsViaMcpAndDiesOnKill() throws Exception {
    Path templates = tempDir.resolve("templates");
    Files.createDirectories(templates);
    writeTemplate(
        templates,
        "reader",
        List.of("echo"),
        List.of(step("echo", Map.of("text", "你好，子 Agent 报到")), step(null, "问候完成")));
    writeTemplate(
        templates,
        "grand",
        List.of("echo", "spawn_sub_agent"),
        List.of(
            step("spawn_sub_agent", Map.of("templateId", "reader", "goal", "提权派生")),
            step("echo", Map.of("text", "提权被拒后仍可服务")),
            step(null, "完成")));

    FakeLlmClient mainLlm =
        FakeLlmClient.with(
            LlmResponse.toolCall(
                "m-1", "spawn_sub_agent", Map.of("templateId", "reader", "goal", "问候主流程")),
            LlmResponse.text("已派遣问候子 Agent"),
            LlmResponse.toolCall(
                "m-2", "spawn_sub_agent", Map.of("templateId", "grand", "goal", "提权探测")),
            LlmResponse.text("已了解"));
    Path dataDir = tempDir.resolve("data");
    BootConfig config =
        new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0, templates);
    App app = App.start(config, mainLlm, List.of(echoTool()));
    try (app) {
      // W3b 接线验收：主 Agent 工具面含三个内置编排工具（Brain 注册三要素 SYSTEM/非敏感/破坏性）
      assertThat(app.runtime().registry().find("spawn_sub_agent")).isPresent();
      assertThat(app.runtime().registry().find("kill_sub_agent")).isPresent();
      assertThat(app.runtime().registry().find("list_sub_agents")).isPresent();

      // 回合 1：主 Agent（脚本）spawn reader —— 真实子进程
      app.runtime().chat("请派一个问候子 Agent");
      SubagentInstance reader = awaitChild(app, "reader");
      Path readerEvents = childEvents(dataDir, reader.instanceId());
      // 子体经 MCP 回环调用父级 echo：tool.call + tool.result（成功）落在子体事件库
      awaitEvent(readerEvents, EventTypes.TOOL_RESULT, "tool", "echo", "ok", true);
      assertThat(eventsOf(readerEvents, EventTypes.TOOL_CALL))
          .extracting(e -> payload(e, "tool"))
          .contains("echo");
      assertThat(eventsOf(readerEvents, EventTypes.PERMISSION_DENIED)).isEmpty();

      // 回合 2：主 Agent（脚本）spawn grand —— 真实子进程（带提权意图的模板）
      app.runtime().chat("请派一个提权探测子 Agent");
      SubagentInstance grand = awaitChild(app, "grand");
      Path grandEvents = childEvents(dataDir, grand.instanceId());
      // grand 子体提权派生 → 父侧编排工具内联 SYSTEM 校验拒绝 → permission.denied 落在子体事件库
      awaitEvent(
          grandEvents, EventTypes.PERMISSION_DENIED, "tool", "spawn_sub_agent", "reason", null);
      // 拒绝后子体继续（echo 仍可服务）：tool.result 成功
      awaitEvent(grandEvents, EventTypes.TOOL_RESULT, "tool", "echo", "ok", true);

      // 父侧零实例落地：拒绝没有到达 manager（systemOnly 内联拒绝），管理器只记录两个合法实例
      assertThat(app.subagents()).hasSize(2);

      // kill（经主 Agent 的 guard 出口驱动——与主 Agent 管线执行工具相同的路径）→ 三层关停 → 生命周期链
      assertThat(kill(app, reader.instanceId()).success()).isTrue();
      assertThat(kill(app, grand.instanceId()).success()).isTrue();
      awaitLifecycleActions(
          dataDir,
          reader.instanceId(),
          List.of("configured", "spawning", "running", "terminating", "killed"));
      awaitLifecycleActions(
          dataDir,
          grand.instanceId(),
          List.of("configured", "spawning", "running", "terminating", "killed"));

      // 主 Agent 父级事件库：spawn 工具调用/结果（脚本驱动、成功）+ 拒绝类事件为零（提权拒绝发生在子体侧）
      try (SqliteEventStore mainStore = SqliteEventStore.open(dataDir.resolve("events.db"))) {
        List<Event> spawnCalls =
            mainStore.query(new EventQuery("main", EventTypes.TOOL_CALL, "", -1, 100));
        assertThat(spawnCalls)
            .extracting(e -> payload(e, "tool"))
            .contains("spawn_sub_agent", "spawn_sub_agent");
        List<Event> spawnResults =
            mainStore.query(new EventQuery("main", EventTypes.TOOL_RESULT, "", -1, 100));
        assertThat(spawnResults)
            .filteredOn(e -> "spawn_sub_agent".equals(payload(e, "tool")))
            .extracting(e -> payload(e, "ok"))
            .allMatch(Boolean.TRUE::equals);
        assertThat(
                mainStore.query(new EventQuery("main", EventTypes.PERMISSION_DENIED, "", -1, 100)))
            .isEmpty();
      }
    }
  }

  private AgentTool echoTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "echo";
      }

      @Override
      public String description() {
        return "原样返回输入文本";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")));
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("echo:" + context.arguments().getOrDefault("text", ""));
      }
    };
  }

  private ToolResult kill(App app, String instanceId) {
    return new ToolExecutionGuard()
        .execute(
            app.runtime().registry(),
            "kill_sub_agent",
            new ToolContext(
                AccessToken.SYSTEM,
                AgentPermissionSet.system(),
                Map.of(),
                Map.of("instanceId", instanceId)));
  }

  // ---- helpers ----

  private void writeTemplate(
      Path dir, String id, List<String> allowedTools, List<Map<String, Object>> script)
      throws Exception {
    Map<String, Object> template =
        Map.ofEntries(
            Map.entry("id", id),
            Map.entry("description", "W3 E2E 模板 " + id),
            Map.entry("systemPrompt", "你是测试子 Agent（模板 " + id + "）。"),
            Map.entry("model", "fake"),
            Map.entry("token", "DEFAULT"),
            Map.entry("allowedTools", allowedTools),
            Map.entry("deniedTools", List.of()),
            Map.entry("destructiveAllowed", false),
            Map.entry("sensitiveAllowed", false),
            Map.entry("readOnly", false),
            Map.entry("maxTurns", 10),
            Map.entry("maxToolCallsPerTurn", 5),
            Map.entry("timeBudgetSeconds", 120),
            Map.entry("quotaMaxTokens", 0),
            Map.entry("script", script));
    Files.writeString(dir.resolve(id + ".json"), JSON.writeValueAsString(template));
  }

  private static Map<String, Object> step(String toolCall, String text) {
    return toolCall == null
        ? Map.of("text", text)
        : Map.of("toolCall", toolCall, "args", Map.of("text", "忽略"), "text", "");
  }

  private static Map<String, Object> step(String toolCall, Map<String, Object> args) {
    return Map.of("toolCall", toolCall, "args", args, "text", "");
  }

  /** 轮询 manager 直到出现指定模板的实例（管理器因 spawn 完成即返回，读取时应已就绪）。 */
  private SubagentInstance awaitChild(App app, String templateId) throws Exception {
    SubagentInstance[] found = new SubagentInstance[1];
    await(
        () -> {
          found[0] =
              app.subagents().stream()
                  .filter(i -> i.templateId().equals(templateId))
                  .findFirst()
                  .orElse(null);
          return found[0] != null;
        },
        30_000,
        "等待子 Agent " + templateId + " 出现");
    return found[0];
  }

  /** 轮询某类型事件满足谓词（子体进程已落地后才出现——读取方与子体并发访问 WAL 事件库）。 */
  private void awaitEvent(Path db, String type, Map<String, Object> expected) throws Exception {
    await(
        () -> {
          List<Event> events = eventsOf(db, type);
          for (Event event : events) {
            boolean matches = true;
            for (Map.Entry<String, Object> entry : expected.entrySet()) {
              if (entry.getValue() == null) {
                if (payload(event, entry.getKey()) == null) {
                  matches = false;
                  break;
                }
              } else if (!entry.getValue().equals(payload(event, entry.getKey()))) {
                matches = false;
                break;
              }
            }
            if (matches) {
              return true;
            }
          }
          return false;
        },
        45_000,
        "等待事件 " + type + " " + expected);
  }

  private void awaitEvent(
      Path db, String type, String key, String value, String key2, Object value2) throws Exception {
    // Map.of/Map.entry 拒绝 null 值——"key 存在即可"约定（value2==null 时只校验 key2 存在）改用 HashMap
    Map<String, Object> expected = new HashMap<>();
    expected.put(key, value);
    if (key2 != null) {
      expected.put(key2, value2);
    }
    awaitEvent(db, type, expected);
  }

  /** 轮询某子 Agent 的生命周期动作序列（父级事件库）。 */
  private void awaitLifecycleActions(Path dataDir, String childId, List<String> expected)
      throws Exception {
    await(
        () -> {
          try (SqliteEventStore store = SqliteEventStore.open(dataDir.resolve("events.db"))) {
            return lifecycleActions(store, childId).equals(expected);
          }
        },
        30_000,
        "等待生命周期链 " + childId + " = " + expected);
  }

  private static List<String> lifecycleActions(SqliteEventStore store, String childId) {
    List<Event> events =
        store.query(new EventQuery(childId, EventTypes.AGENT_LIFECYCLE, childId, -1, 100));
    List<String> actions = new ArrayList<>();
    for (int i = events.size() - 1; i >= 0; i--) {
      actions.add(String.valueOf(payload(events.get(i), "action")));
    }
    return actions;
  }

  private static List<Event> eventsOf(Path db, String type) {
    try (SqliteEventStore store = SqliteEventStore.open(db)) {
      return store.query(new EventQuery("", type, "", -1, 100));
    }
  }

  private static Path childEvents(Path dataDir, String instanceId) {
    return dataDir.resolve("subagents").resolve(instanceId).resolve("events.db");
  }

  @SuppressWarnings("unchecked")
  private static Object payload(Event event, String key) {
    try {
      return JSON.readValue(event.payload(), Map.class).get(key);
    } catch (Exception e) {
      throw new AssertionError("事件 payload 无法解析: " + event.payload(), e);
    }
  }

  private static void await(BooleanSupplier condition, long timeoutMillis, String what)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("超时（" + timeoutMillis + "ms）: " + what);
  }
}
