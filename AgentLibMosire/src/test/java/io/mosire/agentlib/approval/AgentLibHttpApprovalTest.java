package io.mosire.agentlib.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * AgentLib 通用 HTTP 审批件（P1-C）的离线判别性用例：真 {@link PendingApprovals} + 真 {@link HttpApprovalChannel} + 真
 * {@link ApprovalCoordinator} + 真 {@link ApprovalHttpEndpoint}（端口 {@code 0}，走真
 * socket），只有"人怎么答"这一步由用例扮演——所以断言的是<b>装配面</b>的行为，不是某个桩的。
 *
 * <p>四条语义各自命名、各自断言确切码/决定：① 超时 ⇒ DENY/`timeout`；② 重复决议 ⇒ 409 且不覆盖首决；③ 非唯一身份的 `APPROVE_SESSION` ⇒ 降级
 * once 且不写会话键；④ 无可用渠道 ⇒ 立即 DENY/`no-channel` 且绝不放行。另加 `pending → decide → await` 走通、`publish`
 * no-op、`available()` 开关，以及 MAJOR 4 的"同一台 server 追加 context"。
 */
class AgentLibHttpApprovalTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CLASS_KEY = "bash:ask:apt";
  private static final String SUMMARY = "安装 apt 包（人看的说明，工具自报）";
  private static final String SESSION_BODY = "{\"decision\":\"approve\",\"scope\":\"session\"}";

  /**
   * 语义①：到点没人答 ⇒ {@link ApprovalDecision#DENY}，且事件里的 {@code by == SOURCE_TIMEOUT}。
   *
   * <p>判别性：把超时路径改成返回 APPROVE_*（放行）或把 {@code by} 换成别的来源 ⇒ 对应断言转红。
   */
  @Test
  void timeoutWithoutAnyAnswerDeniesWithSourceTimeout() throws Exception {
    try (EventBus bus = new EventBus();
        EventSink events = new EventSink(bus);
        Rig rig = new Rig(Duration.ZERO, bus)) {
      rig.markUp();
      ApprovalRequest req = submit(rig, "SYSTEM");

      assertThat(rig.coordinator.decide(req)).isEqualTo(ApprovalDecision.DENY);

      Event decided =
          events.awaitType(ApprovalEventTypes.DECIDED, Duration.ofSeconds(2)).orElseThrow();
      JsonNode payload = ApprovalStubs.payload(decided);
      assertThat(payload.get("by").asText()).isEqualTo(ApprovalCoordinator.SOURCE_TIMEOUT);
      assertThat(payload.get("scope").asText()).isEqualTo("none");
      assertThat(rig.pending.pending()).as("超时 = 非人工终局，登记项必须摘除").isEmpty();
    }
  }

  /**
   * 语义②：同一 id 重复决议 ⇒ {@link PendingApprovals#decide} 返回 {@code false}、端点返回 {@code 409}，且<b>不覆盖</b>
   * 首次决定。
   *
   * <p>判别性：去掉幂等保护（放行重复 decide）或把 409 写成 200 ⇒ 对应断言转红。
   */
  @Test
  void repeatedDecideReturns409AndDoesNotOverwriteTheFirstDecision() throws Exception {
    try (Rig rig = new Rig(Duration.ofSeconds(5), null)) {
      rig.markUp();
      ApprovalRequest req = submit(rig, "SYSTEM");

      assertThat(rig.post("/" + req.id(), "{\"decision\":\"approve\"}").statusCode())
          .isEqualTo(200);
      assertThat(rig.pending.decide(req.id(), ApprovalDecision.DENY, "second")).isFalse();

      HttpResponse<String> again = rig.post("/" + req.id(), "{\"decision\":\"deny\"}");
      assertThat(again.statusCode()).isEqualTo(409);
      assertThat(rig.pending.await(req.id(), Duration.ofMillis(100)))
          .as("重复决议不得覆盖首次决定")
          .contains(ApprovalDecision.APPROVE_ONCE);
    }
  }

  /**
   * 语义③：{@code APPROVE_SESSION} 对<b>非唯一身份</b>调用者（{@code DEFAULT} 桶）被降级为一次、且<b>不写会话键</b>； 回执的 {@code
   * scope} 走 {@code coordinator.effectiveDecision(...)} 报出实际生效值。
   *
   * <p>判别性（G13 变异自证点）：把 {@code sessionGrantable} 收窄逻辑（或端点回执的 {@code effectiveDecision} 调用） 改坏 ⇒ 第
   * 1/3/4 条断言之一转红。对照 {@code SYSTEM}（唯一身份）证明"降级"来自身份桶，不是会话级放行整体失效。
   */
  @Test
  void sessionApprovalFromANonUniqueIdentityDegradesToOnceAndWritesNoSessionKey() throws Exception {
    try (Rig rig = new Rig(Duration.ofSeconds(5), null)) {
      rig.markUp();
      ExecutorService decisions = Executors.newVirtualThreadPerTaskExecutor();
      try {
        // 非唯一身份（DEFAULT 桶 = 全体子 Agent）：人给"本会话"，实际只批一次
        ApprovalRequest nonUnique = submit(rig, "DEFAULT");
        Future<ApprovalDecision> downgraded =
            decisions.submit(() -> rig.coordinator.decide(nonUnique));
        JsonNode receipt = JSON.readTree(rig.post("/" + nonUnique.id(), SESSION_BODY).body());
        assertThat(receipt.get("scope").asText())
            .as("非唯一身份上的「本会话」必须降级为一次，回执不得说谎")
            .isEqualTo("once");
        assertThat(downgraded.get(3, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.APPROVE_ONCE);
        assertThat(rig.pending.isSessionGranted("DEFAULT", CLASS_KEY)).as("降级后不得写会话键").isFalse();

        // 对照：唯一身份（SYSTEM）⇒ 原样生效，且真的写了会话键
        ApprovalRequest unique = submit(rig, "SYSTEM");
        Future<ApprovalDecision> granted = decisions.submit(() -> rig.coordinator.decide(unique));
        JsonNode uniqueReceipt = JSON.readTree(rig.post("/" + unique.id(), SESSION_BODY).body());
        assertThat(uniqueReceipt.get("scope").asText()).isEqualTo("session");
        assertThat(granted.get(3, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.APPROVE_SESSION);
        assertThat(rig.pending.isSessionGranted("SYSTEM", CLASS_KEY)).isTrue();
      } finally {
        decisions.shutdownNow();
      }
    }
  }

  /**
   * 语义④：无可用渠道（{@link ApprovalChannel#available()} == false）⇒ 编排器<b>立即</b> DENY、{@code by ==
   * SOURCE_NO_CHANNEL}，且<b>绝不放行</b>（不登记、不挂到超时）。
   *
   * <p>判别性：把无渠道路径改成挂到超时（超时给 5 s）或放行 ⇒ 耗时断言/决定断言转红。
   */
  @Test
  void unavailableChannelDeniesImmediatelyWithSourceNoChannelAndNeverAllows() throws Exception {
    try (EventBus bus = new EventBus();
        EventSink events = new EventSink(bus);
        Rig rig = new Rig(Duration.ofSeconds(5), bus)) {
      // 不 markUp：端口虽起了，但"配置说该开"不等于"面可用"——通道仍未就绪
      assertThat(rig.channel.available()).isFalse();
      ApprovalRequest req = rawRequest(); // 未经登记表提交，好证明"无渠道时编排器根本不登记"

      long startedNanos = System.nanoTime();
      ApprovalDecision decision = rig.coordinator.decide(req);
      long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

      assertThat(decision).isEqualTo(ApprovalDecision.DENY);
      assertThat(elapsedMs).as("无渠道必须立即拒，不得挂到 5 s 超时").isLessThan(1_000L);
      assertThat(rig.pending.pending()).as("无渠道时根本不登记（没有幽灵项）").isEmpty();
      assertThat(rig.pending.get(req.id())).as("无渠道时登记表里没有这条").isEmpty();

      Event decided =
          events.awaitType(ApprovalEventTypes.DECIDED, Duration.ofSeconds(2)).orElseThrow();
      JsonNode payload = ApprovalStubs.payload(decided);
      assertThat(payload.get("by").asText()).isEqualTo(ApprovalCoordinator.SOURCE_NO_CHANNEL);
      assertThat(payload.get("scope").asText()).isEqualTo("none");
    }
  }

  /**
   * 闭环：{@code pending → decide → await} 走通——阻塞在 {@link HttpApprovalChannel#await} 的一侧被人经 HTTP 答复唤醒，
   * 拿到同一个决议。
   *
   * <p>判别性：把 handler 里的 {@code pending.decide(...)} 换成"只回 200"⇒ 等待侧拿到的仍是空 ⇒ 转红。
   */
  @Test
  void pendingDecideAwaitRoundTripWakesTheWaiter() throws Exception {
    try (Rig rig = new Rig(Duration.ofSeconds(5), null)) {
      rig.markUp();
      ApprovalRequest req = submit(rig, "SYSTEM");
      ExecutorService awaiters = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<Optional<ApprovalDecision>> blocked =
            awaiters.submit(() -> rig.channel.await(req.id(), Duration.ofSeconds(5)));

        HttpResponse<String> response =
            rig.post(
                "/" + req.id(), "{\"decision\":\"approve\",\"scope\":\"once\",\"by\":\"值班人\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode receipt = JSON.readTree(response.body());
        assertThat(receipt.get("id").asText()).isEqualTo(req.id());
        assertThat(receipt.get("decision").asText()).isEqualTo("approve");
        assertThat(receipt.get("scope").asText()).isEqualTo("once");
        assertThat(receipt.get("by").asText()).isEqualTo("值班人");

        assertThat(blocked.get(3, TimeUnit.SECONDS)).contains(ApprovalDecision.APPROVE_ONCE);
      } finally {
        awaiters.shutdownNow();
      }
    }
  }

  /**
   * {@link HttpApprovalChannel#publish} 是 no-op 且不阻塞（HTTP 面不推送：待裁决列表直接读登记表快照）。
   *
   * <p>判别性：把 publish 改成写登记表/等待 ⇒ 耗时断言或"登记表为空"断言转红。
   */
  @Test
  void publishIsANoOpThatNeverBlocksAndRegistersNothing() throws Exception {
    try (Rig rig = new Rig(Duration.ofSeconds(5), null)) {
      ApprovalRequest unregistered = rawRequest();

      long startedNanos = System.nanoTime();
      rig.channel.publish(unregistered);
      long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

      assertThat(elapsedMs).isLessThan(500L);
      assertThat(rig.pending.pending()).as("publish 不登记任何东西").isEmpty();
    }
  }

  /**
   * 可用性开关：{@code markUp()} 之前为假、之后为真；{@code close()} 后合上——可用性认的是"端口真的在监听"，不是"配置说该开"。
   *
   * <p>判别性：把 {@code available()} 写成恒真/只看配置 ⇒ 首条或末条断言转红。
   */
  @Test
  void availabilityIsFalseBeforeMarkUpTrueAfterAndFalseOnceClosed() throws Exception {
    try (Rig rig = new Rig(Duration.ofSeconds(5), null)) {
      assertThat(rig.channel.available()).isFalse();
      rig.markUp();
      assertThat(rig.channel.available()).isTrue();
      rig.channel.close();
      assertThat(rig.channel.available()).as("关停后不再可用").isFalse();
    }
  }

  /**
   * MAJOR 4：同一台 server 上经 {@link ApprovalHttpEndpoint#createContext(String, HttpHandler)} 追加
   * context （供 MainMosire 薄包装注册 {@code /api/commands/mode}）——<b>同端口</b>可达，且审批面不受影响。
   *
   * <p>判别性：若端点在启动时就把 server 封死、不给追加口子 ⇒ 该路径 404，首条断言转红。
   */
  @Test
  void extraContextCanBeRegisteredOnTheSameServerAndPort() throws Exception {
    try (Rig rig = new Rig(Duration.ofSeconds(5), null)) {
      rig.endpoint.createContext(
          "/api/commands/mode",
          exchange -> {
            byte[] body = "{\"mode\":\"full\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          });

      HttpResponse<String> mode =
          rig.client.send(
              HttpRequest.newBuilder(URI.create(rig.base() + "/api/commands/mode")).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(mode.statusCode()).isEqualTo(200);
      assertThat(mode.body()).contains("full");
      assertThat(rig.get("").statusCode()).as("同一端口上的审批面仍可用").isEqualTo(200);
    }
  }

  /** 用例脚手架：真登记表 + 编排器（一条 HTTP 通道）+ 真 HTTP 面（端口 0，绑 127.0.0.1）。 */
  private static final class Rig implements AutoCloseable {

    private final PendingApprovals pending = new PendingApprovals();
    private final HttpApprovalChannel channel = new HttpApprovalChannel(pending);
    private final ApprovalCoordinator coordinator;
    private final ApprovalHttpEndpoint endpoint;
    private final HttpClient client = HttpClient.newHttpClient();

    Rig(Duration timeout, EventBus bus) {
      this.coordinator =
          new ApprovalCoordinator(
              List.of(), List.of((ApprovalChannel) channel), pending, timeout, bus);
      this.endpoint = ApprovalHttpEndpoint.start(0, pending, coordinator);
    }

    void markUp() {
      channel.markUp(); // 同装配层口径：面真的在监听之后通道才算可用
    }

    HttpResponse<String> get(String suffix) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create(base() + ApprovalHttpEndpoint.PATH + suffix))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String suffix, String body) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create(base() + ApprovalHttpEndpoint.PATH + suffix))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    String base() {
      return "http://127.0.0.1:" + endpoint.boundPort();
    }

    @Override
    public void close() {
      endpoint.close();
      channel.close();
    }
  }

  private static ApprovalRequest submit(Rig rig, String callerKey) {
    return rig.pending.submitRequest(
        callerKey,
        "bash",
        CLASS_KEY,
        SUMMARY,
        "sha256:0123456789abcdef",
        System.currentTimeMillis() + 60_000L);
  }

  /** 不经登记表构造的裸请求（给 publish no-op 用：它不该产生任何登记项）。 */
  private static ApprovalRequest rawRequest() {
    long now = System.currentTimeMillis();
    return new ApprovalRequest(
        "ap-raw", "bash", CLASS_KEY, SUMMARY, "sha256:deadbeef", now, now + 60_000L, "SYSTEM");
  }
}
