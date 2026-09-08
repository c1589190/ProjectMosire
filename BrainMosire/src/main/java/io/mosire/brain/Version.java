package io.mosire.brain;

/** 模块标识常量，供冒烟测试、日志与发行信息使用。 */
public final class Version {

  /** 本模块的 Maven artifactId。 */
  public static final String ARTIFACT_ID = "brain-mosire";

  /** 本模块的版本字符串。 */
  public static final String VERSION = "0.1.0-SNAPSHOT";

  private Version() {
    throw new AssertionError("No instances");
  }
}
