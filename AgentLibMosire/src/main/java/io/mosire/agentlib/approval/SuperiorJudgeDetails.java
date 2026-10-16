package io.mosire.agentlib.approval;

import java.util.List;

/**
 * <b>上级判定提示的逐条明文明细取数口</b>：按一条 {@link ApprovalRequest} 拿出"这次到底要改什么"的逐条明细。
 *
 * <p><b>为什么是取数口而不是往请求里塞字段</b>：{@link ApprovalRequest} 的载荷是脱敏的（{@code summary}/{@code digest}），
 * 明文参数一旦成了请求的字段，就会顺着"请求 → 事件 → 日志"那条既有的路扩散出去——那正是密钥纪律禁止的形状。
 * 让判定面<b>按请求反查</b>，明文就只活在"谁登记了它、谁在读它"这两点之间，而且可以在读完的那一刻被释放 （{@link SuperiorJudgeDetailRegistry}）。
 *
 * <p><b>取不到明细不是错，是"这个请求本来就没有逐条明细"</b>（例如单条 shell 命令）：这时判定退回只看 {@code summary}，与本功能引入前逐字相同（{@link
 * #NONE}）。★ 但"<b>该有明细却没有</b>"是另一回事—— 决策包桥在明细缝未装配时<b>不会</b>把包投给上级代批（{@link AskKind#SENSITIVE} 那一侧），
 * 宁可不代批，也不让上级"看一半批全部"。
 *
 * <p>实现<b>不应抛异常</b>：{@code LlmSuperiorJudgement} 对取数异常按"拒"办（fail-closed），但异常路径的理由只有一层；
 * 实现自己把失败讲清楚更好。
 */
@FunctionalInterface
public interface SuperiorJudgeDetails {

  /** 没有明细来源：判定只看请求自带的 {@code summary}（= 本功能引入前的行为）。 */
  SuperiorJudgeDetails NONE = request -> List.of();

  /**
   * @param request 待判定的请求（非 null）
   * @return 该请求的逐条明细；没有/不适用 ⇒ 空表（<b>不得</b>返回 {@code null}）
   */
  List<JudgementItem> itemsFor(ApprovalRequest request);
}
