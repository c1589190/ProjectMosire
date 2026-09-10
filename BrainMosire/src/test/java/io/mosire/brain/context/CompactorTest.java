package io.mosire.brain.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.brain.context.Compactor.Result;
import io.mosire.brain.context.Compactor.Tier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link Compactor} 的三档阶梯（T17/R1/R2/R3/R7/R9）——纯决策单测，无存储、无管线。
 *
 * <p><b>夹具的字符账（预算在此是硬边界，故把账写下来）</b>：估算口径 = 字符数 / 4（含 role 字符），由 {@link
 * #triggerGateEstimatesTheWholeConversationAtCharsOverFour} 独立 pin；本类的每条用例都只用"预算与估算相差数倍"的
 * 取值，避免把用例的生死绑在口径的末位精度上。
 *
 * <ul>
 *   <li>{@link #longMiddle()}：18 条。头部两条极小、中段 10 条各 ~2000 字符（≈5000 token）、尾部 6 条极小。全量 ≈ 5035 token，档
 *       1 视图 ≈ 32 token——预算 1000 时"档 1 够用"是压倒性的。
 *   <li>{@link #longHeadShortTail()}：18 条。头部一对各 4000 字符（档 1 保留头部 ⇒ 档 1 视图必然 ≥ 2010 token）、尾部 6 条各
 *       ~110 字符（≈170 token）。全量 ≈ 2330 token。预算 300 → 档 1 放不下、档 2 的尾部放得下；预算 60 → 连尾部都放不下。
 * </ul>
 *
 * <p><b>为什么每条用例都额外断言"摘要器被调了几次"</b>：阶梯的验收句是"先试便宜的、禁止跳级花钱"——只断言档位的话，"跳级到 LLM 但碰巧把档位报成
 * micro"这类实现仍能蒙混（本类的大多数结果断言不涉及 LLM 是否真被调用），所以计数与档位必须同时钉。
 */
class CompactorTest {

  /** 八字段齐全的摘要回复（与 {@code CompactSummaryTest} 同源的形态，字段值各不相同以便抓错位）。 */
  private static final String SUMMARY_JSON =
      """
      {"objective":"目标哨兵","completed":["完成哨兵"],"pending":["待办哨兵"],"decisions":["决策哨兵"],
       "changedFiles":["文件哨兵"],"errors":["错误哨兵"],"activeSkills":["技能哨兵"],"unresolved":["未决哨兵"]}
      """;

  private static final CompactSummary EXPECTED =
      new CompactSummary(
          "目标哨兵",
          List.of("完成哨兵"),
          List.of("待办哨兵"),
          List.of("决策哨兵"),
          List.of("文件哨兵"),
          List.of("错误哨兵"),
          List.of("技能哨兵"),
          List.of("未决哨兵"));

  // ---------- 触发门（R2） ----------

  /**
   * 未配置 {@code CONVERSATION} 预算 = 压缩不触发：默认策略只配 SYSTEM，故接上压缩器也不会改变任何既有行为。
   *
   * <p>判别性：把"未配置（0）"当成"预算 0，必超"的实现会立刻压掉这段历史，{@code tier}/{@code workingSet} 两条断言都红；此外 {@link
   * UnscriptedSummarizer} 让任何"顺手调一次摘要"的实现直接炸红（AssertionError 不被 {@code catch (LlmException)} 吞掉）。
   */
  @Test
  void unconfiguredBudgetNeverTriggersAndNeverCallsTheSummarizer() {
    List<LlmMessage> history = longMiddle();

    Result result =
        new Compactor(ContextPolicy.defaults(), new UnscriptedSummarizer()).compact(history, null);

    assertThat(ContextPolicy.defaults().budgetFor(ContextLayer.CONVERSATION)).isZero();
    assertThat(result.tier()).isEqualTo(Tier.NONE);
    assertThat(result.compacted()).isFalse();
    assertThat(result.workingSet()).isEqualTo(history);
    assertThat(result.summary()).isNull();
    assertThat(result.note()).isNull();
    assertThat(result.summarizerCall()).isNull();
  }

  /** 预算够用 → 原样返回（工作集是同一个内容的不可变快照，且未触门时不产生任何披露 note）。 */
  @Test
  void historyWithinBudgetIsLeftUntouched() {
    List<LlmMessage> history = longHeadShortTail();

    Result result =
        new Compactor(policy(100_000), new UnscriptedSummarizer()).compact(history, null);

    assertThat(result.tier()).isEqualTo(Tier.NONE);
    assertThat(result.workingSet()).containsExactlyElementsOf(history);
    assertThat(result.droppedMessages()).isZero();
    assertThat(result.note()).isNull();
  }

  /**
   * 触发门口径 = 整段会话的"字符数 / 4"（含 role 字符），与 {@link ContextComposition} 同口径——不是"按条数"、不是"不含 role"、不是向上取整。
   *
   * <p>判别性：一条 user 消息 = role 4 字符 + 正文 400 字符 = 404 字符 = 101 token。预算 101 不触发、预算 100 触发，两条断言把口径 钉到
   * 1 token 的精度上：换成"（字符+3）/4""只算正文""按消息条数"任一实现，都至少有一条会翻面。触门后因单条消息找不到安全切点而 不动历史，这里只取 {@code note}
   * 作为"门是否开过"的探针。
   */
  @Test
  void triggerGateEstimatesTheWholeConversationAtCharsOverFour() {
    List<LlmMessage> history = List.of(LlmMessage.user("x".repeat(400)));

    Result withinBudget =
        new Compactor(policy(101), new UnscriptedSummarizer()).compact(history, null);
    Result overBudget =
        new Compactor(policy(100), new UnscriptedSummarizer()).compact(history, null);

    assertThat(withinBudget.note()).isNull();
    assertThat(overBudget.note()).isNotNull();
  }

  // ---------- 档 1：micro（无 LLM 调用） ----------

  /**
   * 档 1：保留头部前缀 + 最近尾部，中段被换成<b>披露占位</b>，且本档<b>零 LLM 成本</b>（R1 的"先试便宜的"）。
   *
   * <p>判别性（R9 的档 1 三条）：
   *
   * <ul>
   *   <li>静默丢弃的实现：占位消息不存在 / 其文本不含"已丢弃 10 条"→ 两条断言都红；
   *   <li>直接跳到 LLM 的实现：{@code calls() == 0} 与 {@code tier} 断言都红；
   *   <li>把历史截空或只留尾部的实现：头部前缀（前 2 条）与尾部 6 条的逐条相等断言会红。
   * </ul>
   */
  @Test
  void tierOneDropsTheMiddleDisclosesTheCountAndCostsNothing() {
    List<LlmMessage> history = longMiddle();
    ScriptedSummarizer summarizer = new ScriptedSummarizer();

    Result result = new Compactor(policy(1000), summarizer).compact(history, null);

    List<LlmMessage> tail = history.subList(12, history.size()); // 最后 6 条（TAIL_KEEP 的目标窗口）
    assertThat(result.tier()).isEqualTo(Tier.MICRO);
    assertThat(summarizer.calls()).isZero();
    assertThat(result.summarizerCall()).isNull();
    assertThat(result.summary()).isNull();
    assertThat(result.droppedMessages()).isEqualTo(10);

    List<LlmMessage> view = result.workingSet();
    assertThat(view).hasSize(9);
    assertThat(view.subList(0, 2)).containsExactlyElementsOf(history.subList(0, 2));
    assertThat(view.subList(3, 9)).containsExactlyElementsOf(tail);
    // 占位消息代替中段：条数必须可见（静默丢失是本项目最坏的失败模式）
    assertThat(textOf(view.get(2))).contains("已丢弃").contains("10").contains("条历史消息");
    // 中段内容确实离开了工作集（不是"复制了一份"）
    assertThat(view).noneMatch(message -> textOf(message).contains("MID-SENTINEL"));
    // 传入的 history 不被就地改写（调用方仍持有原历史）
    assertThat(history).hasSize(18);
    assertThatThrownBy(() -> view.add(LlmMessage.user("注入")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /**
   * 档 1 的切除边界必须"干净"：尾部窗口里落着工具调用/结果时，切点向前找到 <b>user</b> 边界，整对工具消息一起进中段。
   *
   * <p>夹具里"倒数第 6 条"正好是一条带 tool_call 的 assistant：按条数硬切的实现会把它的 tool_result 留在尾部、把调用本身丢进中段——
   * 回灌出去的请求里就出现孤儿 {@code tool_result}（部分供应商直接拒收）。判别性断言有三条：占位披露的条数是 3（整对随中段走，不是 4）、占位之后第一条是 user（不是
   * tool 结果）、头部最后一条不含 tool_call。
   */
  @Test
  void tierOneCutPointsNeverSplitToolCallPairs() {
    List<LlmMessage> history = toolPairInTheTailWindow();
    ScriptedSummarizer summarizer = new ScriptedSummarizer();

    Result result = new Compactor(policy(1200), summarizer).compact(history, null);

    assertThat(result.tier()).isEqualTo(Tier.MICRO);
    assertThat(summarizer.calls()).isZero();
    List<LlmMessage> view = result.workingSet();
    // 中段 = [U1, assistant(c1), tool(c1)] 三条（工具对整对随中段走）
    assertThat(result.droppedMessages()).isEqualTo(3);
    assertThat(textOf(view.get(2))).contains("已丢弃").contains("3");
    assertThat(view.get(3).role()).isEqualTo(LlmMessage.ROLE_USER);
    // 头部（占位之前）最后一对不含悬空 tool_call
    assertThat(view.get(1).content()).noneMatch(ContentPart.ToolCall.class::isInstance);
    // 尾部与历史尾段逐条相等（含那对完整的工具调用/结果）
    assertThat(view.subList(3, view.size()))
        .containsExactlyElementsOf(history.subList(5, history.size()));
  }

  // ---------- 档 2：局部摘要（一次 LLM 调用） ----------

  /**
   * 档 2：中段交给摘要器，八字段<b>全部</b>来自 LLM 的回复（不是本地拼的、不是空的），工作集只剩尾部。
   *
   * <p>判别性（R9 的档 2 三条）：八个字段各有哨兵值，任何一个键位接错/字段丢失都红；{@code tier} 让"档 1 就够却花钱"的实现红； {@code
   * summarizerCall} 非空让"摘要器没真被调用"的实现红。
   */
  @Test
  void tierTwoSummarizesTheDroppedMiddleIntoAllEightFields() {
    List<LlmMessage> history = longHeadShortTail();
    ScriptedSummarizer summarizer = ScriptedSummarizer.returning(SUMMARY_JSON);

    Result result = new Compactor(policy(300), summarizer).compact(history, null);

    assertThat(result.tier()).isEqualTo(Tier.SUMMARY);
    assertThat(summarizer.calls()).isEqualTo(1);
    assertThat(result.summarizerCall()).isNotNull();
    assertThat(result.summary()).isEqualTo(EXPECTED);
    assertThat(result.workingSet()).containsExactlyElementsOf(history.subList(12, history.size()));
    assertThat(history).hasSize(18); // 传入的 history 不被改写
  }

  /**
   * 摘要器的输入 = "恰好被丢弃的那段会话"（R1 档 2 的落点 + R3 的边界），且<b>不带任何工具</b>（摘要器没有行动能力）。
   *
   * <p>判别性：
   *
   * <ul>
   *   <li>把整段历史（含尾部）喂给摘要器的实现：{@code messages} 条数断言红；
   *   <li>把 agent 自己的 system 提示/技能目录/记忆也塞进去的实现（R3 禁止"把持久层烤进摘要"）：system 消息条数 > 1 的断言红；
   *   <li>给摘要器注册了工具的"省事复用主 LlmClient 装配"实现：{@code tools()} 为空的断言红。
   * </ul>
   */
  @Test
  void tierTwoSendsExactlyTheDroppedMiddleToTheSummarizerWithoutTools() {
    List<LlmMessage> history = longHeadShortTail();
    ScriptedSummarizer summarizer = ScriptedSummarizer.returning(SUMMARY_JSON);

    Result result = new Compactor(policy(300), summarizer).compact(history, null);

    LlmRequest request = summarizer.requests().get(0);
    List<LlmMessage> dropped = history.subList(0, history.size() - result.workingSet().size());

    assertThat(request.tools()).isEmpty();
    assertThat(request.messages()).hasSize(dropped.size() + 1);
    assertThat(request.messages().subList(1, request.messages().size()))
        .containsExactlyElementsOf(dropped);
    assertThat(request.messages())
        .filteredOn(m -> LlmMessage.ROLE_SYSTEM.equals(m.role()))
        .hasSize(1);
    assertThat(request.messages().get(0)).isNotEqualTo(LlmMessage.system("你是测试 Agent。"));
  }

  /**
   * R4/R7 的上游：档 2 也要照实报"多少条离开了工作集"（记 0 会让 {@code conversation.compact} 少报中段）。
   *
   * <p>判别性：{@code droppedMessages == 0} 的实现（把档 2 的丢弃当成"没丢，只是被摘要了"）在这里必红。
   */
  @Test
  void tierTwoReportsHowManyMessagesLeftTheWorkingSet() {
    List<LlmMessage> history = longHeadShortTail();

    Result result =
        new Compactor(policy(300), ScriptedSummarizer.returning(SUMMARY_JSON))
            .compact(history, null);

    assertThat(result.droppedMessages()).isEqualTo(12);
    assertThat(result.droppedMessages() + result.workingSet().size()).isEqualTo(history.size());
  }

  /**
   * 上一版摘要必须<b>并入</b>新版（{@code ConversationStore.compact} 明确把合并留给调用方）：摘要器的输入里出现上一版摘要的全文，
   * 且它在被丢弃的中段<b>之前</b>。
   *
   * <p>判别性：忽略 {@code previousSummary} 的实现（第二次压缩时上一段被压缩的历史从请求里消失）在这里必红。
   */
  @Test
  void tierTwoMergesThePreviousSummaryIntoTheNewOne() {
    List<LlmMessage> history = longHeadShortTail();
    CompactSummary prior =
        new CompactSummary(
            "旧目标哨兵",
            List.of("旧完成"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    ScriptedSummarizer summarizer = ScriptedSummarizer.returning(SUMMARY_JSON);

    Result result = new Compactor(policy(300), summarizer).compact(history, prior);

    List<LlmMessage> messages = summarizer.requests().get(0).messages();
    assertThat(result.tier()).isEqualTo(Tier.SUMMARY);
    assertThat(messages.get(1).role()).isEqualTo(LlmMessage.ROLE_ASSISTANT);
    assertThat(textOf(messages.get(1))).contains("上一版压缩摘要").contains(prior.render());
    // 上一版摘要在中段之前（并集顺序：指令 → 旧摘要 → 被丢弃的中段）
    assertThat(messages.get(2)).isEqualTo(history.get(0));
  }

  // ---------- 档 3：快照续接 ----------

  /**
   * 档 3：连尾部都超预算 → 摘要覆盖<b>整段历史</b>（压缩点 = 当前末尾，摘要必须代表它之前的全部内容），工作集只剩摘要消息。
   *
   * <p>判别性：拿"中段摘要"当整段用的实现（压缩点之前的尾部内容会从重启后的基线里静默消失）在这里必红——{@code messages}
   * 条数与逐条相等断言要求摘要器看到的是<b>全部</b> 18 条；只保留尾部、或把摘要消息拼成半截的实现也会在 {@code workingSet} 断言上红。
   */
  @Test
  void tierThreeSummarizesTheEntireHistoryAndKeepsOnlyTheSummaryMessage() {
    List<LlmMessage> history = longHeadShortTail();
    ScriptedSummarizer summarizer = ScriptedSummarizer.returning(SUMMARY_JSON);

    Result result = new Compactor(policy(60), summarizer).compact(history, null);

    assertThat(result.tier()).isEqualTo(Tier.SNAPSHOT);
    assertThat(summarizer.calls()).isEqualTo(1);
    assertThat(result.droppedMessages()).isEqualTo(history.size());
    assertThat(result.workingSet())
        .containsExactly(
            LlmMessage.assistant(List.of(new ContentPart.Text(result.summary().render()))));
    assertThat(result.summary()).isEqualTo(EXPECTED);
    // 摘要器看到的是整段历史（含尾部），不是中段
    List<LlmMessage> sent = summarizer.requests().get(0).messages();
    assertThat(sent).hasSize(history.size() + 1);
    assertThat(sent.subList(1, sent.size())).containsExactlyElementsOf(history);
    assertThat(summarizer.requests().get(0).tools()).isEmpty();
  }

  // ---------- 降级（失败也要可见） ----------

  /**
   * 摘要调用失败（{@code LlmException}）→ 退回档 1 的披露式丢弃 + note 写明原因；<b>不编造</b>记账（没有 response 就没有 {@code
   * llm.call} 可落，故 {@code summarizerCall} 必须为 null，由管线落 decision 披露）。
   *
   * <p>判别性：失败后"照常返回一个空摘要"的实现（工作集里既没占位也没摘要 → 中段静默消失）在 {@code tier}/{@code note}/ 占位断言上全红；失败却造一个假
   * response 去记账的实现会在 {@code summarizerCall} 的 null 断言上红。
   */
  @Test
  void failedSummarizerCallDegradesToDisclosedMicroDrop() {
    List<LlmMessage> history = longHeadShortTail();
    ScriptedSummarizer summarizer = ScriptedSummarizer.failing("摘要服务不可用");

    Result result = new Compactor(policy(300), summarizer).compact(history, null);

    assertThat(result.tier()).isEqualTo(Tier.MICRO);
    assertThat(result.summary()).isNull();
    assertThat(result.summarizerCall()).isNull();
    assertThat(result.note()).contains("摘要调用失败").contains("摘要服务不可用");
    assertThat(textOf(result.workingSet().get(2))).contains("已丢弃");
  }

  /** 回复解析不出八字段（不是 JSON 对象）→ 同样降级，且 note 说明是"摘要不可用"而不是"没触门"。 */
  @Test
  void unparseableReplyDegradesToDisclosedMicroDrop() {
    List<LlmMessage> history = longHeadShortTail();
    ScriptedSummarizer summarizer = ScriptedSummarizer.returning("模型说了些别的，不是 JSON");

    Result result = new Compactor(policy(300), summarizer).compact(history, null);

    assertThat(result.tier()).isEqualTo(Tier.MICRO);
    assertThat(result.summary()).isNull();
    assertThat(result.note()).contains("无法解析");
    // 拿到了 response：这笔调用仍要记账（R7——花掉的 token 不能因为解析失败就从账上消失）
    assertThat(result.summarizerCall()).isNotNull();
  }

  /** 八字段全空（{@code {}}）与解析失败同归"摘要不可用"：不拿空摘要冒充成功。 */
  @Test
  void allEmptyReplyDegradesToDisclosedMicroDrop() {
    List<LlmMessage> history = longHeadShortTail();

    Result result =
        new Compactor(policy(300), ScriptedSummarizer.returning("{}")).compact(history, null);

    assertThat(result.tier()).isEqualTo(Tier.MICRO);
    assertThat(result.summary()).isNull();
    assertThat(result.note()).contains("无法解析");
    // 同上：拿到了 response 就有账（R7）
    assertThat(result.summarizerCall()).isNotNull();
  }

  /**
   * 触门但<b>找不到安全切点</b>（整段历史是一条未闭合的工具链，只有下标 0 是干净边界）→ 历史一个字节都不动 + note 披露原因。
   *
   * <p>判别性：按条数硬切的实现会在这里产出"尾部以 tool 结果开头"的工作集（悬空 {@code tool_result}），用例的 {@code tier == NONE} 与
   * {@code workingSet} 逐条相等断言必红。
   */
  @Test
  void noSafeCutPointLeavesTheHistoryUntouchedAndDisclosesWhy() {
    List<LlmMessage> history = unclosedToolChain();
    ScriptedSummarizer summarizer = new ScriptedSummarizer();

    Result result = new Compactor(policy(100), summarizer).compact(history, null);

    assertThat(result.tier()).isEqualTo(Tier.NONE);
    assertThat(result.workingSet()).containsExactlyElementsOf(history);
    assertThat(result.note()).contains("无安全切点");
    assertThat(summarizer.calls()).isZero();
  }

  // ---------- 夹具 ----------

  private static ContextPolicy policy(int conversationBudget) {
    return ContextPolicy.defaults().withBudget(ContextLayer.CONVERSATION, conversationBudget);
  }

  private static String textOf(LlmMessage message) {
    return message.content().stream()
        .filter(ContentPart.Text.class::isInstance)
        .map(ContentPart.Text.class::cast)
        .map(ContentPart.Text::text)
        .reduce("", String::concat);
  }

  /** 18 条：头一对极小、中段 10 条各 ~2000 字符、尾 6 条极小。全量 ≈ 5035 token，档 1 视图 ≈ 32 token。 */
  private static List<LlmMessage> longMiddle() {
    List<LlmMessage> history = new ArrayList<>();
    history.add(LlmMessage.user("U0 头"));
    history.add(LlmMessage.assistant(List.of(new ContentPart.Text("A0 头"))));
    for (int i = 1; i <= 5; i++) {
      history.add(LlmMessage.user("U" + i + " " + "中".repeat(2000) + " MID-SENTINEL"));
      history.add(
          LlmMessage.assistant(List.of(new ContentPart.Text("A" + i + " " + "中".repeat(2000)))));
    }
    for (int i = 6; i <= 8; i++) {
      history.add(LlmMessage.user("U" + i + " 尾"));
      history.add(LlmMessage.assistant(List.of(new ContentPart.Text("A" + i + " 尾"))));
    }
    return history;
  }

  /**
   * 18 条：头一对各 4000 字符（档 1 会保留头部 ⇒ 档 1 视图恒 ≥ 2010 token）、尾 6 条各 ~110 字符。全量 ≈ 2330 token。
   *
   * <p>这个形状让"档 1 放不下、档 2 的尾部放得下"成为预算的单调区间：预算 300 → 档 2；预算 60 → 档 3。
   */
  private static List<LlmMessage> longHeadShortTail() {
    List<LlmMessage> history = new ArrayList<>();
    history.add(LlmMessage.user("U0 " + "头".repeat(4000)));
    history.add(LlmMessage.assistant(List.of(new ContentPart.Text("A0 " + "头".repeat(4000)))));
    for (int i = 1; i <= 8; i++) {
      history.add(LlmMessage.user("U" + i + " " + "尾".repeat(100)));
      history.add(
          LlmMessage.assistant(List.of(new ContentPart.Text("A" + i + " " + "尾".repeat(100)))));
    }
    return history;
  }

  /** 12 条：倒数第 6 条是带 tool_call 的 assistant——按条数硬切会劈开 c2 这一对。 */
  private static List<LlmMessage> toolPairInTheTailWindow() {
    List<LlmMessage> history = new ArrayList<>();
    history.add(LlmMessage.user("U0"));
    history.add(LlmMessage.assistant(List.of(new ContentPart.Text("A0"))));
    history.add(LlmMessage.user("U1 " + "中".repeat(1200)));
    history.add(
        LlmMessage.assistant(
            List.of(new ContentPart.ToolCall("c1", "shell", Map.of("cmd", "echo 1")))));
    history.add(LlmMessage.tool(new ContentPart.ToolResult("c1", "shell", "结".repeat(1200), null)));
    history.add(LlmMessage.user("U2 " + "中".repeat(600)));
    history.add(
        LlmMessage.assistant(
            List.of(new ContentPart.ToolCall("c2", "shell", Map.of("cmd", "echo 2")))));
    history.add(LlmMessage.tool(new ContentPart.ToolResult("c2", "shell", "结".repeat(600), null)));
    history.add(LlmMessage.user("U3 " + "中".repeat(600)));
    history.add(LlmMessage.assistant(List.of(new ContentPart.Text("A3 " + "中".repeat(600)))));
    history.add(LlmMessage.user("U4 " + "中".repeat(600)));
    history.add(LlmMessage.assistant(List.of(new ContentPart.Text("A4 " + "中".repeat(600)))));
    return history;
  }

  /** 17 条未闭合工具链：除下标 0 外没有任何 user 边界（尾部窗口内找不到干净切点）。 */
  private static List<LlmMessage> unclosedToolChain() {
    List<LlmMessage> history = new ArrayList<>();
    history.add(LlmMessage.user("开始 " + "链".repeat(2000)));
    for (int i = 1; i <= 8; i++) {
      history.add(
          LlmMessage.assistant(
              List.of(new ContentPart.ToolCall("c" + i, "shell", Map.of("cmd", "echo " + i)))));
      history.add(
          LlmMessage.tool(new ContentPart.ToolResult("c" + i, "shell", "结果".repeat(300), null)));
    }
    return history;
  }

  /** 任何调用都是编排错误：AssertionError 不被 {@code catch (LlmException)} 吞掉，用例直接炸红。 */
  private static final class UnscriptedSummarizer implements LlmClient {

    @Override
    public LlmResponse chat(LlmRequest request) {
      throw new AssertionError("本用例不该发生摘要调用：" + request.messages().size() + " 条输入");
    }
  }

  /** 脚本化摘要器：记录请求、计数调用，按脚本返回或抛 {@link LlmException}。 */
  private static final class ScriptedSummarizer implements LlmClient {

    private final Deque<LlmResponse> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();
    private LlmException failure;
    private int calls;

    static ScriptedSummarizer returning(String replyText) {
      return new ScriptedSummarizer()
          .enqueue(
              new LlmResponse(
                  LlmMessage.assistant(List.of(new ContentPart.Text(replyText))),
                  "fake-summarizer",
                  321,
                  123,
                  45,
                  6));
    }

    static ScriptedSummarizer failing(String message) {
      ScriptedSummarizer summarizer = new ScriptedSummarizer();
      summarizer.failure = new LlmException(message);
      return summarizer;
    }

    ScriptedSummarizer enqueue(LlmResponse response) {
      script.addLast(response);
      return this;
    }

    int calls() {
      return calls;
    }

    List<LlmRequest> requests() {
      return requests;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      calls++;
      requests.add(request);
      if (failure != null) {
        throw failure;
      }
      LlmResponse response = script.pollFirst();
      if (response == null) {
        throw new AssertionError("摘要器脚本耗空");
      }
      return response;
    }
  }
}
