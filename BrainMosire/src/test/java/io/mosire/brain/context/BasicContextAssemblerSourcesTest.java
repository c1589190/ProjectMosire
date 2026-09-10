package io.mosire.brain.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.brain.memory.MemoryRetriever;
import io.mosire.brain.memory.MemoryStore;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.skills.Skill;
import io.mosire.brain.skills.SkillCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BasicContextAssembler} 的内容源接线（P2-4 波次验收：skill 三档回放、记忆 recall 注入可见）。
 *
 * <p>为什么与 {@code BasicContextAssemblerTest} 分家：本类每例都要真实文件系统（技能目录）或真实 SQLite（记忆库）
 * 落盘夹具，而那个类全是纯字符串断言（无 IO、无夹具）；混在一起会让"既有构造逐字节冻结"这一契约被夹具噪声淹没。既有的 byte-identical
 * 断言留给那个类，本类只测新增装配路径，并在首例就锁死"未注入协作者 = 无新增段落"。
 *
 * <p>渲染格式在此逐字节 pin（{@code SYSTEM}/{@code SKILL_INDEX}/{@code LOADED_SKILLS}/{@code MEMORY} 段与
 * 段间空行）：格式是 prompt-cache 前缀契约的一部分，改了就该红。
 */
class BasicContextAssemblerSourcesTest {

  private static final String PROMPT = "你是测试 Agent。";

  /** 无工具时的 SYSTEM 段（与 {@code BasicContextAssemblerTest} 锁定的格式同源）。 */
  private static final String SYSTEM_TEXT =
      PROMPT
          + "\n\n你有以下工具可用（一律直接调用，不要凭空想象它们不存在）：\n"
          + "（当前没有任何可用工具）\n"
          + "\n完成用户请求后，请用纯文本回复结果，不要虚构工具执行过程。";

  private static final String SKILL_INDEX_HEADER = "你有以下技能可用（只列名称与用途）：";
  private static final String LOADED_SKILLS_HEADER = "已激活技能正文：";
  private static final String MEMORY_HEADER = "与本轮输入相关的记忆（按相关度排序，最多 8 条）：";

  /** 正文哨兵：出现在 SKILL.md 正文里，用来断言"第一档不带正文、第二档才带"。 */
  private static final String BODY_SENTINEL = "BODY-SENTINEL-正文";

  @TempDir Path tempDir;

  // ---------- 三档回放 ----------

  /** 第一档只披露元数据；预载（激活）后第二档才出现正文，且第一档目录仍列着它。 */
  @Test
  void skillTierOneListsMetadataOnlyAndActivationAddsTheBody() {
    writeSkill("alpha", "第一个技能", "ALPHA-" + BODY_SENTINEL);
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    BasicContextAssembler indexOnly =
        new BasicContextAssembler(ContextPolicy.defaults(), ContextSources.skills(catalog));
    String before = systemTextOf(indexOnly.buildRequest(config(), "干活", List.of(), List.of()));

    // 第一档：name + description 可见，正文一个字都不进请求
    assertThat(before).isEqualTo(SYSTEM_TEXT + "\n\n" + SKILL_INDEX_HEADER + "\n- alpha: 第一个技能");
    assertThat(before).doesNotContain(BODY_SENTINEL);
    // 记账同源：SKILL_INDEX 有值、LOADED_SKILLS 为 0（没有段落 → 该层不占 token）
    assertThat(indexOnly.composition(config(), "干活", List.of(), List.of()).estimatedTokens())
        .containsEntry(ContextLayer.LOADED_SKILLS, 0)
        .containsEntry(
            ContextLayer.SKILL_INDEX, (SKILL_INDEX_HEADER + "\n- alpha: 第一个技能").length() / 4);

    BasicContextAssembler preloaded =
        new BasicContextAssembler(
            ContextPolicy.defaults(), ContextSources.skills(catalog, Set.of("alpha")));
    String after = systemTextOf(preloaded.buildRequest(config(), "干活", List.of(), List.of()));

    // 第二档：正文进 LOADED_SKILLS；第一档目录原样仍在（已激活与否都占一行）
    assertThat(after)
        .isEqualTo(
            before
                + "\n\n"
                + LOADED_SKILLS_HEADER
                + "\n\n## 技能 alpha（资源目录："
                + skillsDir().resolve("alpha")
                + "）\nALPHA-"
                + BODY_SENTINEL);
    assertThat(preloaded.composition(config(), "干活", List.of(), List.of()).estimatedTokens())
        .containsEntry(
            ContextLayer.LOADED_SKILLS,
            (LOADED_SKILLS_HEADER
                        + "\n\n## 技能 alpha（资源目录："
                        + skillsDir().resolve("alpha")
                        + "）\nALPHA-"
                        + BODY_SENTINEL)
                    .length()
                / 4)
        .containsEntry(
            ContextLayer.SKILL_INDEX, (SKILL_INDEX_HEADER + "\n- alpha: 第一个技能").length() / 4);
  }

