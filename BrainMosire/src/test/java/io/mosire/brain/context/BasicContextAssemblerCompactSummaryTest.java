package io.mosire.brain.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.skills.Skill;
import io.mosire.brain.skills.SkillCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BasicContextAssembler} 的 {@link ContextLayer#COMPACT_SUMMARY} 接线（T17/R5）。
 *
 * <p>三条不变量在此 pin：
 *
 * <ol>
 *   <li><b>空槽 = 没有这一层</b>：注入空槽的装配器与既有两参构造<b>逐字节一致</b>（不产段落、不留空行、记账为 0）——"未压缩时 byte-identical"
 *       的第一半（另一半在 {@code AgentPipelineCompactionTest}：未触门的回合请求不变）；
 *   <li><b>摘要段追加在最末</b>：它<b>不</b>改写 SYSTEM/SKILL_INDEX/LOADED_SKILLS/MEMORY 的字节（prompt-cache
 *       前缀契约），只是把新内容接在后面；
 *   <li><b>层内容只有一个来源</b>：槽。会话历史里恰好出现一条"摘要样"消息（档 3 落库后重启 load 回来的形态）<b>不</b>会使层里多出段落——
 *       同一份摘要出现两遍就是这么来的。
 * </ol>
 */
class BasicContextAssemblerCompactSummaryTest {

  private static final String PROMPT = "你是测试 Agent。";

  /**
   * 无工具时的 SYSTEM 段（与 {@code BasicContextAssemblerTest}/{@code BasicContextAssemblerSourcesTest}
   * 同源）。
   */
  private static final String SYSTEM_TEXT =
      PROMPT
          + "\n\n你有以下工具可用（一律直接调用，不要凭空想象它们不存在）：\n"
          + "（当前没有任何可用工具）\n"
          + "\n完成用户请求后，请用纯文本回复结果，不要虚构工具执行过程。";

  private static final String COMPACT_SUMMARY_HEADER = "此前会话的压缩摘要（较早的消息已被压缩，以下为要点）：";

  private static final String SKILL_INDEX_HEADER = "你有以下技能可用（只列名称与用途）：";

  private static final CompactSummary SUMMARY =
      new CompactSummary(
          "目标哨兵",
          List.of("完成哨兵"),
          List.of("待办哨兵"),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of());

  @TempDir Path tempDir;

  /**
   * 空槽与既有两参构造逐字节等价：system 正文相同、messages 相同、composition 的每层记账相同（COMPACT_SUMMARY 为 0）。
   *
   * <p>判别性：一个"空槽也产出层标题（空段落）"的实现会多出一个空行级的字节差，{@code isEqualTo} 与逐层记账两条断言都会红。
   */
  @Test
  void emptySlotKeepsTheAssemblerByteIdenticalToTheTwoArgConstructor() {
    BasicContextAssembler withoutSlot = new BasicContextAssembler(ContextPolicy.defaults());
    BasicContextAssembler withEmptySlot =
        new BasicContextAssembler(
            ContextPolicy.defaults(), ContextSources.none(), CompactSummarySlot.empty());

    LlmRequest before = withoutSlot.buildRequest(config(), "干活", List.of(), List.of());
    LlmRequest after = withEmptySlot.buildRequest(config(), "干活", List.of(), List.of());

    assertThat(systemTextOf(after)).isEqualTo(systemTextOf(before)).isEqualTo(SYSTEM_TEXT);
    assertThat(after.messages()).isEqualTo(before.messages());
    assertThat(withEmptySlot.composition(config(), "干活", List.of(), List.of()).estimatedTokens())
        .isEqualTo(withoutSlot.composition(config(), "干活", List.of(), List.of()).estimatedTokens())
        .containsEntry(ContextLayer.COMPACT_SUMMARY, 0);
  }

  /**
   * 有摘要时的确切字节：SYSTEM 段 + 空行 + 层标题 + 换行 + {@link CompactSummary#render()}；记账与段长同源。
   *
   * <p>判别性：摘要正文与层标题的分隔形态（这里是单个换行，不是空行）一旦改了就红——它是请求字节，属于 prompt-cache 前缀的一部分。
   */
  @Test
  void filledSlotAppendsTheSummarySegmentAfterTheSystemSegment() {
    CompactSummarySlot slot = CompactSummarySlot.empty();
    slot.set(SUMMARY);
    BasicContextAssembler assembler =
        new BasicContextAssembler(ContextPolicy.defaults(), ContextSources.none(), slot);

    LlmRequest request = assembler.buildRequest(config(), "干活", List.of(), List.of());
    String summarySegment = COMPACT_SUMMARY_HEADER + "\n" + SUMMARY.render();

    assertThat(systemTextOf(request)).isEqualTo(SYSTEM_TEXT + "\n\n" + summarySegment);
    assertThat(assembler.composition(config(), "干活", List.of(), List.of()).estimatedTokens())
        .containsEntry(ContextLayer.COMPACT_SUMMARY, summarySegment.length() / 4);
  }

  /**
   * R3 判据的装配侧：摘要段追加<b>在后</b>，持久层段落（这里是 SYSTEM 与 SKILL_INDEX）一个字节不改。
   *
   * <p>判别性：一个"把摘要写在 SYSTEM 之前"或"把技能目录挤掉"的实现（都会动摇前缀契约）在 {@code startsWith} 与两条 {@code containsEntry}
   * 上必红。
   */
  @Test
  void summarySegmentLeavesThePersistentLayersByteIntact() {
    writeSkill("alpha", "第一个技能", "ALPHA-正文");
    SkillCatalog catalog = new SkillCatalog();
    catalog.scan(skillsDir());
    ContextSources sources = ContextSources.skills(catalog);

    BasicContextAssembler before = new BasicContextAssembler(ContextPolicy.defaults(), sources);
    String beforeText = systemTextOf(before.buildRequest(config(), "干活", List.of(), List.of()));

    CompactSummarySlot slot = CompactSummarySlot.empty();
    slot.set(SUMMARY);
    BasicContextAssembler after =
        new BasicContextAssembler(ContextPolicy.defaults(), sources, slot);
    LlmRequest request = after.buildRequest(config(), "干活", List.of(), List.of());
    String afterText = systemTextOf(request);

    // 持久层原样在前：SYSTEM + SKILL_INDEX 的字节一个不动（摘要只追加）
    assertThat(beforeText).doesNotContain("目标哨兵"); // 压缩前，请求里没有摘要的任何字节
    assertThat(afterText).startsWith(beforeText);
    assertThat(afterText)
        .isEqualTo(beforeText + "\n\n" + COMPACT_SUMMARY_HEADER + "\n" + SUMMARY.render());
    assertThat(after.composition(config(), "干活", List.of(), List.of()).estimatedTokens())
        .containsEntry(
            ContextLayer.SKILL_INDEX, (SKILL_INDEX_HEADER + "\n- alpha: 第一个技能").length() / 4)
        .containsEntry(
            ContextLayer.COMPACT_SUMMARY,
            (COMPACT_SUMMARY_HEADER + "\n" + SUMMARY.render()).length() / 4);
  }

  /**
   * 层内容只有一个来源：会话历史里出现"摘要样"消息（档 3 落库后重启 load 的形态）时层里<b>不</b>多段落——它在 messages 里逐条回放，
   * 层保持空（否则同一份摘要在请求里出现两遍）。
   *
   * <p>判别性：一个"顺手把历史里的摘要消息也塞进 COMPACT_SUMMARY 层"的实现会让层记账从 0 变正，这里必红。
   */
  @Test
  void historySummaryMessageIsReplayedWithoutDuplicatingTheLayer() {
    BasicContextAssembler assembler =
        new BasicContextAssembler(
            ContextPolicy.defaults(), ContextSources.none(), CompactSummarySlot.empty());
    LlmMessage summaryMessage =
        LlmMessage.assistant(List.of(new ContentPart.Text(SUMMARY.render())));

    LlmRequest request = assembler.buildRequest(config(), "干活", List.of(summaryMessage), List.of());

    assertThat(request.messages())
        .containsExactly(LlmMessage.system(SYSTEM_TEXT), summaryMessage, LlmMessage.user("干活"));
    assertThat(
            assembler
                .composition(config(), "干活", List.of(summaryMessage), List.of())
                .estimatedTokens())
        .containsEntry(ContextLayer.COMPACT_SUMMARY, 0);
  }

  /** 装配器的摘要来源是"调用时的槽值"：清空槽后段落消失，字节回到空槽形态（档 3 落库后清槽即此路径）。 */
  @Test
  void clearingTheSlotRemovesTheSegmentAgain() {
    CompactSummarySlot slot = CompactSummarySlot.empty();
    slot.set(SUMMARY);
    BasicContextAssembler assembler =
        new BasicContextAssembler(ContextPolicy.defaults(), ContextSources.none(), slot);

    assertThat(systemTextOf(assembler.buildRequest(config(), "干活", List.of(), List.of())))
        .contains(COMPACT_SUMMARY_HEADER);

    slot.clear();

    assertThat(systemTextOf(assembler.buildRequest(config(), "干活", List.of(), List.of())))
        .isEqualTo(SYSTEM_TEXT);
  }

  private static AgentConfig config() {
    return AgentConfig.builder("main").systemPrompt(PROMPT).build();
  }

  private static String systemTextOf(LlmRequest request) {
    return ((ContentPart.Text) request.messages().get(0).content().get(0)).text();
  }

  private Path skillsDir() {
    return tempDir.resolve("skills");
  }

  private void writeSkill(String dirName, String description, String body) {
    Path dir = skillsDir().resolve(dirName);
    try {
      Files.createDirectories(dir);
      Files.writeString(
          dir.resolve(Skill.SKILL_FILE_NAME),
          "---\nname: " + dirName + "\ndescription: " + description + "\n---\n" + body,
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
