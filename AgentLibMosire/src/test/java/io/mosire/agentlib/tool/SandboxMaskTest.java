package io.mosire.agentlib.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SandboxMask} 的判定表（全离线，纯函数）：宿主下发的遮蔽面在“条目声明的命名空间 × 调用者可达面”上怎么落成遮蔽/只读。
 *
 * <p><b>条目形状（R1 收编后）</b>：清单元素是 {@code {namespace: String, path:
 * Path}}——命名空间由<b>写清单的装配层</b>声明（本类命名空间无关）， 旧的无命名空间条目（裸字符串 / 裸 {@link
 * Path}）<b>不再兼容且响亮拒</b>：猜一个缺省命名空间等于把刚删掉的硬编码换个地方写回来。
 *
 * <p><b>判别性</b>：把“未表态”读成“不限”（静默放大）、把“前缀命中子目录”读成“覆盖整棵树”（遮盖不全）、把遮蔽与只读混成一档（读得到却也能写）、
 * 或让桶的顺序替“逐条目求与”说话（G7：一条判成不可见就该遮蔽）——四种错法各有用例变红；条目形状的每一格（缺 namespace、未知键、空白、非路径文本、 裸旧形状）各有一条具名拒用例。
 */
class SandboxMaskTest {

  @TempDir Path tempDir;

  @Test
  void missingConfigMeansNoMaskAtAll() {
    assertThat(SandboxMask.entriesOf(Map.of())).isEmpty();
    SandboxMask.Fence fence = SandboxMask.fence(AgentPermissionSet.system(), Map.of());
    assertThat(fence.isEmpty()).isTrue();
    assertThat(fence.sandboxRequired()).isFalse();
  }

