package io.mosire.agentlib.runtime;

import java.util.Optional;

/**
 * <b>何时停</b>：骨架在<b>每个 LLM 往返开始前</b>、<b>每条工具调用分发前</b>各问一次的策略 SPI。
 *
 * <p><b>为什么"停不停"在宿主手里而不在骨架里</b>：轮数上限、时间预算、token 配额、外部取消——这些旋钮的语义全部属于宿主
 * （同一个循环在另一个程序里可能干脆没有预算概念）。骨架只负责<b>在正确的时刻问</b>，不负责理解答案。这也是它能被复用的原因： 换一个宿主只需换一个 lambda，循环一行不改。
 *
 * <p><b>为什么返回 {@link Optional} 而不是 boolean</b>：终止不只是"停"，还要说清"为什么停、附注是什么"（结果摘要与事件都要用）。 {@code
 * Optional.empty()} = 继续；{@code Optional.of(signal)} = 就地终止，骨架会立刻转去调用 {@link
 * TurnListener#onStop}，不再发请求、不再派发工具。
 *
 * <p><b>调用时机是契约</b>（不变量）：只在"每个往返开始前"（{@link #check}）与"每条工具调用分发前"（{@link
 * #checkToolCallBoundary}）这两个时刻调用，其它时刻不调。宿主据此在 <b>工具批中间</b>也能看到取消/超时——
 * 这是"下一个检查点就地终止"的实现落点。本接口实现必须是<b>无副作用</b>的纯判定：骨架可能在一次回合里问它很多次，问一次不该让状态变化。
 *
 * <p><b>为什么是两个判定点而不是一个</b>：这两种时刻在宿主语义里可以不同。宿主若在"工具批中间"也跑完整预算判定， 墙钟恰在批中途到期就会提前终止、把本批剩余工具调用留成<b>悬空
 * {@code tool_call}</b>（部分供应商会拒收下一回合请求）； 而取消本来就是"就地终止、允许悬空"的语义。缺省把两个点接成同一个判定（安全、简单），需要精确控制的宿主覆写
 * {@link #checkToolCallBoundary} 即可——Brain 就是这么做的（批内只查取消）。
 *
 * <p><b>线程安全</b>：与 {@link AgentLoop} 同口径——骨架本身不承诺并发，调用方串行使用；实现不必自行加锁。
 */
@FunctionalInterface
public interface StopPolicy {

  /**
   * 看一眼当前进度，决定要不要停。
   *
   * @param state 当前只读进度快照（非 null）
   * @return 停止信号；{@link Optional#empty()} 表示继续
   */
  Optional<StopSignal> check(LoopState state);

  /**
   * 工具批中间的分发前检查（每派发一条工具调用之前问一次）。
   *
   * <p>缺省与 {@link #check} 相同——对大多数宿主来说"两个时刻用同一套判定"就是想要的语义。需要更精确控制时覆写本方法，
   * 典型场景是"批内只查取消、预算类判定留到下一个往返开始前"（见接口注"为什么是两个判定点"）。
   *
   * @param state 当前只读进度快照（非 null；{@code rounds} 是已完成的往返数，{@code toolCalls} 已含本批中已派发的条数）
   * @return 停止信号；{@link Optional#empty()} 表示继续
   */
  default Optional<StopSignal> checkToolCallBoundary(LoopState state) {
    return check(state);
  }
}
