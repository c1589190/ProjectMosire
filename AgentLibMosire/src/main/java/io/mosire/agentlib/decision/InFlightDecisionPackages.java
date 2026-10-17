package io.mosire.agentlib.decision;

import java.util.List;
import java.util.Optional;

/**
 * <b>「在途决策包」的只读取数口</b>（P3e）：给决策面一条"按包 id 取出<b>此刻正摆在人面前</b>、但<b>不在</b> {@link DecisionPackages}
 * 表里的包"的路。
 *
 * <p><b>它解决的是什么</b>：跨进程那条路（设计 §5sexies）里，父侧为一次子体提交建的是一个<b>临时包</b>（{@code xp-…}，
 * 建即裁、裁完即弃）。它<b>不进</b> {@code DecisionPackages}（否则就多出"第二条决策路径"与第二份历史，违反不变量 1）， 于是决策面 {@code GET
 * /api/decision-packages/{id}} 过去对它是 {@code 404}——而审批面上那条请求的 {@code summary} 里就写着这个 {@code
 * xp-…}，人想"按包看全文"却查不到，只能看一行包级摘要。 用户 2026-10-16 的原话是「肯定展示全部内容啊」： 人要看清自己批的是什么，脱敏摘要回答不了"这条到底改什么"。
 *
 * <p><b>为什么在 AgentLib 定义接口、实现留在宿主</b>：通用层不得 import 领域模块（AGENTS §0.1）。本接口只说清"按 id 取一个包"，
 * 具体那份在途视图（谁登记、活多久、什么时候消失）由宿主实现——<b>可选注入；缺省（{@code null} / 不传）= 今天行为逐字不变</b>。
 *
 * <p><b>★ 它必须满足的三条（实现方契约）</b>：
 *
 * <ol>
 *   <li><b>只读</b>：本口<b>只</b>服务 {@code GET}（详情）。它<b>不</b>进 {@code pending()} 列表、<b>不</b>落任何历史/盘、
 *       <b>不</b>发事件，也<b>不</b>允许 {@code POST} 拿它当"可以裁决的包"——在途包不是决策单元， 它只是"人此刻有权看的全文"；
 *   <li><b>在途即消失</b>：登记只覆盖"这次投递正在等人/等闸"的那段窗口，结算（或到点、或投递异常返回）即<b>取不到</b>；
 *   <li><b>明文只在内存</b>：返回的包内条目带参数<b>明文</b>（与人看全文的要求同源），它只准出现在这条回环 HTTP 的响应里， 不进事件库、不进日志、不进任何 {@code
 *       ApprovalRequest} 载荷（与决策面适配器的明文纪律同一句话）。
 * </ol>
 *
 * <p>{@link Optional#empty()} = 不是这个口管的对象 / 已经结算 / 从来没登记过——三种情形<b>同一个回答</b>（不给存在性 oracle，
 * 与决策面既有的"未知 id ⇒ 404"口径一致）。
 */
@FunctionalInterface
public interface InFlightDecisionPackages {

  /**
   * @param packageId 包 id（{@code xp-…} 这类临时包 id；{@code null} ⇒ 空值）
   * @return 该在途包的快照（含逐条明文条目）；不在途/不认识 ⇒ 空值。实现<b>不应抛异常</b>（抛了按"取不到"办，见 {@link
   *     决策面适配器）
   */
  Optional<DecisionPackage> find(String packageId);

  /**
   * ★ <b>两档查看（用户裁定 15 ①）的落点</b>：{@code full = false} ⇒ 快照里逐条的 {@code args} 被<b>抹掉</b>（只留 {@code
   * index/toolName/summary}）；{@code full = true} ⇒ 原样给明文。
   *
   * <p><b>为什么由取数口回答"要不要明文"，而不是让渲染侧丢掉 args</b>：脱敏必须在<b>取数的最内侧</b>发生——
   * 渲染侧丢字段是"读到了再假装没读"，任何一处漏丢（日志、异常消息、上层缓存）都会把明文带出去。 默认实现保守地返回 {@link #find(String)} 的明文快照：口没实现这一档 ⇒
   * 渲染侧仍必须按 {@code full} 决定要不要输出（两层都拦，任一层漏了还有另一层）。
   *
   * @param full {@code true} = 显式要求明文（调用方必须落一条 {@link DecisionReadAudit} 审计）
   */
  default Optional<DecisionPackage> find(String packageId, boolean full) {
    return find(packageId);
  }

  /**
   * ★ <b>此刻所有在途包</b>（用户裁定 15：父侧/人"拿到一个决策包，首先查看决策包"）。
   *
   * <p>跨进程那条路上，包只活在"提出者正阻塞在裁决上"的那段窗口里——没有这个口，决策面列表就<b>看不到</b>它， 人只有一个 {@code xp-…} 的 id 与一行摘要（P3e
   * 之前连详情都读不到）。默认实现返回空列表：口没实现这一档 ⇒ 列表照旧只列包表里的那些， 行为与本能力引入前逐字一致。
   *
   * <p>与 {@link #find(String)} 同一条纪律：<b>只读</b>——不进 {@code pending()}、不落盘、不发事件；返回的快照<b>不含</b>明文
   * （列表是概览面，明文只走 {@link #find(String, boolean)} 的 {@code full} 那一档）。
   */
  default List<DecisionPackage> list() {
    return List.of();
  }
}
