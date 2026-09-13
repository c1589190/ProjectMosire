package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import org.junit.jupiter.api.Test;

/**
 * {@link TerminalOutcome} 的"空白 = 没有、有才写"契约（设计 §2.4；顺手项 ⑦ 的微分支：空白归一此前零用例）。
 *
 * <p>这一层是父侧终局判定的读数面：它的每一处"归一/省略"都直接决定模型面看到的是"子体交代过"还是"没交代"。
 */
class TerminalOutcomeTest {

  /**
   * 空白归一：{@code null} / 空串 / 全空白 ⇒ {@code stopReason == null}（老事件、坏 payload 里的空串不许冒充"有终局原因"）， 且
   * {@link TerminalOutcome#fields()} 不写这个键。
   *
   * <p>判别性：把 {@code isBlank()} 那一半删掉（只判 {@code == null}）⇒ 空串两条断言转红；把 {@code fields()} 写成 "无条件 put"
   * ⇒ 空表断言转红。
   */
  @Test
  void blankStopReasonIsNoStopReason() {
    for (String blank : new String[] {null, "", "   ", "\t"}) {
      TerminalOutcome outcome = new TerminalOutcome(blank, null, null, null);
      assertThat(outcome.stopReason()).as("blank=[%s]".formatted(blank)).isNull();
      assertThat(outcome.hasStopReason()).as("blank=[%s]".formatted(blank)).isFalse();
      assertThat(outcome.fields()).as("blank=[%s]：不写键".formatted(blank)).isEmpty();
    }
  }

  /**
   * 有值就逐字保留（<b>不</b> trim、不改写）：{@code stopReason} 是子体自报的停因，父侧只做"空白/非空白"二分，不改内容。
   *
   * <p>判别性：给 {@code stopReason} 加 {@code .trim()} ⇒ 本用例转红（本用例就是"父侧不改写子体口径"的判据）。
   */
  @Test
  void presentStopReasonIsKeptVerbatim() {
    TerminalOutcome outcome = new TerminalOutcome(" FINISHED ", null, null, null);
    assertThat(outcome.stopReason()).isEqualTo(" FINISHED ");
    assertThat(outcome.hasStopReason()).isTrue();
    assertThat(outcome.fields()).containsOnly(entry("stopReason", " FINISHED "));
  }

  /**
   * {@code null} 字段不出现、{@code 0} 出现——"没有"与"是 0"在事件/模型面必须可分（§四.6：不编 0，也不吞真的 0）。
   *
   * <p>判别性：把 {@code fields()} 改成"非 null 才写"的变体却顺手把 0 也跳过（{@code i > 0} 之类的"看着无害"防御）⇒ 全零那条转红。
   */
  @Test
  void nullFieldsAreOmittedButZerosAreKept() {
    assertThat(new TerminalOutcome(null, null, null, null).fields()).isEmpty();
    assertThat(new TerminalOutcome("FINISHED", 0, 0, 0).fields())
        .containsOnly(
            entry("stopReason", "FINISHED"),
            entry("turns", 0),
            entry("toolCalls", 0),
            entry("exitCode", 0));
  }
}
