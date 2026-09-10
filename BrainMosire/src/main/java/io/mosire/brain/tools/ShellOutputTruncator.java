package io.mosire.brain.tools;

import io.mosire.agentlib.tool.ToolResultTruncator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Shell 输出裁剪三档（纯字节/字符串函数，不起进程）：档 1 采集期字节硬顶 → 档 3 {@code error-only} 模式 → 档 2 进上下文的 head-tail 截断。
 *
 * <p><strong>为什么不在这里再写一个头尾截断器</strong>：通用头尾截断（保留头尾 + 中段省略 + 截断提示）已由 {@link ToolResultTruncator}
 * 交付（P2-3），本类只做"采集限长 + 模式选择 + 拼装"，档 2 直接<b>调用</b>它。此处新增的只是 Bash 特有的两头：
 *
 * <ol>
 *   <li><b>档 1 采集期字节顶</b>（{@link #drain}）：流式读取时就把上限压住，超限部分<b>继续读但丢弃、只计数</b>——既防止大输出把 Agent
 *       撑爆（超限字节不入内存），又不停读（停读会让子进程写满管道而阻塞，把"大输出"变成"超时"，是同类实现最常见的坑）。
 *   <li><b>档 3 {@code error-only} 模式</b>（{@link #render}）：exit≠0 时只把 stderr 交给模型，stdout 不注入上下文。
 * </ol>
 *
 * <p><strong>采集是尽力而为</strong>：{@link #drain} <b>不抛</b>读取异常，读到哪算哪。理由——对子进程管道而言"读一半流没了"是正常路径
 * （本工具超时终止直接子进程后，JDK 回收该进程时会关掉它的管道，阻塞中的读取拿到的是 {@code IOException: Stream closed} 而非 EOF；
 * 实测）。把已读到的字节连同异常一起丢掉，会让失败消息里连"命令临死前打了什么"都看不到，正是该保留的信息。
 *
 * <p><strong>口径差（必须知道）</strong>：档 1 的上限按<b>字节</b>（{@link #DEFAULT_MAX_OUTPUT_BYTES}），档 2 的 {@link
 * ToolResultTruncator} 按 <b>UTF-16 字符</b>（{@link
 * ToolResultTruncator#DEFAULT_MAX_CHARS}）。两者单位不同、不互为换算， 故一个 3 字节汉字占 3 个档 1 额度、只占 1 个档 2
 * 额度。顶在字符中间时解码会出现一个替换字符（U+FFFD）。
 *
 * <p><strong>三个口径不要混淆</strong>（{@link Rendered}）：
 *
 * <ul>
 *   <li>{@code full} = <b>落库/完整口径</b>：恒含两路输出、不受档 2 字符预算影响。模式只决定"什么进上下文"，不决定"什么被保存" ——被 error-only
 *       抑制的 stdout 仍在 {@code full} 里，否则暂存下来的文档会缺掉被抑制的那一路；
 *   <li>{@code selected} = 按模式选定、<b>未</b>过字符预算的文本；
 *   <li>{@code injected} = {@code selected} 经 {@link ToolResultTruncator#truncate}
 *       后的文本，即真正进模型上下文的那份。
 * </ul>
 *
 * <p>注意：档 1 已经丢弃的字节无法落库，{@code full} 的口径是"<b>采集到的</b>完整文本"——文本里的采集提示会明说原始字节数， 不会让模型把残片当全量。
 */
public final class ShellOutputTruncator {

  /** 档 1 缺省上限：1 MiB（"1 MiB 级"的采集字节顶，见二期计划 P2-5）。 */
  public static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;

  /** 档 2 缺省上限（UTF-16 字符）：直接取通用截断器的缺省，避免两处各有一个"默认预算"。 */
  public static final int DEFAULT_MAX_INJECTED_CHARS = ToolResultTruncator.DEFAULT_MAX_CHARS;

  /** 采集块大小：一次 read 的粒度，与上限无关。 */
  private static final int CHUNK_BYTES = 8 * 1024;

  /** 档 1 截断提示标记（测试与模型都据它辨认"看到的是残片"）。 */
  private static final String CAPTURE_TAG = "[采集上限]";

  /** 档 3 生效提示标记。 */
  private static final String ERROR_ONLY_TAG = "[error-only]";

  private ShellOutputTruncator() {}

  /** 档 3：输出模式。{@code error-only} 只在 exit≠0 时改变注入内容（成功路径照常回显 stdout）。 */
  public enum Mode {
    /** 正常：两路输出都进上下文（受档 2 预算约束）。 */
    NORMAL("normal"),
    /** 仅错误：exit≠0 只回 stderr 摘要，stdout 不注入。 */
    ERROR_ONLY("error-only");

    private final String wireName;

    Mode(String wireName) {
      this.wireName = wireName;
    }

    /** 模型侧参数值（JSON Schema 的 enum 与本名一致，工具参数按它解析）。 */
    public String wireName() {
      return wireName;
    }

    /**
     * 解析参数值（大小写不敏感、容忍首尾空白）。
     *
     * @throws IllegalArgumentException 取值不在枚举内（调用方落地 {@code INVALID_ARGUMENTS}——绝不静默退化为 normal，
     *     那会让"我要求只回错误"悄悄变成"两路都进上下文"）
     */
    public static Mode parse(String raw) {
      if (raw != null) {
        String trimmed = raw.trim();
        for (Mode mode : values()) {
          if (mode.wireName.equalsIgnoreCase(trimmed)) {
            return mode;
          }
        }
      }
      throw new IllegalArgumentException("mode 取值非法（可选 normal / error-only）: " + raw);
    }
  }

  /**
   * 档 1 采集结果。
   *
   * @param text 采集到的文本（承接的字节按 UTF-8 解码；顶在字符中间时尾部是一个替换字符）
   * @param keptBytes 实际留下的字节数（≤ 采集上限）
   * @param totalBytes 子进程写出的总字节数（含被丢弃的部分——它被计数但不曾进入内存）
   * @param truncated 是否触到采集上限（{@code totalBytes > keptBytes}）
   */
  public record Captured(String text, int keptBytes, long totalBytes, boolean truncated) {

    public Captured {
      Objects.requireNonNull(text, "text");
    }
  }

  /**
   * 一次调用的三个文本口径（见类 Javadoc）。
   *
   * @param full 落库/完整口径（恒含两路输出）
   * @param selected 按模式选定、未过字符预算的文本
   * @param injected 真正进上下文的文本（{@code selected} 经档 2 截断）
   */
  public record Rendered(String full, String selected, String injected) {

    public Rendered {
      Objects.requireNonNull(full, "full");
      Objects.requireNonNull(selected, "selected");
      Objects.requireNonNull(injected, "injected");
    }

    /** 档 2 是否真的动了刀（{@code selected} 超出字符预算）。 */
    public boolean cutByBudget() {
      return !selected.equals(injected);
    }
  }

  /**
   * 档 1：读一条子进程输出流，最多在内存里留 {@code maxBytes} 字节。
   *
   * <p>读到 EOF 为止：超限部分照样读、照样计数，只是不写进内存。这个"读完再丢"的顺序是刻意的——提前停读会让子进程写满管道后阻塞，
   * 本该"截断"的大输出会变成"超时"（后者语义完全不同，且模型看不到任何输出）。
   *
   * @param in 子进程的 stdout/stderr（调用方负责关闭）
   * @param maxBytes 采集上限（字节，正整数）
   */
  public static Captured drain(InputStream in, int maxBytes) {
    return drain(in, maxBytes, () -> false);
  }

  /**
   * 同 {@link #drain(InputStream, int)}，外加"放弃"条件。
   *
   * <p>{@code stop} 是给"本次执行已经放弃收尾"的调用方用的：直接子进程已被终止、宽限已过，若仍有后代进程占着管道并持续产出（如 {@code yes}），
   * 无条件读满就意味着一个永远空转的读取线程（还会让那个后代进程永远写下去）。置位 {@code stop} 后读取线程在下一块数据上收手，
   * 那个后代终将写满管道而自行停住。<b>正常路径不要传会置位的条件</b>——提前收手会让本该完整的输出变成残片。
   *
   * @param stop 返回 true 即停止读取（取已采集内容）；在每块数据之间检查，故对已阻塞在空管道上的读取不起作用
   */
  public static Captured drain(InputStream in, int maxBytes, BooleanSupplier stop) {
    Objects.requireNonNull(in, "in");
    Objects.requireNonNull(stop, "stop");
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes 必须为正数: " + maxBytes);
    }
    ByteArrayOutputStream kept = new ByteArrayOutputStream(Math.min(maxBytes, CHUNK_BYTES));
    byte[] chunk = new byte[CHUNK_BYTES];
    long total = 0;
    int read = -1;
    while (!stop.getAsBoolean()) {
      try {
        read = in.read(chunk);
      } catch (IOException e) {
        // 读取失败不改写已采集内容：对"随时可能被本工具终止"的子进程管道而言，流被关闭是正常路径而不是错误
        // （实测：终止直接子进程后，JDK 回收进程时会关掉它的管道，阻塞中的读取拿到的是 "Stream closed" 而非 EOF）
        break;
      }
      if (read == -1) {
        break;
      }
      total += read;
      int room = maxBytes - kept.size();
      if (room > 0) {
        kept.write(chunk, 0, Math.min(room, read));
      }
    }
    byte[] bytes = kept.toByteArray();
    return new Captured(
        new String(bytes, StandardCharsets.UTF_8), bytes.length, total, total > bytes.length);
  }

  /**
   * 三档合流：把一次执行的两路采集结果渲染成"进上下文"与"落库"两个口径。
   *
   * <p>顺序是刻意的——先按模式选（档 3），再过字符预算（档 2）：error-only 的语义是"stdout 根本不进上下文"，而不是"进了再被截掉"。
   *
   * @param stdout stdout 采集结果
   * @param stderr stderr 采集结果
   * @param exitCode 退出码（超时等失败路径不调用本方法）
   * @param mode 输出模式
   * @param maxChars 档 2 预算（UTF-16 字符，正整数）
   */
  public static Rendered render(
      Captured stdout, Captured stderr, int exitCode, Mode mode, int maxChars) {
    Objects.requireNonNull(stdout, "stdout");
    Objects.requireNonNull(stderr, "stderr");
    Objects.requireNonNull(mode, "mode");
    String full = body(stdout, stderr, exitCode, Mode.NORMAL);
    String selected = mode == Mode.NORMAL ? full : body(stdout, stderr, exitCode, Mode.ERROR_ONLY);
    return new Rendered(full, selected, ToolResultTruncator.truncate(selected, maxChars));
  }

  /**
   * 失败路径（超时/被中断）的输出口径：与 {@link Mode} 无关（失败时没有"正常输出"可言，两路都值得看），只把已捕获内容——含档 1 的截断自报 ——过一遍档 2 预算。不带
   * {@code exitCode} 行：失败路径没有正常退出码，编一个只会误导模型。
   *
   * @param maxChars 档 2 预算（UTF-16 字符，正整数）
   */
  public static String renderAborted(Captured stdout, Captured stderr, int maxChars) {
    Objects.requireNonNull(stdout, "stdout");
    Objects.requireNonNull(stderr, "stderr");
    StringBuilder text = new StringBuilder();
    text.append(captureNotice("stdout", stdout)).append(captureNotice("stderr", stderr));
    if (!stdout.text().isBlank()) {
      text.append("stdout:\n").append(stdout.text()).append('\n');
    }
    if (!stderr.text().isBlank()) {
      text.append("stderr:\n").append(stderr.text()).append('\n');
    }
    return text.isEmpty()
        ? "（未捕获到输出：后代进程可能仍占着管道）"
        : ToolResultTruncator.truncate(text.toString(), maxChars);
  }

  /** 按模式拼装正文（不含档 2 截断）。 */
  private static String body(Captured stdout, Captured stderr, int exitCode, Mode mode) {
    StringBuilder text = new StringBuilder();
    text.append("exitCode: ").append(exitCode).append('\n');
    text.append(captureNotice("stdout", stdout)).append(captureNotice("stderr", stderr));
    if (mode == Mode.ERROR_ONLY && exitCode != 0) {
      // 抑制 stdout 而非丢弃它：字数与去向都在提示里，模型能自己决定要不要用更精确的手段重取
      text.append(ERROR_ONLY_TAG)
          .append(" 仅回 stderr 摘要，已抑制 stdout ")
          .append(stdout.totalBytes())
          .append(" 字节\n");
    } else {
      text.append("stdout:\n").append(stdout.text()).append('\n');
    }
    text.append("stderr:\n").append(stderr.text());
    return text.toString();
  }

  /** 档 1 截断是"输出少了"，必须自报（与档 2 的截断提示同理：没有它调用方无法区分全量与残片）。未截断时返回空串。 */
  private static String captureNotice(String stream, Captured captured) {
    if (!captured.truncated()) {
      return "";
    }
    return CAPTURE_TAG
        + " "
        + stream
        + " 原始 "
        + captured.totalBytes()
        + " 字节，仅保留前 "
        + captured.keptBytes()
        + " 字节（超出部分已丢弃、未进入内存）\n";
  }
}
