package io.mosire.main.gateway.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.event.SqliteEventStore;
import java.nio.file.Path;
import java.util.List;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** EventStore 快照实现：put=追加快照事件、get=最新、list=按任务去重且最新在前。 */
class EventStoreA2aTaskStoreTest {

  private static final org.a2aproject.sdk.spec.Message MSG = A2A.toUserMessage("hello");

  @TempDir Path dir;

  @Test
  void getReturnsLatestSnapshotOfTask() {
    try (SqliteEventStore events = SqliteEventStore.open(dir.resolve("t1.db"))) {
      EventStoreA2aTaskStore store = new EventStoreA2aTaskStore(events);
      String taskId = "task-1";
      store.put(task(taskId, "ctx-1", TaskState.TASK_STATE_SUBMITTED));
      store.put(task(taskId, "ctx-1", TaskState.TASK_STATE_WORKING));
      store.put(task(taskId, "ctx-1", TaskState.TASK_STATE_COMPLETED));

      assertThat(store.get(taskId).orElseThrow().status().state())
          .isEqualTo(TaskState.TASK_STATE_COMPLETED);
    }
  }

  @Test
  void listDedupesByTaskLatestFirstAndFiltersContext() {
    try (SqliteEventStore events = SqliteEventStore.open(dir.resolve("t2.db"))) {
      EventStoreA2aTaskStore store = new EventStoreA2aTaskStore(events);
      store.put(task("a", "ctx-1", TaskState.TASK_STATE_SUBMITTED));
      store.put(task("a", "ctx-1", TaskState.TASK_STATE_WORKING));
      store.put(task("b", "ctx-2", TaskState.TASK_STATE_SUBMITTED));

      List<Task> all = store.list(null, Integer.MAX_VALUE, 0);
      assertThat(all).extracting(Task::id).containsExactly("b", "a");
      assertThat(all)
          .extracting(t -> t.status().state())
          .containsExactly(TaskState.TASK_STATE_SUBMITTED, TaskState.TASK_STATE_WORKING);

      List<Task> ctx1 = store.list("ctx-1", Integer.MAX_VALUE, 0);
      assertThat(ctx1).extracting(Task::id).containsExactly("a");
      assertThat(store.list("ctx-99", Integer.MAX_VALUE, 0)).isEmpty();
    }
  }

  @Test
  void listPaginatesOverDedupedTasks() {
    try (SqliteEventStore events = SqliteEventStore.open(dir.resolve("t3.db"))) {
      EventStoreA2aTaskStore store = new EventStoreA2aTaskStore(events);
      // 无上下文任务 contextId 为空字符串（SDK wire 惯例；spec Builder 断言非空）
      store.put(task("a", "", TaskState.TASK_STATE_SUBMITTED));
      store.put(task("b", "", TaskState.TASK_STATE_SUBMITTED));
      store.put(task("c", "", TaskState.TASK_STATE_SUBMITTED));

      List<Task> page1 = store.list(null, 1, 0);
      assertThat(page1).extracting(Task::id).containsExactly("c");
      List<Task> page2 = store.list(null, 1, 1);
      assertThat(page2).extracting(Task::id).containsExactly("b");
      List<Task> beyond = store.list(null, 1, 10);
      assertThat(beyond).isEmpty();
    }
  }

  private static Task task(String taskId, String contextId, TaskState state) {
    return Task.builder()
        .id(taskId)
        .contextId(contextId)
        .status(new TaskStatus(state))
        .history(List.of(MSG))
        .build();
  }
}
