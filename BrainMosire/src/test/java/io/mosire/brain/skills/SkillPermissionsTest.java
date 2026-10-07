package io.mosire.brain.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.PermissionChecker;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SkillPermissions} 的三档语义、编码往返与两条不变式（全离线）：
 *
 * <ul>
 *   <li><b>未表态 = 看不见</b>（不是“不限”）——“缺省静默放大”的反例钉；
 *   <li><b>可编辑面恒 ⊆ 可见面</b>（{@code withScopes} 求交落成数据）；
 *   <li><b>单调性</b>：技能面走的是既有第 7 维，{@link PermissionChecker#isSubset} 逐命名空间比对 ⇒ “子级技能面 ⊆ 父级”不需要第二套实现。
 * </ul>
 */
class SkillPermissionsTest {

  @TempDir Path tempDir;

  @Test
  void undeclaredMeansNothingVisibleNotUnlimited() {
    AgentPermissionSet bare = AgentPermissionSet.system();
    assertThat(SkillPermissions.visible(bare)).isEqualTo(ResourceScope.none());
    assertThat(SkillPermissions.editable(bare)).isEqualTo(ResourceScope.none());
    assertThat(SkillPermissions.isVisible(bare, tempDir.resolve("a").resolve("SKILL.md")))
        .isFalse();
  }

  @Test
  void withScopesMaterializesBothNamespacesAndNarrowsEditIntoVisible() {
    Path visibleRoot = tempDir.resolve("skills");
    // 可编辑面给一个**更宽**的范围（tempDir ⊇ skills）——不变式应当把它夹回可见面之内
    AgentPermissionSet permissions =
        SkillPermissions.withScopes(
            AgentPermissionSet.system(),
            ResourceScope.ofDir(visibleRoot),
            ResourceScope.ofDir(tempDir));

    assertThat(permissions.resourceScopes().declaredScope(ResourceScopeMap.SKILLS))
        .isEqualTo(ResourceScope.ofDir(visibleRoot));
    ResourceScope editable =
        permissions.resourceScopes().declaredScope(ResourceScopeMap.SKILLS_EDIT);
    assertThat(editable.allowsDir(visibleRoot.resolve("a").resolve("SKILL.md"))).isTrue();
    assertThat(editable.allowsDir(tempDir.resolve("elsewhere").resolve("b").resolve("SKILL.md")))
        .as("可编辑面被求交进可见面：更宽的那部分消失")
        .isFalse();
    // 其余命名空间逐字保留
    assertThat(permissions.fsScope()).isEqualTo(AgentPermissionSet.system().fsScope());
  }

  @Test
  void encodeDecodeRoundTripsAllThreeShapes() {
    assertThat(SkillPermissions.decode(SkillPermissions.encode(ResourceScope.unlimited())))
        .isEqualTo(ResourceScope.unlimited());
    assertThat(SkillPermissions.decode(SkillPermissions.encode(ResourceScope.none())))
        .isEqualTo(ResourceScope.none());
    ResourceScope restricted = ResourceScope.ofDir(tempDir.resolve("skills"));
    assertThat(SkillPermissions.decode(SkillPermissions.encode(restricted))).isEqualTo(restricted);
    // 空表读作 none（缺省方向朝收紧）
    assertThat(SkillPermissions.decode(List.of())).isEqualTo(ResourceScope.none());
    assertThat(SkillPermissions.decode(null)).isEqualTo(ResourceScope.none());
  }

  @Test
  void decodeRejectsAmbiguousMixes() {
    assertThatThrownBy(() -> SkillPermissions.decode(List.of("*", "none")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("混用");
    assertThatThrownBy(() -> SkillPermissions.decode(List.of("*", "/tmp/x")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SkillPermissions.decode(List.of(" ")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 单调性走既有第 7 维：子级把技能面放大（父级表态过的命名空间）必被 {@code isSubset} 判否。 */
  @Test
  void widerSkillScopeIsNotASubset() {
    Path allowed = tempDir.resolve("skills").resolve("alpha");

    AgentPermissionSet parent =
        SkillPermissions.withScopes(
            AgentPermissionSet.system(),
            ResourceScope.ofDir(allowed),
            ResourceScope.ofDir(allowed));
    AgentPermissionSet childOk =
        SkillPermissions.withScopes(
            AgentPermissionSet.system(),
            ResourceScope.ofDir(allowed),
            ResourceScope.ofDir(allowed));
    AgentPermissionSet childWide =
        SkillPermissions.withScopes(
            AgentPermissionSet.system(),
            ResourceScope.ofDir(tempDir.resolve("skills")),
            ResourceScope.ofDir(allowed));

    assertThat(PermissionChecker.isSubset(childOk, parent)).isTrue();
    assertThat(PermissionChecker.isSubset(childWide, parent)).as("子级可见面比父级宽 ⇒ 不是子集").isFalse();
    // 父级表态过 skills-edit，子级若“没表态”也判否（未表态按最宽处置，见 ResourceScopeMap#covers）
    assertThat(PermissionChecker.isSubset(AgentPermissionSet.system(), parent)).isFalse();
  }
}
