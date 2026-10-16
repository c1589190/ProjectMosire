package io.mosire.agentlib.decision;

/**
 * 决策包的事件类型常量（RB3；设计 §5.10①）。
 *
 * <p><b>为什么另起一个类而不是塞进 {@code ApprovalEventTypes}</b>：那个类的名字与 javadoc 都限定在"审批请求/决议"两件事上，
 * 它自己就是按"一族事件一个常量类"切的（{@code plugin.lifecycle} 另有归属）。决策包是<b>另一族</b>（包的生命周期，不是
 * 单条审批的），所以另立常量类；两族共用同一条事件存储与同一条总线。
 *
 * <p><b>谁发</b>：{@code DecisionApprovalBridge}——它是"提议 → 裁决 → 提交"那条链的<b>唯一写者</b>（设计 §5.13(a)）。 状态机
 * {@link DecisionPackages} <b>不发事件</b>：HTTP 面的裁决也调它，若它自己发就会有两个发射点，{@code approvedCount} 一类派生值必然分叉。
 *
 * <p><b>payload 的骨架严格只此六项</b>（顺序即组装顺序）：{@code packageId} / {@code itemCount} / {@code
 * approvedCount} / {@code rejectedCount} / {@code toolNames} / {@code digests}；{@link #COMMITTED} 与
 * {@link #CONFLICTED} 另加 {@code revisionRef}（不透明字符串，<b>不得</b>是宿主的 core/世界类型）。P2b
 * 起另有三处<b>只增不减</b>的补充 （都不含任何明文）：{@code proposerKey}（包与历史的归属键，裁定 3 的读侧过滤依据）、{@code
 * trail}（逐级预裁定的轨迹，§5quater ③）、 {@code outcomes}（逐条去向 + 可空理由，裁定 8；只有 {@code committed}/{@code
 * conflicted} 有）。
 *
 * <p>★ <b>绝不写明文参数</b>：{@code digests} 是逐条的脱敏摘要（同 {@code approval.requested} 的口径）。明文只在
 * <b>决策面</b>（本机回环 HTTP 的 {@code /api/decision-packages/{id}}）出面——那是"人要看清自己批的是什么"的地方，
 * 与事件面/提示面是两回事（设计 §5.13(f)）。
 *
 * <p>★ <b>port 抛异常 ⇒ 一条 {@code committed} 都不发</b>：宁可少一条事件，也不写一条假成功。全部被驳 ⇒ 发 {@code decided}（{@code
 * approvedCount=0}）而<b>不发</b> {@code committed}（零落盘，没有提交这回事）。
 *
 * <p><b>两层常量惯例</b>（同 {@code ApprovalEventTypes} 的 javadoc）：AgentLib 不依赖 Brain，Brain 不反向依赖 AgentLib
 * 的事件类，所以两处各有一组常量，<b>字符串值必须逐字一致</b>；本仓由别名用例钉住这一点。
 */
public final class DecisionEventTypes {

  /** 包建立（校验全过）之后。 */
  public static final String PROPOSED = "decision.package.proposed";

  /** 裁决回填之后（含"全驳"：{@code approvedCount=0}）。 */
  public static final String DECIDED = "decision.package.decided";

  /** 提交口回执 {@code COMMITTED} 之后（另带 {@code revisionRef}）。 */
  public static final String COMMITTED = "decision.package.committed";

  /** 提交口回执 {@code CONFLICTED} 之后。 */
  public static final String CONFLICTED = "decision.package.conflicted";

  /**
   * 包被<b>具名作废</b>之后（{@code PROPOSED ⇒ EXPIRED}，零落盘；P2b 新增）。
   *
   * <p><b>为什么需要第五条</b>：设计 §5 裁定 8（用户原话 V10「如实记录包内裁决没有通过的原因，走作废」）把作废也当成<b>结果</b>
   * ——「作废也是结果，也必须可回读」（裁定 5 的底线）。在它之前，作废只发生在内存态、只留一行 WARN：{@code decision.package.proposed}
   * 之后的包在事件库里永远停在"已提议"，下一轮查自己的历史会把一个早已没人会裁的包看成"还在等人"。 这条事件把"为什么没通过"（窗口到点无人答复 / 无可用通道）如实写进记录面。
   *
   * <p><b>载荷</b>：与其它四条同形（{@code packageId} / {@code itemCount} / {@code toolNames} / …），另加 {@code
   * reason}（作废原因，人读一行）。<b>不含</b>参数明文。
   */
  public static final String EXPIRED = "decision.package.expired";

  private DecisionEventTypes() {
    throw new AssertionError("No instances");
  }
}
