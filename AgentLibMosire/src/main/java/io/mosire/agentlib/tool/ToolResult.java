package io.mosire.agentlib.tool;

import java.util.List;
import java.util.Objects;

/**
 * 工具执行结果。
 *
 * <p>约定：{@code code == null} 为成功（消息前置于 LLM 的 "tool output"）；{@code code != null} 为失败（{@code code}
 * 为稳定错误标识，如 {@code PERMISSION_DENIED}/{@code TOOL_NOT_FOUND}）。 {@code assetDocIds}
 * 为资产引用：超出上下文预算的大结果（文档/日志）暂存为 docId，LLM 拿到的是 引用而非全文（计划报告 7.4 "结果溢出暂存 docId"）。
 */
public record ToolResult(String code, String message, List<String> assetDocIds) {

  public ToolResult {
    Objects.requireNonNull(message, "message");
    assetDocIds = assetDocIds == null ? List.of() : List.copyOf(assetDocIds);
  }

  public static ToolResult ok(String message) {
    return new ToolResult(null, message, List.of());
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
