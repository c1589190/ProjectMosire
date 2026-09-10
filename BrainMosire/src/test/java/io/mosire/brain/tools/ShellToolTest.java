package io.mosire.brain.tools;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.tools.ShellTool.OutputSink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ShellTool} 的离线行为契约（真子进程、仅 POSIX）：exitCode/stdout/stderr 语义、档 1 采集字节顶、档 2 复用 {@link
 * io.mosire.agentlib.tool.ToolResultTruncator}、档 3 error-only、超时终止直接子进程（红线 5 的边界，R2）、 落库
 * SPI（R5，默认不落库）。
 *
 * <p>所有用例起真 {@code bash}，命令全是本地回显/生成器（{@code echo}/{@code pwd}/{@code yes|head}/{@code sleep}），
 * 无网络、无外部 LLM。超时类用例带 {@link Timeout} 兜底，避免实现缺陷把门禁挂死。
 */
class ShellToolTest {

  private static final String OUT_SENTINEL = "STDOUT-哨兵";
  private static final String ERR_SENTINEL = "STDERR-哨兵";

  @TempDir Path tempDir;

  @BeforeAll
  static void requirePosix() {
    assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "仅 POSIX（用 bash）");
  }

  // ---------- 基本回显与退出码语义（R9：非零退出 = 成功的 ToolResult） ----------

  @Test
  void echoCommandReturnsExitCodeAndStdout() {
    ToolResult result = new ShellTool(tempDir).execute(context(Map.of("command", "echo hello")));

    assertThat(result.code()).isNull();
    assertThat(result.message()).contains("exitCode: 0").contains("hello");
  }

  @Test
  void nonZeroExitIsASuccessfulResultCarryingTheExitCode() {
    ToolResult result =
        new ShellTool(tempDir)
            .execute(context(Map.of("command", "echo " + ERR_SENTINEL + " 1>&2; exit 3")));

    // 命令确实跑完了：模型需要看到输出才能推理，故 code == null（工具层失败才置码）
    assertThat(result.code()).isNull();
    assertThat(result.message()).contains("exitCode: 3").contains(ERR_SENTINEL);
  }

  // ---------- 参数校验（R9：复用既有 INVALID_ARGUMENTS） ----------

  @Test
  void invalidArgumentsAreReportedWithTheStableCode() {
    ShellTool tool = new ShellTool(tempDir);

    assertThat(tool.execute(context(Map.of())).code()).isEqualTo(ShellTool.INVALID_ARGUMENTS);
    assertThat(tool.execute(context(Map.of("command", "   "))).code())
        .isEqualTo(ShellTool.INVALID_ARGUMENTS);
    assertThat(tool.execute(context(Map.of("command", "echo x", "timeout", 0))).code())
        .isEqualTo(ShellTool.INVALID_ARGUMENTS);
    assertThat(tool.execute(context(Map.of("command", "echo x", "timeout", -5))).code())
        .isEqualTo(ShellTool.INVALID_ARGUMENTS);
    assertThat(tool.execute(context(Map.of("command", "echo x", "timeout", "abc"))).code())
        .isEqualTo(ShellTool.INVALID_ARGUMENTS);
    assertThat(tool.execute(context(Map.of("command", "echo x", "mode", "everything"))).code())
        .isEqualTo(ShellTool.INVALID_ARGUMENTS);
    assertThat(
            tool.execute(context(Map.of("command", "echo x", "env", Map.of("A", List.of("x")))))
                .code())
        .isEqualTo(ShellTool.INVALID_ARGUMENTS);
  }

  @Test
  void constructorRejectsNonPositiveLimitsAndNullBaseDirectory() {
    assertThatThrownBy(
            () ->
                new ShellTool(
                    tempDir, OutputSink.none(), ShellOutputTruncator.Mode.NORMAL, 0, 8000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ShellTool(
                    tempDir, OutputSink.none(), ShellOutputTruncator.Mode.NORMAL, 1024, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ShellTool(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void unknownWorkingDirectoryIsALaunchFailure() {
    ToolResult result =
        new ShellTool(tempDir)
            .execute(
                context(Map.of("command", "pwd", "cwd", tempDir.resolve("missing").toString())));

    assertThat(result.code()).isEqualTo(ShellTool.LAUNCH_FAILED);
  }

  // ---------- 工作目录与环境（R10：注入基准目录 + 逐次覆盖；env 覆盖继承环境） ----------

  @Test
  void baseWorkingDirectoryIsUsedByDefault() throws Exception {
    ToolResult result = new ShellTool(tempDir).execute(context(Map.of("command", "pwd")));

    assertThat(result.message()).contains(tempDir.toRealPath().toString());
  }

  @Test
  void cwdArgumentOverridesBaseDirectory() throws Exception {
    Path sub = Files.createDirectories(tempDir.resolve("sub"));
    Path elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"));

    ShellTool tool = new ShellTool(tempDir);

    assertThat(tool.execute(context(Map.of("command", "pwd", "cwd", "sub"))).message())
        .contains(sub.toRealPath().toString());
    assertThat(
            tool.execute(context(Map.of("command", "pwd", "cwd", elsewhere.toString()))).message())
        .contains(elsewhere.toRealPath().toString());
  }

  @Test
  void envArgumentOverridesInheritedEnvironment() {
    ShellTool tool = new ShellTool(tempDir);

    ToolResult result =
        tool.execute(
            context(
                Map.of(
                    "command",
                    "echo \"[$MOSIRE_SHELL_TEST_VAR][$PATH]\"",
                    "env",
                    Map.of("MOSIRE_SHELL_TEST_VAR", "mosire-value"))));

    assertThat(result.message()).contains("[mosire-value][");
    // 继承的 PATH 未被 env 参数抹掉（env = 覆盖在继承环境之上，不是替换）
    assertThat(result.message()).doesNotContain("[mosire-value][]");
  }

  // ---------- 档 1：采集期字节硬顶 ----------

  @Test
  void largeOutputIsClippedAtTheCaptureCap() {
    ShellTool tool =
        new ShellTool(tempDir, OutputSink.none(), ShellOutputTruncator.Mode.NORMAL, 1024, 8000);

    ToolResult result = tool.execute(context(Map.of("command", "yes x | head -c 300000")));

    assertThat(result.code()).isNull();
    assertThat(result.message()).contains("[采集上限]").contains("300000").contains("1024");
    // 顶截在采集期：进结果的消息体不会带着 300 KB 走（否则 OOM 防线形同虚设）
    assertThat(result.message().length()).isLessThan(2000);
  }

  // ---------- 档 2：进上下文的 head-tail 截断（复用既有截断器） ----------

  @Test
  @Timeout(30)
  void endlessFloodIsBoundedByTheCaptureCapAndStillTimesOut() {
    // exec 让 shell 自身变成 yes：直接子进程被杀即管道关闭。yes 永不结束地刷 stdout，
    // 档 1 必须在采集期就压住内存（超限不入内存），且工具不被自己的读取拖住
    ShellTool tool =
        new ShellTool(tempDir, OutputSink.none(), ShellOutputTruncator.Mode.NORMAL, 1024, 8000);

    ToolResult result = tool.execute(context(Map.of("command", "exec yes", "timeout", 1)));

    assertThat(result.code()).isEqualTo(ShellTool.TIMEOUT);
    assertThat(result.message()).contains("[采集上限]");
    assertThat(result.message().length()).isLessThan(4000);
  }

  @Test
  @Timeout(30)
  void descendantHoldingThePipeDoesNotBlockTheTool() {
    // 不加 exec：bash 会 fork 出 yes，杀掉直接子进程（bash）后 yes 仍占着管道继续写。
    // 这就是类 Javadoc 写明的取舍——本工具不回收进程树，故读取线程有界等待、宁可少读也不永久阻塞（朴素 join 会让本用例挂到超时）
    ShellTool tool = new ShellTool(tempDir);
    long startedAt = System.nanoTime();

    ToolResult result = tool.execute(context(Map.of("command", "yes", "timeout", 1)));

    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    assertThat(result.code()).isEqualTo(ShellTool.TIMEOUT);
    // 朴素 join 会等到后代进程结束（yes 永不结束）→ 本用例挂到超时失败；有界等待 + 放弃读取才是行为
    assertThat(elapsedMs).isLessThan(15_000);
  }

  @Test
  void longOutputIsHeadTailTruncatedBeforeInjection() {
    ShellTool tool =
        new ShellTool(tempDir, OutputSink.none(), ShellOutputTruncator.Mode.NORMAL, 1 << 20, 400);

    ToolResult result = tool.execute(context(Map.of("command", "yes x | head -c 3000")));

    assertThat(result.code()).isNull();
    assertThat(result.message()).contains("Warning: 输出被截断").contains("…（中段省略）…");
    assertThat(result.message().length()).isLessThanOrEqualTo(400);
  }

  // ---------- 落库 SPI（R5：构造注入、Optional 语义、默认不落库） ----------

  @Test
  void truncatedOutputIsOfferedToTheInjectedSink() {
    List<String> stored = new ArrayList<>();
    OutputSink sink =
        content -> {
          stored.add(content);
          return Optional.of("doc-42");
        };
    ShellTool tool = new ShellTool(tempDir, sink, ShellOutputTruncator.Mode.NORMAL, 1 << 20, 400);

    ToolResult result =
        tool.execute(
            context(
                Map.of(
                    "command",
                    "echo HEAD-"
                        + OUT_SENTINEL
                        + "; yes x | head -c 3000; echo TAIL-"
                        + ERR_SENTINEL)));

    assertThat(result.code()).isNull();
    assertThat(result.assetDocIds()).containsExactly("doc-42");
    assertThat(result.message()).contains("doc-42");
    // 落库的是完整输出（不是被字符预算裁过的注入文本），且不是"进了上下文的那份残片"
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0))
        .contains("TAIL-" + ERR_SENTINEL)
        .contains("x\nx\nx")
        .doesNotContain("…（中段省略）…")
        .hasSizeGreaterThan(400);
  }

  @Test
  void sinkIsNotConsultedWhenNothingWasTruncated() {
    List<String> stored = new ArrayList<>();
    OutputSink sink =
        content -> {
          stored.add(content);
          return Optional.of("doc-never");
        };
    ShellTool tool = new ShellTool(tempDir, sink);

    ToolResult result = tool.execute(context(Map.of("command", "echo " + OUT_SENTINEL)));

    assertThat(result.code()).isNull();
    assertThat(stored).isEmpty();
    assertThat(result.assetDocIds()).isEmpty();
  }

  @Test
  void defaultSinkDoesNotPersist() {
    ShellTool tool =
        new ShellTool(tempDir, OutputSink.none(), ShellOutputTruncator.Mode.NORMAL, 1 << 20, 200);

    ToolResult result = tool.execute(context(Map.of("command", "yes x | head -c 3000")));

    assertThat(result.code()).isNull();
    assertThat(result.message()).contains("Warning: 输出被截断");
    assertThat(result.assetDocIds()).isEmpty();
  }

  @Test
  void sinkFailureDoesNotFailTheCommand() {
    // 落库是尽力而为：事件库故障不该把"命令跑完了"变成工具层失败
    ShellTool tool =
        new ShellTool(
            tempDir,
            content -> {
              throw new IllegalStateException("落库坏了");
            },
            ShellOutputTruncator.Mode.NORMAL,
            1 << 20,
            200);

    ToolResult result = tool.execute(context(Map.of("command", "yes x | head -c 3000")));

    assertThat(result.code()).isNull();
    assertThat(result.assetDocIds()).isEmpty();
  }

  // ---------- 超时与中断：终止自己创建的直接子进程（R2） ----------

  @Test
  @Timeout(30)
  void timeoutTerminatesTheDirectChildAndReportsTimeout() throws Exception {
    Path pidFile = tempDir.resolve("child.pid");
    long startedAt = System.nanoTime();

    // 用 exec 让 shell 自身变成 sleep：直接子进程被杀后管道即关闭，读取线程收尾、已捕获输出可见
    // （后代仍持有管道的形态属类 Javadoc 写明的取舍，不在本用例断言范围）
    ToolResult result =
        new ShellTool(tempDir)
            .execute(
                context(
                    Map.of(
                        "command",
                        "echo TIMEOUT-OUT-SENTINEL; echo $$ > '" + pidFile + "'; exec sleep 30",
                        "timeout",
                        1)));

    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
    assertThat(result.code()).isEqualTo(ShellTool.TIMEOUT);
    assertThat(result.message()).contains("超时").contains("TIMEOUT-OUT-SENTINEL");
    // 不终止直接子进程，工具会一路阻塞到 sleep 30 结束——耗时上界即"确实动手终止了"的行为证据
    assertThat(elapsedMs).isLessThan(15_000);
    assertThat(isAlive(awaitPidFile(pidFile))).isFalse();
  }

  @Test
  @Timeout(30)
  void interruptedExecutionTerminatesTheChildAndReportsInterruption() throws Exception {
    Path pidFile = tempDir.resolve("interrupt.pid");
    ShellTool tool = new ShellTool(tempDir);
    Map<String, Object> args =
        Map.of("command", "echo $$ > '" + pidFile + "'; exec sleep 30", "timeout", 30);
    AtomicReference<ToolResult> result = new AtomicReference<>();

    Thread worker =
        Thread.ofPlatform()
            .name("shell-interrupt-test")
            .start(() -> result.set(tool.execute(context(args))));
    long childPid = awaitPidFile(pidFile);
    worker.interrupt();
    worker.join(15_000);

    assertThat(worker.isAlive()).isFalse();
    assertThat(worker.isInterrupted()).isTrue();
    assertThat(result.get().code()).isEqualTo(ShellTool.INTERRUPTED);
    assertThat(result.get().message()).contains("被中断");
    assertThat(isAlive(childPid)).isFalse();
  }

  // ---------- 档 3：error-only 模式 ----------

  @Test
  void errorOnlyModeReturnsOnlyStderrOnFailure() {
    ShellTool tool = new ShellTool(tempDir);

    ToolResult result =
        tool.execute(
            context(
                Map.of(
                    "command",
                    "echo " + OUT_SENTINEL + "; echo " + ERR_SENTINEL + " 1>&2; exit 2",
                    "mode",
                    "error-only")));

    assertThat(result.code()).isNull();
    assertThat(result.message())
        .contains("exitCode: 2")
        .contains("[error-only]")
        .contains(ERR_SENTINEL)
        .doesNotContain(OUT_SENTINEL);
  }

  @Test
  void errorOnlyModeBehavesNormallyOnSuccess() {
    ToolResult viaArgument =
        new ShellTool(tempDir)
            .execute(context(Map.of("command", "echo " + OUT_SENTINEL, "mode", "error-only")));
    ToolResult viaConstructor =
        new ShellTool(tempDir, OutputSink.none(), ShellOutputTruncator.Mode.ERROR_ONLY)
            .execute(context(Map.of("command", "echo " + OUT_SENTINEL)));

    for (ToolResult result : List.of(viaArgument, viaConstructor)) {
      assertThat(result.code()).isNull();
      assertThat(result.message()).contains("exitCode: 0").contains(OUT_SENTINEL);
      assertThat(result.message()).doesNotContain("[error-only]");
    }
  }

  // ---------- 声明与红线（R7 / R2） ----------

  @Test
  void specIsSensitiveAndDestructiveAtDefaultLevel() {
    var spec = new ShellTool(tempDir).spec();

    assertThat(spec.requiredLevel()).isEqualTo(AccessToken.DEFAULT);
    assertThat(spec.sensitive()).isTrue();
    assertThat(spec.destructive()).isTrue();
    assertThat(new ShellTool(tempDir).name()).isEqualTo("bash");
  }

  @Test
  void argumentSurfaceExposesNoKillSemantics() {
    var schema = new ShellTool(tempDir).jsonSchema();

    assertThat(properties(schema)).containsOnlyKeys("command", "cwd", "env", "timeout", "mode");
    // 红线 5：Bash 不提供 kill 语义——整个参数面（含描述文字）都不出现 kill/信号/PID 入口
    assertThat(schema.toString().toLowerCase(Locale.ROOT))
        .doesNotContain("kill")
        .doesNotContain("signal")
        .doesNotContain("pid");
  }

  // ---------- 辅助 ----------

  @SuppressWarnings("unchecked")
  private static Map<String, Object> properties(Map<String, Object> schema) {
    return (Map<String, Object>) schema.get("properties");
  }

  private static ToolContext context(Map<String, Object> arguments) {
    return new ToolContext(
        AccessToken.DEFAULT,
        AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
        Map.of(),
        arguments);
  }

  private static boolean isAlive(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  /** 等子进程把 pid 写进文件（子进程启动是异步的；写文件的时机在超时之前）。 */
  private static long awaitPidFile(Path pidFile) throws Exception {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (Files.exists(pidFile)) {
        String raw = Files.readString(pidFile, UTF_8).trim();
        if (!raw.isEmpty()) {
          return Long.parseLong(raw);
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError("子进程未在 10s 内写出 pid 文件: " + pidFile);
  }
}
