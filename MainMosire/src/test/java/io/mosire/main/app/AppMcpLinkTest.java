package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.mcp.McpServerLinkConfig;
import io.mosire.agentlib.mcp.McpToolSource;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W2a 装配层测试：{@code --mcp-link} 配置驱动外部 MCP 工具源接入主 Agent registry + 主 Agent 工具面经 stdio MCP server 暴露。
 *
 * <p>外部 MCP server 用既有测试模式（AgentLib {@code EchoServerMain}）构造：同 classpath 起一个嵌套测试类的 {@code main}，经
 * AgentToMcpServer 提供 echo 工具——真正的 MCP 握手与 tools/call 语义（裸 {@code sh -c cat} 不 满足 MCP 协议，故沿用该先例）。
 */
class AppMcpLinkTest {

  @TempDir Path tempDir;

  @Test
  void linksExternalMcpServerIntoMainRegistryAndCleansUpOnClose() throws Exception {
    Path pidFile = tempDir.resolve("echo.pid");
    Path quitMarker = tempDir.resolve("echo-quit");
    Path linksFile = writeLinksJson(pidFile, quitMarker);
    BootConfig config = new BootConfig(0, tempDir.resolve("data"), false, "", linksFile, false);
    App app = App.start(config);
    try {
      // W2 验收：配置拉起的 MCP server 工具出现在主 Agent registry
      AgentTool echo = app.runtime().registry().find("echo").orElseThrow();
      assertThat(echo.description()).isEqualTo("原样返回输入文本");
      // 全链路调用：registry → 桥 → McpToolSource → 外部 MCP server
      ToolResult result = echo.execute(contextWith("hi"));
      assertThat(result.success()).isTrue();
      assertThat(result.message()).isEqualTo("echo:hi");
    } finally {
      app.close();
    }
    // close 顺序：bridge 先整组下架工具（registry 恢复原状），再关源子进程
    assertThat(app.runtime().registry().list()).isEmpty();
    waitForExit(pidFile);
  }

  @Test
  void mainAgentToolSurfaceIsListableAndCallableOverStdioMcp() throws Exception {
    Path pidFile = tempDir.resolve("echo.pid");
    Path quitMarker = tempDir.resolve("echo-quit");
    Path linksFile = writeLinksJson(pidFile, quitMarker);
    String classpath = System.getProperty("java.class.path");
    // 测试 JVM 以 MCP 客户端身份拉起完整 App 子进程（java -jar 等价）：主 Agent 的 stdio 即 MCP server 面
    McpServerLinkConfig appProcess =
        new McpServerLinkConfig(
            "mosire-main-app",
            "java",
            List.of(
                "-cp",
                classpath,
                "io.mosire.main.Main",
                "run",
                "--data-dir",
                tempDir.resolve("data").toString(),
                "--port",
                "0",
                "--mcp-link",
                linksFile.toString()),
            Map.of(),
            Duration.ofSeconds(45),
            Duration.ofSeconds(30));
    try (McpToolSource client = new McpToolSource(appProcess)) {
      client.connect();
      // 验收：SDK client listTools 可见主 Agent 工具面（经桥同步进的 echo 工具）
      assertThat(client.listTools()).extracting(AgentTool::name).containsExactly("echo");
      // 穿透调用：客户端 → App 的 AgentToMcpServer → registry → 桥 → 外部 MCP server
      AgentTool echo =
          client.listTools().stream()
              .filter(t -> t.name().equals("echo"))
              .findFirst()
              .orElseThrow();
      ToolResult ok = echo.execute(contextWith("你好"));
      assertThat(ok.success()).isTrue();
      assertThat(ok.message()).isEqualTo("echo:你好");
    }
    // 客户端关闭 → App 子进程 SIGTERM → shutdown 钩子级联关停：bridge → source → 外部 echo 进程退出
    waitForExit(pidFile);
  }

