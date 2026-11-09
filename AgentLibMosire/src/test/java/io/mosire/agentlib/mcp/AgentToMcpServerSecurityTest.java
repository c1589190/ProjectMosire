package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.llm.ToolAssetResolver;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A1：非回环校验、Bearer 401/放行、TLS（PKCS12）真实握手。 */
class AgentToMcpServerSecurityTest {

  private static final String PATH = "/mcp";

  private static final String INITIALIZE =
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-11-25","capabilities":{},
        "clientInfo":{"name":"security-test","version":"1.0"}}}
      """;

  @TempDir Path tempDir;

  @Test
  void nonLoopbackWithoutAuthOrInsecureFailsAtStartup() {
    assertThatThrownBy(() -> start("0.0.0.0", McpHttpSecurity.none()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Bearer")
        .hasMessageContaining("allowInsecure");

    McpHttpTlsConfig dummyTls =
        new McpHttpTlsConfig(tempDir.resolve("missing.p12"), "changeit", null, null);
    assertThatThrownBy(() -> start("0.0.0.0", McpHttpSecurity.tls(dummyTls)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Bearer");
  }

  @Test
  void loopbackBearerChallengesAndAcceptsInitialize() throws Exception {
    try (AgentToMcpServer server =
        start(
            "127.0.0.1",
            McpHttpSecurity.bearer(BearerTokenVerifier.constantTime("secret-token")))) {
      URI uri = URI.create("http://127.0.0.1:" + server.boundPort() + PATH);
      HttpClient client = HttpClient.newHttpClient();

      HttpResponse<String> missing = initialize(client, uri, null);
      assertThat(missing.statusCode()).isEqualTo(401);
      assertThat(missing.headers().firstValue("WWW-Authenticate").orElse("")).contains("Bearer");
      assertThat(missing.body()).contains("未认证");

      HttpResponse<String> wrong = initialize(client, uri, "wrong");
      assertThat(wrong.statusCode()).isEqualTo(401);

      HttpResponse<String> ok = initialize(client, uri, "secret-token");
      assertThat(ok.statusCode()).isEqualTo(200);
      assertThat(ok.body()).contains("protocolVersion").contains("2025-11-25");

      HttpResponse<String> getWithoutAuth =
          client.send(
              HttpRequest.newBuilder(uri).header("Accept", "text/event-stream").GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(getWithoutAuth.statusCode()).isEqualTo(401);
    }
  }

  @Test
  void tlsWithBearerServesHttpsAndRejectsUnauthenticated() throws Exception {
    Path keystore = generatePkcs12();
    McpHttpTlsConfig tls = new McpHttpTlsConfig(keystore, "changeit", "changeit", "PKCS12");
    McpHttpSecurity security =
        McpHttpSecurity.tls(tls, McpHttpAuth.bearer(BearerTokenVerifier.constantTime("tls-token")));

    try (AgentToMcpServer server = start("127.0.0.1", security)) {
      URI uri = URI.create("https://127.0.0.1:" + server.boundPort() + PATH);
      HttpClient client = trustAllClient();

      HttpResponse<String> missing = initialize(client, uri, null);
      assertThat(missing.statusCode()).isEqualTo(401);

      HttpResponse<String> ok = initialize(client, uri, "tls-token");
      assertThat(ok.statusCode()).isEqualTo(200);
      assertThat(ok.body()).contains("protocolVersion");
    }
  }

  private static AgentToMcpServer start(String host, McpHttpSecurity security) {
    return AgentToMcpServer.startHttp(
        host,
        0,
        PATH,
        new ToolRegistry(),
        "security-test",
        "1.0.0",
        ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST)),
        ToolCallAuthorizer.standard(),
        ToolAssetResolver.none(),
        security);
  }

  private static HttpResponse<String> initialize(HttpClient client, URI uri, String token)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private Path generatePkcs12() throws Exception {
    Path keystore = tempDir.resolve("server.p12");
    String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    Process process =
        new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias",
                "server",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=127.0.0.1",
                "-validity",
                "1",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystore.toString(),
                "-storepass",
                "changeit",
                "-keypass",
                "changeit",
                "-ext",
                "SAN=ip:127.0.0.1,dns:localhost")
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("keytool 超时").isTrue();
    assertThat(process.exitValue()).as("keytool 失败: %s", output).isZero();
    assertThat(Files.size(keystore)).isPositive();
    return keystore;
  }

  private static HttpClient trustAllClient() throws Exception {
    TrustManager[] trustAll =
        new TrustManager[] {
          new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
          }
        };
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, trustAll, new SecureRandom());
    return HttpClient.newBuilder().sslContext(context).build();
  }
}
