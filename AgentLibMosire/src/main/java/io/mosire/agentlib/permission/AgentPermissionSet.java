package io.mosire.agentlib.permission;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 每 Agent 权限集——三维权限模型（计划 D9）的"授权"维度。
 *
 * <p>字段语义（按计划 §3.4）：
 *
 * <ul>
 *   <li>{@code allowedTools}：白名单；含 {@link #ALL_TOOLS} 通配表示"不做白名单限制"。
 *       注意：白名单之外默认拒绝（deny-default），因此"空集合"= 不给任何工具，而非"随便用"。
 *   <li>{@code deniedTools}：显式拒绝（优先于白名单）。
 *   <li>{@code destructiveAllowed}/{@code sensitiveAllowed}：对工具三要素的显式放行。
 *   <li>{@code readOnly}：只读 Agent（不得执行任何写入/敏感工具——M2 起子 Agent 使用）。
 * </ul>
 *
 * 所有集合均按不可变快照保存。
 */
public record AgentPermissionSet(
    AccessToken grantedToken,
    Set<String> allowedTools,
    Set<String> deniedTools,
    boolean destructiveAllowed,
    boolean sensitiveAllowed,
    boolean readOnly) {

  /** 白名单通配符。 */
  public static final String ALL_TOOLS = "*";

  public AgentPermissionSet {
    Objects.requireNonNull(grantedToken, "grantedToken");
    // 不可变快照；内联 Set.copyOf/Set.of（经 helper 间接赋值会让 SpotBugs 看不见不可变性来源而误报 EI_EXPOSE_REP）
    allowedTools =
        allowedTools == null || allowedTools.isEmpty()
            ? Set.of()
            : allowedTools.contains(ALL_TOOLS) ? Set.of(ALL_TOOLS) : Set.copyOf(allowedTools);
    deniedTools = deniedTools == null ? Set.of() : Set.copyOf(deniedTools);
  }

  /** 系统级全授权：仅用于主 Agent 运行时自身的身份。 */
  public static AgentPermissionSet system() {
    return new AgentPermissionSet(
        AccessToken.SYSTEM, Set.of(ALL_TOOLS), Set.of(), true, true, false);
  }

  /** 全授权但身份为 {@code token}（无白名单限制）；子 Agent 默认按此再收紧。 */
  public static AgentPermissionSet unrestricted(AccessToken token) {
    return new AgentPermissionSet(token, Set.of(ALL_TOOLS), Set.of(), true, true, false);
  }

  /** 白名单判断：允许则 true。 */
  public boolean isToolAllowed(String toolName) {
    return allowedTools.contains(ALL_TOOLS) || allowedTools.contains(toolName);
  }

  public static Builder builder(AccessToken token) {
    return new Builder(token);
  }

  public static final class Builder {
    private AccessToken token;
    private final Set<String> allowed = new HashSet<>();
    private final Set<String> denied = new HashSet<>();
    private boolean destructiveAllowed;
    private boolean sensitiveAllowed;
    private boolean readOnly;

    private Builder(AccessToken token) {
      this.token = Objects.requireNonNull(token, "token");
    }

    public Builder allowAll() {
      allowed.add(ALL_TOOLS);
      return this;
    }

    public Builder allow(String... toolNames) {
      for (String toolName : toolNames) {
        allowed.add(toolName);
      }
      return this;
    }

    public Builder deny(String... toolNames) {
      for (String toolName : toolNames) {
        denied.add(toolName);
      }
      return this;
    }

    public Builder destructiveAllowed(boolean value) {
      destructiveAllowed = value;
      return this;
    }

    public Builder sensitiveAllowed(boolean value) {
      sensitiveAllowed = value;
      return this;
    }

    public Builder readOnly(boolean value) {
      readOnly = value;
      return this;
    }

    public AgentPermissionSet build() {
      return new AgentPermissionSet(
          token, allowed, denied, destructiveAllowed, sensitiveAllowed, readOnly);
    }
  }
}
