package io.mosire.agentlib.proc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SubprocessManager/ManagedProcess 离线行为测试（全部走 sh，仅 POSIX）。 每个用例带超时保护（onExit/get 带
 * deadline），避免挂死拖垮门禁。
 */
class SubprocessManagerTest {

  @BeforeAll
  static void requirePosix() {
    assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "仅 POSIX（用 sh）");
  }

  /** a) spawn 基本路径：读到 stdout 行、正常退出（exit 0）。 */
  @Test
  void spawnReadsOutputAndExitsCleanly() throws Exception {
    try (SubprocessManager manager = new SubprocessManager();
        ManagedProcess process = manager.spawn(SpawnSpec.of("sh", "-c", "echo hello"))) {
      process.onExit().get(5, TimeUnit.SECONDS);
      assertThat(process.isAlive()).isFalse();
      assertThat(awaitTailContaining(process, "hello", Duration.ofSeconds(5))).contains("hello");
      assertThat(process.overflowed()).isFalse();
    }
  }

  /** b) 工作目录与环境变量生效；maxOutputBytes=0 = 不限。 */
  @Test
  void spawnHonorsWorkingDirAndEnv(@TempDir Path tempDir) throws Exception {
    SpawnSpec spec =
        new SpawnSpec(
            "sh",
            List.of("-c", "pwd; echo $MOSIRE_TEST_VAR"),
            Map.of("MOSIRE_TEST_VAR", "mosire-value"),
            tempDir,
            0);
    try (SubprocessManager manager = new SubprocessManager();
        ManagedProcess process = manager.spawn(spec)) {
      process.onExit().get(5, TimeUnit.SECONDS);
      List<String> tail = awaitTailContaining(process, "mosire-value", Duration.ofSeconds(5));
      assertThat(tail)
          .anySatisfy(line -> assertThat(line).startsWith(tempDir.toAbsolutePath().toString()));
      assertThat(tail).contains("mosire-value");
    }
  }

  /** c) 忽略 TERM 的进程：宽限 300ms 后 SIGKILL 树清生效，总耗时 < 5s。 */
  @Test
  void stopGracefullyKillsTermIgnoringProcessWithinDeadline() throws Exception {
    try (SubprocessManager manager = new SubprocessManager();
        ManagedProcess process =
            manager.spawn(SpawnSpec.of("sh", "-c", "trap \"\" TERM; sleep 30"))) {
      long start = System.nanoTime();
      process.stopGracefully(Duration.ofMillis(300));
      process.onExit().get(5, TimeUnit.SECONDS);
      long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
      assertThat(process.isAlive()).isFalse();
      assertThat(elapsedMillis).isLessThan(5_000L);
    }
  }

  /** d) 输出上限：小上限下数秒内判 overflowed 且进程被终止，尾部缓冲不超过 100 行。 */
  @Test
  void outputOverflowStopsProcess() throws Exception {
    SpawnSpec spec =
        new SpawnSpec(
            "sh",
            List.of("-c", "i=0; while :; do echo line-$i; i=$((i+1)); done"),
            null,
            null,
            1024);
    try (SubprocessManager manager = new SubprocessManager();
        ManagedProcess process = manager.spawn(spec)) {
      process.onExit().get(10, TimeUnit.SECONDS);
      assertThat(process.overflowed()).isTrue();
      assertThat(process.isAlive()).isFalse();
      assertThat(process.outputTail().size()).isLessThanOrEqualTo(100);
    }
  }

  /** e) adopt：收养外部 spawn 的进程，无 stdin，可优雅关停。 */
  @Test
  void adoptManagesLifecycleOfExternalProcess() throws Exception {
    Process external = new ProcessBuilder("sh", "-c", "sleep 30").start();
    try (SubprocessManager manager = new SubprocessManager();
        ManagedProcess adopted = manager.adopt(external.pid())) {
      assertThat(adopted.isAlive()).isTrue();
      assertThat(adopted.stdin()).isEmpty();
      assertThat(adopted.outputTail()).isEmpty();
      adopted.stopGracefully(Duration.ofSeconds(1));
      adopted.onExit().get(5, TimeUnit.SECONDS);
      assertThat(adopted.isAlive()).isFalse();
    } finally {
      external.destroy();
    }
  }

  /** e2) adopt 不存在的 pid 抛 SubprocessException。 */
  @Test
  void adoptUnknownPidFails() {
    try (SubprocessManager manager = new SubprocessManager()) {
      assertThatThrownBy(() -> manager.adopt(Long.MAX_VALUE))
          .isInstanceOf(SubprocessException.class);
    }
  }

  /** f) manager.close() 批量收割：两个 sleep 全部终止、集合清空、幂等。 */
  @Test
  void managerCloseHarvestsAll() throws Exception {
    SubprocessManager manager = new SubprocessManager();
    ManagedProcess first = manager.spawn(SpawnSpec.of("sh", "-c", "sleep 30"));
    ManagedProcess second = manager.spawn(SpawnSpec.of("sh", "-c", "sleep 30"));
    assertThat(manager.managed()).hasSize(2);
    manager.close();
    first.onExit().get(5, TimeUnit.SECONDS);
    second.onExit().get(5, TimeUnit.SECONDS);
    assertThat(first.isAlive()).isFalse();
    assertThat(second.isAlive()).isFalse();
    assertThat(manager.managed()).isEmpty();
    manager.close(); // 幂等
  }

  /** 轮询等待尾部缓冲出现目标行（捕获线程与进程退出之间存在毫秒级时差）。 */
  private static List<String> awaitTailContaining(
      ManagedProcess process, String expected, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      List<String> tail = process.outputTail();
      if (tail.stream().anyMatch(line -> line.contains(expected))) {
        return tail;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("等待输出超时: " + expected);
  }
}
