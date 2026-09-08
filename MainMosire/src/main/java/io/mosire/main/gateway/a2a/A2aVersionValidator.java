package io.mosire.main.gateway.a2a;

import java.util.List;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.VersionNotSupportedError;

/**
 * A2A 版本协商（计划 §5.2.3；D6 不引 server-common → 语义镜像 {@code
 * org.a2aproject.sdk.server.version.A2AVersionValidator}，规则逐条照抄并注明出处）。
 *
 * <p>规则（规范 §3.6.2 + 镜像实现）：
 *
 * <ul>
 *   <li>Header {@code A2A-Version} 缺失/空白 → 按 {@code "0.3"} 处理（向下兼容老客户端）；与镜像一致，缺省值直接参与比对；
 *   <li>“兼容” = 支持的协议版本中任一版本主号与请求相同（minor 任意兼容）；
 *   <li>任一版本解析失败（格式非法）→ 判为不兼容（镜像行为：短路返回 false）；
 *   <li>不兼容 → {@link VersionNotSupportedError}（-32009）。
 * </ul>
 */
public final class A2aVersionValidator {

  private A2aVersionValidator() {}

  /**
   * 校验请求版本（抛 {@link VersionNotSupportedError} 表示失败，错误消息格式 = 镜像实现）。
   *
   * @param card 本服务 Agent Card（supportedInterfaces 的 protocolVersion 集合为支持口径）
   * @param requestedVersion HTTP 请求头原文（可空 = 未发）
   */
  public static void validate(AgentCard card, String requestedVersion)
      throws VersionNotSupportedError {
    List<String> supported =
        card.supportedInterfaces().stream()
            .map(AgentInterface::protocolVersion)
            .distinct()
            .toList();
    String effective =
        requestedVersion == null || requestedVersion.isBlank() ? "0.3" : requestedVersion.trim();
    if (!isCompatible(supported, effective)) {
      throw new VersionNotSupportedError(
          null,
          "Protocol version '" + effective + "' is not supported. Supported versions: " + supported,
          null);
    }
  }

  /** 兼容性判断（镜像 {@code A2AVersionValidator.isVersionCompatible}——包括“任一支持版本解析失败即返回 false”的短路行为）。 */
  static boolean isCompatible(List<String> supportedVersions, String requestedVersion) {
    for (String supportedVersion : supportedVersions) {
      try {
        VersionParts supportedParts = parse(supportedVersion);
        VersionParts requestedParts = parse(requestedVersion);
        if (supportedParts.major() == requestedParts.major()) {
          return true;
        }
      } catch (IllegalArgumentException e) {
        return false;
      }
    }
    return false;
  }

  private static VersionParts parse(String version) {
    if (version == null || version.trim().isEmpty()) {
      throw new IllegalArgumentException("Version cannot be null or empty");
    }
    String[] parts = version.split("\\.");
    if (parts.length < 2) {
      throw new IllegalArgumentException(
          "Version must have at least major.minor format: " + version);
    }
    try {
      return new VersionParts(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid version format: " + version, e);
    }
  }

  private record VersionParts(int major, int minor) {}
}
