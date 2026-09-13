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
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W3b 真实子进程 E2E（本任务唯一的真实子进程 E2E——Task 6 验收口径）。
 *
 * <p>场景（离线、仅回环管道）：主 Agent（FakeLlm 编排脚本）经 {@code spawn_sub_agent} 派生<b>真实</b>子 Agent 子进程 （{@code
 * Main agent --id ... --parent-link}，父侧接管其 stdin/stdout 为 MCP stdio 链接）：
 *
 * <ol>
 *   <li>reader（只读模板 {@code [echo]}）：子体经 MCP 回环调用父级 echo 工具（tool.call/tool.result 落在子体事件库）；
 *   <li>grand（模板 {@code [echo, spawn_sub_agent]}，放行 destructive）：子体经 MCP 回环<b>成功</b>派生孙代——S5-A 起
 *       {@code spawn_sub_agent} 是 DEFAULT 级（此前 SYSTEM 级，子体必然被 {@code permission.denied} 挡下），
 *       开放的安全性由 manager 侧三道<b>锚到调用者</b>的闸承担；
 *   <li>孙代（同 grand 模板，真实子进程）：{@code depth=2}、{@code parentInstanceId=grand 实例}——身份穿透<b>两个</b> MCP
 *       跳的证据；它再派时 {@code depth=3 > 子深度上限 2}，被<b>深度闸</b>拒绝（拒绝类事件落在父级事件库、 子体侧 tool.result
 *       ok=false），父侧不留半个实例——"子体能派"与"派不出跑飞"同时钉住；
 *   <li>三个子体跑完脚本后阻塞等待父侧关停信号内（{@code whenClosed}），父级 kill → TERMINATING→KILLED 事件链。
 * </ol>
 *
 * <p>断言面：父级事件库 = 主 Agent 的 spawn 工具调用/结果 + 三个子 Agent 的完整生命周期链（configured→…→killed）+ 孙代越界的 {@code
 * decision:DEPTH_LIMIT}；子级事件库（{@code <dataDir>/subagents/<instanceId>/events.db}） = 各自的
 * tool.call/tool.result。
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
    // S5-A：grand 放行 destructive（否则 spawn_sub_agent 连工具体都进不去）；脚本<b>自递归</b>——子体派一个同模板的
    // 孙代，孙代再派时撞深度闸（depth 3 > 子深度上限 2）。一条真链同时验"子体能派""跨度由身份定""越界被拒"三件事。
    writeTemplate(
        templates,
        "grand",
        List.of("echo", "spawn_sub_agent"),
        List.of(
            step("spawn_sub_agent", Map.of("templateId", "grand", "goal", "再派一层（应被深度闸拒绝）")),
            step("echo", Map.of("text", "派完孙代后仍可服务")),
            step(null, "完成")),
        true);

    FakeLlmClient mainLlm =
        FakeLlmClient.with(
            LlmResponse.toolCall(
                "m-1", "spawn_sub_agent", Map.of("templateId", "reader", "goal", "问候主流程")),
            LlmResponse.text("已派遣问候子 Agent"),
            LlmResponse.toolCall(
                "m-2", "spawn_sub_agent", Map.of("templateId", "grand", "goal", "派发自递归子体")),
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
      SubagentInstance reader = awaitChild(app, i -> "reader".equals(i.templateId()));
      Path readerEvents = childEvents(dataDir, reader.instanceId());
      // 子体经 MCP 回环调用父级 echo：tool.call + tool.result（成功）落在子体事件库
      awaitEvent(readerEvents, EventTypes.TOOL_RESULT, "tool", "echo", "ok", true);
      // 往返原文钉住：result.message 是父侧 echo 工具的真实返回（模板脚本传入文本 → "echo:" 前缀）
      awaitEventContaining(
          readerEvents, EventTypes.TOOL_RESULT, "tool", "echo", "message", "echo:你好，子 Agent 报到");
      assertThat(eventsOf(readerEvents, EventTypes.TOOL_CALL))
          .extracting(e -> payload(e, "tool"))
          .contains("echo");
      assertThat(eventsOf(readerEvents, EventTypes.PERMISSION_DENIED)).isEmpty();

      // 回合 2：主 Agent（脚本）spawn grand —— 真实子进程
      app.runtime().chat("请派一个自递归探测子 Agent");
      SubagentInstance grand = awaitChild(app, i -> "grand".equals(i.templateId()));
      Path grandEvents = childEvents(dataDir, grand.instanceId());
      // 子体<b>成功</b>派孙代：父侧工具调用入口按 spec（DEFAULT 级）放行，manager 以调用者身份（grand，depth 1）派生
      awaitEvent(grandEvents, EventTypes.TOOL_RESULT, "tool", "spawn_sub_agent", "ok", true);
      // 派完仍可服务（S5-A 降级没有把这条链接弄坏）
      awaitEvent(grandEvents, EventTypes.TOOL_RESULT, "tool", "echo", "ok", true);

      // 孙代：真实子进程。depth=2（= maxDepth-1）+ parentInstanceId=grand ⇒ 身份穿透两个 MCP 跳的证据
      // （旧实现按 manager 的构造期 parentDepth 算，这一层会得到 depth=1 且可以无限繁殖下去）
      SubagentInstance grandchild =
          awaitChild(
              app,
              i ->
                  "grand".equals(i.templateId())
                      && grand.instanceId().equals(i.parentInstanceId()));
      assertThat(grandchild.instanceId()).isNotEqualTo(grand.instanceId());
      assertThat(grandchild.depth()).isEqualTo(2);
      assertThat(grandchild.parentInstanceId()).isEqualTo(grand.instanceId());

      // 孙代再派（depth 3 > 子深度上限 2）→ 深度闸拒绝：子体侧 tool.result ok=false、
      // 父侧 decision:DEPTH_LIMIT（agent=孙代 id ——"谁被拒了"，不是主 Agent）
      Path grandchildEvents = childEvents(dataDir, grandchild.instanceId());
      awaitEvent(grandchildEvents, EventTypes.TOOL_RESULT, "tool", "spawn_sub_agent", "ok", false);
      awaitDecision(dataDir, "DEPTH_LIMIT", grandchild.instanceId());

      // 父侧实例账：只有三个（reader / grand / 孙代），被拒的那次不留半个实例
      assertThat(app.subagents()).hasSize(3);

      // kill（经主 Agent 的 guard 出口驱动——与主 Agent 管线执行工具相同的路径）→ 三层关停 → 生命周期链
      for (SubagentInstance child : List.of(reader, grand, grandchild)) {
        assertThat(kill(app, child.instanceId()).success()).isTrue();
        awaitLifecycleActions(
            dataDir,
            child.instanceId(),
            List.of("configured", "spawning", "running", "terminating", "killed"));
      }

      // 主 Agent 父级事件库：spawn 工具调用/结果（脚本驱动、成功）+ 越权类事件为零
      // （孙代的深度拒绝发的是 decision:DEPTH_LIMIT，不是 permission.denied）
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
    writeTemplate(dir, id, allowedTools, script, false);
  }

  private void writeTemplate(
      Path dir,
      String id,
      List<String> allowedTools,
      List<Map<String, Object>> script,
      boolean destructiveAllowed)
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
            Map.entry("destructiveAllowed", destructiveAllowed),
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

  /** 轮询 manager 直到出现满足条件的实例（管理器因 spawn 完成即返回，读取时应已就绪）。 */
  private SubagentInstance awaitChild(App app, Predicate<SubagentInstance> match) throws Exception {
    SubagentInstance[] found = new SubagentInstance[1];
    await(
        () -> {
          found[0] = app.subagents().stream().filter(match).findFirst().orElse(null);
          return found[0] != null;
        },
        30_000,
        "等待子 Agent 出现");
    return found[0];
  }

  /** 轮询父级事件库里的 {@code decision} 事件（agent = 被拒的调用者）。 */
  private void awaitDecision(Path dataDir, String decision, String agentId) throws Exception {
    await(
        () -> {
          try (SqliteEventStore store = SqliteEventStore.open(dataDir.resolve("events.db"))) {
            return store.query(new EventQuery(agentId, EventTypes.DECISION, "", -1, 100)).stream()
                .anyMatch(e -> decision.equals(payload(e, "decision")));
          }
        },
        30_000,
        "等待 decision:" + decision + " agent=" + agentId);
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
    // Map.of/Map.entry 拒绝 null 值——约定 value2==null = 仅要求 key2 字段存在且值非空（匹配循环对 null 期望值走存在性分支）
    Map<String, Object> expected = new HashMap<>();
    expected.put(key, value);
    if (key2 != null) {
      expected.put(key2, value2);
    }
    awaitEvent(db, type, expected);
  }

  /** 轮询事件（type + key/value 精确匹配、key2 子串匹配——钉住拒绝方/往返原文的断言变体）。 */
  private void awaitEventContaining(
      Path db, String type, String key, Object value, String key2, String contained)
      throws Exception {
    await(
        () -> {
          for (Event event : eventsOf(db, type)) {
            Object actual2 = payload(event, key2);
            if (value.equals(payload(event, key))
                && actual2 != null
                && String.valueOf(actual2).contains(contained)) {
              return true;
            }
          }
          return false;
        },
        45_000,
        "等待事件 " + type + " " + key + "=" + value + " 且 " + key2 + " 含 [" + contained + "]");
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
