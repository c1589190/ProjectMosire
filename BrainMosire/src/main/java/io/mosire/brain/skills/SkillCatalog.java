package io.mosire.brain.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 技能目录（开发计划 §4.5）：把 {@code <skillsDir>/<name>/SKILL.md} 组织成<b>三档渐进披露</b>的供给面。
 *
 * <p>三档与省 token 的关系（R1 Token Economy）：
 *
 * <ol>
 *   <li><b>目录档</b>——{@link #scan(Path)} 只读 frontmatter，{@link #catalog()} / {@link #listing()} 给出
 *       "名字 + 一句话用途（+ allowed-tools 提示）"的元数据；未激活的技能每回合只占一行；
 *   <li><b>正文档</b>——{@link #load(String)} 才读 SKILL.md 正文，进 {@code ContextLayer.LOADED_SKILLS}；
 *   <li><b>资源档</b>——{@code references/}、{@code scripts/} 以 {@link Skill#directory()} 为锚点按需读取，本类不碰。
 * </ol>
 *
 * <p><b>listing 的确定性（prompt-cache 前缀契约）</b>：{@link #listing()} 的输出恒为<b>扫描序</b>（目录名升序）——
 * 层序与内容一旦发布就只追加不改写，重排会让 prompt-cache 前缀失效。超预算时的淘汰同样确定：只从最久未激活的一端丢，平局
 * （含"都没激活过"）按扫描序靠前者优先留下（被丢的一律是优先级尾巴，不会出现留下旧的、丢掉新的），于是预算吃紧时 listing 退化为扫描序前缀——新增技能只追加在尾部，不挤走既有行。
 *
 * <p>预算单位是<b>字符数</b>（同 {@link #MAX_ENTRY_CHARS} 口径，token ≈ 字符 / 4，见 {@code ContextComposition}
 * 的估算法）；后续接线时可由 {@code ContextPolicy.budgetFor(ContextLayer.SKILL_INDEX)} 换算注入。
 *
 * <p>线程安全：元数据快照与激活时间戳都是并发可见的（scan 后可被读线程安全观测）；但 {@code scan}/{@code load} 自身按 运行时的串行调用假设设计（同 {@code
 * AgentRuntime.chat} 的串行化不变量），不做并发写-写互斥。
 */
public final class SkillCatalog {

  /** 默认 listing 预算（字符数）：约 1024 token，够列十来个技能，相对 SYSTEM 层的默认预算仍是小头。 */
  public static final int DEFAULT_LISTING_BUDGET = 4096;

  /**
   * 单条目录行的上限（字符数）：name + description + allowed-tools 提示的复合行在此截断（description 自身的 1–1024 校验见 {@link
   * Skill}）。
   */
  public static final int MAX_ENTRY_CHARS = 1536;

  /** 截断标记：目录档要便宜，解释性提示按行省 token——一个省略号足以让模型知道"这里还有内容，需要就激活技能"。 */
  private static final String TRUNCATION_MARKER = "…";

  private static final String LINE_SEPARATOR = "\n";

  private final int listingBudget;

  /** 技能名 → 激活时间戳（单调递增）；未激活的技能无条目 = 时间戳 0（最不常用）。 */
  private final Map<String, Long> activatedAt = new ConcurrentHashMap<>();

  private final AtomicLong activationClock = new AtomicLong();

  /** 扫描序元数据快照（不可变；volatile 保证 scan 后其它线程可见新快照而非撕裂的旧状态）。 */
  private volatile List<Skill> snapshot = List.of();

  /** 使用 {@link #DEFAULT_LISTING_BUDGET}。 */
  public SkillCatalog() {
    this(DEFAULT_LISTING_BUDGET);
  }

  /**
   * @param listingBudget 目录档总预算（字符数）；0 = 不披露目录（合法配置，不是"预算为零仍全列"），负数即配置错误
   */
  public SkillCatalog(int listingBudget) {
    if (listingBudget < 0) {
      throw new IllegalArgumentException("listing 预算不能为负: " + listingBudget);
    }
    this.listingBudget = listingBudget;
  }

  /** 目录档总预算（字符数）。 */
  public int budget() {
    return listingBudget;
  }

  /**
   * 扫描技能目录（第一档）：只取每个 {@code <name>/SKILL.md} 的 frontmatter，正文不进元数据、不入上下文。
   *
   * <p>扫描序 = 子目录名的升序（{@link Files#list} 的返回顺序依赖文件系统，不能作为序的来源），这样 listing 与激活时间戳 平局判定才有跨机器的确定性。
   *
   * <p>边界：目录<b>不存在</b> = 未配置技能（空目录，不是错误——技能是可选的）；<b>存在但不是目录</b>、 或子目录里没有 SKILL.md、或 frontmatter
   * 校验失败 = 配置错误，抛 {@link IllegalArgumentException}（绝不静默跳过， 否则"写坏了"与"没写"无从区分）。以 {@code .}
   * 开头的子目录跳过——{@code learn_skill} 的技能库要落 git 版控， 同一目录下会出现 {@code .git} 之类的隐藏项。
   *
   * <p>重复扫描已激活技能的时间戳会保留（同 {@code load} 语义），避免重扫把 listing 打乱、白白丢掉 prompt-cache 前缀。
   *
   * @param skillsDir 技能根目录
   */
  public void scan(Path skillsDir) {
    Objects.requireNonNull(skillsDir, "skillsDir");
    if (!Files.exists(skillsDir)) {
      snapshot = List.of();
      activatedAt.clear();
      return;
    }
    if (!Files.isDirectory(skillsDir)) {
      throw new IllegalArgumentException("技能目录不是目录: " + skillsDir);
    }
    List<Skill> loaded = new ArrayList<>();
    try (Stream<Path> children = Files.list(skillsDir)) {
      for (Path child :
          children.filter(Files::isDirectory).filter(p -> !isHidden(p)).sorted().toList()) {
        loaded.add(SkillLoader.readMetadata(child));
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("技能目录无法扫描: " + skillsDir, e);
    }
    snapshot = List.copyOf(loaded);
    Set<String> names = loaded.stream().map(Skill::name).collect(Collectors.toSet());
    activatedAt.keySet().retainAll(names);
  }

  /** 第一档元数据快照（扫描序）：不含正文——正文只在 {@link #load(String)} 时读。返回列表不可变，可安全共享。 */
  public List<Skill> catalog() {
    return snapshot;
  }

  /**
   * 第二档：按需读取技能正文，并记下"刚被激活"（listing 预算吃紧时据此淘汰）。
   *
   * <p>正文每次从盘上重读（技能目录可被 Agent 自己重写）：读成功才记激活，读失败不留半套状态。
   *
   * @param name 技能名
   * @return SKILL.md 正文
   * @throws IllegalArgumentException 技能不存在（catalog 是唯一入口，未知名字不该被当成空技能）
   */
  public String load(String name) {
    Objects.requireNonNull(name, "name");
    Skill skill = find(name);
    if (skill == null) {
      throw new IllegalArgumentException("未知技能: " + name);
    }
    String body = SkillLoader.readBody(skill);
    activatedAt.put(skill.name(), activationClock.incrementAndGet());
    return body;
  }

  /**
   * 第一档的可披露文本（每行一条技能），供 {@code ContextLayer.SKILL_INDEX} 携带：恒按扫描序输出，行内不出现正文。
   *
   * <p>规则：单行超过 {@link #MAX_ENTRY_CHARS} → 头部截断 + {@link #TRUNCATION_MARKER}；全部行加起来超过 {@link
   * #budget()} →
   * 丢最久未激活的（平局按扫描序靠前者优先），按"最近激活优先"自上而下装填，<b>遇到第一个装不下的就停</b>。停而不是跳过，是为了保证被丢的一律是优先级尾巴：绝不会出现"留下没激活过的小行、丢掉刚激活的大行"；代价是优先级高的大行放不下时目录可能比预算允许的更短——确定性优先于装填率。
   */
  public String listing() {
    List<Skill> inScanOrder = snapshot;
    if (inScanOrder.isEmpty() || listingBudget == 0) {
      return "";
    }
    Map<String, String> rendered = new LinkedHashMap<>();
    for (Skill skill : inScanOrder) {
      rendered.put(skill.name(), renderEntry(skill));
    }
    // 优先级：激活时间戳降序；时间戳相同（含都没激活过）时 List.sort 稳定，保持扫描序 —— 靠前者优先
    List<Skill> byPriority = new ArrayList<>(inScanOrder);
    byPriority.sort(Comparator.comparingLong(this::activationStamp).reversed());
    Set<String> kept = new TreeSet<>();
    int used = 0;
    for (Skill skill : byPriority) {
      String line = rendered.get(skill.name());
      int cost = line.length() + (kept.isEmpty() ? 0 : LINE_SEPARATOR.length());
      if (used + cost > listingBudget) {
        // 装不下：就地停止装填（不是跳过它继续试后面的）——留下的条目优先级一律高于被丢的，
        // 否则会出现"留下没激活过的小行、丢掉刚激活的大行"，即淘汰的恰恰是最常用的那条
        break;
      }
      kept.add(skill.name());
      used += cost;
    }
    return inScanOrder.stream()
        .filter(skill -> kept.contains(skill.name()))
        .map(skill -> rendered.get(skill.name()))
        .collect(Collectors.joining(LINE_SEPARATOR));
  }

  private Skill find(String name) {
    for (Skill skill : snapshot) {
      if (skill.name().equals(name)) {
        return skill;
      }
    }
    return null;
  }

  private long activationStamp(Skill skill) {
    return activatedAt.getOrDefault(skill.name(), 0L);
  }

  /** 渲染一条目录行；{@code allowed-tools} 排序后输出（{@code Set.copyOf} 的迭代顺序未定义，排序保 listing 逐字节稳定）。 */
  private String renderEntry(Skill skill) {
    StringBuilder line =
        new StringBuilder("- ").append(skill.name()).append(": ").append(skill.description());
    if (!skill.allowedTools().isEmpty()) {
      line.append(" (allowed-tools: ")
          .append(String.join(", ", new TreeSet<>(skill.allowedTools())))
          .append(')');
    }
    return line.length() <= MAX_ENTRY_CHARS ? line.toString() : truncateEntry(line.toString());
  }

  /**
   * 头部截断 + 省略标记，总长 ≤ {@link #MAX_ENTRY_CHARS}（无代理对时恰为上限）。
   *
   * <p>截断点避开 UTF-16 代理对：若切点正好落在高代理项与低代理项之间，就再退一个字符——否则行尾留下孤立高代理项， 这份 listing 进上下文/落事件后就是坏字符串（emoji
   * 描述在 1535 字符处被腰斩并非理论问题）。退一格使实际内容少 1 字符（1535），上限仍是"≤"，长度口径不变（UTF-16 code unit）。
   */
  private static String truncateEntry(String line) {
    int cut = MAX_ENTRY_CHARS - TRUNCATION_MARKER.length();
    if (Character.isHighSurrogate(line.charAt(cut - 1))) {
      cut--;
    }
    return line.substring(0, cut) + TRUNCATION_MARKER;
  }

  private static boolean isHidden(Path path) {
    Path name = path.getFileName();
    return name != null && name.toString().startsWith(".");
  }
}
