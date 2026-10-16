package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.decision.DecisionTurnScope;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.CommandMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>判据 10（P3c 验收面）：码不靠文案</b>——子体识别"这条被父侧记下了"必须靠具名码 {@link
 * ToolCallAuthorizer#DECISION_QUEUED}，<b>不得</b>匹配那句中文文案（设计 §5sexies④："文案改一个字子体就瞎了"）。
 *
 * <p><b>它钉住两个方向</b>（缺一不可）：
 *
 * <ol>
 *   <li>{@link #c10a_theChildRegistersByCodeEvenWhenTheMessageIsEmpty()}：码对、<b>文案完全不同（这里是空串）</b> ⇒
 *       仍然登记。改文案（哪怕改成空）不影响任何判定；
 *   <li>{@link #c10b_theChildDoesNotRegisterWhenOnlyTheTextLooksRight()}：文案看着对（含父侧那句"已记入本轮决策包…"）、
 *       码不对 ⇒ <b>不</b>登记。若把判据改成匹配文案，本条当场红。
 * </ol>
 *
 * <p>★ 真进程侧的对应证据见 {@code MainMosire} 的 {@code CrossProcessDecisionAcceptanceE2ETest}：把生产文案改掉之后 E2E
 * 仍绿 （登记链只读码）。
 */
class DecisionQueuedCodeOverTextAcceptanceTest {

  private static final String TOOL = "sensitive_probe";

  /** 生产 {@code queuedByParent} 那句文案的开头（"文案看着对"的那一半用它；判据不该是它）。 */
  private static final String PRODUCTION_LOOKING_TEXT =
      "已记入本轮决策包（尚未裁决，本轮不会执行）: tool=sensitive_probe class=probe:ask";

  @AfterEach
  void tearDown() {
    DecisionTurnScope.clear();
  }

  @Test
  void c10a_theChildRegistersByCodeEvenWhenTheMessageIsEmpty() {
    PendingDecisionQueue queue = new PendingDecisionQueue("prober-1", AccessToken.DEFAULT);
    DecisionTurnScope.bind(queue);
    ToolRegistry registry = new ToolRegistry();
    // ★ 文案被"改掉"（这里是彻底改空）：子体仍必须认得——判据钉在码上
    registry.register(resultTool(ToolResult.error(ToolCallAuthorizer.DECISION_QUEUED, "")));

    ToolResult result =
        ToolCallAuthorizer.standard().execute(registry, TOOL, childContext(Map.of("text", "一")));

    assertThat(result.code()).isEqualTo(ToolCallAuthorizer.DECISION_QUEUED);
    List<PendingDecisionQueue.PendingEntry> entries = queue.snapshot();
    assertThat(entries).as("★ 文案为空也必须登记：判据是码（改文案不能让子体瞎）").hasSize(1);
    assertThat(entries.get(0).toolName()).isEqualTo(TOOL);
    assertThat(entries.get(0).args()).as("登记的是本侧收到的那份参数（原样）").isEqualTo(Map.of("text", "一"));
  }

  @Test
  void c10b_theChildDoesNotRegisterWhenOnlyTheTextLooksRight() {
    PendingDecisionQueue queue = new PendingDecisionQueue("prober-1", AccessToken.DEFAULT);
    DecisionTurnScope.bind(queue);
    ToolRegistry registry = new ToolRegistry();
    // ★ 文案与父侧那句一模一样，但码不是 DECISION_QUEUED ⇒ 不许登记（"看着像"不等于"认得"）
    registry.register(resultTool(ToolResult.error("SOME_OTHER_CODE", PRODUCTION_LOOKING_TEXT)));

    ToolResult result =
        ToolCallAuthorizer.standard().execute(registry, TOOL, childContext(Map.of("text", "一")));

    assertThat(result.code()).isEqualTo("SOME_OTHER_CODE");
    assertThat(queue.snapshot()).as("★ 只认码：文案撞对了、码不对 ⇒ 一条都不许登记").isEmpty();
  }

  // ---------- 夹具 ----------

  /** 直接回给定结果的工具（闸位缺省 ALLOW ⇒ 走到 execute ⇒ 走子体侧认码那条路）。 */
  private static AgentTool resultTool(ToolResult result) {
    return new AgentTool() {
      @Override
      public String name() {
        return TOOL;
      }

      @Override
      public String description() {
        return "子体侧认码用例的桩工具";
      }

      @Override
      public Map<String, Object> jsonSchema() {
        return Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")));
      }

      @Override
      public ToolResult execute(ToolContext context) {
        return result;
      }
    };
  }

  private static ToolContext childContext(Map<String, Object> args) {
    return new ToolContext(
        AccessToken.DEFAULT,
        AgentPermissionSet.system(),
        Map.of(),
        args,
        AgentIdentity.subagent("prober-1", CommandMode.LIMITED, "goal", 1));
  }
}
