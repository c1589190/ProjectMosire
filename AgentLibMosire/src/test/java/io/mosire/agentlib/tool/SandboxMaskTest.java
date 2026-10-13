package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SandboxMask} 的判定表（全离线，纯函数）：宿主下发的遮蔽面在“调用者技能可见面”上怎么落成遮蔽/只读。
 *
 * <p>判别性：把“未表态”读成“不限”（静默放大）、把“前缀命中子目录”读成“覆盖整棵树”（遮盖不全）， 或把遮蔽与只读混成一档（读得到却也能写）——三种错法各有一条用例变红。
 */
class SandboxMaskTest {

  @TempDir Path tempDir;

  @Test
  void missingConfigMeansNoMaskAtAll() {
    assertThat(SandboxMask.pathsOf(Map.of())).isEmpty();
    SandboxMask.Fence fence = SandboxMask.fence(AgentPermissionSet.system(), Map.of());
    assertThat(fence.isEmpty()).isTrue();
    assertThat(fence.sandboxRequired()).isFalse();
  }

  @Test
  void pathsAreNormalizedAndBadElementsFailLoudly() {
    Path root = tempDir.resolve("skills").resolve("..").resolve("skills");
    assertThat(SandboxMask.pathsOf(Map.of(SandboxMask.CONFIG_KEY, List.of(root.toString()))))
        .containsExactly(tempDir.resolve("skills").toAbsolutePath().normalize());
    assertThatThrownBy(() -> SandboxMask.pathsOf(Map.of(SandboxMask.CONFIG_KEY, "not-a-list")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SandboxMask.pathsOf(Map.of(SandboxMask.CONFIG_KEY, List.of("  "))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SandboxMask.pathsOf(Map.of(SandboxMask.CONFIG_KEY, List.of(42))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 未表态（本层没写过 {@code skills}）⇒ 整条遮蔽：技能工具缺省是 DENY，未表态 = 一个技能都看不见。 */
  @Test
  void undeclaredSkillsScopeMasksEverything() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    SandboxMask.Fence fence =
        SandboxMask.fence(
            AgentPermissionSet.system(), Map.of(SandboxMask.CONFIG_KEY, List.of(root.toString())));
    assertThat(fence.masked()).containsExactly(root.toAbsolutePath().normalize());
    assertThat(fence.readOnly()).isEmpty();
    assertThat(fence.sandboxRequired()).isTrue();
  }

  @Test
  void explicitNoneMasksAndUnlimitedIsReadOnly() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    Map<String, Object> config = Map.of(SandboxMask.CONFIG_KEY, List.of(root.toString()));

    SandboxMask.Fence none = SandboxMask.fence(withSkills(ResourceScope.none()), config);
    assertThat(none.masked()).hasSize(1);
    assertThat(none.sandboxRequired()).isTrue();

    SandboxMask.Fence all = SandboxMask.fence(withSkills(ResourceScope.unlimited()), config);
    assertThat(all.masked()).isEmpty();
    assertThat(all.readOnly()).hasSize(1);
    assertThat(all.sandboxRequired()).as("全可见 ⇒ 只读暴露即可，不需要遮蔽").isFalse();
  }

  /** 可见前缀只是根的一个子目录 ⇒ 仍然遮蔽整棵树（“只读暴露整棵”会把看不见的那部分一起漏出去）。 */
  @Test
  void partialCoverageStillMasksTheWholeRoot() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    Path sub = Files.createDirectories(root.resolve("alpha"));
    SandboxMask.Fence fence =
        SandboxMask.fence(
            withSkills(ResourceScope.ofDir(sub)),
            Map.of(SandboxMask.CONFIG_KEY, List.of(root.toString())));
    assertThat(fence.masked()).containsExactly(root.toAbsolutePath().normalize());
    assertThat(fence.readOnly()).isEmpty();
  }

  /** 前缀恰好等于根（或比根更宽）⇒ 覆盖整条路径 ⇒ 只读。 */
  @Test
  void coveringPrefixIsReadOnly() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    SandboxMask.Fence exact =
        SandboxMask.fence(
            withSkills(ResourceScope.ofDir(root)),
            Map.of(SandboxMask.CONFIG_KEY, List.of(root.toString())));
    assertThat(exact.readOnly()).hasSize(1);
    assertThat(exact.masked()).isEmpty();

    SandboxMask.Fence wider =
        SandboxMask.fence(
            withSkills(ResourceScope.ofDir(tempDir)),
            Map.of(SandboxMask.CONFIG_KEY, List.of(root.toString())));
    assertThat(wider.readOnly()).hasSize(1);
    assertThat(wider.masked()).isEmpty();
  }

  @Test
  void skillsVisibleRejectsNullOrUndeclared() {
    assertThat(SandboxMask.skillsVisible(null, tempDir)).isFalse();
    assertThat(SandboxMask.skillsVisible(AgentPermissionSet.system(), tempDir)).isFalse();
    assertThat(SandboxMask.skillsVisible(withSkills(ResourceScope.none()), tempDir)).isFalse();
    assertThat(SandboxMask.skillsVisible(withSkills(ResourceScope.ofDir(tempDir)), tempDir))
        .isTrue();
  }

  private static AgentPermissionSet withSkills(ResourceScope scope) {
    return AgentPermissionSet.system().withResourceScopes(ResourceScopeMap.of("skills", scope));
  }
}
