package io.mosire.agentlib.decision;

import java.util.List;

/**
 * <b>「在途决策包」的条目级表态 / 执行命令口</b>（用户裁定 15 ②③；设计 §5septies ②③）：给决策面一条"对<b>不在</b> {@link
 * DecisionPackages} 表里的包（跨进程那条路的临时包 {@code xp-…}）逐条表态、下执行命令"的路。
 *
 * <p><b>为什么需要它</b>：跨进程那条路上，父侧为一次子体提交建的是<b>临时包</b>（{@code CrossProcessDecisionGateway}，
 * 建即裁、裁完即弃、不进包表）。用户裁定 15 要的是"先看包、再逐条看、逐条裁、最后下执行命令"—— 而这套动作必须也作用在<b>人正在裁决的那个包</b>上，
 * 否则"条目级操作"在真子进程场景里等于不存在（人只能整包批/驳，回到裁定 15 之前）。
 *
 * <p><b>它不构成第二条决策路径</b>（不变量 1）：临时包不进 {@code DecisionPackages} 表、不进 {@code pending()}、 不落盘、不发 {@code
 * decision.package.*} 事件、不留第二份历史；结算仍然只发生一次（把终局裁决交回提它的那个子体， 由子体用自己的权限与身份执行——§5quinquies
 * ①「父侧不得替子体执行」）。
 *
 * <p><b>实现方契约（三条）</b>：
 *
 * <ol>
 *   <li><b>只在"在途"窗口内可用</b>：结算（或到点、或投递异常返回）之后一律 {@link DecisionPackages#PACKAGE_NOT_FOUND}
 *       那一档的具名拒——与"从来没见过这个 id"同一个回答（不给存在性 oracle）；
 *   <li><b>不落盘、不发事件</b>：本口只把表态累积在内存里，并把终局裁决交回等待中的提出者；
 *   <li><b>未表态 ≠ 拒绝</b>：表态只覆盖被点到的那一条；没表态的条目保持未决，只在"下执行命令"或窗口结束时按裁定 14 归成被驳。
 * </ol>
 *
 * <p>可空注入：{@code null}（缺省）= 没有这个口 ⇒ 决策面对在途包的写路径回 {@code 404}，行为与本能力引入前逐字一致。
 */
public interface InFlightDecisionActions {

  /**
   * 对在途包的一条条目表态（{@code POST /api/decision-packages/{id}/items/{index}}）。
   *
   * @param packageId 包 id（在途的那一个）
   * @param index 条目 index
   * @param approve {@code true} = 批准，{@code false} = 驳回
   * @param by 表态人（审计注记，不参与判定）
   * @param note 可空理由
   * @return 表态后的回执面（{@code pkg} = 该包此刻的快照，含逐条已表态集合；{@code receipt} = {@code null}——跨进程的执行在子体，
   *     父侧没有回执可说，{@link DecisionSubmission#commitNote()} 负责讲清楚）
   * @throws DecisionPackages.RejectionException 具名拒（未知 / 已终结 / 重复表态 / index 不在包内）
   */
  DecisionSubmission itemDecided(
      String packageId, int index, boolean approve, String by, String note);

  /**
   * 对在途包下执行命令（{@code POST /api/decision-packages/{id}/commit}）：把"就这些了"变成终局裁决交回提出者。
   *
   * @param packageId 包 id（在途的那一个）
   * @param by 下令者（审计注记）
   * @return 回执面（{@code pkg} = 终结后的快照；{@code receipt} = {@code null}——真正的执行在子体，父侧不替它执行）
   * @throws DecisionPackages.RejectionException 具名拒（未知 / 已终结 / 一条都没批 ⇒ {@code NO_ITEM_DECIDED}）
   */
  DecisionSubmission commitPackage(String packageId, String by);

  /**
   * 在途包<b>已表态</b>的条目（裁定 15 ②的读口）：决策面按它渲染"这一条定了没有、谁定的"。
   *
   * <p>未表态的条目<b>不在</b>结果里——"缺席"就是"还没定"（把它算成驳回会让"我还没看到那一条"被读成"我否了它"）。 不实现 ⇒ 空列表（渲染侧按"一条都没表态"显示）。
   *
   * @param packageId 在途包 id；未知/已结算 ⇒ 空列表（不抛）
   */
  default List<DecisionPackages.ItemVerdict> itemVerdicts(String packageId) {
    return List.of();
  }
}