  @Test
  void entriesAreNormalizedAndNamespaceIsKept() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    Path messy = root.resolve("sub").resolve("..");
    List<SandboxMask.Entry> entries =
        SandboxMask.entriesOf(
            Map.of(SandboxMask.CONFIG_KEY, List.of(entry("skills", messy.toString()))));

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).namespace()).isEqualTo("skills");
    assertThat(entries.get(0).path()).isEqualTo(root.toAbsolutePath().normalize());
  }

  /** 裸字符串条目（R1 之前的形状）必须响亮拒——不猜缺省命名空间。 */
  @Test
  void legacyBareStringEntryIsRejectedLoudly() {
    assertThatThrownBy(
            () -> SandboxMask.entriesOf(Map.of(SandboxMask.CONFIG_KEY, List.of("/srv/skills"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(SandboxMask.CONFIG_KEY);
  }

  /** 裸 {@link Path} 条目（R1 之前的形状）同样响亮拒。 */
  @Test
  void legacyBarePathEntryIsRejectedLoudly() {
    assertThatThrownBy(
            () ->
                SandboxMask.entriesOf(
                    Map.of(SandboxMask.CONFIG_KEY, List.of(tempDir.resolve("skills")))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingNamespaceKeyIsRejectedLoudly() {
    assertThatThrownBy(
            () ->
                SandboxMask.entriesOf(
                    Map.of(
                        SandboxMask.CONFIG_KEY,
                        List.of(Map.of(SandboxMask.PATH_KEY, tempDir.toString())))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(SandboxMask.NAMESPACE_KEY);
  }

  @Test
  void blankNamespaceIsRejectedLoudly() {
    assertThatThrownBy(
            () ->
                SandboxMask.entriesOf(
                    Map.of(SandboxMask.CONFIG_KEY, List.of(entry("   ", tempDir.toString())))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(SandboxMask.NAMESPACE_KEY);
  }

  @Test
  void nonStringNamespaceIsRejectedLoudly() {
    Map<String, Object> bad = new LinkedHashMap<>();
    bad.put(SandboxMask.NAMESPACE_KEY, 42);
    bad.put(SandboxMask.PATH_KEY, tempDir.toString());
    assertThatThrownBy(() -> SandboxMask.entriesOf(Map.of(SandboxMask.CONFIG_KEY, List.of(bad))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unknownEntryKeyIsRejectedLoudly() {
    Map<String, Object> bad = new LinkedHashMap<>();
    bad.put(SandboxMask.NAMESPACE_KEY, "skills");
    bad.put(SandboxMask.PATH_KEY, tempDir.toString());
    bad.put("kind", "FILE");
    assertThatThrownBy(() -> SandboxMask.entriesOf(Map.of(SandboxMask.CONFIG_KEY, List.of(bad))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kind");
  }

  @Test
  void blankPathIsRejectedLoudly() {
    assertThatThrownBy(
            () ->
                SandboxMask.entriesOf(
                    Map.of(SandboxMask.CONFIG_KEY, List.of(entry("skills", "   ")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(SandboxMask.PATH_KEY);
  }

  /** 非路径文本（这里用 NUL：Linux 上无法成为路径）响亮拒，不当成“某个怪路径”。 */
  @Test
  void nonPathTextIsRejectedLoudly() {
    assertThatThrownBy(
            () ->
                SandboxMask.entriesOf(
                    Map.of(SandboxMask.CONFIG_KEY, List.of(entry("skills", "a\u0000b")))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonListConfigValueIsRejectedLoudly() {
    assertThatThrownBy(() -> SandboxMask.entriesOf(Map.of(SandboxMask.CONFIG_KEY, "not-a-list")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(SandboxMask.CONFIG_KEY);
  }

  @Test
  void duplicateIdenticalEntriesCollapseToOnePath() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    SandboxMask.Fence fence =
        SandboxMask.fence(
            withNamespace("skills", ResourceScope.ofDir(root)),
            Map.of(
                SandboxMask.CONFIG_KEY,
                List.of(entry("skills", root.toString()), entry("skills", root.toString()))));
    assertThat(fence.readOnly()).containsExactly(root.toAbsolutePath().normalize());
    assertThat(fence.masked()).isEmpty();
  }

  /** 未表态（本层没写过该条目声明的命名空间）⇒ 整条遮蔽：声明该资源的工具缺省是 DENY，未表态 = 什么都看不见。 */
  @Test
  void undeclaredNamespaceMasksEverything() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    SandboxMask.Fence fence =
        SandboxMask.fence(
            AgentPermissionSet.system(),
            Map.of(SandboxMask.CONFIG_KEY, List.of(entry("skills", root.toString()))));
    assertThat(fence.masked()).containsExactly(root.toAbsolutePath().normalize());
    assertThat(fence.readOnly()).isEmpty();
    assertThat(fence.sandboxRequired()).isTrue();
  }

  /** 没有权限集（{@code null}）⇒ 按“什么都看不见”处置，fail-closed。 */
  @Test
  void nullPermissionsMasksEverything() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    SandboxMask.Fence fence =
        SandboxMask.fence(
            null, Map.of(SandboxMask.CONFIG_KEY, List.of(entry("skills", root.toString()))));
    assertThat(fence.masked()).containsExactly(root.toAbsolutePath().normalize());
    assertThat(fence.sandboxRequired()).isTrue();
  }

  @Test
  void explicitNoneMasksAndUnlimitedIsReadOnly() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    Map<String, Object> config = Map.of(SandboxMask.CONFIG_KEY, List.of(entry("skills", root)));

    SandboxMask.Fence none =
        SandboxMask.fence(withNamespace("skills", ResourceScope.none()), config);
    assertThat(none.masked()).hasSize(1);
    assertThat(none.sandboxRequired()).isTrue();

    SandboxMask.Fence all =
        SandboxMask.fence(withNamespace("skills", ResourceScope.unlimited()), config);
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
            withNamespace("skills", ResourceScope.ofDir(sub)),
            Map.of(SandboxMask.CONFIG_KEY, List.of(entry("skills", root.toString()))));
    assertThat(fence.masked()).containsExactly(root.toAbsolutePath().normalize());
    assertThat(fence.readOnly()).isEmpty();
  }

  /** 前缀恰好等于根（或比根更宽）⇒ 覆盖整条路径 ⇒ 只读。 */
  @Test
  void coveringPrefixIsReadOnly() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("skills"));
    Map<String, Object> config = Map.of(SandboxMask.CONFIG_KEY, List.of(entry("skills", root)));

    SandboxMask.Fence exact =
        SandboxMask.fence(withNamespace("skills", ResourceScope.ofDir(root)), config);
    assertThat(exact.readOnly()).hasSize(1);
    assertThat(exact.masked()).isEmpty();

    SandboxMask.Fence wider =
        SandboxMask.fence(withNamespace("skills", ResourceScope.ofDir(tempDir)), config);
    assertThat(wider.readOnly()).hasSize(1);
    assertThat(wider.masked()).isEmpty();
  }

  /**
   * <b>G7：同一物理路径被两条不同命名空间的条目声明 ⇒ 逐条目求与</b>：只要有一条判成不可见就<b>遮蔽</b>（遮蔽压过只读）。
   *
   * <p>把求与改成“后写的说了算”或“第一条说了算”，本用例都会红。
   */
  @Test
  void samePathDeclaredByTwoNamespacesIsMaskedWhenOneIsInvisible() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("shared"));
    Map<String, Object> config =
        Map.of(
            SandboxMask.CONFIG_KEY,
            List.of(entry("skills", root.toString()), entry("skills-edit", root.toString())));

    // 只在一个命名空间上可见（另一个未表态）⇒ 求与后仍是遮蔽
    SandboxMask.Fence half =
        SandboxMask.fence(withNamespace("skills", ResourceScope.ofDir(root)), config);
    assertThat(half.masked()).containsExactly(root.toAbsolutePath().normalize());
    assertThat(half.readOnly()).isEmpty();

    // 反过来（另一条不可见）也必须遮蔽——顺序不许替判定说话
    SandboxMask.Fence otherHalf =
        SandboxMask.fence(withNamespace("skills-edit", ResourceScope.ofDir(root)), config);
    assertThat(otherHalf.masked()).containsExactly(root.toAbsolutePath().normalize());
    assertThat(otherHalf.readOnly()).isEmpty();

    // 两条都可见 ⇒ 只读（求与的另一端）
    SandboxMask.Fence both =
        SandboxMask.fence(
            AgentPermissionSet.system()
                .withResourceScopes(
                    ResourceScopeMap.of(
                        Map.of(
                            "skills", ResourceScope.ofDir(root),
                            "skills-edit", ResourceScope.ofDir(root)))),
            config);
    assertThat(both.readOnly()).containsExactly(root.toAbsolutePath().normalize());
    assertThat(both.masked()).isEmpty();
  }

  /** 同一条路径：一条“覆盖整根”、另一条“只覆盖子目录” ⇒ 前者可见、后者不可见 ⇒ 求与 = 遮蔽。 */
  @Test
  void perEntryVerdictsDoNotLeakAcrossNamespaces() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("tree"));
    Path sub = Files.createDirectories(root.resolve("alpha"));
    Map<String, Object> config =
        Map.of(
            SandboxMask.CONFIG_KEY,
            List.of(entry("skills", root.toString()), entry("docs", root.toString())));
    SandboxMask.Fence fence =
        SandboxMask.fence(
            AgentPermissionSet.system()
                .withResourceScopes(
                    ResourceScopeMap.of(
                        Map.of(
                            "skills", ResourceScope.ofDir(root),
                            "docs", ResourceScope.ofDir(sub)))),
            config);
    assertThat(fence.masked()).containsExactly(root.toAbsolutePath().normalize());
    assertThat(fence.readOnly()).isEmpty();
  }

  /** 不同路径各自成像：可见的那条只读暴露、不可见的那条遮蔽——清单不整份二值化。 */
  @Test
  void pathsAreVerdictedIndependently() throws IOException {
    Path visibleRoot = Files.createDirectories(tempDir.resolve("visible"));
    Path hiddenRoot = Files.createDirectories(tempDir.resolve("hidden"));
    Map<String, Object> config =
        Map.of(
            SandboxMask.CONFIG_KEY,
            List.of(entry("skills", visibleRoot.toString()), entry("docs", hiddenRoot.toString())));
    SandboxMask.Fence fence =
        SandboxMask.fence(withNamespace("skills", ResourceScope.ofDir(visibleRoot)), config);
    assertThat(fence.readOnly()).containsExactly(visibleRoot.toAbsolutePath().normalize());
    assertThat(fence.masked()).containsExactly(hiddenRoot.toAbsolutePath().normalize());
    assertThat(fence.sandboxRequired()).isTrue();
  }

  @Test
  void visibleRejectsNullOrUndeclared() {
    assertThat(SandboxMask.visible(null, "skills", tempDir)).isFalse();
    assertThat(SandboxMask.visible(AgentPermissionSet.system(), "skills", tempDir)).isFalse();
    assertThat(
            SandboxMask.visible(withNamespace("skills", ResourceScope.none()), "skills", tempDir))
        .isFalse();
    assertThat(
            SandboxMask.visible(
                withNamespace("skills", ResourceScope.ofDir(tempDir)), "skills", tempDir))
        .isTrue();
    assertThat(
            SandboxMask.visible(
                withNamespace("skills", ResourceScope.ofDir(tempDir)),
                "skills",
                tempDir.resolve("x")))
        .isTrue();
  }

  /** 条目显式声明的命名空间是判定键：声明 A 却只有 B 可见 ⇒ 不可见（不回落成“任一命名空间可见即可见”）。 */
  @Test
  void verdictUsesTheDeclaredNamespaceOnly() {
    assertThat(
            SandboxMask.visible(
                withNamespace("docs", ResourceScope.ofDir(tempDir)), "skills", tempDir))
        .isFalse();
  }

  private static AgentPermissionSet withNamespace(String namespace, ResourceScope scope) {
    return AgentPermissionSet.system().withResourceScopes(ResourceScopeMap.of(namespace, scope));
  }

  /** 一条新形状的遮蔽面条目（{@code {namespace, path}}）。 */
  private static Map<String, Object> entry(String namespace, Object path) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put(SandboxMask.NAMESPACE_KEY, namespace);
    entry.put(SandboxMask.PATH_KEY, path);
    return entry;
  }
}
