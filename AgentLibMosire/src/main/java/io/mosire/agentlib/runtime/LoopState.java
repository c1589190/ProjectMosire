package io.mosire.agentlib.runtime;

/**
 * 骨架调用宿主策略时给出的<b>只读进度快照</b>：回合已跑几轮、派发过几条工具调用、从回合开始到现在共耗时多少。
 *
 * <p><b>为什么是三值一条 record，而不是三个回调参数</b>：三个数描述的是<b>同一时刻</b>的同一次判定所需的事实。拆成三个参数，
 * "策略比较的是不是同一时刻的数"就变成调用方的责任；合成一条后，宿主写 {@code state.rounds() >= maxTurns} 这类判定时读到的永远是
 * 同一个瞬间。骨架只保证这份快照是只读的、单调的（回合内只增不减），不替宿主解释任何一项。
 *
 * <p><b>为什么三值非负要在构造期校验</b>：负数只可能来自调用方的记账 bug（例如拿两个时钟差相减时搞反方向）。而终止策略恰恰是 "防跑飞"的最后一道判定——把一个负的轮数喂进
 * {@code >= maxTurns} 会让上限<b>静默失效</b>；这里选 fail-closed：构造即抛 {@link IllegalArgumentException}，让 bug
 * 在第一次判定时就响亮出现，而不是在长跑里变成"上限怎么没生效"。
 *
 * @param rounds 本回合已完成的 LLM 往返轮数（从 0 起；LLM 调用失败的那一轮不计数）
 * @param toolCalls 本回合已<b>派发</b>的工具调用条数（从 0 起；只数真正交给 {@link ToolCallDispatcher} 的， 不含超限整批未执行的占位结果）
 * @param elapsedMillis 本回合从开始到这一刻的墙钟毫秒（≥0）。它只是读数——骨架不用它做任何判定，终止判定归 {@link StopPolicy}
 */
public record LoopState(int rounds, int toolCalls, long elapsedMillis) {

  public LoopState {
    if (rounds < 0) {
      throw new IllegalArgumentException("rounds 不能为负: " + rounds);
    }
    if (toolCalls < 0) {
      throw new IllegalArgumentException("toolCalls 不能为负: " + toolCalls);
    }
    if (elapsedMillis < 0) {
      throw new IllegalArgumentException("elapsedMillis 不能为负: " + elapsedMillis);
    }
  }
}
