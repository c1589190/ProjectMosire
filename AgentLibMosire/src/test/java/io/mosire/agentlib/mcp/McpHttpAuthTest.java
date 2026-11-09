package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A1：Bearer 解析、常量时间验证器、TLS/安全配置的脱敏。 */
class McpHttpAuthTest {

  @Test
  void bearerAcceptsCaseInsensitiveSchemeAndRejectsWrongOrMissingToken() {
    McpHttpAuth auth = McpHttpAuth.bearer(BearerTokenVerifier.constantTime("secret"));

    assertThat(auth.authorize("POST", "/mcp", headers("Authorization", "Bearer secret"))).isTrue();
    assertThat(auth.authorize("POST", "/mcp", headers("authorization", "bearer secret"))).isTrue();
    assertThat(auth.authorize("POST", "/mcp", headers("Authorization", "Bearer wrong"))).isFalse();
    assertThat(auth.authorize("POST", "/mcp", headers("Authorization", "Basic secret"))).isFalse();
    assertThat(auth.authorize("POST", "/mcp", headers("Authorization", "Bearer "))).isFalse();
    assertThat(auth.authorize("POST", "/mcp", Map.of())).isFalse();
    assertThat(auth.authorize("POST", "/mcp", null)).isFalse();
  }

  @Test
  void noneExplicitlyAllowsEverything() {
    assertThat(McpHttpAuth.none().authorize("GET", "/mcp", Map.of())).isTrue();
  }

  @Test
  void emptyExpectedTokenIsRejectedLoudly() {
    assertThatThrownBy(() -> BearerTokenVerifier.constantTime(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedToken");
  }

  @Test
  void tlsConfigAndSecurityDoNotLeakSecretsInToString() {
    McpHttpTlsConfig tls = new McpHttpTlsConfig(Path.of("server.p12"), "changeit", null, null);
    McpHttpSecurity security = McpHttpSecurity.tls(tls, McpHttpAuth.none());

    assertThat(tls.toString()).contains("<redacted>").doesNotContain("changeit");
    assertThat(security.toString()).contains("tls=true").doesNotContain("changeit");
  }

  private static Map<String, List<String>> headers(String name, String value) {
    return Map.of(name, List.of(value));
  }
}
