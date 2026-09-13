package io.mosire.agentlib.permission;

import java.util.Optional;

/**
 * 权限判定（纯函数，供 {@code ToolExecutionGuard} 与子 Agent 单调性守卫共用）。
 *
 * <p>判定规则（顺序即优先级）：
 *
 * <ol>
 *   <li>denied 名单命中 → 拒绝（显式拒绝优先级最高）；
 *   <li>白名单存在且不命中（含通配） → 拒绝（deny-default）；
 *   <li>身份级别不足 → 拒绝（工具要求的级别只能由更高身份满足）；
 *   <li>工具标记 sensitive/destructive 而权限集未显式放行 → 拒绝（终局拒绝，不走审批流—— ApprovalGate 在 M2 经 AG-UI 补）。
 * </ol>
 */
public final class PermissionChecker {

  private PermissionChecker() {}

  /**
   * 判定一次调用；拒绝时返回原因，允许时返回空。
   *
   * @param permissions 调用者权限集
   * @param toolName 工具名
   * @param spec 工具三要素（null 视为 {@link ToolSpec#DEFAULT}）
   */
  public static Optional<String> denialReason(
      AgentPermissionSet permissions, String toolName, ToolSpec spec) {
    if (permissions == null) {
      return Optional.of("无权限上下文（权限集为空）");
    }
    ToolSpec s = spec == null ? ToolSpec.DEFAULT : spec;
    if (permissions.readOnly()) {
      return Optional.of("只读 Agent 不允许执行工具 " + toolName);
    }
    if (permissions.deniedTools().contains(toolName)) {
      return Optional.of("工具 " + toolName + " 在 denied-tools 名单中");
    }
    if (!permissions.isToolAllowed(toolName)) {
      return Optional.of("工具 " + toolName + " 不在 allowed-tools 白名单内");
    }
    if (!permissions.grantedToken().atLeast(s.requiredLevel())) {
      return Optional.of(
          "身份级别不足：工具 "
              + toolName
              + " 要求 "
              + s.requiredLevel()
              + "，调用者为 "
              + permissions.grantedToken());
    }
    if (sensitiveOrDestroyed(permissions, s)) {
      return Optional.of("工具 " + toolName + (s.destructive() ? " 为破坏性操作，需显式放行" : " 为敏感操作，需显式放行"));
    }
    return Optional.empty();
  }

  private static boolean sensitiveOrDestroyed(AgentPermissionSet permissions, ToolSpec spec) {
    return (spec.sensitive() && !permissions.sensitiveAllowed())
        || (spec.destructive() && !permissions.destructiveAllowed());
  }

  /**
   * 权限单调性判定：{@code granted}（子 Agent / 会话）是否 ⊆ {@code parent}（父级）。
   *
   * <p>红线（AGENTS.md 第 1 条）的公共校验器：子 Agent 不能获得父级没有的任何能力。各维度：
   *
   * <ul>
   *   <li>身份级别：子级不可高于父级；
   *   <li>白名单：父级通配（无限制）时子级任意；否则子级不得通配且其白名单 ⊆ 父级白名单 （子级空白名单=拒绝全部=更收缩，允许）；
   *   <li>显式拒绝：父级拒绝集合 ⊆ 子级拒绝集合（子级不得重新放行父级禁止的工具）；
   *   <li>敏感/破坏放行：子级放行 ⇒ 父级也已放行；
   *   <li>只读：父级只读 ⇒ 子级必须只读（只读是收缩；子级主动更只读总是允许）；
   *   <li>资源可达面（S5-B 第 7 维）：父级表态过的每个命名空间，子级都不得更宽（{@link ResourceScopeMap#covers}）。
   *       父级没表态的命名空间不构成约束（子级主动更窄总是允许）。
   * </ul>
   *
   * @param granted 被授予（较低/子）权限集
   * @param parent 父级权限集
   */
  public static boolean isSubset(AgentPermissionSet granted, AgentPermissionSet parent) {
    if (granted == null || parent == null) {
      return false;
    }
    // 身份级别：子级不可高于父级
    if (!parent.grantedToken().atLeast(granted.grantedToken())) {
      return false;
    }
    // 白名单：父级通配时子级任意；否则子级不得通配且 ⊆ 父级
    if (parent.allowedTools().contains(AgentPermissionSet.ALL_TOOLS)) {
      // 父级不设白名单限制 → 子级任意白名单（包括通配）都 ⊆ 父级，无需检查
    } else {
      if (granted.allowedTools().contains(AgentPermissionSet.ALL_TOOLS)) {
        return false; // 子级通配 = 可用任意工具，超出父级白名单
      }
      if (!parent.allowedTools().containsAll(granted.allowedTools())) {
        return false;
      }
    }
    // 显式拒绝：不能洗掉父级的拒绝
    if (!granted.deniedTools().containsAll(parent.deniedTools())) {
      return false;
    }
    // 破坏性/敏感放行不可多于父级
    if (granted.destructiveAllowed() && !parent.destructiveAllowed()) {
      return false;
    }
    if (granted.sensitiveAllowed() && !parent.sensitiveAllowed()) {
      return false;
    }
    // 只读：父级只读 ⇒ 子级只读
    if (parent.readOnly() && !granted.readOnly()) {
      return false;
    }
    // 资源可达面（第 7 维）：父级表过态的命名空间，子级一个也不许更宽
    if (!parent.resourceScopes().covers(granted.resourceScopes())) {
      return false;
    }
    return true;
  }
}
