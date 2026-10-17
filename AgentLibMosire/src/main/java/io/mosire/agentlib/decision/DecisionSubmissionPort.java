package io.mosire.agentlib.decision;

/**
 * <b>决策面的写路径口</b>（设计 §5.13(e) + §5septies，用户裁定 15 · 2026-10-17）：把人在外部决策面上 / Agent 在工具面上做的
 * <b>表态</b>与<b>执行命令</b>交给<b>提案链的写者</b>去回填。
 *
 * <p><b>为什么不是一个直通状态机的口</b>：{@link DecisionPackages#decide(String, Decision)} 只改内存态。一个包从"被裁"到"落定"
 * 还要走两件事——<b>发 {@code decision.package.*} 事件</b>（那些事件的唯一写者是决策包桥，设计 §5.13(a)）与 <b>调一次提交口</b>（{@code
 * COMMITTED}/{@code CONFLICTED} 那两个事件就挂在回执之后）。决策面适配器若直调 {@code decide}，
 * 这两件事<b>都不会发生</b>：事件不落、提交口不被调，而决策面还会回一个成功回执说"裁好了"。把这条口做成接口、由桥实现， 就是让"绕过桥"在类型上写不出来。
 *
 * <p><b>三件事，两条命令</b>（用户裁定 15 ③「要不然分 event 命令和裁决包执行命令干什么？」）：
 *
 * <ul>
 *   <li>{@link #submitDecision} / {@link #itemDecided} = <b>表态</b>（整包一次 / 逐条一次）；它们<b>不</b>执行；
 *   <li>{@link #commitPackage} = <b>执行命令</b>；只有它会让被批子集落盘。
 * </ul>
 *
 * <p><b>依赖方向</b>：本接口在 AgentLib，实现方在宿主（{@code DecisionApprovalBridge} 在 Brain）。AgentLib 不认识桥，
 * 也不认识任何一个具体实现——装配层把实现传进宿主的决策面适配器（例如 MCP 工具）与工具面。
 *
 * <p><b>具名拒</b>：实现应当让 {@link DecisionPackages.RejectionException} <b>原样上抛</b>（{@code
 * PACKAGE_NOT_FOUND} / {@code PACKAGE_EXPIRED} / {@code PACKAGE_NOT_PROPOSED} / {@code
 * INVALID_DECISION} / {@code INVALID_ITEM_INDEX} / {@code ITEM_ALREADY_DECIDED} / {@code
 * NO_ITEM_DECIDED}）， 决策面据 {@link DecisionPackages.RejectionException#code()} 映射错误码（设计 §5.11 第 11
 * 条）。提交侧的失败（{@code COMMIT_FAILED}） <b>不</b>走异常：那时裁决已经记下了，包只是没落定——那是 {@link
 * DecisionSubmission#commitNote()} 要说的事。
 *
 * <p><b>为什么不再是 {@code @FunctionalInterface}</b>：一个函数位只够表达"一次整包裁决"。裁定 15 把决策面的写路径 拆成<b>两条命令</b>（表态 /
 * 执行）之后，三件事（整包表态、条目级表态、执行命令）必须由同一个实现给出——否则 "表态走了桥、执行走了另一条路"就会出现两个提交口径。
 */
public interface DecisionSubmissionPort {

  /**
   * 回填一次<b>整包</b>裁决（整包批 / 整包驳 / 逐条例外）。
   *
   * <p><b>★ 用户裁定 15 ③（两段式）之后本方法的语义收窄</b>：它<b>只表态、不执行</b>。执行由 {@link #commitPackage(String,
   * String)}（显式执行命令）承担。实现方若仍在这里顺手提交，就等于把用户要分开的 两条命令又粘回去——"裁决即执行"正是本裁定<b>取代</b>掉的旧口径。
   *
   * @param packageId 包 id（{@link DecisionPackages#find(String)} 取到的那个）
   * @param decision 统一裁决：不在 {@link Decision#approvedIndexes()} 里的条目 = 被驳（零落盘）
   * @return 回填后的包快照 + 提交回执（{@code DECIDED} 尚未执行 ⇒ {@code receipt == null} + {@code commitNote}
   *     说明"还没执行"）
   * @throws DecisionPackages.RejectionException 状态机具名拒（映射见本接口 javadoc）
   */
  DecisionSubmission submitDecision(String packageId, Decision decision);

  /**
   * 对包里的<b>一条</b>条目表态（用户裁定 15 ②，"一次裁决的分段表达"），返回表态后的包快照。
   *
   * <p><b>它仍然是"一次裁决"</b>：不建第二个包、不发第二条投递、不执行——只是裁决累积的一步。全部条目都表态完 ⇒ 包自动进 {@code DECIDED}（等执行命令）。
   *
   * <p>没有默认实现是刻意的：本口是"写路径必经之处"（设计 §5.13(e)），实现方必须显式回答"条目级表态怎么落"。
   *
   * @param packageId 包 id
   * @param index 条目 index
   * @param approve {@code true} = 批准，{@code false} = 驳回
   * @param by 表态人（审计注记）
   * @param note 可空理由
   * @return 表态后的回执面（{@code receipt == null}：表态不是执行）
   * @throws DecisionPackages.RejectionException 具名拒（未知 / 已终结 / 重复表态 / index 不在包内）
   */
  DecisionSubmission itemDecided(
      String packageId, int index, boolean approve, String by, String note);

  /**
   * <b>执行命令</b>（用户裁定 15 ③，"裁决包执行命令"）：把被批子集<b>恰好一次</b>提交，返回统一回执。
   *
   * <p>只有 {@code DECIDED} 可执行（{@code DECIDING} 会被实现方先按裁定 14 终结掉未表态的条目再执行；一条都没批 ⇒ 具名拒）； 重复调用 ⇒
   * 幂等（拿到同一份回执，不重复执行）。
   *
   * @param packageId 包 id
   * @param by 下令者（审计注记）
   * @return 回执面（{@code receipt} 非空 = 真的执行过；{@code null} = 没落定，理由在 {@code commitNote}）
   * @throws DecisionPackages.RejectionException 具名拒（未知 / 未裁决 / 一条都没批 / 已过期 / 提交口失败）
   */
  DecisionSubmission commitPackage(String packageId, String by);
}
