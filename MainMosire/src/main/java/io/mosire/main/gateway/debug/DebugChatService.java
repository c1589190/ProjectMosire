package io.mosire.main.gateway.debug;

import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.StopReason;
import io.mosire.brain.runtime.TurnResult;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调试对话服务（内存态运行管理）：{@code submit(message)} 把用户消息提交到<b>共享 chat 执行器</b>上跑主 {@link AgentRuntime}
 * 一回合，生成自增 runId + RUNNING 记录；执行完以 {@link TurnResult} 投影更新状态。
 *
 * <p><b>串行化不变量（R11）</b>：主 Agent 实例的 {@code chat} 非线程安全——本服务与 A2A/AG-UI 共用同一个单线程 chat
 * 执行器，运行按提交序串行执行，即"一个 Agent 实例的连续对话"。
 *
 * <p><b>stop 的全局语义</b>：{@link #stop} 转发 {@link AgentRuntime#cancel()}——取消作用于"当前运行中的那一回合"。
 * 因运行被单线程执行器串行化，同一时刻至多一个在途运行，故按 runId 寻址的取消实际是全局的（RUNNING 记录即当前运行）；空闲期 cancel 是空操作。取消不硬中断进行中的
 * LLM/工具调用——管线在下一个检查点以 {@link StopReason#CANCELLED} 就地终止。
 *
 * <p><b>生命周期</b>：实现 {@link AutoCloseable}，但 {@link #close()} 只封提交口（后续 {@code submit} 抛 {@link
 * IllegalStateException}），<b>不关停注入的执行器</b>——它是 App 持有的共享组件（A2A/AG-UI 同用，串行化不变量）， 关停顺序归 App。
 */
public final class DebugChatService implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(DebugChatService.class);

  private final AgentRuntime runtime;
  private final ExecutorService chatExecutor;
  private final AtomicLong seq = new AtomicLong();
  private final Map<Long, DebugChatRun> runs = new ConcurrentHashMap<>();
  private volatile boolean closed;

  /**
   * 两个注入件均为装配层按实例共享的<b>长生命周期</b>组件（运行时/共享 chat 执行器），本类的职责就是贯穿持有它们； 只读使用、不向外暴露引用——按构造注入 +
   * 独占持有是设计意图（与 {@code AgUiSessionRegistry} 同语义）。
   */
  public DebugChatService(AgentRuntime runtime, ExecutorService chatExecutor) {
    this.runtime = runtime;
    this.chatExecutor = chatExecutor;
  }

  /**
   * 提交一条用户消息：分配 runId、落 RUNNING 记录、调度到共享 chat 执行器执行。
   *
   * @return 自增 runId（>0）
   * @throws IllegalStateException 服务已关停（close 后不可再提交）
   */
  public long submit(String message) {
    requireOpen();
    long runId = seq.incrementAndGet();
    DebugChatRun run =
        new DebugChatRun(runId, DebugChatRun.Status.RUNNING, message, null, Instant.now());
    runs.put(runId, run);
    try {
      chatExecutor.execute(() -> runIt(run));
    } catch (RejectedExecutionException e) {
      // 关停窗：执行器已拒绝（共享执行器先于本服务关停——关停语义见 App.close 说明），就地记 FAILED 而非悬挂 RUNNING
      runs.put(runId, failed(run));
      LOG.warn("调试对话运行未调度（执行器已关停）: {}", runId);
    }
    return runId;
  }

  /** 回合执行（在 chat 执行器线程上）：一回合 → 状态合成 + TurnResult 投影（整体替换记录）。 */
  private void runIt(DebugChatRun run) {
    TurnResult result;
    try {
      result = runtime.chat(run.message());
    } catch (RuntimeException e) {
      runs.put(run.runId(), failed(run));
      LOG.warn("调试对话运行 {} 回合异常: {}", run.runId(), e.getMessage(), e);
      return;
    }
    DebugChatRun.Status status =
        switch (result.stopReason()) {
          case FINISHED -> DebugChatRun.Status.FINISHED;
          case CANCELLED -> DebugChatRun.Status.CANCELLED;
          case TURN_LIMIT, TOOL_CALL_LIMIT, TIME_BUDGET, QUOTA, LLM_ERROR ->
              DebugChatRun.Status.FAILED;
        };
    runs.put(
        run.runId(), new DebugChatRun(run.runId(), status, run.message(), result, run.createdAt()));
  }

  /** 回合异常（或未调度）的终态投影：FAILED、无 TurnResult。 */
  private static DebugChatRun failed(DebugChatRun run) {
    return new DebugChatRun(
        run.runId(), DebugChatRun.Status.FAILED, run.message(), null, run.createdAt());
  }

  /**
   * 请求取消运行：仅当 {@code runId} 存在且在 RUNNING 时转发 {@link AgentRuntime#cancel()}。
   *
   * <p>取消是全局的（单线程执行器串行化 ⇒ RUNNING 运行即当前运行），语义见类 Javadoc。
   *
   * @return true = 已转发取消（运行此后以 CANCELLED 终态落地）；false = 不存在或已终态（no-op）
   */
  public boolean stop(long runId) {
    DebugChatRun run = runs.get(runId);
    if (run == null || run.status() != DebugChatRun.Status.RUNNING) {
      return false;
    }
    runtime.cancel();
    return true;
  }

  /** 按 runId 查运行记录。 */
  public Optional<DebugChatRun> get(long runId) {
    return Optional.ofNullable(runs.get(runId));
  }

  /**
   * 分页列运行记录：runId 倒序（最新在前），一页 = 一批对话结果。
   *
   * @param page 页号（0 起始）
   * @param size 页大小（≥1）
   * @throws IllegalArgumentException page &lt; 0 或 size &lt; 1
   */
  public List<DebugChatRun> listResults(int page, int size) {
    if (page < 0) {
      throw new IllegalArgumentException("page 必须 >= 0: " + page);
    }
    if (size < 1) {
      throw new IllegalArgumentException("size 必须 >= 1: " + size);
    }
    return runs.values().stream()
        .sorted(Comparator.comparingLong(DebugChatRun::runId).reversed())
        .skip((long) page * size)
        .limit(size)
        .toList();
  }

  /** 关停：只封提交口（后续 submit 抛 IllegalStateException）；不关停共享执行器（归 App——见类 Javadoc）。 */
  @Override
  public void close() {
    closed = true;
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("调试对话服务已关停");
    }
  }
}
