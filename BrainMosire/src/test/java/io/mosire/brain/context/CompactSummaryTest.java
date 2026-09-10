package io.mosire.brain.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link CompactSummary} 的解析与渲染（T17 档 2 的产物形态）。
 *
 * <p>为什么单独立类：这个类的两条契约都被<b>跨进程</b>依赖——{@code render()} 的字节同时是 COMPACT_SUMMARY 层的正文与档 3 落库的摘要 （重启后
 * {@code load} 回来的那份），{@code parse()} 则要顶住 LLM 的形态偏差。它们与压缩阶梯的决策逻辑无关，混在 {@code CompactorTest}
 * 里会让"解析容错"的边界被阶梯用例淹没。
 *
 * <p>渲染格式在此逐字节 pin：改了就红（它是落库字节与请求字节的共同来源）。
 */
class CompactSummaryTest {

  /** 八字段齐全的回复（哨兵取值各不相同——字段接错键位会立刻暴露）。 */
  private static final String FULL_JSON =
      """
      {"objective":"目标哨兵","completed":["完成甲","完成乙"],"pending":["待办哨兵"],
       "decisions":["决策哨兵"],"changedFiles":["文件哨兵"],"errors":["错误哨兵"],
       "activeSkills":["技能哨兵"],"unresolved":["未决哨兵"]}
      """;

  @Test
  void parseMapsEveryFieldToItsOwnKey() {
    CompactSummary summary = CompactSummary.parse(FULL_JSON);

    assertThat(summary).isNotNull();
    assertThat(summary.objective()).isEqualTo("目标哨兵");
    assertThat(summary.completed()).containsExactly("完成甲", "完成乙");
    assertThat(summary.pending()).containsExactly("待办哨兵");
    assertThat(summary.decisions()).containsExactly("决策哨兵");
    assertThat(summary.changedFiles()).containsExactly("文件哨兵");
    assertThat(summary.errors()).containsExactly("错误哨兵");
    assertThat(summary.activeSkills()).containsExactly("技能哨兵");
    assertThat(summary.unresolved()).containsExactly("未决哨兵");
    assertThat(summary.isEmpty()).isFalse();
  }

  /** LLM 常见偏差之一：整段包在 {@code ```json} 围栏里。容错只认形态，不猜语义。 */
  @Test
  void parseAcceptsCodeFencedReplies() {
    assertThat(CompactSummary.parse("```json\n" + FULL_JSON + "\n```"))
        .isEqualTo(CompactSummary.parse(FULL_JSON));
  }

  /** LLM 常见偏差之二：单值字段给字符串而不是单元素数组。 */
  @Test
  void parseAcceptsASingleStringInPlaceOfAOneElementArray() {
    CompactSummary summary =
        CompactSummary.parse("{\"objective\":\"目标\",\"completed\":\"完成了一件事\"}");

    assertThat(summary).isNotNull();
    assertThat(summary.completed()).containsExactly("完成了一件事");
  }

  /**
   * 非文本元素<b>丢弃</b>而不是字符串化：把 {@code 42} 变成 {@code "42"}（或把对象变成 {@code "{a=1}"}）是往摘要里塞编造内容，
   * 而摘要里出现编造内容比缺一条更糟。数组里的空白字符串同样不算一条。
   */
  @Test
  void parseDropsNonTextArrayElementsInsteadOfStringifyingThem() {
    CompactSummary summary =
        CompactSummary.parse(
            "{\"objective\":\"目标\",\"completed\":[\"真的\",42,{\"a\":1},null,\"  \"]}");

    assertThat(summary).isNotNull();
    assertThat(summary.completed()).containsExactly("真的");
  }

  /** 键缺失/类型不符 → 该字段为空列表（不编造），其余字段照常解析。 */
  @Test
  void parseTreatsMissingOrMistypedFieldsAsEmptyRatherThanGuessing() {
    CompactSummary summary =
        CompactSummary.parse("{\"objective\":\"目标\",\"pending\":{\"nested\":\"对象不是数组\"}}");

    assertThat(summary).isNotNull();
    assertThat(summary.objective()).isEqualTo("目标");
    assertThat(summary.pending()).isEmpty();
    assertThat(summary.completed()).isEmpty();
  }

  /**
   * "这次摘要不可用"的三种形态必须同归 {@code null}：空文本 / 不是 JSON 对象 / 八字段全空。
   *
   * <p>判别性：一个"总返回非 null"的实现（例如把解析失败变成空摘要）在这里必红——调用方正是靠 {@code null} 决定降级为档 1 的披露式丢弃，
   * 返回空摘要会被当成"摘要成功但没内容"，压缩后请求里既没有摘要也没有占位标记，被压缩的历史就<b>静默</b>消失了。
   */
  @Test
  void parseReturnsNullWhenThisSummaryIsUnusable() {
    assertThat(CompactSummary.parse(null)).isNull();
    assertThat(CompactSummary.parse("")).isNull();
    assertThat(CompactSummary.parse("   \n  ")).isNull();
    assertThat(CompactSummary.parse("这不是 JSON")).isNull();
    assertThat(CompactSummary.parse("[]")).isNull();
    assertThat(CompactSummary.parse("42")).isNull();
    assertThat(CompactSummary.parse("{}")).isNull();
    assertThat(
            CompactSummary.parse(
                "{\"objective\":\"  \",\"completed\":[],\"pending\":[],\"decisions\":[],"
                    + "\"changedFiles\":[],\"errors\":[],\"activeSkills\":[],\"unresolved\":[]}"))
        .isNull();
  }

  /**
   * 渲染是逐字节契约：行序 = 字段声明序、八个标签<b>恒全</b>（空字段写 {@code （无）}），条目用 {@code "- "} 前缀。
   *
   * <p>"这一类是空的"必须与"这一类没渲染"可区分——后者会让模型把"待办为空"读成"这段历史没提过待办"。
   */
  @Test
  void renderAlwaysEmitsAllEightLabelsInDeclarationOrder() {
    CompactSummary summary =
        new CompactSummary(
            "目标哨兵",
            List.of("完成哨兵"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    assertThat(summary.render())
        .isEqualTo(
            """
            目标：目标哨兵
            已完成：
            - 完成哨兵
            待办：
            （无）
            决策：
            （无）
            改动文件：
            （无）
            错误：
            （无）
            激活技能：
            （无）
            未决问题：
            （无）""");
  }

  /** 确定性 + 同值同字节：render 是 COMPACT_SUMMARY 层与落库摘要的共同来源，两次调用必须一模一样。 */
  @Test
  void renderIsDeterministicForEqualContent() {
    CompactSummary first = CompactSummary.parse(FULL_JSON);
    CompactSummary second = CompactSummary.parse(FULL_JSON);

    assertThat(first).isEqualTo(second);
    assertThat(first.render()).isEqualTo(second.render());
    assertThat(first.render()).isEqualTo(first.render());
    assertThat(first.render()).contains("目标：目标哨兵").contains("- 完成甲").contains("- 未决哨兵");
  }

  /** 构造期防御性拷贝：外部列表后续被改不改写已建摘要；null 列表视作空（不是 NPE）。 */
  @Test
  void constructorCopiesListsAndTreatsNullAsEmpty() {
    List<String> mutable = new ArrayList<>(List.of("原始"));
    CompactSummary summary = new CompactSummary(null, mutable, null, null, null, null, null, null);

    mutable.add("事后追加");

    assertThat(summary.objective()).isEmpty();
    assertThat(summary.completed()).containsExactly("原始");
    assertThat(summary.pending()).isEmpty();
  }
}
