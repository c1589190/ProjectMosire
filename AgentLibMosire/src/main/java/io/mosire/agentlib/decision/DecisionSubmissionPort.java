package io.mosire.agentlib.decision;

/**
 * <b>决策面的裁决提交口</b>（设计 §5.13(e)）：把"人在 HTTP 面上做的这一次统一裁决"交给<b>提案链的写者</b>去回填。
 *
 * <p><b>为什么不是一个直通状态机的口</b>：{@link DecisionPackages#decide(String, Decision)} 只改内存态。一个包从"被裁"到"落定"
 * 还要走两件事——<b>发 {@code decision.package.decided} 事件</b>（四个事件的唯一写者是决策包桥，设计 §5.13(a)）与
 * <b>调一次提交口</b>（{@code COMMITTED}/{@code CONFLICTED} 那两个事件就挂在回执之后）。HTTP 处理器若直调 {@code decide}，
 * 这两件事<b>都不会发生</b>：事件不落、提交口不被调，而 HTTP 面还会回一个 200 说"裁好了"。把这条口做成接口、由桥实现， 就是让"绕过桥"在类型上写不出来。
 *
 * <p><b>依赖方向</b>：本接口在 AgentLib，实现方在宿主（{@code DecisionApprovalBridge} 在 Brain）。AgentLib 不认识桥，
 * 也不认识任何一个具体实现——装配层把实现传进 {@code DecisionPackagesHttpHandler.register(...)}。
 *
 * <p><b>具名拒</b>：实现应当让 {@link DecisionPackages.RejectionException} <b>原样上抛</b>（{@code
 * PACKAGE_NOT_FOUND} / {@code PACKAGE_EXPIRED} / {@code PACKAGE_NOT_PROPOSED} / {@code
 * INVALID_DECISION}）， HTTP 面据 {@link DecisionPackages.RejectionException#code()} 映射状态码（设计 §5.11 第
 * 11 条）。提交侧的失败（{@code COMMIT_FAILED}） <b>不</b>走异常：那时裁决已经记下了，包只是没落定——那是 {@link
 * DecisionSubmission#commitNote()} 要说的事。
 */
@FunctionalInterface
public interface DecisionSubmissionPort {

  /**
   * 回填一次统一裁决（整包批 / 整包驳 / 逐条例外）。
   *
   * @param packageId 包 id（{@link DecisionPackages#find(String)} 取到的那个）
   * @param decision 统一裁决：不在 {@link Decision#approvedIndexes()} 里的条目 = 被驳（零落盘）
   * @return 回填后的包快照 + 提交回执（没提交/没落定时给 {@link DecisionSubmission#commitNote()}）
   * @throws DecisionPackages.RejectionException 状态机具名拒（映射见本接口 javadoc）
   */
  DecisionSubmission submitDecision(String packageId, Decision decision);
}
