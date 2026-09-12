package io.mosire.main.approval;

import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.PendingApprovals;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * tty 审批通道（三期 S4-B2）：把"待人裁决"打到控制终端上、读一行答复（{@code y}/{@code a}/其余）。
 *
 * <p><b>为什么直开 {@code /dev/tty}，不是 {@code System.in}</b>（证据，别再改回去）：{@code run} 默认 {@code
 * mcpExpose=true}，主 Agent 的工具面经<b>stdio</b> MCP 暴露（{@code AgentToMcpServer.start} → {@code
 * StdioServerTransportProvider} → {@code System.in}/{@code System.out} 是协议流）；本仓 {@code Main}
 * 自陈"stdout 保留给 MCP stdio 流"。去读 {@code System.in} 就是与 MCP 协议<b>抢同一个流</b>：人敲的 {@code y}
 * 会被协议层当成一帧坏消息。{@code /dev/tty} 是"本进程的控制终端"这条<b>独立的</b>文件描述符，与 stdin/stdout 重定向到哪儿无关。
 *
 * <p><b>无控制终端 = 通道不可用，不是"默认同意"</b>：{@link #available()} 要求 ① 进程有控制终端（{@link System#console()}
 * 非空——这是"能开且是终端"的保守代理）且 ② {@code /dev/tty} 开得开。任一不成立就返回 {@code false}，编排器据此 <b>不把它算作可用通道</b>（全都不可用
 * ⇒ 立即 DENY）。本类任何地方都<b>没有</b>"问不到人就放行"的分支。
 *
 * <p><b>串行化</b>：一个进程只有一块控制终端，两个人同时提问会把提示与答复交错。因此本类只有<b>一条</b>读线程：{@link #publish} 把请求入队，读线程按 FIFO
 * 逐条打印提示、读一行、把答复落进登记表（{@link #promptLock} 保护"打印 + 读"这一段， 它是"同进程内提示串行化"的那把锁）。两次审批因此天然排队，不会互相抢终端。
 *
 * <p><b>{@link #await} 为什么不自己读</b>：{@code ApprovalChannel.await} 的两条硬契约是"自带等待上界"与"响应中断"。裸读 {@code
 * /dev/tty} 两条都不满足——它既没有上界，也不理会 {@code Thread.interrupt()}（{@code interrupt()} 对不检查中断位的阻塞读
 * <b>没有</b>效力；探针实测：不响应中断的通道在编排器返回 1 s 后等待线程仍 {@code
 * isAlive()==true}）。所以本类把"读"交给那条<b>长驻</b>读线程（进程内一条，不是每次审批一条），{@link #await}
 * 则转成登记表上的条件变量等待（有界、可中断、不自旋）——编排器把它 interrupt 掉时它立刻返回。
 *
 * <p><b>已知边界（记账，别当成已解决）</b>：
 *
 * <ul>
 *   <li><b>陈旧输入</b>：本类不做 termios 级输入冲洗（Java 无该能力），因此"提示出现<b>之前</b>就在终端上敲下的那一行"可能被当成
 *       对本次提示的答复。真实终端上人不会对着空屏乱敲，且 {@link PendingApprovals#decide} 只认第一个答复（后续答复被忽略）， 但这确实是一条存在的能力边界；
 *   <li><b>读面坏掉</b>：{@code /dev/tty} 读到 EOF（终端消失）或读抛异常时，读线程给当次请求记 {@code DENY} 并把通道标记为 {@code
 *       broken}（{@link #available()} 随后恒 {@code false}）——已是 fail-closed 的紧一侧；
 *   <li><b>关流不保证唤醒阻塞中的读</b>：{@link #close()} 关掉流、中断读线程，但阻塞在 {@code read} 上的线程不保证被唤醒
 *       （读线程是守护线程，不挡进程退出）。
 * </ul>
 *
 * <p>线程安全：可用性/队列/流生命周期各自同步；{@link #available()} 会惰性打开 {@code /dev/tty}（开成功即缓存并起读线程，
 * 此后不再重开——重开会得到第二个 fd，把提示面劈成两半）。
 */
public final class TtyApprovalChannel implements ApprovalChannel, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(TtyApprovalChannel.class);

  /** 控制终端设备：本类唯一会打开的路径（没有"可配置的 tty 路径"这个入口）。 */
  public static final Path DEFAULT_TTY = Path.of("/dev/tty");

  private final PendingApprovals pending;
  private final Path ttyPath;
  private final BooleanSupplier controlTerminal;
  private final ReaderOpener openReader;
  private final WriterOpener openWriter;

  /** 流生命周期锁（开/关；<b>不</b>覆盖阻塞中的读——否则 {@link #close()} 会被读线程卡住）。 */
  private final Object streamLock = new Object();

  /** 提示面串行化的那把锁：打印提示 + 读一行这一段独占（同进程内不会有两块终端抢屏）。 */
  private final ReentrantLock promptLock = new ReentrantLock();

  private final BlockingQueue<ApprovalRequest> toPrompt = new LinkedBlockingQueue<>();

  private BufferedReader reader;
  private Writer writer;
  private volatile Thread readerThread;

  /** 读面已废（EOF/IO 故障）：{@link #available()} 恒 false、{@link #await} 立刻按"没人答"返回。 */
  private volatile boolean broken;

  private volatile boolean closed;

  /**
   * 生产装配：直开 {@code /dev/tty}，是否有控制终端由 {@link System#console()} 代理。
   *
   * <p>构造期<b>不</b>打开任何文件（可用性在 {@link #available()} 首次被调时惰性求值）——"没终端"的进程不该因为装配了审批面 就在启动期多一个失败点。
   */
  public TtyApprovalChannel(PendingApprovals pending) {
    this(
        pending,
        DEFAULT_TTY,
        () -> System.console() != null,
        Files::newBufferedReader,
        Files::newBufferedWriter);
  }

  /**
   * 测试缝（包内可见）：把"有无控制终端"与"怎么打开终端流"换成注入件——本机跑测试没有控制终端是常态，离线用例靠它验 {@code y}/{@code a}/空行
   * 的解析与"先落登记表再返回"这条硬契约。
   */
  TtyApprovalChannel(
      PendingApprovals pending,
      BooleanSupplier controlTerminal,
      ReaderOpener openReader,
      WriterOpener openWriter) {
    this(pending, DEFAULT_TTY, controlTerminal, openReader, openWriter);
  }

  /**
   * 终态构造：登记表是<b>进程内共享</b>的那一份（同一进程只能有一个 {@link PendingApprovals}，见 App 装配）——本类只读它、只经 {@code decide}
   * 写它，<b>有意</b>持实时引用而非快照。
   *
   * <p>不加 {@code @SuppressFBWarnings("EI_EXPOSE_REP2")}：本构造器的存引用逐一经 {@link Objects#requireNonNull}
   * 中转，SpotBugs 未在此报告该模式（实测加了标注反被 {@code US_USELESS_SUPPRESSION_ON_METHOD} 判红）。将来若真被报告，抑制要加在
   * <b>持有引用的这个构造器</b>上，别挂到只做委托的重载上。
   */
  private TtyApprovalChannel(
      PendingApprovals pending,
      Path ttyPath,
      BooleanSupplier controlTerminal,
      ReaderOpener openReader,
      WriterOpener openWriter) {
    this.pending = Objects.requireNonNull(pending, "pending");
    this.ttyPath = Objects.requireNonNull(ttyPath, "ttyPath");
    this.controlTerminal = Objects.requireNonNull(controlTerminal, "controlTerminal");
    this.openReader = Objects.requireNonNull(openReader, "openReader");
    this.openWriter = Objects.requireNonNull(openWriter, "openWriter");
  }

  @Override
  public String name() {
    return "tty";
  }

  @Override
  public boolean available() {
    if (closed || broken) {
      return false;
    }
    if (!controlTerminal.getAsBoolean()) {
      // 无控制终端：不可用（<b>不是</b>"默认同意"——编排器会因此不把它算作可用通道）
      return false;
    }
    try {
      ensureOpen();
      return true;
    } catch (IOException | RuntimeException failure) {
      LOG.debug("tty 审批通道不可用（{} 打不开）", ttyPath, failure);
      return false;
    }
  }

  /** 非阻塞入队；读线程随后打印提示。打不开终端 ⇒ 抛（编排器 {@code catch (Throwable)} 转 DENY，fail-closed 且是<b>立即</b>的）。 */
  @Override
  public void publish(ApprovalRequest req) {
    Objects.requireNonNull(req, "req");
    if (closed) {
      return;
    }
    try {
      ensureOpen();
    } catch (IOException failure) {
      // 可用性检查说"能开"、这里却开不开 = 真故障（终端被拔了/权限变了）：响亮拒绝，不静默降级
      throw new UncheckedIOException("tty 审批通道打不开 " + ttyPath, failure);
    }
    toPrompt.add(req);
  }

  /**
   * 等人答复：转成登记表上的<b>有界、可中断</b>等待（答复由读线程经 {@link PendingApprovals#decide} 落表后唤醒本等待）。
   *
   * <p>返回空 = 到点没人答（<b>不是</b>放行）。读面已废（EOF/故障）时立刻返回空——上层会去问别的通道或按超时拒，同样是 fail-closed。
   */
  @Override
  public Optional<ApprovalDecision> await(String id, Duration wait) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(wait, "wait");
    if (closed || broken) {
      return Optional.empty();
    }
    return pending.await(id, wait);
  }

  @Override
  public void close() {
    closed = true;
    Thread thread = readerThread;
    if (thread != null) {
      // 读线程阻塞在 read 上时 interrupt 不保证生效——但它是守护线程，不挡进程退出；这里只是尽力唤醒
      thread.interrupt();
    }
    synchronized (streamLock) {
      closeQuietly(reader);
      closeQuietly(writer);
      reader = null;
      writer = null;
    }
    LOG.info("tty 审批通道已关闭");
  }

  // ---- 打开与读线程 ----

  private void ensureOpen() throws IOException {
    synchronized (streamLock) {
      if (closed || reader != null) {
        return;
      }
      BufferedReader openedReader = openReader.open(ttyPath);
      Writer openedWriter;
      try {
        openedWriter = openWriter.open(ttyPath);
      } catch (IOException | RuntimeException failure) {
        closeQuietly(openedReader);
        throw failure;
      }
      this.reader = openedReader;
      this.writer = openedWriter;
      // 平台线程（不是虚拟线程）：这条线程的语义就是"长期占着一个阻塞读"（进程内一条，不是每次审批一条），
      // 而阻塞式文件 I/O 在虚拟线程上可能钉住载体线程——这里用一个诚实的平台守护线程，语义最直白。
      Thread thread =
          Thread.ofPlatform().daemon().name("approval-tty-reader").unstarted(this::readerLoop);
      this.readerThread = thread;
      thread.start();
      LOG.debug("tty 审批通道已打开 {}", ttyPath);
    }
  }

  /**
   * 唯一的读线程：按 FIFO 逐条"打印提示 → 读一行 → 把答复落进登记表"。
   *
   * <p>只有它碰终端输入，因此提示与答复天然一一对应（{@link #promptLock} 把"打印 + 读"锁成一段：即便将来多一个提示生产者，也不会交错）。
   */
  private void readerLoop() {
    while (true) {
      ApprovalRequest req;
      try {
        req = toPrompt.take();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
      if (closed) {
        return;
      }
      String line;
      promptLock.lock();
      try {
        writePrompt(req);
        line = reader.readLine();
      } catch (IOException | RuntimeException failure) {
        LOG.warn("tty 审批通道读面故障：本通道此后按不可用处理（当次与后续请求都按 fail-closed 拒） id={}", req.id(), failure);
        // 读面坏了：当次请求按"没人答"记 DENY（不能让调用方白等到超时），并标记通道不可用
        broken = true;
        settle(req, ApprovalDecision.DENY);
        return;
      } finally {
        promptLock.unlock();
      }
      if (line == null) {
        // EOF：控制终端消失（人已经不可能再答了）——按 fail-closed 记 DENY 并停掉读面
        LOG.warn("tty 审批通道读到 EOF（控制终端消失），本通道此后按不可用处理 id={}", req.id());
        broken = true;
        settle(req, ApprovalDecision.DENY);
        return;
      }
      settle(req, parse(line));
    }
  }

  /**
   * 一行答复 → 决议：{@code y} = 本次、{@code a} = 本会话，其余（含空行、无法识别的单词）一律<b>拒</b>。
   *
   * <p>回车不是同意：终端上最廉价的动作（误触回车）必须是<b>最保守</b>的结果。这条口径由 {@code TtyApprovalChannelTest} 钉住。
   */
  static ApprovalDecision parse(String line) {
    return switch (line.strip().toLowerCase(Locale.ROOT)) {
      case "y" -> ApprovalDecision.APPROVE_ONCE;
      case "a" -> ApprovalDecision.APPROVE_SESSION;
      default -> ApprovalDecision.DENY;
    };
  }

  /** 提示行（只带工具名/类别/摘要——{@code summary} 由工具自报，本类不拼参数、不带明文）。 */
  private void writePrompt(ApprovalRequest req) throws IOException {
    writer.write(
        "[审批] 工具="
            + req.tool()
            + " 类别="
            + req.classKey()
            + " 摘要="
            + req.summary()
            + " [y=本次 / a=本会话 / N=拒绝] ");
    writer.flush();
  }

  /**
   * 答复<b>先落登记表</b>（{@link ApprovalChannel#await} 的硬约束 3），等待方由条件变量唤醒；通道自己不缓存任何决议。
   *
   * <p>登记表说"这条已经没用了"（已决议/已过期/已被编排器摘除）时只记日志：人的这次按键不该被当成对另一条请求的答复。
   */
  private void settle(ApprovalRequest req, ApprovalDecision decision) {
    if (pending.decide(req.id(), decision, name())) {
      LOG.info("tty 审批答复已记录 id={} decision={}", req.id(), decision);
    } else {
      LOG.debug("tty 审批答复被忽略（该请求已决议/已过期/已被摘除） id={}", req.id());
    }
  }

  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception failure) {
      LOG.debug("关闭 tty 流失败（继续）", failure);
    }
  }

  /** 开读面（生产 = {@code Files.newBufferedReader}；打开失败 = 通道不可用）。 */
  @FunctionalInterface
  interface ReaderOpener {
    BufferedReader open(Path path) throws IOException;
  }

  /** 开写面（与读面各自一个 fd：提示与答复互不干扰）。 */
  @FunctionalInterface
  interface WriterOpener {
    Writer open(Path path) throws IOException;
  }
}
