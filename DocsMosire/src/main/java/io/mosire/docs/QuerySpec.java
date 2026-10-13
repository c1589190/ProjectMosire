package io.mosire.docs;

import java.util.Optional;

/**
 * {@code LOG} 纪律的查询条件（设计 §5.5）：{@code tick} 精确 与 {@code [fromTick, toTick]}
 * 闭区间<b>互斥</b>，且互斥性在<b>构造期</b> 具名拒（不留给执行期"两个都给所以随便挑一个"）。
 *
 * <p><b>排序与截断的顺序写死在契约里</b>：先按（{@code tick} 降序，同 tick 按 {@code (地址, 序号数值)} 升序）<b>排序</b>，再按 {@link
 * #limit()} <b>截断</b>。反过来（先截断再排序）会让"最近 10 条"变成"随便 10 条里的最近"，而两种实现的差别只在数据量上显现——最难查的一类缺陷。
 *
 * <p><b>{@code limit} 必填且 &gt; 0</b>：没有"不限量"这个取值——一次把整个日志捞进上下文既烧 token 又没有任何语义价值（要看全量请分页，或读全）。
 *
 * @param tick 精确时刻（与 {@code fromTick}/{@code toTick} 互斥）
 * @param fromTick 闭区间下界（可空）
 * @param toTick 闭区间上界（可空）
 * @param limit 返回条数上限（必填，&gt; 0）
 */
public record QuerySpec(
    Optional<Long> tick, Optional<Long> fromTick, Optional<Long> toTick, int limit) {

  public QuerySpec {
    tick = tick == null ? Optional.empty() : tick;
    fromTick = fromTick == null ? Optional.empty() : fromTick;
    toTick = toTick == null ? Optional.empty() : toTick;
    boolean hasRange = fromTick.isPresent() || toTick.isPresent();
    if (tick.isPresent() && hasRange) {
      throw DocException.invalid("查询条件互斥：tick（精确时刻）与 [fromTick, toTick]（闭区间）只能给一个");
    }
    tick.ifPresent(
        value -> {
          if (value < 0) {
            throw DocException.invalid("tick 不能为负: " + value);
          }
        });
    fromTick.ifPresent(
        value -> {
          if (value < 0) {
            throw DocException.invalid("fromTick 不能为负: " + value);
          }
        });
    toTick.ifPresent(
        value -> {
          if (value < 0) {
            throw DocException.invalid("toTick 不能为负: " + value);
          }
        });
    if (fromTick.isPresent() && toTick.isPresent() && fromTick.get() > toTick.get()) {
      // 反向区间是"写错了"，不是"空结果"：静默返回空会让人以为文档真的没有
      throw DocException.invalid("闭区间下界大于上界（写反了）: [" + fromTick.get() + ", " + toTick.get() + "]");
    }
    if (limit <= 0) {
      throw DocException.invalid("limit 必填且必须大于 0（本契约没有\"不限量\"这个取值）: " + limit);
    }
  }

  /** 精确按时刻查。 */
  public static QuerySpec atTick(long tick, int limit) {
    return new QuerySpec(Optional.of(tick), Optional.empty(), Optional.empty(), limit);
  }

  /** 按闭区间查（两端都可空：只给一侧时该侧不设限）。 */
  public static QuerySpec range(Long fromTick, Long toTick, int limit) {
    return new QuerySpec(
        Optional.empty(), Optional.ofNullable(fromTick), Optional.ofNullable(toTick), limit);
  }

  /** 不带时刻条件的查询（按 limit 取最新的若干条）。 */
  public static QuerySpec latest(int limit) {
    return new QuerySpec(Optional.empty(), Optional.empty(), Optional.empty(), limit);
  }

  /** 某条目的 tick 是否命中本条件（排序前的过滤；区间为闭区间）。 */
  boolean matches(long candidate) {
    if (tick.isPresent()) {
      return tick.get() == candidate;
    }
    if (fromTick.isPresent() && candidate < fromTick.get()) {
      return false;
    }
    return toTick.isEmpty() || candidate <= toTick.get();
  }
}
