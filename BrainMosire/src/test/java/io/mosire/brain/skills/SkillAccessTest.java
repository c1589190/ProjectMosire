package io.mosire.brain.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SkillAccess} 的行为契约（全离线、纯文件系统）：可见面收窄、创建/修改的校验与原子性、检索字段、第三档资源。
 *
 * <p>判别性集中在四类错法上：
 *
 * <ul>
 *   <li>“列目录/检索时漏过隐藏技能”（可见面只是渲染过滤器）——{@code hiddenSkill...} 两条用例变红；
 *   <li>“先落盘再校验”（写坏文件留在盘上）——{@code createRejectsInvalidContent} 的“目录不存在”断言变红；
 *   <li>“改一份坏文档把好文档改坏”（无校验覆盖）——{@code editRejectsInvalid} 的字节相等断言变红；
 *   <li>“`..` 逃逸读资源”（第三档越出技能目录）——{@code readResourceRejectsEscape} 变红。
 * </ul>
 */
class SkillAccessTest {

  private static final String BODY_SENTINEL = "BODY-SENTINEL-正文";

  @TempDir Path tempDir;

  private Path root;
  private SkillCatalog catalog;

  @BeforeEach
  void setUp() throws IOException {
    root = Files.createDirectories(tempDir.resolve("skills"));
    catalog = new SkillCatalog();
    catalog.scan(root);
  }

  // ---------- 可见面 ----------

  @Test
  void listingAndVisibleSkillsOnlyContainWhatTheCallerCanSee() throws IOException {
    writeSkill("alpha", "第一个技能", "ALPHA-" + BODY_SENTINEL);
    writeSkill("beta", "第二个技能（隐藏）", "BETA-" + BODY_SENTINEL);
    SkillAccess access = access(ResourceScope.ofDir(root.resolve("alpha")), ResourceScope.none());
    access.refresh();

    assertThat(access.visibleSkills()).extracting(Skill::name).containsExactly("alpha");
    assertThat(access.listing()).contains("- alpha: 第一个技能").doesNotContain("beta");
    assertThat(access.visibleNames()).containsExactly("alpha");
  }

  /**
   * 隐藏技能与“不可达的名字”走同一条拒绝（{@code ResourceDeniedException} → 工具面 {@code RESOURCE_DENIED}）：
   * 越权者拿不到“这个技能存不存在”这一个 bit。<b>可见面之内</b>的不存在才是 {@code SKILL_NOT_FOUND}。
   */
  @Test
  void hiddenSkillIsNotDistinguishableFromAnUnreachableName() throws IOException {
    writeSkill("alpha", "可见", "ALPHA");
    writeSkill("secret", "隐藏", "SECRET-" + BODY_SENTINEL);
    SkillAccess access = access(ResourceScope.ofDir(root.resolve("alpha")), ResourceScope.none());

    assertThatThrownBy(() -> access.loadBody("secret"))
        .isInstanceOf(ResourceDeniedException.class)
        .hasMessageNotContaining(BODY_SENTINEL);
    // 同一个调用者猜一个根本不存在的名字：与隐藏技能得到同一种拒绝（无存在性泄漏）
    assertThatThrownBy(() -> access.loadBody("ghost")).isInstanceOf(ResourceDeniedException.class);
    assertThat(access.lookupVisible("secret")).isEmpty();

    // 可见面之内：未知名字才是“不存在”
    SkillAccess full = access(ResourceScope.unlimited(), ResourceScope.none());
    SkillException missing =
        catchThrowableOfType(() -> full.loadBody("ghost"), SkillException.class);
    assertThat(missing.code()).isEqualTo(SkillException.SKILL_NOT_FOUND);
  }

  @Test
  void loadBodyReturnsBodyOfVisibleSkillAndRecordsActivation() throws IOException {
    writeSkill("alpha", "技能", "ALPHA-" + BODY_SENTINEL);
    SkillAccess access = access(ResourceScope.unlimited(), ResourceScope.none());
    assertThat(access.loadBody("alpha")).contains(BODY_SENTINEL);
  }

