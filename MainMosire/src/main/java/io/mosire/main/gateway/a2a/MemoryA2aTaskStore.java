package io.mosire.main.gateway.a2a;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.a2aproject.sdk.spec.Task;

/**
 * 内存实现（默认单进程形态与测试用）。
 *
 * <p>taskId 入队一次即保留（任务快照不消亡，与 A2A task 生命周期一致——最终态也可查询/订阅）。
 */
public final class MemoryA2aTaskStore implements A2aTaskStore {

  private final Object lock = new Object();

  /** 创建序 id 队列（倒序遍历 = 最新在前）。 */
  private final Deque<String> order = new ArrayDeque<>();

  private final Map<String, Task> tasks = new HashMap<>();

  @Override
  public void put(Task task) {
    synchronized (lock) {
      if (!tasks.containsKey(task.id())) {
        order.addLast(task.id());
      }
      tasks.put(task.id(), task);
    }
  }

  @Override
  public Optional<Task> get(String taskId) {
    synchronized (lock) {
      return Optional.ofNullable(tasks.get(taskId));
    }
  }

  @Override
  public List<Task> list(String contextId, int limit, int offset) {
    synchronized (lock) {
      List<Task> result = new ArrayList<>(Math.min(limit, order.size()));
      Iterator<String> it = order.descendingIterator();
      int skipped = 0;
      while (it.hasNext()) {
        Task task = tasks.get(it.next());
        if (contextId != null
            && !contextId.isBlank()
            && !Objects.equals(contextId, task.contextId())) {
          continue;
        }
        if (skipped++ < offset) {
          continue;
        }
        if (result.size() >= limit) {
          break;
        }
        result.add(task);
      }
      return List.copyOf(result);
    }
  }
}
