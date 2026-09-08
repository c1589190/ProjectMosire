package io.mosire.main.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.mcp.McpServerLinkConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * mcp-links.json 配置加载器（W2 步骤 1：装配层读配置 → {@link McpServerLinkConfig} 列表）。
 *
 * <p>文件格式 = JSON 数组，每项字段：{@code name}/{@code command}（必填）、{@code args}/{@code env}{@code
 * startupTimeoutMillis}/{@code requestTimeoutMillis}（可选；缺省走 {@link McpServerLinkConfig} 的默认
 * 15s/60s）。时间上限用整数毫秒（避免 ISO-8601 Duration 对 jsr310 模块的隐含依赖）。
 *
 * <p>错误语义：文件不存在/JSON 非法/字段校验失败均抛 {@link IllegalArgumentException}（带文件路径上下文）， 由 CLI
 * 层映射为"参数错误"退出——声明的链接是启动必需能力，不做逐链接静默跳过（重连/降级属 M3 范围）。
 */
final class McpLinkLoader {

  private static final ObjectMapper JSON = new ObjectMapper();

  private McpLinkLoader() {}

  static List<McpServerLinkConfig> load(Path file) {
    final List<Entry> entries;
    try {
      entries = JSON.readValue(Files.readString(file), new TypeReference<List<Entry>>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("mcp-links 配置解析失败: " + file, e);
    }
    try {
      return entries.stream().map(Entry::toConfig).toList();
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "mcp-links 配置校验失败: " + file + "（" + e.getMessage() + "）", e);
    }
  }

  /** 文件条目（JSON 映射）；超时字段毫秒计，null = 用 {@link McpServerLinkConfig} 默认值。 */
  private record Entry(
      String name,
      String command,
      List<String> args,
      Map<String, String> env,
      Long startupTimeoutMillis,
      Long requestTimeoutMillis) {

    McpServerLinkConfig toConfig() {
      return new McpServerLinkConfig(
          name,
          command,
          args,
          env,
          startupTimeoutMillis == null ? null : Duration.ofMillis(startupTimeoutMillis),
          requestTimeoutMillis == null ? null : Duration.ofMillis(requestTimeoutMillis));
    }
  }
}
