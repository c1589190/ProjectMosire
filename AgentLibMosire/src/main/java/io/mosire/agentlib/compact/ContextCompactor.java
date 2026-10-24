package io.mosire.agentlib.compact;

import io.mosire.agentlib.context.ContextComposer;
import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.tool.AgentTool;
import java.util.List;

/**
 * 每轮 LLM 往返前的上下文压缩 SPI。
 *
 * <p>实现必须在提交任何窗口改动前完成全部拒绝判定；失败应抛 {@link io.mosire.agentlib.llm.LlmException}（默认实现抛 {@link
 * CompactionException}），由 {@link io.mosire.agentlib.runtime.AgentLoop} 交给宿主的错误出口，绝不能静默改用未压缩请求。
 *
 * <p>{@link #compactIfNeeded} 返回的 {@code turnInput} 是接下来真正要写回的输入。压缩器若已把当前 user 放进窗口，必须返回空列表，
 * 避免往返层重复写回。
 */
public interface ContextCompactor {

  /**
   * 每次 LLM 往返前调用。
   *
   * @param window 当前上下文窗口（压缩提交时直接改它）
   * @param turnInput 本轮相对窗口新增的输入
   * @param tools 本轮工具目录
   * @param composer 宿主请求编排器（用于估算完整请求与后校验）
   * @return 本次检查结果（非 null）
   */
  CompactionOutcome compactIfNeeded(
      ContextWindow window,
      List<LlmMessage> turnInput,
      List<AgentTool> tools,
      ContextComposer composer);

  /** 手动请求：下一次 {@link #compactIfNeeded} 强制触发。缺省无操作。 */
  default void requestCompaction() {}
}
