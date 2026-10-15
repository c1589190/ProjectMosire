package io.mosire.agentlib.decision;

/**
 * 决策包的状态机（设计 §5.8 冻结形状；语义见 §5.1 / §5.6）。
 *
 * <p><b>为什么是显式枚举，而不是几个布尔标记</b>：包的生命周期是「提议 → 统一裁决 → 一次提交」的有限状态机， 每个状态都对应<b>不同的落盘语义</b>（{@link
 * #PROPOSED}/{@link #DECIDED} = 还可能有落盘； {@link #COMMITTED} = 已落盘；{@link #REJECTED}/{@link
 * #CONFLICTED}/{@link #EXPIRED} = 零落盘）。 用布尔标记拼状态，"哪些组合合法"就只能靠约定，而约定在并发下会静默漂移——"批了一半却以为整批落了"正是 设计
 * §2.2 里隔壁 #1/#2 两笔出血的形状。
 *
 * <p><b>终局包不消失</b>：与 {@link io.mosire.agentlib.approval.PendingApprovals} 的单条审批不同， 已终局的包仍留在内存表里、仍可被
 * {@link DecisionPackages#find} 查到——「同包重放拿同一份回执」（B-4）
 * 与「零落盘也要给一份统一结果」（B-7）都要求它可被再查到。这不是"没清理干净"，是幂等的一部分。
 */
public enum PackageState {
  /** 已建包、等裁决：唯一「未决」态，{@link DecisionPackages#pending()} 只列它。 */
  PROPOSED,
  /** 裁决已作出（整包一次裁 + 逐条例外）：等提交；被批子集<b>可能为空</b>（全部条目被驳）。 */
  DECIDED,
  /** 被批子集已一次提交、宿主回执已落定：恰好一条 revision。 */
  COMMITTED,
  /** 终局、零落盘：两种来源——① 被批子集为空（全部条目被驳）；② 宿主提交口整批拒收。 */
  REJECTED,
  /** 终局、零落盘：宿主版本冲突，被批子集整批退回；裁决与冲突摘要保留可查。 */
  CONFLICTED,
  /** 终局、零落盘：到点仍没人裁（fail-closed：超时不静默执行）。 */
  EXPIRED
}
