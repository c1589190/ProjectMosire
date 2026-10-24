package io.mosire.agentlib.context;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 上下文窗口：一个会话此刻看得见的那串消息 + 它的标识与中性读数。
 *
 * <p><b>为什么它在库里（AgentLib）而不是在某个 Agent 运行时里</b>：把一轮对话拆开看，任何宿主做的都是同一段三步——<b>按窗口内容拼装请求 → 发信 →
 * 把本轮输入与答复回灌进窗口</b>。本仓 Brain 的管线与隔壁自建的 runner 各自实现了这三步（"水合 → chat → 把 assistant 追加回去"
 * 的顺序都一样），差别只在"谁来拼、头部放什么、失败怎么办"这些<b>策略</b>上。窗口本身不含任何 Agent 假设：它只是一串 {@link LlmMessage}
 * 的有序容器，因此下沉到这里，让多个宿主共用同一份结构语义（见 {@code 设计-上下文窗口下沉.md} §3.1）。
 *
 * <p><b>它刻意不认识的东西（每条都是边界，不是遗漏）</b>：
 *
 * <ul>
 *   <li><b>不认识会话存储</b>：窗口不 load、不 append、不校验"落库了没有"。持久化是宿主策略，窗口只报中性事实——"自上次 {@link #markSaved()}
 *       以来尾部多了哪些消息"（见 {@link #unsavedTail()}）。真实用法里存在<b>刻意不落库</b>的消息（每轮现插的提醒），
 *       若把"落库"写进窗口的不变量，那种用法当场非法。
 *   <li><b>不认识技能 / 记忆 / 压缩策略</b>：这些是"往窗口里放什么"的供给方，窗口不反向依赖它们。窗口只认识一个中性的 compact
 *       标记，并据此生成可见投影；摘要怎么产生、何时产生仍由宿主/压缩器决定。
 *   <li><b>不认识 Brain</b>：本包不得 import 下游模块（Brain / Main）的任何类型（模块边界，见 AGENTS.md §0.1）——反过来也正是它可复用的原因。
 * </ul>
 *
 * <p><b>默认只追加，改写口只有三个</b>：{@link #replaceRange} / {@link #replaceAll} / {@link
 * #reset}。为什么把口收得这么窄：①请求前缀的稳定性是 prompt-cache 的前提（每回合重排或重写前缀 =
 * 每回合缓存全失效，费用与延迟一起涨），"只追加"这个词本身就在守这条；②"未落库尾部"只有在"只追加 + 少数明确的整体改写"下才可推理（见 {@link #unsavedTail()}）。
 *
 * <p><b>线程安全：不做同步、不承诺并发</b>。调用方<b>串行</b>使用——与既有管线对工作集的口径一致（同一个窗口上的回合、重置、压缩必须排在同一个串行化点上）。 {@link
 * #messages()} / {@link #unsavedTail()} 返回不可变<b>快照</b>，拿到快照的线程可以安全读它；但那只说明快照安全，不构成对窗口本身并发写入的许可。
 */
public final class ContextWindow {

  /**
   * compact 标记保留 role。
   *
   * <p>它不是 LLM 合法 role，只存在于 {@link #rawMessages()} 的 raw 历史中；{@link #messages()}
   * 永远会把它投影掉，任何模型请求都不得看到它。
   */
  public static final String ROLE_COMPACT = "compact";

  /** 合成 user 检查点的固定前言（由窗口统一生成，避免各调用方拼出不同格式）。 */
  public static final String COMPACTION_CHECKPOINT_PREFIX =
      "[自动上下文压缩检查点]\n" + "以下是较早对话的压缩摘要，作为已确立的背景继续工作，不要复述或确认本检查点。\n\n" + "<compacted-summary>\n";

  /** 合成 user 检查点的固定结尾。 */
  public static final String COMPACTION_CHECKPOINT_SUFFIX = "\n</compacted-summary>";

  /** raw 历史（含 compact 标记）；对外只经快照方法暴露。 */
  private final List<LlmMessage> messages = new ArrayList<>();

  /** 未落库尾部的起点：不变量恒为 {@code 0 <= unsavedFrom <= messages.size()}。 */
  private int unsavedFrom;

  private String id;

  /**
   * 建一个空窗口。
   *
   * @param id 窗口标识（典型就是会话标识；非 null，空串合法——id 的取值归宿主，窗口只要求"有"）
   */
  public ContextWindow(String id) {
    this.id = Objects.requireNonNull(id, "id");
  }

  /**
   * 建一个带初始内容的窗口。
   *
   * <p><b>初始内容视为"已处置"</b>（构造后 {@link #unsavedTail()} 为空）。为什么选这一边：本构造的主要用途是<b>从会话库水合</b>——
   * 那些消息本来就在库里；若把初始内容算成未落库，宿主第一次保存就会把整段已存在的历史重复 append 进 append-only 的会话库，得到重复前缀
   * （读回来就重了，比"少存"更难发现也更难收拾）。要"装进来一批新内容并让它待落库"，用 {@link #appendAll(List)}——那一个口的语义无歧义。
   *
   * @param id 窗口标识
   * @param initial 初始消息（按序装入；整体拷贝，之后改传入的 List 不影响本窗口）
   */
  public ContextWindow(String id, List<LlmMessage> initial) {
    this(id);
    List<LlmMessage> copy = List.copyOf(Objects.requireNonNull(initial, "initial"));
    messages.addAll(copy);
    // 构造进来的内容不待落库（见上）；unsavedFrom 随之等于 size，尾部为空
    unsavedFrom = messages.size();
  }

  /** 窗口标识（{@link #reset(String)} 之后是新值）。 */
  public String id() {
    return id;
  }

  /**
   * 当前<b>可见投影</b>的不可变快照（按序）。
   *
   * <p><b>两种层次</b>：raw 历史永远 append-only 保留全部消息；compact 标记（{@link #ROLE_COMPACT}）只存在于 raw 中。本方法找最后一个
   * compact 标记，并返回它之后的可见后缀：标记之前的消息全部被屏蔽，标记本身也永不出现。
   *
   * <p><b>投影规则</b>：无 compact 标记时逐字节保持旧行为（返回 raw 全量）。有标记时，摘要取自标记里的文本 part；若标记后首条是 user， 把该 user 的文本
   * part 与摘要按 {@link #COMPACTION_CHECKPOINT_PREFIX}/{@link #COMPACTION_CHECKPOINT_SUFFIX} 合成一条纯文本
   * user，再接后缀其余消息；否则返回 {@code [user(摘要检查点)] + 后缀}。合成 user 不保留图片等非文本 part。
   *
   * <p>为什么给快照而不是活视图：调用方（装配器、宿主）常要把它交给另一次调用或跨线程读；给视图等于同时给了一个绕过"唯一改写路径"的后门。代价是每回合 O(n)
   * 拷贝——与既有实现里同量级的一次拷贝相比不值得为它引入"谁在什么时候能改"的新约定。
   */
  public List<LlmMessage> messages() {
    return List.copyOf(visibleMessages());
  }

  /**
   * 当前<b>可见消息条数</b>（compact 标记不计入；无标记时与 raw 条数相同）。
   *
   * <p>压缩标记之后只保留最后一段可见后缀，因此该值可能小于 {@link #rawSize()}。
   */
  public int size() {
    return visibleMessages().size();
  }

  /**
   * raw 历史的不可变快照（含 compact 标记，按序）。
   *
   * <p><b>只给持久化 / 审计 / 诊断使用；绝不能直接送给模型。</b>compact 是窗口内部标记，不是供应商合法 role； 要组 LLM 请求必须使用 {@link
   * #messages()}（它会自动投影并屏蔽标记前的旧历史）。
   */
  public List<LlmMessage> rawMessages() {
    return List.copyOf(messages);
  }

  /** raw 消息条数（含 compact 标记）。 */
  public int rawSize() {
    return messages.size();
  }

  /** 是否至少存在一个 compact 标记。 */
  public boolean hasCompaction() {
    return lastCompactIndex() >= 0;
  }

  /**
   * 最后一个 compact 标记里的摘要正文（可见诊断用）。
   *
   * <p>返回值只用于诊断/事件的结构性判断；不要把它写进日志原文。标记没有文本 part 时返回空；多个文本 part 按出现顺序以换行连接。
   */
  public Optional<String> lastCompactionSummary() {
    int index = lastCompactIndex();
    if (index < 0) {
      return Optional.empty();
    }
    return Optional.of(summaryText(messages.get(index)));
  }

  /**
   * 追加一条 compact 标记；<b>不删除 raw 历史</b>。
   *
   * <p>标记正文只存模型摘要。它进入未落库尾部（{@link #unsavedTail()} 会包含它），因此正常落库后重启可从 raw 自动恢复投影。
   *
   * @param summary 模型生成的摘要正文（非 null、非空白）
   */
  public void compact(String summary) {
    Objects.requireNonNull(summary, "summary");
    if (summary.isBlank()) {
      throw new IllegalArgumentException("compact 摘要不得为空白");
    }
    append(new LlmMessage(ROLE_COMPACT, List.of(new ContentPart.Text(summary))));
  }

  /**
   * 按固定检查点格式生成压缩后的合成 user 文本。
   *
   * <p>压缩器提交前用它构造后校验请求，保证估算内容与真正投影出来的 user 完全一致。{@code nextUserText} 为 {@code null}
   * 或空串时只生成到摘要结尾（没有下一轮 user 的形态）。
   *
   * @param summary 摘要正文
   * @param nextUserText 下一轮 user 原文；可为 null/空
   * @return 确定性渲染的纯文本 user 内容
   */
  public static String formatCompactedUser(String summary, String nextUserText) {
    Objects.requireNonNull(summary, "summary");
    String checkpoint = COMPACTION_CHECKPOINT_PREFIX + summary + COMPACTION_CHECKPOINT_SUFFIX;
    return nextUserText == null || nextUserText.isEmpty()
        ? checkpoint
        : checkpoint + "\n\n" + nextUserText;
  }

  /** 追加一条消息（默认路径；落进未落库尾部）。 */
  public void append(LlmMessage m) {
    messages.add(Objects.requireNonNull(m, "m"));
  }

  /**
   * 追加一批消息（按序；落进未落库尾部）。
   *
   * <p>先整体拷成快照再追加：某个元素是 {@code null} 时在这里就响亮失败，窗口<b>一个字节都没动</b>——不做"追加了一半再抛"（半截状态会让宿主
   * 的落库尾部凭空多出几条，而调用方以为整批失败了）。
   */
  public void appendAll(List<LlmMessage> ms) {
    List<LlmMessage> copy = List.copyOf(Objects.requireNonNull(ms, "ms"));
    messages.addAll(copy);
  }

  /**
   * 就地替换 <b>raw 历史</b>的半开区间 {@code [from, to)}（下标是 {@link #rawSize()} 的 raw 下标，不是可见投影下标）；{@code
   * replacement} 为空列表 = <b>删除</b>该区间。窗口唯一的"就地改写"口。
   *
   * <p><b>为什么需要它</b>：只追加表达不了三种真实需求——压缩（把中段换成占位/摘要）、悬空 {@code tool_call} 的修复（补回填或删掉发出却没回填的调用）、
   * 以及"某条不进历史"的裁剪。它们都不是"追加"，硬用 append 模拟只会得到更乱的形状。
   *
   * <p><b>参数校验：三种非法一律抛 {@link IndexOutOfBoundsException}</b>（{@code from < 0}、{@code to >
   * rawSize()}、{@code from > to}）。 刻意<b>不</b>做 {@code IllegalArgumentException}/{@code
   * IndexOutOfBoundsException} 二分（{@code List.subList} 是二分的）：调用方对"下标非法" 只需一个
   * catch，分档的收益不值得让每个调用点记两套语义。校验在任何写入<b>之前</b>完成——失败的调用不留下半截窗口。
   *
   * <p><b>对未落库尾部的影响</b>：尾部起点取 {@code min(unsavedFrom, from)}——"从这个改写点起，窗口内容不再能证明与存储一致"。于是它要么不变
   * （改的是尾部内部）、要么变大（改到了已落库的前缀），<b>只会变大、绝不缩小</b>：缩小等于窗口单方面宣布"这段我已经存过了"，而改写恰恰是"存过的那份
   * 现在不对了"的场合。怎么收口（重写、压缩、或明确 {@link #markSaved()} 声明已处置）由宿主决定。
   *
   * @param from raw 区间起点（含），{@code 0 <= from <= rawSize()}
   * @param to raw 区间终点（不含），{@code from <= to <= rawSize()}
   * @param replacement 替换进去的消息（可为空列表 = 删除；非 null）
   * @throws IndexOutOfBoundsException 下标非法（上述三种之一）
   */
  public void replaceRange(int from, int to, List<LlmMessage> replacement) {
    List<LlmMessage> copy = List.copyOf(Objects.requireNonNull(replacement, "replacement"));
    int size = messages.size();
    if (from < 0 || to > size || from > to) {
      throw new IndexOutOfBoundsException(
          "replaceRange 下标非法（要求 0 <= from <= to <= size）: from="
              + from
              + ", to="
              + to
              + ", size="
              + size);
    }
    messages.subList(from, to).clear();
    messages.addAll(from, copy);
    if (from < unsavedFrom) {
      unsavedFrom = from;
    }
  }

  /**
   * 整体替换 <b>raw 历史</b>（= 既有管线的 {@code replaceWorkingSet}）：清空后按序装入给定消息，并把未落库尾部<b>清空</b>。
   *
   * <p><b>为什么整体替换算"已处置"（而不是"整窗都算未落库"）</b>——本类唯一需要选边的语义，选这边的三个理由：
   *
   * <ol>
   *   <li><b>它对齐既有行为</b>：整体替换的既有调用点是压缩——档 3 先 {@code store.compact} 再按 {@code load} 的权威形态重灌，档 1/2
   *       只换内存工作集 （库里仍是全量）。两种情况下"替换后这一窗"都已与存储对齐（或存储另有权威版本）；若把它整窗算未落库，宿主的落库循环就会把已存在的消息重复 append 进
   *       append-only 会话库——重复前缀比"少存"更难发现、更难收拾（读回来就重了）。
   *   <li><b>整体替换没有"新增"语义</b>：真正的"新消息"只有 append/appendAll 一种表达，含义无歧义；若整体替换也顺带宣称"这些都是新的"，宿主就再也
   *       分不出"换了一份已对齐的视图"与"装进来一批新内容"。
   *   <li><b>与 {@link #reset(String)} 同一条规则</b>：两个"整窗级"操作都以空尾部收尾（reset 之后窗口为空，尾部本就无从谈起）。一条规则比两条好记。
   * </ol>
   *
   * <p><b>要"整体替换 + 新内容待落库"怎么办</b>：{@code replaceAll(...)} 之后显式 {@link
   * #appendAll(List)}——后者的语义就是"这些是新的"， 宿主落库循环照常取 {@link
   * #unsavedTail()}。本类不提供"整体替换但同时宣称未落库"的口：那等于让窗口替宿主猜存储状态，而它连 store 都不认识。
   *
   * @param newMessages 新的 raw 内容（非 null；整体拷贝，之后改传入的 List 不影响本窗口）
   */
  public void replaceAll(List<LlmMessage> newMessages) {
    List<LlmMessage> copy = List.copyOf(Objects.requireNonNull(newMessages, "newMessages"));
    messages.clear();
    messages.addAll(copy);
    unsavedFrom = messages.size();
  }

  /**
   * 清空消息并把 id 换成 {@code newId}（= 既有管线 {@code resetConversation} 的内存部分）：之后 {@link #messages()} 与
   * {@link #unsavedTail()} 都为空，{@link #id()} 为新 id。
   *
   * <p><b>存储指针不在这里</b>：窗口不认识 store，"[会话指针 + 内存工作集] 这个组合的两步顺序"由宿主保证——既有管线的顺序是<b>指针先写、内存后切</b>，
   * 理由是写失败时宁可停在旧会话（"重置没生效"，用户可重试），也不要出现"运行期看着已重置、重启后旧历史复活"的分裂态。窗口只负责它那一半。
   *
   * @param newId 新窗口标识（非 null；调用方负责给一个新 id——本方法不生成 id，也不 load 新会话的内容）
   */
  public void reset(String newId) {
    Objects.requireNonNull(newId, "newId");
    messages.clear();
    unsavedFrom = 0;
    this.id = newId;
  }

  /**
   * 自上次 {@link #markSaved()} 以来<b>新增或被改动的消息尾部</b>（不可变快照）——宿主据此落库，或据此判断"哪些不落库"。
   *
   * <p><b>它永远是 raw 历史的一个后缀</b>（不变量：{@code [unsavedFrom, rawSize())}）。这条不变量让宿主的落库循环写成"取尾部 → 逐条
   * append → {@link #markSaved()}"即可，不必自己记下标。它替代的是既有管线里那段下标推导（今天靠 {@code
   * messages.subList(replayedSize + 1, size)} 推 "本回合新增的尾部"）：append/appendAll 之后返回这些新消息，markSaved
   * 之后返回空。
   *
   * <p><b>"未被处置"不等于"没落库"</b>：窗口不知道谁落了库，它只记"宿主还没对这段尾部表态"（见 {@link #markSaved()} 的语义）。
   */
  public List<LlmMessage> unsavedTail() {
    return List.copyOf(messages.subList(unsavedFrom, messages.size()));
  }

  /**
   * 宣告"当前尾部已处置完"：此后 {@link #unsavedTail()} 返回空，直到再有 append/appendAll/replaceRange 落进来。
   *
   * <p><b>处置的含义是"宿主已决定怎么处理它"</b>，不是"物理写入成功"——处置可以是落库，也可以是<b>明确决定不落库</b>（每轮现插、刻意不落盘的提醒消息， 宿主在它之后
   * markSaved 即可，窗口不会因此以为库里多了什么）。窗口永远不校验这件事：它不认识 store，校验就得把存储语义塞进窗口，正是本类要避免的。
   */
  public void markSaved() {
    unsavedFrom = messages.size();
  }

  /**
   * 中性读数：本窗口内容的估算 token（<b>字符数 / 4，整数除法、向下取整</b>）。<b>不做任何预算判断</b>——不抛超限、不触发压缩、不改变任何行为。
   *
   * <p><b>为什么是 /4 而不是真实分词器</b>：本仓当前没有分词器，既有估算口径就是"字符数 / 4"（Brain 侧 {@code ContextComposition} 与
   * {@code Compactor}）。这里<b>刻意照抄同一算式</b>，理由：将来换成 JTokkit 时两处必须同时换，先把口径对齐才不会出现"同一个窗口在两处算出两个数"。
   * 已知偏差与既有实现一致：对中文明显偏低（1 汉字 ≈ 1~2 token），且<b>不计思维链</b>（{@code reasoning} 不进字符数）。
   *
   * <p>计数项逐项为：{@code role} 长度 + 每个分片（文本正文 / 工具调用的 id+name+arguments / 工具结果的 name+content+error /
   * 图片的 mediaType+assetId）；图片只算引用——字节在发送那一刻才解析，不该按图片体积进上下文预算。
   */
  public int estimatedTokens() {
    int chars = 0;
    for (LlmMessage message : visibleMessages()) {
      chars += message.role().length();
      for (ContentPart part : message.content()) {
        chars += partChars(part);
      }
    }
    return chars / 4;
  }

  /** 生成可见投影；无 compact 标记时直接返回 raw 的不可变拷贝（旧行为）。 */
  private List<LlmMessage> visibleMessages() {
    int compactIndex = lastCompactIndex();
    if (compactIndex < 0) {
      return List.copyOf(messages);
    }

    String summary = summaryText(messages.get(compactIndex));
    List<LlmMessage> suffix = messages.subList(compactIndex + 1, messages.size());
    List<LlmMessage> visible = new ArrayList<>(suffix.size() + 1);
    String nextUserText = "";
    if (!suffix.isEmpty() && LlmMessage.ROLE_USER.equals(suffix.get(0).role())) {
      nextUserText = userText(suffix.get(0));
    }
    visible.add(LlmMessage.user(formatCompactedUser(summary, nextUserText)));
    if (!suffix.isEmpty() && LlmMessage.ROLE_USER.equals(suffix.get(0).role())) {
      visible.addAll(suffix.subList(1, suffix.size()));
    } else {
      visible.addAll(suffix);
    }
    return List.copyOf(visible);
  }

  /** 最后一个 compact 标记的 raw 下标；没有时返回 -1。 */
  private int lastCompactIndex() {
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (ROLE_COMPACT.equals(messages.get(i).role())) {
        return i;
      }
    }
    return -1;
  }

  /** compact 标记里的文本正文；多个文本 part 按序以换行连接。 */
  private static String summaryText(LlmMessage compactMarker) {
    return compactMarker.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(ContentPart.Text.class::cast)
        .map(ContentPart.Text::text)
        .reduce((left, right) -> left + "\n" + right)
        .orElse("");
  }

  /** 一条 user 消息里所有文本 part 按序以换行连接；非文本 part 丢弃。 */
  private static String userText(LlmMessage userMessage) {
    return userMessage.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(ContentPart.Text.class::cast)
        .map(ContentPart.Text::text)
        .reduce((left, right) -> left + "\n" + right)
        .orElse("");
  }

  /** 分片字符数（与既有估算逐项同源，含"工具结果不计 toolCallId"这一细节）。 */
  private static int partChars(ContentPart part) {
    return switch (part) {
      case ContentPart.Text text -> text.text().length();
      case ContentPart.ToolCall call ->
          call.id().length() + call.name().length() + call.arguments().toString().length();
      case ContentPart.ToolResult result ->
          result.name().length()
              + (result.content() == null ? 0 : result.content().length())
              + (result.error() == null ? 0 : result.error().length());
      case ContentPart.Image image -> image.mediaType().length() + image.assetId().length();
    };
  }
}
