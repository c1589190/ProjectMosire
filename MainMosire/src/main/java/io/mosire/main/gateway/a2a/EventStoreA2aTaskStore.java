package io.mosire.main.gateway.a2a;

import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.EventWrite;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Task;

/**
 * 事件存储快照实现（计划 §5.2.3：状态存储 = EventStore（Task 快照））。
 *
 * <p>每次 {@link #put} 追加一条 {@code a2a.task.snapshot} 事件（agent={@code
 * a2a}、correlationId=taskId——Event 的 vocabulary 约定即 A2A task id 走 correlationId）； {@link #get}
 * 取该任务最新一条；{@link #list} 经 {@code queryLatestByCorrelation} 取每种 correlationId 的最新快照
 * （R9：不再全量拉取逐条反序列化）。
 *
 * <p>Task 序列化用官方 {@link JsonUtil}（Gson + Part/A2AError/OffsetDateTime 适配器，与官方客户端互认）。
 */
public final class EventStoreA2aTaskStore implements A2aTaskStore {

  /** 磁盘上快照事件类型（事件类型词汇表由 Brain 的 {@code EventTypes} 扩展；A2A 在自己命名空间）。 */
  public static final String SNAPSHOT_TYPE = "a2a.task.snapshot";

  private static final String AGENT_NAME = "a2a";

  private final EventStore events;

  public EventStoreA2aTaskStore(EventStore events) {
    this.events = Objects.requireNonNull(events, "events");
  }

  @Override
  public void put(Task task) {
    String payload;
    try {
      payload = JsonUtil.toJson(task);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("序列化 Task 快照失败: " + task.id(), e);
    }
    events.append(EventWrite.of(SNAPSHOT_TYPE, AGENT_NAME, payload, task.id()));
  }

  @Override
  public Optional<Task> get(String taskId) {
    List<Event> snapshot = events.query(new EventQuery(AGENT_NAME, SNAPSHOT_TYPE, taskId, -1, 1));
    return snapshot.isEmpty() ? Optional.empty() : Optional.of(parse(snapshot.get(0)));
  }

  @Override
  public List<Task> list(String contextId, int limit, int offset) {
    // R9：SQL 侧 GROUP BY correlation_id 取 MAX(seq)，每种 correlationId 恰好一条（= 该任务最新快照），
    // 结果按 seq 倒序与原有"全量倒序取首次出现"语义一致；correlationId 空的行跳过（异常写入防御）
    List<Event> snapshots = events.queryLatestByCorrelation(SNAPSHOT_TYPE, Integer.MAX_VALUE, 0);
    List<Task> all = new ArrayList<>(snapshots.size());
    for (Event event : snapshots) {
      if (!event.correlationId().isBlank()) {
        all.add(parse(event));
      }
    }
    List<Task> filtered = new ArrayList<>(all.size());
    for (Task task : all) {
      if (contextId != null
          && !contextId.isBlank()
          && !Objects.equals(contextId, task.contextId())) {
        continue;
      }
      filtered.add(task);
    }
    int from = Math.min(Math.max(offset, 0), filtered.size());
    int to = Math.min(from + Math.max(limit, 1), filtered.size());
    return List.copyOf(filtered.subList(from, to));
  }

  private static Task parse(Event event) {
    try {
      return JsonUtil.fromJson(event.payload(), Task.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("解析 Task 快照失败 seq=" + event.seq(), e);
    }
  }
}
