package io.mosire.main.gateway.debug;

import io.mosire.brain.runtime.AgentRuntime;
import io.mosire.brain.runtime.StopReason;
import io.mosire.brain.runtime.TurnResult;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * <p><b>会话重置（P3-3 / D26）</b>：{@link #resetSession()} 换新会话 id + 清空主 Agent 的内存工作集（旧会话在库里留档）。切换动作同受
 * 上述串行化约束——{@code history} 是实例级共享状态，重置与回合必须走同一个串行化点；同一执行器 FIFO ⇒ 排在重置之后的回合必然在重置之后跑。 调用方 {@code
 * close()} 之后的重置一律失败（不被吞成"看着像成功"）。
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

  /**
   * 当前会话 id（{@code GET /api/chat/session} 的只读口）：反映的是运行时即时值——重置已受理但尚未执行时仍是旧 id（"切换是一个动作，不是一个声明"）。
   *
   * <p>无条件可读（不要求服务未关停）：它是只读的，关停后读到的仍是最后一次已执行的值，报 503 反而会让"重置到底生效没有"更难查。
   */
  public String conversationId() {
    return runtime.conversationId();
  }

  /**
   * 重置会话（P3-3 / D26）：换新会话 id + 清空主 Agent 的内存工作集，旧会话在库里原样留档（不删）。
   *
   * <p><b>id 在这里生成、切换排在执行器上</b>——两侧各有理由，缺一不可：
   *
   * <ul>
   *   <li>新 id 在<b>调用线程</b>同步生成：HTTP 响应要立刻带上它（调用方拿到就知道"接下来这段聊在哪"），而生成 id 本身不碰任何共享状态；
   *   <li>切换动作（{@link AgentRuntime#resetSession(String)} 清历史 + 换 id）<b>必须</b>提交到共享单线程 chat 执行器：它改的是
   *       {@code AgentPipeline.history}（实例级共享、非线程安全，R11）——在 HTTP 线程上直接改，就会与在途回合交错（在途回合的 {@code
   *       saveHistory} 会把上一个会话的尾部追加进新会话）。同一执行器 FIFO ⇒ 排在本方法之后的对话回合必然在重置之后执行。
   * </ul>
   *
   * <p><b>重置跨重启有效</b>：切换动作里连同<b>会话指针落库</b>一起发生（{@code AgentPipeline#resetConversation} → {@code
   * ConversationStore#setCurrentConversationId}）——否则本进程看着已重置，重启后又从旧会话续起（用户已清掉的历史复活）。指针写在内存切换之前，
   * 写失败则内存不切（宁可不生效，不留下"重启才暴露"的分裂态）。
   *
   * <p><b>响应语义是"已受理"而非"已生效"</b>（口径同 {@code DebugChatHttpServer} 的 POST /api/chat/session）：返回的新 id
   * 是"切换已被受理"的凭据——切换动作只是排进共享执行器队列，此刻可能尚未执行。对调用方仍够用：同一执行器 FIFO ⇒ 拿到本响应之后提交的对话回合
   * 必然在切换<b>之后</b>执行，不会与在途回合交错（这正是不在 HTTP 线程上直接切的原因）。
   *
   * <p><b>"受理了但没生效"的两种已知情形</b>（响应语义不因此改变，如实记录而非掩盖）：① 运行时已关停——任务照常出队执行，但 {@code
   * AgentRuntime.resetSession} 在 {@code ensureOpen} 处抛错，只留下一条日志（此刻响应早已发出），运行时侧的会话 id 仍是旧值；② 已受理的任务
   * 在关停窗内被共享执行器的 {@code shutdownNow()} 从队列里丢弃——永不执行（见 {@link #close()}）。
   *
   * <p><b>失败不撒谎（仅指调度）</b>：调度被拒（执行器已关停——关停窗，见 {@link #close()}）时抛 {@link
   * IllegalStateException}，不回报"已受理"。
   *
   * @return 新会话 id + 提交时刻的旧会话 id（见 {@link DebugSessionReset} 对两次连续重置的语义说明）
   * @throws IllegalStateException 服务已关停，或重置未调度（共享执行器已关停）
   */
  public DebugSessionReset resetSession() {
    requireOpen();
    String previous = runtime.conversationId();
    String conversationId = "s-" + UUID.randomUUID();
    try {
      chatExecutor.execute(
          () -> {
            try {
              runtime.resetSession(conversationId);
            } catch (RuntimeException e) {
              LOG.warn("会话重置执行失败（响应已回报新会话 id）: {}", conversationId, e);
            }
          });
    } catch (RejectedExecutionException e) {
      throw new IllegalStateException("会话重置未调度（共享 chat 执行器已关停）", e);
    }
    return new DebugSessionReset(conversationId, previous);
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

  /**
   * 关停：只封提交口（后续 {@code submit} 抛 IllegalStateException）；不关停共享执行器（归 App——见类 Javadoc）。
   *
   * <p><b>关停窗内已受理的重置可能被丢弃</b>：{@code close()} 本身不碰执行器，但 App 的关停链随后会 {@code shutdownNow()} 共享执行器（A2A
   * 任务服务先关）——此刻仍排在队列里、尚未出队的重置任务会被直接丢弃，永不执行。即：调用方此前拿到的新会话 id 是按"已受理"回报的， 其重置可能不会生效（口径见 {@link
   * #resetSession()} 的两种已知情形）。
   */
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
