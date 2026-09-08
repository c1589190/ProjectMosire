package io.mosire.main.gateway.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.main.gateway.a2a.A2aTaskService.A2aMessageRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A2A Task 服务：状态机/续接/取消/失败合成/订阅顺序/分页（MemoryA2aTaskStore 双胞胎之一，另见 EventStore 实现测试）。 */
class A2aTaskServiceTest {

  private static final Message USER_MSG = A2A.toUserMessage("ping");
  private static final Message USER_MSG_2 = A2A.toUserMessage("pong");

  private final A2aTaskStore store = new MemoryA2aTaskStore();
  private final ExecutorService executor = Executors.newFixedThreadPool(2);
  private A2aTaskService service;

  @AfterEach
  void tearDown() {
    if (service != null) {
      service.close();
    }
    executor.shutdownNow();
  }

  private A2aTaskService service(A2aMessageRunner runner) {
    service = new A2aTaskService(store, runner, executor);
    return service;
  }

  /** 直通 runner：SUBMITTED → WORKING（带状态消息）→ COMPLETED。 */
  private static A2aMessageRunner happyPath() {
    return (taskId, message, editor) -> {
      editor.transition(TaskState.TASK_STATE_WORKING, A2A.toAgentMessage("working on it"));
      editor.appendArtifact(
          Artifact.builder()
              .artifactId("a-1")
              .name("result")
              .parts(new TextPart("result text"))
              .build());
      editor.transition(TaskState.TASK_STATE_COMPLETED);
    };
  }

  private Task awaitFinal(String taskId) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      Task task = service.requireTask(taskId);
      if (task.status().state().isFinal()) {
        return task;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("task " + taskId + " did not reach final state");
  }

  @Test
  void newTaskIsSubmittedWithMessageInHistoryThenRunsToCompletion() throws Exception {
    service(happyPath());
    Task submitted = service.sendMessage(USER_MSG);
    assertThat(submitted.status().state()).isEqualTo(TaskState.TASK_STATE_SUBMITTED);
    assertThat(submitted.history()).containsExactly(USER_MSG);

    Task done = awaitFinal(submitted.id());
    assertThat(done.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
    assertThat(done.status().message()).isNull();
    assertThat(done.artifacts()).hasSize(1);
    assertThat(done.artifacts().get(0).name()).isEqualTo("result");
    // 旧状态消息转入 history（镜像 TaskManager.saveTaskEvent）
    assertThat(done.history()).hasSize(2);
    assertThat(done.history().get(0)).isEqualTo(USER_MSG);
    assertThat(((TextPart) done.history().get(1).parts().get(0)).text()).isEqualTo("working on it");
  }

  @Test
  void continuationAppendsHistoryAndFlipsToWorking() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    service(
        (taskId, message, editor) -> {
          started.countDown();
          release.await(5, TimeUnit.SECONDS);
          editor.transition(TaskState.TASK_STATE_COMPLETED);
        });
    Task first = service.sendMessage(USER_MSG);
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    Message cont =
        new Message(
            USER_MSG_2.role(),
            USER_MSG_2.parts(),
            USER_MSG_2.messageId(),
            USER_MSG_2.contextId(),
            first.id(),
            USER_MSG_2.referenceTaskIds(),
            USER_MSG_2.metadata(),
            USER_MSG_2.extensions());
    Task working = service.sendMessage(cont);
    assertThat(working.status().state()).isEqualTo(TaskState.TASK_STATE_WORKING);
    // 镜像 saveTaskEvent 语义：续接消息**原样**入 history（taskId 保留，官方服务端同样如此）
    assertThat(working.history()).containsExactly(USER_MSG, cont);

    release.countDown();
    Task done = awaitFinal(first.id());
    assertThat(done.status().state()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
  }

  @Test
  void continuationOnFinalTaskRejected() throws Exception {
    service(happyPath());
    Task first = service.sendMessage(USER_MSG);
    awaitFinal(first.id());
    assertThatThrownBy(() -> service.sendMessage(withTaskId(USER_MSG_2, first.id())))
        .isInstanceOf(InternalError.class)
        .hasMessageContaining("terminal state");
  }

  @Test
  void cancelTaskOnFinalRejected() throws Exception {
    service(happyPath());
    Task first = service.sendMessage(USER_MSG);
    awaitFinal(first.id());
    assertThatThrownBy(() -> service.cancelTask(first.id()))
        .isInstanceOf(TaskNotCancelableError.class)
        .hasMessageContaining("cannot be canceled");
  }

  @Test
  void cancelWhileRunningWinsOverLateRunnerTransition() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    service(
        (taskId, message, editor) -> {
          started.countDown();
          release.await(5, TimeUnit.SECONDS);
          // 取消后尝试推进 COMPLETED：状态机拒绝 → 合成失败被幂等跳过
          editor.transition(TaskState.TASK_STATE_COMPLETED);
        });
    Task first = service.sendMessage(USER_MSG);
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    Task canceled = service.cancelTask(first.id());
    assertThat(canceled.status().state()).isEqualTo(TaskState.TASK_STATE_CANCELED);
    release.countDown();
    Thread.sleep(200);
    assertThat(service.requireTask(first.id()).status().state())
        .isEqualTo(TaskState.TASK_STATE_CANCELED);
  }

