package io.mosire.agentlib;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** 模块标识常量，供冒烟测试、日志与发行信息使用。 */
public final class Version {
  private static final String VERSION_RESOURCE = "/io/mosire/agentlib/version.properties";

  /** 本模块的 Maven artifactId。 */
  public static final String ARTIFACT_ID = "agentlib-mosire";

  /** 本模块的版本字符串，来自 Maven 资源过滤后的 version.properties。 */
  public static final String VERSION = loadVersion();

  private Version() {
    throw new AssertionError("No instances");
  }

  private static String loadVersion() {
    Properties properties = new Properties();
    try (InputStream input = Version.class.getResourceAsStream(VERSION_RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("缺少版本资源: " + VERSION_RESOURCE);
      }
      properties.load(input);
    } catch (IOException e) {
      throw new IllegalStateException("读取版本资源失败: " + VERSION_RESOURCE, e);
    }

    String version = properties.getProperty("version", "").trim();
    if (version.isEmpty()) {
      throw new IllegalStateException("版本资源缺少 version: " + VERSION_RESOURCE);
    }
    if (version.contains("${")) {
      throw new IllegalStateException("版本资源未完成 Maven 过滤: " + version);
    }
    return version;
  }
}
