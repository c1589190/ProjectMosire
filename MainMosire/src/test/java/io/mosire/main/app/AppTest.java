package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.brain.runtime.EventTypes;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppTest {

  @TempDir Path tempDir;

  @Test
  void demoBootRunsTurnPersistsEventsAndServesHealth() throws Exception {
    BootConfig config = new BootConfig(0, tempDir.resolve("data"), true, App.DEMO_USER_MESSAGE);
    App app = App.start(config);
    try (app) {
      // demo 回合结果：事件已入库（lifecycle + turn 至少 2 条）
      assertThat(app.eventCount()).isGreaterThanOrEqualTo(2);
      assertThat(app.queryEvents(new EventQuery("", EventTypes.CONVERSATION_TURN, "", -1, 10)))
          .hasSize(1);
      assertThat(app.boundPort()).isGreaterThan(0);

      // HTTP 健康检查（验收：curl /health OK）
      HttpResponse<String> health =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + app.boundPort() + "/health"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(health.statusCode()).isEqualTo(200);
      String body = new ObjectMapper().readTree(health.body()).get("status").asText();
      assertThat(body).isEqualTo("ok");
    }
    // 关闭后数据库仍可重开读取（checkpoint 已落盘）
    try (SqliteEventStore reopened =
        SqliteEventStore.open(tempDir.resolve("data").resolve("events.db"))) {
      assertThat(reopened.count()).isGreaterThanOrEqualTo(2);
    }
  }
}
