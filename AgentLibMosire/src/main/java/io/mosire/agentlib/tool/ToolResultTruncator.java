package io.mosire.agentlib.tool;

/**
 * 工具输出的通用头尾截断器（Token 经济：工具结果污染治理）。
 *
 * <p>参考 Codex CLI 的两段式截断先例（Claude Code 同型做法）：超限的工具输出既不全丢也不全留，而是保留头部与尾部——
 * 开头交代任务与参数背景，结尾交代最终状态/错误信息，中段以省略标记替代；并在输出最前附一条截断提示（含原始字符数与
 * 行数），让模型明确知晓"当前看到的是残片"，可自行决定是否用更精确的手段重取（分页、grep、缩小范围等）。
 *
 * <p>纯字符串函数：不依赖 EventStore、不产生 docId（超限大结果的落库持久化属后续波次，见开发计划 P2-5 Bash）；
 * {@link ToolResult} 的 {@code assetDocIds} 语义不受本类影响。
 */
public final class ToolResultTruncator {

  /** 默认输出上限（字符数）：单次工具结果注入 LLM 上下文的预算基线。 */
  public static final int DEFAULT_MAX_CHARS = 8000;

  /** 中段省略标记（含前后换行，视为截断结构的一部分）。 */
  private static final String MIDDLE_MARKER = "\n…（中段省略）…\n";

  private ToolResultTruncator() {}

  /**
   * 按上限截断工具输出（头尾保留）。
   *
   * <p>规则：
   *
   * <ul>
   *   <li>{@code content} 为空或长度 ≤ {@code maxChars} → 原样返回；
   *   <li>超限 → 截断提示（含原始字符数/行数）+ 头部 + 省略标记 + 尾部，总长 ≤ {@code maxChars}；
   *   <li>{@code maxChars} 过小（提示之外连 1 字符头尾都放不下）→ 只返回提示（提示是不可省略的最小信息，允许单独
   *       超限——没有它调用方无法区分"完整输出"与"残片"）。
   * </ul>
   *
   * @param content 原始工具输出；可为 {@code null}（原样返回）
   * @param maxChars 输出上限（字符数，UTF-16 code unit 口径）
   * @return 截断后的文本
   */
  public static String truncate(String content, int maxChars) {
    if (content == null || content.length() <= maxChars) {
      return content;
    }
    long originalLines = content.lines().count();
    String notice = "Warning: 输出被截断（原始 " + content.length() + " 字符 / " + originalLines + " 行）";
    // 结构开销：提示 + 换行 + 省略标记（标记自带前后换行）
    int overhead = notice.length() + 1 + MIDDLE_MARKER.length();
    if (maxChars - overhead < 2) {
      // 至少 1 字符头 + 1 字符尾都放不下：只返回提示
      return notice;
    }
    int budget = maxChars - overhead;
    int headLen = budget / 2;
    int tailLen = budget - headLen;
    return notice
        + "\n"
        + content.substring(0, headLen)
        + MIDDLE_MARKER
        + content.substring(content.length() - tailLen);
  }
}
