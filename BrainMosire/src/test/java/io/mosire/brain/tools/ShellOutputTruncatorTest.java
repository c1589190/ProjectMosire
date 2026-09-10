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
 * head-tail 截断（<b>复用</b> {@link ToolResultTruncator}，本类不重造头尾算法）。 此外还钉住"落库口径"： {@link
 * ShellOutputTruncator.Rendered#full()} 恒含两路输出（error-only 模式下同样如此），否则暂存下来的文档会缺掉被模式抑制的那一路。
 */
class ShellOutputTruncatorTest {

  private static final String OUT_HEAD = "OUT-HEAD-哨兵";
  private static final String OUT_TAIL = "OUT-TAIL-哨兵";
  private static final String ERR_HEAD = "ERR-HEAD-哨兵";
  private static final String ERR_TAIL = "ERR-TAIL-哨兵";
  private static final String MIDDLE = "MIDDLE-中段哨兵";

  // ---------- 档 1：采集期字节硬顶 ----------

  @Test
  void drainKeepsEverythingUnderTheCap() throws IOException {
    byte[] payload = "hello 世界".getBytes(UTF_8);

    ShellOutputTruncator.Captured captured =
        ShellOutputTruncator.drain(new ByteArrayInputStream(payload), 1024);

    assertThat(captured.text()).isEqualTo("hello 世界");
    assertThat(captured.keptBytes()).isEqualTo(payload.length);
    assertThat(captured.totalBytes()).isEqualTo(payload.length);
    assertThat(captured.truncated()).isFalse();
  }

  /** 8 MiB 源 + 1 KiB 顶：只保留 1 KiB，但总数照实计到 8 MiB（超限部分不入内存、仍被读取以免子进程写满管道阻塞）。 */
  @Test
  void drainClipsAtTheCapAndOnlyCountsTheRest() throws IOException {
    long total = 8L * 1024 * 1024;

    ShellOutputTruncator.Captured captured =
        ShellOutputTruncator.drain(repeating('a', total), 1024);

    assertThat(captured.keptBytes()).isEqualTo(1024);
    assertThat(captured.text()).hasSize(1024);
    assertThat(captured.totalBytes()).isEqualTo(total);
    assertThat(captured.truncated()).isTrue();
  }

  @Test
  void drainRejectsNonPositiveCap() {
    assertThatThrownBy(() -> ShellOutputTruncator.drain(new ByteArrayInputStream(new byte[0]), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxBytes");
  }

  /** 采集是尽力而为：流中途坏掉（子进程被终止时 JDK 会关掉它的管道）不能连已读到的字节一起丢。 */
  @Test
  void drainKeepsWhatItReadWhenTheStreamBreaksMidway() {
    InputStream broken =
        new InputStream() {
          private boolean served;

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

    ShellOutputTruncator.Captured captured = ShellOutputTruncator.drain(broken, 1024);

    assertThat(captured.text()).isEqualTo("aaaaa");
    assertThat(captured.keptBytes()).isEqualTo(5);
    assertThat(captured.truncated()).isFalse();
  }

  /** 放弃条件：无底流的读取能在置位后收手（否则读取线程会永远空转——调用方已放弃本次收尾）。 */
  @Test
  @Timeout(15)
  void drainStopsEarlyWhenTheCallerAbandonsTheRead() {
    AtomicInteger reads = new AtomicInteger();

    ShellOutputTruncator.Captured captured =
        ShellOutputTruncator.drain(endlessStream(reads), 1024, () -> reads.get() >= 3);

    assertThat(reads.get()).isLessThanOrEqualTo(4);
    assertThat(captured.keptBytes()).isEqualTo(1024);
    assertThat(captured.truncated()).isTrue();
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
        new ShellOutputTruncator.Captured("a".repeat(1024), 1024, 3_000_000L, true);

    ShellOutputTruncator.Rendered rendered =
        ShellOutputTruncator.render(stdout, captured(""), 0, modeNormal(), 8000);

    assertThat(rendered.injected())
        .contains("[采集上限]")
        .contains("3000000")
        .contains("1024")
        .contains("stdout");
    // 采集顶已丢弃的字节无法落库：落库口径就是"采集到的"部分，UI 不因此谎报全量
    assertThat(rendered.full()).hasSizeLessThan(8000);
  }

  // ---------- 失败路径（超时/中断）的输出口径 ----------

  /** 失败路径不是绕过档 1 自报的后门：超时/被中断时同样告诉模型"这是残片"，且照样过档 2 预算。 */
  @Test
  void abortedPathKeepsBothStreamsAndTheCaptureNoticeWithinBudget() {
    ShellOutputTruncator.Captured stdout =
        new ShellOutputTruncator.Captured("y".repeat(1024), 1024, 1_500_000L, true);
    ShellOutputTruncator.Captured stderr = captured(ERR_HEAD);

    String aborted = ShellOutputTruncator.renderAborted(stdout, stderr, 400);

    assertThat(aborted)
        .contains("[采集上限]")
        .contains("1500000")
        .contains("stderr:")
        .contains(ERR_HEAD)
        // 失败路径没有正常退出码，编一个只会误导模型
        .doesNotContain("exitCode:");
    assertThat(aborted.length()).isLessThanOrEqualTo(400);
  }

  @Test
  void abortedPathWithNothingCapturedSaysSo() {
    assertThat(ShellOutputTruncator.renderAborted(captured(""), captured("   "), 8000))
        .contains("未捕获到输出");
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

  /** 完整（未被档 1 裁剪）的采集结果。 */
  private static ShellOutputTruncator.Captured captured(String text) {
    return new ShellOutputTruncator.Captured(
        text, text.getBytes(UTF_8).length, text.getBytes(UTF_8).length, false);
  }

  /** 无底字节流：每次读都返回满额数据，并记下读取次数（用于验证"放弃条件"确实让读取收手）。 */
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
    };
  }
}
