package io.mosire.agentlib.llm;

import java.util.Map;
import java.util.Objects;

/**
 * LLM 消息内容分片——封闭代数类型。
 *
 * <p>与 LLM 供应商消息结构对齐的最小公倍数：文本、工具调用请求、工具调用结果（对模型而言 工具调用的发出与回填都是消息内容的一部分）。
 */
public sealed interface ContentPart
    permits ContentPart.Text, ContentPart.ToolCall, ContentPart.ToolResult {

  /** 纯文本分片。 */
  record Text(String text) implements ContentPart {
    public Text {
      Objects.requireNonNull(text, "text");
    }
  }

  /** 模型发起的工具调用请求；{@code arguments} 为 JSON 对象应反序列化成的 Map。 */
  record ToolCall(String id, String name, Map<String, Object> arguments) implements ContentPart {
    public ToolCall {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(arguments, "arguments");
      // 立即不可变快照；参数 Map 中不允许 null 值（与 MCP 适配层 Map.copyOf 语义一致）
      arguments = Map.copyOf(arguments);
    }
  }

  /** 工具执行结果回填；{@code content} 与 {@code error} 至多一个非空——有 {@code error} 时按失败回填。 */
  record ToolResult(String toolCallId, String name, String content, String error)
      implements ContentPart {
    public ToolResult {
      Objects.requireNonNull(toolCallId, "toolCallId");
      Objects.requireNonNull(name, "name");
      if ((content == null) == (error == null)) {
        throw new IllegalArgumentException("content 与 error 必须且只能有一个非空");
      }
    }

    public boolean isError() {
      return error != null;
    }
  }
}
