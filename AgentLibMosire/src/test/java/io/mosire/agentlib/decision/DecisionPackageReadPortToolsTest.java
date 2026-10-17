package io.mosire.agentlib.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link DecisionPackageReadPort} 接入 {@link DecisionPackageTools} 的验收用例。
 *
 * <p><b>为什么单独一组</b>：旧测试证明 {@link DecisionPackages} 作为内存适配器的行为没变；本组证明五工具可以在完全不构造 {@link
 * DecisionPackages} 的情况下，由宿主自己的读口提供包、逐条表态和过期原因。另用固定 JSON 钉住 {@link DecisionPackages.ItemVerdict} →
 * {@link DecisionItemVerdict} 迁移不改变工具输出。
 */
class DecisionPackageReadPortToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String APPROVER_ROLE = "gm";
  private static final String SECRET = "TOKEN-DO-NOT-LEAK-read-port";
  private static final AgentIdentity APPROVER =
      new AgentIdentity("reviewer-7", CommandMode.FULL, "", 0);
  private static final long CREATED_AT = 1_700_000_000_000L;
  private static final long DEADLINE = 1_700_003_600_000L;

  @Test
  void fakeReadPortDrivesListAndGetWithoutDecisionPackages() throws Exception {
    DecisionPackage current =
        packageWithState("pkt-current-1", "dm-1", PackageState.DECIDING, 2, null);
    FakeReadPort port =
        new FakeReadPort(
            List.of(current),
            List.of(current),
            Map.of(current.id(), List.of(new DecisionItemVerdict(0, true, "reviewer-7", "ok"))),
            Map.of(current.id(), "EXPIRED_NO_DECISION"));

    DecisionPackageTools tools =
        new DecisionPackageTools(port, new ExplodingSubmissionPort(), Set.of(APPROVER_ROLE));

    ToolResult listed = tools.listTool().execute(context(Map.of()));
    assertThat(listed.success()).isTrue();
    assertThat(JSON.readTree(listed.message()))
        .as("list 的 JSON 字段和顺序口径不变；pending 只来自读口投影")
        .isEqualTo(
            JSON.readTree(
                """
                {
                  "pending": [ {
                    "id": "pkt-current-1",
                    "proposerKey": "dm-1",
                    "state": "DECIDING",
                    "itemCount": 2,
                    "createdAtEpochMs": 1700000000000,
                    "deadlineEpochMs": 1700003600000,
                    "items": [ {
                      "index": 0,
                      "toolName": "tool_0",
                      "argCount": 2,
                      "digest": "digest-0"
                    }, {
                      "index": 1,
                      "toolName": "tool_1",
                      "argCount": 2,
                      "digest": "digest-1"
                    } ]
                  } ],
                  "awaitingCommit": [ ]
                }
                """));
    assertThat(listed.message()).doesNotContain(SECRET).doesNotContain("\"summary\"");

    ToolResult got =
        tools
            .getTool()
            .execute(context(Map.of("packageId", current.id(), "full", false, "by", "forged")));
    assertThat(got.success()).isTrue();
    assertThat(JSON.readTree(got.message()))
        .as("get 的 JSON 字段逐项不变；verdicts 来自 DecisionItemVerdict")
        .isEqualTo(
            JSON.readTree(
                """
                {
                  "id": "pkt-current-1",
                  "proposerKey": "dm-1",
                  "caller": "SYSTEM",
                  "state": "DECIDING",
                  "itemCount": 2,
                  "createdAtEpochMs": 1700000000000,
                  "deadlineEpochMs": 1700003600000,
                  "decision": null,
                  "verdicts": [ {
                    "index": 0,
                    "verdict": "approve",
                    "by": "reviewer-7",
                    "note": "ok"
                  } ],
                  "undecided": [ 1 ],
                  "expiryReason": "EXPIRED_NO_DECISION",
                  "items": [ {
                    "index": 0,
                    "toolName": "tool_0",
                    "argCount": 2,
                    "digest": "digest-0",
                    "verdict": "approve",
                    "decidedBy": "reviewer-7",
                    "decisionNote": "ok"
                  }, {
                    "index": 1,
                    "toolName": "tool_1",
                    "argCount": 2,
                    "digest": "digest-1",
                    "verdict": null,
                    "decidedBy": null,
                    "decisionNote": null
                  } ]
                }
                """));
    assertThat(got.message()).doesNotContain(SECRET).doesNotContain("\"summary\"");
  }

  @Test
  void findReadsHistoricalPackageOutsideListProjection() throws Exception {
    DecisionPackage current =
        packageWithState("pkt-current-2", "dm-2", PackageState.PROPOSED, 1, null);
    DecisionPackage historical =
        packageWithState(
            "pkt-old-1",
            "dm-1",
            PackageState.COMMITTED,
            2,
            new Decision(Set.of(0), "reviewer-7", "old"));
    Map<String, DecisionPackage> byId = new LinkedHashMap<>();
    byId.put(current.id(), current);
    byId.put(historical.id(), historical);
    FakeReadPort port =
        new FakeReadPort(
            byId,
            List.of(current),
            List.of(current),
            Map.of(
                historical.id(),
                List.of(
                    new DecisionItemVerdict(0, true, "reviewer-7", "old"),
                    new DecisionItemVerdict(1, false, "reviewer-7", "old"))),
            Map.of());
    DecisionPackageTools tools =
        new DecisionPackageTools(port, new ExplodingSubmissionPort(), Set.of(APPROVER_ROLE));

    ToolResult listed = tools.listTool().execute(context(Map.of()));
    assertThat(listed.success()).isTrue();
    assertThat(listed.message())
        .as("list 是宿主投影：当前 tick 不包含历史包")
        .contains("pkt-current-2")
        .doesNotContain("pkt-old-1");

    ToolResult got =
        tools.getTool().execute(context(Map.of("packageId", historical.id(), "full", false)));
    assertThat(got.success()).isTrue();
    JsonNode body = JSON.readTree(got.message());
    assertThat(body.get("id").asText()).isEqualTo("pkt-old-1");
    assertThat(body.get("state").asText()).isEqualTo("COMMITTED");
    assertThat(body.get("verdicts").size()).isEqualTo(2);
  }

  @Test
  void allFactoryAndConstructorFormsAcceptReadPort() {
    DecisionPackage pkg = packageWithState("pkt-forms", "dm-3", PackageState.PROPOSED, 1, null);
    FakeReadPort port = new FakeReadPort(List.of(pkg), List.of(pkg), Map.of(), Map.of());
    ExplodingSubmissionPort submissions = new ExplodingSubmissionPort();
    Set<String> roles = Set.of(APPROVER_ROLE);

    assertThat(new DecisionPackageTools(port, submissions, roles).tools()).hasSize(5);
    assertThat(new DecisionPackageTools(port, submissions, roles, null, null, null).tools())
        .hasSize(5);

    assertThat(DecisionPackageTools.of(port, submissions, roles).tools()).hasSize(5);
    assertThat(DecisionPackageTools.of(port, submissions, roles, null, null, null).tools())
        .hasSize(5);
    assertThat(DecisionPackageTools.all(port, submissions, roles)).hasSize(5);
    assertThat(DecisionPackageTools.all(port, submissions, roles, null, null, null)).hasSize(5);
    assertThat(DecisionPackageTools.tools(port, submissions, roles)).hasSize(5);
  }

  @Test
  void fakeReadPortRoutesWritesThroughSubmissionPort() throws Exception {
    DecisionPackage pkg = packageWithState("pkt-write", "dm-write", PackageState.PROPOSED, 2, null);
    FakeReadPort port = new FakeReadPort(List.of(pkg), List.of(pkg), Map.of(), Map.of());
    RecordingSubmissionPort submissions = new RecordingSubmissionPort(pkg);
    DecisionPackageTools tools = new DecisionPackageTools(port, submissions, Set.of(APPROVER_ROLE));

    ToolResult decided =
        tools
            .decideTool()
            .execute(
                context(
                    Map.of(
                        "packageId", pkg.id(),
                        "approvedIndexes", List.of(0),
                        "by", "forged-model")));
    assertThat(decided.success()).as("message=%s", decided.message()).isTrue();
    assertThat(submissions.decisions).hasSize(1);
    assertThat(submissions.decisions.get(0).by()).isEqualTo("reviewer-7");
    assertThat(submissions.decisions.get(0).approvedIndexes()).containsExactly(0);

    ToolResult item =
        tools
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
                        "by",
                        "forged-model")));
    assertThat(item.success()).isTrue();
    assertThat(submissions.itemCalls).containsExactly("pkt-write:1:false:reviewer-7");

    ToolResult committed =
        tools.commitTool().execute(context(Map.of("packageId", pkg.id(), "by", "forged-model")));
    assertThat(committed.success()).isTrue();
    assertThat(submissions.commitCalls).containsExactly("pkt-write:reviewer-7");
    assertThat(port.findCalls).isGreaterThanOrEqualTo(3);
  }

  @Test
  void legacyItemVerdictConversionKeepsToolJsonStable() throws Exception {
    DecisionPackages packages = new DecisionPackages();
    long deadline = System.currentTimeMillis() + 3_600_000L;
    DecisionPackage pkg =
        packages.propose(
            "pkt-legacy-json",
            new ProposeRequest("dm-legacy", AccessToken.SYSTEM, secretItems(2), deadline));
    packages.itemDecided(pkg.id(), 0, true, "reviewer-7", "ok");
    DecisionPackage after = packages.find(pkg.id()).orElseThrow();

    List<DecisionPackages.ItemVerdict> legacyVerdicts = packages.itemVerdicts(pkg.id());
    assertThat(legacyVerdicts).hasSize(2);
    assertThat(legacyVerdicts.get(0))
        .isEqualTo(new DecisionPackages.ItemVerdict(0, true, "reviewer-7", "ok"));
    assertThat(legacyVerdicts.get(1).index()).isEqualTo(1);
    assertThat(legacyVerdicts.get(1).approved()).isFalse();
    assertThat(legacyVerdicts.get(1).by()).isEqualTo("reviewer-7");

    List<DecisionItemVerdict> protocolVerdicts = packages.verdicts(pkg.id());
    assertThat(protocolVerdicts).as("新读口只换形状，不改变旧适配器的逐条表态内容").hasSize(2);
    assertThat(protocolVerdicts.get(0))
        .isEqualTo(new DecisionItemVerdict(0, true, "reviewer-7", "ok"));
    assertThat(protocolVerdicts.get(1).index()).isEqualTo(1);
    assertThat(protocolVerdicts.get(1).approved()).isFalse();
    assertThat(protocolVerdicts.get(1).by()).isEqualTo("reviewer-7");

    DecisionPackageTools legacy =
        new DecisionPackageTools(packages, new ExplodingSubmissionPort(), Set.of(APPROVER_ROLE));
    DecisionPackageReadPort readPort = packages;
    DecisionPackageTools portTools =
        new DecisionPackageTools(readPort, new ExplodingSubmissionPort(), Set.of(APPROVER_ROLE));

    ToolResult legacyGet =
        legacy.getTool().execute(context(Map.of("packageId", after.id(), "full", false)));
    ToolResult portGet =
        portTools.getTool().execute(context(Map.of("packageId", after.id(), "full", false)));
    assertThat(legacyGet.success()).isTrue();
    assertThat(portGet.success()).isTrue();
    assertThat(JSON.readTree(legacyGet.message()))
        .as("旧构造器与新端口入口对同一包输出同一份工具 JSON")
        .isEqualTo(JSON.readTree(portGet.message()));
  }

  private static ToolContext context(Map<String, Object> arguments) {
    return new ToolContext(
        AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), arguments, APPROVER);
  }

  private static DecisionPackage packageWithState(
      String id, String proposerKey, PackageState state, int count, Decision decision) {
    return new DecisionPackage(
        id,
        proposerKey,
        AccessToken.SYSTEM,
        secretItems(count),
        CREATED_AT,
        DEADLINE,
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
              "summary " + SECRET + " #" + i));
    }
    return items;
  }

  private static final class FakeReadPort implements DecisionPackageReadPort {
    private final Map<String, DecisionPackage> byId = new LinkedHashMap<>();
    private final List<DecisionPackage> pendingRows;
    private final List<DecisionPackage> allRows;
    private final Map<String, List<DecisionItemVerdict>> verdictsById = new LinkedHashMap<>();
    private final Map<String, String> expiryById = new LinkedHashMap<>();
    private int findCalls;

    private FakeReadPort(
        List<DecisionPackage> pendingRows,
        List<DecisionPackage> allRows,
        Map<String, List<DecisionItemVerdict>> verdictsById,
        Map<String, String> expiryById) {
      this(byIdFrom(pendingRows, allRows), pendingRows, allRows, verdictsById, expiryById);
    }

    private FakeReadPort(
        Map<String, DecisionPackage> findById,
        List<DecisionPackage> pendingRows,
        List<DecisionPackage> allRows,
        Map<String, List<DecisionItemVerdict>> verdictsById,
        Map<String, String> expiryById) {
      this.pendingRows = List.copyOf(pendingRows);
      this.allRows = List.copyOf(allRows);
      byId.putAll(findById);
      this.verdictsById.putAll(verdictsById);
      this.expiryById.putAll(expiryById);
    }

    private static Map<String, DecisionPackage> byIdFrom(
        List<DecisionPackage> pendingRows, List<DecisionPackage> allRows) {
      Map<String, DecisionPackage> rows = new LinkedHashMap<>();
      for (DecisionPackage pkg : allRows) {
        rows.put(pkg.id(), pkg);
      }
      for (DecisionPackage pkg : pendingRows) {
        rows.put(pkg.id(), pkg);
      }
      return rows;
    }

    @Override
    public Optional<DecisionPackage> find(String packageId) {
      findCalls++;
      return Optional.ofNullable(byId.get(packageId));
    }

    @Override
    public List<DecisionPackage> pending() {
      return pendingRows;
    }

    @Override
    public List<DecisionPackage> all() {
      return allRows;
    }

    @Override
    public List<DecisionItemVerdict> verdicts(String packageId) {
      return List.copyOf(verdictsById.getOrDefault(packageId, List.of()));
    }

    @Override
    public Optional<String> expiryReason(String packageId) {
      return Optional.ofNullable(expiryById.get(packageId));
    }
  }

  private static final class RecordingSubmissionPort implements DecisionSubmissionPort {
    private final DecisionPackage base;
    private final List<Decision> decisions = new ArrayList<>();
    private final List<String> itemCalls = new ArrayList<>();
    private final List<String> commitCalls = new ArrayList<>();

    private RecordingSubmissionPort(DecisionPackage base) {
      this.base = base;
    }

    @Override
    public DecisionSubmission submitDecision(String packageId, Decision decision) {
      decisions.add(decision);
      return new DecisionSubmission(
          reflect(base, PackageState.DECIDED, decision), null, "not executed");
    }

    @Override
    public DecisionSubmission itemDecided(
        String packageId, int index, boolean approve, String by, String note) {
      itemCalls.add(packageId + ":" + index + ":" + approve + ":" + by);
      Decision decision = new Decision(approve ? Set.of(index) : Set.of(), by, note);
      return new DecisionSubmission(
          reflect(base, PackageState.DECIDED, decision), null, "not executed");
    }

    @Override
    public DecisionSubmission commitPackage(String packageId, String by) {
      commitCalls.add(packageId + ":" + by);
      Decision decision = new Decision(Set.of(0, 1), by, "");
      return new DecisionSubmission(
          reflect(base, PackageState.DECIDED, decision), null, "not executed");
    }

    private static DecisionPackage reflect(
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
  }

  private static final class ExplodingSubmissionPort implements DecisionSubmissionPort {

    @Override
    public DecisionSubmission submitDecision(String packageId, Decision decision) {
      throw new AssertionError("读工具不得触碰写路径");
    }

    @Override
    public DecisionSubmission itemDecided(
        String packageId, int index, boolean approve, String by, String note) {
      throw new AssertionError("读工具不得触碰写路径");
    }

    @Override
    public DecisionSubmission commitPackage(String packageId, String by) {
      throw new AssertionError("读工具不得触碰写路径");
    }
  }
}
