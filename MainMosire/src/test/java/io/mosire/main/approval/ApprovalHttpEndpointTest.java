package io.mosire.main.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalHttpEndpoint;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.HttpApprovalChannel;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.CommandModeHolder;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 审批 HTTP 面（S4-B2）迁移后的 Main 侧判别性用例：H1~H5 + H9（编号同派发包 §一.5 的表）。
 *
 * <p>迁移后（设计 §七 P1-C）面本体是 AgentLib 通用件 {@link ApprovalHttpEndpoint} + {@link
 * HttpApprovalChannel}，Main 只装配 + 挂 {@code /api/commands/mode}
 * 薄包装。本类因此同时是<b>契约对照</b>：端点路径、状态码、回执字段集与 {@code effectiveDecision} 口径必须与迁移前逐条等价。
 *
 * <p>用的是真 {@link PendingApprovals} + 真 AgentLib 通道 + 真 {@link ApprovalCoordinator} + 真 HTTP 面（端口
 * {@code 0}，走真 socket），只有"人怎么答"这一步由用例扮演——所以断言的是<b>装配面</b>的行为，不是某个桩的。
 *
 * <p>每条用例的判别性（改坏哪一行转红）写在方法注释里；H9 另有一条实测变异记录在收尾报告里。
 */
class ApprovalHttpEndpointTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CLASS_KEY = "bash:ask:apt";
  private static final String SUMMARY = "安装 apt 包（人看的说明，工具自报）";

  /** 审批面根路径：AgentLib 端点里的 {@code PATH} 是包私有，Main 侧按<b>对外契约</b>字面量钉住（改路径即转红）。 */
  private static final String APPROVALS = "/api/approvals";

  /**
   * H1：列表列出来源就是登记表本身——<b>决议后它必须消失</b>，且"读一次之后新提交的"必须出现。
   *
   * <p>判别性：把列表改成读一份"启动时抓的快照/副本"，第二条与第三条断言转红（副本不会因为人答了而变化，也不会看见后提交的项）。
   */
  @Test
  void listReflectsTheLiveRegistryAndEmptiesAfterDecision() throws Exception {
    try (Rig rig = new Rig()) {
      ApprovalRequest req = submit(rig, "SYSTEM");

      HttpResponse<String> first = rig.get("");
      assertThat(first.statusCode()).isEqualTo(200);
      JsonNode rows = JSON.readTree(first.body()).get("pending");
      assertThat(rows.isArray()).isTrue();
      assertThat(rows).hasSize(1);
      JsonNode row = rows.get(0);
      // 字段集契约：与迁移前逐字一致（多一个少一个即转红）
      List<String> fields = new ArrayList<>();
      row.fieldNames().forEachRemaining(fields::add);
      assertThat(fields)
          .containsExactlyInAnyOrder(
              "id", "tool", "classKey", "summary", "digest", "createdAtEpochMs", "deadlineEpochMs");
      assertThat(row.get("id").asText()).isEqualTo(req.id());
      assertThat(row.get("tool").asText()).isEqualTo(req.tool());
      assertThat(row.get("classKey").asText()).isEqualTo(CLASS_KEY);
      assertThat(row.get("summary").asText()).isEqualTo(SUMMARY);
      assertThat(row.get("digest").asText()).isEqualTo(req.digest());
      assertThat(row.get("createdAtEpochMs").asLong()).isEqualTo(req.createdAtEpochMs());
      assertThat(row.get("deadlineEpochMs").asLong()).isEqualTo(req.deadlineEpochMs());

      // 读过一次之后新提交的项也要出现（读副本/缓存快照的实现到这里就露馅）
      ApprovalRequest second = submit(rig, "SYSTEM");
      assertThat(ids(rig.get(""))).containsExactlyInAnyOrder(req.id(), second.id());

      assertThat(rig.post("/" + req.id(), "{\"decision\":\"approve\"}").statusCode())
          .isEqualTo(200);
      assertThat(ids(rig.get(""))).containsExactly(second.id());
    }
  }

  /**
   * H2：三态可分辨——未知 id（含已被 {@code drop} 摘除的）⇒ 404，已决议过再答 ⇒ 409，且首决不被覆盖。
   *
   * <p>判别性：去掉"先查后决"（只靠 {@code decide} 的布尔）会把"已摘除"混成 409；把 404 写成 200 则第一条断言红； 去掉幂等保护（放行重复
   * decide）第三条断言红。
   */
  @Test
  void unknownAndDroppedIdsAre404WhileDecidedIdsAre409() throws Exception {
    try (Rig rig = new Rig()) {
      // ① 从未存在
      assertThat(rig.post("/ap-does-not-exist", "{\"decision\":\"approve\"}").statusCode())
          .isEqualTo(404);

      // ② 已被编排器 fail-closed 摘除的 id 与"从未存在"在外部不可区分 ⇒ 同样是 404（不是 409）
      ApprovalRequest dropped = submit(rig, "SYSTEM");
      assertThat(rig.pending.drop(dropped.id())).isTrue();
      assertThat(rig.post("/" + dropped.id(), "{\"decision\":\"approve\"}").statusCode())
          .isEqualTo(404);
      assertThat(rig.pending.get(dropped.id())).isEmpty();

      // ③ 已经被人决议过的 ⇒ 409，且首次决定不被后来的答复覆盖
      ApprovalRequest decided = submit(rig, "SYSTEM");
      assertThat(rig.post("/" + decided.id(), "{\"decision\":\"approve\"}").statusCode())
          .isEqualTo(200);
      HttpResponse<String> again = rig.post("/" + decided.id(), "{\"decision\":\"deny\"}");
      assertThat(again.statusCode()).isEqualTo(409);
      assertThat(rig.pending.await(decided.id(), Duration.ofMillis(200)))
          .as("重复决议不得覆盖首次决定")
          .contains(ApprovalDecision.APPROVE_ONCE);
    }
  }

  /**
   * H3：合法 POST ⇒ 200 + 回执，且<b>阻塞在 {@code await} 的那一侧真的拿到决议</b>（决议打通到登记表，不是只改了列表）。
   *
   * <p>判别性：把 handler 里的 {@code pending.decide(...)} 换成"只回 200"，等待侧拿到的仍是空 ⇒ 转红。
   */
  @Test
  void acceptedPostWakesTheBlockedWaiterWithTheSameDecision() throws Exception {
    try (Rig rig = new Rig()) {
      ApprovalRequest req = submit(rig, "SYSTEM");
      ExecutorService awaiters = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<Optional<ApprovalDecision>> blocked =
            awaiters.submit(() -> rig.pending.await(req.id(), Duration.ofSeconds(5)));

        HttpResponse<String> response =
            rig.post(
                "/" + req.id(), "{\"decision\":\"approve\",\"scope\":\"once\",\"by\":\"值班人\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode receipt = JSON.readTree(response.body());
        assertThat(receipt.get("id").asText()).isEqualTo(req.id());
        assertThat(receipt.get("decision").asText()).isEqualTo("approve");
        assertThat(receipt.get("scope").asText()).isEqualTo("once");
        assertThat(receipt.get("by").asText()).as("body 里的 by 只是审计注记：原样回报，不参与判定").isEqualTo("值班人");

        assertThat(blocked.get(3, TimeUnit.SECONDS)).contains(ApprovalDecision.APPROVE_ONCE);
      } finally {
        awaiters.shutdownNow();
      }
    }
  }

  /**
   * H4：坏 body（非法 JSON / {@code decision} 非法 / {@code deny} 带 {@code scope} / 非对象 / 越界 scope）一律 400，
   * 且<b>不得进审批表</b>——请求仍停在待裁决态（这一点比"返回码对不对"更重要：半途写进去的决议 = 有人替人答了）。
   *
   * <p>判别性：删掉任一条参数校验 ⇒ 对应断言收到 200 且决议被写进登记表（{@code await} 不再是空）⇒ 转红。
   */
  @Test
  void malformedBodiesAre400AndNeverDecideAnything() throws Exception {
    try (Rig rig = new Rig()) {
      ApprovalRequest req = submit(rig, "SYSTEM");
      List<String> badBodies =
          List.of(
              "{\"decision\":\"maybe\"}",
              "{\"decision\":\"deny\",\"scope\":\"session\"}",
              "{\"decision\":\"approve\",\"scope\":\"forever\"}",
              "{\"scope\":\"once\"}",
              "[1,2,3]",
              "{\"decision\":");
      for (String body : badBodies) {
        HttpResponse<String> response = rig.post("/" + req.id(), body);
        assertThat(response.statusCode()).as("坏 body 必须 400: %s", body).isEqualTo(400);
      }

      assertThat(rig.pending.pending())
          .as("坏 body 一个决议都不许落表")
          .extracting(ApprovalRequest::id)
          .containsExactly(req.id());
      assertThat(rig.pending.await(req.id(), Duration.ofMillis(100)))
          .as("坏 body 之后该项仍应是待裁决（没人替人答过）")
          .isEmpty();
    }
  }

  /**
   * 方法门禁契约（迁移前既有惯例）：列表路径只认 {@code GET}、决议路径只认 {@code POST}，非声明方法 {@code 405} 且带 {@code Allow}
   * 头、响应体缺省。
   *
   * <p>判别性：把 {@code requireMethod} 的 405 写成 404/200，或漏掉 {@code Allow} 头 ⇒ 对应断言转红。
   */
  @Test
  void wrongMethodsAre405WithAllowHeader() throws Exception {
    try (Rig rig = new Rig()) {
      ApprovalRequest req = submit(rig, "SYSTEM");

      HttpResponse<String> postToList = rig.post("", "{\"decision\":\"approve\"}");
      assertThat(postToList.statusCode()).isEqualTo(405);
      assertThat(postToList.headers().firstValue("Allow")).contains("GET");

      HttpResponse<String> getToDecide = rig.get("/" + req.id());
      assertThat(getToDecide.statusCode()).isEqualTo(405);
      assertThat(getToDecide.headers().firstValue("Allow")).contains("POST");
    }
  }

  /**
   * H5：恒绑 {@code 127.0.0.1}，且<b>绑定入口没有可配 host 的形参</b>。
   *
   * <p>判据分两半：① 面确实在 loopback 上可通；② 结构性事实——{@link ApprovalHttpEndpoint#start(int, PendingApprovals,
   * ApprovalCoordinator)} 的公开签名里没有任何 {@code String}/{@code InetAddress}/{@code
   * InetSocketAddress}/{@code URI} 形态的形参（加一个 host 参数这条即转红）。{@code createContext(String,
   * HttpHandler)} 的 {@code String} 是<b>路径</b>不是绑定地址，故只查 {@code start} 入口。
   *
   * <p>反例测试（"从别的地址连不上"）在本机多网卡/容器环境下不可靠，故用结构断言 + 注释（派发包 §一.5 H5 明示的取法）。
   */
  @Test
  void loopbackOnlyWithNoHostShapedParameterOnTheBindEntryPoint() throws Exception {
    try (Rig rig = new Rig()) {
      HttpResponse<String> response = rig.get("");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(rig.endpoint.boundPort()).isGreaterThan(0);
    }

    List<Class<?>> hostShaped =
        List.of(String.class, InetAddress.class, InetSocketAddress.class, URI.class);
    for (Method method : ApprovalHttpEndpoint.class.getDeclaredMethods()) {
      if (!Modifier.isPublic(method.getModifiers()) || !"start".equals(method.getName())) {
        continue;
      }
      for (Class<?> parameter : method.getParameterTypes()) {
        assertThat(hostShaped)
            .as("绑定入口 %s 不得有可配绑地址的形参", method.getName())
            .doesNotContain(parameter);
      }
    }
    for (Constructor<?> constructor : ApprovalHttpEndpoint.class.getDeclaredConstructors()) {
      for (Class<?> parameter : constructor.getParameterTypes()) {
        assertThat(hostShaped).as("构造器不得有可配绑地址的形参").doesNotContain(parameter);
      }
    }
  }

  /**
   * H9：同一次 {@code POST}（approve + session）在两条 callerKey 上<b>回报各自实际生效的 scope</b>—— {@code
   * SYSTEM}（身份唯一）⇒ {@code session}；{@code DEFAULT}（身份桶）⇒ 降级 {@code once}。
   *
   * <p>判别性：把 {@code coordinator.effectiveDecision(...)} 换成"把请求的 scope 原样回执"（MED-1 点名的错法），第 2
   * 条断言就会收到 {@code session} ⇒ 转红——而人实际只被批了一次，响应面在说谎。
   */
  @Test
  void receiptReportsTheEffectiveScopeNotTheRequestedOne() throws Exception {
    try (Rig rig = new Rig()) {
      ApprovalRequest systemReq = submit(rig, "SYSTEM");
      ApprovalRequest defaultReq = submit(rig, "DEFAULT");
      String body = "{\"decision\":\"approve\",\"scope\":\"session\"}";

      JsonNode systemReceipt = JSON.readTree(rig.post("/" + systemReq.id(), body).body());
      assertThat(systemReceipt.get("decision").asText()).isEqualTo("approve");
      assertThat(systemReceipt.get("scope").asText())
          .as("SYSTEM = 主 Agent 单实例（身份唯一）⇒ 会话级放行原样生效")
          .isEqualTo("session");

      JsonNode defaultReceipt = JSON.readTree(rig.post("/" + defaultReq.id(), body).body());
      assertThat(defaultReceipt.get("decision").asText()).isEqualTo("approve");
      assertThat(defaultReceipt.get("scope").asText())
          .as("DEFAULT 桶 = 全体子 Agent（分不出实例）⇒ 人给的「本会话」降级为一次，回执必须说实话")
          .isEqualTo("once");
    }
  }

  /**
   * S6 薄包装（MAJOR 4 探针）：{@code CommandModeHttpHandler} 经 {@link
   * ApprovalHttpEndpoint#createContext(String, com.sun.net.httpserver.HttpHandler)} 在<b>同一台
   * server、同一端口</b> 注册档位端点——{@code GET}/{@code POST} 均可达，且审批面不受影响。
   *
   * <p>这条同时实测"JVM 上 {@code HttpServer.start()} 之后还能 {@code createContext}"（已知风险）；若某 JVM 不支持，此用例转红。
   *
   * <p>判别性：若档位逻辑被塞进 AgentLib 审批件、或另起第二台 server，则本类的 {@link CommandModeHttpHandler} 不再存在/注册即 404 ⇒
   * 转红。
   */
  @Test
  void modeEndpointIsRegisteredOnTheSameServerAndPort() throws Exception {
    try (Rig rig = new Rig()) {
      CommandModeHttpHandler.register(rig.endpoint, new CommandModeHolder(CommandMode.FULL));

      HttpResponse<String> get = rig.rawGet("/api/commands/mode");
      assertThat(get.statusCode()).isEqualTo(200);
      assertThat(JSON.readTree(get.body()).get("mode").asText()).isEqualTo("full");

      HttpResponse<String> post =
          rig.rawPost("/api/commands/mode", "{\"mode\":\"limited\",\"by\":\"值班人\"}");
      assertThat(post.statusCode()).isEqualTo(200);
      JsonNode receipt = JSON.readTree(post.body());
      assertThat(receipt.get("mode").asText()).isEqualTo("limited");
      assertThat(receipt.get("previous").asText()).as("回执带改动前的档位").isEqualTo("full");
      assertThat(receipt.get("by").asText()).isEqualTo("值班人");

      // 坏值绝不静默当某一档
      HttpResponse<String> bad = rig.rawPost("/api/commands/mode", "{\"mode\":\"partial\"}");
      assertThat(bad.statusCode()).isEqualTo(400);

      // 方法不符 ⇒ 405 带 Allow
      HttpResponse<String> put =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(rig.base() + "/api/commands/mode"))
                      .PUT(HttpRequest.BodyPublishers.noBody())
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(put.statusCode()).isEqualTo(405);
      assertThat(put.headers().firstValue("Allow")).contains("GET, POST");

      // 同一端口上的审批面仍可用
      assertThat(rig.get("").statusCode()).isEqualTo(200);
    }
  }

  /** 未装配 {@link CommandModeHolder} 时档位路径不存在（404）——与迁移前 {@code null} 形态同口径。 */
  @Test
  void modePathIs404WhenNoHolderIsWired() throws Exception {
    try (Rig rig = new Rig()) {
      CommandModeHttpHandler.register(rig.endpoint, null);
      assertThat(rig.rawGet("/api/commands/mode").statusCode()).isEqualTo(404);
      assertThat(rig.rawPost("/api/commands/mode", "{\"mode\":\"limited\"}").statusCode())
          .isEqualTo(404);
    }
  }

  /** 用例脚手架：真登记表 + 编排器（一条 HTTP 通道）+ 真 HTTP 面（端口 0，绑 127.0.0.1）。 */
  private static final class Rig implements AutoCloseable {

    private final PendingApprovals pending = new PendingApprovals();
    private final HttpApprovalChannel channel = new HttpApprovalChannel(pending);
    private final ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(), List.of((ApprovalChannel) channel), pending, Duration.ofSeconds(5), null);
    private final ApprovalHttpEndpoint endpoint;
    private final HttpClient client = HttpClient.newHttpClient();

    Rig() throws Exception {
      endpoint = ApprovalHttpEndpoint.start(0, pending, coordinator);
      channel.markUp(); // 同装配层口径：面真的在监听之后通道才算可用
    }

    HttpResponse<String> get(String suffix) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create(base() + APPROVALS + suffix)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String suffix, String body) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create(base() + APPROVALS + suffix))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
      return "http://127.0.0.1:" + endpoint.boundPort();
    }

    HttpResponse<String> rawGet(String path) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> rawPost(String path, String body) throws Exception {
      return client.send(
          HttpRequest.newBuilder(URI.create(base() + path))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
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

  private static List<String> ids(HttpResponse<String> listResponse) throws Exception {
    JsonNode rows = JSON.readTree(listResponse.body()).get("pending");
    return rows.findValuesAsText("id");
  }
}
