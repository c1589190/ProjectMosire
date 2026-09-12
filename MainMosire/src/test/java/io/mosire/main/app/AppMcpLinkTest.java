package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.mcp.McpServerLinkConfig;
import io.mosire.agentlib.mcp.McpToolSource;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
    BootConfig config =
        new BootConfig(0, tempDir.resolve("data"), false, "", linksFile, false, "127.0.0.1", 0);
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
    // close 顺序：bridge 先整组下架工具（registry 恢复到"桥接前"的原状），再关源子进程。
    // 注意"原状"不是空表：S4-C 起主 Agent 的 builtin 面含 `bash`（ShellTool）。断言写全等而非"包含"——
    // 桥若没把 echo 摘干净，这里会多出 echo ⇒ 转红。
    assertThat(app.runtime().registry().list()).extracting(AgentTool::name).containsExactly("bash");
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
                linksFile.toString(),
                "--fake"),
            Map.of(),
            Duration.ofSeconds(45),
            Duration.ofSeconds(30));
    try (McpToolSource client = new McpToolSource(appProcess)) {
      client.connect();
      // 验收：SDK client listTools 可见主 Agent 工具面 = 经桥同步进的 echo + 主 Agent 的 builtin（S4-C 起含 bash）。
      // 仍是全等断言：少一个（桥没同步过来）或多一个（不该外发的漏出去了）都转红。
      assertThat(client.listTools())
          .extracting(AgentTool::name)
          .containsExactlyInAnyOrder("echo", "bash");
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
              occupied.getLocalPort(),
              tempDir.resolve("data"),
              false,
              "",
              linksFile,
              true,
              "127.0.0.1",
              0);
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
    BootConfig config =
        new BootConfig(0, tempDir.resolve("data"), false, "", bad, false, "127.0.0.1", 0);
    assertThatThrownBy(() -> App.start(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mcp-links");
  }

  /**
   * 启动失败路径的存储连接回收（P3-3 补丁）：{@code wireMcpLinks} 抛错时控制流<b>在进入 {@code start()} 的主 try 之前</b>就逃出， 主
   * catch 够不着——此前已打开的 events/conversations 两条 SQLite 连接必须被释放，否则每次启动失败泄漏两条。
   *
   * <p>判据 = 本进程还剩几个指向该库的 fd（Linux {@code /proc/self/fd}）：sqlite-jdbc 的连接把库文件（及 WAL 模式的 {@code
   * -wal}/{@code -shm} 伴生文件）持有为打开的 fd，直到 {@code connection.close()}；计数 &gt;0 即连接仍开。 {@code start()}
   * 抛错瞬间取样，不经 GC（否则 finalizer 兜底关闭会把泄漏掩盖成"通过"）。
   */
  @Test
  void startupFailureInsideLinkWiringReleasesStoreConnections() throws Exception {
    assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")), "需要 /proc/self/fd（Linux）观察连接释放");
    Path dataDir = tempDir.resolve("data");
    // 必然让 wireMcpLinks 失败的配置：链接文件不存在（McpLinkLoader 在读文件时就抛 IllegalArgumentException）
    Path missingLinks = tempDir.resolve("no-such-links.json");
    BootConfig config = new BootConfig(0, dataDir, false, "", missingLinks, false, "127.0.0.1", 0);

    assertThatThrownBy(() -> App.start(config)).isInstanceOf(IllegalArgumentException.class);

    assertThat(openLibraryFds(dataDir.resolve("events.db")))
        .as("start() 抛错后不得残留指向 events.db 的打开连接")
        .isZero();
  }

  /**
   * 装配中途失败（端口占用）的连接回收：失败发生在 {@code start()} 的主 try 内，主 catch 的回收清单同样必须覆盖已打开的 events 连接 （P3-3
   * 之前这里只回收 conversations，events 是漏网的一条）。判据同前：抛错后指向 events.db 的 fd 归零。
   */
  @Test
  void startupFailureAfterStoresOpenReleasesEventConnection() throws Exception {
    assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")), "需要 /proc/self/fd（Linux）观察连接释放");
    Path dataDir = tempDir.resolve("data");
    try (java.net.ServerSocket occupied = new java.net.ServerSocket(0)) {
      BootConfig config =
          new BootConfig(occupied.getLocalPort(), dataDir, false, "", null, false, "127.0.0.1", 0);
      assertThatThrownBy(() -> App.start(config)).isInstanceOf(IllegalStateException.class);
    }
    assertThat(openLibraryFds(dataDir.resolve("events.db")))
        .as("start() 抛错后不得残留指向 events.db 的打开连接")
        .isZero();
  }

  /**
   * 本进程打开的、指向 {@code dbFile}（或其 -wal/-shm 伴生文件）的 fd 数。目录也一并比对：每个测试用例有自己的 dataDir， 只看文件名会把同 fork
   * 里其他测试的库连接一并数进来（判据必须只认本用例那一份）。
   */
  private static long openLibraryFds(Path dbFile) throws IOException {
    Path dir = dbFile.toAbsolutePath().getParent();
    String name = dbFile.getFileName().toString();
    try (Stream<Path> fds = Files.list(Path.of("/proc/self/fd"))) {
      return fds.filter(
              fd -> {
                try {
                  Path target = Files.readSymbolicLink(fd);
                  return target.startsWith(dir)
                      && target.getFileName() != null
                      && target.getFileName().toString().startsWith(name);
                } catch (IOException e) {
                  return false; // 列举与读取之间该 fd 已被关闭：与本判据无关
                }
              })
          .count();
    }
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