  @Test
  void startupFailureAfterLinksWireReapsMcpProcess() throws Exception {
    Path pidFile = tempDir.resolve("echo.pid");
    Path quitMarker = tempDir.resolve("echo-quit");
    Path linksFile = writeLinksJson(pidFile, quitMarker);
    try (java.net.ServerSocket occupied = new java.net.ServerSocket(0)) {
      // 端口被占：链接已建立后装配失败 → 已起的 MCP 子进程也必须被回收（不留僵尸）
      BootConfig config =
          new BootConfig(
              occupied.getLocalPort(), tempDir.resolve("data"), false, "", linksFile, true);
      assertThatThrownBy(() -> App.start(config)).isInstanceOf(IllegalStateException.class);
    }
    waitForExit(pidFile);
  }

  @Test
  void malformedLinksFileFailsFastAtBoot() {
    Path bad = tempDir.resolve("bad-links.json");
    try {
      Files.writeString(bad, "这不是 JSON");
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    BootConfig config = new BootConfig(0, tempDir.resolve("data"), false, "", bad, false);
    assertThatThrownBy(() -> App.start(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mcp-links");
  }

  /** 组装 mcp-links.json：array 格式（字段见报告；毫秒为整数，缺省走 McpServerLinkConfig 默认值）。 */
  private Path writeLinksJson(Path pidFile, Path quitMarker) throws Exception {
    String classpath = System.getProperty("java.class.path");
    List<Map<String, Object>> links =
        List.of(
            Map.of(
                "name",
                "echo-link",
                "command",
                "java",
                "args",
                List.of(
                    "-cp",
                    classpath,
                    "io.mosire.main.app.AppMcpLinkTest$EchoServerMain",
                    pidFile.toString(),
                    quitMarker.toString()),
                "env",
                Map.of(),
                "startupTimeoutMillis",
                30_000,
                "requestTimeoutMillis",
                30_000));
    Path file = tempDir.resolve("mcp-links.json");
    Files.writeString(file, new ObjectMapper().writeValueAsString(links));
    return file;
  }

  private static ToolContext contextWith(String text) {
    return new ToolContext(
        AccessToken.DEFAULT,
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build(),
        Map.of(),
        Map.of("text", text));
  }

  /** 轮询子进程退出（不超过 10s），超时则 destroyForcibly 兜底（后续测试失败时进程被回收，不静默残留）。 */
  private static void waitForExit(Path pidFile) throws Exception {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      if (!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) {
        return;
      }
      long pid = Long.parseLong(Files.readString(pidFile).trim());
      if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
        Thread.sleep(50);
        continue;
      }
      return;
    }
    if (Files.exists(pidFile)) {
      long pid = Long.parseLong(Files.readString(pidFile).trim());
      ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
    }
  }

  /** 外部 MCP server 子进程入口（AgentToMcpServer stdio）：echo 工具 + 退出标记（AgentLib 测试先例模式）。 */
  public static final class EchoServerMain {

    public static void main(String[] args) throws Exception {
      Path pidFile = Path.of(args[0]);
      Path quitMarker = Path.of(args[1]);
      Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
      ToolRegistry tools = new ToolRegistry();
      tools.register(
          new AgentTool() {
            @Override
            public String name() {
              return "echo";
            }

            @Override
            public String description() {
              return "原样返回输入文本";
            }

            @Override
            public Map<String, Object> jsonSchema() {
              return Map.of(
                  "type", "object", "properties", Map.of("text", Map.of("type", "string")));
            }

            @Override
            public ToolResult execute(ToolContext context) {
              return ToolResult.ok("echo:" + context.arguments().getOrDefault("text", ""));
            }
          });
      try (io.mosire.agentlib.mcp.AgentToMcpServer server =
          io.mosire.agentlib.mcp.AgentToMcpServer.start(tools, "echo-server", "0.1.0-test")) {
        while (!Files.exists(quitMarker)) {
          Thread.sleep(50);
        }
      }
      Files.deleteIfExists(pidFile);
    }
  }
}
