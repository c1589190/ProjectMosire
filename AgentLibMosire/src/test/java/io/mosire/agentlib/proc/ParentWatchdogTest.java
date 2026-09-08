package io.mosire.agentlib.proc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** ParentWatchdog 离线行为测试（仅 POSIX；用真实进程句柄充当"父进程"）。 */
class ParentWatchdogTest {

  @BeforeAll
  static void requirePosix() {
    assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "仅 POSIX");
  }

  /**
   * g) supplier 返回已退出进程的句柄：onParentGone 在 ~1s 内被调，看门狗自停。 先 start 再 destroy `sh -c
   * "true"`，模拟"看门狗运行期间父进程死亡"。
   */
  @Test
  void firesWhenParentDies() throws Exception {
    Process fakeParent = new ProcessBuilder("sh", "-c", "true").start();
    CountDownLatch fired = new CountDownLatch(1);
    ParentWatchdog watchdog =
        ParentWatchdog.start(Duration.ofMillis(50), fakeParent::toHandle, fired::countDown);
    try {
      fakeParent.destroy();
      fakeParent.waitFor();
      assertThat(fired.await(1, TimeUnit.SECONDS)).as("onParentGone 应在 ~1s 内触发").isTrue();
      assertThat(watchdog.isStopped()).isTrue();
    } finally {
      watchdog.close();
      fakeParent.destroy();
    }
  }

  /** h) 父进程活着不触发；close() 幂等且中断轮询线程。 */
  @Test
  void closeIsIdempotentAndStopsPolling() throws Exception {
    CountDownLatch fired = new CountDownLatch(1);
    ParentWatchdog watchdog =
        ParentWatchdog.start(
            Duration.ofMillis(50), () -> ProcessHandle.current(), fired::countDown);
    try {
      Thread.sleep(200); // 轮询数轮，current() 恒存活 → 不触发
      assertThat(fired.getCount()).isEqualTo(1);
    } finally {
      watchdog.close();
      watchdog.close(); // 幂等
    }
    assertThat(watchdog.isStopped()).isTrue();
    watchdog.watcher().join(2_000);
    assertThat(watchdog.watcher().isAlive()).as("close 后轮询线程应退出").isFalse();
  }

  /** i) onParentGone 抛异常：只记日志，自停流程照常完成。 */
  @Test
  void onParentGoneExceptionDoesNotBlockStop() throws Exception {
    Process fakeParent = new ProcessBuilder("sh", "-c", "true").start();
    ParentWatchdog watchdog =
        ParentWatchdog.start(
            Duration.ofMillis(50),
            fakeParent::toHandle,
            () -> {
              throw new IllegalStateException("故意失败");
            });
    try {
      fakeParent.destroy();
      fakeParent.waitFor();
      long deadline = System.currentTimeMillis() + 2_000;
      while (!watchdog.isStopped() && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }
      assertThat(watchdog.isStopped()).as("回调抛异常后看门狗仍应自停").isTrue();
    } finally {
      watchdog.close();
      fakeParent.destroy();
    }
  }
}