  /**
   * 目录序 = 扫描序：预载集是无序 Set，但目录行与正文块都按目录扫描序产出（跨机器确定）。
   *
   * <p>预载名字<b>按扫描序的逆序</b>装入、且给足 5 个：若实现偷懒拿预载集的迭代序当输出序，"恰好撞上扫描序"的概率约 1/120（2 个名字时约
   * 1/2——那样的断言在坏实现下有一半的运行能蒙混过关），一次运行就能把这种实现揪出来。
   */
  @Test
  void skillLinesAndBodiesFollowCatalogScanOrderNotPreloadSetOrder() {
    String[] names = {"alpha", "bravo", "charlie", "delta", "echo"};
    String[] bodies = {"ALPHA 正文", "BRAVO 正文", "CHARLIE 正文", "DELTA 正文", "ECHO 正文"};
    for (int i = 0; i < names.length; i++) {
      writeSkill(names[i], "第" + (i + 1) + "个", bodies[i]);
    }
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());
    Set<String> reverseScanOrder =
        new LinkedHashSet<>(List.of("echo", "delta", "charlie", "bravo", "alpha"));
    BasicContextAssembler assembler =
        new BasicContextAssembler(
            ContextPolicy.defaults(), ContextSources.skills(catalog, reverseScanOrder));

    String text = systemTextOf(assembler.buildRequest(config(), "干活", List.of(), List.of()));

