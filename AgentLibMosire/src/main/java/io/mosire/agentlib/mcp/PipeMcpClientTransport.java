package io.mosire.agentlib.mcp;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 管道流 MCP 客户端传输（W3b 父子 stdio 链接的核心支点）：在同一对 InputStream/OutputStream 上实现标准 stdio 帧协议（一行一个 JSON-RPC
 * 消息，换行转义），供 {@link io.modelcontextprotocol.client.McpClient} 当作传输使用。
 *
 * <p>为什么需要它：SDK 官方 {@code StdioClientTransport} 自己 spawn 子进程（没有流构造），而 W3b 中 子 Agent 进程（MCP client
 * 角色）的 stdin/stdout 由父进程建好并注入——本类填补"复用已有管道"的缺口，行帧、序列化、 优雅关闭等语义与官方传输逐点对齐（实现逐条镜像其反编译字节码，仅去掉
 * stderr/errorSink 与进程自管部分）。
 *
 * <p>附加契约 {@link #whenClosed()}：管道对端关闭（读端 EOF / 大消息越界）或本端 {@link #closeGracefully()} 时完成该 Future。
 * <b>它不再是任何业务路径的等待点</b>（2026-09-13 子 Agent 终局设计 §2.2 "即退"修正）：子进程 <b>不再</b>在主线程阻塞等"父侧
 * 断开/关停"信号——那个等待（{@code whenClosed().get()}）正是"子体答完还空转 ≥171 s、父侧全程看到 RUNNING"的成因，已删除。
 * 今天生产代码<b>没有</b>调用方（只剩测试拿它观测关停时机），保留它是因为"链接何时关"本身仍是可观测事实。
 *
 * <p><b>线程必须是守护线程（2026-09-13，子 Agent 终局设计 §2.2 即退）</b>：两个 {@code ExecutorService}
 * 的线程若为非守护，进站线程会<b>永久阻塞</b>在 {@code readLine()} 上——管道是阻塞式 {@code InputStream}（通常就是 {@code
 * System.in}/fd 0），读阻塞<b>不可中断</b>，{@link #closeGracefully()} 的 {@code dispose()}（= {@code
 * shutdownNow}）只能置中断标志，线程仍卡在 read 里 ⇒ {@code main} 返回后 JVM 等它到天荒地老。实测（真子进程 thread dump）：即退后只剩
 * {@code main} 已返回、{@code DestroyJavaVM} 在等一个非守护的 {@code inboundLoop} 线程，子体"跑完不退出"换个形态复现。 守护化后 JVM
 * 可在 {@code main} 返回时正常收尾（子进程正常路径 exit 0；父侧 kill 路径的 EOF 语义逐字不变）。
 */
public final class PipeMcpClientTransport implements McpClientTransport {

  private static final Logger LOG = LoggerFactory.getLogger(PipeMcpClientTransport.class);

  /** 单行输入最大字节数（与官方 StdioClientTransport 默认一致：16 MiB）。 */
  private static final int DEFAULT_INPUT_MAX_SIZE = 16_777_216;

  private final Sinks.Many<McpSchema.JSONRPCMessage> inboundSink =
      Sinks.many().unicast().onBackpressureBuffer();
  private final Sinks.Many<McpSchema.JSONRPCMessage> outboundSink =
      Sinks.many().unicast().onBackpressureBuffer();

  private final McpJsonMapper jsonMapper;
  private final InputStream in;
  private final OutputStream out;
  private final int inputMaxSize;

  private final Scheduler inboundScheduler =
      Schedulers.fromExecutorService(
          Executors.newSingleThreadExecutor(daemonThreads("mosire-mcp-inbound")),
          "mosire-mcp-inbound");
  private final Scheduler outboundScheduler =
      Schedulers.fromExecutorService(
          Executors.newSingleThreadExecutor(daemonThreads("mosire-mcp-outbound")),
          "mosire-mcp-outbound");

  private final CompletableFuture<Void> closed = new CompletableFuture<>();

  private volatile boolean isClosing;

  /**
   * 守护线程工厂（见类注释"线程必须是守护线程"）：传输的收发线程只服务于这对管道——它们<b>不该</b>决定进程何时能退出。 进站线程会长期阻塞在不可中断的 {@code
   * readLine()} 上，非守护化等于"进程被自己的 IO 线程钉住"。
   */
  private static java.util.concurrent.ThreadFactory daemonThreads(String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    };
  }

  public PipeMcpClientTransport(McpJsonMapper jsonMapper, InputStream in, OutputStream out) {
    this(jsonMapper, in, out, DEFAULT_INPUT_MAX_SIZE);
  }

  /**
   * @param inputMaxSize 单行输入上限（字节），必须为正；超限按类内 {@link LineTooLongException} 语义处理（记录并关闭传输，与官方
   *     MaxSizeExceededException 行为对齐）
   */
  // 注入的 jsonMapper 与管道流仅作内部只读/受控使用（写侧 synchronized(out)），均不向外暴露引用，
  // 也不写入（mapper 只做序列化配置读取）；按构造注入 + 独占持有是传输对象的设计意图——EI_EXPOSE_REP2 抑制。
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public PipeMcpClientTransport(
      McpJsonMapper jsonMapper, InputStream in, OutputStream out, int inputMaxSize) {
    if (jsonMapper == null || in == null || out == null) {
      throw new IllegalArgumentException("jsonMapper/in/out 不能为空");
    }
    if (inputMaxSize <= 0) {
      throw new IllegalArgumentException("inputMaxSize 必须为正: " + inputMaxSize);
    }
    this.jsonMapper = jsonMapper;
    this.in = in;
    this.out = out;
    this.inputMaxSize = inputMaxSize;
  }

  /**
   * 管道关闭（对端断开 / 优雅关停）时完成。
   *
   * <p><b>生产代码无调用方</b>（2026-09-13 §2.2 "即退"后）：子 Agent 不再阻塞等关停信号（见类注释），测试用它观测关停时机—— 别再按"子体在主线程
   * {@code get()} 它"的旧口径理解本方法。
   *
   * <p>返回副本（{@link CompletableFuture#copy()}）：完成时机/值与原 Future 一致，但调用方拿不到内部引用、
   * 也不能经该副本反向操纵本实例（EI_EXPOSE_REP 防御性拷贝）。
   */
  public CompletableFuture<Void> whenClosed() {
    return closed.copy();
  }

  @Override
  public Mono<Void> connect(
      Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    return Mono.<Void>fromRunnable(() -> doConnect(handler))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private void doConnect(
      Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    LOG.debug("MCP 管道传输启动");
    handleIncomingMessages(handler);
    startInboundProcessing();
    startOutboundProcessing();
  }

  @Override
  public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
    Sinks.EmitResult emitted = outboundSink.tryEmitNext(message);
    if (emitted.isSuccess()) {
      return Mono.empty();
    }
    return Mono.error(new RuntimeException("Failed to enqueue message"));
  }

  @Override
  public <T> T unmarshalFrom(Object result, TypeRef<T> typeRef) {
    return jsonMapper.convertValue(result, typeRef);
  }

  @Override
  public Mono<Void> closeGracefully() {
    return Mono.fromRunnable(() -> isClosing = true)
        .then(
            Mono.defer(
                () -> {
                  inboundSink.tryEmitComplete();
                  outboundSink.tryEmitComplete();
                  return Mono.delay(Duration.ofMillis(100)).then();
                }))
        .then(
            Mono.defer(
                () ->
                    Mono.fromRunnable(
                        () -> {
                          inboundScheduler.dispose();
                          outboundScheduler.dispose();
                          closed.complete(null);
                        })))
        .then()
        .subscribeOn(Schedulers.boundedElastic());
  }

  // ---- 读侧（对端 → inboundSink） ----

  private void handleIncomingMessages(
      Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
    // 同官方实现：每条消息包成 Mono 交给响应处理器（客户端复用同一连接、按 id 路由应答）
    inboundSink.asFlux().flatMap(message -> Mono.just(message).transform(handler)).subscribe();
  }

  private void startInboundProcessing() {
    inboundScheduler.schedule(this::inboundLoop);
  }

  private void inboundLoop() {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      while (!isClosing) {
        String line = readLine(reader, inputMaxSize);
        if (line == null) {
          break; // 对端 EOF：链接断开（父进程关停的可靠信号）
        }
        try {
          McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, line);
          Sinks.EmitResult emitted = inboundSink.tryEmitNext(message);
          if (!emitted.isSuccess() && !isClosing) {
            LOG.error("入站消息入队失败: {}", message);
            break;
          }
        } catch (Exception e) {
          if (!isClosing) {
            LOG.error("处理入站消息失败（行）: {}", line, e);
          }
          break;
        }
      }
    } catch (LineTooLongException e) {
      LOG.error("入站消息超限（单行超过 {} 字节）——关闭传输", inputMaxSize, e);
    } catch (IOException e) {
      if (!isClosing) {
        LOG.warn("读取输入流失败", e);
      }
    } finally {
      isClosing = true;
      inboundSink.tryEmitComplete();
      closed.complete(null);
    }
  }

  // ---- 写侧（outboundSink → 对端） ----

  private void startOutboundProcessing() {
    Flux<McpSchema.JSONRPCMessage> flux =
        outboundSink.asFlux().publishOn(outboundScheduler).handle(this::writeOne);
    flux.doOnComplete(this::onOutboundClosed).doOnError(this::onOutboundError).subscribe();
  }

  private void writeOne(
      McpSchema.JSONRPCMessage message, SynchronousSink<McpSchema.JSONRPCMessage> sink) {
    if (message == null || isClosing) {
      return;
    }
    try {
      // stdio 帧协议：单行 JSON，行内换行转义（与官方传输逐帧一致）
      String line =
          jsonMapper
              .writeValueAsString(message)
              .replace("\r\n", "\\n")
              .replace("\n", "\\n")
              .replace("\r", "\\n");
      synchronized (out) {
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
      }
      sink.next(message);
    } catch (IOException e) {
      sink.error(new RuntimeException(e));
    }
  }

  private void onOutboundClosed() {
    isClosing = true;
    outboundSink.tryEmitComplete();
    closed.complete(null);
  }

  private void onOutboundError(Throwable error) {
    if (!isClosing) {
      LOG.error("出站处理异常", error);
      isClosing = true;
      outboundSink.tryEmitComplete();
      closed.complete(null);
    }
  }

  /**
   * 逐行读（镜像官方实现）：处理 {@code \n} / {@code \r\n} / 行末 {@code \r} 三种终止，单行超过 {@code maxSize} 抛 {@link
   * MaxSizeExceededException}；EOF 且无内容返回 null。
   */
  private static String readLine(BufferedReader reader, int maxSize) throws IOException {
    StringBuilder builder = new StringBuilder();
    int ch = reader.read();
    if (ch == -1) {
      return null;
    }
    while (ch != -1) {
      if (ch == '\n') {
        return builder.toString();
      }
      if (ch == '\r') {
        reader.mark(1);
        int next = reader.read();
        if (next != '\n' && next != -1) {
          reader.reset();
        }
        return builder.toString();
      }
      if (builder.length() >= maxSize) {
        throw new LineTooLongException("单行消息超过上限: " + maxSize);
      }
      builder.append((char) ch);
      ch = reader.read();
    }
    return builder.isEmpty() ? null : builder.toString();
  }

  /** 行超限（SDK 的 MaxSizeExceededException 是包私有不可引用；本类同语义的自有异常）。 */
  private static final class LineTooLongException extends IOException {
    LineTooLongException(String message) {
      super(message);
    }
  }
}
