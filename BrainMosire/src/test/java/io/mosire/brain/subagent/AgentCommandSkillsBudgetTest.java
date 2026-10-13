package io.mosire.brain.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.skills.SkillCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D2 的发送端判据（设计 §10.2 D2；《设计-Skill运行时能力.md》§3 的目录档预算 {@code skills.listingBudget}）：{@code
 * --skills-listing-budget} 只在<b>非缺省</b>时下发。
 *
 * <p><b>为什么要钉"缺省不下发"</b>：子进程对旗标缺席的读法是回落到 {@link SkillCatalog#DEFAULT_LISTING_BUDGET}，与接线前逐字一致。因此
 * "无条件追加旗标"虽然功能上不坏，却会改掉既有装配与既有夹具的 argv——本仓的形状纪律是"公开形状只增不减、既有调用点零改动"。这条判据同时抓
 * "忘传"（非缺省必须下发）与"无条件传"（缺省值不许下发）两类错法。
 *
 * <p><b>逐条对号</b>（D2 的四条）：
 *
 * <ul>
 *   <li>{@link #nonDefaultBudgetIsAppendedRightAfterSkillsDir()}（非缺省 ⇒ 追加，且与 {@code --skills-dir}
 *       同一条缝）；
 *   <li>{@link #explicitDefaultBudgetKeepsArgvIdenticalToLegacy()}（显式给缺省值 ⇒ argv 与旧形态逐字相同）；
 *   <li>{@link #zeroBudgetIsLegalAndMustBeSent()}（{@code 0} 合法且必须下发：0 = 不披露目录档）；
 *   <li>{@link #negativeBudgetIsRejectedAtConstruction()}（负值构造期具名拒，不静默夹成 0）。
 * </ul>
 */
class AgentCommandSkillsBudgetTest {

  private static final String SKILLS_DIR = "/opt/mosire/skills";

  /** D2 ①：非缺省预算 ⇒ argv 追加 {@code --skills-listing-budget <n>}，紧跟 {@code --skills-dir}。 */
  @Test
  void nonDefaultBudgetIsAppendedRightAfterSkillsDir() {
    List<String> argv = command(SKILLS_DIR, 12).argv(instance());

    assertThat(argv)
        .containsSubsequence("--skills-dir", SKILLS_DIR, "--skills-listing-budget", "12");
    assertThat(argv.indexOf("--skills-listing-budget"))
        .as("与 --skills-dir 同一条缝（值同源：都来自 skills.*）")
        .isEqualTo(argv.indexOf("--skills-dir") + 2);
    assertThat(command(SKILLS_DIR, 12).skillsListingBudget()).isEqualTo(12);
  }

  /** D2 ②：显式给缺省值 ⇒ argv 与"未注入"的旧形态<b>逐字相同</b>（子进程本来就回落同一个数）。 */
  @Test
  void explicitDefaultBudgetKeepsArgvIdenticalToLegacy() {
    List<String> legacy = legacyCommand(SKILLS_DIR).argv(instance());
    List<String> explicitDefault =
        command(SKILLS_DIR, SkillCatalog.DEFAULT_LISTING_BUDGET).argv(instance());

    assertThat(explicitDefault).as("缺省值不许下发：既有装配与既有夹具的 argv 必须一字不差").isEqualTo(legacy);
    assertThat(explicitDefault).doesNotContain("--skills-listing-budget");
    assertThat(legacyCommand(SKILLS_DIR).skillsListingBudget())
        .as("9 参兼容构造 = 未注入（null → 子进程按缺省回落）")
        .isNull();
  }

  /** D2 ③：{@code 0} 是合法取值（不披露目录档）且必须真的下发——缺省回落会让它变成 4096。 */
  @Test
  void zeroBudgetIsLegalAndMustBeSent() {
    List<String> argv = command(SKILLS_DIR, 0).argv(instance());

    assertThat(argv)
        .containsSubsequence("--skills-dir", SKILLS_DIR, "--skills-listing-budget", "0");
  }

  /** D2 ④：负值在构造期具名拒（与 {@link SkillCatalog} 同一口径，不静默夹成 0）。 */
  @Test
  void negativeBudgetIsRejectedAtConstruction() {
    assertThatThrownBy(
            () ->
                new AgentCommand(
                    "/usr/bin/java",
                    List.of("-Xmx128m"),
                    List.of("-jar", "/opt/mosire/mosire.jar", "agent"),
                    "--id",
                    "/opt/mosire/templates",
                    "/opt/mosire/data/subagents",
                    true,
                    null,
                    SKILLS_DIR,
                    -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为负");
  }

  /** 附带判据：没有技能根就没有"目录档"这回事 ⇒ 不下发（旗标无意义，也不该在 argv 里出现）。 */
  @Test
  void budgetWithoutSkillsDirIsNotSent() {
    assertThat(command(null, 12).argv(instance())).doesNotContain("--skills-listing-budget");
    assertThat(command(null, 0).argv(instance())).doesNotContain("--skills-listing-budget");
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 10 参形态（D2 之后）：显式给出目录档预算（{@code null} = 未注入）。 */
  private static AgentCommand command(String skillsDir, Integer budget) {
    return new AgentCommand(
        "/usr/bin/java",
        List.of("-Xmx128m"),
        List.of("-jar", "/opt/mosire/mosire.jar", "agent"),
        "--id",
        "/opt/mosire/templates",
        "/opt/mosire/data/subagents",
        true,
        null,
        skillsDir,
        budget);
  }

  /** 9 参兼容形态（D2 之前）：预算未注入。 */
  private static AgentCommand legacyCommand(String skillsDir) {
    return new AgentCommand(
        "/usr/bin/java",
        List.of("-Xmx128m"),
        List.of("-jar", "/opt/mosire/mosire.jar", "agent"),
        "--id",
        "/opt/mosire/templates",
        "/opt/mosire/data/subagents",
        true,
        null,
        skillsDir);
  }

  /** 只读数据模型（与 {@code AgentCommandConfigDirTest} 同款：直接供给 argv 拼装契约）。 */
  private static SubagentInstance instance() {
    return new SubagentInstance(
        "c1",
        "t",
        "g",
        AgentConfig.builder("c1").build(),
        AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
        1,
        SubagentStatus.CONFIGURED);
  }
}
