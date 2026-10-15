package io.mosire.agentlib.decision;

import io.mosire.agentlib.permission.AccessToken;
import java.util.List;
import java.util.Objects;

/**
 * 一次提议（设计 §5.8 冻结形状；粒度见 §5.1）。
 *
 * <p><b>粒度：一个子 Agent 实例一个包，不跨实例合包</b>（U6）。{@code proposerKey} 就是这条粒度的键：
 * 取值口径由宿主定，但必须是<b>实例级</b>身份（{@code AgentIdentity#instanceId()}：主 Agent {@code main}、 子 Agent {@code
 * <模板id>-<hex>}、外部面 {@code external-mcp}）。用等级桶当键会让 "一实例最多一个未决包"退化成"一桶最多一个"——同桶的另一个子 Agent
 * 被误挡，而同一实例的两次提议 反而可能并行（桶相同只是巧合），"防止同一实例两次提议互相覆盖"就落空了。
 *
 * <p>{@code caller} 是等级<b>桶</b>（{@link AccessToken}），与 {@code proposerKey} 正交：桶回答"它是什么等级"，
 * 键回答"它是哪个实例"——与审批内核里 {@code callerKey}（桶）/ {@code requesterId}（实例）同一套分工。
 *
 * <p>{@code deadlineEpochMs} 是<b>裁决</b>的到点时刻（墙钟毫秒）：到点仍没人裁 ⇒ 整包 EXPIRED、零落盘 （§5.6，fail-closed
 * 不静默执行）。必须为正——{@code 0}/{@code 负数}多半是"忘了设"， 一个"永远不到点"的包会把"超时"这条 fail-closed 出口悄悄关掉。
 *
 * @param proposerKey 提议者实例键（"一实例一未决包"的键；空白 = 拒）
 * @param caller 提议者等级桶（只进内存态与审计）
 * @param items 包内条目（至少一条；index 逐包唯一）
 * @param deadlineEpochMs 裁决到点时刻（墙钟毫秒；必须为正）
 */
public record ProposeRequest(
    String proposerKey, AccessToken caller, List<PackageItem> items, long deadlineEpochMs) {

  public ProposeRequest {
    Objects.requireNonNull(proposerKey, "proposerKey");
    if (proposerKey.isBlank()) {
      throw new IllegalArgumentException("proposerKey 不得为空白（它是「一个实例一个未决包」的键）");
    }
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(items, "items");
    for (int i = 0; i < items.size(); i++) {
      Objects.requireNonNull(items.get(i), "items[" + i + "]");
    }
    items = List.copyOf(items);
    if (deadlineEpochMs <= 0) {
      throw new IllegalArgumentException(
          "deadlineEpochMs 必须为正（墙钟毫秒）：" + deadlineEpochMs + "——没有到点时刻就没有超时出口");
    }
  }
}
