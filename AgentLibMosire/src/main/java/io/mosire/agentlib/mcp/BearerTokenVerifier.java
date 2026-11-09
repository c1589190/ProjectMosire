package io.mosire.agentlib.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Bearer token 验证器（A1）。
 *
 * <p><b>密钥纪律</b>：实现不得记录 token；{@link #constantTime(String)} 用 {@link MessageDigest#isEqual}
 * 做常量时间比较， 不把预期 token 写进异常/日志。
 */
@FunctionalInterface
public interface BearerTokenVerifier {

  /** 验证候选 token；实现不得抛异常作为控制流。 */
  boolean verify(String token);

  /** 常量时间比较的便捷验证器（单 token 场景）。 */
  static BearerTokenVerifier constantTime(String expectedToken) {
    Objects.requireNonNull(expectedToken, "expectedToken");
    if (expectedToken.isBlank()) {
      throw new IllegalArgumentException("expectedToken 不得为空白");
    }
    byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
    return candidate ->
        candidate != null
            && MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8));
  }
}
