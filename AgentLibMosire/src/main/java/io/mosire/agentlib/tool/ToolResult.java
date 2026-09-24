package io.mosire.agentlib.tool;

import java.util.List;
import java.util.Objects;

/**
 * 工具执行结果。
 *
 * <p>约定：{@code code == null} 为成功（消息前置于 LLM 的 "tool output"）；{@code code != null} 为失败（{@code code}
 * 为稳定错误标识，如 {@code PERMISSION_DENIED}/{@code TOOL_NOT_FOUND}）。 {@code assetDocIds}
 * 为资产引用：超出上下文预算的大结果（文档/日志）暂存为 docId，LLM 拿到的是 引用而非全文（计划报告 7.4 "结果溢出暂存 docId"）。
 *
 * <p>★ <b>资产引用也用来带图片</b>（2026-09-24）：渲染出的图片（如"世界快照"）同样以 docId 引用进结果，由宿主经 {@code ToolAssetResolver}
 * 解析——MCP 面把 {@code image/*} 资产出成 {@code ImageContent}（见 {@code
 * AgentToMcpServer#toCallToolResult}），Brain/决策人面则把它作为图片分片附给模型（见 {@code
 * ContentPart.Image}）。**字节绝不放这里** （结果对象会被日志/事件/会话库反复搬运）。
 */
public record ToolResult(String code, String message, List<String> assetDocIds) {

  public ToolResult {
    Objects.requireNonNull(message, "message");
    assetDocIds = assetDocIds == null ? List.of() : List.copyOf(assetDocIds);
  }

  public static ToolResult ok(String message) {
    return new ToolResult(null, message, List.of());
  }

  /**
   * 成功 + 资产引用：{@code assetDocIds} 里的图片资产由宿主解析后随结果出站（语义见类注）。
   *
   * @param message 回灌给模型的文本（永远在；图片是<b>附加</b>而非替代）
   * @param assetDocIds 资产引用（可为空表；不得含 null）
   */
  public static ToolResult ok(String message, List<String> assetDocIds) {
    return new ToolResult(null, message, assetDocIds);
  }

  public static ToolResult error(String code, String message) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("失败结果的 code 不能为空");
    }
    return new ToolResult(code, message, List.of());
  }

  public boolean success() {
    return code == null;
  }
}