    // 先钉存在性、再钉顺序：顺序断言用 indexOf，缺项时 -1 也"小于"任何下标，不能被它蒙混过去
    assertThat(text).contains(LOADED_SKILLS_HEADER);
    for (String body : bodies) {
      assertThat(text).contains(body);
    }
    for (int i = 0; i + 1 < names.length; i++) {
      assertThat(text.indexOf("- " + names[i] + ": "))
          .isLessThan(text.indexOf("- " + names[i + 1] + ": "));
      assertThat(text.indexOf(bodies[i])).isLessThan(text.indexOf(bodies[i + 1]));
    }
    // 换一个无序 Set 实现（Set.of 与 LinkedHashSet 的迭代序来源不同）逐字节同输出：输入集的迭代序一个字都不该漏进前缀
    BasicContextAssembler unmodifiableVariant =
        new BasicContextAssembler(
            ContextPolicy.defaults(),
            ContextSources.skills(catalog, Set.of("echo", "delta", "charlie", "bravo", "alpha")));
    assertThat(systemTextOf(unmodifiableVariant.buildRequest(config(), "干活", List.of(), List.of())))
        .isEqualTo(text);
    // 同一输入重复装配逐字节相同（前缀契约的确定性前提）
    assertThat(systemTextOf(assembler.buildRequest(config(), "干活", List.of(), List.of())))
        .isEqualTo(text);
  }

  /** 陈旧的预载名字（spec 里写了目录里没有的技能）只跳过，不让每个回合都崩。 */
  @Test
  void unknownPreloadedSkillNameIsSkippedInsteadOfThrowing() {
    writeSkill("alpha", "技能", "ALPHA 正文");
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());

    BasicContextAssembler staleOnly =
        new BasicContextAssembler(
            ContextPolicy.defaults(), ContextSources.skills(catalog, Set.of("ghost")));

    assertThatCode(() -> staleOnly.buildRequest(config(), "干活", List.of(), List.of()))
        .doesNotThrowAnyException();
    String text = systemTextOf(staleOnly.buildRequest(config(), "干活", List.of(), List.of()));
    assertThat(text).doesNotContain(LOADED_SKILLS_HEADER);
    assertThat(staleOnly.composition(config(), "干活", List.of(), List.of()).estimatedTokens())
        .containsEntry(ContextLayer.LOADED_SKILLS, 0);
    // 目录档照常披露真实技能——"跳过"只作用于第二档
    assertThat(text).contains("- alpha: 技能");

    // 混入真实名字：只跳过 ghost，alpha 照常加载
    BasicContextAssembler mixed =
        new BasicContextAssembler(
            ContextPolicy.defaults(), ContextSources.skills(catalog, Set.of("ghost", "alpha")));

    assertThat(systemTextOf(mixed.buildRequest(config(), "干活", List.of(), List.of())))
        .contains(LOADED_SKILLS_HEADER)
        .contains("ALPHA 正文");
  }

  // ---------- 自动预载路径：坏技能降级为可见标注 ----------

  /**
   * 目录里有、读盘却失败的技能：不进第二档正文，但在它自己那条目录行上可见地标注失败——既不静默吞掉，也不让每个回合都崩。
   *
   * <p>删除与写坏两种形态都测：技能库被 Agent 自己重写（learning loop）时文件会短暂不存在，而写坏 frontmatter 是另一种常见的
   * "半套状态"。标注必须是单行短文本（不带堆栈、不带多行），否则每回合都会把噪声灌进前缀。
   *
   * <p>显式激活路径不受本降级影响：{@link SkillCatalog#load} 照旧抛错——那里的失败是调用者的即时错误，应当响亮。
   */
  @Test
  void unreadableSkillBodyIsSkippedAndMarkedOnItsIndexLine() {
    writeSkill("alpha", "第一", "ALPHA 正文");
    writeSkill("broken", "坏技能", "BROKEN 正文");
    writeSkill("corrupt", "坏 frontmatter", "CORRUPT 正文");
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());
    deleteBodyOf("broken");
    corruptBodyOf("corrupt");
    BasicContextAssembler assembler =
        new BasicContextAssembler(
            ContextPolicy.defaults(),
            ContextSources.skills(catalog, Set.of("broken", "corrupt", "alpha")));

    String text = systemTextOf(assembler.buildRequest(config(), "干活", List.of(), List.of()));

    // 回合不崩：健康的兄弟技能照常进第二档正文
    assertThat(text).contains("## 技能 alpha（资源目录：" + skillsDir().resolve("alpha") + "）");
    assertThat(text).contains("ALPHA 正文");
    // 坏技能：正文一个字都不进请求，但失败在目录档那一行可见
    assertThat(text).doesNotContain("BROKEN 正文").doesNotContain("CORRUPT 正文");
    assertThat(text).contains("- broken: 坏技能（正文加载失败：技能目录缺少 SKILL.md：");
    assertThat(text)
        .contains("- corrupt: 坏 frontmatter（正文加载失败：SKILL.md 必须以 frontmatter 分隔行 --- 开头：");
    // 标注是单行短文本：异常类名/堆栈不进 prompt（消息缺失时也走固定 token，见下面的清洗器用例）
    assertThat(text).doesNotContain("Exception").doesNotContain("\tat ").doesNotContain("\n\t");
    // 标记只出现在失败行：健康行一个字节都不加（ruling 1 的 byte-identical 由"无失败 = 无标记"承接）
    assertThat(text).doesNotContain("- alpha: 第一（正文加载失败：");
    // 显式激活路径仍照抛（本任务不改 SkillCatalog.load 的契约）
    assertThatThrownBy(() -> catalog.load("broken")).isInstanceOf(IllegalArgumentException.class);
    // 记账同源：目录档 token = 含标注后的该段文本长度 / 4
    String indexBlock =
        text.substring(
            text.indexOf(SKILL_INDEX_HEADER), text.indexOf("\n\n" + LOADED_SKILLS_HEADER));
    assertThat(assembler.composition(config(), "干活", List.of(), List.of()).estimatedTokens())
        .containsEntry(ContextLayer.SKILL_INDEX, indexBlock.length() / 4);
  }

  /**
   * 失败原因清洗器：进 prompt 的永远是单行短文本——消息缺失时退化为固定 token，绝不把内部异常类名泄露给模型 （那是上面 {@code
   * doesNotContain("Exception")} 断言成立的前提）。
   */
  @Test
  void loadFailureReasonFallsBackToAFixedTokenAndStaysSingleLine() {
    // 无消息 / 只有空白的合成异常：生产今天到不了这里（SkillLoader 的失败一律带消息），但兜底路径必须有确定行为
    assertThat(BasicContextAssembler.shortReason(new IllegalStateException())).isEqualTo("原因未知");
    assertThat(BasicContextAssembler.shortReason(new IllegalStateException("   ")))
        .isEqualTo("原因未知");
    // 换行/制表折叠成单空格：prompt 里不许出现多行噪声
    assertThat(BasicContextAssembler.shortReason(new IllegalStateException("半行\n\t又半行")))
        .isEqualTo("半行 又半行");
    // 超长尾部截断 + 省略号（上限 120 字符 + 1 个省略号）
    assertThat(BasicContextAssembler.shortReason(new IllegalStateException("x".repeat(500))))
        .hasSize(121)
        .endsWith("…")
        .startsWith("x");
  }

  // ---------- 记忆 recall ----------

  /** 与本轮输入匹配的记忆进 MEMORY 层；调用者令牌级别看不见的那条一个字节都不出现。 */
  @Test
  void memoryRecallInjectsVisibleHitsAndHidesHigherRankedOnes() {
    try (MemoryStore store = MemoryStore.open(tempDir.resolve("mosire.db"))) {
      store.remember("偏好", "用户喜欢用 Kafka", "local", AccessToken.DEFAULT);
      store.remember("运维", "Kafka 集群口令轮换", "user", AccessToken.SYSTEM);
      MemoryRetriever retriever = new MemoryRetriever(store);
      BasicContextAssembler assembler =
          new BasicContextAssembler(
              ContextPolicy.defaults(),
              ContextSources.memory(
                  retriever, AgentPermissionSet.unrestricted(AccessToken.DEFAULT)));

      String text = systemTextOf(assembler.buildRequest(config(), "Kafka", List.of(), List.of()));

      String expectedMemory = MEMORY_HEADER + "\n- (偏好) 用户喜欢用 Kafka";
      assertThat(text).isEqualTo(SYSTEM_TEXT + "\n\n" + expectedMemory);
      // 同一条查询对 SYSTEM 身份可见 → 上一条"不可见"是权限过滤，不是没匹配上
      BasicContextAssembler privileged =
          new BasicContextAssembler(
              ContextPolicy.defaults(),
              ContextSources.memory(retriever, AgentPermissionSet.system()));

      assertThat(systemTextOf(privileged.buildRequest(config(), "Kafka", List.of(), List.of())))
          .contains("用户喜欢用 Kafka")
          .contains("Kafka 集群口令轮换");
      // 记账同源：MEMORY 层 token = 该段文本长度 / 4
      assertThat(assembler.composition(config(), "Kafka", List.of(), List.of()).estimatedTokens())
          .containsEntry(ContextLayer.MEMORY, expectedMemory.length() / 4)
          .containsEntry(ContextLayer.SKILL_INDEX, 0);
    }
  }

  /**
   * 段序恒为 {@link ContextLayer} 的声明序（prompt-cache 前缀契约）：SYSTEM → SKILL_INDEX → LOADED_SKILLS →
   * MEMORY。
   */
  @Test
  void layersFollowContextLayerDeclarationOrder() {
    writeSkill("alpha", "第一个技能", "ALPHA 正文");
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());
    try (MemoryStore store = MemoryStore.open(tempDir.resolve("mosire.db"))) {
      store.remember("偏好", "用户喜欢用 Kafka", "local", AccessToken.DEFAULT);
      BasicContextAssembler both =
          new BasicContextAssembler(
              ContextPolicy.defaults(),
              new ContextSources(
                  catalog,
                  Set.of("alpha"),
                  new MemoryRetriever(store),
                  AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                  MemoryRetriever.DEFAULT_K));

      String full = systemTextOf(both.buildRequest(config(), "Kafka", List.of(), List.of()));
      int system = full.indexOf(PROMPT);
      int index = full.indexOf(SKILL_INDEX_HEADER);
      int loaded = full.indexOf(LOADED_SKILLS_HEADER);
      int memory = full.indexOf(MEMORY_HEADER);

      assertThat(system).isEqualTo(0);
      assertThat(index).isGreaterThan(system);
      assertThat(loaded).isGreaterThan(index);
      assertThat(memory).isGreaterThan(loaded);
      assertThat(full).endsWith("- (偏好) 用户喜欢用 Kafka");
    }
  }

  // ---------- 空层不占位 ----------

  /**
   * 有协作者但没内容（空目录 / 无命中 / 空白查询）→ 一个段落都不追加：输出与未接线逐字节相同，不留空行或空标题。
   *
   * <p>这条同时守住 P2-3 的 byte-identical 保证在新增构造上成立。
   */
  @Test
  void sourcesWithoutContentAddNoSegmentAtAll() {
    SkillCatalog emptyCatalog = new SkillCatalog();
    emptyCatalog.scan(tempDir.resolve("no-such-skills-dir"));
    try (MemoryStore store = MemoryStore.open(tempDir.resolve("mosire.db"))) {
      store.remember("偏好", "用户喜欢用 Kafka", "local", AccessToken.DEFAULT);
      BasicContextAssembler wired =
          new BasicContextAssembler(
              ContextPolicy.defaults(),
              new ContextSources(
                  emptyCatalog,
                  Set.of("ghost"),
                  new MemoryRetriever(store),
                  AgentPermissionSet.unrestricted(AccessToken.DEFAULT),
                  MemoryRetriever.DEFAULT_K));
      BasicContextAssembler unwired = new BasicContextAssembler();

      // 未命中的查询 → 检索结果为空 → 无 MEMORY 段
      assertThat(wired.buildRequest(config(), "今天天气", List.of(), List.of()))
          .isEqualTo(unwired.buildRequest(config(), "今天天气", List.of(), List.of()));
      // 空白查询：MemoryRetriever 契约返回空列表 → 同样无段（不退化成全量倾倒）
      assertThat(wired.buildRequest(config(), "   ", List.of(), List.of()))
          .isEqualTo(unwired.buildRequest(config(), "   ", List.of(), List.of()));
      // 技能目录为空（未配置技能）：无 SKILL_INDEX/LOADED_SKILLS 段
      assertThat(systemTextOf(wired.buildRequest(config(), "今天天气", List.of(), List.of())))
          .isEqualTo(SYSTEM_TEXT);
      assertThat(wired.composition(config(), "今天天气", List.of(), List.of()).estimatedTokens())
          .containsEntry(ContextLayer.SKILL_INDEX, 0)
          .containsEntry(ContextLayer.LOADED_SKILLS, 0)
          .containsEntry(ContextLayer.MEMORY, 0);
    }
  }

  // ---------- 装配不变式 ----------

  /**
   * 自相矛盾的内容源在构造期就拒：与其让每回合静默少一层（或抛 NPE），不如装配时立刻响亮地失败。
   *
   * <p>"预载集非空却没有目录"是纯粹的接线错误；"接了记忆却没有调用者身份"会让记忆层无法做可见性过滤——两者都不是运行期数据问题。
   */
  @Test
  void inconsistentSourcesAreRejectedAtConstruction() {
    assertThatThrownBy(() -> new ContextSources(null, Set.of("alpha"), null, null, 8))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("alpha");
    assertThatThrownBy(() -> new ContextSources(null, Set.of(), null, null, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ContextSources.memory(null, AgentPermissionSet.system(), 8))
        .isInstanceOf(NullPointerException.class);

    try (MemoryStore store = MemoryStore.open(tempDir.resolve("mosire.db"))) {
      MemoryRetriever retriever = new MemoryRetriever(store);
      // 记忆可见性按身份令牌过滤：没有身份就没有可见性语义，明确拒掉而不是默认放行
      assertThatThrownBy(() -> new ContextSources(null, Set.of(), retriever, null, 8))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> ContextSources.memory(retriever, null))
          .isInstanceOf(NullPointerException.class);
      // 合法形态照常：只接记忆、只接技能、两者都接
      assertThat(ContextSources.memory(retriever, AgentPermissionSet.system()).hasMemory())
          .isTrue();
      assertThat(ContextSources.skills(new SkillCatalog()).hasSkills()).isTrue();
      assertThat(ContextSources.none().hasSkills()).isFalse();
      assertThat(ContextSources.none().hasMemory()).isFalse();
    }
  }

  // ---------- 夹具 ----------

  private static AgentConfig config() {
    return AgentConfig.builder("main").systemPrompt(PROMPT).build();
  }

  private static String systemTextOf(LlmRequest request) {
    return ((ContentPart.Text) request.messages().get(0).content().get(0)).text();
  }

  private Path skillsDir() {
    return tempDir.resolve("skills");
  }

  /** 落盘 {@code <skillsDir>/<dirName>/SKILL.md}（frontmatter 的 name ≡ 目录名，正文只留 body）。 */
  private void writeSkill(String dirName, String description, String body) {
    writeRawSkill(
        dirName, "---\nname: " + dirName + "\ndescription: " + description + "\n---\n" + body);
  }

  /** 直接写入 SKILL.md 原文（测坏 frontmatter 形态）。 */
  private void writeRawSkill(String dirName, String content) {
    Path dir = skillsDir().resolve(dirName);
    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(Skill.SKILL_FILE_NAME), content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 扫描后删掉 SKILL.md：目录快照里技能仍在，读盘必然失败。 */
  private void deleteBodyOf(String dirName) {
    try {
      Files.delete(skillsDir().resolve(dirName).resolve(Skill.SKILL_FILE_NAME));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 扫描后把正文写成没有 frontmatter 的内容：文件在、内容坏。 */
  private void corruptBodyOf(String dirName) {
    writeRawSkill(dirName, "没有 frontmatter 的正文");
  }
}