  @Test
  void overlongBodyIsTruncatedWithVisibleNotice() throws IOException {
    writeSkill("big", "大技能", "x".repeat(SkillAccess.MAX_BODY_CHARS + 500));
    SkillAccess access = access(ResourceScope.unlimited(), ResourceScope.none());
    String body = access.loadBody("big");
    assertThat(body).hasSize(SkillAccess.MAX_BODY_CHARS + SkillAccess.TRUNCATION_NOTICE.length());
    assertThat(body).endsWith(SkillAccess.TRUNCATION_NOTICE);
  }

  @Test
  void refreshSeesExternalWrites() throws IOException {
    writeSkill("alpha", "技能", "旧正文");
    SkillAccess access = access(ResourceScope.unlimited(), ResourceScope.none());
    assertThat(access.loadBody("alpha")).contains("旧正文");

    Files.writeString(root.resolve("alpha").resolve("SKILL.md"), skillText("alpha", "技能", "新正文"));
    assertThat(access.loadBody("alpha")).as("每次加载前重扫 ⇒ 盘上变化立刻可见").contains("新正文");
  }

  // ---------- 检索 ----------

  @Test
  void searchMatchesNameDescriptionAndBodyButOnlyWithinVisibleScope() throws IOException {
    writeSkill("alpha", "处理 Kafka 的主题", "ALPHA 正文");
    writeSkill("beta", "另一个技能", "BETA 正文里提到 kafka");
    writeSkill("secret", "隐藏技能", "SECRET 也提到 kafka");
    SkillAccess access =
        access(
            ResourceScope.of(root.resolve("alpha").toString(), root.resolve("beta").toString()),
            ResourceScope.none());

    // 字段覆盖：name
    assertThat(access.search("alpha", 0).hits())
        .extracting(SkillAccess.SearchHit::name)
        .containsExactly("alpha");
    // 字段覆盖：description
    assertThat(access.search("kafka 主题", 0).hits())
        .extracting(SkillAccess.SearchHit::name)
        .containsExactly("alpha");
    // 字段覆盖：body
    assertThat(access.search("正文里提到", 0).hits())
        .extracting(SkillAccess.SearchHit::name)
        .containsExactly("beta");
    assertThat(access.search("正文里提到", 0).hits().get(0).matchedIn()).isEqualTo("body");
    // 隐藏技能不参与检索：它的正文里也有 kafka，但结果里没有它
    assertThat(access.search("kafka", 0).hits())
        .extracting(SkillAccess.SearchHit::name)
        .containsExactlyInAnyOrder("alpha", "beta");
  }

  /**
   * 坏技能只影响它自己：健康技能照常可检索，坏掉这件事在 {@link SkillAccess#broken()} 里<b>可见地披露</b>——
   * 宽容不等于静默（判别性：把宽容做成静默跳过，第二条断言变红）。
   */
  @Test
  void brokenSkillDoesNotTakeHealthyOnesDownButIsDisclosed() throws IOException {
    writeSkill("alpha", "健康技能", "ALPHA 正文");
    writeSkill("broken", "坏技能", "BROKEN");
    Files.writeString(root.resolve("broken").resolve("SKILL.md"), "没有 frontmatter 的坏文件");
    SkillAccess access = access(ResourceScope.unlimited(), ResourceScope.none());

    SkillAccess.SearchResult result = access.search("正文", 0);
    assertThat(result.hits()).extracting(SkillAccess.SearchHit::name).containsExactly("alpha");
    assertThat(result.scanned()).isEqualTo(1);
    assertThat(access.broken()).containsKey("broken");
    assertThat(access.visibleSkills()).extracting(Skill::name).containsExactly("alpha");
  }

  @Test
  void blankQueryIsEmptyResultNotError() throws IOException {
    writeSkill("alpha", "技能", "正文");
    SkillAccess access = access(ResourceScope.unlimited(), ResourceScope.none());
    assertThat(access.search("   ", 0).hits()).isEmpty();
    assertThat(access.search("alpha", 0).hits()).hasSize(1);
  }

  // ---------- 创建 ----------

