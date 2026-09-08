package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.brain.runtime.AgentConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** {@link InProcessExecutor} 的假执行体契约：体在虚拟线程异步跑、句柄如实反映存亡、 close 幂等（Manager 的杀流程可能多次触发）。 */
class InProcessExecutorTest {

  @Test
  void bodyReceivesInstanceIdAndConfigAndHandleTracksDeath() throws Exception {
    AtomicReference<String> seenId = new AtomicReference<>();
    AtomicReference<AgentConfig> seenConfig = new AtomicReference<>();
    InProcessExecutor executor =
        new InProcessExecutor(
            (instanceId, config) -> {
              seenId.set(instanceId);
              seenConfig.set(config);
            });
    AgentConfig config = AgentConfig.builder("sleuth-1").systemPrompt("只读调查").build();

    LaunchedSubagent handle = executor.launch("sleuth-1", config);

    waitUntilDead(handle);
    assertThat(seenId).hasValue("sleuth-1");
    assertThat(seenConfig).hasValue(config);
    executor.close();
  }

  @Test
  void handleCloseIsIdempotent() throws Exception {
    // 阻塞体：在 close 前句柄保持存活，避免与体速完成的竞态
    CountDownLatch running = new CountDownLatch(1);
    InProcessExecutor executor =
        new InProcessExecutor((instanceId, config) -> running.await(2, TimeUnit.SECONDS));

    LaunchedSubagent handle = executor.launch("a-1", AgentConfig.builder("a-1").build());
    assertThat(handle.isAlive()).isTrue();

    handle.close();
    handle.close();

    assertThat(handle.isAlive()).isFalse();
    executor.close();
  }

  private static void waitUntilDead(LaunchedSubagent handle) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (handle.isAlive() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(handle.isAlive()).isFalse();
  }
}
