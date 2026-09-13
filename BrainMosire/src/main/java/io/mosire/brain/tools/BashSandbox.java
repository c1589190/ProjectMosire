package io.mosire.brain.tools;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>L3 OS 沙箱</b>（S5-D）：把一条命令包进 {@code unshare -m} + bind + {@code chroot} + 降权的执行体。
 *
 * <p><b>它承诺什么、不承诺什么</b>（口径与 {@code 设计-Bash工具与工作目录围栏.md} §2.1 的分级表一致）：
 *
 * <ul>
 *   <li><b>结构性</b>：没被 bind 的宿主路径在沙箱里<b>不存在</b>（不是"被拒"，是 ENOENT）——这是本层与 L1/L2 的本质区别：L1/L2
 *       只是参数面的契约与起点，任何"L1/L2 就是隔离"的措辞都是假的；
 *   <li><b>逃逸口关闭</b>：{@code setpriv --bounding-set=-all --no-new-privs} 之后 {@code mknod}/{@code
 *       mount}/{@code chroot} 再逃逸/ptrace 附加全部 EPERM（2026-09-13 实机探针，裸退出码见 {@code
 *       .work/s5d-probe/}）。清空 capability 边界集是<b>承重的</b>：不降权的对照组三个逃逸口全开；
 *   <li><b>做不到</b>：① <b>同 uid 的信号仍可达</b>——沙箱内 uid 恒为 0，{@code kill -9 <宿主 root 进程>} 实测 rc=0（本机
 *       uid=0 部署下这是已知缺口，要封它需要换 uid 或私有 PID 命名空间，两者都超出本段裁决范围）；② 沙箱内<b>没有 {@code /proc}</b>（不 bind
 *       它是有意的：宿主 {@code /proc} 会泄漏全机进程与 cmdline）；③ 未穷举逃生面（{@code io_uring}/设备节点/{@code /sys} 等未测）；④
 *       需要 root 或 CAP_SYS_ADMIN，非 root 部署<b>不可用</b>（本机 {@code
 *       apparmor_restrict_unprivileged_userns=1}，user namespace 路线实测不可行）。
 * </ul>
 *
 * <p><b>可用性必须响亮失败</b>：{@link #available()} 是懒探针（{@code unshare -m} 真跑一次，结果缓存）；调用方在"需要沙箱"时先问
 * 它，<b>不可用就报错，绝不静默退回无围栏执行</b>——静默退化会让"受限调用者被围栏"这句话变成谎言。
 *
 * <p><b>清理必须在命名空间退出之后</b>（本类 {@link #cleanup} 由调用方在进程结束后调）：沙箱根里挂着 rw bind， 在命名空间<b>内</b>删根会穿透 bind
 * 删掉<b>宿主源目录</b>的内容——2026-09-13 的探针 v1 实测把宿主 {@code in.txt} 删掉了。 见 {@code
 * .work/s5d-probe/probe.out}（143k 行 {@code rm: Read-only file system} 的另一半分是同一件事的镜像）。
 *
 * <p><b>不是"通用容器"</b>：只做"目录可见面 + 降权"两件事。没有网络隔离（沙箱内仍可连网）、没有资源限额（CPU/内存/磁盘）、 没有 seccomp
 * 过滤。要那些得换实现（bwrap/nsjail），不在本仓当前裁决内。
 */
public final class BashSandbox {

  private static final Logger LOG = LoggerFactory.getLogger(BashSandbox.class);

  /** 缺省只读面（{@code tools.bash.sandboxReadOnly} 可换）：够跑 bash 与常规工具，又都是"读了也无害"的系统目录。 */
  public static final List<Path> DEFAULT_READ_ONLY = defaultReadOnly();

  /**
   * 缺省只读面的取值处。
   *
   * <p>DMI_HARDCODED_ABSOLUTE_FILENAME 抑制：这些绝对路径<b>就是</b>本类的数据（沙箱要绑的是宿主系统目录），
   * 不存在"换个部署就不该硬编码"的余地——配置面的替代品是 {@code tools.bash.sandboxReadOnly}。抑制挂在<b>这里</b>而不是字段上：SpotBugs
   * 把这些常量算在 {@code <static initializer>} 名下，挂字段会被判 {@code US_USELESS_SUPPRESSION_ON_FIELD}。
   */
  @SuppressFBWarnings(
      value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification = "沙箱的绑定面按定义就是宿主绝对路径；要换用配置键 tools.bash.sandboxReadOnly")
  private static List<Path> defaultReadOnly() {
    return List.of(
        Path.of("/usr"),
        Path.of("/bin"),
        Path.of("/lib"),
        Path.of("/lib64"),
        Path.of("/sbin"),
        Path.of("/etc"),
        Path.of("/run"));
  }

  /** 最小 {@code /dev}：只 bind 这几个设备节点（{@code 2>/dev/null} 这类重定向是命令的日常写法）。 */
  private static final List<String> DEVICE_NODES = List.of("null", "zero", "urandom", "random");

  /** 探针自身的超时：探针卡住就是"不可用"，不许把工具执行挂在这里。 */
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

  /** 进程自己的挂载表（{@link #cleanup} 用它确认命名空间已退出）。 */
  private static final Path MOUNTINFO = Path.of("/proc/self/mountinfo");

  /**
   * 沙箱包装脚本（POSIX sh）。<b>数据一律走 argv，不走字符串拼接</b>：根/工作目录/命令/挂载项都是形参， 于是路径里的空格、引号、{@code $}
   * 都不会被二次解析（命令文本原样交给最内层 {@code bash -c}）。
   *
   * <p>{@code exec chroot} 之前先 {@code cd "$root"}：{@code chroot(2)} <b>不改 cwd</b>，留着 cwd 在外面的进程可以走
   * cwd 绕出新根（经典 chroot 逃逸），所以必须先把脚拔进来。
   *
   * <p><b>本脚本必须跑在 {@code unshare -m} 里</b>（见 {@link #wrap} 的 argv）：没有私有挂载命名空间，这几条 {@code mount}
   * 就落在<b>宿主</b>命名空间里——进程退出后挂载仍在，{@link #cleanup} 的删根还会<b>穿过</b>那些仍然活动的 rw bind
   * 删掉宿主源目录的内容（2026-09-13 实测：少了 {@code unshare} 的版本把宿主工作目录内容删了，并且 {@code /proc/self/mountinfo}
   * 里留下几十条 {@code mosire-bash-sbx-*} 条目）。
   */
  private static final String WRAPPER =
      """
      set -eu
      root="$1"; workdir="$2"; cmd="$3"; setpriv="$4"; shell="$5"; shift 5
      for item in "$@"; do
        case "$item" in
          ro:*) path="${item#ro:}"; mount --bind -o ro "$path" "$root$path" ;;
          rw:*) path="${item#rw:}"; mount --bind "$path" "$root$path" ;;
          *) echo "沙箱挂载项非法: $item" >&2; exit 125 ;;
        esac
      done
      cd "$root"
      exec chroot "$root" "$setpriv" --bounding-set=-all --no-new-privs "$shell" -c \
      'cd "$1" || { echo "沙箱内工作目录不可达: $1" >&2; exit 125; }; exec "$2" -c "$3"' \
      mosire-sandbox "$workdir" "$shell" "$cmd"
      """;

  /** 已装配好的一条沙箱命令：{@code command} 是宿主侧的 argv，{@code root} 供 {@link #cleanup} 删。 */
  public record Wrapped(List<String> command, Path root) {

    public Wrapped {
      command = List.copyOf(command);
      Objects.requireNonNull(root, "root");
    }
  }

  private final List<Path> readOnlyDirs;
  private final Supplier<Optional<String>> probe;
  private volatile Optional<String> probeResult;

  private BashSandbox(List<Path> readOnlyDirs, Supplier<Optional<String>> probe) {
    this.readOnlyDirs = List.copyOf(Objects.requireNonNull(readOnlyDirs, "readOnlyDirs"));
    this.probe = Objects.requireNonNull(probe, "probe");
  }

  /** 本机沙箱（真探针：{@code unshare -m} 实跑一次并缓存），只读面取 {@link #DEFAULT_READ_ONLY}。 */
  public static BashSandbox system() {
    return of(DEFAULT_READ_ONLY);
  }

  /** 同上，换只读面（{@code tools.bash.sandboxReadOnly}）。 */
  public static BashSandbox of(List<Path> readOnlyDirs) {
    return new BashSandbox(readOnlyDirs, BashSandbox::realProbe);
  }

  /**
   * <b>构造用</b>：恒定不可用（{@code reason} 进错误消息与日志）。
   *
   * <p>它存在的理由是"沙箱不可用 ⇒ 响亮失败"必须能被<b>确定性</b>地测到：让探针去制造一次真实的"不可用"（比如在非 root
   * 环境里跑）既不可移植也不稳定，而这条分支恰恰是安全语义所在（fail-closed，不静默退回）。注入一个恒否的探针测的是同一条代码路径。
   */
  public static BashSandbox unavailable(String reason) {
    return new BashSandbox(DEFAULT_READ_ONLY, () -> Optional.of(reason));
  }

  /**
   * 沙箱是否可用（懒探针，结果缓存）。
   *
   * @return 空 = 可用；非空 = 不可用的原因（面向日志与错误消息，不含秘密）
   */
  public Optional<String> unavailableReason() {
    Optional<String> cached = probeResult;
    if (cached == null) {
      cached = probe.get();
      probeResult = cached;
      if (cached.isEmpty()) {
        LOG.info("bash 沙箱可用: 只读面={} 配置面可写目录随调用注入", readOnlyDirs);
      } else {
        LOG.warn("bash 沙箱不可用: {}", cached.get());
      }
    }
    return cached;
  }

  /** 见 {@link #unavailableReason()}。 */
  public boolean available() {
    return unavailableReason().isEmpty();
  }

  /**
   * 生成一条被沙箱包装的命令。
   *
   * <p><b>宿主侧先建整棵沙箱树</b>（挂载点必须存在，否则 {@code mount} 报"mount point does not exist"——那<b>不是</b>围栏证据， 附录
   * A 的自我更正记的就是这种零判别力的伪证据）；脚本只负责挂载与降权。
   *
   * <p><b>可写目录必须真实存在</b>：声明了却绑不进去，会让"判定面说可以、执行面看不见"分叉——宁可响亮失败。 <b>只读目录不存在则跳过</b>（发行版差异，如没有 {@code
   * /lib64}），它是"可见的系统面"，少一个不削弱目录围栏。
   *
   * @param workingDirectory 命令的工作目录（宿主路径；它必须落在 {@code writableDirs} 或只读面内，否则最内层 {@code cd} 会响亮失败）
   * @param writableDirs 可写面（调用者的 fs 可达面；顺序即挂载顺序）
   * @param command 命令文本（原样交给最内层 {@code bash -c}，本类不解释它）
   * @throws SandboxUnavailableException 沙箱不可用、根/绑定重叠、可写目录缺失或建树失败
   */
  @SuppressFBWarnings(
      value = {"DMI_HARDCODED_ABSOLUTE_FILENAME", "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION"},
      justification =
          "本方法按定义操作宿主绝对路径（/dev 设备节点、沙箱根）；且『装不起来就响亮失败』是它的契约"
              + "（SandboxUnavailableException 是 RuntimeException：调用方在工具边界统一转成错误码，"
              + "不引入受检异常——见 BashToolConfig.Sandbox 的 fail-closed 说明）")
  public Wrapped wrap(Path workingDirectory, List<Path> writableDirs, String command) {
    Objects.requireNonNull(workingDirectory, "workingDirectory");
    Objects.requireNonNull(command, "command");
    Optional<String> reason = unavailableReason();
    if (reason.isPresent()) {
      throw new SandboxUnavailableException(reason.get());
    }
    Path unshare = requireExecutable("unshare");
    Path setpriv = requireExecutable("setpriv");
    Path shell = requireExecutable("bash");
    Path root;
    try {
      root = Files.createTempDirectory("mosire-bash-sbx-");
    } catch (IOException e) {
      throw new SandboxUnavailableException("建沙箱根失败: " + e.getMessage());
    }
    try {
      List<String> mounts = new ArrayList<>();
      for (Path dir : readOnlyDirs) {
        if (!Files.isDirectory(dir)) {
          LOG.warn("沙箱只读面条目不存在，跳过: {}", dir);
          continue;
        }
        createMountPoint(root, dir);
        mounts.add("ro:" + dir);
      }
      for (Path dir : writableDirs) {
        Path normalized = dir.toAbsolutePath().normalize();
        if (normalized.startsWith(root) || root.startsWith(normalized)) {
          // 根在宿主临时目录下：把"含根的目录"整体绑进沙箱 = 把沙箱自己的根暴露给自己（并发调用之间还会互相看见）
          throw new SandboxUnavailableException(
              "允许目录与沙箱根重叠（" + normalized + " vs " + root + "）：本工具不把含沙箱根的目录绑进沙箱");
        }
        if (!Files.isDirectory(normalized)) {
          throw new SandboxUnavailableException("允许目录不可用（不存在或不是目录）: " + normalized);
        }
        createMountPoint(root, normalized);
        mounts.add("rw:" + normalized);
      }
      Files.createDirectories(root.resolve("tmp"));
      Files.createDirectories(root.resolve("dev"));
      for (String device : DEVICE_NODES) {
        Path hostDevice = Path.of("/dev", device);
        if (!Files.exists(hostDevice)) {
          continue;
        }
        Files.createFile(root.resolve("dev").resolve(device));
        mounts.add("ro:" + hostDevice);
      }
      List<String> argv = new ArrayList<>();
      // 私有挂载命名空间：unshare(1) 的缺省传播是 private，挂载不外泄给宿主（--propagation 的缺省值就是 private）
      argv.add(unshare.toString());
      argv.add("-m");
      argv.add("--");
      argv.add(shell.toString());
      argv.add("-c");
      argv.add(WRAPPER);
      argv.add("mosire-sandbox");
      argv.add(root.toString());
      argv.add(workingDirectory.toAbsolutePath().normalize().toString());
      argv.add(command);
      argv.add(setpriv.toString());
      argv.add(shell.toString());
      argv.addAll(mounts);
      LOG.info(
          "bash 沙箱已装配: root={} 工作目录={} 只读面={} 可写面={}",
          root,
          workingDirectory,
          readOnlyDirs,
          writableDirs);
      return new Wrapped(argv, root);
    } catch (IOException e) {
      deleteRecursively(root);
      throw new SandboxUnavailableException("建沙箱目录树失败: " + e.getMessage());
    } catch (RuntimeException e) {
      deleteRecursively(root);
      throw e;
    }
  }

  /**
   * 删除沙箱根（<b>必须在包装进程结束后调用</b>——见类 javadoc 的"清理必须在命名空间退出之后"）。
   *
   * <p><b>先查根下有没有活动挂载</b>（读 {@code /proc/self/mountinfo}）：有 ⇒ 命名空间没退干净，此刻删根会<b>穿过</b> bind
   * 删掉<b>宿主源目录</b>的内容（v1 探针与"少了 unshare 的实现"两次实测都是这个形态）。这时<b>不删</b>、记 ERROR 留证—— 残留只是宿主 {@code
   * /tmp} 下的一棵空目录树，而删错方向是不可逆的。
   *
   * <p>查不到 {@code /proc}（极端环境）时按"无挂载"处理并记 WARN：正常路径下挂载都在私有命名空间里，进程一退就没了，这条兜底是给
   * "装配被人改坏"这种异常情况准备的，不是日常路径。
   */
  public void cleanup(Wrapped wrapped) {
    Objects.requireNonNull(wrapped, "wrapped");
    Path root = wrapped.root();
    List<String> active = mountsUnder(root);
    if (active != null && !active.isEmpty()) {
      LOG.error("沙箱根下仍有活动挂载（命名空间未退出？）——拒绝删根以免穿透 bind 删掉宿主内容: {} 挂载点={}", root, active);
      return;
    }
    deleteRecursively(root);
  }

  /** 根下（含根本身）的活动挂载点；{@code null} = 查不到（无 {@code /proc}），空表 = 干净。 */
  private static List<String> mountsUnder(Path root) {
    Path mountinfo = MOUNTINFO;
    if (!Files.isReadable(mountinfo)) {
      LOG.warn("读不到 {}，无法确认沙箱根下是否还有活动挂载", mountinfo);
      return null;
    }
    String prefix = root.toString();
    List<String> found = new ArrayList<>();
    try {
      for (String line : Files.readAllLines(mountinfo)) {
        String[] fields = line.split(" ");
        if (fields.length < 5) {
          continue;
        }
        String target = fields[4].replace("\\040", " "); // mountinfo 用 \040 转义空格
        if (target.equals(prefix) || target.startsWith(prefix + "/")) {
          found.add(target);
        }
      }
    } catch (IOException e) {
      LOG.warn("读 {} 失败，无法确认沙箱根下是否还有活动挂载: {}", mountinfo, e.getMessage());
      return null;
    }
    return found;
  }

  /** 挂了挂载点就必须先有挂载点：宿主侧把 {@code $root<绝对路径>} 整条目录链建出来（脚本里再 mkdir 会撞只读面）。 */
  private static void createMountPoint(Path root, Path absoluteDir) throws IOException {
    Files.createDirectories(
        root.resolve(absoluteDir.toAbsolutePath().normalize().toString().substring(1)));
  }

  private static void deleteRecursively(Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  LOG.warn("沙箱残留未能删净: {}", path);
                }
              });
    } catch (IOException e) {
      LOG.warn("沙箱根清理失败（残留空目录树，无安全面影响）: {}", root);
    }
  }

  /** 按 PATH 找一个可执行文件（不依赖外部 {@code which}）。 */
  private static Path requireExecutable(String name) {
    Path found = findExecutable(name);
    if (found == null) {
      throw new SandboxUnavailableException("找不到可执行文件: " + name);
    }
    return found;
  }

  private static Path findExecutable(String name) {
    String path = System.getenv("PATH");
    if (path == null) {
      return null;
    }
    for (String element : path.split(":")) {
      if (element.isEmpty()) {
        continue;
      }
      Path candidate = Path.of(element, name);
      if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
        return candidate.toAbsolutePath().normalize();
      }
    }
    return null;
  }

  /** 真探针：工具齐不齐 + {@code unshare -m} 能不能建命名空间（探针进程自行退出，无宿主副作用）。 */
  private static Optional<String> realProbe() {
    if (findExecutable("unshare") == null) {
      return Optional.of("找不到 unshare（util-linux）");
    }
    if (findExecutable("chroot") == null) {
      return Optional.of("找不到 chroot（coreutils）");
    }
    if (findExecutable("setpriv") == null) {
      return Optional.of("找不到 setpriv（util-linux）");
    }
    if (findExecutable("bash") == null) {
      return Optional.of("找不到 bash");
    }
    Path trueBinary = findExecutable("true");
    if (trueBinary == null) {
      return Optional.of("找不到 true");
    }
    ProcessBuilder builder =
        new ProcessBuilder(findExecutable("unshare").toString(), "-m", "--", trueBinary.toString());
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    try {
      Process process = builder.start();
      if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return Optional.of("unshare -m 探针超时（" + PROBE_TIMEOUT.toSeconds() + " 秒）");
      }
      int rc = process.exitValue();
      if (rc != 0) {
        return Optional.of("unshare -m 探针失败（rc=" + rc + "，需要 root 或 CAP_SYS_ADMIN）");
      }
      return Optional.empty();
    } catch (IOException e) {
      return Optional.of("unshare 探针无法启动: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.of("unshare 探针被中断");
    }
  }

  /** 沙箱装不起来时的响亮失败（由工具层转成 {@code SANDBOX_UNAVAILABLE}，<b>绝不</b>静默退回无围栏执行）。 */
  public static final class SandboxUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SandboxUnavailableException(String message) {
      super(message);
    }
  }
}