  @Test
  void createRequiresEditableScopeAndIsVisibleImmediately() throws IOException {
    SkillAccess readOnly = access(ResourceScope.unlimited(), ResourceScope.none());
    assertThatThrownBy(() -> readOnly.create("newbie", "新技能", "正文", Set.of("Read")))
        .isInstanceOf(ResourceDeniedException.class);

    SkillAccess editable = access(ResourceScope.unlimited(), ResourceScope.unlimited());
    Skill created = editable.create("newbie", "新技能", "正文内容", Set.of("Read", "Grep"));
    assertThat(created.name()).isEqualTo("newbie");
    assertThat(created.allowedTools()).containsExactlyInAnyOrder("Read", "Grep");

    // 写入后同一访问面立刻看得见（写路径内部重扫）
    assertThat(editable.visibleSkills()).extracting(Skill::name).contains("newbie");
    assertThat(editable.loadBody("newbie")).contains("正文内容");
    assertThat(Files.readString(root.resolve("newbie").resolve("SKILL.md")))
        .startsWith("---\nname: newbie\ndescription: 新技能\nallowed-tools: Grep, Read\n---");
  }

  @Test
  void createRefusesExistingSkillAndOutsideScope() throws IOException {
    writeSkill("alpha", "已存在", "正文");
    SkillAccess access = access(ResourceScope.unlimited(), ResourceScope.unlimited());

    SkillException exists =
        catchThrowableOfType(() -> access.create("alpha", "想覆盖", "x", null), SkillException.class);
    assertThat(exists.code()).isEqualTo(SkillException.SKILL_EXISTS);
    assertThat(Files.readString(root.resolve("alpha").resolve("SKILL.md"))).contains("已存在");

    // 只可编辑 alpha 这一个技能 ⇒ 在别处建新技能被拒
    SkillAccess scoped =
        access(ResourceScope.unlimited(), ResourceScope.ofDir(root.resolve("alpha")));
    assertThatThrownBy(() -> scoped.create("beta", "别处", "x", null))
        .isInstanceOf(ResourceDeniedException.class);
    assertThat(Files.exists(root.resolve("beta"))).isFalse();
  }

  @Test
  void createRejectsInvalidContentWithoutLeavingAnythingBehind() throws IOException {
    SkillAccess access = access(ResourceScope.unlimited(), ResourceScope.unlimited());

    SkillException badDescription =
        catchThrowableOfType(
            () -> access.create("multi", "第一行\n第二行", "正文", null), SkillException.class);
    assertThat(badDescription.code()).isEqualTo(SkillException.INVALID_ARGUMENTS);
    assertThat(Files.exists(root.resolve("multi"))).as("校验失败不留半成品目录").isFalse();

    SkillException badName =
        catchThrowableOfType(
            () -> access.create("../escape", "逃逸", "正文", null), SkillException.class);
    assertThat(badName.code()).isEqualTo(SkillException.INVALID_ARGUMENTS);
    assertThat(Files.exists(tempDir.resolve("escape"))).isFalse();

    SkillException badTool =
        catchThrowableOfType(
            () -> access.create("tools", "工具名带逗号", "正文", Set.of("Read,Grep")),
            SkillException.class);
    assertThat(badTool.code()).isEqualTo(SkillException.INVALID_ARGUMENTS);
    assertThat(Files.exists(root.resolve("tools"))).isFalse();
  }

  // ---------- 修改 ----------

  @Test
  void editKeepsUnspecifiedFieldsAndWritesAtomically() throws IOException {
    writeSkill("alpha", "旧用途", "旧正文");
    SkillAccess access = access(ResourceScope.unlimited(), ResourceScope.unlimited());

    Skill edited = access.edit("alpha", null, "新正文", null);
    assertThat(edited.description()).isEqualTo("旧用途");
    assertThat(access.loadBody("alpha")).contains("新正文").doesNotContain("旧正文");

    Skill retitled = access.edit("alpha", "新用途：处理 Kafka", null, Set.of("Read"));
    assertThat(retitled.description()).isEqualTo("新用途：处理 Kafka");
    assertThat(retitled.allowedTools()).containsExactly("Read");
    assertThat(access.loadBody("alpha")).as("正文未给 ⇒ 保持不变").contains("新正文");
  }

