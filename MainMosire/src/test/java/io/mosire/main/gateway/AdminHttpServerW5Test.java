package io.mosire.main.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.subagent.SubagentInstance;
import io.mosire.brain.subagent.SubagentStatus;
import io.mosire.main.Version;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * W5 AdminREST 扩展测试（guest 只读断言——"guest" 即<b>无凭据</b>调用）：{@code GET /api/agents}（SubagentManager
 * 快照）、{@code GET /api/tools}（registry 名单）、{@code GET /api/events}（只读 + offset/limit 分页 + 过滤透传）；
 * 三个端点均只接受 GET（其余方法 405——只读面）。
 */
class AdminHttpServerW5Test {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private final AtomicReference<EventQuery> lastQuery = new AtomicReference<>();

  private static AdminHttpServer server(
      AtomicReference<EventQuery> lastQuery, Function<EventQuery, List<Event>> eventsSource) {
    return AdminHttpServer.start(
        0,
        () -> StatusSnapshot.healthy(Version.ARTIFACT_ID, Version.VERSION, "main", 25),
        () ->
            List.of(
                new SubagentInstance(
                    "sub-1",
                    "reader",
                    "问候主流程",
                    AgentConfig.builder("sub-1").build(),
                    AgentPermissionSet.system(),
                    2,
                    SubagentStatus.RUNNING)),
        () -> List.of("echo", "spawn_sub_agent"),
        query -> {
          lastQuery.set(query);
          // 25 条伪造事件（seq 100..76，倒序——与 Store 契约一致：最新在前）
          List<Event> events = new ArrayList<>();
          for (long seq = 100; seq > 75; seq--) {
            events.add(
                new Event(
                    seq,
                    Instant.parse("2026-09-09T00:00:00Z"),
                    "tool.call",
                    "main",
                    "{\"tool\":\"echo\"}",
                    "c-1"));
          }
          return events;
        });
  }

  private static HttpResponse<String> get(int port, String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(int port, String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** guest 只读 + 数据来源断言：无任何凭据头直接 200，body 来自 SubagentManager 快照。 */
  @Test
  void agentsEndpointIsGuestReadOnlyFromManagerSnapshot() throws Exception {
    try (AdminHttpServer server = server(lastQuery, q -> List.of())) {
      HttpResponse<String> resp = get(server.boundPort(), "/api/agents");
      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.body()).contains("\"instanceId\":\"sub-1\"");
      assertThat(resp.body()).contains("\"templateId\":\"reader\"");
      assertThat(resp.body()).contains("\"status\":\"RUNNING\"");
      assertThat(resp.body()).contains("\"depth\":2");
    }
  }

  /** guest 只读 + registry 名单断言。 */
  @Test
  void toolsEndpointIsGuestReadOnlyWithRegistryNames() throws Exception {
    try (AdminHttpServer server = server(lastQuery, q -> List.of())) {
      HttpResponse<String> resp = get(server.boundPort(), "/api/tools");
      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(resp.body()).contains("\"echo\"");
      assertThat(resp.body()).contains("\"spawn_sub_agent\"");
    }
  }

  /** 只读面：三个新端点非 GET 一律 405。 */
  @Test
  void newEndpointsRejectNonGetWith405() throws Exception {
    try (AdminHttpServer server = server(lastQuery, q -> List.of())) {
      assertThat(post(server.boundPort(), "/api/agents").statusCode()).isEqualTo(405);
      assertThat(post(server.boundPort(), "/api/tools").statusCode()).isEqualTo(405);
      assertThat(post(server.boundPort(), "/api/events").statusCode()).isEqualTo(405);
    }
  }

  /**
   * 分页断言 offset/limit：handler 以 {@code EventQuery(limit = offset + limit)} 一次拉取再由存储侧跳过 offset 条
   * （复用现有查询 API——EventStore 无 offset 形态）；响应 5 条、首条 seq=97、末条 seq=93。
   */
  @Test
  void eventsEndpointPaginationHonorsOffsetAndLimit() throws Exception {
    AdminHttpServer server = server(lastQuery, q -> List.of());
    try (server) {
      HttpResponse<String> resp = get(server.boundPort(), "/api/events?limit=5&offset=3");
      assertThat(resp.statusCode()).isEqualTo(200);
      // 复用查询 API 的形态：limit 参数 = offset + limit（8）
      EventQuery captured = lastQuery.get();
      assertThat(captured.limit()).isEqualTo(8);
      assertThat(captured.beforeSeq()).isEqualTo(-1);
      assertThat(resp.body()).contains("\"seq\":97");
      assertThat(resp.body()).contains("\"seq\":93");
      assertThat(resp.body()).doesNotContain("\"seq\":100");
      assertThat(resp.body()).doesNotContain("\"seq\":92");
    }
  }

  /** 分页/过滤参数透传与合法性：过滤条件进入 EventQuery；非法参数 400。 */
  @Test
  void eventsEndpointForwardsFiltersAndRejectsBadParams() throws Exception {
    AdminHttpServer server = server(lastQuery, q -> List.of());
    try (server) {
      HttpResponse<String> filtered =
          get(
              server.boundPort(),
              "/api/events?type=tool.call&agent=main&correlationId=c-1&limit=2");
      assertThat(filtered.statusCode()).isEqualTo(200);
      EventQuery captured = lastQuery.get();
      assertThat(captured.agent()).isEqualTo("main");
      assertThat(captured.type()).isEqualTo("tool.call");
      assertThat(captured.correlationId()).isEqualTo("c-1");

      assertThat(get(server.boundPort(), "/api/events?limit=0").statusCode()).isEqualTo(400);
      assertThat(get(server.boundPort(), "/api/events?offset=-1").statusCode()).isEqualTo(400);
      assertThat(get(server.boundPort(), "/api/events?limit=abc").statusCode()).isEqualTo(400);
    }
  }
}
