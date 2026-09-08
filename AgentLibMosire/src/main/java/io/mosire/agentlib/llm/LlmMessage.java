package io.mosire.agentlib.llm;

import java.util.List;
import java.util.Objects;

/**
 * 一条 LLM 消息：role + 内容分片列表。
 *
 * <p>role 取 {@code system}/{@code user}/{@code assistant}/{@code tool} 四值（与主流 OpenAI 兼容 供应商一致）；除
 * {@link #ROLE_SYSTEM} 外的角色名不做强制校验，以便透传未见过的供应商扩展角色。
 */
public record LlmMessage(String role, List<ContentPart> content) {

  public static final String ROLE_SYSTEM = "system";
  public static final String ROLE_USER = "user";
  public static final String ROLE_ASSISTANT = "assistant";
  public static final String ROLE_TOOL = "tool";

  public LlmMessage {
    Objects.requireNonNull(role, "role");
    content = List.copyOf(content);
  }

  public static LlmMessage system(String text) {
    return new LlmMessage(ROLE_SYSTEM, List.of(new ContentPart.Text(text)));
  }

  public static LlmMessage user(String text) {
    return new LlmMessage(ROLE_USER, List.of(new ContentPart.Text(text)));
  }

  public static LlmMessage assistant(List<ContentPart> content) {
    return new LlmMessage(ROLE_ASSISTANT, List.copyOf(content));
  }

  public static LlmMessage tool(ContentPart.ToolResult result) {
    return new LlmMessage(ROLE_TOOL, List.of(result));
  }
}
