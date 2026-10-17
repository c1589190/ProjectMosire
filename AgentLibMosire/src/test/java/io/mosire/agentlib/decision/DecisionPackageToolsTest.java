package io.mosire.agentlib.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link DecisionPackageTools} 的验收用例（U4）。
 *
 * <p><b>为什么这些用例必须存在</b>：这五个工具是决策包唯一的 MCP 审核面。它们最危险的错法都不是“编译不过”， 而是看起来成功——① 模型自报的 {@code by}
 * 被当成裁决人；② 概览面把参数明文/由参数派生的 summary 带出去；③ full 读取不落审计； ④ 写工具绕过 {@link DecisionSubmissionPort}；⑤
 * 在途包主表未命中时被静默当成未知包；⑥ 服务端异常消息把参数回显给模型。 每条都有正/负向断言钉住。
 *
 * <p><b>判别力（改坏哪里会红）</b>：
 *
 * <ul>
 *   <li>把 {@code callerOf(ctx)} 改成读 {@code arguments.by} ⇒ {@link
 *       #decideAndItemUseIdentityNotModelSuppliedBy()} 与 {@link
 *       #commitUsesIdentityAndReturnsTheReceipt()} 红；
 *   <li>在概览档输出 {@code item.args()} 或 {@code item.summary()} ⇒ {@link
 *       #getDefaultsToOverviewAndFullAudits()} 与 {@link
 *       #listIsOverviewOnlyAndDeduplicatesInFlightIds()} 的明文断言红；
 *   <li>删掉 {@code full} 分支里的 audit 调用 ⇒ {@link #getDefaultsToOverviewAndFullAudits()} 的审计断言红；
 *   <li>把写工具改调 {@code packages.decide(...)} / {@code packages.commit(...)} ⇒ 录制提交口的调用断言红；
 *   <li>删掉在途回退 ⇒ {@link #inFlightPackagesAreReadAndWrittenThroughTheirOwnPorts()} 红；
 *   <li>把未知包伪装成成功，或把 runtime 异常消息原样透出 ⇒ {@link #unknownPackagesNeverReachTheWritePort()} 与 {@link
 *       #portFailuresAreMappedWithoutLeakingText()} 红。
 * </ul>
 */
class DecisionPackageToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long DEADLINE = System.currentTimeMillis() + 3_600_000L;
  private static final String SECRET = "TOKEN-DO-NOT-LEAK-7f3a";
  private static final String PHYSICAL_PATH = "/home/cna/ProjectMosire/.work/do-not-print.md";
  private static final String APPROVER_ROLE = "approver";
  private static final AgentIdentity APPROVER_IDENTITY =
      new AgentIdentity("reviewer-7", CommandMode.FULL, "", 0);

  @Test
  void emptyApproverRolesIsRejectedAndAllFiveToolsCarryRolesAndFrozenSpecs() {
    DecisionPackages packages = new DecisionPackages();
    RecordingSubmissionPort port = new RecordingSubmissionPort(proposedPackage("unused", 1));

    assertThatThrownBy(() -> new DecisionPackageTools(packages, port, Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("approverRoles");
    assertThatThrownBy(() -> new DecisionPackageTools(packages, port, Set.of("gm", " ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("空白");

    DecisionPackageTools facade =
        new DecisionPackageTools(packages, port, Set.of(APPROVER_ROLE, "auditor"));
    List<AgentTool> tools = facade.tools();

    assertThat(tools)
        .extracting(AgentTool::name)
        .containsExactly(
            DecisionPackageTools.LIST_NAME,
            DecisionPackageTools.GET_NAME,
            DecisionPackageTools.DECIDE_NAME,
            DecisionPackageTools.DECIDE_ITEM_NAME,
            DecisionPackageTools.COMMIT_NAME);
    assertThat(facade.all()).isSameAs(tools);
    assertThat(tools)
        .allSatisfy(
            tool -> assertThat(tool.roles()).containsExactlyInAnyOrder(APPROVER_ROLE, "auditor"));

    Map<String, AgentTool> byName = new LinkedHashMap<>();
    for (AgentTool tool : tools) {
      byName.put(tool.name(), tool);
    }
    assertSpec(byName.get(DecisionPackageTools.LIST_NAME), true, false);
    assertSpec(byName.get(DecisionPackageTools.GET_NAME), true, false);
    assertSpec(byName.get(DecisionPackageTools.DECIDE_NAME), true, false);
    assertSpec(byName.get(DecisionPackageTools.DECIDE_ITEM_NAME), true, false);
    assertSpec(byName.get(DecisionPackageTools.COMMIT_NAME), true, true);
    assertThat(facade.listTool()).isSameAs(byName.get(DecisionPackageTools.LIST_NAME));
    assertThat(facade.getTool()).isSameAs(byName.get(DecisionPackageTools.GET_NAME));
    assertThat(facade.decideTool()).isSameAs(byName.get(DecisionPackageTools.DECIDE_NAME));
    assertThat(facade.decideItemTool()).isSameAs(byName.get(DecisionPackageTools.DECIDE_ITEM_NAME));
    assertThat(facade.commitTool()).isSameAs(byName.get(DecisionPackageTools.COMMIT_NAME));
  }

  @Test
  void listIsOverviewOnlyAndDeduplicatesInFlightIds() throws Exception {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage pending = propose(packages, "proposer-pending", 1);
    DecisionPackage awaiting = propose(packages, "proposer-awaiting", 1);
    packages.decide(awaiting.id(), new Decision(Set.of(0), "setup", ""));
    DecisionPackage expired = propose(packages, "proposer-expired", 1);
    packages.expireNow(expired.id(), System.currentTimeMillis());

    FakeInFlight inFlight = new FakeInFlight();
    inFlight.add(copyWithState(pending, PackageState.PROPOSED, null));
    inFlight.add(inFlightPackage("xp-pending", PackageState.PROPOSED, 1, null));
    inFlight.add(
        inFlightPackage(
            "xp-deciding", PackageState.DECIDING, 1, new Decision(Set.of(0), "setup", "")));
    inFlight.add(
        inFlightPackage(
            "xp-awaiting", PackageState.DECIDED, 1, new Decision(Set.of(0), "setup", "")));

    DecisionPackageTools facade =
        new DecisionPackageTools(
            packages,
            new RecordingSubmissionPort(pending),
            Set.of(APPROVER_ROLE),
            inFlight,
            null,
            null);

    ToolResult result = facade.listTool().execute(context(Map.of()));
    assertThat(result.success()).isTrue();
    JsonNode body = JSON.readTree(result.message());

    assertThat(ids(body.get("pending")))
        .as("待决档只含 PROPOSED/DECIDING；在途与主表同 id 时只保留一条")
        .containsExactly(pending.id(), "xp-pending", "xp-deciding");
    assertThat(ids(body.get("awaitingCommit")))
        .as("已批未执行只收 DECIDED；COMMITTED/EXPIRED 不出现")
        .containsExactly(awaiting.id(), "xp-awaiting");
    assertThat(result.message())
        .as("列表是概览面：参数值、参数派生 summary 与物理路径都不出现")
        .doesNotContain(SECRET)
        .doesNotContain(PHYSICAL_PATH)
        .doesNotContain("\"args\"")
        .doesNotContain("\"summary\"");
  }

  @Test
  void getDefaultsToOverviewAndFullAudits() throws Exception {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage pkg = propose(packages, "proposer-get", 2);
    RecordingSubmissionPort port = new RecordingSubmissionPort(pkg);
    RecordingReadAudit audit = new RecordingReadAudit();
    DecisionPackageTools facade =
        new DecisionPackageTools(
            packages, port, Set.of(APPROVER_ROLE), new FakeInFlight(), null, audit);

    ToolResult overview =
        facade
            .getTool()
            .execute(context(Map.of("packageId", pkg.id(), "full", false, "by", "forged-model")));
    assertThat(overview.success()).isTrue();
    assertThat(overview.message())
        .doesNotContain(SECRET)
        .doesNotContain(PHYSICAL_PATH)
        .doesNotContain("\"args\"")
        .doesNotContain("\"summary\"");
    assertThat(audit.calls).as("概览读取不落明文审计").isEmpty();

    ToolResult full =
        facade
            .getTool()
            .execute(context(Map.of("packageId", pkg.id(), "full", true, "by", "forged-model")));
    assertThat(full.success()).isTrue();
    JsonNode body = JSON.readTree(full.message());
    assertThat(body.at("/items/0/args/secret").asText()).as("full=true 才给参数明文").isEqualTo(SECRET);
    assertThat(full.message())
        .as("summary 由参数派生，不是 full 的 args；任何档位都不输出")
        .doesNotContain(PHYSICAL_PATH)
        .doesNotContain("\"summary\"");
    assertThat(audit.calls)
        .as("每次 full 读取都报 packageId + 实际返回的 index + ToolContext.identity().instanceId()")
        .containsExactly(new AuditCall(pkg.id(), List.of(0, 1), APPROVER_IDENTITY.instanceId()));

    audit.explode = true;
    ToolResult fullDespiteAuditFailure =
        facade.getTool().execute(context(Map.of("packageId", pkg.id(), "full", true)));
    assertThat(fullDespiteAuditFailure.success()).as("审计面故障不得把一次正常读取变成工具失败").isTrue();
    assertThat(fullDespiteAuditFailure.message()).contains(SECRET);

    DecisionPackageTools noAudit =
        new DecisionPackageTools(
            packages, port, Set.of(APPROVER_ROLE), new FakeInFlight(), null, null);
    ToolResult fullWithoutAuditSeam =
        noAudit.getTool().execute(context(Map.of("packageId", pkg.id(), "full", true)));
    assertThat(fullWithoutAuditSeam.success()).isTrue();
    assertThat(fullWithoutAuditSeam.message()).contains(SECRET);
  }

  @Test
  void decideAndItemUseIdentityNotModelSuppliedBy() throws Exception {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage pkg = propose(packages, "proposer-write", 2);
    RecordingSubmissionPort port = new RecordingSubmissionPort(pkg);
    DecisionPackageTools facade = new DecisionPackageTools(packages, port, Set.of(APPROVER_ROLE));

    ToolResult decide =
        facade
            .decideTool()
            .execute(
                context(
                    Map.of(
                        "packageId",
                        pkg.id(),
                        "approvedIndexes",
                        List.of(0),
                        "note",
                        "ok",
                        "by",
                        "forged-model")));
    assertThat(decide.success()).isTrue();
    assertThat(port.decisions).hasSize(1);
    Decision decision = port.decisions.get(0);
    assertThat(decision.approvedIndexes()).containsExactly(0);
    assertThat(decision.by()).as("by 只来自 ToolContext.identity()").isEqualTo("reviewer-7");
    assertThat(decision.note()).isEqualTo("ok");
    assertThat(decide.message()).contains("\"approved\":[0]").contains("\"rejected\":[1]");

    ToolResult item =
        facade
            .decideItemTool()
            .execute(
                context(
                    Map.of(
                        "packageId",
                        pkg.id(),
                        "index",
                        1,
                        "verdict",
                        "reject",
                        "note",
                        "keep it",
                        "by",
                        "forged-model")));
    assertThat(item.success()).isTrue();
    assertThat(port.itemCalls).hasSize(1);
    ItemCall call = port.itemCalls.get(0);
    assertThat(call.packageId()).isEqualTo(pkg.id());
    assertThat(call.index()).isEqualTo(1);
    assertThat(call.approve()).isFalse();
    assertThat(call.by()).isEqualTo("reviewer-7");
    assertThat(call.note()).isEqualTo("keep it");
  }

  @Test
  void decideWithoutApprovedIndexesAndEmptyArrayBothRejectEverything() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage absent = propose(packages, "proposer-absent", 2);
    DecisionPackage empty = propose(packages, "proposer-empty", 2);
    RecordingSubmissionPort port = new RecordingSubmissionPort(absent);
    DecisionPackageTools facade = new DecisionPackageTools(packages, port, Set.of(APPROVER_ROLE));

    assertThat(facade.decideTool().execute(context(Map.of("packageId", absent.id()))).success())
        .isTrue();
    assertThat(
            facade
                .decideTool()
                .execute(context(Map.of("packageId", empty.id(), "approvedIndexes", List.of())))
                .success())
        .isTrue();

    assertThat(port.decisions).hasSize(2);
    assertThat(port.decisions.get(0).approvedIndexes()).isEmpty();
    assertThat(port.decisions.get(1).approvedIndexes()).isEmpty();
  }

  @Test
  void invalidVerdictAndUnknownIndexFailBeforeThePort() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage pkg = propose(packages, "proposer-bad-index", 1);
    RecordingSubmissionPort port = new RecordingSubmissionPort(pkg);
    DecisionPackageTools facade = new DecisionPackageTools(packages, port, Set.of(APPROVER_ROLE));

    ToolResult badVerdict =
        facade
            .decideItemTool()
            .execute(context(Map.of("packageId", pkg.id(), "index", 0, "verdict", "maybe")));
    assertThat(badVerdict.success()).isFalse();
    assertThat(badVerdict.code()).isEqualTo(DecisionPackageTools.INVALID_ARGUMENTS);
    assertThat(badVerdict.message()).doesNotContain("maybe");
    assertThat(port.itemCalls).isEmpty();

    ToolResult unknownIndex =
        facade
            .decideItemTool()
            .execute(context(Map.of("packageId", pkg.id(), "index", 99, "verdict", "approve")));
    assertThat(unknownIndex.success()).isFalse();
    assertThat(unknownIndex.code()).isEqualTo(DecisionPackages.INVALID_ITEM_INDEX);
    assertThat(port.itemCalls).isEmpty();

    ToolResult unknownApproval =
        facade
            .decideTool()
            .execute(context(Map.of("packageId", pkg.id(), "approvedIndexes", List.of(99))));
    assertThat(unknownApproval.success()).isFalse();
    assertThat(unknownApproval.code()).isEqualTo(DecisionPackages.INVALID_DECISION);
    assertThat(port.decisions).isEmpty();
  }

  @Test
  void commitUsesIdentityAndReturnsTheReceipt() throws Exception {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage pkg = propose(packages, "proposer-commit", 2);
    RecordingSubmissionPort port = new RecordingSubmissionPort(pkg);
    DecisionPackageTools facade = new DecisionPackageTools(packages, port, Set.of(APPROVER_ROLE));

    ToolResult result =
        facade.commitTool().execute(context(Map.of("packageId", pkg.id(), "by", "forged-model")));
    assertThat(result.success()).isTrue();
    assertThat(port.commitCalls)
        .containsExactly(new CommitCall(pkg.id(), APPROVER_IDENTITY.instanceId()));
    JsonNode body = JSON.readTree(result.message());
    assertThat(body.get("step").asText()).isEqualTo("commit");
    assertThat(body.at("/commit/status").asText()).isEqualTo("COMMITTED");
    assertThat(body.at("/commit/revisionRef").asText()).isEqualTo("rev-" + pkg.id());
  }

  @Test
  void inFlightPackagesAreReadAndWrittenThroughTheirOwnPorts() throws Exception {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage xp = inFlightPackage("xp-1", PackageState.PROPOSED, 2, null);
    FakeInFlight inFlight = new FakeInFlight();
    inFlight.add(xp);
    RecordingSubmissionPort mainPort = new RecordingSubmissionPort(xp);
    FakeInFlightActions actions = new FakeInFlightActions(xp);
    RecordingReadAudit audit = new RecordingReadAudit();
    DecisionPackageTools facade =
        new DecisionPackageTools(
            packages, mainPort, Set.of(APPROVER_ROLE), inFlight, actions, audit);

    ToolResult full = facade.getTool().execute(context(Map.of("packageId", xp.id(), "full", true)));
    assertThat(full.success()).isTrue();
    assertThat(JSON.readTree(full.message()).at("/items/0/args/secret").asText()).isEqualTo(SECRET);
    assertThat(audit.calls).containsExactly(new AuditCall(xp.id(), List.of(0, 1), "reviewer-7"));

    ToolResult overview = facade.getTool().execute(context(Map.of("packageId", xp.id())));
    assertThat(overview.success()).isTrue();
    assertThat(overview.message()).doesNotContain(SECRET).doesNotContain(PHYSICAL_PATH);

    ToolResult item =
        facade
            .decideItemTool()
            .execute(context(Map.of("packageId", xp.id(), "index", 1, "verdict", "approve")));
    assertThat(item.success()).isTrue();
    assertThat(actions.itemCalls).containsExactly(new ItemCall(xp.id(), 1, true, "reviewer-7", ""));
    assertThat(mainPort.itemCalls).as("在途包不得绕过在途写口走主包表").isEmpty();

    ToolResult whole =
        facade
            .decideTool()
            .execute(context(Map.of("packageId", xp.id(), "approvedIndexes", List.of(1))));
    assertThat(whole.success()).isTrue();
    assertThat(actions.itemCalls)
        .as("在途口没有整包方法：工具按包内顺序逐条表达同一次裁决")
        .containsExactly(
            new ItemCall(xp.id(), 1, true, "reviewer-7", ""),
            new ItemCall(xp.id(), 0, false, "reviewer-7", ""),
            new ItemCall(xp.id(), 1, true, "reviewer-7", ""));
    assertThat(mainPort.decisions).isEmpty();

    ToolResult commit = facade.commitTool().execute(context(Map.of("packageId", xp.id())));
    assertThat(commit.success()).isTrue();
    assertThat(actions.commitCalls).containsExactly(new CommitCall(xp.id(), "reviewer-7"));
    assertThat(mainPort.commitCalls).isEmpty();
  }

  @Test
  void missingInFlightPackagesReturnTheSameUnknownAnswerAndNeverTouchActions() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage template = proposedPackage("template", 1);
    RecordingSubmissionPort mainPort = new RecordingSubmissionPort(template);
    FakeInFlight inFlight = new FakeInFlight();
    FakeInFlightActions actions = new FakeInFlightActions(template);
    DecisionPackageTools facade =
        new DecisionPackageTools(
            packages, mainPort, Set.of(APPROVER_ROLE), inFlight, actions, new RecordingReadAudit());

    ToolResult get =
        facade.getTool().execute(context(Map.of("packageId", "xp-missing", "full", true)));
    assertThat(get.success()).isFalse();
    assertThat(get.code()).isEqualTo(DecisionPackages.PACKAGE_NOT_FOUND);

    ToolResult item =
        facade
            .decideItemTool()
            .execute(context(Map.of("packageId", "xp-missing", "index", 0, "verdict", "approve")));
    assertThat(item.success()).isFalse();
    assertThat(item.code()).isEqualTo(DecisionPackages.PACKAGE_NOT_FOUND);

    ToolResult decide = facade.decideTool().execute(context(Map.of("packageId", "xp-missing")));
    assertThat(decide.success()).isFalse();
    assertThat(decide.code()).isEqualTo(DecisionPackages.PACKAGE_NOT_FOUND);

    ToolResult commit = facade.commitTool().execute(context(Map.of("packageId", "xp-missing")));
    assertThat(commit.success()).isFalse();
    assertThat(commit.code()).isEqualTo(DecisionPackages.PACKAGE_NOT_FOUND);

    assertThat(actions.itemCalls).isEmpty();
    assertThat(actions.commitCalls).isEmpty();
    assertThat(mainPort.decisions).isEmpty();
    assertThat(mainPort.commitCalls).isEmpty();
  }

  @Test
  void unknownPackagesNeverReachTheWritePort() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage template = proposedPackage("template", 1);
    RecordingSubmissionPort port = new RecordingSubmissionPort(template);
    DecisionPackageTools facade = new DecisionPackageTools(packages, port, Set.of(APPROVER_ROLE));

    assertThat(facade.decideTool().execute(context(Map.of("packageId", "nope"))).code())
        .isEqualTo(DecisionPackages.PACKAGE_NOT_FOUND);
    assertThat(
            facade
                .decideItemTool()
                .execute(context(Map.of("packageId", "nope", "index", 0, "verdict", "approve")))
                .code())
        .isEqualTo(DecisionPackages.PACKAGE_NOT_FOUND);
    assertThat(facade.commitTool().execute(context(Map.of("packageId", "nope"))).code())
        .isEqualTo(DecisionPackages.PACKAGE_NOT_FOUND);
    assertThat(port.decisions).isEmpty();
    assertThat(port.itemCalls).isEmpty();
    assertThat(port.commitCalls).isEmpty();
  }

  @Test
  void portFailuresAreMappedWithoutLeakingText() {
    DecisionPackages packages = new DecisionPackages();
    DecisionPackage pkg = propose(packages, "proposer-failure", 1);
    RecordingSubmissionPort port = new RecordingSubmissionPort(pkg);
    DecisionPackageTools facade = new DecisionPackageTools(packages, port, Set.of(APPROVER_ROLE));

    port.failure = new DecisionPackages.RejectionException("PACKAGE_EXPIRED", "包已过期");
    ToolResult named = facade.commitTool().execute(context(Map.of("packageId", pkg.id())));
    assertThat(named.success()).isFalse();
    assertThat(named.code()).isEqualTo("PACKAGE_EXPIRED");
    assertThat(named.message()).isEqualTo("包已过期");

    port.failure = new IllegalStateException("runtime leaked " + SECRET + " " + PHYSICAL_PATH);
    ToolResult runtime = facade.commitTool().execute(context(Map.of("packageId", pkg.id())));
    assertThat(runtime.success()).isFalse();
    assertThat(runtime.code()).isEqualTo(DecisionPackageTools.DECISION_TOOL_FAILED);
    assertThat(runtime.message())
        .doesNotContain(SECRET)
        .doesNotContain(PHYSICAL_PATH)
        .doesNotContain("runtime leaked");

    port.failure = new IllegalArgumentException("argument leaked " + SECRET);
    ToolResult illegal = facade.commitTool().execute(context(Map.of("packageId", pkg.id())));
    assertThat(illegal.success()).isFalse();
    assertThat(illegal.code()).isEqualTo(DecisionPackageTools.INVALID_ARGUMENTS);
    assertThat(illegal.message()).doesNotContain(SECRET);
  }

  private static void assertSpec(AgentTool tool, boolean sensitive, boolean destructive) {
    ToolSpec spec = tool.spec();
    assertThat(spec.requiredLevel()).isEqualTo(AccessToken.DEFAULT);
    assertThat(spec.sensitive()).isTrue();
    assertThat(spec.sensitive()).isEqualTo(sensitive);
    assertThat(spec.destructive()).isEqualTo(destructive);
    assertThat(spec.noExport()).as("决策面必须可作为 MCP 外发工具").isFalse();
  }

  private static ToolContext context(Map<String, Object> arguments) {
    return new ToolContext(
        AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), arguments, APPROVER_IDENTITY);
  }

  private static DecisionPackage propose(DecisionPackages packages, String proposerKey, int count) {
    return packages.propose(
        new ProposeRequest(proposerKey, AccessToken.SYSTEM, secretItems(count), DEADLINE));
  }

  private static DecisionPackage proposedPackage(String id, int count) {
    return packageWithState(id, PackageState.PROPOSED, count, null);
  }

  private static DecisionPackage inFlightPackage(
      String id, PackageState state, int count, Decision decision) {
    return packageWithState(id, state, count, decision);
  }

  private static DecisionPackage packageWithState(
      String id, PackageState state, int count, Decision decision) {
    return new DecisionPackage(
        id,
        "proposer-" + id,
        AccessToken.SYSTEM,
        secretItems(count),
        1_700_000_000_000L,
        DEADLINE,
        state,
        decision);
  }

  private static DecisionPackage copyWithState(
      DecisionPackage pkg, PackageState state, Decision decision) {
    return new DecisionPackage(
        pkg.id(),
        pkg.proposerKey(),
        pkg.caller(),
        pkg.items(),
        pkg.createdAtEpochMs(),
        pkg.deadlineEpochMs(),
        state,
        decision);
  }

  private static List<PackageItem> secretItems(int count) {
    List<PackageItem> items = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      items.add(
          new PackageItem(
              i,
              "tool_" + i,
              Map.of("secret", SECRET, "ordinal", i),
              "digest-" + i,
              "summary " + PHYSICAL_PATH + " #" + i));
    }
    return items;
  }

  private static List<String> ids(JsonNode rows) {
    List<String> ids = new ArrayList<>();
    rows.forEach(row -> ids.add(row.get("id").asText()));
    return ids;
  }

  private static DecisionSubmission submission(
      DecisionPackage base,
      PackageState state,
      Decision decision,
      CommitReceipt receipt,
      String commitNote) {
    DecisionPackage reflected =
        new DecisionPackage(
            base.id(),
            base.proposerKey(),
            base.caller(),
            base.items(),
            base.createdAtEpochMs(),
            base.deadlineEpochMs(),
            state,
            decision);
    return new DecisionSubmission(reflected, receipt, receipt == null ? commitNote : "");
  }

  private static CommitReceipt committedReceipt(String packageId, int count) {
    List<CommitReceipt.ItemOutcome> outcomes = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      outcomes.add(CommitReceipt.ItemOutcome.COMMITTED);
    }
    return new CommitReceipt(CommitReceipt.Status.COMMITTED, "rev-" + packageId, outcomes, "ok");
  }

  private static final class RecordingSubmissionPort implements DecisionSubmissionPort {
    private final DecisionPackage base;
    private final List<Decision> decisions = new ArrayList<>();
    private final List<ItemCall> itemCalls = new ArrayList<>();
    private final List<CommitCall> commitCalls = new ArrayList<>();
    private RuntimeException failure;

    private RecordingSubmissionPort(DecisionPackage base) {
      this.base = Objects.requireNonNull(base, "base");
    }

    @Override
    public DecisionSubmission submitDecision(String packageId, Decision decision) {
      failIfNeeded();
      decisions.add(decision);
      return submission(base, PackageState.DECIDED, decision, null, "表态已回填，尚未执行");
    }

    @Override
    public DecisionSubmission itemDecided(
        String packageId, int index, boolean approve, String by, String note) {
      failIfNeeded();
      itemCalls.add(new ItemCall(packageId, index, approve, by, note));
      Decision decision = new Decision(approve ? Set.of(index) : Set.of(), by, note);
      return submission(base, PackageState.DECIDED, decision, null, "表态已回填，尚未执行");
    }

    @Override
    public DecisionSubmission commitPackage(String packageId, String by) {
      failIfNeeded();
      commitCalls.add(new CommitCall(packageId, by));
      Decision decision =
          base.decision() == null ? new Decision(Set.of(), by, "") : base.decision();
      return submission(
          base,
          PackageState.COMMITTED,
          decision,
          committedReceipt(packageId, base.items().size()),
          "");
    }

    private void failIfNeeded() {
      if (failure != null) {
        throw failure;
      }
    }
  }

  private static final class FakeInFlight implements InFlightDecisionPackages {
    private final Map<String, DecisionPackage> byId = new LinkedHashMap<>();
    private final List<DecisionPackage> listRows = new ArrayList<>();
    private boolean throwOnAccess;

    private void add(DecisionPackage pkg) {
      byId.put(pkg.id(), pkg);
      listRows.add(pkg);
    }

    @Override
    public Optional<DecisionPackage> find(String packageId) {
      return find(packageId, true);
    }

    @Override
    public Optional<DecisionPackage> find(String packageId, boolean full) {
      if (throwOnAccess) {
        throw new IllegalStateException("in-flight leaked " + SECRET + " " + PHYSICAL_PATH);
      }
      DecisionPackage pkg = byId.get(packageId);
      if (pkg == null) {
        return Optional.empty();
      }
      if (full) {
        return Optional.of(pkg);
      }
      List<PackageItem> redacted = new ArrayList<>(pkg.items().size());
      for (PackageItem item : pkg.items()) {
        redacted.add(
            new PackageItem(
                item.index(), item.toolName(), Map.of(), item.argsDigest(), item.summary()));
      }
      return Optional.of(
          new DecisionPackage(
              pkg.id(),
              pkg.proposerKey(),
              pkg.caller(),
              redacted,
              pkg.createdAtEpochMs(),
              pkg.deadlineEpochMs(),
              pkg.state(),
              pkg.decision()));
    }

    @Override
    public List<DecisionPackage> list() {
      if (throwOnAccess) {
        throw new IllegalStateException("in-flight list leaked " + SECRET);
      }
      return List.copyOf(listRows);
    }
  }

  private static final class FakeInFlightActions implements InFlightDecisionActions {
    private final DecisionPackage base;
    private final List<ItemCall> itemCalls = new ArrayList<>();
    private final List<CommitCall> commitCalls = new ArrayList<>();

    private FakeInFlightActions(DecisionPackage base) {
      this.base = Objects.requireNonNull(base, "base");
    }

    @Override
    public DecisionSubmission itemDecided(
        String packageId, int index, boolean approve, String by, String note) {
      itemCalls.add(new ItemCall(packageId, index, approve, by, note));
      Decision decision = new Decision(approve ? Set.of(index) : Set.of(), by, note);
      return submission(base, PackageState.DECIDED, decision, null, "在途表态只回填，不执行");
    }

    @Override
    public DecisionSubmission commitPackage(String packageId, String by) {
      commitCalls.add(new CommitCall(packageId, by));
      Decision decision =
          base.decision() == null ? new Decision(Set.of(), by, "") : base.decision();
      return submission(base, PackageState.DECIDED, decision, null, "在途执行在子体，父侧无回执");
    }

    @Override
    public List<DecisionPackages.ItemVerdict> itemVerdicts(String packageId) {
      return List.of();
    }
  }

  private static final class RecordingReadAudit implements DecisionReadAudit {
    private final List<AuditCall> calls = new ArrayList<>();
    private boolean explode;

    @Override
    public void detailRead(String packageId, List<Integer> indexes, String by) {
      if (explode) {
        throw new IllegalStateException("audit leaked " + SECRET + " " + PHYSICAL_PATH);
      }
      calls.add(new AuditCall(packageId, List.copyOf(indexes), by));
    }
  }

  private record AuditCall(String packageId, List<Integer> indexes, String by) {}

  private record ItemCall(String packageId, int index, boolean approve, String by, String note) {}

  private record CommitCall(String packageId, String by) {}
}
