package io.mosire.main.app;

import java.nio.file.Path;
import java.util.Objects;

/**
 * `run` 子命令的启动配置（命令行参数解析结果）。
 *
 * <p>W2a 新增：{@code mcpLinks}（外部 MCP 工具源配置文件路径，{@code null} = 不接入）与 {@code mcpExpose} （主 Agent
 * 工具注册表经 stdio MCP server 对外暴露，默认启用——R7 警示下保持 GUEST 默认身份，收窄属 M3 权限接线）。
 *
 * <p>W2b 新增：{@code a2aHost}/{@code a2aPort}（A2A 网关绑定地址/端口——默认 127.0.0.1 + 空闲端口，网络面红线： 对外打开必须显式配置）。
 *
 * <p>W3b 新增：{@code templatesDir}（子 Agent 模板目录 {@code configs/agents}，{@code null} = 本进程不启用子 Agent
 * 编排——不装配 SubagentManager，子命令 {@code agent} 仅在装配了这一能力的进程内可用）。
 */
public record BootConfig(
    int port,
    Path dataDir,
    boolean demo,
    String demoMessage,
    Path mcpLinks,
    boolean mcpExpose,
    String a2aHost,
    int a2aPort,
    Path templatesDir) {

  public static final Path DEFAULT_DATA_DIR = Path.of(".work/mosire");

  /** A2A 网关默认绑定地址（红线：默认仅本机）。 */
  public static final String DEFAULT_A2A_HOST = "127.0.0.1";

  /** A2A 网关默认端口：0 = 空闲端口自动分配。 */
  public static final int DEFAULT_A2A_PORT = 0;

  /** 子 Agent 模板目录默认位置（{@code <cwd>/configs/agents/<id>.json}，中期计划 W3 约定）。 */
  public static final Path DEFAULT_TEMPLATES_DIR = Path.of("configs", "agents");

  /** W3a 兼容构造：不启用子 Agent 编排（templatesDir = null）。 */
  public BootConfig(
      int port,
      Path dataDir,
      boolean demo,
      String demoMessage,
      Path mcpLinks,
      boolean mcpExpose,
      String a2aHost,
      int a2aPort) {
    this(port, dataDir, demo, demoMessage, mcpLinks, mcpExpose, a2aHost, a2aPort, null);
  }

  public BootConfig {
    Objects.requireNonNull(dataDir, "dataDir");
    Objects.requireNonNull(a2aHost, "a2aHost");
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("端口越界: " + port);
    }
    if (a2aPort < 0 || a2aPort > 65535) {
      throw new IllegalArgumentException("A2A 端口越界: " + a2aPort);
    }
  }

  public static BootConfig defaults() {
    return new BootConfig(
        8080, DEFAULT_DATA_DIR, false, "", null, true, DEFAULT_A2A_HOST, DEFAULT_A2A_PORT);
  }
}
