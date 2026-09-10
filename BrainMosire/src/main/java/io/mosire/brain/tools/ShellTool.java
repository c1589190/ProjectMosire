package io.mosire.brain.tools;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bash 工具（二期 P2-5）：在受控工作目录下跑一条 shell 命令，把 {@code exitCode}/{@code stdout}/{@code stderr}
 * 作为工具结果交回模型。
 *
 * <p><strong>执行模型（二期计划 §九.4 的落定）</strong>：工具级执行 = {@link ProcessBuilder}（{@code bash -c}），
 * <b>不</b>接入 {@link io.mosire.agentlib.proc.SubprocessManager}——那是子 Agent 进程生命周期的归属（心跳、进程树治理）。 本工具
 * <b>不发心跳、不做进程树清理</b>。已知取舍（不是遗漏）：命令自行派生的后代进程（如 {@code sleep 30 &}）在超时终止后
 * <b>可能继续存活</b>，本工具不回收进程树；要"整棵树"的治理就得走子 Agent 级执行。
 *
 * <p><strong>红线 5 的边界</strong>（{@code AGENTS.md}：任何工具不能关进程）：Bash <b>不提供 kill 语义</b>——参数面没有
 * kill/信号/PID 入口，也不存在"作用于任意进程"的接口（{@link #jsonSchema()} 由用例 pin 住）。超时或被中断时，本工具终止的只有
 * <b>自己创建并持有的直接子进程</b>（{@link Process#destroyForcibly()}，仅该进程，不涉进程组、不涉进程树）；这是工具自身执行生命周期的
 * 收尾——不终止则工具永久阻塞，那才是缺陷。
 *
 * <p><strong>输出裁剪三档</strong>（细节见 {@link ShellOutputTruncator}）：
 *
 * <ol>
 *   <li>档 1 {@code maxOutputBytes}（缺省 1 MiB）= 采集期<b>字节</b>硬顶，读流时就压住，超限字节不入内存；
 *   <li>档 2 进上下文的 head-tail 截断<b>复用</b> {@link
 *       io.mosire.agentlib.tool.ToolResultTruncator}（口径：UTF-16 字符）；
 *   <li>档 3 {@code error-only} 模式：exit≠0 时只回 stderr 摘要（成功路径照常回显 stdout）。模式只改"什么进上下文"，
 *       不改"什么被保存"——落库口径恒含两路输出。
 * </ol>
 *
 * <p><strong>退出码语义</strong>：非零退出是<b>成功</b>的 {@link ToolResult}（{@code code == null}）——命令确实跑完了，
 * 模型需要看到输出才能推理；把非零退出做成错误码会让管线/审计把正常业务结果当成工具故障。{@code code != null} 表示<b>工具层失败</b>：
 *
 * <ul>
 *   <li>{@link #INVALID_ARGUMENTS}：缺/空白 {@code command}；{@code timeout} 非正或非数字；{@code mode} 取值非法；
 *       {@code env} 不是对象或值不是标量；{@code cwd} 路径字面非法；
 *   <li>{@link #LAUNCH_FAILED}：进程启动失败（{@code cwd} 不存在/不可进入、可执行文件不可用等 IO 失败）；
 *   <li>{@link #TIMEOUT}：超过 {@code timeout} 秒仍未结束（已终止直接子进程，消息里带已捕获的输出）；
 *   <li>{@link #INTERRUPTED}：执行线程被中断（已终止直接子进程，中断标记归还调用方）。
 * </ul>
 *
 * <p><strong>工作目录</strong>：构造注入基准目录（缺省 = JVM 当前目录），调用参数 {@code cwd} 可逐次覆盖（相对路径按基准目录解析）。
 * <b>本阶段不做目录围栏/沙箱</b>：{@code cwd} 可指向任意可访问路径——控制手段是权限门禁（{@link #spec()} 声明 sensitive +
 * destructive，默认拒绝、需显式放行，本类自身不做权限判断，那是 {@code ToolExecutionGuard} 的职责）；目录围栏归三期的 {@code
 * Workspace}，此处不假装已有。
 *
 * <p><strong>环境变量</strong>：{@code env} 参数<b>叠加在继承环境之上</b>（同名覆盖，与 {@code
 * ProcessBuilder.environment()} 从父进程继承一致的语义），不是替换整个环境。
 *
 * <p><strong>大输出落库</strong>：输出被裁（档 1 截断或档 2 超预算）时，把<b>采集到的完整文本</b>交给构造注入的 {@link
 * OutputSink}，拿到的引用放进 {@link ToolResult#assetDocIds()}（既有字段）。缺省实现 {@link OutputSink#none()} 不落库； 真实
 * EventStore 接线属 T15。落库是尽力而为：sink 抛异常只降级为"无引用"，不改变命令结果。
 *
 * <p>线程安全：实例不可变（字段全 final），{@link #execute} 可并发调用（每次调用各起各的直接子进程与两条读取线程）。仅 POSIX （固定 {@code bash
 * -c}）。
 */
public final class ShellTool implements AgentTool {

  private static final Logger LOG = LoggerFactory.getLogger(ShellTool.class);

  /** 工具名（模型侧与权限白名单用的稳定标识）。 */
  public static final String NAME = "bash";

  /** 缺省超时（秒）：够长到能跑完常规命令，又短到不会让一次误判把回合挂死。 */
  public static final long DEFAULT_TIMEOUT_SECONDS = 60;

  /** 参数非法（缺必填、取值非法）——沿用既有约定码。 */
  public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";

  /** 启动/IO 失败（如 {@code cwd} 不存在）——沿用既有约定码。 */
  public static final String LAUNCH_FAILED = "LAUNCH_FAILED";

  /** 超时未结束（已终止直接子进程）。 */
  public static final String TIMEOUT = "SHELL_TIMEOUT";

  /** 执行线程被中断（已终止直接子进程，中断标记归还调用方）。 */
  public static final String INTERRUPTED = "SHELL_INTERRUPTED";

  /** 固定用 bash：工具名与语义一致（模型写 bash 语法应可用）；POSIX 之外不支持。 */
  private static final String SHELL_EXECUTABLE = "bash";

  /** 读取线程的收尾宽限：直接子进程退出后，仍持有管道写端的后代最多让它多等这么久（不设宽限＝可能永久阻塞）。 */
  private static final Duration DRAIN_GRACE = Duration.ofSeconds(2);

  /** 强杀后的收殓宽限（SIGKILL 不可捕获，正常即返回）。 */
  private static final Duration TERMINATE_GRACE = Duration.ofSeconds(5);

  /** 工具声明的入参 schema（不可变，构造期算一次）。 */
  private static final Map<String, Object> SCHEMA = buildSchema();

  private final Path baseWorkingDirectory;
  private final OutputSink sink;
  private final ShellOutputTruncator.Mode defaultMode;
  private final int maxOutputBytes;
  private final int maxInjectedChars;

  /**
   * 大输出落库 SPI：把被裁掉的完整文本交给宿主（如事件库），返回可引用的 docId。
   *
   * <p>约定：返回非 null 的 {@link Optional}；{@link Optional#empty()} = 未落库（本阶段就是缺省行为）。实现抛异常视为落库失败，
   * 调用方降级为"无引用"，不影响命令结果。为什么用注入而非 {@link ToolContext#config()}：落库是宿主能力（离线单测不该被事件库绑架）， 而 {@code
   * config} 只承载"工具自身配置"；真实接线属 T15。
   */
  public interface OutputSink {

    /**
     * 暂存一份完整输出。
     *
     * @param content 采集到的完整文本（已含 exitCode 与两路输出；档 1 丢弃的字节不在其中）
     * @return 引用标识；空 = 未落库
     */
    Optional<String> store(String content);

    /** 缺省实现：不落库（保证离线单测无需事件库）。 */
    static OutputSink none() {
      return content -> Optional.empty();
    }
  }

  /** 缺省组装：JVM 当前目录为基准、不落库、normal 模式、1 MiB 采集顶、通用截断预算。 */
  public ShellTool() {
    this(
        Path.of("").toAbsolutePath(),
        OutputSink.none(),
        ShellOutputTruncator.Mode.NORMAL,
        ShellOutputTruncator.DEFAULT_MAX_OUTPUT_BYTES,
        ShellOutputTruncator.DEFAULT_MAX_INJECTED_CHARS);
  }

  /** 注入基准工作目录（其余取缺省）——避免行为隐式依赖进程 CWD。 */
  public ShellTool(Path baseWorkingDirectory) {
    this(
        baseWorkingDirectory,
        OutputSink.none(),
        ShellOutputTruncator.Mode.NORMAL,
        ShellOutputTruncator.DEFAULT_MAX_OUTPUT_BYTES,
        ShellOutputTruncator.DEFAULT_MAX_INJECTED_CHARS);
  }

  public ShellTool(Path baseWorkingDirectory, OutputSink sink) {
    this(
        baseWorkingDirectory,
        sink,
        ShellOutputTruncator.Mode.NORMAL,
        ShellOutputTruncator.DEFAULT_MAX_OUTPUT_BYTES,
        ShellOutputTruncator.DEFAULT_MAX_INJECTED_CHARS);
  }

  /** 注入缺省输出模式（调用参数 {@code mode} 仍可逐次覆盖）。 */
  public ShellTool(
      Path baseWorkingDirectory, OutputSink sink, ShellOutputTruncator.Mode defaultMode) {
    this(
        baseWorkingDirectory,
        sink,
        defaultMode,
        ShellOutputTruncator.DEFAULT_MAX_OUTPUT_BYTES,
        ShellOutputTruncator.DEFAULT_MAX_INJECTED_CHARS);
  }

  /**
   * 全量组装（测试与小预算场景用）。
   *
   * @param baseWorkingDirectory 基准工作目录（非 null）
   * @param sink 落库 SPI（非 null；{@link OutputSink#none()} = 不落库）
   * @param defaultMode 缺省输出模式
   * @param maxOutputBytes 档 1 采集字节顶（正数）
   * @param maxInjectedChars 档 2 字符预算（正数）
   */
  public ShellTool(
      Path baseWorkingDirectory,
      OutputSink sink,
      ShellOutputTruncator.Mode defaultMode,
      int maxOutputBytes,
      int maxInjectedChars) {
    this.baseWorkingDirectory =
        Objects.requireNonNull(baseWorkingDirectory, "baseWorkingDirectory");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.defaultMode = Objects.requireNonNull(defaultMode, "defaultMode");
    if (maxOutputBytes <= 0) {
      throw new IllegalArgumentException("maxOutputBytes 必须为正数: " + maxOutputBytes);
    }
    if (maxInjectedChars <= 0) {
      throw new IllegalArgumentException("maxInjectedChars 必须为正数: " + maxInjectedChars);
    }
    this.maxOutputBytes = maxOutputBytes;
    this.maxInjectedChars = maxInjectedChars;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "在受控工作目录下执行一条 shell 命令（bash -c），返回退出码与 stdout/stderr；"
        + "大输出会被截断，非零退出不代表调用失败。破坏性操作：需显式放行。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return SCHEMA;
  }

  @Override
  public ToolSpec spec() {
    // 敏感 + 破坏：默认拒绝，需显式放行（工具自身不做权限判断，见类 Javadoc）
    return ToolSpec.level(AccessToken.DEFAULT, true, true);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    Objects.requireNonNull(context, "context");
    String command = strArg(context, "command");
    if (command == null) {
      return ToolResult.error(INVALID_ARGUMENTS, "command 为必填参数（非空命令字符串）");
    }

    Path workingDirectory;
    Map<String, String> env;
    long timeoutSeconds;
    ShellOutputTruncator.Mode mode;
    try {
      workingDirectory = resolveWorkingDirectory(strArg(context, "cwd"));
      env = envArg(context, "env");
      timeoutSeconds = longArg(context, "timeout", DEFAULT_TIMEOUT_SECONDS);
      if (timeoutSeconds <= 0) {
        throw new IllegalArgumentException("timeout 必须为正数（秒）: " + timeoutSeconds);
      }
      String modeArg = strArg(context, "mode");
      mode = modeArg == null ? defaultMode : ShellOutputTruncator.Mode.parse(modeArg);
    } catch (IllegalArgumentException e) {
      return ToolResult.error(INVALID_ARGUMENTS, e.getMessage());
    }

    Process process;
    try {
      ProcessBuilder builder = new ProcessBuilder(SHELL_EXECUTABLE, "-c", command);
      builder.directory(workingDirectory.toFile());
      builder.environment().putAll(env);
      builder.redirectErrorStream(false);
      process = builder.start();
    } catch (IOException e) {
      LOG.warn("bash 启动失败: {}", command, e);
      return ToolResult.error(LAUNCH_FAILED, "启动命令失败（工作目录或可执行文件不可用）: " + e.getMessage());
    }

    ReaderTask stdoutTask = new ReaderTask(process.getInputStream(), maxOutputBytes);
    ReaderTask stderrTask = new ReaderTask(process.getErrorStream(), maxOutputBytes);
    Thread stdoutReader = startReader(stdoutTask, "mosire-bash-stdout");
    Thread stderrReader = startReader(stderrTask, "mosire-bash-stderr");

    boolean exited = false;
    boolean interrupted = false;
    try {
      exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      interrupted = true;
    }
    if (interrupted || !exited) {
      // 红线 5 的边界：只终止本工具创建的直接子进程；后代进程可能存活（类 Javadoc 的取舍）
      terminate(process);
    }
    joinQuietly(stdoutReader);
    joinQuietly(stderrReader);
    ShellOutputTruncator.Captured stdout = stdoutTask.captured();
    ShellOutputTruncator.Captured stderr = stderrTask.captured();
    // 宽限已过仍未收尾（后代进程占着管道继续产出）：放弃读取，避免留下一个空转的读取线程
    stdoutTask.abandonIfAlive(stdoutReader);
    stderrTask.abandonIfAlive(stderrReader);

    if (interrupted) {
      Thread.currentThread().interrupt(); // 中断标记归还调用方，不外吞
      return ToolResult.error(
          INTERRUPTED,
          "命令执行被中断，已终止本工具创建的直接子进程（后代进程可能仍存活）: " + command + "\n" + partialOutput(stdout, stderr));
    }
    if (!exited) {
      return ToolResult.error(
          TIMEOUT,
          "命令超时（"
              + timeoutSeconds
              + " 秒），已终止本工具创建的直接子进程（后代进程可能仍存活）: "
              + command
              + "\n"
              + partialOutput(stdout, stderr));
    }
    return completed(stdout, stderr, process.exitValue(), mode);
  }

  /** 命令跑完（exit 0 或非零）后的正常结果：非零退出同样是成功的 {@link ToolResult}，只是把 exitCode 写进消息。 */
  private ToolResult completed(
      ShellOutputTruncator.Captured stdout,
      ShellOutputTruncator.Captured stderr,
      int exitCode,
      ShellOutputTruncator.Mode mode) {
    ShellOutputTruncator.Rendered rendered =
        ShellOutputTruncator.render(stdout, stderr, exitCode, mode, maxInjectedChars);
    String message = rendered.injected();
    List<String> assetDocIds = List.of();
    boolean withheld = rendered.cutByBudget() || stdout.truncated() || stderr.truncated();
    if (withheld) {
      Optional<String> reference = persist(rendered.full());
      if (reference.isPresent()) {
        assetDocIds = List.of(reference.get());
        // 引用行追加在截断之后：它只有几十字符，而"模型知道去哪儿拿全量"比死守预算重要（同 ToolResultTruncator
        // 对截断提示的处理——最小必要信息允许单独超限）
        message = message + "\n[完整输出已暂存] 资产引用: " + reference.get();
      }
    }
    return new ToolResult(null, message, assetDocIds);
  }

  /** 落库尽力而为：sink 抛异常只记为无引用（命令已跑完，不该因存储故障变成工具层失败）。 */
  private Optional<String> persist(String content) {
    try {
      return sink.store(content);
    } catch (RuntimeException e) {
      LOG.warn("输出落库失败（命令结果不受影响）", e);
      return Optional.empty();
    }
  }

  /** 超时/中断路径的输出（含档 1 截断自报）：同样过档 2 预算，失败路径不是绕过输出上限的后门。 */
  private String partialOutput(
      ShellOutputTruncator.Captured stdout, ShellOutputTruncator.Captured stderr) {
    return ShellOutputTruncator.renderAborted(stdout, stderr, maxInjectedChars);
  }

  /** 终止本工具持有的直接子进程（不涉进程组/树）；宽限后不再等待，避免收尾自身变成新的阻塞点。 */
  private static void terminate(Process process) {
    process.destroyForcibly();
    try {
      process.waitFor(TERMINATE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 有界等待读取线程：直接子进程已退出而某个后代仍持有管道时，宁可少读也不永久阻塞。 */
  private static void joinQuietly(Thread reader) {
    try {
      reader.join(DRAIN_GRACE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * 启动一条读取线程。<b>平台守护线程而非虚拟线程</b>：被后代占住的管道读取会阻塞很久（本工具不回收进程树），而虚拟线程在阻塞式管道读上会 pin
   * 住载体线程，长期占用会拖垮整个调度器；平台守护线程最坏只是多一条闲置线程，且不拦 JVM 退出。
   */
  private static Thread startReader(ReaderTask task, String name) {
    return Thread.ofPlatform().daemon().name(name).start(task);
  }

  /** 相对 {@code cwd} 按基准目录解析（基准目录就是"进程 CWD 的注入替身"）。 */
  private Path resolveWorkingDirectory(String rawCwd) {
    if (rawCwd == null) {
      return baseWorkingDirectory;
    }
    Path path = Path.of(rawCwd); // 字面非法 → InvalidPathException（IAE 子类）→ INVALID_ARGUMENTS
    return path.isAbsolute() ? path : baseWorkingDirectory.resolve(path);
  }

  private static Map<String, Object> buildSchema() {
    String modes =
        Arrays.stream(ShellOutputTruncator.Mode.values())
            .map(ShellOutputTruncator.Mode::wireName)
            .collect(Collectors.joining(" / "));
    return Map.of(
        "type", "object",
        "properties",
            Map.of(
                "command", Map.of("type", "string", "description", "要执行的 shell 命令（bash -c 语义）"),
                "cwd", Map.of("type", "string", "description", "工作目录；相对路径按基准目录解析，缺省 = 基准目录"),
                "env",
                    Map.of(
                        "type",
                        "object",
                        "additionalProperties",
                        Map.of("type", "string"),
                        "description",
                        "追加/覆盖的环境变量（叠加在继承环境之上）"),
                "timeout",
                    Map.of(
                        "type",
                        "integer",
                        "description",
                        "超时秒数（正整数，缺省 " + DEFAULT_TIMEOUT_SECONDS + "），超时即终止命令"),
                "mode",
                    Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("normal", "error-only"),
                        "description",
                        "输出模式（" + modes + "）；error-only 仅当退出码非 0 时只回 stderr")),
        "required", List.of("command"));
  }

  /** 采集线程：读一条流（采集不抛异常，见 {@link ShellOutputTruncator#drain}——流被回收的 JDK 关掉是正常路径）。 */
  private static final class ReaderTask implements Runnable {

    private static final ShellOutputTruncator.Captured EMPTY =
        new ShellOutputTruncator.Captured("", 0, 0, false);

    private final InputStream stream;
    private final int maxBytes;
    private final AtomicBoolean abandoned = new AtomicBoolean();
    private volatile ShellOutputTruncator.Captured captured = EMPTY;

    private ReaderTask(InputStream stream, int maxBytes) {
      this.stream = stream;
      this.maxBytes = maxBytes;
    }

    @Override
    public void run() {
      captured = ShellOutputTruncator.drain(stream, maxBytes, abandoned::get);
    }

    /** 放弃读取（仅当直接子进程已终止、宽限已过）：让读取线程在下一块数据上收手。 */
    private void abandonIfAlive(Thread reader) {
      if (reader.isAlive()) {
        abandoned.set(true);
      }
    }

    /** 已采集内容快照。读取线程未在宽限内结束（后代进程仍持有管道）时仍是空值——本工具不为此阻塞，失败消息会明说"未捕获到输出"。 */
    private ShellOutputTruncator.Captured captured() {
      return captured;
    }
  }

  private static String strArg(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof String s) {
      return s.isBlank() ? null : s;
    }
    return String.valueOf(value);
  }

  /** 整数参数：缺失 → 缺省值；存在但非数字 → 抛 IAE（调用方落地 {@code INVALID_ARGUMENTS}，绝不静默用缺省）。 */
  private static long longArg(ToolContext context, String name, long defaultValue) {
    Object value = context.arguments().get(name);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("参数 " + name + " 必须是整数（秒）: " + value);
    }
  }

  /** 环境变量参数：必须是对象且值为标量（嵌套结构无法变成进程环境，属参数非法而非静默忽略）。 */
  private static Map<String, String> envArg(ToolContext context, String name) {
    Object value = context.arguments().get(name);
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 " + name + " 必须是对象（键值均为标量）: " + value);
    }
    Map<String, String> env = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object item = entry.getValue();
      if (item == null || item instanceof Map || item instanceof Collection) {
        throw new IllegalArgumentException("参数 " + name + " 的值必须是标量: " + entry.getKey());
      }
      env.put(String.valueOf(entry.getKey()), String.valueOf(item));
    }
    return Map.copyOf(env);
  }
}
