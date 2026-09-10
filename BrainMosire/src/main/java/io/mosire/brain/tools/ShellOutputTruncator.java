package io.mosire.brain.tools;

import io.mosire.agentlib.tool.ToolResultTruncator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Shell 输出采集与裁剪三档（纯字节/字符串函数，自己不起进程）：档 1 采集期字节硬顶 → 档 3 {@code error-only} 模式 → 档 2 进上下文的 head-tail
 * 截断。
 *
 * <p><strong>为什么不在这里再写一个头尾截断器</strong>：通用头尾截断（保留头尾 + 中段省略 + 截断提示）已由 {@link ToolResultTruncator}
 * 交付（P2-3），本类只做"采集限长 + 模式选择 + 拼装"，档 2 直接<b>调用</b>它。此处新增的只是 Bash 特有的两头：
 *
 * <ol>
 *   <li><b>档 1 采集期字节顶</b>（{@link Capture}）：流式读取时就把上限压住，超限部分<b>继续读但丢弃、只计数</b>——既防止大输出把 Agent
 *       撑爆（超限字节不入内存），又不停读（停读会让子进程写满管道而阻塞，把"大输出"变成"超时"）。
 *   <li><b>档 3 {@code error-only} 模式</b>（{@link #render}）：exit≠0 时只把 stderr 交给模型，stdout 不注入上下文。
 * </ol>
 *
 * <p><strong>采集是尽力而为、且不依赖"读到 EOF"</strong>：{@link Capture#pump} 只在 {@link InputStream#available()}
 * 报告有数据时才读（此时读不会阻塞），无数据时轮询；已读字节<b>逐块</b>发布，随时可快照。
 * 这条设计的由来是一次实测缺陷：直接子进程退出后，若其后代仍占着管道写端，阻塞式读取既等不到数据也等不到 EOF（读取线程永久卡死），
 * 而"读取返回时才发布"的缓冲让工具拿到空快照——命令明明打了输出，却回报成"成功 + 空输出"。现在的规则是：读不到就如实说， 绝不把"没读到"伪装成"没有输出"。
 *
 * <p><strong>可观测边界（"读到尽头"的确切含义）</strong>：JDK 回收直接子进程时会先把管道里的残留字节搬进内存流、再关掉读端， 且这一切发生在 {@code
 * waitFor} 返回<b>之前</b>（JDK 21 实测 20/20：返回那一刻 {@code available()} 已能看到残留字节）——所以
 * 直接子进程自己写出的字节不会漏。此后该读端<b>永远</b>只会给出 {@code -1}（实测：即使后代仍占着写端，{@code read} 也立即返回 {@code
 * -1}），而后代此后的写入再也读不到（实测：那部分字节永远不会出现）。{@link Capture#pump} 因此把"直接子进程已回收且流已静音"
 * 视为读到了<b>可观测范围的尽头</b>（{@link Captured#endObserved()}）；未到尽头就收手（放弃/被中断/读端异常关闭）时 {@code endObserved}
 * 为 false，渲染层会附 <b>{@value #INCOMPLETE_TAG} 披露行</b>——残片绝不能冒充全量。
 *
 * <p><strong>口径差（必须知道）</strong>：档 1 的上限按<b>字节</b>（{@link #DEFAULT_MAX_OUTPUT_BYTES}）、档 2 的 {@link
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

  /** 轮询间隔：无数据时的等待粒度（只影响收尾延迟，不影响吞吐——有数据时连续读、不休眠）。 */
  private static final long POLL_MILLIS = 10;

  /** 直接子进程退出后的静音窗口：窗口内一直无数据即认为可观测范围内已读完（见类 Javadoc 的"可观测边界"）。 */
  private static final long QUIET_AFTER_EXIT_MILLIS = 60;

  /** 放弃后的有界排空时间：即使后代仍在持续刷输出，也只再读这么久（保证读取线程真的结束）。 */
  private static final long ABANDON_DRAIN_MILLIS = 200;

  /** 读端报错后的重试次数：JDK 回收子进程时会"搬残留字节 + 关读端"，重试可跨过这个交换瞬间而不丢已搬进内存流的字节。 */
  private static final int CLOSE_RETRIES = 3;

  /** 失败路径里命令回显的字符上限：回显只为定位用，整段塞进消息会让一次失败变成 100× 预算漏洞。 */
  private static final int MAX_ECHO_CHARS = 200;

  /** 档 1 截断提示标记（测试与模型都据它辨认"看到的是残片"）。 */
  static final String CAPTURE_TAG = "[采集上限]";

  /** 读取未到尽头就收手的披露标记（残片不得冒充全量）。 */
  static final String INCOMPLETE_TAG = "[输出可能不完整]";

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
   * 档 1 采集结果（一次快照）。
   *
   * @param text 采集到的文本（承接的字节按 UTF-8 解码；顶在字符中间时尾部是一个替换字符）
   * @param keptBytes 实际留下的字节数（≤ 采集上限）
   * @param totalBytes 子进程写出的总字节数（含被丢弃的部分——它被计数但不曾进入内存）
   * @param truncated 是否触到采集上限（{@code totalBytes > keptBytes}）
   * @param endObserved 是否读到了可观测范围的尽头（{@code read} 返回 -1／读端被回收器关闭，或直接子进程已回收且流已静音）； false =
   *     收手时可能还有未捕获的输出，渲染层必须披露（见类 Javadoc 的"可观测边界"）。注意：直接子进程被回收<b>之后</b>
   *     其后代写出的字节本就不在可观测范围内，不算这里的"未捕获输出"（读不到，也无从逐条披露）
   */
  public record Captured(
      String text, int keptBytes, long totalBytes, boolean truncated, boolean endObserved) {

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
   * 一路子进程输出的采集器：档 1 字节顶 + {@code available()} 轮询读取 + 增量发布。
   *
   * <p><b>为什么不是"阻塞式读到 EOF"</b>：直接子进程退出后，若它的后代还占着管道写端（{@code ShellTool} 的取舍：不回收进程树），
   * 阻塞中的读取会永远等不到数据也等不到 EOF——读取线程永久卡死（线程泄漏），而"读取返回才发布"的缓冲让工具拿到空快照， 于是"命令明明打了输出"被回报成"成功 +
   * 空输出"（静默的错误输出，比报错更糟）。故：
   *
   * <ul>
   *   <li>只在 {@link InputStream#available()} 报告有数据时才 {@code read}（此时读不会阻塞）；无数据时只轮询；
   *   <li>已读字节逐块写入缓冲并立即可见（{@link #snapshot()}），发布不依赖读取结束；
   *   <li>终止条件恒存在：读到尽头、直接子进程已退出且流静音、{@link #abandon()} 后有界排空、线程被中断。
   * </ul>
   *
   * <p>本类线程安全：{@link #pump} 只能由一个线程调用（那正是"读取线程"），{@link #snapshot()} 可由其他线程随时调用； {@link
   * #markChildExited()} 与 {@link #abandon()} 由工具线程置位（volatile）。
   */
  public static final class Capture {

    private final int maxBytes;
    private final ByteArrayOutputStream kept;
    private final byte[] chunk = new byte[CHUNK_BYTES];
    private long total;
    private volatile boolean endObserved;
    private volatile boolean childExited;
    private volatile boolean abandoned;

    /**
     * @param maxBytes 采集上限（字节，正整数）
     */
    public Capture(int maxBytes) {
      if (maxBytes <= 0) {
        throw new IllegalArgumentException("maxBytes 必须为正数: " + maxBytes);
      }
      this.maxBytes = maxBytes;
      this.kept = new ByteArrayOutputStream(Math.min(maxBytes, CHUNK_BYTES));
    }

    /**
     * 直接子进程已回收（工具在 {@code waitFor}/终止之后置位）：回收器的"搬残留字节 + 关读端"已完成（在 {@code waitFor}
     * 返回前），此后"流静音"即可判定读到了可观测范围的尽头（见类 Javadoc）。
     */
    public void markChildExited() {
      childExited = true;
    }

    /** 本次执行放弃收尾（超时/中断后置位）：读完有界排空窗口就收手，不再等数据。 */
    public void abandon() {
      abandoned = true;
    }

    /**
     * 轮询读取直到终止条件成立（见类 Javadoc）。<b>不抛 IO 异常</b>：读不到就是读不到，已读字节照常保留。
     *
     * <p><b>调用方契约</b>：必须保证"直接子进程已结束"或"本次执行已放弃"时置位 {@link #markChildExited()}／ {@link
     * #abandon()}——静默的管道（有数据结束、也有永远静默两种）无法只靠 {@code available()} 与 EOF 区分，
     * 没有这两个信号时本方法不会凭空判定"读完了"（宁可等着，也不能把"没读到"说成"没有输出"）。
     *
     * @param in 子进程的一路输出（调用方负责关闭）
     */
    public void pump(InputStream in) {
      Objects.requireNonNull(in, "in");
      long quietSinceNanos = 0;
      long abandonSinceNanos = 0;
      int closeStreak = 0;
      while (true) {
        long now = System.nanoTime();
        if (abandoned) {
          if (abandonSinceNanos == 0) {
            abandonSinceNanos = now;
          } else if (now - abandonSinceNanos >= ABANDON_DRAIN_MILLIS * 1_000_000L) {
            endObserved = false; // 放弃：可能还有没读到的输出，渲染层据此披露
            return;
          }
        }
        int available;
        try {
          available = in.available();
        } catch (IOException closed) {
          // 连续失败才判定"读端没了"：回收器换流的瞬间会短暂报错，重试可跨过去而不丢已搬进内存流的残留字节
          if (++closeStreak >= CLOSE_RETRIES) {
            endObserved = childExited; // 子进程已退出=回收器关的；否则属异常收尾，如实记为"未观测到终结"
            return;
          }
          if (!park()) {
            endObserved = false;
            return;
          }
          continue;
        }
        if (available > 0) {
          int read;
          try {
            read = in.read(chunk, 0, Math.min(available, chunk.length));
            closeStreak = 0; // 读成功才算恢复
          } catch (IOException closed) {
            if (++closeStreak >= CLOSE_RETRIES) {
              endObserved = childExited;
              return;
            }
            if (!park()) {
              endObserved = false;
              return;
            }
            continue;
          }
          if (read == -1) {
            endObserved = true; // 读到尽头
            return;
          }
          offer(chunk, read);
          quietSinceNanos = 0;
          continue; // 有数据就连续读，不休眠
        }
        // available() == 0：这里绝不 read——管道读端若仍被后代占着，read 会永久阻塞（本类要消灭的形态）
        if (childExited) {
          if (quietSinceNanos == 0) {
            quietSinceNanos = now;
          } else if (now - quietSinceNanos >= QUIET_AFTER_EXIT_MILLIS * 1_000_000L) {
            endObserved = true; // 子进程已退出且流静音：可观测范围内已读完（见类 Javadoc）
            return;
          }
        }
        if (!park()) {
          endObserved = false;
          return;
        }
      }
    }

    /** 当前快照：随时可调用（读取进行中也能拿到已读字节），读取结束后即最终结果。 */
    public synchronized Captured snapshot() {
      byte[] bytes = kept.toByteArray();
      return new Captured(
          new String(bytes, StandardCharsets.UTF_8),
          bytes.length,
          total,
          total > bytes.length,
          endObserved);
    }

    /** 逐块发布：超限部分只计数不入内存（档 1）。 */
    private synchronized void offer(byte[] data, int length) {
      total += length;
      int room = maxBytes - kept.size();
      if (room > 0) {
        kept.write(data, 0, Math.min(room, length));
      }
    }
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
   * 失败路径（超时/被中断）的注入文本：标题 + 命令回显 + 已捕获输出（含档 1 截断自报与"可能不完整"披露）， <b>整体</b>过一遍档 2 预算。不带 {@code
   * exitCode} 行：失败路径没有正常退出码，编一个只会误导模型。
   *
   * <p>命令回显先按 {@link #MAX_ECHO_CHARS} 收短再进预算：命令是模型自己写的，回显只为定位——整段照抄会让一次失败 （超时/中断可逐回合重现）变成绕过 token
   * 预算的后门。
   *
   * @param headline 失败原因（如"命令超时（N 秒）…"），必须自带"后代可能仍存活"这类不确定性的交代
   * @param command 原始命令（回显前会被收短）
   * @param maxChars 档 2 预算（UTF-16 字符，正整数）
   */
  public static String renderAborted(
      String headline, String command, Captured stdout, Captured stderr, int maxChars) {
    Objects.requireNonNull(headline, "headline");
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(stdout, "stdout");
    Objects.requireNonNull(stderr, "stderr");
    StringBuilder text = new StringBuilder();
    text.append(headline).append('\n');
    text.append("command: ").append(shortenCommand(command)).append('\n');
    text.append(captureNotice("stdout", stdout)).append(captureNotice("stderr", stderr));
    text.append(incompleteNotice("stdout", stdout)).append(incompleteNotice("stderr", stderr));
    if (stdout.text().isBlank() && stderr.text().isBlank()) {
      text.append("（未捕获到输出：后代进程可能仍占着管道）");
    } else {
      if (!stdout.text().isBlank()) {
        text.append("stdout:\n").append(stdout.text()).append('\n');
      }
      if (!stderr.text().isBlank()) {
        text.append("stderr:\n").append(stderr.text()).append('\n');
      }
    }
    return ToolResultTruncator.truncate(text.toString(), maxChars);
  }

  /** 按模式拼装正文（不含档 2 截断）。 */
  private static String body(Captured stdout, Captured stderr, int exitCode, Mode mode) {
    StringBuilder text = new StringBuilder();
    text.append("exitCode: ").append(exitCode).append('\n');
    text.append(captureNotice("stdout", stdout)).append(captureNotice("stderr", stderr));
    text.append(incompleteNotice("stdout", stdout)).append(incompleteNotice("stderr", stderr));
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

  /** 读取没到尽头就收手（放弃/中断/读端异常）：输出可能少了，必须披露——残片冒充全量正是本项目最不能接受的失败形态。 */
  private static String incompleteNotice(String stream, Captured captured) {
    if (captured.endObserved()) {
      return "";
    }
    return INCOMPLETE_TAG + " " + stream + " 未读到流结束（管道可能仍被后代进程占用），其后可能还有未捕获的输出\n";
  }

  /** 命令回显收短：保留开头（足够辨认是哪条命令）并明说省略了多少。 */
  private static String shortenCommand(String command) {
    if (command.length() <= MAX_ECHO_CHARS) {
      return command;
    }
    return command.substring(0, MAX_ECHO_CHARS) + "…（命令回显已截断，原始 " + command.length() + " 字符）";
  }

  /** 轮询间隔休眠；返回 false 表示线程被中断（读取线程据此结束，中断标记原样归还）。 */
  private static boolean park() {
    try {
      Thread.sleep(POLL_MILLIS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
