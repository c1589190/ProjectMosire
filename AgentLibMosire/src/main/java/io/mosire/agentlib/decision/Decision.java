package io.mosire.agentlib.decision;

import java.util.Objects;
import java.util.Set;

/**
 * 一次统一裁决 + 逐条例外（设计 §5.8 冻结形状；粒度见 §5.2 的 U6 裁定）。
 *
 * <p><b>缺席 = 驳回</b>：{@link #approvedIndexes()} 里<b>没有</b>的条目一律视为被驳、零落盘。 这个方向是刻意选的 fail-closed ——
 * 人只勾了 4 条，第 5 条不该因为"没被写进拒绝名单"而被执行； 反过来（缺席 = 放行）会让"漏勾"变成"静默执行"，而漏勾在提示面上与"我懒得勾"长得一模一样。
 *
 * <p><b>为什么一次裁决能逐条不同、却仍然是"一次提交"</b>：U6 要的是"选择性执行 + 驳回一部分"，
 * 但结果必须<b>统一</b>——被批的那个子集仍然是一次提交、要么全落要么全不落（不变量 B-1/B-2）。 所以本记录只表达"哪几条被批"，<b>不</b>表达"分几次落"：分几次由
 * {@link DecisionPackages#commit} 收口成恰好一次。
 *
 * <p><b>{@code by} = 「谁批的」</b>：与 {@link DecisionPackage#proposerKey()}、{@link DecisionPackage#id()}
 * 一起构成对账三元组（设计 §2.2 约束 3 要求能回答"谁批的、批了什么"）。空白 {@code by} 在构造期就拒—— 一个查不出裁决人的包等于没有审计面。
 *
 * <p><b>{@code note} 与 {@code args} 不同档</b>：它只进内存态提示面，不进事件面（设计 §5.5 的载荷清单里没有它）。 宿主填它时不得把明文参数/密钥抄进去。
 *
 * @param approvedIndexes 被批条目的 index 集合（不在其中的 = 被驳；{@code null} 视作空集 = 全驳）
 * @param by 裁决人（通道名 / 闸名 / 上级 Agent 的实例名；只进审计，不参与判定）
 * @param note 裁决说明（只进内存态提示面，不进事件面；{@code null} 视作空串）
 */
public record Decision(Set<Integer> approvedIndexes, String by, String note) {

  public Decision {
    if (approvedIndexes == null) {
      approvedIndexes = Set.of();
    } else {
      for (Integer index : approvedIndexes) {
        if (index == null) {
          // null 不是任何条目的身份；静默丢掉它等于把"批了 null"洗成"没批"，属于宿主 bug，必须在构造期点名
          throw new IllegalArgumentException("approvedIndexes 含 null：null 不是条目身份（被驳用'缺席'表达）");
        }
      }
      approvedIndexes = Set.copyOf(approvedIndexes);
    }
    Objects.requireNonNull(by, "by");
    if (by.isBlank()) {
      throw new IllegalArgumentException("by 不得为空白（「谁批的」必须能对账）");
    }
    note = note == null ? "" : note;
  }

  /** 本条 index 是否被批（不在 {@link #approvedIndexes()} 里 = 被驳，见类注的 fail-closed 方向）。 */
  public boolean approves(int index) {
    return approvedIndexes.contains(index);
  }
}
