package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalEventTypes;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionEventTypes;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.decision.DecisionApprovalBridge;
import io.mosire.brain.decision.SubmitDecisionPackageTools;
import io.mosire.brain.runtime.EventTypes;
import io.mosire.brain.subagent.SubagentInstance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>P3b 的缺口实测（真子进程）</b>：子体的敏感调用在<b>父侧逐条问人</b>，子体自己的库里<b>一个决策包都没有</b>。
 *
 * <p><b>它钉住的事实（不是设计意图，是今天真进程里发生的事）</b>：
 *
 * <ol>
 *   <li>子体的 registry 全部来自父侧导出面（{@code SubagentProcessMain} 里只有 {@code McpSourceBridge.bind} 一个填充点），
 *       而导出的工具是 {@code McpToolAdapter}——<b>不覆写</b> {@code gate()}（缺省 {@code ToolGate.ALLOW}）， MCP 的
 *       {@code tools/list} 也<b>不带闸位</b> ⇒ 子体侧永远看不到 {@code Ask}；
 *   <li>于是子体的本轮待裁决队列<b>恒空</b>（{@code ToolCallAuthorizer} 的登记分支只在有队列且工具报 {@code Ask} 时进），
 *       跨进程提交路径在真子进程里<b>不可达</b>；
 *   <li>真正的"问人"发生在<b>父侧</b>：子体的调用经 MCP 回到父进程，父侧 {@code AgentToMcpServer} 用自己那份 authorizer 对真工具跑判定 ⇒
 *       {@code Ask} ⇒ <b>恰好一条</b>逐条审批请求（{@code classKey} = 工具自己的，<b>不是</b> {@code
 *       decision:package}）。
 * </ol>
 *
 * <p><b>判别力（改坏哪里它会红）</b>：若哪天闸位过了桥（子体看得见 {@code Ask}），本用例的第 ① 组断言会红——那时应该把它 改成"子体库恰好一个包 +
 * 父侧一条包级请求"的正面判据（口径见 P3b 报告 §8 与设计 §5quinquies ④）。在那之前，本用例是 <b>已知缺口</b>的守卫：它保证缺口不会被"写得像已经实现"。
 *
 * <p>同一趟还证明<b>接线没有破坏子体进程语义</b>：进得去、跑得完、自行退出、终局记录落在自己的库里（退出码 0）。
 */
