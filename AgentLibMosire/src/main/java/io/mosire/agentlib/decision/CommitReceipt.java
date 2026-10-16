package io.mosire.agentlib.decision;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
 *
 * <p><b>★ 逐条理由（{@code itemReasons} / {@link #itemResults()}，P2b 新增）</b>：设计 §5 裁定 8 补充（用户原话 V14
 * 「那加上可填可不填的原因位呗？」）要求每条去向可以带一个<b>可空</b>理由—— {@code null} = <b>未说明理由</b>（不是"没有原因"）。
 * 落点分两段（同一个字段的两种来源）：
 *
 * <ul>
 *   <li><b>被驳/未通过</b>的那几条由<b>包层</b>填（{@link DecisionPackages} 的 {@code REJECTED_ITEM_REASON}；母稿
 *       §5.3 早就要求）；
 *   <li><b>执行期失败</b>的那几条由<b>提交口</b>填（本机形态 = {@code LocalDecisionCommitPort}；它把拒码/异常类名写进理由）。
 * </ul>
 *
 * <p><b>为什么不改 {@code outcomes()}</b>：{@code List<ItemOutcome> outcomes()} 是冻结形状（P2b 的硬约束点名"一律不动"），
 * 所以逐条理由走<b>并列</b>的读口 {@link #itemResults()}：它把两个列表按<b>同一顺序</b>拉链起来，一个 {@link ItemResult} = 一条去向 +
 * 一个可空理由。渲染口径（裁定 8 补充）：无理由 ⇒ 只报去向；有理由 ⇒ 去向 + 理由。
 *
 * <p><b>为什么 {@code itemReasons} 的元素类型是 {@code Optional<String>}</b>：本记录是<b>不可变</b>值对象，内部要能安全地
 * {@code List.copyOf}；而 {@code List.copyOf} 拒绝 null 元素，偏偏"没写理由"必须与"写了理由"区分开。把"有没有"交给类型 （{@code
 * Optional}）之后：{@code List.copyOf} 可用（深度不可变、无暴露内部表示的形状），对外的 {@link ItemResult#reason()} 仍回可空 {@code
 * String}（调用方不必到处拆 {@code Optional}）。长度必须与 {@code outcomes}
 * 对齐——对不齐就说明"哪条理由属于哪条"已经不可知，构造期响亮拒（fail-closed）。
 *
 * <p><b>4 参兼容构造保留</b>：本单之前写下的调用点（含测试与宿主实现）逐字不动，逐条理由缺省 = 全部"未说明理由"。
 *
 * <p>★ <b>{@code EI_EXPOSE_REP} 的记录级抑制（写明理由，照本仓既有手法）</b>：本记录的每个 {@code List} 分量都在规范构造里经 {@code
 * List.copyOf} 冻结（{@code outcomes} 的元素是枚举、{@code itemReasons} 的元素是 {@link Optional}），**深度不可变**；
 * {@link #itemReasons()} 另外还回拷贝。SpotBugs 不认"构造期已冻结"这条推理，且它把告警钉在<b>记录分量声明</b>那一行（record
 * 的隐式成员不吃方法级注解）——所以抑制只能落在类型上。这里<b>不</b>为消一条误报去改数据形状。
 */
public record CommitReceipt(
    Status status,
    String revisionRef,
    List<ItemOutcome> outcomes,
    List<Optional<String>> itemReasons,
    String reason) {

  /**
   * 逐条理由（与 {@link #outcomes()} 同序等长；元素 {@code Optional.empty()} = 未说明理由）。
   *
   * <p><b>不必再拷贝</b>：分量在规范构造里已 {@code List.copyOf} 冻结，元素 {@link Optional} 不可变 ⇒ 返回字段本身是安全的 （SpotBugs
   * 也不报这一处：实测"强制重编后"它只报"抑制多余"）。★ 教训写在这里：判定 SpotBugs 结论前要先确证**编译产物是新的** ——本类曾因增量编译的旧 class
   * 连报三次同一个幽灵告警。
   */
  @SuppressFBWarnings("EI_EXPOSE_REP")
  @Override
  public List<Optional<String>> itemReasons() {
    return itemReasons;
  }

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

  /**
   * 一条条目的完整结果：<b>去向 + 可空理由</b>（裁定 8 补充的载体）。
   *
   * @param outcome 去向（{@link ItemOutcome}；非 null）
   * @param reason 理由；<b>{@code null} = 未说明理由</b>（空白串一并归一到 null——"没写"与"写了个空串"不该是两种形态）
   */
  public record ItemResult(ItemOutcome outcome, String reason) {

    public ItemResult {
      Objects.requireNonNull(outcome, "outcome");
      reason = reason == null || reason.isBlank() ? null : reason;
    }

    /** 有没有说明理由（渲染口径的分支判据：无理由 ⇒ 只报去向）。 */
    public boolean hasReason() {
      return reason != null;
    }
  }

  /**
   * 4 参兼容构造（本单之前写下的调用点逐字不动）：逐条理由缺省 = 全部"未说明理由"（{@code null}）。
   *
   * <p>它与 5 参规范构造是<b>同一个</b>对象的两种写法，不是两条真相——{@link #outcomes()} 与 {@link #reason()} 的行为逐字不变。
   */
  public CommitReceipt(
      Status status, String revisionRef, List<ItemOutcome> outcomes, String reason) {
    this(status, revisionRef, outcomes, null, reason);
  }

  public CommitReceipt {
    Objects.requireNonNull(status, "status");
    if (status == Status.COMMITTED && (revisionRef == null || revisionRef.isBlank())) {
      throw new IllegalArgumentException(
          "status=COMMITTED 时 revisionRef 不得为空白：" + "「恰好一条 revision」是 B-1 的一半，空引用等于落定了却没法对账");
    }
    Objects.requireNonNull(outcomes, "outcomes");
    reason = reason == null ? "" : reason;
    // ★ 防御性拷贝的顺序是刻意的：先把两个 List 都换成**深不可变**快照（List.copyOf ⇒ 元素不可再被外部改写），
    //   再赋值给字段。`itemReasons` 用 Optional<String> 元素就是为了能走 List.copyOf（"没写理由"由类型表达，
    //   而不是靠 null 元素——null 元素会让 List.copyOf 抛，也会让"可空"从类型里消失）。
    List<ItemOutcome> frozenOutcomes = List.copyOf(outcomes);
    List<Optional<String>> frozenReasons = normalizeReasons(frozenOutcomes.size(), itemReasons);
    outcomes = frozenOutcomes;
    itemReasons = frozenReasons;
  }

  /**
   * 逐条结果（并列读口）：与 {@link #outcomes()} <b>同序等长</b>，每条带上它的可空理由。
   *
   * <p>这是"每条去向 + 可空理由"的<b>唯一</b>读法（渲染面按它出文本；{@link #outcomes()} 保持冻结形状不变）。
   */
  public List<ItemResult> itemResults() {
    List<ItemResult> results = new ArrayList<>(outcomes.size());
    for (int i = 0; i < outcomes.size(); i++) {
      // 对外仍给可空 String（调用方不必到处拆 Optional）；"有没有"在内部由类型表达
      results.add(new ItemResult(outcomes.get(i), itemReasons.get(i).orElse(null)));
    }
    return List.copyOf(results);
  }

  /**
   * 逐条理由的规范化：缺省（{@code null}）⇒ 与 outcomes 等长的全 null 表；给了就必须与 outcomes 等长。
   *
   * <p>长度不齐 ⇒ 构造期抛 {@link IllegalArgumentException}：`第几条的理由是什么`已经不可知，猜一个对齐方式等于伪造审计。
   */
  private static List<Optional<String>> normalizeReasons(
      int outcomeCount, List<Optional<String>> given) {
    if (given == null) {
      return List.copyOf(Collections.nCopies(outcomeCount, Optional.<String>empty()));
    }
    if (given.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("itemReasons 不得含 null 元素：未说明理由请用 Optional.empty()（类型即契约）");
    }
    if (given.size() != outcomeCount) {
      throw new IllegalArgumentException(
          "itemReasons 必须与 outcomes 等长（逐条理由与逐条去向一一对应）：outcomes="
              + outcomeCount
              + " itemReasons="
              + given.size());
    }
    return List.copyOf(given);
  }
}
