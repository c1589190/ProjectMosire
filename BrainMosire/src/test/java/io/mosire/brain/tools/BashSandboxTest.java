package io.mosire.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>L3 沙箱</b>（{@link BashSandbox}）的两类用例：
 *
 * <ul>
 *   <li><b>确定性</b>（不需要 root）：不可用 ⇒ 响亮失败、拒绝装配（{@link BashSandbox#unavailable}）；
 *   <li><b>结构性</b>（需要 root/CAP_SYS_ADMIN，用 {@link org.junit.jupiter.api.Assumptions#assumeTrue}
 *       跳过）：隐藏面 ENOENT、逃逸口 EPERM、可写/只读面的真实区别、重叠守卫与缺失可写目录的响亮失败。
 * </ul>
 *
 * <p><b>为什么结构性用例必须"真跑一次宿主"</b>：这些断言的对象是<b>内核</b>给的行为（{@code ENOENT}/{@code EPERM}/{@code
 * EROFS}），任何"我们调了 mount 命令"式的断言都会被一段没生效的装配骗过——2026-09-13 的探针就吃过这种零判别力伪证据的亏 （{@code mount}
 * 报的是"挂载点不存在"却被读成"被拒"）。所以这里的每条断言都看<b>结果</b>：文件在不在、退出码是不是 0。
 *
 * <p>结构性用例一律<b>不写宿主</b>（不碰只读面）：只读性用 {@code test -w}（{@code access(2)} 在只读挂载上失败）证，不靠真去写一个文件——
 * 万一沙箱没生效，那条"证明"就变成在宿主 {@code /usr} 里造垃圾。
 */
class BashSandboxTest {

  // ── 确定性：不可用就是不可用，绝不放行 ──────────────────────────────────────────────────

  @Test
  void unavailableSandboxReportsTheReasonVerbatim(@TempDir Path dir) {
    BashSandbox sandbox = BashSandbox.unavailable("构造用：探针恒否");

    assertThat(sandbox.available()).isFalse();
    assertThat(sandbox.unavailableReason()).contains("构造用：探针恒否");
    // ★ 判别性：把"不可用"改成静默放行 ⇒ 这里会装配出一条命令而不是抛异常
    assertThatThrownBy(() -> sandbox.wrap(dir, List.of(dir), "true"))
        .isInstanceOf(BashSandbox.SandboxUnavailableException.class)
        .hasMessageContaining("构造用：探针恒否");
  }

  @Test
  void probeResultIsCachedAndStable() {
    BashSandbox sandbox = BashSandbox.of(BashSandbox.DEFAULT_READ_ONLY);
    // 连问三次必须同一个答案（懒探针缓存；每次都真起进程会让判定面随时间抖动）
    assertThat(sandbox.unavailableReason()).isEqualTo(sandbox.unavailableReason());
    assertThat(sandbox.available()).isEqualTo(sandbox.unavailableReason().isEmpty());
  }

  // ── 结构性：真沙箱（需要 root；不满足则跳过） ────────────────────────────────────────────

  @Test
  void hiddenHostPathsDoNotExistInsideTheSandbox(@TempDir Path workdir) throws Exception {
    BashSandbox sandbox = BashSandbox.system();
    assumeTrue(sandbox.available(), () -> "本机沙箱不可用: " + sandbox.unavailableReason().orElse(""));

    // 隐藏面条目：仓库根（含密钥目录）与 /root/.ssh 都不在绑定面里；且"读密钥"这一步的退出码必须是读不到的（ENOENT）
    Run run =
        run(
            sandbox,
            workdir,
            List.of(workdir),
            """
        if [ -e /root/ProjectMosire ]; then echo LEAK-REPO; else echo HIDDEN-REPO; fi
        if [ -e /root/ProjectMosire/.work/mosire/config.json ]; then echo LEAK-KEY; else echo HIDDEN-KEY; fi
        if [ -e /root/.ssh ]; then echo LEAK-SSH; else echo HIDDEN-SSH; fi
        cat /root/ProjectMosire/.work/mosire/config.json >/dev/null 2>&1
        echo "key-read-rc=$?"
        """);

    assertThat(run.exitCode()).isZero();
    assertThat(run.stdout()).contains("HIDDEN-REPO").contains("HIDDEN-KEY").contains("HIDDEN-SSH");
    assertThat(run.stdout()).doesNotContain("LEAK");
    // ★ 判别性："读不到"必须是真读不到（cat 报错），而不是"我们没让 cat 跑"
    assertThat(run.stdout()).contains("key-read-rc=1");
  }

  @Test
  void escapeHatchesAreClosedInsideTheSandbox(@TempDir Path workdir) throws Exception {
    BashSandbox sandbox = BashSandbox.system();
    assumeTrue(sandbox.available(), () -> "本机沙箱不可用: " + sandbox.unavailableReason().orElse(""));

    Run run =
        run(
            sandbox,
            workdir,
            List.of(workdir),
            """
        mkdir -p /tmp/mnt-point
        for t in mknod mount chroot; do command -v "$t" >/dev/null && echo "HAVE-$t" || echo "MISSING-$t"; done
        mknod /tmp/node-probe c 1 3 2>/dev/null; echo "mknod-rc=$?"
        mount -t tmpfs none /tmp/mnt-point 2>/dev/null; echo "mount-rc=$?"
        chroot /tmp /bin/true 2>/dev/null; echo "chroot-rc=$?"
        if [ -e /tmp/node-probe ]; then echo NODE-CREATED; else echo NODE-ABSENT; fi
        if [ -d /proc ]; then echo PROC-PRESENT; else echo PROC-ABSENT; fi
        """);

    assertThat(run.exitCode()).isZero();
    // 先证"工具在"：否则"非零退出"里混着"命令没找到"，那正是零判别力的伪证据（附录 A 的教训）
    assertThat(run.stdout()).contains("HAVE-mknod").contains("HAVE-mount").contains("HAVE-chroot");
    // ★ 判别性：清空 capability 边界集是承重的——2026-09-13 探针里不降权的对照组这三个口子全开（rc=0）；
    //   这里只钉"非零"，因为具体码是各工具自己的约定（mknod=1 / mount=32 / chroot=125），不是判定的对象
    assertThat(run.stdout()).as("逃逸口必须全部关闭（非零退出）").doesNotContain("-rc=0");
    assertThat(run.stdout()).contains("NODE-ABSENT");
    // 不 bind /proc 是有意的：宿主 /proc 会泄漏全机进程与 cmdline
    assertThat(run.stdout()).contains("PROC-ABSENT");
  }

  @Test
  void writableFaceIsWritableAndReadOnlyFaceIsNot(@TempDir Path workdir) throws Exception {
    BashSandbox sandbox = BashSandbox.system();
    assumeTrue(sandbox.available(), () -> "本机沙箱不可用: " + sandbox.unavailableReason().orElse(""));

    Run run =
        run(
            sandbox,
            workdir,
            List.of(workdir),
            """
        touch ./written.txt && echo "WROTE=$(cat ./written.txt >/dev/null; echo ok)"
        if [ -w /usr ]; then echo RO-FACE-WRITABLE; else echo RO-FACE-READONLY; fi
        """);

    assertThat(run.exitCode()).isZero();
    assertThat(run.stdout()).contains("WROTE=ok");
    // ★ 判别性：只读面真的只读（access(2) 在只读挂载上失败）；写它只会在沙箱里失败，宿主不受影响
    assertThat(run.stdout()).contains("RO-FACE-READONLY");
    assertThat(workdir.resolve("written.txt")).exists();
  }

  @Test
  void mountsStayInsideThePrivateNamespaceAndNeverLeakToTheHost(@TempDir Path workdir)
      throws Exception {
    BashSandbox sandbox = BashSandbox.system();
    assumeTrue(sandbox.available(), () -> "本机沙箱不可用: " + sandbox.unavailableReason().orElse(""));

    Run run = run(sandbox, workdir, List.of(workdir), "echo from-sandbox > ./kept.txt");
    assertThat(run.exitCode()).isZero();

    // ① 可写面真的接到了宿主目录（bind 生效：沙箱里写的文件，宿主看得见）
    assertThat(workdir.resolve("kept.txt")).exists();
    assertThat(Files.readString(workdir.resolve("kept.txt"))).contains("from-sandbox");
    // ② 挂载没有泄进宿主命名空间：包装命令结束后宿主看不见任何 mosire-bash-sbx-* 挂载
    // ★ 判别性（2026-09-13 实测过的那条缺陷）：argv 少一个 `unshare -m` ⇒ 这几条 bind 落在宿主命名空间，
    //    进程退出后仍在 /proc/self/mountinfo 里；随后的 cleanup 删根会穿透 rw bind 把宿主工作目录的内容删掉 ⇒
    //    上面 ① 的文件消失、② 的条目出现——两条断言同时转红。
    assertThat(hostMountTargetsUnderSandboxRoots()).as("宿主挂载表里不该有沙箱挂载").isEmpty();
  }

  /** 宿主挂载表里 target 落在 {@code /tmp/mosire-bash-sbx-*} 下的条目（泄漏的证据）。 */
  private static List<String> hostMountTargetsUnderSandboxRoots() throws IOException {
    Path mountinfo = Path.of("/proc/self/mountinfo");
    assumeTrue(Files.isReadable(mountinfo), "本机没有 /proc/self/mountinfo，无从判定挂载泄漏");
    return Files.readAllLines(mountinfo).stream()
        .map(line -> line.split(" "))
        .filter(fields -> fields.length >= 5)
        .map(fields -> fields[4].replace("\\040", " "))
        .filter(target -> target.contains("/mosire-bash-sbx-"))
        .toList();
  }

  @Test
  void sandboxRootOverlappingAWritableDirIsRefused(@TempDir Path workdir) {
    BashSandbox sandbox = BashSandbox.system();
    assumeTrue(sandbox.available(), () -> "本机沙箱不可用: " + sandbox.unavailableReason().orElse(""));

    // 沙箱根建在系统临时目录下 ⇒ 把 /tmp 整体绑进去等于把沙箱自己的根（以及并发调用的沙箱）暴露给它自己
    assertThatThrownBy(() -> sandbox.wrap(workdir, List.of(Path.of("/tmp")), "true"))
        .isInstanceOf(BashSandbox.SandboxUnavailableException.class)
        .hasMessageContaining("重叠");
  }

  @Test
  void missingWritableDirIsRefusedLoudly(@TempDir Path workdir) {
    BashSandbox sandbox = BashSandbox.system();
    assumeTrue(sandbox.available(), () -> "本机沙箱不可用: " + sandbox.unavailableReason().orElse(""));

    Path missing = workdir.resolve("not-created");
    // 判定面说可以、执行面看不见 ⇒ 宁可响亮失败（可写面是安全面，不许静默缩小）
    assertThatThrownBy(() -> sandbox.wrap(workdir, List.of(missing), "true"))
        .isInstanceOf(BashSandbox.SandboxUnavailableException.class)
        .hasMessageContaining("允许目录不可用");
  }

  // ── 辅助：装配 + 跑 + 清（清必须发生在包装进程结束之后） ──────────────────────────────────

  private record Run(int exitCode, String stdout, String stderr) {}

  /**
   * 跑一条被包装的命令并等它结束，然后才清理。
   *
   * <p>顺序不是风格问题：沙箱根里挂着 rw bind，在命名空间<b>还在</b>的时候删根会穿透 bind 删掉<b>宿主</b>目录内容（探针 v1 实测）。
   */
  private static Run run(BashSandbox sandbox, Path workdir, List<Path> writable, String command)
      throws IOException, InterruptedException {
    BashSandbox.Wrapped wrapped = sandbox.wrap(workdir, writable, command);
    try {
      Process process = new ProcessBuilder(wrapped.command()).redirectErrorStream(false).start();
      String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("沙箱命令必须在 60 秒内结束").isTrue();
      return new Run(process.exitValue(), stdout, stderr);
    } finally {
      sandbox.cleanup(wrapped);
    }
  }
}
