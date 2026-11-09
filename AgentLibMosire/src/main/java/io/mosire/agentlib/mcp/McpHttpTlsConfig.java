package io.mosire.agentlib.mcp;

import java.nio.file.Path;
import java.util.Objects;

/**
 * MCP HTTPS 的证书配置（A1）。
 *
 * <p>只支持 JDK 原生 keystore（PKCS12/JKS）；PEM 由调用方先转。密码只用于 {@code KeyStore.load} / {@code
 * KeyManagerFactory.init}，<b>不出现在日志、异常消息、事件</b>；{@link #toString()} 已脱敏。
 *
 * @param keystore keystore 文件路径（非 null）
 * @param keystorePassword keystore 密码（非 null；不落日志）
 * @param keyPassword key 密码（null ⇒ 同 keystore 密码）
 * @param keystoreType keystore 类型（null/空白 ⇒ {@code PKCS12}）
 */
public record McpHttpTlsConfig(
    Path keystore, String keystorePassword, String keyPassword, String keystoreType) {

  public McpHttpTlsConfig {
    Objects.requireNonNull(keystore, "keystore");
    Objects.requireNonNull(keystorePassword, "keystorePassword");
    keyPassword = keyPassword == null ? keystorePassword : keyPassword;
    keystoreType = keystoreType == null || keystoreType.isBlank() ? "PKCS12" : keystoreType.strip();
  }

  /** 脱敏：密码永不进日志/异常/toString。 */
  @Override
  public String toString() {
    return "McpHttpTlsConfig[keystore="
        + keystore
        + ", keystoreType="
        + keystoreType
        + ", password=<redacted>]";
  }
}
