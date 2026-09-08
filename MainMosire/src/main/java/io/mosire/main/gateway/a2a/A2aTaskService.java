package io.mosire.main.gateway.a2a;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A2A Task 服务：状态机 + 执行器桥（计划 §5.2.3：状态机参照 server-common {@code TaskManager} 语义）。
 *
 * <p>状态机规则（照搬 {@code TaskManager.validateStateTransition} 镜像）：
 *
 * <ul>
 *   <li>新任务 = {@code SUBMITTED}，首条消息入 history；
 *   <li>{@code final → 不同状态} 拒绝（{@link InternalError}）；相同终态幂等覆盖（允许重放/刷新）；
 *   <li>终态：completed / canceled / failed / rejected。
 * </ul>
 *
 * <p>任务执行经 {@link A2aMessageRunner} 在独立执行器上异步进行（M2-D 由 Brain 执行循环实现）， 执行过程经 {@link TaskEditor}
 * 推进状态——每次推进校验 + 快照落库 + 通知订阅者（流式 SSE 的事件源）。
 *
 * <p>P0 简化（文档化）：{@code MessageSendConfiguration} 忽略（仅解析不执行模式协商，返回 Immediately 等价语义——异步模型本来就是即时返回）；
 * cancel 不强制中断 runner（其下一次转移会被状态机拒绝，与镜像语义一致；真正的进程级中断在 M2-D 接线）。
 */
