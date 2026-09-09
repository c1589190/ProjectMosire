package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link ToolResultTruncator} 头尾截断行为的单元测试（纯字符串函数，无外部依赖）。 */
class ToolResultTruncatorTest {

  /** 短内容、恰好等于上限、空串、null：一律原样返回。 */
  @Test
  void shortOrNullContentPassesThroughUnchanged() {
    assertThat(ToolResultTruncator.truncate("hello", 100)).isEqualTo("hello");
    // 长度 ≤ 上限：不触发截断
    assertThat(ToolResultTruncator.truncate("x".repeat(50), 50)).isEqualTo("x".repeat(50));
    assertThat(ToolResultTruncator.truncate("", 100)).isEmpty();
    assertThat(ToolResultTruncator.truncate(null, 100)).isNull();
  }

  /** 超限：提示（含原始字符数/行数）+ 头部 + 省略标记 + 尾部，总长 ≤ 上限。 */
  @Test
  void overLimitKeepsHeadAndTailWithNotice() {
    // 10 行、每行 1000 字符，行间 9 个换行 = 10009 字符
    String content = buildLines(10, 1000);
    int maxChars = 500;
    String out = ToolResultTruncator.truncate(content, maxChars);

    assertThat(out.length()).isLessThanOrEqualTo(maxChars);
    assertThat(out).startsWith("Warning: 输出被截断（原始 10009 字符 / 10 行）");
    assertThat(out).contains("…（中段省略）…");
    assertThat(out).contains(content.substring(0, 100)); // 头部保留
    assertThat(out).endsWith(content.substring(content.length() - 100)); // 尾部保留
  }

  /** 上限过小（头尾结构放不下）：只返回提示，不带正文片段。 */
  @Test
  void tinyMaxCharsReturnsNoticeOnly() {
    String content = "y".repeat(9999) + "\n" + "z";
    assertThat(content).hasSize(10001);
    String out = ToolResultTruncator.truncate(content, 10);
    assertThat(out).isEqualTo("Warning: 输出被截断（原始 10001 字符 / 2 行）");
  }

  /** 上限连提示本身都容不下：仍返回提示（提示是不可省略的最小信息，允许单独超限）。 */
  @Test
  void noticeAloneExceedingMaxStillReturnsNotice() {
    String out = ToolResultTruncator.truncate("c".repeat(300), 5);
    assertThat(out).isEqualTo("Warning: 输出被截断（原始 300 字符 / 1 行）");
  }

  private static String buildLines(int lines, int charsPerLine) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines; i++) {
      if (i > 0) {
        sb.append('\n');
      }
      sb.append(String.valueOf(i).repeat(charsPerLine));
    }
    return sb.toString();
  }
}
