package io.mosire.main.app;

import java.nio.file.Path;
import java.util.Objects;

/**
 * `run` 子命令的启动配置（命令行参数解析结果）。
 *
 * <p>W2a 新增：{@code mcpLinks}（外部 MCP 工具源配置文件路径，{@code null} = 不接入）与 {@code mcpExpose} （主 Agent
 * 工具注册表经 stdio MCP server 对外暴露，默认启用——R7 警示下保持 GUEST 默认身份，收窄属 M3 权限接线）。
 */
public record BootConfig(
    int port, Path dataDir, boolean demo, String demoMessage, Path mcpLinks, boolean mcpExpose) {

  public static final Path DEFAULT_DATA_DIR = Path.of(".work/mosire");

  public BootConfig {
    Objects.requireNonNull(dataDir, "dataDir");
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("端口越界: " + port);
    }
  }

  public static BootConfig defaults() {
    return new BootConfig(8080, DEFAULT_DATA_DIR, false, "", null, true);
  }
}
