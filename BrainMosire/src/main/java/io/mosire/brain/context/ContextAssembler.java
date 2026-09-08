package io.mosire.brain.context;

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
   * 生成首轮 LLM 请求（system + user 消息、工具定义）；历史消息由管线随后追加。
   *
   * @param config Agent 配置（systemPrompt）
   * @param userMessage 本轮用户输入
   * @param tools 当前可用工具（渲染成目录 + 工具定义）
   */
  LlmRequest buildRequest(AgentConfig config, String userMessage, List<AgentTool> tools);
}
