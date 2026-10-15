package io.mosire.agentlib.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
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

  // ---- 单调性第 7 维：资源可达面（S5-B） ----

  @Test
  void subsetChecksResourceScopeDimension() {
    AgentPermissionSet parent = unrestrictedWithFs(ResourceScope.of("/srv"));
    assertThat(
            PermissionChecker.isSubset(unrestrictedWithFs(ResourceScope.of("/srv/work")), parent))
        .isTrue();
    assertThat(PermissionChecker.isSubset(unrestrictedWithFs(ResourceScope.of("/data")), parent))
        .isFalse();
    assertThat(PermissionChecker.isSubset(unrestrictedWithFs(ResourceScope.none()), parent))
        .isTrue();
    assertThat(PermissionChecker.isSubset(unrestrictedWithFs(ResourceScope.unlimited()), parent))
        .isFalse();
  }

  @Test
  void subsetRejectsResourceWideningEvenWhenToolDimensionsAreNarrower() {
    // 判别性形状：工具维全都更窄，只有资源维更宽——必须被资源那一维拦下（否则"换个维度提权"就成立了）
    AgentPermissionSet parent = unrestrictedWithFs(ResourceScope.of("/srv/work"));
    AgentPermissionSet child =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow("echo")
            .resourceScopes(ResourceScopeMap.of(ResourceScopeMap.FS, ResourceScope.of("/srv")))
            .build();
    assertThat(PermissionChecker.isSubset(child, parent)).isFalse();
  }

  @Test
  void subsetTreatsChildSilenceOnADeclaredNamespaceAsTooWide() {
    AgentPermissionSet parent = unrestrictedWithFs(ResourceScope.of("/srv"));
    // 子级这一维留空 = "本层不表态"（运行期会落到工具默认），无从证明不比父级宽 ⇒ fail-closed
    assertThat(
            PermissionChecker.isSubset(
                AgentPermissionSet.unrestricted(AccessToken.DEFAULT), parent))
        .isFalse();
  }

  @Test
  void subsetIgnoresResourceNamespacesTheParentSilences() {
    AgentPermissionSet parent = AgentPermissionSet.unrestricted(AccessToken.DEFAULT);
    AgentPermissionSet child = unrestrictedWithFs(ResourceScope.of("/srv"));
    assertThat(PermissionChecker.isSubset(child, parent)).isTrue();
  }

  // ---- 判定第 8 维：角色（§A 角色维，设计 §4.4） ----

  @Test
  void undeclaredToolRolesLeaveEveryCallerVerdictByteIdenticalThroughTheThreeArgPath() {
    // A-2（三参路）：不覆写 roles() 的工具（= 今天全部既有工具）声明集为空 ⇒ 本维不表态。
    // 这里逐条对照“本维引入前”的判定：任意调用者（含角色 ⊥ 的缺省）都不得因角色被拒。
    AgentPermissionSet bottomCaller =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
    assertThat(bottomCaller.roles()).as("调用者侧缺省 = ⊥").isEqualTo(RoleSet.none());
    assertThat(PermissionChecker.denialReason(bottomCaller, "echo", ToolSpec.DEFAULT)).isEmpty();
    assertThat(PermissionChecker.denialReason(bottomCaller, "echo", ToolSpec.DEFAULT, Set.of()))
        .as("三参与显式空声明同结果")
        .isEmpty();
    assertThat(PermissionChecker.denialReason(bottomCaller, "echo", ToolSpec.DEFAULT, null))
        .as("null 与空集同读“不表态”")
        .isEmpty();

    AgentPermissionSet gmCaller = unrestrictedWithRoles("GM");
    assertThat(PermissionChecker.denialReason(gmCaller, "echo", ToolSpec.DEFAULT)).isEmpty();

    // “不表态”只免除本维，不得把既有维度的拒放宽——逐维对照本维引入前的拒因
    AgentPermissionSet whitelistMissed =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("other").build();
    assertThat(PermissionChecker.denialReason(whitelistMissed, "echo", ToolSpec.DEFAULT))
        .hasValueSatisfying(reason -> assertThat(reason).contains("白名单"));
    AgentPermissionSet deniedList =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().deny("echo").build();
    assertThat(PermissionChecker.denialReason(deniedList, "echo", ToolSpec.DEFAULT))
        .hasValueSatisfying(reason -> assertThat(reason).contains("denied-tools"));
    AgentPermissionSet readOnly =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().readOnly(true).build();
    assertThat(PermissionChecker.denialReason(readOnly, "echo", ToolSpec.DEFAULT))
        .hasValueSatisfying(reason -> assertThat(reason).contains("只读"));
    AgentPermissionSet lowToken = AgentPermissionSet.builder(AccessToken.GUEST).allowAll().build();
    assertThat(
            PermissionChecker.denialReason(
                lowToken, "spawn", ToolSpec.level(AccessToken.SYSTEM), Set.of()))
        .hasValueSatisfying(reason -> assertThat(reason).contains("级别不足"));
    ToolSpec destructive = ToolSpec.level(AccessToken.GUEST, false, true);
    assertThat(PermissionChecker.denialReason(bottomCaller, "rm", destructive, Set.of()))
        .hasValueSatisfying(reason -> assertThat(reason).contains("破坏性"));
  }

  @Test
  void declaredRolesRequireANonEmptyIntersectionWithTheCallerRoles() {
    AgentPermissionSet bottom = AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build();
    assertThat(PermissionChecker.denialReason(bottom, "gm_tool", ToolSpec.DEFAULT, Set.of("GM")))
        .as("调用者 ⊥ + 工具声明非空 ⇒ 拒（A-3 的纯函数面）")
        .hasValueSatisfying(reason -> assertThat(reason).contains("角色不相交"));

    AgentPermissionSet gm = unrestrictedWithRoles("GM");
    assertThat(PermissionChecker.denialReason(gm, "gm_tool", ToolSpec.DEFAULT, Set.of("GM")))
        .isEmpty();
    assertThat(PermissionChecker.denialReason(gm, "gm_tool", ToolSpec.DEFAULT, Set.of("DM")))
        .hasValueSatisfying(reason -> assertThat(reason).contains("角色不相交"));
    assertThat(
            PermissionChecker.denialReason(
                unrestrictedWithRoles("DM", "GM"), "gm_tool", ToolSpec.DEFAULT, Set.of("GM")))
        .as("多角色调用者命中其一即相交")
        .isEmpty();
    assertThat(PermissionChecker.denialReason(gm, "gm_tool", ToolSpec.DEFAULT, List.of(" GM ")))
        .as("声明侧名字去空白后同口径（大小写仍敏感）")
        .isEmpty();
    assertThat(PermissionChecker.denialReason(gm, "gm_tool", ToolSpec.DEFAULT, List.of(" ")))
        .as("声明侧只含空白名 ⇒ 不匹配任何人 ⇒ 拒（fail-closed）")
        .hasValueSatisfying(reason -> assertThat(reason).contains("角色不相交"));
    assertThat(PermissionChecker.denialReason(gm, "gm_tool", ToolSpec.DEFAULT, List.of("GM", " ")))
        .as("空白项不影响同一集合里的有效项")
        .isEmpty();
  }

  @Test
  void roleCheckSitsAfterWhitelistAndBeforeSensitiveOrDestructive() {
    ToolSpec sensitive = ToolSpec.level(AccessToken.GUEST, true, false);

    // 白名单/黑名单/只读先于角色：角色已相交也照旧报既有拒因
    AgentPermissionSet gmOutsideWhitelist =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow("other")
            .build()
            .withRoles(RoleSet.of("GM"));
    assertThat(
            PermissionChecker.denialReason(
                gmOutsideWhitelist, "gm_tool", ToolSpec.DEFAULT, Set.of("GM")))
        .hasValueSatisfying(reason -> assertThat(reason).contains("白名单"));
    AgentPermissionSet gmDenied =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allowAll()
            .deny("gm_tool")
            .build()
            .withRoles(RoleSet.of("GM"));
    assertThat(PermissionChecker.denialReason(gmDenied, "gm_tool", ToolSpec.DEFAULT, Set.of("GM")))
        .hasValueSatisfying(reason -> assertThat(reason).contains("denied-tools"));

    // 角色先于敏感：相交但未放行 ⇒ 报敏感；不相交但已放行 ⇒ 仍报角色（这一对把相对顺序钉住）
    // 注意不能用 unrestricted(...)（它 destructive/sensitive 都放行），否则“敏感未放行”这个前提不成立
    AgentPermissionSet gmSensitiveNotAllowed =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allowAll()
            .build()
            .withRoles(RoleSet.of("GM"));
    assertThat(
            PermissionChecker.denialReason(
                gmSensitiveNotAllowed, "gm_tool", sensitive, Set.of("GM")))
        .hasValueSatisfying(reason -> assertThat(reason).contains("敏感"));
    AgentPermissionSet dmSensitiveAllowed =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allowAll()
            .sensitiveAllowed(true)
            .build()
            .withRoles(RoleSet.of("DM"));
    assertThat(
            PermissionChecker.denialReason(dmSensitiveAllowed, "gm_tool", sensitive, Set.of("GM")))
        .hasValueSatisfying(reason -> assertThat(reason).contains("角色不相交"));

    // 角色与敏感都过 ⇒ 放行
    AgentPermissionSet gmSensitiveAllowed =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allowAll()
            .sensitiveAllowed(true)
            .build()
            .withRoles(RoleSet.of("GM"));
    assertThat(
            PermissionChecker.denialReason(gmSensitiveAllowed, "gm_tool", sensitive, Set.of("GM")))
        .isEmpty();
  }

  // ---- 单调性第 8 维：角色（§A A-1，设计 §4.5） ----

  @Test
  void subsetChecksTheRoleDimension() {
    AgentPermissionSet parent = unrestrictedWithRoles("a", "b");
    assertThat(PermissionChecker.isSubset(unrestrictedWithRoles("a"), parent))
        .as("子级拿父级角色的子集 ⇒ 允许")
        .isTrue();
    assertThat(PermissionChecker.isSubset(unrestrictedWithRoles("a", "b"), parent)).isTrue();
    assertThat(PermissionChecker.isSubset(unrestrictedWithRoles("a", "b", "c"), parent))
        .as("子级多拿一个角色 ⇒ 拒（放大）")
        .isFalse();
    assertThat(PermissionChecker.isSubset(unrestrictedWithRoles(), parent))
        .as("子级 ⊥ 是最小身份 ⇒ 允许")
        .isTrue();
    assertThat(PermissionChecker.isSubset(unrestrictedWithRoles(), unrestrictedWithRoles()))
        .isTrue();
  }

  @Test
  void subsetRejectsChildClaimingRolesWhenTheParentIsSilent() {
    // ★ A-1 的核心负向：父 ⊥（未表态 = 谁都不是）+ 子 {a} ⇒ false。
    // 没有这条，“父不表态、子自称管理员”就能靠一维放大通过 isSubset（红线 1）。
    AgentPermissionSet silentParent = unrestrictedWithRoles();
    assertThat(silentParent.roles()).isEqualTo(RoleSet.none());
    assertThat(PermissionChecker.isSubset(unrestrictedWithRoles("a"), silentParent)).isFalse();
    assertThat(PermissionChecker.isSubset(unrestrictedWithRoles("a", "b"), silentParent)).isFalse();
  }

  @Test
  void subsetRejectsRoleWideningEvenWhenEveryOtherDimensionIsNarrower() {
    // 判别性形状：工具维全都更窄/相同，只有角色维更宽——必须被角色那一维拦下（否则“换个维度提权”成立）
    AgentPermissionSet parent =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow("echo", "view")
            .build()
            .withRoles(RoleSet.of("a"));
    AgentPermissionSet child =
        AgentPermissionSet.builder(AccessToken.DEFAULT)
            .allow("echo")
            .build()
            .withRoles(RoleSet.of("a", "b"));
    assertThat(PermissionChecker.isSubset(child, parent)).isFalse();
    // 反向（同样的工具维、角色更窄）⇒ 允许，证明上面的 false 来自角色维而不是别的维
    AgentPermissionSet narrower =
        AgentPermissionSet.builder(AccessToken.DEFAULT).allow("echo").build();
    assertThat(PermissionChecker.isSubset(narrower, parent)).isTrue();
  }

  private static AgentPermissionSet unrestrictedWithRoles(String... roles) {
    AgentPermissionSet set = AgentPermissionSet.unrestricted(AccessToken.DEFAULT);
    return roles.length == 0 ? set : set.withRoles(RoleSet.of(roles));
  }

  private static AgentPermissionSet unrestrictedWithFs(ResourceScope fs) {
    return AgentPermissionSet.unrestricted(AccessToken.DEFAULT)
        .withResourceScopes(ResourceScopeMap.of(ResourceScopeMap.FS, fs));
  }
}
