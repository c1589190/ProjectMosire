package io.mosire.brain.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmQuota;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.store.ConversationStore;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.brain.context.ContextAssembler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 主循环（Brain 内部核心）：
 *
 * <pre>{@code
 * assembler → LlmClient → 解析工具调用 → Guard → ToolRegistry 执行 → 事件 → 循环
 * }</pre>
 *
 * <p>终止条件：模型无工具调用（FINISHED）或任一硬顶（计划 D10）。反幻觉护栏（伪造 [TOOL_RESULT]/finish_action/结果溢出暂存）为 GSimulator
 * 现成件，P0 下半程（M3）接入。
 *
 * <p><b>并发约定（R11 未尽项落定）</b>：{@link #history} 非线程安全——调用方必须保证任意时刻只跑一个回合： 每任务一个 runtime
 * 实例，或对外（A2A/AG-UI 等）按提交序串行化调度。选择"每任务一 runtime"= 会话隔离（各任务独立历史）； 选择"串行化"= 一个 Agent 实例的连续对话（A2A
 * 任务续接自然延续上下文）。{@link ConversationStore} 不是并发方案——它只保证"每次调用自身原子"， 上述契约不因持久化而放宽。
 *
 * <p><b>会话持久化（T16）</b>：带 {@code (store, conversationId)} 的构造把历史落到 {@link ConversationStore}——构造时
 * {@code load} 灌入内存工作集（重启续聊），每回合只把<b>新增的尾部</b>追加进去（prompt-cache 不变量：已落库的消息永不改写）。不带 store
 * 的构造委派到"不落库"实现，行为与持久化接入前逐字节一致。
 */
public final class AgentPipeline {

  private static final Logger LOG = LoggerFactory.getLogger(AgentPipeline.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final AgentConfig config;
  private final LlmClient llm;
  private final ToolRegistry registry;
  private final ToolExecutionGuard guard;
  private final ContextAssembler assembler;
  private final EventStore events;
  private final EventBus bus;
  private final AccessToken caller;
  private final AgentPermissionSet permissionSet;
  private final ConversationStore store;
  private final String conversationId;

  /** 会话历史（不含 system 头，进程内累积 + 每回合把新增尾部落库，供下一回合原样回灌）；构造时由 {@link #store} 灌入。 */
  private final List<LlmMessage> history = new ArrayList<>();

  /** 取消请求标志（volatile 保证跨线程可见；只在 run() 入口重置——取消只对"正在跑的回合"生效）。 */
  private volatile boolean cancelled;

  /**
   * 不落库构造（M2 起的既有签名，行为逐字节不变）：委派到 {@link DiscardingConversationStore}。
   *
   * <p>生产侧要持久化请用 {@link #AgentPipeline(AgentConfig, LlmClient, ToolRegistry, ToolExecutionGuard,
   * ContextAssembler, EventStore, EventBus, AccessToken, AgentPermissionSet, ConversationStore,
   * String)}。
   */
  public AgentPipeline(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AccessToken caller,
      AgentPermissionSet permissionSet) {
    this(
        config,
        llm,
        registry,
        guard,
        assembler,
        events,
        bus,
        caller,
        permissionSet,
        DiscardingConversationStore.INSTANCE,
        conversationIdOf(config));
  }

  /**
   * 全参装配 + 会话持久化：历史落 {@code conversationId} 名下的 {@link ConversationStore}。
   *
   * <p><b>hydrate</b>：构造即 {@code store.load(conversationId)} 灌入内存工作集——同一库文件上新建实例（进程重启）能接着
   * 上一段对话聊，靠的就是这一步。会话应只被一个 pipeline 实例驱动（并发约定见类 Javadoc）。
   *
   * @param store 会话存储（非 null；用 {@link #AgentPipeline(AgentConfig, LlmClient, ToolRegistry,
   *     ToolExecutionGuard, ContextAssembler, EventStore, EventBus, AccessToken,
   *     AgentPermissionSet)} 表示不落库）
   * @param conversationId 会话标识（非 null、显式给定；生产接线由 {@code AgentRuntime} 传 {@code config.id()}）
   */
  public AgentPipeline(
      AgentConfig config,
      LlmClient llm,
      ToolRegistry registry,
      ToolExecutionGuard guard,
      ContextAssembler assembler,
      EventStore events,
      EventBus bus,
      AccessToken caller,
      AgentPermissionSet permissionSet,
      ConversationStore store,
      String conversationId) {
    this.config = Objects.requireNonNull(config, "config");
    this.llm = Objects.requireNonNull(llm, "llm");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.guard = Objects.requireNonNull(guard, "guard");
    this.assembler = Objects.requireNonNull(assembler, "assembler");
    this.events = Objects.requireNonNull(events, "events");
    this.bus = Objects.requireNonNull(bus, "bus");
    this.caller = Objects.requireNonNull(caller, "caller");
    this.permissionSet = Objects.requireNonNull(permissionSet, "permissionSet");
    this.store = Objects.requireNonNull(store, "store");
    this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
    // hydrate：既有会话（进程重启前落的库）先灌回内存工作集，否则"重启续聊"名存实亡
    history.addAll(this.store.load(this.conversationId));
  }

  /** 不落库构造的会话 id 取法：Store 是空实现，id 只用于占位，取配置里的 Agent id（与生产接线同源）。 */
  private static String conversationIdOf(AgentConfig config) {
    return Objects.requireNonNull(config, "config").id();
  }

  /**
   * 请求取消当前运行中的回合（幂等、线程安全）。
   *
   * <p>不做硬中断——进行中的 LLM 调用与工具执行照常完成，管线在下一个检查点（循环顶/工具执行前） 就地以 {@link StopReason#CANCELLED}
   * 终止，终止前保存历史供后续对话回灌。无运行中的回合时调用是空操作 （标志在 {@link #run(String)} 入口重置）。
   */
  public void cancel() {
    cancelled = true;
  }

  /**
   * 跑一回合：用户输入 →（LLM 循环 + 工具调用）→ 终止。
   *
   * <p>约定：方法是同步的（网络/子进程调用在工具内自行发生）；回合间共享 {@code history} （一个 Agent 实例的连续对话）。
   */
  public TurnResult run(String userMessage) {
    Objects.requireNonNull(userMessage, "userMessage");
    // 上一次回合的取消请求到此为止：cancel() 只对当时正在跑的回合负责
    cancelled = false;

    LlmQuota quota = config.quotaMaxTokens() > 0 ? new LlmQuota(config.quotaMaxTokens()) : null;
    Instant deadline = Instant.now().plus(config.timeBudget());
    List<AgentTool> tools = registry.list();
    // 回合起点：历史长度在组请求前取定——saveHistory 用同一份 messages 列表按下标推出本回合新增的尾部（不靠猜、
    // 不比对内容）；快照同时让"本回合"与历史后续变化解耦
    List<LlmMessage> replayedHistory = List.copyOf(history);
    int replayedSize = replayedHistory.size();
    // 历史回灌：assembler 契约保证 messages[0] 是 system、history 紧随其后、本轮 user 收尾
    List<LlmMessage> messages =
        new ArrayList<>(
            assembler.buildRequest(config, userMessage, replayedHistory, tools).messages());

    int turns = 0;
    int totalToolCalls = 0;
    String finalText = "";

    while (true) {
      if (cancelled) {
        emitDecision("CANCELLED", Map.of());
        saveHistory(messages, replayedSize);
        return new TurnResult(StopReason.CANCELLED, turns, totalToolCalls, finalText);
      }
      if (turns >= config.maxTurns()) {
        emitDecision("TURN_LIMIT", Map.of("maxTurns", config.maxTurns()));
        saveHistory(messages, replayedSize);
        return new TurnResult(StopReason.TURN_LIMIT, turns, totalToolCalls, finalText);
      }
      if (Instant.now().isAfter(deadline)) {
        emitDecision("TIME_BUDGET", Map.of("timeBudget", config.timeBudget().toString()));
        saveHistory(messages, replayedSize);
        return new TurnResult(StopReason.TIME_BUDGET, turns, totalToolCalls, finalText);
      }

      LlmResponse response;
      long startedNanos = System.nanoTime();
      try {
        response = llm.chat(new LlmRequest(List.copyOf(messages), toolsDefs(tools)));
      } catch (io.mosire.agentlib.llm.LlmException e) {
        // LlmClient 契约只抛 LlmException；此刻 assistant 消息尚未入列，历史以工具结果干净收尾
        emitDecision("LLM_ERROR", Map.of("reason", String.valueOf(e.getMessage())));
        saveHistory(messages, replayedSize);
        return new TurnResult(StopReason.LLM_ERROR, turns, totalToolCalls, finalText);
      }
      long latencyMs = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
      turns++;
      // llm.call 记账（D18）在配额判定之前落库：超限终止也不能丢"这次调用确实发生了"的事实
      emit(
          EventTypes.LLM_CALL,
          Map.of(
              "inputTokens",
              response.inputTokens(),
              "outputTokens",
              response.outputTokens(),
              "cacheReadTokens",
              response.cacheReadTokens(),
              "cacheWriteTokens",
              response.cacheWriteTokens(),
              "model",
              response.model(),
              "latencyMs",
              latencyMs,
              "turns",
              turns));
      if (quota != null) {
        try {
          quota.record(response.inputTokens(), response.outputTokens());
        } catch (io.mosire.agentlib.llm.QuotaExceededException e) {
          emitDecision("QUOTA", Map.of("reason", e.getMessage()));
          saveHistory(messages, replayedSize);
          return new TurnResult(StopReason.QUOTA, turns, totalToolCalls, finalText);
        }
      }

      // 追加 assistant 消息 + 回合事件
      messages.add(response.assistantMessage());
      emit(
          EventTypes.CONVERSATION_TURN,
          Map.of(
              "turns",
              turns,
              "input",
              userMessage,
              "output",
              response.textPart().orElse(""),
              "model",
              response.model()));

      List<ContentPart.ToolCall> toolCalls =
          response.assistantMessage().content().stream()
              .filter(ContentPart.ToolCall.class::isInstance)
              .map(ContentPart.ToolCall.class::cast)
              .toList();

      if (toolCalls.isEmpty()) {
        saveHistory(messages, replayedSize);
        return new TurnResult(
            StopReason.FINISHED, turns, totalToolCalls, response.textPart().orElse(""));
      }

      if (toolCalls.size() > config.maxToolCallsPerTurn()) {
        emitDecision(
            "TOOL_CALL_LIMIT",
            Map.of("toolCalls", toolCalls.size(), "max", config.maxToolCallsPerTurn()));
        // 悬空 tool_call 修复：本回合一个都没执行，回灌前为每个调用补占位失败结果——
        // 否则历史里存在无应答的 tool_call，下一回合部分供应商会拒收请求
        for (ContentPart.ToolCall call : toolCalls) {
          messages.add(
              LlmMessage.tool(
                  new ContentPart.ToolResult(call.id(), call.name(), null, "回合因单次响应工具调用数超限中止")));
        }
        saveHistory(messages, replayedSize);
        return new TurnResult(
            StopReason.TOOL_CALL_LIMIT, turns, totalToolCalls, response.textPart().orElse(""));
      }

      for (ContentPart.ToolCall call : toolCalls) {
        if (cancelled) {
          emitDecision("CANCELLED", Map.of());
          saveHistory(messages, replayedSize);
          return new TurnResult(StopReason.CANCELLED, turns, totalToolCalls, finalText);
        }
        totalToolCalls++;
        executeToolCall(messages, call);
      }
    }
  }

  /**
   * 把本回合<b>新增的尾部</b>追加进历史：内存 {@link #history} 与 {@link #store} 都只追加，绝不重写已有前缀。
   *
   * <p>为什么不是"清空重写全量"：机制上的 clear+addAll 直译到持久层就变成每回合 DELETE + 全量 INSERT——行标识与插入 时序每回合全变，是
   * prompt-cache 前缀稳定的隐性敌人。这里改成内容与机制一致的 append-only。
   *
   * <p><b>增量怎么算</b>：{@code messages} 的结构恒为 {@code [system] + 回合起点历史 + 本回合新增...}（assembler 契约：第一条必为
   * system、回合起点历史<b>原样</b>紧随其后、本轮 user 收尾），故 {@code messages[replayedSize + 1]} 起就是本回合新增的部分——
   * 用同一份列表按下标推出，不比对内容（{@code replayedSize} 是组请求前取定的历史长度）。推导式本身是精确算术，不靠猜。
   *
   * <p><b>但下标推导有条件</b>：本轮 user 必须<b>恰好</b>落在 {@code replayedSize + 1}。assembler 少回灌历史（该下标落到别处）或在头部
   * 多插一条（下标整体前移）都会让切片错位，表现从"静默丢弃本回合尾部"到"把已落库的历史当新增重复追加"（append-only 库里留下重复前缀）不等；
   * 且错位多数<b>不</b>落在越界上——不抛任何异常，只是写错（越界时 {@code ArrayList.subList} 抛的也是 {@link
   * IllegalArgumentException}，不是 {@link IndexOutOfBoundsException}）。故切片前先按契约校验（见下），把静默错写一律变成响亮失败。
   *
   * <p><b>前置守卫不误伤合法压缩</b>：合法压缩走 {@link ConversationStore#compact} 落库后重灌，{@code history} 变短是合法的——
   * {@code replayedSize} 取的是组请求前的实际长度，回灌长度随之一同变短，守卫不响。
   *
   * <p><b>守卫的辨识范围（别把它想得比实际宽）</b>：它<b>只</b>校验两点——{@code size ≥ replayedSize + 2}，且 {@code
   * messages[replayedSize + 1]} 这条的 role 是 {@code user}。落在其外的情形<b>不</b>被辨识：等长的内容改写/重排（与"不比对内容"的
   * 设计取舍相抵）、在本轮 user <b>之后</b>再插消息（回合内本就会追加 assistant/tool，无法与之区分）。它拦的是"assembler
   * <b>擅自</b>改变回灌长度或本轮 user 位置"这一类<b>编程契约违反</b>，不是运行期数据状态，故抛 {@link IllegalStateException}
   * 而非参数类异常。守卫在任何内存/落库写入<b>之前</b>求值——不满足时不留下半截写入。
   *
   * <p>七条终止路径（FINISHED/TOOL_CALL_LIMIT/TURN_LIMIT/TIME_BUDGET/QUOTA/LLM_ERROR/CANCELLED）都必须先经过这里再
   * return——否则下一回合回灌的历史残缺，Agent 就会失忆。
   *
   * <p>落库失败（磁盘/DB 异常）不吞：内存历史已追加、异常向上抛，回合以失败告终而不是静默丢持久化。尾部是逐条 append（Store 只保证 单次调用原子，见 {@link
   * ConversationStore}）——中途失败会留下"已落库的前缀"，重启后正是从那个前缀续起。
   */
  private void saveHistory(List<LlmMessage> messages, int replayedSize) {
    // assembler 契约校验：本轮 user 必须恰好是 messages[replayedSize + 1]。最小合法长度 = [system] + 回灌历史
    // （replayedSize 条）+ 本轮 user = replayedSize + 2（此刻本回合尚未产生任何新消息）；长度不足或该位不是
    // user → 下标推导失去意义，响亮失败
    if (messages.size() < replayedSize + 2
        || !LlmMessage.ROLE_USER.equals(messages.get(replayedSize + 1).role())) {
      throw new IllegalStateException(
          "assembler 违反契约：历史未按其长度原样回灌、本轮 user 未紧随其后（replayedSize="
              + replayedSize
              + ", size="
              + messages.size()
              + "）");
    }
    List<LlmMessage> appended = messages.subList(replayedSize + 1, messages.size());
    history.addAll(appended);
    for (LlmMessage message : appended) {
      store.append(conversationId, message);
    }
  }

  /**
   * "不落库"的默认实现：9 参构造（{@code AgentRuntime} 与既有测试）委派到它，行为与持久化接入前逐字节一致。
   *
   * <p>{@code load} 返回空 → 构造时不 hydrate；{@code append}/{@code compact} 皆空操作。放在本类内部而非 AgentLib 公开
   * API：它只是"没有 Store"这一情形的表达，没有独立复用价值。
   */
  private static final class DiscardingConversationStore implements ConversationStore {

    static final DiscardingConversationStore INSTANCE = new DiscardingConversationStore();

    @Override
    public void append(String conversationId, LlmMessage message) {
      // 有意为空：不落库
    }

    @Override
    public List<LlmMessage> load(String conversationId) {
      return List.of();
    }

    @Override
    public void compact(String conversationId, String summary) {
      // 有意为空：不落库
    }
  }

  private void executeToolCall(List<LlmMessage> messages, ContentPart.ToolCall call) {
    emit(
        EventTypes.TOOL_CALL,
        Map.of("tool", call.name(), "callId", call.id(), "args", call.arguments()));

    ToolContext context = new ToolContext(caller, permissionSet, Map.of(), call.arguments());
    ToolResult result;
    try {
      result = guard.execute(registry, call.name(), context);
    } catch (RuntimeException e) {
      LOG.error("工具执行抛出未捕获异常 tool={}", call.name(), e);
      result = ToolResult.error("TOOL_CRASH", "工具内部异常: " + e.getMessage());
    }

    if (ToolExecutionGuard.DENIED.equals(result.code())) {
      emit(EventTypes.PERMISSION_DENIED, Map.of("tool", call.name(), "reason", result.message()));
    }
    emit(
        EventTypes.TOOL_RESULT,
        Map.of(
            "tool", call.name(),
            "callId", call.id(),
            "ok", result.success(),
            "message", result.message()));

    // ToolResult 契约：成功时 message 为给 LLM 的文本，失败时 message 为错误信息（二者都非 null）——
    // ContentPart.ToolResult 要求 content 与 error 恰好一个非空：成功走 content（error 置空）、失败走 error（content 置空）
    String content = result.success() ? result.message() : null;
    String error = result.success() ? null : result.message();
    messages.add(
        LlmMessage.tool(new ContentPart.ToolResult(call.id(), call.name(), content, error)));
  }

  /**
   * 最近一次回合的会话历史快照（<b>不含 system 头</b>；M2 供 A2A/AG-UI 读会话用）。
   *
   * <p>与 {@link #run(String)} 内部消息序列的关系：{@code [system] + lastHistory()} 即完整请求消息。
   *
   * <p><b>契约冻结</b>：接 {@link ConversationStore} 之后本方法读的仍是内存工作集（不是库），形状与语义与持久化前一致—— 不含 system
   * 头、返回不可变快照（后续回合不回改已发出的快照）。
   *
   * <p><b>压缩之后的形状（按实例状态分两种，勿混为一谈）</b>：{@code "摘要 + 压缩点之后的消息"} 只对<b>新实例 hydrate 之后</b>为真（构造时 {@code
   * store.load(...)} 灌入，故进程重启即反映最近一次压缩）。<b>活实例</b>的工作集只在构造时灌一次，外部调用 {@link
   * ConversationStore#compact} 之后<b>不会</b>被回灌——本方法仍返回压缩前的全量、不含摘要（并且下一回合会把压缩点之前的内容重新送进
   * 请求，反而抵消压缩）。把已持久化的压缩结果重新注入活实例的工作集归 T17，本类不做。
   */
  public List<LlmMessage> lastHistory() {
    return List.copyOf(history);
  }

  private static List<io.mosire.agentlib.llm.ToolDef> toolsDefs(List<AgentTool> tools) {
    return tools.stream()
        .map(t -> new io.mosire.agentlib.llm.ToolDef(t.name(), t.description(), t.jsonSchema()))
        .toList();
  }

  private void emit(String type, Object payload) {
    EventWrite write = EventWrite.of(type, config.id(), toJson(payload));
    Event event = events.append(write);
    bus.publish(event);
  }

  private void emitDecision(String decision, Map<String, Object> extra) {
    emit(
        EventTypes.DECISION,
        extra.isEmpty()
            ? Map.of("decision", decision)
            : Map.of("decision", decision, "detail", extra));
  }

  private static String toJson(Object payload) {
    try {
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      LOG.error("事件 payload 序列化失败 type={}", payload, e);
      return "{}";
    }
  }
}
