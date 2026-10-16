package io.mosire.brain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.DecisionCommitPort;
import io.mosire.agentlib.decision.DecisionEventTypes;
import io.mosire.agentlib.decision.DecisionPackage;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.decision.ResolvedCall;
import io.mosire.agentlib.event.Event;
import io.mosire.agentlib.event.EventBus;
import io.mosire.agentlib.event.EventQuery;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.llm.ContentPart;
import io.mosire.agentlib.llm.FakeLlmClient;
import io.mosire.agentlib.llm.LlmMessage;
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
import io.mosire.brain.context.BasicContextAssembler;
import io.mosire.brain.runtime.AgentConfig;
import io.mosire.brain.runtime.AgentPipeline;
import io.mosire.brain.runtime.StopReason;
import io.mosire.brain.runtime.TurnResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 子体侧回合收尾的<b>跨进程五步</b>（P3b，设计 §5quinquies ①/⑤）在 Brain 层的判据：
 *
 * <ol>
 *   <li>队列非空 ⇒ 先在<b>自己的包表</b>里落一个 {@code PROPOSED} 包，包 id 就是提交的 {@code requestId}（幂等键同源）；
 *   <li>提交走<b>既有工具调用路径</b>（registry 里那个 {@code submit_decision_package} 的代理）；
 *   <li>回执 ⇒ {@code decide} 回填 + 执行<b>被批子集</b> + 落 {@code proposed}/{@code decided}/{@code
 *       committed} 事件 （{@code trail} 直接用回执里的那一份）；
 *   <li>软拒（{@code deciderLevel=NONE}）⇒ 建包但<b>零批准、零落盘</b>（{@code REJECTED}，提交口一次都不被调）；
 *   <li>硬拒（{@code ToolResult.error} 带码）⇒ 提交没被受理 ⇒ 当场作废（{@code EXPIRED}）+ 原因，绝不当成"零批准"。
 * </ol>
 *
 * <p><b>判别力（改坏哪里它会红）</b>：
 *
 * <ul>
 *   <li>把"先落本地包"换成"提交成功再建包"（{@code requestId} 不再等于本地包 id）⇒ {@link
 *       #theLocalPackageIsProposedBeforeTheSubmitAndItsIdIsTheRequestId()} 红；
 *   <li>把硬拒那条路改成"按零批准回填"⇒ {@link #aHardRefusalExpiresThePackageInsteadOfRecordingADecision()} 红；
 *   <li>把软拒那条路改成"绝不建包"⇒ {@link #aSoftRefusalKeepsThePackageWithZeroApprovalsAndZeroWrites()} 红；
 *   <li>把 {@code trail} 换成"自己算一份空轨迹"⇒ {@link
 *       #theDecidedEventCarriesTheTrailFromTheReplyAndNoArgumentPlaintext()} 红；
 *   <li>把"缝在不在"的判据改成"只要接了缝就算在"⇒ {@link #withoutTheToolInTheRegistryThereIsNoSeam()} 与其管线级判据 {@link
 *       #thePipelineDoesNotBindTheQueueWhenTheSeamIsMissing()} 红。
 * </ul>
 */
class CrossProcessDecisionSubmitterTest {

  private static final String SECRET = "sk-live-SECRET123";

  @TempDir Path tempDir;

  private SqliteEventStore store;
  private EventBus bus;
  private DecisionPackages packages;
  private RecordingCommitPort commitPort;
  private RecordingSubmitTool submitTool;
  private ToolRegistry registry;

  /** 父侧那个工具的本地代理（记录入参、按脚本回答；真实形态里它是 MCP 桥同步进来的代理）。 */
  private static final class RecordingSubmitTool implements AgentTool {

    private final List<Map<String, Object>> calls = new CopyOnWriteArrayList<>();
    private ToolResult answer = ToolResult.ok("{}");

    RecordingSubmitTool answer(ToolResult result) {
      this.answer = result;
      return this;
    }

    @Override
    public String name() {
      return CrossProcessDecisionSubmitter.SUBMIT_TOOL_NAME;
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
      return answer;
    }
  }

  /**
   * 本机执行者桩：记下"被批子集"（真形态是 {@code LocalDecisionCommitPort}）。
   *
   * <p>给 {@code executor} 时它还会<b>真的逐条执行</b>工具本体（用来证"被批子集确实被执行了"）——判据链不复刻 （真形态那条链的判据在 {@code
   * LocalDecisionCommitPort} 自己的用例里），这里只做"有没有执行、执行了谁"。
   */
  private static final class RecordingCommitPort implements DecisionCommitPort {

    private final ToolRegistry executor;
    private final List<List<ResolvedCall>> batches = new CopyOnWriteArrayList<>();
    private final List<String> executed = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    RecordingCommitPort() {
      this(null);
    }

    RecordingCommitPort(ToolRegistry executor) {
      this.executor = executor;
    }

    @Override
    public CommitReceipt commit(String packageId, List<ResolvedCall> calls) {
      this.calls.incrementAndGet();
      batches.add(List.copyOf(calls));
      if (executor != null) {
        for (ResolvedCall call : calls) {
          executor
              .find(call.toolName())
              .ifPresent(
                  tool -> {
                    executed.add(call.toolName());
                    tool.execute(
                        new ToolContext(
                            AccessToken.SYSTEM,
                            AgentPermissionSet.system(),
                            Map.of(),
                            call.args(),
                            AgentIdentity.subagent("bash-agent-1", CommandMode.LIMITED, "g", 1)));
                  });
        }
      }
      List<CommitReceipt.ItemOutcome> outcomes = new ArrayList<>(calls.size());
      for (int i = 0; i < calls.size(); i++) {
        outcomes.add(CommitReceipt.ItemOutcome.COMMITTED);
      }
      return new CommitReceipt(CommitReceipt.Status.COMMITTED, "batch:" + packageId, outcomes, "");
    }
  }

  @BeforeEach
  void setUp() {
    store = SqliteEventStore.open(tempDir.resolve("events.db"));
    bus = new EventBus();
    packages = new DecisionPackages();
    commitPort = new RecordingCommitPort();
    submitTool = new RecordingSubmitTool();
    registry = new ToolRegistry();
    registry.register(submitTool);
  }

  @AfterEach
  void tearDown() {
    bus.close();
    store.close();
  }

  private CrossProcessDecisionSubmitter submitter(AgentPermissionSet permissions) {
    return submitter(permissions, commitPort);
  }

  private CrossProcessDecisionSubmitter submitter(
      AgentPermissionSet permissions, DecisionCommitPort port) {
    AgentIdentity identity = AgentIdentity.subagent("bash-agent-1", CommandMode.LIMITED, "重启服务", 1);
    return new CrossProcessDecisionSubmitter(
        packages,
        Optional.of(port),
        registry,
        ToolCallAuthorizer.standard(),
        Map.of(),
        permissions.grantedToken(),
        permissions,
        identity,
        store,
        bus);
  }

  private static PendingDecisionQueue queueWithThreeAsks() {
    PendingDecisionQueue queue = new PendingDecisionQueue("bash-agent-1", AccessToken.DEFAULT);
    queue.register(
        "bash",
        Map.of("command", "systemctl restart " + SECRET),
        new ToolGate.Ask("bash:ask:restart", "重启服务", AskKind.SENSITIVE));
    queue.register(
        "bash",
        Map.of("command", "echo two"),
        new ToolGate.Ask("bash:ask:echo", "普通命令", AskKind.MODE_LIMITED));
    queue.register(
        "doc_edit",
        Map.of("path", "x.md"),
        new ToolGate.Ask("docs:ask", "改文档", AskKind.MODE_LIMITED));
    return queue;
  }

  private static String verdictJson(
      List<Integer> approved, String finalizedBy, String deciderLevel, String trailJson) {
    return "{\"approvedIndexes\":"
        + approved
        + ",\"rejectedIndexes\":[],\"perItemReasons\":null,\"trail\":"
        + trailJson
        + ",\"finalizedBy\":"
        + (finalizedBy == null ? "null" : "\"" + finalizedBy + "\"")
        + ",\"deciderLevel\":\""
        + deciderLevel
        + "\"}";
  }

  private List<Event> eventsOfType(String type) {
    return store.query(new EventQuery("", type, "", -1, 100));
  }

  // ---------- ① + ② + ③：五步的正常路径 ----------

  @Test
  void theLocalPackageIsProposedBeforeTheSubmitAndItsIdIsTheRequestId() {
    submitTool.answer(
        ToolResult.ok(
            verdictJson(
                List.of(0, 2), "human", "HUMAN", "{\"levels\":[],\"finalizedBy\":\"human\"}")));

    Optional<DecisionPackage> settled =
        submitter(AgentPermissionSet.system()).close(queueWithThreeAsks());

    assertThat(submitTool.calls).as("恰好提交一次").hasSize(1);
    Map<String, Object> args = submitTool.calls.get(0);
    assertThat(args.get("proposerKey")).isEqualTo("bash-agent-1");
    String requestId = String.valueOf(args.get("requestId"));
    assertThat(packages.find(requestId))
        .as("★ requestId 就是本地那个包（先落包、后提交：①的产物作 ② 的幂等键）")
        .isPresent();
    assertThat(settled).isPresent();
    assertThat(settled.orElseThrow().id()).isEqualTo(requestId);
    assertThat(settled.orElseThrow().state()).isEqualTo(PackageState.COMMITTED);
    assertThat(settled.orElseThrow().decision().approvedIndexes())
        .as("Decision 的 approvedIndexes 是 Set（冻结形状不保证迭代序）⇒ 这里只判集合")
        .containsExactlyInAnyOrder(0, 2);
    assertThat(settled.orElseThrow().decision().by())
        .as("裁决人 = 父侧回执里的 finalizedBy（记录面）")
        .isEqualTo("human");

    List<Map<String, Object>> items = itemsOf(args);
    assertThat(items)
        .as("条目原样带上：顺序 = 登记顺序、index = 包内条目身份")
        .extracting(row -> row.get("index"))
        .containsExactly(0, 1, 2);
    assertThat(items)
        .extracting(row -> row.get("toolName"))
        .containsExactly("bash", "bash", "doc_edit");
    assertThat(items)
        .extracting(row -> row.get("askKind"))
        .containsExactly("SENSITIVE", "MODE_LIMITED", "MODE_LIMITED");
    assertThat(items.get(0).get("args"))
        .isEqualTo(Map.of("command", "systemctl restart " + SECRET));

    assertThat(commitPort.batches).as("被批子集一次提交").hasSize(1);
    assertThat(commitPort.batches.get(0)).extracting(ResolvedCall::index).containsExactly(0, 2);
    assertThat(commitPort.batches.get(0))
        .extracting(ResolvedCall::toolName)
        .containsExactly("bash", "doc_edit");

    assertThat(eventsOfType(DecisionEventTypes.PROPOSED)).hasSize(1);
    assertThat(eventsOfType(DecisionEventTypes.DECIDED)).hasSize(1);
    assertThat(eventsOfType(DecisionEventTypes.COMMITTED)).hasSize(1);
    assertThat(eventsOfType(DecisionEventTypes.EXPIRED)).isEmpty();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> itemsOf(Map<String, Object> args) {
    return (List<Map<String, Object>>) args.get("items");
  }

  @Test
  void theDecidedEventCarriesTheTrailFromTheReplyAndNoArgumentPlaintext() throws Exception {
    String trail =
        "{\"levels\":[{\"decider\":\"main\",\"mode\":\"FULL\",\"approved\":[],\"rejected\":[],\"reason\":null}],"
            + "\"escalatedToHuman\":[0],\"finalApproved\":[0],\"finalizedBy\":\"human\"}";
    submitTool.answer(ToolResult.ok(verdictJson(List.of(0), "human", "HUMAN", trail)));

    submitter(AgentPermissionSet.system()).close(queueWithThreeAsks());

    Event decided = eventsOfType(DecisionEventTypes.DECIDED).get(0);
    assertThat(decided.payload())
        .as("★ trail 直接用回执里的那一份（不另算）")
        .contains("\"trail\"")
        .contains("\"escalatedToHuman\":[0]")
        .contains("\"decider\":\"main\"");

    for (Event event : store.query(EventQuery.recent(100))) {
      assertThat(event.payload()).as("密钥纪律：任何事件的载荷里都没有参数明文（digests 才是那一档）").doesNotContain(SECRET);
    }
  }

  @Test
  void eventsLandInTheChildsOwnStoreWithThePackageIdAsCorrelationId() {
    submitTool.answer(ToolResult.ok(verdictJson(List.of(0), "human", "HUMAN", null)));

    DecisionPackage settled =
        submitter(AgentPermissionSet.system()).close(queueWithThreeAsks()).orElseThrow();

    List<Event> byCorrelation = store.queryLatestByCorrelation(DecisionEventTypes.COMMITTED, 10, 0);
    assertThat(byCorrelation).hasSize(1);
    assertThat(byCorrelation.get(0).correlationId())
        .as("correlationId = 包 id（与桥同源，读侧按它分组）")
        .isEqualTo(settled.id());
    assertThat(byCorrelation.get(0).agent()).isEqualTo("bash-agent-1");
  }

  // ---------- ④ 软拒 ----------

  @Test
  void aSoftRefusalKeepsThePackageWithZeroApprovalsAndZeroWrites() {
    submitTool.answer(ToolResult.ok(verdictJson(List.of(), null, "NONE", null)));

    Optional<DecisionPackage> settled =
        submitter(AgentPermissionSet.system()).close(queueWithThreeAsks());

    assertThat(settled).isPresent();
    assertThat(settled.orElseThrow().state())
        .as("★ 软拒 = 一次没有通过的裁决：建包、零批准、零落盘（进历史）")
        .isEqualTo(PackageState.REJECTED);
    assertThat(settled.orElseThrow().decision().approvedIndexes()).isEmpty();
    assertThat(settled.orElseThrow().decision().by())
        .isEqualTo(CrossProcessDecisionSubmitter.BY_NONE);
    assertThat(commitPort.calls).as("被批子集为空 ⇒ 包层判 REJECTED，提交口一次都不被调").hasValue(0);
    assertThat(eventsOfType(DecisionEventTypes.PROPOSED)).hasSize(1);
    assertThat(eventsOfType(DecisionEventTypes.DECIDED)).hasSize(1);
    assertThat(eventsOfType(DecisionEventTypes.COMMITTED)).isEmpty();
    assertThat(eventsOfType(DecisionEventTypes.EXPIRED)).as("软拒不是作废：它是「没人批」，不是「提交没被受理」").isEmpty();
  }

  // ---------- ⑤ 硬拒 ----------

  @Test
  void aHardRefusalExpiresThePackageInsteadOfRecordingADecision() {
    submitTool.answer(
        ToolResult.error(
            CrossProcessDecisionGateway.PROPOSER_UNKNOWN,
            "PROPOSER_UNKNOWN：proposerKey=bash-agent-1 不在实例注册表里"));

    Optional<DecisionPackage> settled =
        submitter(AgentPermissionSet.system()).close(queueWithThreeAsks());

    assertThat(settled).isPresent();
    assertThat(settled.orElseThrow().state())
        .as("★ 硬拒 = 提交没被受理 ⇒ 当场作废（不留一个悬着的未决包）")
        .isEqualTo(PackageState.EXPIRED);
    assertThat(settled.orElseThrow().decision()).as("没有裁决这回事").isNull();
    assertThat(commitPort.calls).hasValue(0);
    assertThat(eventsOfType(DecisionEventTypes.PROPOSED)).hasSize(1);
    assertThat(eventsOfType(DecisionEventTypes.DECIDED)).isEmpty();
    List<Event> expired = eventsOfType(DecisionEventTypes.EXPIRED);
    assertThat(expired).hasSize(1);
    assertThat(expired.get(0).payload())
        .as("如实记「这次提交没被受理」+ 拒码")
        .contains("未被受理")
        .contains(CrossProcessDecisionGateway.PROPOSER_UNKNOWN);
  }

  @Test
  void anUnreadableReplyIsTreatedAsNotAccepted() {
    submitTool.answer(ToolResult.ok("这不是 JSON"));

    Optional<DecisionPackage> settled =
        submitter(AgentPermissionSet.system()).close(queueWithThreeAsks());

    assertThat(settled.orElseThrow().state()).isEqualTo(PackageState.EXPIRED);
    assertThat(commitPort.calls).hasValue(0);
    assertThat(eventsOfType(DecisionEventTypes.EXPIRED)).hasSize(1);
  }

  @Test
  void aReplyThatApprovesWithoutADeciderIsRefused() {
    submitTool.answer(ToolResult.ok(verdictJson(List.of(0), null, "HUMAN", null)));

    Optional<DecisionPackage> settled =
        submitter(AgentPermissionSet.system()).close(queueWithThreeAsks());

    assertThat(settled.orElseThrow().state())
        .as("批了东西却没人拍板 = 回执违约：不编一个裁决人把它洗成合法裁决")
        .isEqualTo(PackageState.EXPIRED);
    assertThat(commitPort.calls).hasValue(0);
  }

  // ---------- 空队列 / 缝不在 ----------

  @Test
  void anEmptyQueueHasZeroSideEffects() {
    Optional<DecisionPackage> settled =
        submitter(AgentPermissionSet.system())
            .close(new PendingDecisionQueue("bash-agent-1", AccessToken.DEFAULT));

    assertThat(settled).isEmpty();
    assertThat(submitTool.calls).isEmpty();
    assertThat(commitPort.calls).hasValue(0);
    assertThat(store.query(EventQuery.recent(100))).isEmpty();
  }

  @Test
  void withoutTheToolInTheRegistryThereIsNoSeam() {
    ToolRegistry bare = new ToolRegistry();
    AgentPermissionSet permissions = AgentPermissionSet.system();
    CrossProcessDecisionSubmitter seam =
        new CrossProcessDecisionSubmitter(
            packages,
            Optional.of(commitPort),
            bare,
            ToolCallAuthorizer.standard(),
            Map.of(),
            permissions.grantedToken(),
            permissions,
            AgentIdentity.subagent("bash-agent-1", CommandMode.LIMITED, "g", 1),
            store,
            bus);

    assertThat(seam.available()).as("registry 里没有那个工具 ⇒ 缝不在（不绑队列）").isFalse();
  }

  @Test
  void aPermissionSetThatDoesNotAllowTheToolMeansNoSeam() {
    AgentPermissionSet narrow =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("read_agent_context").build();

    assertThat(submitter(narrow).available())
        .as("工具在目录里但判定面不放行 ⇒ 缝不在（否则子体会看见'一调就吃 PERMISSION_DENIED'的形状）")
        .isFalse();
    assertThat(submitter(AgentPermissionSet.system()).available()).isTrue();
  }

  // ---------- 管线级：绑不绑队列 ----------

  @Test
  void thePipelineDoesNotBindTheQueueWhenTheSeamIsMissing() {
    // 同一个 walk：registry 里没有 submit_decision_package ⇒ 缝不在 ⇒ 不绑队列 ⇒ Ask 走既有那一跳
    // （这里没有审批编排器 ⇒ fail-closed 拒；关键是"没有包、没有事件、没有提交"）
    ToolRegistry bare = new ToolRegistry();
    bare.register(askTool());
    AgentPermissionSet permissions = AgentPermissionSet.system();
    CrossProcessDecisionSubmitter seam =
        new CrossProcessDecisionSubmitter(
            packages,
            Optional.of(commitPort),
            bare,
            ToolCallAuthorizer.standard(),
            Map.of(),
            permissions.grantedToken(),
            permissions,
            AgentIdentity.subagent("bash-agent-1", CommandMode.LIMITED, "g", 1),
            store,
            bus);

    assertThat(seam.available()).isFalse();
    assertThat(runOneAskTurn(bare, seam, permissions))
        .as("无跨进程缝 ⇒ 行为逐字不变：Ask 既不进包也不执行（这里吃 fail-closed 拒）")
        .isEqualTo(StopReason.FINISHED);
    assertThat(commitPort.calls).hasValue(0);
    assertThat(eventsOfType(DecisionEventTypes.PROPOSED))
        .as("没有队列、没有包、没有 decision.package.* 事件")
        .isEmpty();
  }

  @Test
  void thePipelineBindsTheQueueAndClosesCrossProcessWhenTheSeamIsPresent() {
    ToolRegistry full = new ToolRegistry();
    full.register(askTool());
    full.register(submitTool);
    submitTool.answer(ToolResult.ok(verdictJson(List.of(0), "human", "HUMAN", null)));
    AgentPermissionSet permissions = AgentPermissionSet.system();
    RecordingCommitPort executingPort = new RecordingCommitPort(full);

    assertThat(runOneAskTurn(full, submitter(permissions, executingPort), permissions))
        .isEqualTo(StopReason.FINISHED);

    assertThat(submitTool.calls).as("★ 一轮 N 条 ⇒ 恰好一个包、恰好一次跨进程提交").hasSize(1);
    assertThat(itemsOf(submitTool.calls.get(0))).hasSize(1);
    assertThat(eventsOfType(DecisionEventTypes.PROPOSED)).hasSize(1);
    assertThat(eventsOfType(DecisionEventTypes.DECIDED)).hasSize(1);
    assertThat(executingPort.calls).hasValue(1);
    // 被批子集执行的是"那条 Ask 工具"（本例里它是本地桩，真形态里是父侧工具面的代理）
    assertThat(executingPort.executed).as("★ 执行发生在收尾这条路上：被批子集真被执行了").containsExactly("danger_tool");
    assertThat(executedToolNames).containsExactly("danger_tool");
  }

  /** 上一条用例里"被批子集到底执行了哪条"的观察点。 */
  private final List<String> executedToolNames = new CopyOnWriteArrayList<>();

  /** 一条自报 {@code Ask} 的工具（模型一点它就会进本轮待裁决队列）。 */
  private AgentTool askTool() {
    return new AgentTool() {
      @Override
      public String name() {
        return "danger_tool";
      }

      @Override
      public String description() {
        return "会问人的工具（用例桩）";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object");
      }

      @Override
      public ToolGate gate(ToolContext context) {
        return new ToolGate.Ask("danger:ask", "这条要问人", AskKind.SENSITIVE);
      }

      @Override
      public ToolResult execute(ToolContext context) {
        executedToolNames.add(name());
        return ToolResult.ok("executed");
      }
    };
  }

  /**
   * 跑一个"模型点了一次 Ask 工具、然后收尾"的回合；返回停因。
   *
   * <p>管线用"不落库 + 工具配置 + 跨进程缝"那条构造：子体进程的真实形态就是它（不落库、无审批面）。
   */
  private StopReason runOneAskTurn(
      ToolRegistry tools, CrossProcessDecisionSubmitter seam, AgentPermissionSet permissions) {
    AgentConfig config = AgentConfig.builder("bash-agent-1").maxTurns(4).build();
    FakeLlmClient llm =
        FakeLlmClient.with(
            new LlmResponse(
                LlmMessage.assistant(
                    List.of(
                        new ContentPart.ToolCall(
                            "c-1", "danger_tool", new LinkedHashMap<>(Map.of("x", 1))))),
                "fake-model",
                LlmResponse.UNKNOWN_TOKENS,
                LlmResponse.UNKNOWN_TOKENS),
            LlmResponse.text("结束"));
    AgentPipeline pipeline =
        new AgentPipeline(
            config,
            llm,
            tools,
            new ToolExecutionGuard(),
            new BasicContextAssembler(),
            store,
            bus,
            permissions.grantedToken(),
            permissions,
            Map.of(),
            seam);
    TurnResult result = pipeline.run("点一次敏感工具");
    return result.stopReason();
  }
}
