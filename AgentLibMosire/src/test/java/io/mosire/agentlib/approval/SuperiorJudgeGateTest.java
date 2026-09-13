package io.mosire.agentlib.approval;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.CommandMode;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@link SuperiorJudgeGate} 的<b>分流</b>契约（S6）——判定实现用桩，全程离线。
 *
 * <p>判别力来源：桩判定器<b>计数</b>。"该判"的用例断言计数为 1，"不该判"的用例断言计数为 0 且返回 {@code empty}
 * ——只看返回值的话，"敏感请求被本级判了"与"本级没判、落到人"两种实现在链上表现相同（都到不了人之前的那一跳）， 计数才分得开。真实链路的证据在真 LLM
 * 使用模式测试里（上级判定放行/敏感到人两例）。
 *
 * <p>另一条重点是 <b>fail-closed 的三种失败</b>（拒/空/抛）都落到 {@link ApprovalDecision#DENY}：判定面挂了
 * 还去烦人是错的（那是本功能要免掉的事），但静默放行更错——按拒办。
 */
class SuperiorJudgeGateTest {

  private static final String SUBAGENT = "bash-probe-935033b286064257";

  private static ApprovalRequest request(AskKind kind, String requesterId) {
    long now = System.currentTimeMillis();
    return new ApprovalRequest(
        "ap-1",
        "bash",
        "bash:limited:ls",
        "不完全权限（所有命令需审批）: class=bash:limited:ls",
        "sha256:abcd",
        now,
        now + 5_000L,
        "DEFAULT",
        requesterId,
        kind,
        "把目录列出来");
  }

  /** 计数桩：不仅能给答案，还能回答"到底判没判"。 */
  private static final class CountingJudgement implements SuperiorJudgement {

    final AtomicInteger calls = new AtomicInteger();
    Decision answer = Decision.approve("看起来与任务相符");
    RuntimeException boom;

    @Override
    public Decision judge(ApprovalRequest request) {
      calls.incrementAndGet();
      if (boom != null) {
        throw boom;
      }
      return answer;
    }
  }

  private static SuperiorJudgeGate gate(CountingJudgement judgement, CommandMode self) {
    return new SuperiorJudgeGate(judgement, () -> self);
  }

  @Test
  void limitedRequestFromOwnSubagentIsJudgedAndApprovedOnce() {
    CountingJudgement judgement = new CountingJudgement();
    Optional<ApprovalDecision> decision =
        gate(judgement, CommandMode.FULL).decide(request(AskKind.MODE_LIMITED, SUBAGENT));

    assertThat(decision).contains(ApprovalDecision.APPROVE_ONCE);
    assertThat(judgement.calls).hasValue(1);
    // 放行只给一次：会话级放行是"人批"的口子，上级判定不产生可继承的放行
    assertThat(decision.get()).isNotEqualTo(ApprovalDecision.APPROVE_SESSION);
  }

  @Test
  void sensitiveRequestNeverReachesTheJudgement() {
    CountingJudgement judgement = new CountingJudgement();
    assertThat(gate(judgement, CommandMode.FULL).decide(request(AskKind.SENSITIVE, SUBAGENT)))
        .isEmpty();
    assertThat(judgement.calls).as("敏感区永远到人：上级连'看一眼'都不该发生（判了就是能被代批的口子）").hasValue(0);
  }

  @Test
  void externalAndUnknownRequestersAreNotJudged() {
    for (String requester : new String[] {AgentIdentity.EXTERNAL_ID, "unknown"}) {
      CountingJudgement judgement = new CountingJudgement();
      assertThat(gate(judgement, CommandMode.FULL).decide(request(AskKind.MODE_LIMITED, requester)))
          .as("requester=%s", requester)
          .isEmpty();
      assertThat(judgement.calls).as("requester=%s", requester).hasValue(0);
    }
  }

  @Test
  void nonFullSelfDoesNotJudge() {
    CountingJudgement judgement = new CountingJudgement();
    assertThat(gate(judgement, CommandMode.LIMITED).decide(request(AskKind.MODE_LIMITED, SUBAGENT)))
        .isEmpty();
    assertThat(judgement.calls).as("本级不是完全权限 ⇒ 交给更上一跳/人，不许自己点头").hasValue(0);
  }

  /** 档位是<b>现读</b>的：改档后同一个闸的下一次判定换行为（模拟 HTTP 断点改档）。 */
  @Test
  void selfModeIsReadLive() {
    AtomicReference<CommandMode> live = new AtomicReference<>(CommandMode.LIMITED);
    CountingJudgement judgement = new CountingJudgement();
    SuperiorJudgeGate gate = new SuperiorJudgeGate(judgement, live::get);

    assertThat(gate.decide(request(AskKind.MODE_LIMITED, SUBAGENT))).isEmpty();
    assertThat(judgement.calls).hasValue(0);

    live.set(CommandMode.FULL);
    assertThat(gate.decide(request(AskKind.MODE_LIMITED, SUBAGENT)))
        .contains(ApprovalDecision.APPROVE_ONCE);
    assertThat(judgement.calls).hasValue(1);
  }

  @Test
  void denialAndFailureAreTerminalDenyNotFallbackToHuman() {
    record Case(SuperiorJudgement.Decision answer, RuntimeException boom) {}
    java.util.List<Case> cases =
        java.util.List.of(
            new Case(SuperiorJudgement.Decision.deny("与任务不相符"), null),
            new Case(null, null), // 判定实现违约返回 null
            new Case(null, new IllegalStateException("判定面挂了")));

    for (Case testCase : cases) {
      CountingJudgement judgement = new CountingJudgement();
      judgement.answer = testCase.answer();
      judgement.boom = testCase.boom();
      assertThat(gate(judgement, CommandMode.FULL).decide(request(AskKind.MODE_LIMITED, SUBAGENT)))
          .as("拒/空/抛三种失败都按拒办（fail-closed）: %s", testCase)
          .contains(ApprovalDecision.DENY);
      assertThat(judgement.calls).hasValue(1);
    }
  }
}
