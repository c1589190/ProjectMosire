package io.mosire.agentlib.permission;

import java.util.Objects;

/**
 * 工具三要素——工具本身的权限元数据（计划 D9 的"工具"维度）：
 *
 * <ul>
 *   <li>{@code requiredLevel}：最低身份级别；
 *   <li>{@code sensitive}：敏感动作（向用户发送消息/写文件），默认拒绝、需显式放行；
 *   <li>{@code destructive}：破坏性动作（删除/覆盖/杀进程），默认拒绝。
 * </ul>
 */
public record ToolSpec(AccessToken requiredLevel, boolean sensitive, boolean destructive) {

  /** 常规工具（guest 一级即可用、非敏感非破坏）。 */
  public static final ToolSpec DEFAULT = new ToolSpec(AccessToken.GUEST, false, false);

  public ToolSpec {
    Objects.requireNonNull(requiredLevel, "requiredLevel");
  }

  public static ToolSpec level(AccessToken requiredLevel) {
    return new ToolSpec(requiredLevel, false, false);
  }

  public static ToolSpec level(AccessToken requiredLevel, boolean sensitive, boolean destructive) {
    return new ToolSpec(requiredLevel, sensitive, destructive);
  }
}
