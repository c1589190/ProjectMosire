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
 *   <li>{@code resourceScopes}：第 7 维——<b>资源可达面</b>{@code 命名空间 → ResourceScope}（S5-B）。白名单管"能用哪个
 *       工具"，这一维管"这个工具能碰哪些资源"（工作目录围栏是 {@code fs} 命名空间的一个实例）。缺省 {@code null} ⇒ 空图 = 本层不表态（=
 *       本维引入前的行为）；<b>"未声明 ≠ 全拒"</b>，想表达"够不着"要显式写 {@link ResourceScope#none()}（语义全文见 {@link
 *       ResourceScopeMap}）。
 *   <li>{@code roles}：第 8 维——<b>身份角色</b>（§A 角色维的<b>调用者侧</b>，设计 §4.3）。空集 = ⊥ = "谁都不是"
 *       （<b>fail-closed</b>）：不能被任何"声明了角色的工具"调用。★ 与第 7 维的"未表态 = 不构成约束"<b>有意不同</b>——
 *       资源面是可达区域（可逐层回落），角色是<b>身份</b>（"我是什么"没有回落这一说）。缺省（6/7 参兼容构造）⇒ {@link
 *       RoleSet#none()}，既有调用点逐字不动。代数（{@code narrowTo}/{@code covers}）见 {@link RoleSet}。
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
    boolean readOnly,
    ResourceScopeMap resourceScopes,
    RoleSet roles) {

  /** 白名单通配符。 */
  public static final String ALL_TOOLS = "*";

  /** 七参兼容构造（{@code roles = RoleSet.none()}）：第 8 维之前写下的调用点逐字不动（副作用是 fail-closed 的 ⊥）。 */
  public AgentPermissionSet(
      AccessToken grantedToken,
      Set<String> allowedTools,
      Set<String> deniedTools,
      boolean destructiveAllowed,
      boolean sensitiveAllowed,
      boolean readOnly,
      ResourceScopeMap resourceScopes) {
    this(
        grantedToken,
        allowedTools,
        deniedTools,
        destructiveAllowed,
        sensitiveAllowed,
        readOnly,
        resourceScopes,
        null);
  }

  /** 六参兼容构造（{@code resourceScopes = 不限, roles = RoleSet.none()}）：第 7 维之前写下的调用点逐字不动。 */
  public AgentPermissionSet(
      AccessToken grantedToken,
      Set<String> allowedTools,
      Set<String> deniedTools,
      boolean destructiveAllowed,
      boolean sensitiveAllowed,
      boolean readOnly) {
    this(
        grantedToken,
        allowedTools,
        deniedTools,
        destructiveAllowed,
        sensitiveAllowed,
        readOnly,
        null,
        null);
  }

  public AgentPermissionSet {
    Objects.requireNonNull(grantedToken, "grantedToken");
    // 缺省 = 空图（本层不表态，本维引入前的行为）；"哪里都不许"必须由调用方显式写 none()，不能靠省略字段滑进来
    resourceScopes = resourceScopes == null ? ResourceScopeMap.empty() : resourceScopes;
    // 第 8 维：缺省 = ⊥（谁都不是，fail-closed）。与第 7 维的"缺省 = 不表态"有意不同——见类注释；
    // null 只出现在 6/7 参兼容构造的链上，是"没写角色"而不是"谁都能用"。
    roles = roles == null ? RoleSet.none() : roles;
    // 不可变快照；内联 Set.copyOf/Set.of（经 helper 间接赋值会让 SpotBugs 看不见不可变性来源而误报 EI_EXPOSE_REP）
    allowedTools =
        allowedTools == null || allowedTools.isEmpty()
            ? Set.of()
            : allowedTools.contains(ALL_TOOLS) ? Set.of(ALL_TOOLS) : Set.copyOf(allowedTools);
    deniedTools = deniedTools == null ? Set.of() : Set.copyOf(deniedTools);
  }

  /**
   * 系统级全授权：仅用于主 Agent 运行时自身的身份。
   *
   * <p><b>角色维 = ⊥</b>："运行时自身"不做身份表态，主 Agent 的角色由装配层从配置读出后经 {@link #withRoles} 显式挂上 （设计 §4.5：缺省空集 =
   * fail-closed）。这样"忘了配角色"的部署拿不到角色门工具，而不是把它们全开。
   */
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

  /** 只换资源可达面这一维（其余逐字不动，<b>角色维照抄</b>：收窄链上丢一环 = 静默改变身份）。 */
  public AgentPermissionSet withResourceScopes(ResourceScopeMap scopes) {
    return new AgentPermissionSet(
        grantedToken,
        allowedTools,
        deniedTools,
        destructiveAllowed,
        sensitiveAllowed,
        readOnly,
        scopes,
        roles);
  }

  /**
   * 只换角色这一维（其余逐字不动）。
   *
   * <p>装配层从配置 / 模板读出角色后挂到权限集上就走这里（设计 §4.5）：它刻意做成"逐字段转发"，避免手工重建时漏掉某一维 ——{@code roles} 是 fail-closed
   * 的 ⊥，漏掉不是"放宽"而是"静默降权"，但一样是收窄链丢环。
   */
  public AgentPermissionSet withRoles(RoleSet roles) {
    return new AgentPermissionSet(
        grantedToken,
        allowedTools,
        deniedTools,
        destructiveAllowed,
        sensitiveAllowed,
        readOnly,
        resourceScopes,
        roles);
  }

  /**
   * {@code fs} 命名空间的可达面（工作目录围栏的判定入口）。
   *
   * <p><b>未表态按"不限"读</b>：本维是限制层，"没写过目录"= 没设限（= 裁决 ① 的缺省不限）；要"哪里都不许"必须显式配 空数组 / {@code
   * ResourceScope.none()}。子体这一维由 {@code SubagentManager} 逐跳物化（继承父级 ∩ 模板 ∩ 请求），不会留空。
   */
  public ResourceScope fsScope() {
    return resourceScopes.scopeOrUnrestricted(ResourceScopeMap.FS);
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
    private ResourceScopeMap resourceScopes;
    private RoleSet roles;

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

    public Builder resourceScopes(ResourceScopeMap scopes) {
      resourceScopes = scopes;
      return this;
    }

    /** 角色维（不设 = {@link RoleSet#none()}，⊥ = 谁都不是，fail-closed）。 */
    public Builder roles(RoleSet value) {
      roles = value;
      return this;
    }

    public AgentPermissionSet build() {
      return new AgentPermissionSet(
          token,
          allowed,
          denied,
          destructiveAllowed,
          sensitiveAllowed,
          readOnly,
          resourceScopes,
          roles);
    }
  }
}
