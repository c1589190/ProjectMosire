package io.mosire.agentlib.runtime;

import io.mosire.agentlib.llm.ContentPart;

/**
 * <b>工具怎么执行</b>：骨架把"模型要调一条工具"这件事原样交给宿主，<b>只交一次、按原序交</b>。
 *
 * <p><b>为什么执行在宿主而不在骨架</b>：工具调用前要过什么闸（权限判定、审批、沙箱）、执行后要发什么事件、结果怎么脱敏、怎么回写窗口
 * ——全是宿主的知识。骨架只认识"模型返回了一串工具调用"，不认识工具本身；把执行抽成这个 SPI 是"通用循环"与"某个 Agent 运行时" 之间的分界线。
 *
 * <p><b>为什么没有返回值</b>：骨架不解析工具结果，也不替宿主决定失败/超限的语义——工具结果（成功文本、错误码、占位失败）由宿主自己写回窗口。
 * 宿主实现里抛出的运行时异常会原样向上传播（与改造前一致：编程契约违例不该被循环吞掉）。
 *
 * <p><b>调用面是不变量</b>：一次响应里的工具调用按<b>模型给出的原序</b>逐条分派；每条分派前骨架都会问一次 {@link
 * StopPolicy#check}，被策略叫停时<b>尚未分派</b>的那条不会被执行、也不计入 {@link TurnResult#toolCalls()}。
 *
 * <p><b>线程安全</b>：与 {@link AgentLoop} 同口径——调用方串行。
 */
@FunctionalInterface
public interface ToolCallDispatcher {

  /**
   * 执行一条工具调用（判定/审批/沙箱/事件/结果写回都在实现里）。
   *
   * @param call 模型这次要调用的工具（非 null；id/name/arguments 原样来自响应）
   * @param state 分派这条调用时的进度快照（非 null；已含这条调用的计数）
   */
  void dispatch(ContentPart.ToolCall call, LoopState state);
}
