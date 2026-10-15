package io.mosire.agentlib.decision;

import io.mosire.agentlib.permission.AccessToken;
import java.util.List;
import java.util.Objects;

/**
 * 决策包——本责任区的<b>一等对象</b>（设计 §5.8 冻结形状；§5.1 的包形状）。
 *
 * <p>它是一次「提议 → 统一裁决 → 一次提交」的完整载体：{@link DecisionPackages} 负责状态迁移，
 * 本记录只承载"此刻的包长什么样"。每一次状态迁移都产生<b>新实例</b>（不可变值）， 所以调用方拿到的快照不会被并发改写（{@link DecisionPackages}
 * 的返回值都是快照）。
 *
 * <p><b>{@code id}</b>：由 {@link DecisionPackages} 发放，是包的身份、幂等键与对账键 （宿主落盘信封里的 id
 * 必须与它逐字相等，B-6）。它不得含明文参数/密钥（密钥纪律）。
 *
 * <p><b>{@code proposerKey}</b>：提议者的<b>实例级</b>身份（见 {@link ProposeRequest} 的说明）， 与 {@code
 * caller}（等级桶）正交。
 *
 * <p><b>{@code decision == null} = 未裁决</b>：{@link PackageState#PROPOSED} 的包一定是 {@code null}；
 * 裁决之后（{@link PackageState#DECIDED} 及其后的终局）保留那份 {@link Decision}—— 包括冲突后被退回的包：§5.6
 * 要求"裁决保留"，否则没人说得清"退回来的是哪次裁决"。
 *
 * <p><b>为什么要把 {@code createdAtEpochMs}/{@code deadlineEpochMs} 放进包</b>：超时是 fail-closed 的一环，
 * 而"到点"必须由包自身回答——只有包知道自己是什么时候被谁提的；把到点时刻存在外部表里， 就会出现"包在、到点记录不在"的半状态。
 *
 * @param id 包 id（本仓发放；幂等键与对账键；不得含明文参数/密钥）
 * @param proposerKey 提议者实例键（"一实例一未决包"的键）
 * @param caller 提议者等级桶
 * @param items 包内条目（不可变快照；顺序 = 提议顺序，回执的逐条 outcomes 与它同序）
 * @param createdAtEpochMs 建包时刻（墙钟毫秒）
 * @param deadlineEpochMs 裁决到点时刻（墙钟毫秒；必须为正）
 * @param state 当刻状态
 * @param decision 统一裁决（{@code null} = 尚未裁决；非 {@code PROPOSED} 态保留裁决快照）
 */
public record DecisionPackage(
    String id,
    String proposerKey,
    AccessToken caller,
    List<PackageItem> items,
    long createdAtEpochMs,
    long deadlineEpochMs,
    PackageState state,
    Decision decision) {

  public DecisionPackage {
    Objects.requireNonNull(id, "id");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id 不得为空白（它是包的幂等键与对账键）");
    }
    Objects.requireNonNull(proposerKey, "proposerKey");
    if (proposerKey.isBlank()) {
      throw new IllegalArgumentException("proposerKey 不得为空白");
    }
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(items, "items");
    for (int i = 0; i < items.size(); i++) {
      Objects.requireNonNull(items.get(i), "items[" + i + "]");
    }
    items = List.copyOf(items);
    if (deadlineEpochMs <= 0) {
      throw new IllegalArgumentException("deadlineEpochMs 必须为正（墙钟毫秒）：" + deadlineEpochMs);
    }
    Objects.requireNonNull(state, "state");
    if (state == PackageState.PROPOSED && decision != null) {
      // 两个字段表达同一件事（裁没裁），不一致的对象会让"谁在等裁决"出现两个答案
      throw new IllegalArgumentException("state=PROPOSED 时 decision 必须为 null（PROPOSED = 还没人裁）");
    }
  }
}
