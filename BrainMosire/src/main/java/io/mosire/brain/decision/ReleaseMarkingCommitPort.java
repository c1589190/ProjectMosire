package io.mosire.brain.decision;

import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.DecisionCommitPort;
import io.mosire.agentlib.decision.DecisionReleaseMarker;
import io.mosire.agentlib.decision.ResolvedCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <b>子体侧的提交口装饰器：给"执行被批子集"的每一条挂上放行标记</b>（设计 §5sexies⑥，P3d）。
 *
 * <p><b>为什么必须有这一层</b>：子体的工具全是父侧经 MCP 借给它的代理 ⇒ "执行被批条目"只能<b>再经一次 MCP</b>回到父侧；父侧若不认识 "这是包 P 的第 i
 * 条、已批且尚未执行"，就会<b>再问一遍人</b>（今天的形态），"执行在子体"就落不下去。标记就是那一声"我带着批准回来了"。
 *
 * <p><b>为什么是装饰器而不是改 {@code LocalDecisionCommitPort}</b>：后者是<b>本机执行者</b>（工具包侧的实现，也服务主进程那条链），它不该
 * 知道"跨进程怎么证明自己批过"这件事；而"包 id 从哪来"只有提交口这一层答得出（{@link DecisionCommitPort#commit} 的入参就是它）。
 * 装饰器把两件事接起来，且对既有装配零影响（不套它 = 逐字旧行为）。
 *
 * <p><b>不许改动的部分</b>：{@code calls} 的<b>顺序、条数与 index 原样透传</b>（回执逐条 {@code outcomes} 靠位置对齐，见 {@link
 * DecisionCommitPort} 的契约），也不在这里做任何判定——标记只是参数上的一个保留键，真伪由父侧的放行簿判。
 *
 * <p><b>密钥纪律</b>：标记里只有包 id 与条目序号；参数明文本来就在 {@link ResolvedCall#args()} 里（同一趟调用就地传给执行者，不进事件/日志）。
 */
public final class ReleaseMarkingCommitPort implements DecisionCommitPort {

  private final DecisionCommitPort delegate;

  /**
   * @param delegate 真正执行的那一个（本机形态 = {@code LocalDecisionCommitPort}；执行身份 = <b>本实例</b>，红线 R7）
   */
  public ReleaseMarkingCommitPort(DecisionCommitPort delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public CommitReceipt commit(String packageId, List<ResolvedCall> calls) {
    Objects.requireNonNull(packageId, "packageId");
    Objects.requireNonNull(calls, "calls");
    List<ResolvedCall> marked = new ArrayList<>(calls.size());
    for (ResolvedCall call : calls) {
      marked.add(
          new ResolvedCall(
              call.index(),
              call.toolName(),
              DecisionReleaseMarker.attach(call.args(), packageId, call.index())));
    }
    return delegate.commit(packageId, List.copyOf(marked));
  }
}
