package io.mosire.agentlib.decision;

import java.util.List;
import java.util.Objects;

/**
 * 提交口 SPI（设计 §5.8 冻结形状；§5.3「一次提交 + 回执」）：把<b>一个包</b>的<b>被批子集</b>当一次提交落下去。
 *
 * <p>这是 RB1 与宿主（隔壁的世界写口）之间<b>唯一</b>的缝：AgentLib 负责状态机与统一结果，
 * 落盘与版本冲突由宿主负责。实现方（RC1）必须守住下面这份契约——它每一句都对应一条不变量。
 *
 * <h2>契约</h2>
 *
 * <ol>
 *   <li><b>{@code calls} 就是被批子集</b>：被驳条目<b>永不</b>出现在这里（B-2），列表非空 （被批子集为空的包由包层直接判 {@code
 *       REJECTED}，<b>根本不调用本方法</b>——红线 5）， 顺序 = 包内 {@code items()} 顺序。
 *   <li><b>一次调用 = 一次提交 = 一个包</b>：N 条必须落成同一条 revision，<b>不得</b>拆成 N 次提交 （B-1）。拆开就把"整批要么全落要么全不落"降级成"N
 *       次各自的成败"。
 *   <li><b>写后不许抛</b>（D1 的教训）：已经落盘之后不得用异常表达任何事。包层看到异常只能保持 {@code
 *       DECIDED}（它分不清"没写"与"写了才抛"），调用方会以为没落定，而世界已经变了。
 *   <li><b>幂等键 = {@code packageId}</b>：同一个 {@code packageId} 重复提交只许落一次 （世界侧 {@code commandId}
 *       幂等，§5.3）。包层在重放时已经直接返回旧回执、不再调用本方法， 所以这是<b>第二道</b>保险，不是可选项——重试路径（提交口抛过异常之后）就靠它兜底。
 *   <li><b>回执 id 要对得上</b>（B-6）：宿主落盘信封里的 id 必须与传进来的 {@code packageId} <b>逐字相等</b>；不要另生成一个
 *       correlationId 塞进信封、再让它对不上包 id （隔壁 I-G1 的教训就是"对不了账的回执"）。
 *   <li><b>逐条 {@code outcomes}</b>：与 {@code calls} 一一对应、同序。可以短于 {@code calls} （缺失项由包层按 {@code
 *       status} 推导：{@code COMMITTED} ⇒ 该条 COMMITTED，其余 ⇒ NOT_COMMITTED），
 *       但<b>少报会让"逐条去向"退化成按整批猜</b>，所以正常路径应当给全；多报的部分会被忽略并告警 （包层只认包内条目）。<b>不要</b>报被驳条目——宿主管不着它们。
 *   <li><b>冲突不许装作没发生</b>：版本冲突 ⇒ {@link CommitReceipt.Status#CONFLICTED}，零落盘， 并把当前世界版本<b>以摘要形式</b>写进
 *       {@code reason} 交回确认者（§5.6）。 不得把 core 的世界类型塞进 AgentLib（§2.4 解耦红线）。
 *   <li><b>线程与重入</b>：本方法在包层的锁内被调用（"同一包恰好一次提交"靠它保证），实现可以阻塞； 但不得从<b>另一个线程</b>回调同一个 {@link
 *       DecisionPackages} 实例的写方法（等待持锁线程 = 自堵）。 同线程重入是安全的（可重入锁）。
 * </ol>
 */
public interface DecisionCommitPort {

  /**
   * 提交一个包的被批子集，返回回执。
   *
   * @param packageId 包 id（本仓发放，同时是幂等键与对账键；宿主落盘信封里的 id 必须与它逐字相等）
   * @param calls 被批子集（非空；被驳条目永不在此）
   * @return 回执：三结局之一 + 逐条去向；{@code COMMITTED} 时必须带回非空白的不透明 {@code revisionRef}
   */
  CommitReceipt commit(String packageId, List<ResolvedCall> calls);

  /**
   * 提交一个 {@link DecisionCommitPlan}（U1 新增的重载）：默认委托冻结的旧方法，旧实现一行都不用改。
   *
   * <p><b>为什么默认方法而不是抽象方法</b>：旧实现在 Brain / Main 里已经存在；加抽象方法会让它们全部编译失败。默认委托让 "一次提交 = 一个计划"的新口与"一次提交 =
   * 一组 calls"的旧口保持<b>同一份提交语义</b>，而不是第二条提交路径。
   *
   * <p>实现若需要提交前快照（同一事务里写效果 + outcome + 最终包状态），应覆写本方法从 {@code plan.before()} 取数； 默认实现只把 {@code
   * plan.calls()} 交给旧方法。
   *
   * @param packageId 包 id（幂等键与对账键）
   * @param plan 提交计划（calls 非空；before 是提交前快照）
   * @return 回执：三结局之一 + 逐条去向
   */
  default CommitReceipt commit(String packageId, DecisionCommitPlan plan) {
    Objects.requireNonNull(plan, "plan");
    return commit(packageId, plan.calls());
  }
}
