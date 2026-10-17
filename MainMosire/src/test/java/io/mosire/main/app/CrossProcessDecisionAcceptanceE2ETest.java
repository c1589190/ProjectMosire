package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalEventTypes;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionEventTypes;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.decision.DecisionApprovalBridge;
import io.mosire.brain.decision.DecisionHistoryTools;
import io.mosire.brain.decision.ParentSubagentDecisionLedger;
import io.mosire.brain.decision.SubmitDecisionPackageTools;
import io.mosire.brain.runtime.EventTypes;
import io.mosire.brain.subagent.SubagentInstance;
import io.mosire.brain.subagent.SubagentManager;
import io.mosire.main.decision.DecisionPackagesHttpHandler;
import java.lang.reflect.Field;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>P3c 跨进程决策包验收判据（RT 责任区）· 真子进程那几条</b>——按任务书清单逐条落实，<b>不是</b>按代码反推。
 *
 * <p>真进程是这几条判据唯一成立的地方：子体的工具目录全部来自父侧导出面（{@code McpToolAdapter} 不覆写 {@code gate()} ⇒ 缺省 {@code
 * ALLOW}），MCP 的 {@code tools/list} 也不带闸位 ⇒ <b>子体看不见 {@code Ask}</b>；"要不要裁决"只能由父侧在它那条必经链上判 （设计
 * §5sexies①），子体只认具名码 {@code DECISION_QUEUED}（④）。
 *
 * <p>本类覆盖的判据：
 *
 * <ol>
 *   <li><b>判据 1</b>：子体一轮 3 条敏感 ⇒ 子体库恰好 1 个包（{@code itemCount=3}）+ 父侧审批面恰好 1 条请求（包级 {@code
 *       classKey}）+ 裁决回灌 + <b>执行由子体发起</b>（父侧账上无第二次审批）+ 子体终局 {@code exitCode=0}；
 *   <li><b>判据 2</b>：{@code GET /api/decision-packages/{xp-…}} 在"正等人裁"窗口 ⇒ 200 逐条明文（与子体原始参数逐字一致）、
 *       {@code POST} ⇒ 404 {@code PACKAGE_NOT_FOUND}、不进 {@code pending()}、结算后同理 ⇒ 404；
 *   <li><b>判据 9</b>（★ 裁定 14）：无人答复 ⇒ 零执行、零落盘（真子进程里跑一遍）；
 *   <li><b>判据 11</b>：模板 {@code allowedTools} 缺 {@code submit_decision_package} ⇒ 行为逐字不变（逐条问人），且不出现
 *       "已记入本轮决策包却没人裁"；
 *   <li><b>判据 14</b>：无未决项的回合 ⇒ 不建包、零 {@code decision.package.*} 事件；
 *   <li><b>判据 15</b>：子体被杀 ⇒ 父侧队列清空、无残留（真进程里杀一次）。
 * </ol>
 *
 * <p>★ 与同模块 {@code ChildCrossProcessDecisionE2ETest}（P3d/P3e 的<b>证据载体</b>）的关系：本类是 P3c
 * 的<b>验收面</b>，两者各自独立 搭台（互不引用），本类另加了"执行身份 = 子体实例"与"杀进程 ⇒ 清队列"两条它们没有的判据。
 */
