package io.mosire.main.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.DecisionItemDigest;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.DecisionReadAudit;
import io.mosire.agentlib.decision.DecisionSubmission;
import io.mosire.agentlib.decision.DecisionSubmissionPort;
import io.mosire.agentlib.decision.InFlightDecisionPackages;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.decision.ProposeRequest;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.main.approval.ApprovalHttpEndpoint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * <b>P3e：决策面的"在途明文面"</b>（本单自己的证据载体，不是 P3c 的验收面）：真 socket + 真 {@link
 * DecisionPackagesHttpHandler}，只有"在途取数口"是本用例给的桩。
 *
 * <p><b>它钉住的四件事</b>：
 *
 * <ol>
 *   <li>{@code GET /{id}} 在主表未命中时问在途口，逐条返回<b>同一形状</b>的明文载荷（{@code args} 是明文）；
 *   <li>★ <b>只读</b>：在途包<b>不进</b> {@code pending()} 列表、{@code POST} 也不认它（{@code 404}）+ 提交口一次都不被调 ——
 *       它不构成第二条决策路径（不变量 1）；
 *   <li><b>结算即消失</b>：口里取不到 ⇒ 与"从未存在"同一个 {@code 404}；
 *   <li>主表命中时<b>不</b>问在途口（两级取数有先后，不是"两处真相"）；取数口异常按"取不到"办（{@code 404}，不是 500）。
 * </ol>
 */
class DecisionPackagesHttpHandlerInFlightTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String IN_FLIGHT_ID = "xp-11111111-2222-3333-4444-555555555555";

  /** 明文读取审计桩（用户裁定 15 ①）：只记"哪一次读了什么"，用来断言审计真的落了、且不含明文。 */
  private final RecordingReadAudit audit = new RecordingReadAudit();

  /** 真包（在主表里）与在途包（只在取数口里）各一个：判据要能分辨"这两级"。 */
  @Test
  void theDetailFaceResolvesInFlightPackagesAsPlaintextButStaysReadOnly() throws Exception {
    PendingApprovals pending = new PendingApprovals();
    EventBus bus = new EventBus();
    DecisionPackages packages = new DecisionPackages();
    AtomicInteger submissions = new AtomicInteger();
    DecisionSubmissionPort submissionsPort =
        new DecisionSubmissionPort() {
          @Override
          public DecisionSubmission submitDecision(String packageId, Decision decision) {
            submissions.incrementAndGet();
            throw new AssertionError("在途包不得走到提交口");
          }

          @Override
          public DecisionSubmission itemDecided(
              String packageId, int index, boolean approve, String by, String note) {
            submissions.incrementAndGet();
            throw new AssertionError("在途包不得走到提交口");
          }

          @Override
          public DecisionSubmission commitPackage(String packageId, String by) {
            submissions.incrementAndGet();
            throw new AssertionError("在途包不得走到提交口");
          }
        };
    CountingInFlight inFlight = new CountingInFlight();
    try (bus;
        ApprovalHttpEndpoint endpoint =
            ApprovalHttpEndpoint.start(
                0,
                pending,
                new ApprovalCoordinator(
                    List.of(), List.of(), pending, Duration.ofMillis(50), bus, key -> true))) {
      DecisionPackagesHttpHandler.register(
          endpoint,
          packages,
          submissionsPort,
          packageId -> packageId.equals(IN_FLIGHT_ID) ? Set.of(0) : null,
          inFlight,
          null,
          audit);
      int port = endpoint.boundPort();

      // ---- ① 在途包：GET 详情读得到逐条（★ 用户裁定 15 ①：默认概览，full=1 才给明文）----
      inFlight.put(inFlightPackage());
      HttpResponse<String> detail =
          get(port, DecisionPackagesHttpHandler.PATH + "/" + IN_FLIGHT_ID);
      assertThat(detail.statusCode()).as("★ 过去这里是 404（临时包不进包登记表）").isEqualTo(200);
      JsonNode row = JSON.readTree(detail.body());
      assertThat(row.get("id").asText()).isEqualTo(IN_FLIGHT_ID);
      assertThat(row.get("proposerKey").asText()).isEqualTo("prober-1");
      assertThat(row.get("itemCount").asInt()).isEqualTo(1);
      assertThat(row.get("state").asText()).isEqualTo("PROPOSED");
      assertThat(row.get("decision").isNull()).isTrue();
      JsonNode item = row.get("items").get(0);
      assertThat(item.get("index").asInt()).isZero();
      assertThat(item.get("toolName").asText()).isEqualTo("sensitive_probe");
      assertThat(item.get("overview").asText())
          .as("★ 概览档给的是「哪个工具、几个参数」，不是参数值")
          .contains("sensitive_probe");
      assertThat(item.has("summary")).as("★ 概览档连人读摘要都不给（它就是截断后的参数）").isFalse();
      assertThat(item.get("escalated").asBoolean()).as("上报视图对这个 id 说了 {0}").isTrue();
      // ★ 裁定 15 ①：**默认概览**——明文（args）一个字都不出现，摘要照旧给
      assertThat(item.has("args")).as("★ 默认概览不含明文（用户裁定 15 ①）").isFalse();
      assertThat(item.get("digest").asText()).isNotBlank();
      // ★ 明文"不出现"的判据要看**解析后的字段**：脱敏摘要（argsDigest 的十六进制）里可以合法地含有明文的
      //   某个字符，拿整段 body 做子串匹配会得到一条假红（本用例第一版就是这么红的）
      assertThat(detail.body()).as("★ 默认响应体里不得出现明文本身").doesNotContain("TOPSECRET-INFLIGHT");
      assertThat(row.get("items").get(0).has("args")).isFalse();
      assertThat(audit.reads).as("★ 没要明文就不落审计（读了没看 ≠ 看了）").isEmpty();

      // ★ 裁定 15 ①：**显式 full=1 才给明文**，并且每一次都落审计事件（只记谁/哪个包/哪些 index）
      HttpResponse<String> full =
          get(port, DecisionPackagesHttpHandler.PATH + "/" + IN_FLIGHT_ID + "?full=1");
      assertThat(full.statusCode()).isEqualTo(200);
      JsonNode fullRow = JSON.readTree(full.body());
      assertThat(fullRow.get("items").get(0).get("args").get("text").asText())
          .as("★ full=1 ⇒ args 是明文")
          .isEqualTo("TOPSECRET-INFLIGHT");
      assertThat(fullRow.get("items").get(0).get("summary").asText())
          .as("★ 明文档才给人读摘要（它含参数值）")
          .contains("TOPSECRET-INFLIGHT");
      assertThat(audit.reads).as("★ 每一次 full 读取都落一条审计").hasSize(1);
      assertThat(audit.reads.get(0)).contains(IN_FLIGHT_ID).contains("[0]");
      assertThat(audit.reads.get(0))
          .as("★ 审计只记谁/哪个包/哪些 index，绝不记明文")
          .doesNotContain("TOPSECRET-INFLIGHT");

      // ---- ② 只读：不进 pending() 列表、POST 不认它 ----
      JsonNode list = JSON.readTree(get(port, DecisionPackagesHttpHandler.PATH).body());
      assertThat(list.get("pending")).as("★ 在途包不进 pending()（不变量 1：它不是决策单元）").isEmpty();

      HttpResponse<String> refused =
          post(
              port, DecisionPackagesHttpHandler.PATH + "/" + IN_FLIGHT_ID, "{\"approveAll\":true}");
      assertThat(refused.statusCode()).as("★ 写路径不认在途包").isEqualTo(404);
      assertThat(JSON.readTree(refused.body()).get("code").asText())
          .isEqualTo(DecisionPackages.PACKAGE_NOT_FOUND);
      assertThat(submissions.get()).as("提交口一次都没被调").isZero();

      // ---- ③ 结算即消失（口里取不到 ⇒ 与从未存在同一个 404）----
      inFlight.remove(IN_FLIGHT_ID);
      assertThat(get(port, DecisionPackagesHttpHandler.PATH + "/" + IN_FLIGHT_ID).statusCode())
          .as("★ 结算/过期即消失")
          .isEqualTo(404);
      assertThat(inFlight.removeCount()).isEqualTo(1);

      // ---- ④ 主表命中时不问在途口；取数口异常按"取不到"办（404 而不是 500）----
      DecisionPackage real = packages.propose(inFlightPackageAsProposeRequest());
      int callsBefore = inFlight.findCount();
      HttpResponse<String> realDetail =
          get(port, DecisionPackagesHttpHandler.PATH + "/" + real.id());
      assertThat(realDetail.statusCode()).isEqualTo(200);
      assertThat(JSON.readTree(realDetail.body()).get("items").get(0).has("args"))
          .as("★ 真包的默认详情同样是概览（口径不分在途/在表）")
          .isFalse();
      assertThat(inFlight.findCount()).as("★ 主表命中 ⇒ 不问在途口").isEqualTo(callsBefore);
      HttpResponse<String> realFull =
          get(port, DecisionPackagesHttpHandler.PATH + "/" + real.id() + "?full=1");
      assertThat(realFull.statusCode()).isEqualTo(200);
      assertThat(
              JSON.readTree(realFull.body()).get("items").get(0).get("args").get("text").asText())
          .isEqualTo("真包明文");
      assertThat(audit.reads).as("★ 真包的 full 读取同样落审计").hasSize(2);

      inFlight.failWith(new IllegalStateException("取数口炸了（用例桩）"));
      assertThat(get(port, DecisionPackagesHttpHandler.PATH + "/" + IN_FLIGHT_ID).statusCode())
          .as("★ 取数口异常 ⇒ 按取不到办（404），不让详情面变 500")
          .isEqualTo(404);
    }
  }

  // ---------- 夹具 ----------

  private static DecisionPackage inFlightPackage() {
    return new DecisionPackage(
        IN_FLIGHT_ID,
        "prober-1",
        AccessToken.DEFAULT,
        List.of(packageItem(0, "sensitive_probe", Map.of("text", "TOPSECRET-INFLIGHT"))),
        System.currentTimeMillis(),
        System.currentTimeMillis() + DecisionPackages.DEFAULT_DEADLINE_MILLIS,
        PackageState.PROPOSED,
        null);
  }

  private static ProposeRequest inFlightPackageAsProposeRequest() {
    return new ProposeRequest(
        "main",
        AccessToken.DEFAULT,
        List.of(packageItem(0, "docs_edit", Map.of("text", "真包明文"))),
        System.currentTimeMillis() + DecisionPackages.DEFAULT_DEADLINE_MILLIS);
  }

  private static PackageItem packageItem(int index, String toolName, Map<String, Object> args) {
    return new PackageItem(
        index,
        toolName,
        args,
        DecisionItemDigest.argsDigest(args),
        DecisionItemDigest.itemSummary(toolName, args));
  }

  private static HttpResponse<String> get(int port, String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(int port, String path, String body) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  /** 明文读取审计口的最简实现：把每次读取的原样记下来（判据自己再断言内容）。 */
  private static final class RecordingReadAudit implements DecisionReadAudit {

    private final List<String> reads = new CopyOnWriteArrayList<>();

    @Override
    public void detailRead(String packageId, List<Integer> indexes, String by) {
      reads.add(packageId + " " + indexes + " by=" + by);
    }
  }

  /** 在途取数口的桩：一张可增删的表 + 调用计数 + 可注入的异常（判据要能分辨"命中主表"与"问在途口"）。 */
  private static final class CountingInFlight implements InFlightDecisionPackages {

    private final Map<String, DecisionPackage> table =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final AtomicInteger removed = new AtomicInteger();
    private volatile RuntimeException failure;

    void put(DecisionPackage pkg) {
      table.put(pkg.id(), pkg);
    }

    void remove(String packageId) {
      if (table.remove(packageId) != null) {
        removed.incrementAndGet();
      }
    }

    void failWith(RuntimeException failure) {
      this.failure = failure;
    }

    int findCount() {
      return asked.size();
    }

    int removeCount() {
      return removed.get();
    }

    @Override
    public Optional<DecisionPackage> find(String packageId) {
      asked.add(packageId);
      if (failure != null) {
        throw failure;
      }
      return Optional.ofNullable(table.get(packageId));
    }
  }
}
