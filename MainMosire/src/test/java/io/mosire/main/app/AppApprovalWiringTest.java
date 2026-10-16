package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionEventTypes;
import io.mosire.agentlib.decision.DecisionPackagesHttpHandler;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.mcp.PipeMcpClientTransport;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.decision.DecisionApprovalBridge;
import io.mosire.brain.runtime.StopReason;
import io.mosire.brain.runtime.TurnResult;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
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
 * 审批面在<b>生产装配路径</b>上的接线判据（S4-B2）：H8（同一份 {@code PendingApprovals}）+ H7 的装配线版本（MCP 面） + fail-closed
 * 的反面对照。
 *
 * <p><b>为什么必须在 App 这一层验</b>：管线层/通道层各自的用例证明不了"装配时把同一份登记表交给了两条路"——那正是本包最可能犯的错 （H8
 * 的判别性就是"两处各建一个登记表"）。这里起真 {@code App}（真 HTTP 面、真编排器、真工具面）。
 *
 * <p><b>★ P1b 之后两条来路落到不同的面上（本类因此分成两组用例）</b>：
 *
 * <ul>
 *   <li>① <b>主 Agent 管线</b>里的 {@code Ask}（{@code chat} 里模型要求调用工具）：不再"当场问人、当场执行/拒"——它登记进本轮待裁决队列，
 *       回合收尾收成<b>一个包</b>、<b>同步</b>投递给裁决者（裁定 1/2/4）。所以人裁决的地方是<b>决策面</b>（{@code GET/POST
 *       /api/decision-packages}）；包在等人期间只表现为审批面上<b>一条包级请求</b>（{@code
 *       classKey=decision:package}，一条包=一条请求）；
 *   <li>② <b>外部 MCP 客户端</b>的 {@code tools/call}（App 的 MCP 暴露面）：那条路没有回合作用域（不绑队列）⇒ {@code Ask}
 *       仍走既有审批闸，人由 {@code POST /api/approvals/{id}} 扮演（第三条用例）。
 * </ul>
 *
 * <p>两条来路都必须落到<b>同一个</b>编排器与<b>同一份</b>登记表上（H8）——这条装配纪律没有变，变的是管线那条路的裁决入口。
 */
class AppApprovalWiringTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CLASS_KEY = "bash:ask:probe";
  private static final String SUMMARY = "探测命令（工具自报的一行说明）";

  /**
   * 参数里的"密钥样"明文：<b>审批请求与事件面</b>里不许出现它（红线 4：只带 digest/summary/classKey）。
   *
   * <p>★ <b>决策面是有意的例外</b>（设计 §5ter 裁定 7 丙 / V12「肯定全文」）：包<b>详情</b>给明文 {@code args}，包<b>列表</b>的条目人读摘要
   * 也由 {@code args} 派生（{@code DecisionItemDigest.itemSummary}）——"人要看清自己批的是什么"是那条裁定的原话。所以本常量在决策面响应里
   * <b>会</b>出现，且有用例钉着它（见 {@link #httpDecisionReleasesThePipelineWaitingOnTheSameRegistry}）。
   */
  private static final String SECRET = "sk-live-SECRET123";

  @TempDir Path tempDir;

  /**
   * H8 的装配线版本（P1b 新语义）：包在决策面上等人裁决 ⇒ 人从<b>决策面</b>批它 ⇒ 被批条目在提交口真的执行了一次，且管线侧的等待确实 停在<b>同一份</b>登记表上。
   *
   * <p><b>判别性（对照旧用例逐条，不许弱化）</b>：
   *
   * <ul>
   *   <li>① "人还没答之前工具体一次都不许跑"——<b>原样保留</b>（裁决前 {@code runs} 必须为 0）；
   *   <li>② "批准之后工具体真的跑了一次"——<b>原样保留且更强</b>：回执逐条给出去向（{@code COMMITTED}）与批次引用，"跑了一次"不再只由计数器回答；
   *   <li>③ H8（"装配时两条路各建一份登记表"——本类最可能犯的错）——换成它在 P1b 的等价形态：包在等人期间，审批面上恰好<b>一条包级请求</b> （{@code
   *       classKey=decision:package}、{@code tool}=包内首条真工具名）⇒ 管线收尾确实阻塞在那张共享登记表上；这条同时守住"一条包 =
   *       一条请求"（不拆成逐条）。
   * </ul>
   *
   * <p><b>★ 新链路下确实变了形状的两条（照实记，不许说成没变）</b>：
   *
   * <ul>
   *   <li>"参数明文一个字节都不许出现"原来是在<b>审批面</b>上验的——那条断言<b>原样保留</b>在包级请求上（只有 digest/摘要）。决策面是另一条面： 按裁定 7 丙 /
   *       V12「肯定全文」，包<b>详情</b>给明文 {@code args}，包<b>列表</b>的条目摘要也由 {@code args} 派生 （{@code
   *       DecisionItemDigest.itemSummary}）。所以本用例不再对决策面断言"不含明文"，改为钉住"摘要 = 包层派生"这条口径；
   *   <li>旧用例的"阻塞在管线侧的等待<b>随这次裁决</b>返回"——P2b 起<b>成立且被钉住</b>：决策面答复成功回填后会把审批面上那条包级请求 <b>结算</b>（{@code
   *       DecisionApprovalBridge#settleApprovalRequest}），提出者线程<b>立即</b>放行。所以本用例把审批窗口设成 60s 再断言"回合在
   *       20s 内收尾"——窗口没到点就返回，只能是结算把它放开的（P2a 时这里要等满 8s 窗口，本用例当时只能断言"没崩"）。
   * </ul>
   *
   * <p><b>为什么把档位钉成 limited</b>：包请求的 kind 是 {@code MODE_LIMITED}（装配接了上级判定明细缝），而档位<b>缺省 FULL</b> ⇒
   * 包会先落进 {@code SuperiorJudgeGate} 的"上级代批"那条路；本用例的 LLM 是脚本桩（两个响应只够喂主循环），判定调用必然失败并终局拒绝，
   * 包根本到不了人（2026-10-16 实测：轮询 20s 一条都没有）。钉成 limited 才是"这一包交给裁决者本人"的形状——见 {@link
   * #writeDecisionConfig}。
   */
  @Test
  void httpDecisionReleasesThePipelineWaitingOnTheSameRegistry() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    Path dataDir = tempDir.resolve("data");
    writeDecisionConfig(dataDir, 60);
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(LlmResponse.toolCall("call-1", "danger_probe", Map.of("token", SECRET)));
    llm.enqueue(LlmResponse.text("已按人的裁决处理"));
    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);

    try (App app = App.start(config, llm, List.of(probeTool(runs)))) {
      assertThat(app.approvalPort()).as("审批 HTTP 面默认开（approval.http 缺省 true）").isGreaterThan(0);
      ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<TurnResult> turn = turns.submit(() -> app.runtime().chat("帮我装个包"));

        JsonNode pending = awaitPendingPackage(app.approvalPort(), Duration.ofSeconds(20));
        String packageId = pending.get("id").asText();
        assertThat(pending.get("itemCount").asInt()).as("一轮点一次敏感调用 ⇒ 一个包、一条").isEqualTo(1);
        JsonNode item = pending.get("items").get(0);
        assertThat(item.get("index").asInt()).isZero();
        assertThat(item.get("toolName").asText()).isEqualTo("danger_probe");
        assertThat(item.get("summary").asText())
            .as("条目摘要由包层从 args 派生（裁定 6：不再是工具自报的 Ask.summary，也不再是『工具名 + 摘要』）")
            .isEqualTo("danger_probe token=" + SECRET)
            .isNotEqualTo(SUMMARY);

        // ③ 包在等人 ⇒ 管线侧阻塞在同一份登记表上：一条包 = 一条请求（不是逐条一条）
        JsonNode request = awaitPendingItem(app.approvalPort(), Duration.ofSeconds(10));
        assertThat(request.get("classKey").asText()).isEqualTo(DecisionApprovalBridge.CLASS_KEY);
        assertThat(request.get("tool").asText())
            .as("请求的 tool 取包内首条的真工具名（不是常量）")
            .isEqualTo("danger_probe");
        assertThat(request.toString())
            .as("审批请求只带 digest/summary：参数明文一个字节都不许出现（旧用例的同一条断言，原样保留）")
            .doesNotContain(SECRET);
        assertThat(
                JSON.readTree(get(app.approvalPort(), "/api/approvals").body())
                    .get("pending")
                    .size())
            .as("一条包 = 一条请求（不拆成逐条）")
            .isEqualTo(1);

        assertThat(runs).as("人还没答，工具体绝不能先跑").hasValue(0);

        HttpResponse<String> receipt =
            postPackageDecision(
                app.approvalPort(), packageId, "{\"approveAll\":true,\"by\":\"case-human\"}");
        assertThat(receipt.statusCode()).isEqualTo(200);
        JsonNode receiptBody = JSON.readTree(receipt.body());
        assertThat(receiptBody.get("state").asText()).isEqualTo("COMMITTED");
        assertThat(receiptBody.get("decision").get("by").asText())
            .as("裁决人如实记在回执里（裁定 11 的『负责人』）")
            .isEqualTo("case-human");
        assertThat(receiptBody.get("decision").get("approved").size()).isEqualTo(1);
        assertThat(receiptBody.get("decision").get("approved").get(0).asInt()).isZero();
        JsonNode commit = receiptBody.get("commit");
        assertThat(commit.get("status").asText())
            .as("提交口（本机形态=就地执行被批子集）真的落了这一条")
            .isEqualTo("COMMITTED");
        assertThat(commit.get("outcomes").size()).as("逐条去向与包内条目一一对应").isEqualTo(1);
        assertThat(commit.get("outcomes").get(0).asText()).isEqualTo("COMMITTED");
        assertThat(commit.get("revisionRef").asText())
            .as("COMMITTED 必须带非空引用（设计 §5.11 第 7 条）")
            .isNotEmpty();
        assertThat(runs).as("批准之后工具体真的执行了").hasValue(1);

        assertThat(turn.get(20, TimeUnit.SECONDS).stopReason())
            .as("决策面答复把审批面那条请求结算掉了 ⇒ 提出者线程立即放行（审批窗口是 60s，没到点）")
            .isEqualTo(StopReason.FINISHED);
        assertThat(packageState(app.approvalPort(), packageId))
            .as("管线侧到点收尾时不得把已落定的包改写作废（迟到路径不许覆盖裁决）")
            .isEqualTo("COMMITTED");
      } finally {
        turns.shutdownNow();
      }
    }
  }

  /**
   * fail-closed 的反面对照（红线 1：本包不得新增放行分支）：人从决策面<b>整包驳</b> ⇒ 工具体一次都不跑、零落盘，且"被驳"是<b>具名裁决</b>
   * （不是"没人答复/窗口到点"那一档）。
   *
   * <p><b>判别性（对照旧用例逐条，不许弱化）</b>：
   *
   * <ul>
   *   <li>"被拒的调用绝不执行工具体"——<b>原样保留</b>（{@code runs} 仍必须为 0），并补一条"人还没答之前也不许跑"；
   *   <li>旧用例第三条"拒绝的结论回到了模型面（不是超时形态）"——<b>在新链路下无法用同一形状表达</b>（见下），改钉到能表达它的那一面： 回执 {@code
   *       state=REJECTED}、逐条去向 {@code REJECTED}、理由写明"被批子集为空…未调用提交口"，且管线侧到点收尾<b>不许</b>把 {@code
   *       REJECTED} 改写成 {@code EXPIRED}——"被驳"与"没人答复"必须看得见地区分开；
   *   <li>模型侧那条<b>收紧成</b>：模型当场拿到的是"已加入决策包（尚未裁决、本轮不会执行）"这一<b>受理</b>形态——既不是执行成功，也不是旧的 {@code
   *       APPROVAL_DENIED} 形态。
   * </ul>
   *
   * <p><b>★ 为什么"模型侧拿到拒绝结论"这条在新链路下表达不了</b>：{@code Ask} 命中时当场回给模型的是受理（登记进本轮队列），而裁决发生在
   * <b>回合收尾之后</b>——结果不回灌进本轮的对话（登记在案的 P2 ④"查自己的历史"）。这不是断言被放宽，是形状变了：旧形状里"拒"与"模型侧结论"是
   * 同一次调用的两面，新形状里它们分属两个时刻。
   */
  @Test
  void deniedApprovalFailsClosedAndKeepsTheToolBodyUnfired() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    Path dataDir = tempDir.resolve("data");
    writeDecisionConfig(dataDir, 60);
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(LlmResponse.toolCall("call-1", "danger_probe", Map.of("token", SECRET)));
    llm.enqueue(LlmResponse.text("好的，我不装了"));
    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);

    try (App app = App.start(config, llm, List.of(probeTool(runs)))) {
      ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<TurnResult> turn = turns.submit(() -> app.runtime().chat("帮我装个包"));

        JsonNode pending = awaitPendingPackage(app.approvalPort(), Duration.ofSeconds(20));
        String packageId = pending.get("id").asText();
        assertThat(runs).as("人还没答，工具体绝不能先跑").hasValue(0);

        HttpResponse<String> receipt =
            postPackageDecision(
                app.approvalPort(), packageId, "{\"rejectAll\":true,\"by\":\"case-human\"}");
        assertThat(receipt.statusCode()).isEqualTo(200);
        JsonNode receiptBody = JSON.readTree(receipt.body());
        assertThat(receiptBody.get("state").asText())
            .as("整包驳 ⇒ 零落盘终局（不是 EXPIRED 那一档『没人答复』）")
            .isEqualTo("REJECTED");
        assertThat(receiptBody.get("decision").get("by").asText()).isEqualTo("case-human");
        assertThat(receiptBody.get("decision").get("approved").size()).isZero();
        assertThat(receiptBody.get("decision").get("rejected").size()).isEqualTo(1);
        assertThat(receiptBody.get("decision").get("rejected").get(0).asInt()).isZero();
        JsonNode commit = receiptBody.get("commit");
        assertThat(commit.get("status").asText()).isEqualTo("REJECTED");
        assertThat(commit.get("outcomes").size()).isEqualTo(1);
        assertThat(commit.get("outcomes").get(0).asText())
            .as("逐条去向：被驳（由包层填，不是宿主）")
            .isEqualTo("REJECTED");
        assertThat(commit.get("reason").asText()).as("零落盘的理由要说得出来").contains("被批子集为空");
        assertThat(runs).as("被拒的调用绝不执行工具体").hasValue(0);

        assertThat(turn.get(20, TimeUnit.SECONDS).stopReason())
            .as("驳回同样结算那条包级请求 ⇒ 立即放行（不是等满 60s 窗口）")
            .isEqualTo(StopReason.FINISHED);
        assertThat(packageState(app.approvalPort(), packageId))
            .as("到点收尾不得把『被驳』改写成『没人答』（REJECTED 与 EXPIRED 必须分得开）")
            .isEqualTo("REJECTED");
        assertThat(toolContents(llm.requests.get(1)))
            .as("模型侧拿到的是受理形态（P1b）：既不是执行成功，也不是旧的审批拒绝形态")
            .singleElement()
            .satisfies(
                content ->
                    assertThat(content)
                        .contains("已加入决策包")
                        .contains("不会在现在执行")
                        .doesNotContain("审批未放行"));
      } finally {
        turns.shutdownNow();
      }
    }
  }

  /**
   * <b>包级请求不接受会话放行</b>（设计 §5ter 裁定 13 的口径面；P2b）：人从审批面给<b>包级请求</b>批"本会话" ⇒ 实际生效 = 一次 （回执 {@code
   * scope=once}），而这一包照常被裁决与执行。
   *
   * <p><b>为什么这条必须钉住</b>：包级 classKey 是冻结字面量（{@code decision:package}），若请求的 callerKey 取令牌桶 （主 Agent =
   * {@code SYSTEM}），人对<b>某一个包</b>批的"本会话"就会被 {@code AutoApproveGate} 按 {@code (callerKey, classKey)}
   * 命中、把以后<b>每一个</b>包都放行——"不同范围的两次裁决不得互相放行"这条口径就没了。收窄的落点是 {@code App} 的 {@code sessionGrantable}
   * 判据（{@code ApprovalCoordinator} 的既有注入缝，那个类一个字没改）。
   *
   * <p><b>判别性（变异自证点）</b>：把 {@code App.sessionGrantable} 里的包级收窄去掉（包级 callerKey 也算合格） ⇒ 回执会回 {@code
   * scope=session} ⇒ 本用例当场红。
   */
  @Test
  void packageLevelApprovalIsNarrowedToOnce() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    Path dataDir = tempDir.resolve("data");
    writeDecisionConfig(dataDir, 60);
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(LlmResponse.toolCall("call-1", "danger_probe", Map.of("token", SECRET)));
    llm.enqueue(LlmResponse.text("收到"));
    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);

    try (App app = App.start(config, llm, List.of(probeTool(runs)))) {
      ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<TurnResult> turn = turns.submit(() -> app.runtime().chat("帮我装个包"));

        JsonNode request = awaitPendingItem(app.approvalPort(), Duration.ofSeconds(20));
        assertThat(request.get("classKey").asText())
            .as("人这次面对的是那条包级请求")
            .isEqualTo(DecisionApprovalBridge.CLASS_KEY);
        assertThat(request.toString())
            .as("包级请求的 callerKey 是包级唯一桶（审批列表不暴露该字段，这里只能钉住它的形状不出现在明文面）")
            .doesNotContain(SECRET);
        assertThat(runs).as("人还没答，工具体绝不能先跑").hasValue(0);

        HttpResponse<String> receipt =
            postDecision(
                app.approvalPort(),
                request.get("id").asText(),
                "{\"decision\":\"approve\",\"scope\":\"session\"}");
        assertThat(receipt.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(receipt.body()).get("scope").asText())
            .as("包级请求的「本会话」降级为一次")
            .isEqualTo("once");
        assertThat(runs).as("收窄的是会话语义，不是这一次的批准").hasValue(1);
        assertThat(turn.get(20, TimeUnit.SECONDS).stopReason()).isEqualTo(StopReason.FINISHED);
      } finally {
        turns.shutdownNow();
      }
    }
  }

  /**
   * ★ P2b：<b>级联自己拍板 ⇒ 不投审批面</b>（设计 §5ter 裁定 12：到顶仍无人有能力才到人；这里"有能力的那一级"就是提出者本身）。
   *
   * <p>形状：工具自报 {@link io.mosire.agentlib.approval.AskKind#MODE_LIMITED}（档位提升那一类，<b>可以</b>被逐级预裁定），
   * 档位缺省 {@code FULL} ⇒ 主 Agent 那一级过能力判据 ⇒ 预裁定批准、收尾时执行，<b>人一次都不被问</b>。 反面是 SENSITIVE（{@link
   * #httpDecisionReleasesThePipelineWaitingOnTheSameRegistry} 的桩工具）：那一条永远到人。
   *
   * <p><b>判别性（变异自证点）</b>：把"没人上报就不投递"改回"一律投递" ⇒ 审批面上会出现一条请求、而 {@code runs} 仍为 0 （等人答）⇒ 本用例当场红。
   *
   * <p>同时钉住轨迹的落点之一（§5quater ③）：{@code decision.package.decided} 事件的载荷里要有 {@code trail}
   * （逐级"谁批了哪些条、谁上报到人、终局裁决者是谁"）——事件经总线异步镜像落库，所以这里轮询等它出现。
   */
  @Test
  void cascadeApprovesModeLimitedItemWithoutAskingTheHuman() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    Path dataDir = tempDir.resolve("data");
    // 档位缺省 FULL（不钉 limited）：这正是"本级有能力"的那一档
    writeApprovalConfig(dataDir, 60);
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(LlmResponse.toolCall("call-1", "limited_probe", Map.of("token", SECRET)));
    llm.enqueue(LlmResponse.text("已按预裁定处理"));
    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);

    try (App app = App.start(config, llm, List.of(modeLimitedProbeTool(runs)))) {
      TurnResult result = app.runtime().chat("跑一条档位提升的命令");

      assertThat(result.stopReason()).isEqualTo(StopReason.FINISHED);
      assertThat(runs).as("级联预裁定批准 ⇒ 被批子集在收尾时执行了（全程没有人参与）").hasValue(1);
      assertThat(
              JSON.readTree(get(app.approvalPort(), "/api/approvals").body()).get("pending").size())
          .as("没有人被上报 ⇒ 审批面上一条请求都不该有")
          .isZero();
      assertThat(
              JSON.readTree(get(app.approvalPort(), DecisionPackagesHttpHandler.PATH).body())
                  .get("pending")
                  .size())
          .as("包已经终局（不是待裁决）⇒ 决策面上也没有未决包")
          .isZero();
      JsonNode settled =
          JSON.readTree(get(app.approvalPort(), DecisionPackagesHttpHandler.PATH + "/dp-1").body());
      assertThat(settled.get("state").asText()).isEqualTo("COMMITTED");
      assertThat(settled.get("decision").get("by").asText())
          .as("终局裁决者 = 那一级 Agent（不是 approval / http）")
          .isEqualTo("main");

      JsonNode trail = awaitDecidedTrail(dataDir, "dp-1", Duration.ofSeconds(10));
      assertThat(trail.get("finalizedBy").asText())
          .as("★ 轨迹落点：decided 事件的载荷里有 trail（§5quater ③）")
          .isEqualTo("main");
      assertThat(trail.get("escalatedToHuman").size()).as("没人上报到人（这一包全程没问人）").isZero();
      assertThat(trail.get("levels").get(0).get("approved").get(0).asInt()).isZero();
    }
  }

  /**
   * ★ P2b：<b>人裁的只是上报的那些条目，下级已经预裁定批准的照旧执行</b>（设计 §5quater ②「人面对的是剩下的这些条目，不是整包」）。
   *
   * <p>一个包里两条：{@code limited_probe}（{@link AskKind#MODE_LIMITED}，缺省 FULL 档下本级有能力 ⇒ 预裁定批准、不上报）+
   * {@code danger_probe}（{@link AskKind#SENSITIVE} ⇒ 强制到人）。要钉住四件事：
   *
   * <ul>
   *   <li>决策面列表<b>只列上报的那条</b>（{@code items} 过滤 + {@code escalated} 标记）——但 {@code itemCount} 仍是整包条数；
   *   <li>人还没答之前两条都不执行（预裁定只是"先记下"，不是"先执行"——裁定 12 的原话）；
   *   <li>人 {@code approveAll} 之后<b>两条都落定</b>：人批的是上报集合，预批准的那条不因他没勾而被撤（合并口径）；
   *   <li>逐条去向与理由都在回执里（没有理由的成功条目 {@code reason=null}）。
   * </ul>
   *
   * <p><b>判别性</b>：把桥的合并口径改成"人的裁决 = 整包"（丢掉预批准）⇒ 第三条红；"人驳回时把预批准一起撤掉"由 {@link
   * #humanRejectionDoesNotRevokePreApprovedItems()} 单独钉住（{@code approveAll} 这一半钉不住它）。
   */
  @Test
  void humanDecidesOnlyEscalatedItemsWhilePreApprovedOnesStillRun() throws Exception {
    AtomicInteger limitedRuns = new AtomicInteger();
    AtomicInteger sensitiveRuns = new AtomicInteger();
    Path dataDir = tempDir.resolve("data");
    writeApprovalConfig(dataDir, 60);
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(twoToolCalls());
    llm.enqueue(LlmResponse.text("好"));
    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);

    try (App app =
        App.start(
            config, llm, List.of(modeLimitedProbeTool(limitedRuns), probeTool(sensitiveRuns)))) {
      ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<TurnResult> turn = turns.submit(() -> app.runtime().chat("两条一起跑"));

        JsonNode pending = awaitPendingPackage(app.approvalPort(), Duration.ofSeconds(20));
        String packageId = pending.get("id").asText();
        assertThat(pending.get("itemCount").asInt()).as("包里有两条").isEqualTo(2);
        assertThat(pending.get("escalated").size()).as("只有 SENSITIVE 那条上报到人").isEqualTo(1);
        assertThat(pending.get("items").size())
            .as("列表只列上报给人的条目（§5quater ④ 的 items 过滤）")
            .isEqualTo(1);
        assertThat(pending.get("items").get(0).get("toolName").asText()).isEqualTo("danger_probe");
        assertThat(pending.get("items").get(0).get("escalated").asBoolean()).isTrue();
        assertThat(limitedRuns).as("预裁定只是先记下，不是先执行（裁定 12）").hasValue(0);
        assertThat(sensitiveRuns).hasValue(0);

        HttpResponse<String> receipt =
            postPackageDecision(
                app.approvalPort(), packageId, "{\"approveAll\":true,\"by\":\"case-human\"}");
        assertThat(receipt.statusCode()).isEqualTo(200);
        JsonNode receiptBody = JSON.readTree(receipt.body());
        assertThat(receiptBody.get("state").asText()).isEqualTo("COMMITTED");
        JsonNode commit = receiptBody.get("commit");
        assertThat(commit.get("outcomes").size()).isEqualTo(2);
        assertThat(commit.get("outcomes").get(0).asText())
            .as("预裁定批准的那条照旧落定（人没勾它，但他批的是上报集合）")
            .isEqualTo("COMMITTED");
        assertThat(commit.get("outcomes").get(1).asText()).isEqualTo("COMMITTED");
        assertThat(commit.get("itemResults").get(0).get("reason").isNull())
            .as("成功条目没有理由可说 ⇒ null = 未说明理由（V14）")
            .isTrue();
        assertThat(limitedRuns).hasValue(1);
        assertThat(sensitiveRuns).hasValue(1);
        assertThat(turn.get(20, TimeUnit.SECONDS).stopReason()).isEqualTo(StopReason.FINISHED);
      } finally {
        turns.shutdownNow();
      }
    }
  }

  /**
   * ★ P2b：<b>人驳回上报的条目，撤不掉下级已经预裁定批准的那些</b>（§5quater ②「允许上一级修改下一级」要走级联那条正规路， 而不是"人不勾就没了"）。
   *
   * <p>判别性：把 {@code mergeWithEscalation} 里的"预批准 ∪ …"去掉 ⇒ 第一条去向会变成 {@code REJECTED}、{@code runs} 为 0
   * ⇒ 红。
   */
  @Test
  void humanRejectionDoesNotRevokePreApprovedItems() throws Exception {
    AtomicInteger limitedRuns = new AtomicInteger();
    AtomicInteger sensitiveRuns = new AtomicInteger();
    Path dataDir = tempDir.resolve("data");
    writeApprovalConfig(dataDir, 60);
    RecordingLlmClient llm = new RecordingLlmClient();
    llm.enqueue(twoToolCalls());
    llm.enqueue(LlmResponse.text("好"));
    BootConfig config = new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0);

    try (App app =
        App.start(
            config, llm, List.of(modeLimitedProbeTool(limitedRuns), probeTool(sensitiveRuns)))) {
      ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<TurnResult> turn = turns.submit(() -> app.runtime().chat("两条一起跑"));
        JsonNode pending = awaitPendingPackage(app.approvalPort(), Duration.ofSeconds(20));
        String packageId = pending.get("id").asText();

        HttpResponse<String> receipt =
            postPackageDecision(
                app.approvalPort(), packageId, "{\"rejectAll\":true,\"by\":\"case-human\"}");
        assertThat(receipt.statusCode()).isEqualTo(200);
        JsonNode commit = JSON.readTree(receipt.body()).get("commit");
        assertThat(commit.get("outcomes").get(0).asText())
            .as("预裁定批准的那条不受人的驳回影响")
            .isEqualTo("COMMITTED");
        assertThat(commit.get("outcomes").get(1).asText())
            .as("上报给人的那条被驳：零落盘")
            .isEqualTo("REJECTED");
        assertThat(commit.get("itemResults").get(1).get("reason").asText())
            .as("被驳条目的理由由包层填")
            .contains("未被裁决批准");
        assertThat(limitedRuns).as("预批准的跑了").hasValue(1);
        assertThat(sensitiveRuns).as("被驳的绝不跑").hasValue(0);
        assertThat(turn.get(20, TimeUnit.SECONDS).stopReason()).isEqualTo(StopReason.FINISHED);
      } finally {
        turns.shutdownNow();
      }
    }
  }

  /**
   * H7 的装配线版本：外部 MCP 客户端经 <b>App 的 stdio MCP 面</b>调一个 {@code Ask} 工具时，判定同样落到审批面（V8 的结构前提）， 且人从 HTTP
   * 面能答上——两处看到同一个 id。
   *
   * <p>MCP 侧的身份是 5 参重载的缺省调用者 {@code GUEST}（= 全体外部 MCP 客户端，桶粒度分不出实例）：人对它批"本会话"会被收窄为一次 ——"下次还问"是设计（S5
   * 身份穿透前不许泛化），回执必须如实回报 {@code once}。
   *
   * <p>判别性：把 {@code App.java} 里的 authorizer 换回不含协调器的 {@code ToolCallAuthorizer.standard()}（或把 5
   * 参重载换成 3 参），本用例的 {@code awaitPendingItem} 到点抛错 ⇒ 转红。
   *
   * <p>装配细节：MCP stdio 传输在构造期捕获 {@code System.in}/{@code System.out}，而 App 起的 MCP server 走的是默认 stdio
   * 传输 ⇒ 起 App 之前把这两个流换成管道、起完立刻还原（其余组件不写 stdout：日志走 stderr）。
   */
  @Test
  void mcpExposedToolCallGoesThroughTheSameApprovalFace() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    Path dataDir = tempDir.resolve("data");
    writeApprovalConfig(dataDir, 25);
    RecordingLlmClient llm = new RecordingLlmClient();
    BootConfig config = new BootConfig(0, dataDir, false, "", null, true, "127.0.0.1", 0);

    PrintStream originalOut = System.out;
    InputStream originalIn = System.in;
    PipedInputStream serverReads = new PipedInputStream();
    PipedOutputStream clientWrites = new PipedOutputStream(serverReads);
    PipedInputStream clientReads = new PipedInputStream();
    PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
    PrintStream protocolOut = new PrintStream(serverWrites, true, StandardCharsets.UTF_8);
    App app;
    System.setIn(serverReads);
    System.setOut(protocolOut);
    try {
      app = App.start(config, llm, List.of(probeTool(runs)));
    } finally {
      System.setIn(originalIn);
      System.setOut(originalOut);
    }

    try (app;
        McpSyncClient client =
            McpClient.sync(
                    new PipeMcpClientTransport(
                        McpJsonDefaults.getMapper(), clientReads, clientWrites))
                .build()) {
      client.initialize();
      ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<McpSchema.CallToolResult> call =
            calls.submit(
                () ->
                    client.callTool(
                        new McpSchema.CallToolRequest("danger_probe", Map.of("token", SECRET))));

        JsonNode item = awaitPendingItem(app.approvalPort(), Duration.ofSeconds(20));
        assertThat(item.get("tool").asText()).isEqualTo("danger_probe");
        assertThat(item.get("classKey").asText()).isEqualTo(CLASS_KEY);
        assertThat(item.toString()).doesNotContain(SECRET);
        assertThat(runs).as("人还没答，MCP 这条路上的工具体也不能先跑").hasValue(0);

        HttpResponse<String> receipt =
            postDecision(
                app.approvalPort(),
                item.get("id").asText(),
                "{\"decision\":\"approve\",\"scope\":\"session\"}");
        assertThat(receipt.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(receipt.body()).get("scope").asText())
            .as("GUEST 桶 = 全体外部 MCP 客户端（身份非唯一）⇒ 人给的「本会话」降级为一次")
            .isEqualTo("once");

        McpSchema.CallToolResult result = call.get(20, TimeUnit.SECONDS);
        assertThat(result.isError()).isFalse();
        assertThat(runs).hasValue(1);
      } finally {
        calls.shutdownNow();
      }
    }
  }

  // ---------- 工具与助手 ----------

  /** 从事件库轮询"该包的 decided 事件里那段 trail"（总线是异步镜像，落库有微小延迟）。 */
  private static JsonNode awaitDecidedTrail(Path dataDir, String packageId, Duration limit)
      throws Exception {
    Path db = dataDir.resolve("events.db");
    long deadline = System.currentTimeMillis() + limit.toMillis();
    JsonNode last = null;
    while (System.currentTimeMillis() < deadline) {
      if (Files.isRegularFile(db)) {
        try (SqliteEventStore store = SqliteEventStore.openReadOnly(db)) {
          for (var event :
              store.query(new EventQuery("", DecisionEventTypes.DECIDED, packageId, -1, 5))) {
            last = JSON.readTree(event.payload()).get("trail");
            if (last != null) {
              return last;
            }
          }
        }
      }
      Thread.sleep(50L);
    }
    throw new AssertionError("等不到 decided 事件里的 trail（等了 " + limit + "），最后拿到: " + last);
  }

  /** 自报 {@link ToolGate.Ask} 的桩工具（真实形态 = C 包的 bash）：体一旦执行就计数。 */
  private static AgentTool probeTool(AtomicInteger runs) {
    return new AgentTool() {
      @Override
      public String name() {
        return "danger_probe";
      }

      @Override
      public String description() {
        return "测试用的待审批工具";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of("token", Map.of("type", "string")));
      }

      @Override
      public ToolGate gate(ToolContext context) {
        return new ToolGate.Ask(CLASS_KEY, SUMMARY);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        runs.incrementAndGet();
        return ToolResult.ok("probe:ran");
      }
    };
  }

  /** 一条模型响应里两个工具调用（一个档位提升、一个敏感区）：用来验"一包两条、人只裁上报的那条"。 */
  private static LlmResponse twoToolCalls() {
    return new LlmResponse(
        LlmMessage.assistant(
            List.of(
                new ContentPart.ToolCall("call-1", "limited_probe", Map.of("token", SECRET)),
                new ContentPart.ToolCall("call-2", "danger_probe", Map.of("token", SECRET)))),
        "fake-model",
        0,
        0);
  }

  /**
   * 自报 {@link io.mosire.agentlib.approval.AskKind#MODE_LIMITED} 的桩工具（真实形态 = bash 在不完全权限档下的"提升门"）：
   * 它<b>可以</b>被逐级预裁定（与 SENSITIVE 的分水岭），体一旦执行就计数。
   */
  private static AgentTool modeLimitedProbeTool(AtomicInteger runs) {
    return new AgentTool() {
      @Override
      public String name() {
        return "limited_probe";
      }

      @Override
      public String description() {
        return "测试用的档位提升工具";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of("token", Map.of("type", "string")));
      }

      @Override
      public ToolGate gate(ToolContext context) {
        return new ToolGate.Ask(CLASS_KEY, SUMMARY, AskKind.MODE_LIMITED);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        runs.incrementAndGet();
        return ToolResult.ok("probe:ran");
      }
    };
  }

  /**
   * 决策面两条用例的装配参数：审批窗口取小值（"没人答"的失败路径有界）+ <b>命令档位钉成 {@code limited}</b>。
   *
   * <p><b>为什么必须钉档位</b>：包请求的 kind 是 {@code MODE_LIMITED}（装配接了上级判定明细缝），而档位<b>缺省 FULL</b> ⇒ 包会先落进
   * {@code SuperiorJudgeGate} 的"上级代批"那条路；决策面用例的 LLM 是脚本桩（响应只够喂主循环），判定调用必然以 {@code LlmException}
   * 失败并终局拒绝 ⇒ 包在到达人之前就 {@code EXPIRED}（2026-10-16 实测：该形状下两张面都轮询不到东西）。 钉成 limited 才是"这一包交给裁决者本人"的形状。
   */
  private static void writeDecisionConfig(Path dataDir, int timeoutSeconds) throws Exception {
    writeConfig(
        dataDir,
        "{\"approval\":{\"timeoutSeconds\":"
            + timeoutSeconds
            + "},\"commands\":{\"mode\":\"limited\"}}");
  }

  /** 审批装配参数（MCP 那条用例：那条路不走汇总包，档位保持缺省 FULL）：只写超时——HTTP 面缺省开、端口缺省 0。 */
  private static void writeApprovalConfig(Path dataDir, int timeoutSeconds) throws Exception {
    writeConfig(dataDir, "{\"approval\":{\"timeoutSeconds\":" + timeoutSeconds + "}}");
  }

  private static void writeConfig(Path dataDir, String json) throws Exception {
    Files.createDirectories(dataDir);
    Files.writeString(dataDir.resolve("config.json"), json, StandardCharsets.UTF_8);
  }

  /** 轮询审批面待裁决列表直到出现第一项；到点仍为空即判失败（把"没装配上"与"装配成两份登记表"一并抓出来）。 */
  private static JsonNode awaitPendingItem(int port, Duration limit) throws Exception {
    return awaitFirstRow(port, "/api/approvals", limit, "审批 HTTP 面上一直没有待裁决项");
  }

  /** 轮询决策面未决包列表直到出现第一个包；到点仍为空即判失败（"决策面没挂上"与"包没建成"一并抓出来）。 */
  private static JsonNode awaitPendingPackage(int port, Duration limit) throws Exception {
    return awaitFirstRow(port, DecisionPackagesHttpHandler.PATH, limit, "决策面上一直没有未决包");
  }

  /** 两张面共用的轮询：列表响应体里的 {@code pending} 数组出现第一行即返回（20ms 一次；到点用最后拿到的东西报错）。 */
  private static JsonNode awaitFirstRow(int port, String path, Duration limit, String what)
      throws Exception {
    long deadline = System.currentTimeMillis() + limit.toMillis();
    JsonNode rows = null;
    while (System.currentTimeMillis() < deadline) {
      rows = JSON.readTree(get(port, path).body()).get("pending");
      if (rows.size() > 0) {
        return rows.get(0);
      }
      Thread.sleep(20L);
    }
    throw new AssertionError(what + "（等了 " + limit + "），最后拿到: " + rows);
  }

  private static HttpResponse<String> get(int port, String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(base(port) + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }

  /** 决策面包详情里的状态（用于"管线侧到点收尾之后，包没被迟到路径改写"这条复核）。 */
  private static String packageState(int port, String packageId) throws Exception {
    HttpResponse<String> detail = get(port, DecisionPackagesHttpHandler.PATH + "/" + packageId);
    assertThat(detail.statusCode()).as("包详情必须查得到（终局包不淘汰）").isEqualTo(200);
    return JSON.readTree(detail.body()).get("state").asText();
  }

  /** 从决策面裁决一个包（body 三形态见 {@code DecisionPackagesHttpHandler}：approveAll / rejectAll / items）。 */
  private static HttpResponse<String> postPackageDecision(int port, String id, String body)
      throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create(base(port) + DecisionPackagesHttpHandler.PATH + "/" + id))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> postDecision(int port, String id, String body)
      throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(base(port) + "/api/approvals/" + id))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static String base(int port) {
    return "http://127.0.0.1:" + port;
  }

  /** 请求里的工具<b>成功侧</b>结果正文（受理形态走这一侧；失败信息在 {@code error}，见 {@link ContentPart.ToolResult} 契约）。 */
  private static List<String> toolContents(LlmRequest request) {
    List<String> contents = new ArrayList<>();
    for (var message : request.messages()) {
      for (ContentPart part : message.content()) {
        if (part instanceof ContentPart.ToolResult result && result.content() != null) {
          contents.add(result.content());
        }
      }
    }
    return contents;
  }

  /** 请求里的工具结果（失败信息在 {@code error} 字段，见 {@code ContentPart.ToolResult} 契约）。 */
  private static List<String> toolErrors(LlmRequest request) {
    List<String> errors = new ArrayList<>();
    for (var message : request.messages()) {
      for (ContentPart part : message.content()) {
        if (part instanceof ContentPart.ToolResult result && result.error() != null) {
          errors.add(result.error());
        }
      }
    }
    return errors;
  }

  /** 记录请求的脚本桩（本类的判据要读"模型第二次看到的是什么"）。 */
  private static final class RecordingLlmClient implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    void enqueue(LlmResponse response) {
      script.addLast(response);
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      requests.add(request);
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new LlmException("RecordingLlmClient 脚本已耗空");
      }
      return response;
    }
  }
}
