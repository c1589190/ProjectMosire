package io.mosire.agentlib.compact;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.context.ContextComposer;
import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.llm.ModelCapabilities;
import io.mosire.agentlib.tool.AgentTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认上下文压缩实现：达到阈值或收到一次性手动请求时，让摘要模型总结当前可见历史，并用 compact 标记 + 下一轮 user 继续。
 *
 * <p>触发与提交顺序是硬约束：先估算完整当前请求，再调用摘要模型，再用<b>与真实投影完全一致的合成 user</b>做后校验；只有后校验通过才修改 {@link
 * ContextWindow}。失败、空摘要或后校验超线都抛 {@link CompactionException}，不退回未压缩请求。
 *
 * <p>本类非 final；{@link #summarize(List)}、{@link #estimateRequest(LlmRequest)}、{@link
 * #nextUserText(List)} 与 {@link #validatePostCompaction(LlmRequest)} 都可覆写。子类若替换 {@link
 * #summarize(List)} 并希望宿主仍能记账，应同时填写 {@link #lastSummarizerResponse} 与 {@link
 * #lastSummarizerLatencyMs}。
 *
 * <p>并发口径沿用 AgentRuntime 的串行约定：同一实例不在多个执行线程上并发调用；一次性 force 标志使用 {@link AtomicBoolean}
 * 只是为了管理线程与执行线程之间的可见性。
 */
public class DefaultContextCompactor implements ContextCompactor {

  private final LlmClient summarizer;
  private final CompactionPolicy policy;
  private final TokenEstimator tokenEstimator;
  private final AtomicBoolean forceNextCompaction = new AtomicBoolean(false);

  /** 最近一次摘要调用返回的响应；默认实现自动填写。子类替换 {@link #summarize(List)} 时可填写它，以保留宿主记账能力。 */
  protected LlmResponse lastSummarizerResponse;

  /** 最近一次摘要调用的墙钟耗时毫秒；默认实现自动填写。volatile 保证管理/执行线程可见性。 */
  protected volatile long lastSummarizerLatencyMs;

  /**
   * 使用默认保守估算器。
   *
   * @param summarizer 摘要模型客户端（非 null）
   * @param policy 压缩策略（非 null）
   */
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "注入依赖/策略在构造期 fail-fast 是设计意图；本类不使用 finalizer，子类若要覆写构造期行为应自行承担异常安全责任")
  public DefaultContextCompactor(LlmClient summarizer, CompactionPolicy policy) {
    this(summarizer, policy, new TokenEstimator());
  }

  /**
   * 显式注入 token 估算器。
   *
   * @param summarizer 摘要模型客户端（非 null）
   * @param policy 压缩策略（非 null）
   * @param tokenEstimator token 估算器（非 null；可注入更精确实现）
   */
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "注入依赖/策略在构造期 fail-fast 是设计意图；本类不使用 finalizer，子类若要覆写构造期行为应自行承担异常安全责任")
  public DefaultContextCompactor(
      LlmClient summarizer, CompactionPolicy policy, TokenEstimator tokenEstimator) {
    this.summarizer = Objects.requireNonNull(summarizer, "summarizer");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
  }

  /**
   * 从模型能力与 Agent 级输出上限构造默认压缩策略。
   *
   * <p>本工厂<b>严格失败、不猜模型容量</b>：{@code capabilities.maxContext} 或 {@code capabilities.maxOutput}
   * 缺失（≤0）时直接抛 {@link IllegalArgumentException}，不会回落到固定常数或自动关闭压缩。 有效输出上限优先取 {@code
   * agentMaxOutputTokens} 与 {@code capabilities.maxOutput()} 的较小值； {@code agentMaxOutputTokens} 为 0
   * 表示沿用模型能力上限。
   *
   * <p>默认参数：{@code summaryMaxTokens = min(effectiveMaxOutput, 4096)}、{@code headroomTokens = 0}，
   * 压缩提示词使用 {@link CompactionPrompts#COMPACTION_PROMPT}。
   *
   * @param capabilities 模型能力（非 null）
   * @param agentMaxOutputTokens Agent 级最大输出 token；0 = 不额外收窄，负数非法
   * @return 默认压缩策略
   * @throws IllegalArgumentException capabilities 容量缺失、agentMaxOutputTokens 为负，或派生策略不合法
   */
  public static CompactionPolicy defaultPolicy(
      ModelCapabilities capabilities, int agentMaxOutputTokens) {
    Objects.requireNonNull(capabilities, "capabilities");
    if (capabilities.maxContext() <= 0) {
      throw new IllegalArgumentException(
          "模型能力缺失或非法：capabilities.maxContext 必须为正整数，当前值=" + capabilities.maxContext());
    }
    if (capabilities.maxOutput() <= 0) {
      throw new IllegalArgumentException(
          "模型能力缺失或非法：capabilities.maxOutput 必须为正整数，当前值=" + capabilities.maxOutput());
    }
    if (agentMaxOutputTokens < 0) {
      throw new IllegalArgumentException("agentMaxOutputTokens 不得为负: " + agentMaxOutputTokens);
    }
    int effectiveMaxOutput =
        agentMaxOutputTokens > 0
            ? Math.min(agentMaxOutputTokens, capabilities.maxOutput())
            : capabilities.maxOutput();
    int summaryMaxTokens = Math.min(effectiveMaxOutput, 4096);
    return new CompactionPolicy(
        capabilities.maxContext(),
        effectiveMaxOutput,
        summaryMaxTokens,
        0,
        CompactionPrompts.COMPACTION_PROMPT);
  }

  /** 手动请求下一次检查强制压缩；多次请求合并为一次。 */
  @Override
  public void requestCompaction() {
    forceNextCompaction.set(true);
  }

  @Override
  @SuppressFBWarnings(
      value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
      justification = "summarize 是 protected 可覆写方法；默认实现非 null，但子类可能返回 null，这里必须严格失败而不是静默接受")
  public CompactionOutcome compactIfNeeded(
      ContextWindow window,
      List<LlmMessage> turnInput,
      List<AgentTool> tools,
      ContextComposer composer) {
    Objects.requireNonNull(window, "window");
    List<LlmMessage> input = List.copyOf(Objects.requireNonNull(turnInput, "turnInput"));
    List<AgentTool> toolList = List.copyOf(Objects.requireNonNull(tools, "tools"));
    Objects.requireNonNull(composer, "composer");

    boolean forced = forceNextCompaction.getAndSet(false);
    List<LlmMessage> visible = window.messages();
    LlmRequest currentRequest =
        Objects.requireNonNull(
            composer.compose(visible, input, toolList), "composer.compose 返回的请求");
    int beforeTokens = estimateRequest(currentRequest);

    int triggerTokens = computeTriggerTokens();

    if (!forced && beforeTokens < triggerTokens) {
      return notCompacted(input, beforeTokens, triggerTokens);
    }
    // 手动强制也不能在没有可见历史时凭空产生摘要；保持窗口不变，也不花一次摘要调用。
    if (visible.isEmpty()) {
      return notCompacted(input, beforeTokens, triggerTokens);
    }

    lastSummarizerResponse = null;
    lastSummarizerLatencyMs = 0L;

    LlmRequest summaryRequest = buildSummaryRequest(visible);
    long summaryRequestBudget = (long) estimateRequest(summaryRequest) + policy.summaryMaxTokens();
    if (summaryRequestBudget > policy.maxContextTokens()) {
      throw new CompactionException("摘要请求预算不足：摘要输入估算 + summaryMaxTokens 超过 maxContextTokens");
    }

    String summary;
    try {
      summary = summarize(visible);
    } catch (CompactionException e) {
      throw e;
    } catch (LlmException e) {
      throw new CompactionException("压缩摘要调用失败", e, lastSummarizerResponse, lastSummarizerLatencyMs);
    }
    if (summary == null || summary.isBlank()) {
      throw new CompactionException("压缩摘要正文为空", lastSummarizerResponse, lastSummarizerLatencyMs);
    }
    summary = summary.strip();

    String nextUserText = nextUserText(input);
    LlmMessage projectedUser =
        LlmMessage.user(ContextWindow.formatCompactedUser(summary, nextUserText));
    LlmRequest postRequest =
        Objects.requireNonNull(
            composer.compose(List.of(projectedUser), List.of(), toolList),
            "composer.compose 返回的请求");

    validatePostCompaction(postRequest);
    int afterTokens = estimateRequest(postRequest);
    if (afterTokens >= triggerTokens) {
      throw new CompactionException("压缩后估算仍达到触发线", lastSummarizerResponse, lastSummarizerLatencyMs);
    }

    // 所有拒绝判定均已通过，才提交窗口改动。
    window.compact(summary);
    window.append(LlmMessage.user(nextUserText));

    return new CompactionOutcome(
        List.of(),
        true,
        forced ? "manual" : "threshold",
        beforeTokens,
        afterTokens,
        triggerTokens,
        visible.size(),
        tokenEstimator.estimateText(summary),
        lastSummarizerResponse,
        lastSummarizerLatencyMs);
  }

  /**
   * 构造摘要请求：只包含当前可见历史 + 末尾固定的 compactionPrompt，且显式无 tools、输出上限为 {@code summaryMaxTokens}。
   *
   * @param visibleHistory 当前窗口可见历史
   * @return 摘要请求
   */
  protected LlmRequest buildSummaryRequest(List<LlmMessage> visibleHistory) {
    List<LlmMessage> messages = new ArrayList<>(visibleHistory.size() + 1);
    messages.addAll(visibleHistory);
    messages.add(LlmMessage.user(policy.compactionPrompt()));
    return new LlmRequest(messages, List.of()).withMaxTokens(policy.summaryMaxTokens());
  }

  /**
   * 调用摘要模型并返回非空摘要正文。
   *
   * <p>默认实现只接受第一条文本 part；响应带任何工具调用、只有思维链（折叠或未折叠）或正文空白都抛 {@link
   * CompactionException}。失败时也会先填入响应与耗时字段，供上层记账。
   *
   * @param visibleHistory 可见历史（不含当前 turnInput）
   * @return 去掉首尾空白后的摘要正文
   */
  protected String summarize(List<LlmMessage> visibleHistory) {
    LlmRequest request = buildSummaryRequest(visibleHistory);
    long startedNanos = System.nanoTime();
    try {
      LlmResponse response = Objects.requireNonNull(summarizer.chat(request), "summarizer.chat");
      long latencyMs = elapsedMillis(startedNanos);
      lastSummarizerResponse = response;
      lastSummarizerLatencyMs = latencyMs;

      boolean hasToolCall =
          response.assistantMessage().content().stream()
              .anyMatch(ContentPart.ToolCall.class::isInstance);
      if (hasToolCall) {
        throw new CompactionException("压缩摘要响应包含工具调用", response, latencyMs);
      }
      if (response.reasoningOnly()) {
        throw new CompactionException("压缩摘要响应只有思维链、没有正文", response, latencyMs);
      }
      Optional<String> text = response.textPart();
      if (text.isEmpty() || text.get().isBlank()) {
        throw new CompactionException("压缩摘要响应没有非空正文", response, latencyMs);
      }
      return text.get().strip();
    } catch (CompactionException e) {
      throw e;
    } catch (LlmException e) {
      throw new CompactionException(
          "压缩摘要调用失败", e, lastSummarizerResponse, elapsedMillis(startedNanos));
    } finally {
      lastSummarizerLatencyMs = elapsedMillis(startedNanos);
    }
  }

  /**
   * 估算完整请求（messages + tools）。
   *
   * @param request 请求
   * @return 保守估算 token
   */
  protected int estimateRequest(LlmRequest request) {
    return tokenEstimator.estimateRequest(request);
  }

  /**
   * 取下一轮真实 user 文本：所有 user 消息的文本 part 按序以换行连接，非文本 part 丢弃；没有可用文本时返回固定续作提示。
   *
   * @param turnInput 本轮输入
   * @return 非空、纯文本的下一轮 user 内容
   */
  protected String nextUserText(List<LlmMessage> turnInput) {
    StringBuilder text = new StringBuilder();
    for (LlmMessage message : turnInput) {
      if (!LlmMessage.ROLE_USER.equals(message.role())) {
        continue;
      }
      for (ContentPart part : message.content()) {
        if (part instanceof ContentPart.Text textPart) {
          if (text.length() > 0) {
            text.append('\n');
          }
          text.append(textPart.text());
        }
      }
    }
    if (text.toString().isBlank()) {
      return CompactionPrompts.CONTINUATION_PROMPT;
    }
    return text.toString();
  }

  /**
   * 后校验：压缩后的完整请求估算必须严格小于触发线。
   *
   * <p>默认实现用 {@link #estimateRequest(LlmRequest)} 判断；子类可覆写以增加检查，但不得放宽默认严格失败语义。
   *
   * @param postRequest 压缩后请求（messages = 真实可见投影等价物；tools 仍为本轮工具）
   * @throws CompactionException 估算仍达到触发线
   */
  protected void validatePostCompaction(LlmRequest postRequest) {
    int afterTokens = estimateRequest(postRequest);
    if (afterTokens >= computeTriggerTokens()) {
      throw new CompactionException("压缩后估算仍达到触发线", lastSummarizerResponse, lastSummarizerLatencyMs);
    }
  }

  /** 按当前策略局部计算触发线；不使用跨方法共享的可变字段。 */
  private int computeTriggerTokens() {
    int promptTokens = tokenEstimator.estimateText(policy.compactionPrompt());
    long trigger =
        (long) policy.maxContextTokens()
            - policy.maxOutputTokens()
            - promptTokens
            - policy.headroomTokens();
    if (trigger <= 0L) {
      throw new CompactionException(
          "压缩触发线不为正：maxContextTokens - maxOutputTokens - promptTokens - headroomTokens = "
              + trigger);
    }
    return (int) trigger;
  }

  private CompactionOutcome notCompacted(
      List<LlmMessage> turnInput, int beforeTokens, int trigger) {
    return new CompactionOutcome(
        turnInput, false, "", beforeTokens, beforeTokens, trigger, 0, 0, null, 0L);
  }

  private static long elapsedMillis(long startedNanos) {
    return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
  }
}
