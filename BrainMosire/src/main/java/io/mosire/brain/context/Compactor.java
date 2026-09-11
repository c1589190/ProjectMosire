package io.mosire.brain.context;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 会话压缩器（计划 §4.2 / {@code 开发计划.md:101}）：<b>阈值触发（token 预算门）</b>，三档——micro（丢弃中段）→ 局部摘要（一次只读、无工具的 LLM
 * 调用）→ 快照续接（摘要落库、跨重启存活）。
 *
 * <p><b>压缩对象是会话（conversation），不是持久层</b>（R3）：{@code SYSTEM}/{@code RULES}/{@code SKILL_INDEX}/{@code
 * LOADED_SKILLS}/{@code MEMORY} 不属于会话，每回合由各自来源重新构造——它们<b>既不许烤进摘要</b>（摘要器只拿到被丢弃的会话消息， 见 {@link
 * #summarize}），<b>也不许因压缩而丢</b>（本类只产出新的会话工作集，从不触碰装配器里的持久层段落）。
 *
 * <p><b>触发与判据（R2：只用 {@link ContextPolicy}，不新造配置面）</b>：触发门 = {@code
 * budgetFor(ContextLayer.CONVERSATION)}；{@code 0 = 未启用}（默认策略只配 SYSTEM，故默认<b>不触发</b>）。 token 估算与
 * {@link ContextComposition} 同口径（字符数 / 4，整数除法，对整段会话算一次）。档 1/档 2 的"是否够用"各自用同一预算复审。
 *
 * <p><b>升级阶梯（R1：先试便宜的，禁止跳级花钱）</b>：
 *
 * <ol>
 *   <li>{@link Tier#MICRO 档 1}（无 LLM 调用）：保留<b>头部前缀</b> + <b>最近尾部</b>，中段移出工作集并插入<b>占位标记</b>（"已丢弃 N
 *       条历史消息"）——<b>绝不静默丢失</b>。压缩后估算 ≤ 预算 → 就此打住（这一档不产生任何成本）。
 *   <li>{@link Tier#SUMMARY 档 2}（一次 LLM 调用）：对"被丢弃的中段"做结构化摘要（{@link CompactSummary}），注入 {@link
 *       ContextLayer#COMPACT_SUMMARY} 层；工作集只剩最近尾部。摘要器<b>同 runtime 但只读、无工具</b>：请求里 {@code tools}
 *       恒为空表。
 *   <li>{@link Tier#SNAPSHOT 档 3}：仅保留尾部都已超预算时进入（摘要只增不减，档 2
 *       在此已<b>不可达</b>——不是"跳级"）。摘要覆盖<b>整段历史</b>并落库 （{@code ConversationStore.compact}），此后会话自"摘要 +
 *       压缩点之后的消息"续接（R1 档 3；持久化本身由持有 store 的 {@code AgentPipeline} 执行，本类不持有 store——见下）。
 * </ol>
 *
 * <p><b>为什么档 3 的摘要覆盖整段历史</b>：{@code compact} 的压缩点 = 调用当时的会话最大行 id，即"摘要必须代表它之前的<b>全部</b>
 * 消息"，否则压缩点之前那些没被摘要覆盖的内容会从重启后的基线里<b>静默消失</b>。因此档 3 不复用"中段摘要"当整段用。
 *
 * <p><b>不变量与"基准"（F3）</b>：上述不变量要求档 3 的摘要器输入覆盖压缩点将要隐藏的全部内容。档 1 会把中段移出工作集（只剩一行"已丢弃 N 条"
 * 占位），此后<b>工作集不再是权威视图</b>——它其中有一段内容只被占位代表。因此档 3 的基准由调用方给出（{@link #compact(List, CompactSummary,
 * List)}）：工作集与存储同源时就是工作集，否则必须是 {@code ConversationStore.load}
 * 的权威全量视图（管线如此做）。若两个视图都不覆盖（例如传了个不含该内容的基准），本类<b>无法察觉</b>——它不持有存储，
 * 判据只能由调用方提供；调用方在"工作集曾丢失内容"时必须升级基准，这是接线的契约。
 *
 * <p><b>成本可观测（R7）</b>：摘要调用是<b>额外</b>的 LLM 调用，它的 {@link LlmResponse} 随结果返回（{@link
 * Result#summarizerCall}），由管线落 {@code llm.call} 事件——P2-3 的 token 汇总因此看得见压缩成本。 调用失败（{@link
 * LlmException}）时没有 response 可记账，此时降级并<b>落 decision 披露</b>（不静默）。本类不进 {@code AgentPipeline}
 * 的配额：配额是"本回合的自主推理预算"， 压缩是系统级维护成本（靠事件记账可见，而非挤占回合配额）。
 *
 * <p><b>降级（失败也要可见）</b>：摘要不可用（调用失败 / 回复解析不出八字段 / 八个字段全空）时，<b>不</b>拿空摘要冒充成功，而是退回档 1
 * 的披露式丢弃（工作集仍变小、占位标记仍在），并在 {@link Result#note()} 里写明原因——管线据此落 {@code decision} 事件。 若连档 1
 * 的切点都不存在（整段历史是一条未闭合的工具链），则 {@link Tier#NONE 不动} + note，同样披露。
 *
 * <p><b>切除边界必须是"干净"的</b>：工具调用与工具结果必须成对留在同一侧——中段的两个端点都取在 {@code user} 消息上、且前一条不含未被回填的 {@code
 * tool_call}。否则回灌出去的请求里会出现悬空 {@code tool_call}（部分供应商直接拒收）或孤儿 {@code tool_result}。
 *
 * <p><b>本类无状态、不触存储</b>：{@code compact(...)} 是纯决策 + 纯产出（除摘要器这一外部调用）。档 3 的落库由管线用<b>自己持有的那个 store
 * 实例</b>执行——这同时是撕裂读（R11）的结构性回避：管线存活期间不存在第二个 store 实例/第二条连接，{@code load()} 的两条查询间不可能插进一次 {@code
 * compact()}。
 */
public final class Compactor {

  /** 压缩档位（{@code NONE} = 未压缩：未触门 / 无安全切点）。 */
  public enum Tier {
    /** 未压缩（工作集原样）。 */
    NONE,
    /** 档 1：micro——丢弃中段（含占位披露），无 LLM 调用。 */
    MICRO,
    /** 档 2：局部摘要——被丢弃的中段由一次只读 LLM 调用摘要成 {@link CompactSummary}。 */
    SUMMARY,
    /** 档 3：快照续接——整段历史摘要落库，重启后自"摘要 + 压缩点之后的消息"续接。 */
    SNAPSHOT
  }

  /** 一次摘要调用（{@code response} 供管线落 {@code llm.call} 记账；{@code latencyMs} 与主循环同口径）。 */
  public record SummarizerCall(LlmResponse response, long latencyMs) {

    public SummarizerCall {
      Objects.requireNonNull(response, "response");
    }
  }

  /**
   * 一次压缩决策的结果。
   *
   * @param tier 档位（{@link Tier#NONE} = 未压缩）
   * @param workingSet 应采纳的新会话工作集：档 1/2 = 压缩后的视图；档 3 = 摘要消息（与 {@code ConversationStore.load}
   *     的形状同源，但档 3 的<b>权威</b>工作集取自 {@code store.load(conversationId)}——管线如此做， 本字段供无存储的单测断言用）；{@link
   *     Tier#NONE} = 原样
   * @param summary 本次产出的结构化摘要；档 {@code NONE}/{@code MICRO} 为 {@code null}（档 1 的披露是占位消息，不是摘要）
   * @param droppedMessages 本次压缩<b>作用域</b>内不再逐字保留的原消息条数——档 2 也照实计（中段虽被摘要，但确实离开了工作集； 记 0 会让 {@code
   *     conversation.compact} 事件少报），{@code NONE} = 0
   * @param baselineMessages 本次压缩<b>作用域</b>的原长：档 1/2 = 工作集原长；档 3 = {@code snapshotBaseline}
   *     的长度（压缩点将要隐藏的 全部内容，通常大于工作集）。恒等式 {@code droppedMessages + 逐字保留条数 == baselineMessages}
   *     对三档都成立——事件里 {@code keptMessages} 由它推出（占位消息与摘要消息都<b>不</b>算"保留的原消息"，它们不是原消息）
   * @param note 需要披露的偏离（降级原因 / 无安全切点）；{@code null} = 无
   * @param summarizerCall 本次摘要调用；{@code null} = 未发生（档 1 / {@code NONE}）<b>或调用直接失败</b>（没有 response
   *     可记账）。注意它与 {@code summary} 相互独立：摘要在解析失败而降级时，{@code summary} 为 {@code null} 但 {@code
   *     summarizerCall} <b>非</b>空——那笔 token 是真花掉的，不能因为解析失败就从账上消失（R7）。同理，<b>只要摘要器返回过
   *     response</b>，降级路径也必须把它带出来（F1）："没拿到摘要"与"没发生调用"是两回事，只有 {@link LlmException} 那条路径 （连 response
   *     都没有）才不记账
   */
  public record Result(
      Tier tier,
      List<LlmMessage> workingSet,
      CompactSummary summary,
      int droppedMessages,
      int baselineMessages,
      String note,
      SummarizerCall summarizerCall) {

    public Result {
      Objects.requireNonNull(tier, "tier");
      workingSet = List.copyOf(workingSet);
    }

    /** 是否真的压缩了（{@code NONE} 之外都算）。 */
    public boolean compacted() {
      return tier != Tier.NONE;
    }

    static Result untouched(List<LlmMessage> history) {
      return new Result(Tier.NONE, history, null, 0, history.size(), null, null);
    }

    static Result untouched(List<LlmMessage> history, String note) {
      return new Result(Tier.NONE, history, null, 0, history.size(), note, null);
    }

    /**
     * 未压缩但<b>发生过记账</b>：摘要器返回过 response，只是这份 response 没能变成摘要（解析不出八字段）而退回不动。 调用已花掉
     * token，账必须原样带出（F1）；管线照常落 {@code llm.call} + {@code decision}。
     */
    static Result untouched(List<LlmMessage> history, String note, SummarizerCall call) {
      return new Result(Tier.NONE, history, null, 0, history.size(), note, call);
    }

    static Result micro(
        List<LlmMessage> view,
        int droppedMessages,
        int baselineMessages,
        String note,
        SummarizerCall call) {
      return new Result(Tier.MICRO, view, null, droppedMessages, baselineMessages, note, call);
    }

    static Result summary(
        List<LlmMessage> view,
        int droppedMessages,
        int baselineMessages,
        CompactSummary summary,
        SummarizerCall call) {
      return new Result(Tier.SUMMARY, view, summary, droppedMessages, baselineMessages, null, call);
    }

    /** {@code baseline} = 本次摘要<b>作用域</b>（档 3 = 压缩点将要隐藏的全部内容；通常是工作集，见 {@link #compact}）。 */
    static Result snapshot(List<LlmMessage> baseline, CompactSummary summary, SummarizerCall call) {
      return new Result(
          Tier.SNAPSHOT,
          List.of(summaryMessage(summary)),
          summary,
          baseline.size(),
          baseline.size(),
          null,
          call);
    }
  }

  /** 档 1 保留的头部前缀条数上限语义：从第 {@code HEAD_KEEP} 条起找<b>干净的</b>切点（不是恰好切在这里）。 */
  private static final int HEAD_KEEP = 2;

  /** 档 1/2 保留的最近尾部目标条数：从倒数第 {@code TAIL_KEEP} 条起向前找干净的切点。 */
  private static final int TAIL_KEEP = 6;

  private static final String PLACEHOLDER_PREFIX = "[已丢弃 ";

  /** 占位标记（档 1 的披露）：条数必须可见——静默丢失是本项目最坏的失败模式。 */
  private static final String PLACEHOLDER_SUFFIX =
      " 条历史消息（micro-compaction 保留开头与最近的尾部，中段已移出本回合请求）]";

  /** 摘要器提示词：八字段 + R3 的边界（不得复述持久层内容）。 */
  private static final String SUMMARIZER_INSTRUCTION =
      """
      你是会话压缩器。下面给你一段较早的会话记录，请把它压缩成结构化摘要，供后续回合继续工作使用。
      只输出一个 JSON 对象（不要代码块、不要解释），键固定为八个：
      objective（字符串：这段会话要达成的目标）、
      completed（字符串数组：已完成的事）、
      pending（字符串数组：待办）、
      decisions（字符串数组：已做出的关键决策）、
      changedFiles（字符串数组：涉及的文件路径）、
      errors（字符串数组：遇到的错误）、
      activeSkills（字符串数组：用到的技能名）、
      unresolved（字符串数组：未决问题）。
      只描述这段会话本身发生过什么：不要复述系统提示、规则、技能目录或记忆内容——它们不属于会话，每回合会另行提供。
      """;

  /** 上一版摘要并入新版时在摘要器输入里的前缀标记（合并是调用方的责任：{@code ConversationStore.compact} 不做合并）。 */
  private static final String PRIOR_SUMMARY_PREFIX = "（上一版压缩摘要，请并入新版摘要）\n";

  private final ContextPolicy policy;
  private final LlmClient summarizer;

  /**
   * @param policy 每层预算（触发门与各档判据的唯一来源；{@code CONVERSATION} 未配置 = 压缩不触发）
   * @param summarizer 摘要器（隐藏配置：系统级选定的模型/provider，不是用户可见工具、不进 {@code AgentTool}/权限面；二期无第二 provider
   *     时回落主 provider 同模型）
   */
  public Compactor(ContextPolicy policy, LlmClient summarizer) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.summarizer = Objects.requireNonNull(summarizer, "summarizer");
  }

  /**
   * 按预算门决定并执行一次压缩；未触门或无安全切点时返回 {@link Tier#NONE} 且工作集原样。
   *
   * <p>调用约定：只在<b>回合边界</b>（一轮结束之后、下一轮组请求之前）调用，且必须用管线自己持有的那个 store 完成档 3 的落库（见类 Javadoc）。本方法不修改传入的
   * {@code history}。
   *
   * <p>等价于 {@code compact(history, previousSummary, history)}：摘要基准 = 工作集（适用于"工作集与存储同源"的常规调用方）。
   *
   * @param history 当前会话工作集（不含 system 头）
   * @param previousSummary 上一版仍生效的摘要（{@link CompactSummarySlot#current()}；{@code null} = 无）：档 2/3
   *     的摘要器输入会带上 它，使新版摘要<b>并入</b>旧版——否则上一段被压缩的历史会在第二次压缩时从请求里消失
   */
  public Result compact(List<LlmMessage> history, CompactSummary previousSummary) {
    return compact(history, previousSummary, history);
  }

  /**
   * 同 {@link #compact(List, CompactSummary)}，但档 3 的摘要基准可由调用方指定为<b>权威全量视图</b>（{@code
   * ConversationStore.load} 的结果）。
   *
   * <p><b>为什么需要它（F3，不变量见类 Javadoc）</b>：档 1 把中段移出工作集后，工作集里只剩一行"已丢弃 N 条"占位——它<b>不再</b>
   * 包含压缩点将要隐藏的全部内容。若档 3 仍以工作集为基准去摘 要，那些被占位代表的消息既不进摘要、又会被新压缩点划进"之前"，重启后从基线里静默消失。 因此调用方在
   * "工作集自上次落库以来曾丢失内容"时必须传权威视图（管线传 {@code store.load(conversationId)}）； 工作集与存储同源时传 {@code history}
   * 即可（两者等价，白白多读一次库没有意义）。
   *
   * @param snapshotBaseline 档 3 的摘要基准 = 压缩点将要隐藏的全部内容（档 1/2 不受影响：它们的切除点只落在工作集上）
   */
  public Result compact(
      List<LlmMessage> history, CompactSummary previousSummary, List<LlmMessage> snapshotBaseline) {
    Objects.requireNonNull(history, "history");
    Objects.requireNonNull(snapshotBaseline, "snapshotBaseline");
    List<LlmMessage> current = List.copyOf(history);
    int budget = policy.budgetFor(ContextLayer.CONVERSATION);
    if (budget <= 0) {
      return Result.untouched(current);
    }
    if (tokensOf(current) <= budget) {
      return Result.untouched(current);
    }

    // 尾部切点：最近 TAIL_KEEP 条之前、最靠近它的干净边界（user 起始且前一条无悬空 tool_call）
    int tailStart = cleanBoundaryAtOrBefore(current, current.size() - TAIL_KEEP);
    if (tailStart <= 0) {
      return Result.untouched(current, "触门但无安全切点：尾部窗口内找不到以 user 起始、且不切断工具调用配对的边界");
    }
    List<LlmMessage> tail = List.copyOf(current.subList(tailStart, current.size()));

    // 档 1：保留头部前缀 + 最近尾部，中段换成披露占位（无 LLM 调用）
    int headEnd = headEndOf(current, tailStart);
    List<LlmMessage> microView = headEnd < 0 ? null : microView(current, headEnd, tail);
    int microDropped = headEnd < 0 ? 0 : tailStart - headEnd;
    if (microView != null && tokensOf(microView) <= budget) {
      return Result.micro(microView, microDropped, current.size(), null, null);
    }

    // 档 2：仅保留尾部已超预算 → 摘要（只增不减）不可能容纳，档 2 不可达，直接进档 3
    if (tokensOf(tail) <= budget) {
      SummaryAttempt middle = summarize(current.subList(0, tailStart), previousSummary);
      if (middle.summary() != null) {
        return Result.summary(tail, tailStart, current.size(), middle.summary(), middle.call());
      }
      return degradeToMicro(current, microView, microDropped, middle);
    }

    // 档 3：摘要覆盖"压缩点将要隐藏的全部内容"——基准是权威视图而不是工作集（F3）
    SummaryAttempt whole = summarize(snapshotBaseline, previousSummary);
    if (whole.summary() != null) {
      return Result.snapshot(snapshotBaseline, whole.summary(), whole.call());
    }
    return degradeToMicro(current, microView, microDropped, whole);
  }

  /** 档 1 的候选视图（头部 + 占位 + 尾部）。 */
  private static List<LlmMessage> microView(
      List<LlmMessage> history, int headEnd, List<LlmMessage> tail) {
    int tailStart = history.size() - tail.size();
    List<LlmMessage> view = new ArrayList<>(headEnd + 1 + tail.size());
    view.addAll(history.subList(0, headEnd));
    view.add(placeholderMessage(tailStart - headEnd));
    view.addAll(tail);
    return List.copyOf(view);
  }

  /** 头部切点：{@link #HEAD_KEEP} 之后最靠近的干净边界；它不早于 {@code tailStart} 时返回 -1（没有中段可丢）。 */
  private static int headEndOf(List<LlmMessage> history, int tailStart) {
    int headEnd = cleanBoundaryAtOrAfter(history, HEAD_KEEP);
    return headEnd >= 0 && headEnd < tailStart ? headEnd : -1;
  }

  /**
   * 摘要不可用时的降级：退回档 1 的披露式丢弃（仍变小、仍有占位），note 写明原因；无档 1 视图则不动。
   *
   * <p>{@code attempt.call()} 原样带出去：解析失败时摘要没拿到、但调用确实发生过（token 已花），管线据此照常落 {@code
   * llm.call}（R7）——降级只降级"摘要"，不降级"记账"。<b>两条降级路径都带</b>：连档 1 视图都没有（{@link Result#untouched(List,
   * String, SummarizerCall)}）时同样带出（F1）——"没拿到摘要"与"没发生调用"是两回事。
   */
  private static Result degradeToMicro(
      List<LlmMessage> history,
      List<LlmMessage> microView,
      int droppedMessages,
      SummaryAttempt attempt) {
    return microView == null
        ? Result.untouched(history, attempt.note(), attempt.call())
        : Result.micro(microView, droppedMessages, history.size(), attempt.note(), attempt.call());
  }

  /** 摘要器调用（只读、无工具）：返回摘要或"不可用 + 原因"。 */
  private SummaryAttempt summarize(List<LlmMessage> dropped, CompactSummary previousSummary) {
    List<LlmMessage> messages = new ArrayList<>(dropped.size() + 2);
    messages.add(LlmMessage.system(SUMMARIZER_INSTRUCTION));
    if (previousSummary != null) {
      messages.add(
          LlmMessage.assistant(
              List.of(new ContentPart.Text(PRIOR_SUMMARY_PREFIX + previousSummary.render()))));
    }
    messages.addAll(dropped);
    // 无工具：摘要器不注册、不提供任何工具（R1 档 2——摘要器不得有行动能力）
    LlmRequest request = new LlmRequest(messages, List.of());

    long startedNanos = System.nanoTime();
    LlmResponse response;
    try {
      response = summarizer.chat(request);
    } catch (LlmException e) {
      // 失败没有 response 可记账：不编造 llm.call，改由管线落 decision 披露（R7 + 降级可见）
      return new SummaryAttempt(null, null, "摘要调用失败：" + e.getMessage());
    }
    long latencyMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    SummarizerCall call = new SummarizerCall(response, latencyMs);
    CompactSummary summary = CompactSummary.parse(response.textPart().orElse(""));
    if (summary == null) {
      return new SummaryAttempt(null, call, "摘要回复无法解析为八字段 JSON 对象（八个字段全空或不是 JSON 对象）");
    }
    return new SummaryAttempt(summary, call, null);
  }

  /** 摘要尝试：三者恰有其二——成功（summary + call）、解析失败（call + note）、调用失败（note）。 */
  private record SummaryAttempt(CompactSummary summary, SummarizerCall call, String note) {}

  /**
   * 段落边界谓词：{@code i} 处可否作为切除点。{@code 0} 与末尾恒可（空段）；其余位置必须是 {@code user} 消息，且其前一条不含未被回填的 {@code
   * tool_call}（否则头部会以悬空调用收尾）。
   */
  private static boolean isCleanBoundary(List<LlmMessage> history, int index) {
    if (index <= 0 || index >= history.size()) {
      return true;
    }
    if (!LlmMessage.ROLE_USER.equals(history.get(index).role())) {
      return false;
    }
    return !hasToolCall(history.get(index - 1));
  }

  private static int cleanBoundaryAtOrAfter(List<LlmMessage> history, int from) {
    for (int index = Math.max(from, 0); index <= history.size(); index++) {
      if (isCleanBoundary(history, index)) {
        return index;
      }
    }
    return -1;
  }

  /**
   * 不超过 {@code from} 的最靠后的干净边界；返回值恒在契约内（{@code [0, size]}）。
   *
   * <p>{@code from < 0}（历史比尾部窗口还短）收敛到 {@code 0}：{@code 0} 本来就是合法切点（{@link
   * #isCleanBoundary}），负下标则是契约外的取值——调用点用 {@code tailStart <= 0} 判"无切点"，靠的是"要么 0 要么 -1
   * 要么正数"，一旦这里漏出负数，那个判断就不再是它以为的那个意思（F5）。
   */
  private static int cleanBoundaryAtOrBefore(List<LlmMessage> history, int from) {
    for (int index = Math.min(Math.max(from, 0), history.size()); index >= 0; index--) {
      if (isCleanBoundary(history, index)) {
        return index;
      }
    }
    return -1;
  }

  private static boolean hasToolCall(LlmMessage message) {
    return message.content().stream().anyMatch(ContentPart.ToolCall.class::isInstance);
  }

  /** 档 1 的披露占位：{@code assistant} 角色——它代替的是"模型自己对被丢弃那段的记忆"（与落库摘要同角色，T16 的既有形态）。 */
  private static LlmMessage placeholderMessage(int droppedMessages) {
    return LlmMessage.assistant(
        List.of(new ContentPart.Text(PLACEHOLDER_PREFIX + droppedMessages + PLACEHOLDER_SUFFIX)));
  }

  /** 档 3 的续接形态：摘要作为首条 {@code assistant} 消息——与 {@code SqliteConversationStore#load} 的既有形态同源。 */
  private static LlmMessage summaryMessage(CompactSummary summary) {
    return LlmMessage.assistant(List.of(new ContentPart.Text(summary.render())));
  }

  /** 会话工作集的估算 token（字符数 / 4，整数除法；口径与 {@link ContextComposition} 一致，对整段算一次）。 */
  private static int tokensOf(List<LlmMessage> messages) {
    int chars = 0;
    for (LlmMessage message : messages) {
      chars += message.role().length();
      for (ContentPart part : message.content()) {
        chars += partChars(part);
      }
    }
    return chars / 4;
  }

  private static int partChars(ContentPart part) {
    return switch (part) {
      case ContentPart.Text text -> text.text().length();
      case ContentPart.ToolCall call ->
          call.id().length() + call.name().length() + call.arguments().toString().length();
      case ContentPart.ToolResult result ->
          result.name().length()
              + (result.content() == null ? 0 : result.content().length())
              + (result.error() == null ? 0 : result.error().length());
    };
  }
}
