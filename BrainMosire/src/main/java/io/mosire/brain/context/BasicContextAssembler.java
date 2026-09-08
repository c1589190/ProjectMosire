package io.mosire.brain.context;

import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.ToolDef;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.brain.runtime.AgentConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * 基础实现：system = 行为提示 + 工具目录（一行一个名字+描述）；历史逐条原样；user 原样；工具定义照传。
 *
 * <p>刻意保持简单——这是 M1 骨架，四来源的完整组装（记忆/skills/配额预算提示）在 M3 扩展。
 */
public final class BasicContextAssembler implements ContextAssembler {

  @Override
  public LlmRequest buildRequest(
      AgentConfig config, String userMessage, List<LlmMessage> history, List<AgentTool> tools) {
    StringBuilder system = new StringBuilder(config.systemPrompt());
    system.append("\n\n你有以下工具可用（一律直接调用，不要凭空想象它们不存在）：\n");
    if (tools.isEmpty()) {
      system.append("（当前没有任何可用工具）\n");
    } else {
      for (AgentTool tool : tools) {
        system.append("- ").append(tool.name());
        if (!tool.description().isBlank()) {
          system.append(": ").append(tool.description());
        }
        system.append('\n');
      }
    }
    system.append("\n完成用户请求后，请用纯文本回复结果，不要虚构工具执行过程。");

    // 契约：[system] + history（逐条原样）+ [user]——history 原样回灌以保 prompt-cache 前缀稳定
    List<LlmMessage> messages = new ArrayList<>(history.size() + 2);
    messages.add(LlmMessage.system(system.toString()));
    messages.addAll(history);
    messages.add(LlmMessage.user(userMessage));
    List<ToolDef> toolDefs =
        tools.stream().map(t -> new ToolDef(t.name(), t.description(), t.jsonSchema())).toList();
    return new LlmRequest(messages, toolDefs);
  }
}
