package io.mosire.agentlib.permission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PermissionCheckerTest {

  @Test
  void deniesWhenWhitelistDoesNotContainTool() {
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("allowed_tool").build();
    assertThat(PermissionChecker.denialReason(permissions, "other_tool", ToolSpec.DEFAULT))
        .hasValueSatisfying(reason -> assertThat(reason).contains("白名单"));
  }

  @Test
  void deniesWhenDeniedListHitsEvenIfAllowed() {
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().deny("dangerous_tool").build();
    assertThat(PermissionChecker.denialReason(permissions, "dangerous_tool", ToolSpec.DEFAULT))
        .hasValueSatisfying(reason -> assertThat(reason).contains("denied-tools"));
  }

  @Test
  void deniesWhenTokenLevelInsufficient() {
    AgentPermissionSet guest = AgentPermissionSet.builder(AccessToken.GUEST).allowAll().build();
    assertThat(
            PermissionChecker.denialReason(guest, "spawn_tool", ToolSpec.level(AccessToken.SYSTEM)))
        .hasValueSatisfying(reason -> assertThat(reason).contains("级别不足"));
  }

  @Test
  void deniesDestructiveWithoutExplicitAllowance() {
    AgentPermissionSet permissions =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
    assertThat(PermissionChecker.denialReason(permissions, "rm", ToolSpec.DEFAULT))
        .isEmpty(); // 工具未声明破坏性 → 放行
    ToolSpec destructive = ToolSpec.level(AccessToken.GUEST, false, true);
    assertThat(PermissionChecker.denialReason(permissions, "rm", destructive))
        .hasValueSatisfying(reason -> assertThat(reason).contains("破坏性"));
    AgentPermissionSet allowed =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().destructiveAllowed(true).build();
    assertThat(PermissionChecker.denialReason(allowed, "rm", destructive)).isEmpty();
  }

  @Test
  void deniesEverythingForReadOnlyAgent() {
    AgentPermissionSet readOnly =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().readOnly(true).build();
    assertThat(PermissionChecker.denialReason(readOnly, "any_tool", ToolSpec.DEFAULT))
        .hasValueSatisfying(reason -> assertThat(reason).contains("只读"));
  }

  // ---- 单调性（红线：子集关系） ----

  @Test
  void subsetSimpleRestrictions() {
    AgentPermissionSet parent = AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
    AgentPermissionSet child =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("tool_a").build();
    assertThat(PermissionChecker.isSubset(child, parent)).isTrue();
  }

  @Test
  void subsetRejectsHigherToken() {
    AgentPermissionSet parent = AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
    AgentPermissionSet child = AgentPermissionSet.builder(AccessToken.SYSTEM).allowAll().build();
    assertThat(PermissionChecker.isSubset(child, parent)).isFalse();
  }

  @Test
  void subsetRejectsWildcardAgainstParentWhitelist() {
    AgentPermissionSet parent =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("tool_a", "tool_b").build();
    AgentPermissionSet child = AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
    assertThat(PermissionChecker.isSubset(child, parent)).isFalse();
  }

  @Test
  void subsetRejectsLiftingParentDenial() {
    AgentPermissionSet parent =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().deny("rm").build();
    AgentPermissionSet child = AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
    assertThat(PermissionChecker.isSubset(child, parent)).isFalse();
    AgentPermissionSet childWithDeny =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().deny("rm").build();
    assertThat(PermissionChecker.isSubset(childWithDeny, parent)).isTrue();
  }

  @Test
  void subsetRejectsExtraDestructiveAllowance() {
    AgentPermissionSet parent = AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
    AgentPermissionSet child =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().destructiveAllowed(true).build();
    assertThat(PermissionChecker.isSubset(child, parent)).isFalse();
    AgentPermissionSet both =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().destructiveAllowed(true).build();
    assertThat(PermissionChecker.isSubset(both, both)).isTrue();
  }

  @Test
  void subsetAllowsMoreReadOnlyChild() {
    AgentPermissionSet parent = AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
    AgentPermissionSet child =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().readOnly(true).build();
    assertThat(PermissionChecker.isSubset(child, parent)).isTrue();
    // 父只读 → 子也必须只读
    AgentPermissionSet readOnlyParent =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().readOnly(true).build();
    assertThat(PermissionChecker.isSubset(parent, readOnlyParent)).isFalse();
  }
}
