package io.mosire.main.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.brain.skills.SkillCatalog;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D2 的子进程侧形状判据（设计 §10.2 D2）：{@code --skills-listing-budget} <b>缺席</b>必须表现为 {@code null}（子体的 {@code
 * skillAssembler} 据此回落到 {@link SkillCatalog#DEFAULT_LISTING_BUDGET}，与接线前逐字一致）；{@code 0} 合法； 负数解析即拒。
 *
 * <p><b>为什么把"缺席 = null"钉成判据</b>：回落算术住在包私有的 {@code skillAssembler} 里（要跑子进程装配才观察得到），而"缺席"这一侧 只有
 * {@code Options} 表达得出来——把缺席写死成 0 或 4096，都会让"父侧根本没配"与"父侧配了那个值"变得不可区分，D2 的缺口就会以另一种 形状回来。
 *
 * <p>真正的整链（父侧装配 → argv → 子进程目录档字符数）由 R6a 的仓外探针取证（{@code R6aChildBudgetProbe}/{@code
 * R6aAppBudgetProbe}），本用例只钉单测层可观察的形状。
 */
class SubagentProcessOptionsBudgetTest {

  /** 旗标缺席（12 参兼容构造）⇒ {@code null} ⇒ 子体按缺省回落。 */
  @Test
  void absentFlagStaysNullSoTheChildFallsBackToTheDefault() {
    SubagentProcessMain.Options options = legacyOptions();

    assertThat(options.skillsListingBudget())
        .as("缺席不许被写成 0 或 4096：那会让「父侧没配」与「父侧配了那个值」不可区分")
        .isNull();
    assertThat(SkillCatalog.DEFAULT_LISTING_BUDGET).as("回落点（设计 §10.2 D2 写死 4096）").isEqualTo(4096);
  }

  /** 显式预算原样保留；{@code 0}（不披露目录档）是合法取值，不许被当成"空"丢掉。 */
  @Test
  void explicitBudgetSurvivesConstructionIncludingZero() {
    assertThat(options(12).skillsListingBudget()).isEqualTo(12);
    assertThat(options(0).skillsListingBudget()).as("0 = 不披露目录档").isZero();
  }

  /** 负预算 = 配置错误：解析即拒（不静默夹成 0，与 {@link SkillCatalog} 同一口径）。 */
  @Test
  void negativeBudgetIsRejectedAtConstruction() {
    assertThatThrownBy(() -> options(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为负");
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 13 参形态（D2 之后）：显式给出目录档预算。 */
  private static SubagentProcessMain.Options options(Integer budget) {
    return new SubagentProcessMain.Options(
        "c1",
        "t",
        "g",
        Path.of("/opt/mosire/templates"),
        Path.of("/opt/mosire/data/subagents"),
        true,
        null,
        Path.of("/opt/mosire/skills"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        budget);
  }

  /** 12 参兼容形态（D2 之前）：预算未下发。 */
  private static SubagentProcessMain.Options legacyOptions() {
    return new SubagentProcessMain.Options(
        "c1",
        "t",
        "g",
        Path.of("/opt/mosire/templates"),
        Path.of("/opt/mosire/data/subagents"),
        true,
        null,
        Path.of("/opt/mosire/skills"),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}
