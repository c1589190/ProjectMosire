package io.mosire.agentlib.approval;

import java.util.Optional;

/**
 * 收盘闸：永远 {@code empty} = 交给后面的人（编排器去问人）。
 *
 * <p><b>为什么留一个什么都不做的闸</b>：它是闸链的<b>显式终点</b>——链跑完没命中就该去问人，而"链跑完"这件事 在代码里必须有个可见的落点（{@code ConfirmGate}
 * 就是那句话），否则后来者加一条"链尾兜底放行"时没人看得见自己 越过了什么。
 *
 * <p>注意：{@code empty} <b>不是</b>放行（放行要显式 {@code APPROVE_*}）；编排器在无可用通道时会立即拒。
 */
public final class ConfirmGate implements ApprovalGate {

  /** 闸名（决议事件里的 {@code by}）。 */
  public static final String NAME = "ConfirmGate";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Optional<ApprovalDecision> decide(ApprovalRequest req) {
    return Optional.empty();
  }
}
