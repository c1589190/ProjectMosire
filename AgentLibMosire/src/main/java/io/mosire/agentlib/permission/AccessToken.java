package io.mosire.agentlib.permission;

/**
 * 调用者身份令牌——三维权限模型（计划 D9）的"身份"维度，三级单调递增。
 *
 * <ul>
 *   <li>{@link #GUEST}：未认证/匿名调用（网关下游、只读查询）；
 *   <li>{@link #DEFAULT}：主 Agent 与子 Agent 的运行身份；
 *   <li>{@link #SYSTEM}：仅 Agent 运行时自身（进程生命周期等红线工具）。
 * </ul>
 */
public enum AccessToken {
  GUEST(0),
  DEFAULT(1),
  SYSTEM(2);

  private final int rank;

  AccessToken(int rank) {
    this.rank = rank;
  }

  /** 本令牌是否至少达到 {@code other} 的级别（用于"工具要求 X 级 → 调用者是否够格"）。 */
  public boolean atLeast(AccessToken other) {
    return rank >= other.rank;
  }
}
