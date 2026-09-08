package io.mosire.agentlib.event;

import java.util.List;
import java.util.Optional;

/**
 * 事件存储——Agent 世界事实的唯一持久化层（计划 D8）。
 *
 * <p>约定：{@link #append} 立即落库并返回带 seq 的完整 {@link Event}（因果：后续读一定看得到）； 实现必须是线程安全的（Brain 管线与网关并发写）。
 */
public interface EventStore extends AutoCloseable {

  /** 追加一条事件，返回分配了 seq/ts 的完整记录。 */
  Event append(EventWrite write);

  /** 按条件查询，seq 倒序、最多 {@code limit} 条。 */
  List<Event> query(EventQuery query);

  /**
   * 按 {@code correlationId} 分组取每组最新一条（组内 {@code seq} 最大），结果按 {@code seq} 倒序返回。
   *
   * <p>用途："每任务最新快照"类读取（如 A2A {@code tasks/list}）——SQL 侧经 {@code (type, correlation_id, seq)}
   * 索引直接取每组最大值行，避免全量拉取再逐条反序列化。
   *
   * <p>{@code limit}/{@code offset} 为分组后最新集合的分页游标（{@code offset} 为 0 起）。
   */
  List<Event> queryLatestByCorrelation(String type, int limit, int offset);

  /** 按序号精确查找。 */
  Optional<Event> bySeq(long seq);

  /** 总条数。 */
  long count();

  @Override
  void close();
}
