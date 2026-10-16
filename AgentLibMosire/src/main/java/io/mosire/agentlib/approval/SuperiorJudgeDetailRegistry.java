package io.mosire.agentlib.approval;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SuperiorJudgeDetails} 的<b>内存态实现</b>：按<b>审批请求 id</b> 登记该请求的逐条明细，判完即释放。
 *
 * <p><b>它存在的理由</b>：明细（含 {@code args} 明文）不能挂在 {@link ApprovalRequest} 上（见 {@link
 * SuperiorJudgeDetails} 的说明），而"谁有明细"只有<b>造这条请求的人</b>知道——决策包桥在把包投成一条请求时登记，判定闸在判定时读取。
 * 两边由装配层接在<b>同一个实例</b>上（{@code App} 各传一份引用），所以这里没有静态状态，也不做单例。
 *
 * <p><b>生命周期 = 一条请求的投递窗口</b>：{@code register} → 判定 → {@code release}（桥放在 {@code finally} 里）。 释放之后
 * {@link #itemsFor} 返回空表 ⇒ 判定退回只看 {@code summary}；这既是"明文不留内存"，也保证 迟到的读者不会拿到上一个包的内容——键是请求
 * id，<b>不复用</b>（每次投递都新生成一个 id）。
 *
 * <p><b>线程安全</b>：{@link ConcurrentHashMap}；投递在虚拟线程上、判定在编排器的调用栈上（同一条投递线程），
 * 但读取口对任何线程开放，所以不做"只有自己线程看得见"的假设。
 *
 * <p><b>不落库、不进日志</b>：本类只持有引用，一行内容都不打印（密钥纪律）。{@link #size()} 只回答"现在有几条在途明细"， 供装配诊断用，不带内容。
 */
public final class SuperiorJudgeDetailRegistry implements SuperiorJudgeDetails {

  private static final Logger LOG = LoggerFactory.getLogger(SuperiorJudgeDetailRegistry.class);

  private final Map<String, List<JudgementItem>> byRequestId = new ConcurrentHashMap<>();

  /**
   * 登记一条请求的明细（同 id 重复登记 ⇒ 覆盖：id 由投递面新生成，重复只可能是同一投递的重入）。
   *
   * @param requestId 审批请求 id（{@link ApprovalRequest#id()}）
   * @param items 逐条明细（非 null；空表 = 这条请求没有明细）
   */
  public void register(String requestId, List<JudgementItem> items) {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(items, "items");
    List<JudgementItem> snapshot = List.copyOf(items);
    byRequestId.put(requestId, snapshot);
    LOG.debug("判定明细已登记 requestId={} items={}（明文只在内存态，判定后立刻释放）", requestId, snapshot.size());
  }

  /**
   * 释放一条请求的明细（幂等；未知 id 是无操作——释放从来不该抛，它跑在 {@code finally} 里）。
   *
   * @param requestId 审批请求 id
   */
  public void release(String requestId) {
    if (requestId == null) {
      return;
    }
    byRequestId.remove(requestId);
  }

  @Override
  public List<JudgementItem> itemsFor(ApprovalRequest request) {
    if (request == null || request.id() == null) {
      return List.of();
    }
    List<JudgementItem> items = byRequestId.get(request.id());
    return items == null ? List.of() : items;
  }

  /** 当前在途明细条数（装配诊断用；不含内容）。 */
  public int size() {
    return byRequestId.size();
  }
}
