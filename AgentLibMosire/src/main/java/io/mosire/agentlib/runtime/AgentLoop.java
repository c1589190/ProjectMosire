package io.mosire.agentlib.runtime;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.context.ContextComposer;
import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.context.LlmRoundTrip;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmException;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.tool.AgentTool;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * <b>通用 Agent 循环骨架</b>：一次 {@link #runTurn} = 一个用户回合，从本轮输入开始，循环「组请求 → 一轮 LLM 往返 → 工具调用分发 → 终止判定」，
 * 直到宿主策略喊停、模型自然收尾、单响应工具调用超限或 LLM 调用失败。
 *
 * <p><b>为什么循环有资格进库（而压缩/技能/记忆没有）</b>：循环的<b>结构</b>——先问停不停、组请求、发信、写回、解析工具调用、逐条分派、回到开头 ——是所有调用 LLM 的
 * Agent 都一样的骨架；而"每步做什么"（预算怎么判、事件怎么记、工具怎么执行、历史怎么落库）才是每个宿主的策略。 本类只实现结构，把三个决策点抽成 SPI：{@link
 * StopPolicy}（何时停）、{@link TurnListener}（记账/事件/终止收尾）、{@link ToolCallDispatcher}（工具怎么执行）。任何程序依赖
 * {@code agentlib-mosire} 就能复用它，不需要 Agent 运行时。
 *
 * <p><b>★ 骨架<b>不做</b>的事（边界，不是遗漏）</b>：
 *
 * <ul>
 *   <li><b>不直接改窗口</b>：窗口写入只来自 {@link LlmRoundTrip}（往返成功后的本轮输入 + 答复）与宿主的 {@code dispatcher}/{@code
 *       listener}（工具结果、占位失败结果、失败输入回补）。骨架手里没有"追加一条消息"的口。
 *   <li><b>不发任何事件、不落任何库</b>：事件词汇与落库时机是宿主的（Brain 的 {@code llm.call}/{@code decision}/… 全部在 {@link
 *       TurnListener} 实现里）。
 *   <li><b>不做压缩 / 记忆 / 技能 / 权限 / 审批 / 沙箱 / 重试 / 降级</b>：这些是对上下文或工具面的<b>具体实现</b>， 各宿主差异最大的一部分，留在宿主。
 *   <li><b>不 catch {@link LlmException} 以外的异常</b>：编程契约违例（例如 {@link ContextComposer} 违反位置契约）直接向上抛，
 *       与改造前完全一致——循环不该把 bug 吞成"正常终止"。
 *   <li><b>不缓存请求、不解析工具结果</b>：请求永远从窗口现组（{@link LlmRoundTrip} 的契约），骨架不持有第二份请求真相；
 *       工具结果长什么样、算不算成功，全是宿主的语义。
 *   <li><b>不持有 {@code AgentConfig}</b>：旋钮以显式参数/策略对象传入（轮数/时间/配额由 {@link StopPolicy} 解释，
 *       单响应工具调用上限是本类的显式 int），没装配 {@code AgentConfig} 的程序也能用。
 * </ul>
 *
 * <p><b>★ 恰好一次终止回调（exactly-once）</b>：一次 {@link #runTurn} 调用必定且只触发 {@link TurnListener} 四个终止回调
 * （{@link TurnListener#onFinished} / {@link TurnListener#onToolCallLimit} / {@link
 * TurnListener#onStop} / {@link TurnListener#onLlmError}）之一；{@link #runTurn} 返回后不再有任何回调。宿主据此把"落库 /
 * 落事件"放进这四个回调里 ——"结果已经返回、历史却没保存"的窗口不存在。
 *
 * <p><b>一次回合的九步（顺序是契约；宿主实现按它推导事件/落库顺序）</b>：
 *
 * <ol>
 *   <li>问 {@link StopPolicy#check}：有信号 ⇒ {@link TurnListener#onStop} → 返回该原因；
 *   <li>本轮输入：<b>只有第 1 轮</b>带 {@code turnInput}，此后传空列表（窗口里已有它——重复带上会让模型看到两条同样的 user）；
 *   <li>{@link LlmRoundTrip#call}：组请求 + 发信 + 写回；只 catch {@link LlmException} ⇒ {@link
 *       TurnListener#onLlmError} → 返回 {@link StopReason#LLM_ERROR}（轮数不加一）；
 *   <li>往返耗时到账、轮数加一；
 *   <li>问 {@link TurnListener#onRoundTrip}：可返回信号（如配额超限）⇒ {@link TurnListener#onStop} → 返回该原因；
 *   <li>响应里没有工具调用 ⇒ {@link TurnListener#onFinished} → 返回 {@link StopReason#FINISHED}；
 *   <li>有工具调用且条数超过 {@link Builder#maxToolCallsPerResponse}（>0 时）⇒ {@link
 *       TurnListener#onToolCallLimit} → 返回 {@link StopReason#TOOL_CALL_LIMIT}（整批不执行）；
 *   <li>逐条分派：每条之前再问一次 {@link StopPolicy#checkToolCallBoundary}（有信号 ⇒ {@link TurnListener#onStop} →
 *       返回该原因，未派发的那条不计数）， 然后计数并调 {@link ToolCallDispatcher#dispatch}；
 *   <li>回到第 1 步。
 * </ol>
 *
 * <p><b>写回在往返里，不在本类里</b>：{@link LlmRoundTrip} 返回时窗口已含本轮输入与这次答复——所以 {@link StopReason#FINISHED} 之外的
 * 终止出口，历史里也留着"已经产生的答复"（这是既有管线的既定语义）。
 *
 * <p><b>线程安全</b>：不做同步、不承诺并发。{@link ContextWindow} 本身非线程安全，一次 {@link #runTurn} 期间窗口不得被别处改动；
 * 调用方必须串行（同一实例连续回合）。
 */
public final class AgentLoop {

  private final ContextWindow window;
  private final ContextComposer composer;
  private final LlmClient llm;
  private final ToolCallDispatcher dispatcher;
  private final StopPolicy stopPolicy;
  private final TurnListener listener;
  private final int maxToolCallsPerResponse;

  private AgentLoop(
      ContextWindow window,
      ContextComposer composer,
      LlmClient llm,
      ToolCallDispatcher dispatcher,
      StopPolicy stopPolicy,
      TurnListener listener,
      int maxToolCallsPerResponse) {
    this.window = window;
    this.composer = composer;
    this.llm = llm;
    this.dispatcher = dispatcher;
    this.stopPolicy = stopPolicy;
    this.listener = listener;
    this.maxToolCallsPerResponse = maxToolCallsPerResponse;
  }

  /** 建一个空的装配器；必需依赖在 {@link Builder#build()} 处校验（失败抛带参数名的 {@link NullPointerException}）。 */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * 跑一个用户回合：本轮输入 → 循环 → 终止。同步方法；一次调用只服务一个回合。
   *
   * <p><b>顺序是契约</b>：见类注"一次回合的九步"。此处只强调两个宿主最容易依赖的点——①{@code firstRound} 只对第 1 轮传 {@code
   * turnInput}（此后 {@link LlmRoundTrip} 每轮都会把答复写回窗口，历史自然变长，本轮输入不能再重复）；②每次成功往返后先加轮数， <b>再</b>把 {@code
   * state} 交给 {@link TurnListener#onRoundTrip}（于是宿主看到的 {@code rounds} 已含本次）。
   *
   * @param turnInput 本轮输入（相对窗口新增的部分；首轮写回窗口，之后各轮为空——传什么都不会在第 2 轮起被使用）
   * @param tools 本轮可用的工具（原样交给 composer，骨架不解释）
   * @return 终止原因 + 回合计数 + 最终文本（非 null）
   */
  public TurnResult runTurn(List<LlmMessage> turnInput, List<AgentTool> tools) {
    int rounds = 0;
    int toolCalls = 0;
    long startedNanos = System.nanoTime();
    boolean firstRound = true;

    while (true) {
      // 1. 往返开始前的终止判定（宿主决定：取消 / 轮数上限 / 时间预算……）
      LoopState state = state(rounds, toolCalls, startedNanos);
      Optional<StopSignal> policySignal = stopPolicy.check(state);
      if (policySignal.isPresent()) {
        return stop(policySignal.get(), state, rounds, toolCalls);
      }

      // 2. 本轮输入只在第 1 轮出现：此后窗口里已经有了它，重复带上会让模型看到两条同样的 user
      List<LlmMessage> roundInput = firstRound ? List.copyOf(turnInput) : List.of();
      firstRound = false;

      // 3. 一次往返 = 组请求（从窗口现取）+ 发信 + 写回；失败时窗口一个字节都没动，故本轮输入交回宿主决定去留
      long roundStartedNanos = System.nanoTime();
      LlmResponse response;
      try {
        response = LlmRoundTrip.call(llm, window, composer, roundInput, tools);
      } catch (LlmException e) {
        LoopState errorState = state(rounds, toolCalls, startedNanos);
        listener.onLlmError(e, roundInput, errorState);
        return new TurnResult(StopReason.LLM_ERROR, rounds, toolCalls, "");
      }

      // 4. 往返成功：单轮耗时到账、轮数加一（失败的那轮不加，见上面 catch）
      long latencyMs = Math.max(0L, (System.nanoTime() - roundStartedNanos) / 1_000_000L);
      rounds++;

      // 5. 成功往返后的宿主记账/判定（如 llm.call 先落、配额居中、会话事件在后）；可返回终止信号（如 QUOTA）
      state = state(rounds, toolCalls, startedNanos);
      Optional<StopSignal> roundSignal = listener.onRoundTrip(response, latencyMs, state);
      if (roundSignal.isPresent()) {
        return stop(roundSignal.get(), state, rounds, toolCalls);
      }

      // 6. 解析工具调用（按模型给出的原序；响应里没有 ⇒ 自然收尾）
      List<ContentPart.ToolCall> calls =
          response.assistantMessage().content().stream()
              .filter(ContentPart.ToolCall.class::isInstance)
              .map(ContentPart.ToolCall.class::cast)
              .toList();
      if (calls.isEmpty()) {
        String text = response.textPart().orElse("");
        listener.onFinished(text, state);
        return new TurnResult(StopReason.FINISHED, rounds, toolCalls, text);
      }

      // 7. 单响应工具调用硬顶：整批不执行（占位结果/事件/落库由宿主在回调里补）
      if (maxToolCallsPerResponse > 0 && calls.size() > maxToolCallsPerResponse) {
        listener.onToolCallLimit(calls, state);
        return new TurnResult(
            StopReason.TOOL_CALL_LIMIT, rounds, toolCalls, response.textPart().orElse(""));
      }

      // 8. 逐条分派：每条之前再问一次策略（工具批中间的取消/超时在此生效；未派发的那条不计数）。
      //    这里问的是 checkToolCallBoundary 而不是 check——缺省两者相同，但宿主可以只在批内查取消
      //    （批内跑完整预算判定会在批中途终止并留下悬空 tool_call，见 StopPolicy 接口注）
      for (ContentPart.ToolCall call : calls) {
        LoopState beforeDispatch = state(rounds, toolCalls, startedNanos);
        Optional<StopSignal> dispatchSignal = stopPolicy.checkToolCallBoundary(beforeDispatch);
        if (dispatchSignal.isPresent()) {
          return stop(dispatchSignal.get(), beforeDispatch, rounds, toolCalls);
        }
        toolCalls++;
        dispatcher.dispatch(call, state(rounds, toolCalls, startedNanos));
      }
      // 9. 回到第 1 步
    }
  }

  /** 终止收口：先回调宿主（落事件/落库），再返回结果——顺序是契约（结果返回前历史已处置）。 */
  private TurnResult stop(StopSignal signal, LoopState state, int rounds, int toolCalls) {
    listener.onStop(signal, state);
    return new TurnResult(signal.reason(), rounds, toolCalls, "");
  }

  /** 当前进度快照（elapsed 从回合起点算；只读读数，骨架不用它做判定）。 */
  private static LoopState state(int rounds, int toolCalls, long startedNanos) {
    long elapsedMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    return new LoopState(rounds, toolCalls, elapsedMillis);
  }

  /**
   * {@link AgentLoop} 的装配器：必需四件（窗口 / 编排器 / 模型客户端 / 工具分派器）+ 三个可选旋钮（停止策略 / 监听器 / 单响应工具调用上限）。
   *
   * <p><b>为什么用 Builder 而不是 7 参构造</b>：7 参构造里四个是"必须给"、三个是"通常用缺省"，按位置传参时"把 listener 传给 stopPolicy"
   * 这种错位编译期发现不了，运行时表现为"策略从不喊停"——最坏的一类 bug。Builder 让每件依赖显式命名，缺必需依赖在 {@code build()} 处带参数名响亮失败。
   *
   * <p><b>缺省值</b>：{@code stopPolicy} 缺省 = 永不主动停（只有模型自然收尾或单响应硬顶能结束回合）；{@code listener} 缺省 = 全
   * no-op（什么都不记、什么都不落——一次性脚本宿主够用）；{@code maxToolCallsPerResponse} 缺省 {@code 0} = 不限。
   */
  public static final class Builder {

    private ContextWindow window;
    private ContextComposer composer;
    private LlmClient llm;
    private ToolCallDispatcher dispatcher;
    private StopPolicy stopPolicy;
    private TurnListener listener;
    private int maxToolCallsPerResponse;

    private Builder() {}

    /**
     * 必需：本会话的上下文窗口（往返读写都经它；非线程安全，调用方串行）。
     *
     * <p><b>为什么这里按引用持有、且需要 {@code EI_EXPOSE_REP2} 抑制</b>：窗口是<b>调用方的</b>会话状态，骨架的职责是"在回合里读写<b>同一份</b>
     * 窗口"——LlmRoundTrip 的写回、宿主的工具结果、下一次往返的组请求都必须是同一串消息。防御性拷贝在这里不仅无意义，还会制造"两套历史"
     * 的第二份真相（窗口本就没有拷贝构造；即便手写一份，写回也会落到副本上，宿主再也看不见本回合的进展）。引用只在 Builder → AgentLoop
     * 的私有字段间传递，本类不提供任何把它暴露出去的取值口，因此这是<b>依赖注入的故意共享</b>，不是内部状态泄漏。
     */
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "窗口是调用方持有的会话状态，注入按引用共享是设计意图：骨架必须读写同一份窗口（请求从它派生、写回落在它上面），"
                + "防御性拷贝会制造'两套历史'的第二份真相；引用仅存于私有 final 字段、不向外暴露，属依赖注入而非状态泄漏。")
    public Builder window(ContextWindow window) {
      this.window = window;
      return this;
    }

    /** 必需：组请求的编排器（每次往返从窗口现组，骨架不缓存请求）。 */
    public Builder composer(ContextComposer composer) {
      this.composer = composer;
      return this;
    }

    /** 必需：模型客户端（同步、一次请求一次返回、只抛 {@link LlmException}）。 */
    public Builder llm(LlmClient llm) {
      this.llm = llm;
      return this;
    }

    /** 必需：工具调用分派器（判定/审批/沙箱/事件/结果写回都在宿主的实现里）。 */
    public Builder dispatcher(ToolCallDispatcher dispatcher) {
      this.dispatcher = dispatcher;
      return this;
    }

    /** 可选：何时停。缺省 = 永不主动停（见类注 {@link Builder} 的缺省值说明）。 */
    public Builder stopPolicy(StopPolicy stopPolicy) {
      this.stopPolicy = stopPolicy;
      return this;
    }

    /** 可选：每轮记账/事件/终止收尾。缺省 = 全 no-op。 */
    public Builder listener(TurnListener listener) {
      this.listener = listener;
      return this;
    }

    /**
     * 可选：单次响应允许的工具调用条数上限；{@code 0}（缺省）= 不限。超出 ⇒ 整批不执行，转 {@link TurnListener#onToolCallLimit} 出口。
     *
     * @throws IllegalArgumentException 负数（"不限"用 0 表达；负数只可能是装配 bug，不静默当 0）
     */
    public Builder maxToolCallsPerResponse(int max) {
      if (max < 0) {
        throw new IllegalArgumentException("maxToolCallsPerResponse 不能为负: " + max);
      }
      this.maxToolCallsPerResponse = max;
      return this;
    }

    /**
     * 装配。
     *
     * @throws NullPointerException 任一必需依赖（window / composer / llm / dispatcher）未给（消息含参数名）
     */
    public AgentLoop build() {
      return new AgentLoop(
          require(window, "window"),
          require(composer, "composer"),
          require(llm, "llm"),
          require(dispatcher, "dispatcher"),
          stopPolicy == null ? state -> Optional.empty() : stopPolicy,
          listener == null ? new TurnListener() {} : listener,
          maxToolCallsPerResponse);
    }

    /** 必需依赖校验：消息必须带参数名（"哪个没给"比"有东西没给"可查得多）。 */
    private static <T> T require(T value, String name) {
      return Objects.requireNonNull(value, name);
    }
  }
}
