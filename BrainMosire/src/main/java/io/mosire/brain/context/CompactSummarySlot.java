package io.mosire.brain.context;

import java.util.Objects;

/**
 * 当前生效的压缩摘要槽：{@link Compactor} 产出摘要后由 {@code AgentPipeline} 在<b>回合边界</b>写入，{@link
 * BasicContextAssembler} 每回合读取并渲染 {@link ContextLayer#COMPACT_SUMMARY} 层。
 *
 * <p><b>为什么需要这个槽（而不是把摘要塞进 {@link ContextSources}）</b>：{@code ContextSources} 是不可变记录，且它的规范构造器
 * 已被既有用例按五参形态使用（改元数会波及既有断言）。而摘要的<b>生灭时机</b>与技能目录/记忆检索不同——后者装配期固定，前者在一次 run() 内
 * 才产生。于是按"装配期注入一个活内容源"的同一思路，给装配器注入一个<b>可写的槽</b>：写者只有一个（管线），读者是装配器。
 *
 * <p><b>并发约定</b>：单写者（持有它的管线实例线程；{@code AgentPipeline} 本就以"任意时刻只跑一个回合"为调用约定）、多读者。 {@code volatile}
 * 保证跨线程可见性；本类不自造锁——它承载的是"最近一次压缩的产物"这一<b>单值</b>，不需要复合原子性。
 *
 * <p><b>生命周期（谁在什么时候写）</b>：
 *
 * <ul>
 *   <li>档 2（局部摘要）生效 → {@link #set(CompactSummary)}；
 *   <li>档 3（快照续接）落库成功 → {@link #clear()}：摘要已进入会话（{@code ConversationStore.load} 的首条 {@code
 *       assistant} 消息），再留在层里就是同一份内容出现两遍；
 *   <li>档 1（micro）→ <b>不动</b>：档 1 的披露靠占位消息，而槽里可能还挂着更早一次档 2 的摘要——那份摘要描述的历史依然成立，清掉就是丢信息。
 * </ul>
 */
public final class CompactSummarySlot {

  private volatile CompactSummary current;

  private CompactSummarySlot() {
    // 只能由 empty() 构造：槽的初值恒为"无摘要"
  }

  /** 空槽：未发生任何压缩（既有装配路径的取法——层内容为空 → 不产出段落，byte-identical 不变量由此成立）。 */
  public static CompactSummarySlot empty() {
    return new CompactSummarySlot();
  }

  /** 当前生效的摘要；{@code null} = 无（层不产出段落）。 */
  public CompactSummary current() {
    return current;
  }

  /** 写入最近一次压缩的摘要（{@code null} 不接受——"清空"是 {@link #clear()} 的语义）。 */
  public void set(CompactSummary summary) {
    this.current = Objects.requireNonNull(summary, "summary");
  }

  /** 清空（档 3 落库成功后：摘要已转入会话本身）。 */
  public void clear() {
    this.current = null;
  }
}
