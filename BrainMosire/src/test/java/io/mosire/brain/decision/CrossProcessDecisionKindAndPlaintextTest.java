package io.mosire.brain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.LlmSuperiorJudgement;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.approval.SuperiorJudgeDetailRegistry;
import io.mosire.agentlib.approval.SuperiorJudgeGate;
import io.mosire.agentlib.approval.SuperiorJudgement;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionItemDigest;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.event.EventBus;
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
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>P3e 的两条判据</b>（本单自己的证据载体，<b>不是</b> P3c 的验收面）：跨进程请求的 {@code AskKind} 按<b>父侧那份</b> （设计
 * §5sexies⑦）+ 在途明文面（设计 §5sexies⑤★★）。
 *
 * <p><b>它钉住的四件事（改坏哪一处都会红）</b>：
 *
 * <ol>
 *   <li>★ <b>{@code SENSITIVE} 不被代批</b>：上报条目里有敏感区条目 ⇒ 请求取 {@code SENSITIVE} ⇒ 上级判定闸<b>一次都没被问</b>
 *       （零批准）。判别力：把 {@code requestKind} 的"任一条不是 MODE_LIMITED ⇒ SENSITIVE"那一句去掉 ⇒ 假模型会点头 ⇒ 本条红；
 *   <li>★ <b>{@code MODE_LIMITED} 在装配了明细缝时可被代批，且判定提示里带逐条明文</b>：用真 {@link LlmSuperiorJudgement} + 假
 *       {@link LlmClient}（不联网、不花钱）把提示抓下来，断言里面逐条写着参数原文；并断言<b>自动裁决的终局 = 被批</b> （2026-10-17
 *       控制方裁定「无人在环的自动裁决 ⇒ 表态即 commit」，用户可推翻——见 {@code awaitAnswer} 里那一段）；
 *   <li>★ <b>明细缝缺席 ⇒ 退回 {@code SENSITIVE}</b>（fail-closed）：同一个 {@code MODE_LIMITED} 条目在没有明细缝的装配里，
 *       上级判定闸一次都没被问、零批准。判别力：把那条回退去掉 ⇒ 上级<b>盲批</b> ⇒ 本条红；
 *   <li><b>在途明文面</b>：判定当刻（= 正等人裁的窗口里）决策面按 {@code xp-…} 取得到父侧自己那份逐条明文；投递返回即消失。
 * </ol>
 */
class CrossProcessDecisionKindAndPlaintextTest {

  private static final String CHILD = "prober-1";
  private static final String TOOL = "sensitive_probe";
  private static final String PACKAGE = "dp-1";

  private static final Pattern TRANSIENT_ID = Pattern.compile("xp-[0-9a-fA-F-]{36}");

  /**
   * <b>第二段"等执行命令"的窗口</b>（毫秒，用例级）：按缺省 30 秒等的话本类四条就是 120 秒（实测）。 它只决定"没人下执行命令时多久按裁定 15 ④ 收尾"，与判据无关——见
   * AGENTS §四 第 11 条。
   */
  private static final long COMMIT_WAIT_MILLIS = 200L;

  private final EventBus bus = new EventBus();
  private final PendingApprovals pending = new PendingApprovals();

  @AfterEach
  void tearDown() {
    bus.close();
  }