class ChildCrossProcessDecisionGapE2ETest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  /** 父侧那个"会问人"的工具到底执行了几次（本用例期望 0 次：审批窗口里没人答 ⇒ fail-closed 拒）。 */
  private final List<String> executed = new CopyOnWriteArrayList<>();

  /**
   * <b>子体面看得见 {@code submit_decision_package}</b>（P3b 交付物 §1(3) 的确认项）：子体脚本自己点它一次。
   *
   * <p>脚本给一个<b>别人的</b> {@code proposerKey}（{@code main}）⇒ 父侧工具的第一层身份核对当场具名拒（{@code
   * INVALID_ARGUMENTS}）， 拒答里带着<b>子体自己的实例 id</b>——这一条同时证明三件事：① 工具确实经 MCP 桥外发到了子体（模板 {@code
   * allowedTools} 里那一条生效）； ② 子体调得动它（不是"看得见一调就吃 PERMISSION_DENIED"）；③ 父侧认得出"这条是谁发的"（身份绑在链接上，模型给不出）。
   */
  @Test
  void theChildSeesAndCanCallTheCrossProcessSubmitTool() throws Exception {
    Path templates = tempDir.resolve("templates");
    Files.createDirectories(templates);
    writeTemplate(
        templates,
        "submitter",
        List.of(SubmitDecisionPackageTools.NAME),
        List.of(
            step(
                SubmitDecisionPackageTools.NAME,
                Map.of(
                    "proposerKey",
                    "main",
                    "items",
                    List.of(Map.of("toolName", "read_agent_context", "args", Map.of())))),
            step(null, "完成")));
    Path dataDir = tempDir.resolve("data");
    Files.createDirectories(dataDir);
    BootConfig config =
        new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0, templates);
    FakeLlmClient mainLlm =
        FakeLlmClient.with(
            LlmResponse.toolCall(
                "m-1", "spawn_sub_agent", Map.of("templateId", "submitter", "goal", "自己点一次提交口")),
            LlmResponse.text("已派遣"));
    App app = App.start(config, mainLlm, List.of());
    try (app) {
      app.runtime().chat("派一个会点提交口的子 Agent");
      SubagentInstance child = awaitChild(app, i -> "submitter".equals(i.templateId()));
      Path childEvents = childEvents(dataDir, child.instanceId());
      awaitEvent(childEvents, EventTypes.TOOL_RESULT, "tool", SubmitDecisionPackageTools.NAME);

      assertThat(eventsOf(childEvents, EventTypes.TOOL_RESULT))
          .filteredOn(e -> SubmitDecisionPackageTools.NAME.equals(payload(e, "tool")))
          .anySatisfy(
              e -> {
                String message = String.valueOf(payload(e, "message"));
                assertThat(message)
                    .as("★ 工具看得见、调得动；父侧按链接身份具名拒绝冒充（拒答里点名子体自己的实例 id）")
                    .contains("proposerKey 与调用者身份不一致")
                    .contains(child.instanceId());
              });
      assertThat(eventsOf(childEvents, EventTypes.PERMISSION_DENIED))
          .as("不是权限问题（模板 allowedTools 那一条生效）")
          .isEmpty();
    }
  }

  @Test
  void aRealChildsSensitiveCallIsDecidedAtTheParentFaceSoTheChildBuildsNoPackage()
      throws Exception {
    Path templates = tempDir.resolve("templates");
    Files.createDirectories(templates);
    writeTemplate(
        templates,
        "prober",
        List.of("sensitive_probe", SubmitDecisionPackageTools.NAME),
        List.of(step("sensitive_probe", Map.of("text", "探测一次")), step(null, "完成")));
    Path dataDir = tempDir.resolve("data");
    Files.createDirectories(dataDir);
    // 审批窗口 2s：本用例要的正是"父侧逐条问人"这一步——没人答 ⇒ 窗口到点 ⇒ fail-closed 拒（子体那条调用拿到 APPROVAL_DENIED）
    Files.writeString(
        dataDir.resolve("config.json"), "{\"approval\":{\"http\":true,\"timeoutSeconds\":2}}");

    BootConfig config =
        new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0, templates);
    FakeLlmClient mainLlm =
        FakeLlmClient.with(
            LlmResponse.toolCall(
                "m-1", "spawn_sub_agent", Map.of("templateId", "prober", "goal", "点一次敏感工具")),
            LlmResponse.text("已派遣探针"));
    App app = App.start(config, mainLlm, List.of(sensitiveProbe()));
    try (app) {
      app.runtime().chat("派一个会点敏感工具的探针子 Agent");
      SubagentInstance child = awaitChild(app, i -> "prober".equals(i.templateId()));
      Path childEvents = childEvents(dataDir, child.instanceId());

      // ---- 子体：跑得完（那条 Ask 被父侧拒了，回合照常结束）----
      awaitEvent(childEvents, EventTypes.TOOL_RESULT, "tool", "sensitive_probe");
      assertThat(executed).as("审批窗口里没人答 ⇒ 父侧 fail-closed 拒 ⇒ 工具本体一次都没执行").isEmpty();

      // ---- ★ 缺口①：子体自己的库里一个决策包都没有（队列恒空 ⇒ 没有包可提交）----
      try (SqliteEventStore childStore = SqliteEventStore.open(childEvents)) {
        for (String type :
            List.of(
                DecisionEventTypes.PROPOSED,
                DecisionEventTypes.DECIDED,
                DecisionEventTypes.COMMITTED,
                DecisionEventTypes.CONFLICTED,
                DecisionEventTypes.EXPIRED)) {
          assertThat(childStore.query(new EventQuery("", type, "", -1, 50)))
              .as("★ 缺口：真子进程里 %s 一条都不该有（子体看不见闸位 ⇒ 队列恒空）", type)
              .isEmpty();
        }
      }

      // 子体拿到的那句拒答由【父侧审批链】给出（class = 工具自己的闸位类键）：这是"问人发生在父侧"的直接证据
      assertThat(eventsOf(childEvents, EventTypes.TOOL_RESULT))
          .anySatisfy(
              event -> {
                Object message = payload(event, "message");
                assertThat(String.valueOf(message)).contains("审批未放行").contains("class=probe:ask");
              });

      // ---- ★ 缺口②：问人的那一跳在父侧，恰好一条，classKey = 工具自己的（不是 decision:package）----
      Path parentEvents = dataDir.resolve("events.db");
      awaitEvent(parentEvents, ApprovalEventTypes.REQUESTED, "tool", "sensitive_probe");
      try (SqliteEventStore parentStore = SqliteEventStore.open(parentEvents)) {
        List<Event> requests =
            parentStore.query(new EventQuery("", ApprovalEventTypes.REQUESTED, "", -1, 50));
        assertThat(requests).as("★ 缺口：子体这一轮只换来父侧一条【逐条】审批请求（不是一条包级请求）").hasSize(1);
        assertThat(payload(requests.get(0), "classKey"))
            .as("classKey = 工具自己的闸位类键；decision:package 那条路今天在真子进程里走不到")
            .isEqualTo("probe:ask");
        assertThat(requests)
            .as("★ 缺口：父侧这些请求里没有一条是包级请求（包路径在真子进程里不可达）")
            .noneMatch(e -> DecisionApprovalBridge.CLASS_KEY.equals(payload(e, "classKey")));
      }

      // ---- 接线没有破坏子体进程语义：终局记录落在自己的库、父侧记 FINISHED、退出码 0 ----
      awaitTerminalRecord(childEvents, child.instanceId());
      Map<String, Object> parentTerminal = awaitParentTerminal(dataDir, child.instanceId());
      assertThat(parentTerminal)
          .containsEntry("action", "finished")
          .containsEntry("stopReason", "FINISHED")
          .containsEntry("exitCode", 0);
    }
  }

  // ---------- 夹具 ----------

  /** 父侧的"会问人"工具：非敏感非破坏（否则子体的权限集看不见它）+ 自报 {@code Ask}（这才是分流点）。 */
  private AgentTool sensitiveProbe() {
    return new AgentTool() {
      @Override
      public String name() {
        return "sensitive_probe";
      }

      @Override
      public String description() {
        return "探针：自报 Ask，用来观察'问人'发生在哪一侧";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")));
      }

      @Override
      public ToolGate gate(ToolContext context) {
        return new ToolGate.Ask("probe:ask", "探针要向人确认一次", AskKind.SENSITIVE);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        executed.add(name());
        return ToolResult.ok("probe:" + context.arguments().getOrDefault("text", ""));
      }
    };
  }

  private void writeTemplate(
      Path dir, String id, List<String> allowedTools, List<Map<String, Object>> script)
      throws Exception {
    Map<String, Object> template =
        Map.ofEntries(
            Map.entry("id", id),
            Map.entry("description", "P3b 缺口实测模板 " + id),
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

  private static Map<String, Object> step(String toolCall, Map<String, Object> args) {
    return Map.of("toolCall", toolCall, "args", args, "text", "");
  }

  private static Map<String, Object> step(String toolCall, String text) {
    return toolCall == null
        ? Map.of("text", text)
        : Map.of("toolCall", toolCall, "args", Map.of("text", "忽略"), "text", "");
  }

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

  private void awaitEvent(Path db, String type, String key, Object value) throws Exception {
    await(
        () -> {
          for (Event event : eventsOf(db, type)) {
            if (value.equals(payload(event, key))) {
              return true;
            }
          }
          return false;
        },
        45_000,
        "等待事件 " + type + " " + key + "=" + value);
  }

  private void awaitTerminalRecord(Path childDb, String instanceId) throws Exception {
    await(
        () -> {
          for (Event event : eventsOf(childDb, EventTypes.AGENT_LIFECYCLE)) {
            if ("finished".equals(payload(event, "action"))
                && payload(event, "stopReason") != null) {
              return true;
            }
          }
          return false;
        },
        30_000,
        "等待子库终局记录 " + instanceId);
  }

  private Map<String, Object> awaitParentTerminal(Path dataDir, String childId) throws Exception {
    Path db = dataDir.resolve("events.db");
    Object[] found = new Object[1];
    await(
        () -> {
          for (Event event : eventsOf(db, EventTypes.AGENT_LIFECYCLE)) {
            if (childId.equals(event.agent()) && "finished".equals(payload(event, "action"))) {
              found[0] = payloadMap(event);
              return true;
            }
          }
          return false;
        },
        30_000,
        "等待父侧终局事件 " + childId);
    @SuppressWarnings("unchecked")
    Map<String, Object> terminal = (Map<String, Object>) found[0];
    return terminal;
  }

  private static void await(BooleanSupplier condition, long timeoutMs, String what)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("超时（" + timeoutMs + "ms）: " + what);
  }

  private static List<Event> eventsOf(Path db, String type) {
    if (!Files.isRegularFile(db)) {
      return List.of();
    }
    try (SqliteEventStore store = SqliteEventStore.open(db)) {
      List<Event> events = new ArrayList<>(store.query(new EventQuery("", type, "", -1, 100)));
      return events;
    } catch (RuntimeException justCreated) {
      return List.of();
    }
  }

  private static Path childEvents(Path dataDir, String instanceId) {
    return dataDir.resolve("subagents").resolve(instanceId).resolve("events.db");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> payloadMap(Event event) {
    try {
      return JSON.readValue(event.payload(), Map.class);
    } catch (Exception e) {
      throw new AssertionError("事件 payload 无法解析: " + event.payload(), e);
    }
  }

  private static Object payload(Event event, String key) {
    return payloadMap(event).get(key);
  }
}
