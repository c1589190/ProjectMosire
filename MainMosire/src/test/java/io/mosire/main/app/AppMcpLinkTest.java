package io.mosire.main.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.mosire.agentlib.mcp.McpServerLinkConfig;
import io.mosire.agentlib.mcp.McpToolSource;
import io.mosire.agentlib.mcp.PipeMcpClientTransport;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.decision.DecisionHistoryTools;
import io.mosire.brain.decision.DecisionItemTools;
import io.mosire.brain.decision.SubmitDecisionPackageTools;
import io.mosire.brain.skills.SkillTools;
import io.mosire.docs.DocsTools;
import io.mosire.workspace.WorkspaceTools;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
    // 注意"原状"不是空表：主 Agent 的工具面 = 插件供给（本测试无插件目录 ⇒ 空）+ 内置技能工具 + R3b 起的
    // docs 工具（7+1=8：含 R8 的 doc_guide）与工作区工具（3+1=4：含 R8 的 workspace_info），
    // 决策包提议面 0（2026-10-16 裁定 6：decision_propose 已移除），P2b 起另有决策历史 1（decision_history，
    // 只有主 Agent 自己的工具面注册它），P3a 起另有跨进程提交 1（submit_decision_package，父侧结算口，
    // 它必须<b>外发</b>给子体 ⇒ ToolSpec.DEFAULT），★ 2026-10-17 裁定 15 起再加条目级裁决 2
    // （decide_decision_item / commit_decision_package，都是 SYSTEM + noExport：
    // 只对"包住在本进程"的主 Agent 成立）⇒ 合计 6+8+4+1+1+2 = 22。
    // ★ 数字不再只写在注释里：下面那条 hasSize 把它钉住（家族增删而不回来看注释 ⇒ 当场红）。名字集合本身
    //   由 builtinToolNames() 从生产常量现取，全等断言自动跟随。
    // 断言写全等而非"包含"——桥若没把 echo 摘干净，这里会多出 echo ⇒ 转红。
    assertThat(app.runtime().registry().list())
        .extracting(AgentTool::name)
        .containsExactlyInAnyOrderElementsOf(builtinToolNames());
    assertThat(builtinToolNames())
        .as("技能 6 + Docs 8 + 工作区 4 + 决策历史 1 + 跨进程提交 1 + 条目级裁决 2")
        .hasSize(22);
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
      // 验收（★ 2026-10-16 随 A-6「可见面 = 判定面」改口径；旧断言已被推翻，理由如下）：
      //   外部 MCP 面的调用者令牌缺省 = AccessToken.GUEST（agent.externalMcp.token 可配），而生产内置工具
      //   逐个把级别提到 ToolSpec.level(AccessToken.DEFAULT, …) ⇒ 它们在该面上**一个都调不动**。旧断言要求
      //   "19 个内置工具都列得出来"= 断言"看得见却调不动"，正是 A-6 禁止的形状（模型会看见一调就吃
      //   PERMISSION_DENIED 的工具）。现在可见面由一个谓词收窄（PermissionChecker.allows，与判定面同一个 oracle），
      //   于是该面只列得出**判定面也放行**的工具：本 fixture 里是桥同步进来的 echo 与 P3a 的
      //   submit_decision_package（两者都是 ToolSpec.DEFAULT ⇒ 要求 GUEST）。★ P3a 改动的是这条期望值本身：
      //   新工具按设计必须"经既有 MCP 桥暴露"（§5quinquies ②），而 DEFAULT 级就落在 GUEST 面上——不是它越权，
      //   是它按设计对 GUEST 可见；真调用它仍然会吃 NO_IDENTITY（外部面身份 = unknown，工具与网关两层都 fail-closed）。
      //   ★ 判别力仍在：bridge 没把 echo 摘干净/同步过来 ⇒ 少一个即红；把别的内置工具（技能/Docs/工作区/历史、
      //   以及裁定 15 的两个"裁决动作"工具 decide_decision_item / commit_decision_package）漏出去 ⇒ 多一个即红；
      //   submit_decision_package 哪天被改成 noExport 或提到 DEFAULT 级 ⇒ 这里也当场红。
      //   ★ 2026-10-17 收口单复核（回代码核 + 真跑复核）：P4 收口记录里"裁定 15 的两个工具一度是 `DEFAULT +
      //   noExport`，而 noExport 只挡子体桥、挡不住外部 MCP 面 ⇒ 本条当场红"这条机制描述**不成立**——
      //   两个面走的是**同一个**装配段（{@code AgentToMcpServer.assemble} → {@code exportable} =
      //   {@code include.test(name) && !spec.noExport()}），{@code noExport} 对**两个面**都生效；
      //   当时真正红的是上面那条"主 Agent 内置工具面"的 hasSize（20 vs 22，与 noExport 无关）。
      //   ⇒ 这里保留 `SYSTEM + noExport` 两道（控制方裁定 3）：noExport 是**结构性**的那道（永不出外发面），
      //     SYSTEM 是**判定面**那道（外部面缺省 GUEST ⇒ 即使将来有人漏掉 noExport 也调不动）。
      //   判别力：本条只钉"这两个工具不在这张表上"；"noExport 真的在外部面上求交"由下面
      //   {@link #externalMcpFaceIntersectsNoExportEvenWhenTheJudgmentFaceWouldAllowIt()} 单独钉住
      //   （那里用同级别的 noExport 工具做判别位，排除"级别不够"这个替代解释）。
      //   要恢复更多工具（那是部署方的选择）：把 agent.externalMcp.token 配成 DEFAULT/SYSTEM。
      assertThat(client.listTools())
          .extracting(AgentTool::name)
          .containsExactlyInAnyOrder("echo", SubmitDecisionPackageTools.NAME);
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

  /**
   * ★ 2026-10-17 收口单：<b>外部 MCP 面同样与 {@code noExport} 求交</b>——即使"判定面"会放行那个工具。
   *
   * <p><b>为什么要有这一条</b>：P4 的收口记录里有一条机制描述说"`noExport` 只挡子体 MCP 桥；外部 MCP 面的可见面 = 判定面，DEFAULT 级对它放行 ⇒
   * 裁决动作工具列在外部面上并可见"。回代码核的结果是<b>这条描述不成立</b>：两个面用的是 <b>同一个</b>装配段（{@code AgentToMcpServer.assemble}
   * → {@code exportable}）与<b>同一个</b>谓词 （{@code include.test(name) &&
   * !spec.noExport()}），初始注册与增量同步都只经它 ⇒ {@code noExport} 对<b>两个面</b> 都生效。本用例把这件事钉在<b>真的外部面</b>上（真
   * App + 真 stdio MCP 客户端 + 真 {@code tools/list} 往返）。
   *
   * <p><b>判别位与正对照</b>（同一份装配、同一张 registry，只差 {@code noExport} 一位）：
   *
   * <ul>
   *   <li>{@code submit_decision_package} = {@code ToolSpec.DEFAULT} + <b>可外发</b> ⇒ 令牌配成 DEFAULT 后
   *       <b>必须列得出</b>：这是<b>正对照</b>——证明判定面确实放行了 DEFAULT 级工具，本用例不是"什么都没列出来"的假绿；
   *   <li>{@code decision_history} = {@code ToolSpec.DEFAULT} + <b>{@code noExport=true}</b> ⇒
   *       <b>不得列出</b>： 它的级别与正对照<b>相同</b> ⇒ 判定面同样放行它，"级别不够"这个替代解释被排除，唯一能解释它缺席的就是 {@code noExport}。
   * </ul>
   *
   * <p><b>判别力（变异自证）</b>：把 {@code AgentToMcpServer.exportable} 里的 {@code && !spec.noExport()} 删掉 ⇒
   * 本用例 第二条断言（{@code doesNotContain(decision_history)}）当场红，而正对照仍绿（红的正是被保护的那一行）。
   */
  @Test
  void externalMcpFaceIntersectsNoExportEvenWhenTheJudgmentFaceWouldAllowIt() throws Exception {
    Path dataDir = tempDir.resolve("data");
    Files.createDirectories(dataDir);
    // 外部面的令牌提成 DEFAULT：A-6 的可见面谓词（= 判定面，同一个 PermissionChecker.allows）从此对 DEFAULT 级
    // 工具全部放行 ⇒ 于是"某个 DEFAULT 级工具没被列出"不可能再拿"级别不够"解释。
    Files.writeString(
        dataDir.resolve("config.json"),
        "{\"agent\":{\"externalMcp\":{\"token\":\"DEFAULT\"}}}",
        StandardCharsets.UTF_8);
    BootConfig config = new BootConfig(0, dataDir, false, "", null, true, "127.0.0.1", 0);

    // App 的 MCP server 走默认 stdio 传输（构造期捕获 System.in/out）⇒ 起 App 之前换成管道、起完立刻还原
    // （同 AppApprovalWiringTest#mcpExposedToolCallGoesThroughTheSameApprovalFace 的既有做法）。
    PrintStream originalOut = System.out;
    InputStream originalIn = System.in;
    PipedInputStream serverReads = new PipedInputStream();
    PipedOutputStream clientWrites = new PipedOutputStream(serverReads);
    PipedInputStream clientReads = new PipedInputStream();
    PipedOutputStream serverWrites = new PipedOutputStream(clientReads);
    PrintStream protocolOut = new PrintStream(serverWrites, true, StandardCharsets.UTF_8);
    App app;
    System.setIn(serverReads);
    System.setOut(protocolOut);
    try {
      app = App.start(config);
    } finally {
      System.setIn(originalIn);
      System.setOut(originalOut);
    }

    try (app;
        McpSyncClient client =
            McpClient.sync(
                    new PipeMcpClientTransport(
                        McpJsonDefaults.getMapper(), clientReads, clientWrites))
                .build()) {
      client.initialize();
      List<String> names = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
      assertThat(names)
          .as("正对照：DEFAULT 级 + 可外发的工具在令牌=DEFAULT 时列得出（判定面确实放行了它）")
          .contains(SubmitDecisionPackageTools.NAME);
      assertThat(names)
          .as("★ 判别位：同级别的 decision_history 带 noExport ⇒ 外部面不得列出它（唯一解释就是 noExport）")
          .doesNotContain(DecisionHistoryTools.NAME);
      assertThat(names)
          .as("★ 裁定 15 的两个裁决动作工具（SYSTEM + noExport）也不得出现在外部面上")
          .doesNotContain(DecisionItemTools.DECIDE_NAME, DecisionItemTools.COMMIT_NAME);
    }
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

  /**
   * 主 Agent 的<b>内置</b>工具名（不含插件与 MCP 桥）：技能 6 + Docs 8 + 工作区 4 + 决策历史 1 + 跨进程提交 1 + 条目级裁决 2 = 22（R8 起
   * Docs 7→8、工作区 3→4；RB2a 起曾加 decision_propose，2026-10-16 裁定 6 已移除；P2b 加 {@code
   * decision_history}；P3a 加 {@code submit_decision_package}；2026-10-17 裁定 15 加 {@code
   * decide_decision_item} 与 {@code commit_decision_package}）。
   *
   * <p>名字集合都从生产常量现取（不重抄字面量）：哪一族少注册一个工具，全等断言当场转红； 上面的总数由调用点的 {@code hasSize(22)} 钉住，不再只活在注释里（★
   * 旧注释写"技能 11 … 合计 21"， 而 {@code SkillTools.NAMES} 实际只有 6 条——数字漂了没人发现，正是"注释里的数字"这类 drift 的典型）。
   */
  private static java.util.Set<String> builtinToolNames() {
    return java.util.stream.Stream.of(
            SkillTools.NAMES,
            DocsTools.NAMES,
            WorkspaceTools.NAMES,
            java.util.List.of(
                DecisionHistoryTools.NAME,
                SubmitDecisionPackageTools.NAME,
                DecisionItemTools.DECIDE_NAME,
                DecisionItemTools.COMMIT_NAME))
        .flatMap(java.util.Collection::stream)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
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