  /**
   * ★ <b>人在审批面批准 = 人自己的执行命令</b>（用户裁定 15 ③ 在跨进程这条路上的落点）。
   *
   * <p>为什么它不是"绕过两段式"：人按下审批面那个批准键，本来就是<b>第二段</b>的动作——它会结算审批面上那条请求、
   * 放行正阻塞在提交口上的提出者线程；若这里只把它当"表态"，这一包会按"已批未执行"作废（零落盘）， 而线程恰恰是被它放行的："放行了却什么都不执行"。⇒ 记成 committed，终局交给
   * {@code committedVerdict} （与决策面 commit 同一条算法），裁决者如实记 {@code HUMAN}。
   *
   * <p><b>判别力</b>：去掉 {@code settle} 里那段 {@code byHuman ⇒ waiting.commit()} ⇒ 本条的 {@code
   * approvedIndexes} 变空、{@code deciderLevel} 变 {@code NONE}（这正是 2026-10-17 收口前实测的形态）。
   */
  @Test
  void aHumanApprovalOnTheApprovalFaceIsTheCommitCommand() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingChannel channel = new RecordingChannel();
    channel.answer(ApprovalDecision.APPROVE_ONCE);
    ApprovalCoordinator coordinator = coordinator(List.of(), channel, Duration.ofSeconds(5));
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator, Optional.empty());

    Map<String, Object> args = Map.of("text", "一");
    ledger.defer(
        probe(AskKind.SENSITIVE),
        childContext(args),
        new ToolGate.Ask("probe:ask", "探针要向人确认一次", AskKind.SENSITIVE));

    CrossProcessDecisionGateway.Verdict verdict = gateway.submit(request(args));

    assertThat(verdict.approvedIndexes())
        .as("★ 人批了 ⇒ 这一条被批、交回提出者执行（人都点头了，不该再作废）")
        .containsExactly(0);
    assertThat(verdict.deciderLevel())
        .as("★ 记录说真话：人拍的板记 HUMAN")
        .isEqualTo(CrossProcessDecisionGateway.DECIDER_HUMAN);
    assertThat(verdict.finalizedBy()).isEqualTo(DecisionCascade.HUMAN_DECIDER);
  }

  /**
   * ★ <b>人在窗口内表态 + 下执行命令 ⇒ 交回提出者执行</b>（用户裁定 15 ②③ 的操作序列："看一条、裁一条、再下执行命令"）。
   *
   * <p>本条的判别力落在"执行命令真的要有人下"这件事上：与上一条唯一的差别就是多了一次 {@link
   * CrossProcessDecisionGateway#commitPackage}（在同一个窗口内），结果从零批准变成"这一条被批"。
   */
  @Test
  void aCommitCommandInsideTheWindowCommitsTheVotedItems() throws Exception {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingChannel channel = new RecordingChannel();
    // ★ 协调器窗口也压到毫秒级（本条要的正是"没人答 ⇒ 进第二段等执行命令"）：按 5s 等就白等 5 秒。
    ApprovalCoordinator coordinator = coordinator(List.of(), channel, Duration.ofMillis(200));
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator, Optional.empty());

    Map<String, Object> args = Map.of("text", "一");
    ledger.defer(
        probe(AskKind.SENSITIVE),
        childContext(args),
        new ToolGate.Ask("probe:ask", "探针要向人确认一次", AskKind.SENSITIVE));

    AtomicReference<CrossProcessDecisionGateway.Verdict> verdict = new AtomicReference<>();
    Thread submitter =
        new Thread(() -> verdict.set(gateway.submit(request(args))), "case-submitter");
    submitter.start();
    // 等人这一包出现在在途面上，再"看一条、裁一条、下执行命令"（正是用户裁定 15 的操作序列）
    String packageId = null;
    for (int i = 0; i < 2000 && packageId == null; i++) {
      packageId = gateway.list().stream().map(DecisionPackage::id).findFirst().orElse(null);
      if (packageId == null) {
        Thread.sleep(5L);
      }
    }
    assertThat(packageId).as("在途面出现这一包").isNotNull();
    gateway.itemDecided(packageId, 0, true, "用例：人", "");
    gateway.commitPackage(packageId, "用例：人");
    submitter.join();

    assertThat(verdict.get().approvedIndexes())
        .as("★ 人在窗口内表态 + 下执行命令 ⇒ 这一条被批、交回提出者执行")
        .containsExactly(0);
    assertThat(verdict.get().deciderLevel())
        .as("★ 记录说真话：人下的执行命令记成 HUMAN")
        .isEqualTo(CrossProcessDecisionGateway.DECIDER_HUMAN);
  }

  // ---------- ③ MODE_LIMITED + 明细缝在 ⇒ 可被代批，提示里带逐条明文 ----------

  @Test
  void aModeLimitedEntryWithTheDetailSeamMayBeApprovedByTheSuperiorJudgeWithPerItemPlaintext()
      throws Exception {
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
            Duration.ofSeconds(5));
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator, Optional.of(details));
    judgeLlm.watch(gateway);

    Map<String, Object> args = Map.of("text", "一");
    ledger.defer(
        probe(AskKind.MODE_LIMITED),
        childContext(args),
        new ToolGate.Ask("probe:ask", "探针要向上级确认一次", AskKind.MODE_LIMITED));

    CrossProcessDecisionGateway.Verdict verdict = gateway.submit(request(args));

    assertThat(judgedKinds)
        .as("★ kind 取父侧那份（MODE_LIMITED）⇒ 请求真的上了上级判定台")
        .containsExactly(AskKind.MODE_LIMITED);
    // ★★ 控制方裁定（2026-10-17，用户可推翻）：**无人在环的自动裁决 ⇒ 表态即 commit**。
    //   上级代批这条路上审批面从来没有过登记项（approval.requested 都没发）⇒ 没有"人"能来敲执行命令；
    //   若仍要求显式 commit，这一包只会白等到窗口结束再按"已批未执行"作废，自动路径等于失效。
    //   ⇒ 闸链批准 = 终局：被批条目交回提出者执行（本类不执行）。判别力：去掉 awaitAnswer 里那段 isAutoApproval
    //     分支 ⇒ 本条 verdict.approvedIndexes() 变空（**这正是 2026-10-17 实测的那条红**）、且白等满窗口。
    assertThat(verdict.approvedIndexes()).as("上级代批 ⇒ 这一条被批（自动路径：表态即 commit）").containsExactly(0);
    assertThat(verdict.deciderLevel())
        .as("★ 记录说真话：代批记成 SUPERIOR，不记成 HUMAN")
        .isEqualTo(CrossProcessDecisionGateway.DECIDER_SUPERIOR);
    assertThat(verdict.finalizedBy()).isEqualTo(DecisionCascade.SUPERIOR_DECIDER);
    assertThat(verdict.trail()).isNotNull();
    assertThat(verdict.trail().finalizedBy()).isEqualTo(DecisionCascade.SUPERIOR_DECIDER);

    assertThat(judgeLlm.prompts).as("判定只跑一次").hasSize(1);
    assertThat(judgeLlm.prompts.get(0))
        .as("★ 上级判定提示里带逐条明文（不是只看得到'几条、哪些工具'）")
        .contains("逐条明细（1 条")
        .contains(TOOL)
        .contains("\"text\":\"一\"");

    assertThat(judgeLlm.inFlightAtJudgeTime).as("★ 判定当刻，决策面的在途取数口已经能按 xp-… 取到这一包").hasSize(1);
    DecisionPackage seenByJudge = judgeLlm.inFlightAtJudgeTime.get(0);
    assertThat(seenByJudge.items()).hasSize(1);
    assertThat(seenByJudge.items().get(0).args()).as("在途面给的是父侧自己那份明文").isEqualTo(args);
    assertThat(gateway.find(seenByJudge.id())).as("★ 结算完即消失：在途面没变成第二份历史").isEmpty();
  }

  // ---------- ② SENSITIVE 永远到人：上级一次都没被问 ----------

  @Test
  void aSensitiveEntryIsNeverHandedToTheSuperiorJudgeEvenWithTheDetailSeam() {
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
            Duration.ofMillis(120));
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator, Optional.of(details));
    judgeLlm.watch(gateway);

    Map<String, Object> args = Map.of("text", "一");
    ledger.defer(
        probe(AskKind.SENSITIVE),
        childContext(args),
        new ToolGate.Ask("probe:ask", "探针要向人确认一次", AskKind.SENSITIVE));

    CrossProcessDecisionGateway.Verdict verdict = gateway.submit(request(args));

    assertThat(channel.published).as("一条包 = 一条请求").hasSize(1);
    assertThat(channel.published.get(0).kind())
        .as("★ 请求取父侧那份 = SENSITIVE（敏感区永远到人）")
        .isEqualTo(AskKind.SENSITIVE);
    assertThat(judgedKinds).as("★ 上级判定闸一次都没被问（SENSITIVE 它自己会让开）").isEmpty();
    assertThat(judgeLlm.prompts).as("假模型一次都没被调用").isEmpty();
    assertThat(verdict.approvedIndexes()).as("★ 没人答 ⇒ 零批准（绝不被代批）").isEmpty();
    assertThat(verdict.deciderLevel()).isEqualTo(CrossProcessDecisionGateway.DECIDER_NONE);
    assertThat(verdict.finalizedBy()).isNull();
  }

  // ---------- ④ 明细缝缺席 ⇒ MODE_LIMITED 退回 SENSITIVE（fail-closed） ----------

  @Test
  void aModeLimitedEntryWithoutTheDetailSeamFallsBackToSensitive() {
    ParentSubagentDecisionLedger ledger = canSubmitLedger();
    RecordingOnlyJudgement judgement = new RecordingOnlyJudgement();
    RecordingChannel channel = new RecordingChannel();
    ApprovalCoordinator coordinator =
        coordinator(
            List.of(new SuperiorJudgeGate(judgement, () -> CommandMode.FULL)),
            channel,
            Duration.ofMillis(120));
    CrossProcessDecisionGateway gateway = gateway(ledger, coordinator, Optional.empty());

    Map<String, Object> args = Map.of("text", "一");
    ledger.defer(
        probe(AskKind.MODE_LIMITED),
        childContext(args),
        new ToolGate.Ask("probe:ask", "探针要向人确认一次", AskKind.MODE_LIMITED));

    CrossProcessDecisionGateway.Verdict verdict = gateway.submit(request(args));

    assertThat(channel.published).hasSize(1);
    assertThat(channel.published.get(0).kind())
        .as("★ 看不见就不给批：明细缝缺席 ⇒ 退回 SENSITIVE（fail-closed）")
        .isEqualTo(AskKind.SENSITIVE);
    assertThat(judgement.judged).as("★ 上级一次都没被问（否则就是'没明细还让上级代批'）").isEmpty();
    assertThat(verdict.approvedIndexes()).isEmpty();
    assertThat(verdict.deciderLevel()).isEqualTo(CrossProcessDecisionGateway.DECIDER_NONE);
  }

  // ---------- ⑤ 老形态（父侧队列未接线）⇒ 不采信调用方自报的 kind ----------

  @Test
  void withoutTheParentQueueTheSubmittedKindIsNotTrusted() {
    SuperiorJudgeDetailRegistry details = new SuperiorJudgeDetailRegistry();
    RecordingOnlyJudgement judgement = new RecordingOnlyJudgement();
    RecordingChannel channel = new RecordingChannel();
    ApprovalCoordinator coordinator =
        coordinator(
            List.of(new SuperiorJudgeGate(judgement, () -> CommandMode.FULL)),
            channel,
            Duration.ofMillis(120));
    // 五参构造：接线明细缝但**没有**父侧本轮队列 ⇒ 没有"父侧那一刻算出来的那份 kind"可采信
    // ★ 同样注入毫秒级执行等待窗（按缺省 30s 等的话，本条"没人答"就要白挂 30 秒——实测 30.122s）。
    CrossProcessDecisionGateway gateway =
        new CrossProcessDecisionGateway(
            instanceId ->
                Optional.of(
                    new DecisionCascade.Level(
                        instanceId, AgentPermissionSet.system(), CommandMode.FULL, "main")),
            cascade(),
            coordinator,
            pending,
            null,
            Optional.of(details),
            COMMIT_WAIT_MILLIS);

    Map<String, Object> args = Map.of("text", "一");
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

  // ---------- 夹具 ----------

  private ApprovalCoordinator coordinator(
      List<io.mosire.agentlib.approval.ApprovalGate> gates,
      RecordingChannel channel,
      Duration timeout) {
    return new ApprovalCoordinator(gates, List.of(channel), pending, timeout, bus, key -> true);
  }

  private CrossProcessDecisionGateway gateway(
      ParentSubagentDecisionLedger ledger,
      ApprovalCoordinator coordinator,
      Optional<SuperiorJudgeDetailRegistry> details) {
    // ★ 裁定 15 ④（2026-10-17 用户指令「太变态了直接等30分钟，改！」+ AGENTS §四 第 11 条）：用例**一律**注入毫秒级
    //   执行等待窗——按缺省（30s）等的话，本类四条用例就是 4×30s（实测 120.6s）。窗口只决定"没人下执行命令时多久收尾"。
    return new CrossProcessDecisionGateway(
        instanceId ->
            Optional.of(
                new DecisionCascade.Level(
                    instanceId, AgentPermissionSet.system(), CommandMode.FULL, "main")),
        cascade(),
        coordinator,
        pending,
        ledger,
        details,
        COMMIT_WAIT_MILLIS);
  }

  /** 没有可上溯层级的级联（每一级都解析不出来 ⇒ 上报集合 = 全部条目，与真装配的"没人有能力"同形）。 */
  private static DecisionCascade cascade() {
    return new DecisionCascade(instanceId -> Optional.empty(), name -> Optional.empty());
  }

  private static ParentSubagentDecisionLedger canSubmitLedger() {
    ToolRegistry tools = new ToolRegistry();
    tools.register(submitToolStub());
    return new ParentSubagentDecisionLedger(
        tools, instanceId -> Optional.of(AgentPermissionSet.system()));
  }

  /** 子体提交的那三件（{@code args}/{@code classKey}/{@code askKind} 一律给"不像真的"的值：父侧以自己那份为准）。 */
  private static CrossProcessDecisionGateway.Request request(Map<String, Object> args) {
    return new CrossProcessDecisionGateway.Request(
        CHILD,
        List.of(
            new CrossProcessDecisionGateway.Item(
                0, TOOL, Map.of(), "", AskKind.SENSITIVE, DecisionItemDigest.argsDigest(args))),
        PACKAGE);
  }

  private static ToolContext childContext(Map<String, Object> args) {
    return new ToolContext(
        AccessToken.DEFAULT,
        AgentPermissionSet.system(),
        Map.of(),
        args,
        AgentIdentity.subagent(CHILD, CommandMode.LIMITED, "goal", 1));
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

  /** 自报 {@code Ask} 的真工具（父侧注册表里那一个）；kind 由用例给定。 */
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

  /** 记下"这条请求的 kind 是什么"的判定装饰器（真判定仍由里层跑）。 */
  private record JudgingSpy(SuperiorJudgement delegate, List<AskKind> judgedKinds)
      implements SuperiorJudgement {

    @Override
    public Decision judge(ApprovalRequest request) {
      judgedKinds.add(request.kind());
      return delegate.judge(request);
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

  /**
   * <b>假模型</b>（不联网、不花钱）：把判定提示抓下来，并在判定当刻读一次在途明文面。
   *
   * <p>它跑在 {@code LlmSuperiorJudgement} 的判定线程上（{@code coordinator.decide} 的调用栈里）——正是"人/上级此刻在看"
   * 的那一段窗口，所以在这里读到的在途包就是"决策面此刻能查到的那一份"。
   */
  private static final class RecordingJudgeLlm implements LlmClient {

    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final List<DecisionPackage> inFlightAtJudgeTime = new CopyOnWriteArrayList<>();
    private volatile CrossProcessDecisionGateway gateway;

    void watch(CrossProcessDecisionGateway gateway) {
      this.gateway = gateway;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
      String prompt = promptOf(request);
      prompts.add(prompt);
      Matcher found = TRANSIENT_ID.matcher(prompt);
      if (found.find() && gateway != null) {
        gateway.find(found.group()).ifPresent(inFlightAtJudgeTime::add);
      }
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

  /** 记下"投了几条请求"的桩通道（脚本给出答复；没脚本 ⇒ 到点没人答）。 */
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
}
