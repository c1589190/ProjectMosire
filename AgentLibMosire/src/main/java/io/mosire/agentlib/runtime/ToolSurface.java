package io.mosire.agentlib.runtime;

import io.mosire.agentlib.tool.AgentTool;
import java.util.List;
import java.util.Objects;

/**
 * 一次回合里<b>逐轮可重新求值</b>的工具面。
 *
 * <p><b>为什么需要它</b>：工具定义本来就是每次 LLM 请求独立发送的（{@code LlmRequest.tools}），不是 system 提示词的一部分。旧 {@link
 * AgentLoop#runTurn(java.util.List, java.util.List)} 把工具列表固定成整回合一份，只是因为历史实现这样传参，不是协议限制。 本接口把“当前工具面
 * + 单调 revision”交给骨架，骨架每轮取一次。
 *
 * <p><b>契约</b>：
 *
 * <ul>
 *   <li>{@link #revision()} 单调不减；同一 revision 下 {@link #tools()} 应返回语义相同的一份工具面；
 *   <li>{@link #tools()} 非 null、不含 null 元素；骨架每轮会做不可变快照；
 *   <li>实现可缓存，但缓存失效必须由 revision 驱动——不要用“某一刻的快照”冒充动态面。
 * </ul>
 */
public interface ToolSurface {

  /** 当前工具面版本（单调不减）。 */
  long revision();

  /** 当前工具面（非 null；调用方按快照使用）。 */
  List<AgentTool> tools();

  /** 固定工具面（旧行为兼容）：revision 恒为 0。 */
  static ToolSurface fixed(List<AgentTool> tools) {
    return fixed(0L, tools);
  }

  /** 固定工具面 + 指定 revision（测试/一次性宿主）。 */
  static ToolSurface fixed(long revision, List<AgentTool> tools) {
    List<AgentTool> snapshot = List.copyOf(Objects.requireNonNull(tools, "tools"));
    for (AgentTool tool : snapshot) {
      Objects.requireNonNull(tool, "tools 不得含 null");
    }
    return new ToolSurface() {
      @Override
      public long revision() {
        return revision;
      }

      @Override
      public List<AgentTool> tools() {
        return List.copyOf(snapshot);
      }
    };
  }
}
