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

  /** 按序号精确查找。 */
  Optional<Event> bySeq(long seq);

  /** 总条数。 */
  long count();

  @Override
  void close();
}
