package io.mosire.agentlib.mcp;

/**
 * MCP HTTP 面的安全配置（A1）：TLS、Bearer 认证与“显式不安全”开关。
 *
 * <p><b>安全矩阵由 {@code AgentToMcpServer} 在启动时校验</b>：回环默认允许无认证/无 TLS；非回环必须有认证，或 {@code
 * allowInsecure=true}（显式承担公开无鉴权风险）。TLS 单独不构成认证：非回环 + TLS + 无认证仍需 {@code allowInsecure=true}。
 *
 * @param tls TLS 配置；{@code null} = 不启用
 * @param auth 认证钩子；{@code null} = 无认证
 * @param allowInsecure 显式允许非回环 + 无认证（或 TLS 无认证）的公开面
 */
public record McpHttpSecurity(McpHttpTlsConfig tls, McpHttpAuth auth, boolean allowInsecure) {

  /** 回环开发默认：无 TLS、无认证、不显式不安全。 */
  public static McpHttpSecurity none() {
    return new McpHttpSecurity(null, null, false);
  }

  /** 非回环推荐：TLS + Bearer。 */
  public static McpHttpSecurity tls(McpHttpTlsConfig tls, McpHttpAuth auth) {
    return new McpHttpSecurity(tls, auth, false);
  }

  /** TLS 但无认证（非回环仍需 {@code allowInsecure}）。 */
  public static McpHttpSecurity tls(McpHttpTlsConfig tls) {
    return new McpHttpSecurity(tls, null, false);
  }

  /** Bearer，无 TLS（非回环会 WARN 明文 token）。 */
  public static McpHttpSecurity bearer(BearerTokenVerifier verifier) {
    return new McpHttpSecurity(null, McpHttpAuth.bearer(verifier), false);
  }

  /** 显式不安全：允许非回环 + 无认证（可无 TLS）。 */
  public static McpHttpSecurity insecure() {
    return new McpHttpSecurity(null, null, true);
  }

  public boolean hasTls() {
    return tls != null;
  }

  public boolean hasAuth() {
    return auth != null;
  }

  /** 脱敏：只报告开关，不输出 token/密码。 */
  @Override
  public String toString() {
    return "McpHttpSecurity[tls="
        + hasTls()
        + ", auth="
        + hasAuth()
        + ", allowInsecure="
        + allowInsecure
        + "]";
  }
}
