package io.mosire.main.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionEventTypes;
import io.mosire.agentlib.decision.DecisionPackagesHttpHandler;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.decision.DecisionApprovalBridge;
import io.mosire.brain.runtime.StopReason;
import io.mosire.brain.runtime.TurnResult;
import io.mosire.main.app.App;
import io.mosire.main.app.BootConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 「一轮 {@code Ask} ⇒ 一个包」在<b>生产装配路径</b>上的端到端判据（设计 {@code 设计-决策包作为唯一决策单元.md} 裁定 1+2+4 + P1 验收判据
 * ①②③），以及事件面真的落进 {@code events.db} 的回读判据（§5quater ⑤）。
 *
 * <p><b>为什么必须在 App 这一层验</b>：队列、包、桥、审批面、事件镜像分别在四个模块里，"一轮三条只建一个包"这件事只有把真 {@code App} 起来、真 HTTP 面 +
 * 真事件库跑一遍才看得见；模块内的单元用例证明不了装配把队列绑上了、把事件镜像挂上了。
 *
 * <p><b>判别力（改坏哪里它会红）</b>：
 *
 * <ul>
 *   <li>把"回合收尾汇总"改成"每次 {@code Ask} 各建一包" ⇒ {@link
 *       #oneRoundWithThreeAsksBecomesExactlyOnePackageAndOneRequest()} 的"待裁决包恰好 1 个 /
 *       itemCount=3"红；
 *   <li>把 {@code Ask} 的登记分支去掉（回退成当场问人）⇒ 同一用例的"决策面上有包"红；
 *   <li>把"决策面答复 ⇒ 结算审批面请求"删掉 ⇒ 同一用例的"窗口 600s 却在 60s 内返回"红；
 *   <li>把事件镜像前缀白名单里的 {@code decision.package.} 去掉 ⇒ 三条从库回读的断言红（事件静默消失）；
 *   <li>把空队列的早返回删掉（没命中敏感调用的回合也建包）⇒ {@link #aRoundWithoutAsksLeavesNoDecisionPackageTrace} 红。
 * </ul>
 */
class AppDecisionPackageFlowTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SECRET = "sk-live-SECRET123";
  private static final String CLASS_KEY = "case:ask:probe";

  @TempDir Path tempDir;

  /**
   * 一轮点 3 个敏感调用 ⇒ <b>恰好 1 个包、3 条</b>；人从决策面整包批 ⇒ 三条各跑一次；提出者线程在 <b>60s 内</b>返回（而审批窗口是 600s ⇒
   * 只可能是"决策面答复把它结算了"，不是等满窗口）。
   *
   * <p>同时从 {@code events.db} 回读 {@code proposed/decided/committed} 三条事件，并断言载荷里没有参数明文。
   */
  @Test
  void oneRoundWithThreeAsksBecomesExactlyOnePackageAndOneRequest() throws Exception {
    AtomicInteger runsA = new AtomicInteger();
    AtomicInteger runsB = new AtomicInteger();
    AtomicInteger runsC = new AtomicInteger();
    Path dataDir = tempDir.resolve("data");
    writeDecisionConfig(dataDir, 600);
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(
        new LlmResponse(
            LlmMessage.assistant(
                List.of(
                    new ContentPart.ToolCall("call-1", "probe_a", Map.of("token", SECRET)),
                    new ContentPart.ToolCall("call-2", "probe_b", Map.of("token", SECRET)),
                    new ContentPart.ToolCall("call-3", "probe_c", Map.of("token", SECRET)))),
            "fake-model",
            0,
            0));
    llm.enqueue(LlmResponse.text("三条都交给裁决者了"));
    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);

    try (App app =
        App.start(
            config,
            llm,
            List.of(
                sensitiveProbe("probe_a", runsA),
                sensitiveProbe("probe_b", runsB),
                sensitiveProbe("probe_c", runsC)))) {
      ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();
      try {
        long startedNanos = System.nanoTime();
        Future<TurnResult> turn = turns.submit(() -> app.runtime().chat("三条一起跑"));

        JsonNode pending =
            awaitFirstRow(
                app.approvalPort(), DecisionPackagesHttpHandler.PATH, Duration.ofSeconds(20));
        String packageId = pending.get("id").asText();
        assertThat(pending.get("itemCount").asInt()).as("★ 一轮三条 ⇒ 恰好一个包、三条（不是三个包各一条）").isEqualTo(3);
        JsonNode packages =
            JSON.readTree(get(app.approvalPort(), DecisionPackagesHttpHandler.PATH).body());
        assertThat(packages.get("pending").size()).as("待裁决的包恰好一个").isEqualTo(1);
        assertThat(pending.get("items").size()).as("三条都上报给人（全是 SENSITIVE）").isEqualTo(3);

        JsonNode approvals = JSON.readTree(get(app.approvalPort(), "/api/approvals").body());
        assertThat(approvals.get("pending").size())
            .as("一条包 = 一条审批请求（不拆成逐条、也没有'不属于任何包的待裁决项'）")
            .isEqualTo(1);
        assertThat(approvals.get("pending").get(0).get("classKey").asText())
            .isEqualTo(DecisionApprovalBridge.CLASS_KEY);
        assertThat(approvals.get("pending").get(0).toString()).doesNotContain(SECRET);

        assertThat(runsA.get() + runsB.get() + runsC.get()).as("人还没答，三条的工具体一条都不许跑").isZero();

        HttpResponse<String> receipt =
            postPackageDecision(
                app.approvalPort(), packageId, "{\"approveAll\":true,\"by\":\"case-human\"}");
        assertThat(receipt.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(receipt.body());
        assertThat(body.get("state").asText()).isEqualTo("COMMITTED");
        assertThat(body.get("decision").get("by").asText()).isEqualTo("case-human");
        assertThat(body.get("commit").get("outcomes")).as("B-7：三条各有去向").hasSize(3);
        assertThat(body.get("commit").get("revisionRef").asText()).isNotEmpty();
        assertThat(runsA).hasValue(1);
        assertThat(runsB).hasValue(1);
        assertThat(runsC).hasValue(1);

        TurnResult result = turn.get(60, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
        assertThat(elapsedMs).as("审批窗口 600s，但人在决策面答完 ⇒ 提出者远早于窗口返回（结算生效）").isLessThan(60_000L);

        // 事件面回读（不是只订阅 bus）：三条事件真的落进提出者自己的 events.db
        List<Event> decisionEvents =
            awaitDecisionEvents(dataDir, packageId, 3, Duration.ofSeconds(10));
        assertThat(decisionEvents)
            .extracting(Event::type)
            .contains(
                DecisionEventTypes.PROPOSED,
                DecisionEventTypes.DECIDED,
                DecisionEventTypes.COMMITTED);
        for (Event event : decisionEvents) {
          assertThat(event.payload()).as("事件面只留摘要：参数明文一个字节都不许出现（红线 6）").doesNotContain(SECRET);
        }
        JsonNode decided = payloadOf(decisionEvents, DecisionEventTypes.DECIDED);
        assertThat(decided.get("proposerKey").asText()).isEqualTo("main");
        assertThat(decided.get("approvedCount").asInt()).isEqualTo(3);
        // ★ 逐条去向落在 committed 事件上（实现事实；设计 §5quater⑤ 的形状表把 outcomes 记在 decided 上——
        //   两者不符，已登记为文档/实现分歧，本单只按实现所能给出的面断言，不改生产代码）
        JsonNode committed = payloadOf(decisionEvents, DecisionEventTypes.COMMITTED);
        assertThat(committed.get("outcomes")).as("逐条去向随事件落库（下一轮查得到）").hasSize(3);
        assertThat(committed.get("revisionRef").asText()).isNotEmpty();
      } finally {
        turns.shutdownNow();
      }
    }
  }

  /**
   * 没人答复 ⇒ 包到点作废、工具体一次都不跑、{@code decision.package.expired} 落库（§5 裁定 8：作废也是结果，也必须可回读）。
   *
   * <p>★ <b>边界（照实记，不许读成"原因准确"）</b>：本用例把 {@code approval.timeoutSeconds} 设成 2s，而包自己的 deadline 是
   * 30min， 于是 {@code noAnswerReason} 会把"窗口到点没人答"写成"无可用通道"——这是桥的 javadoc 已登记的偏报（P2 第 ⑤ 项要修， 修它要动
   * {@code ApprovalCoordinator} 的公开面）。所以下面对原因的断言是<b>记录当前行为 + 标注边界</b>，不是"原因准确"。
   */
  @Test
  void anUnansweredRoundExpiresThePackageAndNeverRunsTheTool() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    Path dataDir = tempDir.resolve("data");
    writeDecisionConfig(dataDir, 2);
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(LlmResponse.toolCall("call-1", "probe_a", Map.of("token", SECRET)));
    llm.enqueue(LlmResponse.text("等不到人就算了"));
    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);

    try (App app = App.start(config, llm, List.of(sensitiveProbe("probe_a", runs)))) {
      TurnResult result = app.runtime().chat("跑一条没人答的");

      assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(runs).as("无人裁决 ⇒ 零落盘（fail-closed，不静默执行）").hasValue(0);

      String packageId = awaitPackageIdFromEvents(dataDir, Duration.ofSeconds(10));
      assertThat(packageState(app.approvalPort(), packageId))
          .as("没人答复的包当场作废（EXPIRED），不是停在'待裁决'")
          .isEqualTo("EXPIRED");

      List<Event> decisionEvents =
          awaitDecisionEvents(dataDir, packageId, 2, Duration.ofSeconds(10));
      assertThat(decisionEvents)
          .extracting(Event::type)
          .as("作废事件（第五条）真的落库，而不是只留一行 WARN")
          .contains(DecisionEventTypes.PROPOSED, DecisionEventTypes.EXPIRED)
          .doesNotContain(DecisionEventTypes.COMMITTED);
      JsonNode expired = payloadOf(decisionEvents, DecisionEventTypes.EXPIRED);
      assertThat(expired.get("reason").asText())
          .as("★ 记录当前行为：本次装配下偏报为'无可用通道'（窗口 2s ≪ 包 deadline 30min）——边界见 javadoc，不是原因准确")
          .contains("无可用通道");
      assertThat(expired.get("itemCount").asInt()).isEqualTo(1);
    }
  }

  /**
   * 一轮<b>没有</b>任何 {@code Ask} ⇒ 零副作用：不建包、不发事件、不投递（P1 验收判据 ③）。
   *
   * <p>这条是"不建包"那半边唯一的端到端钉子：把空队列的早返回删掉，事件库里会立刻多出 {@code decision.package.proposed}。
   */
  @Test
  void aRoundWithoutAsksLeavesNoDecisionPackageTrace() throws Exception {
    Path dataDir = tempDir.resolve("data");
    writeDecisionConfig(dataDir, 60);
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(LlmResponse.text("这轮不需要裁决任何东西"));
    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);

    try (App app =
        App.start(config, llm, List.of(sensitiveProbe("probe_a", new AtomicInteger())))) {
      TurnResult result = app.runtime().chat("随便聊两句");

      assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(
              JSON.readTree(get(app.approvalPort(), DecisionPackagesHttpHandler.PATH).body())
                  .get("pending")
                  .size())
          .as("决策面上没有未决包")
          .isZero();
      assertThat(
              JSON.readTree(get(app.approvalPort(), "/api/approvals").body()).get("pending").size())
          .as("审批面上也没有请求")
          .isZero();

      Path db = dataDir.resolve("events.db");
      long deadline = System.currentTimeMillis() + 1_000L;
      while (System.currentTimeMillis() < deadline) {
        assertThat(decisionPackageEvents(dataDir))
            .as("这轮没有命中敏感调用 ⇒ 事件库里一条 decision.package.* 都不该有")
            .isEmpty();
        Thread.sleep(100L);
      }
      assertThat(Files.isRegularFile(db)).as("事件库本身是开的（空的是决策包事件，不是没落库）").isTrue();
    }
  }

  /**
   * 装订期 {@code role.audit} <b>真的落进 events.db</b>（设计 §4.6 / §5.13(c)：审计事件的唯一目的是留痕，所以它走同步落库而不是总线）。
   *
   * <p><b>为什么单独立一条</b>：既有 {@code AppRoleAuditTest} 判的是"启动失败/成功"这条结论，从不回读事件库； 而这条留痕恰恰是"结论之外的证据"。
   * 判别力：把 {@code appendRoleAuditEvent} 的 {@code events.append} 换成只写日志 ⇒ 本用例红。
   */
  @Test
  void roleAuditEventLandsInTheEventsDbAtAssembly() throws Exception {
    Path dataDir = tempDir.resolve("data");
    writeDecisionConfig(dataDir, 60);
    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);

    try (App app =
        App.start(
            config,
            new RecordingLlmClient(),
            List.of(sensitiveProbe("probe_a", new AtomicInteger())))) {
      Event audit =
          readAllEvents(dataDir).stream()
              .filter(event -> "role.audit".equals(event.type()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("事件库里没有 role.audit（装订期审计没有留痕）"));
      JsonNode payload = JSON.readTree(audit.payload());
      assertThat(payload.get("toolCount").asInt()).isPositive();
      assertThat(audit.payload()).doesNotContain(SECRET);
      assertThat(app.approvalPort()).isPositive();
    }
  }

  // ---------- 工具与助手 ----------

  /** 自报 {@link AskKind#SENSITIVE} 的桩工具（体一旦执行就计数）。 */
  private static AgentTool sensitiveProbe(String name, AtomicInteger runs) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return "端到端用例的待裁决工具";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of("token", Map.of("type", "string")));
      }

      @Override
      public ToolGate gate(ToolContext context) {
        return new ToolGate.Ask(CLASS_KEY, "工具自报的一行说明", AskKind.SENSITIVE);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        runs.incrementAndGet();
        return ToolResult.ok("probe:ran");
      }
    };
  }

  private static void writeDecisionConfig(Path dataDir, int timeoutSeconds) throws Exception {
    Files.createDirectories(dataDir);
    Files.writeString(
        dataDir.resolve("config.json"),
        "{\"approval\":{\"timeoutSeconds\":"
            + timeoutSeconds
            + "},\"commands\":{\"mode\":\"limited\"}}",
        StandardCharsets.UTF_8);
  }

  /** 轮询列表响应的 {@code pending} 数组，直到出现第一行；到点用最后拿到的东西报错。 */
  private static JsonNode awaitFirstRow(int port, String path, Duration limit) throws Exception {
    long deadline = System.currentTimeMillis() + limit.toMillis();
    JsonNode rows = null;
    while (System.currentTimeMillis() < deadline) {
      rows = JSON.readTree(get(port, path).body()).get("pending");
      if (rows.size() > 0) {
        return rows.get(0);
      }
      Thread.sleep(20L);
    }
    throw new AssertionError("决策面上一直没有未决包，最后拿到: " + rows);
  }

  /** 轮询事件库直到该包出现 {@code expected} 条 decision.package.* 事件。 */
  private static List<Event> awaitDecisionEvents(
      Path dataDir, String packageId, int expected, Duration limit) throws Exception {
    long deadline = System.currentTimeMillis() + limit.toMillis();
    List<Event> last = List.of();
    while (System.currentTimeMillis() < deadline) {
      last =
          decisionPackageEvents(dataDir).stream()
              .filter(e -> packageId.equals(e.correlationId()))
              .toList();
      if (last.size() >= expected) {
        return last;
      }
      Thread.sleep(50L);
    }
    throw new AssertionError("等不到 " + expected + " 条包事件（包 " + packageId + "），最后拿到: " + last);
  }

  private static String awaitPackageIdFromEvents(Path dataDir, Duration limit) throws Exception {
    long deadline = System.currentTimeMillis() + limit.toMillis();
    while (System.currentTimeMillis() < deadline) {
      List<Event> events = decisionPackageEvents(dataDir);
      if (!events.isEmpty()) {
        return events.get(0).correlationId();
      }
      Thread.sleep(50L);
    }
    throw new AssertionError("事件库里一直没有 decision.package.* 事件");
  }

  private static List<Event> decisionPackageEvents(Path dataDir) throws Exception {
    List<Event> filtered = new ArrayList<>();
    for (Event event : readAllEvents(dataDir)) {
      if (event.type() != null && event.type().startsWith("decision.package.")) {
        filtered.add(event);
      }
    }
    return filtered;
  }

  /** 事件库里的全部事件（最近 500 条；只读打开，与生产写入并存）。 */
  private static List<Event> readAllEvents(Path dataDir) throws Exception {
    Path db = dataDir.resolve("events.db");
    if (!Files.isRegularFile(db)) {
      return List.of();
    }
    try (SqliteEventStore store = SqliteEventStore.openReadOnly(db)) {
      return store.query(EventQuery.recent(500));
    }
  }

  private static JsonNode payloadOf(List<Event> events, String type) throws Exception {
    for (Event event : events) {
      if (type.equals(event.type())) {
        return JSON.readTree(event.payload());
      }
    }
    throw new AssertionError("事件里没有 " + type + "：" + events.stream().map(Event::type).toList());
  }

  private static String packageState(int port, String packageId) throws Exception {
    HttpResponse<String> detail = get(port, DecisionPackagesHttpHandler.PATH + "/" + packageId);
    assertThat(detail.statusCode()).as("包详情必须查得到（终局包不淘汰）").isEqualTo(200);
    return JSON.readTree(detail.body()).get("state").asText();
  }

  private static HttpResponse<String> postPackageDecision(int port, String id, String body)
      throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + port + DecisionPackagesHttpHandler.PATH + "/" + id))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(int port, String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }

  /** 记录请求的脚本桩（本类的判据要读"模型第二次看到的是什么"）。 */
  private static final class RecordingLlmClient implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();

    void enqueue(LlmResponse response) {
      script.addLast(response);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new LlmException("RecordingLlmClient 脚本已耗空");
      }
      return response;
    }
  }
}
