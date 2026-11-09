package io.mosire.agentlib.mcp;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * MCP HTTP 面的认证钩子（A1）：传输无关，只拿 method/path/headers，不泄漏 {@code HttpExchange} 或具体 HTTP 框架类型。
 *
 * <p><b>默认</b>：{@link #none()} 表示<b>显式</b>无认证（回环开发面）。生产面用 {@link #bearer(BearerTokenVerifier)}。
 *
 * <p><b>顺序</b>：provider 在“路径精确”之后、closing/方法分派之前调用；认证覆盖 {@code POST}（含 {@code initialize}）、 {@code
 * GET} 监听流与 {@code DELETE}。失败由 provider 回 401 + {@code WWW-Authenticate: Bearer}。
 */
@FunctionalInterface
public interface McpHttpAuth {

  /** 是否放行这次请求；实现不得抛异常作为控制流，不得记录 token。 */
  boolean authorize(String method, String path, Map<String, List<String>> headers);

  /** 显式无认证（回环开发面）。 */
  static McpHttpAuth none() {
    return (method, path, headers) -> true;
  }

  /** Bearer 认证：从 {@code Authorization: Bearer <token>} 取 token，交给验证器。 */
  static McpHttpAuth bearer(BearerTokenVerifier verifier) {
    Objects.requireNonNull(verifier, "verifier");
    return (method, path, headers) -> {
      String token = bearerToken(headers);
      return token != null && verifier.verify(token);
    };
  }

  /** scheme 大小写不敏感；多值取第一个；空 token/非 Bearer ⇒ 空。 */
  private static String bearerToken(Map<String, List<String>> headers) {
    if (headers == null || headers.isEmpty()) {
      return null;
    }
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (entry.getKey() == null || !"Authorization".equalsIgnoreCase(entry.getKey())) {
        continue;
      }
      List<String> values = entry.getValue();
      if (values == null || values.isEmpty()) {
        return null;
      }
      String value = values.get(0);
      if (value == null) {
        return null;
      }
      String trimmed = value.strip();
      if (trimmed.length() <= 7
          || !trimmed.substring(0, 7).toLowerCase(Locale.ROOT).equals("bearer ")) {
        return null;
      }
      String token = trimmed.substring(7).strip();
      return token.isEmpty() ? null : token;
    }
    return null;
  }
}
