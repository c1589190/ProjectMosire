package io.mosire.agentlib.llm;

import java.util.List;
import java.util.Objects;

/** 一次 LLM 补全请求：消息序列 + 可选工具目录。 */
public record LlmRequest(List<LlmMessage> messages, List<ToolDef> tools) {

  public LlmRequest {
    Objects.requireNonNull(messages, "messages");
    messages = List.copyOf(messages);
    tools = List.copyOf(tools == null ? List.of() : tools);
  }

  public LlmRequest(List<LlmMessage> messages) {
    this(messages, List.of());
  }

  /** 工具目录为空的便捷工厂。 */
  public static LlmRequest ofMessages(List<LlmMessage> messages) {
    return new LlmRequest(messages, List.of());
  }
}
