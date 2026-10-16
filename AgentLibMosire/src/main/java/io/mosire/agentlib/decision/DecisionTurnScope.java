package io.mosire.agentlib.decision;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>「当前回合的待裁决队列」的取数口</b>（设计 §5 裁定 1+2 的装配缝）：管线在回合开始时把本轮的 {@link PendingDecisionQueue}
 * 绑到当前线程上，工具调用链（{@code ToolCallAuthorizer} 的 {@code Ask} 分支）在这里取到它。
 *
 * <p><b>为什么是线程作用域，而不是加参数</b>：登记发生在 {@code ToolCallAuthorizer} 内部，而那里的入参是 {@code (registry,
 * toolName, ToolContext)} —— {@link io.mosire.agentlib.tool.ToolContext} 与 {@link
 * io.mosire.agentlib.tool.AgentTool} 都是<b>冻结形状</b>（母稿 §5.8：只做新增、不改既有签名），本轮不允许往它们身上挂字段。
 * 于是"这一轮归谁"改走<b>线程作用域</b>这条既有缝：谁绑、谁清、谁读都只有一处。
 *
 * <p><b>跨线程的诚实边界</b>：作用域锚在<b>绑定它的那个线程</b>上。管线在自己的线程上绑定、在自己的收尾里清（同一个线程，因为回合是
 * 同步跑的），所以管线发出的工具调用看得见队列。若同一轮里有<b>别的线程</b>执行工具（MCP 桥线程、工具自起的线程），那边 <b>看不到</b>本轮的队列 ⇒
 * 它退回"没有绑定队列"的既有行为（{@code Ask} 走审批闸，见 {@code ToolCallAuthorizer} 的注释），
 * <b>不会静默丢调用</b>。要跨线程收集，由调用方显式把队列引用传过去（子 Agent 那条路的建议见 P1b 报告）。
 *
 * <p><b>为什么不是 {@link InheritableThreadLocal}</b>：继承作用域会让"某个工具起了一条后台线程"变成"那条线程悄悄继承了本轮的队列"
 * ——它是本轮的一次性登记口，不该活得比调用栈长。
 *
 * <p><b>为什么要按身份清（见 {@link #clear(PendingDecisionQueue)}）</b>：回合收尾清作用域，但收尾之后同一线程可能已经跑起了
 * <b>下一轮</b>（例如把回合排在同一个单线程执行器上连续跑）。无脑清会把下一轮的绑定一起清掉——那样下一轮的 {@code Ask} 就会
 * "没有队列"而走审批闸，表现为"有时进包、有时当场问人"这种最难查的间歇态。
 *
 * <p><b>不持有生命周期</b>：本类不建队列、不建包、不发事件；它只回答"这个线程现在属于哪一轮"。
 */
public final class DecisionTurnScope {

  private static final ThreadLocal<AtomicReference<PendingDecisionQueue>> CURRENT =
      new ThreadLocal<>();

  private DecisionTurnScope() {}

  /**
   * 绑定本轮的待裁决队列（管线在回合开始时调）。
   *
   * <p>返回<b>刚绑上的那一个</b>（而不是 void）：管线把返回值用作收尾清作用域的身份守卫（见 {@link
   * #clear(PendingDecisionQueue)}），免得它再回头读一次"当前是谁"——读回来的可能是别人刚换上去的。
   *
   * @param queue 本轮的队列（非 null）
   * @return 刚绑上的同一个队列
   */
  public static PendingDecisionQueue bind(PendingDecisionQueue queue) {
    if (queue == null) {
      throw new IllegalArgumentException("bind 的队列不得为 null（要解绑请用 clear）");
    }
    AtomicReference<PendingDecisionQueue> slot = CURRENT.get();
    if (slot == null) {
      slot = new AtomicReference<>();
      CURRENT.set(slot);
    }
    slot.set(queue);
    return queue;
  }

  /** 当前线程绑定的队列；没有绑定 ⇒ 空值 = "这一轮不收集待裁决项"（{@code Ask} 保持既有行为）。 */
  public static Optional<PendingDecisionQueue> current() {
    AtomicReference<PendingDecisionQueue> slot = CURRENT.get();
    return slot == null ? Optional.empty() : Optional.ofNullable(slot.get());
  }

  /**
   * 解绑：无绑定 ⇒ 空操作；有绑定 ⇒ 无论绑的是谁都解绑（收尾是"这一轮结束了"，而当前线程此刻如果有绑定，那<b>就是</b>这一轮的—— 同一实例同一时刻只跑一个回合，见 {@code
   * AgentPipeline} 的并发约定）。要按身份清的调用方走 {@link #clear(PendingDecisionQueue)}。
   */
  public static void clear() {
    AtomicReference<PendingDecisionQueue> slot = CURRENT.get();
    if (slot != null) {
      slot.set(null);
      // ★ 清到空就把 ThreadLocal 条目一并移除：留着"值是 null 的槽"会让下一次 clear(expected) 走
      //   compareAndSet(null, null) 而返回 false——与 javadoc 的"本来就没绑定也算清干净 ⇒ true"矛盾
      //   （RT 的判据核查发现，报告 §5-3）。
      CURRENT.remove();
    }
  }

  /**
   * 只清"就是我绑的那一个"（管线收尾用这个）：当前绑的是别的队列 ⇒ 什么都不做，绝不动别人的绑定。
   *
   * @param expected 自己 {@link #bind} 时拿到的那个队列；null ⇒ 退化为 {@link #clear()}
   * @return 真的清了（本来就没绑定也算清干净了）⇒ true；当前绑的是别的队列 ⇒ false（调用方据此知道下一轮已经开始了）
   */
  public static boolean clear(PendingDecisionQueue expected) {
    if (expected == null) {
      clear();
      return true;
    }
    AtomicReference<PendingDecisionQueue> slot = CURRENT.get();
    if (slot == null) {
      return true;
    }
    // CAS：先看 current == expected 再 set(null) 会误清别人刚换上去的绑定
    boolean cleared = slot.compareAndSet(expected, null);
    if (cleared) {
      // 同上：清干净就移除条目，保证"重复清"仍然回答 true（幂等语义与 javadoc 一致）
      CURRENT.remove();
    }
    return cleared;
  }
}
