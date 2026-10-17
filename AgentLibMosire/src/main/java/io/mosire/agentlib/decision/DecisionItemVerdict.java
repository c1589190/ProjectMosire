package io.mosire.agentlib.decision;

import java.util.Objects;

/**
 * 条目的稳定表态（U1，上游契约 {@code docs/upstream/agentlib-decision-contract.md} §3.2）。
 *
 * <p>它是 {@link DecisionPackageSnapshot#verdicts()} 的元素，也是宿主持久化的最小逐条裁决单元： {@link
 * DecisionPackages.ItemVerdict} 是旧的 Brain 内存适配器形状（含同一个 {@code index} 口径），
 * 本记录是通用协议形状。两者刻意分开，避免为了新协议改冻结的旧类型。
 *
 * <p><b>{@code index} 是稳定身份，不是序号</b>：包内允许 {@code [0, 2]} 这种带洞的 index 集合（remove 不重排、 append
 * 只取更大值），所以本记录不承诺 index 连续，只承诺它指向包内一条仍然存在的条目。
 *
 * <p><b>{@code by} 空白即拒</b>：一条查不出表态人的记录等于没有审计面。{@code note} 可空，空白串归一为空串（与既有 {@code ItemVerdict}
 * 同口径）。
 *
 * @param index 条目在包内的稳定身份（非负）
 * @param approved {@code true} = 批准，{@code false} = 驳回（没有第三种取值：未表态由"没有本记录"表达）
 * @param by 表态人（通道名 / 实例 id / 工具名；只进审计，不参与判定）
 * @param note 可空理由（只进内存态与审计面；{@code null} 归一为空串）
 */
public record DecisionItemVerdict(int index, boolean approved, String by, String note) {

  public DecisionItemVerdict {
    if (index < 0) {
      throw new IllegalArgumentException("index 不得为负（它是条目在包内的稳定身份）：" + index);
    }
    Objects.requireNonNull(by, "by");
    if (by.isBlank()) {
      throw new IllegalArgumentException("by 不得为空白（「谁表的态」必须能对账）");
    }
    note = note == null ? "" : note;
  }
}
