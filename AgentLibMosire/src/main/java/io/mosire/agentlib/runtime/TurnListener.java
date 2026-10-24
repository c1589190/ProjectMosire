package io.mosire.agentlib.runtime;

import io.mosire.agentlib.compact.CompactionOutcome;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmResponse;
import java.util.List;
import java.util.Optional;

/**
 * <b>每轮的通知与收尾点</b>：骨架在固定时刻回调宿主，让宿主完成记账、事件、落库与占位修复——骨架自己<b>不直接改窗口、不发事件、 不落库</b>（见 {@link AgentLoop}
 * 类注"骨架不做的事"）。
 *
 * <p><b>为什么全部是 default</b>：不同宿主关心的回调不同（只跑一次性脚本的宿主可能只想要 {@link #onFinished}；完整 Agent 宿主七种终止 原因全要）。全
 * default 让"按需覆写"成为缺省，宿主不必为一个空实现写七行样板。
 *
 * <p><b>★ 恰好一次契约（exactly-once）</b>：一次 {@link AgentLoop#runTurn} 调用必定且只触发以下四个<b>终止回调</b>之一—— {@link
 * #onFinished} / {@link #onToolCallLimit} / {@link #onStop} / {@link #onLlmError}，退出后不再回调。宿主据此把 "落库
 * / 落事件"统一放在这四个回调里，而不是放在 {@code runTurn} 返回之后再补——后者会漏掉"异常在 return 前发生"的时序，
 * 而且"结果已经返回、历史还没保存"这个窗口本身就是失忆 bug 的形状。
 *
 * <p><b>{@link #onRoundTrip} / {@link #onCompacted} 不是终止回调</b>：{@code onRoundTrip} 每次成功往返恰好一次；
 * {@code onCompacted} 每次成功提交压缩恰好一次（可能 0 次，也可能跨多轮工具循环出现多次）。{@code onRoundTrip} 可以返回终止信号（见下），
 * 返回信号时骨架会先转去 {@link #onStop}，所以一次回合内的 {@code onRoundTrip} 次数可以多于 {@code onStop} 次数。
 *
 * <p><b>回调里允许做什么</b>：记账、发事件、写窗口（工具结果/占位）、落库、记录指标。不允许：重新发起 LLM 调用、修改骨架参数、
 * 抛异常当控制流用——回调抛出运行时异常会原样向上传播（骨架不吞宿主 bug）。
 *
 * <p><b>线程安全</b>：与 {@link AgentLoop} 同口径——调用方串行，回调在调用线程上同步执行。
 */
public interface TurnListener {

  /**
   * 一次<b>成功</b>的 LLM 往返之后、解析工具调用之前。宿主在这里先记账（如 {@code llm.call}）再做业务判定（如 token 配额）。
   *
   * <p><b>为什么会返回终止信号</b>：有些终止原因只能在这一刻判定——典型是 token 配额：它要等这次响应的 token 数到账才判得了，而
   * "这次调用确实发生了"的记账又必须<b>先落</b>（否则超限终止会丢失成本事实）。于是这里允许"记完账再返回 signal"， 骨架收到后立刻走 {@link #onStop}
   * 出口：本回合不再发请求、不再派发工具。
   *
   * @param response 本次响应（非 null；窗口已含本轮输入与答复——写回是往返的一部分）
   * @param latencyMs 本次往返的墙钟耗时毫秒（≥0）
   * @param state 本轮回合计数已加一后的进度快照
   * @return 终止信号；{@link Optional#empty()} 表示继续
   */
  default Optional<StopSignal> onRoundTrip(LlmResponse response, long latencyMs, LoopState state) {
    return Optional.empty();
  }

  /**
   * 上下文压缩器成功提交一次压缩之后、主 LLM 请求发出之前调用。
   *
   * <p>宿主在这里落压缩摘要调用成本（{@code outcome.summarizerResponse()}）与压缩事件；<b>不要把摘要正文写进事件或日志</b>，它已经在窗口 raw
   * 的历史里。本回调不是终止回调，抛出运行时异常会原样向上传播。
   *
   * @param outcome 压缩结果（非 null；{@code compacted=true}，turnInput 为空）
   * @param state 压缩提交后的回合进度快照（主请求尚未发出、轮数未增加）
   */
  default void onCompacted(CompactionOutcome outcome, LoopState state) {}

  /**
   * 模型没有工具调用 ⇒ 本回合正常结束。宿主通常在这里落库（{@code saveHistory} 一类的动作）。
   *
   * @param finalText 最终纯文本（模型答复里的第一条文本；没有文本时为空串——回调必然非 null）
   * @param state 终止时刻的进度快照
   */
  default void onFinished(String finalText, LoopState state) {}

  /**
   * 单次响应里的工具调用数超过骨架硬顶（{@code AgentLoop.Builder#maxToolCallsPerResponse}）⇒ <b>整批不执行</b>。
   *
   * <p>宿主负责补占位失败结果（否则历史里会留下无应答的 {@code tool_call}，部分供应商下一轮会拒收请求）、落决策事件、落库。 骨架只上报这批调用与进度，不解释"超限"的语义。
   *
   * @param calls 这次响应里的全部工具调用（按原序；非 null、非空）
   * @param state 终止时刻的进度快照（{@code toolCalls} <b>不含</b>这批未执行的调用）
   */
  default void onToolCallLimit(List<ContentPart.ToolCall> calls, LoopState state) {}

  /**
   * 因 {@link StopPolicy} 或 {@link #onRoundTrip} 返回的信号而终止。宿主负责落决策事件与落库（包括 {@link
   * StopSignal#detail()} 的宿主语义映射）。
   *
   * <p><b>注意它和另外三个终止回调互斥</b>：一次回合只会有其中之一被调用（见类注的 exactly-once 契约）。
   *
   * @param signal 终止信号（非 null；就是策略返回的那一个实例/值，库未做改写）
   * @param state 终止时刻的进度快照
   */
  default void onStop(StopSignal signal, LoopState state) {}

  /**
   * 上下文压缩器或 {@code LlmRoundTrip#call} 抛 {@link LlmException}：本回合以 {@link StopReason#LLM_ERROR} 收尾。
   *
   * <p><b>{@code roundInput} 为什么交回给宿主</b>：压缩器按契约在提交前失败时窗口<b>一个字节都没动</b>；往返失败时窗口同样未写回（写回只在 chat
   * 成功后发生）。所以这次失败的往返 "原本要写回的本轮输入"还在骨架手里。失败的输入算不算历史是<b>宿主的失败策略</b>：完整 Agent 宿主通常希望下一轮还记得用户说过什么 （于是把
   * {@code roundInput} append 回窗口再落库）；一次性脚本宿主可能选择丢弃。库不替宿主决定，只把原样交回。
   *
   * @param error 失败原因（非 null；可能是 {@link io.mosire.agentlib.compact.CompactionException}）
   * @param roundInput 这次失败的往返原本要写回的本轮输入（非 null；首轮非空、此后各轮为空列表；压缩已提交时为空）
   * @param state 失败时刻的进度快照（<b>{@code rounds} 未加一</b>——这次往返没有完成）
   */
  default void onLlmError(LlmException error, List<LlmMessage> roundInput, LoopState state) {}
}
