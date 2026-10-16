package io.mosire.brain.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.decision.DecisionEventTypes;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.event.EventWrite;
import io.mosire.agentlib.event.SqliteEventStore;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "查自己的决策历史"工具的判据（设计 §5 裁定 5 的底线 + 裁定 3 的归属口径）：只读自己的 {@code events.db}、按 {@code proposerKey}
 * 只列自己的包、逐条去向 + 可空理由、无存在性 oracle、出参不含参数明文。
 *
 * <p><b>判别性</b>：把 {@code proposerKey} 过滤去掉 ⇒ {@link #onlyOwnPackagesAreVisible()} 当场红（别人的包会冒出来）；
 * 把"归属不符"改成"报错 / 报存在" ⇒ {@link #otherPeoplesPackageLooksExactlyLikeAnUnknownOne()} 当场红。
 */
class DecisionHistoryToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SELF = "main";

  @TempDir Path tempDir;

  private Path db;

  @BeforeEach
  void setUp() {
    db = tempDir.resolve("events.db");
  }

  /** 决策包事件的载荷骨架（顺序即真实装配顺序的一部分；不含任何明文参数）。 */
  private static Map<String, Object> payload(
      String packageId, String proposerKey, int itemCount, List<String> tools) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("packageId", packageId);
    if (proposerKey != null) {
      payload.put("proposerKey", proposerKey);
    }
    payload.put("itemCount", itemCount);
    payload.put("approvedCount", 0);
    payload.put("rejectedCount", 0);
    payload.put("toolNames", tools);
    payload.put("digests", List.of("digest-0", "digest-1"));
    payload.put("indexes", List.of(0, 1).subList(0, itemCount));
    return payload;
  }

  private static Map<String, Object> trail(
      List<Integer> escalated, List<Integer> finalApproved, String finalizedBy) {
    Map<String, Object> level = new LinkedHashMap<>();
    level.put("decider", "main");
    level.put("mode", "FULL");
    level.put("approved", finalApproved);
    level.put("rejected", List.of());
    level.put("reason", null);
    Map<String, Object> trail = new LinkedHashMap<>();
    trail.put("levels", List.of(level));
    trail.put("escalatedToHuman", escalated);
    trail.put("finalApproved", finalApproved);
    trail.put("finalizedBy", finalizedBy);
    return trail;
  }

  private void append(String type, String packageId, Map<String, Object> payload) {
    try (SqliteEventStore store = SqliteEventStore.open(db)) {
      store.append(EventWrite.of(type, "main", JSON.writeValueAsString(payload), packageId));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  /** 写四个包：自己的（正常 / 全驳 / 作废）、别人的、以及没有归属键的旧事件。 */
  private void seed() {
    // dp-1：自己的包，一条落定、一条没落定（带理由）
    Map<String, Object> proposed = payload("dp-1", SELF, 2, List.of("write_doc", "bash"));
    append(DecisionEventTypes.PROPOSED, "dp-1", proposed);
    Map<String, Object> decided = payload("dp-1", SELF, 2, List.of("write_doc", "bash"));
    decided.put("approvedCount", 1);
    decided.put("rejectedCount", 1);
    decided.put("trail", trail(List.of(1), List.of(0), DecisionCascade.HUMAN_DECIDER));
    append(DecisionEventTypes.DECIDED, "dp-1", decided);
    Map<String, Object> committed = payload("dp-1", SELF, 2, List.of("write_doc", "bash"));
    committed.put("approvedCount", 1);
    committed.put("rejectedCount", 1);
    committed.put("revisionRef", "batch:dp-1");
    committed.put(
        "outcomes",
        List.of(
            outcome(0, "write_doc", "COMMITTED", null),
            outcome(1, "bash", "NOT_COMMITTED", "RESOURCE_DENIED 够不着")));
    append(DecisionEventTypes.COMMITTED, "dp-1", committed);

    // dp-2：别人的包（本工具不该看见它）
    append(DecisionEventTypes.PROPOSED, "dp-2", payload("dp-2", "sub-7", 1, List.of("bash")));
    Map<String, Object> otherDecided = payload("dp-2", "sub-7", 1, List.of("bash"));
    otherDecided.put("trail", trail(List.of(0), List.of(), "human"));
    append(DecisionEventTypes.DECIDED, "dp-2", otherDecided);

    // dp-3：自己的包，整包被驳（零落盘：没有 committed 事件）
    append(DecisionEventTypes.PROPOSED, "dp-3", payload("dp-3", SELF, 1, List.of("write_doc")));
    Map<String, Object> rejected = payload("dp-3", SELF, 1, List.of("write_doc"));
    rejected.put("trail", trail(List.of(0), List.of(), DecisionCascade.HUMAN_DECIDER));
    append(DecisionEventTypes.DECIDED, "dp-3", rejected);

    // dp-4：没有归属键的旧事件（本单之前的包）：归属读不出 ⇒ 不出现
    append(DecisionEventTypes.PROPOSED, "dp-4", payload("dp-4", null, 1, List.of("bash")));

    // dp-5：自己的包，作废（原因随载荷）
    append(DecisionEventTypes.PROPOSED, "dp-5", payload("dp-5", SELF, 1, List.of("bash")));
    Map<String, Object> expired = payload("dp-5", SELF, 1, List.of("bash"));
    expired.put("reason", "审批窗口到期而无人答复");
    append(DecisionEventTypes.EXPIRED, "dp-5", expired);
  }

  private static Map<String, Object> outcome(
      int index, String tool, String outcome, String reason) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("index", index);
    row.put("toolName", tool);
    row.put("outcome", outcome);
    row.put("reason", reason);
    return row;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Map<String, Object> body) {
    return (List<Map<String, Object>>) body.get("packages");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> rowOf(Map<String, Object> body, String packageId) {
    for (Map<String, Object> row : rows(body)) {
      if (packageId.equals(row.get("packageId"))) {
        return row;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(Map<String, Object> row) {
    return (List<Map<String, Object>>) row.get("items");
  }

  @Test
  void onlyOwnPackagesAreVisible() throws Exception {
    seed();

    Map<String, Object> body = DecisionHistoryTools.read(db, SELF, null, 20);

    assertThat(body.get("self")).isEqualTo(SELF);
    assertThat(rows(body))
        .extracting(row -> row.get("packageId"))
        .as("只列自己的包：别人的（dp-2）与没有归属键的旧事件（dp-4）都不出现")
        .containsExactly("dp-5", "dp-3", "dp-1");
    assertThat(body.toString()).doesNotContain("sub-7");
  }

  @Test
  void perItemOutcomesAndReasonsComeFromTheCommittedEvent() throws Exception {
    seed();

    Map<String, Object> row = rowOf(DecisionHistoryTools.read(db, SELF, null, 20), "dp-1");

    assertThat(row.get("state")).isEqualTo("COMMITTED");
    assertThat(row.get("finalizedBy")).as("裁决者来自轨迹").isEqualTo(DecisionCascade.HUMAN_DECIDER);
    assertThat(row.get("escalatedToHuman")).isEqualTo(List.of(1));
    List<Map<String, Object>> items = items(row);
    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("index")).isEqualTo(0);
    assertThat(items.get(0).get("tool")).isEqualTo("write_doc");
    assertThat(items.get(0).get("outcome")).isEqualTo("COMMITTED");
    assertThat(items.get(0).get("reason")).as("成功条目的理由 = null（未说明理由）").isNull();
    assertThat(items.get(1).get("outcome")).isEqualTo("NOT_COMMITTED");
    assertThat(items.get(1).get("reason")).isEqualTo("RESOURCE_DENIED 够不着");
    assertThat(row.toString()).as("事件面本来就没有明文，出参更不该有").doesNotContain("args");
  }

  @Test
  void rejectedPackageReportsThePackageLayerReason() throws Exception {
    seed();

    Map<String, Object> row = rowOf(DecisionHistoryTools.read(db, SELF, null, 20), "dp-3");

    assertThat(row.get("state")).as("一条都没批 ⇒ 零落盘终局").isEqualTo("REJECTED");
    assertThat(items(row).get(0).get("outcome")).isEqualTo("REJECTED");
    assertThat(items(row).get(0).get("reason"))
        .as("被驳条目的理由由包层填（同一条口径）")
        .isEqualTo(DecisionPackages.REJECTED_ITEM_REASON);
  }

  @Test
  void expiredPackageIsReadableWithItsReason() throws Exception {
    seed();

    Map<String, Object> row = rowOf(DecisionHistoryTools.read(db, SELF, null, 20), "dp-5");

    assertThat(row.get("state")).as("作废也是结果，也必须可回读（裁定 8）").isEqualTo("EXPIRED");
    assertThat(items(row).get(0).get("outcome")).isEqualTo("NOT_COMMITTED");
    assertThat(String.valueOf(items(row).get(0).get("reason"))).contains("审批窗口到期而无人答复");
  }

  @Test
  void otherPeoplesPackageLooksExactlyLikeAnUnknownOne() throws Exception {
    seed();

    Map<String, Object> others = DecisionHistoryTools.read(db, SELF, "dp-2", 20);
    Map<String, Object> unknown = DecisionHistoryTools.read(db, SELF, "dp-404", 20);

    assertThat(rows(others)).as("查别人 = 查不到（无存在性 oracle）").isEmpty();
    assertThat(JSON.writeValueAsString(others))
        .as("两种答复逐字相同：从响应里分不出'存在但不是你的'与'不存在'")
        .isEqualTo(JSON.writeValueAsString(unknown));
  }

  @Test
  void singlePackageQueryReturnsThatPackage() throws Exception {
    seed();

    Map<String, Object> body = DecisionHistoryTools.read(db, SELF, "dp-1", 20);

    assertThat(rows(body)).hasSize(1);
    assertThat(rows(body).get(0).get("packageId")).isEqualTo("dp-1");
  }

  @Test
  void toolReadsThroughTheInjectedConfigAndRefusesIdentitylessCallers() throws Exception {
    seed();
    AgentPermissionSet permissions = AgentPermissionSet.system();
    ToolContext context =
        new ToolContext(
            AccessToken.SYSTEM,
            permissions,
            Map.of(DecisionHistoryTools.CONFIG_SELF_EVENTS_DB, db.toString()),
            Map.of(),
            new AgentIdentity(SELF, CommandMode.FULL, "", 0));

    ToolResult result = DecisionHistoryTools.tool().execute(context);

    assertThat(result.success()).isTrue();
    assertThat(result.message()).contains("dp-1").contains("dp-5");

    ToolContext anonymous =
        new ToolContext(
            AccessToken.SYSTEM,
            permissions,
            Map.of(DecisionHistoryTools.CONFIG_SELF_EVENTS_DB, db.toString()),
            Map.of(),
            AgentIdentity.UNKNOWN);
    ToolResult refused = DecisionHistoryTools.tool().execute(anonymous);
    assertThat(refused.success()).as("身份没穿透 ⇒ fail-closed 拒，不做无归属读取").isFalse();
    assertThat(refused.code()).isEqualTo("NO_IDENTITY");
  }
}
