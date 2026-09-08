package io.mosire.agentlib.mcp;

import io.mosire.agentlib.tool.ToolResult;

/**
 * mosire 间 MCP 私有错误码带内约定。
 *
 * <p>MCP 的 {@code CallToolResult} 本身只有 {@code isError} 布尔 + 文本内容，没有错误码字段。 为了让"brain 暴露工具给子
 * Agent"这条链路上保留 {@link ToolResult#code()}（权限拒绝、校验失败等有语义的码），本包在 {link
 * AgentToMcpServer#toCallToolResult} 编码、{@link McpToolSource#map} 解码，格式约定为：错误结果首行 {@code
 * [mosire:code=<code>]}，其余为消息体。 外部非 mosire 的 MCP server 不会带此标记，解码回退为通用码 {@code MCP_TOOL_ERROR}（幂等）。
 */
final class McpWireCode {

  /** 通用远程调用错误码：带内无码时的解码回退与 {@link McpToolSource} 远程异常映射共用。 */
  static final String MCP_TOOL_ERROR = "MCP_TOOL_ERROR";

  private static final String PREFIX = "[mosire:code=";
  private static final String SUFFIX = "]";

  private McpWireCode() {}

  /** 错误结果 → 带内文本（首行码标记 + 消息体），成功结果原样返回。 */
  static String encode(ToolResult result) {
    if (result.success()) {
      return result.message();
    }
    String code = result.code() == null ? "ERROR" : result.code();
    return PREFIX + code + SUFFIX + "\n" + result.message();
  }

  /**
   * 带内文本 → [code, message]；无标记或文本为空 → {@code MCP_TOOL_ERROR} + 原文。
   *
   * <p>错误码字段约定不支持换行（含换行会被截断，码的语义由工具侧自行约定）。
   */
  static String[] decode(String text, String fallbackMessage) {
    if (text != null && text.startsWith(PREFIX) && text.indexOf(SUFFIX) > PREFIX.length()) {
      int suffixAt = text.indexOf(SUFFIX);
      String code = text.substring(PREFIX.length(), suffixAt);
      String message = text.substring(suffixAt + 1).stripLeading();
      return new String[] {code, message.isEmpty() ? fallbackMessage : message};
    }
    return new String[] {MCP_TOOL_ERROR, text == null || text.isBlank() ? fallbackMessage : text};
  }
}