  @Test
  void runnerErrorSynthesizesFailedTask() throws Exception {
    service(
        (taskId, message, editor) -> {
          throw new A2AError(-32000, "boom", Map.of());
        });
    Task first = service.sendMessage(USER_MSG);
    Task failed = awaitFinal(first.id());
    assertThat(failed.status().state()).isEqualTo(TaskState.TASK_STATE_FAILED);
    assertThat(failed.metadata()).containsEntry("error", "boom");
  }

  @Test
  void getTaskMissingThrows() {
    service(happyPath());
    assertThatThrownBy(() -> service.requireTask("nope")).isInstanceOf(TaskNotFoundError.class);
  }

  @Test
  void subscribeDeliversSnapshotThenTransitionsInOrder() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    service(
        (taskId, message, editor) -> {
          started.countDown();
          release.await(5, TimeUnit.SECONDS);
          editor.transition(TaskState.TASK_STATE_WORKING);
          editor.transition(TaskState.TASK_STATE_COMPLETED);
        });
    Task first = service.sendMessage(USER_MSG);
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

    List<TaskState> seen = new ArrayList<>();
    CountDownLatch done = new CountDownLatch(1);
    Thread sub =
        new Thread(
            () -> {
              service.subscribeUntilFinal(
                  first.id(),
                  task -> {
                    seen.add(task.status().state());
                    if (task.status().state().isFinal()) {
                      done.countDown();
                    }
                  });
            });
    sub.start();
    Thread.sleep(150);
    assertThat(seen).isNotEmpty(); // 快照已推送（SUBMITTED）
    release.countDown();
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    sub.join();
    assertThat(seen)
        .containsExactly(
            TaskState.TASK_STATE_SUBMITTED,
            TaskState.TASK_STATE_WORKING,
            TaskState.TASK_STATE_COMPLETED);
  }

  @Test
  void subscribeOnFinalTaskDeliversSingleSnapshot() throws Exception {
    service(happyPath());
    Task first = service.sendMessage(USER_MSG);
    awaitFinal(first.id());

    List<TaskState> seen = new ArrayList<>();
    service.subscribeUntilFinal(first.id(), task -> seen.add(task.status().state()));
    assertThat(seen).containsExactly(TaskState.TASK_STATE_COMPLETED);
  }

  @Test
  void subscribeMissingTaskThrows() {
    service(happyPath());
    assertThatThrownBy(() -> service.subscribeUntilFinal("nope", t -> {}))
        .isInstanceOf(TaskNotFoundError.class);
  }

  @Test
  void listTasksFiltersByStatusAndPaginates() throws Exception {
    service(happyPath());
    Task a = service.sendMessage(USER_MSG);
    awaitFinal(a.id());
    Message ctxMsg = A2A.createUserTextMessage("contextual", "ctx-1", null);
    Task b = service.sendMessage(ctxMsg);
    awaitFinal(b.id());
    Task c = service.sendMessage(USER_MSG);
    awaitFinal(c.id());

    // contextId 过滤（透传首条消息）
    ListTasksResult run =
        service.listTasks(new ListTasksParams("ctx-1", null, 25, null, 0, null, false, null));
    assertThat(run.tasks()).extracting(Task::id).containsExactly(b.id()); // 倒序：最新在前
    assertThat(run.totalSize()).isEqualTo(1);

    // status 过滤
    ListTasksResult completed =
        service.listTasks(
            new ListTasksParams(
                null, TaskState.TASK_STATE_COMPLETED, 25, null, 0, null, false, null));
    assertThat(completed.tasks()).hasSize(3);

    // 分页：pageSize=1 → nextPageToken=1
    ListTasksResult page1 =
        service.listTasks(
            new ListTasksParams(
                null, TaskState.TASK_STATE_COMPLETED, 1, null, 0, null, false, null));
    assertThat(page1.tasks()).hasSize(1);
    assertThat(page1.pageSize()).isEqualTo(1);
    assertThat(page1.nextPageToken()).isEqualTo("1");
    ListTasksResult page2 =
        service.listTasks(
            new ListTasksParams(
                null,
                TaskState.TASK_STATE_COMPLETED,
                1,
                page1.nextPageToken(),
                0,
                null,
                false,
                null));
    assertThat(page2.tasks()).hasSize(1);
    assertThat(page2.nextPageToken()).isEqualTo("2");

    // 越界偏移 → 空页
    ListTasksResult beyond =
        service.listTasks(
            new ListTasksParams(
                null, TaskState.TASK_STATE_COMPLETED, 25, "99", 0, null, false, null));
    assertThat(beyond.tasks()).isEmpty();
    assertThat(beyond.totalSize()).isEqualTo(3);
  }

  @Test
  void listTasksRejectsBadPageToken() {
    service(happyPath());
    assertThatThrownBy(
            () ->
                service.listTasks(new ListTasksParams(null, null, 25, "abc", 0, null, false, null)))
        .isInstanceOf(org.a2aproject.sdk.spec.InvalidParamsError.class);
  }

  private static Message withTaskId(Message message, String taskId) {
    return new Message(
        message.role(),
        message.parts(),
        message.messageId(),
        message.contextId(),
        taskId,
        message.referenceTaskIds(),
        message.metadata(),
        message.extensions());
  }
}