  @Test
  void editRejectsInvalidContentAndLeavesFileByteIdentical() throws IOException {
    writeSkill("alpha", "用途", "原正文");
    Path file = root.resolve("alpha").resolve("SKILL.md");
    byte[] before = Files.readAllBytes(file);
    SkillAccess access = access(ResourceScope.unlimited(), ResourceScope.unlimited());

    SkillException invalid =
        catchThrowableOfType(
            () -> access.edit("alpha", "多行\ndescription", null, null), SkillException.class);
    assertThat(invalid.code()).isEqualTo(SkillException.INVALID_ARGUMENTS);
    assertThat(Files.readAllBytes(file)).as("校验失败不落盘").isEqualTo(before);

    SkillException nothing =
        catchThrowableOfType(() -> access.edit("alpha", null, null, null), SkillException.class);
    assertThat(nothing.code()).isEqualTo(SkillException.INVALID_ARGUMENTS);
    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void editRequiresEditableScopeAndExistingSkill() throws IOException {
    writeSkill("alpha", "用途", "正文");
    writeSkill("secret", "隐藏", "正文");
    SkillAccess access =
        access(
            ResourceScope.ofDir(root.resolve("alpha")), ResourceScope.ofDir(root.resolve("alpha")));

    assertThatThrownBy(() -> access.edit("secret", "改隐藏的", null, null))
        .isInstanceOf(ResourceDeniedException.class);
    // 可见/可编辑面之外的名字与隐藏技能得到同一种拒绝（无存在性泄漏）
    assertThatThrownBy(() -> access.edit("ghost", "改不存在的", null, null))
        .isInstanceOf(ResourceDeniedException.class);
    // 面内但不存在的名字才是 SKILL_NOT_FOUND
    SkillException missing =
        catchThrowableOfType(
            () ->
                access(ResourceScope.unlimited(), ResourceScope.unlimited())
                    .edit("ghost", "改不存在的", null, null),
            SkillException.class);
    assertThat(missing.code()).isEqualTo(SkillException.SKILL_NOT_FOUND);
  }

  // ---------- 第三档资源 ----------

  @Test
  void readResourceReadsInsideVisibleSkillAndRejectsEscape() throws IOException {
    writeSkill("alpha", "技能", "正文");
    Files.createDirectories(root.resolve("alpha").resolve("references"));
    Files.writeString(root.resolve("alpha").resolve("references").resolve("api.md"), "API 参考");
    Files.writeString(tempDir.resolve("outside.txt"), "目录外内容");
    SkillAccess access = access(ResourceScope.unlimited(), ResourceScope.none());

    assertThat(access.readResource("alpha", "references/api.md")).isEqualTo("API 参考");
    SkillException escape =
        catchThrowableOfType(
            () -> access.readResource("alpha", "../outside.txt"), SkillException.class);
    assertThat(escape.code()).isEqualTo(SkillException.INVALID_ARGUMENTS);
    SkillException unknown =
        catchThrowableOfType(
            () -> access.readResource("alpha", "references/none.md"), SkillException.class);
    assertThat(unknown.code()).isEqualTo(SkillException.SKILL_NOT_FOUND);
  }

  @Test
  void singleDocumentScopeDoesNotLeakSiblingResources() throws IOException {
    writeSkill("alpha", "技能", "正文");
    Files.createDirectories(root.resolve("alpha").resolve("references"));
    Files.writeString(root.resolve("alpha").resolve("references").resolve("api.md"), "API 参考");
    // 可见面 = 单个 SKILL.md 文件（单文档粒度）
    SkillAccess access =
        access(
            ResourceScope.ofDir(root.resolve("alpha").resolve("SKILL.md")), ResourceScope.none());

    assertThat(access.loadBody("alpha")).contains("正文");
    assertThatThrownBy(() -> access.readResource("alpha", "references/api.md"))
        .isInstanceOf(ResourceDeniedException.class);
  }

  // ---------- 夹具 ----------

  private SkillAccess access(ResourceScope visible, ResourceScope editable) {
    AgentPermissionSet permissions =
        SkillPermissions.withScopes(AgentPermissionSet.system(), visible, editable);
    return SkillAccess.of(permissions, catalog, root);
  }

  private void writeSkill(String name, String description, String body) throws IOException {
    Path dir = Files.createDirectories(root.resolve(name));
    Files.writeString(
        dir.resolve("SKILL.md"), skillText(name, description, body), StandardCharsets.UTF_8);
  }

  private static String skillText(String name, String description, String body) {
    return "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body + "\n";
  }
}
