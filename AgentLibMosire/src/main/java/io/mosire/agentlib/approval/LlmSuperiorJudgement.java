package io.mosire.agentlib.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上级判定的<b>真 LLM 实现</b>：一次性调用（无工具、单轮、不进聊天执行器）问"这条命令与下级任务相符吗"。
 *
 * <p><b>为什么是一次性调用而不是"让上级 Agent 跑一轮"</b>：判定发生在<b>调用者线程</b>上（工具调用经 MCP 面进来，等待审批是同步的），而本进程的 Agent
 * 回合跑在单线程聊天执行器上（{@code App} 的 {@code newSingleThreadExecutor}，A2A/AG-UI/调试面共用）。若判定去排聊天执行器，一个子 Agent
 * 的命令审批就能把整条聊天管线锁死 ——它自己排在自己后面。故走 {@code Compactor.summarize} 那条先例：{@code LlmClient.chat}
 * 直接调，无工具、无历史、无管线。
 *
 * <p><b>失败与超时一律按"拒"办</b>（fail-closed）：解析不出、调用抛错、超过 {@link #DEFAULT_TIMEOUT} 都返回 {@code DENY}。
 * 反向选择（失败即放行）会让"判定面一挂，子 Agent 就无人看管"。
 *
 * <p><b>判定提示只进内存</b>：提示里带着命令原文（提示面语义，同 {@code summary}），回复里的理由只进日志—— 两者都不进事件库（D23/D24）。
 *
 * <h2>★ 看全部内容，看不全就降级到人（决策包）</h2>
 *
 * <p>一条 {@link ApprovalRequest} 只带 {@code summary}/{@code digest}：对单条命令够用，对<b>决策包</b>不够——包的 {@code
 * summary} 只有"几条、哪些工具"，上级看得到"要改"，看不到"改成什么"（用户 2026-10-16 原话：「肯定展示全部内容啊」）。 所以装配面可以经 {@link
 * SuperiorJudgeDetails} 另路提供<b>逐条明细</b>（工具名 + 人读摘要 + 参数明文），本类把它整段写进提示。
 *
 * <p><b>长度策略是响亮的</b>（不是截断）：单条明细超过 {@link #MAX_DETAIL_ITEM_CHARS} 或整条提示超过 {@link #MAX_PROMPT_CHARS}
 * ⇒ <b>不截断</b>，直接 {@link SuperiorJudgement.Decision#deny(String)} 并给出 {@value #REASON_OVER_BUDGET}，
 * 把这次决定<b>降级到人</b>。截半条让上级"看一半批全部"是把人的判断力换成了一句谎言——本项目不做。
 */
public final class LlmSuperiorJudgement implements SuperiorJudgement {

  private static final Logger LOG = LoggerFactory.getLogger(LlmSuperiorJudgement.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 判定调用的墙钟上限（默认 30s）。要卡在审批 deadline（缺省 300s）与子体预算之内的数量级上：判定是同步等待，慢判定 = 空转的子 Agent。 */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /** 判定提示里<b>单条明细块</b>的字符上限（工具名 + 摘要 + 参数明文）：超过 ⇒ 拒（不截断），见类注释的长度策略。 */
  public static final int MAX_DETAIL_ITEM_CHARS = 4000;

  /** 判定提示（整条 user 消息）的字符上限：超过 ⇒ 拒（不截断）。 */
  public static final int MAX_PROMPT_CHARS = 24000;

  /** ★ 超预算的拒因（逐字固定，用户要求的"降级到人"那句话）。 */
  public static final String REASON_OVER_BUDGET = "内容超出判读预算，转人工";

  /** 明细取数失败的拒因（fail-closed：看不见要改什么，就不给上级批）。 */
  static final String REASON_DETAILS_FAILED = "判定明细取数失败，转人工";

  private static final int MAX_GOAL_CHARS = 400;
  private static final int MAX_SUMMARY_CHARS = 800;
  private static final int MAX_REASON_CHARS = 200;

  /** 判定提示（system）：把标准写死在这里，别指望模型猜。 */
  static final String INSTRUCTION =
      """
      你是 Mosire 的上级 Agent。下级 Agent 处于「不完全权限」档：它执行的每条 shell 命令、以及它提的每份决策包，
      都必须先得到批准。它跑在独立进程里，动作不可回滚。你的任务是判断这一次调用（一条命令，或一个包里的一批改动）该不该放行。

      判断顺序：
      1. 与任务相符吗？这次调用要服务于任务里写明的事；与任务无关的一律拒。
      2. 有无更小的做法？越权、越界写入、批量删除、把权限面扩大（改权限/起服务/装软件）一律拒。
      3. 后果可逆吗？不可逆或影响面不清的，拒。

      提示里若给了「逐条明细」，那就是这条请求要执行的全部内容：逐条看清楚，任何一条看不全、看不懂都不要放行。

      只输出一行：先给 APPROVE 或 DENY，再跟不超过 60 字的中文理由。
      拿不准就 DENY。不要输出别的任何内容。""";

  /** 认的"放行"首词（同意意思的常见写法都认；只认<b>首词</b>，句中出现的字眼不算数）。 */
  private static final Set<String> APPROVE_WORDS = Set.of("APPROVE", "ALLOW", "同意", "放行", "批准");

  /** 认的"拒"首词。 */
  private static final Set<String> DENY_WORDS =
      Set.of("DENY", "REJECT", "BLOCK", "拒绝", "驳回", "不允许");

  private static final String DECORATION = "*_`\"'[](){}#";
  private static final String DELIMITERS = " \t\n\r:：,，.。!！;；、—";

  private final LlmClient llm;
  private final Duration timeout;

  /** 逐条明细的取数口（{@link SuperiorJudgeDetails#NONE} = 没有明细来源：只看请求自带的 summary）。 */
  private final SuperiorJudgeDetails details;

  public LlmSuperiorJudgement(LlmClient llm) {
    this(llm, DEFAULT_TIMEOUT);
  }

  public LlmSuperiorJudgement(LlmClient llm, Duration timeout) {
    this(llm, timeout, SuperiorJudgeDetails.NONE);
  }

  /**
   * @param llm 判定用的模型客户端（一次性调用，见类注释）
   * @param timeout 墙钟上限（必须为正）
   * @param details 逐条明细的取数口（非 null；{@link SuperiorJudgeDetails#NONE} = 不提供明细）。
   *     <b>决策包的上级代批必须接上它</b>：接不上就只能看到"几条、哪些工具"，那是"看一半批全部"的形状 ——装配侧因此按"明细缝在不在"决定要不要把包交给上级（见 {@code
   *     DecisionApprovalBridge#requestFor}）。
   */
  public LlmSuperiorJudgement(LlmClient llm, Duration timeout, SuperiorJudgeDetails details) {
    this.llm = Objects.requireNonNull(llm, "llm");
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("判定超时必须为正: " + timeout);
    }
    this.timeout = timeout;
    this.details = Objects.requireNonNull(details, "details");
  }

  @Override
  public SuperiorJudgement.Decision judge(ApprovalRequest request) {
    Objects.requireNonNull(request, "request");
    Prompt rendered = prompt(request, details);
    if (rendered.refused()) {
      // ★ 不截断：超预算 / 明细取数失败一律回绝，把决定降级到人（fail-closed 朝向"人来判"那一侧）
      LOG.warn(
          "上级判定降级到人: requester={} class={} 原因={}",
          request.requesterId(),
          request.classKey(),
          rendered.refusal());
      return Decision.deny(rendered.refusal());
    }
    LlmRequest ask =
        LlmRequest.ofMessages(
            List.of(LlmMessage.system(INSTRUCTION), LlmMessage.user(rendered.text())));
    // 判定跑在调用者线程派生的一条虚拟线程上：① 不占聊天执行器（见类 javadoc 的自互锁）；② 墙钟有上限，
    // 到点就放弃等待并中断调用（HTTP 客户端自己的读超时是第二道兜底）。
    ExecutorService worker =
        Executors.newSingleThreadExecutor(Thread.ofVirtual().name("approval-judge").factory());
    try {
      Future<LlmResponse> pending = worker.submit(() -> llm.chat(ask));
      LlmResponse response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      Decision parsed = parse(response.textPart().orElse(""));
      LOG.debug("上级判定完成: model={} requester={}", llm.model(), request.requesterId());
      return parsed;
    } catch (TimeoutException e) {
      return Decision.deny("判定超时（" + timeout.toSeconds() + "s）");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Decision.deny("判定等待被中断");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      return Decision.deny("判定调用失败：" + cause.getClass().getSimpleName());
    } catch (RuntimeException e) {
      return Decision.deny("判定调用失败：" + e.getClass().getSimpleName());
    } finally {
      worker.shutdownNow();
    }
  }

  /** 无明细来源时的判定提示（等价于 {@code prompt(request, SuperiorJudgeDetails.NONE)}）。 */
  static Prompt prompt(ApprovalRequest request) {
    return prompt(request, SuperiorJudgeDetails.NONE);
  }

  /**
   * 判定提示（user）：把"谁在问、被派去干什么、想问什么"讲清楚——上级判定要回答的正是这三者的一致性。
   *
   * <p><b>逐条明细整段写入、逐字不截断</b>：明细存在时另起一段把每条（工具名 + 人读摘要 + 参数明文）写全。长度不是靠截断收口， 而是靠"超预算 ⇒ {@link
   * Prompt#refusal()} 非空 ⇒ 调用方拒并转人工"（见类注释的长度策略）。
   *
   * <p>{@code goal}/{@code summary} 仍按既有上限 {@code clamp}：它们是宿主自报的<b>一行</b>说明（单条请求本来就靠它），
   * 与"决策包要执行的全部内容"不是一回事；包的明细走 {@code items} 这一路，不受这两个上限影响。
   */
  static Prompt prompt(ApprovalRequest request, SuperiorJudgeDetails details) {
    List<JudgementItem> items;
    try {
      items = details == null ? List.of() : details.itemsFor(request);
    } catch (RuntimeException failure) {
      // 取数失败 = 不知道要改什么：fail-closed 朝向"人来判"
      return new Prompt("", REASON_DETAILS_FAILED);
    }
    if (items == null) {
      return new Prompt("", REASON_DETAILS_FAILED);
    }
    String goal = request.goal().isBlank() ? "（未提供）" : clamp(request.goal(), MAX_GOAL_CHARS);
    try {
      StringBuilder out = new StringBuilder();
      out.append("下级 Agent: ")
          .append(request.requesterId())
          .append("\n它的任务: ")
          .append(goal)
          .append("\n工具: ")
          .append(request.tool())
          .append("\n命令类别: ")
          .append(request.classKey())
          .append("\n命令说明: ")
          .append(clamp(request.summary(), MAX_SUMMARY_CHARS));
      if (!items.isEmpty()) {
        out.append("\n逐条明细（").append(items.size()).append(" 条；以下为全部内容，未截断）:");
        for (int position = 0; position < items.size(); position++) {
          String block = itemBlock(position, items.get(position));
          if (block.length() > MAX_DETAIL_ITEM_CHARS) {
            // ★ 单条超限：不截断、不省略，直接让调用方拒（转人工）
            return new Prompt("", REASON_OVER_BUDGET);
          }
          out.append('\n').append(block);
        }
      }
      out.append('\n');
      if (out.length() > MAX_PROMPT_CHARS) {
        // ★ 整包超预算：同上（宁可多问一次人，也不给上级一份"看起来完整"的残页）
        return new Prompt("", REASON_OVER_BUDGET);
      }
      return new Prompt(out.toString(), "");
    } catch (RuntimeException failure) {
      return new Prompt("", REASON_DETAILS_FAILED);
    }
  }

  /** 单条明细块（工具名 + 人读摘要 + 参数明文 JSON）：<b>不截断</b>，超限由 {@link #prompt} 拒。 */
  private static String itemBlock(int position, JudgementItem item) {
    return "["
        + position
        + "] 工具: "
        + item.toolName()
        + "\n    摘要: "
        + item.summary()
        + "\n    参数: "
        + json(item.args());
  }

  /** 参数明文 → JSON（明细的呈现形式）；序列化不出来 ⇒ 抛给 {@link #prompt} 统一转成"取数失败"。 */
  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("判定明细的参数无法呈现为 JSON", e);
    }
  }

  /**
   * 解析模型回复（<b>严格</b>）：首词在 {@link #APPROVE_WORDS} 里 = 放行，在 {@link #DENY_WORDS} 里 = 拒，
   * 其余一律拒（fail-closed）——"看不懂的答复"不能等同于批准。
   */
  static SuperiorJudgement.Decision parse(String modelText) {
    String text = modelText == null ? "" : modelText.strip();
    if (text.isEmpty()) {
      return Decision.deny("判定答复为空");
    }
    String head = firstToken(text);
    String word = head.toUpperCase(Locale.ROOT);
    String reason = reasonOf(text, head);
    if (APPROVE_WORDS.contains(word)) {
      return Decision.approve(reason);
    }
    if (DENY_WORDS.contains(word)) {
      return Decision.deny(reason);
    }
    return Decision.deny("判定答复无法解析（首词=" + head + "）");
  }

  /** 取首个词：跳过 markdown/引号装饰，遇到分隔符或空白即止（{@code "**APPROVE：** 理由"} ⇒ {@code APPROVE}）。 */
  static String firstToken(String text) {
    int start = 0;
    while (start < text.length() && DECORATION.indexOf(text.charAt(start)) >= 0) {
      start++;
    }
    int end = start;
    while (end < text.length() && DELIMITERS.indexOf(text.charAt(end)) < 0) {
      end++;
    }
    return text.substring(start, end);
  }

  private static String reasonOf(String text, String head) {
    int at = text.indexOf(head);
    String rest = at < 0 ? "" : text.substring(at + head.length());
    return clamp(
        rest.replaceAll("\\s+", " ").replaceAll("^[:：,，.。!！;；、—]+", "").strip(), MAX_REASON_CHARS);
  }

  private static String clamp(String value, int max) {
    return value.length() > max ? value.substring(0, max) + "…" : value;
  }

  /**
   * 判定提示的组装结果：要么一份可判读的提示（{@code refusal} 为空），要么一条<b>降级到人</b>的理由。
   *
   * <p>把"拒"做成组装结果的一部分，是为了让"超预算"这条路径<b>结构上不可能</b>被写成一个截断后的提示 ——那正是本类要禁止的形状（{@code refusal}
   * 非空时提示文本为空，调用方没有任何东西可以拿去判）。
   */
  record Prompt(String text, String refusal) {

    Prompt {
      text = text == null ? "" : text;
      refusal = refusal == null ? "" : refusal;
    }

    /** {@code true} = 不许判，交给调用方回绝（转人工）。 */
    boolean refused() {
      return !refusal.isEmpty();
    }
  }
}
