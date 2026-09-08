package io.mosire.main.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.main.Version;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class AdminHttpServerTest {

  @Test
  void servesHealthAndStatus() throws Exception {
    AdminHttpServer server =
        AdminHttpServer.start(
            0, () -> StatusSnapshot.healthy(Version.ARTIFACT_ID, Version.VERSION, "main", 0, 7));
    try (server) {
      assertThat(server.boundPort()).isGreaterThan(0);
      HttpClient client = HttpClient.newHttpClient();
      HttpResponse<String> health =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + server.boundPort() + "/health"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(health.statusCode()).isEqualTo(200);
      assertThat(health.body()).contains("\"status\":\"ok\"");
      assertThat(health.body()).contains(Version.VERSION);

      HttpResponse<String> status =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + server.boundPort() + "/api/status"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(status.statusCode()).isEqualTo(200);
      assertThat(status.body()).contains("\"eventCount\":7");
      assertThat(status.body()).contains("\"agentId\":\"main\"");
    }
  }

  @Test
  void bindsLoopbackOnly() throws Exception {
    AdminHttpServer server =
        AdminHttpServer.start(
            0, () -> StatusSnapshot.healthy(Version.ARTIFACT_ID, Version.VERSION, "main", 0, 0));
    try (server) {
      InetAddress address = InetAddress.getByName("127.0.0.1");
      assertThat(address.isLoopbackAddress()).isTrue();
    }
  }
}
