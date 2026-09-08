package io.mosire.brain.context;

import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.brain.runtime.AgentConfig;
import java.util.List;

/**
 * 上下文组装（四来源之首）：把"一次回合的输入"变成 LLM 请求的初始消息序列。
 *
 * <p>四来源顺序固定以保 prompt-cache 前缀稳定（计划 §4.2）：system（prompt + 工具目录 + 项目 上下文）→ 会话历史。记忆检索（M3）与 skills
 * 目录（M3）按固定槽位插入。
 */
public interface ContextAssembler {

  /**
   * 生成一回合的 LLM 请求（system + 历史 + 本轮 user 消息、工具定义）。
   *
   * <p>契约（管线依赖，二者缺一不可）：
   *
   * <ul>
   *   <li>返回消息序列的<b>第一条必须是 system 消息</b>——管线据此在回合结束时剥离 system 头再累积历史（若允许 system 出现在中间位置，历史
   *       与新输入的边界就不可判定）。
   *   <li>{@code history} 是上一回合累积的消息（不含 system 头），必须<b>原样插在 system 之后、本轮 user 之前</b>——顺序固定以保
   *       prompt-cache 前缀稳定（system 恒为前缀，历史只追加不改写）。
   * </ul>
   *
   * @param config Agent 配置（systemPrompt）
   * @param userMessage 本轮用户输入
   * @param history 上一回合累积的消息（不含 system 头），可为空列表（首轮）
   * @param tools 当前可用工具（渲染成目录 + 工具定义）
   */
  LlmRequest buildRequest(
      AgentConfig config, String userMessage, List<LlmMessage> history, List<AgentTool> tools);
}
