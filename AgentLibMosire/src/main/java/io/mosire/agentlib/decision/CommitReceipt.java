package io.mosire.agentlib.decision;

import java.util.List;
import java.util.Objects;

/**
 * 一次提交的回执（设计 §5.8 冻结形状；语义见 §5.3 / §5.6）。
 *
 * <p><b>{@code revisionRef} 是不透明字符串</b>：AgentLib <b>不认识</b>世界的版本类型—— 隔壁的 {@code StateRef} / {@code
 * expectedRevision} / {@code Conflict(current)} 都是 core 的类型， 把它们搬进通用层正是设计 §2.4 点名的解耦红线（隔壁 #4
 * 出血的成因）。所以回执只承诺 「id + 三结局 + 不透明引用」：谁产生它、怎么解释它，是宿主的知识；通用层只做<b>原样传递</b>， 绝不解析、不比较、不拼装。
 *
 * <p><b>三结局与"零落盘"的对应</b>：{@link Status#COMMITTED} = 被批子集一次落定、恰好一条 revision （所以 {@code COMMITTED} 时
 * {@code revisionRef} 必填——空引用等于没法对账，B-1/B-6）； {@link Status#REJECTED} 与 {@link Status#CONFLICTED} =
 * <b>零落盘</b>，{@code revisionRef} 为空。
 *
 * <p><b>逐条 {@code outcomes}</b>：与包内条目<b>一一对应、同序</b>，长度 = 包内条目数 （B-7：批 4 驳 1 ⇒ 5
 * 条各有去向）。提交口只报"它落没落"（按 calls 顺序）， <b>被驳条目的 {@link ItemOutcome#REJECTED} 由包层填</b>（设计
 * §5.3）——这是"统一对应结果"在提交侧的另一半。
 *
 * <p>{@code reason} 是给人看的失败/冲突说明；冲突时宿主把当前世界版本<b>以摘要形式</b>放进来 （§5.3：不是 core
 * 类型，就是一串给人读的文字），包层原样透传。{@code null} 视作空串。
 */
public record CommitReceipt(
    Status status, String revisionRef, List<ItemOutcome> outcomes, String reason) {

  /** 提交的三结局（穷尽 switch；刻意<b>不</b>引入 {@code PARTIAL_FAILURE} 之类第二码——B-7）。 */
  public enum Status {
    /** 被批子集已整批落定（恰好一条 revision）。 */
    COMMITTED,
    /** 整批被拒（零落盘；也用于包层判定的"被批子集为空"）。 */
    REJECTED,
    /** 版本冲突：被批子集整批退回（零落盘），{@code reason} 带当前版本摘要。 */
    CONFLICTED
  }

  /** 单条去向：{@code REJECTED} 是"被驳"（包层判的），另两个是"落没落"（宿主报的）。 */
  public enum ItemOutcome {
    /** 落了。 */
    COMMITTED,
    /** 没落（被批子集是原子的：同批要么全落要么全不落，B-2）。 */
    NOT_COMMITTED,
    /** 被驳：零落盘，理由由包层填——宿主只报"落没落"，不判"该不该落"（设计 §5.3）。 */
    REJECTED
  }

  public CommitReceipt {
    Objects.requireNonNull(status, "status");
    if (status == Status.COMMITTED && (revisionRef == null || revisionRef.isBlank())) {
      throw new IllegalArgumentException(
          "status=COMMITTED 时 revisionRef 不得为空白：" + "「恰好一条 revision」是 B-1 的一半，空引用等于落定了却没法对账");
    }
    Objects.requireNonNull(outcomes, "outcomes");
    outcomes = List.copyOf(outcomes);
    reason = reason == null ? "" : reason;
  }
}
