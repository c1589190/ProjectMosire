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
import java.io.IOException;
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

  // ---------- 后代进程占着管道时的输出完整性（评审 Critical 回归） ----------

  /**
   * 静默后代（本轮评审 Critical 的探针场景逐字回归）：直接子进程（bash）退出后，后代（sleep）仍占着管道写端 （本工具不回收进程树，见类 Javadoc）。
   *
   * <p>旧实现里读取线程永久阻塞在 {@code read} 上、采集缓冲只在读取返回时才发布 → 工具拿到空快照，把"命令明明打了哨兵"回报成 {@code exitCode: 0} +
   * {@code stdout:\n\nstderr:\n}（静默的错误输出，比报错更糟）。哨兵必须出现——已读到的字节不得因为 流没有等到可观测尽头而整体丢弃。
   */
  @Test
  @Timeout(60)
  void silentDescendantStillYieldsItsOutput() {
    ToolResult result =
        new ShellTool(tempDir)
            .execute(
                context(
                    Map.of(
                        "command",
                        // 评审探针用的是 sleep 3600；这里 10 秒足够跑完本次调用，且不给门禁留下长命孤儿
                        "sleep 10 & echo VISIBLE-STDOUT-SENTINEL; echo VISIBLE-ERR-SENTINEL 1>&2",
                        "timeout",
                        10)));

    assertThat(result.code()).isNull();
    assertThat(result.message())
        .contains("exitCode: 0")
        .contains("VISIBLE-STDOUT-SENTINEL")
        .contains("VISIBLE-ERR-SENTINEL");
    // 旧失败形态：成功 + 空输出（什么都没报到）
    assertThat(result.message()).doesNotContain("stdout:\n\nstderr:\n");
    assertThat(result.message()).doesNotContain(ShellOutputTruncator.INCOMPLETE_TAG);
  }

  /**
   * 可观测边界（如实钉住，不是想要的行为）：直接子进程被 JDK 回收器回收的瞬间，管道读端就被强制关闭——此后进程树里任何进程的 写入都不可能再被读到（{@code
   * ProcessPipeInputStream.processExited()} 只把回收那一刻管道里的残留字节搬进内存流）。
   *
   * <p>因此"晚到的后代输出"既捕获不到、也无法逐条披露（它与"干净结束"不可区分）。这是取舍的代价，不是本实现的选择：要治理这种 输出得靠子 Agent 级的进程树归属（类 Javadoc
   * 的取舍）。回收前已进入管道的字节照常捕获——上一用例是它的回归面。
   */
  @Test
  @Timeout(60)
  void descendantWritesAfterTheDirectChildIsReapedAreBeyondObservability() {
    long startedAt = System.nanoTime();

    ToolResult result =
        new ShellTool(tempDir)
            .execute(
                context(
                    Map.of(
                        "command",
                        "(sleep 0.5; echo LATE-SENTINEL) & echo EARLY-SENTINEL",
                        "timeout",
                        5)));

    assertThat(result.code()).isNull();
    assertThat(result.message()).contains("EARLY-SENTINEL").doesNotContain("LATE-SENTINEL");
    // 后代还占着管道也不拖着收尾：直接子进程结束后即有界返回
    assertThat((System.nanoTime() - startedAt) / 1_000_000).isLessThan(5_000);
  }

  /** 读取线程必须真的结束（不是"被放弃"）：后代命令跑 N 次后，{@code mosire-bash-*} 线程数回到基线。 */
  @Test
  @Timeout(90)
  void readerThreadsAreReclaimedAfterCommandsWithSurvivingDescendants() throws Exception {
    int baseline = bashReaderCount();
    ShellTool tool = new ShellTool(tempDir);
    Map<String, Object> args =
        Map.of(
            "command",
            "sleep 10 & echo THREAD-CENSUS-OUT; echo THREAD-CENSUS-ERR 1>&2",
            "timeout",
            5);

    for (int i = 0; i < 8; i++) {
      assertThat(tool.execute(context(args)).message()).contains("THREAD-CENSUS-OUT");
    }
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (bashReaderCount() > baseline && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }

    assertThat(bashReaderCount()).isEqualTo(baseline);
  }

  /** 进上下文的文本（成功与失败两条路径）都必须尊重字符预算，即使命令本身很长（失败路径曾把原始命令整段塞进消息）。 */
  @Test
  @Timeout(30)
  void injectedTextRespectsTheBudgetOnBothPathsWithALongCommand() {
    int budget = 200;
    ShellTool tool =
        new ShellTool(
            tempDir, OutputSink.none(), ShellOutputTruncator.Mode.NORMAL, 1 << 20, budget);

    ToolResult success =
        tool.execute(context(Map.of("command", "echo " + OUT_SENTINEL + " # " + "x".repeat(3000))));
    ToolResult failure =
        tool.execute(context(Map.of("command", "sleep 30 # " + "x".repeat(3000), "timeout", 1)));

    assertThat(success.code()).isNull();
    assertThat(success.message().length()).isLessThanOrEqualTo(budget);
    assertThat(failure.code()).isEqualTo(ShellTool.TIMEOUT);
    assertThat(failure.message()).contains("超时");
    assertThat(failure.message().length()).isLessThanOrEqualTo(budget);
  }

  /**
   * 200 MB 级输出 + 1 KiB 采集顶：字节顶必须在<b>采集期</b>生效（超限字节只计数不入内存）——测试 JVM 只有 288m 堆，任何"先收全再裁"的实现都会在这里
   * OOM（等价于评审在 -Xmx48m 下的实测）。
   */
  @Test
  @Timeout(120)
  void twoHundredMegabytesOfOutputStaysWithinTheCaptureCap() {
    ShellTool tool =
        new ShellTool(tempDir, OutputSink.none(), ShellOutputTruncator.Mode.NORMAL, 1024, 8000);

    ToolResult result = tool.execute(context(Map.of("command", "yes x | head -c 200000000")));

    assertThat(result.code()).isNull();
    assertThat(result.message())
        .contains(ShellOutputTruncator.CAPTURE_TAG)
        .contains("200000000")
        .contains("1024");
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
  void descendantHoldingThePipeDoesNotBlockTheTool() throws Exception {
    // 不加 exec：bash 会 fork 出 yes（不让它变成直接子进程）；后代 PID 写进文件，用例结束自行收殓——
    // 空转的 yes 会一直烧 CPU，把同门用例（乃至门禁里的其它模块）拖成随机失败。
    // 这就是类 Javadoc 写明的取舍：本工具不回收进程树，故读取线程有界等待、宁可少读也不永久阻塞（朴素 join 会让本用例挂到超时）
    ShellTool tool = new ShellTool(tempDir);
    Path strayYes = tempDir.resolve("stray-yes.pid");
    Path straySleep = tempDir.resolve("stray-sleep.pid");
    long startedAt = System.nanoTime();

    try {
      ToolResult result =
          tool.execute(
              context(
                  Map.of(
                      "command",
                      "yes & echo $! > stray-yes.pid; sleep 30 & echo $! > stray-sleep.pid; wait",
                      "timeout",
                      1)));

      long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
      assertThat(result.code()).isEqualTo(ShellTool.TIMEOUT);
      // 失败路径同样自报采集顶：yes 刷满 1 MiB 顶，消息必须说清"看到的是残片"
      assertThat(result.message()).contains(ShellOutputTruncator.CAPTURE_TAG);
      // 朴素 join 会等到后代进程结束（yes 永不结束）→ 本用例挂到超时失败；有界等待 + 放弃读取才是行为
      assertThat(elapsedMs).isLessThan(15_000);
    } finally {
      killStray(strayYes);
      killStray(straySleep);
    }
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
            .daemon() // 非守护线程残留会拖住门禁 JVM 退出
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

  /** 收殓命令自己派生的后代（工具按 R1 的取舍不回收进程树）：只按记下的 PID 精确终止，不做全系统按名杀。 */
  private static void killStray(Path pidFile) {
    try {
      String raw = Files.readString(pidFile, UTF_8).trim();
      if (!raw.isEmpty()) {
        ProcessHandle.of(Long.parseLong(raw)).ifPresent(ProcessHandle::destroyForcibly);
      }
    } catch (IOException | NumberFormatException e) {
      // 没用例要收殓的进程（PID 文件没写成/已不在）：无需处理
    }
  }

  /** 存活的读取线程数（线程结束后即从线程组移除，故这个计数就是"还在跑的"）。 */
  private static int bashReaderCount() {
    int live = 0;
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      if (thread.isAlive() && thread.getName().startsWith("mosire-bash-")) {
        live++;
      }
    }
    return live;
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
