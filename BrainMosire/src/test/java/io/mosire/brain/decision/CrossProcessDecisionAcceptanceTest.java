package io.mosire.brain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalGate;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.LlmSuperiorJudgement;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.approval.SuperiorJudgeDetailRegistry;
import io.mosire.agentlib.approval.SuperiorJudgeGate;
import io.mosire.agentlib.approval.SuperiorJudgement;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.DecisionEventTypes;
import io.mosire.agentlib.decision.DecisionItemDigest;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.DecisionReleaseMarker;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.EventStore;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.LlmClient;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>P3c 跨进程决策包验收判据（RT 责任区）</b>——按任务书的判据清单逐条落实，<b>不是</b>按代码反推。
 *
 * <p>本类是 Brain 层能独立验收的那一批（判据 3/4/5/6/7/8/9/11/12/13/14/15）；真子进程那几条（1/2/9-E2E/11-E2E/14-E2E/15-E2E）在
 * {@code MainMosire} 的 {@code CrossProcessDecisionAcceptanceE2ETest}，"码不靠文案"（判据 10）在 {@code
 * AgentLibMosire} 的 {@code DecisionQueuedCodeOverTextAcceptanceTest}。
 *
 * <p><b>为什么用真件</b>：本类里 {@link ParentSubagentDecisionLedger}、{@link CrossProcessDecisionGateway}、
 * {@link ToolCallAuthorizer}、{@link ToolExecutionGuard}、{@link LlmSuperiorJudgement}、 {@link
 * SuperiorJudgeDetailRegistry} 都是生产件（假件只有"审批通道 / 判定模型 / 工具本体"三个不该在单测里真的碰的东西）。 判据要能"改坏 ⇒
 * 当场红"，红点必须落在被保护的那行上（见报告 §变异表）。
 *
 * <p><b>裁定 14（静默 = 拒绝）</b>贯穿本类：只有"明确同意的那些条目"才有 {@code approvedIndexes}，其余一切（没人答 / 到点 / 无通道 / 对不上账 /
 * 提交被拒收）在行为上同归"什么都不做"，且不存在"保留到下一轮"。
 */
class CrossProcessDecisionAcceptanceTest {

