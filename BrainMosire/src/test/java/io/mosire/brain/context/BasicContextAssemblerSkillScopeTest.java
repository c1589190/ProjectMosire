package io.mosire.brain.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.skills.SkillAccess;
import io.mosire.brain.skills.SkillCatalog;
import io.mosire.brain.skills.SkillPermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 技能可见面在<b>上下文预注入</b>这一侧的落点（本能力新增）：每回合的 {@code SKILL_INDEX} 与预载正文都只含可见技能。
 *
 * <p>判别性：把可见面只当渲染过滤器（先按全量渲染再过滤，或预载路径旁路可见面）⇒ 隐藏技能的名字/正文会出现在系统提示里， 本类三条断言转红。这是“无权限 Skill
 * 不得泄入上下文”的机器判据。
 */
class BasicContextAssemblerSkillScopeTest {

  private static final String HIDDEN_BODY = "HIDDEN-BODY-哨兵";

  @TempDir Path tempDir;

  @Test
  void skillIndexOnlyCarriesVisibleSkills() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    writeSkill(root, "alpha", "可见技能", "ALPHA 正文");
    writeSkill(root, "secret", "隐藏技能", HIDDEN_BODY);

    BasicContextAssembler assembler =
        assembler(root, ResourceScope.ofDir(root.resolve("alpha")), Set.of());
    String text = systemText(assembler);

    assertThat(text).contains("- alpha: 可见技能");
    assertThat(text).doesNotContain("secret").doesNotContain("隐藏技能");
  }

  @Test
  void preloadedHiddenSkillIsSkippedInsteadOfLeakingBody() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    writeSkill(root, "alpha", "可见技能", "ALPHA 正文");
    writeSkill(root, "secret", "隐藏技能", HIDDEN_BODY);

    BasicContextAssembler assembler =
        assembler(root, ResourceScope.ofDir(root.resolve("alpha")), Set.of("secret", "alpha"));
    String text = systemText(assembler);

    assertThat(text).contains("ALPHA 正文");
    assertThat(text).doesNotContain(HIDDEN_BODY).doesNotContain("## 技能 secret");
  }

  @Test
  void undeclaredSkillsScopeMeansNoSkillLayerAtAll() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    writeSkill(root, "alpha", "可见技能", "ALPHA 正文");

    BasicContextAssembler assembler = assembler(root, ResourceScope.none(), Set.of("alpha"));
    String text = systemText(assembler);

    assertThat(text).doesNotContain("- alpha").doesNotContain("ALPHA 正文");
    assertThat(
            assembler
                .composition(config(), "干活", List.of(), List.of())
                .estimatedTokens()
                .get(ContextLayer.SKILL_INDEX))
        .isZero();
  }

  // ---------- 夹具 ----------

  private BasicContextAssembler assembler(Path root, ResourceScope visible, Set<String> preloaded)
      throws IOException {
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(root);
    AgentPermissionSet permissions =
        SkillPermissions.withScopes(AgentPermissionSet.system(), visible, ResourceScope.none());
    SkillAccess access = SkillAccess.of(permissions, catalog, root);
    return new BasicContextAssembler(
        ContextPolicy.defaults(), ContextSources.skills(access, preloaded));
  }

  private static String systemText(BasicContextAssembler assembler) {
    LlmMessage system =
        assembler.buildRequest(config(), "干活", List.of(), List.<AgentTool>of()).messages().get(0);
    return ((io.mosire.agentlib.llm.ContentPart.Text) system.content().get(0)).text();
  }

  private static AgentConfig config() {
    return AgentConfig.builder("test").systemPrompt("你是测试 Agent。").build();
  }

  private static void writeSkill(Path root, String name, String description, String body)
      throws IOException {
    Path dir = Files.createDirectories(root.resolve(name));
    Files.writeString(
        dir.resolve("SKILL.md"),
        "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + body + "\n");
  }
}
