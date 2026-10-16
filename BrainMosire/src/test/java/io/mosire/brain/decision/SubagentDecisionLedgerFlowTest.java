package io.mosire.brain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionItemDigest;
import io.mosire.agentlib.decision.DecisionReleaseMarker;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.decision.SubagentDecisionLedger;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * P3d 的四个新件（<b>本单自己的验证面，不是 P3c 的验收面</b>）：父侧本轮队列簿 + 两侧核对 + 一次性放行 + 标记剥离。
 *
 * <p><b>它钉住的判据（逐条对应设计 §5sexies③⑤⑥）</b>：
 *
 * <ol>
 *   <li><b>只有子体能 defer</b>：主 Agent（深度 0）与外部面不记账 ⇒ 返回假，调用方走既有审批闸；
 *   <li><b>父侧那份带明文</b>：{@code pendingEntries} 给的是父侧自己收到的 args（裁决者照旧看全文）；
 *   <li><b>两侧核对</b>：摘要/工具名/条数任一处对不上 ⇒ 具名拒 {@code ITEMS_MISMATCH} + <b>零批准</b>（一次审批请求都不投）；
 *   <li><b>一次性放行</b>：批了的条目重调 ⇒ 放行一次并记"已用"；重复使用 ⇒ 具名拒；没批的条目 ⇒ 具名拒；标记必须被剥掉；
 *   <li><b>三条清空路径</b>：结算清（{@link SubagentDecisionLedger#settled}）/ 回收清（{@link
 *       SubagentDecisionLedger#recycle}）/ 到点作废（短 TTL 的簿子）。
 * </ol>
 */
class SubagentDecisionLedgerFlowTest {

  private static final String CHILD = "prober-1";
  private static final String TOOL = "sensitive_probe";
  private static final String PACKAGE = "dp-1";

  private final EventBus bus = new EventBus();
  private final PendingApprovals pending = new PendingApprovals();

  @AfterEach
  void tearDown() {
    bus.close();
  }

  // ---------- ① 谁会被记账 ----------

  @Test
  void onlySubagentsAreBookedAtTheParent() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    ToolGate.Ask ask = new ToolGate.Ask("probe:ask", "探针", AskKind.SENSITIVE);

    assertThat(
            ledger.defer(
                probe(),
                context(
                    AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1),
                    Map.of("text", "一")),
                ask))
        .as("链上身份 depth>0 = 本进程派出去的子体 ⇒ 记账")
        .isTrue();
    assertThat(
            ledger.defer(
                probe(), context(AgentIdentity.main(CommandMode.FULL), Map.of("text", "主")), ask))
        .as("主 Agent（深度 0）不记账：它有自己的进程内队列那条路")
        .isFalse();
    assertThat(ledger.defer(probe(), context(AgentIdentity.external(), Map.of("text", "外部")), ask))
        .as("外部 MCP 面（深度 0）不记账")
        .isFalse();
    assertThat(ledger.trackedInstances()).containsExactly(CHILD);
  }

  @Test
  void theParentsOwnPlaintextIsWhatTheEntryCarries() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    ledger.defer(
        probe(),
        context(AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1), Map.of("text", "一")),
        new ToolGate.Ask("probe:ask", "探针", AskKind.MODE_LIMITED));

    List<PendingDecisionQueue.PendingEntry> entries = ledger.pendingEntries(CHILD);
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).toolName()).isEqualTo(TOOL);
    assertThat(entries.get(0).args()).as("★ 父侧自己收到的明文（裁决者照旧看全文）").isEqualTo(Map.of("text", "一"));
    assertThat(entries.get(0).classKey()).isEqualTo("probe:ask");
    assertThat(entries.get(0).askKind()).isEqualTo(AskKind.MODE_LIMITED);
    assertThat(entries.get(0).packageItem().argsDigest())
        .isEqualTo(DecisionItemDigest.argsDigest(Map.of("text", "一")));
  }

  // ---------- ② 一次性放行 ----------

  @Test
  void anApprovedItemIsReleasedExactlyOnce() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    ledger.defer(
        probe(),
        context(AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1), Map.of("text", "一")),
        new ToolGate.Ask("probe:ask", "探针", AskKind.SENSITIVE));
    ledger.settled(CHILD, PACKAGE, java.util.Set.of(0));

    ToolContext marked =
        context(
            AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1),
            DecisionReleaseMarker.attach(Map.of("text", "一"), PACKAGE, 0));

    Optional<SubagentDecisionLedger.Release> first = ledger.release(marked);
    assertThat(first).isPresent();
    assertThat(first.get().allowed()).as("批过且未用 ⇒ 放行").isTrue();
    assertThat(first.get().arguments())
        .as("★ 放行时给的是剥掉标记后的参数（标记不是工具的参数）")
        .isEqualTo(Map.of("text", "一"));

    ledger.consumeRelease(first.get());
    Optional<SubagentDecisionLedger.Release> again = ledger.release(marked);
    assertThat(again).isPresent();
    assertThat(again.get().allowed()).as("★ 一次性：同一条放行不能被用第二次").isFalse();
    assertThat(again.get().code()).isEqualTo(ToolCallAuthorizer.DECISION_RELEASE_USED);
  }

  @Test
  void anItemThatWasNotApprovedHasNoReleaseAtAll() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    ledger.defer(
        probe(),
        context(AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1), Map.of("text", "一")),
        new ToolGate.Ask("probe:ask", "探针", AskKind.SENSITIVE));
    ledger.settled(CHILD, PACKAGE, java.util.Set.of()); // 零批准

    Optional<SubagentDecisionLedger.Release> denied =
        ledger.release(
            context(
                AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1),
                DecisionReleaseMarker.attach(Map.of("text", "一"), PACKAGE, 0)));
    assertThat(denied).isPresent();
    assertThat(denied.get().allowed()).isFalse();
    assertThat(denied.get().code()).isEqualTo(ToolCallAuthorizer.DECISION_RELEASE_UNKNOWN);
  }

  @Test
  void aMarkWithoutAGrantIsRefusedAndTheMarkerItselfIsNotATriggerForOtherIdentities() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    assertThat(
            ledger.release(
                context(
                    AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1),
                    DecisionReleaseMarker.attach(Map.of("text", "一"), PACKAGE, 0))))
        .as("没有放行条 ⇒ 具名拒（伪造标记不成立）")
        .isPresent()
        .get()
        .extracting(SubagentDecisionLedger.Release::allowed)
        .isEqualTo(false);
    assertThat(
            ledger.release(
                context(
                    AgentIdentity.main(CommandMode.FULL),
                    DecisionReleaseMarker.attach(Map.of("text", "一"), PACKAGE, 0))))
        .as("主 Agent 的调用根本不被这条缝解释（标记对它是普通参数）")
        .isEmpty();
  }

  // ---------- ③ 三条清空路径 ----------

  @Test
  void settlingClearsTheTurnQueueAndRecyclingClearsTheGrantsToo() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    ledger.defer(
        probe(),
        context(AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1), Map.of("text", "一")),
        new ToolGate.Ask("probe:ask", "探针", AskKind.SENSITIVE));
    ledger.settled(CHILD, PACKAGE, java.util.Set.of(0));

    assertThat(ledger.pendingEntries(CHILD)).as("① 收尾结算完成即清本轮队列").isEmpty();
    assertThat(ledger.grantCount()).isEqualTo(1);

    ledger.recycle(CHILD);
    assertThat(ledger.grantCount()).as("② 子体进程结束/被回收 ⇒ 放行条一起清").isZero();
    assertThat(
            ledger.release(
                context(
                    AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1),
                    DecisionReleaseMarker.attach(Map.of("text", "一"), PACKAGE, 0))))
        .isPresent()
        .get()
        .extracting(SubagentDecisionLedger.Release::code)
        .isEqualTo(ToolCallAuthorizer.DECISION_RELEASE_UNKNOWN);
  }

  @Test
  void anExpiredTurnIsDiscardedLazily() throws Exception {
    ParentSubagentDecisionLedger ledger = canSubmitLedger(50L);
    ledger.defer(
        probe(),
        context(AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1), Map.of("text", "一")),
        new ToolGate.Ask("probe:ask", "探针", AskKind.SENSITIVE));
    assertThat(ledger.pendingEntries(CHILD)).hasSize(1);

    Thread.sleep(80L);

    assertThat(ledger.pendingEntries(CHILD)).as("③ 到点作废（惰性判）").isEmpty();
    assertThat(ledger.trackedInstances()).doesNotContain(CHILD);
  }

  @Test
  void aSubagentThatCannotSubmitIsNeverBookedSoTheCallFallsBackToTheApprovalGate() {
    // ① 权限集查不到这台子体（已回收 / 不认识）
    ParentSubagentDecisionLedger unknown =
        new ParentSubagentDecisionLedger(new ToolRegistry(), instanceId -> Optional.empty());
    // ② 目录里没有提交口（父侧没注册它）
    ParentSubagentDecisionLedger noTool =
        new ParentSubagentDecisionLedger(
            new ToolRegistry(), instanceId -> Optional.of(AgentPermissionSet.system()));
    // ③ 权限集不放行提交口（模板 allowedTools 少了它）
    ParentSubagentDecisionLedger notAllowed =
        new ParentSubagentDecisionLedger(
            toolsWithSubmitTool(),
            instanceId ->
                Optional.of(
                    new AgentPermissionSet(
                        AccessToken.GUEST,
                        java.util.Set.of("echo"),
                        java.util.Set.of(),
                        false,
                        false,
                        false)));

    for (ParentSubagentDecisionLedger ledger : List.of(unknown, noTool, notAllowed)) {
      assertThat(
              ledger.defer(
                  probe(),
                  context(
                      AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1),
                      Map.of("text", "一")),
                  new ToolGate.Ask("probe:ask", "探针", AskKind.SENSITIVE)))
          .as("★ 子体提交不了 ⇒ 父侧绝不记账（否则这条调用既不执行、也没人裁）⇒ 退回既有审批闸")
          .isFalse();
      assertThat(ledger.trackedInstances()).isEmpty();
    }
  }

  // ---------- ④ 两侧核对（网关） ----------

  @Test
  void aSubmissionThatDoesNotMatchTheParentsQueueIsRefusedWithZeroApprovals() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingChannel channel = new RecordingChannel();
    CrossProcessDecisionGateway gateway = gateway(ledger, channel);
    ledger.defer(
        probe(),
        context(AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1), Map.of("text", "一")),
        new ToolGate.Ask("probe:ask", "探针", AskKind.SENSITIVE));

    // 子体说"这条的参数摘要是另一份"（同一条调用两侧应同摘要）
    CrossProcessDecisionGateway.Request forged =
        new CrossProcessDecisionGateway.Request(
            CHILD,
            List.of(
                new CrossProcessDecisionGateway.Item(
                    0, TOOL, Map.of(), "", AskKind.SENSITIVE, "deadbeefdead")),
            PACKAGE);

    assertThatThrownBy(() -> gateway.submit(forged))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(CrossProcessDecisionGateway.ITEMS_MISMATCH);
    assertThat(channel.published).as("★ 对不上账 ⇒ 零批准：审批面一次都没被投").isEmpty();
    assertThat(ledger.pendingEntries(CHILD)).as("对不上账那一轮不留账").isEmpty();
  }

  @Test
  void aSubmissionWithoutAParentQueueIsRefusedInsteadOfInventingADecision() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingChannel channel = new RecordingChannel();
    CrossProcessDecisionGateway gateway = gateway(ledger, channel);

    assertThatThrownBy(
            () ->
                gateway.submit(
                    new CrossProcessDecisionGateway.Request(
                        CHILD,
                        List.of(
                            new CrossProcessDecisionGateway.Item(
                                0, TOOL, Map.of(), "", AskKind.SENSITIVE, "deadbeefdead")),
                        PACKAGE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(CrossProcessDecisionGateway.ITEMS_MISMATCH);
    assertThat(channel.published).isEmpty();
  }

  @Test
  void aReconciledSubmissionSettlesWithTheParentsOwnItemsAndGrantsTheApprovedOnes() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingChannel channel = new RecordingChannel().answer(ApprovalDecision.APPROVE_ONCE);
    CrossProcessDecisionGateway gateway = gateway(ledger, channel);
    Map<String, Object> args = Map.of("text", "一");
    ledger.defer(
        probe(),
        context(AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1), args),
        new ToolGate.Ask("probe:ask", "探针", AskKind.SENSITIVE));

    CrossProcessDecisionGateway.Verdict verdict =
        gateway.submit(
            new CrossProcessDecisionGateway.Request(
                CHILD,
                List.of(
                    new CrossProcessDecisionGateway.Item(
                        0,
                        TOOL,
                        Map.of(),
                        "",
                        AskKind.SENSITIVE,
                        DecisionItemDigest.argsDigest(args))),
                PACKAGE));

    assertThat(verdict.approvedIndexes()).as("人批了这一包 ⇒ 那一条被批").containsExactly(0);
    assertThat(channel.published).as("一条包 = 一条请求").hasSize(1);
    assertThat(channel.published.get(0).classKey()).isEqualTo(DecisionApprovalBridge.CLASS_KEY);
    assertThat(ledger.pendingEntries(CHILD)).as("结算完即清本轮队列").isEmpty();
    assertThat(
            ledger
                .release(
                    context(
                        AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1),
                        DecisionReleaseMarker.attach(args, PACKAGE, 0)))
                .orElseThrow()
                .allowed())
        .as("★ 被批条目拿到一次性放行（子体重调才执行得起来）")
        .isTrue();

    // 同键重提：原样返回上次裁决，不再问人第二遍、也不重复记放行
    CrossProcessDecisionGateway.Verdict retry =
        gateway.submit(
            new CrossProcessDecisionGateway.Request(
                CHILD,
                List.of(
                    new CrossProcessDecisionGateway.Item(
                        0,
                        TOOL,
                        Map.of(),
                        "",
                        AskKind.SENSITIVE,
                        DecisionItemDigest.argsDigest(args))),
                PACKAGE));
    assertThat(retry.approvedIndexes()).containsExactly(0);
    assertThat(channel.published).as("★ 幂等命中：不再投审批面").hasSize(1);
  }

  // ---------- 夹具 ----------

  /** 接了一台"能提交"的子体（目录里有提交口 + 权限集放行它）的簿子。 */
  private static ParentSubagentDecisionLedger canSubmitLedger() {
    return canSubmitLedger(ParentSubagentDecisionLedger.TURN_TTL_MILLIS);
  }

  private static ParentSubagentDecisionLedger canSubmitLedger(long turnTtlMillis) {
    ToolRegistry tools = new ToolRegistry();
    tools.register(submitToolStub());
    return new ParentSubagentDecisionLedger(
        tools, instanceId -> Optional.of(AgentPermissionSet.system()), turnTtlMillis);
  }

  private static ToolRegistry toolsWithSubmitTool() {
    ToolRegistry tools = new ToolRegistry();
    tools.register(submitToolStub());
    return tools;
  }

  /** 提交口的桩（判"子体能不能提交"只需要它在目录里且 spec 放行它）。 */
  private static AgentTool submitToolStub() {
    return new AgentTool() {
      @Override
      public String name() {
        return SubmitDecisionPackageTools.NAME;
      }

      @Override
      public String description() {
        return "提交口（用例桩）";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object");
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("{}");
      }
    };
  }

  private CrossProcessDecisionGateway gateway(
      ParentSubagentDecisionLedger ledger, RecordingChannel channel) {
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(), List.of(channel), pending, Duration.ofSeconds(5), bus, key -> true);
    return new CrossProcessDecisionGateway(
        instanceId ->
            Optional.of(
                new DecisionCascade.Level(
                    instanceId, AgentPermissionSet.system(), CommandMode.FULL, "main")),
        null,
        coordinator,
        pending,
        ledger);
  }

  private static ToolContext context(AgentIdentity identity, Map<String, Object> arguments) {
    return new ToolContext(
        AccessToken.DEFAULT, AgentPermissionSet.system(), Map.of(), arguments, identity);
  }

  /** 记下"投了几条请求"的桩通道（脚本给出答复）。 */
  private static final class RecordingChannel implements ApprovalChannel {

    private final List<ApprovalRequest> published = new CopyOnWriteArrayList<>();
    private final Deque<ApprovalDecision> script = new ArrayDeque<>();
    private final AtomicInteger availableCalls = new AtomicInteger();

    RecordingChannel answer(ApprovalDecision decision) {
      script.addLast(decision);
      return this;
    }

    @Override
    public String name() {
      return "case-channel";
    }

    @Override
    public boolean available() {
      availableCalls.incrementAndGet();
      return true;
    }

    @Override
    public void publish(ApprovalRequest request) {
      published.add(request);
    }

    @Override
    public Optional<ApprovalDecision> await(String id, Duration wait) {
      return Optional.ofNullable(script.pollFirst());
    }
  }

  /** 自报 {@code Ask} 的真工具（父侧注册表里那一个）。 */
  private static AgentTool probe() {
    return new AgentTool() {
      @Override
      public String name() {
        return TOOL;
      }

      @Override
      public String description() {
        return "探针（用例桩）";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object");
      }

      @Override
      public ToolGate gate(ToolContext context) {
        return new ToolGate.Ask("probe:ask", "探针", AskKind.SENSITIVE);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        Map<String, Object> copy = new LinkedHashMap<>(context.arguments());
        return ToolResult.ok("probe:" + copy.getOrDefault("text", ""));
      }
    };
  }
}
