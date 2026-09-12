package io.mosire.agentlib.approval;

import java.time.Duration;
import java.util.Optional;

/**
 * 一条把"待人裁决"送出去、并等回决定的通道（S4 内核的出口面）。tty / http / agui 各一实现（B2）。
 *
 * <p><b>可用性是第一公民</b>：{@link #available()} 为 {@code false}（无 {@code /dev/tty}、端口未开、
 * 提示面关了）时，调用方<b>不得</b>把它当成"默认同意"——编排器在没有任何可用通道时<b>立即拒</b>（{@link ApprovalCoordinator} 的
 * fail-closed），绝不静默降级、更不放行。
 *
 * <p><b>不落库</b>：审批是"当下这个人的当下决定"，登记与决议都在进程内（{@link PendingApprovals}）， 通道实现只是它的 I/O 面。
 *
 * <p>实现约束（B2 落地时逐条对）：{@link #await} 的<b>硬约束</b>见其 javadoc（自带等待上界 + 响应中断 + 不自旋）；
 * 同进程内提示要<b>串行化</b>（防两个工具调用交错抢同一块提示面）。
 */
public interface ApprovalChannel {

  /** 通道名（决议事件里的 {@code by} 取它——审计要能回答"谁答的"）。返回 {@code null} 时编排器记 {@code "unnamed"}。 */
  String name();

  /** 通道此刻可用否（无 {@code /dev/tty}、端口未开 ⇒ false）。 */
  boolean available();

  /** 非阻塞：登记/外发（不等人）。 */
  void publish(ApprovalRequest req);

  /**
   * 阻塞等待（虚拟线程友好）；返回空 = 到点没人答。
   *
   * <p>返回空<b>不是</b>放行信号：编排器把它当 DENY（fail-closed）。
   *
   * <p><b>三条硬约束（B2 落地时逐条对；违反其一即实现缺陷，不是"风格问题"）</b>：
   *
   * <ol>
   *   <li><b>必须自带等待上界</b>：{@code wait} 到点就返回空，<b>不得</b>无限阻塞（挂死 = 工具调用永远不返回，
   *       而编排器只看自己的预算、不会替通道兜底）。等待要落在 {@link PendingApprovals#await(String, Duration)}
   *       这类条件变量上，<b>不得自旋</b>——自旋既烧 CPU， 又让"到点没人答 ⇒ 拒"的用例又脆又慢；
   *   <li><b>必须响应 {@link InterruptedException}</b>：被打断就尽快返回（并按惯例恢复中断位），不得"吞掉中断后继续阻塞"。 编排器靠这条收尾：它在
   *       {@code finally} 里对每个等待线程 {@code interrupt()}（人已经从另一条通道答完/已经到点，这些等待就是垃圾了）， 而 {@code
   *       Thread.interrupt()} 对<b>不检查中断位的阻塞调用</b>（朴素的 {@code read}/{@code accept}、不响应中断的等待）
   *       <b>没有</b>效力——探针实测：不响应中断的通道，在 {@code decide} 返回 1 s 后其等待线程仍 {@code isAlive() ==
   *       true}；把同一次等待换成会抛 {@code InterruptedException} 的实现，线程当时就已终止。B2 的 tty 通道 正是"阻塞在读 {@code
   *       /dev/tty}"这一形态，所以这不是洁癖：它决定一个已经没人要的等待能不能被撤掉。
   *   <li><b>答复必须先落进登记表，再返回</b>：人的答复一律经 {@link PendingApprovals#decide(String, ApprovalDecision,
   *       String)} 写决议，{@link #await} 只是被它唤醒后把决定交回编排器——<b>不要</b>凭通道自己的内部状态返回一个决定。
   *       否则登记项会停在"待裁决"：提示面继续展示它，人再点"批准"拿到成功回执，而这次调用早已按那个答复结束了。
   * </ol>
   */
  Optional<ApprovalDecision> await(String id, Duration wait);
}
