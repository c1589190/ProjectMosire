package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalEventTypes;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionEventTypes;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.DecisionPackagesHttpHandler;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>真子进程的跨进程决策包端到端</b>（P3d 的判据；本文件原为 P3b 的"缺口实测"，缺口已被 P3d 关闭）： 子体一轮点 N 条敏感工具 ⇒ <b>子体库恰好一个包</b> +
 * <b>父侧审批面恰好一条包级请求</b> + 裁决回灌 + <b>执行由子体发起</b> （父侧账上不再出现第二次审批）。
 *
 * <p><b>为什么必须真子进程</b>：这条链的每一跳都只在真进程里成立——子体的 registry 全部来自父侧导出面（{@code SubagentProcessMain} 里只有
 * {@code McpSourceBridge.bind} 一个填充点），导出的工具是 {@code McpToolAdapter}（不覆写 {@code gate()} ⇒ 缺省 {@code
 * ALLOW}），MCP 的 {@code tools/list} 也不带闸位 ⇒ <b>子体看不见 {@code Ask}</b>。 于是"要不要裁决"只能由父侧在它那条必经链上判（设计
 * §5sexies①），子体只认具名码 {@code DECISION_QUEUED}（④）。
 *
 * <p><b>它钉住的四件事（改坏哪一处都会红）</b>：
 *
 * <ol>
 *   <li><b>码必须过桥</b>：子体全靠 {@code ToolResult.code() == DECISION_QUEUED} 认账。若带内码在 MCP 往返里丢了 （退化成
 *       {@code MCP_TOOL_ERROR}），子体本地队列恒空 ⇒ 建不出包 ⇒ 本用例第 ① 组断言全红；
 *   <li><b>父侧本轮队列</b>：父侧把每一条 {@code Ask} 记进"该子体本轮队列"而不是逐条问人 ⇒ 父侧审批面上<b>没有</b> {@code
 *       classKey=probe:ask} 的逐条请求，只有一条 {@code classKey=decision:package}（本用例第 ③ 组）；
 *   <li><b>一次性放行</b>：被批子集由<b>子体发起</b>重调（标记 {packageId,index}）⇒ 父侧放行且<b>不再问人</b>：终局时父侧 {@code
 *       approval.requested} 仍恰好一条；标记还必须被剥掉——工具本体看到的参数里不许出现那个保留键；
 *   <li><b>裁决者看全文</b>：临时包里的明文来自<b>父侧自己收到的那一份</b>（提交里只有 index/toolName/digest）。
 * </ol>
 *
 * <p>★ <b>这不是 P3c 的验收面</b>：本用例是本单（P3d）交付物的<b>证据载体</b>，判据的判别力复核（变异自证 / 负向清单）归 P3c。
 */
