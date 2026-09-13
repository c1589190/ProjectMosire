package io.mosire.agentlib.approval;

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
 */
public final class LlmSuperiorJudgement implements SuperiorJudgement {

  private static final Logger LOG = LoggerFactory.getLogger(LlmSuperiorJudgement.class);

  /** 判定调用的墙钟上限（默认 30s）。要卡在审批 deadline（缺省 300s）与子体预算之内的数量级上：判定是同步等待，慢判定 = 空转的子 Agent。 */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private static final int MAX_GOAL_CHARS = 400;
  private static final int MAX_SUMMARY_CHARS = 800;
  private static final int MAX_REASON_CHARS = 200;

  /** 判定提示（system）：把标准写死在这里，别指望模型猜。 */
  static final String INSTRUCTION =
      """
      你是 Mosire 的上级 Agent。下级 Agent 处于「不完全权限」档：它执行的每条 shell 命令都必须先得到批准。
      它跑在独立进程里，动作不可回滚。你的任务是判断这一条命令该不该放行。

      判断顺序：
      1. 与任务相符吗？命令要服务于任务里写明的事；与任务无关的命令一律拒。
      2. 有无更小的做法？越权、越界写入、批量删除、把权限面扩大（改权限/起服务/装软件）一律拒。
      3. 后果可逆吗？不可逆或影响面不清的，拒。

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

  public LlmSuperiorJudgement(LlmClient llm) {
    this(llm, DEFAULT_TIMEOUT);
  }

  public LlmSuperiorJudgement(LlmClient llm, Duration timeout) {
    this.llm = Objects.requireNonNull(llm, "llm");
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("判定超时必须为正: " + timeout);
    }
    this.timeout = timeout;
  }

  @Override
  public SuperiorJudgement.Decision judge(ApprovalRequest request) {
    Objects.requireNonNull(request, "request");
    LlmRequest ask =
        LlmRequest.ofMessages(
            List.of(LlmMessage.system(INSTRUCTION), LlmMessage.user(prompt(request))));
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

  /** 判定提示（user）：把"谁在问、被派去干什么、想问什么"讲清楚——上级判定要回答的正是这三者的一致性。 */
  static String prompt(ApprovalRequest request) {
    String goal = request.goal().isBlank() ? "（未提供）" : clamp(request.goal(), MAX_GOAL_CHARS);
    return "下级 Agent: "
        + request.requesterId()
        + "\n它的任务: "
        + goal
        + "\n工具: "
        + request.tool()
        + "\n命令类别: "
        + request.classKey()
        + "\n命令说明: "
        + clamp(request.summary(), MAX_SUMMARY_CHARS)
        + "\n";
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
}
