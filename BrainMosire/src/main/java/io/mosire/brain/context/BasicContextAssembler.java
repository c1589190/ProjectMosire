package io.mosire.brain.context;

import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.ToolDef;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.brain.memory.MemoryRecord;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.skills.Skill;
import io.mosire.brain.skills.SkillCatalog;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基础实现：system = 行为提示 + 工具目录（一行一个名字+描述）；历史逐条原样；user 原样；工具定义照传。
 *
 * <p>内部按 {@link ContextLayer} 固定层序组装段落（{@code Segment}），目前 SYSTEM（恒为前缀）、SKILL_INDEX、
 * LOADED_SKILLS、MEMORY 四层有内容源，其余（RULES/COMPACT_SUMMARY）留给后续任务——接入时在 {@code systemSegments}
 * 按声明序插入，既有的段落字节不动（prompt-cache 前缀契约）。{@link ContextPolicy} 作为每层预算的来源随构造注入（当前只记账不 截断，预算是建议值，不改变输出）。
 *
 * <p><b>内容源与身份（P2-4 接线，见 {@link ContextSources}）</b>：技能三档中的前两档与记忆检索结果由 {@link ContextSources}
 * 供给；{@link ContextSources#none()}（既有两个构造的取法）表示都没接线，输出与接线前逐字节一致。
 *
 * <p><b>为什么身份是每实例而非每请求</b>：{@link ContextAssembler#buildRequest} 的入参没有调用者权限集，而记忆可见性必须按
 * 令牌级别过滤。与其改接口签名（会波及 {@code AgentPipeline} 与 A2A/AG-UI 路径，并动摇 P2-3 的逐字节断言），不如承认现状： assembler 本就是每
 * runtime 一个实例（{@code AgentRuntime} 构造注入），调用者身份随实例固定。代价是同一实例不能服务多身份 ——跨身份请各自装配实例。
 *
 * <p><b>渲染格式（确定性契约，逐字节 pin 在测试里）</b>：段间以空行（{@code "\n\n"}）分隔，段落自身不带头尾空白；非空段的格式为
 *
 * <ul>
 *   <li>{@code SKILL_INDEX}：{@code "你有以下技能可用（只列名称与用途）："} + 换行 + {@link
 *       SkillCatalog#listing()}（扫描序，每行 {@code "- 名字: 用途"}）；自动预载失败的技能在该行尾追加 {@code
 *       "（正文加载失败：<简短原因>）"}——健康行一个字节都不加；
 *   <li>{@code LOADED_SKILLS}：{@code "已激活技能正文："} + 空行 + 每技能一块 {@code "## 技能 <名>（资源目录：<dir>）"} + 换行
 *       + 正文，块间空行；块序 = 目录扫描序（不是预载集顺序）；
 *   <li>{@code MEMORY}：{@code "与本轮输入相关的记忆（按相关度排序，最多 <k> 条）："} + 每行 {@code "- (<主题>) <正文>"}，行序 =
 *       检索器返回序。
 * </ul>
 *
 * <p><b>空层不占位</b>：层的内容缺失或为空（技能目录空、无命中、空白查询、预载名字在目录里不存在）时<b>不产出段落</b>，而不是产出空
 * 串——否则会多出空行、多出标题，前缀也就不是原来的字节了。
 */
public final class BasicContextAssembler implements ContextAssembler {

  /** 段落分隔：一个空行（既有 SYSTEM 段的三段结构自身已用空行分块，故只有多段时才会出现）。 */
  private static final String SEGMENT_SEPARATOR = "\n\n";

  private static final String SKILL_INDEX_HEADER = "你有以下技能可用（只列名称与用途）：";

  private static final String LOADED_SKILLS_HEADER = "已激活技能正文：";

  /** MEMORY 段标题前缀；条数上限（受 {@link ContextSources#memoryK()} 控制）拼在其后，让模型知道这不是全量。 */
  private static final String MEMORY_HEADER_PREFIX = "与本轮输入相关的记忆（按相关度排序，最多 ";

  private static final String MEMORY_HEADER_SUFFIX = " 条）：";

  /** 第二档单技能块标题：技能名 + 第三档资源锚点（{@code references/}、{@code scripts/} 相对它按需读取）。 */
  private static final String LOADED_SKILL_TITLE_PREFIX = "## 技能 ";

  private static final String LOADED_SKILL_TITLE_SEPARATOR = "（资源目录：";

  /** 自动预载失败时加在该技能目录行尾的标注：失败必须可见（模型看得到"这个技能这回合没带上"），但不值得崩掉整个回合。 */
  private static final String LOAD_FAILURE_MARKER_PREFIX = "（正文加载失败：";

  private static final String LOAD_FAILURE_MARKER_SUFFIX = "）";

  /** 失败原因进 prompt 前截断到的字符数上限：异常消息里的路径可能很长，目录档要便宜。 */
  private static final int MAX_LOAD_FAILURE_REASON_CHARS = 120;

  private final ContextPolicy policy;
  private final ContextSources sources;

  /** 使用默认预算策略（{@link ContextPolicy#defaults()}）与空内容源（只组装 SYSTEM 层）。 */
  public BasicContextAssembler() {
    this(ContextPolicy.defaults());
  }

  public BasicContextAssembler(ContextPolicy policy) {
    this(policy, ContextSources.none());
  }

  /**
   * 注入内容源（技能目录 / 记忆检索）。
   *
   * @param policy 每层预算
   * @param sources 装配期协作者；{@link ContextSources#none()} = 与既有构造同行为
   */
  public BasicContextAssembler(ContextPolicy policy, ContextSources sources) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.sources = Objects.requireNonNull(sources, "sources");
  }

  /** 本装配器生效的每层预算策略（诊断与 {@code context} CLI 用）。当前预算只是建议值——接入截断/淘汰前不影响 {@code buildRequest} 输出。 */
  public ContextPolicy policy() {
    return policy;
  }

  /** 本装配器接线的内容源（诊断用；{@link ContextSources#none()} = 未接线）。 */
  public ContextSources sources() {
    return sources;
  }

  @Override
  public LlmRequest buildRequest(
      AgentConfig config, String userMessage, List<LlmMessage> history, List<AgentTool> tools) {
    StringBuilder system = new StringBuilder();
    appendSegments(system, systemSegments(config, userMessage, tools));

    // 契约：[system] + history（逐条原样）+ [user]——history 原样回灌以保 prompt-cache 前缀稳定
    List<LlmMessage> messages = new ArrayList<>(history.size() + 2);
    messages.add(LlmMessage.system(system.toString()));
    messages.addAll(history);
    messages.add(LlmMessage.user(userMessage));
    List<ToolDef> toolDefs =
        tools.stream().map(t -> new ToolDef(t.name(), t.description(), t.jsonSchema())).toList();
    return new LlmRequest(messages, toolDefs);
  }

  @Override
  public ContextComposition composition(
      AgentConfig config, String userMessage, List<LlmMessage> history, List<AgentTool> tools) {
    Map<ContextLayer, Integer> tokens = new EnumMap<>(ContextLayer.class);
    for (ContextLayer layer : ContextLayer.values()) {
      tokens.put(layer, 0);
    }
    for (Segment segment : systemSegments(config, userMessage, tools)) {
      tokens.merge(segment.layer(), estimateTokens(segment.text()), Integer::sum);
    }
    return new ContextComposition(tokens);
  }

  /** 一个层内段落：层归属 + 原文（composition 按它记账，buildRequest 按层序拼接）。 */
  private record Segment(ContextLayer layer, String text) {}

  /** 一个预载技能：元数据（含第三档资源锚点）+ 读到的正文。 */
  private record LoadedSkill(Skill skill, String body) {}

  /**
   * 一次自动预载的结果：读成功的正文块（扫描序）+ 读失败的技能名 → 简短原因（扫描序）。
   *
   * <p>失败不丢：正文块照常只剩成功的，失败信息转由 {@code SKILL_INDEX} 对应行尾标注披露——"绝不静默跳过"要靠可见性兑现， 不靠崩回合。
   */
  private record PreloadedSkills(List<LoadedSkill> bodies, Map<String, String> failures) {

    static PreloadedSkills empty() {
      return new PreloadedSkills(List.of(), Map.of());
    }
  }

  /**
   * 按 {@link ContextLayer} 声明顺序产出 system 消息的段落。SYSTEM 恒为第一段且字节与接线前一致；SKILL_INDEX → LOADED_SKILLS →
   * MEMORY 依次追加（RULES/COMPACT_SUMMARY 仍为空占位，留给后续任务）。空内容的层不产出段落。
   *
   * <p>需要 {@code userMessage}：MEMORY 层的检索词就是本轮用户输入（与 {@code buildRequest} 同源，composition
   * 因此会执行同一次只读检索——这是"诊断不得失真"的必要代价）。
   */
  private List<Segment> systemSegments(
      AgentConfig config, String userMessage, List<AgentTool> tools) {
    List<Segment> segments = new ArrayList<>(4);
    segments.add(new Segment(ContextLayer.SYSTEM, systemText(config, tools)));
    if (sources.hasSkills()) {
      // 顺序有讲究：先读第二档正文（load 会记下"刚激活"），再渲染第一档目录——listing 在预算吃紧时按激活新近度淘汰，
      // 先记激活能让目录档从第一回合起就是稳态，而不是第二回合因首回合的激活而改写前缀（前缀只追加不改写）
      PreloadedSkills preloaded = loadPreloadedSkills();
      addIfPresent(segments, ContextLayer.SKILL_INDEX, skillIndexText(preloaded));
      addIfPresent(segments, ContextLayer.LOADED_SKILLS, loadedSkillsText(preloaded.bodies()));
    }
    if (sources.hasMemory()) {
      addIfPresent(segments, ContextLayer.MEMORY, memoryText(userMessage));
    }
    return segments;
  }

  /** SYSTEM 段：行为提示 + 工具目录 + 收尾指令（P2-3 起逐字节冻结，新增内容源一律另起段落，不改这里）。 */
  private static String systemText(AgentConfig config, List<AgentTool> tools) {
    StringBuilder text = new StringBuilder(config.systemPrompt());
    text.append("\n\n你有以下工具可用（一律直接调用，不要凭空想象它们不存在）：\n");
    if (tools.isEmpty()) {
      text.append("（当前没有任何可用工具）\n");
    } else {
      for (AgentTool tool : tools) {
        text.append("- ").append(tool.name());
        if (!tool.description().isBlank()) {
          text.append(": ").append(tool.description());
        }
        text.append('\n');
      }
    }
    text.append("\n完成用户请求后，请用纯文本回复结果，不要虚构工具执行过程。");
    return text.toString();
  }

  /** 第一档：目录档（名字 + 用途）；目录档为空（没扫到技能或预算为 0）时返回空串，由 {@link #addIfPresent} 挡下。 */
  private String skillIndexText(PreloadedSkills preloaded) {
    String listing = sources.skills().listing();
    if (listing.isBlank()) {
      return "";
    }
    return SKILL_INDEX_HEADER + "\n" + markLoadFailures(listing, preloaded.failures());
  }

  /**
   * 在目录档里把自动预载失败的技能行标出来：{@code （正文加载失败：<简短原因>）} 追加在 {@code "- <名>: "} 行尾。
   *
   * <p>只加在失败行、无失败时逐字节原样返回（ruling 1 的 byte-identity 由此不受影响）；失败集为空是常态， 这里也走同一路径，不搞特殊分支。
   */
  private static String markLoadFailures(String listing, Map<String, String> failures) {
    if (failures.isEmpty()) {
      return listing;
    }
    String[] lines = listing.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      for (Map.Entry<String, String> failure : failures.entrySet()) {
        if (lines[i].startsWith("- " + failure.getKey() + ": ")) {
          lines[i] =
              lines[i]
                  + LOAD_FAILURE_MARKER_PREFIX
                  + failure.getValue()
                  + LOAD_FAILURE_MARKER_SUFFIX;
          break;
        }
      }
    }
    return String.join("\n", lines);
  }

  /**
   * 第二档：自动预载技能的正文，块序 = 目录扫描序（不是预载集顺序——Set 无序，逐字节确定只能靠扫描序）。
   *
   * <p>预载集里目录没有的名字被<b>跳过而非抛错</b>：spec 里一个陈旧名字不该让每个回合都崩。做法是按目录遍历再查预载集，不存在的 名字根本不会被访问（{@link
   * SkillCatalog#load} 对未知名字是抛错的，不能拿它当存在性检查）。正文为空的技能不进第二档—— 只留一个空标题纯属占 token，且会让"空层不占位"破功。
   *
   * <p>目录里<b>有</b>这个技能、但读盘失败（被删/写坏）时<b>不抛</b>：自动预载是每回合都走的路，一次坏磁盘让 整个回合崩掉的代价远大于不带这份正文。失败改由 {@link
   * #markLoadFailures} 在目录档该行可见地披露（绝不静默跳过靠 可见性兑现，不靠崩）。注意边界在哪儿：<b>显式</b>激活路径（{@link
   * SkillCatalog#load}）的契约不变，未知名字与坏文件 照抛——那条路是调用方明确要求读这个技能，静默降级反而会掩盖错误。
   */
  private PreloadedSkills loadPreloadedSkills() {
    Set<String> preloaded = sources.preloadedSkills();
    if (preloaded.isEmpty()) {
      return PreloadedSkills.empty();
    }
    List<LoadedSkill> loaded = new ArrayList<>(preloaded.size());
    Map<String, String> failures = new LinkedHashMap<>();
    for (Skill skill : sources.skills().catalog()) {
      if (!preloaded.contains(skill.name())) {
        continue;
      }
      String body;
      try {
        body = sources.skills().load(skill.name());
      } catch (RuntimeException e) {
        failures.put(skill.name(), shortReason(e));
        continue;
      }
      if (!body.isBlank()) {
        loaded.add(new LoadedSkill(skill, body));
      }
    }
    return new PreloadedSkills(loaded, failures);
  }

  /** 失败原因收成单行短文本（异常类名与堆栈不进 prompt）：空白折叠、去首尾、超长尾部截断。 */
  private static String shortReason(RuntimeException e) {
    String message = e.getMessage();
    String reason = message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    reason = reason.replaceAll("\\s+", " ").strip();
    return reason.length() <= MAX_LOAD_FAILURE_REASON_CHARS
        ? reason
        : reason.substring(0, MAX_LOAD_FAILURE_REASON_CHARS) + "…";
  }

  private static String loadedSkillsText(List<LoadedSkill> loaded) {
    if (loaded.isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder(LOADED_SKILLS_HEADER);
    for (LoadedSkill skill : loaded) {
      text.append(SEGMENT_SEPARATOR)
          .append(LOADED_SKILL_TITLE_PREFIX)
          .append(skill.skill().name())
          .append(LOADED_SKILL_TITLE_SEPARATOR)
          .append(skill.skill().directory())
          .append('）')
          .append('\n')
          .append(skill.body());
    }
    return text.toString();
  }

  /**
   * MEMORY 段：以本轮用户输入为检索词、按装配期注入的调用者身份与 K 取回忆结果，行序 = 检索器返回序（不重排——重排就不是
   * 检索器的相关度序了）。无命中或空白查询（检索器契约：空白查询返回空列表）时返回空串。
   */
  private String memoryText(String userMessage) {
    List<MemoryRecord> hits =
        sources.memory().retrieve(userMessage, sources.identity(), sources.memoryK());
    if (hits.isEmpty()) {
      return "";
    }
    StringBuilder text =
        new StringBuilder(MEMORY_HEADER_PREFIX)
            .append(sources.memoryK())
            .append(MEMORY_HEADER_SUFFIX);
    for (MemoryRecord hit : hits) {
      text.append("\n- (").append(hit.topic()).append(") ").append(hit.text());
    }
    return text.toString();
  }

  /** 空串段落不入列：层的内容缺失时"没有段落"与"空段落"在拼接结果上必须可区分（后者会留下多余空行）。 */
  private static void addIfPresent(List<Segment> segments, ContextLayer layer, String text) {
    if (!text.isEmpty()) {
      segments.add(new Segment(layer, text));
    }
  }

  /** 按段落顺序拼接，段落之间插一个空行；只剩一段时逐字节等于该段自身（既有构造的 byte-identical 由此成立）。 */
  private static void appendSegments(StringBuilder target, List<Segment> segments) {
    for (int i = 0; i < segments.size(); i++) {
      if (i > 0) {
        target.append(SEGMENT_SEPARATOR);
      }
      target.append(segments.get(i).text());
    }
  }

  /** token 估算：≈ 字符数 / 4（整数除法、向下取整）——无真实分词器前的占位算法，后续波次换 JTokkit， 届时只改这一处。 */
  private static int estimateTokens(String text) {
    return text.length() / 4;
  }
}