class ChildCrossProcessDecisionE2ETest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 父侧 {@code sensitive_probe} 每次执行看到的参数（用来证"执行真的发生了"与"标记没漏进工具"）。 */
  private final List<Map<String, Object>> executed = new CopyOnWriteArrayList<>();

  @TempDir Path tempDir;

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
                    List.of(Map.of("toolName", "read_agent_context", "digest", "000000000000")))),
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

  /**
   * ★ <b>本单的核心判据（真子进程）</b>：子体一轮点 <b>3</b> 条敏感工具 ⇒ 子体库<b>恰好 1 个包（3 条）</b> + 父侧审批面<b>恰好 1 条</b>包级请求 +
   * 人批准 ⇒ 裁决回灌 + 被批子集由<b>子体</b>发起执行、父侧不产生第二次审批。
   */
  @Test
  void aRealChildsRoundOfThreeSensitiveCallsBecomesExactlyOnePackageAtBothSides() throws Exception {
    Path templates = tempDir.resolve("templates");
    Files.createDirectories(templates);
    writeTemplate(
        templates,
        "prober",
        List.of("sensitive_probe", SubmitDecisionPackageTools.NAME),
        List.of(
            step("sensitive_probe", Map.of("text", "一")),
            step("sensitive_probe", Map.of("text", "二")),
            step("sensitive_probe", Map.of("text", "三")),
            step(null, "完成")));
    Path dataDir = tempDir.resolve("data");
    Files.createDirectories(dataDir);
    // 审批窗口给足（人要在这段时间里从 HTTP 面答一次）；HTTP 面缺省开、端口由系统分配（0）
    Files.writeString(
        dataDir.resolve("config.json"), "{\"approval\":{\"http\":true,\"timeoutSeconds\":60}}");

    BootConfig config =
        new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0, templates);
    FakeLlmClient mainLlm =
        FakeLlmClient.with(
            LlmResponse.toolCall(
                "m-1", "spawn_sub_agent", Map.of("templateId", "prober", "goal", "点三次敏感工具")),
            LlmResponse.text("已派遣探针"));
    App app = App.start(config, mainLlm, List.of(sensitiveProbe()));
    try (app) {
      app.runtime().chat("派一个会点三次敏感工具的探针子 Agent");
      SubagentInstance child = awaitChild(app, i -> "prober".equals(i.templateId()));
      Path childEvents = childEvents(dataDir, child.instanceId());

      // ---- ③ 父侧审批面：等到"那一条"包级请求（包路径在真子进程里可达的直接证据）----
      var request = awaitPendingItem(app.approvalPort(), Duration.ofSeconds(30));
      assertThat(request.get("classKey").asText())
          .as("★ 一条包 = 一条请求；classKey 是包级冻结字面量")
          .isEqualTo(DecisionApprovalBridge.CLASS_KEY);
      assertThat(request.get("tool").asText())
          .as("请求的 tool 取上报条目里的第一条真工具名（不是常量）")
          .isEqualTo("sensitive_probe");
      assertThat(executed).as("人还没答：三条一条都不许跑").isEmpty();

      // ---- ★ P3e 判据①：决策面按审批面 summary 里的 xp-… 读得到**逐条明文**（与子体原始参数一致）----
      String packageId = crossProcessPackageIdOf(request);
      HttpResponse<String> detail =
          get(app.approvalPort(), DecisionPackagesHttpHandler.PATH + "/" + packageId);
      assertThat(detail.statusCode()).as("★ 决策面解析在途的跨进程临时包（过去这里是 404：人只能看一行包级摘要）").isEqualTo(200);
      JsonNode detailBody = JSON.readTree(detail.body());
      assertThat(detailBody.get("id").asText()).isEqualTo(packageId);
      assertThat(detailBody.get("itemCount").asInt()).isEqualTo(3);
      assertThat(detailBody.get("state").asText()).isEqualTo("PROPOSED");
      assertThat(detailBody.get("decision").isNull()).as("还没人裁 ⇒ decision 为 null").isTrue();
      List<Map<String, Object>> plaintext = new ArrayList<>();
      for (JsonNode item : detailBody.get("items")) {
        assertThat(item.get("toolName").asText()).isEqualTo("sensitive_probe");
        assertThat(item.get("summary").asText()).as("人读摘要也在（明文不是唯一的字段）").isNotBlank();
        assertThat(item.get("escalated").asBoolean()).as("三条都上报到人").isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> args = JSON.convertValue(item.get("args"), Map.class);
        plaintext.add(args);
      }
      assertThat(plaintext)
          .as("★ 逐条明文 = 父侧自己收到的那一份，与子体的原始参数逐字一致")
          .containsExactly(Map.of("text", "一"), Map.of("text", "二"), Map.of("text", "三"));

      // ---- ★ P3e：在途包**只读**：决策面的写路径（POST）不认它（它没有状态机、也不是决策单元）----
      HttpResponse<String> refused =
          postJson(
              app.approvalPort(),
              DecisionPackagesHttpHandler.PATH + "/" + packageId,
              "{\"approveAll\":true}");
      assertThat(refused.statusCode()).as("★ POST 不认在途包（不构成第二条决策路径）").isEqualTo(404);
      assertThat(JSON.readTree(refused.body()).get("code").asText())
          .isEqualTo(DecisionPackages.PACKAGE_NOT_FOUND);
      assertThat(
              JSON.readTree(get(app.approvalPort(), DecisionPackagesHttpHandler.PATH).body())
                  .get("pending"))
          .as("★ 在途包不进 pending() 列表（不变量 1：包登记表里没有它）")
          .isEmpty();

      // ---- 人批准这一包（一次）----
      HttpResponse<String> receipt =
          postDecision(
              app.approvalPort(),
              request.get("id").asText(),
              "{\"decision\":\"approve\",\"scope\":\"once\"}");
      assertThat(receipt.statusCode()).isEqualTo(200);

      // ---- 子体跑完（自己留下终局记录、退出码 0）----
      awaitTerminalRecord(childEvents, child.instanceId());
      Map<String, Object> parentTerminal = awaitParentTerminal(dataDir, child.instanceId());
      assertThat(parentTerminal)
          .containsEntry("action", "finished")
          .containsEntry("stopReason", "FINISHED")
          .containsEntry("exitCode", 0);

      // ---- ① 子体库：恰好一个包、三条、走完 proposed → decided → committed ----
      assertThat(eventsOf(childEvents, DecisionEventTypes.PROPOSED)).as("恰好一个包").hasSize(1);
      assertThat(eventsOf(childEvents, DecisionEventTypes.DECIDED)).hasSize(1);
      assertThat(eventsOf(childEvents, DecisionEventTypes.COMMITTED)).hasSize(1);
      assertThat(eventsOf(childEvents, DecisionEventTypes.EXPIRED))
          .as("没有作废（提交被受理 ⇒ 包不当场作废）")
          .isEmpty();
      assertThat(eventsOf(childEvents, DecisionEventTypes.CONFLICTED)).isEmpty();
      Map<String, Object> committed =
          payloadMap(eventsOf(childEvents, DecisionEventTypes.COMMITTED).get(0));
      assertThat(committed).containsEntry("itemCount", 3).containsEntry("approvedCount", 3);
      assertThat(committed.get("toolNames"))
          .as("一个包收了三条（不是三个包）")
          .isEqualTo(List.of("sensitive_probe", "sensitive_probe", "sensitive_probe"));

      // ---- ★ 执行由子体发起：三条都被执行，且工具看到的参数里没有放行标记（标记在父侧被剥掉）----
      assertThat(executed).as("三条被批条目各执行一次").hasSize(3);
      assertThat(executed)
          .extracting(row -> row.get("text"))
          .containsExactlyInAnyOrder("一", "二", "三");
      assertThat(executed)
          .allSatisfy(
              row ->
                  assertThat(row.keySet())
                      .as("★ 放行标记必须被剥掉：工具本体只该看到它自己的参数")
                      .containsExactly("text"));

      // ---- ③ 父侧账：审批面上恰好一条请求，且不是逐条那条（不再有第二次审批）----
      List<Event> requests = eventsOf(dataDir.resolve("events.db"), ApprovalEventTypes.REQUESTED);
      assertThat(requests).as("★ 一轮三条敏感调用 ⇒ 父侧恰好一条审批请求").hasSize(1);
      assertThat(payload(requests.get(0), "classKey")).isEqualTo(DecisionApprovalBridge.CLASS_KEY);
      assertThat(requests)
          .as("★ 逐条问人那条路不再被走到（classKey = probe:ask 一条都没有）")
          .noneMatch(e -> "probe:ask".equals(payload(e, "classKey")));

      // ---- 子体侧看到的是"已记入本轮决策包"（具名码 DECISION_QUEUED 的文案），不是审批拒绝 ----
      assertThat(eventsOf(childEvents, EventTypes.TOOL_RESULT))
          .filteredOn(e -> "sensitive_probe".equals(payload(e, "tool")))
          .hasSize(3)
          .allSatisfy(
              e -> {
                assertThat(payload(e, "ok")).isEqualTo(false);
                assertThat(String.valueOf(payload(e, "message")))
                    .as("父侧把这条记进了本轮队列（子体靠具名码认出，不靠文案匹配）")
                    .contains("已记入本轮决策包")
                    .doesNotContain("审批未放行");
              });

      // ---- ★ P3e：结算完成 ⇒ 在途明文面消失（只活在"正等人裁"的那段窗口里）----
      assertThat(
              get(app.approvalPort(), DecisionPackagesHttpHandler.PATH + "/" + packageId)
                  .statusCode())
          .as("★ 结算完即消失：同一个 id 再查是 404（在途面没有变成第二份历史）")
          .isEqualTo(404);
    }
  }

  /**
   * ★ <b>回归网（真子进程）：没有提交缝的子体，行为与接线前逐字一致</b>——父侧<b>不</b>把它的 {@code Ask} 记进本轮队列
   * （记了就等于"这条既不执行、也没人去裁"），而是照旧<b>逐条问人</b>；没人答 ⇒ 子体那条调用拿到 {@code APPROVAL_DENIED}，
   * 子体库<b>一个包都没有</b>（它绑不了队列 ⇒ 没有包可提交）。
   *
   * <p>这条钉的是 P3d 最危险的那个误接线：父侧若只按"来者是子体"就记账，那么模板 {@code allowedTools} 里没有 {@code
   * submit_decision_package} 的子体（今天的多数模板！）会看到"已记入本轮决策包"，却永远没有人来裁——敏感调用悄悄消失。
   */
  @Test
  void aSubagentWithoutTheSubmitSeamStillGetsPerCallApprovalAtTheParent() throws Exception {
    Path templates = tempDir.resolve("templates");
    Files.createDirectories(templates);
    // 模板 allowedTools 只有 sensitive_probe：没有提交口 ⇒ 子体那一侧 available() 判不在 ⇒ 不绑队列
    writeTemplate(
        templates,
        "blind",
        List.of("sensitive_probe"),
        List.of(step("sensitive_probe", Map.of("text", "探测一次")), step(null, "完成")));
    Path dataDir = tempDir.resolve("data");
    Files.createDirectories(dataDir);
    // 审批窗口 2s：本用例要的正是"父侧逐条问人"这一步——没人答 ⇒ 到点 ⇒ fail-closed 拒
    Files.writeString(
        dataDir.resolve("config.json"), "{\"approval\":{\"http\":true,\"timeoutSeconds\":2}}");

    BootConfig config =
        new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0, templates);
    FakeLlmClient mainLlm =
        FakeLlmClient.with(
            LlmResponse.toolCall(
                "m-1", "spawn_sub_agent", Map.of("templateId", "blind", "goal", "点一次敏感工具")),
            LlmResponse.text("已派遣探针"));
    App app = App.start(config, mainLlm, List.of(sensitiveProbe()));
    try (app) {
      app.runtime().chat("派一个会点敏感工具的探针子 Agent");
      SubagentInstance child = awaitChild(app, i -> "blind".equals(i.templateId()));
      Path childEvents = childEvents(dataDir, child.instanceId());
      awaitEvent(childEvents, EventTypes.TOOL_RESULT, "tool", "sensitive_probe");
      awaitTerminalRecord(childEvents, child.instanceId());

      assertThat(executed).as("窗口里没人答 ⇒ 父侧 fail-closed 拒 ⇒ 工具本体一次都没执行").isEmpty();
      assertThat(eventsOf(childEvents, EventTypes.TOOL_RESULT))
          .filteredOn(e -> "sensitive_probe".equals(payload(e, "tool")))
          .anySatisfy(
              e ->
                  assertThat(String.valueOf(payload(e, "message")))
                      .as("★ 没有缝 ⇒ 退回既有那一跳：父侧逐条问人（不是'已记入决策包'）")
                      .contains("审批未放行")
                      .contains("class=probe:ask"));

      List<Event> requests = eventsOf(dataDir.resolve("events.db"), ApprovalEventTypes.REQUESTED);
      assertThat(requests).as("父侧逐条问人：恰好一条（老行为逐字保持）").hasSize(1);
      assertThat(payload(requests.get(0), "classKey")).isEqualTo("probe:ask");
      for (String type :
          List.of(
              DecisionEventTypes.PROPOSED,
              DecisionEventTypes.DECIDED,
              DecisionEventTypes.COMMITTED,
              DecisionEventTypes.CONFLICTED,
              DecisionEventTypes.EXPIRED)) {
        assertThat(eventsOf(childEvents, type)).as("没有缝 ⇒ 子体库一个决策包事件都不该有（%s）", type).isEmpty();
      }
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
        executed.add(Map.copyOf(context.arguments()));
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
            Map.entry("description", "P3d 真子进程端到端模板 " + id),
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

  /** 轮询审批面待裁决列表直到出现第一项（HTTP 面在 App 进程里，子体的提交正阻塞在那条请求上）。 */
  private static JsonNode awaitPendingItem(int port, Duration limit) throws Exception {
    long deadline = System.currentTimeMillis() + limit.toMillis();
    JsonNode rows = null;
    while (System.currentTimeMillis() < deadline) {
      rows = JSON.readTree(get(port, "/api/approvals").body()).get("pending");
      if (rows != null && rows.size() > 0) {
        return rows.get(0);
      }
      Thread.sleep(20L);
    }
    throw new AssertionError("审批 HTTP 面上一直没有待裁决项（等了 " + limit + "），最后拿到: " + rows);
  }

  private static HttpResponse<String> get(int port, String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> postDecision(int port, String id, String body)
      throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/approvals/" + id))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  /** 任意路径的 POST（P3e：用来证决策面的写路径不认在途包）。 */
  private static HttpResponse<String> postJson(int port, String path, String body)
      throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  /**
   * 从审批面那条请求的 {@code summary} 里取出跨进程临时包 id（{@code xp-…}）。
   *
   * <p>刻意走"人看到的那一行"而不是从日志或内部状态里捞：决策面能被查到的前提就是"人在审批面上看得见这个 id"—— 取不到 ⇒ 直接失败（那是"人根本无从查全文"的形状，正是 P3e
   * 要修的那条）。
   */
  private static String crossProcessPackageIdOf(JsonNode request) {
    String summary = request.get("summary").asText();
    Matcher found = Pattern.compile("xp-[0-9a-fA-F-]{36}").matcher(summary);
    assertThat(found.find()).as("审批面 summary 里带着跨进程临时包 id（人据此到决策面看全文）: %s", summary).isTrue();
    return found.group();
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
