package io.mosire.main.app;

import java.nio.file.Path;
import java.util.Objects;

/** `run` 子命令的启动配置（命令行参数解析结果）。 */
public record BootConfig(int port, Path dataDir, boolean demo, String demoMessage) {

  public static final Path DEFAULT_DATA_DIR = Path.of(".work/mosire");

  public BootConfig {
    Objects.requireNonNull(dataDir, "dataDir");
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("端口越界: " + port);
    }
  }

  public static BootConfig defaults() {
    return new BootConfig(8080, DEFAULT_DATA_DIR, false, "");
  }
}