  private static final String CHILD = "prober-1";
  private static final String OTHER_CHILD = "prober-2";
  private static final String TOOL = "sensitive_probe";
  private static final String PACKAGE = "dp-1";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final EventBus bus = new EventBus();
  private final PendingApprovals pending = new PendingApprovals();
  private final List<SqliteEventStore> stores = new CopyOnWriteArrayList<>();

  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    bus.close();
    for (SqliteEventStore store : stores) {
      store.close();
    }
  }

  // ============================================================================================
  // 判据 3：上级代批（合法那一路）——全 MODE_LIMITED + 明细缝在 + 父侧队列在 ⇒ 可代批；
  //        判定提示里带逐条明文；记录 deciderLevel=SUPERIOR、finalizedBy=superior。
  // ============================================================================================

  @Test
  void c3_aModeLimitedRoundIsBatchedByTheSuperiorWithEveryItemInPlaintext() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    SuperiorJudgeDetailRegistry details = new SuperiorJudgeDetailRegistry();
    RecordingJudgeLlm judgeLlm = new RecordingJudgeLlm();
    List<AskKind> judgedKinds = new CopyOnWriteArrayList<>();
    RecordingChannel channel = new RecordingChannel();
    ApprovalCoordinator coordinator =
        coordinator(
            List.of(
                new SuperiorJudgeGate(
                    new JudgingSpy(
                        new LlmSuperiorJudgement(judgeLlm, Duration.ofSeconds(5), details),
                        judgedKinds),
                    () -> CommandMode.FULL)),
            channel,
            Duration.ofMillis(200));
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator, Optional.of(details));

    List<Map<String, Object>> args = List.of(args("一"), args("二"));
    deferAll(ledger, CHILD, args, AskKind.MODE_LIMITED);

    CrossProcessDecisionGateway.Verdict verdict = gateway.submit(request(CHILD, PACKAGE, args));

    assertThat(judgedKinds)
        .as("★ kind 取父侧队列那一份（MODE_LIMITED）⇒ 请求真的上了上级判定台")
        .containsExactly(AskKind.MODE_LIMITED);
    assertThat(verdict.approvedIndexes()).as("上级代批 ⇒ 两条都被批（逐条粒度）").containsExactly(0, 1);
    assertThat(verdict.deciderLevel())
        .as("★ 记录说真话：谁拍的板记 SUPERIOR")
        .isEqualTo(CrossProcessDecisionGateway.DECIDER_SUPERIOR);
    assertThat(verdict.finalizedBy()).isEqualTo(DecisionCascade.SUPERIOR_DECIDER);
    assertThat(verdict.trail()).isNotNull();
    assertThat(verdict.trail().finalizedBy()).isEqualTo(DecisionCascade.SUPERIOR_DECIDER);

    assertThat(judgeLlm.prompts).as("判定只跑一次（整包一次）").hasSize(1);
    assertThat(judgeLlm.prompts.get(0))
        .as("★ 判定提示里带逐条明文（不是只看得到'几条、哪些工具'）")
        .contains("逐条明细（2 条")
        .contains(TOOL)
        .contains("\"text\":\"一\"")
        .contains("\"text\":\"二\"");
  }

  // ============================================================================================
  // 判据 4：幂等——同 proposerKey + 同 requestId ⇒ 原样返回上次裁决、不再投审批面；
  //        不同 proposerKey 的相同 requestId 不互相命中；同键不同内容 ⇒ 具名拒。
  // ============================================================================================

  @Test
  void c4a_theSameProposerAndRequestIdReturnTheSameVerdictWithoutASecondApprovalRequest() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingChannel channel = new RecordingChannel().answer(ApprovalDecision.APPROVE_ONCE);
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator(channel), Optional.empty());

    List<Map<String, Object>> args = List.of(args("一"));
    deferAll(ledger, CHILD, args, AskKind.SENSITIVE);

    CrossProcessDecisionGateway.Request submission = request(CHILD, PACKAGE, args);
    CrossProcessDecisionGateway.Verdict first = gateway.submit(submission);
    assertThat(first.approvedIndexes()).containsExactly(0);
    assertThat(channel.published).as("第一次提交 ⇒ 一条包 = 一条请求").hasSize(1);

    CrossProcessDecisionGateway.Verdict again = gateway.submit(submission);
    assertThat(again.approvedIndexes())
        .as("★ 命中幂等缓存 ⇒ 原样返回上次裁决（此刻父侧队列已被清空，能返回就只能是缓存命中）")
        .containsExactly(0);
    assertThat(again.deciderLevel()).isEqualTo(first.deciderLevel());
    assertThat(channel.published).as("★ 重试不再问人第二遍").hasSize(1);
  }

  @Test
  void c4b_twoProposersWithTheSameRequestIdDoNotHitEachOthersCache() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingChannel channel =
        new RecordingChannel()
            .answer(ApprovalDecision.APPROVE_ONCE)
            .answer(ApprovalDecision.APPROVE_ONCE);
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator(channel), Optional.empty());

    List<Map<String, Object>> firstArgs = List.of(args("甲"));
    List<Map<String, Object>> secondArgs = List.of(args("乙"));
    deferAll(ledger, CHILD, firstArgs, AskKind.SENSITIVE);
    deferAll(ledger, OTHER_CHILD, secondArgs, AskKind.SENSITIVE);

    CrossProcessDecisionGateway.Verdict first = gateway.submit(request(CHILD, PACKAGE, firstArgs));
    CrossProcessDecisionGateway.Verdict second =
        gateway.submit(request(OTHER_CHILD, PACKAGE, secondArgs));

    assertThat(channel.published)
        .as("★ 同一 requestId、不同 proposerKey ⇒ 两次提交各问一次（裸键互撞会答错人）")
        .hasSize(2);
    assertThat(channel.published.get(0).requesterId()).isEqualTo(CHILD);
    assertThat(channel.published.get(1).requesterId()).isEqualTo(OTHER_CHILD);
    assertThat(second.approvedIndexes()).as("后一个提出者照样拿得到自己的裁决").containsExactly(0);
    assertThat(first.approvedIndexes()).as("先前的裁决没有被后来的提交改写").containsExactly(0);
  }

  @Test
  void c4c_theSameKeyWithDifferentContentIsRefusedByName() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingChannel channel = new RecordingChannel().answer(ApprovalDecision.APPROVE_ONCE);
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator(channel), Optional.empty());

    List<Map<String, Object>> args = List.of(args("一"));
    deferAll(ledger, CHILD, args, AskKind.SENSITIVE);
    gateway.submit(request(CHILD, PACKAGE, args));

    assertThatThrownBy(() -> gateway.submit(request(CHILD, PACKAGE, List.of(args("另一批完全不同的内容")))))
        .as("★ 同键不同内容 ⇒ 具名拒（把旧裁决交回去等于用针对 A 的裁决回答 B）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(CrossProcessDecisionGateway.INVALID_ITEMS);
    assertThat(channel.published).as("被拒的那次没有再问人").hasSize(1);
  }

  // ============================================================================================
  // 判据 12：摘要一致性——同一份参数经 JSON 往返后两侧 digest 逐字相同（合法提交不被误判 mismatch）。
  // ============================================================================================

  @Test
  void c12_theSameArgumentsSurviveAJsonRoundTripSoALegitimateSubmitIsNotMisjudged()
      throws Exception {
    Map<String, Object> original = new LinkedHashMap<>();
    original.put("text", "跨进程");
    original.put("path", "a/b.md");
    original.put("nested", Map.of("z", 1, "a", List.of("x", "y")));
    String json = JSON.writeValueAsString(original);
    @SuppressWarnings("unchecked")
    Map<String, Object> roundTripped = JSON.readValue(json, Map.class);

    assertThat(DecisionItemDigest.argsDigest(roundTripped))
        .as("★ 规范化摘要：同一份参数经 JSON 往返（Map 实现变了）后逐字相同")
        .isEqualTo(DecisionItemDigest.argsDigest(original));

    // 键序不同的同一份参数（同一份内容、另一种 Map 实现/插入序）⇒ 同一摘要——规范化的判别力钉在这里
    Map<String, Object> nestedReordered = new LinkedHashMap<>();
    nestedReordered.put("a", List.of("x", "y"));
    nestedReordered.put("z", 1);
    Map<String, Object> reordered = new LinkedHashMap<>();
    reordered.put("nested", nestedReordered);
    reordered.put("path", "a/b.md");
    reordered.put("text", "跨进程");
    assertThat(reordered).as("前置事实：两份 Map 内容相等、迭代序不同").isEqualTo(original);
    assertThat(DecisionItemDigest.argsDigest(reordered))
        .as("★ 键序不参与摘要（否则同一条调用两侧会得到两个摘要 ⇒ 每次合法提交都被判 mismatch）")
        .isEqualTo(DecisionItemDigest.argsDigest(original));

    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingChannel channel = new RecordingChannel().answer(ApprovalDecision.APPROVE_ONCE);
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator(channel), Optional.empty());
    // 父侧 defer 那一刻收到的是 original；子体提交时用的是经 JSON 往返后的那一份（真实形态就是如此）
    ledger.defer(probe(AskKind.SENSITIVE), childContext(CHILD, original), ask(AskKind.SENSITIVE));

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
                        DecisionItemDigest.argsDigest(roundTripped))),
                PACKAGE));

    assertThat(verdict.approvedIndexes())
        .as("★ 合法提交不被误判成 ITEMS_MISMATCH：摘要对得上账，包照常结算")
        .containsExactly(0);
  }

  // ============================================================================================
  // 判据 5：一次性放行——被批条目重调 ⇒ 放行、标记被消费并被剥；重复使用 ⇒ 具名拒。
  // ============================================================================================

  @Test
  void c5a_anApprovedItemIsReleasedExactlyOnceAndTheMarkerNeverReachesTheToolBody() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    Map<String, Object> args = args("一");
    ledger.defer(probe(AskKind.SENSITIVE), childContext(CHILD, args), ask(AskKind.SENSITIVE));
    ledger.settled(CHILD, PACKAGE, Set.of(0));

    List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
    ToolRegistry tools = new ToolRegistry();
    tools.register(recordingProbe(seen));
    RecordingChannel channel = new RecordingChannel();
    ToolCallAuthorizer authorizer =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(),
            coordinator(List.of(), channel, Duration.ofMillis(100)),
            context -> "case-digest",
            ledger);

    ToolContext marked = childContext(CHILD, DecisionReleaseMarker.attach(args, PACKAGE, 0));
    ToolResult first = authorizer.execute(tools, TOOL, marked);
    assertThat(first.success()).as("被批条目重调 ⇒ 放行").isTrue();
    assertThat(seen).as("★ 工具体看到的是剥掉标记之后的参数（标记不是工具的参数）").containsExactly(Map.of("text", "一"));

    ToolResult again = authorizer.execute(tools, TOOL, marked);
    assertThat(again.success()).as("★ 一次性：同一 {packageId,index} 不能再放行一次").isFalse();
    assertThat(again.code()).isEqualTo(ToolCallAuthorizer.DECISION_RELEASE_USED);
    assertThat(seen).as("第二次没有再执行（零执行）").hasSize(1);
    assertThat(channel.published).as("带标记的调用绝不落到审批闸（否则伪造标记就成了一次免审批的尝试）").isEmpty();
  }

  @Test
  void c5b_aMarkerWithoutAGrantIsRefusedByNameAndNothingExecutes() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    Map<String, Object> args = args("一");
    ledger.defer(probe(AskKind.SENSITIVE), childContext(CHILD, args), ask(AskKind.SENSITIVE));
    ledger.settled(CHILD, PACKAGE, Set.of()); // 零批准

    List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
    ToolRegistry tools = new ToolRegistry();
    tools.register(recordingProbe(seen));
    ToolCallAuthorizer authorizer =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(),
            coordinator(List.of(), new RecordingChannel(), Duration.ofMillis(100)),
            context -> "case-digest",
            ledger);

    ToolResult refused =
        authorizer.execute(
            tools, TOOL, childContext(CHILD, DecisionReleaseMarker.attach(args, PACKAGE, 0)));

    assertThat(refused.success()).isFalse();
    assertThat(refused.code()).isEqualTo(ToolCallAuthorizer.DECISION_RELEASE_UNKNOWN);
    assertThat(seen).as("★ 没有放行条 ⇒ 工具体一次都没跑（零执行）").isEmpty();
  }

  // ============================================================================================
  // 判据 6：items 与父侧队列不一致 ⇒ ITEMS_MISMATCH + 零批准 + 零执行。
  // ============================================================================================

  @Test
  void c6_aSubmissionThatDoesNotMatchTheParentQueueIsRefusedWithZeroApprovalAndZeroExecution() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingChannel channel = new RecordingChannel().answer(ApprovalDecision.APPROVE_ONCE);
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator(channel), Optional.empty());
    List<Map<String, Object>> args = List.of(args("一"), args("二"), args("三"));
    deferAll(ledger, CHILD, args, AskKind.SENSITIVE);

    // ① 摘要对不上（同条数、第 1 条内容不同）
    List<CrossProcessDecisionGateway.Item> wrongDigest = new ArrayList<>();
    wrongDigest.add(item(0, DecisionItemDigest.argsDigest(args.get(0))));
    wrongDigest.add(item(1, DecisionItemDigest.argsDigest(args("另一份内容"))));
    wrongDigest.add(item(2, DecisionItemDigest.argsDigest(args.get(2))));
    assertMismatchAndNoApproval(gateway, channel, ledger, wrongDigest);

    // ② 条数对不上（父侧 3 条、提交 1 条）——对不上账的上一轮不留账，所以这里重新 defer
    deferAll(ledger, CHILD, args, AskKind.SENSITIVE);
    assertMismatchAndNoApproval(
        gateway, channel, ledger, List.of(item(0, DecisionItemDigest.argsDigest(args.get(0)))));
  }

  private void assertMismatchAndNoApproval(
      CrossProcessDecisionGateway gateway,
      RecordingChannel channel,
      ParentSubagentDecisionLedger ledger,
      List<CrossProcessDecisionGateway.Item> items) {
    assertThatThrownBy(
            () -> gateway.submit(new CrossProcessDecisionGateway.Request(CHILD, items, PACKAGE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(CrossProcessDecisionGateway.ITEMS_MISMATCH);
    assertThat(channel.published).as("★ 对不上账 ⇒ 零批准：审批面一次都没被投").isEmpty();
    assertThat(ledger.pendingEntries(CHILD)).as("对不上账的那一轮不留账").isEmpty();
    assertThat(ledger.grantCount()).as("★ 一条放行条都不记（零执行）").isZero();

    List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
    ToolRegistry tools = new ToolRegistry();
    tools.register(recordingProbe(seen));
    ToolCallAuthorizer authorizer =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(),
            coordinator(List.of(), new RecordingChannel(), Duration.ofMillis(100)),
            context -> "case-digest",
            ledger);
    ToolResult forged =
        authorizer.execute(
            tools, TOOL, childContext(CHILD, DecisionReleaseMarker.attach(args("一"), PACKAGE, 0)));
    assertThat(forged.success()).isFalse();
    assertThat(seen).as("★ 零执行：即使子体拿伪造标记回来，工具体也不跑").isEmpty();
  }

  // ============================================================================================
  // 判据 7：SENSITIVE 条目永不被上级代批。
  // ============================================================================================

  @Test
  void c7_aSensitiveItemIsNeverBatchedByTheSuperiorEvenWhenEverythingElseIsInPlace() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    SuperiorJudgeDetailRegistry details = new SuperiorJudgeDetailRegistry();
    RecordingOnlyJudgement judgement = new RecordingOnlyJudgement();
    RecordingChannel channel = new RecordingChannel();
    ApprovalCoordinator coordinator =
        coordinator(
            List.of(new SuperiorJudgeGate(judgement, () -> CommandMode.FULL)),
            channel,
            Duration.ofMillis(150));
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator, Optional.of(details));

    List<Map<String, Object>> args = List.of(args("敏感区"));
    deferAll(ledger, CHILD, args, AskKind.SENSITIVE);

    CrossProcessDecisionGateway.Verdict verdict = gateway.submit(request(CHILD, PACKAGE, args));

    assertThat(channel.published).as("一条包 = 一条请求").hasSize(1);
    assertThat(channel.published.get(0).kind())
        .as("★ 请求取父侧那份 = SENSITIVE（敏感区永远到人）")
        .isEqualTo(AskKind.SENSITIVE);
    assertThat(judgement.judged).as("★ 上级判定闸一次都没被问（被代批 ⇒ 红）").isEmpty();
    assertThat(verdict.approvedIndexes()).as("没人答 ⇒ 零批准").isEmpty();
    assertThat(verdict.deciderLevel()).isEqualTo(CrossProcessDecisionGateway.DECIDER_NONE);
  }

  // ============================================================================================
  // 判据 8：fail-closed 两条——明细缝缺席 ⇒ 退回 SENSITIVE；父侧队列未接线 ⇒ 不采信自报的 kind。
  // ============================================================================================

  @Test
  void c8a_withoutTheDetailSeamAModeLimitedItemFallsBackToSensitive() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingOnlyJudgement judgement = new RecordingOnlyJudgement();
    RecordingChannel channel = new RecordingChannel();
    ApprovalCoordinator coordinator =
        coordinator(
            List.of(new SuperiorJudgeGate(judgement, () -> CommandMode.FULL)),
            channel,
            Duration.ofMillis(150));
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator, Optional.empty());

    List<Map<String, Object>> args = List.of(args("一"));
    deferAll(ledger, CHILD, args, AskKind.MODE_LIMITED);

    CrossProcessDecisionGateway.Verdict verdict = gateway.submit(request(CHILD, PACKAGE, args));

    assertThat(channel.published).hasSize(1);
    assertThat(channel.published.get(0).kind())
        .as("★ 看不见内容就不给批：明细缝缺席 ⇒ 退回 SENSITIVE")
        .isEqualTo(AskKind.SENSITIVE);
    assertThat(judgement.judged).as("★ 上级一次都没被问（否则就是'没明细还让上级代批'）").isEmpty();
    assertThat(verdict.approvedIndexes()).isEmpty();
  }

  @Test
  void c8b_withoutTheParentQueueTheSubmittedKindIsNotTrusted() {
    SuperiorJudgeDetailRegistry details = new SuperiorJudgeDetailRegistry();
    RecordingOnlyJudgement judgement = new RecordingOnlyJudgement();
    RecordingChannel channel = new RecordingChannel();
    ApprovalCoordinator coordinator =
        coordinator(
            List.of(new SuperiorJudgeGate(judgement, () -> CommandMode.FULL)),
            channel,
            Duration.ofMillis(150));
    // 五参构造：没有父侧本轮队列 ⇒ 没有"父侧那一刻算出来的那份 kind"可采信
    CrossProcessDecisionGateway gateway =
        new CrossProcessDecisionGateway(
            instanceId ->
                Optional.of(
                    new DecisionCascade.Level(
                        instanceId, AgentPermissionSet.system(), CommandMode.FULL, "main")),
            emptyCascade(),
            coordinator,
            pending,
            null,
            Optional.of(details));

    Map<String, Object> args = args("一");
    CrossProcessDecisionGateway.Verdict verdict =
        gateway.submit(
            new CrossProcessDecisionGateway.Request(
                CHILD,
                List.of(
                    new CrossProcessDecisionGateway.Item(
                        0,
                        TOOL,
                        args,
                        "",
                        AskKind.MODE_LIMITED,
                        DecisionItemDigest.argsDigest(args))),
                PACKAGE));

    assertThat(channel.published).hasSize(1);
    assertThat(channel.published.get(0).kind())
        .as("★ 调用方自报的 MODE_LIMITED 不被采信（没有权威来源就不上代批台）")
        .isEqualTo(AskKind.SENSITIVE);
    assertThat(judgement.judged).isEmpty();
    assertThat(verdict.approvedIndexes()).isEmpty();
  }

  // ============================================================================================
  // 判据 9：★ 裁定 14「静默 = 拒绝」——无人答复 / 到点 / 无可用通道 ⇒ 零执行、零落盘，
  //        且不存在"保留到下一轮"（下一轮不得自己续上）。
  // ============================================================================================

  @Test
  void c9_silenceMeansRejectAndTheNextRoundCannotResumeTheOldQueue() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingChannel channel = new RecordingChannel(); // 没有任何答复 ⇒ 到点
    CrossProcessDecisionGateway gateway =
        gateway(ledger, coordinator(channel, Duration.ofMillis(120)), Optional.empty());

    List<Map<String, Object>> args = List.of(args("一"), args("二"), args("三"));
    deferAll(ledger, CHILD, args, AskKind.SENSITIVE);

    CrossProcessDecisionGateway.Verdict silent = gateway.submit(request(CHILD, PACKAGE, args));

    assertThat(silent.approvedIndexes()).as("★ 静默 = 拒绝：零批准").isEmpty();
    assertThat(silent.deciderLevel()).isEqualTo(CrossProcessDecisionGateway.DECIDER_NONE);
    assertThat(channel.published).as("问过一次（这正是'没人答'的形状）").hasSize(1);
    assertThat(ledger.pendingEntries(CHILD)).as("本轮队列当场收束").isEmpty();
    assertThat(ledger.grantCount()).as("★ 零执行：一条放行条都不记").isZero();

    // ★ 不存在"保留到下一轮"：下一轮拿同一个 requestId 之外的键、同一批条目回来 ⇒ 队列已不在 ⇒ 具名拒
    assertThatThrownBy(() -> gateway.submit(request(CHILD, "dp-2", args)))
        .as("★ 到点即终局：下一轮不得自己续上上一轮的账")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(CrossProcessDecisionGateway.ITEMS_MISMATCH);
    assertThat(channel.published).as("被拒的那次没有再问人").hasSize(1);

    // 下一轮从零开始：重新 Ask（父侧重新记账）之后照常可结算
    deferAll(ledger, CHILD, List.of(args("下一轮")), AskKind.SENSITIVE);
    assertThat(ledger.pendingEntries(CHILD)).as("新的一轮只有新的一条（不是 3+1）").hasSize(1);
    CrossProcessDecisionGateway.Verdict next =
        gateway.submit(request(CHILD, "dp-2", List.of(args("下一轮"))));
    assertThat(next.approvedIndexes()).as("新一轮里没人答 ⇒ 仍然零批准").isEmpty();
  }

  // ============================================================================================
  // 判据 11：canSubmit 与子体侧 available() 同源（模板缺 submit_decision_package ⇒ 两侧同时判"不在"）。
  // ============================================================================================

  @Test
  void c11_canSubmitAndAvailableAgreeInAllThreeConfigurations() {
    record Config(String what, ToolRegistry tools, AgentPermissionSet permissions) {}
    List<Config> configs =
        List.of(
            new Config("目录里没有提交口", registryWithoutSubmitTool(), AgentPermissionSet.system()),
            new Config("权限集不放行提交口", registryWithSubmitTool(), permissionWithoutSubmitTool()),
            new Config("目录与权限集都在", registryWithSubmitTool(), AgentPermissionSet.system()));

    List<Boolean> availability = new ArrayList<>();
    for (Config config : configs) {
      ParentSubagentDecisionLedger ledger =
          new ParentSubagentDecisionLedger(
              config.tools(), instanceId -> Optional.of(config.permissions()));
      CrossProcessDecisionSubmitter submitter = submitter(config.permissions(), config.tools());

      boolean available = submitter.available();
      availability.add(available);
      boolean booked =
          ledger.defer(
              probe(AskKind.SENSITIVE), childContext(CHILD, args("一")), ask(AskKind.SENSITIVE));

      assertThat(booked)
          .as("★ 两侧同一把 oracle（%s）：available()=%s ⇒ 父侧记账=%s", config.what(), available, booked)
          .isEqualTo(available);
      if (!available) {
        assertThat(ledger.trackedInstances()).as("★ 子体提交不了 ⇒ 父侧绝不记账（否则'已记入本轮决策包却没人裁'）").isEmpty();
      }
    }
    assertThat(availability)
        .as("三档实测值（缺口/不放行/都在）——两个方向都钉住：既不许恒真、也不许恒假")
        .containsExactly(false, false, true);
  }

  // ============================================================================================
  // 判据 13：Block 硬拒不进队列、不入包（连登记都没有）。
  // ============================================================================================

  @Test
  void c13_aBlockedCallIsNeitherQueuedNorPackaged() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    List<Map<String, Object>> seen = new CopyOnWriteArrayList<>();
    ToolRegistry tools = new ToolRegistry();
    tools.register(blockingProbe(seen));
    RecordingChannel channel = new RecordingChannel();
    ToolCallAuthorizer authorizer =
        ToolCallAuthorizer.of(
            new ToolExecutionGuard(),
            coordinator(List.of(), channel, Duration.ofMillis(100)),
            context -> "case-digest",
            ledger);

    ToolResult blocked = authorizer.execute(tools, TOOL, childContext(CHILD, args("硬拒")));

    assertThat(blocked.success()).isFalse();
    assertThat(blocked.code()).isEqualTo(ToolCallAuthorizer.COMMAND_BLOCKED);
    assertThat(seen).as("工具体没跑").isEmpty();
    assertThat(ledger.trackedInstances()).as("★ 硬拒连队列都不建（不进队列 ⇒ 也不入包）").isEmpty();
    assertThat(ledger.pendingEntries(CHILD)).isEmpty();
    assertThat(channel.published).as("★ 硬拒不进审批面（防'审批把硬拒洗白'）").isEmpty();
  }

  // ============================================================================================
  // 判据 14：无未决项的回合——不建包、不提交、零 decision.package.* 事件（零副作用）。
  // ============================================================================================

  @Test
  void c14_aTurnWithoutPendingItemsProducesNoPackageNoSubmitAndNoDecisionEvents() {
    SqliteEventStore store = store("c14-events.db");
    DecisionPackages packages = new DecisionPackages();
    RecordingSubmitTool submitTool = new RecordingSubmitTool();
    ToolRegistry registry = new ToolRegistry();
    registry.register(submitTool);
    CrossProcessDecisionSubmitter submitter =
        new CrossProcessDecisionSubmitter(
            packages,
            Optional.empty(),
            registry,
            ToolCallAuthorizer.standard(),
            Map.of(),
            AccessToken.DEFAULT,
            AgentPermissionSet.system(),
            AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1),
            store,
            null);

    Optional<DecisionPackage> settled =
        submitter.close(new PendingDecisionQueue(CHILD, AccessToken.DEFAULT));

    assertThat(settled).as("空队列 ⇒ 不建包（与桥的空队列口径逐字一致）").isEmpty();
    assertThat(submitTool.calls).as("★ 不提交：提交口一次都没被调").isEmpty();
    assertThat(packages.pending()).as("包表里一个包都没有").isEmpty();
    for (String type :
        List.of(
            DecisionEventTypes.PROPOSED,
            DecisionEventTypes.DECIDED,
            DecisionEventTypes.COMMITTED,
            DecisionEventTypes.CONFLICTED,
            DecisionEventTypes.EXPIRED)) {
      assertThat(events(store, type)).as("★ 零 decision.package.* 事件（%s）", type).isEmpty();
    }
  }

  // ============================================================================================
  // 判据 15：生命周期——子体被杀 / 被回收 ⇒ 父侧队列清空、无残留；
  //         之后同实例再来从零开始（不拿旧队列结算）。
  // ============================================================================================

  @Test
  void c15_recyclingClearsTheQueueAndTheSameInstanceRestartsFromZero() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingChannel channel = new RecordingChannel().answer(ApprovalDecision.APPROVE_ONCE);
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator(channel), Optional.empty());
    List<Map<String, Object>> old = List.of(args("一"), args("二"), args("三"));
    deferAll(ledger, CHILD, old, AskKind.SENSITIVE);
    assertThat(ledger.pendingEntries(CHILD)).hasSize(3);

    ledger.recycle(CHILD);

    assertThat(ledger.trackedInstances()).as("★ 回收 ⇒ 队列清空、无残留").isEmpty();
    assertThat(ledger.pendingEntries(CHILD)).isEmpty();
    assertThat(ledger.grantCount()).isZero();

    // 之后同实例再来：只记新的那一条（不是 3+1，也不拿旧队列结算）
    deferAll(ledger, CHILD, List.of(args("四")), AskKind.SENSITIVE);
    assertThat(ledger.pendingEntries(CHILD)).as("★ 从零开始：新的一轮只有新的一条").hasSize(1);

    assertThatThrownBy(() -> gateway.submit(request(CHILD, "dp-old", old)))
        .as("★ 不拿旧队列结算：拿旧的 3 条回来 ⇒ 对不上账")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(CrossProcessDecisionGateway.ITEMS_MISMATCH);
    assertThat(channel.published).as("对不上账 ⇒ 零批准").isEmpty();

    deferAll(ledger, CHILD, List.of(args("四")), AskKind.SENSITIVE);
    CrossProcessDecisionGateway.Verdict fresh =
        gateway.submit(request(CHILD, "dp-new", List.of(args("四"))));
    assertThat(fresh.approvedIndexes()).as("新的一轮照常结算（证'清空'不是'把这条路弄坏了'）").containsExactly(0);
  }

  // ============================================================================================
  // 夹具
  // ============================================================================================

  private static Map<String, Object> args(String text) {
    return Map.of("text", text);
  }

  private static ToolGate.Ask ask(AskKind kind) {
    return new ToolGate.Ask("probe:ask", "探针要向人确认一次", kind);
  }

  private static CrossProcessDecisionGateway.Item item(int index, String digest) {
    return new CrossProcessDecisionGateway.Item(
        index, TOOL, Map.of(), "", AskKind.SENSITIVE, digest);
  }

  private static CrossProcessDecisionGateway.Request request(
      String proposerKey, String requestId, List<Map<String, Object>> argsList) {
    List<CrossProcessDecisionGateway.Item> items = new ArrayList<>(argsList.size());
    for (int i = 0; i < argsList.size(); i++) {
      items.add(item(i, DecisionItemDigest.argsDigest(argsList.get(i))));
    }
    return new CrossProcessDecisionGateway.Request(proposerKey, items, requestId);
  }

  private static void deferAll(
      ParentSubagentDecisionLedger ledger,
      String instanceId,
      List<Map<String, Object>> argsList,
      AskKind kind) {
    for (Map<String, Object> args : argsList) {
      ledger.defer(probe(kind), childContext(instanceId, args), ask(kind));
    }
  }

  private static ToolContext childContext(String instanceId, Map<String, Object> args) {
    return new ToolContext(
        AccessToken.DEFAULT,
        AgentPermissionSet.system(),
        Map.of(),
        args,
        AgentIdentity.subagent(instanceId, CommandMode.LIMITED, "goal", 1));
  }

  private static ParentSubagentDecisionLedger canSubmitLedger() {
    return new ParentSubagentDecisionLedger(
        registryWithSubmitTool(), instanceId -> Optional.of(AgentPermissionSet.system()));
  }

  private CrossProcessDecisionGateway gateway(
      ParentSubagentDecisionLedger ledger,
      ApprovalCoordinator coordinator,
      Optional<SuperiorJudgeDetailRegistry> details) {
    return new CrossProcessDecisionGateway(
        instanceId ->
            Optional.of(
                new DecisionCascade.Level(
                    instanceId, AgentPermissionSet.system(), CommandMode.FULL, "main")),
        emptyCascade(),
        coordinator,
        pending,
        ledger,
        details);
  }

  private ApprovalCoordinator coordinator(RecordingChannel channel) {
    return coordinator(channel, Duration.ofSeconds(5));
  }

  private ApprovalCoordinator coordinator(RecordingChannel channel, Duration timeout) {
    return coordinator(List.of(), channel, timeout);
  }

  private ApprovalCoordinator coordinator(
      List<ApprovalGate> gates, RecordingChannel channel, Duration timeout) {
    return new ApprovalCoordinator(gates, List.of(channel), pending, timeout, bus, key -> true);
  }

  /** 没有可上溯层级的级联（每一级都解析不出来 ⇒ 上报集合 = 全部条目，与真装配的"没人有能力"同形）。 */
  private static DecisionCascade emptyCascade() {
    return new DecisionCascade(instanceId -> Optional.empty(), name -> Optional.empty());
  }

  private static ToolRegistry registryWithSubmitTool() {
    ToolRegistry tools = new ToolRegistry();
    tools.register(submitToolStub());
    return tools;
  }

  private static ToolRegistry registryWithoutSubmitTool() {
    return new ToolRegistry();
  }

  /** 放行 {@code sensitive_probe}、但白名单里<b>没有</b>提交口（模板 allowedTools 少一条的形状）。 */
  private static AgentPermissionSet permissionWithoutSubmitTool() {
    return new AgentPermissionSet(AccessToken.DEFAULT, Set.of(TOOL), Set.of(), false, false, false);
  }

  private CrossProcessDecisionSubmitter submitter(
      AgentPermissionSet permissions, ToolRegistry registry) {
    return submitter(permissions, registry, new RecordingSubmitTool());
  }

  private CrossProcessDecisionSubmitter submitter(
      AgentPermissionSet permissions, ToolRegistry registry, RecordingSubmitTool submitTool) {
    return new CrossProcessDecisionSubmitter(
        new DecisionPackages(),
        Optional.empty(),
        registry,
        ToolCallAuthorizer.standard(),
        Map.of(),
        permissions.grantedToken(),
        permissions,
        AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1),
        store("submitter-events.db"),
        null);
  }

  private SqliteEventStore store(String name) {
    try {
      SqliteEventStore store = SqliteEventStore.open(tempDir.resolve(name));
      stores.add(store);
      return store;
    } catch (Exception failure) {
      throw new AssertionError("用例夹具失败", failure);
    }
  }

  private static List<Event> events(EventStore store, String type) {
    return store.query(new EventQuery("", type, "", -1, 100));
  }

  /** 记参数的探针（被批条目重调时用它证"工具体看到了什么"）。 */
  private static AgentTool recordingProbe(List<Map<String, Object>> seen) {
    return new AgentTool() {
      @Override
      public String name() {
        return TOOL;
      }

      @Override
      public String description() {
        return "探针（用例桩：记下工具体看到的参数）";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object");
      }

      @Override
      public ToolGate gate(ToolContext context) {
        return new ToolGate.Ask("probe:ask", "探针要向人确认一次", AskKind.SENSITIVE);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        seen.add(Map.copyOf(context.arguments()));
        return ToolResult.ok("probe:" + context.arguments().getOrDefault("text", ""));
      }
    };
  }

  /** 硬拒探针（{@link ToolGate.Block}）。 */
  private static AgentTool blockingProbe(List<Map<String, Object>> seen) {
    return new AgentTool() {
      @Override
      public String name() {
        return TOOL;
      }

      @Override
      public String description() {
        return "探针（用例桩：硬拒）";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object");
      }

      @Override
      public ToolGate gate(ToolContext context) {
        return new ToolGate.Block("probe:block", "用例桩：这类调用连问都不该问");
      }

      @Override
      public ToolResult execute(ToolContext context) {
        seen.add(Map.copyOf(context.arguments()));
        return ToolResult.ok("不该跑到这里");
      }
    };
  }

  /** 自报 {@code Ask} 的探针（父侧注册表里那一个；kind 由用例给定）。 */
  private static AgentTool probe(AskKind kind) {
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
        return new ToolGate.Ask("probe:ask", "探针要向人确认一次", kind);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return ToolResult.ok("probe:" + context.arguments().getOrDefault("text", ""));
      }
    };
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

  /** 记下"投了几条请求"的桩通道（可脚本答复；没脚本 ⇒ 到点没人答）。 */
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

  /** 只记"有没有被问过"的判定桩（永远批准：它被问到时本条用例就该红）。 */
  private static final class RecordingOnlyJudgement implements SuperiorJudgement {

    private final List<ApprovalRequest> judged = new CopyOnWriteArrayList<>();

    @Override
    public Decision judge(ApprovalRequest request) {
      judged.add(request);
      return Decision.approve("用例桩：批准");
    }
  }

  /** 记下"这条请求的 kind"的判定装饰器（真判定仍由里层跑）。 */
  private record JudgingSpy(SuperiorJudgement delegate, List<AskKind> judgedKinds)
      implements SuperiorJudgement {

    @Override
    public Decision judge(ApprovalRequest request) {
      judgedKinds.add(request.kind());
      return delegate.judge(request);
    }
  }

  /** 记下提交口代理被调了几次（判据 14 要的是"一次都没被调"）。 */
  private static final class RecordingSubmitTool implements AgentTool {

    private final List<Map<String, Object>> calls = new CopyOnWriteArrayList<>();

    @Override
    public String name() {
      return SubmitDecisionPackageTools.NAME;
    }

    @Override
    public String description() {
      return "父侧结算口的本地代理（用例桩）";
    }

    @Override
    public Map<String, Object> jsonSchema() {
      return Map.of("type", "object");
    }

    @Override
    public ToolResult execute(ToolContext context) {
      calls.add(Map.copyOf(context.arguments()));
      return ToolResult.ok("{}");
    }
  }

  /**
   * <b>假模型</b>（不联网、不花钱）：把上级判定提示抓下来。
   *
   * <p>它跑在 {@link LlmSuperiorJudgement} 的判定线程上（= 判定者此刻真在看的那段调用栈）。
   */
  private static final class RecordingJudgeLlm implements LlmClient {

    private final List<String> prompts = new CopyOnWriteArrayList<>();

    @Override
    public LlmResponse chat(LlmRequest request) {
      prompts.add(promptOf(request));
      return LlmResponse.text("APPROVE 用例桩：看得见逐条明文才点头");
    }

    @Override
    public String model() {
      return "case-fake-judge";
    }

    private static String promptOf(LlmRequest request) {
      StringBuilder out = new StringBuilder();
      for (LlmMessage message : request.messages()) {
        for (ContentPart part : message.content()) {
          if (part instanceof ContentPart.Text text) {
            out.append(text.text()).append('\n');
          }
        }
      }
      return out.toString();
    }
  }
}
