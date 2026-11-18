package io.mosire.agentlib.runtime;

import io.mosire.agentlib.agent.AgentConfig;
import io.mosire.agentlib.context.ContextWindow;
import io.mosire.agentlib.decision.DecisionTurnScope;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmQuota;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.llm.QuotaExceededException;
import io.mosire.agentlib.llm.ToolDef;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** AgentLib 的内存会话入口。调用方在一个实例上串行执行 {@link #chat(String)}。 */
public final class SimpleAgent {

  private static final ToolCallAuthorizer AUTHORIZER = ToolCallAuthorizer.standard();

  private final AgentConfig config;
  private final LlmClient llm;
  private final ToolRegistry registry;
  private final AgentPermissionSet permissions;
  private final AgentIdentity identity;
  private final ContextWindow window;
  private final LlmQuota quota;
  private boolean quotaExhausted;

  private SimpleAgent(
      AgentConfig config, LlmClient llm, ToolRegistry registry, AgentPermissionSet permissions) {
    this.config = Objects.requireNonNull(config, "config");
    this.llm = Objects.requireNonNull(llm, "llm");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.permissions =
        narrowPermissions(config, Objects.requireNonNull(permissions, "permissions"));
    this.identity = new AgentIdentity(config.id(), CommandMode.LIMITED, "", 0);
    this.window = new ContextWindow(config.id());
    if (config.quotaMaxTokens() < 0) {
      throw new IllegalArgumentException("quotaMaxTokens 不能为负");
    }
    this.quota = config.quotaMaxTokens() > 0 ? new LlmQuota(config.quotaMaxTokens()) : null;
  }

  /** 建立一个没有工具授权的内存会话。模型客户端由宿主提供。 */
  public static SimpleAgent create(AgentConfig config, LlmClient llm) {
    return create(
        config,
        llm,
        new ToolRegistry(),
        AgentPermissionSet.builder(AccessToken.GUEST).readOnly(true).build());
  }

  /**
   * 借用宿主的模型客户端和工具注册表。配置中的白名单与宿主授权取交集，拒绝名单取并集，其他权限维度沿用宿主授权。
   *
   * <p>每个用户回合开始时读取工具注册表，模型目录与本回合可执行的工具来自同一份快照。审批工具在本入口按拒绝处理。
   */
  public static SimpleAgent create(
      AgentConfig config, LlmClient llm, ToolRegistry registry, AgentPermissionSet permissions) {
    return new SimpleAgent(config, llm, registry, permissions);
  }

  /**
   * 处理一次用户输入并延续本实例的对话。墙钟预算在模型往返前检查，工具执行由既有硬超时约束。token 额度按实例累计。
   *
   * @param userMessage 本回合的用户消息
   * @return 回合停因、成功的模型往返次数和已派发工具调用次数
   */
  public TurnResult chat(String userMessage) {
    List<LlmMessage> input = List.of(LlmMessage.user(userMessage));
    List<AgentTool> visibleTools =
        registry.list().stream()
            .filter(
                tool ->
                    PermissionChecker.allows(permissions, tool.name(), tool.spec(), tool.roles()))
            .toList();
    ToolRegistry turnRegistry = new ToolRegistry();
    turnRegistry.registerAll(visibleTools);
    return AgentLoop.builder()
        .window(window)
        .composer(this::compose)
        .llm(llm)
        .dispatcher((call, state) -> appendResult(call, dispatch(turnRegistry, call)))
        .stopPolicy(
            new StopPolicy() {
              @Override
              public Optional<StopSignal> check(LoopState state) {
                return checkBudget(state);
              }

              @Override
              public Optional<StopSignal> checkToolCallBoundary(LoopState state) {
                // 预算在模型往返边界检查，已派发的整批调用必须各有结果。
                return Optional.empty();
              }
            })
        .listener(new SessionListener())
        .maxToolCallsPerResponse(config.maxToolCallsPerTurn())
        .build()
        .runTurn(input, visibleTools);
  }

  private LlmRequest compose(
      List<LlmMessage> history, List<LlmMessage> input, List<AgentTool> tools) {
    List<LlmMessage> messages = new ArrayList<>(1 + history.size() + input.size());
    messages.add(LlmMessage.system(config.systemPrompt()));
    messages.addAll(history);
    messages.addAll(input);
    return new LlmRequest(
        messages,
        tools.stream()
            .map(tool -> ToolDef.of(tool.name(), tool.description(), tool.jsonSchema()))
            .toList());
  }

  private Optional<StopSignal> checkBudget(LoopState state) {
    if (quota != null && (quotaExhausted || quota.usedTokens() >= config.quotaMaxTokens())) {
      return Optional.of(new StopSignal(StopReason.QUOTA, "会话 token 额度已用完"));
    }
    if (state.rounds() >= config.maxTurns()) {
      return Optional.of(new StopSignal(StopReason.TURN_LIMIT, ""));
    }
    if (Duration.ofMillis(state.elapsedMillis()).compareTo(config.timeBudget()) >= 0) {
      return Optional.of(new StopSignal(StopReason.TIME_BUDGET, ""));
    }
    return Optional.empty();
  }

  private ToolResult dispatch(ToolRegistry turnRegistry, ContentPart.ToolCall call) {
    ToolContext context =
        new ToolContext(
            permissions.grantedToken(), permissions, Map.of(), call.arguments(), identity);
    // 嵌入到已有回合线程时隔离审批队列，避免把本会话的请求登记进外层包。
    PendingDecisionQueue outerQueue = DecisionTurnScope.current().orElse(null);
    if (outerQueue != null) {
      DecisionTurnScope.clear(outerQueue);
    }
    try {
      return Objects.requireNonNull(
          AUTHORIZER.execute(turnRegistry, call.name(), context), "工具返回结果");
    } catch (RuntimeException failure) {
      return ToolResult.error("TOOL_CRASH", "工具执行异常");
    } finally {
      if (outerQueue != null) {
        DecisionTurnScope.bind(outerQueue);
      }
    }
  }

  private void appendResult(ContentPart.ToolCall call, ToolResult result) {
    String content = result.success() ? result.message() : null;
    String error = result.success() ? null : result.message();
    window.append(
        LlmMessage.tool(new ContentPart.ToolResult(call.id(), call.name(), content, error)));
  }

  private void appendSkipped(List<ContentPart.ToolCall> calls, String code, String detail) {
    ToolResult result = ToolResult.error(code, detail);
    calls.forEach(call -> appendResult(call, result));
  }

  private static AgentPermissionSet narrowPermissions(
      AgentConfig config, AgentPermissionSet granted) {
    Set<String> allowed = granted.allowedTools();
    Set<String> configured = config.allowedTools();
    if (!configured.contains(AgentPermissionSet.ALL_TOOLS)) {
      if (allowed.contains(AgentPermissionSet.ALL_TOOLS)) {
        allowed = configured;
      } else {
        allowed = new HashSet<>(allowed);
        allowed.retainAll(configured);
      }
    }
    Set<String> denied = new HashSet<>(granted.deniedTools());
    denied.addAll(config.deniedTools());
    return new AgentPermissionSet(
        granted.grantedToken(),
        allowed,
        denied,
        granted.destructiveAllowed(),
        granted.sensitiveAllowed(),
        granted.readOnly(),
        granted.resourceScopes(),
        granted.roles());
  }

  private final class SessionListener implements TurnListener {
    @Override
    public Optional<StopSignal> onRoundTrip(LlmResponse response, long latencyMs, LoopState state) {
      if (quota != null) {
        try {
          quota.record(response.inputTokens(), response.outputTokens());
        } catch (QuotaExceededException | ArithmeticException exhausted) {
          quotaExhausted = true;
          List<ContentPart.ToolCall> calls =
              response.assistantMessage().content().stream()
                  .filter(ContentPart.ToolCall.class::isInstance)
                  .map(ContentPart.ToolCall.class::cast)
                  .toList();
          appendSkipped(calls, "QUOTA", "会话 token 额度超限，工具未执行");
          return Optional.of(new StopSignal(StopReason.QUOTA, "会话 token 额度超限"));
        }
      }
      return Optional.empty();
    }

    @Override
    public void onToolCallLimit(List<ContentPart.ToolCall> calls, LoopState state) {
      appendSkipped(calls, "TOOL_CALL_LIMIT", "本次响应工具调用数超限，整批未执行");
    }
  }
}
