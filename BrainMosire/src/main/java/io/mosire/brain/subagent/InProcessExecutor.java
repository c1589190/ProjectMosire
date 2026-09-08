package io.mosire.brain.subagent;

import io.mosire.brain.runtime.AgentConfig;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 离线测试/演示用执行体（计划 §4.4 InProcessExecutor）：不启真实子进程，在虚拟线程里跑注入的 "子 Agent 体"（{@link
 * AgentBody}），句柄存亡如实镜像（体结束 = 句柄死；{@link LaunchedSubagent#close} = 请求终止）。
 *
 * <p>为什么放 main 源集：Main 的离线 demo 与 Brain 单元测试共用同一假件（同 FakeLlmClient 先例——离线 假件随被测模块发布，编排核心不因接线而改动；真实
 * E2E 只在 Task 6 / Main 侧）
 *
 * <p>体由测试用门闩/标志控制时长与完成时机（"controlled"）；{@link #close()} 只中断工作体、不等待 （虚拟线程不占用关停路径，等待语义由外层编排决定）。
 */
public final class InProcessExecutor implements AgentExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(InProcessExecutor.class);

  /** 子 Agent 体：模拟一次子 Agent 运行（测试用门闩控制完成时机）。 */
  @FunctionalInterface
  public interface AgentBody {

    void run(String instanceId, AgentConfig config) throws Exception;
  }

  private final AgentBody body;
  private final ExecutorService workers;
  private volatile boolean closed;

  /** 无体默认：启动即完成（"瞬时完成"的子 Agent）。 */
  public InProcessExecutor() {
    this((instanceId, config) -> {});
  }

  public InProcessExecutor(AgentBody body) {
    this.body = Objects.requireNonNull(body, "body");
    this.workers = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public LaunchedSubagent launch(SubagentInstance instance) {
    if (closed) {
      throw new IllegalStateException("InProcessExecutor 已关闭");
    }
    Handle handle = new Handle();
    workers.execute(
        () -> {
          try {
            body.run(instance.instanceId(), instance.config());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            LOG.debug("InProcessExecutor 体异常 instanceId={}", instance.instanceId(), e);
          } finally {
            handle.markFinished();
          }
        });
    return handle;
  }

  /** 中断全部工作体（不等待）；幂等。 */
  @Override
  public void close() {
    closed = true;
    workers.shutdownNow();
  }

  private static final class Handle implements LaunchedSubagent {

    private final AtomicBoolean alive = new AtomicBoolean(true);

    @Override
    public boolean isAlive() {
      return alive.get();
    }

    @Override
    public void close() {
      alive.set(false);
    }

    void markFinished() {
      alive.set(false);
    }
  }
}
