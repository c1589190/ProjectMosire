package io.mosire.agentlib.compact;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.ToolDef;
import java.util.List;
import java.util.Objects;

/**
 * 保守的字符级 token 估算器。
 *
 * <p>本类不使用供应商 tokenizer，也不引入第三方依赖。估算口径是：ASCII 字符约 4 个算 1 token（向上取整），每个非 ASCII 码点至少算 1
 * token。该口径刻意偏高，用于压缩触发与后校验；它不能替代供应商精确 tokenizer，但能让明显超限在发请求前被看见。
 *
 * <p>本类非 final，公开方法可覆写；宿主可以注入自己的实现，把 {@code DefaultContextCompactor} 的预算判断替换成更精确的估算。
 */
public class TokenEstimator {

  /** 保守估算器的便捷工厂。 */
  public static TokenEstimator conservative() {
    return new TokenEstimator();
  }

  /**
   * 估算一段文本。
   *
   * @param text 文本（非 null）
   * @return 估算 token（空串为 0）
   */
  public int estimateText(String text) {
    Objects.requireNonNull(text, "text");
    int asciiChars = 0;
    int nonAsciiCodePoints = 0;
    for (int offset = 0; offset < text.length(); ) {
      int codePoint = text.codePointAt(offset);
      if (codePoint <= 0x7F) {
        asciiChars++;
      } else {
        nonAsciiCodePoints++;
      }
      offset += Character.charCount(codePoint);
    }
    return (asciiChars + 3) / 4 + nonAsciiCodePoints;
  }

  /**
   * 估算一条消息：role、全部内容分片与思维链都计入。
   *
   * @param message 消息（非 null）
   * @return 估算 token
   */
  public int estimateMessage(LlmMessage message) {
    Objects.requireNonNull(message, "message");
    int tokens = estimateText(message.role());
    for (ContentPart part : message.content()) {
      tokens += estimatePart(part);
    }
    if (!message.reasoning().isEmpty()) {
      tokens += estimateText(message.reasoning());
    }
    return tokens;
  }

  /**
   * 估算一组消息。
   *
   * @param messages 消息列表（非 null）
   * @return 估算 token
   */
  public int estimateMessages(List<LlmMessage> messages) {
    Objects.requireNonNull(messages, "messages");
    int tokens = 0;
    for (LlmMessage message : messages) {
      tokens += estimateMessage(message);
    }
    return tokens;
  }

  /**
   * 估算完整请求：messages + tools（name/description/jsonSchema）。
   *
   * @param request 请求（非 null）
   * @return 估算 token
   */
  public int estimateRequest(LlmRequest request) {
    Objects.requireNonNull(request, "request");
    int tokens = estimateMessages(request.messages());
    for (ToolDef tool : request.tools()) {
      tokens += estimateTool(tool);
    }
    return tokens;
  }

  /** 估算单个工具定义。 */
  protected int estimateTool(ToolDef tool) {
    Objects.requireNonNull(tool, "tool");
    return estimateText(tool.name())
        + estimateText(tool.description())
        + estimateText(String.valueOf(tool.jsonSchema()));
  }

  /** 估算单个内容分片；图片只算引用描述，不计资产字节。 */
  protected int estimatePart(ContentPart part) {
    return switch (part) {
      case ContentPart.Text text -> estimateText(text.text());
      case ContentPart.ToolCall call ->
          estimateText(call.id())
              + estimateText(call.name())
              + estimateText(String.valueOf(call.arguments()));
      case ContentPart.ToolResult result ->
          estimateText(result.toolCallId())
              + estimateText(result.name())
              + (result.content() == null ? 0 : estimateText(result.content()))
              + (result.error() == null ? 0 : estimateText(result.error()));
      case ContentPart.Image image ->
          estimateText(image.mediaType()) + estimateText(image.assetId());
    };
  }
}
