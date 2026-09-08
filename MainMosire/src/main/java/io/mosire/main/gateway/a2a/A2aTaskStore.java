package io.mosire.main.gateway.a2a;

import java.util.List;
import java.util.Optional;
import org.a2aproject.sdk.spec.Task;

/**
 * A2A Task 快照存储（server-common {@code TaskStore} 的最小镜像；D6：不引 server-common，存储接口自实现）。
 *
 * <p>契约：{@link #put} 为覆盖式写入（调用方负责状态机校验），快照语义 = 完整 Task（history/status/artifacts 一体的最新状态）； 列表顺序 =
 * 任务创建序（ {@link #list} 倒序返回，最新在前）。 实现：{@link MemoryA2aTaskStore}（测试/进程内）与 {@link
 * EventStoreA2aTaskStore}（事件存储快照，P0 生产，计划 §5.2.3）。
 */
public interface A2aTaskStore {

  /** 覆盖式写入最新快照。 */
  void put(Task task);

  /** 按任务 id 读取。 */
  Optional<Task> get(String taskId);

  /** 任务列表（创建序倒序；{@code contextId} 空 = 全部否则精确过滤；{@code offset}/{@code limit} 为翻页游标）。 */
  List<Task> list(String contextId, int limit, int offset);
}
