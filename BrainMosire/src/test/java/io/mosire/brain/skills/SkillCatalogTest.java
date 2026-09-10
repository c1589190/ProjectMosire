package io.mosire.brain.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SkillCatalog} 三档渐进披露的行为契约（开发计划 §4.5，全离线、纯文件系统）：
 *
 * <p>第一档（scan → catalog/listing：只有元数据，正文不读）→ 第二档（load：正文按需）→ 第三档（{@link Skill#directory()}
 * 为锚点，references/scripts 由调用方按需读取）；外加 frontmatter 校验与 {@code allowed-tools} → {@link ToolContext}
 * 的单调收紧映射。
 */
class SkillCatalogTest {

  /** 正文哨兵：出现在 SKILL.md 正文里，用来断言"第一档未把正文带进目录"。 */
  private static final String BODY_SENTINEL = "BODY-SENTINEL-正文";

  @TempDir Path tempDir;

  // ---------- 第一档：catalog 元数据可见、正文未加载 ----------

  @Test
  void scanExposesCatalogMetadataWithoutLoadingBodies() {
    writeSkill("alpha", "alpha", "第一个技能", "Read, Grep", "ALPHA-" + BODY_SENTINEL);
    writeSkill("beta", "beta", "第二个技能", null, "BETA-" + BODY_SENTINEL);

    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    assertThat(catalog.catalog()).extracting(Skill::name).containsExactly("alpha", "beta");
    Skill alpha = catalog.catalog().get(0);
    assertThat(alpha.description()).isEqualTo("第一个技能");
    assertThat(alpha.allowedTools()).containsExactlyInAnyOrder("Read", "Grep");
    // 第一档只披露 name + description + allowed-tools 提示；正文一字未进目录
    assertThat(catalog.listing())
        .contains("- alpha: 第一个技能")
        .contains("- beta: 第二个技能")
        .doesNotContain(BODY_SENTINEL);
  }

  @Test
  void catalogMetadataListsSkillsInScanOrder() {
    // 目录名与扫描顺序不一致（c 先于 a 落盘），扫描序仍按目录名确定性排序
    writeSkill("charlie", "charlie", "第三", null, "正文");
    writeSkill("alpha", "alpha", "第一", null, "正文");
    writeSkill("bravo", "bravo", "第二", null, "正文");

    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    assertThat(catalog.catalog())
        .extracting(Skill::name)
        .containsExactly("alpha", "bravo", "charlie");
  }

  @Test
  void catalogReturnsImmutableSnapshot() {
    writeSkill("alpha", "alpha", "技能", null, "正文");
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    List<Skill> snapshot = catalog.catalog();
    assertThatThrownBy(() -> snapshot.add(snapshot.get(0)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ---------- 第二档：load 才读正文 ----------

  @Test
  void bodyIsReadFromDiskAtLoadTime() {
    writeSkill("alpha", "alpha", "技能", null, "旧正文");
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    // 扫描后改写正文（frontmatter 不变）：load 必须读到新正文 → 证明扫描期没有缓存正文
    writeSkill("alpha", "alpha", "技能", null, "新正文");

    assertThat(catalog.load("alpha")).isEqualTo("新正文");
  }

  @Test
  void loadReturnsBodyWithoutFrontmatterAndTrimsSurroundingBlankLines() {
    writeSkill("alpha", "alpha", "技能", null, "\n\n正文第一行\n正文第二行\n\n");

    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    assertThat(catalog.load("alpha")).isEqualTo("正文第一行\n正文第二行");
  }

  @Test
  void loadUnknownSkillThrows() {
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    assertThatThrownBy(() -> catalog.load("nope"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope");
  }

  @Test
  void skillDirectoryIsTheAnchorForThirdTierResources() {
    writeSkill("alpha", "alpha", "技能", null, "正文");

    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    Skill alpha = catalog.catalog().get(0);
    assertThat(alpha.directory()).isEqualTo(skillsDir().resolve("alpha"));
    assertThat(alpha.skillFile()).isEqualTo(skillsDir().resolve("alpha").resolve("SKILL.md"));
  }

  // ---------- frontmatter 校验 ----------

  @Test
  void frontmatterNameMustMatchDirectoryName() {
    writeSkill("alpha", "beta", "描述", null, "正文");

    SkillCatalog catalog = new SkillCatalog();
    assertThatThrownBy(() -> catalog.scan(skillsDir()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("beta")
        .hasMessageContaining("alpha");
  }

  @Test
  void missingDescriptionIsRejected() {
    writeFrontmatter("alpha", "name: alpha\n", "正文");

    SkillCatalog catalog = new SkillCatalog();
    assertThatThrownBy(() -> catalog.scan(skillsDir()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("description");
  }

  @Test
  void blankDescriptionIsRejected() {
    writeSkill("alpha", "alpha", "", null, "正文");

    SkillCatalog catalog = new SkillCatalog();
    assertThatThrownBy(() -> catalog.scan(skillsDir()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("description");
  }

  @Test
  void descriptionOver1024CharsIsRejected() {
    writeSkill("alpha", "alpha", "x".repeat(Skill.MAX_DESCRIPTION_LENGTH + 1), null, "正文");

    SkillCatalog catalog = new SkillCatalog();
    assertThatThrownBy(() -> catalog.scan(skillsDir()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1024");
  }

  @Test
  void descriptionAtBoundariesIsAccepted() {
    writeSkill("one", "one", "x", null, "正文");
    writeSkill("max", "max", "x".repeat(Skill.MAX_DESCRIPTION_LENGTH), null, "正文");

    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    assertThat(catalog.catalog()).extracting(Skill::name).containsExactly("max", "one");
  }

  @Test
  void skillDirectoryWithoutSkillMdIsRejected() {
    try {
      Files.createDirectories(skillsDir().resolve("alpha"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    SkillCatalog catalog = new SkillCatalog();
    assertThatThrownBy(() -> catalog.scan(skillsDir()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SKILL.md");
  }

  @Test
  void frontmatterValuesMayContainColonsAndQuotes() {
    writeFrontmatter("alpha", "name: alpha\ndescription: \"审查代码：先看 diff\"\n", "正文");

    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    assertThat(catalog.catalog().get(0).description()).isEqualTo("审查代码：先看 diff");
  }

  @Test
  void allowedToolsAcceptsYamlListForm() {
    writeFrontmatter(
        "alpha", "name: alpha\ndescription: 技能\nallowed-tools:\n  - Read\n  - Grep\n", "正文");

    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    assertThat(catalog.catalog().get(0).allowedTools()).containsExactlyInAnyOrder("Read", "Grep");
  }

  @Test
  void scanSkipsHiddenDirectoriesAndLooseFiles() {
    // 技能库要落 git 版控（learn_skill），目录里还会夹带 README——两者都不是技能，不该让扫描失败
    writeSkill("alpha", "alpha", "技能", null, "正文");
    try {
      Files.createDirectories(skillsDir().resolve(".git"));
      Files.writeString(skillsDir().resolve("README.md"), "技能库说明", StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    assertThat(catalog.catalog()).extracting(Skill::name).containsExactly("alpha");
  }

  @Test
  void unknownFrontmatterKeysAreIgnored() {
    writeFrontmatter("alpha", "name: alpha\ndescription: 技能\nlicense: MIT\nversion: 1.2.0\n", "正文");

    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    assertThat(catalog.catalog().get(0).description()).isEqualTo("技能");
  }

  // ---------- description 复合行截断（扫描期校验 1–1024 与渲染期 ≤1536 并存） ----------

  @Test
  void renderedEntryIsTruncatedAtMaxEntryCharsWhileMetadataStaysWhole() {
    String description = "x".repeat(Skill.MAX_DESCRIPTION_LENGTH);
    String allowedTools =
        IntStream.range(0, 80).mapToObj(i -> "tool-" + i).collect(Collectors.joining(", "));
    writeSkill("huge", "huge", description, allowedTools, "正文");

    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    String line = catalog.listing();
    assertThat(line).hasSizeLessThanOrEqualTo(SkillCatalog.MAX_ENTRY_CHARS).endsWith("…");
    // 渲染截断不反噬元数据：catalog() 里的 description 仍是完整的 1024 字符
    assertThat(catalog.catalog().get(0).description()).isEqualTo(description);
  }

  // ---------- listing 预算：丢最不常用，平局按扫描序 ----------

  @Test
  void listingDropsLeastRecentlyActivatedAndBreaksTiesByScanOrder() {
    // 每行 "- a: " + 10 字符描述 = 15 字符；两行含换行分隔 = 31 ≤ 33，三行 = 47 > 33
    writeSkill("a", "a", "x".repeat(10), null, "正文");
    writeSkill("b", "b", "x".repeat(10), null, "正文");
    writeSkill("c", "c", "x".repeat(10), null, "正文");
    SkillCatalog catalog = new SkillCatalog(33);
    catalog.scan(skillsDir());

    // 都没激活过 = 平局 → 扫描序靠前者留下（listing 是扫描序前缀，前缀稳定最利于 prompt-cache）
    assertThat(catalog.listing()).isEqualTo("- a: xxxxxxxxxx\n- b: xxxxxxxxxx");

    // c 刚激活 → 挤掉平局的 b；输出仍按扫描序（a 在 c 前）
    catalog.load("c");
    assertThat(catalog.listing()).isEqualTo("- a: xxxxxxxxxx\n- c: xxxxxxxxxx");

    // b 也激活（比 c 更近）→ 现在最不常用的是 a
    catalog.load("b");
    assertThat(catalog.listing()).isEqualTo("- b: xxxxxxxxxx\n- c: xxxxxxxxxx");
  }

  @Test
  void rescanKeepsActivationRecencySoListingDoesNotChurn() {
    writeSkill("a", "a", "x".repeat(10), null, "正文");
    writeSkill("b", "b", "x".repeat(10), null, "正文");
    writeSkill("c", "c", "x".repeat(10), null, "正文");
    SkillCatalog catalog = new SkillCatalog(33);
    catalog.scan(skillsDir());
    catalog.load("c");
    String before = catalog.listing();

    catalog.scan(skillsDir());

    assertThat(catalog.listing()).isEqualTo(before);
  }

  @Test
  void listingOfEmptyOrBudgetlessCatalogIsEmpty() {
    assertThat(new SkillCatalog().listing()).isEmpty();

    writeSkill("alpha", "alpha", "技能", null, "正文");
    SkillCatalog noBudget = new SkillCatalog(0);
    noBudget.scan(skillsDir());

    assertThat(noBudget.catalog()).hasSize(1);
    assertThat(noBudget.listing()).isEmpty();
  }

  @Test
  void scanOfMissingDirectoryYieldsEmptyCatalog() {
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(tempDir.resolve("no-such-skills-dir"));

    assertThat(catalog.catalog()).isEmpty();
    assertThat(catalog.listing()).isEmpty();
  }

  @Test
  void scanOfNonDirectoryPathIsRejected() {
    Path file = tempDir.resolve("skills");
    try {
      Files.writeString(file, "not a dir", StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    SkillCatalog catalog = new SkillCatalog();
    assertThatThrownBy(() -> catalog.scan(file)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void budgetIsExposedAndMustNotBeNegative() {
    assertThat(new SkillCatalog().budget()).isEqualTo(SkillCatalog.DEFAULT_LISTING_BUDGET);
    assertThat(new SkillCatalog(128).budget()).isEqualTo(128);
    assertThatThrownBy(() -> new SkillCatalog(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- allowed-tools → ToolContext（单调收紧，永不放宽） ----------

  @Test
  void allowedToolsNarrowAnUnrestrictedCallerToOneSkill() {
    writeSkill("review", "review", "审查", "Read, Grep", "正文");
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    ToolContext caller =
        ToolContext.of(AccessToken.DEFAULT, AgentPermissionSet.unrestricted(AccessToken.DEFAULT));
    ToolContext constrained = catalog.catalog().get(0).constrain(caller);

    assertThat(constrained.permissions().isToolAllowed("Read")).isTrue();
    assertThat(constrained.permissions().isToolAllowed("Grep")).isTrue();
    assertThat(constrained.permissions().isToolAllowed("Bash")).isFalse();
    // 身份与一次调用的参数不动，只收紧权限维度
    assertThat(constrained.caller()).isEqualTo(AccessToken.DEFAULT);
    assertThat(constrained.arguments()).isEqualTo(caller.arguments());
  }

  @Test
  void allowedToolsNeverWidenANarrowerCaller() {
    writeSkill("review", "review", "审查", "Read, Grep", "正文");
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    ToolContext readOnlyCaller =
        ToolContext.of(
            AccessToken.GUEST, AgentPermissionSet.builder(AccessToken.GUEST).allow("Read").build());
    ToolContext constrained = catalog.catalog().get(0).constrain(readOnlyCaller);

    assertThat(constrained.permissions().isToolAllowed("Read")).isTrue();
    assertThat(constrained.permissions().isToolAllowed("Grep")).isFalse();
  }

  @Test
  void allowedToolsNeverOverrideCallerDenials() {
    writeSkill("review", "review", "审查", "Read, Grep", "正文");
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    ToolContext denyingCaller =
        ToolContext.of(
            AccessToken.DEFAULT,
            AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().deny("Read").build());
    ToolContext constrained = catalog.catalog().get(0).constrain(denyingCaller);

    // 拒绝维度原样保留——"拒绝优先"由 PermissionChecker 判定，与白名单交集是两件事，技能无权解除调用者的拒绝
    assertThat(constrained.permissions().deniedTools()).contains("Read");
    assertThat(constrained.permissions().allowedTools()).containsExactlyInAnyOrder("Read", "Grep");
  }

  @Test
  void skillWithoutAllowedToolsLeavesCallerPermissionsUntouched() {
    writeSkill("plain", "plain", "无约束技能", null, "正文");
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    ToolContext caller =
        ToolContext.of(AccessToken.DEFAULT, AgentPermissionSet.unrestricted(AccessToken.DEFAULT));

    assertThat(catalog.catalog().get(0).constrain(caller)).isSameAs(caller);
  }

  // ---------- 落盘助手 ----------

  private Path skillsDir() {
    return tempDir.resolve("skills");
  }

  /**
   * 落盘 {@code <skillsDir>/<dirName>/SKILL.md}；{@code name}/{@code description}/{@code allowedTools}
   * 为 null 即省略该键。
   */
  private void writeSkill(
      String dirName, String name, String description, String allowedTools, String body) {
    StringBuilder frontmatter = new StringBuilder();
    if (name != null) {
      frontmatter.append("name: ").append(name).append('\n');
    }
    if (description != null) {
      frontmatter.append("description: ").append(description).append('\n');
    }
    if (allowedTools != null) {
      frontmatter.append("allowed-tools: ").append(allowedTools).append('\n');
    }
    writeFrontmatter(dirName, frontmatter.toString(), body);
  }

  /** 用原始 frontmatter 文本落盘一个技能（测解析边角：引号/列表/未知键）。 */
  private void writeFrontmatter(String dirName, String frontmatter, String body) {
    Path dir = skillsDir().resolve(dirName);
    try {
      Files.createDirectories(dir);
      Files.writeString(
          dir.resolve("SKILL.md"), "---\n" + frontmatter + "---\n" + body, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
