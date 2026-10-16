package io.mosire.agentlib.decision;

import java.util.List;

/**
 * <b>「看了明文」的审计口</b>（用户裁定 15 ①；设计 §5septies ①）。
 *
 * <p><b>为什么需要它</b>：默认概览<b>不</b>给参数明文，只有显式要求（{@code full}）才给。给了明文就必须留下"谁看过" ——
 * 否则"默认不给"只是把泄漏从常态改成偶尔，而偶尔的那几次没人查得出来。所以每一次明文读取都落一条 {@code decision.package.detail_read}（载荷只记谁 / 哪个包
 * / 哪些 index / 何时，<b>绝不记明文本身</b>）。
 *
 * <p><b>为什么是注入的可选口而不是让读侧自己写事件</b>：读侧（HTTP 面 / 工具面）拿不到事件总线，也不该拿—— 决策包事件的唯一写者是桥（设计
 * §5.13(a)）。本口就是那条缝：读侧只说"这些 index 的明文被这次读取暴露了"， 由装配方决定落哪条总线、记成什么。
 *
 * <p>可空注入：{@code null}（缺省）= 没有审计口 ⇒ 明细面照常返回明文、只是不落审计事件。★ 这是<b>有意</b>保留的降级
 * （离线夹具、只验渲染的用例不必自建总线）；生产装配必须接上，接法见 {@code App} 的装配点。
 */
@FunctionalInterface
public interface DecisionReadAudit {

  /**
   * 记一次明文读取。
   *
   * @param packageId 包 id（在途包也走这条）
   * @param indexes <b>这一次真的被返回的</b>条目 index（顺序 = 返回顺序；空列表 = 没有条目可暴露，比如空包）
   * @param by 谁读的（通道名 / 实例 id / 工具名；审计面能据此回答"是模型看的还是人看的"）
   */
  void detailRead(String packageId, List<Integer> indexes, String by);
}
