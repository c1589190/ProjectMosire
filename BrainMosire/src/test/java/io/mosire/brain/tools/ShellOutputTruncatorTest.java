package io.mosire.brain.tools;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.tool.ToolResultTruncator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link ShellOutputTruncator} 三档契约（全离线、纯字节/字符串函数，不起子进程）：
 *
 * <p>档 1 采集期字节硬顶（超限字节继续读取并计数、但不入内存，见 R3）→ 档 3 {@code error-only} 模式（exit≠0 只回 stderr）→ 档 2 进上下文的
 * head-tail 截断（<b>复用</b> {@link ToolResultTruncator}，本类不重造头尾算法）。
 *
 * <p>此外钉住本轮评审的 Critical 修复：{@link ShellOutputTruncator.Capture} 的采集<b>增量发布</b>（读取没结束也能取到已读字节）、
 * <b>不阻塞</b>（无数据时绝不 read）、<b>必然终止</b>（放弃后有界排空），以及"没读到流尽头就收手"时渲染层的 {@value
 * ShellOutputTruncator#INCOMPLETE_TAG} 披露。落库口径同样钉住：{@link ShellOutputTruncator.Rendered#full()}
 * 恒含两路输出 （error-only 模式下亦然），否则暂存下来的文档会缺掉被模式抑制的那一路。
 */
class ShellOutputTruncatorTest {

  private static final String OUT_HEAD = "OUT-HEAD-哨兵";
  private static final String OUT_TAIL = "OUT-TAIL-哨兵";
  private static final String ERR_HEAD = "ERR-HEAD-哨兵";
  private static final String ERR_TAIL = "ERR-TAIL-哨兵";
  private static final String MIDDLE = "MIDDLE-中段哨兵";

  // ---------- 档 1：采集期字节硬顶 + 增量发布 + 必然终止 ----------

  @Test
  void pumpKeepsEverythingUnderTheCapAndEndsAtEof() {
    byte[] payload = "hello 世界".getBytes(UTF_8);
    ShellOutputTruncator.Capture capture = new ShellOutputTruncator.Capture(1024);
    capture.markChildExited(); // 直接子进程结束：此后"流静音"即判定读到尽头（真实管道上就是 JDK 回收器关读端的时刻）

    capture.pump(new ByteArrayInputStream(payload));
    ShellOutputTruncator.Captured captured = capture.snapshot();

    assertThat(captured.text()).isEqualTo("hello 世界");
    assertThat(captured.keptBytes()).isEqualTo(payload.length);
    assertThat(captured.totalBytes()).isEqualTo(payload.length);
    assertThat(captured.truncated()).isFalse();
    assertThat(captured.endObserved()).isTrue();
  }

  /** 读到 EOF（{@code read} 返回 -1）直接收手：这一路径不依赖"子进程已退出"信号。 */
  @Test
  @Timeout(15)
  void pumpStopsAtEofReportedByTheStream() {
    InputStream eofOnly =
        new InputStream() {
          private int calls;

          @Override
          public int available() {
            return calls++ == 0 ? 1 : 0;
          }

          @Override
          public int read() {
            return -1;
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            return -1;
          }
        };
    ShellOutputTruncator.Capture capture = new ShellOutputTruncator.Capture(1024);

    capture.pump(eofOnly);

    assertThat(capture.snapshot().endObserved()).isTrue();
  }

  /** 8 MiB 源 + 1 KiB 顶：只保留 1 KiB，但总数照实计到 8 MiB（超限部分不入内存、仍被读取以免子进程写满管道阻塞）。 */
  @Test
  void pumpClipsAtTheCapAndOnlyCountsTheRest() {
    long total = 8L * 1024 * 1024;
    ShellOutputTruncator.Capture capture = new ShellOutputTruncator.Capture(1024);
    capture.markChildExited();

    capture.pump(repeating('a', total));
    ShellOutputTruncator.Captured captured = capture.snapshot();

    assertThat(captured.keptBytes()).isEqualTo(1024);
    assertThat(captured.text()).hasSize(1024);
    assertThat(captured.totalBytes()).isEqualTo(total);
    assertThat(captured.truncated()).isTrue();
    assertThat(captured.endObserved()).isTrue();
  }

  @Test
  void captureRejectsNonPositiveCap() {
    assertThatThrownBy(() -> new ShellOutputTruncator.Capture(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxBytes");
  }

  /**
   * 静默管道（后代占着写端：不会有数据、也不会有 EOF）+ 直接子进程已退出 → pump 必须在安静窗口内收手， 且<b>不得</b>调用 read（真管道上那一下就是永久阻塞，正是评审
   * Critical 的成因）。
   */
  @Test
  @Timeout(15)
  void pumpStopsQuietlyAfterTheChildExitedWithoutEverBlockingOnASilentPipe() {
    AtomicInteger reads = new AtomicInteger();
    InputStream silentPipe =
        new InputStream() {
          @Override
          public int available() {
            return 0;
          }

          @Override
          public int read() {
            reads.incrementAndGet();
            return -1;
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            reads.incrementAndGet();
            return -1;
          }
        };
    ShellOutputTruncator.Capture capture = new ShellOutputTruncator.Capture(1024);
    capture.markChildExited();

    capture.pump(silentPipe);

    assertThat(reads.get()).isZero();
    assertThat(capture.snapshot().endObserved()).isTrue();
  }

  /** 增量发布：读取还在进行时快照就能取到已读字节（旧实现只在读取返回时发布，后代占管道时快照是空的 → 输出被静默吞掉）。 */
  @Test
  @Timeout(15)
  void snapshotPublishesBytesWhileTheReadIsStillRunning() throws Exception {
    InputStream slowPipe =
        new InputStream() {
          private int served;

          @Override
          public int available() {
            return served < 5 ? 1 : 0; // 有数据才报有数据（pump 只在此时读）
          }

          @Override
          public int read() {
            return 'a';
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            if (served >= 5) {
              return -1;
            }
            try {
              Thread.sleep(80);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            served++;
            buffer[offset] = 'a';
            return 1;
          }
        };
    ShellOutputTruncator.Capture capture = new ShellOutputTruncator.Capture(1024);
    Thread reader =
        Thread.ofPlatform().daemon().name("capture-test").start(() -> capture.pump(slowPipe));

    long deadline = System.nanoTime() + 10_000_000_000L;
    while (capture.snapshot().totalBytes() < 2 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }

    assertThat(reader.isAlive()).isTrue();
    assertThat(capture.snapshot().totalBytes()).isGreaterThanOrEqualTo(2);
    capture.abandon();
    reader.join(5_000);
    // "被放弃"不算收尾：线程必须真的结束
    assertThat(reader.isAlive()).isFalse();
    assertThat(capture.snapshot().endObserved()).isFalse();
  }

  /** 放弃：即使数据一直有（后代在刷输出），也只在有界排空窗口内继续读——否则读取线程永不结束＝线程泄漏。 */
  @Test
  @Timeout(15)
  void abandonStopsTheReadEvenWhenDataKeepsComing() {
    AtomicInteger reads = new AtomicInteger();
    ShellOutputTruncator.Capture capture = new ShellOutputTruncator.Capture(1024);
    capture.abandon();

    capture.pump(endlessStream(reads));
    ShellOutputTruncator.Captured captured = capture.snapshot();

    assertThat(captured.keptBytes()).isEqualTo(1024);
    assertThat(captured.truncated()).isTrue();
    // 没读到流尽头 → 渲染层必须披露（残片不得冒充全量）
    assertThat(captured.endObserved()).isFalse();
  }

  /** 采集是尽力而为：流中途坏掉（子进程被回收时 JDK 会关掉读端）不能连已读到的字节一起丢。 */
  @Test
  @Timeout(15)
  void pumpKeepsWhatItReadWhenTheStreamBreaksMidway() {
    InputStream broken = brokenStream();

    ShellOutputTruncator.Capture capture = new ShellOutputTruncator.Capture(1024);
    capture.markChildExited(); // 直接子进程已退出 → 关读端的是 JDK 回收器，属"读到尽头"
    capture.pump(broken);
    ShellOutputTruncator.Captured captured = capture.snapshot();

    assertThat(captured.text()).isEqualTo("aaaaa");
    assertThat(captured.keptBytes()).isEqualTo(5);
    assertThat(captured.truncated()).isFalse();
    assertThat(captured.endObserved()).isTrue();
  }

  /** 直接子进程还活着就读端关闭：不是"读到尽头"，必须记未观测到终结（渲染层会披露，不许当成正常收尾）。 */
  @Test
  @Timeout(15)
  void streamClosedWhileTheChildIsAliveIsNotTreatedAsTheEnd() {
    ShellOutputTruncator.Capture capture = new ShellOutputTruncator.Capture(1024);

    capture.pump(brokenStream());
    ShellOutputTruncator.Captured captured = capture.snapshot();

    assertThat(captured.text()).isEqualTo("aaaaa");
    assertThat(captured.endObserved()).isFalse();
  }

  // ---------- 档 2：head-tail 截断复用既有实现 ----------

  @Test
  void renderReturnsInjectedEqualToFullWhenNothingWasCut() {
    ShellOutputTruncator.Rendered rendered =
        ShellOutputTruncator.render(
            captured("hello"),
            captured(""),
            0,
            modeNormal(),
            ToolResultTruncator.DEFAULT_MAX_CHARS);

    assertThat(rendered.injected()).isEqualTo(rendered.full());
    assertThat(rendered.cutByBudget()).isFalse();
    assertThat(rendered.injected()).contains("exitCode: 0", "stdout:", "hello", "stderr:");
  }

  /** 长输出走 {@link ToolResultTruncator}（断言其截断提示与中段省略标记），且注入文本不超预算。 */
  @Test
  void longOutputIsHeadTailTruncatedByTheExistingTruncator() {
    ShellOutputTruncator.Captured stdout = captured(OUT_HEAD + "x".repeat(500) + OUT_TAIL);
    ShellOutputTruncator.Captured stderr = captured(ERR_HEAD + MIDDLE + "y".repeat(500) + ERR_TAIL);

    ShellOutputTruncator.Rendered rendered =
        ShellOutputTruncator.render(stdout, stderr, 0, modeNormal(), 400);

    assertThat(rendered.injected())
        .contains("Warning: 输出被截断")
        .contains("…（中段省略）…")
        .contains(OUT_HEAD)
        .contains(ERR_TAIL)
        .doesNotContain(MIDDLE);
    assertThat(rendered.injected().length()).isLessThanOrEqualTo(400);
    assertThat(rendered.cutByBudget()).isTrue();
    // 落库口径是完整文本（未被字符预算裁剪）
    assertThat(rendered.full()).contains(MIDDLE).contains(OUT_TAIL).contains(ERR_TAIL);
  }

  @Test
  void captureTruncationIsReportedWithByteCounts() {
    ShellOutputTruncator.Captured stdout =
        new ShellOutputTruncator.Captured("a".repeat(1024), 1024, 3_000_000L, true, true);

    ShellOutputTruncator.Rendered rendered =
        ShellOutputTruncator.render(stdout, captured(""), 0, modeNormal(), 8000);

    assertThat(rendered.injected())
        .contains(ShellOutputTruncator.CAPTURE_TAG)
        .contains("3000000")
        .contains("1024")
        .contains("stdout");
    // 采集顶已丢弃的字节无法落库：落库口径就是"采集到的"部分，UI 不因此谎报全量
    assertThat(rendered.full()).hasSizeLessThan(8000);
    // 读到尽头 → 不披露
    assertThat(rendered.injected()).doesNotContain(ShellOutputTruncator.INCOMPLETE_TAG);
  }

  /** 收手时没读到流尽头：成功路径与失败路径都必须披露"输出可能不完整"（静默的缺失比报错更糟）。 */
  @Test
  void incompleteCaptureIsDisclosedOnBothPaths() {
    ShellOutputTruncator.Captured partial =
        new ShellOutputTruncator.Captured("部分输出", 12, 12, false, false);

    assertThat(ShellOutputTruncator.render(partial, captured(""), 0, modeNormal(), 8000).injected())
        .contains(ShellOutputTruncator.INCOMPLETE_TAG)
        .contains("部分输出");
    assertThat(
            ShellOutputTruncator.renderAborted(
                "命令超时（1 秒），已终止本工具创建的直接子进程（后代进程可能仍存活）", "sleep 30", partial, captured(""), 8000))
        .contains(ShellOutputTruncator.INCOMPLETE_TAG);
  }

  // ---------- 失败路径（超时/中断）的输出口径 ----------

  /** 失败路径不是绕过档 1 自报的后门：超时/被中断时同样告诉模型"这是残片"，且照样过档 2 预算。 */
  @Test
  void abortedPathKeepsBothStreamsAndTheCaptureNoticeWithinBudget() {
    ShellOutputTruncator.Captured stdout =
        new ShellOutputTruncator.Captured("y".repeat(1024), 1024, 1_500_000L, true, true);
    ShellOutputTruncator.Captured stderr = captured(ERR_HEAD);

    String aborted =
        ShellOutputTruncator.renderAborted("命令超时（1 秒）", "sleep 30", stdout, stderr, 400);

    assertThat(aborted)
        .contains(ShellOutputTruncator.CAPTURE_TAG)
        .contains("1500000")
        .contains("stderr:")
        .contains(ERR_HEAD)
        // 失败路径没有正常退出码，编一个只会误导模型
        .doesNotContain("exitCode:");
    assertThat(aborted.length()).isLessThanOrEqualTo(400);
  }

  @Test
  void abortedPathWithNothingCapturedSaysSo() {
    assertThat(
            ShellOutputTruncator.renderAborted(
                "命令超时（1 秒）", "sleep 30", captured(""), captured("   "), 8000))
        .contains("未捕获到输出");
  }

  /** 命令回显必须被收短（不是靠外层截断兜底）：超时/中断可逐回合重现，整段照抄命令就是 100× 预算漏洞。 */
  @Test
  void abortedPathBoundsTheEchoedCommand() {
    String command = "sleep 30 # " + "x".repeat(3000);

    String roomy =
        ShellOutputTruncator.renderAborted("命令超时（1 秒）", command, captured(""), captured(""), 8000);
    assertThat(roomy).contains("命令回显已截断").doesNotContain("x".repeat(201));

    String tight =
        ShellOutputTruncator.renderAborted(
            "命令超时（1 秒）", command, captured("TIMEOUT-OUT"), captured(""), 200);
    assertThat(tight).contains("超时");
    assertThat(tight.length()).isLessThanOrEqualTo(200);
  }

  // ---------- 档 3：error-only 模式 ----------

  @Test
  void errorOnlyKeepsStderrAndSuppressesStdoutOnFailure() {
    ShellOutputTruncator.Captured stdout = captured(OUT_HEAD + "正常输出");
    ShellOutputTruncator.Captured stderr = captured(ERR_HEAD + "失败了");

    ShellOutputTruncator.Rendered rendered =
        ShellOutputTruncator.render(stdout, stderr, 3, modeErrorOnly(), 8000);

    assertThat(rendered.injected())
        .contains("exitCode: 3")
        .contains("[error-only]")
        .contains(ERR_HEAD)
        .doesNotContain(OUT_HEAD);
    // 落库口径不受模式影响：被抑制的 stdout 仍在完整文本里（"不注入上下文"≠"不保存"）
    assertThat(rendered.full()).contains(OUT_HEAD).contains(ERR_HEAD);
  }

  @Test
  void errorOnlyBehavesNormallyOnSuccess() {
    ShellOutputTruncator.Rendered rendered =
        ShellOutputTruncator.render(
            captured(OUT_HEAD), captured(ERR_HEAD), 0, modeErrorOnly(), 8000);

    assertThat(rendered.injected()).contains("exitCode: 0").contains(OUT_HEAD);
    assertThat(rendered.injected()).doesNotContain("[error-only]");
  }

  @Test
  void modeParseAcceptsWireNamesAndRejectsUnknown() {
    assertThat(ShellOutputTruncator.Mode.parse("normal"))
        .isEqualTo(ShellOutputTruncator.Mode.NORMAL);
    assertThat(ShellOutputTruncator.Mode.parse("error-only"))
        .isEqualTo(ShellOutputTruncator.Mode.ERROR_ONLY);
    assertThatThrownBy(() -> ShellOutputTruncator.Mode.parse("everything"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mode");
  }

  // ---------- 辅助 ----------

  private static ShellOutputTruncator.Mode modeNormal() {
    return ShellOutputTruncator.Mode.NORMAL;
  }

  private static ShellOutputTruncator.Mode modeErrorOnly() {
    return ShellOutputTruncator.Mode.ERROR_ONLY;
  }

  /** 完整（未触顶、已读到尽头）的采集结果。 */
  private static ShellOutputTruncator.Captured captured(String text) {
    int bytes = text.getBytes(UTF_8).length;
    return new ShellOutputTruncator.Captured(text, bytes, bytes, false, true);
  }

  /** 无底字节流：每次读都返回满额数据，并记下读取次数。 */
  private static InputStream endlessStream(AtomicInteger reads) {
    return new InputStream() {
      @Override
      public int read() {
        reads.incrementAndGet();
        return 'a';
      }

      @Override
      public int read(byte[] buffer, int offset, int length) {
        reads.incrementAndGet();
        Arrays.fill(buffer, offset, offset + length, (byte) 'a');
        return length;
      }

      @Override
      public int available() {
        return 1;
      }
    };
  }

  /** 先吐 5 个字节、此后每次都抛 {@code IOException} 的流（模拟"读端被关掉"）。 */
  private static InputStream brokenStream() {
    return new InputStream() {
      private boolean served;

      @Override
      public int available() {
        return 5; // 谎报有数据：让 pump 继续 read，从而撞上 IOException
      }

      @Override
      public int read() throws IOException {
        return read(new byte[1], 0, 1) == -1 ? -1 : 'a';
      }

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        if (served) {
          throw new IOException("Stream closed");
        }
        served = true;
        Arrays.fill(buffer, offset, offset + 5, (byte) 'a');
        return 5;
      }
    };
  }

  /** 惰性字节流：吐 {@code count} 个 {@code c}，不占 8 MiB 堆（测试机堆上限 288m）。 */
  private static InputStream repeating(char c, long count) {
    return new InputStream() {
      private long served;

      @Override
      public int read() {
        if (served >= count) {
          return -1;
        }
        served++;
        return c;
      }

      @Override
      public int read(byte[] buffer, int offset, int length) {
        if (served >= count) {
          return -1;
        }
        int n = (int) Math.min(length, count - served);
        Arrays.fill(buffer, offset, offset + n, (byte) c);
        served += n;
        return n;
      }

      @Override
      public int available() {
        long remaining = count - served;
        return remaining <= 0 ? 0 : (int) Math.min(remaining, 1 << 16);
      }
    };
  }
}