public final class A2aTaskService implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(A2aTaskService.class);

  /** 任务推进器：服务在执行器上异步调用；经 {@link TaskEditor} 推进状态。 */
  @FunctionalInterface
  public interface A2aMessageRunner {
    void run(String taskId, Message message, TaskEditor editor) throws A2AError;
  }

  /** 任务状态编辑句柄（runner 唯一推进入口；全部转移经状态机校验）。 */
  public static final class TaskEditor {

    private final A2aTaskService service;
    private final String taskId;

    private TaskEditor(A2aTaskService service, String taskId) {
      this.service = service;
      this.taskId = taskId;
    }

    /** 状态转移（可附状态消息）。返回推进后的任务快照。 */
    public Task transition(TaskState state) {
      return transition(state, null);
    }

    /** 状态转移并附状态消息（旧状态消息自动转存 history——镜像 TaskManager.saveTaskEvent）。 */
    public Task transition(TaskState state, Message message) {
      return service.transition(taskId, state, message, Map.of());
    }

    /** 追加产物（append 语义：入 artifacts 尾部）。 */
    public Task appendArtifact(Artifact artifact) {
      return service.appendArtifact(taskId, artifact);
    }

    /** 当前快照。 */
    public Task snapshot() {
      return service.requireTask(taskId);
    }
  }

  private final Object lock = new Object();

  private final A2aTaskStore store;
  private final A2aMessageRunner runner;
  private final ExecutorService executor;

  /** 流式订阅者（taskId → 监听器；仅锁内访问）。 */
  private final Map<String, List<Consumer<Task>>> listeners = new HashMap<>();

  public A2aTaskService(A2aTaskStore store, A2aMessageRunner runner) {
    this(store, runner, Executors.newVirtualThreadPerTaskExecutor());
  }

  /** 注入执行器（测试用）；本服务拥有其生命周期（close 时关闭）。 */
  public A2aTaskService(A2aTaskStore store, A2aMessageRunner runner, ExecutorService executor) {
    this.store = Objects.requireNonNull(store, "store");
    this.runner = Objects.requireNonNull(runner, "runner");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /**
   * 送达消息：{@code message.taskId()} 为空 → 新任务；非空 → 续接既有任务（消息追加 history，状态 → WORKING； 终态任务续接按状态机拒绝）。
   *
   * <p>返回**当前时刻**快照（默认 SUBMITTED/WORKING）；推进异步进行（{@link #subscribeUntilFinal} 可观察全程）。
   */
  public Task sendMessage(Message message) {
    synchronized (lock) {
      String incomingTaskId = message.taskId();
      if (incomingTaskId != null && !incomingTaskId.isBlank()) {
        return continueMessageLocked(incomingTaskId, message);
      }
      String taskId = java.util.UUID.randomUUID().toString();
      String incomingContextId = message.contextId();
      // contextId 透传首条消息；缺失（null/空白）→ 生成 UUID——镜像 server-common RequestContext 的 coalesce
      // 语义（builder → message → generate，ListTasks 的 contextId 过滤因此成立）。偏差：官方对 "" 原样保留，
      // 但 spec Task 构造器断言 contextId 非空，且官方客户端把 wire 上的空 contextId 归一化为 null
      // （spec-grpc TaskMapper.emptyToNull）→ 发 "" 会被官方客户端拒收，故空白等同缺失。
      Task task =
          Task.builder()
              .id(taskId)
              .contextId(
                  incomingContextId == null || incomingContextId.isBlank()
                      ? java.util.UUID.randomUUID().toString()
                      : incomingContextId)
              .status(new TaskStatus(TaskState.TASK_STATE_SUBMITTED))
              .history(List.of(message))
              .build();
      store.put(task);
      notifySubscribers(task);
      // 执行器已关闭时任务已入库，不能把拒绝抛给 HTTP 层：合成 FAILED 并回读终态快照返回
      try {
        executor.execute(() -> runMessage(taskId, message));
      } catch (RejectedExecutionException e) {
        LOG.warn("执行器已关闭，任务未调度: {}", taskId, e);
        failToState(taskId, TaskState.TASK_STATE_FAILED, "执行器已关闭，任务未调度");
        return requireTask(taskId);
      }
      return task;
    }
  }

  private Task continueMessageLocked(String taskId, Message message) {
    Task current = store.get(taskId).orElseThrow(TaskNotFoundError::new);
    validateTransition(current.status().state(), TaskState.TASK_STATE_WORKING, taskId);
    Task next =
        Task.builder(current)
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(appendHistory(current.history(), message))
            .build();
    store.put(next);
    notifySubscribers(next);
    try {
      executor.execute(() -> runMessage(taskId, message));
    } catch (RejectedExecutionException e) {
      LOG.warn("执行器已关闭，任务未调度: {}", taskId, e);
      failToState(taskId, TaskState.TASK_STATE_FAILED, "执行器已关闭，任务未调度");
      return requireTask(taskId);
    }
    return next;
  }

  private void runMessage(String taskId, Message message) {
    try {
      runner.run(taskId, message, new TaskEditor(this, taskId));
    } catch (A2AError e) {
      // A2AError → 合成 FAILED（计划语义）；若是状态机拒绝（任务已终态），failToState 内部幂等跳过
      failToState(taskId, TaskState.TASK_STATE_FAILED, e.getMessage());
    } catch (Exception e) {
      failToState(taskId, TaskState.TASK_STATE_FAILED, "内部错误: " + e.getMessage());
    }
  }

  /** 读取任务（不存在 → {@link TaskNotFoundError} (-32001)）。 */
  public Task requireTask(String taskId) {
    synchronized (lock) {
      return store.get(taskId).orElseThrow(TaskNotFoundError::new);
    }
  }

  /** 取消：终态任务 → {@link TaskNotCancelableError} (-32002)；否则置 CANCELED（终态）。 */
  public Task cancelTask(String taskId) {
    synchronized (lock) {
      Task current = store.get(taskId).orElseThrow(TaskNotFoundError::new);
      if (current.status().state().isFinal()) {
        throw new TaskNotCancelableError(
            "Task cannot be canceled - current state: " + current.status().state());
      }
      return transitionLocked(taskId, TaskState.TASK_STATE_CANCELED, null, Map.of());
    }
  }

  /**
   * 列任务（status 过滤按镜像的 ListTasksParams 语义；pageToken = 整数 offset——镜像用 keyset 令牌， 此处以“offset
   * 自洽往返”的简版实现，令牌对客户端不透明，互操作不受影响）。
   *
   * <p>Released {@code ListTasksResult} 构造函数断言 {@code pageSize == tasks.size()}（pageSize =
   * **本次返回条数**， 非请求页大小——与镜像 {@code InMemoryTaskStore.list} 一致）。
   */
  public ListTasksResult listTasks(ListTasksParams params) {
    List<Task> all = store.list(params.contextId(), Integer.MAX_VALUE, 0);
    List<Task> filtered = new ArrayList<>(all.size());
    for (Task task : all) {
      if (params.status() == null || params.status() == task.status().state()) {
        filtered.add(task);
      }
    }
    int pageSize = params.getEffectivePageSize();
    int offset = parsePageToken(params.pageToken());
    if (offset >= filtered.size()) {
      return new ListTasksResult(List.of(), filtered.size(), 0, null);
    }
    int from = Math.max(offset, 0);
    int to = Math.min(from + pageSize, filtered.size());
    List<Task> page = List.copyOf(filtered.subList(from, to));
    String next = to < filtered.size() ? String.valueOf(to) : null;
    return new ListTasksResult(page, filtered.size(), page.size(), next);
  }

  /**
   * 阻塞式订阅：立即推当前快照（Task），其后每次状态推进推最新快照，直至终态后返回（流式 SSE 的事件源）。
   *
   * <p>线程模型：快照在锁内推送、后续更新在推进线程推送——{@code sink} 必须廉价且线程安全（HTTP 层用无界队列承接，写帧在 handler 线程）。
   */
  public void subscribeUntilFinal(String taskId, Consumer<Task> sink) {
    Objects.requireNonNull(sink, "sink");
    CountDownLatch done = new CountDownLatch(1);
    Consumer<Task> forwarding =
        task -> {
          sink.accept(task);
          if (task.status().state().isFinal()) {
            done.countDown();
          }
        };
    synchronized (lock) {
      Task current = store.get(taskId).orElseThrow(TaskNotFoundError::new);
      // 先快照（锁内嵌序保证：注册前的全部变化已在快照内，注册后变化走 forwarding，顺序单调不回退）
      forwarding.accept(current);
      if (!current.status().state().isFinal()) {
        listeners.computeIfAbsent(taskId, k -> new ArrayList<>()).add(forwarding);
      }
    }
    try {
      done.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Task transition(
      String taskId, TaskState state, Message message, Map<String, Object> metadata) {
    synchronized (lock) {
      return transitionLocked(taskId, state, message, metadata);
    }
  }

  /** 锁内转移（调用方持锁）：状态机校验 → 快照 → 通知订阅者。 */
  private Task transitionLocked(
      String taskId, TaskState state, Message message, Map<String, Object> metadata) {
    Task current = store.get(taskId).orElseThrow(TaskNotFoundError::new);
    validateTransition(current.status().state(), state, taskId);
    Task.Builder builder =
        Task.builder(current).status(new TaskStatus(state, message, OffsetDateTime.now()));
    if (current.status().message() != null) {
      builder.history(appendHistory(current.history(), current.status().message()));
    }
    if (!metadata.isEmpty()) {
      Map<String, Object> merged = new HashMap<>();
      Map<String, Object> currentMetadata = current.metadata();
      if (currentMetadata != null) {
        merged.putAll(currentMetadata);
      }
      merged.putAll(metadata);
      builder.metadata(Map.copyOf(merged));
    }
    Task next = builder.build();
    store.put(next);
    notifySubscribers(next);
    return next;
  }

  private void failToState(String taskId, TaskState state, String message) {
    synchronized (lock) {
      var current = store.get(taskId);
      if (current.isEmpty() || current.get().status().state().isFinal()) {
        // 已终态（如已被 cancel 并成功置终态）：不再覆盖，镜像语义（终态后仅允许同终态幂等）
        return;
      }
      transitionLocked(taskId, state, null, Map.of("error", message));
    }
  }

  private Task appendArtifact(String taskId, Artifact artifact) {
    synchronized (lock) {
      Task current = store.get(taskId).orElseThrow(TaskNotFoundError::new);
      List<Artifact> currentArtifacts = current.artifacts();
      List<Artifact> artifacts =
          currentArtifacts == null ? new ArrayList<>() : new ArrayList<>(currentArtifacts);
      artifacts.add(artifact);
      Task next = Task.builder(current).artifacts(List.copyOf(artifacts)).build();
      store.put(next);
      notifySubscribers(next);
      return next;
    }
  }

  /** 状态机校验（镜像 TaskManager.validateStateTransition：null 跳过；终态→不同状态拒绝；同终态幂等）。 */
  static void validateTransition(TaskState current, TaskState next, String taskId) {
    if (current == null || next == null) {
      return;
    }
    if (current.isFinal() && current != next) {
      throw new InternalError(
          "Task "
              + taskId
              + " is already in terminal state "
              + current
              + " and cannot transition to "
              + next);
    }
  }

  private void notifySubscribers(Task task) {
    List<Consumer<Task>> subs = listeners.get(task.id());
    if (subs == null) {
      return;
    }
    for (Consumer<Task> sub : List.copyOf(subs)) {
      sub.accept(task);
    }
    if (task.status().state().isFinal()) {
      listeners.remove(task.id());
    }
  }

  private static List<Message> appendHistory(List<Message> history, Message message) {
    List<Message> result = history == null ? new ArrayList<>() : new ArrayList<>(history);
    result.add(message);
    return List.copyOf(result);
  }

  private static int parsePageToken(String pageToken) {
    if (pageToken == null || pageToken.isBlank()) {
      return 0;
    }
    try {
      int offset = Integer.parseInt(pageToken);
      if (offset < 0) {
        throw new NumberFormatException(pageToken);
      }
      return offset;
    } catch (NumberFormatException e) {
      throw new InvalidParamsError("Invalid pageToken: '" + pageToken + "'");
    }
  }

  /**
   * 关闭：停接新任务 → 对<b>全部</b>非终态任务合成 FAILED → 有界等待在途任务退场——保证 close 之后不存在悬挂的非终态任务 （订阅者观察到终态而非永久等待；类契约）。
   *
   * <p><b>合成先于等待</b>：{@code shutdownNow} 中断了在途 runner 线程——其退场路径（管线中断 → runner 抛 {@link
   * A2AError}）虽然也会调 {@link #failToState}，但该线程带着中断标记，终态帧投递经过的阻塞入队会因中断 直接失效（丢帧）。故先由未被中断的 close 线程合成
   * FAILED（在途任务在此收终态），再等 runner 退场； 若 runner 先得锁合成，投递由 {@link A2aHttpServer} 的 offer 入队兜底（见其 feeder
   * 说明）。
   *
   * <p>两次扫描覆盖的竞态窗口：
   *
   * <ul>
   *   <li>扫描①（入口，锁外取，避免持锁调 store.list）：close 开始时刻的非终态任务；
   *   <li>{@code shutdownNow}：丢弃已入队而未启动的 runMessage（execute 已被接受）——被丢弃的任务没人会自行合成 FAILED；
   *   <li>扫描②（{@code awaitTermination} 之后）：覆盖"扫描①与 shutdownNow 之间"被 put 的新任务（其 execute 在
   *       shutdownNow 前被接受、入队、随后被丢弃）——扫描①见不到、异常路径也不会触发，只有二次扫描能接住；
   *   <li>shutdownNow 之后的新提交 → {@code executor.execute} 抛 {@link RejectedExecutionException} →
   *       {@link #sendMessage}/{@link #continueMessageLocked} 既有路径自行合成 FAILED（任务已入库），无需第三次扫描。
   * </ul>
   */
  @Override
  public void close() {
    List<Task> firstScan = store.list(null, Integer.MAX_VALUE, 0);
    executor.shutdownNow();
    synthesizeFailed(firstScan);
    // 有界等待在途 runner 退场：其管线回调还会向 EventStore/EventBus 写事件，App.close 将随后关掉存储——
    // 超时不阻塞（任务已中断，残留写由事件层兜底），只记警告
    try {
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        LOG.warn("A2A 任务执行器未在 2 秒内退出，继续关闭");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    synthesizeFailed(store.list(null, Integer.MAX_VALUE, 0));
  }

  /** 对非终态任务合成 FAILED（单个失败只记日志不阻断整批；幂等——已终态/终态重放均安全）。 */
  private void synthesizeFailed(List<Task> tasks) {
    for (Task task : tasks) {
      if (!task.status().state().isFinal()) {
        try {
          failToState(task.id(), TaskState.TASK_STATE_FAILED, "A2A 服务已关闭，任务强制失败");
        } catch (RuntimeException e) {
          LOG.warn("关闭时合成任务 FAILED 状态失败: {}", task.id(), e);
        }
      }
    }
  }
}
