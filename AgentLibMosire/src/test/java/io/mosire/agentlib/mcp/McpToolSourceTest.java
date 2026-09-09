package io.mosire.agentlib.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpToolSourceTest {

  @Test
  void adaptsToolMetadataAndDelegatesCall() {
    McpSchema.Tool tool =
        McpSchema.Tool.builder("echo", Map.of("type", "object"))
            .title("回显")
            .description("原样返回输入")
            .build();
    McpCaller caller = (name, arguments) -> ToolResult.ok(name + ":" + arguments.get("text"));

    AgentTool adapted = McpToolAdapter.of(tool, caller);
    assertThat(adapted.name()).isEqualTo("echo");
    assertThat(adapted.description()).isEqualTo("原样返回输入");
    assertThat(adapted.jsonSchema()).containsEntry("type", "object");

    ToolContext context =
        new ToolContext(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build(),
            Map.of(),
            Map.of("text", "hi"));
    ToolResult result = adapted.execute(context);
    assertThat(result.success()).isTrue();
    assertThat(result.message()).isEqualTo("echo:hi"); // 参数经 ToolContext 传入调用方
  }

  @Test
  void mapsMcpCallToolResultToToolResult() {
    McpSchema.CallToolResult ok =
        new McpSchema.CallToolResult(
            List.of(
                McpSchema.TextContent.builder("结果一").build(),
                McpSchema.TextContent.builder("结果二").build()),
            false,
            null,
            Map.of());
    ToolResult mapped = McpToolSource.map(ok);
    assertThat(mapped.success()).isTrue();
    assertThat(mapped.message()).isEqualTo("结果一\n结果二");

    McpSchema.CallToolResult error =
        new McpSchema.CallToolResult(
            List.of(McpSchema.TextContent.builder("炸了").build()), true, null, Map.of());
    ToolResult mappedError = McpToolSource.map(error);
    assertThat(mappedError.success()).isFalse();
    assertThat(mappedError.code()).isEqualTo("MCP_TOOL_ERROR");
    assertThat(mappedError.message()).isEqualTo("炸了");
  }

  @Test
  void deadRemoteProcessMapsToToolErrorAndReconnectClearsDisconnectedFlag(@TempDir Path tempDir)
      throws Exception {
    Path pidFile = tempDir.resolve("echo.pid");
    Path quitMarker = tempDir.resolve("echo-quit");
    McpServerLinkConfig config = echoServerConfig(pidFile, quitMarker);
    try (McpToolSource source = new McpToolSource(config)) {
      source.connect();
      AgentTool echo = findTool(source.listTools(), "echo");
      ToolContext context = contextWith("hi");

      // 连接正常：调用成功且未标记断连
      assertThat(echo.execute(context).success()).isTrue();
      assertThat(source.isDisconnected()).isFalse();

      // 远程进程死亡：callTool 异常映射为 MCP_TOOL_ERROR，且 source 标记断连（交由接线层决定重连）
      killProcess(pidFile);
      ToolResult failed = echo.execute(context);
      assertThat(failed.success()).isFalse();
      assertThat(failed.code()).isEqualTo("MCP_TOOL_ERROR");
      assertThat(failed.message()).isNotBlank();
      assertThat(source.isDisconnected()).isTrue();

      // 重连成功（关停后重连 = 接线层重连路径）：断连标记复位，同一工具实例可再次调用
      source.close();
      source.connect();
      assertThat(source.isDisconnected()).isFalse();
      assertThat(echo.execute(context).message()).isEqualTo("echo:hi");
    } finally {
      Files.writeString(quitMarker, "q");
      waitForExit(pidFile);
    }
  }

  @Test
  void remoteTimeoutMapsToToolErrorAndMarksDisconnected(@TempDir Path tempDir) throws Exception {
    Path pidFile = tempDir.resolve("hang.pid");
    Path quitMarker = tempDir.resolve("hang-quit");
    McpServerLinkConfig config = hangServerConfig(pidFile, quitMarker);
    try (McpToolSource source = new McpToolSource(config)) {
      source.connect();
      AgentTool hang = findTool(source.listTools(), "hang");
      ToolContext context = contextWith("x");

      // 远程挂起超时：异常映射为 MCP_TOOL_ERROR，且标记断连（同步客户端层面超时与进程死亡同表象，
      // 无法区分“挂起”与“死亡”一并标记，交由接线层决定重连）
      ToolResult failed = hang.execute(context);
      assertThat(failed.success()).isFalse();
      assertThat(failed.code()).isEqualTo("MCP_TOOL_ERROR");
      assertThat(failed.message()).isNotBlank();
      assertThat(source.isDisconnected()).isTrue();
    } finally {
      Files.writeString(quitMarker, "q");
      waitForExit(pidFile);
    }
  }

  @Test
  void implementsToolSourceWithIdAndCachedSnapshot() {
    try (McpToolSource source = new McpToolSource("spi-test")) {
      // ToolSource 契约：id() 即源名
      assertThat(source).isInstanceOf(ToolSource.class);
      assertThat(source.id()).isEqualTo("spi-test");
      // 快照语义：未连接时返回空缓存而非抛异常（ToolSource.listTools() 是"当前快照"不是"活查询"）
      assertThat(source.listTools()).isEmpty();
    }
  }

  @Test
  void onChangeNotifiesEveryListenerOnConnectBaseline(@TempDir Path tempDir) throws Exception {
    Path pidFile = tempDir.resolve("spi-echo.pid");
    Path quitMarker = tempDir.resolve("spi-echo-quit");
    McpServerLinkConfig config = echoServerConfig(pidFile, quitMarker);
    try (McpToolSource source = new McpToolSource(config)) {
      AtomicInteger fired = new AtomicInteger();
      AutoCloseable first = source.onChange(fired::incrementAndGet);
      AutoCloseable second = source.onChange(fired::incrementAndGet);

      // 连接尾基线回放：每个订阅者各触发一次（无参数回调，消费方经 listTools() 重读快照）
      source.connect();
      assertThat(fired.get()).isEqualTo(2);
      assertThat(source.listTools()).extracting(AgentTool::name).contains("echo");

      // 撤销订阅：句柄 close 后源仍可用、快照读取不受影响
      first.close();
      second.close();
      assertThat(source.listTools()).extracting(AgentTool::name).contains("echo");
    } finally {
      Files.writeString(quitMarker, "q");
      waitForExit(pidFile);
    }
  }

  private static McpServerLinkConfig echoServerConfig(Path pidFile, Path quitMarker) {
    String classpath = System.getProperty("java.class.path");
    return new McpServerLinkConfig(
        "echo-fail-test",
        "java",
        List.of(
            "-cp",
            classpath,
            "io.mosire.agentlib.mcp.AgentToMcpServerTest$EchoServerMain",
            pidFile.toString(),
            quitMarker.toString()),
        Map.of(),
        Duration.ofSeconds(20),
        Duration.ofSeconds(5));
  }

  private static McpServerLinkConfig hangServerConfig(Path pidFile, Path quitMarker) {
    String classpath = System.getProperty("java.class.path");
    return new McpServerLinkConfig(
        "hang-test",
        "java",
        List.of(
            "-cp",
            classpath,
            "io.mosire.agentlib.mcp.McpToolSourceTest$HangMain",
            pidFile.toString(),
            quitMarker.toString()),
        Map.of(),
        Duration.ofSeconds(20),
        Duration.ofSeconds(3));
  }

  private static AgentTool findTool(List<AgentTool> tools, String name) {
    return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
  }

  private static ToolContext contextWith(String text) {
    return new ToolContext(
        AccessToken.DEFAULT,
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build(),
        Map.of(),
        Map.of("text", text));
  }

  /** SIGKILL 子进程并等待其死亡（上限 5s，负责失败时测试直接挂掉不静默）。 */
  private static void killProcess(Path pidFile) throws Exception {
    long pid = Long.parseLong(Files.readString(pidFile).trim());
    ProcessHandle process = ProcessHandle.of(pid).orElseThrow();
    process.destroyForcibly();
    long deadline = System.currentTimeMillis() + 5_000;
    while (process.isAlive() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertThat(process.isAlive()).isFalse();
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
      ProcessHandle.of(Long.parseLong(content.trim()))
          .ifPresent(ProcessHandle::destroyForcibly);
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

  /** 挂起 server 入口：每次工具调用一律 sleep（不响应），用于触发 requestTimeout。 */
  public static final class HangMain {

    public static void main(String[] args) throws Exception {
      Path pidFile = Path.of(args[0]);
      Path quitMarker = Path.of(args[1]);
      Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
      ToolRegistry tools = new ToolRegistry();
      tools.register(
          new AgentTool() {
            @Override
            public String name() {
              return "hang";
            }

            @Override
            public ToolResult execute(ToolContext context) {
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return ToolResult.ok("late");
            }
          });
      try (AgentToMcpServer server = AgentToMcpServer.start(tools, "hang-server", "0.1.0-test")) {
        while (!Files.exists(quitMarker)) {
          Thread.sleep(50);
        }
      }
      Files.deleteIfExists(pidFile);
    }
  }
}