class CrossProcessDecisionAcceptanceE2ETest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 父侧工具每次执行时看到的参数（证"执行真的发生了"与"放行标记没漏进工具"）。 */
  private final List<Map<String, Object>> executed = new CopyOnWriteArrayList<>();

  /** 父侧工具每次执行时链上绑定的身份（证"执行由子体发起"）。 */
  private final List<String> executedBy = new CopyOnWriteArrayList<>();

  @TempDir Path tempDir;

  // ============================================================================================
  // 判据 1：真子进程一轮 3 条 ⇒ 两侧各恰好一个"包"
  // ============================================================================================

  @Test
  void c1_aRealChildRoundOfThreeSensitiveCallsIsOnePackageOneRequestAndTheChildExecutes()
      throws Exception {
    Path dataDir = tempDir.resolve("data");
    App app =
        startProber(
            dataDir, "prober", proberScript(), List.of(SubmitDecisionPackageTools.NAME), 60);
    try (app) {
      app.runtime().chat("派一个会点三次敏感工具的探针子 Agent");
      SubagentInstance child = awaitChild(app, i -> "prober".equals(i.templateId()));
      Path childEvents = childEvents(dataDir, child.instanceId());

      // ---- 父侧审批面：恰好那一条包级请求（不是三条逐条请求）----
      JsonNode request = awaitPendingItem(app.approvalPort(), Duration.ofSeconds(30));
      assertThat(request.get("classKey").asText())
          .as("★ 一条包 = 一条请求（classKey 是包级冻结字面量）")
          .isEqualTo(DecisionApprovalBridge.CLASS_KEY);
      assertThat(executed).as("人还没答：三条一条都不许跑").isEmpty();

      // ---- 人批准这一包（一次）----
      assertThat(
              postDecision(
                      app.approvalPort(),
                      request.get("id").asText(),
                      "{\"decision\":\"approve\",\"scope\":\"once\"}")
                  .statusCode())
          .isEqualTo(200);

      // ---- 子体跑完（自己留下终局记录、退出码 0）----
      awaitTerminalRecord(childEvents, child.instanceId());
      Map<String, Object> parentTerminal = awaitParentTerminal(dataDir, child.instanceId());
      assertThat(parentTerminal)
          .as("★ 子体终局 = finished / FINISHED / exitCode=0")
          .containsEntry("action", "finished")
          .containsEntry("stopReason", "FINISHED")
          .containsEntry("exitCode", 0);

      // ---- ① 子体库：恰好一个包、三条、走完 proposed → decided → committed ----
      assertThat(eventsOf(childEvents, DecisionEventTypes.PROPOSED)).as("★ 子体库恰好 1 个包").hasSize(1);
      assertThat(eventsOf(childEvents, DecisionEventTypes.DECIDED)).hasSize(1);
      assertThat(eventsOf(childEvents, DecisionEventTypes.COMMITTED)).hasSize(1);
      assertThat(eventsOf(childEvents, DecisionEventTypes.EXPIRED)).isEmpty();
      assertThat(eventsOf(childEvents, DecisionEventTypes.CONFLICTED)).isEmpty();
      Map<String, Object> committed =
          payloadMap(eventsOf(childEvents, DecisionEventTypes.COMMITTED).get(0));
      assertThat(committed).containsEntry("itemCount", 3).containsEntry("approvedCount", 3);
      assertThat(committed.get("toolNames"))
          .as("一个包收了三条（不是三个包）")
          .isEqualTo(List.of("sensitive_probe", "sensitive_probe", "sensitive_probe"));

      // ---- ★ 执行由子体发起：三条被批条目各执行一次、参数无放行标记、链上身份 = 这台子体 ----
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
      assertThat(executedBy).as("★ 执行发起者 = 子体实例（父侧不替它执行，红线 R7）").containsOnly(child.instanceId());

      // ---- ③ 父侧账：审批面上恰好一条请求，且不是逐条那条（不再有第二次审批）----
      List<Event> requests = eventsOf(dataDir.resolve("events.db"), ApprovalEventTypes.REQUESTED);
      assertThat(requests).as("★ 一轮三条敏感调用 ⇒ 父侧恰好一条审批请求").hasSize(1);
      assertThat(payload(requests.get(0), "classKey")).isEqualTo(DecisionApprovalBridge.CLASS_KEY);
      assertThat(requests)
          .as("★ 逐条问人那条路不再被走到（classKey = probe:ask 一条都没有）")
          .noneMatch(e -> "probe:ask".equals(payload(e, "classKey")));
    }
  }

  // ============================================================================================
  // 判据 2：在途明文面（正等人裁的窗口里可读，结算即消失，且是只读面）
  // ============================================================================================

  @Test
  void c2_theInFlightPlaintextFaceIsReadableWhileWaitingAndGoneAfterSettlement() throws Exception {
    Path dataDir = tempDir.resolve("data");
    App app =
        startProber(
            dataDir, "prober", proberScript(), List.of(SubmitDecisionPackageTools.NAME), 60);
    try (app) {
      app.runtime().chat("派一个会点三次敏感工具的探针子 Agent");
      awaitChild(app, i -> "prober".equals(i.templateId()));
      JsonNode request = awaitPendingItem(app.approvalPort(), Duration.ofSeconds(30));

      // ★ 包 id 从"人看到的那一行"里取（人据此到决策面看全文）
      String packageId = crossProcessPackageIdOf(request);
      HttpResponse<String> detail =
          get(app.approvalPort(), DecisionPackagesHttpHandler.PATH + "/" + packageId);
      assertThat(detail.statusCode()).as("★ 正等人裁窗口里，决策面按 xp-… 读得到在途的跨进程临时包").isEqualTo(200);
      JsonNode body = JSON.readTree(detail.body());
      assertThat(body.get("id").asText()).isEqualTo(packageId);
      assertThat(body.get("itemCount").asInt()).isEqualTo(3);
      assertThat(body.get("state").asText()).isEqualTo("PROPOSED");
      assertThat(body.get("decision").isNull()).as("还没人裁 ⇒ decision 为 null").isTrue();
      List<Integer> indexes = new ArrayList<>();
      for (JsonNode item : body.get("items")) {
        indexes.add(item.get("index").asInt());
        assertThat(item.get("toolName").asText()).isEqualTo("sensitive_probe");
        // ★ 裁定 15 ①：**默认 = 概览**——明文（args）一个字都不在这里，摘要也不在（它就是截断后的参数值）
        assertThat(item.has("args")).as("默认概览不含明文").isFalse();
        assertThat(item.has("summary")).as("默认概览连人读摘要都不给").isFalse();
        assertThat(item.get("overview").asText())
            .as("概览给的是「哪个工具、几个参数」")
            .contains("sensitive_probe");
      }
      assertThat(indexes).as("逐条 index 连续（决策面按序号逐条列）").containsExactly(0, 1, 2);

      // ★ 只有显式 full=1 才给明文（并且落一条 detail_read 审计）——两档查看的真子进程证据
      List<Map<String, Object>> plaintext = new ArrayList<>();
      for (int index : indexes) {
        HttpResponse<String> full =
            get(
                app.approvalPort(),
                DecisionPackagesHttpHandler.PATH + "/" + packageId + "/items/" + index + "?full=1");
        assertThat(full.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> args =
            JSON.convertValue(JSON.readTree(full.body()).get("args"), Map.class);
        plaintext.add(args);
      }
      assertThat(plaintext)
          .as("★ full 档的逐条明文与子体的原始参数逐字一致（父侧自己收到的那一份）")
          .containsExactly(Map.of("text", "一"), Map.of("text", "二"), Map.of("text", "三"));
      // 事件落库是同步的，但读口在另一个连接上 ⇒ 给一小段收敛窗（判据是"3 条"，不是"立刻 3 条"）
      long auditDeadline = System.currentTimeMillis() + 5_000L;
      List<Event> audits = List.of();
      while (System.currentTimeMillis() < auditDeadline) {
        audits = eventsOf(dataDir.resolve("events.db"), DecisionEventTypes.DETAIL_READ);
        if (audits.size() >= 3) {
          break;
        }
        Thread.sleep(20L);
      }
      assertThat(audits)
          .as("★ 每一次 full 读取都落一条 decision.package.detail_read 审计（读了 3 条 ⇒ 3 条）")
          .hasSize(3);
      for (Event audit : audits) {
        assertThat(payload(audit, "packageId")).isEqualTo(packageId);
        assertThat(String.valueOf(payload(audit, "by"))).as("审计记下'谁读的'").isNotBlank();
        assertThat(audit.payload())
            .as("★ 审计只记谁/哪个包/哪些 index——绝不记明文")
            .doesNotContain("一")
            .doesNotContain("二")
            .doesNotContain("三");
      }

      // ★ 只读纪律：在途包**不进** pending() 列表、不落父侧历史（不变量 1：包是唯一决策单元，临时包只是门面）
      assertThat(
              JSON.readTree(get(app.approvalPort(), DecisionPackagesHttpHandler.PATH).body())
                  .get("pending"))
          .as("★ 在途包不进 pending() 列表（不变量 1）")
          .isEmpty();

      // ★★ 裁定 15 ②（2026-10-17 落地）：在途包**接受条目级表态**——"看一条、裁一条"这条链在真子进程场景里同样成立
      //    （父侧那个在途口同时是 InFlightDecisionActions）。它仍然只是"一次裁决的分段表达"：不建第二个包、不落父侧历史。
      HttpResponse<String> voted =
          postJson(
              app.approvalPort(),
              DecisionPackagesHttpHandler.PATH + "/" + packageId + "/items/0",
              "{\"verdict\":\"approve\",\"by\":\"用例：人\"}");
      assertThat(voted.statusCode()).as("★ 在途包收条目级表态（裁定 15 ②）").isEqualTo(200);
      assertThat(
              JSON.readTree(
                      get(app.approvalPort(), DecisionPackagesHttpHandler.PATH + "/" + packageId)
                          .body())
                  .get("state")
                  .asText())
          .as("★ 逐条表态只是累积（还没全表态 ⇒ DECIDING），包不终结")
          .isEqualTo("DECIDING");
      assertThat(
              JSON.readTree(get(app.approvalPort(), DecisionPackagesHttpHandler.PATH).body())
                  .get("pending"))
          .as("★ 表过态的临时包仍然不进 pending()（不变量 1 不变）")
          .isEmpty();

      // ---- 结算 ⇒ 同一个 id 再查是 404 ----
      assertThat(
              postDecision(
                      app.approvalPort(),
                      request.get("id").asText(),
                      "{\"decision\":\"approve\",\"scope\":\"once\"}")
                  .statusCode())
          .isEqualTo(200);
      await(
          () -> {
            try {
              return get(app.approvalPort(), DecisionPackagesHttpHandler.PATH + "/" + packageId)
                      .statusCode()
                  == 404;
            } catch (Exception notYet) {
              return false;
            }
          },
          30_000,
          "结算后在途面应当消失（同 id ⇒ 404）");
      assertThat(
              get(app.approvalPort(), DecisionPackagesHttpHandler.PATH + "/" + packageId)
                  .statusCode())
          .as("★ 结算完即消失：在途面没有变成第二份历史")
          .isEqualTo(404);
    }
  }

  // ============================================================================================
  // 裁定 15（条目级操作 + 两段式）· 真子进程里跑一遍：分段表态 ⇒ 一个包、不终结；commit ⇒ 恰好执行一次
  // ============================================================================================

  /**
   * ★ <b>分段逐条表态（≥2 次）⇒ 只有一个包、不终结；全表态 ⇒ DECIDED；显式 commit ⇒ COMMITTED 且被批子集恰好执行一次</b> （裁定 15 ②③
   * 的正面判据）。重复 {@code commit} ⇒ <b>幂等</b>（不重复执行）。
   *
   * <p>它同时钉住"两段式没有被绕开"：全表态之后、下执行命令之前，三条探针**一次都不许跑**（表态 ≠ 执行）。 判别力：让表态顺带执行（回到裁定 15 之前"裁决即执行"）⇒ 那一处
   * {@code executed} 立刻非空、用例红。
   */
  @Test
  void c16_itemVotesAccumulateInOnePackageAndOnlyCommitExecutes() throws Exception {
    Path dataDir = tempDir.resolve("data");
    App app =
        startProber(
            dataDir,
            "prober",
            proberScript(),
            List.of(SubmitDecisionPackageTools.NAME),
            60,
            HUMAN_WINDOW_MS);
    try (app) {
      app.runtime().chat("派一个会点三次敏感工具的探针子 Agent");
      SubagentInstance child = awaitChild(app, i -> "prober".equals(i.templateId()));
      Path childEvents = childEvents(dataDir, child.instanceId());
      JsonNode request = awaitPendingItem(app.approvalPort(), Duration.ofSeconds(30));
      String packageId = crossProcessPackageIdOf(request);

      // ---- 一条都还没表态就下执行命令 ⇒ 具名拒（在途包那条 NO_ITEM_DECIDED 守卫）----
      HttpResponse<String> nothingApproved =
          postJson(
              app.approvalPort(),
              DecisionPackagesHttpHandler.PATH + "/" + packageId + "/commit",
              "{\"by\":\"用例：人\"}");
      assertThat(nothingApproved.statusCode())
          .as("★ 没有任何『明确同意』就下执行命令 ⇒ 具名拒（不是 200，也不是 500）")
          .isEqualTo(400);
      assertThat(JSON.readTree(nothingApproved.body()).get("code").asText())
          .as("拒答带具名码（不靠文案）")
          .isEqualTo(DecisionPackages.NO_ITEM_DECIDED);
      assertThat(executed).as("被拒的执行命令 ⇒ 零执行").isEmpty();

      // ---- 逐条表态两次：包始终只有一个、状态累积到 DECIDING、一条都没执行 ----
      for (int index = 0; index < 2; index++) {
        HttpResponse<String> voted =
            postJson(
                app.approvalPort(),
                DecisionPackagesHttpHandler.PATH + "/" + packageId + "/items/" + index,
                "{\"verdict\":\"approve\",\"by\":\"用例：人\"}");
        assertThat(voted.statusCode()).as("第 %d 条表态被受理", index).isEqualTo(200);
      }
      JsonNode afterTwo =
          JSON.readTree(
              get(app.approvalPort(), DecisionPackagesHttpHandler.PATH + "/" + packageId).body());
      assertThat(afterTwo.get("state").asText())
          .as("★ 只表了两条 ⇒ DECIDING（裁决累积中，包不终结）")
          .isEqualTo("DECIDING");
      assertThat(afterTwo.get("itemCount").asInt()).as("★ 分段表态不产生第二个包").isEqualTo(3);
      assertThat(executed).as("★ 表态 ≠ 执行：一条都还没跑").isEmpty();

      // ★ 口径说明（不写断言）：在途包上"表了两条就下执行命令"是**合法**的——那正是"我就要这两条"，
      //   未表态的按裁定 14 = 拒绝；这里不这么走，是为了把"全表态 ⇒ DECIDED ⇒ commit"这条主链走完整。

      // ---- 第三条表态 ⇒ 包自动进 DECIDED（还没执行）----
      assertThat(
              postJson(
                      app.approvalPort(),
                      DecisionPackagesHttpHandler.PATH + "/" + packageId + "/items/2",
                      "{\"verdict\":\"approve\",\"by\":\"用例：人\"}")
                  .statusCode())
          .isEqualTo(200);
      assertThat(
              JSON.readTree(
                      get(app.approvalPort(), DecisionPackagesHttpHandler.PATH + "/" + packageId)
                          .body())
                  .get("state")
                  .asText())
          .as("★ 全部条目都表了态 ⇒ DECIDED（等同终结，但还没执行）")
          .isEqualTo("DECIDED");
      assertThat(executed).as("★ DECIDED 但没下执行命令 ⇒ 仍然零执行").isEmpty();

      // ---- 显式下执行命令 ⇒ COMMITTED 且被批子集恰好执行一次 ----
      assertThat(
              postJson(
                      app.approvalPort(),
                      DecisionPackagesHttpHandler.PATH + "/" + packageId + "/commit",
                      "{\"by\":\"用例：人\"}")
                  .statusCode())
          .isEqualTo(200);
      awaitTerminalRecord(childEvents, child.instanceId());
      awaitParentTerminal(dataDir, child.instanceId());

      assertThat(executed).as("★ 执行命令之后：三条被批条目各执行一次（恰好一次）").hasSize(3);
      assertThat(executed)
          .extracting(row -> row.get("text"))
          .containsExactlyInAnyOrder("一", "二", "三");
      assertThat(eventsOf(childEvents, DecisionEventTypes.PROPOSED)).as("★ 全程只有一个包").hasSize(1);
      assertThat(eventsOf(childEvents, DecisionEventTypes.DECIDED)).hasSize(1);
      assertThat(eventsOf(childEvents, DecisionEventTypes.COMMITTED)).hasSize(1);

      // ---- 重复下执行命令 ⇒ 幂等：不再执行第二次 ----
      List<Map<String, Object>> afterFirstCommit = List.copyOf(executed);
      HttpResponse<String> again =
          postJson(
              app.approvalPort(),
              DecisionPackagesHttpHandler.PATH + "/" + packageId + "/commit",
              "{\"by\":\"用例：人\"}");
      assertThat(again.statusCode()).as("重复执行命令不报错（幂等）").isEqualTo(200);
      Thread.sleep(300L);
      assertThat(executed).as("★ 重复 commit ⇒ 不重复执行（幂等）").isEqualTo(afterFirstCommit);
      assertThat(eventsOf(childEvents, DecisionEventTypes.COMMITTED))
          .as("★ 也不会多出一条 committed 事件")
          .hasSize(1);
    }
  }

  /**
   * ★ <b>子体侧读回（裁定 15 ⑤）</b>：子体在<b>下一轮</b>用本地的 {@code decision_history} 查<b>自己的</b>上一轮包——
   * 默认概览（<b>无明文</b>）、{@code full} 才给明文。
   *
   * <p><b>为什么在真子进程 E2E 里跑</b>：这个工具读的是<b>子体自己的</b> {@code events.db}（明文来自它自己那条 {@code
   * tool.call}），与父侧那份同名工具（读父库）是两回事。判据落在"子体库里那个包 + 那份明文确实是它自己进程的"。
   *
   * <p><b>怎么拿到子体那一份工具</b>：子体进程只跑一轮就退出（本单不改它的生命周期），所以这里用<b>同一个生产件</b> {@link
   * DecisionHistoryTools#localTool(Path, String)} 指着<b>那个真子进程留下的库与实例 id</b>取数——
   * 与子体进程内注册的那一份逐字同源（同一工厂、同一个库路径、同一个 id）。它<b>不</b>复制工具逻辑。
   */
  @Test
  void c17_theChildCanReadBackItsOwnLastPackageFromItsOwnDatabase() throws Exception {
    Path dataDir = tempDir.resolve("data");
    App app =
        startProber(
            dataDir,
            "prober",
            proberScript(),
            List.of(SubmitDecisionPackageTools.NAME),
            60,
            HUMAN_WINDOW_MS);
    try (app) {
      app.runtime().chat("派一个会点三次敏感工具的探针子 Agent");
      SubagentInstance child = awaitChild(app, i -> "prober".equals(i.templateId()));
      Path childEvents = childEvents(dataDir, child.instanceId());
      JsonNode request = awaitPendingItem(app.approvalPort(), Duration.ofSeconds(30));
      String packageId = crossProcessPackageIdOf(request);
      // 人批准（= 执行命令）⇒ 子体跑完、在自己库里落 proposed/decided/committed
      postDecision(
          app.approvalPort(),
          request.get("id").asText(),
          "{\"decision\":\"approve\",\"scope\":\"once\"}");
      awaitTerminalRecord(childEvents, child.instanceId());
      awaitParentTerminal(dataDir, child.instanceId());

      List<Event> proposedInChild = eventsOf(childEvents, DecisionEventTypes.PROPOSED);
      assertThat(proposedInChild).as("★ 子体库恰好一个包（它自己的）").hasSize(1);
      // ★ 两个 id 不是一回事：父侧在途临时包是 xp-…（父侧门面），子体自己那个包是它 propose 时拿到的 id
      //   （= 它提交时用的 requestId）。读回子体自己的包要用**后者**。
      String childPackageId = String.valueOf(payload(proposedInChild.get(0), "packageId"));
      assertThat(childPackageId).isNotBlank().isNotEqualTo(packageId);

      // ★ 下一轮的读回：同一个生产件（子体进程内注册的那一份），指着真子进程留下的库与实例 id
      AgentTool localHistory = DecisionHistoryTools.localTool(childEvents, child.instanceId());
      String overview = callTool(localHistory, Map.of("packageId", childPackageId));
      assertThat(overview)
          .as("★ 概览：看得到包与逐条 index/toolName")
          .contains(childPackageId)
          .contains("sensitive_probe")
          .contains("OVERVIEW");
      assertThat(overview)
          .as("★ 概览不含明文（用户裁定 15 ①：除非要求 full）")
          .doesNotContain("\"一\"")
          .doesNotContain("\"二\"")
          .doesNotContain("\"三\"");

      String full =
          callTool(localHistory, Map.of("packageId", childPackageId, "index", 0, "full", true));
      assertThat(full).as("★ full=true：这一条的明文读得到（明文来自子体自己的 tool.call 事件）").contains("一");
      assertThat(full).as("full 档自报明细档位").contains("FULL");
    }
  }

  /** 直接调一次工具（离线、无网络）：把返回文本取出来给断言用。 */
  private static String callTool(AgentTool tool, Map<String, Object> arguments) {
    ToolContext context =
        new ToolContext(
            io.mosire.agentlib.permission.AccessToken.DEFAULT,
            io.mosire.agentlib.permission.AgentPermissionSet.system(),
            Map.of(),
            arguments,
            null,
            null);
    ToolResult result = tool.execute(context);
    return result.message();
  }

  // ============================================================================================
  // 判据 9（★ 裁定 14）：无人答复 ⇒ 零执行、零落盘（真子进程里跑一遍）
  // ============================================================================================

  @Test
  void c9_anUnansweredPackageIsZeroExecutionAndZeroPersistence() throws Exception {
    Path dataDir = tempDir.resolve("data");
    // 审批窗口 2s：本用例要的正是"窗口里没人答" ⇒ 到点 ⇒ 静默 = 拒绝
    App app =
        startProber(dataDir, "prober", proberScript(), List.of(SubmitDecisionPackageTools.NAME), 2);
    try (app) {
      app.runtime().chat("派一个会点三次敏感工具的探针子 Agent");
      SubagentInstance child = awaitChild(app, i -> "prober".equals(i.templateId()));
      Path childEvents = childEvents(dataDir, child.instanceId());
      awaitTerminalRecord(childEvents, child.instanceId());
      awaitParentTerminal(dataDir, child.instanceId());

      assertThat(executed).as("★ 静默 = 拒绝：三条一条都没执行（零执行）").isEmpty();

      // 包被受理过（一次没有通过的裁决该进历史），但没有任何落盘
      assertThat(eventsOf(childEvents, DecisionEventTypes.PROPOSED)).hasSize(1);
      assertThat(eventsOf(childEvents, DecisionEventTypes.DECIDED)).hasSize(1);
      assertThat(eventsOf(childEvents, DecisionEventTypes.COMMITTED))
          .as("★ 零落盘：一条 committed 都没有")
          .isEmpty();
      assertThat(eventsOf(childEvents, DecisionEventTypes.CONFLICTED)).isEmpty();
      Map<String, Object> decided =
          payloadMap(eventsOf(childEvents, DecisionEventTypes.DECIDED).get(0));
      assertThat(decided).containsEntry("approvedCount", 0);

      List<Event> requests = eventsOf(dataDir.resolve("events.db"), ApprovalEventTypes.REQUESTED);
      assertThat(requests).as("★ 问过且只问过一次（'没人答'的形状）——不存在第二次审批").hasSize(1);
      assertThat(payload(requests.get(0), "classKey")).isEqualTo(DecisionApprovalBridge.CLASS_KEY);
    }
  }

  // ============================================================================================
  // 判据 11：模板缺 submit_decision_package ⇒ 行为逐字不变（逐条问人）+ 不出现"没人裁"
  // ============================================================================================

  @Test
  void c11_aChildWithoutTheSubmitSeamKeepsPerCallApprovalAndBooksNoPackage() throws Exception {
    Path dataDir = tempDir.resolve("data");
    // 模板 allowedTools 只有 sensitive_probe：子体那一侧 available() 判不在 ⇒ 不绑队列
    App app =
        startProber(
            dataDir,
            "blind",
            List.of(step("sensitive_probe", Map.of("text", "探测一次")), step(null, "完成")),
            List.of(),
            2);
    try (app) {
      app.runtime().chat("派一个会点敏感工具的探针子 Agent");
      SubagentInstance child = awaitChild(app, i -> "blind".equals(i.templateId()));
      Path childEvents = childEvents(dataDir, child.instanceId());
      awaitEvent(childEvents, EventTypes.TOOL_RESULT, "tool", "sensitive_probe");
      awaitTerminalRecord(childEvents, child.instanceId());

      assertThat(executed).as("窗口里没人答 ⇒ 父侧 fail-closed 拒 ⇒ 工具体一次都没执行").isEmpty();
      assertThat(eventsOf(childEvents, EventTypes.TOOL_RESULT))
          .filteredOn(e -> "sensitive_probe".equals(payload(e, "tool")))
          .anySatisfy(
              e ->
                  assertThat(String.valueOf(payload(e, "message")))
                      .as("★ 没有缝 ⇒ 退回既有那一跳：父侧逐条问人（不是'已记入决策包'）")
                      .contains("审批未放行")
                      .contains("class=probe:ask")
                      .doesNotContain("已记入本轮决策包"));

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
        assertThat(eventsOf(childEvents, type)).as("★ 没有缝 ⇒ 子体库一个决策包事件都不该有（%s）", type).isEmpty();
      }
    }
  }

  // ============================================================================================
  // 判据 14：无未决项的回合 ⇒ 不建包、不提交、零 decision.package.* 事件（零副作用）
  // ============================================================================================

  @Test
  void c14_aTurnWithoutPendingItemsLeavesZeroDecisionEvents() throws Exception {
    Path dataDir = tempDir.resolve("data");
    // 缝在（allowedTools 含提交口），但这一轮的脚本只点一个"不问人"的工具 ⇒ 本轮零未决项
    Path templates = tempDir.resolve("templates");
    Files.createDirectories(templates);
    writeTemplate(
        templates,
        "quiet",
        List.of("quiet_probe", SubmitDecisionPackageTools.NAME),
        List.of(step("quiet_probe", Map.of("text", "不许问人")), step(null, "完成")));
    App app = start(templates, dataDir, List.of(quietProbe()), 60);
    try (app) {
      app.runtime().chat("派一个不点敏感工具的探针子 Agent");
      SubagentInstance child = awaitChild(app, i -> "quiet".equals(i.templateId()));
      Path childEvents = childEvents(dataDir, child.instanceId());
      awaitTerminalRecord(childEvents, child.instanceId());
      Map<String, Object> terminal = awaitParentTerminal(dataDir, child.instanceId());
      assertThat(terminal).containsEntry("action", "finished").containsEntry("exitCode", 0);

      for (String type :
          List.of(
              DecisionEventTypes.PROPOSED,
              DecisionEventTypes.DECIDED,
              DecisionEventTypes.COMMITTED,
              DecisionEventTypes.CONFLICTED,
              DecisionEventTypes.EXPIRED)) {
        assertThat(eventsOf(childEvents, type))
            .as("★ 无未决项的回合：不建包、不发任何 decision.package.* 事件（%s）", type)
            .isEmpty();
      }
      assertThat(eventsOf(dataDir.resolve("events.db"), ApprovalEventTypes.REQUESTED))
          .as("★ 不提交、不问人：父侧审批面零请求")
          .isEmpty();
    }
  }

  // ============================================================================================
  // 判据 15：子体被杀 ⇒ 父侧队列清空、无残留（真进程里杀一次）
  // ============================================================================================

  @Test
  void c15_killingTheChildClearsTheParentTurnQueueWithoutResidue() throws Exception {
    Path dataDir = tempDir.resolve("data");
    CountDownLatch hold = new CountDownLatch(1);
    List<String> heldFor = new CopyOnWriteArrayList<>();
    AgentTool holdProbe =
        new AgentTool() {
          @Override
          public String name() {
            return "hold_probe";
          }

          @Override
          public String description() {
            return "把子体的回合按住（用例桩：父侧队列里此刻还压着 3 条待裁条目）";
          }

          @Override
          public Map<String, Object> jsonSchema() {
            return Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")));
          }

          @Override
          public ToolResult execute(ToolContext context) {
            heldFor.add(context.identity() == null ? "unknown" : context.identity().instanceId());
            try {
              hold.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
            return ToolResult.ok("已放行（用例桩）");
          }
        };
    Path templates = tempDir.resolve("templates");
    Files.createDirectories(templates);
    writeTemplate(
        templates,
        "holder",
        List.of("sensitive_probe", SubmitDecisionPackageTools.NAME, "hold_probe"),
        List.of(
            step("sensitive_probe", Map.of("text", "一")),
            step("sensitive_probe", Map.of("text", "二")),
            step("sensitive_probe", Map.of("text", "三")),
            step("hold_probe", Map.of("text", "按住")),
            step(null, "完成")));
    List<AgentTool> extras = new ArrayList<>();
    extras.add(sensitiveProbe());
    extras.add(holdProbe);

    // ★ 判据 15 的证据面：父侧队列簿是 App.start 里的**局部变量**（测试拿不到它，也不许为它加生产读口）
    //   ⇒ 用它在 recycle 时打的那一行 INFO 作证（"父侧队列清空"这条判据的可观测面）。
    List<String> ledgerLogs = new CopyOnWriteArrayList<>();
    AbstractAppender capture = ledgerLogAppender(ledgerLogs);
    org.apache.logging.log4j.core.Logger ledgerLogger =
        (org.apache.logging.log4j.core.Logger)
            LogManager.getLogger(ParentSubagentDecisionLedger.class);
    ledgerLogger.addAppender(capture);

    App app = start(templates, dataDir, extras, 60);
    try (app) {
      app.runtime().chat("派一个会点三次敏感工具的探针子 Agent");
      SubagentInstance child = awaitChild(app, i -> "holder".equals(i.templateId()));
      Path childEvents = childEvents(dataDir, child.instanceId());
      // ★ 判据 10 的顺带保证：这个同步点**不读文案**（只数"三条敏感调用都没执行成功"这个结构事实）
      await(() -> heldFor.contains(child.instanceId()), 30_000, "子体的回合被 hold_probe 按住");
      assertThat(
              eventsOf(childEvents, EventTypes.TOOL_RESULT).stream()
                  .filter(e -> "sensitive_probe".equals(payload(e, "tool")))
                  .filter(e -> Boolean.FALSE.equals(payload(e, "ok")))
                  .count())
          .as("三条敏感调用都已被父侧记进本轮队列（本轮不执行 ⇒ 子体侧看到三条失败结果）")
          .isEqualTo(3L);

      assertThat(managerOf(app).kill(child.instanceId())).as("真的发起了终止").isTrue();

      await(
          () ->
              ledgerLogs.stream()
                  .anyMatch(line -> line.contains(child.instanceId()) && line.contains("清掉它的本轮队列")),
          30_000,
          "★ 子体被杀 ⇒ 父侧队列清空（终局 agent.lifecycle ⇒ recycle 的那一行）");
      assertThat(ledgerLogs)
          .as("★ 无残留：清的时候队列里正是那 3 条、且放行条为 0")
          .anySatisfy(
              line -> {
                assertThat(line).contains(child.instanceId());
                assertThat(line).contains("队列条目=3");
                assertThat(line).contains("放行条=0");
              });
      assertThat(eventsOf(dataDir.resolve("events.db"), EventTypes.AGENT_LIFECYCLE))
          .filteredOn(e -> child.instanceId().equals(e.agent()))
          .anySatisfy(e -> assertThat(payload(e, "action")).isEqualTo("killed"));
    } finally {
      hold.countDown();
      ledgerLogger.removeAppender(capture);
      capture.stop();
    }
  }

  // ============================================================================================
  // 夹具
  // ============================================================================================

  private static List<Map<String, Object>> proberScript() {
    return List.of(
        step("sensitive_probe", Map.of("text", "一")),
        step("sensitive_probe", Map.of("text", "二")),
        step("sensitive_probe", Map.of("text", "三")),
        step(null, "完成"));
  }

  /** 起一个真 App：模板目录 + 数据目录 + 审批窗口；额外工具 = 探针（记录每次执行）。 */
  private App startProber(
      Path dataDir,
      String templateId,
      List<Map<String, Object>> script,
      List<String> extraAllowed,
      int approvalSeconds)
      throws Exception {
    return startProber(
        dataDir, templateId, script, extraAllowed, approvalSeconds, DECISION_DEADLINE_MS);
  }

  private App startProber(
      Path dataDir,
      String templateId,
      List<Map<String, Object>> script,
      List<String> extraAllowed,
      int approvalSeconds,
      long decisionDeadlineMs)
      throws Exception {
    Path templates = tempDir.resolve("templates");
    Files.createDirectories(templates);
    List<String> allowed = new ArrayList<>();
    allowed.add("sensitive_probe");
    allowed.addAll(extraAllowed);
    writeTemplate(templates, templateId, allowed, script);
    return start(
        templates, dataDir, List.of(sensitiveProbe()), approvalSeconds, decisionDeadlineMs);
  }

  /**
   * 决策链路的两个窗（毫秒，★ 用户指令「肯定在测试环境都改成毫秒级啊」+ AGENTS §四 第 11 条）： {@code decision.package.deadlineMs} =
   * 等人答复（同时是包 deadline）/ {@code decision.commit.waitMs} = 等执行命令。 按缺省（30 分钟 / 30
   * 秒）跑的话，本类每一条"没人答"的用例都要白等分钟级——真子进程 E2E 会慢到没法当门禁用。
   */
  private static final long DECISION_DEADLINE_MS = 1_500L;

  private static final long COMMIT_WAIT_MS = 400L;

  /**
   * 人在真子进程 E2E 里要"逐条表态 + 下执行命令"时给宽一点的包窗口：每一条都要一次 HTTP 往返 + 一次事件落库， 1.5 秒在忙机器上会翻车。★
   * 它仍然是**毫秒级**（秒级上限，不是 30 分钟的缺省）。
   */
  private static final long HUMAN_WINDOW_MS = 8_000L;

  private App start(Path templates, Path dataDir, List<AgentTool> extras, int approvalSeconds)
      throws Exception {
    return start(templates, dataDir, extras, approvalSeconds, DECISION_DEADLINE_MS);
  }

  private App start(
      Path templates,
      Path dataDir,
      List<AgentTool> extras,
      int approvalSeconds,
      long decisionDeadlineMs)
      throws Exception {
    Files.createDirectories(dataDir);
    // ★ 两个决策窗口经**真配置装配**注入（这就是 decision.package.deadlineMs / decision.commit.waitMs 的 E2E 生效面）：
    //   父侧读它 → toolConfig → AgentPipeline → 本轮队列（deadline），以及 → 网关七参（commitWait）。
    //   ★ 子体进程也读同一份 config.json（父侧经 --config-dir 传下来）⇒ 它本地那个包的窗口同样被压到毫秒级。
    Files.writeString(
        dataDir.resolve("config.json"),
        "{\"approval\":{\"http\":true,\"timeoutSeconds\":"
            + approvalSeconds
            + "},\"decision\":{\"package\":{\"deadlineMs\":"
            + decisionDeadlineMs
            + "},\"commit\":{\"waitMs\":"
            + COMMIT_WAIT_MS
            + "}}}");
    BootConfig config =
        new BootConfig(0, dataDir, false, "", null, false, "127.0.0.1", 0, templates);
    FakeLlmClient mainLlm =
        FakeLlmClient.with(
            LlmResponse.toolCall(
                "m-1",
                "spawn_sub_agent",
                Map.of("templateId", templateOf(templates), "goal", "点敏感工具")),
            LlmResponse.text("已派遣探针"));
    return App.start(config, mainLlm, extras);
  }

  /** 模板 id = 模板目录里那一个 json 的文件名（本类每个用例只写一个模板）。 */
  private static String templateOf(Path templates) {
    try (var files = Files.list(templates)) {
      return files
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(".json"))
          .map(name -> name.substring(0, name.length() - ".json".length()))
          .findFirst()
          .orElseThrow();
    } catch (Exception failure) {
      throw new AssertionError("读模板目录失败: " + templates, failure);
    }
  }

  /** 父侧的"会问人"工具：自报 {@code Ask}（分流点）+ 记下参数与链上身份。 */
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
        executedBy.add(context.identity() == null ? "unknown" : context.identity().instanceId());
        return ToolResult.ok("probe:" + context.arguments().getOrDefault("text", ""));
      }
    };
  }

  /** 不问人的探针（判据 14：这一轮零未决项）。 */
  private AgentTool quietProbe() {
    return new AgentTool() {
      @Override
      public String name() {
        return "quiet_probe";
      }

      @Override
      public String description() {
        return "探针：闸位缺省 ALLOW（本轮不产生任何待裁决项）";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")));
      }

      @Override
      public ToolResult execute(ToolContext context) {
        executed.add(Map.copyOf(context.arguments()));
        return ToolResult.ok("quiet:" + context.arguments().getOrDefault("text", ""));
      }
    };
  }

  private void writeTemplate(
      Path dir, String id, List<String> allowedTools, List<Map<String, Object>> script)
      throws Exception {
    Map<String, Object> template =
        Map.ofEntries(
            Map.entry("id", id),
            Map.entry("description", "P3c 真子进程验收模板 " + id),
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

  /**
   * 抓住 {@link ParentSubagentDecisionLedger} 自己打的那一行 INFO（判据 15 的可观测面）。
   *
   * <p>为什么走日志：父侧队列簿是 {@code App.start} 里的<b>局部变量</b>（测试拿不到、也不许为它加生产读口），而 {@code recycle}
   * 只在真的清了东西时打这一行——它带着实例 id、清掉时的队列条数与放行条数，正好是"队列清空、无残留"这三个事实。
   */
  private static AbstractAppender ledgerLogAppender(List<String> sink) {
    AbstractAppender appender =
        new AbstractAppender(
            "p3c-ledger-capture",
            null,
            PatternLayout.createDefaultLayout(),
            false,
            Property.EMPTY_ARRAY) {
          @Override
          public void append(LogEvent event) {
            sink.add(event.getMessage().getFormattedMessage());
          }
        };
    appender.start();
    return appender;
  }

  /** 子 Agent 管理器（杀进程的唯一合法入口，红线 5）。 */
  private static SubagentManager managerOf(App app) throws Exception {
    Field field = App.class.getDeclaredField("subagentManager");
    field.setAccessible(true);
    return (SubagentManager) field.get(app);
  }

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

  /** 从审批面那条请求的 {@code summary} 里取出跨进程临时包 id（{@code xp-…}）——那条"人看到的那一行"。 */
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
      return new ArrayList<>(store.query(new EventQuery("", type, "", -1, 100)));
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
