package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AgentToMcpServer} 离线测试：
 *
 * <ol>
 *   <li><b>变更同步</b>：进程内 + 管道流构造（不走真实 stdio），不开客户端即验证初始注册与 Registry 增删的 addTool/removeTool 联动；
 *   <li><b>端到端</b>：真实 stdio 子进程回声 server，经 {@link McpToolSource}（SDK 官方 client，真实握手）列工具 + 调用。
 * </ol>
 */
class AgentToMcpServerTest {

  /** 回声工具（同步测试与子进程共用）。 */
  private static AgentTool echoTool(String name) {
    return new AgentTool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return "原样返回输入文本";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")));
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok(name + ":" + context.arguments().getOrDefault("text", ""));
      }
    };
  }

  private static AgentTool boomTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "boom";
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.error("BOOM", "故意失败");
      }
    };
  }

  @Test
  void liveSyncWithRegistryChanges() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool("ping"));
    PipedInputStream serverReads = new PipedInputStream();
    PipedOutputStream clientWrites = new PipedOutputStream(serverReads);
    PipedInputStream clientReads = new PipedInputStream();
    PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
    try (AgentToMcpServer server =
        AgentToMcpServer.startWith(
            registry,
            "sync-test",
            "0.1.0",
            ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST)),
            new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), serverReads, serverWrites))) {
      // 初始注册 + 运行时增删经 onChange → addTool/removeTool 联动
      assertThat(server.toolNames()).containsExactly("ping");
      registry.register(echoTool("pong"));
      assertThat(server.toolNames()).containsExactlyInAnyOrder("ping", "pong");
      registry.unregister("pong");
      assertThat(server.toolNames()).containsExactly("ping");
      registry.unregister("ping");
      assertThat(server.toolNames()).isEmpty();
    }
  }

  @Test
  void includeFilterExposesOnlyMatchingToolsAndKeepsThemOut() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.register(echoTool("ping"));
    registry.register(echoTool("secret"));
    PipedInputStream serverReads = new PipedInputStream();
    PipedOutputStream clientWrites = new PipedOutputStream(serverReads);
    PipedInputStream clientReads = new PipedInputStream();
    PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
    try (AgentToMcpServer server =
        AgentToMcpServer.startWith(
            registry,
            "include-test",
            "0.1.0",
            ToolContext.of(AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST)),
            new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), serverReads, serverWrites),
            name -> !name.startsWith("secret"))) {
      // 初始注册即过滤：secret 不暴露
      assertThat(server.toolNames()).containsExactly("ping");
      // 后续变更：命中的加入，被排除的始终在外
      registry.register(echoTool("pong"));
      registry.register(echoTool("secret2"));
      assertThat(server.toolNames()).containsExactlyInAnyOrder("ping", "pong");
      // 暴露面收窄：已暴露工具被排除后（如过滤器语义变化），下次同步时被移除
      registry.register(echoTool("ping2"));
      assertThat(server.toolNames()).containsExactlyInAnyOrder("ping", "ping2", "pong");
      registry.unregister("ping2");
      registry.unregister("ping");
      assertThat(server.toolNames()).containsExactly("pong");
      assertThat(server.toolNames()).doesNotContain("secret", "secret2");
    }
  }

  @Test
  void e2eTalkToStdioSubProcess(@TempDir Path tempDir) throws Exception {
    Path pidFile = tempDir.resolve("echo.pid");
    Path quitMarker = tempDir.resolve("quit");
    String classpath = System.getProperty("java.class.path");
    McpServerLinkConfig config =
        new McpServerLinkConfig(
            "echo-test",
            "java",
            List.of(
                "-cp",
                classpath,
                "io.mosire.agentlib.mcp.AgentToMcpServerTest$EchoServerMain",
                pidFile.toString(),
                quitMarker.toString()),
            Map.of(),
            Duration.ofSeconds(20),
            Duration.ofSeconds(20));
    try (McpToolSource source = new McpToolSource(config)) {
      source.connect();
      List<AgentTool> tools = source.listTools();
      assertThat(tools).extracting(AgentTool::name).contains("echo", "boom");

      AgentTool echo =
          tools.stream().filter(t -> t.name().equals("echo")).findFirst().orElseThrow();
      ToolResult ok =
          echo.execute(
              new ToolContext(
                  AccessToken.GUEST,
                  AgentPermissionSet.unrestricted(AccessToken.GUEST),
                  Map.of(),
                  Map.of("text", "你好")));
      assertThat(ok.success()).isTrue();
      assertThat(ok.message()).isEqualTo("echo:你好");

      AgentTool boom =
          tools.stream().filter(t -> t.name().equals("boom")).findFirst().orElseThrow();
      ToolResult bad =
          boom.execute(
              ToolContext.of(
                  AccessToken.GUEST, AgentPermissionSet.unrestricted(AccessToken.GUEST)));
      assertThat(bad.success()).isFalse();
      assertThat(bad.code()).isEqualTo("BOOM");

      // onChange：连接后订阅不回放快照（初始状态由消费方经 listTools() 主动读取）
      AtomicInteger notifications = new AtomicInteger();
      source.onChange(notifications::incrementAndGet);
      assertThat(notifications.get()).isEqualTo(0);
    } finally {
      Files.writeString(quitMarker, "q");
    }
    waitForExit(pidFile);
  }

  @Test
  void bridgeSyncsExternalToolsIntoRegistryAndCleansUp(@TempDir Path tempDir) throws Exception {
    Path pidFile = tempDir.resolve("bridge.pid");
    Path quitMarker = tempDir.resolve("bridge-quit");
    String classpath = System.getProperty("java.class.path");
    McpServerLinkConfig config =
        new McpServerLinkConfig(
            "echo-test-bridge",
            "java",
            List.of(
                "-cp",
                classpath,
                "io.mosire.agentlib.mcp.AgentToMcpServerTest$EchoServerMain",
                pidFile.toString(),
                quitMarker.toString()),
            Map.of(),
            Duration.ofSeconds(20),
            Duration.ofSeconds(20));
    ToolRegistry registry = new ToolRegistry();
    try (McpToolSource source = new McpToolSource(config)) {
      try (McpSourceBridge bridge = McpSourceBridge.bind(source, registry)) {
        // 先绑定再连接：connect 尾部回放缓存 → diff 幂等同步进注册表
        source.connect();
        assertThat(registry.list())
            .extracting(AgentTool::name)
            .containsExactlyInAnyOrder("echo", "boom");
        assertThat(bridge.synchronizedNames()).containsExactlyInAnyOrder("echo", "boom");
      }
      // bridge close → 整组下架，注册表恢复原状
      assertThat(registry.list()).isEmpty();
    } finally {
      Files.writeString(quitMarker, "q");
    }
    waitForExit(pidFile);
  }

  /** 轮询子进程退出（不超过 10s），超时则 destroyForcibly 兜底。 */
  private static void waitForExit(Path pidFile) throws Exception {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline) {
      String content = readPidFileOrGone(pidFile);
      if (content == null || content.isBlank()) {
        return;
      }
      long pid = Long.parseLong(content.trim());
      if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
        Thread.sleep(50);
        continue;
      }
      return;
    }
    String content = readPidFileOrGone(pidFile);
    if (content != null) {
      ProcessHandle.of(Long.parseLong(content.trim())).ifPresent(ProcessHandle::destroyForcibly);
    }
  }

  /** 读取 pid 文件内容；文件不存在（含读取途中被退出的子进程删除）返回 null——避免 exists/read 竞态。 */
  private static String readPidFileOrGone(Path pidFile) throws IOException {
    try {
      return Files.readString(pidFile);
    } catch (NoSuchFileException e) {
      return null;
    }
  }

  /** 子进程入口：MCP stdio server（echo + boom），写 pid 文件，等待退出标记后关停。 */
  public static final class EchoServerMain {

    public static void main(String[] args) throws Exception {
      Path pidFile = Path.of(args[0]);
      Path quitMarker = Path.of(args[1]);
      Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
      ToolRegistry tools = new ToolRegistry();
      tools.register(echoTool("echo"));
      tools.register(boomTool());
      try (AgentToMcpServer server = AgentToMcpServer.start(tools, "echo-server", "0.1.0-test")) {
        while (!Files.exists(quitMarker)) {
          Thread.sleep(50);
        }
      }
      Files.deleteIfExists(pidFile);
    }
  }
}
